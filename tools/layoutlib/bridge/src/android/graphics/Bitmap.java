/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.graphics;

import com.android.layoutlib.bridge.BridgeCanvas;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

public final class Bitmap extends _Original_Bitmap {
    
    private BufferedImage mImage;

    public Bitmap(File input) throws IOException {
        super(1, true, null);
        
        mImage = ImageIO.read(input);
    }
    
    Bitmap(BufferedImage image) {
        super(1, true, null);
        mImage = image;
    }

    public BufferedImage getImage() {
        return mImage;
    }
    
    // ----- overriden methods
    
    public enum Config {
        // these native values must match up with the enum in SkBitmap.h
        ALPHA_8     (2),
        RGB_565     (4),
        ARGB_4444   (5),
        ARGB_8888   (6);

        Config(int ni) {
            this.nativeInt = ni;
        }
        final int nativeInt;
        
        /* package */ static Config nativeToConfig(int ni) {
            return sConfigs[ni];
        }
        
        private static Config sConfigs[] = {
            null, null, ALPHA_8, null, RGB_565, ARGB_4444, ARGB_8888
        };
    }

    
    @Override
    public int getWidth() {
        return mImage.getWidth();
    }
    
    @Override
    public int getHeight() {
        return mImage.getHeight();
    }
    
    /**
     * Returns an immutable bitmap from the source bitmap. The new bitmap may
     * be the same object as source, or a copy may have been made.
     */
    public static Bitmap createBitmap(Bitmap src) {
        return createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), null, false);
    }

    /**
     * Returns an immutable bitmap from the specified subset of the source
     * bitmap. The new bitmap may be the same object as source, or a copy may
     * have been made.
     *
     * @param source   The bitmap we are subsetting
     * @param x        The x coordinate of the first pixel in source
     * @param y        The y coordinate of the first pixel in source
     * @param width    The number of pixels in each row
     * @param height   The number of rows
     */
    public static Bitmap createBitmap(Bitmap source, int x, int y,
                                      int width, int height) {
        return new Bitmap(source.mImage.getSubimage(x, y, width, height));
    }
    
    /**
     * Returns an immutable bitmap from subset of the source bitmap,
     * transformed by the optional matrix.
     *
     * @param source   The bitmap we are subsetting
     * @param x        The x coordinate of the first pixel in source
     * @param y        The y coordinate of the first pixel in source
     * @param width    The number of pixels in each row
     * @param height   The number of rows
     * @param m        Option matrix to be applied to the pixels
     * @param filter   true if the source should be filtered.
     *                   Only applies if the matrix contains more than just
     *                   translation.
     * @return A bitmap that represents the specified subset of source
     * @throws IllegalArgumentException if the x, y, width, height values are
     *         outside of the dimensions of the source bitmap.
     */
    public static Bitmap createBitmap(Bitmap source, int x, int y, int width,
                                      int height, Matrix m, boolean filter) {
        checkXYSign(x, y);
        checkWidthHeight(width, height);
        if (x + width > source.getWidth()) {
            throw new IllegalArgumentException(
                    "x + width must be <= bitmap.width()");
        }
        if (y + height > source.getHeight()) {
            throw new IllegalArgumentException(
                    "y + height must be <= bitmap.height()");
        }

        // check if we can just return our argument unchanged
        if (!source.isMutable() && x == 0 && y == 0
                && width == source.getWidth() && height == source.getHeight()
                && (m == null || m.isIdentity())) {
            return source;
        }

        if (m == null || m.isIdentity()) {
            return new Bitmap(source.mImage.getSubimage(x, y, width, height));
        }

        int neww = width;
        int newh = height;
        Paint paint;

        Rect srcR = new Rect(x, y, x + width, y + height);
        RectF dstR = new RectF(0, 0, width, height);

        /*  the dst should have alpha if the src does, or if our matrix
            doesn't preserve rectness
        */
        boolean hasAlpha = source.hasAlpha() || !m.rectStaysRect();
        RectF deviceR = new RectF();
        m.mapRect(deviceR, dstR);
        neww = Math.round(deviceR.width());
        newh = Math.round(deviceR.height());

        BridgeCanvas canvas = new BridgeCanvas(neww, newh);

        canvas.translate(-deviceR.left, -deviceR.top);
        canvas.concat(m);
        paint = new Paint();
        paint.setFilterBitmap(filter);
        if (!m.rectStaysRect()) {
            paint.setAntiAlias(true);
        }

        canvas.drawBitmap(source, srcR, dstR, paint);
        
        return new Bitmap(canvas.getImage());
    }
    
    /**
     * Returns a mutable bitmap with the specified width and height.
     *
     * @param width    The width of the bitmap
     * @param height   The height of the bitmap
     * @param config   The bitmap config to create.
     * @throws IllegalArgumentException if the width or height are <= 0
     */
    public static Bitmap createBitmap(int width, int height, Config config) {
        return new Bitmap(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB));
    }
    
    /**
     * Returns a immutable bitmap with the specified width and height, with each
     * pixel value set to the corresponding value in the colors array.
     *
     * @param colors   Array of {@link Color} used to initialize the pixels.
     * @param offset   Number of values to skip before the first color in the
     *                 array of colors.
     * @param stride   Number of colors in the array between rows (must be >=
     *                 width or <= -width).
     * @param width    The width of the bitmap
     * @param height   The height of the bitmap
     * @param config   The bitmap config to create. If the config does not
     *                 support per-pixel alpha (e.g. RGB_565), then the alpha
     *                 bytes in the colors[] will be ignored (assumed to be FF)
     * @throws IllegalArgumentException if the width or height are <= 0, or if
     *         the color array's length is less than the number of pixels.
     */
    public static Bitmap createBitmap(int colors[], int offset, int stride,
                                      int width, int height, Config config) {
        checkWidthHeight(width, height);
        if (Math.abs(stride) < width) {
            throw new IllegalArgumentException("abs(stride) must be >= width");
        }
        int lastScanline = offset + (height - 1) * stride;
        int length = colors.length;
        if (offset < 0 || (offset + width > length)
            || lastScanline < 0
            || (lastScanline + width > length)) {
            throw new ArrayIndexOutOfBoundsException();
        }
        
        // TODO: create an immutable bitmap...
        throw new UnsupportedOperationException();
    }

    /**
     * Returns a immutable bitmap with the specified width and height, with each
     * pixel value set to the corresponding value in the colors array.
     *
     * @param colors   Array of {@link Color} used to initialize the pixels.
     *                 This array must be at least as large as width * height.
     * @param width    The width of the bitmap
     * @param height   The height of the bitmap
     * @param config   The bitmap config to create. If the config does not
     *                 support per-pixel alpha (e.g. RGB_565), then the alpha
     *                 bytes in the colors[] will be ignored (assumed to be FF)
     * @throws IllegalArgumentException if the width or height are <= 0, or if
     *         the color array's length is less than the number of pixels.
     */
    public static Bitmap createBitmap(int colors[], int width, int height,
                                      Config config) {
        return createBitmap(colors, 0, width, width, height, config);
    }

}
