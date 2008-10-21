/*
 * Copyright 2007, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 *
 *     http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License.
 */
package com.android.internal.awt;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.awt.Transparency;
import java.awt.color.ColorSpace;
//import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DirectColorModel;
import java.awt.image.ImageConsumer;
import java.awt.image.IndexColorModel;
import java.io.IOException;
import java.io.InputStream;
import java.util.Hashtable;

import org.apache.harmony.awt.gl.image.DecodingImageSource;
import org.apache.harmony.awt.gl.image.ImageDecoder;
import org.apache.harmony.awt.internal.nls.Messages;

public class AndroidImageDecoder extends ImageDecoder {
    
    private static final int hintflags =
        ImageConsumer.SINGLEFRAME | // PNG is a static image
        ImageConsumer.TOPDOWNLEFTRIGHT | // This order is only one possible
        ImageConsumer.COMPLETESCANLINES; // Don't deliver incomplete scanlines
    
    // Each pixel is a grayscale sample.
    private static final int PNG_COLOR_TYPE_GRAY = 0;
    // Each pixel is an R,G,B triple.
    private static final int PNG_COLOR_TYPE_RGB = 2;
    // Each pixel is a palette index, a PLTE chunk must appear.
    private static final int PNG_COLOR_TYPE_PLTE = 3;
    // Each pixel is a grayscale sample, followed by an alpha sample.
    private static final int PNG_COLOR_TYPE_GRAY_ALPHA = 4;
    // Each pixel is an R,G,B triple, followed by an alpha sample.
    private static final int PNG_COLOR_TYPE_RGBA = 6;
    
    private static final int NB_OF_LINES_PER_CHUNK = 1;  // 0 = full image
    
    Bitmap bm;  // The image as decoded by Android
    
    // Header information
    int imageWidth; // Image size
    int imageHeight;  
    int colorType;  // One of the PNG_ constants from above
    int bitDepth;   // Number of bits per color
    byte cmap[];    // The color palette for index color models
    ColorModel model;  // The corresponding AWT color model
    
    boolean transferInts; // Is transfer of type int or byte?
    int dataElementsPerPixel;

    // Buffers for decoded image data
    byte byteOut[];
    int intOut[];

    
    public AndroidImageDecoder(DecodingImageSource src, InputStream is) {
        super(src, is);
        dataElementsPerPixel = 1;
    }

    @Override
    /**
     * All the decoding is done in Android
     * 
     * AWT???: Method returns only once the image is completly 
     * decoded; decoding is not done asynchronously
     */
    public void decodeImage() throws IOException {
        try {
            bm = BitmapFactory.decodeStream(inputStream);
            if (bm == null) {
                throw new IOException("Input stream empty and no image cached");
            }

            // Check size
            imageWidth = bm.getWidth();
            imageHeight = bm.getHeight();
            if (imageWidth < 0 || imageHeight < 0 ) {
                throw new RuntimeException("Illegal image size: " 
                        + imageWidth + ", " + imageHeight);
            }
            
            // We got the image fully decoded; now send all image data to AWT
            setDimensions(imageWidth, imageHeight);
            model = createColorModel();
            setColorModel(model);
            setHints(hintflags);
            setProperties(new Hashtable<Object, Object>()); // Empty
            sendPixels(NB_OF_LINES_PER_CHUNK != 0 ? NB_OF_LINES_PER_CHUNK : imageHeight);
            imageComplete(ImageConsumer.STATICIMAGEDONE);        
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            imageComplete(ImageConsumer.IMAGEERROR);
            throw e;
        } finally {
            closeStream();
        }
    }
    
    /**
     * Create the AWT color model
     *
     * ???AWT: Android Bitmaps are always of type: ARGB-8888-Direct color model
     * 
     * However, we leave the code here for a more powerfull decoder 
     * that returns a native model, and the conversion is then handled
     * in AWT. With such a decoder, we would need to get the colorType, 
     * the bitDepth, (and the color palette for an index color model)
     * from the image and construct the correct color model here.
     */
    private ColorModel createColorModel() {
        ColorModel cm = null;
        int bmModel = 5; // TODO This doesn't exist: bm.getColorModel();
        cmap = null;
           
        switch (bmModel) {
        // A1_MODEL
        case 1: 
            colorType = PNG_COLOR_TYPE_GRAY;
            bitDepth = 1;
            break;
            
        // A8_MODEL
        case 2:
            colorType = PNG_COLOR_TYPE_GRAY_ALPHA;
            bitDepth = 8;
            break;
            
        // INDEX8_MODEL
        // RGB_565_MODEL
        // ARGB_8888_MODEL
        case 3:
        case 4: 
        case 5: 
            colorType = bm.hasAlpha() ? PNG_COLOR_TYPE_RGBA : PNG_COLOR_TYPE_RGB;
            bitDepth = 8;
            break;

        default:
            // awt.3C=Unknown PNG color type
            throw new IllegalArgumentException(Messages.getString("awt.3C")); //$NON-NLS-1$
        }
        
        switch (colorType) {
        
            case PNG_COLOR_TYPE_GRAY: {
                if (bitDepth != 8 && bitDepth != 4 && bitDepth != 2 &&  bitDepth != 1) {
                    // awt.3C=Unknown PNG color type
                    throw new IllegalArgumentException(Messages.getString("awt.3C")); //$NON-NLS-1$
                }

                // Create gray color model
                int numEntries = 1 << bitDepth;
                int scaleFactor = 255 / (numEntries-1);
                byte comps[] = new byte[numEntries];
                for (int i = 0; i < numEntries; i++) {
                    comps[i] = (byte) (i * scaleFactor);
                }
                cm = new IndexColorModel(bitDepth, numEntries, comps, comps, comps);

                transferInts = false;
                break;
            }

            case PNG_COLOR_TYPE_RGB: {
                if (bitDepth != 8) {
                    // awt.3C=Unknown PNG color type
                    throw new IllegalArgumentException(Messages.getString("awt.3C")); //$NON-NLS-1$
                }
                
                cm = new DirectColorModel(24, 0xff0000, 0xFF00, 0xFF);
                
                transferInts = true;
                break;
            }

            case PNG_COLOR_TYPE_PLTE: {
                if (bitDepth != 8 && bitDepth != 4 && bitDepth != 2 && bitDepth != 1) {
                    // awt.3C=Unknown PNG color type
                    throw new IllegalArgumentException(Messages.getString("awt.3C")); //$NON-NLS-1$
                }

                if (cmap == null) {
                    throw new IllegalStateException("Palette color type is not supported");
                }

                cm = new IndexColorModel(bitDepth, cmap.length / 3, cmap, 0, false);

                transferInts = false;
                break;
            }

            case PNG_COLOR_TYPE_GRAY_ALPHA: {
                if (bitDepth != 8) {
                    // awt.3C=Unknown PNG color type
                    throw new IllegalArgumentException(Messages.getString("awt.3C")); //$NON-NLS-1$
                }

                cm = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY),
                        true, false,
                        Transparency.TRANSLUCENT,
                        DataBuffer.TYPE_BYTE);

                transferInts = false;
                dataElementsPerPixel = 2;
                break;
            }

            case PNG_COLOR_TYPE_RGBA: {
                if (bitDepth != 8) {
                    // awt.3C=Unknown PNG color type
                    throw new IllegalArgumentException(Messages.getString("awt.3C")); //$NON-NLS-1$
                }

                cm = ColorModel.getRGBdefault();

                transferInts = true;
                break;
            }
            default:
                // awt.3C=Unknown PNG color type
                throw new IllegalArgumentException(Messages.getString("awt.3C")); //$NON-NLS-1$
        }
        
        return cm;
    }
    
    private void sendPixels(int nbOfLinesPerChunk) {
        int w = imageWidth;
        int h = imageHeight;
        int n = 1;
        if (nbOfLinesPerChunk > 0 && nbOfLinesPerChunk <= h) {
            n = nbOfLinesPerChunk;
        }
        
        if (transferInts) {
            // Create output buffer
            intOut = new int[w * n];
            for (int yi = 0; yi < h; yi += n) {
                // Last chunk might contain less liness
                if (n > 1 && h - yi < n ) {
                    n = h - yi;
                }
                bm.getPixels(intOut, 0, w, 0, yi, w, n);
                setPixels(0, yi, w, n, model, intOut, 0, w);
            }
        } else {
            // Android bitmaps always store ints (ARGB-8888 direct model)
            throw new RuntimeException("Byte transfer not supported");
        }
    }
    
}
