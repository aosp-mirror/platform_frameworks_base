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
import static android.view.Display.INVALID_DISPLAY;
import static android.view.SurfaceControlProto.HASH_CODE;
import static android.view.SurfaceControlProto.LAYER_ID;
import static android.view.SurfaceControlProto.NAME;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.Size;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.graphics.ColorSpace;
import android.graphics.GraphicBuffer;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.gui.DropInputMode;
import android.gui.StalledTransactionInfo;
import android.hardware.DataSpace;
import android.hardware.HardwareBuffer;
import android.hardware.OverlayProperties;
import android.hardware.SyncFence;
import android.hardware.display.DeviceProductInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerGlobal;
import android.hardware.display.DisplayedContentSample;
import android.hardware.display.DisplayedContentSamplingAttributes;
import android.hardware.display.IDisplayManager;
import android.hardware.display.IVirtualDisplayCallback;
import android.hardware.display.VirtualDisplay;
import android.hardware.graphics.common.DisplayDecorationSupport;
import android.opengl.EGLDisplay;
import android.opengl.EGLSync;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.proto.ProtoOutputStream;
import android.view.Surface.OutOfResourcesException;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.internal.util.VirtualRefBasePtr;

import dalvik.system.CloseGuard;

import libcore.util.NativeAllocationRegistry;

import java.io.Closeable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

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
    private static native long nativeGetNativeSurfaceControlFinalizer();
    private static native void nativeDisconnect(long nativeObject);
    private static native void nativeUpdateDefaultBufferSize(long nativeObject, int width, int height);

    private static native long nativeMirrorSurface(long mirrorOfObject);
    private static native long nativeCreateTransaction();
    private static native long nativeGetNativeTransactionFinalizer();
    private static native void nativeApplyTransaction(long transactionObj, boolean sync,
            boolean oneWay);
    private static native void nativeMergeTransaction(long transactionObj,
            long otherTransactionObj);
    private static native void nativeClearTransaction(long transactionObj);
    private static native void nativeSetAnimationTransaction(long transactionObj);
    private static native void nativeSetEarlyWakeupStart(long transactionObj);
    private static native void nativeSetEarlyWakeupEnd(long transactionObj);
    private static native long nativeGetTransactionId(long transactionObj);

    private static native void nativeSetLayer(long transactionObj, long nativeObject, int zorder);
    private static native void nativeSetRelativeLayer(long transactionObj, long nativeObject,
            long relativeToObject, int zorder);
    private static native void nativeSetPosition(long transactionObj, long nativeObject,
            float x, float y);
    private static native void nativeSetScale(long transactionObj, long nativeObject,
            float x, float y);
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
    private static native void nativeSetBlurRegions(long transactionObj, long nativeObj,
            float[][] regions, int length);
    private static native void nativeSetStretchEffect(long transactionObj, long nativeObj,
            float width, float height, float vecX, float vecY,
            float maxStretchAmountX, float maxStretchAmountY, float childRelativeLeft,
            float childRelativeTop, float childRelativeRight, float childRelativeBottom);
    private static native void nativeSetTrustedOverlay(long transactionObj, long nativeObject,
            boolean isTrustedOverlay);
    private static native void nativeSetDropInputMode(
            long transactionObj, long nativeObject, int flags);
    private static native void nativeSurfaceFlushJankData(long nativeSurfaceObject);
    private static native boolean nativeClearContentFrameStats(long nativeObject);
    private static native boolean nativeGetContentFrameStats(long nativeObject, WindowContentFrameStats outStats);
    private static native boolean nativeClearAnimationFrameStats();
    private static native boolean nativeGetAnimationFrameStats(WindowAnimationFrameStats outStats);

    private static native void nativeSetDisplaySurface(long transactionObj,
            IBinder displayToken, long nativeSurfaceObject);
    private static native void nativeSetDisplayLayerStack(long transactionObj,
            IBinder displayToken, int layerStack);
    private static native void nativeSetDisplayFlags(long transactionObj,
            IBinder displayToken, int flags);
    private static native void nativeSetDisplayProjection(long transactionObj,
            IBinder displayToken, int orientation,
            int l, int t, int r, int b,
            int L, int T, int R, int B);
    private static native void nativeSetDisplaySize(long transactionObj, IBinder displayToken,
            int width, int height);
    private static native StaticDisplayInfo nativeGetStaticDisplayInfo(long displayId);
    private static native DynamicDisplayInfo nativeGetDynamicDisplayInfo(long displayId);
    private static native DisplayedContentSamplingAttributes
            nativeGetDisplayedContentSamplingAttributes(IBinder displayToken);
    private static native boolean nativeSetDisplayedContentSamplingEnabled(IBinder displayToken,
            boolean enable, int componentMask, int maxFrames);
    private static native DisplayedContentSample nativeGetDisplayedContentSample(
            IBinder displayToken, long numFrames, long timestamp);
    private static native boolean nativeSetDesiredDisplayModeSpecs(IBinder displayToken,
            DesiredDisplayModeSpecs desiredDisplayModeSpecs);
    private static native DesiredDisplayModeSpecs
            nativeGetDesiredDisplayModeSpecs(IBinder displayToken);
    private static native DisplayPrimaries nativeGetDisplayNativePrimaries(
            IBinder displayToken);
    private static native int[] nativeGetCompositionDataspaces();
    private static native OverlayProperties nativeGetOverlaySupport();
    private static native boolean nativeSetActiveColorMode(IBinder displayToken,
            int colorMode);
    private static native boolean nativeGetBootDisplayModeSupport();
    private static native void nativeSetBootDisplayMode(IBinder displayToken, int displayMode);
    private static native void nativeClearBootDisplayMode(IBinder displayToken);
    private static native void nativeSetAutoLowLatencyMode(IBinder displayToken, boolean on);
    private static native void nativeSetGameContentType(IBinder displayToken, boolean on);
    private static native void nativeSetDisplayPowerMode(
            IBinder displayToken, int mode);
    private static native void nativeReparent(long transactionObj, long nativeObject,
            long newParentNativeObject);
    private static native void nativeSetBuffer(long transactionObj, long nativeObject,
            HardwareBuffer buffer, long fencePtr, Consumer<SyncFence> releaseCallback);
    private static native void nativeUnsetBuffer(long transactionObj, long nativeObject);
    private static native void nativeSetBufferTransform(long transactionObj, long nativeObject,
            int transform);
    private static native void nativeSetDataSpace(long transactionObj, long nativeObject,
            @DataSpace.NamedDataSpace int dataSpace);
    private static native void nativeSetExtendedRangeBrightness(long transactionObj,
            long nativeObject, float currentBufferRatio, float desiredRatio);

    private static native void nativeSetCachingHint(long transactionObj,
            long nativeObject, int cachingHint);
    private static native void nativeSetDamageRegion(long transactionObj, long nativeObject,
            Region region);
    private static native void nativeSetDimmingEnabled(long transactionObj, long nativeObject,
            boolean dimmingEnabled);

    private static native void nativeSetInputWindowInfo(long transactionObj, long nativeObject,
            InputWindowHandle handle);

    private static native boolean nativeGetProtectedContentSupport();
    private static native void nativeSetMetadata(long transactionObj, long nativeObject, int key,
            Parcel data);
    private static native void nativeAddWindowInfosReportedListener(long transactionObj,
            Runnable listener);
    private static native boolean nativeGetDisplayBrightnessSupport(IBinder displayToken);
    private static native boolean nativeSetDisplayBrightness(IBinder displayToken,
            float sdrBrightness, float sdrBrightnessNits, float displayBrightness,
            float displayBrightnessNits);
    private static native long nativeReadTransactionFromParcel(Parcel in);
    private static native void nativeWriteTransactionToParcel(long nativeObject, Parcel out);
    private static native void nativeSetShadowRadius(long transactionObj, long nativeObject,
            float shadowRadius);
    private static native void nativeSetGlobalShadowSettings(@Size(4) float[] ambientColor,
            @Size(4) float[] spotColor, float lightPosY, float lightPosZ, float lightRadius);
    private static native DisplayDecorationSupport nativeGetDisplayDecorationSupport(
            IBinder displayToken);

    private static native void nativeSetFrameRate(long transactionObj, long nativeObject,
            float frameRate, int compatibility, int changeFrameRateStrategy);
    private static native void nativeSetDefaultFrameRateCompatibility(long transactionObj,
            long nativeObject, int compatibility);
    private static native void nativeSetFrameRateCategory(
            long transactionObj, long nativeObject, int category, boolean smoothSwitchOnly);
    private static native void nativeSetFrameRateSelectionStrategy(
            long transactionObj, long nativeObject, int strategy);
    private static native long nativeGetHandle(long nativeObject);

    private static native void nativeSetFixedTransformHint(long transactionObj, long nativeObject,
            int transformHint);
    private static native void nativeRemoveCurrentInputFocus(long nativeObject, int displayId);
    private static native void nativeSetFocusedWindow(long transactionObj, IBinder toToken,
            String windowName, int displayId);
    private static native void nativeSetFrameTimelineVsync(long transactionObj,
            long frameTimelineVsyncId);
    private static native void nativeAddJankDataListener(long nativeListener,
            long nativeSurfaceControl);
    private static native void nativeRemoveJankDataListener(long nativeListener);
    private static native long nativeCreateJankDataListenerWrapper(OnJankDataListener listener);
    private static native int nativeGetGPUContextPriority();
    private static native void nativeSetTransformHint(long nativeObject,
            @SurfaceControl.BufferTransform int transformHint);
    private static native int nativeGetTransformHint(long nativeObject);
    private static native int nativeGetLayerId(long nativeObject);
    private static native void nativeAddTransactionCommittedListener(long nativeObject,
            TransactionCommittedListener listener);
    private static native void nativeSanitize(long transactionObject, int pid, int uid);
    private static native void nativeSetDestinationFrame(long transactionObj, long nativeObject,
            int l, int t, int r, int b);
    private static native void nativeSetDefaultApplyToken(IBinder token);
    private static native IBinder nativeGetDefaultApplyToken();
    private static native boolean nativeBootFinished();
    private static native long nativeCreateTpc(TrustedPresentationCallback callback);
    private static native long getNativeTrustedPresentationCallbackFinalizer();
    private static native void nativeSetTrustedPresentationCallback(long transactionObj,
            long nativeObject, long nativeTpc, TrustedPresentationThresholds thresholds);
    private static native void nativeClearTrustedPresentationCallback(long transactionObj,
            long nativeObject);
    private static native StalledTransactionInfo nativeGetStalledTransactionInfo(int pid);

    /**
     * Transforms that can be applied to buffers as they are displayed to a window.
     *
     * Supported transforms are any combination of horizontal mirror, vertical mirror, and
     * clock-wise 90 degree rotation, in that order. Rotations of 180 and 270 degrees are made up
     * of those basic transforms.
     * Mirrors {@code ANativeWindowTransform} definitions.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"BUFFER_TRANSFORM_"},
            value = {BUFFER_TRANSFORM_IDENTITY, BUFFER_TRANSFORM_MIRROR_HORIZONTAL,
                    BUFFER_TRANSFORM_MIRROR_VERTICAL, BUFFER_TRANSFORM_ROTATE_90,
                    BUFFER_TRANSFORM_ROTATE_180, BUFFER_TRANSFORM_ROTATE_270,
                    BUFFER_TRANSFORM_MIRROR_HORIZONTAL | BUFFER_TRANSFORM_ROTATE_90,
                    BUFFER_TRANSFORM_MIRROR_VERTICAL | BUFFER_TRANSFORM_ROTATE_90})
    public @interface BufferTransform {
    }

    /**
     * Identity transform.
     *
     * These transforms that can be applied to buffers as they are displayed to a window.
     * @see HardwareBuffer
     *
     * Supported transforms are any combination of horizontal mirror, vertical mirror, and
     * clock-wise 90 degree rotation, in that order. Rotations of 180 and 270 degrees are
     * made up of those basic transforms.
     */
    public static final int BUFFER_TRANSFORM_IDENTITY = 0x00;
    /**
     * Mirror horizontally. Can be combined with {@link #BUFFER_TRANSFORM_MIRROR_VERTICAL}
     * and {@link #BUFFER_TRANSFORM_ROTATE_90}.
     */
    public static final int BUFFER_TRANSFORM_MIRROR_HORIZONTAL = 0x01;
    /**
     * Mirror vertically. Can be combined with {@link #BUFFER_TRANSFORM_MIRROR_HORIZONTAL}
     * and {@link #BUFFER_TRANSFORM_ROTATE_90}.
     */
    public static final int BUFFER_TRANSFORM_MIRROR_VERTICAL = 0x02;
    /**
     * Rotate 90 degrees clock-wise. Can be combined with {@link
     * #BUFFER_TRANSFORM_MIRROR_HORIZONTAL} and {@link #BUFFER_TRANSFORM_MIRROR_VERTICAL}.
     */
    public static final int BUFFER_TRANSFORM_ROTATE_90 = 0x04;
    /**
     * Rotate 180 degrees clock-wise. Cannot be combined with other transforms.
     */
    public static final int BUFFER_TRANSFORM_ROTATE_180 =
            BUFFER_TRANSFORM_MIRROR_HORIZONTAL | BUFFER_TRANSFORM_MIRROR_VERTICAL;
    /**
     * Rotate 270 degrees clock-wise. Cannot be combined with other transforms.
     */
    public static final int BUFFER_TRANSFORM_ROTATE_270 =
            BUFFER_TRANSFORM_ROTATE_180 | BUFFER_TRANSFORM_ROTATE_90;

    /**
     * @hide
     */
    public static @BufferTransform int rotationToBufferTransform(@Surface.Rotation int rotation) {
        switch (rotation) {
            case Surface.ROTATION_0: return BUFFER_TRANSFORM_IDENTITY;
            case Surface.ROTATION_90: return BUFFER_TRANSFORM_ROTATE_90;
            case Surface.ROTATION_180: return BUFFER_TRANSFORM_ROTATE_180;
            case Surface.ROTATION_270: return BUFFER_TRANSFORM_ROTATE_270;
        }
        Log.e(TAG, "Trying to convert unknown rotation=" + rotation);
        return BUFFER_TRANSFORM_IDENTITY;
    }

    @Nullable
    @GuardedBy("mLock")
    private ArrayList<OnReparentListener> mReparentListeners;

    /**
     * Listener to observe surface reparenting.
     *
     * @hide
     */
    public interface OnReparentListener {

        /**
         * Callback for reparenting surfaces.
         *
         * Important: You should only interact with the provided surface control
         * only if you have a contract with its owner to avoid them closing it
         * under you or vise versa.
         *
         * @param transaction The transaction that would commit reparenting.
         * @param parent The future parent surface.
         */
        void onReparent(@NonNull Transaction transaction, @Nullable SurfaceControl parent);
    }

    /**
     * Jank information to be fed back via {@link OnJankDataListener}.
     * @hide
     */
    public static class JankData {

        /** @hide */
        @IntDef(flag = true, value = {JANK_NONE,
                DISPLAY_HAL,
                JANK_SURFACEFLINGER_DEADLINE_MISSED,
                JANK_SURFACEFLINGER_GPU_DEADLINE_MISSED,
                JANK_APP_DEADLINE_MISSED,
                PREDICTION_ERROR,
                SURFACE_FLINGER_SCHEDULING})
        @Retention(RetentionPolicy.SOURCE)
        public @interface JankType {}

        // Needs to be kept in sync with frameworks/native/libs/gui/include/gui/JankInfo.h

        // No Jank
        public static final int JANK_NONE = 0x0;

        // Jank not related to SurfaceFlinger or the App
        public static final int DISPLAY_HAL = 0x1;
        // SF took too long on the CPU
        public static final int JANK_SURFACEFLINGER_DEADLINE_MISSED = 0x2;
        // SF took too long on the GPU
        public static final int JANK_SURFACEFLINGER_GPU_DEADLINE_MISSED = 0x4;
        // Either App or GPU took too long on the frame
        public static final int JANK_APP_DEADLINE_MISSED = 0x8;
        // Vsync predictions have drifted beyond the threshold from the actual HWVsync
        public static final int PREDICTION_ERROR = 0x10;
        // Latching a buffer early might cause an early present of the frame
        public static final int SURFACE_FLINGER_SCHEDULING = 0x20;
        // A buffer is said to be stuffed if it was expected to be presented on a vsync but was
        // presented later because the previous buffer was presented in its expected vsync. This
        // usually happens if there is an unexpectedly long frame causing the rest of the buffers
        // to enter a stuffed state.
        public static final int BUFFER_STUFFING = 0x40;
        // Jank due to unknown reasons.
        public static final int UNKNOWN = 0x80;

        public JankData(long frameVsyncId, @JankType int jankType, long frameIntervalNs) {
            this.frameVsyncId = frameVsyncId;
            this.jankType = jankType;
            this.frameIntervalNs = frameIntervalNs;

        }

        public final long frameVsyncId;
        public final @JankType int jankType;
        public final long frameIntervalNs;
    }

    /**
     * Listener interface to be informed about SurfaceFlinger's jank classification for a specific
     * surface.
     *
     * @see JankData
     * @see #addJankDataListener
     * @hide
     */
    public static abstract class OnJankDataListener {
        private final VirtualRefBasePtr mNativePtr;

        public OnJankDataListener() {
            mNativePtr = new VirtualRefBasePtr(nativeCreateJankDataListenerWrapper(this));
        }

        /**
         * Called when new jank classifications are available.
         */
        public abstract void onJankDataAvailable(JankData[] jankStats);
    }

    private final CloseGuard mCloseGuard = CloseGuard.get();
    private String mName;
    private String mCallsite;

     /**
     * Note: do not rename, this field is used by native code.
     * @hide
     */
    public long mNativeObject;
    private long mNativeHandle;

    private final Object mChoreographerLock = new Object();
    @GuardedBy("mChoreographerLock")
    private Choreographer mChoreographer;

    // TODO: Move width/height to native and fix locking through out.
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private int mWidth;
    @GuardedBy("mLock")
    private int mHeight;

    private TrustedPresentationCallback mTrustedPresentationCallback;

    private WeakReference<View> mLocalOwnerView;

    // A throwable with the stack filled when this SurfaceControl is released (only if
    // sDebugUsageAfterRelease) is enabled
    private Throwable mReleaseStack = null;

    // Triggers the stack to be saved when any SurfaceControl in this process is released, which can
    // be dumped as additional context
    private static volatile boolean sDebugUsageAfterRelease = false;

    static GlobalTransactionWrapper sGlobalTransaction;
    static long sTransactionNestCount = 0;

    private static final NativeAllocationRegistry sRegistry =
            NativeAllocationRegistry.createMalloced(SurfaceControl.class.getClassLoader(),
                    nativeGetNativeSurfaceControlFinalizer());

    private Runnable mFreeNativeResources;

    /**
     * Adds a reparenting listener.
     *
     * @param listener The listener.
     * @return Whether listener was added.
     *
     * @hide
     */
    public boolean addOnReparentListener(@NonNull OnReparentListener listener) {
        synchronized (mLock) {
            if (mReparentListeners == null) {
                mReparentListeners = new ArrayList<>(1);
            }
            return mReparentListeners.add(listener);
        }
    }

    /**
     * Removes a reparenting listener.
     *
     * @param listener The listener.
     * @return Whether listener was removed.
     *
     * @hide
     */
    public boolean removeOnReparentListener(@NonNull OnReparentListener listener) {
        synchronized (mLock) {
            final boolean removed = mReparentListeners.remove(listener);
            if (mReparentListeners.isEmpty()) {
                mReparentListeners = null;
            }
            return removed;
        }
    }

    /* flags used in constructor (keep in sync with ISurfaceComposerClient.h) */

    /**
     * Surface creation flag: Surface is created hidden
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int HIDDEN = 0x00000004;

    /**
     * Surface creation flag: Skip this layer and its children when taking a screenshot. This
     * also includes mirroring and screen recording, so the layers with flag SKIP_SCREENSHOT
     * will not be included on non primary displays.
     * @hide
     */
    public static final int SKIP_SCREENSHOT = 0x00000040;

    /**
     * Surface creation flag: Special measures will be taken to disallow the surface's content to
     * be copied. In particular, screenshots and secondary, non-secure displays will render black
     * content instead of the surface content.
     *
     * @see com.android.server.display.DisplayControl#createDisplay(String, boolean)
     * @hide
     */
    public static final int SECURE = 0x00000080;


    /**
     * Queue up BufferStateLayer buffers instead of dropping the oldest buffer when this flag is
     * set. This blocks the client until all the buffers have been presented. If the buffers
     * have presentation timestamps, then we may drop buffers.
     * @hide
     */
    public static final int ENABLE_BACKPRESSURE = 0x00000100;

    /**
     * Buffers from this SurfaceControl should be considered display decorations.
     *
     * If the hardware has optimizations for display decorations (e.g. rounded corners, camera
     * cutouts, etc), it should use them for this layer.
     * @hide
     */
    public static final int DISPLAY_DECORATION = 0x00000200;

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
     * Surface creation flag: Indicates the effect layer will not have a color fill on
     * creation.
     *
     * @hide
     */
    public static final int NO_COLOR_FILL = 0x00004000;

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

    /* flags used with setDisplayFlags() (keep in sync with DisplayDevice.h) */

    /**
     * DisplayDevice flag: This display's transform is sent to inputflinger and used for input
     * dispatch. This flag is used to disambiguate displays which share a layerstack.
     * @hide
     */
    public static final int DISPLAY_RECEIVES_INPUT = 0x01;

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
     * Hint that this SurfaceControl should not participate in layer caching within SurfaceFlinger.
     *
     * A system layer may request that a layer does not participate in caching when there are known
     * quality limitations when caching via the compositor's GPU path.
     * Use only with {@link SurfaceControl.Transaction#setCachingHint}.
     * @hide
     */
    public static final int CACHING_DISABLED = 0;

    /**
     * Hint that this SurfaceControl should participate in layer caching within SurfaceFlinger.
     *
     * Use only with {@link SurfaceControl.Transaction#setCachingHint}.
     * @hide
     */
    public static final int CACHING_ENABLED = 1;

    /** @hide */
    @IntDef(flag = true, value = {CACHING_DISABLED, CACHING_ENABLED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface CachingHint {}

    private void assignNativeObject(long nativeObject, String callsite) {
        if (mNativeObject != 0) {
            release();
        }
        if (nativeObject != 0) {
            mFreeNativeResources =
                    sRegistry.registerNativeAllocation(this, nativeObject);
        }
        mNativeObject = nativeObject;
        mNativeHandle = mNativeObject != 0 ? nativeGetHandle(nativeObject) : 0;
        if (sDebugUsageAfterRelease && mNativeObject == 0) {
            mReleaseStack = new Throwable("Assigned invalid nativeObject");
        } else {
            mReleaseStack = null;
        }
        setUnreleasedWarningCallSite(callsite);
        if (nativeObject != 0) {
            // Only add valid surface controls to the registry. This is called at the end of this
            // method since its information is dumped if the process threshold is reached.
            SurfaceControlRegistry.getProcessInstance().add(this);
        }
    }

    /**
     * @hide
     */
    public void copyFrom(@NonNull SurfaceControl other, String callsite) {
        mName = other.mName;
        mWidth = other.mWidth;
        mHeight = other.mHeight;
        mLocalOwnerView = other.mLocalOwnerView;
        assignNativeObject(nativeCopyFromSurfaceControl(other.mNativeObject), callsite);
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
     * owner PID.
     * @hide
     */
    public static final int METADATA_OWNER_PID = 6;

    /**
     * game mode for the layer - used for metrics
     * @hide
     */
    public static final int METADATA_GAME_MODE = 8;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"FRAME_RATE_SELECTION_STRATEGY_"},
            value = {FRAME_RATE_SELECTION_STRATEGY_SELF,
                    FRAME_RATE_SELECTION_STRATEGY_OVERRIDE_CHILDREN})
    public @interface FrameRateSelectionStrategy {}

    // From window.h. Keep these in sync.
    /**
     * Default value. The layer uses its own frame rate specifications, assuming it has any
     * specifications, instead of its parent's.
     * However, {@link #FRAME_RATE_SELECTION_STRATEGY_OVERRIDE_CHILDREN} on an ancestor layer
     * supersedes this behavior, meaning that this layer will inherit the frame rate specifications
     * of that ancestor layer.
     * @hide
     */
    public static final int FRAME_RATE_SELECTION_STRATEGY_SELF = 0;

    /**
     * The layer's frame rate specifications will propagate to and override those of its descendant
     * layers.
     * The layer with this strategy has the {@link #FRAME_RATE_SELECTION_STRATEGY_SELF} behavior
     * for itself. This does mean that any parent or ancestor layer that also has the strategy
     * {@link FRAME_RATE_SELECTION_STRATEGY_OVERRIDE_CHILDREN} will override this layer's
     * frame rate specifications.
     * @hide
     */
    public static final int FRAME_RATE_SELECTION_STRATEGY_OVERRIDE_CHILDREN = 1;

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
        private WeakReference<View> mLocalOwnerView;
        private SurfaceControl mParent;
        private SparseIntArray mMetadata;
        private String mCallsite = "SurfaceControl.Builder";

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
            if ((mWidth > 0 || mHeight > 0) && (isEffectLayer() || isContainerLayer())) {
                throw new IllegalStateException(
                        "Only buffer layers can set a valid buffer size.");
            }

            if (mName == null) {
                Log.w(TAG, "Missing name for SurfaceControl", new Throwable());
            }

            if ((mFlags & FX_SURFACE_MASK) == FX_SURFACE_NORMAL) {
                setBLASTLayer();
            }

            return new SurfaceControl(
                    mSession, mName, mWidth, mHeight, mFormat, mFlags, mParent, mMetadata,
                    mLocalOwnerView, mCallsite);
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
         * Set the local owner view for the surface. This view is only
         * valid in the same process and is not transferred in an IPC.
         *
         * Note: This is used for cases where we want to know the view
         * that manages the surface control while intercepting reparenting.
         * A specific example is InlineContentView which exposes is surface
         * control for reparenting as a way to implement clipping of several
         * InlineContentView instances within a certain area.
         *
         * @param view The owner view.
         * @return This builder.
         *
         * @hide
         */
        @NonNull
        public Builder setLocalOwnerView(@NonNull View view) {
            mLocalOwnerView = new WeakReference<>(view);
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
         * Indicate whether an 'EffectLayer' is to be constructed.
         *
         * An effect layer behaves like a container layer by default but it can support
         * color fill, shadows and/or blur. These layers will not have an associated buffer.
         * When created, this layer has no effects set and will be transparent but the caller
         * can render an effect by calling:
         *  - {@link Transaction#setColor(SurfaceControl, float[])}
         *  - {@link Transaction#setBackgroundBlurRadius(SurfaceControl, int)}
         *  - {@link Transaction#setShadowRadius(SurfaceControl, float)}
         *
         * @hide
         */
        public Builder setEffectLayer() {
            mFlags |= NO_COLOR_FILL;
            unsetBufferSize();
            return setFlags(FX_SURFACE_EFFECT, FX_SURFACE_MASK);
        }

        /**
         * A convenience function to create an effect layer with a default color fill
         * applied to it. Currently that color is black.
         *
         * @hide
         */
        public Builder setColorLayer() {
            unsetBufferSize();
            return setFlags(FX_SURFACE_EFFECT, FX_SURFACE_MASK);
        }

        private boolean isEffectLayer() {
            return  (mFlags & FX_SURFACE_EFFECT) == FX_SURFACE_EFFECT;
        }

        /**
         * @hide
         */
        public Builder setBLASTLayer() {
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

        private boolean isContainerLayer() {
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

        /**
         * Sets the callsite this SurfaceControl is constructed from.
         *
         * @param callsite String uniquely identifying callsite that created this object. Used for
         *                 leakage tracking.
         * @hide
         */
        public Builder setCallsite(String callsite) {
            mCallsite = callsite;
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
     * @param callsite String uniquely identifying callsite that created this object. Used for
     *                 leakage tracking.
     * @throws throws OutOfResourcesException If the SurfaceControl cannot be created.
     */
    private SurfaceControl(SurfaceSession session, String name, int w, int h, int format, int flags,
            SurfaceControl parent, SparseIntArray metadata, WeakReference<View> localOwnerView,
            String callsite)
                    throws OutOfResourcesException, IllegalArgumentException {
        if (name == null) {
            throw new IllegalArgumentException("name must not be null");
        }

        mName = name;
        mWidth = w;
        mHeight = h;
        mLocalOwnerView = localOwnerView;
        Parcel metaParcel = Parcel.obtain();
        long nativeObject = 0;
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
            nativeObject = nativeCreate(session, name, w, h, format, flags,
                    parent != null ? parent.mNativeObject : 0, metaParcel);
        } finally {
            metaParcel.recycle();
        }
        if (nativeObject == 0) {
            throw new OutOfResourcesException(
                    "Couldn't allocate SurfaceControl native object");
        }
        assignNativeObject(nativeObject, callsite);
    }

    /**
     * Copy constructor. Creates a new native object pointing to the same surface as {@code other}.
     *
     * @param other The object to copy the surface from.
     * @param callsite String uniquely identifying callsite that created this object. Used for
     *                 leakage tracking.
     * @hide
     */
    @TestApi
    public SurfaceControl(@NonNull SurfaceControl other, @NonNull String callsite) {
        copyFrom(other, callsite);
    }

    private SurfaceControl(Parcel in) {
        readFromParcel(in);
    }

    /**
     * Note: Most callers should use {@link SurfaceControl.Builder} or one of the other constructors
     *       to build an instance of a SurfaceControl. This constructor is mainly used for
     *       unparceling and passing into an AIDL call as an out parameter.
     * @hide
     */
    public SurfaceControl() {
    }

    public void readFromParcel(Parcel in) {
        if (in == null) {
            throw new IllegalArgumentException("source must not be null");
        }

        mName = in.readString8();
        mWidth = in.readInt();
        mHeight = in.readInt();

        long object = 0;
        if (in.readInt() != 0) {
            object = nativeReadFromParcel(in);
        }
        assignNativeObject(object, "readFromParcel");
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (sDebugUsageAfterRelease) {
            checkNotReleased();
        }
        dest.writeString8(mName);
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
     * Enables additional debug logs to track usage-after-release of all SurfaceControls in this
     * process.
     * @hide
     */
    public static void setDebugUsageAfterRelease(boolean debug) {
        if (!Build.isDebuggable()) {
            return;
        }
        sDebugUsageAfterRelease = debug;
    }

    /**
     * Provides more information to show about the source of this SurfaceControl if it is finalized
     * without being released. This is primarily intended for callers to update the call site after
     * receiving a SurfaceControl from another process, which would otherwise get a generic default
     * call site.
     * @hide
     */
    public void setUnreleasedWarningCallSite(@NonNull String callsite) {
        if (!isValid()) {
            return;
        }
        mCloseGuard.openWithCallSite("release", callsite);
        mCallsite = callsite;
    }

    /**
     * Returns the last provided call site when this SurfaceControl was created.
     * @hide
     */
    @Nullable String getCallsite() {
        return mCallsite;
    }

    /**
     * Returns the name of this SurfaceControl, mainly for debugging purposes.
     * @hide
     */
    @NonNull String getName() {
        return mName;
    }

    /**
     * Checks whether two {@link SurfaceControl} objects represent the same surface.
     *
     * @param other The other object to check
     * @return {@code true} if these two {@link SurfaceControl} objects represent the same surface.
     * @hide
     */
    @TestApi
    public boolean isSameSurface(@NonNull SurfaceControl other) {
        return other.mNativeHandle == mNativeHandle;
    }

    /**
     * When called for the first time a new instance of the {@link Choreographer} is created
     * with a {@link android.os.Looper} of the current thread. Every subsequent call will return
     * the same instance of the Choreographer.
     *
     * @see #getChoreographer(Looper) to create Choreographer with a different
     * looper than current thread looper.
     *
     * @hide
     */
    @TestApi
    public @NonNull Choreographer getChoreographer() {
        checkNotReleased();
        synchronized (mChoreographerLock) {
            if (mChoreographer == null) {
                return getChoreographer(Looper.myLooper());
            }
            return mChoreographer;
        }
    }

    /**
     * When called for the first time a new instance of the {@link Choreographer} is created with
     * the sourced {@link android.os.Looper}. Every subsequent call will return the same
     * instance of the Choreographer.
     *
     * @see #getChoreographer()
     *
     * @throws IllegalStateException when a {@link Choreographer} instance exists with a different
     * looper than sourced.
     * @param looper the choreographer is attached on this looper.
     *
     * @hide
     */
    @TestApi
    public @NonNull Choreographer getChoreographer(@NonNull Looper looper) {
        checkNotReleased();
        synchronized (mChoreographerLock) {
            if (mChoreographer == null) {
                mChoreographer = Choreographer.getInstanceForSurfaceControl(mNativeHandle, looper);
            } else if (!mChoreographer.isTheLooperSame(looper)) {
                throw new IllegalStateException(
                        "Choreographer already exists with a different looper");
            }
            return mChoreographer;
        }
    }

    /**
     * Returns true if {@link Choreographer} is present otherwise false.
     * To check the validity use {@link #isValid} on the SurfaceControl, a valid SurfaceControl with
     * choreographer will have the valid Choreographer.
     *
     * @hide
     */
    @TestApi
    @UnsupportedAppUsage
    public boolean hasChoreographer() {
        synchronized (mChoreographerLock) {
            return mChoreographer != null;
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
        proto.write(LAYER_ID, getLayerId());
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
            SurfaceControlRegistry.getProcessInstance().remove(this);
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
            if (SurfaceControlRegistry.sCallStackDebuggingEnabled) {
                SurfaceControlRegistry.getProcessInstance().checkCallStackDebugging(
                        "release", null, this, null);
            }
            mFreeNativeResources.run();
            mNativeObject = 0;
            mNativeHandle = 0;
            if (sDebugUsageAfterRelease) {
                mReleaseStack = new Throwable("Released");
            }
            mCloseGuard.close();
            synchronized (mChoreographerLock) {
                if (mChoreographer != null) {
                    mChoreographer.invalidate();
                    mChoreographer = null;
                }
            }
            SurfaceControlRegistry.getProcessInstance().remove(this);
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
        if (mNativeObject == 0) {
            if (mReleaseStack != null) {
                throw new IllegalStateException("Invalid usage after release of " + this,
                        mReleaseStack);
            } else {
                throw new NullPointerException("mNativeObject of " + this
                        + " is null. Have you called release() already?");
            }
        }
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
                sGlobalTransaction = new GlobalTransactionWrapper();
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
            sGlobalTransaction.applyGlobalTransaction(false);
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
    public int getWidth() {
        synchronized (mLock) {
            return mWidth;
        }
    }

    /**
     * @hide
     */
    public int getHeight() {
        synchronized (mLock) {
            return mHeight;
        }
    }

    /**
     * Gets the local view that owns this surface.
     *
     * @return The owner view.
     *
     * @hide
     */
    public @Nullable View getLocalOwnerView() {
        return (mLocalOwnerView != null) ? mLocalOwnerView.get() : null;
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
    public static final class StaticDisplayInfo {
        public boolean isInternal;
        public float density;
        public boolean secure;
        public DeviceProductInfo deviceProductInfo;
        public @Surface.Rotation int installOrientation;

        @Override
        public String toString() {
            return "StaticDisplayInfo{isInternal=" + isInternal
                    + ", density=" + density
                    + ", secure=" + secure
                    + ", deviceProductInfo=" + deviceProductInfo
                    + ", installOrientation=" + installOrientation + "}";
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            StaticDisplayInfo that = (StaticDisplayInfo) o;
            return isInternal == that.isInternal
                    && density == that.density
                    && secure == that.secure
                    && Objects.equals(deviceProductInfo, that.deviceProductInfo)
                    && installOrientation == that.installOrientation;
        }

        @Override
        public int hashCode() {
            return Objects.hash(isInternal, density, secure, deviceProductInfo, installOrientation);
        }
    }

    /**
     * Dynamic information about physical display.
     *
     * @hide
     */
    public static final class DynamicDisplayInfo {
        public DisplayMode[] supportedDisplayModes;
        public int activeDisplayModeId;
        public float renderFrameRate;

        public int[] supportedColorModes;
        public int activeColorMode;

        public Display.HdrCapabilities hdrCapabilities;

        public boolean autoLowLatencyModeSupported;
        public boolean gameContentTypeSupported;

        public int preferredBootDisplayMode;

        @Override
        public String toString() {
            return "DynamicDisplayInfo{"
                    + "supportedDisplayModes=" + Arrays.toString(supportedDisplayModes)
                    + ", activeDisplayModeId=" + activeDisplayModeId
                    + ", renderFrameRate=" + renderFrameRate
                    + ", supportedColorModes=" + Arrays.toString(supportedColorModes)
                    + ", activeColorMode=" + activeColorMode
                    + ", hdrCapabilities=" + hdrCapabilities
                    + ", autoLowLatencyModeSupported=" + autoLowLatencyModeSupported
                    + ", gameContentTypeSupported" + gameContentTypeSupported
                    + ", preferredBootDisplayMode" + preferredBootDisplayMode + "}";
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DynamicDisplayInfo that = (DynamicDisplayInfo) o;
            return Arrays.equals(supportedDisplayModes, that.supportedDisplayModes)
                && activeDisplayModeId == that.activeDisplayModeId
                && renderFrameRate == that.renderFrameRate
                && Arrays.equals(supportedColorModes, that.supportedColorModes)
                && activeColorMode == that.activeColorMode
                && Objects.equals(hdrCapabilities, that.hdrCapabilities)
                && preferredBootDisplayMode == that.preferredBootDisplayMode;
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(supportedDisplayModes), activeDisplayModeId,
                    renderFrameRate, activeColorMode, hdrCapabilities);
        }
    }

    /**
     * Configuration supported by physical display.
     *
     * @hide
     */
    public static final class DisplayMode {
        public int id;
        public int width;
        public int height;
        public float xDpi;
        public float yDpi;

        // Some modes have peak refresh rate lower than the panel vsync rate.
        public float peakRefreshRate;
        // Fixed rate of vsync deadlines for the panel.
        // This can be higher then the peak refresh rate for some panel technologies
        // See: VrrConfig.aidl
        public float vsyncRate;
        public long appVsyncOffsetNanos;
        public long presentationDeadlineNanos;
        public int[] supportedHdrTypes;

        /**
         * The config group ID this config is associated to.
         * Configs in the same group are similar from vendor's perspective and switching between
         * configs within the same group can be done seamlessly in most cases.
         * @see: android.hardware.graphics.composer@2.4::IComposerClient::Attribute::CONFIG_GROUP
         */
        public int group;

        @Override
        public String toString() {
            return "DisplayMode{id=" + id
                    + ", width=" + width
                    + ", height=" + height
                    + ", xDpi=" + xDpi
                    + ", yDpi=" + yDpi
                    + ", peakRefreshRate=" + peakRefreshRate
                    + ", vsyncRate=" + vsyncRate
                    + ", appVsyncOffsetNanos=" + appVsyncOffsetNanos
                    + ", presentationDeadlineNanos=" + presentationDeadlineNanos
                    + ", supportedHdrTypes=" + Arrays.toString(supportedHdrTypes)
                    + ", group=" + group + "}";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DisplayMode that = (DisplayMode) o;
            return id == that.id
                    && width == that.width
                    && height == that.height
                    && Float.compare(that.xDpi, xDpi) == 0
                    && Float.compare(that.yDpi, yDpi) == 0
                    && Float.compare(that.peakRefreshRate, peakRefreshRate) == 0
                    && Float.compare(that.vsyncRate, vsyncRate) == 0
                    && appVsyncOffsetNanos == that.appVsyncOffsetNanos
                    && presentationDeadlineNanos == that.presentationDeadlineNanos
                    && Arrays.equals(supportedHdrTypes, that.supportedHdrTypes)
                    && group == that.group;
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, width, height, xDpi, yDpi, peakRefreshRate, vsyncRate,
                    appVsyncOffsetNanos, presentationDeadlineNanos, group,
                    Arrays.hashCode(supportedHdrTypes));
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
    public static StaticDisplayInfo getStaticDisplayInfo(long displayId) {
        return nativeGetStaticDisplayInfo(displayId);
    }

    /**
     * @hide
     */
    public static DynamicDisplayInfo getDynamicDisplayInfo(long displayId) {
        return nativeGetDynamicDisplayInfo(displayId);
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
     * Information about the min and max refresh rate DM would like to set the display to.
     * @hide
     */
    public static final class RefreshRateRange implements Parcelable {
        public static final String TAG = "RefreshRateRange";

        // The tolerance within which we consider something approximately equals.
        public static final float FLOAT_TOLERANCE = 0.01f;

        /**
         * The lowest desired refresh rate.
         */
        public float min;

        /**
         * The highest desired refresh rate.
         */
        public float max;

        public RefreshRateRange() {}

        public RefreshRateRange(float min, float max) {
            if (min < 0 || max < 0 || min > max + FLOAT_TOLERANCE) {
                Slog.e(TAG, "Wrong values for min and max when initializing RefreshRateRange : "
                        + min + " " + max);
                this.min = this.max = 0;
                return;
            }
            if (min > max) {
                // Min and max are within epsilon of each other, but in the wrong order.
                float t = min;
                min = max;
                max = t;
            }
            this.min = min;
            this.max = max;
        }

        /**
         * Checks whether the two objects have the same values.
         */
        @Override
        public boolean equals(Object other) {
            if (other == this) {
                return true;
            }

            if (!(other instanceof RefreshRateRange)) {
                return false;
            }

            RefreshRateRange refreshRateRange = (RefreshRateRange) other;
            return (min == refreshRateRange.min && max == refreshRateRange.max);
        }

        @Override
        public int hashCode() {
            return Objects.hash(min, max);
        }

        @Override
        public String toString() {
            return "(" + min + " " + max + ")";
        }

        /**
         * Copies the supplied object's values to this object.
         */
        public void copyFrom(RefreshRateRange other) {
            this.min = other.min;
            this.max = other.max;
        }

        /**
         * Writes the RefreshRateRange to parce
         *
         * @param dest parcel to write the transaction to
         */
        @Override
        public void writeToParcel(@NonNull Parcel dest, @WriteFlags int flags) {
            dest.writeFloat(min);
            dest.writeFloat(max);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final @NonNull Creator<RefreshRateRange> CREATOR =
                new Creator<RefreshRateRange>() {
                    @Override
                    public RefreshRateRange createFromParcel(Parcel in) {
                        return new RefreshRateRange(in.readFloat(), in.readFloat());
                    }

                    @Override
                    public RefreshRateRange[] newArray(int size) {
                        return new RefreshRateRange[size];
                    }
                };
    }

    /**
     * Information about the ranges of refresh rates for the display physical refresh rates and the
     * render frame rate DM would like to set the policy to.
     * @hide
     */
    public static final class RefreshRateRanges {
        public static final String TAG = "RefreshRateRanges";

        /**
         *  The range of refresh rates that the display should run at.
         */
        public final RefreshRateRange physical;

        /**
         *  The range of refresh rates that apps should render at.
         */
        public final RefreshRateRange render;

        public RefreshRateRanges() {
            physical = new RefreshRateRange();
            render = new RefreshRateRange();
        }

        public RefreshRateRanges(RefreshRateRange physical, RefreshRateRange render) {
            this.physical = new RefreshRateRange(physical.min, physical.max);
            this.render = new RefreshRateRange(render.min, render.max);
        }

        /**
         * Checks whether the two objects have the same values.
         */
        @Override
        public boolean equals(Object other) {
            if (other == this) {
                return true;
            }

            if (!(other instanceof RefreshRateRanges)) {
                return false;
            }

            RefreshRateRanges rates = (RefreshRateRanges) other;
            return physical.equals(rates.physical) && render.equals(
                    rates.render);
        }

        @Override
        public int hashCode() {
            return Objects.hash(physical, render);
        }

        @Override
        public String toString() {
            return "physical: " + physical + " render:  " + render;
        }

        /**
         * Copies the supplied object's values to this object.
         */
        public void copyFrom(RefreshRateRanges other) {
            this.physical.copyFrom(other.physical);
            this.render.copyFrom(other.render);
        }
    }


    /**
     * Contains information about desired display configuration.
     *
     * @hide
     */
    public static final class DesiredDisplayModeSpecs {
        public int defaultMode;
        /**
         * If true this will allow switching between modes in different display configuration
         * groups. This way the user may see visual interruptions when the display mode changes.
         */
        public boolean allowGroupSwitching;

        /**
         * The primary physical and render refresh rate ranges represent display manager's general
         * guidance on the display configs surface flinger will consider when switching refresh
         * rates and scheduling the frame rate. Unless surface flinger has a specific reason to do
         * otherwise, it will stay within this range.
         */
        public final RefreshRateRanges primaryRanges;

        /**
         * The app request physical and render refresh rate ranges allow surface flinger to consider
         * more display configs when switching refresh rates. Although surface flinger will
         * generally stay within the primary range, specific considerations, such as layer frame
         * rate settings specified via the setFrameRate() api, may cause surface flinger to go
         * outside the primary range. Surface flinger never goes outside the app request range.
         * The app request range will be greater than or equal to the primary refresh rate range,
         * never smaller.
         */
        public final RefreshRateRanges appRequestRanges;

        public DesiredDisplayModeSpecs() {
            this.primaryRanges = new RefreshRateRanges();
            this.appRequestRanges = new RefreshRateRanges();
        }

        public DesiredDisplayModeSpecs(DesiredDisplayModeSpecs other) {
            this.primaryRanges = new RefreshRateRanges();
            this.appRequestRanges = new RefreshRateRanges();
            copyFrom(other);
        }

        public DesiredDisplayModeSpecs(int defaultMode, boolean allowGroupSwitching,
                RefreshRateRanges primaryRanges, RefreshRateRanges appRequestRanges) {
            this.defaultMode = defaultMode;
            this.allowGroupSwitching = allowGroupSwitching;
            this.primaryRanges =
                    new RefreshRateRanges(primaryRanges.physical, primaryRanges.render);
            this.appRequestRanges =
                    new RefreshRateRanges(appRequestRanges.physical, appRequestRanges.render);
        }

        @Override
        public boolean equals(@Nullable Object o) {
            return o instanceof DesiredDisplayModeSpecs && equals((DesiredDisplayModeSpecs) o);
        }

        /**
         * Tests for equality.
         */
        public boolean equals(DesiredDisplayModeSpecs other) {
            return other != null && defaultMode == other.defaultMode
                    && allowGroupSwitching == other.allowGroupSwitching
                    && primaryRanges.equals(other.primaryRanges)
                    && appRequestRanges.equals(other.appRequestRanges);
        }

        @Override
        public int hashCode() {
            return 0; // don't care
        }

        /**
         * Copies the supplied object's values to this object.
         */
        public void copyFrom(DesiredDisplayModeSpecs other) {
            defaultMode = other.defaultMode;
            allowGroupSwitching = other.allowGroupSwitching;
            primaryRanges.copyFrom(other.primaryRanges);
            appRequestRanges.copyFrom(other.appRequestRanges);
        }

        @Override
        public String toString() {
            return "defaultMode=" + defaultMode
                    + " allowGroupSwitching=" + allowGroupSwitching
                    + " primaryRanges=" + primaryRanges
                    + " appRequestRanges=" + appRequestRanges;
        }
    }

    /**
     * @hide
     */
    public static boolean setDesiredDisplayModeSpecs(IBinder displayToken,
            DesiredDisplayModeSpecs desiredDisplayModeSpecs) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        if (desiredDisplayModeSpecs == null) {
            throw new IllegalArgumentException("desiredDisplayModeSpecs must not be null");
        }
        if (desiredDisplayModeSpecs.defaultMode < 0) {
            throw new IllegalArgumentException("defaultMode must be non-negative");
        }

        return nativeSetDesiredDisplayModeSpecs(displayToken, desiredDisplayModeSpecs);
    }

    /**
     * @hide
     */
    public static DesiredDisplayModeSpecs getDesiredDisplayModeSpecs(
            IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }

        return nativeGetDesiredDisplayModeSpecs(displayToken);
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
    public static DisplayPrimaries getDisplayNativePrimaries(
            IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }

        return nativeGetDisplayNativePrimaries(displayToken);
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
                ColorSpace cs = ColorSpace.getFromDataSpace(dataspaces[i]);
                if (cs != null) {
                    colorSpaces[i] = cs;
                }
            }
        }
        return colorSpaces;
    }

    /**
     * @return the overlay properties of the device
     * @hide
     */
    public static OverlayProperties getOverlaySupport() {
        return nativeGetOverlaySupport();
    }

    /**
     * @hide
     */
    public static boolean getBootDisplayModeSupport() {
        return nativeGetBootDisplayModeSupport();
    }

    /** There is no associated getter for this method.  When this is set, the display is expected
     * to start up in this mode next time the device reboots.
     * @hide
     */
    public static void setBootDisplayMode(IBinder displayToken, int displayModeId) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }

        nativeSetBootDisplayMode(displayToken, displayModeId);
    }

    /**
     * @hide
     */
    public static void clearBootDisplayMode(IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }

        nativeClearBootDisplayMode(displayToken);
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
     * Because this API is now going through {@link DisplayManager}, orientation and displayRect
     * will automatically be computed based on configuration changes. Because of this, the params
     * orientation and displayRect are ignored
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.TIRAMISU,
            publicAlternatives = "Use {@code VirtualDisplay#resize(int, int, int)} instead.",
            trackingBug = 247078497)
    public static void setDisplayProjection(IBinder displayToken, int orientation,
            Rect layerStackRect, Rect displayRect) {
        DisplayManagerGlobal.getInstance().resizeVirtualDisplay(
                IVirtualDisplayCallback.Stub.asInterface(displayToken), layerStackRect.width(),
                layerStackRect.height(), 1);
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.TIRAMISU,
            publicAlternatives = "Use {@code MediaProjection#createVirtualDisplay()} with flag "
                    + " {@code VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR} for mirroring instead.",
            trackingBug = 247078497)
    public static void setDisplayLayerStack(IBinder displayToken, int layerStack) {
        IBinder b = ServiceManager.getService(Context.DISPLAY_SERVICE);
        if (b == null) {
            throw new UnsupportedOperationException();
        }

        IDisplayManager dm = IDisplayManager.Stub.asInterface(b);
        try {
            dm.setDisplayIdToMirror(displayToken, layerStack);
        } catch (RemoteException e) {
            throw new UnsupportedOperationException(e);
        }
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.TIRAMISU,
            publicAlternatives = "Use {@code VirtualDisplay#setSurface(Surface)} instead.",
            trackingBug = 247078497)
    public static void setDisplaySurface(IBinder displayToken, Surface surface) {
        IVirtualDisplayCallback virtualDisplayCallback =
                IVirtualDisplayCallback.Stub.asInterface(displayToken);
        DisplayManagerGlobal dm = DisplayManagerGlobal.getInstance();
        dm.setVirtualDisplaySurface(virtualDisplayCallback, surface);
    }

    /**
     * Secure is no longer supported because this is only called from outside system which cannot
     * create secure displays.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.TIRAMISU,
            publicAlternatives = "Use {@code MediaProjection#createVirtualDisplay()} or "
                    + "{@code DisplayManager#createVirtualDisplay()} instead.",
            trackingBug = 247078497)
    public static IBinder createDisplay(String name, boolean secure) {
        if (name == null) {
            throw new IllegalArgumentException("name must not be null");
        }

        // We don't have a size yet so pass in 1 for width and height since 0 is invalid
        VirtualDisplay vd = DisplayManager.createVirtualDisplay(name, 1 /* width */, 1 /* height */,
                INVALID_DISPLAY, null /* Surface */);
        return vd == null ? null : vd.getToken().asBinder();
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.TIRAMISU,
            publicAlternatives = "Use {@code VirtualDisplay#release()} instead.",
            trackingBug = 247078497)
    public static void destroyDisplay(IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }

        DisplayManagerGlobal.getInstance().releaseVirtualDisplay(
                IVirtualDisplayCallback.Stub.asInterface(displayToken));
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
        return setDisplayBrightness(displayToken, brightness, -1, brightness, -1);
    }

    /**
     * Sets the brightness of a display.
     *
     * @param displayToken
     *      The token for the display whose brightness is set.
     * @param sdrBrightness
     *      A number between 0.0f (minimum brightness) and 1.0f (maximum brightness), or -1.0f to
     *      turn the backlight off. Specifies the desired brightness of SDR content.
     * @param sdrBrightnessNits
     *      The value of sdrBrightness converted to calibrated nits. -1 if this isn't available.
     * @param displayBrightness
     *     A number between 0.0f (minimum brightness) and 1.0f (maximum brightness), or
     *     -1.0f to turn the backlight off. Specifies the desired brightness of the display itself,
     *     used directly for HDR content.
     * @param displayBrightnessNits
     *      The value of displayBrightness converted to calibrated nits. -1 if this isn't
     *      available.
     *
     * @return Whether the method succeeded or not.
     *
     * @throws IllegalArgumentException if:
     *      - displayToken is null;
     *      - brightness is NaN or greater than 1.0f.
     *
     * @hide
     */
    public static boolean setDisplayBrightness(IBinder displayToken, float sdrBrightness,
            float sdrBrightnessNits, float displayBrightness, float displayBrightnessNits) {
        Objects.requireNonNull(displayToken);
        if (Float.isNaN(displayBrightness) || displayBrightness > 1.0f
                || (displayBrightness < 0.0f && displayBrightness != -1.0f)) {
            throw new IllegalArgumentException("displayBrightness must be a number between 0.0f "
                    + " and 1.0f, or -1 to turn the backlight off: " + displayBrightness);
        }
        if (Float.isNaN(sdrBrightness) || sdrBrightness > 1.0f
                || (sdrBrightness < 0.0f && sdrBrightness != -1.0f)) {
            throw new IllegalArgumentException("sdrBrightness must be a number between 0.0f "
                    + "and 1.0f, or -1 to turn the backlight off: " + sdrBrightness);
        }
        return nativeSetDisplayBrightness(displayToken, sdrBrightness, sdrBrightnessNits,
                displayBrightness, displayBrightnessNits);
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
        sc.mName = mirrorOf.mName + " (mirror)";
        sc.assignNativeObject(nativeObj, "mirrorSurface");
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
     * Returns whether/how a display supports DISPLAY_DECORATION.
     *
     * @param displayToken
     *      The token for the display.
     *
     * @return A class describing how the display supports DISPLAY_DECORATION or null if it does
     * not.
     *
     * TODO (b/218524164): Move this out of SurfaceControl.
     * @hide
     */
    public static DisplayDecorationSupport getDisplayDecorationSupport(IBinder displayToken) {
        return nativeGetDisplayDecorationSupport(displayToken);
    }

    /**
     * Adds a callback to be informed about SF's jank classification for a specific surface.
     * @hide
     */
    public static void addJankDataListener(OnJankDataListener listener, SurfaceControl surface) {
        nativeAddJankDataListener(listener.mNativePtr.get(), surface.mNativeObject);
    }

    /**
     * Removes a jank callback previously added with {@link #addJankDataListener}
     * @hide
     */
    public static void removeJankDataListener(OnJankDataListener listener) {
        nativeRemoveJankDataListener(listener.mNativePtr.get());
    }

    /**
     * Return GPU Context priority that is set in SurfaceFlinger's Render Engine.
     * @hide
     */
    public static int getGPUContextPriority() {
        return nativeGetGPUContextPriority();
    }

    /**
     * Lets surfaceFlinger know the boot procedure is completed.
     * @hide
     */
    public static boolean bootFinished() {
        return nativeBootFinished();
    }

    /**
     * Interface to handle request to
     * {@link SurfaceControl.Transaction#addTransactionCommittedListener(Executor, TransactionCommittedListener)}
     */
    public interface TransactionCommittedListener {
        /**
         * Invoked when the transaction has been committed in SurfaceFlinger.
         */
        void onTransactionCommitted();
    }

    /**
     * Threshold values that are sent with
     * {@link Transaction#setTrustedPresentationCallback(SurfaceControl,
     * TrustedPresentationThresholds, Executor, Consumer)}
     */
    public static final class TrustedPresentationThresholds {
        private final float mMinAlpha;
        private final float mMinFractionRendered;
        private final int mStabilityRequirementMs;

        /**
         * Creates a TrustedPresentationThresholds that's used when calling
         * {@link Transaction#setTrustedPresentationCallback(SurfaceControl,
         * TrustedPresentationThresholds, Executor, Consumer)}
         *
         * @param minAlpha               The min alpha the {@link SurfaceControl} is required to
         *                               have to be considered inside the threshold.
         * @param minFractionRendered    The min fraction of the SurfaceControl that was presented
         *                               to the user to be considered inside the threshold.
         * @param stabilityRequirementMs The time in milliseconds required for the
         *                               {@link SurfaceControl} to be in the threshold.
         * @throws IllegalArgumentException If threshold values are invalid.
         */
        public TrustedPresentationThresholds(
                @FloatRange(from = 0f, fromInclusive = false, to = 1f) float minAlpha,
                @FloatRange(from = 0f, fromInclusive = false, to = 1f) float minFractionRendered,
                @IntRange(from = 1) int stabilityRequirementMs) {
            mMinAlpha = minAlpha;
            mMinFractionRendered = minFractionRendered;
            mStabilityRequirementMs = stabilityRequirementMs;

            checkValid();
        }

        private void checkValid() {
            if (mMinAlpha <= 0 || mMinFractionRendered <= 0 || mStabilityRequirementMs < 1) {
                throw new IllegalArgumentException(
                        "TrustedPresentationThresholds values are invalid");
            }
        }
    }

    /**
     * Register a TrustedPresentationCallback for a particular SurfaceControl so it can be notified
     * when the specified Threshold has been crossed.
     *
     * @hide
     */
    public abstract static class TrustedPresentationCallback {
        private final long mNativeObject;

        private static final NativeAllocationRegistry sRegistry =
                NativeAllocationRegistry.createMalloced(
                        TrustedPresentationCallback.class.getClassLoader(),
                        getNativeTrustedPresentationCallbackFinalizer());

        private final Runnable mFreeNativeResources;

        private TrustedPresentationCallback() {
            mNativeObject = nativeCreateTpc(this);
            mFreeNativeResources = sRegistry.registerNativeAllocation(this, mNativeObject);
        }

        /**
         * Invoked when the SurfaceControl that this TrustedPresentationCallback was registered for
         * enters or exits the threshold bounds.
         *
         * @param inTrustedPresentationState true when the SurfaceControl entered the
         *                                   presentation state, false when it has left.
         */
        public abstract void onTrustedPresentationChanged(boolean inTrustedPresentationState);
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
        private final ArrayMap<SurfaceControl, SurfaceControl> mReparentedSurfaces =
                 new ArrayMap<>();

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
            this(nativeCreateTransaction());
        }

        private Transaction(long nativeObject) {
            mNativeObject = nativeObject;
            mFreeNativeResources = sRegistry.registerNativeAllocation(this, mNativeObject);
            if (!SurfaceControlRegistry.sCallStackDebuggingInitialized) {
                SurfaceControlRegistry.initializeCallStackDebugging();
            }
        }

        private Transaction(Parcel in) {
            readFromParcel(in);
        }

        /**
         *
         * @hide
         */
        public static void setDefaultApplyToken(IBinder token) {
            nativeSetDefaultApplyToken(token);
        }

        /**
         *
         * @hide
         */
        public static IBinder getDefaultApplyToken() {
            return nativeGetDefaultApplyToken();
        }

        /**
         * Apply the transaction, clearing it's state, and making it usable
         * as a new transaction.
         */
        public void apply() {
            apply(/*sync*/ false);
        }

        /**
         * Applies the transaction as a one way binder call. This transaction will be applied out
         * of order with other transactions that are applied synchronously. This method is not
         * safe. It should only be used when the order does not matter.
         *
         * @hide
         */
        public void applyAsyncUnsafe() {
            apply(/*sync*/ false, /*oneWay*/ true);
        }


        /**
         * Clear the transaction object, without applying it.
         *
         * @hide
         */
        public void clear() {
            mResizedSurfaces.clear();
            mReparentedSurfaces.clear();
            if (mNativeObject != 0) {
                nativeClearTransaction(mNativeObject);
            }
        }

        /**
         * Release the native transaction object, without applying it.
         */
        @Override
        public void close() {
            mResizedSurfaces.clear();
            mReparentedSurfaces.clear();
            mFreeNativeResources.run();
            mNativeObject = 0;
        }

        /**
         * Jankier version of apply. Avoid use (b/28068298).
         * @hide
         */
        public void apply(boolean sync) {
            apply(sync, /*oneWay*/ false);
        }

        private void apply(boolean sync, boolean oneWay) {
            applyResizedSurfaces();
            notifyReparentedSurfaces();
            nativeApplyTransaction(mNativeObject, sync, oneWay);

            if (SurfaceControlRegistry.sCallStackDebuggingEnabled) {
                SurfaceControlRegistry.getProcessInstance().checkCallStackDebugging(
                        "apply", this, null, null);
            }
        }

        /**
         * @hide
         */
        protected void applyResizedSurfaces() {
            for (int i = mResizedSurfaces.size() - 1; i >= 0; i--) {
                final Point size = mResizedSurfaces.valueAt(i);
                final SurfaceControl surfaceControl = mResizedSurfaces.keyAt(i);
                synchronized (surfaceControl.mLock) {
                    surfaceControl.resize(size.x, size.y);
                }
            }
            mResizedSurfaces.clear();
        }

        /**
         * @hide
         */
        protected void notifyReparentedSurfaces() {
            final int reparentCount = mReparentedSurfaces.size();
            for (int i = reparentCount - 1; i >= 0; i--) {
                final SurfaceControl child = mReparentedSurfaces.keyAt(i);
                synchronized (child.mLock) {
                    final int listenerCount = (child.mReparentListeners != null)
                            ? child.mReparentListeners.size() : 0;
                    for (int j = 0; j < listenerCount; j++) {
                        final OnReparentListener listener = child.mReparentListeners.get(j);
                        listener.onReparent(this, mReparentedSurfaces.valueAt(i));
                    }
                    mReparentedSurfaces.removeAt(i);
                }
            }
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
            checkPreconditions(sc);
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
            if (SurfaceControlRegistry.sCallStackDebuggingEnabled) {
                SurfaceControlRegistry.getProcessInstance().checkCallStackDebugging(
                        "show", this, sc, null);
            }
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
            if (SurfaceControlRegistry.sCallStackDebuggingEnabled) {
                SurfaceControlRegistry.getProcessInstance().checkCallStackDebugging(
                        "hide", this, sc, null);
            }
            nativeSetFlags(mNativeObject, sc.mNativeObject, SURFACE_HIDDEN, SURFACE_HIDDEN);
            return this;
        }

        /**
         * Sets the SurfaceControl to the specified position relative to the parent
         * SurfaceControl
         *
         * @param sc The SurfaceControl to change position
         * @param x the X position
         * @param y the Y position
         * @return this transaction
         */
        @NonNull
        public Transaction setPosition(@NonNull SurfaceControl sc, float x, float y) {
            checkPreconditions(sc);
            if (SurfaceControlRegistry.sCallStackDebuggingEnabled) {
                SurfaceControlRegistry.getProcessInstance().checkCallStackDebugging(
                        "setPosition", this, sc, "x=" + x + " y=" + y);
            }
            nativeSetPosition(mNativeObject, sc.mNativeObject, x, y);
            return this;
        }

        /**
         * Sets the SurfaceControl to the specified scale with (0, 0) as the center point
         * of the scale.
         *
         * @param sc The SurfaceControl to change scale
         * @param scaleX the X scale
         * @param scaleY the Y scale
         * @return this transaction
         */
        @NonNull
        public Transaction setScale(@NonNull SurfaceControl sc, float scaleX, float scaleY) {
            checkPreconditions(sc);
            Preconditions.checkArgument(scaleX >= 0, "Negative value passed in for scaleX");
            Preconditions.checkArgument(scaleY >= 0, "Negative value passed in for scaleY");
            if (SurfaceControlRegistry.sCallStackDebuggingEnabled) {
                SurfaceControlRegistry.getProcessInstance().checkCallStackDebugging(
                        "setScale", this, sc, "sx=" + scaleX + " sy=" + scaleY);
            }
            nativeSetScale(mNativeObject, sc.mNativeObject, scaleX, scaleY);
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
            if (SurfaceControlRegistry.sCallStackDebuggingEnabled) {
                SurfaceControlRegistry.getProcessInstance().checkCallStackDebugging(
                        "setBufferSize", this, sc, "w=" + w + " h=" + h);
            }
            mResizedSurfaces.put(sc, new Point(w, h));
            return this;
        }

        /**
         * Provide the graphic producer a transform hint if the layer and its children are
         * in an orientation different from the display's orientation. The caller is responsible
         * for clearing this transform hint if the layer is no longer in a fixed orientation.
         *
         * The transform hint is used to prevent allocating a buffer of different size when a
         * layer is rotated. The producer can choose to consume the hint and allocate the buffer
         * with the same size.
         *
         * @return This Transaction.
         * @hide
         */
        @NonNull
        public Transaction setFixedTransformHint(@NonNull SurfaceControl sc,
                       @Surface.Rotation int transformHint) {
            checkPreconditions(sc);
            if (SurfaceControlRegistry.sCallStackDebuggingEnabled) {
                SurfaceControlRegistry.getProcessInstance().checkCallStackDebugging(
                        "setFixedTransformHint", this, sc, "hint=" + transformHint);
            }
            nativeSetFixedTransformHint(mNativeObject, sc.mNativeObject, transformHint);
            return this;
        }

        /**
         * Clearing any transform hint if set on this layer.
         *
         * @return This Transaction.
         * @hide
         */
        @NonNull
        public Transaction unsetFixedTransformHint(@NonNull SurfaceControl sc) {
            checkPreconditions(sc);
            if (SurfaceControlRegistry.sCallStackDebuggingEnabled) {
                SurfaceControlRegistry.getProcessInstance().checkCallStackDebugging(
                        "unsetFixedTransformHint", this, sc, null);
            }
            nativeSetFixedTransformHint(mNativeObject, sc.mNativeObject, -1/* INVALID_ROTATION */);
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
            if (SurfaceControlRegistry.sCallStackDebuggingEnabled) {
                SurfaceControlRegistry.getProcessInstance().checkCallStackDebugging(
                        "setLayer", this, sc, "z=" + z);
            }
            nativeSetLayer(mNativeObject, sc.mNativeObject, z);
            return this;
        }

        /**
         * @hide
         */
        public Transaction setRelativeLayer(SurfaceControl sc, SurfaceControl relativeTo, int z) {
            checkPreconditions(sc);
            if (SurfaceControlRegistry.sCallStackDebuggingEnabled) {
                SurfaceControlRegistry.getProcessInstance().checkCallStackDebugging(
                        "setRelativeLayer", this, sc, "relTo=" + relativeTo + " z=" + z);
            }
            nativeSetRelativeLayer(mNativeObject, sc.mNativeObject, relativeTo.mNativeObject, z);
            return this;
        }

        /**
         * @hide
         */
        public Transaction setTransparentRegionHint(SurfaceControl sc, Region transparentRegion) {
            checkPreconditions(sc);
            if (SurfaceControlRegistry.sCallStackDebuggingEnabled) {
                SurfaceControlRegistry.getProcessInstance().checkCallStackDebugging(
                        "unsetFixedTransformHint", this, sc, "region=" + transparentRegion);
            }
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
            if (SurfaceControlRegistry.sCallStackDebuggingEnabled) {
                SurfaceControlRegistry.getProcessInstance().checkCallStackDebugging(
                        "setAlpha", this, sc, "alpha=" + alpha);
            }
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
         * Adds a callback that is called after WindowInfosListeners from the systems server are
         * complete. This is primarily used to ensure that InputDispatcher::setInputWindowsLocked
         * has been called before running the added callback.
         *
         * @hide
         */
        public Transaction addWindowInfosReportedListener(@NonNull Runnable listener) {
            nativeAddWindowInfosReportedListener(mNativeObject, listener);
            return this;
        }

        /**
         * Specify how the buffer associated with this Surface is mapped in to the
         * parent coordinate space. The source frame will be scaled to fit the destination
         * frame, after being rotated according to the orientation parameter.
         *
         * @param sc The SurfaceControl to specify the geometry of
         * @param sourceCrop The source rectangle in buffer space. Or null for the entire buffer.
         * @param destFrame The destination rectangle in parent space. Or null for the source frame.
         * @param orientation The buffer rotation
         * @return This transaction object.
         * @deprecated Use {@link #setCrop(SurfaceControl, Rect)},
         * {@link #setBufferTransform(SurfaceControl, int)},
         * {@link #setPosition(SurfaceControl, float, float)} and
         * {@link #setScale(SurfaceControl, float, float)} instead.
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
            if (SurfaceControlRegistry.sCallStackDebuggingEnabled) {
                SurfaceControlRegistry.getProcessInstance().checkCallStackDebugging(
                        "setMatrix", this, sc,
                        "dsdx=" + dsdx + " dtdx=" + dtdx + " dtdy=" + dtdy + " dsdy=" + dsdy);
            }
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
         * @deprecated Use {@link #setCrop(SurfaceControl, Rect)} instead.
         */
        @Deprecated
        @UnsupportedAppUsage
        public Transaction setWindowCrop(SurfaceControl sc, Rect crop) {
            checkPreconditions(sc);
            if (SurfaceControlRegistry.sCallStackDebuggingEnabled) {
                SurfaceControlRegistry.getProcessInstance().checkCallStackDebugging(
                        "setWindowCrop", this, sc, "crop=" + crop);
            }
            if (crop != null) {
                nativeSetWindowCrop(mNativeObject, sc.mNativeObject,
                        crop.left, crop.top, crop.right, crop.bottom);
            } else {
                nativeSetWindowCrop(mNativeObject, sc.mNativeObject, 0, 0, 0, 0);
            }

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
         * @return this This transaction for chaining
         */
        public @NonNull Transaction setCrop(@NonNull SurfaceControl sc, @Nullable Rect crop) {
            checkPreconditions(sc);
            if (SurfaceControlRegistry.sCallStackDebuggingEnabled) {
                SurfaceControlRegistry.getProcessInstance().checkCallStackDebugging(
                        "setCrop", this, sc, "crop=" + crop);
            }
            if (crop != null) {
                Preconditions.checkArgument(crop.isValid(), "Crop " + crop
                        + " isn't valid");
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
            if (SurfaceControlRegistry.sCallStackDebuggingEnabled) {
                SurfaceControlRegistry.getProcessInstance().checkCallStackDebugging(
                        "setWindowCrop", this, sc, "w=" + width + " h=" + height);
            }
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
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public Transaction setCornerRadius(SurfaceControl sc, float cornerRadius) {
            checkPreconditions(sc);
            if (SurfaceControlRegistry.sCallStackDebuggingEnabled) {
                SurfaceControlRegistry.getProcessInstance().checkCallStackDebugging(
                        "setCornerRadius", this, sc, "cornerRadius=" + cornerRadius);
            }
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
            if (SurfaceControlRegistry.sCallStackDebuggingEnabled) {
                SurfaceControlRegistry.getProcessInstance().checkCallStackDebugging(
                        "setBackgroundBlurRadius", this, sc, "radius=" + radius);
            }
            nativeSetBackgroundBlurRadius(mNativeObject, sc.mNativeObject, radius);
            return this;
        }

        /**
         * Specify what regions should be blurred on the {@link SurfaceControl}.
         *
         * @param sc SurfaceControl.
         * @param regions List of regions that will have blurs.
         * @return itself.
         * @see BlurRegion#toFloatArray()
         * @hide
         */
        public Transaction setBlurRegions(SurfaceControl sc, float[][] regions) {
            checkPreconditions(sc);
            nativeSetBlurRegions(mNativeObject, sc.mNativeObject, regions, regions.length);
            return this;
        }

        /**
         * @hide
         */
        public Transaction setStretchEffect(SurfaceControl sc, float width, float height,
                float vecX, float vecY, float maxStretchAmountX,
                float maxStretchAmountY, float childRelativeLeft, float childRelativeTop, float childRelativeRight,
                float childRelativeBottom) {
            checkPreconditions(sc);
            nativeSetStretchEffect(mNativeObject, sc.mNativeObject, width, height,
                    vecX, vecY, maxStretchAmountX, maxStretchAmountY, childRelativeLeft, childRelativeTop,
                    childRelativeRight, childRelativeBottom);
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
            if (SurfaceControlRegistry.sCallStackDebuggingEnabled) {
                SurfaceControlRegistry.getProcessInstance().checkCallStackDebugging(
                        "reparent", this, sc, "newParent=" + newParent);
            }
            long otherObject = 0;
            if (newParent != null) {
                newParent.checkNotReleased();
                otherObject = newParent.mNativeObject;
            }
            nativeReparent(mNativeObject, sc.mNativeObject, otherObject);
            mReparentedSurfaces.put(sc, newParent);
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
            if (SurfaceControlRegistry.sCallStackDebuggingEnabled) {
                SurfaceControlRegistry.getProcessInstance().checkCallStackDebugging(
                        "reparent", this, sc,
                        "r=" + color[0] + " g=" + color[1] + " b=" + color[2]);
            }
            nativeSetColor(mNativeObject, sc.mNativeObject, color);
            return this;
        }

        /**
         * Removes color fill.
        * @hide
        */
        public Transaction unsetColor(SurfaceControl sc) {
            checkPreconditions(sc);
            if (SurfaceControlRegistry.sCallStackDebuggingEnabled) {
                SurfaceControlRegistry.getProcessInstance().checkCallStackDebugging(
                        "unsetColor", this, sc, null);
            }
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
            if (SurfaceControlRegistry.sCallStackDebuggingEnabled) {
                SurfaceControlRegistry.getProcessInstance().checkCallStackDebugging(
                        "setSecure", this, sc, "secure=" + isSecure);
            }
            if (isSecure) {
                nativeSetFlags(mNativeObject, sc.mNativeObject, SECURE, SECURE);
            } else {
                nativeSetFlags(mNativeObject, sc.mNativeObject, 0, SECURE);
            }
            return this;
        }

        /**
         * Sets whether the surface should take advantage of display decoration optimizations.
         * @hide
         */
        public Transaction setDisplayDecoration(SurfaceControl sc, boolean displayDecoration) {
            checkPreconditions(sc);
            if (displayDecoration) {
                nativeSetFlags(mNativeObject, sc.mNativeObject, DISPLAY_DECORATION,
                        DISPLAY_DECORATION);
            } else {
                nativeSetFlags(mNativeObject, sc.mNativeObject, 0, DISPLAY_DECORATION);
            }
            return this;
        }

        /**
         * Indicates whether the surface must be considered opaque, even if its pixel format is
         * set to translucent. This can be useful if an application needs full RGBA 8888 support
         * for instance but will still draw every pixel opaque.
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
         *
         * @see Builder#setOpaque(boolean)
         *
         * @param sc The SurfaceControl to update
         * @param isOpaque true if the buffer's alpha should be ignored, false otherwise
         * @return this
         */
        @NonNull
        public Transaction setOpaque(@NonNull SurfaceControl sc, boolean isOpaque) {
            checkPreconditions(sc);
            if (SurfaceControlRegistry.sCallStackDebuggingEnabled) {
                SurfaceControlRegistry.getProcessInstance().checkCallStackDebugging(
                        "setOpaque", this, sc, "opaque=" + isOpaque);
            }
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
        public Transaction setDisplayFlags(IBinder displayToken, int flags) {
            if (displayToken == null) {
                throw new IllegalArgumentException("displayToken must not be null");
            }
            nativeSetDisplayFlags(mNativeObject, displayToken, flags);
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
          * Provides a hint to SurfaceFlinger to change its offset so that SurfaceFlinger wakes up
          * earlier to compose surfaces. The caller should use this as a hint to SurfaceFlinger
          * when the scene is complex enough to use GPU composition. The hint will remain active
          * until until the client calls {@link Transaction#setEarlyWakeupEnd}.
          *
          * @hide
          */
        @RequiresPermission(Manifest.permission.WAKEUP_SURFACE_FLINGER)
        public Transaction setEarlyWakeupStart() {
            nativeSetEarlyWakeupStart(mNativeObject);
            return this;
        }

        /**
         * Removes the early wake up hint set by {@link Transaction#setEarlyWakeupStart}.
         *
         * @hide
         */
        @RequiresPermission(Manifest.permission.WAKEUP_SURFACE_FLINGER)
        public Transaction setEarlyWakeupEnd() {
            nativeSetEarlyWakeupEnd(mNativeObject);
            return this;
        }

        /**
         * @hide
         * @return The transaction's current id.
         *         The id changed every time the transaction is applied.
         */
        public long getId() {
            return nativeGetTransactionId(mNativeObject);
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
            if (SurfaceControlRegistry.sCallStackDebuggingEnabled) {
                SurfaceControlRegistry.getProcessInstance().checkCallStackDebugging(
                        "setShadowRadius", this, sc, "radius=" + shadowRadius);
            }
            nativeSetShadowRadius(mNativeObject, sc.mNativeObject, shadowRadius);
            return this;
        }

        /**
         * Sets the intended frame rate for this surface. Any switching of refresh rates is
         * most probably going to be seamless.
         *
         * @see #setFrameRate(SurfaceControl, float, int, int)
         */
        @NonNull
        public Transaction setFrameRate(@NonNull SurfaceControl sc,
                @FloatRange(from = 0.0) float frameRate,
                @Surface.FrameRateCompatibility int compatibility) {
            return setFrameRate(sc, frameRate, compatibility,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
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
         * <p>
         * Note that this only has an effect for surfaces presented on the display. If this
         * surface is consumed by something other than the system compositor, e.g. a media
         * codec, this call has no effect.
         *
         * @param sc The SurfaceControl to specify the frame rate of.
         * @param frameRate The intended frame rate for this surface, in frames per second. 0 is a
         *                  special value that indicates the app will accept the system's choice for
         *                  the display frame rate, which is the default behavior if this function
         *                  isn't called. The <code>frameRate</code> param does <em>not</em> need
         *                  to be a valid refresh rate for this device's display - e.g., it's fine
         *                  to pass 30fps to a device that can only run the display at 60fps.
         * @param compatibility The frame rate compatibility of this surface. The compatibility
         *                      value may influence the system's choice of display frame rate.
         *                      This parameter is ignored when <code>frameRate</code> is 0.
         * @param changeFrameRateStrategy Whether display refresh rate transitions caused by this
         *                                surface should be seamless. A seamless transition is one
         *                                that doesn't have any visual interruptions, such as a
         *                                black screen for a second or two. This parameter is
         *                                ignored when <code>frameRate</code> is 0.
         * @return This transaction object.
         *
         * @see #clearFrameRate(SurfaceControl)
         */
        @NonNull
        public Transaction setFrameRate(@NonNull SurfaceControl sc,
                @FloatRange(from = 0.0) float frameRate,
                @Surface.FrameRateCompatibility int compatibility,
                @Surface.ChangeFrameRateStrategy int changeFrameRateStrategy) {
            checkPreconditions(sc);
            nativeSetFrameRate(mNativeObject, sc.mNativeObject, frameRate, compatibility,
                    changeFrameRateStrategy);
            return this;
        }

        /**
         * Clears the frame rate which was set for the surface {@link SurfaceControl}.
         *
         * <p>This is equivalent to calling {@link #setFrameRate(SurfaceControl, float, int, int)}
         * using {@code 0} for {@code frameRate}.
         * <p>
         * Note that this only has an effect for surfaces presented on the display. If this
         * surface is consumed by something other than the system compositor, e.g. a media
         * codec, this call has no effect.
         *
         * @param sc The SurfaceControl to clear the frame rate of.
         * @return This transaction object.
         *
         * @see #setFrameRate(SurfaceControl, float, int)
         */
        @NonNull
        public Transaction clearFrameRate(@NonNull SurfaceControl sc) {
            checkPreconditions(sc);
            nativeSetFrameRate(mNativeObject, sc.mNativeObject, 0.0f,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ALWAYS);
            return this;
        }

        /**
         * Sets the default frame rate compatibility for the surface {@link SurfaceControl}
         *
         * @param sc The SurfaceControl to specify the frame rate of.
         * @param compatibility The frame rate compatibility of this surface. The compatibility
         *               value may influence the system's choice of display frame rate.
         *
         * @return This transaction object.
         *
         * @hide
         */
        @NonNull
        public Transaction setDefaultFrameRateCompatibility(@NonNull SurfaceControl sc,
                @Surface.FrameRateCompatibility int compatibility) {
            checkPreconditions(sc);
            nativeSetDefaultFrameRateCompatibility(mNativeObject, sc.mNativeObject, compatibility);
            return this;
        }

        /**
         * Sets the frame rate category for the {@link SurfaceControl}.
         *
         * This helps instruct the system on choosing a display refresh rate based on the surface's
         * chosen category, which is a device-specific range of frame rates.
         * {@link #setFrameRateCategory} should be used by components such as animations that do not
         * require an exact frame rate, but has an opinion on an approximate desirable frame rate.
         * The values of {@code category} gives example use cases for which category to choose.
         *
         * To instead specify an exact frame rate, use
         * {@link #setFrameRate(SurfaceControl, float, int, int)}, which is more suitable for
         * content that knows specifically which frame rate is optimal.
         * Although not a common use case, {@link #setFrameRateCategory} and {@link #setFrameRate}
         * can be called together, with both calls potentially influencing the display refresh rate.
         * For example, considering only one {@link SurfaceControl}: if {@link #setFrameRate}'s
         * value is 24 and {@link #setFrameRateCategory}'s value is
         * {@link Surface#FRAME_RATE_CATEGORY_HIGH}, defined to be the range [90,120] fps for an
         * example device, then the best refresh rate for the SurfaceControl should be 120 fps.
         * This is because 120 fps is a multiple of 24 fps, and 120 fps is in the specified
         * category's range.
         *
         * @param sc The SurfaceControl to specify the frame rate category of.
         * @param category The frame rate category of this surface. The category value may influence
         * the system's choice of display frame rate.
         * @param smoothSwitchOnly Set to {@code true} to indicate the display frame rate should not
         * change if changing it would cause jank. Else {@code false}.
         * This parameter is ignored when {@code category} is
         * {@link Surface#FRAME_RATE_CATEGORY_DEFAULT}.
         *
         * @return This transaction object.
         *
         * @see #setFrameRate(SurfaceControl, float, int, int)
         *
         * @hide
         */
        @NonNull
        public Transaction setFrameRateCategory(@NonNull SurfaceControl sc,
                @Surface.FrameRateCategory int category, boolean smoothSwitchOnly) {
            checkPreconditions(sc);
            nativeSetFrameRateCategory(mNativeObject, sc.mNativeObject, category, smoothSwitchOnly);
            return this;
        }

        /**
         * Sets the frame rate selection strategy for the {@link SurfaceControl}.
         *
         * This instructs the system on how to choose a display refresh rate, following the
         * strategy for the layer's frame rate specifications relative to other layers'.
         *
         * @param sc The SurfaceControl to specify the frame rate category of.
         * @param strategy The frame rate selection strategy.
         *
         * @return This transaction object.
         *
         * @see #setFrameRate(SurfaceControl, float, int, int)
         * @see #setFrameRateCategory(SurfaceControl, int)
         * @see #setDefaultFrameRateCompatibility(SurfaceControl, int)
         *
         * @hide
         */
        @NonNull
        public Transaction setFrameRateSelectionStrategy(
                @NonNull SurfaceControl sc, @FrameRateSelectionStrategy int strategy) {
            checkPreconditions(sc);
            nativeSetFrameRateSelectionStrategy(mNativeObject, sc.mNativeObject, strategy);
            return this;
        }

        /**
         * Sets focus on the window identified by the input {@code token} if the window is focusable
         * otherwise the request is dropped.
         *
         * If the window is not visible, the request will be queued until the window becomes
         * visible or the request is overrriden by another request. The currently focused window
         * will lose focus immediately. This is to send the newly focused window any focus
         * dispatched events that occur while it is completing its first draw.
         *
         * @hide
         */
        public Transaction setFocusedWindow(@NonNull IBinder token, String windowName,
                int displayId) {
            nativeSetFocusedWindow(mNativeObject, token, windowName, displayId);
            return this;
        }

        /**
         * Removes the input focus from the current window which is having the input focus. Should
         * only be called when the current focused app is not responding and the current focused
         * window is not beloged to the current focused app.
         * @hide
         */
        public Transaction removeCurrentInputFocus(int displayId) {
            nativeRemoveCurrentInputFocus(mNativeObject, displayId);
            return this;
        }

        /**
         * Adds or removes the flag SKIP_SCREENSHOT of the surface.  Setting the flag is equivalent
         * to creating the Surface with the {@link #SKIP_SCREENSHOT} flag.
         *
         * @hide
         */
        public Transaction setSkipScreenshot(SurfaceControl sc, boolean skipScrenshot) {
            checkPreconditions(sc);
            if (skipScrenshot) {
                nativeSetFlags(mNativeObject, sc.mNativeObject, SKIP_SCREENSHOT, SKIP_SCREENSHOT);
            } else {
                nativeSetFlags(mNativeObject, sc.mNativeObject, 0, SKIP_SCREENSHOT);
            }
            return this;
        }

        /**
         * Set a buffer for a SurfaceControl. This can only be used for SurfaceControls that were
         * created as type {@link #FX_SURFACE_BLAST}
         *
         * @hide
         * @deprecated Use {@link #setBuffer(SurfaceControl, HardwareBuffer)} instead
         */
        @Deprecated
        public Transaction setBuffer(SurfaceControl sc, GraphicBuffer buffer) {
            return setBuffer(sc, HardwareBuffer.createFromGraphicBuffer(buffer));
        }

        /**
         * Updates the HardwareBuffer displayed for the SurfaceControl.
         *
         * Note that the buffer must be allocated with {@link HardwareBuffer#USAGE_COMPOSER_OVERLAY}
         * as well as {@link HardwareBuffer#USAGE_GPU_SAMPLED_IMAGE} as the surface control might
         * be composited using either an overlay or using the GPU.
         *
         * @param sc The SurfaceControl to update
         * @param buffer The buffer to be displayed
         * @return this
         */
        public @NonNull Transaction setBuffer(@NonNull SurfaceControl sc,
                @Nullable HardwareBuffer buffer) {
            return setBuffer(sc, buffer, null);
        }

        /**
         * Unsets the buffer for the SurfaceControl in the current Transaction. This will not clear
         * the buffer being rendered, but resets the buffer state in the Transaction only. The call
         * will also invoke the release callback.
         *
         * Note, this call is different from passing a null buffer to
         * {@link SurfaceControl.Transaction#setBuffer} which will release the last displayed
         * buffer.
         *
         * @hide
         */
        public Transaction unsetBuffer(SurfaceControl sc) {
            nativeUnsetBuffer(mNativeObject, sc.mNativeObject);
            return this;
        }

        /**
         * Updates the HardwareBuffer displayed for the SurfaceControl.
         *
         * Note that the buffer must be allocated with {@link HardwareBuffer#USAGE_COMPOSER_OVERLAY}
         * as well as {@link HardwareBuffer#USAGE_GPU_SAMPLED_IMAGE} as the surface control might
         * be composited using either an overlay or using the GPU.
         *
         * A presentation fence may be passed to improve performance by allowing the buffer
         * to complete rendering while it is waiting for the transaction to be applied.
         * For example, if the buffer is being produced by rendering with OpenGL ES then
         * a fence created with
         * {@link android.opengl.EGLExt#eglDupNativeFenceFDANDROID(EGLDisplay, EGLSync)} can be
         * used to allow the GPU rendering to be concurrent with the transaction. The compositor
         * will wait for the fence to be signaled before the buffer is displayed. If multiple
         * buffers are set as part of the same transaction, the presentation fences of all of them
         * must signal before any buffer is displayed. That is, the entire transaction is delayed
         * until all presentation fences have signaled, ensuring the transaction remains consistent.
         *
         * @param sc The SurfaceControl to update
         * @param buffer The buffer to be displayed. Pass in a null buffer to release the last
         * displayed buffer.
         * @param fence The presentation fence. If null or invalid, this is equivalent to
         *              {@link #setBuffer(SurfaceControl, HardwareBuffer)}
         * @return this
         */
        public @NonNull Transaction setBuffer(@NonNull SurfaceControl sc,
                @Nullable HardwareBuffer buffer, @Nullable SyncFence fence) {
            return setBuffer(sc, buffer, fence, null);
        }

        /**
         * Updates the HardwareBuffer displayed for the SurfaceControl.
         *
         * Note that the buffer must be allocated with {@link HardwareBuffer#USAGE_COMPOSER_OVERLAY}
         * as well as {@link HardwareBuffer#USAGE_GPU_SAMPLED_IMAGE} as the surface control might
         * be composited using either an overlay or using the GPU.
         *
         * A presentation fence may be passed to improve performance by allowing the buffer
         * to complete rendering while it is waiting for the transaction to be applied.
         * For example, if the buffer is being produced by rendering with OpenGL ES then
         * a fence created with
         * {@link android.opengl.EGLExt#eglDupNativeFenceFDANDROID(EGLDisplay, EGLSync)} can be
         * used to allow the GPU rendering to be concurrent with the transaction. The compositor
         * will wait for the fence to be signaled before the buffer is displayed. If multiple
         * buffers are set as part of the same transaction, the presentation fences of all of them
         * must signal before any buffer is displayed. That is, the entire transaction is delayed
         * until all presentation fences have signaled, ensuring the transaction remains consistent.
         *
         * A releaseCallback may be passed to know when the buffer is safe to be reused. This
         * is recommended when attempting to render continuously using SurfaceControl transactions
         * instead of through {@link Surface}, as it provides a safe & reliable way to know when
         * a buffer can be re-used. The callback will be invoked with a {@link SyncFence} which,
         * if {@link SyncFence#isValid() valid}, must be waited on prior to using the buffer. This
         * can either be done directly with {@link SyncFence#awaitForever()} or it may be done
         * indirectly such as passing it as a release fence to
         * {@link android.media.Image#setFence(SyncFence)} when using
         * {@link android.media.ImageReader}.
         *
         * @param sc The SurfaceControl to update
         * @param buffer The buffer to be displayed
         * @param fence The presentation fence. If null or invalid, this is equivalent to
         *              {@link #setBuffer(SurfaceControl, HardwareBuffer)}
         * @param releaseCallback The callback to invoke when the buffer being set has been released
         *                        by a later transaction. That is, the point at which it is safe
         *                        to re-use the buffer.
         * @return this
         */
        public @NonNull Transaction setBuffer(@NonNull SurfaceControl sc,
                @Nullable HardwareBuffer buffer, @Nullable SyncFence fence,
                @Nullable Consumer<SyncFence> releaseCallback) {
            checkPreconditions(sc);
            if (fence != null) {
                synchronized (fence.getLock()) {
                    nativeSetBuffer(mNativeObject, sc.mNativeObject, buffer,
                            fence.getNativeFence(), releaseCallback);
                }
            } else {
                nativeSetBuffer(mNativeObject, sc.mNativeObject, buffer, 0, releaseCallback);
            }
            return this;
        }


        /**
         * Sets the buffer transform that should be applied to the current buffer.
         *
         * This can be used in combination with
         * {@link AttachedSurfaceControl#addOnBufferTransformHintChangedListener(AttachedSurfaceControl.OnBufferTransformHintChangedListener)}
         * to pre-rotate the buffer for the current display orientation. This can
         * improve the performance of displaying the associated buffer.
         *
         * @param sc The SurfaceControl to update
         * @param transform The transform to apply to the buffer.
         * @return this
         */
        public @NonNull Transaction setBufferTransform(@NonNull SurfaceControl sc,
                @SurfaceControl.BufferTransform int transform) {
            checkPreconditions(sc);
            nativeSetBufferTransform(mNativeObject, sc.mNativeObject, transform);
            return this;
        }

        /**
         * Updates the region for the content on this surface updated in this transaction. The
         * damage region is the area of the buffer that has changed since the previously
         * sent buffer. This can be used to reduce the amount of recomposition that needs
         * to happen when only a small region of the buffer is being updated, such as for
         * a small blinking cursor or a loading indicator.
         *
         * @param sc The SurfaceControl on which to set the damage region
         * @param region The region to set. If null, the entire buffer is assumed dirty. This is
         *               equivalent to not setting a damage region at all.
         */
        public @NonNull Transaction setDamageRegion(@NonNull SurfaceControl sc,
                @Nullable Region region) {
            nativeSetDamageRegion(mNativeObject, sc.mNativeObject, region);
            return this;
        }

        /**
         * Set if the layer can be dimmed.
         *
         * <p>Dimming is to adjust brightness of the layer.
         * Default value is {@code true}, which means the layer can be dimmed.
         * Disabling dimming means the brightness of the layer can not be changed, i.e.,
         * keep the white point for the layer same as the display brightness.</p>
         *
         * @param sc The SurfaceControl on which to enable or disable dimming.
         * @param dimmingEnabled The dimming flag.
         * @return this.
         *
         * @hide
         */
        public @NonNull Transaction setDimmingEnabled(@NonNull SurfaceControl sc,
                boolean dimmingEnabled) {
            checkPreconditions(sc);
            nativeSetDimmingEnabled(mNativeObject, sc.mNativeObject, dimmingEnabled);
            return this;
        }

        /**
         * Set the color space for the SurfaceControl. The supported color spaces are SRGB
         * and Display P3, other color spaces will be treated as SRGB. This can only be used for
         * SurfaceControls that were created as type {@link #FX_SURFACE_BLAST}
         *
         * @hide
         * @deprecated use {@link #setDataSpace(SurfaceControl, long)} instead
         */
        @Deprecated
        public Transaction setColorSpace(SurfaceControl sc, ColorSpace colorSpace) {
            checkPreconditions(sc);
            if (colorSpace.getId() == ColorSpace.Named.DISPLAY_P3.ordinal()) {
                setDataSpace(sc, DataSpace.DATASPACE_DISPLAY_P3);
            } else {
                setDataSpace(sc, DataSpace.DATASPACE_SRGB);
            }
            return this;
        }

        /**
         * Set the dataspace for the SurfaceControl. This will control how the buffer
         * set with {@link #setBuffer(SurfaceControl, HardwareBuffer)} is displayed.
         *
         * @param sc The SurfaceControl to update
         * @param dataspace The dataspace to set it to
         * @return this
         */
        public @NonNull Transaction setDataSpace(@NonNull SurfaceControl sc,
                @DataSpace.NamedDataSpace int dataspace) {
            checkPreconditions(sc);
            nativeSetDataSpace(mNativeObject, sc.mNativeObject, dataspace);
            return this;
        }

        /**
         * Sets the desired extended range brightness for the layer. This only applies for layers
         * whose dataspace has RANGE_EXTENDED.
         *
         * @param sc The layer whose extended range brightness is being specified
         * @param currentBufferRatio The current hdr/sdr ratio of the current buffer. For example
         *                           if the buffer was rendered with a target SDR whitepoint of
         *                           100 nits and a max display brightness of 200 nits, this should
         *                           be set to 2.0f.
         *
         *                           <p>Default value is 1.0f.
         *
         *                           <p>Transfer functions that encode their own brightness ranges,
         *                           such as HLG or PQ, should also set this to 1.0f and instead
         *                           communicate extended content brightness information via
         *                           metadata such as CTA861_3 or SMPTE2086.
         *
         *                           <p>Must be finite && >= 1.0f
         *
         * @param desiredRatio The desired hdr/sdr ratio. This can be used to communicate the max
         *                     desired brightness range. This is similar to the "max luminance"
         *                     value in other HDR metadata formats, but represented as a ratio of
         *                     the target SDR whitepoint to the max display brightness. The system
         *                     may not be able to, or may choose not to, deliver the
         *                     requested range.
         *
         *                     <p>While requesting a large desired ratio will result in the most
         *                     dynamic range, voluntarily reducing the requested range can help
         *                     improve battery life as well as can improve quality by ensuring
         *                     greater bit depth is allocated to the luminance range in use.
         *
         *                     <p>Default value is 1.0f and indicates that extended range brightness
         *                     is not being used, so the resulting SDR or HDR behavior will be
         *                     determined entirely by the dataspace being used (ie, typically SDR
         *                     however PQ or HLG transfer functions will still result in HDR)
         *
         *                     <p>Must be finite && >= 1.0f
         * @return this
         **/
        public @NonNull Transaction setExtendedRangeBrightness(@NonNull SurfaceControl sc,
                float currentBufferRatio, float desiredRatio) {
            checkPreconditions(sc);
            if (!Float.isFinite(currentBufferRatio) || currentBufferRatio < 1.0f) {
                throw new IllegalArgumentException(
                        "currentBufferRatio must be finite && >= 1.0f; got " + currentBufferRatio);
            }
            if (!Float.isFinite(desiredRatio) || desiredRatio < 1.0f) {
                throw new IllegalArgumentException(
                        "desiredRatio must be finite && >= 1.0f; got " + desiredRatio);
            }
            nativeSetExtendedRangeBrightness(mNativeObject, sc.mNativeObject, currentBufferRatio,
                    desiredRatio);
            return this;
        }

        /**
         * Sets the caching hint for the layer. By default, the caching hint is
         * {@link CACHING_ENABLED}.
         *
         * @param sc The SurfaceControl to update
         * @param cachingHint The caching hint to apply to the SurfaceControl. The CachingHint is
         *                    not applied to any children of this SurfaceControl.
         * @return this
         * @hide
         */
        public @NonNull Transaction setCachingHint(
                @NonNull SurfaceControl sc, @CachingHint int cachingHint) {
            checkPreconditions(sc);
            nativeSetCachingHint(mNativeObject, sc.mNativeObject, cachingHint);
            return this;
        }

        /**
         * Sets the trusted overlay state on this SurfaceControl and it is inherited to all the
         * children. The caller must hold the ACCESS_SURFACE_FLINGER permission.
         * @hide
         */
        public Transaction setTrustedOverlay(SurfaceControl sc, boolean isTrustedOverlay) {
            checkPreconditions(sc);
            nativeSetTrustedOverlay(mNativeObject, sc.mNativeObject, isTrustedOverlay);
            return this;
        }

        /**
         * Sets the input event drop mode on this SurfaceControl and its children. The caller must
         * hold the ACCESS_SURFACE_FLINGER permission. See {@code InputEventDropMode}.
         * @hide
         */
        public Transaction setDropInputMode(SurfaceControl sc, @DropInputMode int mode) {
            checkPreconditions(sc);
            nativeSetDropInputMode(mNativeObject, sc.mNativeObject, mode);
            return this;
        }

        /**
         * Sends a flush jank data transaction for the given surface.
         * @hide
         */
        public static void sendSurfaceFlushJankData(SurfaceControl sc) {
            sc.checkNotReleased();
            nativeSurfaceFlushJankData(sc.mNativeObject);
        }

        /**
         * @hide
         */
        public void sanitize(int pid, int uid) {
            nativeSanitize(mNativeObject, pid, uid);
        }

        /**
         * @hide
         */
        public Transaction setDesintationFrame(SurfaceControl sc, @NonNull Rect destinationFrame) {
            checkPreconditions(sc);
            nativeSetDestinationFrame(mNativeObject, sc.mNativeObject,
                    destinationFrame.left, destinationFrame.top, destinationFrame.right,
                    destinationFrame.bottom);
            return this;
        }

        /**
         * @hide
         */
        public Transaction setDesintationFrame(SurfaceControl sc, int width, int height) {
            checkPreconditions(sc);
            nativeSetDestinationFrame(mNativeObject, sc.mNativeObject, 0, 0, width, height);
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
            mReparentedSurfaces.putAll(other.mReparentedSurfaces);
            other.mReparentedSurfaces.clear();
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
         * Sets the frame timeline vsync id received from choreographer
         * {@link Choreographer#getVsyncId()} that corresponds to the transaction submitted on that
         * surface control.
         *
         * @hide
         */
        @NonNull
        public Transaction setFrameTimelineVsync(long frameTimelineVsyncId) {
            nativeSetFrameTimelineVsync(mNativeObject, frameTimelineVsyncId);
            return this;
        }

        /**
         * Request to add a {@link TransactionCommittedListener}.
         *
         * The callback is invoked when transaction is applied and the updates are ready to be
         * presented. This callback does not mean buffers have been released! It simply means that
         * any new transactions applied will not overwrite the transaction for which we are
         * receiving a callback and instead will be included in the next frame. If you are trying
         * to avoid dropping frames (overwriting transactions), and unable to use timestamps (Which
         * provide a more efficient solution), then this method provides a method to pace your
         * transaction application.
         *
         * @param executor The executor that the callback should be invoked on.
         * @param listener The callback that will be invoked when the transaction has been
         *                 committed.
         */
        @NonNull
        public Transaction addTransactionCommittedListener(
                @NonNull @CallbackExecutor Executor executor,
                @NonNull TransactionCommittedListener listener) {
            TransactionCommittedListener listenerInner =
                    () -> executor.execute(listener::onTransactionCommitted);
            nativeAddTransactionCommittedListener(mNativeObject, listenerInner);
            return this;
        }

        /**
         * Sets a callback to receive feedback about the presentation of a {@link SurfaceControl}.
         * When the {@link SurfaceControl} is presented according to the passed in
         * {@link TrustedPresentationThresholds}, it is said to "enter the state", and receives the
         * callback with {@code true}. When the conditions fall out of thresholds, it is then
         * said to leave the state.
         * <p>
         * There are a few simple thresholds:
         * <ul>
         *    <li>minAlpha: Lower bound on computed alpha</li>
         *    <li>minFractionRendered: Lower bounds on fraction of pixels that were rendered</li>
         *    <li>stabilityThresholdMs: A time that alpha and fraction rendered must remain within
         *    bounds before we can "enter the state" </li>
         * </ul>
         * <p>
         * The fraction of pixels rendered is a computation based on scale, crop
         * and occlusion. The calculation may be somewhat counterintuitive, so we
         * can work through an example. Imagine we have a SurfaceControl with a 100x100 buffer
         * which is occluded by (10x100) pixels on the left, and cropped by (100x10) pixels
         * on the top. Furthermore imagine this SurfaceControl is scaled by 0.9 in both dimensions.
         * (c=crop,o=occluded,b=both,x=none)
         *
         * <blockquote>
         * <table>
         *   <caption></caption>
         *   <tr><td>b</td><td>c</td><td>c</td><td>c</td></tr>
         *   <tr><td>o</td><td>x</td><td>x</td><td>x</td></tr>
         *   <tr><td>o</td><td>x</td><td>x</td><td>x</td></tr>
         *   <tr><td>o</td><td>x</td><td>x</td><td>x</td></tr>
         * </table>
         * </blockquote>
         *
         *<p>
         * We first start by computing fr=xscale*yscale=0.9*0.9=0.81, indicating
         * that "81%" of the pixels were rendered. This corresponds to what was 100
         * pixels being displayed in 81 pixels. This is somewhat of an abuse of
         * language, as the information of merged pixels isn't totally lost, but
         * we err on the conservative side.
         * <p>
         * We then repeat a similar process for the crop and covered regions and
         * accumulate the results: fr = fr * (fractionNotCropped) * (fractionNotCovered)
         * So for this example we would get 0.9*0.9*0.9*0.9=0.65...
         * <p>
         * Notice that this is not completely accurate, as we have double counted
         * the region marked as b. However we only wanted a "lower bound" and so it
         * is ok to err in this direction. Selection of the threshold will ultimately
         * be somewhat arbitrary, and so there are some somewhat arbitrary decisions in
         * this API as well.
         * <p>
         * @param sc         The {@link SurfaceControl} to set the callback on
         * @param thresholds The {@link TrustedPresentationThresholds} that will specify when the to
         *                   invoke the callback.
         * @param executor   The {@link Executor} where the callback will be invoked on.
         * @param listener   The {@link Consumer} that will receive the callbacks when entered or
         *                   exited the threshold.
         * @return This transaction
         * @see TrustedPresentationThresholds
         */
        @NonNull
        public Transaction setTrustedPresentationCallback(@NonNull SurfaceControl sc,
                @NonNull TrustedPresentationThresholds thresholds, @NonNull Executor executor,
                @NonNull Consumer<Boolean> listener) {
            checkPreconditions(sc);
            TrustedPresentationCallback tpc = new TrustedPresentationCallback() {
                @Override
                public void onTrustedPresentationChanged(boolean inTrustedPresentationState) {
                    executor.execute(
                            () -> listener.accept(inTrustedPresentationState));
                }
            };

            if (sc.mTrustedPresentationCallback != null) {
                sc.mTrustedPresentationCallback.mFreeNativeResources.run();
            }

            nativeSetTrustedPresentationCallback(mNativeObject, sc.mNativeObject,
                    tpc.mNativeObject, thresholds);
            sc.mTrustedPresentationCallback = tpc;
            return this;
        }

        /**
         * Clears the callback for a specific {@link SurfaceControl}
         *
         * @param sc The SurfaceControl that the callback should be cleared from
         * @return This transaction
         */
        @NonNull
        public Transaction clearTrustedPresentationCallback(@NonNull SurfaceControl sc) {
            checkPreconditions(sc);
            nativeClearTrustedPresentationCallback(mNativeObject, sc.mNativeObject);
            if (sc.mTrustedPresentationCallback != null) {
                sc.mTrustedPresentationCallback.mFreeNativeResources.run();
                sc.mTrustedPresentationCallback = null;
            }
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
                return;
            }

            dest.writeInt(1);
            nativeWriteTransactionToParcel(mNativeObject, dest);
            if ((flags & Parcelable.PARCELABLE_WRITE_RETURN_VALUE) != 0) {
                nativeClearTransaction(mNativeObject);
            }
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

    /**
     * As part of eliminating usage of the global Transaction we expose
     * a SurfaceControl.getGlobalTransaction function. However calling
     * apply on this global transaction (rather than using closeTransaction)
     * would be very dangerous. So for the global transaction we use this
     * subclass of Transaction where the normal apply throws an exception.
     */
    private static class GlobalTransactionWrapper extends SurfaceControl.Transaction {
        void applyGlobalTransaction(boolean sync) {
            applyResizedSurfaces();
            notifyReparentedSurfaces();
            nativeApplyTransaction(mNativeObject, sync, /*oneWay*/ false);
        }

        @Override
        public void apply(boolean sync) {
            throw new RuntimeException("Global transaction must be applied from closeTransaction");
        }
    }

    /**
     * This is a refactoring utility function to enable lower levels of code to be refactored
     * from using the global transaction (and instead use a passed in Transaction) without
     * having to refactor the higher levels at the same time.
     * The returned global transaction can't be applied, it must be applied from closeTransaction
     * Unless you are working on removing Global Transaction usage in the WindowManager, this
     * probably isn't a good function to use.
     * @hide
     */
    public static Transaction getGlobalTransaction() {
        return sGlobalTransaction;
    }

    /**
     * @hide
     */
    public void resize(int w, int h) {
        mWidth = w;
        mHeight = h;
        nativeUpdateDefaultBufferSize(mNativeObject, w, h);
    }

    /**
     * @hide
     */
    public @SurfaceControl.BufferTransform int getTransformHint() {
        checkNotReleased();
        return nativeGetTransformHint(mNativeObject);
    }

    /**
     * Update the transform hint of current SurfaceControl. Only affect if type is
     * {@link #FX_SURFACE_BLAST}
     *
     * The transform hint is used to prevent allocating a buffer of different size when a
     * layer is rotated. The producer can choose to consume the hint and allocate the buffer
     * with the same size.
     * @hide
     */
    public void setTransformHint(@SurfaceControl.BufferTransform int transformHint) {
        nativeSetTransformHint(mNativeObject, transformHint);
    }

    /**
     * @hide
     */
    public int getLayerId() {
        if (mNativeObject != 0) {
            return nativeGetLayerId(mNativeObject);
        }

        return -1;
    }

    // Called by native
    private static void invokeReleaseCallback(Consumer<SyncFence> callback, long nativeFencePtr) {
        SyncFence fence = new SyncFence(nativeFencePtr);
        callback.accept(fence);
    }

    /**
     * @hide
     */
    public static StalledTransactionInfo getStalledTransactionInfo(int pid) {
        return nativeGetStalledTransactionInfo(pid);
    }

}
