/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.view;

import dalvik.system.CloseGuard;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.Region;
import android.view.Surface;
import android.os.IBinder;
import android.os.SystemProperties;
import android.util.Log;
import android.view.Surface.OutOfResourcesException;

/**
 * SurfaceControl
 *  @hide
 */
public class SurfaceControl {
    private static final String TAG = "SurfaceControl";

    private static native int nativeCreate(SurfaceSession session, String name,
            int w, int h, int format, int flags)
            throws OutOfResourcesException;
    private static native void nativeRelease(int nativeObject);
    private static native void nativeDestroy(int nativeObject);

    private static native Bitmap nativeScreenshot(IBinder displayToken,
            int width, int height, int minLayer, int maxLayer, boolean allLayers);
    private static native void nativeScreenshot(IBinder displayToken, Surface consumer,
            int width, int height, int minLayer, int maxLayer, boolean allLayers);

    private static native void nativeOpenTransaction();
    private static native void nativeCloseTransaction();
    private static native void nativeSetAnimationTransaction();

    private static native void nativeSetLayer(int nativeObject, int zorder);
    private static native void nativeSetPosition(int nativeObject, float x, float y);
    private static native void nativeSetSize(int nativeObject, int w, int h);
    private static native void nativeSetTransparentRegionHint(int nativeObject, Region region);
    private static native void nativeSetAlpha(int nativeObject, float alpha);
    private static native void nativeSetMatrix(int nativeObject, float dsdx, float dtdx, float dsdy, float dtdy);
    private static native void nativeSetFlags(int nativeObject, int flags, int mask);
    private static native void nativeSetWindowCrop(int nativeObject, int l, int t, int r, int b);
    private static native void nativeSetLayerStack(int nativeObject, int layerStack);

    private static native IBinder nativeGetBuiltInDisplay(int physicalDisplayId);
    private static native IBinder nativeCreateDisplay(String name, boolean secure);
    private static native void nativeDestroyDisplay(IBinder displayToken);
    private static native void nativeSetDisplaySurface(
            IBinder displayToken, int nativeSurfaceObject);
    private static native void nativeSetDisplayLayerStack(
            IBinder displayToken, int layerStack);
    private static native void nativeSetDisplayProjection(
            IBinder displayToken, int orientation,
            int l, int t, int r, int b,
            int L, int T, int R, int B);
    private static native boolean nativeGetDisplayInfo(
            IBinder displayToken, SurfaceControl.PhysicalDisplayInfo outInfo);
    private static native void nativeBlankDisplay(IBinder displayToken);
    private static native void nativeUnblankDisplay(IBinder displayToken);


    private final CloseGuard mCloseGuard = CloseGuard.get();
    private final String mName;
    int mNativeObject; // package visibility only for Surface.java access

    private static final boolean HEADLESS = "1".equals(
        SystemProperties.get("ro.config.headless", "0"));

    /* flags used in constructor (keep in sync with ISurfaceComposerClient.h) */

    /**
     * Surface creation flag: Surface is created hidden
     */
    public static final int HIDDEN = 0x00000004;

    /**
     * Surface creation flag: The surface contains secure content, special
     * measures will be taken to disallow the surface's content to be copied
     * from another process. In particular, screenshots and VNC servers will
     * be disabled, but other measures can take place, for instance the
     * surface might not be hardware accelerated.
     *
     */
    public static final int SECURE = 0x00000080;

    /**
     * Surface creation flag: Creates a surface where color components are interpreted
     * as "non pre-multiplied" by their alpha channel. Of course this flag is
     * meaningless for surfaces without an alpha channel. By default
     * surfaces are pre-multiplied, which means that each color component is
     * already multiplied by its alpha value. In this case the blending
     * equation used is:
     *
     *    DEST = SRC + DEST * (1-SRC_ALPHA)
     *
     * By contrast, non pre-multiplied surfaces use the following equation:
     *
     *    DEST = SRC * SRC_ALPHA * DEST * (1-SRC_ALPHA)
     *
     * pre-multiplied surfaces must always be used if transparent pixels are
     * composited on top of each-other into the surface. A pre-multiplied
     * surface can never lower the value of the alpha component of a given
     * pixel.
     *
     * In some rare situations, a non pre-multiplied surface is preferable.
     *
     */
    public static final int NON_PREMULTIPLIED = 0x00000100;

    /**
     * Surface creation flag: Indicates that the surface must be considered opaque,
     * even if its pixel format is set to translucent. This can be useful if an
     * application needs full RGBA 8888 support for instance but will
     * still draw every pixel opaque.
     *
     */
    public static final int OPAQUE = 0x00000400;

    /**
     * Surface creation flag: Application requires a hardware-protected path to an
     * external display sink. If a hardware-protected path is not available,
     * then this surface will not be displayed on the external sink.
     *
     */
    public static final int PROTECTED_APP = 0x00000800;

    // 0x1000 is reserved for an independent DRM protected flag in framework

    /**
     * Surface creation flag: Creates a normal surface.
     * This is the default.
     *
     */
    public static final int FX_SURFACE_NORMAL   = 0x00000000;

    /**
     * Surface creation flag: Creates a Dim surface.
     * Everything behind this surface is dimmed by the amount specified
     * in {@link #setAlpha}.  It is an error to lock a Dim surface, since it
     * doesn't have a backing store.
     *
     */
    public static final int FX_SURFACE_DIM = 0x00020000;

    /**
     * Mask used for FX values above.
     *
     */
    public static final int FX_SURFACE_MASK = 0x000F0000;

    /* flags used with setFlags() (keep in sync with ISurfaceComposer.h) */

    /**
     * Surface flag: Hide the surface.
     * Equivalent to calling hide().
     */
    public static final int SURFACE_HIDDEN = 0x01;


    /* built-in physical display ids (keep in sync with ISurfaceComposer.h)
     * these are different from the logical display ids used elsewhere in the framework */

    /**
     * Built-in physical display id: Main display.
     * Use only with {@link SurfaceControl#getBuiltInDisplay()}.
     */
    public static final int BUILT_IN_DISPLAY_ID_MAIN = 0;

    /**
     * Built-in physical display id: Attached HDMI display.
     * Use only with {@link SurfaceControl#getBuiltInDisplay()}.
     */
    public static final int BUILT_IN_DISPLAY_ID_HDMI = 1;



    /**
     * Create a surface with a name.
     *
     * The surface creation flags specify what kind of surface to create and
     * certain options such as whether the surface can be assumed to be opaque
     * and whether it should be initially hidden.  Surfaces should always be
     * created with the {@link #HIDDEN} flag set to ensure that they are not
     * made visible prematurely before all of the surface's properties have been
     * configured.
     *
     * Good practice is to first create the surface with the {@link #HIDDEN} flag
     * specified, open a transaction, set the surface layer, layer stack, alpha,
     * and position, call {@link #show} if appropriate, and close the transaction.
     *
     * @param session The surface session, must not be null.
     * @param name The surface name, must not be null.
     * @param w The surface initial width.
     * @param h The surface initial height.
     * @param flags The surface creation flags.  Should always include {@link #HIDDEN}
     * in the creation flags.
     *
     * @throws throws OutOfResourcesException If the SurfaceControl cannot be created.
     */
    public SurfaceControl(SurfaceSession session,
            String name, int w, int h, int format, int flags)
                    throws OutOfResourcesException {
        if (session == null) {
            throw new IllegalArgumentException("session must not be null");
        }
        if (name == null) {
            throw new IllegalArgumentException("name must not be null");
        }

        if ((flags & SurfaceControl.HIDDEN) == 0) {
            Log.w(TAG, "Surfaces should always be created with the HIDDEN flag set "
                    + "to ensure that they are not made visible prematurely before "
                    + "all of the surface's properties have been configured.  "
                    + "Set the other properties and make the surface visible within "
                    + "a transaction.  New surface name: " + name,
                    new Throwable());
        }

        checkHeadless();

        mName = name;
        mNativeObject = nativeCreate(session, name, w, h, format, flags);
        if (mNativeObject == 0) {
            throw new OutOfResourcesException(
                    "Couldn't allocate SurfaceControl native object");
        }

        mCloseGuard.open("release");
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }
            if (mNativeObject != 0) {
                nativeRelease(mNativeObject);
            }
        } finally {
            super.finalize();
        }
    }

    @Override
    public String toString() {
        return "Surface(name=" + mName + ")";
    }

    /**
     * Release the local reference to the server-side surface.
     * Always call release() when you're done with a Surface.
     * This will make the surface invalid.
     */
    public void release() {
        if (mNativeObject != 0) {
            nativeRelease(mNativeObject);
            mNativeObject = 0;
        }
        mCloseGuard.close();
    }

    /**
     * Free all server-side state associated with this surface and
     * release this object's reference.  This method can only be
     * called from the process that created the service.
     */
    public void destroy() {
        if (mNativeObject != 0) {
            nativeDestroy(mNativeObject);
            mNativeObject = 0;
        }
        mCloseGuard.close();
    }

    private void checkNotReleased() {
        if (mNativeObject == 0) throw new NullPointerException(
                "mNativeObject is null. Have you called release() already?");
    }

    /*
     * set surface parameters.
     * needs to be inside open/closeTransaction block
     */

    /** start a transaction */
    public static void openTransaction() {
        nativeOpenTransaction();
    }

    /** end a transaction */
    public static void closeTransaction() {
        nativeCloseTransaction();
    }

    /** flag the transaction as an animation */
    public static void setAnimationTransaction() {
        nativeSetAnimationTransaction();
    }

    public void setLayer(int zorder) {
        checkNotReleased();
        nativeSetLayer(mNativeObject, zorder);
    }

    public void setPosition(float x, float y) {
        checkNotReleased();
        nativeSetPosition(mNativeObject, x, y);
    }

    public void setSize(int w, int h) {
        checkNotReleased();
        nativeSetSize(mNativeObject, w, h);
    }

    public void hide() {
        checkNotReleased();
        nativeSetFlags(mNativeObject, SURFACE_HIDDEN, SURFACE_HIDDEN);
    }

    public void show() {
        checkNotReleased();
        nativeSetFlags(mNativeObject, 0, SURFACE_HIDDEN);
    }

    public void setTransparentRegionHint(Region region) {
        checkNotReleased();
        nativeSetTransparentRegionHint(mNativeObject, region);
    }

    public void setAlpha(float alpha) {
        checkNotReleased();
        nativeSetAlpha(mNativeObject, alpha);
    }

    public void setMatrix(float dsdx, float dtdx, float dsdy, float dtdy) {
        checkNotReleased();
        nativeSetMatrix(mNativeObject, dsdx, dtdx, dsdy, dtdy);
    }

    public void setFlags(int flags, int mask) {
        checkNotReleased();
        nativeSetFlags(mNativeObject, flags, mask);
    }

    public void setWindowCrop(Rect crop) {
        checkNotReleased();
        if (crop != null) {
            nativeSetWindowCrop(mNativeObject,
                crop.left, crop.top, crop.right, crop.bottom);
        } else {
            nativeSetWindowCrop(mNativeObject, 0, 0, 0, 0);
        }
    }

    public void setLayerStack(int layerStack) {
        checkNotReleased();
        nativeSetLayerStack(mNativeObject, layerStack);
    }

    /*
     * set display parameters.
     * needs to be inside open/closeTransaction block
     */

    /**
     * Describes the properties of a physical display known to surface flinger.
     */
    public static final class PhysicalDisplayInfo {
        public int width;
        public int height;
        public float refreshRate;
        public float density;
        public float xDpi;
        public float yDpi;
        public boolean secure;

        public PhysicalDisplayInfo() {
        }

        public PhysicalDisplayInfo(PhysicalDisplayInfo other) {
            copyFrom(other);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof PhysicalDisplayInfo && equals((PhysicalDisplayInfo)o);
        }

        public boolean equals(PhysicalDisplayInfo other) {
            return other != null
                    && width == other.width
                    && height == other.height
                    && refreshRate == other.refreshRate
                    && density == other.density
                    && xDpi == other.xDpi
                    && yDpi == other.yDpi
                    && secure == other.secure;
        }

        @Override
        public int hashCode() {
            return 0; // don't care
        }

        public void copyFrom(PhysicalDisplayInfo other) {
            width = other.width;
            height = other.height;
            refreshRate = other.refreshRate;
            density = other.density;
            xDpi = other.xDpi;
            yDpi = other.yDpi;
            secure = other.secure;
        }

        // For debugging purposes
        @Override
        public String toString() {
            return "PhysicalDisplayInfo{" + width + " x " + height + ", " + refreshRate + " fps, "
                    + "density " + density + ", " + xDpi + " x " + yDpi + " dpi, secure " + secure
                    + "}";
        }
    }

    public static void unblankDisplay(IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        nativeUnblankDisplay(displayToken);
    }

    public static void blankDisplay(IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        nativeBlankDisplay(displayToken);
    }

    public static boolean getDisplayInfo(IBinder displayToken, SurfaceControl.PhysicalDisplayInfo outInfo) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        if (outInfo == null) {
            throw new IllegalArgumentException("outInfo must not be null");
        }
        return nativeGetDisplayInfo(displayToken, outInfo);
    }

    public static void setDisplayProjection(IBinder displayToken,
            int orientation, Rect layerStackRect, Rect displayRect) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        if (layerStackRect == null) {
            throw new IllegalArgumentException("layerStackRect must not be null");
        }
        if (displayRect == null) {
            throw new IllegalArgumentException("displayRect must not be null");
        }
        nativeSetDisplayProjection(displayToken, orientation,
                layerStackRect.left, layerStackRect.top, layerStackRect.right, layerStackRect.bottom,
                displayRect.left, displayRect.top, displayRect.right, displayRect.bottom);
    }

    public static void setDisplayLayerStack(IBinder displayToken, int layerStack) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        nativeSetDisplayLayerStack(displayToken, layerStack);
    }

    public static void setDisplaySurface(IBinder displayToken, Surface surface) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }

        if (surface != null) {
            synchronized (surface.mLock) {
                nativeSetDisplaySurface(displayToken, surface.mNativeObject);
            }
        } else {
            nativeSetDisplaySurface(displayToken, 0);
        }
    }

    public static IBinder createDisplay(String name, boolean secure) {
        if (name == null) {
            throw new IllegalArgumentException("name must not be null");
        }
        return nativeCreateDisplay(name, secure);
    }

    public static void destroyDisplay(IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        nativeDestroyDisplay(displayToken);
    }

    public static IBinder getBuiltInDisplay(int builtInDisplayId) {
        return nativeGetBuiltInDisplay(builtInDisplayId);
    }


    /**
     * Copy the current screen contents into the provided {@link Surface}
     *
     * @param display The display to take the screenshot of.
     * @param consumer The {@link Surface} to take the screenshot into.
     * @param width The desired width of the returned bitmap; the raw
     * screen will be scaled down to this size.
     * @param height The desired height of the returned bitmap; the raw
     * screen will be scaled down to this size.
     * @param minLayer The lowest (bottom-most Z order) surface layer to
     * include in the screenshot.
     * @param maxLayer The highest (top-most Z order) surface layer to
     * include in the screenshot.
     */
    public static void screenshot(IBinder display, Surface consumer,
            int width, int height, int minLayer, int maxLayer) {
        screenshot(display, consumer, width, height, minLayer, maxLayer, false);
    }

    /**
     * Copy the current screen contents into the provided {@link Surface}
     *
     * @param display The display to take the screenshot of.
     * @param consumer The {@link Surface} to take the screenshot into.
     * @param width The desired width of the returned bitmap; the raw
     * screen will be scaled down to this size.
     * @param height The desired height of the returned bitmap; the raw
     * screen will be scaled down to this size.
     */
    public static void screenshot(IBinder display, Surface consumer,
            int width, int height) {
        screenshot(display, consumer, width, height, 0, 0, true);
    }

    /**
     * Copy the current screen contents into the provided {@link Surface}
     *
     * @param display The display to take the screenshot of.
     * @param consumer The {@link Surface} to take the screenshot into.
     */
    public static void screenshot(IBinder display, Surface consumer) {
        screenshot(display, consumer, 0, 0, 0, 0, true);
    }


    /**
     * Copy the current screen contents into a bitmap and return it.
     *
     * CAVEAT: Versions of screenshot that return a {@link Bitmap} can
     * be extremely slow; avoid use unless absolutely necessary; prefer
     * the versions that use a {@link Surface} instead, such as
     * {@link SurfaceControl#screenshot(IBinder, Surface)}.
     *
     * @param width The desired width of the returned bitmap; the raw
     * screen will be scaled down to this size.
     * @param height The desired height of the returned bitmap; the raw
     * screen will be scaled down to this size.
     * @param minLayer The lowest (bottom-most Z order) surface layer to
     * include in the screenshot.
     * @param maxLayer The highest (top-most Z order) surface layer to
     * include in the screenshot.
     * @return Returns a Bitmap containing the screen contents, or null
     * if an error occurs. Make sure to call Bitmap.recycle() as soon as
     * possible, once its content is not needed anymore.
     */
    public static Bitmap screenshot(int width, int height, int minLayer, int maxLayer) {
        // TODO: should take the display as a parameter
        IBinder displayToken = SurfaceControl.getBuiltInDisplay(
                SurfaceControl.BUILT_IN_DISPLAY_ID_MAIN);
        return nativeScreenshot(displayToken, width, height, minLayer, maxLayer, false);
    }

    /**
     * Like {@link SurfaceControl#screenshot(int, int, int, int)} but includes all
     * Surfaces in the screenshot.
     *
     * @param width The desired width of the returned bitmap; the raw
     * screen will be scaled down to this size.
     * @param height The desired height of the returned bitmap; the raw
     * screen will be scaled down to this size.
     * @return Returns a Bitmap containing the screen contents, or null
     * if an error occurs. Make sure to call Bitmap.recycle() as soon as
     * possible, once its content is not needed anymore.
     */
    public static Bitmap screenshot(int width, int height) {
        // TODO: should take the display as a parameter
        IBinder displayToken = SurfaceControl.getBuiltInDisplay(
                SurfaceControl.BUILT_IN_DISPLAY_ID_MAIN);
        return nativeScreenshot(displayToken, width, height, 0, 0, true);
    }

    private static void screenshot(IBinder display, Surface consumer,
            int width, int height, int minLayer, int maxLayer, boolean allLayers) {
        if (display == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        if (consumer == null) {
            throw new IllegalArgumentException("consumer must not be null");
        }
        nativeScreenshot(display, consumer, width, height, minLayer, maxLayer, allLayers);
    }

    private static void checkHeadless() {
        if (HEADLESS) {
            throw new UnsupportedOperationException("Device is headless");
        }
    }
}
