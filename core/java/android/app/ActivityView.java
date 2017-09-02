/**
 * Copyright (c) 2017 The Android Open Source Project
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

package android.app;

import android.annotation.NonNull;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.input.InputManager;
import android.os.RemoteException;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.WindowManager;

import dalvik.system.CloseGuard;

/**
 * Activity container that allows launching activities into itself and does input forwarding.
 * <p>Creation of this view is only allowed to callers who have
 * {@link android.Manifest.permission#INJECT_EVENTS} permission.
 * <p>Activity launching into this container is restricted by the same rules that apply to launching
 * on VirtualDisplays.
 * @hide
 */
public class ActivityView extends ViewGroup {

    private static final String DISPLAY_NAME = "ActivityViewVirtualDisplay";
    private static final String TAG = "ActivityView";

    private VirtualDisplay mVirtualDisplay;
    private final SurfaceView mSurfaceView;
    private Surface mSurface;

    private final SurfaceCallback mSurfaceCallback;
    private StateCallback mActivityViewCallback;

    private IInputForwarder mInputForwarder;

    private final CloseGuard mGuard = CloseGuard.get();
    private boolean mOpened; // Protected by mGuard.

    public ActivityView(Context context) {
        this(context, null /* attrs */);
    }

    public ActivityView(Context context, AttributeSet attrs) {
        this(context, attrs, 0 /* defStyle */);
    }

    public ActivityView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        mSurfaceView = new SurfaceView(context);
        mSurfaceCallback = new SurfaceCallback();
        mSurfaceView.getHolder().addCallback(mSurfaceCallback);
        addView(mSurfaceView);

        mOpened = true;
        mGuard.open("release");
    }

    /** Callback that notifies when the container is ready or destroyed. */
    public abstract static class StateCallback {
        /**
         * Called when the container is ready for launching activities. Calling
         * {@link #startActivity(Intent)} prior to this callback will result in an
         * {@link IllegalStateException}.
         *
         * @see #startActivity(Intent)
         */
        public abstract void onActivityViewReady(ActivityView view);
        /**
         * Called when the container can no longer launch activities. Calling
         * {@link #startActivity(Intent)} after this callback will result in an
         * {@link IllegalStateException}.
         *
         * @see #startActivity(Intent)
         */
        public abstract void onActivityViewDestroyed(ActivityView view);
    }

    /**
     * Set the callback to be notified about state changes.
     * <p>This class must finish initializing before {@link #startActivity(Intent)} can be called.
     * <p>Note: If the instance was ready prior to this call being made, then
     * {@link StateCallback#onActivityViewReady(ActivityView)} will be called from within
     * this method call.
     *
     * @param callback The callback to report events to.
     *
     * @see StateCallback
     * @see #startActivity(Intent)
     */
    public void setCallback(StateCallback callback) {
        mActivityViewCallback = callback;

        if (mVirtualDisplay != null && mActivityViewCallback != null) {
            mActivityViewCallback.onActivityViewReady(this);
        }
    }

    /**
     * Launch a new activity into this container.
     * <p>Activity resolved by the provided {@link Intent} must have
     * {@link android.R.attr#resizeableActivity} attribute set to {@code true} in order to be
     * launched here. Also, if activity is not owned by the owner of this container, it must allow
     * embedding and the caller must have permission to embed.
     * <p>Note: This class must finish initializing and
     * {@link StateCallback#onActivityViewReady(ActivityView)} callback must be triggered before
     * this method can be called.
     *
     * @param intent Intent used to launch an activity.
     *
     * @see StateCallback
     * @see #startActivity(PendingIntent)
     */
    public void startActivity(@NonNull Intent intent) {
        final ActivityOptions options = prepareActivityOptions();
        getContext().startActivity(intent, options.toBundle());
    }

    /**
     * Launch a new activity into this container.
     * <p>Activity resolved by the provided {@link PendingIntent} must have
     * {@link android.R.attr#resizeableActivity} attribute set to {@code true} in order to be
     * launched here. Also, if activity is not owned by the owner of this container, it must allow
     * embedding and the caller must have permission to embed.
     * <p>Note: This class must finish initializing and
     * {@link StateCallback#onActivityViewReady(ActivityView)} callback must be triggered before
     * this method can be called.
     *
     * @param pendingIntent Intent used to launch an activity.
     *
     * @see StateCallback
     * @see #startActivity(Intent)
     */
    public void startActivity(@NonNull PendingIntent pendingIntent) {
        final ActivityOptions options = prepareActivityOptions();
        try {
            pendingIntent.send(null /* context */, 0 /* code */, null /* intent */,
                    null /* onFinished */, null /* handler */, null /* requiredPermission */,
                    options.toBundle());
        } catch (PendingIntent.CanceledException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Check if container is ready to launch and create {@link ActivityOptions} to target the
     * virtual display.
     */
    private ActivityOptions prepareActivityOptions() {
        if (mVirtualDisplay == null) {
            throw new IllegalStateException(
                    "Trying to start activity before ActivityView is ready.");
        }
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(mVirtualDisplay.getDisplay().getDisplayId());
        return options;
    }

    /**
     * Release this container. Activity launching will no longer be permitted.
     * <p>Note: Calling this method is allowed after
     * {@link StateCallback#onActivityViewReady(ActivityView)} callback was triggered and before
     * {@link StateCallback#onActivityViewDestroyed(ActivityView)}.
     *
     * @see StateCallback
     */
    public void release() {
        if (mVirtualDisplay == null) {
            throw new IllegalStateException(
                    "Trying to release container that is not initialized.");
        }
        performRelease();
    }

    @Override
    public void onLayout(boolean changed, int l, int t, int r, int b) {
        mSurfaceView.layout(0 /* left */, 0 /* top */, r - l /* right */, b - t /* bottom */);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return injectInputEvent(event) || super.onTouchEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            if (injectInputEvent(event)) {
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    private boolean injectInputEvent(InputEvent event) {
        if (mInputForwarder != null) {
            try {
                return mInputForwarder.forwardEvent(event);
            } catch (RemoteException e) {
                e.rethrowAsRuntimeException();
            }
        }
        return false;
    }

    private class SurfaceCallback implements SurfaceHolder.Callback {
        @Override
        public void surfaceCreated(SurfaceHolder surfaceHolder) {
            mSurface = mSurfaceView.getHolder().getSurface();
            if (mVirtualDisplay == null) {
                initVirtualDisplay();
                if (mVirtualDisplay != null && mActivityViewCallback != null) {
                    mActivityViewCallback.onActivityViewReady(ActivityView.this);
                }
            } else {
                mVirtualDisplay.setSurface(surfaceHolder.getSurface());
            }
        }

        @Override
        public void surfaceChanged(SurfaceHolder surfaceHolder, int format, int width, int height) {
            if (mVirtualDisplay != null) {
                mVirtualDisplay.resize(width, height, getBaseDisplayDensity());
            }
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
            mSurface.release();
            mSurface = null;
            if (mVirtualDisplay != null) {
                mVirtualDisplay.setSurface(null);
            }
        }
    }

    private void initVirtualDisplay() {
        if (mVirtualDisplay != null) {
            throw new IllegalStateException("Trying to initialize for the second time.");
        }

        final int width = mSurfaceView.getWidth();
        final int height = mSurfaceView.getHeight();
        final DisplayManager displayManager = mContext.getSystemService(DisplayManager.class);
        mVirtualDisplay = displayManager.createVirtualDisplay(
                DISPLAY_NAME + "@" + System.identityHashCode(this),
                width, height, getBaseDisplayDensity(), mSurface, 0 /* flags */);
        if (mVirtualDisplay == null) {
            Log.e(TAG, "Failed to initialize ActivityView");
            return;
        }

        mInputForwarder = InputManager.getInstance().createInputForwarder(
                mVirtualDisplay.getDisplay().getDisplayId());
    }

    private void performRelease() {
        if (!mOpened) {
            return;
        }

        mSurfaceView.getHolder().removeCallback(mSurfaceCallback);

        if (mInputForwarder != null) {
            mInputForwarder = null;
        }

        final boolean displayReleased;
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
            displayReleased = true;
        } else {
            displayReleased = false;
        }

        if (mSurface != null) {
            mSurface.release();
            mSurface = null;
        }

        if (displayReleased && mActivityViewCallback != null) {
            mActivityViewCallback.onActivityViewDestroyed(this);
        }

        mGuard.close();
        mOpened = false;
    }

    /** Get density of the hosting display. */
    private int getBaseDisplayDensity() {
        final WindowManager wm = mContext.getSystemService(WindowManager.class);
        final DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(metrics);
        return metrics.densityDpi;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mGuard != null) {
                mGuard.warnIfOpen();
                performRelease();
            }
        } finally {
            super.finalize();
        }
    }
}
