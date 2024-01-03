/*
 * Copyright (C) 2007 The Android Open Source Project
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

import static android.system.OsConstants.EINVAL;

import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.pm.ActivityInfo;
import android.content.res.CompatibilityInfo.Translator;
import android.graphics.BLASTBufferQueue;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.HardwareRenderer;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.graphics.SurfaceTexture;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import dalvik.system.CloseGuard;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Handle onto a raw buffer that is being managed by the screen compositor.
 *
 * <p>A Surface is generally created by or from a consumer of image buffers (such as a
 * {@link android.graphics.SurfaceTexture}, {@link android.media.MediaRecorder}, or
 * {@link android.renderscript.Allocation}), and is handed to some kind of producer (such as
 * {@link android.opengl.EGL14#eglCreateWindowSurface(android.opengl.EGLDisplay,android.opengl.EGLConfig,java.lang.Object,int[],int) OpenGL},
 * {@link android.media.MediaPlayer#setSurface MediaPlayer}, or
 * {@link android.hardware.camera2.CameraDevice#createCaptureSession CameraDevice}) to draw
 * into.</p>
 *
 * <p><strong>Note:</strong> A Surface acts like a
 * {@link java.lang.ref.WeakReference weak reference} to the consumer it is associated with. By
 * itself it will not keep its parent consumer from being reclaimed.</p>
 */
public class Surface implements Parcelable {
    private static final String TAG = "Surface";

    private static native long nativeCreateFromSurfaceTexture(SurfaceTexture surfaceTexture)
            throws OutOfResourcesException;

    private static native long nativeCreateFromSurfaceControl(long surfaceControlNativeObject);
    private static native long nativeGetFromSurfaceControl(long surfaceObject,
            long surfaceControlNativeObject);
    private static native long nativeGetFromBlastBufferQueue(long surfaceObject,
                                                             long blastBufferQueueNativeObject);

    private static native long nativeLockCanvas(long nativeObject, Canvas canvas, Rect dirty)
            throws OutOfResourcesException;
    private static native void nativeUnlockCanvasAndPost(long nativeObject, Canvas canvas);

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private static native void nativeRelease(long nativeObject);
    private static native boolean nativeIsValid(long nativeObject);
    private static native boolean nativeIsConsumerRunningBehind(long nativeObject);
    private static native long nativeReadFromParcel(long nativeObject, Parcel source);
    private static native void nativeWriteToParcel(long nativeObject, Parcel dest);

    private static native void nativeAllocateBuffers(long nativeObject);

    private static native int nativeGetWidth(long nativeObject);
    private static native int nativeGetHeight(long nativeObject);

    private static native long nativeGetNextFrameNumber(long nativeObject);
    private static native int nativeSetScalingMode(long nativeObject, int scalingMode);
    private static native int nativeForceScopedDisconnect(long nativeObject);
    private static native int nativeAttachAndQueueBufferWithColorSpace(long nativeObject,
            HardwareBuffer buffer, int colorSpaceId);

    private static native int nativeSetSharedBufferModeEnabled(long nativeObject, boolean enabled);
    private static native int nativeSetAutoRefreshEnabled(long nativeObject, boolean enabled);

    private static native int nativeSetFrameRate(
            long nativeObject, float frameRate, int compatibility, int changeFrameRateStrategy);
    private static native void nativeDestroy(long nativeObject);

    public static final @android.annotation.NonNull Parcelable.Creator<Surface> CREATOR =
            new Parcelable.Creator<Surface>() {
        @Override
        public Surface createFromParcel(Parcel source) {
            try {
                Surface s = new Surface();
                s.readFromParcel(source);
                return s;
            } catch (Exception e) {
                Log.e(TAG, "Exception creating surface from parcel", e);
                return null;
            }
        }

        @Override
        public Surface[] newArray(int size) {
            return new Surface[size];
        }
    };

    private final CloseGuard mCloseGuard = CloseGuard.get();

    // Guarded state.
    @UnsupportedAppUsage
    final Object mLock = new Object(); // protects the native state
    @UnsupportedAppUsage
    private String mName;
    @UnsupportedAppUsage
    long mNativeObject; // package scope only for SurfaceControl access
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private long mLockedObject;
    private int mGenerationId; // incremented each time mNativeObject changes
    private final Canvas mCanvas = new CompatibleCanvas();

    // A matrix to scale the matrix set by application. This is set to null for
    // non compatibility mode.
    private Matrix mCompatibleMatrix;

    private HwuiContext mHwuiContext;

    private boolean mIsSingleBuffered;
    private boolean mIsSharedBufferModeEnabled;
    private boolean mIsAutoRefreshEnabled;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "SCALING_MODE_" }, value = {
            SCALING_MODE_FREEZE,
            SCALING_MODE_SCALE_TO_WINDOW,
            SCALING_MODE_SCALE_CROP,
            SCALING_MODE_NO_SCALE_CROP
    })
    public @interface ScalingMode {}
    // From system/window.h
    /** @hide */
    public static final int SCALING_MODE_FREEZE = 0;
    /** @hide */
    public static final int SCALING_MODE_SCALE_TO_WINDOW = 1;
    /** @hide */
    public static final int SCALING_MODE_SCALE_CROP = 2;
    /** @hide */
    public static final int SCALING_MODE_NO_SCALE_CROP = 3;

    /** @hide */
    @IntDef(prefix = { "ROTATION_" }, value = {
            ROTATION_0,
            ROTATION_90,
            ROTATION_180,
            ROTATION_270
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Rotation {}

    /**
     * Rotation constant: 0 degree rotation (natural orientation)
     */
    public static final int ROTATION_0 = 0;

    /**
     * Rotation constant: 90 degree rotation.
     */
    public static final int ROTATION_90 = 1;

    /**
     * Rotation constant: 180 degree rotation.
     */
    public static final int ROTATION_180 = 2;

    /**
     * Rotation constant: 270 degree rotation.
     */
    public static final int ROTATION_270 = 3;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"FRAME_RATE_COMPATIBILITY_"},
            value = {FRAME_RATE_COMPATIBILITY_DEFAULT, FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    FRAME_RATE_COMPATIBILITY_EXACT, FRAME_RATE_COMPATIBILITY_NO_VOTE,
                    FRAME_RATE_COMPATIBILITY_MIN, FRAME_RATE_COMPATIBILITY_GTE})
    public @interface FrameRateCompatibility {}

    // From native_window.h. Keep these in sync.
    /**
     * There are no inherent restrictions on the frame rate of this surface. When the
     * system selects a frame rate other than what the app requested, the app will be able
     * to run at the system frame rate without requiring pull down. This value should be
     * used when displaying game content, UIs, and anything that isn't video.
     */
    public static final int FRAME_RATE_COMPATIBILITY_DEFAULT = 0;

    /**
     * This surface is being used to display content with an inherently fixed frame rate,
     * e.g. a video that has a specific frame rate. When the system selects a frame rate
     * other than what the app requested, the app will need to do pull down or use some
     * other technique to adapt to the system's frame rate. The user experience is likely
     * to be worse (e.g. more frame stuttering) than it would be if the system had chosen
     * the app's requested frame rate. This value should be used for video content.
     */
    public static final int FRAME_RATE_COMPATIBILITY_FIXED_SOURCE = 1;

    /**
     * This surface belongs to an app on the High Refresh Rate Deny list, and needs the display
     * to operate at the exact frame rate.
     *
     * This is used internally by the platform and should not be used by apps.
     * @hide
     */
    public static final int FRAME_RATE_COMPATIBILITY_EXACT = 100;

    // From window.h. Keep these in sync.
    /**
     * This surface is ignored while choosing the refresh rate.
     * @hide
     */
    public static final int FRAME_RATE_COMPATIBILITY_NO_VOTE = 101;

    // From window.h. Keep these in sync.
    /**
     * This surface will vote for the minimum refresh rate.
     * @hide
     */
    public static final int FRAME_RATE_COMPATIBILITY_MIN = 102;

    // From window.h. Keep these in sync.
    /**
     * The surface requests a frame rate that is greater than or equal to {@code frameRate}.
     * @hide
     */
    public static final int FRAME_RATE_COMPATIBILITY_GTE = 103;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"CHANGE_FRAME_RATE_"},
            value = {CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS, CHANGE_FRAME_RATE_ALWAYS})
    public @interface ChangeFrameRateStrategy {}

    /**
     * Change the frame rate only if the transition is going to be seamless.
     */
    public static final int CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS = 0;

    /**
     * Change the frame rate even if the transition is going to be non-seamless, i.e. with visual
     * interruptions for the user. Non-seamless switches might be used when the benefit of matching
     * the content's frame rate outweighs the cost of the transition, for example when
     * displaying long-running video content.
     */
    public static final int CHANGE_FRAME_RATE_ALWAYS = 1;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"FRAME_RATE_CATEGORY_"},
            value = {FRAME_RATE_CATEGORY_DEFAULT, FRAME_RATE_CATEGORY_NO_PREFERENCE,
                    FRAME_RATE_CATEGORY_LOW, FRAME_RATE_CATEGORY_NORMAL,
                    FRAME_RATE_CATEGORY_HIGH_HINT, FRAME_RATE_CATEGORY_HIGH})
    public @interface FrameRateCategory {}

    // From native_window.h or window.h. Keep these in sync.
    /**
     * Default value. This value can also be set to return to default behavior, indicating that this
     * layer has no data for the frame rate.
     * @hide
     */
    public static final int FRAME_RATE_CATEGORY_DEFAULT = 0;

    /**
     * The layer will explicitly not influence the frame rate.
     * This may indicate a frame rate suitable for no animation updates (such as a cursor blinking
     * or a sporadic update).
     * @hide
     */
    public static final int FRAME_RATE_CATEGORY_NO_PREFERENCE = 1;

    /**
     * Indicates a frame rate suitable for animations that looks fine even if played at a low frame
     * rate.
     * @hide
     */
    public static final int FRAME_RATE_CATEGORY_LOW = 2;

    /**
     * Indicates a middle frame rate suitable for animations that do not require higher frame
     * rates, or do not benefit from high smoothness. This is normally 60 Hz or close to it.
     * @hide
     */
    public static final int FRAME_RATE_CATEGORY_NORMAL = 3;

    /**
     * Hints that, as a result of a user interaction, an animation is likely to start.
     * This category is a signal that a user interaction heuristic determined the need of a
     * high refresh rate, and is not an explicit request from the app.
     * As opposed to {@link #FRAME_RATE_CATEGORY_HIGH}, this vote may be ignored in favor of
     * more explicit votes.
     * @hide
     */
    public static final int FRAME_RATE_CATEGORY_HIGH_HINT = 4;

    /**
     * Indicates a frame rate suitable for animations that require a high frame rate, which may
     * increase smoothness but may also increase power usage.
     * @hide
     */
    public static final int FRAME_RATE_CATEGORY_HIGH = 5;

    /**
     * Create an empty surface, which will later be filled in by readFromParcel().
     * @hide
     */
    @UnsupportedAppUsage
    public Surface() {
    }

    /**
     * Create a Surface associated with a given {@link SurfaceControl}. Buffers submitted to this
     * surface will be displayed by the system compositor according to the parameters
     * specified by the control. Multiple surfaces may be constructed from one SurfaceControl,
     * but only one can be connected (e.g. have an active EGL context) at a time.
     *
     * @param from The SurfaceControl to associate this Surface with
     */
    public Surface(@NonNull SurfaceControl from) {
        copyFrom(from);
    }

    /**
     * Create Surface from a {@link SurfaceTexture}.
     *
     * Images drawn to the Surface will be made available to the {@link
     * SurfaceTexture}, which can attach them to an OpenGL ES texture via {@link
     * SurfaceTexture#updateTexImage}.
     *
     * Please note that holding onto the Surface created here is not enough to
     * keep the provided SurfaceTexture from being reclaimed.  In that sense,
     * the Surface will act like a
     * {@link java.lang.ref.WeakReference weak reference} to the SurfaceTexture.
     *
     * @param surfaceTexture The {@link SurfaceTexture} that is updated by this
     * Surface.
     * @throws OutOfResourcesException if the surface could not be created.
     */
    public Surface(SurfaceTexture surfaceTexture) {
        if (surfaceTexture == null) {
            throw new IllegalArgumentException("surfaceTexture must not be null");
        }
        mIsSingleBuffered = surfaceTexture.isSingleBuffered();
        synchronized (mLock) {
            mName = surfaceTexture.toString();
            setNativeObjectLocked(nativeCreateFromSurfaceTexture(surfaceTexture));
        }
    }

    /* called from android_view_Surface_createFromIGraphicBufferProducer() */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private Surface(long nativeObject) {
        synchronized (mLock) {
            setNativeObjectLocked(nativeObject);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }
            release();
        } finally {
            super.finalize();
        }
    }

    /**
     * Release the local reference to the server-side surface.
     * Always call release() when you're done with a Surface.
     * This will make the surface invalid.
     */
    public void release() {
        synchronized (mLock) {
            if (mHwuiContext != null) {
                mHwuiContext.destroy();
                mHwuiContext = null;
            }
            if (mNativeObject != 0) {
                nativeRelease(mNativeObject);
                setNativeObjectLocked(0);
            }
        }
    }

    /**
     * Free all server-side state associated with this surface and
     * release this object's reference.  This method can only be
     * called from the process that created the service.
     * @hide
     */
    @UnsupportedAppUsage
    public void destroy() {
        synchronized (mLock) {
            if (mNativeObject != 0) {
                nativeDestroy(mNativeObject);
            }
            release();
        }
    }

    /**
     * Destroys the HwuiContext without completely
     * releasing the Surface.
     * @hide
     */
    public void hwuiDestroy() {
        if (mHwuiContext != null) {
            mHwuiContext.destroy();
            mHwuiContext = null;
        }
    }

    /**
     * Returns true if this object holds a valid surface.
     *
     * @return True if it holds a physical surface, so lockCanvas() will succeed.
     * Otherwise returns false.
     */
    public boolean isValid() {
        synchronized (mLock) {
            if (mNativeObject == 0) return false;
            return nativeIsValid(mNativeObject);
        }
    }

    /**
     * Gets the generation number of this surface, incremented each time
     * the native surface contained within this object changes.
     *
     * @return The current generation number.
     * @hide
     */
    public int getGenerationId() {
        synchronized (mLock) {
            return mGenerationId;
        }
    }

    /**
     * Returns the next frame number which will be dequeued for rendering.
     * Intended for use with SurfaceFlinger's deferred transactions API.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public long getNextFrameNumber() {
        synchronized (mLock) {
            checkNotReleasedLocked();
            return nativeGetNextFrameNumber(mNativeObject);
        }
    }

    /**
     * Returns true if the consumer of this Surface is running behind the producer.
     *
     * @return True if the consumer is more than one buffer ahead of the producer.
     * @hide
     */
    public boolean isConsumerRunningBehind() {
        synchronized (mLock) {
            checkNotReleasedLocked();
            return nativeIsConsumerRunningBehind(mNativeObject);
        }
    }

    /**
     * Returns the default size of this Surface provided by the consumer of the surface.
     * Should only be used by the producer of the surface.
     *
     * @hide
     */
    @NonNull
    public Point getDefaultSize() {
        synchronized (mLock) {
            checkNotReleasedLocked();
            return new Point(nativeGetWidth(mNativeObject), nativeGetHeight(mNativeObject));
        }
    }

    /**
     * Gets a {@link Canvas} for drawing into this surface.
     *
     * After drawing into the provided {@link Canvas}, the caller must
     * invoke {@link #unlockCanvasAndPost} to post the new contents to the surface.
     *
     * @param inOutDirty A rectangle that represents the dirty region that the caller wants
     * to redraw.  This function may choose to expand the dirty rectangle if for example
     * the surface has been resized or if the previous contents of the surface were
     * not available.  The caller must redraw the entire dirty region as represented
     * by the contents of the inOutDirty rectangle upon return from this function.
     * The caller may also pass <code>null</code> instead, in the case where the
     * entire surface should be redrawn.
     * @return A canvas for drawing into the surface.
     *
     * @throws IllegalArgumentException If the inOutDirty rectangle is not valid.
     * @throws OutOfResourcesException If the canvas cannot be locked.
     */
    public Canvas lockCanvas(Rect inOutDirty)
            throws Surface.OutOfResourcesException, IllegalArgumentException {
        synchronized (mLock) {
            checkNotReleasedLocked();
            if (mLockedObject != 0) {
                // Ideally, nativeLockCanvas() would throw in this situation and prevent the
                // double-lock, but that won't happen if mNativeObject was updated.  We can't
                // abandon the old mLockedObject because it might still be in use, so instead
                // we just refuse to re-lock the Surface.
                throw new IllegalArgumentException("Surface was already locked");
            }
            mLockedObject = nativeLockCanvas(mNativeObject, mCanvas, inOutDirty);
            return mCanvas;
        }
    }

    /**
     * Posts the new contents of the {@link Canvas} to the surface and
     * releases the {@link Canvas}.
     *
     * @param canvas The canvas previously obtained from {@link #lockCanvas}.
     */
    public void unlockCanvasAndPost(Canvas canvas) {
        synchronized (mLock) {
            checkNotReleasedLocked();

            if (mHwuiContext != null) {
                mHwuiContext.unlockAndPost(canvas);
            } else {
                unlockSwCanvasAndPost(canvas);
            }
        }
    }

    private void unlockSwCanvasAndPost(Canvas canvas) {
        if (canvas != mCanvas) {
            throw new IllegalArgumentException("canvas object must be the same instance that "
                    + "was previously returned by lockCanvas");
        }
        if (mNativeObject != mLockedObject) {
            Log.w(TAG, "WARNING: Surface's mNativeObject (0x" +
                    Long.toHexString(mNativeObject) + ") != mLockedObject (0x" +
                    Long.toHexString(mLockedObject) +")");
        }
        if (mLockedObject == 0) {
            throw new IllegalStateException("Surface was not locked");
        }
        try {
            nativeUnlockCanvasAndPost(mLockedObject, canvas);
        } finally {
            nativeRelease(mLockedObject);
            mLockedObject = 0;
        }
    }

    /**
     * Gets a {@link Canvas} for drawing into this surface.
     *
     * After drawing into the provided {@link Canvas}, the caller must
     * invoke {@link #unlockCanvasAndPost} to post the new contents to the surface.
     *
     * Unlike {@link #lockCanvas(Rect)} this will return a hardware-accelerated
     * canvas. See the <a href="{@docRoot}guide/topics/graphics/hardware-accel.html#unsupported">
     * unsupported drawing operations</a> for a list of what is and isn't
     * supported in a hardware-accelerated canvas. It is also required to
     * fully cover the surface every time {@link #lockHardwareCanvas()} is
     * called as the buffer is not preserved between frames. Partial updates
     * are not supported.
     *
     * @return A canvas for drawing into the surface.
     *
     * @throws IllegalStateException If the canvas cannot be locked.
     */
    public Canvas lockHardwareCanvas() {
        synchronized (mLock) {
            checkNotReleasedLocked();
            if (mHwuiContext == null) {
                mHwuiContext = new HwuiContext(false);
            }
            return mHwuiContext.lockCanvas(
                    nativeGetWidth(mNativeObject),
                    nativeGetHeight(mNativeObject));
        }
    }

    /**
     * Gets a {@link Canvas} for drawing into this surface that supports wide color gamut.
     *
     * After drawing into the provided {@link Canvas}, the caller must
     * invoke {@link #unlockCanvasAndPost} to post the new contents to the surface.
     *
     * Unlike {@link #lockCanvas(Rect)} and {@link #lockHardwareCanvas()},
     * this will return a hardware-accelerated canvas that supports wide color gamut.
     * See the <a href="{@docRoot}guide/topics/graphics/hardware-accel.html#unsupported">
     * unsupported drawing operations</a> for a list of what is and isn't
     * supported in a hardware-accelerated canvas. It is also required to
     * fully cover the surface every time {@link #lockHardwareCanvas()} is
     * called as the buffer is not preserved between frames. Partial updates
     * are not supported.
     *
     * @return A canvas for drawing into the surface.
     *
     * @throws IllegalStateException If the canvas cannot be locked.
     *
     * @hide
     */
    public Canvas lockHardwareWideColorGamutCanvas() {
        synchronized (mLock) {
            checkNotReleasedLocked();
            if (mHwuiContext != null && !mHwuiContext.isWideColorGamut()) {
                mHwuiContext.destroy();
                mHwuiContext = null;
            }
            if (mHwuiContext == null) {
                mHwuiContext = new HwuiContext(true);
            }
            return mHwuiContext.lockCanvas(
                    nativeGetWidth(mNativeObject),
                    nativeGetHeight(mNativeObject));
        }
    }

    /**
     * @deprecated This API has been removed and is not supported.  Do not use.
     */
    @Deprecated
    public void unlockCanvas(Canvas canvas) {
        throw new UnsupportedOperationException();
    }

    /**
     * Sets the translator used to scale canvas's width/height in compatibility
     * mode.
     */
    void setCompatibilityTranslator(Translator translator) {
        if (translator != null) {
            float appScale = translator.applicationScale;
            mCompatibleMatrix = new Matrix();
            mCompatibleMatrix.setScale(appScale, appScale);
        }
    }

    private void updateNativeObject(long newNativeObject) {
        synchronized (mLock) {
            if (newNativeObject == mNativeObject) {
                return;
            }
            if (mNativeObject != 0) {
                nativeRelease(mNativeObject);
            }
            setNativeObjectLocked(newNativeObject);
        }
    }

    /**
     * Copy another surface to this one.  This surface now holds a reference
     * to the same data as the original surface, and is -not- the owner.
     * This is for use by the window manager when returning a window surface
     * back from a client, converting it from the representation being managed
     * by the window manager to the representation the client uses to draw
     * in to it.
     *
     * @param other {@link SurfaceControl} to copy from.
     * @hide
     */
    @UnsupportedAppUsage
    public void copyFrom(SurfaceControl other) {
        if (other == null) {
            throw new IllegalArgumentException("other must not be null");
        }

        long surfaceControlPtr = other.mNativeObject;
        if (surfaceControlPtr == 0) {
            throw new NullPointerException(
                    "null SurfaceControl native object. Are you using a released SurfaceControl?");
        }
        long newNativeObject = nativeGetFromSurfaceControl(mNativeObject, surfaceControlPtr);
        updateNativeObject(newNativeObject);
    }

    /**
     * Update the surface if the BLASTBufferQueue IGraphicBufferProducer is different from this
     * surface's IGraphicBufferProducer.
     *
     * @param queue {@link BLASTBufferQueue} to copy from.
     * @hide
     */
    public void copyFrom(BLASTBufferQueue queue) {
        if (queue == null) {
            throw new IllegalArgumentException("queue must not be null");
        }

        long blastBufferQueuePtr = queue.mNativeObject;
        if (blastBufferQueuePtr == 0) {
            throw new NullPointerException("Null BLASTBufferQueue native object");
        }
        long newNativeObject = nativeGetFromBlastBufferQueue(mNativeObject, blastBufferQueuePtr);
        updateNativeObject(newNativeObject);
    }

    /**
     * Gets a reference a surface created from this one.  This surface now holds a reference
     * to the same data as the original surface, and is -not- the owner.
     * This is for use by the window manager when returning a window surface
     * back from a client, converting it from the representation being managed
     * by the window manager to the representation the client uses to draw
     * in to it.
     *
     * @param other {@link SurfaceControl} to create surface from.
     *
     * @hide
     */
    public void createFrom(SurfaceControl other) {
        if (other == null) {
            throw new IllegalArgumentException("other must not be null");
        }

        long surfaceControlPtr = other.mNativeObject;
        if (surfaceControlPtr == 0) {
            throw new NullPointerException(
                    "null SurfaceControl native object. Are you using a released SurfaceControl?");
        }
        long newNativeObject = nativeCreateFromSurfaceControl(surfaceControlPtr);

        synchronized (mLock) {
            if (mNativeObject != 0) {
                nativeRelease(mNativeObject);
            }
            setNativeObjectLocked(newNativeObject);
        }
    }

    /**
     * This is intended to be used by {@link SurfaceView#updateWindow} only.
     * @param other access is not thread safe
     * @hide
     * @deprecated
     */
    @Deprecated
    @UnsupportedAppUsage
    public void transferFrom(Surface other) {
        if (other == null) {
            throw new IllegalArgumentException("other must not be null");
        }
        if (other != this) {
            final long newPtr;
            synchronized (other.mLock) {
                newPtr = other.mNativeObject;
                other.setNativeObjectLocked(0);
            }

            synchronized (mLock) {
                if (mNativeObject != 0) {
                    nativeRelease(mNativeObject);
                }
                setNativeObjectLocked(newPtr);
            }
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public void readFromParcel(Parcel source) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }

        synchronized (mLock) {
            // nativeReadFromParcel() will either return mNativeObject, or
            // create a new native Surface and return it after reducing
            // the reference count on mNativeObject.  Either way, it is
            // not necessary to call nativeRelease() here.
            // NOTE: This must be kept synchronized with the native parceling code
            // in frameworks/native/libs/Surface.cpp
            mName = source.readString();
            mIsSingleBuffered = source.readInt() != 0;
            setNativeObjectLocked(nativeReadFromParcel(mNativeObject, source));
        }
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (dest == null) {
            throw new IllegalArgumentException("dest must not be null");
        }
        synchronized (mLock) {
            // NOTE: This must be kept synchronized with the native parceling code
            // in frameworks/native/libs/Surface.cpp
            dest.writeString(mName);
            dest.writeInt(mIsSingleBuffered ? 1 : 0);
            nativeWriteToParcel(mNativeObject, dest);
        }
        if ((flags & Parcelable.PARCELABLE_WRITE_RETURN_VALUE) != 0) {
            release();
        }
    }

    @Override
    public String toString() {
        synchronized (mLock) {
            return "Surface(name=" + mName + ")/@0x" +
                    Integer.toHexString(System.identityHashCode(this));
        }
    }

    private void setNativeObjectLocked(long ptr) {
        if (mNativeObject != ptr) {
            if (mNativeObject == 0 && ptr != 0) {
                mCloseGuard.open("Surface.release");
            } else if (mNativeObject != 0 && ptr == 0) {
                mCloseGuard.close();
            }
            mNativeObject = ptr;
            mGenerationId += 1;
            if (mHwuiContext != null) {
                mHwuiContext.updateSurface();
            }
        }
    }

    private void checkNotReleasedLocked() {
        if (mNativeObject == 0) {
            throw new IllegalStateException("Surface has already been released.");
        }
    }

    /**
     * Allocate buffers ahead of time to avoid allocation delays during rendering
     * @hide
     */
    public void allocateBuffers() {
        synchronized (mLock) {
            checkNotReleasedLocked();
            nativeAllocateBuffers(mNativeObject);
        }
    }

    /**
     * Set the scaling mode to be used for this surfaces buffers
     * @hide
     */
     public void setScalingMode(@ScalingMode int scalingMode) {
        synchronized (mLock) {
            checkNotReleasedLocked();
            int err = nativeSetScalingMode(mNativeObject, scalingMode);
            if (err != 0) {
                throw new IllegalArgumentException("Invalid scaling mode: " + scalingMode);
            }
        }
    }

    void forceScopedDisconnect() {
        synchronized (mLock) {
            checkNotReleasedLocked();
            int err = nativeForceScopedDisconnect(mNativeObject);
            if (err != 0) {
                throw new RuntimeException("Failed to disconnect Surface instance (bad object?)");
            }
        }
    }

    /**
     * Transfer ownership of buffer with a color space and present it on the Surface.
     * The supported color spaces are SRGB and Display P3, other color spaces will be
     * treated as SRGB.
     * @hide
     */
    public void attachAndQueueBufferWithColorSpace(HardwareBuffer buffer, ColorSpace colorSpace) {
        synchronized (mLock) {
            checkNotReleasedLocked();
            if (colorSpace == null) {
                colorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
            }
            int err = nativeAttachAndQueueBufferWithColorSpace(mNativeObject, buffer,
                    colorSpace.getId());
            if (err != 0) {
                throw new RuntimeException(
                        "Failed to attach and queue buffer to Surface (bad object?), "
                        + "native error: " + err);
            }
        }
    }

    /**
     * Returns whether or not this Surface is backed by a single-buffered SurfaceTexture
     * @hide
     */
    public boolean isSingleBuffered() {
        return mIsSingleBuffered;
    }

    /**
     * <p>The shared buffer mode allows both the application and the surface compositor
     * (SurfaceFlinger) to concurrently access this surface's buffer. While the
     * application is still required to issue a present request
     * (see {@link #unlockCanvasAndPost(Canvas)}) to the compositor when an update is required,
     * the compositor may trigger an update at any time. Since the surface's buffer is shared
     * between the application and the compositor, updates triggered by the compositor may
     * cause visible tearing.</p>
     *
     * <p>The shared buffer mode can be used with
     * {@link #setAutoRefreshEnabled(boolean) auto-refresh} to avoid the overhead of
     * issuing present requests.</p>
     *
     * <p>If the application uses the shared buffer mode to reduce latency, it is
     * recommended to use software rendering (see {@link #lockCanvas(Rect)} to ensure
     * the graphics workloads are not affected by other applications and/or the system
     * using the GPU. When using software rendering, the application should update the
     * smallest possible region of the surface required.</p>
     *
     * <p class="note">The shared buffer mode might not be supported by the underlying
     * hardware. Enabling shared buffer mode on hardware that does not support it will
     * not yield an error but the application will not benefit from lower latency (and
     * tearing will not be visible).</p>
     *
     * <p class="note">Depending on how many and what kind of surfaces are visible, the
     * surface compositor may need to copy the shared buffer before it is displayed. When
     * this happens, the latency benefits of shared buffer mode will be reduced.</p>
     *
     * @param enabled True to enable the shared buffer mode on this surface, false otherwise
     *
     * @see #isSharedBufferModeEnabled()
     * @see #setAutoRefreshEnabled(boolean)
     *
     * @hide
     */
    public void setSharedBufferModeEnabled(boolean enabled) {
        if (mIsSharedBufferModeEnabled != enabled) {
            int error = nativeSetSharedBufferModeEnabled(mNativeObject, enabled);
            if (error != 0) {
                throw new RuntimeException(
                        "Failed to set shared buffer mode on Surface (bad object?)");
            } else {
                mIsSharedBufferModeEnabled = enabled;
            }
        }
    }

    /**
     * @return True if shared buffer mode is enabled on this surface, false otherwise
     *
     * @see #setSharedBufferModeEnabled(boolean)
     *
     * @hide
     */
    public boolean isSharedBufferModeEnabled() {
        return mIsSharedBufferModeEnabled;
    }

    /**
     * <p>When auto-refresh is enabled, the surface compositor (SurfaceFlinger)
     * automatically updates the display on a regular refresh cycle. The application
     * can continue to issue present requests but it is not required. Enabling
     * auto-refresh may result in visible tearing.</p>
     *
     * <p>Auto-refresh has no effect if the {@link #setSharedBufferModeEnabled(boolean)
     * shared buffer mode} is not enabled.</p>
     *
     * <p>Because auto-refresh will trigger continuous updates of the display, it is
     * recommended to turn it on only when necessary. For example, in a drawing/painting
     * application auto-refresh should be enabled on finger/pen down and disabled on
     * finger/pen up.</p>
     *
     * @param enabled True to enable auto-refresh on this surface, false otherwise
     *
     * @see #isAutoRefreshEnabled()
     * @see #setSharedBufferModeEnabled(boolean)
     *
     * @hide
     */
    public void setAutoRefreshEnabled(boolean enabled) {
        if (mIsAutoRefreshEnabled != enabled) {
            int error = nativeSetAutoRefreshEnabled(mNativeObject, enabled);
            if (error != 0) {
                throw new RuntimeException("Failed to set auto refresh on Surface (bad object?)");
            } else {
                mIsAutoRefreshEnabled = enabled;
            }
        }
    }

    /**
     * @return True if auto-refresh is enabled on this surface, false otherwise
     *
     * @hide
     */
    public boolean isAutoRefreshEnabled() {
        return mIsAutoRefreshEnabled;
    }

    /**
     * Sets the intended frame rate for this surface.
     *
     * <p>On devices that are capable of running the display at different refresh rates,
     * the system may choose a display refresh rate to better match this surface's frame
     * rate. Usage of this API won't introduce frame rate throttling, or affect other
     * aspects of the application's frame production pipeline. However, because the system
     * may change the display refresh rate, calls to this function may result in changes
     * to Choreographer callback timings, and changes to the time interval at which the
     * system releases buffers back to the application.</p>
     *
     * <p>Note that this only has an effect for surfaces presented on the display. If this
     * surface is consumed by something other than the system compositor, e.g. a media
     * codec, this call has no effect.</p>
     *
     * @param frameRate The intended frame rate of this surface, in frames per second. 0
     * is a special value that indicates the app will accept the system's choice for the
     * display frame rate, which is the default behavior if this function isn't
     * called. The <code>frameRate</code> parameter does <em>not</em> need to be a valid refresh
     * rate for this device's display - e.g., it's fine to pass 30fps to a device that can only run
     * the display at 60fps.
     *
     * @param compatibility The frame rate compatibility of this surface. The
     * compatibility value may influence the system's choice of display frame rate.
     * This parameter is ignored when <code>frameRate</code> is 0.
     *
     * @param changeFrameRateStrategy Whether display refresh rate transitions caused by this
     * surface should be seamless. A seamless transition is one that doesn't have any visual
     * interruptions, such as a black screen for a second or two. This parameter is ignored when
     * <code>frameRate</code> is 0.
     *
     * @throws IllegalArgumentException If <code>frameRate</code>, <code>compatibility</code> or
     * <code>changeFrameRateStrategy</code> are invalid.
     *
     * @see #clearFrameRate()
     */
    public void setFrameRate(@FloatRange(from = 0.0) float frameRate,
            @FrameRateCompatibility int compatibility,
            @ChangeFrameRateStrategy int changeFrameRateStrategy) {
        synchronized (mLock) {
            checkNotReleasedLocked();
            int error = nativeSetFrameRate(mNativeObject, frameRate, compatibility,
                    changeFrameRateStrategy);
            if (error == -EINVAL) {
                throw new IllegalArgumentException("Invalid argument to Surface.setFrameRate()");
            } else if (error != 0) {
                throw new RuntimeException("Failed to set frame rate on Surface. Native error: "
                        + error);
            }
        }
    }

    /**
     * Clears the frame rate which was set for this surface.
     *
     * <p>This is equivalent to calling {@link #setFrameRate(float, int, int)} using {@code 0} for
     * {@code frameRate}.
     * <p>Note that this only has an effect for surfaces presented on the display. If this
     * surface is consumed by something other than the system compositor, e.g. a media
     * codec, this call has no effect.</p>
     *
     * @see #setFrameRate(float, int, int)
     */
    public void clearFrameRate() {
        synchronized (mLock) {
            checkNotReleasedLocked();
            // The values FRAME_RATE_COMPATIBILITY_DEFAULT and CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
            // are ignored because the value of frameRate is 0
            int error = nativeSetFrameRate(mNativeObject, 0,
                    FRAME_RATE_COMPATIBILITY_DEFAULT, CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
            if (error != 0) {
                throw new RuntimeException("Failed to clear the frame rate on Surface. Native error"
                        + ": " + error);
            }
        }
    }

    /**
     * Sets the intended frame rate for this surface. Any switching of refresh rates is
     * most probably going to be seamless.
     *
     * @see #setFrameRate(float, int, int)
     */
    public void setFrameRate(
            @FloatRange(from = 0.0) float frameRate, @FrameRateCompatibility int compatibility) {
        setFrameRate(frameRate, compatibility, CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
    }

    /**
     * Exception thrown when a Canvas couldn't be locked with {@link Surface#lockCanvas}, or
     * when a SurfaceTexture could not successfully be allocated.
     */
    @SuppressWarnings("serial")
    public static class OutOfResourcesException extends RuntimeException {
        public OutOfResourcesException() {
        }
        public OutOfResourcesException(String name) {
            super(name);
        }
    }

    /**
     * Returns a human readable representation of a rotation.
     *
     * @param rotation The rotation.
     * @return The rotation symbolic name.
     *
     * @hide
     */
    public static String rotationToString(int rotation) {
        switch (rotation) {
            case Surface.ROTATION_0: {
                return "ROTATION_0";
            }
            case Surface.ROTATION_90: {
                return "ROTATION_90";
            }
            case Surface.ROTATION_180: {
                return "ROTATION_180";
            }
            case Surface.ROTATION_270: {
                return "ROTATION_270";
            }
            default: {
                return Integer.toString(rotation);
            }
        }
    }

    /**
     * A Canvas class that can handle the compatibility mode.
     * This does two things differently.
     * <ul>
     * <li>Returns the width and height of the target metrics, rather than
     * native. For example, the canvas returns 320x480 even if an app is running
     * in WVGA high density.
     * <li>Scales the matrix in setMatrix by the application scale, except if
     * the matrix looks like obtained from getMatrix. This is a hack to handle
     * the case that an application uses getMatrix to keep the original matrix,
     * set matrix of its own, then set the original matrix back. There is no
     * perfect solution that works for all cases, and there are a lot of cases
     * that this model does not work, but we hope this works for many apps.
     * </ul>
     */
    private final class CompatibleCanvas extends Canvas {
        // A temp matrix to remember what an application obtained via {@link getMatrix}
        private Matrix mOrigMatrix = null;

        @Override
        public void setMatrix(Matrix matrix) {
            if (mCompatibleMatrix == null || mOrigMatrix == null || mOrigMatrix.equals(matrix)) {
                // don't scale the matrix if it's not compatibility mode, or
                // the matrix was obtained from getMatrix.
                super.setMatrix(matrix);
            } else {
                Matrix m = new Matrix(mCompatibleMatrix);
                m.preConcat(matrix);
                super.setMatrix(m);
            }
        }

        @SuppressWarnings("deprecation")
        @Override
        public void getMatrix(Matrix m) {
            super.getMatrix(m);
            if (mOrigMatrix == null) {
                mOrigMatrix = new Matrix();
            }
            mOrigMatrix.set(m);
        }
    }

    private final class HwuiContext {
        private final RenderNode mRenderNode;
        private HardwareRenderer mHardwareRenderer;
        private RecordingCanvas mCanvas;
        private final boolean mIsWideColorGamut;

        HwuiContext(boolean isWideColorGamut) {
            mRenderNode = RenderNode.create("HwuiCanvas", null);
            mRenderNode.setClipToBounds(false);
            mRenderNode.setForceDarkAllowed(false);
            mIsWideColorGamut = isWideColorGamut;

            mHardwareRenderer = new HardwareRenderer();
            mHardwareRenderer.setContentRoot(mRenderNode);
            mHardwareRenderer.setSurface(Surface.this, true);
            mHardwareRenderer.setColorMode(
                    isWideColorGamut
                            ? ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
                            : ActivityInfo.COLOR_MODE_DEFAULT);
            mHardwareRenderer.setLightSourceAlpha(0.0f, 0.0f);
            mHardwareRenderer.setLightSourceGeometry(0.0f, 0.0f, 0.0f, 0.0f);
        }

        Canvas lockCanvas(int width, int height) {
            if (mCanvas != null) {
                throw new IllegalStateException("Surface was already locked!");
            }
            mCanvas = mRenderNode.beginRecording(width, height);
            return mCanvas;
        }

        void unlockAndPost(Canvas canvas) {
            if (canvas != mCanvas) {
                throw new IllegalArgumentException("canvas object must be the same instance that "
                        + "was previously returned by lockCanvas");
            }
            mRenderNode.endRecording();
            mCanvas = null;
            mHardwareRenderer.createRenderRequest()
                    .setVsyncTime(System.nanoTime())
                    .syncAndDraw();
        }

        void updateSurface() {
            mHardwareRenderer.setSurface(Surface.this, true);
        }

        void destroy() {
            mHardwareRenderer.destroy();
        }

        boolean isWideColorGamut() {
            return mIsWideColorGamut;
        }
    }
}
