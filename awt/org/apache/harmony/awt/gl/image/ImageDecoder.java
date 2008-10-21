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
/**
 * @author Oleg V. Khaschansky
 * @version $Revision$
 */
/*
 * Created on 18.01.2005
 */
package org.apache.harmony.awt.gl.image;

import com.android.internal.awt.AndroidImageDecoder;

import java.awt.image.ColorModel;
import java.awt.image.ImageConsumer;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ConcurrentModificationException;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;


/**
 * This class contains common functionality for all image decoders.
 */
public abstract class ImageDecoder {
    
    /** Image types */
    public static final int GENERIC_DECODER = 0;
    public static final int JPG_DECODER = 1;
    public static final int GIF_DECODER = 2;
    public static final int PNG_DECODER = 3;
    
    private static final int MAX_BYTES_IN_SIGNATURE = 8;

    protected List<ImageConsumer> consumers;
    protected InputStream inputStream;
    protected DecodingImageSource src;

    protected boolean terminated;

    /**
     * Chooses appropriate image decoder by looking into input stream and checking
     * the image signature.
     * @param src - image producer, required for passing data to it from the
     * created decoder via callbacks
     * @param is - stream
     * @return decoder
     */
    static ImageDecoder createDecoder(DecodingImageSource src, InputStream is) {
        InputStream markable;

        if (!is.markSupported()) {
            // BEGIN android-modified
            markable = new BufferedInputStream(is, 8192);
            // END android-modified
        } else {
            markable = is;
        }
            
        // Read the signature from the stream and then reset it back
        try {
            markable.mark(MAX_BYTES_IN_SIGNATURE);

            byte[] signature = new byte[MAX_BYTES_IN_SIGNATURE];
            markable.read(signature, 0, MAX_BYTES_IN_SIGNATURE);
            markable.reset();

            if ((signature[0] & 0xFF) == 0xFF &&
                    (signature[1] & 0xFF) == 0xD8 &&
                    (signature[2] & 0xFF) == 0xFF) { // JPEG
                return loadDecoder(PNG_DECODER, src, is);
            } else if ((signature[0] & 0xFF) == 0x47 && // G
                    (signature[1] & 0xFF) == 0x49 && // I
                    (signature[2] & 0xFF) == 0x46) { // F
                return loadDecoder(GIF_DECODER, src, is);
            } else if ((signature[0] & 0xFF) == 137 && // PNG signature: 137 80 78 71 13 10 26 10
                    (signature[1] & 0xFF) == 80 &&
                    (signature[2] & 0xFF) == 78 &&
                    (signature[3] & 0xFF) == 71 &&
                    (signature[4] & 0xFF) == 13 &&
                    (signature[5] & 0xFF) == 10 &&
                    (signature[6] & 0xFF) == 26 &&
                    (signature[7] & 0xFF) == 10) {
                return loadDecoder(JPG_DECODER, src, is);
            }

            return loadDecoder(GENERIC_DECODER, src, is);
            
        } catch (IOException e) { // Silently
        }

        return null;
    }
    
    /*
     * In the future, we might return different decoders for differen image types.
     * But for now, we always return the generic one.
     * Also: we could add a factory to load image decoder.
     */
    private static ImageDecoder loadDecoder(int type, DecodingImageSource src, 
            InputStream is) {
        return new AndroidImageDecoder(src, is);
    }

    protected ImageDecoder(DecodingImageSource _src, InputStream is) {
        src = _src;
        consumers = src.consumers;
        inputStream = is;
    }

    public abstract void decodeImage() throws IOException;

    public synchronized void closeStream() {
        if (inputStream != null) {
            try {
                inputStream.close();
            } catch (IOException e) {
            }
        }
    }

    /**
     * Stops the decoding by interrupting the current decoding thread.
     * Used when all consumers are removed and there's no more need to
     * run the decoder.
     */
    public void terminate() {
        src.lockDecoder(this);
        closeStream();

        AccessController.doPrivileged(
                new PrivilegedAction<Void>() {
                    public Void run() {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
        );

        terminated = true;
    }

    protected void setDimensions(int w, int h) {
        if (terminated) {
            return;
        }

        for (ImageConsumer ic : consumers) {
            ic.setDimensions(w, h);
        }
    }

    protected void setProperties(Hashtable<?, ?> props) {
        if (terminated) {
            return;
        }

        for (ImageConsumer ic : consumers) {
            ic.setProperties(props);
        }
    }

    protected void setColorModel(ColorModel cm) {
        if (terminated) {
            return;
        }

        for (ImageConsumer ic : consumers) {
            ic.setColorModel(cm);
        }
    }

    protected void setHints(int hints) {
        if (terminated) {
            return;
        }

        for (ImageConsumer ic : consumers) {
            ic.setHints(hints);
        }
    }

    protected void setPixels(
            int x, int y,
            int w, int h,
            ColorModel model,
            byte pix[],
            int off, int scansize
            ) {
        if (terminated) {
            return;
        }

        src.lockDecoder(this);

        for (ImageConsumer ic : consumers) {
            ic.setPixels(x, y, w, h, model, pix, off, scansize);
        }
    }

    protected void setPixels(
            int x, int y,
            int w, int h,
            ColorModel model,
            int pix[],
            int off, int scansize
            ) {
        if (terminated) {
            return;
        }

        src.lockDecoder(this);

        for (ImageConsumer ic : consumers) {
            ic.setPixels(x, y, w, h, model, pix, off, scansize);
        }
    }

    protected void imageComplete(int status) {
        if (terminated) {
            return;
        }

        src.lockDecoder(this);

        ImageConsumer ic = null;

        for (Iterator<ImageConsumer> i = consumers.iterator(); i.hasNext();) {
            try {
                ic = i.next();
            } catch (ConcurrentModificationException e) {
                i = consumers.iterator();
                continue;
            }
            ic.imageComplete(status);
        }
    }

}
