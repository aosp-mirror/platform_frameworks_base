/*
 * Copyright (C) 2010 The Android Open Source Project
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

import com.android.ide.common.rendering.api.ResourceDensity;
import com.android.layoutlib.bridge.impl.DelegateManager;

import android.graphics.Bitmap.Config;
import android.os.Parcel;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.Buffer;

import javax.imageio.ImageIO;

/**
 * Delegate implementing the native methods of android.graphics.Bitmap
 *
 * Through the layoutlib_create tool, the original native methods of Bitmap have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 * This class behaves like the original native implementation, but in Java, keeping previously
 * native data into its own objects and mapping them to int that are sent back and forth between
 * it and the original Bitmap class.
 *
 * @see DelegateManager
 *
 */
public class Bitmap_Delegate {

    // ---- delegate manager ----
    private static final DelegateManager<Bitmap_Delegate> sManager =
            new DelegateManager<Bitmap_Delegate>();

    // ---- delegate helper data ----

    // ---- delegate data ----
    private BufferedImage mImage;
    private boolean mHasAlpha = true;

    // ---- Public Helper methods ----

    /**
     * Returns the native delegate associated to a given {@link Bitmap_Delegate} object.
     */
    public static Bitmap_Delegate getDelegate(Bitmap bitmap) {
        return sManager.getDelegate(bitmap.mNativeBitmap);
    }

    /**
     * Returns the native delegate associated to a given an int referencing a {@link Bitmap} object.
     */
    public static Bitmap_Delegate getDelegate(int native_bitmap) {
        return sManager.getDelegate(native_bitmap);
    }

    /**
     * Creates and returns a {@link Bitmap} initialized with the given file content.
     *
     * @param input the file from which to read the bitmap content
     * @param isMutable whether the bitmap is mutable
     * @param density the density associated with the bitmap
     *
     * @see Bitmap#isMutable()
     * @see Bitmap#getDensity()
     */
    public static Bitmap createBitmap(File input, boolean isMutable, ResourceDensity density)
            throws IOException {
        // create a delegate with the content of the file.
        Bitmap_Delegate delegate = new Bitmap_Delegate(ImageIO.read(input));

        return createBitmap(delegate, isMutable, density.getDpi());
    }

    /**
     * Creates and returns a {@link Bitmap} initialized with the given stream content.
     *
     * @param input the stream from which to read the bitmap content
     * @param isMutable whether the bitmap is mutable
     * @param density the density associated with the bitmap
     *
     * @see Bitmap#isMutable()
     * @see Bitmap#getDensity()
     */
    public static Bitmap createBitmap(InputStream input, boolean isMutable, ResourceDensity density)
            throws IOException {
        // create a delegate with the content of the stream.
        Bitmap_Delegate delegate = new Bitmap_Delegate(ImageIO.read(input));

        return createBitmap(delegate, isMutable, density.getDpi());
    }

    /**
     * Creates and returns a {@link Bitmap} initialized with the given {@link BufferedImage}
     *
     * @param image the bitmap content
     * @param isMutable whether the bitmap is mutable
     * @param density the density associated with the bitmap
     *
     * @see Bitmap#isMutable()
     * @see Bitmap#getDensity()
     */
    public static Bitmap createBitmap(BufferedImage image, boolean isMutable,
            ResourceDensity density) throws IOException {
        // create a delegate with the given image.
        Bitmap_Delegate delegate = new Bitmap_Delegate(image);

        return createBitmap(delegate, isMutable, density.getDpi());
    }

    /**
     * Returns the {@link BufferedImage} used by the delegate of the given {@link Bitmap}.
     */
    public static BufferedImage getImage(Bitmap bitmap) {
        // get the delegate from the native int.
        Bitmap_Delegate delegate = sManager.getDelegate(bitmap.mNativeBitmap);
        if (delegate == null) {
            assert false;
            return null;
        }

        return delegate.mImage;
    }

    public static int getBufferedImageType(int nativeBitmapConfig) {
        switch (Config.sConfigs[nativeBitmapConfig]) {
            case ALPHA_8:
                return BufferedImage.TYPE_INT_ARGB;
            case RGB_565:
                return BufferedImage.TYPE_INT_ARGB;
            case ARGB_4444:
                return BufferedImage.TYPE_INT_ARGB;
            case ARGB_8888:
                return BufferedImage.TYPE_INT_ARGB;
        }

        return BufferedImage.TYPE_INT_ARGB;
    }

    /**
     * Returns the {@link BufferedImage} used by the delegate of the given {@link Bitmap}.
     */
    public BufferedImage getImage() {
        return mImage;
    }

    // ---- native methods ----

    /*package*/ static Bitmap nativeCreate(int[] colors, int offset, int stride, int width,
            int height, int nativeConfig, boolean mutable) {
        int imageType = getBufferedImageType(nativeConfig);

        // create the image
        BufferedImage image = new BufferedImage(width, height, imageType);

        // FIXME fill the bitmap!

        // create a delegate with the content of the stream.
        Bitmap_Delegate delegate = new Bitmap_Delegate(image);

        return createBitmap(delegate, mutable, Bitmap.getDefaultDensity());
    }

    /*package*/ static Bitmap nativeCopy(int srcBitmap, int nativeConfig, boolean isMutable) {
        // FIXME implement native delegate
        throw new UnsupportedOperationException("Native delegate needed for Bitmap");
    }

    /*package*/ static void nativeDestructor(int nativeBitmap) {
        sManager.removeDelegate(nativeBitmap);
    }

    /*package*/ static void nativeRecycle(int nativeBitmap) {
        sManager.removeDelegate(nativeBitmap);
    }

    /*package*/ static boolean nativeCompress(int nativeBitmap, int format, int quality,
            OutputStream stream, byte[] tempStorage) {
        // FIXME implement native delegate
        throw new UnsupportedOperationException("Native delegate needed for Bitmap");
    }

    /*package*/ static void nativeErase(int nativeBitmap, int color) {
        // get the delegate from the native int.
        Bitmap_Delegate delegate = sManager.getDelegate(nativeBitmap);
        if (delegate == null) {
            assert false;
            return;
        }

        BufferedImage image = delegate.mImage;

        Graphics2D g = image.createGraphics();
        try {
            if (delegate.mHasAlpha == false) {
                color |= color & 0xFF000000;
            }
            g.setColor(new java.awt.Color(color));

            g.fillRect(0, 0, image.getWidth(), image.getHeight());
        } finally {
            g.dispose();
        }
    }

    /*package*/ static int nativeWidth(int nativeBitmap) {
        // get the delegate from the native int.
        Bitmap_Delegate delegate = sManager.getDelegate(nativeBitmap);
        if (delegate == null) {
            assert false;
            return 0;
        }

        return delegate.mImage.getWidth();
    }

    /*package*/ static int nativeHeight(int nativeBitmap) {
        // get the delegate from the native int.
        Bitmap_Delegate delegate = sManager.getDelegate(nativeBitmap);
        if (delegate == null) {
            assert false;
            return 0;
        }

        return delegate.mImage.getHeight();
    }

    /*package*/ static int nativeRowBytes(int nativeBitmap) {
        // get the delegate from the native int.
        Bitmap_Delegate delegate = sManager.getDelegate(nativeBitmap);
        if (delegate == null) {
            assert false;
            return 0;
        }

        return delegate.mImage.getWidth();
    }

    /*package*/ static int nativeConfig(int nativeBitmap) {
        return Config.ARGB_8888.nativeInt;
    }

    /*package*/ static boolean nativeHasAlpha(int nativeBitmap) {
        // get the delegate from the native int.
        Bitmap_Delegate delegate = sManager.getDelegate(nativeBitmap);
        if (delegate == null) {
            assert false;
            return true;
        }

        return delegate.mHasAlpha;
    }

    /*package*/ static int nativeGetPixel(int nativeBitmap, int x, int y) {
        // get the delegate from the native int.
        Bitmap_Delegate delegate = sManager.getDelegate(nativeBitmap);
        if (delegate == null) {
            assert false;
            return 0;
        }

        return delegate.mImage.getRGB(x, y);
    }

    /*package*/ static void nativeGetPixels(int nativeBitmap, int[] pixels, int offset,
            int stride, int x, int y, int width, int height) {
        // FIXME implement native delegate
        throw new UnsupportedOperationException("Native delegate needed for Bitmap.nativeGetPixels");
    }


    /*package*/ static void nativeSetPixel(int nativeBitmap, int x, int y, int color) {
        // FIXME implement native delegate
        throw new UnsupportedOperationException("Native delegate needed for Bitmap.nativeSetPixel");
    }

    /*package*/ static void nativeSetPixels(int nativeBitmap, int[] colors, int offset,
            int stride, int x, int y, int width, int height) {
        // FIXME implement native delegate
        throw new UnsupportedOperationException("Native delegate needed for Bitmap.nativeSetPixels");
    }

    /*package*/ static void nativeCopyPixelsToBuffer(int nativeBitmap, Buffer dst) {
        // FIXME implement native delegate
        throw new UnsupportedOperationException("Native delegate needed for Bitmap.nativeCopyPixelsToBuffer");
    }

    /*package*/ static void nativeCopyPixelsFromBuffer(int nb, Buffer src) {
        // FIXME implement native delegate
        throw new UnsupportedOperationException("Native delegate needed for Bitmap.nativeCopyPixelsFromBuffer");
    }

    /*package*/ static int nativeGenerationId(int nativeBitmap) {
        // FIXME implement native delegate
        throw new UnsupportedOperationException("Native delegate needed for Bitmap.nativeGenerationId");
    }

    /*package*/ static Bitmap nativeCreateFromParcel(Parcel p) {
        // FIXME implement native delegate
        throw new UnsupportedOperationException("Native delegate needed for Bitmap.nativeCreateFromParcel");
    }

    /*package*/ static boolean nativeWriteToParcel(int nativeBitmap, boolean isMutable,
            int density, Parcel p) {
        // FIXME implement native delegate
        throw new UnsupportedOperationException("Native delegate needed for Bitmap.nativeWriteToParcel");
    }

    /*package*/ static Bitmap nativeExtractAlpha(int nativeBitmap, int nativePaint,
            int[] offsetXY) {
        // FIXME implement native delegate
        throw new UnsupportedOperationException("Native delegate needed for Bitmap.nativeExtractAlpha");
    }


    /*package*/ static void nativePrepareToDraw(int nativeBitmap) {
        // FIXME implement native delegate
        throw new UnsupportedOperationException("Native delegate needed for Bitmap.nativePrepareToDraw");
    }

    /*package*/ static void nativeSetHasAlpha(int nativeBitmap, boolean hasAlpha) {
        // get the delegate from the native int.
        Bitmap_Delegate delegate = sManager.getDelegate(nativeBitmap);
        if (delegate == null) {
            assert false;
            return;
        }

        delegate.mHasAlpha = hasAlpha;
    }

    /*package*/ static boolean nativeSameAs(int nb0, int nb1) {
        // FIXME implement native delegate
        throw new UnsupportedOperationException("Native delegate needed for Bitmap.nativeSameAs");
    }

    // ---- Private delegate/helper methods ----

    private Bitmap_Delegate(BufferedImage image) {
        mImage = image;
    }

    private static Bitmap createBitmap(Bitmap_Delegate delegate, boolean isMutable, int density) {
        // get its native_int
        int nativeInt = sManager.addDelegate(delegate);

        // and create/return a new Bitmap with it
        return new Bitmap(nativeInt, null /* buffer */, isMutable, null /*ninePatchChunk*/, density);
    }
}
