/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.app.ActivityManagerNative;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import com.android.systemui.R;

/**
 * Implementation for the bottom area of the Keyguard, including camera/phone affordance and status
 * text.
 */
public class KeyguardBottomAreaView extends FrameLayout {

    final static String TAG = "PhoneStatusBar/KeyguardBottomAreaView";

    private View mCameraButton;
    private float mCameraDragDistance;
    private PowerManager mPowerManager;
    private int mScaledTouchSlop;

    public KeyguardBottomAreaView(Context context) {
        super(context);
    }

    public KeyguardBottomAreaView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public KeyguardBottomAreaView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public KeyguardBottomAreaView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mCameraButton = findViewById(R.id.camera_button);
        watchForDevicePolicyChanges();
        watchForAccessibilityChanges();
        updateCameraVisibility();
        mCameraDragDistance = getResources().getDimension(R.dimen.camera_drag_distance);
        mScaledTouchSlop = ViewConfiguration.get(mContext).getScaledTouchSlop();
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
    }

    private void updateCameraVisibility() {
        boolean visible = !isCameraDisabledByDpm();
        mCameraButton.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private boolean isCameraDisabledByDpm() {
        final DevicePolicyManager dpm =
                (DevicePolicyManager) getContext().getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm != null) {
            try {
                final int userId = ActivityManagerNative.getDefault().getCurrentUser().id;
                final int disabledFlags = dpm.getKeyguardDisabledFeatures(null, userId);
                final  boolean disabledBecauseKeyguardSecure =
                        (disabledFlags & DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA) != 0
                                && KeyguardTouchDelegate.getInstance(getContext()).isSecure();
                return dpm.getCameraDisabled(null) || disabledBecauseKeyguardSecure;
            } catch (RemoteException e) {
                Log.e(TAG, "Can't get userId", e);
            }
        }
        return false;
    }

    private void watchForDevicePolicyChanges() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
        getContext().registerReceiver(new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                post(new Runnable() {
                    @Override
                    public void run() {
                        updateCameraVisibility();
                    }
                });
            }
        }, filter);
    }

    private void watchForAccessibilityChanges() {
        final AccessibilityManager am =
                (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);

        // Set the initial state
        enableAccessibility(am.isTouchExplorationEnabled());

        // Watch for changes
        am.addTouchExplorationStateChangeListener(
                new AccessibilityManager.TouchExplorationStateChangeListener() {
            @Override
            public void onTouchExplorationStateChanged(boolean enabled) {
                enableAccessibility(enabled);
            }
        });
    }

    private void enableAccessibility(boolean touchExplorationEnabled) {

        // Add a touch handler or accessibility click listener for camera button.
        if (touchExplorationEnabled) {
            mCameraButton.setOnTouchListener(null);
            mCameraButton.setOnClickListener(mCameraClickListener);
        } else {
            mCameraButton.setOnTouchListener(mCameraTouchListener);
            mCameraButton.setOnClickListener(null);
        }
    }

    private void launchCamera() {
        mContext.startActivityAsUser(
                new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE),
                UserHandle.CURRENT);
    }

    private final OnClickListener mCameraClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            launchCamera();
        }
    };

    private final OnTouchListener mCameraTouchListener = new OnTouchListener() {
        private float mStartX;
        private boolean mTouchSlopReached;
        private boolean mSkipCancelAnimation;

        @Override
        public boolean onTouch(final View cameraButtonView, MotionEvent event) {
            float realX = event.getRawX();
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    mStartX = realX;
                    mTouchSlopReached = false;
                    mSkipCancelAnimation = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (realX > mStartX) {
                        realX = mStartX;
                    }
                    if (realX < mStartX - mCameraDragDistance) {
                        cameraButtonView.setPressed(true);
                        mPowerManager.userActivity(event.getEventTime(), false);
                    } else {
                        cameraButtonView.setPressed(false);
                    }
                    if (realX < mStartX - mScaledTouchSlop) {
                        mTouchSlopReached = true;
                    }
                    cameraButtonView.setTranslationX(Math.max(realX - mStartX,
                            -mCameraDragDistance));
                    break;
                case MotionEvent.ACTION_UP:
                    if (realX < mStartX - mCameraDragDistance) {
                        launchCamera();
                        cameraButtonView.animate().x(-cameraButtonView.getWidth())
                                .setInterpolator(new AccelerateInterpolator(2f)).withEndAction(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        cameraButtonView.setTranslationX(0);
                                    }
                                });
                        mSkipCancelAnimation = true;
                    }
                    if (realX < mStartX - mScaledTouchSlop) {
                        mTouchSlopReached = true;
                    }
                    if (!mTouchSlopReached) {
                        mSkipCancelAnimation = true;
                        cameraButtonView.animate().translationX(-mCameraDragDistance / 2).
                                setInterpolator(new DecelerateInterpolator()).withEndAction(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        cameraButtonView.animate().translationX(0).
                                                setInterpolator(new AccelerateInterpolator());
                                    }
                                });
                    }
                case MotionEvent.ACTION_CANCEL:
                    cameraButtonView.setPressed(false);
                    if (!mSkipCancelAnimation) {
                        cameraButtonView.animate().translationX(0)
                                .setInterpolator(new AccelerateInterpolator(2f));
                    }
                    break;
            }
            return true;
        }
    };
}
