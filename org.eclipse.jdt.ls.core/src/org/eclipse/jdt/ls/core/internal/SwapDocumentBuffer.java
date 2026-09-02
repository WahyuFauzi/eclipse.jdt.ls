/*******************************************************************************
 * Copyright (c) 2024 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Red Hat Inc. - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.BufferChangedEvent;
import org.eclipse.jdt.core.IBuffer;
import org.eclipse.jdt.core.IBufferChangedListener;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IOpenable;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;

/**
 * An {@link IBuffer} implementation that wraps a {@link DocumentAdapter} and supports
 * transparent compression/decompression of content to reduce memory usage for clean
 * (saved, unmodified) documents.
 * <p>
 * <b>Hot mode</b> (dirty/editing): delegates to the underlying {@link DocumentAdapter}
 * with a live {@link IDocument}. All reads and writes operate normally.
 * </p>
 * <p>
 * <b>Cold mode</b> (clean/saved): the full text is stored as a zlib-compressed byte
 * array. The underlying {@link IDocument} content is cleared to free memory. On read
 * access, the content is decompressed on demand (~10-50μs for a typical file). On write
 * access (first edit after save), the buffer is automatically decompressed back to
 * hot mode.
 * </p>
 * <p>
 * Compression uses Java's built-in {@link Deflater} (zlib, level 9) — no external
 * dependencies required. Java source text typically compresses 3-5x.
 * </p>
 */
public class SwapDocumentBuffer implements IBuffer, IDocumentListener {

	private static final int COMPRESSION_LEVEL = 9;

	private final ICompilationUnit fOwner;
	private final IFile fFile;

	/** The delegate DocumentAdapter used in hot (dirty) mode. */
	private DocumentAdapter fDelegate;
	/** Zlib-compressed content bytes. Non-null only in cold mode. */
	private byte[] fCompressedContent;
	/** Decompressed content cache. Non-null only in cold mode while cached. */
	private String fDecompressedCache;
	/** The IDocument from the delegate, kept across mode switches. */
	private IDocument fDocument;

	private boolean fIsClosed;
	private boolean fDirty;
	private List<IBufferChangedListener> fBufferListeners;

	/**
	 * Creates a new SwapDocumentBuffer in hot mode.
	 *
	 * @param owner the owning compilation unit
	 * @param file  the underlying file
	 */
	public SwapDocumentBuffer(ICompilationUnit owner, IFile file) {
		this.fOwner = owner;
		this.fFile = file;
		this.fBufferListeners = new ArrayList<>(3);
		this.fDelegate = new DocumentAdapter(owner, file);
		this.fDocument = fDelegate.getDocument();
		if (fDocument != null) {
			fDocument.addDocumentListener(this);
		}
		this.fDirty = true; // start hot — user may edit immediately
	}

	// ---- Mode switching ----

	/**
	 * Switches the buffer to cold (compressed) mode. Should be called after
	 * {@code didSave} when the file is clean. The underlying IDocument content
	 * is cleared to free memory.
	 */
	public synchronized void compress() {
		if (fIsClosed || fCompressedContent != null || fDelegate == null) {
			return;
		}
		String content = fDocument != null ? fDocument.get() : null;
		if (content == null || content.isEmpty()) {
			return;
		}
		try {
			byte[] input = content.getBytes(StandardCharsets.UTF_8);
			Deflater deflater = new Deflater(COMPRESSION_LEVEL);
			deflater.setInput(input);
			deflater.finish();
			ByteArrayOutputStream bos = new ByteArrayOutputStream(input.length / 2);
			byte[] buf = new byte[8192];
			while (!deflater.finished()) {
				int len = deflater.deflate(buf);
				if (len > 0) {
					bos.write(buf, 0, len);
				}
			}
			deflater.end();
			fCompressedContent = bos.toByteArray();
			fDecompressedCache = null;
			fDirty = false;

			// Clear the IDocument content to free memory
			if (fDocument != null) {
				fDocument.set("");
			}
		} catch (Exception e) {
			JavaLanguageServerPlugin.logException("Failed to compress document buffer", e);
		}
	}

	/**
	 * Switches the buffer to hot (decompressed) mode. Should be called before
	 * {@code didChange} to prepare for edits. The content is decompressed from
	 * the compressed bytes and restored into the IDocument.
	 */
	public synchronized void decompress() {
		if (fIsClosed || fCompressedContent == null) {
			return;
		}
		try {
			Inflater inflater = new Inflater();
			inflater.setInput(fCompressedContent);
			ByteArrayOutputStream bos = new ByteArrayOutputStream(fCompressedContent.length * 3);
			byte[] buf = new byte[8192];
			while (!inflater.finished()) {
				int len = inflater.inflate(buf);
				if (len > 0) {
					bos.write(buf, 0, len);
				}
			}
			inflater.end();
			String content = bos.toString(StandardCharsets.UTF_8);
			fCompressedContent = null;
			fDecompressedCache = content;
			fDirty = true;

			// Restore the IDocument content
			if (fDocument != null) {
				fDocument.set(content);
			}
		} catch (DataFormatException e) {
			JavaLanguageServerPlugin.logException("Failed to decompress document buffer", e);
		}
	}

	/**
	 * @return {@code true} if the buffer is in cold (compressed) mode
	 */
	public boolean isCompressed() {
		return fCompressedContent != null;
	}

	/**
	 * @return {@code true} if the buffer has been modified since last compress
	 */
	public boolean isDirty() {
		return fDirty;
	}

	// ---- Content access ----

	/**
	 * Returns the underlying {@link IDocument} if available. If the buffer is
	 * compressed, decompresses it first so that callers see the actual content.
	 */
	public IDocument getDocument() {
		if (fCompressedContent != null) {
			decompress();
		}
		return fDocument;
	}

	// ---- IBuffer implementation ----

	@Override
	public synchronized void addBufferChangedListener(IBufferChangedListener listener) {
		if (!fBufferListeners.contains(listener)) {
			fBufferListeners.add(listener);
		}
	}

	@Override
	public synchronized void removeBufferChangedListener(IBufferChangedListener listener) {
		fBufferListeners.remove(listener);
	}

	@Override
	public void append(char[] text) {
		append(new String(text));
	}

	@Override
	public void append(String text) {
		try {
			ensureHot();
			if (fDocument != null) {
				fDocument.replace(fDocument.getLength(), 0, text);
			}
		} catch (BadLocationException e) {
			throw new IndexOutOfBoundsException(e.getMessage());
		}
	}

	@Override
	public synchronized void close() {
		if (fIsClosed) {
			return;
		}
		fIsClosed = true;
		if (fDocument != null) {
			fDocument.removeDocumentListener(this);
		}
		if (fDelegate != null) {
			fDelegate.close();
			fDelegate = null;
		}
		fCompressedContent = null;
		fDecompressedCache = null;
		fDocument = null;
		fireBufferChanged(new BufferChangedEvent(this, 0, 0, null));
		fBufferListeners.clear();
	}

	@Override
	public char getChar(int position) {
		String content = getContent();
		if (position < 0 || position >= content.length()) {
			throw new IndexOutOfBoundsException("Position " + position + " out of bounds");
		}
		return content.charAt(position);
	}

	@Override
	public char[] getCharacters() {
		String content = getContent();
		return content != null ? content.toCharArray() : null;
	}

	@Override
	public String getContents() {
		return getContent();
	}

	@Override
	public int getLength() {
		if (fCompressedContent != null && fDecompressedCache != null) {
			return fDecompressedCache.length();
		}
		if (fCompressedContent != null) {
			return getContent().length();
		}
		if (fDocument != null) {
			return fDocument.getLength();
		}
		return 0;
	}

	@Override
	public IOpenable getOwner() {
		return fOwner;
	}

	@Override
	public String getText(int offset, int length) {
		String content = getContent();
		try {
			return content.substring(offset, offset + length);
		} catch (StringIndexOutOfBoundsException e) {
			throw new IndexOutOfBoundsException(e.getMessage());
		}
	}

	@Override
	public IResource getUnderlyingResource() {
		return fFile;
	}

	@Override
	public boolean hasUnsavedChanges() {
		return fDirty || (fDelegate != null && fDelegate.hasUnsavedChanges());
	}

	@Override
	public boolean isClosed() {
		return fIsClosed;
	}

	@Override
	public boolean isReadOnly() {
		if (fDelegate != null) {
			return fDelegate.isReadOnly();
		}
		return fFile != null && !fFile.getLocation().toFile().canWrite();
	}

	@Override
	public void replace(int position, int length, char[] text) {
		replace(position, length, new String(text));
	}

	@Override
	public void replace(int position, int length, String text) {
		try {
			ensureHot();
			if (fDocument != null) {
				fDocument.replace(position, length, text);
			}
		} catch (BadLocationException e) {
			throw new IndexOutOfBoundsException(e.getMessage());
		}
	}

	@Override
	public void save(IProgressMonitor progress, boolean force) throws JavaModelException {
		if (fDelegate != null) {
			fDelegate.save(progress, force);
		}
	}

	@Override
	public void setContents(char[] contents) {
		setContents(new String(contents));
	}

	@Override
	public synchronized void setContents(String contents) {
		ensureHot();
		if (fDocument != null && !contents.equals(fDocument.get())) {
			fDocument.set(contents);
		}
		fDirty = true;
	}

	// ---- Internal ----

	/**
	 * Returns the content, decompressing if necessary.
	 */
	private String getContent() {
		if (fCompressedContent != null) {
			if (fDecompressedCache != null) {
				return fDecompressedCache;
			}
			decompress();
		}
		if (fDocument != null) {
			return fDocument.get();
		}
		return "";
	}

	/**
	 * Ensures the buffer is in hot (decompressed) mode.
	 */
	private void ensureHot() {
		if (fCompressedContent != null) {
			decompress();
		}
	}

	private void fireBufferChanged(BufferChangedEvent event) {
		IBufferChangedListener[] listeners;
		synchronized (this) {
			listeners = fBufferListeners.toArray(new IBufferChangedListener[0]);
		}
		for (IBufferChangedListener listener : listeners) {
			listener.bufferChanged(event);
		}
	}

	// ---- IDocumentListener (forwarded from underlying document) ----

	@Override
	public void documentAboutToBeChanged(DocumentEvent event) {
		// no-op
	}

	@Override
	public void documentChanged(DocumentEvent event) {
		fDirty = true;
		fireBufferChanged(new BufferChangedEvent(this, event.getOffset(), event.getLength(), event.getText()));
	}
}
