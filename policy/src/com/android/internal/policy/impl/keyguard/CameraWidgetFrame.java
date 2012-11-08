/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.internal.policy.impl.keyguard;

import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Point;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;

import com.android.internal.R;
import com.android.internal.policy.impl.keyguard.KeyguardActivityLauncher.CameraWidgetInfo;

public class CameraWidgetFrame extends KeyguardWidgetFrame implements View.OnClickListener {
    private static final String TAG = CameraWidgetFrame.class.getSimpleName();
    private static final boolean DEBUG = KeyguardHostView.DEBUG;
    private static final int WIDGET_ANIMATION_DURATION = 250; // ms
    private static final int WIDGET_WAIT_DURATION = 650; // ms
    private static final int RECOVERY_DELAY = 1000; // ms

    interface Callbacks {
        void onLaunchingCamera();
        void onCameraLaunchedSuccessfully();
        void onCameraLaunchedUnsuccessfully();
    }

    private final Handler mHandler = new Handler();
    private final KeyguardActivityLauncher mActivityLauncher;
    private final Callbacks mCallbacks;
    private final WindowManager mWindowManager;
    private final Point mRenderedSize = new Point();
    private final int[] mScreenLocation = new int[2];

    private View mWidgetView;
    private long mLaunchCameraStart;
    private boolean mActive;
    private boolean mTransitioning;
    private boolean mRecovering;
    private boolean mDown;

    private final Runnable mTransitionToCameraRunnable = new Runnable() {
        @Override
        public void run() {
            transitionToCamera();
        }};

    private final Runnable mTransitionToCameraEndAction = new Runnable() {
        @Override
        public void run() {
            if (!mTransitioning)
                return;
            Handler worker =  getWorkerHandler() != null ? getWorkerHandler() : mHandler;
            mLaunchCameraStart = SystemClock.uptimeMillis();
            if (DEBUG) Log.d(TAG, "Launching camera at " + mLaunchCameraStart);
            mActivityLauncher.launchCamera(worker, mSecureCameraActivityStartedRunnable);
        }};

    private final Runnable mRecoverRunnable = new Runnable() {
        @Override
        public void run() {
            recover();
        }};

    private final Runnable mRecoverEndAction = new Runnable() {
        @Override
        public void run() {
            if (!mRecovering)
                return;
            mCallbacks.onCameraLaunchedUnsuccessfully();
            reset();
        }};

    private final Runnable mRenderRunnable = new Runnable() {
        @Override
        public void run() {
            render();
        }};

    private final Runnable mSecureCameraActivityStartedRunnable = new Runnable() {
        @Override
        public void run() {
            onSecureCameraActivityStarted();
        }
    };

    private final KeyguardUpdateMonitorCallback mCallback = new KeyguardUpdateMonitorCallback() {
        private boolean mShowing;
        void onKeyguardVisibilityChanged(boolean showing) {
            if (mShowing == showing)
                return;
            mShowing = showing;
            CameraWidgetFrame.this.onKeyguardVisibilityChanged(mShowing);
        };
    };

    private CameraWidgetFrame(Context context, Callbacks callbacks,
            KeyguardActivityLauncher activityLauncher) {
        super(context);
        mCallbacks = callbacks;
        mActivityLauncher = activityLauncher;
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        KeyguardUpdateMonitor.getInstance(context).registerCallback(mCallback);
        if (DEBUG) Log.d(TAG, "new CameraWidgetFrame instance " + instanceId());
    }

    public static CameraWidgetFrame create(Context context, Callbacks callbacks,
            KeyguardActivityLauncher launcher) {
        if (context == null || callbacks == null || launcher == null)
            return null;

        CameraWidgetInfo widgetInfo = launcher.getCameraWidgetInfo();
        if (widgetInfo == null)
            return null;
        View widgetView = widgetInfo.layoutId > 0 ?
                inflateWidgetView(context, widgetInfo) :
                inflateGenericWidgetView(context);
        if (widgetView == null)
            return null;

        ImageView preview = new ImageView(context);
        preview.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        preview.setScaleType(ScaleType.FIT_CENTER);
        preview.setContentDescription(preview.getContext().getString(
                R.string.keyguard_accessibility_camera));
        CameraWidgetFrame cameraWidgetFrame = new CameraWidgetFrame(context, callbacks, launcher);
        cameraWidgetFrame.addView(preview);
        cameraWidgetFrame.mWidgetView = widgetView;
        preview.setOnClickListener(cameraWidgetFrame);
        return cameraWidgetFrame;
    }

    private static View inflateWidgetView(Context context, CameraWidgetInfo widgetInfo) {
        if (DEBUG) Log.d(TAG, "inflateWidgetView: " + widgetInfo.contextPackage);
        View widgetView = null;
        Exception exception = null;
        try {
            Context cameraContext = context.createPackageContext(
                    widgetInfo.contextPackage, Context.CONTEXT_RESTRICTED);
            LayoutInflater cameraInflater = (LayoutInflater)
                    cameraContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            cameraInflater = cameraInflater.cloneInContext(cameraContext);
            widgetView = cameraInflater.inflate(widgetInfo.layoutId, null, false);
        } catch (NameNotFoundException e) {
            exception = e;
        } catch (RuntimeException e) {
            exception = e;
        }
        if (exception != null) {
            Log.w(TAG, "Error creating camera widget view", exception);
        }
        return widgetView;
    }

    private static View inflateGenericWidgetView(Context context) {
        if (DEBUG) Log.d(TAG, "inflateGenericWidgetView");
        ImageView iv = new ImageView(context);
        iv.setImageResource(com.android.internal.R.drawable.ic_lockscreen_camera);
        iv.setScaleType(ScaleType.CENTER);
        iv.setBackgroundColor(Color.argb(127, 0, 0, 0));
        return iv;
    }

    public void render() {
        final Throwable[] thrown = new Throwable[1];
        final Bitmap[] offscreen = new Bitmap[1];
        try {
            final int width = getRootView().getWidth();
            final int height = getRootView().getHeight();
            if (mRenderedSize.x == width && mRenderedSize.y == height) {
                if (DEBUG) Log.d(TAG, String.format("Already rendered at size=%sx%s",
                        width, height));
                return;
            }
            if (width == 0 || height == 0) {
                return;
            }
            final long start = SystemClock.uptimeMillis();
            offscreen[0] = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            final Canvas c = new Canvas(offscreen[0]);
            mWidgetView.measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            mWidgetView.layout(0, 0, width, height);
            mWidgetView.draw(c);

            final long end = SystemClock.uptimeMillis();
            if (DEBUG) Log.d(TAG, String.format(
                    "Rendered camera widget in %sms size=%sx%s instance=%s at %s",
                    end - start,
                    width, height,
                    instanceId(),
                    end));
            mRenderedSize.set(width, height);
        } catch (Throwable t) {
            thrown[0] = t;
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (thrown[0] == null) {
                    try {
                        ((ImageView) getChildAt(0)).setImageBitmap(offscreen[0]);
                    } catch (Throwable t) {
                        thrown[0] = t;
                    }
                }
                if (thrown[0] == null)
                    return;

                Log.w(TAG, "Error rendering camera widget", thrown[0]);
                try {
                    removeAllViews();
                    final View genericView = inflateGenericWidgetView(mContext);
                    addView(genericView);
                } catch (Throwable t) {
                    Log.w(TAG, "Error inflating generic camera widget", t);
                }
            }});
    }

    private void transitionToCamera() {
        if (mTransitioning || mDown) return;

        mTransitioning = true;

        final View child = getChildAt(0);
        final View root = getRootView();

        final int startWidth = child.getWidth();
        final int startHeight = child.getHeight();

        final int finishWidth = root.getWidth();
        final int finishHeight = root.getHeight();

        final float scaleX = (float) finishWidth / startWidth;
        final float scaleY = (float) finishHeight / startHeight;
        final float scale = Math.round( Math.max(scaleX, scaleY) * 100) / 100f;

        final int[] loc = new int[2];
        root.getLocationInWindow(loc);
        final int finishCenter = loc[1] + finishHeight / 2;

        child.getLocationInWindow(loc);
        final int startCenter = loc[1] + startHeight / 2;

        if (DEBUG) Log.d(TAG, String.format("Transitioning to camera. " +
                "(start=%sx%s, finish=%sx%s, scale=%s,%s, startCenter=%s, finishCenter=%s)",
                startWidth, startHeight,
                finishWidth, finishHeight,
                scaleX, scaleY,
                startCenter, finishCenter));

        enableWindowExitAnimation(false);
        animate()
            .scaleX(scale)
            .scaleY(scale)
            .translationY(finishCenter - startCenter)
            .setDuration(WIDGET_ANIMATION_DURATION)
            .withEndAction(mTransitionToCameraEndAction)
            .start();

        mCallbacks.onLaunchingCamera();
    }

    private void recover() {
        if (DEBUG) Log.d(TAG, "recovering at " + SystemClock.uptimeMillis());
        mRecovering = true;
        animate()
            .scaleX(1)
            .scaleY(1)
            .translationY(0)
            .setDuration(WIDGET_ANIMATION_DURATION)
            .withEndAction(mRecoverEndAction)
            .start();
    }

    @Override
    public void onClick(View v) {
        if (DEBUG) Log.d(TAG, "clicked");
        if (mTransitioning) return;
        if (mActive) {
            cancelTransitionToCamera();
            transitionToCamera();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (DEBUG) Log.d(TAG, "onDetachedFromWindow: instance " + instanceId()
                + " at " + SystemClock.uptimeMillis());
        super.onDetachedFromWindow();
        KeyguardUpdateMonitor.getInstance(mContext).removeCallback(mCallback);
        cancelTransitionToCamera();
        mHandler.removeCallbacks(mRecoverRunnable);
    }

    @Override
    public void onActive(boolean isActive) {
        mActive = isActive;
        if (mActive) {
            rescheduleTransitionToCamera();
        } else {
            reset();
        }
    }

    @Override
    public boolean onUserInteraction(MotionEvent event) {
        if (mTransitioning) {
            if (DEBUG) Log.d(TAG, "onUserInteraction eaten: mTransitioning");
            return true;
        }

        getLocationOnScreen(mScreenLocation);
        int rawBottom = mScreenLocation[1] + getHeight();
        if (event.getRawY() > rawBottom) {
            if (DEBUG) Log.d(TAG, "onUserInteraction eaten: below widget");
            return true;
        }

        int action = event.getAction();
        mDown = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE;
        if (mActive) {
            rescheduleTransitionToCamera();
        }
        if (DEBUG) Log.d(TAG, "onUserInteraction observed, not eaten");
        return false;
    }

    @Override
    protected void onFocusLost() {
        if (DEBUG) Log.d(TAG, "onFocusLost at " + SystemClock.uptimeMillis());
        cancelTransitionToCamera();
        super.onFocusLost();
    }

    public void onScreenTurnedOff() {
        if (DEBUG) Log.d(TAG, "onScreenTurnedOff");
        reset();
    }

    private void rescheduleTransitionToCamera() {
        if (DEBUG) Log.d(TAG, "rescheduleTransitionToCamera at " + SystemClock.uptimeMillis());
        mHandler.removeCallbacks(mTransitionToCameraRunnable);
        mHandler.postDelayed(mTransitionToCameraRunnable, WIDGET_WAIT_DURATION);
    }

    private void cancelTransitionToCamera() {
        if (DEBUG) Log.d(TAG, "cancelTransitionToCamera at " + SystemClock.uptimeMillis());
        mHandler.removeCallbacks(mTransitionToCameraRunnable);
    }

    private void onCameraLaunched() {
        mCallbacks.onCameraLaunchedSuccessfully();
        reset();
    }

    private void reset() {
        if (DEBUG) Log.d(TAG, "reset at " + SystemClock.uptimeMillis());
        mLaunchCameraStart = 0;
        mTransitioning = false;
        mRecovering = false;
        mDown = false;
        cancelTransitionToCamera();
        mHandler.removeCallbacks(mRecoverRunnable);
        animate().cancel();
        setScaleX(1);
        setScaleY(1);
        setTranslationY(0);
        enableWindowExitAnimation(true);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (DEBUG) Log.d(TAG, String.format("onSizeChanged new=%sx%s old=%sx%s at %s",
                w, h, oldw, oldh, SystemClock.uptimeMillis()));
        final Handler worker =  getWorkerHandler();
        (worker != null ? worker : mHandler).post(mRenderRunnable);
        super.onSizeChanged(w, h, oldw, oldh);
    }

    private void enableWindowExitAnimation(boolean isEnabled) {
        View root = getRootView();
        ViewGroup.LayoutParams lp = root.getLayoutParams();
        if (!(lp instanceof WindowManager.LayoutParams))
            return;
        WindowManager.LayoutParams wlp = (WindowManager.LayoutParams) lp;
        int newWindowAnimations = isEnabled ? com.android.internal.R.style.Animation_LockScreen : 0;
        if (newWindowAnimations != wlp.windowAnimations) {
            if (DEBUG) Log.d(TAG, "setting windowAnimations to: " + newWindowAnimations
                    + " at " + SystemClock.uptimeMillis());
            wlp.windowAnimations = newWindowAnimations;
            mWindowManager.updateViewLayout(root, wlp);
        }
    }

    private void onKeyguardVisibilityChanged(boolean showing) {
        if (DEBUG) Log.d(TAG, "onKeyguardVisibilityChanged " + showing
                + " at " + SystemClock.uptimeMillis());
        if (mTransitioning && !showing) {
          mTransitioning = false;
          mRecovering = false;
          mHandler.removeCallbacks(mRecoverRunnable);
          if (mLaunchCameraStart > 0) {
              long launchTime = SystemClock.uptimeMillis() - mLaunchCameraStart;
              if (DEBUG) Log.d(TAG, String.format("Camera took %sms to launch", launchTime));
              mLaunchCameraStart = 0;
              onCameraLaunched();
          }
        }
    }

    private void onSecureCameraActivityStarted() {
        if (DEBUG) Log.d(TAG, "onSecureCameraActivityStarted at " + SystemClock.uptimeMillis());
        mHandler.postDelayed(mRecoverRunnable, RECOVERY_DELAY);
    }

    private String instanceId() {
        return Integer.toHexString(hashCode());
    }
}
