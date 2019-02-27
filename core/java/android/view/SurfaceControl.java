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
import android.annotation.UnsupportedAppUsage;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.GraphicBuffer;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.HardwareBuffer;
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
    private static native void nativeDestroy(long nativeObject);
    private static native void nativeDisconnect(long nativeObject);

    private static native GraphicBuffer nativeScreenshot(IBinder displayToken,
            Rect sourceCrop, int width, int height, boolean useIdentityTransform, int rotation,
            boolean captureSecureLayers);
    private static native GraphicBuffer nativeCaptureLayers(IBinder layerHandleToken,
            Rect sourceCrop, float frameScale);

    private static native long nativeCreateTransaction();
    private static native long nativeGetNativeTransactionFinalizer();
    private static native void nativeApplyTransaction(long transactionObj, boolean sync);
    private static native void nativeMergeTransaction(long transactionObj,
            long otherTransactionObj);
    private static native void nativeSetAnimationTransaction(long transactionObj);
    private static native void nativeSetEarlyWakeup(long transactionObj);

    private static native void nativeSetLayer(long transactionObj, long nativeObject, int zorder);
    private static native void nativeSetRelativeLayer(long transactionObj, long nativeObject,
            IBinder relativeTo, int zorder);
    private static native void nativeSetPosition(long transactionObj, long nativeObject,
            float x, float y);
    private static native void nativeSetGeometryAppliesWithResize(long transactionObj,
            long nativeObject);
    private static native void nativeSetSize(long transactionObj, long nativeObject, int w, int h);
    private static native void nativeSetTransparentRegionHint(long transactionObj,
            long nativeObject, Region region);
    private static native void nativeSetAlpha(long transactionObj, long nativeObject, float alpha);
    private static native void nativeSetMatrix(long transactionObj, long nativeObject,
            float dsdx, float dtdx,
            float dtdy, float dsdy);
    private static native void nativeSetColorTransform(long transactionObj, long nativeObject,
            float[] matrix, float[] translation);
    private static native void nativeSetGeometry(long transactionObj, long nativeObject,
            Rect sourceCrop, Rect dest, long orientation);
    private static native void nativeSetColor(long transactionObj, long nativeObject, float[] color);
    private static native void nativeSetFlags(long transactionObj, long nativeObject,
            int flags, int mask);
    private static native void nativeSetWindowCrop(long transactionObj, long nativeObject,
            int l, int t, int r, int b);
    private static native void nativeSetCornerRadius(long transactionObj, long nativeObject,
            float cornerRadius);
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
    private static native SurfaceControl.PhysicalDisplayInfo[] nativeGetDisplayConfigs(
            IBinder displayToken);
    private static native DisplayedContentSamplingAttributes
            nativeGetDisplayedContentSamplingAttributes(IBinder displayToken);
    private static native boolean nativeSetDisplayedContentSamplingEnabled(IBinder displayToken,
            boolean enable, int componentMask, int maxFrames);
    private static native DisplayedContentSample nativeGetDisplayedContentSample(
            IBinder displayToken, long numFrames, long timestamp);
    private static native int nativeGetActiveConfig(IBinder displayToken);
    private static native boolean nativeSetActiveConfig(IBinder displayToken, int id);
    private static native boolean nativeSetAllowedDisplayConfigs(IBinder displayToken,
                                                                 int[] allowedConfigs);
    private static native int[] nativeGetDisplayColorModes(IBinder displayToken);
    private static native SurfaceControl.DisplayPrimaries nativeGetDisplayNativePrimaries(
            IBinder displayToken);
    private static native int[] nativeGetCompositionDataspaces();
    private static native int nativeGetActiveColorMode(IBinder displayToken);
    private static native boolean nativeSetActiveColorMode(IBinder displayToken,
            int colorMode);
    private static native void nativeSetDisplayPowerMode(
            IBinder displayToken, int mode);
    private static native void nativeDeferTransactionUntil(long transactionObj, long nativeObject,
            IBinder handle, long frame);
    private static native void nativeDeferTransactionUntilSurface(long transactionObj,
            long nativeObject,
            long surfaceObject, long frame);
    private static native void nativeReparentChildren(long transactionObj, long nativeObject,
            IBinder handle);
    private static native void nativeReparent(long transactionObj, long nativeObject,
            long newParentNativeObject);
    private static native void nativeSeverChildren(long transactionObj, long nativeObject);
    private static native void nativeSetOverrideScalingMode(long transactionObj, long nativeObject,
            int scalingMode);
    private static native IBinder nativeGetHandle(long nativeObject);
    private static native boolean nativeGetTransformToDisplayInverse(long nativeObject);

    private static native Display.HdrCapabilities nativeGetHdrCapabilities(IBinder displayToken);

    private static native void nativeSetInputWindowInfo(long transactionObj, long nativeObject,
            InputWindowHandle handle);
    private static native void nativeTransferTouchFocus(long transactionObj, IBinder fromToken,
            IBinder toToken);
    private static native boolean nativeGetProtectedContentSupport();
    private static native void nativeSetMetadata(long transactionObj, int key, Parcel data);
    private static native void nativeSyncInputWindows(long transactionObj);

    private final CloseGuard mCloseGuard = CloseGuard.get();
    private String mName;
    long mNativeObject; // package visibility only for Surface.java access

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
     * Surface creation flag: Creates a Dim surface.
     * Everything behind this surface is dimmed by the amount specified
     * in {@link #setAlpha}.  It is an error to lock a Dim surface, since it
     * doesn't have a backing store.
     *
     * @hide
     */
    public static final int FX_SURFACE_DIM = 0x00020000;

    /**
     * Surface creation flag: Creates a container surface.
     * This surface will have no buffers and will only be used
     * as a container for other surfaces, or for its InputInfo.
     * @hide
     */
    public static final int FX_SURFACE_CONTAINER = 0x00080000;

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
     * Builder class for {@link SurfaceControl} objects.
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
        public SurfaceControl build() {
            if (mWidth < 0 || mHeight < 0) {
                throw new IllegalArgumentException(
                        "width and height must be positive or unset");
            }
            if ((mWidth > 0 || mHeight > 0) && (isColorLayerSet() || isContainerLayerSet())) {
                throw new IllegalArgumentException(
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
        public Builder setName(String name) {
            mName = name;
            return this;
        }

        /**
         * Set the initial size of the controlled surface's buffers in pixels.
         *
         * @param width The buffer width in pixels.
         * @param height The buffer height in pixels.
         */
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
            return setFlags(FX_SURFACE_DIM, FX_SURFACE_MASK);
        }

        private boolean isColorLayerSet() {
            return  (mFlags & FX_SURFACE_DIM) == FX_SURFACE_DIM;
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
     * and position, call {@link #show} if appropriate, and close the transaction.
     * <p>
     * Bounds of the surface is determined by its crop and its buffer size. If the
     * surface has no buffer or crop, the surface is boundless and only constrained
     * by the size of its parent bounds.
     *
     * @param session The surface session, must not be null.
     * @param name The surface name, must not be null.
     * @param w The surface initial width.
     * @param h The surface initial height.
     * @param flags The surface creation flags.  Should always include {@link #HIDDEN}
     * in the creation flags.
     * @param metadata Initial metadata.
     *
     * @throws throws OutOfResourcesException If the SurfaceControl cannot be created.
     */
    private SurfaceControl(SurfaceSession session, String name, int w, int h, int format, int flags,
            SurfaceControl parent, SparseIntArray metadata)
                    throws OutOfResourcesException, IllegalArgumentException {
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
        mCloseGuard.open("release");
    }

    /**
     * @hide
     */
    public SurfaceControl() {
        mCloseGuard.open("release");
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
    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(HASH_CODE, System.identityHashCode(this));
        proto.write(NAME, mName);
        proto.end(token);
    }

    public static final Creator<SurfaceControl> CREATOR
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
     * {@link Transaction#reparent} with a null-parent.
     *
     * Always call release() when you're done with a SurfaceControl.
     */
    public void release() {
        if (mNativeObject != 0) {
            nativeRelease(mNativeObject);
            mNativeObject = 0;
        }
        mCloseGuard.close();
    }

    /**
     * Release the local resources like {@link #release} but also
     * remove the Surface from the screen.
     * @hide
     */
    public void remove() {
        if (mNativeObject != 0) {
            nativeDestroy(mNativeObject);
            mNativeObject = 0;
        }
        mCloseGuard.close();
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
     * example this may be false if construction failed, or the layer was released.
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

    private static void closeTransaction(boolean sync) {
        synchronized(SurfaceControl.class) {
            if (sTransactionNestCount == 0) {
                Log.e(TAG, "Call to SurfaceControl.closeTransaction without matching openTransaction");
            } else if (--sTransactionNestCount > 0) {
                return;
            }
            sGlobalTransaction.apply(sync);
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
        closeTransaction(false);
    }

    /**
     * @hide
     */
    public static void closeTransactionSync() {
        closeTransaction(true);
    }

    /**
     * @hide
     */
    public void deferTransactionUntil(IBinder handle, long frame) {
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.deferTransactionUntil(this, handle, frame);
        }
    }

    /**
     * @hide
     */
    public void deferTransactionUntil(Surface barrier, long frame) {
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.deferTransactionUntilSurface(this, barrier, frame);
        }
    }

    /**
     * @hide
     */
    public void reparentChildren(IBinder newParentHandle) {
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.reparentChildren(this, newParentHandle);
        }
    }

    /**
     * @hide
     */
    public void reparent(SurfaceControl newParent) {
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.reparent(this, newParent);
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
    public IBinder getHandle() {
        return nativeGetHandle(mNativeObject);
    }

    /**
     * @hide
     */
    public static void setAnimationTransaction() {
        synchronized (SurfaceControl.class) {
            sGlobalTransaction.setAnimationTransaction();
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
    public void setRelativeLayer(SurfaceControl relativeTo, int zorder) {
        checkNotReleased();
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.setRelativeLayer(this, relativeTo, zorder);
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
    public void setGeometryAppliesWithResize() {
        checkNotReleased();
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.setGeometryAppliesWithResize(this);
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
    public void setColor(@Size(3) float[] color) {
        checkNotReleased();
        synchronized (SurfaceControl.class) {
            sGlobalTransaction.setColor(this, color);
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
     * Sets the transform and position of a {@link SurfaceControl} from a 3x3 transformation matrix.
     *
     * @param matrix The matrix to apply.
     * @param float9 An array of 9 floats to be used to extract the values from the matrix.
     * @hide
     */
    public void setMatrix(Matrix matrix, float[] float9) {
        checkNotReleased();
        matrix.getValues(float9);
        synchronized (SurfaceControl.class) {
            sGlobalTransaction.setMatrix(this, float9[MSCALE_X], float9[MSKEW_Y],
                    float9[MSKEW_X], float9[MSCALE_Y]);
            sGlobalTransaction.setPosition(this, float9[MTRANS_X], float9[MTRANS_Y]);
        }
    }

    /**
     * Sets the color transform for the Surface.
     * @param matrix A float array with 9 values represents a 3x3 transform matrix
     * @param translation A float array with 3 values represents a translation vector
     * @hide
     */
    public void setColorTransform(@Size(9) float[] matrix, @Size(3) float[] translation) {
        checkNotReleased();
        synchronized (SurfaceControl.class) {
            sGlobalTransaction.setColorTransform(this, matrix, translation);
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
     * Same as {@link SurfaceControl#setWindowCrop(Rect)} but sets the crop rect top left at 0, 0.
     *
     * @param width width of crop rect
     * @param height height of crop rect
     * @hide
     */
    public void setWindowCrop(int width, int height) {
        checkNotReleased();
        synchronized (SurfaceControl.class) {
            sGlobalTransaction.setWindowCrop(this, width, height);
        }
    }

    /**
     * Sets the corner radius of a {@link SurfaceControl}.
     *
     * @param cornerRadius Corner radius in pixels.
     * @hide
     */
    public void setCornerRadius(float cornerRadius) {
        checkNotReleased();
        synchronized (SurfaceControl.class) {
            sGlobalTransaction.setCornerRadius(this, cornerRadius);
        }
    }

    /**
     * @hide
     */
    public void setLayerStack(int layerStack) {
        checkNotReleased();
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.setLayerStack(this, layerStack);
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

    /*
     * set display parameters.
     * needs to be inside open/closeTransaction block
     */

    /**
     * Describes the properties of a physical display known to surface flinger.
     * @hide
     */
    public static final class PhysicalDisplayInfo {
        /**
         * @hide
         */
        @UnsupportedAppUsage
        public int width;

        /**
         * @hide
         */
        @UnsupportedAppUsage
        public int height;

        /**
         * @hide
         */
        @UnsupportedAppUsage
        public float refreshRate;

        /**
         * @hide
         */
        @UnsupportedAppUsage
        public float density;

        /**
         * @hide
         */
        @UnsupportedAppUsage
        public float xDpi;

        /**
         * @hide
         */
        @UnsupportedAppUsage
        public float yDpi;

        /**
         * @hide
         */
        @UnsupportedAppUsage
        public boolean secure;

        /**
         * @hide
         */
        @UnsupportedAppUsage
        public long appVsyncOffsetNanos;

        /**
         * @hide
         */
        @UnsupportedAppUsage
        public long presentationDeadlineNanos;

        /**
         * @hide
         */
        @UnsupportedAppUsage
        public PhysicalDisplayInfo() {
        }

        /**
         * @hide
         */
        public PhysicalDisplayInfo(PhysicalDisplayInfo other) {
            copyFrom(other);
        }

        /**
         * @hide
         */
        @Override
        public boolean equals(Object o) {
            return o instanceof PhysicalDisplayInfo && equals((PhysicalDisplayInfo)o);
        }

        /**
         * @hide
         */
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

        /**
         * @hide
         */
        @Override
        public int hashCode() {
            return 0; // don't care
        }

        /**
         * @hide
         */
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

        /**
         * @hide
         */
        @Override
        public String toString() {
            return "PhysicalDisplayInfo{" + width + " x " + height + ", " + refreshRate + " fps, "
                    + "density " + density + ", " + xDpi + " x " + yDpi + " dpi, secure " + secure
                    + ", appVsyncOffset " + appVsyncOffsetNanos
                    + ", bufferDeadline " + presentationDeadlineNanos + "}";
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
    @UnsupportedAppUsage
    public static SurfaceControl.PhysicalDisplayInfo[] getDisplayConfigs(IBinder displayToken) {
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
     * @hide
     */
    public static boolean setActiveConfig(IBinder displayToken, int id) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        return nativeSetActiveConfig(displayToken, id);
    }

    /**
     * @hide
     */
    public static boolean setAllowedDisplayConfigs(IBinder displayToken, int[] allowedConfigs) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        if (allowedConfigs == null) {
            throw new IllegalArgumentException("allowedConfigs must not be null");
        }

        return nativeSetAllowedDisplayConfigs(displayToken, allowedConfigs);
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
     * TODO(116025192): Remove this stopgap once framework is display-agnostic.
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

        final GraphicBuffer buffer = screenshotToBuffer(display, sourceCrop, width, height,
                useIdentityTransform, rotation);
        try {
            consumer.attachAndQueueBuffer(buffer);
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
        final GraphicBuffer buffer = screenshotToBuffer(displayToken, sourceCrop, width, height,
                useIdentityTransform, rotation);

        if (buffer == null) {
            Log.w(TAG, "Failed to take screenshot");
            return null;
        }
        // TODO(b/116112787) Now that hardware bitmap creation can take color space, we
        // should continue to fix screenshot.
        return Bitmap.wrapHardwareBuffer(HardwareBuffer.createFromGraphicBuffer(buffer),
                ColorSpace.get(ColorSpace.Named.SRGB));
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
    public static GraphicBuffer screenshotToBuffer(IBinder display, Rect sourceCrop, int width,
            int height, boolean useIdentityTransform, int rotation) {
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
    public static GraphicBuffer screenshotToBufferWithSecureLayersUnsafe(IBinder display,
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
     * @param layerHandleToken The root layer to capture.
     * @param sourceCrop       The portion of the root surface to capture; caller may pass in 'new
     *                         Rect()' or null if no cropping is desired.
     * @param frameScale       The desired scale of the returned buffer; the raw
     *                         screen will be scaled up/down.
     *
     * @return Returns a GraphicBuffer that contains the layer capture.
     * @hide
     */
    public static GraphicBuffer captureLayers(IBinder layerHandleToken, Rect sourceCrop,
            float frameScale) {
        return nativeCaptureLayers(layerHandleToken, sourceCrop, frameScale);
    }

    /**
     * Returns whether protected content is supported in GPU composition.
     * @hide
     */
    public static boolean getProtectedContentSupport() {
        return nativeGetProtectedContentSupport();
    }

    /**
     * An atomic set of changes to a set of SurfaceControl.
     */
    public static class Transaction implements Closeable {
        /**
         * @hide
         */
        public static final NativeAllocationRegistry sRegistry = new NativeAllocationRegistry(
                Transaction.class.getClassLoader(),
                nativeGetNativeTransactionFinalizer(), 512);
        private long mNativeObject;

        private final ArrayMap<SurfaceControl, Point> mResizedSurfaces = new ArrayMap<>();
        Runnable mFreeNativeResources;

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

        /**
         * Apply the transaction, clearing it's state, and making it usable
         * as a new transaction.
         */
        public void apply() {
            apply(false);
        }

        /**
         * Close the transaction, if the transaction was not already applied this will cancel the
         * transaction.
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
            sc.checkNotReleased();
            if (visible) {
                return show(sc);
            } else {
                return hide(sc);
            }
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
            sc.checkNotReleased();
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
            sc.checkNotReleased();
            nativeSetFlags(mNativeObject, sc.mNativeObject, SURFACE_HIDDEN, SURFACE_HIDDEN);
            return this;
        }

        /**
         * @hide
         */
        @UnsupportedAppUsage
        public Transaction setPosition(SurfaceControl sc, float x, float y) {
            sc.checkNotReleased();
            nativeSetPosition(mNativeObject, sc.mNativeObject, x, y);
            return this;
        }

        /**
         * Set the default buffer size for the SurfaceControl, if there is an
         * {@link Surface} assosciated with the control, then
         * this will be the default size for buffers dequeued from it.
         * @param sc The surface to set the buffer size for.
         * @param w The default width
         * @param h The default height
         * @return This Transaction
         */
        @NonNull
        public Transaction setBufferSize(@NonNull SurfaceControl sc,
                @IntRange(from = 0) int w, @IntRange(from = 0) int h) {
            sc.checkNotReleased();
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
            sc.checkNotReleased();
            nativeSetLayer(mNativeObject, sc.mNativeObject, z);
            return this;
        }

        /**
         * @hide
         */
        public Transaction setRelativeLayer(SurfaceControl sc, SurfaceControl relativeTo, int z) {
            sc.checkNotReleased();
            nativeSetRelativeLayer(mNativeObject, sc.mNativeObject,
                    relativeTo.getHandle(), z);
            return this;
        }

        /**
         * @hide
         */
        public Transaction setTransparentRegionHint(SurfaceControl sc, Region transparentRegion) {
            sc.checkNotReleased();
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
            sc.checkNotReleased();
            nativeSetAlpha(mNativeObject, sc.mNativeObject, alpha);
            return this;
        }

        /**
         * @hide
         */
        public Transaction setInputWindowInfo(SurfaceControl sc, InputWindowHandle handle) {
            sc.checkNotReleased();
            nativeSetInputWindowInfo(mNativeObject, sc.mNativeObject, handle);
            return this;
        }

        /**
         * Transfers touch focus from one window to another. It is possible for multiple windows to
         * have touch focus if they support split touch dispatch
         * {@link android.view.WindowManager.LayoutParams#FLAG_SPLIT_TOUCH} but this
         * method only transfers touch focus of the specified window without affecting
         * other windows that may also have touch focus at the same time.
         * @param fromToken The token of a window that currently has touch focus.
         * @param toToken The token of the window that should receive touch focus in
         * place of the first.
         * @hide
         */
        public Transaction transferTouchFocus(IBinder fromToken, IBinder toToken) {
            nativeTransferTouchFocus(mNativeObject, fromToken, toToken);
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
            sc.checkNotReleased();
            nativeSetGeometry(mNativeObject, sc.mNativeObject, sourceCrop, destFrame, orientation);
            return this;
        }

        /**
         * @hide
         */
        @UnsupportedAppUsage
        public Transaction setMatrix(SurfaceControl sc,
                float dsdx, float dtdx, float dtdy, float dsdy) {
            sc.checkNotReleased();
            nativeSetMatrix(mNativeObject, sc.mNativeObject,
                    dsdx, dtdx, dtdy, dsdy);
            return this;
        }

        /**
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
         * @param matrix A float array with 9 values represents a 3x3 transform matrix
         * @param translation A float array with 3 values represents a translation vector
         * @hide
         */
        public Transaction setColorTransform(SurfaceControl sc, @Size(9) float[] matrix,
                @Size(3) float[] translation) {
            sc.checkNotReleased();
            nativeSetColorTransform(mNativeObject, sc.mNativeObject, matrix, translation);
            return this;
        }

        /**
         * @hide
         */
        @UnsupportedAppUsage
        public Transaction setWindowCrop(SurfaceControl sc, Rect crop) {
            sc.checkNotReleased();
            if (crop != null) {
                nativeSetWindowCrop(mNativeObject, sc.mNativeObject,
                        crop.left, crop.top, crop.right, crop.bottom);
            } else {
                nativeSetWindowCrop(mNativeObject, sc.mNativeObject, 0, 0, 0, 0);
            }

            return this;
        }

        /**
         * @hide
         */
        public Transaction setWindowCrop(SurfaceControl sc, int width, int height) {
            sc.checkNotReleased();
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
            sc.checkNotReleased();
            nativeSetCornerRadius(mNativeObject, sc.mNativeObject, cornerRadius);

            return this;
        }

        /**
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.O)
        public Transaction setLayerStack(SurfaceControl sc, int layerStack) {
            sc.checkNotReleased();
            nativeSetLayerStack(mNativeObject, sc.mNativeObject, layerStack);
            return this;
        }

        /**
         * @hide
         */
        @UnsupportedAppUsage
        public Transaction deferTransactionUntil(SurfaceControl sc, IBinder handle,
                long frameNumber) {
            if (frameNumber < 0) {
                return this;
            }
            sc.checkNotReleased();
            nativeDeferTransactionUntil(mNativeObject, sc.mNativeObject, handle, frameNumber);
            return this;
        }

        /**
         * @hide
         */
        @UnsupportedAppUsage
        public Transaction deferTransactionUntilSurface(SurfaceControl sc, Surface barrierSurface,
                long frameNumber) {
            if (frameNumber < 0) {
                return this;
            }
            sc.checkNotReleased();
            nativeDeferTransactionUntilSurface(mNativeObject, sc.mNativeObject,
                    barrierSurface.mNativeObject, frameNumber);
            return this;
        }

        /**
         * @hide
         */
        public Transaction reparentChildren(SurfaceControl sc, IBinder newParentHandle) {
            sc.checkNotReleased();
            nativeReparentChildren(mNativeObject, sc.mNativeObject, newParentHandle);
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
            sc.checkNotReleased();
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
            sc.checkNotReleased();
            nativeSeverChildren(mNativeObject, sc.mNativeObject);
            return this;
        }

        /**
         * @hide
         */
        public Transaction setOverrideScalingMode(SurfaceControl sc, int overrideScalingMode) {
            sc.checkNotReleased();
            nativeSetOverrideScalingMode(mNativeObject, sc.mNativeObject,
                    overrideScalingMode);
            return this;
        }

        /**
         * Sets a color for the Surface.
         * @param color A float array with three values to represent r, g, b in range [0..1]
         * @hide
         */
        @UnsupportedAppUsage
        public Transaction setColor(SurfaceControl sc, @Size(3) float[] color) {
            sc.checkNotReleased();
            nativeSetColor(mNativeObject, sc.mNativeObject, color);
            return this;
        }

        /**
         * If the buffer size changes in this transaction, position and crop updates specified
         * in this transaction will not complete until a buffer of the new size
         * arrives. As transform matrix and size are already frozen in this fashion,
         * this enables totally freezing the surface until the resize has completed
         * (at which point the geometry influencing aspects of this transaction will then occur)
         * @hide
         */
        public Transaction setGeometryAppliesWithResize(SurfaceControl sc) {
            sc.checkNotReleased();
            nativeSetGeometryAppliesWithResize(mNativeObject, sc.mNativeObject);
            return this;
        }

        /**
         * Sets the security of the surface.  Setting the flag is equivalent to creating the
         * Surface with the {@link #SECURE} flag.
         * @hide
         */
        public Transaction setSecure(SurfaceControl sc, boolean isSecure) {
            sc.checkNotReleased();
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
            sc.checkNotReleased();
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
        public Transaction setMetadata(int key, int data) {
            Parcel parcel = Parcel.obtain();
            parcel.writeInt(data);
            try {
                setMetadata(key, parcel);
            } finally {
                parcel.recycle();
            }
            return this;
        }

        /**
         * Sets an arbitrary piece of metadata on the surface.
         * @hide
         */
        public Transaction setMetadata(int key, Parcel data) {
            nativeSetMetadata(mNativeObject, key, data);
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
    }
}
