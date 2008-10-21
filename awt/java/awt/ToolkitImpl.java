/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package java.awt;

import java.awt.im.InputMethodHighlight;
import java.awt.image.ColorModel;
import java.awt.image.ImageObserver;
import java.awt.image.ImageProducer;
import java.awt.peer.*;
import java.io.Serializable;
import java.net.URL;
import java.util.Hashtable;
import java.util.Map;
import org.apache.harmony.awt.gl.image.*;
import org.apache.harmony.awt.wtk.GraphicsFactory;

class ToolkitImpl extends Toolkit {
	
    static final Hashtable<Serializable, Image> imageCache = new Hashtable<Serializable, Image>();

    @Override
    public void sync() {
        lockAWT();
        try {
        } finally {
            unlockAWT();
        }
    }

    @Override
    public int checkImage(Image image, int width, int height, ImageObserver observer) {
        lockAWT();
        try {
            if (width == 0 || height == 0) {
                return ImageObserver.ALLBITS;
            }
            if (!(image instanceof OffscreenImage)) {
                return ImageObserver.ALLBITS;
            }
            OffscreenImage oi = (OffscreenImage) image;
            return oi.checkImage(observer);
        } finally {
            unlockAWT();
        }
    }

    @Override
    public Image createImage(ImageProducer producer) {
        lockAWT();
        try {
            return new OffscreenImage(producer);
        } finally {
            unlockAWT();
        }
    }

    @Override
    public Image createImage(byte[] imagedata, int imageoffset, int imagelength) {
        lockAWT();
        try {
            return new OffscreenImage(new ByteArrayDecodingImageSource(imagedata, imageoffset,
                    imagelength));
        } finally {
            unlockAWT();
        }
    }

    @Override
    public Image createImage(URL url) {
        lockAWT();
        try {
            return new OffscreenImage(new URLDecodingImageSource(url));
        } finally {
            unlockAWT();
        }
    }

    @Override
    public Image createImage(String filename) {
        lockAWT();
        try {
            return new OffscreenImage(new FileDecodingImageSource(filename));
        } finally {
            unlockAWT();
        }
    }

    @Override
    public ColorModel getColorModel() {
        lockAWT();
        try {
            return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                    .getDefaultConfiguration().getColorModel();
        } finally {
            unlockAWT();
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    @Deprecated
    public FontMetrics getFontMetrics(Font font) {
        lockAWT();
        try {
        	GraphicsFactory gf = getGraphicsFactory();
            return gf.getFontMetrics(font);
        } finally {
            unlockAWT();
        }
    }
    
    @Override
    public boolean prepareImage(Image image, int width, int height, ImageObserver observer) {
        lockAWT();
        try {
            if (width == 0 || height == 0) {
                return true;
            }
            if (!(image instanceof OffscreenImage)) {
                return true;
            }
            OffscreenImage oi = (OffscreenImage) image;
            return oi.prepareImage(observer);
        } finally {
            unlockAWT();
        }
    }

    @Override
    public void beep() {
        lockAWT();
        try {
        	// ???AWT: is there nothing to be implemented here?
        } finally {
            unlockAWT();
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    @Deprecated
    public String[] getFontList() {
        lockAWT();
        try {
        } finally {
            unlockAWT();
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    @Override
    @Deprecated
    protected FontPeer getFontPeer(String a0, int a1) {
        lockAWT();
        try {
            return null;
        } finally {
            unlockAWT();
        }
    }

    @Override
    public Image getImage(String filename) {
        return getImage(filename, this);
    }

    static Image getImage(String filename, Toolkit toolkit) {
        synchronized (imageCache) {
            Image im = (filename == null ? null : imageCache.get(filename));

            if (im == null) {
                try {
                    im = toolkit.createImage(filename);
                    imageCache.put(filename, im);
                } catch (Exception e) {
                }
            }

            return im;
        }
    }

    @Override
    public Image getImage(URL url) {
        return getImage(url, this);
    }

    static Image getImage(URL url, Toolkit toolkit) {
        synchronized (imageCache) {
            Image im = imageCache.get(url);
            if (im == null) {
                try {
                    im = toolkit.createImage(url);
                    imageCache.put(url, im);
                } catch (Exception e) {
                }
            }
            return im;
        }
    }

    @Override
    public int getScreenResolution() throws HeadlessException {
        lockAWT();
        try {
        	return 62;
        } finally {
            unlockAWT();
        }
    }

    @Override
    public Dimension getScreenSize() {
        lockAWT();
        try {
            DisplayMode dm = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDisplayMode();
            return new Dimension(dm.getWidth(), dm.getHeight());
        } finally {
            unlockAWT();
        }
    }

    @Override
    public Map<java.awt.font.TextAttribute, ?> mapInputMethodHighlight(
            InputMethodHighlight highlight) throws HeadlessException {
        lockAWT();
        try {
            return mapInputMethodHighlightImpl(highlight);
        } finally {
            unlockAWT();
        }
    }

    @Override
    protected EventQueue getSystemEventQueueImpl() {
        return getSystemEventQueueCore().getActiveEventQueue();
    }
}
