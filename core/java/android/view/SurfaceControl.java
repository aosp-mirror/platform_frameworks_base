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
import static android.view.SurfaceControlProto.HASH_CODE;
import static android.view.SurfaceControlProto.NAME;

import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Size;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.GraphicBuffer;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.HardwareBuffer;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
    private static native void nativeUpdateDefaultBufferSize(long nativeObject, int width, int height);
    private static native int nativeCaptureDisplay(DisplayCaptureArgs captureArgs,
            ScreenCaptureListener captureListener);
    private static native int nativeCaptureLayers(LayerCaptureArgs captureArgs,
            ScreenCaptureListener captureListener);
    private static native long nativeMirrorSurface(long mirrorOfObject);
    private static native long nativeCreateTransaction();
    private static native long nativeGetNativeTransactionFinalizer();
    private static native void nativeApplyTransaction(long transactionObj, boolean sync);
    private static native void nativeMergeTransaction(long transactionObj,
            long otherTransactionObj);
    private static native void nativeClearTransaction(long transactionObj);
    private static native void nativeSetAnimationTransaction(long transactionObj);
    private static native void nativeSetEarlyWakeupStart(long transactionObj);
    private static native void nativeSetEarlyWakeupEnd(long transactionObj);

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
    private static native void nativeSetBlurRegions(long transactionObj, long nativeObj,
            float[][] regions, int length);
    private static native void nativeSetStretchEffect(long transactionObj, long nativeObj,
            float width, float height, float vecX, float vecY,
            float maxStretchAmountX, float maxStretchAmountY, float childRelativeLeft,
            float childRelativeTop, float childRelativeRight, float childRelativeBottom);

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
    private static native StaticDisplayInfo nativeGetStaticDisplayInfo(IBinder displayToken);
    private static native DynamicDisplayInfo nativeGetDynamicDisplayInfo(IBinder displayToken);
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
    private static native boolean nativeSetActiveColorMode(IBinder displayToken,
            int colorMode);
    private static native void nativeSetAutoLowLatencyMode(IBinder displayToken, boolean on);
    private static native void nativeSetGameContentType(IBinder displayToken, boolean on);
    private static native void nativeSetDisplayPowerMode(
            IBinder displayToken, int mode);
    private static native void nativeReparent(long transactionObj, long nativeObject,
            long newParentNativeObject);
    private static native void nativeSetBuffer(long transactionObj, long nativeObject,
            GraphicBuffer buffer);
    private static native void nativeSetColorSpace(long transactionObj, long nativeObject,
            int colorSpace);

    private static native void nativeOverrideHdrTypes(IBinder displayToken, int[] modes);

    private static native void nativeSetInputWindowInfo(long transactionObj, long nativeObject,
            InputWindowHandle handle);

    private static native boolean nativeGetProtectedContentSupport();
    private static native void nativeSetMetadata(long transactionObj, long nativeObject, int key,
            Parcel data);
    private static native void nativeSyncInputWindows(long transactionObj);
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

    private static native void nativeSetFrameRate(long transactionObj, long nativeObject,
            float frameRate, int compatibility, int changeFrameRateStrategy);
    private static native long nativeGetHandle(long nativeObject);

    private static native long nativeAcquireFrameRateFlexibilityToken();
    private static native void nativeReleaseFrameRateFlexibilityToken(long token);
    private static native void nativeSetFixedTransformHint(long transactionObj, long nativeObject,
            int transformHint);
    private static native void nativeSetFocusedWindow(long transactionObj, IBinder toToken,
            String windowName, IBinder focusedToken, String focusedWindowName, int displayId);
    private static native void nativeSetFrameTimelineVsync(long transactionObj,
            long frameTimelineVsyncId);
    private static native void nativeAddJankDataListener(long nativeListener,
            long nativeSurfaceControl);
    private static native void nativeRemoveJankDataListener(long nativeListener);
    private static native long nativeCreateJankDataListenerWrapper(OnJankDataListener listener);
    private static native int nativeGetGPUContextPriority();
    private static native void nativeSetTransformHint(long nativeObject, int transformHint);
    private static native int nativeGetTransformHint(long nativeObject);

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
        // Predictions live for 120ms, if prediction is expired for a frame, there is definitely a
        // jank
        // associated with the App if this is for a SurfaceFrame, and SF for a DisplayFrame.
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

        public JankData(long frameVsyncId, @JankType int jankType) {
            this.frameVsyncId = frameVsyncId;
            this.jankType = jankType;
        }

        public final long frameVsyncId;
        public final @JankType int jankType;
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

     /**
     * @hide
     */
    public long mNativeObject;
    private long mNativeHandle;

    // TODO: Move width/height to native and fix locking through out.
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private int mWidth;
    @GuardedBy("mLock")
    private int mHeight;

    private int mTransformHint;

    private WeakReference<View> mLocalOwnerView;

    static GlobalTransactionWrapper sGlobalTransaction;
    static long sTransactionNestCount = 0;

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
     * @see #createDisplay(String, boolean)
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
     * internal representation of how to interpret pixel value, used only to convert to ColorSpace.
     */
    private static final int INTERNAL_DATASPACE_SRGB = 142671872;
    private static final int INTERNAL_DATASPACE_DISPLAY_P3 = 143261696;
    private static final int INTERNAL_DATASPACE_SCRGB = 411107328;

    private void assignNativeObject(long nativeObject, String callsite) {
        if (mNativeObject != 0) {
            release();
        }
        if (nativeObject != 0) {
            mCloseGuard.openWithCallSite("release", callsite);
        }
        mNativeObject = nativeObject;
        mNativeHandle = mNativeObject != 0 ? nativeGetHandle(nativeObject) : 0;
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

    /**
     * A wrapper around HardwareBuffer that contains extra information about how to
     * interpret the screenshot HardwareBuffer.
     *
     * @hide
     */
    public static class ScreenshotHardwareBuffer {
        private final HardwareBuffer mHardwareBuffer;
        private final ColorSpace mColorSpace;
        private final boolean mContainsSecureLayers;

        public ScreenshotHardwareBuffer(HardwareBuffer hardwareBuffer, ColorSpace colorSpace,
                boolean containsSecureLayers) {
            mHardwareBuffer = hardwareBuffer;
            mColorSpace = colorSpace;
            mContainsSecureLayers = containsSecureLayers;
        }

       /**
        * Create ScreenshotHardwareBuffer from an existing HardwareBuffer object.
        * @param hardwareBuffer The existing HardwareBuffer object
        * @param namedColorSpace Integer value of a named color space {@link ColorSpace.Named}
        * @param containsSecureLayers Indicates whether this graphic buffer contains captured
        *                             contents
        *        of secure layers, in which case the screenshot should not be persisted.
        */
        private static ScreenshotHardwareBuffer createFromNative(HardwareBuffer hardwareBuffer,
                int namedColorSpace, boolean containsSecureLayers) {
            ColorSpace colorSpace = ColorSpace.get(ColorSpace.Named.values()[namedColorSpace]);
            return new ScreenshotHardwareBuffer(hardwareBuffer, colorSpace, containsSecureLayers);
        }

        public ColorSpace getColorSpace() {
            return mColorSpace;
        }

        public HardwareBuffer getHardwareBuffer() {
            return mHardwareBuffer;
        }

        public boolean containsSecureLayers() {
            return mContainsSecureLayers;
        }

        /**
         * Copy content of ScreenshotHardwareBuffer into a hardware bitmap and return it.
         * Note: If you want to modify the Bitmap in software, you will need to copy the Bitmap
         * into
         * a software Bitmap using {@link Bitmap#copy(Bitmap.Config, boolean)}
         *
         * CAVEAT: This can be extremely slow; avoid use unless absolutely necessary; prefer to
         * directly
         * use the {@link HardwareBuffer} directly.
         *
         * @return Bitmap generated from the {@link HardwareBuffer}
         */
        public Bitmap asBitmap() {
            if (mHardwareBuffer == null) {
                Log.w(TAG, "Failed to take screenshot. Null screenshot object");
                return null;
            }
            return Bitmap.wrapHardwareBuffer(mHardwareBuffer, mColorSpace);
        }
    }

    /**
     * @hide
     */
    public interface ScreenCaptureListener {
        /**
         * The callback invoked when the screen capture is complete.
         * @param hardwareBuffer Data containing info about the screen capture.
         */
        void onScreenCaptureComplete(ScreenshotHardwareBuffer hardwareBuffer);
    }

    private static class SyncScreenCaptureListener implements ScreenCaptureListener {
        private static final int SCREENSHOT_WAIT_TIME_S = 1;
        private ScreenshotHardwareBuffer mScreenshotHardwareBuffer;
        private final CountDownLatch mCountDownLatch = new CountDownLatch(1);

        @Override
        public void onScreenCaptureComplete(ScreenshotHardwareBuffer hardwareBuffer) {
            mScreenshotHardwareBuffer = hardwareBuffer;
            mCountDownLatch.countDown();
        }

        private ScreenshotHardwareBuffer waitForScreenshot() {
            try {
                mCountDownLatch.await(SCREENSHOT_WAIT_TIME_S, TimeUnit.SECONDS);
            } catch (Exception e) {
                Log.e(TAG, "Failed to wait for screen capture result", e);
            }

            return mScreenshotHardwareBuffer;
        }
    }

    /**
     * A common arguments class used for various screenshot requests. This contains arguments that
     * are shared between {@link DisplayCaptureArgs} and {@link LayerCaptureArgs}
     * @hide
     */
    private abstract static class CaptureArgs {
        private final int mPixelFormat;
        private final Rect mSourceCrop = new Rect();
        private final float mFrameScaleX;
        private final float mFrameScaleY;
        private final boolean mCaptureSecureLayers;
        private final boolean mAllowProtected;
        private final long mUid;
        private final boolean mGrayscale;

        private CaptureArgs(Builder<? extends Builder<?>> builder) {
            mPixelFormat = builder.mPixelFormat;
            mSourceCrop.set(builder.mSourceCrop);
            mFrameScaleX = builder.mFrameScaleX;
            mFrameScaleY = builder.mFrameScaleY;
            mCaptureSecureLayers = builder.mCaptureSecureLayers;
            mAllowProtected = builder.mAllowProtected;
            mUid = builder.mUid;
            mGrayscale = builder.mGrayscale;
        }

        /**
         * The Builder class used to construct {@link CaptureArgs}
         *
         * @param <T> A builder that extends {@link Builder}
         */
        abstract static class Builder<T extends Builder<T>> {
            private int mPixelFormat = PixelFormat.RGBA_8888;
            private final Rect mSourceCrop = new Rect();
            private float mFrameScaleX = 1;
            private float mFrameScaleY = 1;
            private boolean mCaptureSecureLayers;
            private boolean mAllowProtected;
            private long mUid = -1;
            private boolean mGrayscale;

            /**
             * The desired pixel format of the returned buffer.
             */
            public T setPixelFormat(int pixelFormat) {
                mPixelFormat = pixelFormat;
                return getThis();
            }

            /**
             * The portion of the screen to capture into the buffer. Caller may pass  in
             * 'new Rect()' if no cropping is desired.
             */
            public T setSourceCrop(Rect sourceCrop) {
                mSourceCrop.set(sourceCrop);
                return getThis();
            }

            /**
             * The desired scale of the returned buffer. The raw screen will be scaled up/down.
             */
            public T setFrameScale(float frameScale) {
                mFrameScaleX = frameScale;
                mFrameScaleY = frameScale;
                return getThis();
            }

            /**
             * The desired scale of the returned buffer, allowing separate values for x and y scale.
             * The raw screen will be scaled up/down.
             */
            public T setFrameScale(float frameScaleX, float frameScaleY) {
                mFrameScaleX = frameScaleX;
                mFrameScaleY = frameScaleY;
                return getThis();
            }

            /**
             * Whether to allow the screenshot of secure layers. Warning: This should only be done
             * if the content will be placed in a secure SurfaceControl.
             *
             * @see ScreenshotHardwareBuffer#containsSecureLayers()
             */
            public T setCaptureSecureLayers(boolean captureSecureLayers) {
                mCaptureSecureLayers = captureSecureLayers;
                return getThis();
            }

            /**
             * Whether to allow the screenshot of protected (DRM) content. Warning: The screenshot
             * cannot be read in unprotected space.
             *
             * @see HardwareBuffer#USAGE_PROTECTED_CONTENT
             */
            public T setAllowProtected(boolean allowProtected) {
                mAllowProtected = allowProtected;
                return getThis();
            }

            /**
             * Set the uid of the content that should be screenshot. The code will skip any surfaces
             * that don't belong to the specified uid.
             */
            public T setUid(long uid) {
                mUid = uid;
                return getThis();
            }

            /**
             * Set whether the screenshot should use grayscale or not.
             */
            public T setGrayscale(boolean grayscale) {
                mGrayscale = grayscale;
                return getThis();
            }

            /**
             * Each sub class should return itself to allow the builder to chain properly
             */
            abstract T getThis();
        }
    }

    /**
     * The arguments class used to make display capture requests.
     *
     * @see #nativeCaptureDisplay(DisplayCaptureArgs, ScreenCaptureListener)
     * @hide
     */
    public static class DisplayCaptureArgs extends CaptureArgs {
        private final IBinder mDisplayToken;
        private final int mWidth;
        private final int mHeight;
        private final boolean mUseIdentityTransform;

        private DisplayCaptureArgs(Builder builder) {
            super(builder);
            mDisplayToken = builder.mDisplayToken;
            mWidth = builder.mWidth;
            mHeight = builder.mHeight;
            mUseIdentityTransform = builder.mUseIdentityTransform;
        }

        /**
         * The Builder class used to construct {@link DisplayCaptureArgs}
         */
        public static class Builder extends CaptureArgs.Builder<Builder> {
            private IBinder mDisplayToken;
            private int mWidth;
            private int mHeight;
            private boolean mUseIdentityTransform;

            /**
             * Construct a new {@link LayerCaptureArgs} with the set parameters. The builder
             * remains valid.
             */
            public DisplayCaptureArgs build() {
                if (mDisplayToken == null) {
                    throw new IllegalStateException(
                            "Can't take screenshot with null display token");
                }
                return new DisplayCaptureArgs(this);
            }

            public Builder(IBinder displayToken) {
                setDisplayToken(displayToken);
            }

            /**
             * The display to take the screenshot of.
             */
            public Builder setDisplayToken(IBinder displayToken) {
                mDisplayToken = displayToken;
                return this;
            }

            /**
             * Set the desired size of the returned buffer. The raw screen  will be  scaled down to
             * this size
             *
             * @param width  The desired width of the returned buffer. Caller may pass in 0 if no
             *               scaling is desired.
             * @param height The desired height of the returned buffer. Caller may pass in 0 if no
             *               scaling is desired.
             */
            public Builder setSize(int width, int height) {
                mWidth = width;
                mHeight = height;
                return this;
            }

            /**
             * Replace the rotation transform of the display with the identity transformation while
             * taking the screenshot. This ensures the screenshot is taken in the ROTATION_0
             * orientation. Set this value to false if the screenshot should be taken in the
             * current screen orientation.
             */
            public Builder setUseIdentityTransform(boolean useIdentityTransform) {
                mUseIdentityTransform = useIdentityTransform;
                return this;
            }

            @Override
            Builder getThis() {
                return this;
            }
        }
    }

    /**
     * The arguments class used to make layer capture requests.
     *
     * @see #nativeCaptureLayers(LayerCaptureArgs, ScreenCaptureListener)
     * @hide
     */
    public static class LayerCaptureArgs extends CaptureArgs {
        private final long mNativeLayer;
        private final long[] mNativeExcludeLayers;
        private final boolean mChildrenOnly;

        private LayerCaptureArgs(Builder builder) {
            super(builder);
            mChildrenOnly = builder.mChildrenOnly;
            mNativeLayer = builder.mLayer.mNativeObject;
            if (builder.mExcludeLayers != null) {
                mNativeExcludeLayers = new long[builder.mExcludeLayers.length];
                for (int i = 0; i < builder.mExcludeLayers.length; i++) {
                    mNativeExcludeLayers[i] = builder.mExcludeLayers[i].mNativeObject;
                }
            } else {
                mNativeExcludeLayers = null;
            }
        }

        /**
         * The Builder class used to construct {@link LayerCaptureArgs}
         */
        public static class Builder extends CaptureArgs.Builder<Builder> {
            private SurfaceControl mLayer;
            private SurfaceControl[] mExcludeLayers;
            private boolean mChildrenOnly = true;

            /**
             * Construct a new {@link LayerCaptureArgs} with the set parameters. The builder
             * remains valid.
             */
            public LayerCaptureArgs build() {
                if (mLayer == null) {
                    throw new IllegalStateException(
                            "Can't take screenshot with null layer");
                }
                return new LayerCaptureArgs(this);
            }

            public Builder(SurfaceControl layer) {
                setLayer(layer);
            }

            /**
             * The root layer to capture.
             */
            public Builder setLayer(SurfaceControl layer) {
                mLayer = layer;
                return this;
            }


            /**
             * An array of layer handles to exclude.
             */
            public Builder setExcludeLayers(@Nullable SurfaceControl[] excludeLayers) {
                mExcludeLayers = excludeLayers;
                return this;
            }

            /**
             * Whether to include the layer itself in the screenshot or just the children and their
             * descendants.
             */
            public Builder setChildrenOnly(boolean childrenOnly) {
                mChildrenOnly = childrenOnly;
                return this;
            }

            @Override
            Builder getThis() {
                return this;
            }

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
        mNativeHandle = nativeGetHandle(mNativeObject);
        mCloseGuard.openWithCallSite("release", callsite);
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
            mNativeHandle = 0;
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
                "Invalid " + this + ", mNativeObject is null. Have you called release() already?");
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

        @Override
        public String toString() {
            return "StaticDisplayInfo{isInternal=" + isInternal
                    + ", density=" + density
                    + ", secure=" + secure
                    + ", deviceProductInfo=" + deviceProductInfo + "}";
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            StaticDisplayInfo that = (StaticDisplayInfo) o;
            return isInternal == that.isInternal
                    && density == that.density
                    && secure == that.secure
                    && Objects.equals(deviceProductInfo, that.deviceProductInfo);
        }

        @Override
        public int hashCode() {
            return Objects.hash(isInternal, density, secure, deviceProductInfo);
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

        public int[] supportedColorModes;
        public int activeColorMode;

        public Display.HdrCapabilities hdrCapabilities;

        public boolean autoLowLatencyModeSupported;
        public boolean gameContentTypeSupported;

        @Override
        public String toString() {
            return "DynamicDisplayInfo{"
                    + "supportedDisplayModes=" + Arrays.toString(supportedDisplayModes)
                    + ", activeDisplayModeId=" + activeDisplayModeId
                    + ", supportedColorModes=" + Arrays.toString(supportedColorModes)
                    + ", activeColorMode=" + activeColorMode
                    + ", hdrCapabilities=" + hdrCapabilities
                    + ", autoLowLatencyModeSupported=" + autoLowLatencyModeSupported
                    + ", gameContentTypeSupported" + gameContentTypeSupported + "}";
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DynamicDisplayInfo that = (DynamicDisplayInfo) o;
            return Arrays.equals(supportedDisplayModes, that.supportedDisplayModes)
                && activeDisplayModeId == that.activeDisplayModeId
                && Arrays.equals(supportedColorModes, that.supportedColorModes)
                && activeColorMode == that.activeColorMode
                && Objects.equals(hdrCapabilities, that.hdrCapabilities);
        }

        @Override
        public int hashCode() {
            return Objects.hash(supportedDisplayModes, activeDisplayModeId, activeDisplayModeId,
                    activeColorMode, hdrCapabilities);
        }
    }

    /**
     * Configuration supported by physical display.
     *
     * @hide
     */
    public static final class DisplayMode {
        /**
         * Invalid display config id.
         */
        public static final int INVALID_DISPLAY_MODE_ID = -1;

        public int id;
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
        public int group;

        @Override
        public String toString() {
            return "DisplayMode{id=" + id
                    + ", width=" + width
                    + ", height=" + height
                    + ", xDpi=" + xDpi
                    + ", yDpi=" + yDpi
                    + ", refreshRate=" + refreshRate
                    + ", appVsyncOffsetNanos=" + appVsyncOffsetNanos
                    + ", presentationDeadlineNanos=" + presentationDeadlineNanos
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
                    && Float.compare(that.refreshRate, refreshRate) == 0
                    && appVsyncOffsetNanos == that.appVsyncOffsetNanos
                    && presentationDeadlineNanos == that.presentationDeadlineNanos
                    && group == that.group;
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, width, height, xDpi, yDpi, refreshRate, appVsyncOffsetNanos,
                    presentationDeadlineNanos, group);
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
    public static StaticDisplayInfo getStaticDisplayInfo(IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        return nativeGetStaticDisplayInfo(displayToken);
    }

    /**
     * @hide
     */
    public static DynamicDisplayInfo getDynamicDisplayInfo(IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        return nativeGetDynamicDisplayInfo(displayToken);
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
    public static final class DesiredDisplayModeSpecs {
        public int defaultMode;
        /**
         * The primary refresh rate range represents display manager's general guidance on the
         * display configs surface flinger will consider when switching refresh rates. Unless
         * surface flinger has a specific reason to do otherwise, it will stay within this range.
         */
        public float primaryRefreshRateMin;
        public float primaryRefreshRateMax;
        /**
         * The app request refresh rate range allows surface flinger to consider more display
         * configs when switching refresh rates. Although surface flinger will generally stay within
         * the primary range, specific considerations, such as layer frame rate settings specified
         * via the setFrameRate() api, may cause surface flinger to go outside the primary
         * range. Surface flinger never goes outside the app request range. The app request range
         * will be greater than or equal to the primary refresh rate range, never smaller.
         */
        public float appRequestRefreshRateMin;
        public float appRequestRefreshRateMax;

        /**
         * If true this will allow switching between modes in different display configuration
         * groups. This way the user may see visual interruptions when the display mode changes.
         */
        public boolean allowGroupSwitching;

        public DesiredDisplayModeSpecs() {}

        public DesiredDisplayModeSpecs(DesiredDisplayModeSpecs other) {
            copyFrom(other);
        }

        public DesiredDisplayModeSpecs(int defaultMode, boolean allowGroupSwitching,
                float primaryRefreshRateMin, float primaryRefreshRateMax,
                float appRequestRefreshRateMin, float appRequestRefreshRateMax) {
            this.defaultMode = defaultMode;
            this.allowGroupSwitching = allowGroupSwitching;
            this.primaryRefreshRateMin = primaryRefreshRateMin;
            this.primaryRefreshRateMax = primaryRefreshRateMax;
            this.appRequestRefreshRateMin = appRequestRefreshRateMin;
            this.appRequestRefreshRateMax = appRequestRefreshRateMax;
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
                    && primaryRefreshRateMin == other.primaryRefreshRateMin
                    && primaryRefreshRateMax == other.primaryRefreshRateMax
                    && appRequestRefreshRateMin == other.appRequestRefreshRateMin
                    && appRequestRefreshRateMax == other.appRequestRefreshRateMax;
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
            primaryRefreshRateMin = other.primaryRefreshRateMin;
            primaryRefreshRateMax = other.primaryRefreshRateMax;
            appRequestRefreshRateMin = other.appRequestRefreshRateMin;
            appRequestRefreshRateMax = other.appRequestRefreshRateMax;
        }

        @Override
        public String toString() {
            return String.format("defaultConfig=%d primaryRefreshRateRange=[%.0f %.0f]"
                            + " appRequestRefreshRateRange=[%.0f %.0f]",
                    defaultMode, primaryRefreshRateMin, primaryRefreshRateMax,
                    appRequestRefreshRateMin, appRequestRefreshRateMax);
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
     * Overrides HDR modes for a display device.
     *
     * If the caller does not have ACCESS_SURFACE_FLINGER permission, this will throw a Security
     * Exception.
     * @hide
     */
    @TestApi
    public static void overrideHdrTypes(@NonNull IBinder displayToken, @NonNull int[] modes) {
        nativeOverrideHdrTypes(displayToken, modes);
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
    @TestApi
    @NonNull
    public static IBinder getInternalDisplayToken() {
        final long[] physicalDisplayIds = getPhysicalDisplayIds();
        if (physicalDisplayIds.length == 0) {
            return null;
        }
        return getPhysicalDisplayToken(physicalDisplayIds[0]);
    }

    /**
     * @param captureArgs Arguments about how to take the screenshot
     * @param captureListener A listener to receive the screenshot callback
     * @hide
     */
    public static int captureDisplay(@NonNull DisplayCaptureArgs captureArgs,
            @NonNull ScreenCaptureListener captureListener) {
        return nativeCaptureDisplay(captureArgs, captureListener);
    }

    /**
     * Captures all the surfaces in a display and returns a {@link ScreenshotHardwareBuffer} with
     * the content.
     *
     * @hide
     */
    public static ScreenshotHardwareBuffer captureDisplay(DisplayCaptureArgs captureArgs) {
        SyncScreenCaptureListener screenCaptureListener = new SyncScreenCaptureListener();

        int status = captureDisplay(captureArgs, screenCaptureListener);
        if (status != 0) {
            return null;
        }

        return screenCaptureListener.waitForScreenshot();
    }

    /**
     * Captures a layer and its children and returns a {@link HardwareBuffer} with the content.
     *
     * @param layer            The root layer to capture.
     * @param sourceCrop       The portion of the root surface to capture; caller may pass in 'new
     *                         Rect()' or null if no cropping is desired. If the root layer does not
     *                         have a buffer or a crop set, then a non-empty source crop must be
     *                         specified.
     * @param frameScale       The desired scale of the returned buffer; the raw
     *                         screen will be scaled up/down.
     *
     * @return Returns a HardwareBuffer that contains the layer capture.
     * @hide
     */
    public static ScreenshotHardwareBuffer captureLayers(SurfaceControl layer, Rect sourceCrop,
            float frameScale) {
        return captureLayers(layer, sourceCrop, frameScale, PixelFormat.RGBA_8888);
    }

    /**
     * Captures a layer and its children and returns a {@link HardwareBuffer} with the content.
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
     * @return Returns a HardwareBuffer that contains the layer capture.
     * @hide
     */
    public static ScreenshotHardwareBuffer captureLayers(SurfaceControl layer, Rect sourceCrop,
            float frameScale, int format) {
        LayerCaptureArgs captureArgs = new LayerCaptureArgs.Builder(layer)
                .setSourceCrop(sourceCrop)
                .setFrameScale(frameScale)
                .setPixelFormat(format)
                .build();

        return captureLayers(captureArgs);
    }

    /**
     * @hide
     */
    public static ScreenshotHardwareBuffer captureLayers(LayerCaptureArgs captureArgs) {
        SyncScreenCaptureListener screenCaptureListener = new SyncScreenCaptureListener();

        int status = captureLayers(captureArgs, screenCaptureListener);
        if (status != 0) {
            return null;
        }

        return screenCaptureListener.waitForScreenshot();
    }

    /**
     * Like {@link #captureLayers(SurfaceControl, Rect, float, int)} but with an array of layer
     * handles to exclude.
     * @hide
     */
    public static ScreenshotHardwareBuffer captureLayersExcluding(SurfaceControl layer,
            Rect sourceCrop, float frameScale, int format, SurfaceControl[] exclude) {
        LayerCaptureArgs captureArgs = new LayerCaptureArgs.Builder(layer)
                .setSourceCrop(sourceCrop)
                .setFrameScale(frameScale)
                .setPixelFormat(format)
                .setExcludeLayers(exclude)
                .build();

        return captureLayers(captureArgs);
    }

    /**
     * @param captureArgs Arguments about how to take the screenshot
     * @param captureListener A listener to receive the screenshot callback
     * @hide
     */
    public static int captureLayers(@NonNull LayerCaptureArgs captureArgs,
            @NonNull ScreenCaptureListener captureListener) {
        return nativeCaptureLayers(captureArgs, captureListener);
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
            applyResizedSurfaces();
            notifyReparentedSurfaces();
            nativeApplyTransaction(mNativeObject, sync);
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
         * Specify how the buffer associated with this Surface is mapped in to the
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
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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
          * Provides a hint to SurfaceFlinger to change its offset so that SurfaceFlinger wakes up
          * earlier to compose surfaces. The caller should use this as a hint to SurfaceFlinger
          * when the scene is complex enough to use GPU composition. The hint will remain active
          * until until the client calls {@link Transaction#setEarlyWakeupEnd}.
          *
          * @hide
          */
        public Transaction setEarlyWakeupStart() {
            nativeSetEarlyWakeupStart(mNativeObject);
            return this;
        }

        /**
         * Removes the early wake up hint set by {@link Transaction#setEarlyWakeupStart}.
         *
         * @hide
         */
        public Transaction setEarlyWakeupEnd() {
            nativeSetEarlyWakeupEnd(mNativeObject);
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
            nativeSetFocusedWindow(mNativeObject, token,  windowName,
                    null /* focusedToken */, null /* focusedWindowName */, displayId);
            return this;
        }

        /**
         * Set focus on the window identified by the input {@code token} if the window identified by
         * the input {@code focusedToken} is currently focused. If the {@code focusedToken} does not
         * have focus, the request is dropped.
         *
         * This is used by forward focus transfer requests from clients that host embedded windows,
         * and want to transfer focus to/from them.
         *
         * @hide
         */
        public Transaction requestFocusTransfer(@NonNull IBinder token,
                                                String windowName,
                                                @NonNull IBinder focusedToken,
                                                String focusedWindowName,
                                                int displayId) {
            nativeSetFocusedWindow(mNativeObject, token, windowName, focusedToken,
                    focusedWindowName, displayId);
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
         */
        public Transaction setBuffer(SurfaceControl sc, GraphicBuffer buffer) {
            checkPreconditions(sc);
            nativeSetBuffer(mNativeObject, sc.mNativeObject, buffer);
            return this;
        }

        /**
         * Set the color space for the SurfaceControl. The supported color spaces are SRGB
         * and Display P3, other color spaces will be treated as SRGB. This can only be used for
         * SurfaceControls that were created as type {@link #FX_SURFACE_BLAST}
         *
         * @hide
         */
        public Transaction setColorSpace(SurfaceControl sc, ColorSpace colorSpace) {
            checkPreconditions(sc);
            nativeSetColorSpace(mNativeObject, sc.mNativeObject, colorSpace.getId());
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
            nativeApplyTransaction(mNativeObject, sync);
        }

        @Override
        public void apply(boolean sync) {
            throw new RuntimeException("Global transaction must be applied from closeTransaction");
        }
    }

    /**
     * Acquire a frame rate flexibility token, which allows surface flinger to freely switch display
     * frame rates. This is used by CTS tests to put the device in a consistent state. See
     * ISurfaceComposer::acquireFrameRateFlexibilityToken(). The caller must have the
     * ACCESS_SURFACE_FLINGER permission, or else the call will fail, returning 0.
     * @hide
     */
    @TestApi
    public static long acquireFrameRateFlexibilityToken() {
        return nativeAcquireFrameRateFlexibilityToken();
    }

    /**
     * Release a frame rate flexibility token.
     * @hide
     */
    @TestApi
    public static void releaseFrameRateFlexibilityToken(long token) {
        nativeReleaseFrameRateFlexibilityToken(token);
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
    public int getTransformHint() {
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
    public void setTransformHint(@Surface.Rotation int transformHint) {
        nativeSetTransformHint(mNativeObject, transformHint);
    }
}
