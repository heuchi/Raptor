/**
 * New BSD License
 * http://www.opensource.org/licenses/bsd-license.php
 * Copyright 2009-2016 RaptorProject (https://github.com/Raptor-Fics-Interface/Raptor)
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
 * Neither the name of the RaptorProject nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

/*******************************************************************************
 * Copyright (c) 2000, 2006 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * IBM Corporation - initial API and implementation
 * Steven Ketcham (sketcham@dsicdi.com) - Bug 42451
 * [Dialogs] ImageRegistry throws null pointer exception in
 * application with multiple Display's
 *******************************************************************************/
package raptor.swt;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.runtime.Assert;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.resource.DeviceResourceException;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.resource.ResourceManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Device;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.widgets.Display;

import raptor.util.RaptorRunnable;

/**
 * An image registry maintains a mapping between symbolic image names and SWT
 * image objects or special image descriptor objects which defer the creation of
 * SWT image objects until they are needed.
 * <p>
 * An image registry owns all of the image objects registered with it, and
 * automatically disposes of them when the SWT Display that creates the images
 * is disposed. Because of this, clients do not need to (indeed, must not
 * attempt to) dispose of these images themselves.
 * </p>
 * <p>
 * Clients may instantiate this class (it was not designed to be subclassed).
 * </p>
 * <p>
 * Unlike the FontRegistry, it is an error to replace images. As a result there
 * are no events that fire when values are changed in the registry
 * </p>
 * 
 * 
 * Copied from JFace to provide information of number of images in use.
 */
public class RaptorImageRegistry {
	/**
	 * Contains the data for an entry in the registry.
	 */
	private static class Entry {
		/** the descriptor */
		protected ImageDescriptor descriptor;

		/** the image */
		protected Image image;
	}

	private static class OriginalImageDescriptor extends ImageDescriptor {
		private Image original;
		private Device originalDisplay;
		private int refCount = 0;

		/**
		 * @param original
		 *            the original image
		 * @param originalDisplay
		 *            the device the image is part of
		 */
		public OriginalImageDescriptor(Image original, Device originalDisplay) {
			this.original = original;
			this.originalDisplay = originalDisplay;
		}

		@Override
		public Object createResource(Device device)
				throws DeviceResourceException {
			if (device == originalDisplay) {
				refCount++;
				return original;
			}
			return super.createResource(device);
		}

		@Override
		public void destroyResource(Object toDispose) {
			if (original == toDispose) {
				refCount--;
				if (refCount == 0) {
					original.dispose();
					original = null;
				}
			} else {
				super.destroyResource(toDispose);
			}
		}

		/*
		 * (non-)
		 * 
		 * @see org.eclipse.jface.resource.ImageDescriptor#getImageData()
		 */
		@Override
		public ImageData getImageData() {
			return original.getImageData();
		}
	}

	/**
	 * display used when getting images
	 */
	private Display display;

	private Runnable disposeRunnable = new Runnable() {
		public void run() {
			dispose();
		}
	};

	private ResourceManager manager;

	private Map<String, Entry> table;

	/**
	 * Creates an empty image registry.
	 * <p>
	 * There must be an SWT Display created in the current thread before calling
	 * this method.
	 * </p>
	 */
	public RaptorImageRegistry() {
		this(Display.getCurrent());
	}

	/**
	 * Creates an empty image registry.
	 * 
	 * @param display
	 *            this <code>Display</code> must not be <code>null</code> and
	 *            must not be disposed in order to use this registry
	 */
	public RaptorImageRegistry(Display display) {
		this(JFaceResources.getResources(display));
	}

	/**
	 * Creates an empty image registry using the given resource manager to
	 * allocate images.
	 * 
	 * @param manager
	 *            the resource manager used to allocate images
	 * 
	 * @since 3.1
	 */
	public RaptorImageRegistry(ResourceManager manager) {
		Assert.isNotNull(manager);
		Device dev = manager.getDevice();
		if (dev instanceof Display) {
			display = (Display) dev;
		}
		this.manager = manager;
		manager.disposeExec(disposeRunnable);
	}

	/**
	 * Disposes this image registry, disposing any images that were allocated
	 * for it, and clearing its entries.
	 * 
	 * @since 3.1
	 */
	public void dispose() {
		manager.cancelDisposeExec(disposeRunnable);

		if (table != null) {
			for (Entry entry : table.values()) {
				if (entry.image != null) {
					manager.destroyImage(entry.descriptor);
				}
			}
			table = null;
		}
		display = null;
	}

	/**
	 * Returns the image associated with the given key in this registry, or
	 * <code>null</code> if none.
	 * 
	 * @param key
	 *            the key
	 * @return the image, or <code>null</code> if none
	 */
	@SuppressWarnings( { "deprecation" })
	public Image get(String key) {

		// can be null
		if (key == null) {
			return null;
		}

		if (display != null) {
			/**
			 * NOTE, for backwards compatibility the following images are
			 * supported here, they should never be disposed, hence we
			 * explicitly return them rather then registering images that SWT
			 * will dispose.
			 * 
			 * Applications should go direclty to SWT for these icons.
			 * 
			 * @see Display.getSystemIcon(int ID)
			 */
			int swtKey = -1;
			if (key.equals(Dialog.DLG_IMG_INFO)) {
				swtKey = SWT.ICON_INFORMATION;
			}
			if (key.equals(Dialog.DLG_IMG_QUESTION)) {
				swtKey = SWT.ICON_QUESTION;
			}
			if (key.equals(Dialog.DLG_IMG_WARNING)) {
				swtKey = SWT.ICON_WARNING;
			}
			if (key.equals(Dialog.DLG_IMG_ERROR)) {
				swtKey = SWT.ICON_ERROR;
			}
			// if we actually just want to return an SWT image do so without
			// looking in the registry
			if (swtKey != -1) {
				final Image[] image = new Image[1];
				final int id = swtKey;
				display.syncExec(new RaptorRunnable() {
					@Override
					public void execute() {
						image[0] = display.getSystemImage(id);
					}
				});
				return image[0];
			}
		}

		Entry entry = getEntry(key);
		if (entry == null) {
			return null;
		}

		if (entry.image == null) {
			entry.image = manager.createImageWithDefault(entry.descriptor);
		}

		return entry.image;
	}

	/**
	 * Returns the descriptor associated with the given key in this registry, or
	 * <code>null</code> if none.
	 * 
	 * @param key
	 *            the key
	 * @return the descriptor, or <code>null</code> if none
	 * @since 2.1
	 */
	public ImageDescriptor getDescriptor(String key) {
		Entry entry = getEntry(key);
		if (entry == null) {
			return null;
		}

		return entry.descriptor;
	}

	public int getSize() {
		return table.size();
	}

	/**
	 * Adds an image to this registry. This method fails if there is already an
	 * image or descriptor for the given key.
	 * <p>
	 * Note that an image registry owns all of the image objects registered with
	 * it, and automatically disposes of them when the SWT Display is disposed.
	 * Because of this, clients must not register an image object that is
	 * managed by another object.
	 * </p>
	 * 
	 * @param key
	 *            the key
	 * @param image
	 *            the image, should not be <code>null</code>
	 * @exception IllegalArgumentException
	 *                if the key already exists
	 */
	public void put(String key, Image image) {
		Entry entry = getEntry(key);

		if (entry == null) {
			entry = new Entry();
			putEntry(key, entry);
		}

		if (entry.image != null || entry.descriptor != null) {
			throw new IllegalArgumentException(
					"ImageRegistry key already in use: " + key); //$NON-NLS-1$
		}

		// Should be checking for a null image here.
		// Current behavior is that a null image won't be caught until dispose.
		// See https://bugs.eclipse.org/bugs/show_bug.cgi?id=130315
		entry.image = image;
		entry.descriptor = new OriginalImageDescriptor(image, manager
				.getDevice());

		try {
			manager.create(entry.descriptor);
		} catch (DeviceResourceException e) {
		}
	}

	/**
	 * Adds (or replaces) an image descriptor to this registry. The first time
	 * this new entry is retrieved, the image descriptor's image will be
	 * computed (via </code>ImageDescriptor.createImage</code>) and remembered.
	 * This method replaces an existing image descriptor associated with the
	 * given key, but fails if there is a real image associated with it.
	 * 
	 * @param key
	 *            the key
	 * @param descriptor
	 *            the ImageDescriptor
	 * @exception IllegalArgumentException
	 *                if the key already exists
	 */
	public void put(String key, ImageDescriptor descriptor) {
		Entry entry = getEntry(key);
		if (entry == null) {
			entry = new Entry();
			getTable().put(key, entry);
		}

		if (entry.image != null) {
			throw new IllegalArgumentException(
					"ImageRegistry key already in use: " + key); //$NON-NLS-1$
		}

		entry.descriptor = descriptor;
	}

	/**
	 * Removes an image from this registry. If an SWT image was allocated, it is
	 * disposed. This method has no effect if there is no image or descriptor
	 * for the given key.
	 * 
	 * @param key
	 *            the key
	 */
	public void remove(String key) {
		ImageDescriptor descriptor = getDescriptor(key);
		if (descriptor != null) {
			manager.destroy(descriptor);
			getTable().remove(key);
		}
	}

	private Entry getEntry(String key) {
		return getTable().get(key);
	}

	private Map<String, Entry> getTable() {
		if (table == null) {
			table = new HashMap<String, Entry>(10);
		}
		return table;
	}

	private void putEntry(String key, Entry entry) {
		getTable().put(key, entry);
	}
}
