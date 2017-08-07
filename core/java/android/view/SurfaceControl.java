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

import static android.view.WindowManager.LayoutParams.INVALID_WINDOW_TYPE;

import android.graphics.Bitmap;
import android.graphics.GraphicBuffer;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import android.view.Surface.OutOfResourcesException;

import dalvik.system.CloseGuard;

/**
 * SurfaceControl
 *  @hide
 */
public class SurfaceControl {
    private static final String TAG = "SurfaceControl";

    private static native long nativeCreate(SurfaceSession session, String name,
            int w, int h, int format, int flags, long parentObject, int windowType, int ownerUid)
            throws OutOfResourcesException;
    private static native void nativeRelease(long nativeObject);
    private static native void nativeDestroy(long nativeObject);
    private static native void nativeDisconnect(long nativeObject);

    private static native Bitmap nativeScreenshot(IBinder displayToken,
            Rect sourceCrop, int width, int height, int minLayer, int maxLayer,
            boolean allLayers, boolean useIdentityTransform, int rotation);
    private static native GraphicBuffer nativeScreenshotToBuffer(IBinder displayToken,
            Rect sourceCrop, int width, int height, int minLayer, int maxLayer,
            boolean allLayers, boolean useIdentityTransform, int rotation);
    private static native void nativeScreenshot(IBinder displayToken, Surface consumer,
            Rect sourceCrop, int width, int height, int minLayer, int maxLayer,
            boolean allLayers, boolean useIdentityTransform);

    private static native void nativeOpenTransaction();
    private static native void nativeCloseTransaction(boolean sync);
    private static native void nativeSetAnimationTransaction();

    private static native void nativeSetLayer(long nativeObject, int zorder);
    private static native void nativeSetRelativeLayer(long nativeObject, IBinder relativeTo,
            int zorder);
    private static native void nativeSetPosition(long nativeObject, float x, float y);
    private static native void nativeSetGeometryAppliesWithResize(long nativeObject);
    private static native void nativeSetSize(long nativeObject, int w, int h);
    private static native void nativeSetTransparentRegionHint(long nativeObject, Region region);
    private static native void nativeSetAlpha(long nativeObject, float alpha);
    private static native void nativeSetMatrix(long nativeObject, float dsdx, float dtdx,
            float dtdy, float dsdy);
    private static native void nativeSetFlags(long nativeObject, int flags, int mask);
    private static native void nativeSetWindowCrop(long nativeObject, int l, int t, int r, int b);
    private static native void nativeSetFinalCrop(long nativeObject, int l, int t, int r, int b);
    private static native void nativeSetLayerStack(long nativeObject, int layerStack);

    private static native boolean nativeClearContentFrameStats(long nativeObject);
    private static native boolean nativeGetContentFrameStats(long nativeObject, WindowContentFrameStats outStats);
    private static native boolean nativeClearAnimationFrameStats();
    private static native boolean nativeGetAnimationFrameStats(WindowAnimationFrameStats outStats);

    private static native IBinder nativeGetBuiltInDisplay(int physicalDisplayId);
    private static native IBinder nativeCreateDisplay(String name, boolean secure);
    private static native void nativeDestroyDisplay(IBinder displayToken);
    private static native void nativeSetDisplaySurface(
            IBinder displayToken, long nativeSurfaceObject);
    private static native void nativeSetDisplayLayerStack(
            IBinder displayToken, int layerStack);
    private static native void nativeSetDisplayProjection(
            IBinder displayToken, int orientation,
            int l, int t, int r, int b,
            int L, int T, int R, int B);
    private static native void nativeSetDisplaySize(IBinder displayToken, int width, int height);
    private static native SurfaceControl.PhysicalDisplayInfo[] nativeGetDisplayConfigs(
            IBinder displayToken);
    private static native int nativeGetActiveConfig(IBinder displayToken);
    private static native boolean nativeSetActiveConfig(IBinder displayToken, int id);
    private static native int[] nativeGetDisplayColorModes(IBinder displayToken);
    private static native int nativeGetActiveColorMode(IBinder displayToken);
    private static native boolean nativeSetActiveColorMode(IBinder displayToken,
            int colorMode);
    private static native void nativeSetDisplayPowerMode(
            IBinder displayToken, int mode);
    private static native void nativeDeferTransactionUntil(long nativeObject,
            IBinder handle, long frame);
    private static native void nativeDeferTransactionUntilSurface(long nativeObject,
            long surfaceObject, long frame);
    private static native void nativeReparentChildren(long nativeObject,
            IBinder handle);
    private static native void nativeSeverChildren(long nativeObject);
    private static native void nativeSetOverrideScalingMode(long nativeObject,
            int scalingMode);
    private static native IBinder nativeGetHandle(long nativeObject);
    private static native boolean nativeGetTransformToDisplayInverse(long nativeObject);

    private static native Display.HdrCapabilities nativeGetHdrCapabilities(IBinder displayToken);


    private final CloseGuard mCloseGuard = CloseGuard.get();
    private final String mName;
    long mNativeObject; // package visibility only for Surface.java access

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
     * <p>
     *    <code>DEST = SRC + DEST * (1-SRC_ALPHA)</code>
     * <p>
     * By contrast, non pre-multiplied surfaces use the following equation:
     * <p>
     *    <code>DEST = SRC * SRC_ALPHA * DEST * (1-SRC_ALPHA)</code>
     * <p>
     * pre-multiplied surfaces must always be used if transparent pixels are
     * composited on top of each-other into the surface. A pre-multiplied
     * surface can never lower the value of the alpha component of a given
     * pixel.
     * <p>
     * In some rare situations, a non pre-multiplied surface is preferable.
     *
     */
    public static final int NON_PREMULTIPLIED = 0x00000100;

    /**
     * Surface creation flag: Indicates that the surface must be considered opaque,
     * even if its pixel format is set to translucent. This can be useful if an
     * application needs full RGBA 8888 support for instance but will
     * still draw every pixel opaque.
     * <p>
     * This flag is ignored if setAlpha() is used to make the surface non-opaque.
     * Combined effects are (assuming a buffer format with an alpha channel):
     * <ul>
     * <li>OPAQUE + alpha(1.0) == opaque composition
     * <li>OPAQUE + alpha(0.x) == blended composition
     * <li>!OPAQUE + alpha(1.0) == blended composition
     * <li>!OPAQUE + alpha(0.x) == blended composition
     * </ul>
     * If the underlying buffer lacks an alpha channel, the OPAQUE flag is effectively
     * set automatically.
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
     * Surface creation flag: Window represents a cursor glyph.
     */
    public static final int CURSOR_WINDOW = 0x00002000;

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
     * Updates the value set during Surface creation (see {@link #HIDDEN}).
     */
    private static final int SURFACE_HIDDEN = 0x01;

    /**
     * Surface flag: composite without blending when possible.
     * Updates the value set during Surface creation (see {@link #OPAQUE}).
     */
    private static final int SURFACE_OPAQUE = 0x02;


    /* built-in physical display ids (keep in sync with ISurfaceComposer.h)
     * these are different from the logical display ids used elsewhere in the framework */

    /**
     * Built-in physical display id: Main display.
     * Use only with {@link SurfaceControl#getBuiltInDisplay(int)}.
     */
    public static final int BUILT_IN_DISPLAY_ID_MAIN = 0;

    /**
     * Built-in physical display id: Attached HDMI display.
     * Use only with {@link SurfaceControl#getBuiltInDisplay(int)}.
     */
    public static final int BUILT_IN_DISPLAY_ID_HDMI = 1;

    /* Display power modes * /

    /**
     * Display power mode off: used while blanking the screen.
     * Use only with {@link SurfaceControl#setDisplayPowerMode}.
     */
    public static final int POWER_MODE_OFF = 0;

    /**
     * Display power mode doze: used while putting the screen into low power mode.
     * Use only with {@link SurfaceControl#setDisplayPowerMode}.
     */
    public static final int POWER_MODE_DOZE = 1;

    /**
     * Display power mode normal: used while unblanking the screen.
     * Use only with {@link SurfaceControl#setDisplayPowerMode}.
     */
    public static final int POWER_MODE_NORMAL = 2;

    /**
     * Display power mode doze: used while putting the screen into a suspended
     * low power mode.  Use only with {@link SurfaceControl#setDisplayPowerMode}.
     */
    public static final int POWER_MODE_DOZE_SUSPEND = 3;

    /**
     * A value for windowType used to indicate that the window should be omitted from screenshots
     * and display mirroring. A temporary workaround until we express such things with
     * the hierarchy.
     * TODO: b/64227542
     * @hide
     */
    public static final int WINDOW_TYPE_DONT_SCREENSHOT = 441731;

    /**
     * Create a surface with a name.
     * <p>
     * The surface creation flags specify what kind of surface to create and
     * certain options such as whether the surface can be assumed to be opaque
     * and whether it should be initially hidden.  Surfaces should always be
     * created with the {@link #HIDDEN} flag set to ensure that they are not
     * made visible prematurely before all of the surface's properties have been
     * configured.
     * <p>
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
     * @param windowType The type of the window as specified in WindowManager.java.
     * @param ownerUid A unique per-app ID.
     *
     * @throws throws OutOfResourcesException If the SurfaceControl cannot be created.
     */
    public SurfaceControl(SurfaceSession session,
            String name, int w, int h, int format, int flags, int windowType, int ownerUid)
                    throws OutOfResourcesException {
        this(session, name, w, h, format, flags, null, windowType, ownerUid);
    }

    public SurfaceControl(SurfaceSession session,
            String name, int w, int h, int format, int flags)
                    throws OutOfResourcesException {
        this(session, name, w, h, format, flags, null, INVALID_WINDOW_TYPE, Binder.getCallingUid());
    }

    public SurfaceControl(SurfaceSession session, String name, int w, int h, int format, int flags,
            SurfaceControl parent, int windowType, int ownerUid)
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

        mName = name;
        mNativeObject = nativeCreate(session, name, w, h, format, flags,
            parent != null ? parent.mNativeObject : 0, windowType, ownerUid);
        if (mNativeObject == 0) {
            throw new OutOfResourcesException(
                    "Couldn't allocate SurfaceControl native object");
        }

        mCloseGuard.open("release");
    }

    // This is a transfer constructor, useful for transferring a live SurfaceControl native
    // object to another Java wrapper which could have some different behavior, e.g.
    // event logging.
    public SurfaceControl(SurfaceControl other) {
        mName = other.mName;
        mNativeObject = other.mNativeObject;
        other.mCloseGuard.close();
        other.mNativeObject = 0;
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

    /**
     * Disconnect any client still connected to the surface.
     */
    public void disconnect() {
        if (mNativeObject != 0) {
            nativeDisconnect(mNativeObject);
        }
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
        nativeCloseTransaction(false);
    }

    public static void closeTransactionSync() {
        nativeCloseTransaction(true);
    }

    public void deferTransactionUntil(IBinder handle, long frame) {
        if (frame > 0) {
            nativeDeferTransactionUntil(mNativeObject, handle, frame);
        }
    }

    public void deferTransactionUntil(Surface barrier, long frame) {
        if (frame > 0) {
            nativeDeferTransactionUntilSurface(mNativeObject, barrier.mNativeObject, frame);
        }
    }

    public void reparentChildren(IBinder newParentHandle) {
        nativeReparentChildren(mNativeObject, newParentHandle);
    }

    public void detachChildren() {
        nativeSeverChildren(mNativeObject);
    }

    public void setOverrideScalingMode(int scalingMode) {
        checkNotReleased();
        nativeSetOverrideScalingMode(mNativeObject, scalingMode);
    }

    public IBinder getHandle() {
        return nativeGetHandle(mNativeObject);
    }

    /** flag the transaction as an animation */
    public static void setAnimationTransaction() {
        nativeSetAnimationTransaction();
    }

    public void setLayer(int zorder) {
        checkNotReleased();
        nativeSetLayer(mNativeObject, zorder);
    }

    public void setRelativeLayer(IBinder relativeTo, int zorder) {
        checkNotReleased();
        nativeSetRelativeLayer(mNativeObject, relativeTo, zorder);
    }

    public void setPosition(float x, float y) {
        checkNotReleased();
        nativeSetPosition(mNativeObject, x, y);
    }

    /**
     * If the buffer size changes in this transaction, position and crop updates specified
     * in this transaction will not complete until a buffer of the new size
     * arrives. As transform matrix and size are already frozen in this fashion,
     * this enables totally freezing the surface until the resize has completed
     * (at which point the geometry influencing aspects of this transaction will then occur)
     */
    public void setGeometryAppliesWithResize() {
        checkNotReleased();
        nativeSetGeometryAppliesWithResize(mNativeObject);
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

    public boolean clearContentFrameStats() {
        checkNotReleased();
        return nativeClearContentFrameStats(mNativeObject);
    }

    public boolean getContentFrameStats(WindowContentFrameStats outStats) {
        checkNotReleased();
        return nativeGetContentFrameStats(mNativeObject, outStats);
    }

    public static boolean clearAnimationFrameStats() {
        return nativeClearAnimationFrameStats();
    }

    public static boolean getAnimationFrameStats(WindowAnimationFrameStats outStats) {
        return nativeGetAnimationFrameStats(outStats);
    }

    /**
     * Sets an alpha value for the entire Surface.  This value is combined with the
     * per-pixel alpha.  It may be used with opaque Surfaces.
     */
    public void setAlpha(float alpha) {
        checkNotReleased();
        nativeSetAlpha(mNativeObject, alpha);
    }

    public void setMatrix(float dsdx, float dtdx, float dtdy, float dsdy) {
        checkNotReleased();
        nativeSetMatrix(mNativeObject, dsdx, dtdx, dtdy, dsdy);
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

    public void setFinalCrop(Rect crop) {
        checkNotReleased();
        if (crop != null) {
            nativeSetFinalCrop(mNativeObject,
                crop.left, crop.top, crop.right, crop.bottom);
        } else {
            nativeSetFinalCrop(mNativeObject, 0, 0, 0, 0);
        }
    }

    public void setLayerStack(int layerStack) {
        checkNotReleased();
        nativeSetLayerStack(mNativeObject, layerStack);
    }

    /**
     * Sets the opacity of the surface.  Setting the flag is equivalent to creating the
     * Surface with the {@link #OPAQUE} flag.
     */
    public void setOpaque(boolean isOpaque) {
        checkNotReleased();
        if (isOpaque) {
            nativeSetFlags(mNativeObject, SURFACE_OPAQUE, SURFACE_OPAQUE);
        } else {
            nativeSetFlags(mNativeObject, 0, SURFACE_OPAQUE);
        }
    }

    /**
     * Sets the security of the surface.  Setting the flag is equivalent to creating the
     * Surface with the {@link #SECURE} flag.
     */
    public void setSecure(boolean isSecure) {
        checkNotReleased();
        if (isSecure) {
            nativeSetFlags(mNativeObject, SECURE, SECURE);
        } else {
            nativeSetFlags(mNativeObject, 0, SECURE);
        }
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
        public long appVsyncOffsetNanos;
        public long presentationDeadlineNanos;

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
                    && secure == other.secure
                    && appVsyncOffsetNanos == other.appVsyncOffsetNanos
                    && presentationDeadlineNanos == other.presentationDeadlineNanos;
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
            appVsyncOffsetNanos = other.appVsyncOffsetNanos;
            presentationDeadlineNanos = other.presentationDeadlineNanos;
        }

        // For debugging purposes
        @Override
        public String toString() {
            return "PhysicalDisplayInfo{" + width + " x " + height + ", " + refreshRate + " fps, "
                    + "density " + density + ", " + xDpi + " x " + yDpi + " dpi, secure " + secure
                    + ", appVsyncOffset " + appVsyncOffsetNanos
                    + ", bufferDeadline " + presentationDeadlineNanos + "}";
        }
    }

    public static void setDisplayPowerMode(IBinder displayToken, int mode) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        nativeSetDisplayPowerMode(displayToken, mode);
    }

    public static SurfaceControl.PhysicalDisplayInfo[] getDisplayConfigs(IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        return nativeGetDisplayConfigs(displayToken);
    }

    public static int getActiveConfig(IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        return nativeGetActiveConfig(displayToken);
    }

    public static boolean setActiveConfig(IBinder displayToken, int id) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        return nativeSetActiveConfig(displayToken, id);
    }

    public static int[] getDisplayColorModes(IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        return nativeGetDisplayColorModes(displayToken);
    }

    public static int getActiveColorMode(IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        return nativeGetActiveColorMode(displayToken);
    }

    public static boolean setActiveColorMode(IBinder displayToken, int colorMode) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        return nativeSetActiveColorMode(displayToken, colorMode);
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

    public static void setDisplaySize(IBinder displayToken, int width, int height) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be positive");
        }

        nativeSetDisplaySize(displayToken, width, height);
    }

    public static Display.HdrCapabilities getHdrCapabilities(IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        return nativeGetHdrCapabilities(displayToken);
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
     * @param useIdentityTransform Replace whatever transformation (rotation,
     * scaling, translation) the surface layers are currently using with the
     * identity transformation while taking the screenshot.
     */
    public static void screenshot(IBinder display, Surface consumer,
            int width, int height, int minLayer, int maxLayer,
            boolean useIdentityTransform) {
        screenshot(display, consumer, new Rect(), width, height, minLayer, maxLayer,
                false, useIdentityTransform);
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
        screenshot(display, consumer, new Rect(), width, height, 0, 0, true, false);
    }

    /**
     * Copy the current screen contents into the provided {@link Surface}
     *
     * @param display The display to take the screenshot of.
     * @param consumer The {@link Surface} to take the screenshot into.
     */
    public static void screenshot(IBinder display, Surface consumer) {
        screenshot(display, consumer, new Rect(), 0, 0, 0, 0, true, false);
    }

    /**
     * Copy the current screen contents into a bitmap and return it.
     *
     * CAVEAT: Versions of screenshot that return a {@link Bitmap} can
     * be extremely slow; avoid use unless absolutely necessary; prefer
     * the versions that use a {@link Surface} instead, such as
     * {@link SurfaceControl#screenshot(IBinder, Surface)}.
     *
     * @param sourceCrop The portion of the screen to capture into the Bitmap;
     * caller may pass in 'new Rect()' if no cropping is desired.
     * @param width The desired width of the returned bitmap; the raw
     * screen will be scaled down to this size.
     * @param height The desired height of the returned bitmap; the raw
     * screen will be scaled down to this size.
     * @param minLayer The lowest (bottom-most Z order) surface layer to
     * include in the screenshot.
     * @param maxLayer The highest (top-most Z order) surface layer to
     * include in the screenshot.
     * @param useIdentityTransform Replace whatever transformation (rotation,
     * scaling, translation) the surface layers are currently using with the
     * identity transformation while taking the screenshot.
     * @param rotation Apply a custom clockwise rotation to the screenshot, i.e.
     * Surface.ROTATION_0,90,180,270. Surfaceflinger will always take
     * screenshots in its native portrait orientation by default, so this is
     * useful for returning screenshots that are independent of device
     * orientation.
     * @return Returns a Bitmap containing the screen contents, or null
     * if an error occurs. Make sure to call Bitmap.recycle() as soon as
     * possible, once its content is not needed anymore.
     */
    public static Bitmap screenshot(Rect sourceCrop, int width, int height,
            int minLayer, int maxLayer, boolean useIdentityTransform,
            int rotation) {
        // TODO: should take the display as a parameter
        IBinder displayToken = SurfaceControl.getBuiltInDisplay(
                SurfaceControl.BUILT_IN_DISPLAY_ID_MAIN);
        return nativeScreenshot(displayToken, sourceCrop, width, height,
                minLayer, maxLayer, false, useIdentityTransform, rotation);
    }

    /**
     * Like {@link SurfaceControl#screenshot(Rect, int, int, int, int, boolean, int)}
     * but returns a GraphicBuffer.
     */
    public static GraphicBuffer screenshotToBuffer(Rect sourceCrop, int width, int height,
            int minLayer, int maxLayer, boolean useIdentityTransform,
            int rotation) {
        IBinder displayToken = SurfaceControl.getBuiltInDisplay(
                SurfaceControl.BUILT_IN_DISPLAY_ID_MAIN);
        return nativeScreenshotToBuffer(displayToken, sourceCrop, width, height,
                minLayer, maxLayer, false, useIdentityTransform, rotation);
    }

    /**
     * Like {@link SurfaceControl#screenshot(int, int, int, int, boolean)} but
     * includes all Surfaces in the screenshot.
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
        return nativeScreenshot(displayToken, new Rect(), width, height, 0, 0, true,
                false, Surface.ROTATION_0);
    }

    private static void screenshot(IBinder display, Surface consumer, Rect sourceCrop,
            int width, int height, int minLayer, int maxLayer, boolean allLayers,
            boolean useIdentityTransform) {
        if (display == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        if (consumer == null) {
            throw new IllegalArgumentException("consumer must not be null");
        }
        nativeScreenshot(display, consumer, sourceCrop, width, height,
                minLayer, maxLayer, allLayers, useIdentityTransform);
    }
}
