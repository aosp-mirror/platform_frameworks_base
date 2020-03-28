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

import static android.graphics.Matrix.MSCALE_X;
import static android.graphics.Matrix.MSCALE_Y;
import static android.graphics.Matrix.MSKEW_X;
import static android.graphics.Matrix.MSKEW_Y;
import static android.graphics.Matrix.MTRANS_X;
import static android.graphics.Matrix.MTRANS_Y;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;
import static android.view.SurfaceControlProto.HASH_CODE;
import static android.view.SurfaceControlProto.NAME;

import android.annotation.FloatRange;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Size;
import android.compat.annotation.UnsupportedAppUsage;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.GraphicBuffer;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.display.DeviceProductInfo;
import android.hardware.display.DisplayedContentSample;
import android.hardware.display.DisplayedContentSamplingAttributes;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseIntArray;
import android.util.proto.ProtoOutputStream;
import android.view.Surface.OutOfResourcesException;

import com.android.internal.annotations.GuardedBy;

import dalvik.system.CloseGuard;

import libcore.util.NativeAllocationRegistry;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Handle to an on-screen Surface managed by the system compositor. The SurfaceControl is
 * a combination of a buffer source, and metadata about how to display the buffers.
 * By constructing a {@link Surface} from this SurfaceControl you can submit buffers to be
 * composited. Using {@link SurfaceControl.Transaction} you can manipulate various
 * properties of how the buffer will be displayed on-screen. SurfaceControl's are
 * arranged into a scene-graph like hierarchy, and as such any SurfaceControl may have
 * a parent. Geometric properties like transform, crop, and Z-ordering will be inherited
 * from the parent, as if the child were content in the parents buffer stream.
 */
public final class SurfaceControl implements Parcelable {
    private static final String TAG = "SurfaceControl";

    private static native long nativeCreate(SurfaceSession session, String name,
            int w, int h, int format, int flags, long parentObject, Parcel metadata)
            throws OutOfResourcesException;
    private static native long nativeReadFromParcel(Parcel in);
    private static native long nativeCopyFromSurfaceControl(long nativeObject);
    private static native void nativeWriteToParcel(long nativeObject, Parcel out);
    private static native void nativeRelease(long nativeObject);
    private static native void nativeDisconnect(long nativeObject);

    private static native ScreenshotGraphicBuffer nativeScreenshot(IBinder displayToken,
            Rect sourceCrop, int width, int height, boolean useIdentityTransform, int rotation,
            boolean captureSecureLayers);
    private static native ScreenshotGraphicBuffer nativeCaptureLayers(IBinder displayToken,
            long layerObject, Rect sourceCrop, float frameScale, long[] excludeLayerObjects,
            int format);
    private static native long nativeMirrorSurface(long mirrorOfObject);
    private static native long nativeCreateTransaction();
    private static native long nativeGetNativeTransactionFinalizer();
    private static native void nativeApplyTransaction(long transactionObj, boolean sync);
    private static native void nativeMergeTransaction(long transactionObj,
            long otherTransactionObj);
    private static native void nativeSetAnimationTransaction(long transactionObj);
    private static native void nativeSetEarlyWakeup(long transactionObj);

    private static native void nativeSetLayer(long transactionObj, long nativeObject, int zorder);
    private static native void nativeSetRelativeLayer(long transactionObj, long nativeObject,
            long relativeToObject, int zorder);
    private static native void nativeSetPosition(long transactionObj, long nativeObject,
            float x, float y);
    private static native void nativeSetSize(long transactionObj, long nativeObject, int w, int h);
    private static native void nativeSetTransparentRegionHint(long transactionObj,
            long nativeObject, Region region);
    private static native void nativeSetAlpha(long transactionObj, long nativeObject, float alpha);
    private static native void nativeSetMatrix(long transactionObj, long nativeObject,
            float dsdx, float dtdx,
            float dtdy, float dsdy);
    private static native void nativeSetColorTransform(long transactionObj, long nativeObject,
            float[] matrix, float[] translation);
    private static native void nativeSetColorSpaceAgnostic(long transactionObj, long nativeObject,
            boolean agnostic);
    private static native void nativeSetGeometry(long transactionObj, long nativeObject,
            Rect sourceCrop, Rect dest, long orientation);
    private static native void nativeSetColor(long transactionObj, long nativeObject, float[] color);
    private static native void nativeSetFlags(long transactionObj, long nativeObject,
            int flags, int mask);
    private static native void nativeSetFrameRateSelectionPriority(long transactionObj,
            long nativeObject, int priority);
    private static native void nativeSetWindowCrop(long transactionObj, long nativeObject,
            int l, int t, int r, int b);
    private static native void nativeSetCornerRadius(long transactionObj, long nativeObject,
            float cornerRadius);
    private static native void nativeSetBackgroundBlurRadius(long transactionObj, long nativeObject,
            int blurRadius);
    private static native void nativeSetLayerStack(long transactionObj, long nativeObject,
            int layerStack);

    private static native boolean nativeClearContentFrameStats(long nativeObject);
    private static native boolean nativeGetContentFrameStats(long nativeObject, WindowContentFrameStats outStats);
    private static native boolean nativeClearAnimationFrameStats();
    private static native boolean nativeGetAnimationFrameStats(WindowAnimationFrameStats outStats);

    private static native long[] nativeGetPhysicalDisplayIds();
    private static native IBinder nativeGetPhysicalDisplayToken(long physicalDisplayId);
    private static native IBinder nativeCreateDisplay(String name, boolean secure);
    private static native void nativeDestroyDisplay(IBinder displayToken);
    private static native void nativeSetDisplaySurface(long transactionObj,
            IBinder displayToken, long nativeSurfaceObject);
    private static native void nativeSetDisplayLayerStack(long transactionObj,
            IBinder displayToken, int layerStack);
    private static native void nativeSetDisplayProjection(long transactionObj,
            IBinder displayToken, int orientation,
            int l, int t, int r, int b,
            int L, int T, int R, int B);
    private static native void nativeSetDisplaySize(long transactionObj, IBinder displayToken,
            int width, int height);
    private static native SurfaceControl.DisplayInfo nativeGetDisplayInfo(IBinder displayToken);
    private static native SurfaceControl.DisplayConfig[] nativeGetDisplayConfigs(
            IBinder displayToken);
    private static native DisplayedContentSamplingAttributes
            nativeGetDisplayedContentSamplingAttributes(IBinder displayToken);
    private static native boolean nativeSetDisplayedContentSamplingEnabled(IBinder displayToken,
            boolean enable, int componentMask, int maxFrames);
    private static native DisplayedContentSample nativeGetDisplayedContentSample(
            IBinder displayToken, long numFrames, long timestamp);
    private static native int nativeGetActiveConfig(IBinder displayToken);
    private static native boolean nativeSetDesiredDisplayConfigSpecs(IBinder displayToken,
            SurfaceControl.DesiredDisplayConfigSpecs desiredDisplayConfigSpecs);
    private static native SurfaceControl.DesiredDisplayConfigSpecs
            nativeGetDesiredDisplayConfigSpecs(IBinder displayToken);
    private static native int[] nativeGetDisplayColorModes(IBinder displayToken);
    private static native SurfaceControl.DisplayPrimaries nativeGetDisplayNativePrimaries(
            IBinder displayToken);
    private static native int[] nativeGetCompositionDataspaces();
    private static native int nativeGetActiveColorMode(IBinder displayToken);
    private static native boolean nativeSetActiveColorMode(IBinder displayToken,
            int colorMode);
    private static native void nativeSetAutoLowLatencyMode(IBinder displayToken, boolean on);
    private static native void nativeSetGameContentType(IBinder displayToken, boolean on);
    private static native void nativeSetDisplayPowerMode(
            IBinder displayToken, int mode);
    private static native void nativeDeferTransactionUntil(long transactionObj, long nativeObject,
            long barrierObject, long frame);
    private static native void nativeDeferTransactionUntilSurface(long transactionObj,
            long nativeObject,
            long surfaceObject, long frame);
    private static native void nativeReparentChildren(long transactionObj, long nativeObject,
            long newParentObject);
    private static native void nativeReparent(long transactionObj, long nativeObject,
            long newParentNativeObject);
    private static native void nativeSeverChildren(long transactionObj, long nativeObject);
    private static native void nativeSetOverrideScalingMode(long transactionObj, long nativeObject,
            int scalingMode);

    private static native Display.HdrCapabilities nativeGetHdrCapabilities(IBinder displayToken);

    private static native boolean nativeGetAutoLowLatencyModeSupport(IBinder displayToken);
    private static native boolean nativeGetGameContentTypeSupport(IBinder displayToken);

    private static native void nativeSetInputWindowInfo(long transactionObj, long nativeObject,
            InputWindowHandle handle);

    private static native boolean nativeGetProtectedContentSupport();
    private static native void nativeSetMetadata(long transactionObj, long nativeObject, int key,
            Parcel data);
    private static native void nativeSyncInputWindows(long transactionObj);
    private static native boolean nativeGetDisplayBrightnessSupport(IBinder displayToken);
    private static native boolean nativeSetDisplayBrightness(IBinder displayToken,
            float brightness);
    private static native long nativeReadTransactionFromParcel(Parcel in);
    private static native void nativeWriteTransactionToParcel(long nativeObject, Parcel out);
    private static native void nativeSetShadowRadius(long transactionObj, long nativeObject,
            float shadowRadius);
    private static native void nativeSetGlobalShadowSettings(@Size(4) float[] ambientColor,
            @Size(4) float[] spotColor, float lightPosY, float lightPosZ, float lightRadius);

    private static native void nativeSetFrameRate(
            long transactionObj, long nativeObject, float frameRate, int compatibility);

    private final CloseGuard mCloseGuard = CloseGuard.get();
    private String mName;
    /**
     * @hide
     */
    public long mNativeObject;

    // TODO: Move this to native.
    private final Object mSizeLock = new Object();
    @GuardedBy("mSizeLock")
    private int mWidth;
    @GuardedBy("mSizeLock")
    private int mHeight;

    static Transaction sGlobalTransaction;
    static long sTransactionNestCount = 0;

    /* flags used in constructor (keep in sync with ISurfaceComposerClient.h) */

    /**
     * Surface creation flag: Surface is created hidden
     * @hide
     */
    @UnsupportedAppUsage
    public static final int HIDDEN = 0x00000004;

    /**
     * Surface creation flag: The surface contains secure content, special
     * measures will be taken to disallow the surface's content to be copied
     * from another process. In particular, screenshots and VNC servers will
     * be disabled, but other measures can take place, for instance the
     * surface might not be hardware accelerated.
     * @hide
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
     * @hide
     */
    public static final int NON_PREMULTIPLIED = 0x00000100;

    /**
     * Surface creation flag: Indicates that the surface must be considered opaque,
     * even if its pixel format contains an alpha channel. This can be useful if an
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
     * @hide
     */
    public static final int OPAQUE = 0x00000400;

    /**
     * Surface creation flag: Application requires a hardware-protected path to an
     * external display sink. If a hardware-protected path is not available,
     * then this surface will not be displayed on the external sink.
     *
     * @hide
     */
    public static final int PROTECTED_APP = 0x00000800;

    // 0x1000 is reserved for an independent DRM protected flag in framework

    /**
     * Surface creation flag: Window represents a cursor glyph.
     * @hide
     */
    public static final int CURSOR_WINDOW = 0x00002000;

    /**
     * Surface creation flag: Creates a normal surface.
     * This is the default.
     *
     * @hide
     */
    public static final int FX_SURFACE_NORMAL   = 0x00000000;

    /**
     * Surface creation flag: Creates a effect surface which
     * represents a solid color and or shadows.
     *
     * @hide
     */
    public static final int FX_SURFACE_EFFECT = 0x00020000;

    /**
     * Surface creation flag: Creates a container surface.
     * This surface will have no buffers and will only be used
     * as a container for other surfaces, or for its InputInfo.
     * @hide
     */
    public static final int FX_SURFACE_CONTAINER = 0x00080000;

    /**
     * @hide
     */
    public static final int FX_SURFACE_BLAST = 0x00040000;

    /**
     * Mask used for FX values above.
     *
     * @hide
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

    // Display power modes.
    /**
     * Display power mode off: used while blanking the screen.
     * Use only with {@link SurfaceControl#setDisplayPowerMode}.
     * @hide
     */
    public static final int POWER_MODE_OFF = 0;

    /**
     * Display power mode doze: used while putting the screen into low power mode.
     * Use only with {@link SurfaceControl#setDisplayPowerMode}.
     * @hide
     */
    public static final int POWER_MODE_DOZE = 1;

    /**
     * Display power mode normal: used while unblanking the screen.
     * Use only with {@link SurfaceControl#setDisplayPowerMode}.
     * @hide
     */
    public static final int POWER_MODE_NORMAL = 2;

    /**
     * Display power mode doze: used while putting the screen into a suspended
     * low power mode.  Use only with {@link SurfaceControl#setDisplayPowerMode}.
     * @hide
     */
    public static final int POWER_MODE_DOZE_SUSPEND = 3;

    /**
     * Display power mode on: used while putting the screen into a suspended
     * full power mode.  Use only with {@link SurfaceControl#setDisplayPowerMode}.
     * @hide
     */
    public static final int POWER_MODE_ON_SUSPEND = 4;

    /**
     * A value for windowType used to indicate that the window should be omitted from screenshots
     * and display mirroring. A temporary workaround until we express such things with
     * the hierarchy.
     * TODO: b/64227542
     * @hide
     */
    public static final int WINDOW_TYPE_DONT_SCREENSHOT = 441731;

    /**
     * internal representation of how to interpret pixel value, used only to convert to ColorSpace.
     */
    private static final int INTERNAL_DATASPACE_SRGB = 142671872;
    private static final int INTERNAL_DATASPACE_DISPLAY_P3 = 143261696;
    private static final int INTERNAL_DATASPACE_SCRGB = 411107328;

    private void assignNativeObject(long nativeObject) {
        if (mNativeObject != 0) {
            release();
        }
      	if (nativeObject != 0) {
            mCloseGuard.open("release");
        }
        mNativeObject = nativeObject;
    }

    /**
     * @hide
     */
    public void copyFrom(SurfaceControl other) {
        mName = other.mName;
        mWidth = other.mWidth;
        mHeight = other.mHeight;
        assignNativeObject(nativeCopyFromSurfaceControl(other.mNativeObject));
    }

    /**
     * owner UID.
     * @hide
     */
    public static final int METADATA_OWNER_UID = 1;

    /**
     * Window type as per {@link WindowManager.LayoutParams}.
     * @hide
     */
    public static final int METADATA_WINDOW_TYPE = 2;

    /**
     * Task id to allow association between surfaces and task.
     * @hide
     */
    public static final int METADATA_TASK_ID = 3;

    /**
     * The style of mouse cursor and hotspot.
     * @hide
     */
    public static final int METADATA_MOUSE_CURSOR = 4;

    /**
     * Accessibility ID to allow association between surfaces and accessibility tree.
     * @hide
     */
    public static final int METADATA_ACCESSIBILITY_ID = 5;

    /**
     * A wrapper around GraphicBuffer that contains extra information about how to
     * interpret the screenshot GraphicBuffer.
     * @hide
     */
    public static class ScreenshotGraphicBuffer {
        private final GraphicBuffer mGraphicBuffer;
        private final ColorSpace mColorSpace;
        private final boolean mContainsSecureLayers;

        public ScreenshotGraphicBuffer(GraphicBuffer graphicBuffer, ColorSpace colorSpace,
                boolean containsSecureLayers) {
            mGraphicBuffer = graphicBuffer;
            mColorSpace = colorSpace;
            mContainsSecureLayers = containsSecureLayers;
        }

       /**
        * Create ScreenshotGraphicBuffer from existing native GraphicBuffer object.
        * @param width The width in pixels of the buffer
        * @param height The height in pixels of the buffer
        * @param format The format of each pixel as specified in {@link PixelFormat}
        * @param usage Hint indicating how the buffer will be used
        * @param unwrappedNativeObject The native object of GraphicBuffer
        * @param namedColorSpace Integer value of a named color space {@link ColorSpace.Named}
        * @param containsSecureLayer Indicates whether this graphic buffer contains captured contents
        *        of secure layers, in which case the screenshot should not be persisted.
        */
        private static ScreenshotGraphicBuffer createFromNative(int width, int height, int format,
                int usage, long unwrappedNativeObject, int namedColorSpace,
                boolean containsSecureLayers) {
            GraphicBuffer graphicBuffer = GraphicBuffer.createFromExisting(width, height, format,
                    usage, unwrappedNativeObject);
            ColorSpace colorSpace = ColorSpace.get(ColorSpace.Named.values()[namedColorSpace]);
            return new ScreenshotGraphicBuffer(graphicBuffer, colorSpace, containsSecureLayers);
        }

        public ColorSpace getColorSpace() {
            return mColorSpace;
        }

        public GraphicBuffer getGraphicBuffer() {
            return mGraphicBuffer;
        }

        public boolean containsSecureLayers() {
            return mContainsSecureLayers;
        }
    }

    /**
     * Builder class for {@link SurfaceControl} objects.
     *
     * By default the surface will be hidden, and have "unset" bounds, meaning it can
     * be as large as the bounds of its parent if a buffer or child so requires.
     *
     * It is necessary to set at least a name via {@link Builder#setName}
     */
    public static class Builder {
        private SurfaceSession mSession;
        private int mFlags = HIDDEN;
        private int mWidth;
        private int mHeight;
        private int mFormat = PixelFormat.OPAQUE;
        private String mName;
        private SurfaceControl mParent;
        private SparseIntArray mMetadata;

        /**
         * Begin building a SurfaceControl with a given {@link SurfaceSession}.
         *
         * @param session The {@link SurfaceSession} with which to eventually construct the surface.
         * @hide
         */
        public Builder(SurfaceSession session) {
            mSession = session;
        }

        /**
         * Begin building a SurfaceControl.
         */
        public Builder() {
        }

        /**
         * Construct a new {@link SurfaceControl} with the set parameters. The builder
         * remains valid.
         */
        @NonNull
        public SurfaceControl build() {
            if (mWidth < 0 || mHeight < 0) {
                throw new IllegalStateException(
                        "width and height must be positive or unset");
            }
            if ((mWidth > 0 || mHeight > 0) && (isColorLayerSet() || isContainerLayerSet())) {
                throw new IllegalStateException(
                        "Only buffer layers can set a valid buffer size.");
            }
            return new SurfaceControl(
                    mSession, mName, mWidth, mHeight, mFormat, mFlags, mParent, mMetadata);
        }

        /**
         * Set a debugging-name for the SurfaceControl.
         *
         * @param name A name to identify the Surface in debugging.
         */
        @NonNull
        public Builder setName(@NonNull String name) {
            mName = name;
            return this;
        }

        /**
         * Set the initial size of the controlled surface's buffers in pixels.
         *
         * @param width The buffer width in pixels.
         * @param height The buffer height in pixels.
         */
        @NonNull
        public Builder setBufferSize(@IntRange(from = 0) int width,
                @IntRange(from = 0) int height) {
            if (width < 0 || height < 0) {
                throw new IllegalArgumentException(
                        "width and height must be positive");
            }
            mWidth = width;
            mHeight = height;
            // set this as a buffer layer since we are specifying a buffer size.
            return setFlags(FX_SURFACE_NORMAL, FX_SURFACE_MASK);
        }

        /**
         * Set the initial size of the controlled surface's buffers in pixels.
         */
        private void unsetBufferSize() {
            mWidth = 0;
            mHeight = 0;
        }

        /**
         * Set the pixel format of the controlled surface's buffers, using constants from
         * {@link android.graphics.PixelFormat}.
         */
        @NonNull
        public Builder setFormat(@PixelFormat.Format int format) {
            mFormat = format;
            return this;
        }

        /**
         * Specify if the app requires a hardware-protected path to
         * an external display sync. If protected content is enabled, but
         * such a path is not available, then the controlled Surface will
         * not be displayed.
         *
         * @param protectedContent Whether to require a protected sink.
         * @hide
         */
        @NonNull
        public Builder setProtected(boolean protectedContent) {
            if (protectedContent) {
                mFlags |= PROTECTED_APP;
            } else {
                mFlags &= ~PROTECTED_APP;
            }
            return this;
        }

        /**
         * Specify whether the Surface contains secure content. If true, the system
         * will prevent the surfaces content from being copied by another process. In
         * particular screenshots and VNC servers will be disabled. This is however
         * not a complete prevention of readback as {@link #setProtected}.
         * @hide
         */
        @NonNull
        public Builder setSecure(boolean secure) {
            if (secure) {
                mFlags |= SECURE;
            } else {
                mFlags &= ~SECURE;
            }
            return this;
        }

        /**
         * Indicates whether the surface must be considered opaque,
         * even if its pixel format is set to translucent. This can be useful if an
         * application needs full RGBA 8888 support for instance but will
         * still draw every pixel opaque.
         * <p>
         * This flag only determines whether opacity will be sampled from the alpha channel.
         * Plane-alpha from calls to setAlpha() can still result in blended composition
         * regardless of the opaque setting.
         *
         * Combined effects are (assuming a buffer format with an alpha channel):
         * <ul>
         * <li>OPAQUE + alpha(1.0) == opaque composition
         * <li>OPAQUE + alpha(0.x) == blended composition
         * <li>OPAQUE + alpha(0.0) == no composition
         * <li>!OPAQUE + alpha(1.0) == blended composition
         * <li>!OPAQUE + alpha(0.x) == blended composition
         * <li>!OPAQUE + alpha(0.0) == no composition
         * </ul>
         * If the underlying buffer lacks an alpha channel, it is as if setOpaque(true)
         * were set automatically.
         * @param opaque Whether the Surface is OPAQUE.
         */
        @NonNull
        public Builder setOpaque(boolean opaque) {
            if (opaque) {
                mFlags |= OPAQUE;
            } else {
                mFlags &= ~OPAQUE;
            }
            return this;
        }

        /**
         * Set the initial visibility for the SurfaceControl.
         *
         * @param hidden Whether the Surface is initially HIDDEN.
         * @hide
         */
        @NonNull
        public Builder setHidden(boolean hidden) {
            if (hidden) {
                mFlags |= HIDDEN;
            } else {
                mFlags &= ~HIDDEN;
            }
            return this;
        }

        /**
         * Set a parent surface for our new SurfaceControl.
         *
         * Child surfaces are constrained to the onscreen region of their parent.
         * Furthermore they stack relatively in Z order, and inherit the transformation
         * of the parent.
         *
         * @param parent The parent control.
         */
        @NonNull
        public Builder setParent(@Nullable SurfaceControl parent) {
            mParent = parent;
            return this;
        }

        /**
         * Sets a metadata int.
         *
         * @param key metadata key
         * @param data associated data
         * @hide
         */
        public Builder setMetadata(int key, int data) {
            if (mMetadata == null) {
                mMetadata = new SparseIntArray();
            }
            mMetadata.put(key, data);
            return this;
        }

        /**
         * Indicate whether a 'ColorLayer' is to be constructed.
         *
         * Color layers will not have an associated BufferQueue and will instead always render a
         * solid color (that is, solid before plane alpha). Currently that color is black.
         *
         * @hide
         */
        public Builder setColorLayer() {
            unsetBufferSize();
            return setFlags(FX_SURFACE_EFFECT, FX_SURFACE_MASK);
        }

        private boolean isColorLayerSet() {
            return  (mFlags & FX_SURFACE_EFFECT) == FX_SURFACE_EFFECT;
        }

        /**
         * @hide
         */
        public Builder setBLASTLayer() {
            unsetBufferSize();
            return setFlags(FX_SURFACE_BLAST, FX_SURFACE_MASK);
        }

        /**
         * Indicates whether a 'ContainerLayer' is to be constructed.
         *
         * Container layers will not be rendered in any fashion and instead are used
         * as a parent of renderable layers.
         *
         * @hide
         */
        public Builder setContainerLayer() {
            unsetBufferSize();
            return setFlags(FX_SURFACE_CONTAINER, FX_SURFACE_MASK);
        }

        private boolean isContainerLayerSet() {
            return  (mFlags & FX_SURFACE_CONTAINER) == FX_SURFACE_CONTAINER;
        }

        /**
         * Set 'Surface creation flags' such as {@link #HIDDEN}, {@link #SECURE}.
         *
         * TODO: Finish conversion to individual builder methods?
         * @param flags The combined flags
         * @hide
         */
        public Builder setFlags(int flags) {
            mFlags = flags;
            return this;
        }

        private Builder setFlags(int flags, int mask) {
            mFlags = (mFlags & ~mask) | flags;
            return this;
        }
    }

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
     * and position, call {@link Transaction#show(SurfaceControl)} if appropriate, and close the
     * transaction.
     * <p>
     * Bounds of the surface is determined by its crop and its buffer size. If the
     * surface has no buffer or crop, the surface is boundless and only constrained
     * by the size of its parent bounds.
     *
     * @param session  The surface session, must not be null.
     * @param name     The surface name, must not be null.
     * @param w        The surface initial width.
     * @param h        The surface initial height.
     * @param flags    The surface creation flags.
     * @param metadata Initial metadata.
     * @throws throws OutOfResourcesException If the SurfaceControl cannot be created.
     */
    private SurfaceControl(SurfaceSession session, String name, int w, int h, int format, int flags,
            SurfaceControl parent, SparseIntArray metadata)
                    throws OutOfResourcesException, IllegalArgumentException {
        if (name == null) {
            throw new IllegalArgumentException("name must not be null");
        }

        mName = name;
        mWidth = w;
        mHeight = h;
        Parcel metaParcel = Parcel.obtain();
        try {
            if (metadata != null && metadata.size() > 0) {
                metaParcel.writeInt(metadata.size());
                for (int i = 0; i < metadata.size(); ++i) {
                    metaParcel.writeInt(metadata.keyAt(i));
                    metaParcel.writeByteArray(
                            ByteBuffer.allocate(4).order(ByteOrder.nativeOrder())
                                    .putInt(metadata.valueAt(i)).array());
                }
                metaParcel.setDataPosition(0);
            }
            mNativeObject = nativeCreate(session, name, w, h, format, flags,
                    parent != null ? parent.mNativeObject : 0, metaParcel);
        } finally {
            metaParcel.recycle();
        }
        if (mNativeObject == 0) {
            throw new OutOfResourcesException(
                    "Couldn't allocate SurfaceControl native object");
        }

        mCloseGuard.open("release");
    }

    /** This is a transfer constructor, useful for transferring a live SurfaceControl native
     * object to another Java wrapper which could have some different behavior, e.g.
     * event logging.
     * @hide
     */
    public SurfaceControl(SurfaceControl other) {
        mName = other.mName;
        mWidth = other.mWidth;
        mHeight = other.mHeight;
        mNativeObject = other.mNativeObject;
        other.mCloseGuard.close();
        other.mNativeObject = 0;
        mCloseGuard.open("release");
    }

    private SurfaceControl(Parcel in) {
        readFromParcel(in);
    }

    /**
     * @hide
     */
    public SurfaceControl() {
    }

    public void readFromParcel(Parcel in) {
        if (in == null) {
            throw new IllegalArgumentException("source must not be null");
        }

        mName = in.readString();
        mWidth = in.readInt();
        mHeight = in.readInt();

        long object = 0;
        if (in.readInt() != 0) {
            object = nativeReadFromParcel(in);
        }
        assignNativeObject(object);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mName);
        dest.writeInt(mWidth);
        dest.writeInt(mHeight);
        if (mNativeObject == 0) {
            dest.writeInt(0);
        } else {
            dest.writeInt(1);
        }
        nativeWriteToParcel(mNativeObject, dest);

        if ((flags & Parcelable.PARCELABLE_WRITE_RETURN_VALUE) != 0) {
            release();
        }
    }

    /**
     * Write to a protocol buffer output stream. Protocol buffer message definition is at {@link
     * android.view.SurfaceControlProto}.
     *
     * @param proto Stream to write the SurfaceControl object to.
     * @param fieldId Field Id of the SurfaceControl as defined in the parent message.
     * @hide
     */
    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(HASH_CODE, System.identityHashCode(this));
        proto.write(NAME, mName);
        proto.end(token);
    }

    public static final @android.annotation.NonNull Creator<SurfaceControl> CREATOR
            = new Creator<SurfaceControl>() {
        public SurfaceControl createFromParcel(Parcel in) {
            return new SurfaceControl(in);
        }

        public SurfaceControl[] newArray(int size) {
            return new SurfaceControl[size];
        }
    };

    /**
     * @hide
     */
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

    /**
     * Release the local reference to the server-side surface. The surface
     * may continue to exist on-screen as long as its parent continues
     * to exist. To explicitly remove a surface from the screen use
     * {@link Transaction#reparent} with a null-parent. After release,
     * {@link #isValid} will return false and other methods will throw
     * an exception.
     *
     * Always call release() when you're done with a SurfaceControl.
     */
    public void release() {
        if (mNativeObject != 0) {
            nativeRelease(mNativeObject);
            mNativeObject = 0;
            mCloseGuard.close();
        }
    }

    /**
     * Disconnect any client still connected to the surface.
     * @hide
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

    /**
     * Check whether this instance points to a valid layer with the system-compositor. For
     * example this may be false if construction failed, or the layer was released
     * ({@link #release}).
     *
     * @return Whether this SurfaceControl is valid.
     */
    public boolean isValid() {
        return mNativeObject != 0;
    }

    /*
     * set surface parameters.
     * needs to be inside open/closeTransaction block
     */

    /** start a transaction
     * @hide
     */
    @UnsupportedAppUsage
    public static void openTransaction() {
        synchronized (SurfaceControl.class) {
            if (sGlobalTransaction == null) {
                sGlobalTransaction = new Transaction();
            }
            synchronized(SurfaceControl.class) {
                sTransactionNestCount++;
            }
        }
    }

    /**
     * Merge the supplied transaction in to the deprecated "global" transaction.
     * This clears the supplied transaction in an identical fashion to {@link Transaction#merge}.
     * <p>
     * This is a utility for interop with legacy-code and will go away with the Global Transaction.
     * @hide
     */
    @Deprecated
    public static void mergeToGlobalTransaction(Transaction t) {
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.merge(t);
        }
    }

    /** end a transaction
     * @hide
     */
    @UnsupportedAppUsage
    public static void closeTransaction() {
        synchronized(SurfaceControl.class) {
            if (sTransactionNestCount == 0) {
                Log.e(TAG,
                        "Call to SurfaceControl.closeTransaction without matching openTransaction");
            } else if (--sTransactionNestCount > 0) {
                return;
            }
            sGlobalTransaction.apply();
        }
    }

    /**
     * @hide
     */
    public void deferTransactionUntil(SurfaceControl barrier, long frame) {
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.deferTransactionUntil(this, barrier, frame);
        }
    }

    /**
     * @hide
     */
    public void reparentChildren(SurfaceControl newParent) {
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.reparentChildren(this, newParent);
        }
    }

    /**
     * @hide
     */
    public void detachChildren() {
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.detachChildren(this);
        }
    }

    /**
     * @hide
     */
    public void setOverrideScalingMode(int scalingMode) {
        checkNotReleased();
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.setOverrideScalingMode(this, scalingMode);
        }
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public void setLayer(int zorder) {
        checkNotReleased();
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.setLayer(this, zorder);
        }
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public void setPosition(float x, float y) {
        checkNotReleased();
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.setPosition(this, x, y);
        }
    }

    /**
     * @hide
     */
    public void setBufferSize(int w, int h) {
        checkNotReleased();
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.setBufferSize(this, w, h);
        }
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public void hide() {
        checkNotReleased();
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.hide(this);
        }
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public void show() {
        checkNotReleased();
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.show(this);
        }
    }

    /**
     * @hide
     */
    public void setTransparentRegionHint(Region region) {
        checkNotReleased();
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.setTransparentRegionHint(this, region);
        }
    }

    /**
     * @hide
     */
    public boolean clearContentFrameStats() {
        checkNotReleased();
        return nativeClearContentFrameStats(mNativeObject);
    }

    /**
     * @hide
     */
    public boolean getContentFrameStats(WindowContentFrameStats outStats) {
        checkNotReleased();
        return nativeGetContentFrameStats(mNativeObject, outStats);
    }

    /**
     * @hide
     */
    public static boolean clearAnimationFrameStats() {
        return nativeClearAnimationFrameStats();
    }

    /**
     * @hide
     */
    public static boolean getAnimationFrameStats(WindowAnimationFrameStats outStats) {
        return nativeGetAnimationFrameStats(outStats);
    }

    /**
     * @hide
     */
    public void setAlpha(float alpha) {
        checkNotReleased();
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.setAlpha(this, alpha);
        }
    }

    /**
     * @hide
     */
    public void setMatrix(float dsdx, float dtdx, float dtdy, float dsdy) {
        checkNotReleased();
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.setMatrix(this, dsdx, dtdx, dtdy, dsdy);
        }
    }

    /**
     * Sets the Surface to be color space agnostic. If a surface is color space agnostic,
     * the color can be interpreted in any color space.
     * @param agnostic A boolean to indicate whether the surface is color space agnostic
     * @hide
     */
    public void setColorSpaceAgnostic(boolean agnostic) {
        checkNotReleased();
        synchronized (SurfaceControl.class) {
            sGlobalTransaction.setColorSpaceAgnostic(this, agnostic);
        }
    }

    /**
     * Bounds the surface and its children to the bounds specified. Size of the surface will be
     * ignored and only the crop and buffer size will be used to determine the bounds of the
     * surface. If no crop is specified and the surface has no buffer, the surface bounds is only
     * constrained by the size of its parent bounds.
     *
     * @param crop Bounds of the crop to apply.
     * @hide
     */
    public void setWindowCrop(Rect crop) {
        checkNotReleased();
        synchronized (SurfaceControl.class) {
            sGlobalTransaction.setWindowCrop(this, crop);
        }
    }

    /**
     * @hide
     */
    public void setOpaque(boolean isOpaque) {
        checkNotReleased();

        synchronized (SurfaceControl.class) {
            sGlobalTransaction.setOpaque(this, isOpaque);
        }
    }

    /**
     * @hide
     */
    public void setSecure(boolean isSecure) {
        checkNotReleased();

        synchronized (SurfaceControl.class) {
            sGlobalTransaction.setSecure(this, isSecure);
        }
    }

    /**
     * @hide
     */
    public int getWidth() {
        synchronized (mSizeLock) {
            return mWidth;
        }
    }

    /**
     * @hide
     */
    public int getHeight() {
        synchronized (mSizeLock) {
            return mHeight;
        }
    }

    @Override
    public String toString() {
        return "Surface(name=" + mName + ")/@0x" +
                Integer.toHexString(System.identityHashCode(this));
    }

    /**
     * Immutable information about physical display.
     *
     * @hide
     */
    public static final class DisplayInfo {
        public boolean isInternal;
        public float density;
        public boolean secure;
        public DeviceProductInfo deviceProductInfo;

        @Override
        public String toString() {
            return "DisplayInfo{isInternal=" + isInternal
                    + ", density=" + density
                    + ", secure=" + secure
                    + ", deviceProductInfo=" + deviceProductInfo + "}";
        }
    }

    /**
     * Configuration supported by physical display.
     *
     * @hide
     */
    public static final class DisplayConfig {
        /**
         * Invalid display config id.
         */
        public static final int INVALID_DISPLAY_CONFIG_ID = -1;

        public int width;
        public int height;
        public float xDpi;
        public float yDpi;

        public float refreshRate;
        public long appVsyncOffsetNanos;
        public long presentationDeadlineNanos;

        /**
         * The config group ID this config is associated to.
         * Configs in the same group are similar from vendor's perspective and switching between
         * configs within the same group can be done seamlessly in most cases.
         * @see: android.hardware.graphics.composer@2.4::IComposerClient::Attribute::CONFIG_GROUP
         */
        public int configGroup;

        @Override
        public String toString() {
            return "DisplayConfig{width=" + width
                    + ", height=" + height
                    + ", xDpi=" + xDpi
                    + ", yDpi=" + yDpi
                    + ", refreshRate=" + refreshRate
                    + ", appVsyncOffsetNanos=" + appVsyncOffsetNanos
                    + ", presentationDeadlineNanos=" + presentationDeadlineNanos
                    + ", configGroup=" + configGroup + "}";
        }
    }

    /**
     * @hide
     */
    public static void setDisplayPowerMode(IBinder displayToken, int mode) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        nativeSetDisplayPowerMode(displayToken, mode);
    }

    /**
     * @hide
     */
    public static SurfaceControl.DisplayInfo getDisplayInfo(IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        return nativeGetDisplayInfo(displayToken);
    }

    /**
     * @hide
     */
    public static SurfaceControl.DisplayConfig[] getDisplayConfigs(IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        return nativeGetDisplayConfigs(displayToken);
    }

    /**
     * @hide
     */
    public static int getActiveConfig(IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        return nativeGetActiveConfig(displayToken);
    }

    /**
     * @hide
     */
    public static DisplayedContentSamplingAttributes getDisplayedContentSamplingAttributes(
            IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        return nativeGetDisplayedContentSamplingAttributes(displayToken);
    }

    /**
     * @hide
     */
    public static boolean setDisplayedContentSamplingEnabled(
            IBinder displayToken, boolean enable, int componentMask, int maxFrames) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        final int maxColorComponents = 4;
        if ((componentMask >> maxColorComponents) != 0) {
            throw new IllegalArgumentException("invalid componentMask when enabling sampling");
        }
        return nativeSetDisplayedContentSamplingEnabled(
                displayToken, enable, componentMask, maxFrames);
    }

    /**
     * @hide
     */
    public static DisplayedContentSample getDisplayedContentSample(
            IBinder displayToken, long maxFrames, long timestamp) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        return nativeGetDisplayedContentSample(displayToken, maxFrames, timestamp);
    }


    /**
     * Contains information about desired display configuration.
     *
     * @hide
     */
    public static final class DesiredDisplayConfigSpecs {
        public int defaultConfig;
        public float minRefreshRate;
        public float maxRefreshRate;

        public DesiredDisplayConfigSpecs() {}

        public DesiredDisplayConfigSpecs(DesiredDisplayConfigSpecs other) {
            copyFrom(other);
        }

        public DesiredDisplayConfigSpecs(
                int defaultConfig, float minRefreshRate, float maxRefreshRate) {
            this.defaultConfig = defaultConfig;
            this.minRefreshRate = minRefreshRate;
            this.maxRefreshRate = maxRefreshRate;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof DesiredDisplayConfigSpecs && equals((DesiredDisplayConfigSpecs) o);
        }

        /**
         * Tests for equality.
         */
        public boolean equals(DesiredDisplayConfigSpecs other) {
            return other != null && defaultConfig == other.defaultConfig
                    && minRefreshRate == other.minRefreshRate
                    && maxRefreshRate == other.maxRefreshRate;
        }

        @Override
        public int hashCode() {
            return 0; // don't care
        }

        /**
         * Copies the supplied object's values to this object.
         */
        public void copyFrom(DesiredDisplayConfigSpecs other) {
            defaultConfig = other.defaultConfig;
            minRefreshRate = other.minRefreshRate;
            maxRefreshRate = other.maxRefreshRate;
        }

        @Override
        public String toString() {
            return String.format("defaultConfig=%d min=%.0f max=%.0f", defaultConfig,
                    minRefreshRate, maxRefreshRate);
        }
    }

    /**
     * @hide
     */
    public static boolean setDesiredDisplayConfigSpecs(IBinder displayToken,
            SurfaceControl.DesiredDisplayConfigSpecs desiredDisplayConfigSpecs) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }

        return nativeSetDesiredDisplayConfigSpecs(displayToken, desiredDisplayConfigSpecs);
    }

    /**
     * @hide
     */
    public static SurfaceControl.DesiredDisplayConfigSpecs getDesiredDisplayConfigSpecs(
            IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }

        return nativeGetDesiredDisplayConfigSpecs(displayToken);
    }

    /**
     * @hide
     */
    public static int[] getDisplayColorModes(IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        return nativeGetDisplayColorModes(displayToken);
    }

    /**
     * Color coordinates in CIE1931 XYZ color space
     *
     * @hide
     */
    public static final class CieXyz {
        /**
         * @hide
         */
        public float X;

        /**
         * @hide
         */
        public float Y;

        /**
         * @hide
         */
        public float Z;
    }

    /**
     * Contains a display's color primaries
     *
     * @hide
     */
    public static final class DisplayPrimaries {
        /**
         * @hide
         */
        public CieXyz red;

        /**
         * @hide
         */
        public CieXyz green;

        /**
         * @hide
         */
        public CieXyz blue;

        /**
         * @hide
         */
        public CieXyz white;

        /**
         * @hide
         */
        public DisplayPrimaries() {
        }
    }

    /**
     * @hide
     */
    public static SurfaceControl.DisplayPrimaries getDisplayNativePrimaries(
            IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }

        return nativeGetDisplayNativePrimaries(displayToken);
    }

    /**
     * @hide
     */
    public static int getActiveColorMode(IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        return nativeGetActiveColorMode(displayToken);
    }

    /**
     * @hide
     */
    public static boolean setActiveColorMode(IBinder displayToken, int colorMode) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        return nativeSetActiveColorMode(displayToken, colorMode);
    }

    /**
     * Returns an array of color spaces with 2 elements. The first color space is the
     * default color space and second one is wide color gamut color space.
     * @hide
     */
    public static ColorSpace[] getCompositionColorSpaces() {
        int[] dataspaces = nativeGetCompositionDataspaces();
        ColorSpace srgb = ColorSpace.get(ColorSpace.Named.SRGB);
        ColorSpace[] colorSpaces = { srgb, srgb };
        if (dataspaces.length == 2) {
            for (int i = 0; i < 2; ++i) {
                switch(dataspaces[i]) {
                    case INTERNAL_DATASPACE_DISPLAY_P3:
                        colorSpaces[i] = ColorSpace.get(ColorSpace.Named.DISPLAY_P3);
                        break;
                    case INTERNAL_DATASPACE_SCRGB:
                        colorSpaces[i] = ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB);
                        break;
                    case INTERNAL_DATASPACE_SRGB:
                    // Other dataspace is not recognized, use SRGB color space instead,
                    // the default value of the array is already SRGB, thus do nothing.
                    default:
                        break;
                }
            }
        }
        return colorSpaces;
    }

    /**
     * @hide
     */
    public static void setAutoLowLatencyMode(IBinder displayToken, boolean on) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }

        nativeSetAutoLowLatencyMode(displayToken, on);
    }

    /**
     * @hide
     */
    public static void setGameContentType(IBinder displayToken, boolean on) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }

        nativeSetGameContentType(displayToken, on);
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public static void setDisplayProjection(IBinder displayToken,
            int orientation, Rect layerStackRect, Rect displayRect) {
        synchronized (SurfaceControl.class) {
            sGlobalTransaction.setDisplayProjection(displayToken, orientation,
                    layerStackRect, displayRect);
        }
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public static void setDisplayLayerStack(IBinder displayToken, int layerStack) {
        synchronized (SurfaceControl.class) {
            sGlobalTransaction.setDisplayLayerStack(displayToken, layerStack);
        }
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public static void setDisplaySurface(IBinder displayToken, Surface surface) {
        synchronized (SurfaceControl.class) {
            sGlobalTransaction.setDisplaySurface(displayToken, surface);
        }
    }

    /**
     * @hide
     */
    public static void setDisplaySize(IBinder displayToken, int width, int height) {
        synchronized (SurfaceControl.class) {
            sGlobalTransaction.setDisplaySize(displayToken, width, height);
        }
    }

    /**
     * @hide
     */
    public static Display.HdrCapabilities getHdrCapabilities(IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        return nativeGetHdrCapabilities(displayToken);
    }

    /**
     * @hide
     */
    public static boolean getAutoLowLatencyModeSupport(IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }

        return nativeGetAutoLowLatencyModeSupport(displayToken);
    }

    /**
     * @hide
     */
    public static boolean getGameContentTypeSupport(IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }

        return nativeGetGameContentTypeSupport(displayToken);
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public static IBinder createDisplay(String name, boolean secure) {
        if (name == null) {
            throw new IllegalArgumentException("name must not be null");
        }
        return nativeCreateDisplay(name, secure);
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public static void destroyDisplay(IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        nativeDestroyDisplay(displayToken);
    }

    /**
     * @hide
     */
    public static long[] getPhysicalDisplayIds() {
        return nativeGetPhysicalDisplayIds();
    }

    /**
     * @hide
     */
    public static IBinder getPhysicalDisplayToken(long physicalDisplayId) {
        return nativeGetPhysicalDisplayToken(physicalDisplayId);
    }

    /**
     * TODO(b/116025192): Remove this stopgap once framework is display-agnostic.
     *
     * @hide
     */
    public static IBinder getInternalDisplayToken() {
        final long[] physicalDisplayIds = getPhysicalDisplayIds();
        if (physicalDisplayIds.length == 0) {
            return null;
        }
        return getPhysicalDisplayToken(physicalDisplayIds[0]);
    }

    /**
     * @see SurfaceControl#screenshot(IBinder, Surface, Rect, int, int, boolean, int)
     * @hide
     */
    public static void screenshot(IBinder display, Surface consumer) {
        screenshot(display, consumer, new Rect(), 0, 0, false, 0);
    }

    /**
     * Copy the current screen contents into the provided {@link Surface}
     *
     * @param consumer The {@link Surface} to take the screenshot into.
     * @see SurfaceControl#screenshotToBuffer(IBinder, Rect, int, int, boolean, int)
     * @hide
     */
    public static void screenshot(IBinder display, Surface consumer, Rect sourceCrop, int width,
            int height, boolean useIdentityTransform, int rotation) {
        if (consumer == null) {
            throw new IllegalArgumentException("consumer must not be null");
        }

        final ScreenshotGraphicBuffer buffer = screenshotToBuffer(display, sourceCrop, width,
                height, useIdentityTransform, rotation);
        try {
            consumer.attachAndQueueBufferWithColorSpace(buffer.getGraphicBuffer(),
                    buffer.getColorSpace());
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to take screenshot - " + e.getMessage());
        }
    }

    /**
     * @see SurfaceControl#screenshot(Rect, int, int, boolean, int)}
     * @hide
     */
    @UnsupportedAppUsage
    public static Bitmap screenshot(Rect sourceCrop, int width, int height, int rotation) {
        return screenshot(sourceCrop, width, height, false, rotation);
    }

    /**
     * Copy the current screen contents into a hardware bitmap and return it.
     * Note: If you want to modify the Bitmap in software, you will need to copy the Bitmap into
     * a software Bitmap using {@link Bitmap#copy(Bitmap.Config, boolean)}
     *
     * CAVEAT: Versions of screenshot that return a {@link Bitmap} can be extremely slow; avoid use
     * unless absolutely necessary; prefer the versions that use a {@link Surface} such as
     * {@link SurfaceControl#screenshot(IBinder, Surface)} or {@link GraphicBuffer} such as
     * {@link SurfaceControl#screenshotToBuffer(IBinder, Rect, int, int, boolean, int)}.
     *
     * @see SurfaceControl#screenshotToBuffer(IBinder, Rect, int, int, boolean, int)}
     * @hide
     */
    @UnsupportedAppUsage
    public static Bitmap screenshot(Rect sourceCrop, int width, int height,
            boolean useIdentityTransform, int rotation) {
        // TODO: should take the display as a parameter
        final IBinder displayToken = SurfaceControl.getInternalDisplayToken();
        if (displayToken == null) {
            Log.w(TAG, "Failed to take screenshot because internal display is disconnected");
            return null;
        }

        if (rotation == ROTATION_90 || rotation == ROTATION_270) {
            rotation = (rotation == ROTATION_90) ? ROTATION_270 : ROTATION_90;
        }

        SurfaceControl.rotateCropForSF(sourceCrop, rotation);
        final ScreenshotGraphicBuffer buffer = screenshotToBuffer(displayToken, sourceCrop, width,
                height, useIdentityTransform, rotation);

        if (buffer == null) {
            Log.w(TAG, "Failed to take screenshot");
            return null;
        }
        return Bitmap.wrapHardwareBuffer(buffer.getGraphicBuffer(), buffer.getColorSpace());
    }

    /**
     * Captures all the surfaces in a display and returns a {@link GraphicBuffer} with the content.
     *
     * @param display              The display to take the screenshot of.
     * @param sourceCrop           The portion of the screen to capture into the Bitmap; caller may
     *                             pass in 'new Rect()' if no cropping is desired.
     * @param width                The desired width of the returned bitmap; the raw screen will be
     *                             scaled down to this size; caller may pass in 0 if no scaling is
     *                             desired.
     * @param height               The desired height of the returned bitmap; the raw screen will
     *                             be scaled down to this size; caller may pass in 0 if no scaling
     *                             is desired.
     * @param useIdentityTransform Replace whatever transformation (rotation, scaling, translation)
     *                             the surface layers are currently using with the identity
     *                             transformation while taking the screenshot.
     * @param rotation             Apply a custom clockwise rotation to the screenshot, i.e.
     *                             Surface.ROTATION_0,90,180,270. SurfaceFlinger will always take
     *                             screenshots in its native portrait orientation by default, so
     *                             this is useful for returning screenshots that are independent of
     *                             device orientation.
     * @return Returns a GraphicBuffer that contains the captured content.
     * @hide
     */
    public static ScreenshotGraphicBuffer screenshotToBuffer(IBinder display, Rect sourceCrop,
            int width, int height, boolean useIdentityTransform, int rotation) {
        if (display == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }

        return nativeScreenshot(display, sourceCrop, width, height, useIdentityTransform, rotation,
                false /* captureSecureLayers */);
    }

    /**
     * Like screenshotToBuffer, but if the caller is AID_SYSTEM, allows
     * for the capture of secure layers. This is used for the screen rotation
     * animation where the system server takes screenshots but does
     * not persist them or allow them to leave the server. However in other
     * cases in the system server, we mostly want to omit secure layers
     * like when we take a screenshot on behalf of the assistant.
     *
     * @hide
     */
    public static ScreenshotGraphicBuffer screenshotToBufferWithSecureLayersUnsafe(IBinder display,
            Rect sourceCrop, int width, int height, boolean useIdentityTransform,
            int rotation) {
        if (display == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }

        return nativeScreenshot(display, sourceCrop, width, height, useIdentityTransform, rotation,
                true /* captureSecureLayers */);
    }

    private static void rotateCropForSF(Rect crop, int rot) {
        if (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270) {
            int tmp = crop.top;
            crop.top = crop.left;
            crop.left = tmp;
            tmp = crop.right;
            crop.right = crop.bottom;
            crop.bottom = tmp;
        }
    }

    /**
     * Captures a layer and its children and returns a {@link GraphicBuffer} with the content.
     *
     * @param layer            The root layer to capture.
     * @param sourceCrop       The portion of the root surface to capture; caller may pass in 'new
     *                         Rect()' or null if no cropping is desired. If the root layer does not
     *                         have a buffer or a crop set, then a non-empty source crop must be
     *                         specified.
     * @param frameScale       The desired scale of the returned buffer; the raw
     *                         screen will be scaled up/down.
     *
     * @return Returns a GraphicBuffer that contains the layer capture.
     * @hide
     */
    public static ScreenshotGraphicBuffer captureLayers(SurfaceControl layer, Rect sourceCrop,
            float frameScale) {
        return captureLayers(layer, sourceCrop, frameScale, PixelFormat.RGBA_8888);
    }

    /**
     * Captures a layer and its children and returns a {@link GraphicBuffer} with the content.
     *
     * @param layer            The root layer to capture.
     * @param sourceCrop       The portion of the root surface to capture; caller may pass in 'new
     *                         Rect()' or null if no cropping is desired. If the root layer does not
     *                         have a buffer or a crop set, then a non-empty source crop must be
     *                         specified.
     * @param frameScale       The desired scale of the returned buffer; the raw
     *                         screen will be scaled up/down.
     * @param format           The desired pixel format of the returned buffer.
     *
     * @return Returns a GraphicBuffer that contains the layer capture.
     * @hide
     */
    public static ScreenshotGraphicBuffer captureLayers(SurfaceControl layer, Rect sourceCrop,
            float frameScale, int format) {
        final IBinder displayToken = SurfaceControl.getInternalDisplayToken();
        return nativeCaptureLayers(displayToken, layer.mNativeObject, sourceCrop, frameScale, null,
                format);
    }

    /**
     * Like {@link captureLayers} but with an array of layer handles to exclude.
     * @hide
     */
    public static ScreenshotGraphicBuffer captureLayersExcluding(SurfaceControl layer,
          Rect sourceCrop, float frameScale, int format, SurfaceControl[] exclude) {
        final IBinder displayToken = SurfaceControl.getInternalDisplayToken();
        long[] nativeExcludeObjects = new long[exclude.length];
        for (int i = 0; i < exclude.length; i++) {
            nativeExcludeObjects[i] = exclude[i].mNativeObject;
        }
        return nativeCaptureLayers(displayToken, layer.mNativeObject, sourceCrop, frameScale,
                nativeExcludeObjects, PixelFormat.RGBA_8888);
    }

    /**
     * Returns whether protected content is supported in GPU composition.
     * @hide
     */
    public static boolean getProtectedContentSupport() {
        return nativeGetProtectedContentSupport();
    }

    /**
     * Returns whether brightness operations are supported on a display.
     *
     * @param displayToken
     *      The token for the display.
     *
     * @return Whether brightness operations are supported on the display.
     *
     * @hide
     */
    public static boolean getDisplayBrightnessSupport(IBinder displayToken) {
        return nativeGetDisplayBrightnessSupport(displayToken);
    }

    /**
     * Sets the brightness of a display.
     *
     * @param displayToken
     *      The token for the display whose brightness is set.
     * @param brightness
     *      A number between 0.0f (minimum brightness) and 1.0f (maximum brightness), or -1.0f to
     *      turn the backlight off.
     *
     * @return Whether the method succeeded or not.
     *
     * @throws IllegalArgumentException if:
     *      - displayToken is null;
     *      - brightness is NaN or greater than 1.0f.
     *
     * @hide
     */
    public static boolean setDisplayBrightness(IBinder displayToken, float brightness) {
        Objects.requireNonNull(displayToken);
        if (Float.isNaN(brightness) || brightness > 1.0f
                || (brightness < 0.0f && brightness != -1.0f)) {
            throw new IllegalArgumentException("brightness must be a number between 0.0f and 1.0f,"
                    + " or -1 to turn the backlight off.");
        }
        return nativeSetDisplayBrightness(displayToken, brightness);
    }

    /**
     * Creates a mirrored hierarchy for the mirrorOf {@link SurfaceControl}
     *
     * Real Hierarchy    Mirror
     *                     SC (value that's returned)
     *                      |
     *      A               A'
     *      |               |
     *      B               B'
     *
     * @param mirrorOf The root of the hierarchy that should be mirrored.
     * @return A SurfaceControl that's the parent of the root of the mirrored hierarchy.
     *
     * @hide
     */
    public static SurfaceControl mirrorSurface(SurfaceControl mirrorOf) {
        long nativeObj = nativeMirrorSurface(mirrorOf.mNativeObject);
        SurfaceControl sc = new SurfaceControl();
        sc.assignNativeObject(nativeObj);
        return sc;
    }

    private static void validateColorArg(@Size(4) float[] color) {
        final String msg = "Color must be specified as a float array with"
                + " four values to represent r, g, b, a in range [0..1]";
        if (color.length != 4) {
            throw new IllegalArgumentException(msg);
        }
        for (float c:color) {
            if ((c < 0.f) || (c > 1.f)) {
                throw new IllegalArgumentException(msg);
            }
        }
    }

    /**
     * Sets the global configuration for all the shadows drawn by SurfaceFlinger. Shadow follows
     * material design guidelines.
     *
     * @param ambientColor Color applied to the ambient shadow. The alpha is premultiplied. A
     *                     float array with four values to represent r, g, b, a in range [0..1]
     * @param spotColor Color applied to the spot shadow. The alpha is premultiplied. The position
     *                  of the spot shadow depends on the light position. A float array with
     *                  four values to represent r, g, b, a in range [0..1]
     * @param lightPosY Y axis position of the light used to cast the spot shadow in pixels.
     * @param lightPosZ Z axis position of the light used to cast the spot shadow in pixels. The X
     *                  axis position is set to the display width / 2.
     * @param lightRadius Radius of the light casting the shadow in pixels.
     *[
     * @hide
     */
    public static void setGlobalShadowSettings(@Size(4) float[] ambientColor,
            @Size(4) float[] spotColor, float lightPosY, float lightPosZ, float lightRadius) {
        validateColorArg(ambientColor);
        validateColorArg(spotColor);
        nativeSetGlobalShadowSettings(ambientColor, spotColor, lightPosY, lightPosZ, lightRadius);
    }

     /**
     * An atomic set of changes to a set of SurfaceControl.
     */
    public static class Transaction implements Closeable, Parcelable {
        /**
         * @hide
         */
        public static final NativeAllocationRegistry sRegistry = new NativeAllocationRegistry(
                Transaction.class.getClassLoader(),
                nativeGetNativeTransactionFinalizer(), 512);
        /**
         * @hide
         */
        public long mNativeObject;

        private final ArrayMap<SurfaceControl, Point> mResizedSurfaces = new ArrayMap<>();
        Runnable mFreeNativeResources;
        private static final float[] INVALID_COLOR = {-1, -1, -1};

        /**
         * @hide
         */
        protected void checkPreconditions(SurfaceControl sc) {
            sc.checkNotReleased();
        }

        /**
         * Open a new transaction object. The transaction may be filed with commands to
         * manipulate {@link SurfaceControl} instances, and then applied atomically with
         * {@link #apply}. Eventually the user should invoke {@link #close}, when the object
         * is no longer required. Note however that re-using a transaction after a call to apply
         * is allowed as a convenience.
         */
        public Transaction() {
            mNativeObject = nativeCreateTransaction();
            mFreeNativeResources
                = sRegistry.registerNativeAllocation(this, mNativeObject);
        }

        private Transaction(Parcel in) {
            readFromParcel(in);
        }

        /**
         * Apply the transaction, clearing it's state, and making it usable
         * as a new transaction.
         */
        public void apply() {
            apply(false);
        }

        /**
         * Release the native transaction object, without applying it.
         */
        @Override
        public void close() {
            mFreeNativeResources.run();
            mNativeObject = 0;
        }

        /**
         * Jankier version of apply. Avoid use (b/28068298).
         * @hide
         */
        public void apply(boolean sync) {
            applyResizedSurfaces();
            nativeApplyTransaction(mNativeObject, sync);
        }

        private void applyResizedSurfaces() {
            for (int i = mResizedSurfaces.size() - 1; i >= 0; i--) {
                final Point size = mResizedSurfaces.valueAt(i);
                final SurfaceControl surfaceControl = mResizedSurfaces.keyAt(i);
                synchronized (surfaceControl.mSizeLock) {
                    surfaceControl.mWidth = size.x;
                    surfaceControl.mHeight = size.y;
                }
            }
            mResizedSurfaces.clear();
        }

        /**
         * Toggle the visibility of a given Layer and it's sub-tree.
         *
         * @param sc The SurfaceControl for which to set the visibility
         * @param visible The new visibility
         * @return This transaction object.
         */
        @NonNull
        public Transaction setVisibility(@NonNull SurfaceControl sc, boolean visible) {
            checkPreconditions(sc);
            if (visible) {
                return show(sc);
            } else {
                return hide(sc);
            }
        }

        /**
         * This information is passed to SurfaceFlinger to decide which window should have a
         * priority when deciding about the refresh rate of the display. All windows have the
         * lowest priority by default.
         * @hide
         */
        @NonNull
        public Transaction setFrameRateSelectionPriority(@NonNull SurfaceControl sc, int priority) {
            sc.checkNotReleased();
            nativeSetFrameRateSelectionPriority(mNativeObject, sc.mNativeObject, priority);
            return this;
        }

        /**
         * Request that a given surface and it's sub-tree be shown.
         *
         * @param sc The surface to show.
         * @return This transaction.
         * @hide
         */
        @UnsupportedAppUsage
        public Transaction show(SurfaceControl sc) {
            checkPreconditions(sc);
            nativeSetFlags(mNativeObject, sc.mNativeObject, 0, SURFACE_HIDDEN);
            return this;
        }

        /**
         * Request that a given surface and it's sub-tree be hidden.
         *
         * @param sc The surface to hidden.
         * @return This transaction.
         * @hide
         */
        @UnsupportedAppUsage
        public Transaction hide(SurfaceControl sc) {
            checkPreconditions(sc);
            nativeSetFlags(mNativeObject, sc.mNativeObject, SURFACE_HIDDEN, SURFACE_HIDDEN);
            return this;
        }

        /**
         * @hide
         */
        @UnsupportedAppUsage
        public Transaction setPosition(SurfaceControl sc, float x, float y) {
            checkPreconditions(sc);
            nativeSetPosition(mNativeObject, sc.mNativeObject, x, y);
            return this;
        }

        /**
         * Set the default buffer size for the SurfaceControl, if there is a
         * {@link Surface} associated with the control, then
         * this will be the default size for buffers dequeued from it.
         * @param sc The surface to set the buffer size for.
         * @param w The default width
         * @param h The default height
         * @return This Transaction
         */
        @NonNull
        public Transaction setBufferSize(@NonNull SurfaceControl sc,
                @IntRange(from = 0) int w, @IntRange(from = 0) int h) {
            checkPreconditions(sc);
            mResizedSurfaces.put(sc, new Point(w, h));
            nativeSetSize(mNativeObject, sc.mNativeObject, w, h);
            return this;
        }

        /**
         * Set the Z-order for a given SurfaceControl, relative to it's siblings.
         * If two siblings share the same Z order the ordering is undefined. Surfaces
         * with a negative Z will be placed below the parent surface.
         *
         * @param sc The SurfaceControl to set the Z order on
         * @param z The Z-order
         * @return This Transaction.
         */
        @NonNull
        public Transaction setLayer(@NonNull SurfaceControl sc,
                @IntRange(from = Integer.MIN_VALUE, to = Integer.MAX_VALUE) int z) {
            checkPreconditions(sc);
            nativeSetLayer(mNativeObject, sc.mNativeObject, z);
            return this;
        }

        /**
         * @hide
         */
        public Transaction setRelativeLayer(SurfaceControl sc, SurfaceControl relativeTo, int z) {
            checkPreconditions(sc);
            nativeSetRelativeLayer(mNativeObject, sc.mNativeObject, relativeTo.mNativeObject, z);
            return this;
        }

        /**
         * @hide
         */
        public Transaction setTransparentRegionHint(SurfaceControl sc, Region transparentRegion) {
            checkPreconditions(sc);
            nativeSetTransparentRegionHint(mNativeObject,
                    sc.mNativeObject, transparentRegion);
            return this;
        }

        /**
         * Set the alpha for a given surface. If the alpha is non-zero the SurfaceControl
         * will be blended with the Surfaces under it according to the specified ratio.
         *
         * @param sc The given SurfaceControl.
         * @param alpha The alpha to set.
         */
        @NonNull
        public Transaction setAlpha(@NonNull SurfaceControl sc,
                @FloatRange(from = 0.0, to = 1.0) float alpha) {
            checkPreconditions(sc);
            nativeSetAlpha(mNativeObject, sc.mNativeObject, alpha);
            return this;
        }

        /**
         * @hide
         */
        public Transaction setInputWindowInfo(SurfaceControl sc, InputWindowHandle handle) {
            checkPreconditions(sc);
            nativeSetInputWindowInfo(mNativeObject, sc.mNativeObject, handle);
            return this;
        }

        /**
         * Waits until any changes to input windows have been sent from SurfaceFlinger to
         * InputFlinger before returning.
         *
         * @hide
         */
        public Transaction syncInputWindows() {
            nativeSyncInputWindows(mNativeObject);
            return this;
        }

        /**
         * Specify how the buffer assosciated with this Surface is mapped in to the
         * parent coordinate space. The source frame will be scaled to fit the destination
         * frame, after being rotated according to the orientation parameter.
         *
         * @param sc The SurfaceControl to specify the geometry of
         * @param sourceCrop The source rectangle in buffer space. Or null for the entire buffer.
         * @param destFrame The destination rectangle in parent space. Or null for the source frame.
         * @param orientation The buffer rotation
         * @return This transaction object.
         */
        @NonNull
        public Transaction setGeometry(@NonNull SurfaceControl sc, @Nullable Rect sourceCrop,
                @Nullable Rect destFrame, @Surface.Rotation int orientation) {
            checkPreconditions(sc);
            nativeSetGeometry(mNativeObject, sc.mNativeObject, sourceCrop, destFrame, orientation);
            return this;
        }

        /**
         * @hide
         */
        @UnsupportedAppUsage
        public Transaction setMatrix(SurfaceControl sc,
                float dsdx, float dtdx, float dtdy, float dsdy) {
            checkPreconditions(sc);
            nativeSetMatrix(mNativeObject, sc.mNativeObject,
                    dsdx, dtdx, dtdy, dsdy);
            return this;
        }

        /**
         * Sets the transform and position of a {@link SurfaceControl} from a 3x3 transformation
         * matrix.
         *
         * @param sc     SurfaceControl to set matrix of
         * @param matrix The matrix to apply.
         * @param float9 An array of 9 floats to be used to extract the values from the matrix.
         * @hide
         */
        @UnsupportedAppUsage
        public Transaction setMatrix(SurfaceControl sc, Matrix matrix, float[] float9) {
            matrix.getValues(float9);
            setMatrix(sc, float9[MSCALE_X], float9[MSKEW_Y],
                    float9[MSKEW_X], float9[MSCALE_Y]);
            setPosition(sc, float9[MTRANS_X], float9[MTRANS_Y]);
            return this;
        }

        /**
         * Sets the color transform for the Surface.
         *
         * @param sc          SurfaceControl to set color transform of
         * @param matrix      A float array with 9 values represents a 3x3 transform matrix
         * @param translation A float array with 3 values represents a translation vector
         * @hide
         */
        public Transaction setColorTransform(SurfaceControl sc, @Size(9) float[] matrix,
                @Size(3) float[] translation) {
            checkPreconditions(sc);
            nativeSetColorTransform(mNativeObject, sc.mNativeObject, matrix, translation);
            return this;
        }

        /**
         * Sets the Surface to be color space agnostic. If a surface is color space agnostic,
         * the color can be interpreted in any color space.
         * @param agnostic A boolean to indicate whether the surface is color space agnostic
         * @hide
         */
        public Transaction setColorSpaceAgnostic(SurfaceControl sc, boolean agnostic) {
            checkPreconditions(sc);
            nativeSetColorSpaceAgnostic(mNativeObject, sc.mNativeObject, agnostic);
            return this;
        }

        /**
         * Bounds the surface and its children to the bounds specified. Size of the surface will be
         * ignored and only the crop and buffer size will be used to determine the bounds of the
         * surface. If no crop is specified and the surface has no buffer, the surface bounds is
         * only constrained by the size of its parent bounds.
         *
         * @param sc   SurfaceControl to set crop of.
         * @param crop Bounds of the crop to apply.
         * @hide
         */
        @UnsupportedAppUsage
        public Transaction setWindowCrop(SurfaceControl sc, Rect crop) {
            checkPreconditions(sc);
            if (crop != null) {
                nativeSetWindowCrop(mNativeObject, sc.mNativeObject,
                        crop.left, crop.top, crop.right, crop.bottom);
            } else {
                nativeSetWindowCrop(mNativeObject, sc.mNativeObject, 0, 0, 0, 0);
            }

            return this;
        }

        /**
         * Same as {@link Transaction#setWindowCrop(SurfaceControl, Rect)} but sets the crop rect
         * top left at 0, 0.
         *
         * @param sc     SurfaceControl to set crop of.
         * @param width  width of crop rect
         * @param height height of crop rect
         * @hide
         */
        public Transaction setWindowCrop(SurfaceControl sc, int width, int height) {
            checkPreconditions(sc);
            nativeSetWindowCrop(mNativeObject, sc.mNativeObject, 0, 0, width, height);
            return this;
        }

        /**
         * Sets the corner radius of a {@link SurfaceControl}.
         * @param sc SurfaceControl
         * @param cornerRadius Corner radius in pixels.
         * @return Itself.
         * @hide
         */
        @UnsupportedAppUsage
        public Transaction setCornerRadius(SurfaceControl sc, float cornerRadius) {
            checkPreconditions(sc);
            nativeSetCornerRadius(mNativeObject, sc.mNativeObject, cornerRadius);

            return this;
        }

        /**
         * Sets the background blur radius of the {@link SurfaceControl}.
         *
         * @param sc SurfaceControl.
         * @param radius Blur radius in pixels.
         * @return itself.
         * @hide
         */
        public Transaction setBackgroundBlurRadius(SurfaceControl sc, int radius) {
            checkPreconditions(sc);
            nativeSetBackgroundBlurRadius(mNativeObject, sc.mNativeObject, radius);
            return this;
        }

        /**
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.O)
        public Transaction setLayerStack(SurfaceControl sc, int layerStack) {
            checkPreconditions(sc);
            nativeSetLayerStack(mNativeObject, sc.mNativeObject, layerStack);
            return this;
        }

        /**
         * @hide
         */
        @UnsupportedAppUsage
        public Transaction deferTransactionUntil(SurfaceControl sc, SurfaceControl barrier,
                long frameNumber) {
            if (frameNumber < 0) {
                return this;
            }
            checkPreconditions(sc);
            nativeDeferTransactionUntil(mNativeObject, sc.mNativeObject, barrier.mNativeObject,
                    frameNumber);
            return this;
        }

        /**
         * @hide
         */
        @Deprecated
        @UnsupportedAppUsage
        public Transaction deferTransactionUntilSurface(SurfaceControl sc, Surface barrierSurface,
                long frameNumber) {
            if (frameNumber < 0) {
                return this;
            }
            checkPreconditions(sc);
            nativeDeferTransactionUntilSurface(mNativeObject, sc.mNativeObject,
                    barrierSurface.mNativeObject, frameNumber);
            return this;
        }

        /**
         * @hide
         */
        public Transaction reparentChildren(SurfaceControl sc, SurfaceControl newParent) {
            checkPreconditions(sc);
            nativeReparentChildren(mNativeObject, sc.mNativeObject, newParent.mNativeObject);
            return this;
        }

        /**
         * Re-parents a given layer to a new parent. Children inherit transform (position, scaling)
         * crop, visibility, and Z-ordering from their parents, as if the children were pixels within the
         * parent Surface.
         *
         * @param sc The SurfaceControl to reparent
         * @param newParent The new parent for the given control.
         * @return This Transaction
         */
        @NonNull
        public Transaction reparent(@NonNull SurfaceControl sc,
                @Nullable SurfaceControl newParent) {
            checkPreconditions(sc);
            long otherObject = 0;
            if (newParent != null) {
                newParent.checkNotReleased();
                otherObject = newParent.mNativeObject;
            }
            nativeReparent(mNativeObject, sc.mNativeObject, otherObject);
            return this;
        }

        /**
         * @hide
         */
        public Transaction detachChildren(SurfaceControl sc) {
            checkPreconditions(sc);
            nativeSeverChildren(mNativeObject, sc.mNativeObject);
            return this;
        }

        /**
         * @hide
         */
        public Transaction setOverrideScalingMode(SurfaceControl sc, int overrideScalingMode) {
            checkPreconditions(sc);
            nativeSetOverrideScalingMode(mNativeObject, sc.mNativeObject,
                    overrideScalingMode);
            return this;
        }

        /**
         * Fills the surface with the specified color.
         * @param color A float array with three values to represent r, g, b in range [0..1]. An
         * invalid color will remove the color fill.
         * @hide
         */
        @UnsupportedAppUsage
        public Transaction setColor(SurfaceControl sc, @Size(3) float[] color) {
            checkPreconditions(sc);
            nativeSetColor(mNativeObject, sc.mNativeObject, color);
            return this;
        }

        /**
         * Removes color fill.
        * @hide
        */
        public Transaction unsetColor(SurfaceControl sc) {
            checkPreconditions(sc);
            nativeSetColor(mNativeObject, sc.mNativeObject, INVALID_COLOR);
            return this;
        }

        /**
         * Sets the security of the surface.  Setting the flag is equivalent to creating the
         * Surface with the {@link #SECURE} flag.
         * @hide
         */
        public Transaction setSecure(SurfaceControl sc, boolean isSecure) {
            checkPreconditions(sc);
            if (isSecure) {
                nativeSetFlags(mNativeObject, sc.mNativeObject, SECURE, SECURE);
            } else {
                nativeSetFlags(mNativeObject, sc.mNativeObject, 0, SECURE);
            }
            return this;
        }

        /**
         * Sets the opacity of the surface.  Setting the flag is equivalent to creating the
         * Surface with the {@link #OPAQUE} flag.
         * @hide
         */
        public Transaction setOpaque(SurfaceControl sc, boolean isOpaque) {
            checkPreconditions(sc);
            if (isOpaque) {
                nativeSetFlags(mNativeObject, sc.mNativeObject, SURFACE_OPAQUE, SURFACE_OPAQUE);
            } else {
                nativeSetFlags(mNativeObject, sc.mNativeObject, 0, SURFACE_OPAQUE);
            }
            return this;
        }

        /**
         * @hide
         */
        public Transaction setDisplaySurface(IBinder displayToken, Surface surface) {
            if (displayToken == null) {
                throw new IllegalArgumentException("displayToken must not be null");
            }

            if (surface != null) {
                synchronized (surface.mLock) {
                    nativeSetDisplaySurface(mNativeObject, displayToken, surface.mNativeObject);
                }
            } else {
                nativeSetDisplaySurface(mNativeObject, displayToken, 0);
            }
            return this;
        }

        /**
         * @hide
         */
        public Transaction setDisplayLayerStack(IBinder displayToken, int layerStack) {
            if (displayToken == null) {
                throw new IllegalArgumentException("displayToken must not be null");
            }
            nativeSetDisplayLayerStack(mNativeObject, displayToken, layerStack);
            return this;
        }

        /**
         * @hide
         */
        public Transaction setDisplayProjection(IBinder displayToken,
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
            nativeSetDisplayProjection(mNativeObject, displayToken, orientation,
                    layerStackRect.left, layerStackRect.top, layerStackRect.right, layerStackRect.bottom,
                    displayRect.left, displayRect.top, displayRect.right, displayRect.bottom);
            return this;
        }

        /**
         * @hide
         */
        public Transaction setDisplaySize(IBinder displayToken, int width, int height) {
            if (displayToken == null) {
                throw new IllegalArgumentException("displayToken must not be null");
            }
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("width and height must be positive");
            }

            nativeSetDisplaySize(mNativeObject, displayToken, width, height);
            return this;
        }

        /** flag the transaction as an animation
         * @hide
         */
        public Transaction setAnimationTransaction() {
            nativeSetAnimationTransaction(mNativeObject);
            return this;
        }

        /**
         * Indicate that SurfaceFlinger should wake up earlier than usual as a result of this
         * transaction. This should be used when the caller thinks that the scene is complex enough
         * that it's likely to hit GL composition, and thus, SurfaceFlinger needs to more time in
         * order not to miss frame deadlines.
         * <p>
         * Corresponds to setting ISurfaceComposer::eEarlyWakeup
         * @hide
         */
        public Transaction setEarlyWakeup() {
            nativeSetEarlyWakeup(mNativeObject);
            return this;
        }

        /**
         * Sets an arbitrary piece of metadata on the surface. This is a helper for int data.
         * @hide
         */
        public Transaction setMetadata(SurfaceControl sc, int key, int data) {
            Parcel parcel = Parcel.obtain();
            parcel.writeInt(data);
            try {
                setMetadata(sc, key, parcel);
            } finally {
                parcel.recycle();
            }
            return this;
        }

        /**
         * Sets an arbitrary piece of metadata on the surface.
         * @hide
         */
        public Transaction setMetadata(SurfaceControl sc, int key, Parcel data) {
            checkPreconditions(sc);
            nativeSetMetadata(mNativeObject, sc.mNativeObject, key, data);
            return this;
        }

         /**
          * Draws shadows of length {@code shadowRadius} around the surface {@link SurfaceControl}.
          * If the length is 0.0f then the shadows will not be drawn.
          *
          * Shadows are drawn around the screen bounds, these are the post transformed cropped
          * bounds. They can draw over their parent bounds and will be occluded by layers with a
          * higher z-order. The shadows will respect the surface's corner radius if the
          * rounded corner bounds (transformed source bounds) are within the screen bounds.
          *
          * A shadow will only be drawn on buffer and color layers. If the radius is applied on a
          * container layer, it will be passed down the hierarchy to be applied on buffer and color
          * layers but not its children. A scenario where this is useful is when SystemUI animates
          * a task by controlling a leash to it, can draw a shadow around the app surface by
          * setting a shadow on the leash. This is similar to how rounded corners are set.
          *
          * @hide
          */
        public Transaction setShadowRadius(SurfaceControl sc, float shadowRadius) {
            checkPreconditions(sc);
            nativeSetShadowRadius(mNativeObject, sc.mNativeObject, shadowRadius);
            return this;
        }

        /**
         * Sets the intended frame rate for the surface {@link SurfaceControl}.
         * <p>
         * On devices that are capable of running the display at different refresh rates, the system
         * may choose a display refresh rate to better match this surface's frame rate. Usage of
         * this API won't directly affect the application's frame production pipeline. However,
         * because the system may change the display refresh rate, calls to this function may result
         * in changes to Choreographer callback timings, and changes to the time interval at which
         * the system releases buffers back to the application.
         *
         * @param sc The SurfaceControl to specify the frame rate of.
         * @param frameRate The intended frame rate for this surface, in frames per second. 0 is a
         *                  special value that indicates the app will accept the system's choice for
         *                  the display frame rate, which is the default behavior if this function
         *                  isn't called. The frameRate param does <em>not</em> need to be a valid
         *                  refresh rate for this device's display - e.g., it's fine to pass 30fps
         *                  to a device that can only run the display at 60fps.
         * @param compatibility The frame rate compatibility of this surface. The compatibility
         *                      value may influence the system's choice of display frame rate. See
         *                      the Surface.FRAME_RATE_COMPATIBILITY_* values for more info.
         * @return This transaction object.
         */
        @NonNull
        public Transaction setFrameRate(@NonNull SurfaceControl sc,
                @FloatRange(from = 0.0) float frameRate,
                @Surface.FrameRateCompatibility int compatibility) {
            checkPreconditions(sc);
            nativeSetFrameRate(mNativeObject, sc.mNativeObject, frameRate, compatibility);
            return this;
        }

        /**
         * Merge the other transaction into this transaction, clearing the
         * other transaction as if it had been applied.
         *
         * @param other The transaction to merge in to this one.
         * @return This transaction.
         */
        @NonNull
        public Transaction merge(@NonNull Transaction other) {
            if (this == other) {
                return this;
            }
            mResizedSurfaces.putAll(other.mResizedSurfaces);
            other.mResizedSurfaces.clear();
            nativeMergeTransaction(mNativeObject, other.mNativeObject);
            return this;
        }

        /**
         * Equivalent to reparent with a null parent, in that it removes
         * the SurfaceControl from the scene, but it also releases
         * the local resources (by calling {@link SurfaceControl#release})
         * after this method returns, {@link SurfaceControl#isValid} will return
         * false for the argument.
         *
         * @param sc The surface to remove and release.
         * @return This transaction
         * @hide
         */
        @NonNull
        public Transaction remove(@NonNull SurfaceControl sc) {
            reparent(sc, null);
            sc.release();
            return this;
        }

        /**
         * Writes the transaction to parcel, clearing the transaction as if it had been applied so
         * it can be used to store future transactions. It's the responsibility of the parcel
         * reader to apply the original transaction.
         *
         * @param dest parcel to write the transaction to
         * @param flags
         */
        @Override
        public void writeToParcel(@NonNull Parcel dest, @WriteFlags int flags) {
            if (mNativeObject == 0) {
                dest.writeInt(0);
            } else {
                dest.writeInt(1);
            }
            nativeWriteTransactionToParcel(mNativeObject, dest);
        }

        private void readFromParcel(Parcel in) {
            mNativeObject = 0;
            if (in.readInt() != 0) {
                mNativeObject = nativeReadTransactionFromParcel(in);
                mFreeNativeResources = sRegistry.registerNativeAllocation(this, mNativeObject);
            }
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final @NonNull Creator<Transaction> CREATOR = new Creator<Transaction>() {
                    @Override
                    public Transaction createFromParcel(Parcel in) {
                        return new Transaction(in);
                    }
                    @Override
                    public Transaction[] newArray(int size) {
                        return new Transaction[size];
                    }
                };
    }

    /**
     * A debugging utility subclass of SurfaceControl.Transaction. At construction
     * you can pass in a monitor object, and all the other methods will throw an exception
     * if the monitor is not held when they are called.
     * @hide
     */
    public static class LockDebuggingTransaction extends SurfaceControl.Transaction {
        Object mMonitor;

        public LockDebuggingTransaction(Object o) {
            mMonitor = o;
        }

        @Override
        protected void checkPreconditions(SurfaceControl sc) {
            super.checkPreconditions(sc);
            if (!Thread.holdsLock(mMonitor)) {
                throw new RuntimeException(
                        "Unlocked access to synchronized SurfaceControl.Transaction");
            }
        }
    }
}
