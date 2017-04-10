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

import com.android.ide.common.rendering.api.LayoutLog;
import com.android.ide.common.rendering.api.RenderResources;
import com.android.ide.common.rendering.api.ResourceValue;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.layoutlib.bridge.impl.RenderAction;
import com.android.resources.Density;
import com.android.resources.ResourceType;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.annotation.Nullable;
import android.graphics.Bitmap.Config;
import android.os.Parcel;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.Buffer;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import javax.imageio.ImageIO;
import libcore.util.NativeAllocationRegistry_Delegate;

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
public final class Bitmap_Delegate {


    public enum BitmapCreateFlags {
        NONE, PREMULTIPLIED, MUTABLE
    }

    // ---- delegate manager ----
    private static final DelegateManager<Bitmap_Delegate> sManager =
            new DelegateManager<>(Bitmap_Delegate.class);
    private static long sFinalizer = -1;

    // ---- delegate helper data ----

    // ---- delegate data ----
    private final Config mConfig;
    private final BufferedImage mImage;
    private boolean mHasAlpha = true;
    private boolean mHasMipMap = false;      // TODO: check the default.
    private boolean mIsPremultiplied = true;
    private int mGenerationId = 0;


    // ---- Public Helper methods ----

    /**
     * Returns the native delegate associated to a given an int referencing a {@link Bitmap} object.
     */
    public static Bitmap_Delegate getDelegate(long native_bitmap) {
        return sManager.getDelegate(native_bitmap);
    }

    @Nullable
    public static Bitmap_Delegate getDelegate(@Nullable Bitmap bitmap) {
        return bitmap == null ? null : getDelegate(bitmap.getNativeInstance());
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
    public static Bitmap createBitmap(File input, boolean isMutable, Density density)
            throws IOException {
        return createBitmap(input, getPremultipliedBitmapCreateFlags(isMutable), density);
    }

    /**
     * Creates and returns a {@link Bitmap} initialized with the given file content.
     *
     * @param input the file from which to read the bitmap content
     * @param density the density associated with the bitmap
     *
     * @see Bitmap#isPremultiplied()
     * @see Bitmap#isMutable()
     * @see Bitmap#getDensity()
     */
    private static Bitmap createBitmap(File input, Set<BitmapCreateFlags> createFlags,
            Density density) throws IOException {
        // create a delegate with the content of the file.
        BufferedImage image = ImageIO.read(input);
        if (image == null && input.exists()) {
            // There was a problem decoding the image, or the decoder isn't registered. Webp maybe.
            // Replace with a broken image icon.
            BridgeContext currentContext = RenderAction.getCurrentContext();
            if (currentContext != null) {
                RenderResources resources = currentContext.getRenderResources();
                ResourceValue broken = resources.getFrameworkResource(ResourceType.DRAWABLE,
                        "ic_menu_report_image");
                File brokenFile = new File(broken.getValue());
                if (brokenFile.exists()) {
                    image = ImageIO.read(brokenFile);
                }
            }
        }
        Bitmap_Delegate delegate = new Bitmap_Delegate(image, Config.ARGB_8888);

        return createBitmap(delegate, createFlags, density.getDpiValue());
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
    public static Bitmap createBitmap(InputStream input, boolean isMutable, Density density)
            throws IOException {
        return createBitmap(input, getPremultipliedBitmapCreateFlags(isMutable), density);
    }

    /**
     * Creates and returns a {@link Bitmap} initialized with the given stream content.
     *
     * @param input the stream from which to read the bitmap content
     * @param density the density associated with the bitmap
     *
     * @see Bitmap#isPremultiplied()
     * @see Bitmap#isMutable()
     * @see Bitmap#getDensity()
     */
    public static Bitmap createBitmap(InputStream input, Set<BitmapCreateFlags> createFlags,
            Density density) throws IOException {
        // create a delegate with the content of the stream.
        Bitmap_Delegate delegate = new Bitmap_Delegate(ImageIO.read(input), Config.ARGB_8888);

        return createBitmap(delegate, createFlags, density.getDpiValue());
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
    public static Bitmap createBitmap(BufferedImage image, boolean isMutable, Density density) {
        return createBitmap(image, getPremultipliedBitmapCreateFlags(isMutable), density);
    }

    /**
     * Creates and returns a {@link Bitmap} initialized with the given {@link BufferedImage}
     *
     * @param image the bitmap content
     * @param density the density associated with the bitmap
     *
     * @see Bitmap#isPremultiplied()
     * @see Bitmap#isMutable()
     * @see Bitmap#getDensity()
     */
    public static Bitmap createBitmap(BufferedImage image, Set<BitmapCreateFlags> createFlags,
            Density density) {
        // create a delegate with the given image.
        Bitmap_Delegate delegate = new Bitmap_Delegate(image, Config.ARGB_8888);

        return createBitmap(delegate, createFlags, density.getDpiValue());
    }

    private static int getBufferedImageType() {
        return BufferedImage.TYPE_INT_ARGB;
    }

    /**
     * Returns the {@link BufferedImage} used by the delegate of the given {@link Bitmap}.
     */
    public BufferedImage getImage() {
        return mImage;
    }

    /**
     * Returns the Android bitmap config. Note that this not the config of the underlying
     * Java2D bitmap.
     */
    public Config getConfig() {
        return mConfig;
    }

    /**
     * Returns the hasAlpha rendering hint
     * @return true if the bitmap alpha should be used at render time
     */
    public boolean hasAlpha() {
        return mHasAlpha && mConfig != Config.RGB_565;
    }

    /**
     * Update the generationId.
     *
     * @see Bitmap#getGenerationId()
     */
    public void change() {
        mGenerationId++;
    }

    // ---- native methods ----

    @LayoutlibDelegate
    /*package*/ static Bitmap nativeCreate(int[] colors, int offset, int stride, int width,
            int height, int nativeConfig, boolean isMutable, @Nullable float[] xyzD50,
            @Nullable ColorSpace.Rgb.TransferParameters p) {
        int imageType = getBufferedImageType();

        // create the image
        BufferedImage image = new BufferedImage(width, height, imageType);

        if (colors != null) {
            image.setRGB(0, 0, width, height, colors, offset, stride);
        }

        // create a delegate with the content of the stream.
        Bitmap_Delegate delegate = new Bitmap_Delegate(image, Config.nativeToConfig(nativeConfig));

        return createBitmap(delegate, getPremultipliedBitmapCreateFlags(isMutable),
                            Bitmap.getDefaultDensity());
    }

    @LayoutlibDelegate
    /*package*/ static Bitmap nativeCopy(long srcBitmap, int nativeConfig, boolean isMutable) {
        Bitmap_Delegate srcBmpDelegate = sManager.getDelegate(srcBitmap);
        if (srcBmpDelegate == null) {
            return null;
        }

        BufferedImage srcImage = srcBmpDelegate.getImage();

        int width = srcImage.getWidth();
        int height = srcImage.getHeight();

        int imageType = getBufferedImageType();

        // create the image
        BufferedImage image = new BufferedImage(width, height, imageType);

        // copy the source image into the image.
        int[] argb = new int[width * height];
        srcImage.getRGB(0, 0, width, height, argb, 0, width);
        image.setRGB(0, 0, width, height, argb, 0, width);

        // create a delegate with the content of the stream.
        Bitmap_Delegate delegate = new Bitmap_Delegate(image, Config.nativeToConfig(nativeConfig));

        return createBitmap(delegate, getPremultipliedBitmapCreateFlags(isMutable),
                Bitmap.getDefaultDensity());
    }

    @LayoutlibDelegate
    /*package*/ static Bitmap nativeCopyAshmem(long nativeSrcBitmap) {
        // Unused method; no implementation provided.
        assert false;
        return null;
    }

    @LayoutlibDelegate
    /*package*/ static Bitmap nativeCopyAshmemConfig(long nativeSrcBitmap, int nativeConfig) {
        // Unused method; no implementation provided.
        assert false;
        return null;
    }

    @LayoutlibDelegate
    /*package*/ static long nativeGetNativeFinalizer() {
        synchronized (Bitmap_Delegate.class) {
            if (sFinalizer == -1) {
                sFinalizer = NativeAllocationRegistry_Delegate.createFinalizer(sManager::removeJavaReferenceFor);
            }
            return sFinalizer;
        }
    }

    @LayoutlibDelegate
    /*package*/ static boolean nativeRecycle(long nativeBitmap) {
        // In our case reycle() is a no-op. We will let the finalizer to dispose the bitmap.
        return true;
    }

    @LayoutlibDelegate
    /*package*/ static void nativeReconfigure(long nativeBitmap, int width, int height,
            int config, boolean isPremultiplied) {
        Bridge.getLog().error(LayoutLog.TAG_UNSUPPORTED,
                "Bitmap.reconfigure() is not supported", null /*data*/);
    }

    @LayoutlibDelegate
    /*package*/ static boolean nativeCompress(long nativeBitmap, int format, int quality,
            OutputStream stream, byte[] tempStorage) {
        Bridge.getLog().error(LayoutLog.TAG_UNSUPPORTED,
                "Bitmap.compress() is not supported", null /*data*/);
        return true;
    }

    @LayoutlibDelegate
    /*package*/ static void nativeErase(long nativeBitmap, int color) {
        // get the delegate from the native int.
        Bitmap_Delegate delegate = sManager.getDelegate(nativeBitmap);
        if (delegate == null) {
            return;
        }

        BufferedImage image = delegate.mImage;

        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new java.awt.Color(color, true));

            g.fillRect(0, 0, image.getWidth(), image.getHeight());
        } finally {
            g.dispose();
        }
    }

    @LayoutlibDelegate
    /*package*/ static int nativeRowBytes(long nativeBitmap) {
        // get the delegate from the native int.
        Bitmap_Delegate delegate = sManager.getDelegate(nativeBitmap);
        if (delegate == null) {
            return 0;
        }

        return delegate.mImage.getWidth();
    }

    @LayoutlibDelegate
    /*package*/ static int nativeConfig(long nativeBitmap) {
        // get the delegate from the native int.
        Bitmap_Delegate delegate = sManager.getDelegate(nativeBitmap);
        if (delegate == null) {
            return 0;
        }

        return delegate.mConfig.nativeInt;
    }

    @LayoutlibDelegate
    /*package*/ static boolean nativeHasAlpha(long nativeBitmap) {
        // get the delegate from the native int.
        Bitmap_Delegate delegate = sManager.getDelegate(nativeBitmap);
        return delegate == null || delegate.mHasAlpha;

    }

    @LayoutlibDelegate
    /*package*/ static boolean nativeHasMipMap(long nativeBitmap) {
        // get the delegate from the native int.
        Bitmap_Delegate delegate = sManager.getDelegate(nativeBitmap);
        return delegate == null || delegate.mHasMipMap;

    }

    @LayoutlibDelegate
    /*package*/ static int nativeGetPixel(long nativeBitmap, int x, int y) {
        // get the delegate from the native int.
        Bitmap_Delegate delegate = sManager.getDelegate(nativeBitmap);
        if (delegate == null) {
            return 0;
        }

        return delegate.mImage.getRGB(x, y);
    }

    @LayoutlibDelegate
    /*package*/ static void nativeGetPixels(long nativeBitmap, int[] pixels, int offset,
            int stride, int x, int y, int width, int height) {
        Bitmap_Delegate delegate = sManager.getDelegate(nativeBitmap);
        if (delegate == null) {
            return;
        }

        delegate.getImage().getRGB(x, y, width, height, pixels, offset, stride);
    }


    @LayoutlibDelegate
    /*package*/ static void nativeSetPixel(long nativeBitmap, int x, int y, int color) {
        Bitmap_Delegate delegate = sManager.getDelegate(nativeBitmap);
        if (delegate == null) {
            return;
        }

        delegate.getImage().setRGB(x, y, color);
    }

    @LayoutlibDelegate
    /*package*/ static void nativeSetPixels(long nativeBitmap, int[] colors, int offset,
            int stride, int x, int y, int width, int height) {
        Bitmap_Delegate delegate = sManager.getDelegate(nativeBitmap);
        if (delegate == null) {
            return;
        }

        delegate.getImage().setRGB(x, y, width, height, colors, offset, stride);
    }

    @LayoutlibDelegate
    /*package*/ static void nativeCopyPixelsToBuffer(long nativeBitmap, Buffer dst) {
        // FIXME implement native delegate
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                "Bitmap.copyPixelsToBuffer is not supported.", null, null /*data*/);
    }

    @LayoutlibDelegate
    /*package*/ static void nativeCopyPixelsFromBuffer(long nb, Buffer src) {
        // FIXME implement native delegate
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                "Bitmap.copyPixelsFromBuffer is not supported.", null, null /*data*/);
    }

    @LayoutlibDelegate
    /*package*/ static int nativeGenerationId(long nativeBitmap) {
        Bitmap_Delegate delegate = sManager.getDelegate(nativeBitmap);
        if (delegate == null) {
            return 0;
        }

        return delegate.mGenerationId;
    }

    @LayoutlibDelegate
    /*package*/ static Bitmap nativeCreateFromParcel(Parcel p) {
        // This is only called by Bitmap.CREATOR (Parcelable.Creator<Bitmap>), which is only
        // used during aidl call so really this should not be called.
        Bridge.getLog().error(LayoutLog.TAG_UNSUPPORTED,
                "AIDL is not suppored, and therefore Bitmaps cannot be created from parcels.",
                null /*data*/);
        return null;
    }

    @LayoutlibDelegate
    /*package*/ static boolean nativeWriteToParcel(long nativeBitmap, boolean isMutable,
            int density, Parcel p) {
        // This is only called when sending a bitmap through aidl, so really this should not
        // be called.
        Bridge.getLog().error(LayoutLog.TAG_UNSUPPORTED,
                "AIDL is not suppored, and therefore Bitmaps cannot be written to parcels.",
                null /*data*/);
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static Bitmap nativeExtractAlpha(long nativeBitmap, long nativePaint,
            int[] offsetXY) {
        Bitmap_Delegate bitmap = sManager.getDelegate(nativeBitmap);
        if (bitmap == null) {
            return null;
        }

        // get the paint which can be null if nativePaint is 0.
        Paint_Delegate paint = Paint_Delegate.getDelegate(nativePaint);

        if (paint != null && paint.getMaskFilter() != null) {
            Bridge.getLog().fidelityWarning(LayoutLog.TAG_MASKFILTER,
                    "MaskFilter not supported in Bitmap.extractAlpha",
                    null, null /*data*/);
        }

        int alpha = paint != null ? paint.getAlpha() : 0xFF;
        BufferedImage image = createCopy(bitmap.getImage(), BufferedImage.TYPE_INT_ARGB, alpha);

        // create the delegate. The actual Bitmap config is only an alpha channel
        Bitmap_Delegate delegate = new Bitmap_Delegate(image, Config.ALPHA_8);

        // the density doesn't matter, it's set by the Java method.
        return createBitmap(delegate, EnumSet.of(BitmapCreateFlags.MUTABLE),
                Density.DEFAULT_DENSITY /*density*/);
    }

    @LayoutlibDelegate
    /*package*/ static boolean nativeIsPremultiplied(long nativeBitmap) {
        // get the delegate from the native int.
        Bitmap_Delegate delegate = sManager.getDelegate(nativeBitmap);
        return delegate != null && delegate.mIsPremultiplied;

    }

    @LayoutlibDelegate
    /*package*/ static void nativeSetPremultiplied(long nativeBitmap, boolean isPremul) {
        // get the delegate from the native int.
        Bitmap_Delegate delegate = sManager.getDelegate(nativeBitmap);
        if (delegate == null) {
            return;
        }

        delegate.mIsPremultiplied = isPremul;
    }

    @LayoutlibDelegate
    /*package*/ static void nativeSetHasAlpha(long nativeBitmap, boolean hasAlpha,
            boolean isPremul) {
        // get the delegate from the native int.
        Bitmap_Delegate delegate = sManager.getDelegate(nativeBitmap);
        if (delegate == null) {
            return;
        }

        delegate.mHasAlpha = hasAlpha;
    }

    @LayoutlibDelegate
    /*package*/ static void nativeSetHasMipMap(long nativeBitmap, boolean hasMipMap) {
        // get the delegate from the native int.
        Bitmap_Delegate delegate = sManager.getDelegate(nativeBitmap);
        if (delegate == null) {
            return;
        }

        delegate.mHasMipMap = hasMipMap;
    }

    @LayoutlibDelegate
    /*package*/ static boolean nativeSameAs(long nb0, long nb1) {
        Bitmap_Delegate delegate1 = sManager.getDelegate(nb0);
        if (delegate1 == null) {
            return false;
        }

        Bitmap_Delegate delegate2 = sManager.getDelegate(nb1);
        if (delegate2 == null) {
            return false;
        }

        BufferedImage image1 = delegate1.getImage();
        BufferedImage image2 = delegate2.getImage();
        if (delegate1.mConfig != delegate2.mConfig ||
                image1.getWidth() != image2.getWidth() ||
                image1.getHeight() != image2.getHeight()) {
            return false;
        }

        // get the internal data
        int w = image1.getWidth();
        int h = image2.getHeight();
        int[] argb1 = new int[w*h];
        int[] argb2 = new int[w*h];

        image1.getRGB(0, 0, w, h, argb1, 0, w);
        image2.getRGB(0, 0, w, h, argb2, 0, w);

        // compares
        if (delegate1.mConfig == Config.ALPHA_8) {
            // in this case we have to manually compare the alpha channel as the rest is garbage.
            final int length = w*h;
            for (int i = 0 ; i < length ; i++) {
                if ((argb1[i] & 0xFF000000) != (argb2[i] & 0xFF000000)) {
                    return false;
                }
            }
            return true;
        }

        return Arrays.equals(argb1, argb2);
    }

    @LayoutlibDelegate
    /*package*/ static int nativeGetAllocationByteCount(long nativeBitmap) {
        // get the delegate from the native int.
        Bitmap_Delegate delegate = sManager.getDelegate(nativeBitmap);
        if (delegate == null) {
            return 0;
        }
        return nativeRowBytes(nativeBitmap) * delegate.mImage.getHeight();

    }

    @LayoutlibDelegate
    /*package*/ static void nativePrepareToDraw(long nativeBitmap) {
        // do nothing as Bitmap_Delegate does not have caches
    }

    @LayoutlibDelegate
    /*package*/ static Bitmap nativeCopyPreserveInternalConfig(long nativeBitmap) {
        Bitmap_Delegate srcBmpDelegate = sManager.getDelegate(nativeBitmap);
        if (srcBmpDelegate == null) {
            return null;
        }

        BufferedImage srcImage = srcBmpDelegate.getImage();

        // create the image
        BufferedImage image = new BufferedImage(srcImage.getColorModel(), srcImage.copyData(null),
                srcImage.isAlphaPremultiplied(), null);

        // create a delegate with the content of the stream.
        Bitmap_Delegate delegate = new Bitmap_Delegate(image, srcBmpDelegate.getConfig());

        return createBitmap(delegate, EnumSet.of(BitmapCreateFlags.NONE),
                Bitmap.getDefaultDensity());
    }

    @LayoutlibDelegate
    /*package*/ static Bitmap nativeCreateHardwareBitmap(GraphicBuffer buffer) {
        Bridge.getLog().error(LayoutLog.TAG_UNSUPPORTED,
                "Bitmap.nativeCreateHardwareBitmap() is not supported", null /*data*/);
        return null;
    }

    @LayoutlibDelegate
    /*package*/ static GraphicBuffer nativeCreateGraphicBufferHandle(long nativeBitmap) {
        Bridge.getLog().error(LayoutLog.TAG_UNSUPPORTED,
                "Bitmap.nativeCreateGraphicBufferHandle() is not supported", null /*data*/);
        return null;
    }

    @LayoutlibDelegate
    /*package*/ static boolean nativeIsSRGB(long nativeBitmap) {
        Bridge.getLog().error(LayoutLog.TAG_UNSUPPORTED,
                "Color spaces are not supported", null /*data*/);
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static boolean nativeGetColorSpace(long nativePtr, float[] xyz, float[] params) {
        Bridge.getLog().error(LayoutLog.TAG_UNSUPPORTED,
                "Color spaces are not supported", null /*data*/);
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static void nativeCopyColorSpace(long srcBitmap, long dstBitmap) {
        Bridge.getLog().error(LayoutLog.TAG_UNSUPPORTED,
                "Color spaces are not supported", null /*data*/);
    }

    // ---- Private delegate/helper methods ----

    private Bitmap_Delegate(BufferedImage image, Config config) {
        mImage = image;
        mConfig = config;
    }

    private static Bitmap createBitmap(Bitmap_Delegate delegate,
            Set<BitmapCreateFlags> createFlags, int density) {
        // get its native_int
        long nativeInt = sManager.addNewDelegate(delegate);

        int width = delegate.mImage.getWidth();
        int height = delegate.mImage.getHeight();
        boolean isMutable = createFlags.contains(BitmapCreateFlags.MUTABLE);
        boolean isPremultiplied = createFlags.contains(BitmapCreateFlags.PREMULTIPLIED);

        // and create/return a new Bitmap with it
        return new Bitmap(nativeInt, width, height, density, isMutable,
                          isPremultiplied, null /*ninePatchChunk*/, null /* layoutBounds */);
    }

    private static Set<BitmapCreateFlags> getPremultipliedBitmapCreateFlags(boolean isMutable) {
        Set<BitmapCreateFlags> createFlags = EnumSet.of(BitmapCreateFlags.PREMULTIPLIED);
        if (isMutable) {
            createFlags.add(BitmapCreateFlags.MUTABLE);
        }
        return createFlags;
    }

    /**
     * Creates and returns a copy of a given BufferedImage.
     * <p/>
     * if alpha is different than 255, then it is applied to the alpha channel of each pixel.
     *
     * @param image the image to copy
     * @param imageType the type of the new image
     * @param alpha an optional alpha modifier
     * @return a new BufferedImage
     */
    /*package*/ static BufferedImage createCopy(BufferedImage image, int imageType, int alpha) {
        int w = image.getWidth();
        int h = image.getHeight();

        BufferedImage result = new BufferedImage(w, h, imageType);

        int[] argb = new int[w * h];
        image.getRGB(0, 0, image.getWidth(), image.getHeight(), argb, 0, image.getWidth());

        if (alpha != 255) {
            final int length = argb.length;
            for (int i = 0 ; i < length; i++) {
                int a = (argb[i] >>> 24 * alpha) / 255;
                argb[i] = (a << 24) | (argb[i] & 0x00FFFFFF);
            }
        }

        result.setRGB(0, 0, w, h, argb, 0, w);

        return result;
    }

}
