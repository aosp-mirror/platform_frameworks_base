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
import android.content.pm.PackageManager;
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
import android.widget.ImageView;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.R;

/**
 * Implementation for the bottom area of the Keyguard, including camera/phone affordance and status
 * text.
 */
public class KeyguardBottomAreaView extends FrameLayout
        implements SwipeAffordanceView.AffordanceListener {

    final static String TAG = "PhoneStatusBar/KeyguardBottomAreaView";

    private static final Intent PHONE_INTENT = new Intent(Intent.ACTION_DIAL);

    private SwipeAffordanceView mCameraButton;
    private SwipeAffordanceView mPhoneButton;
    private ImageView mLockIcon;

    private PowerManager mPowerManager;
    private ActivityStarter mActivityStarter;

    private LockPatternUtils mLockPatternUtils;
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;

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
        mCameraButton = (SwipeAffordanceView) findViewById(R.id.camera_button);
        mPhoneButton = (SwipeAffordanceView) findViewById(R.id.phone_button);
        mLockIcon = (ImageView) findViewById(R.id.lock_icon);
        mCameraButton.setAffordanceListener(this);
        mPhoneButton.setAffordanceListener(this);
        mLockPatternUtils = new LockPatternUtils(getContext());
        mKeyguardUpdateMonitor = KeyguardUpdateMonitor.getInstance(getContext());
        KeyguardUpdateMonitor.getInstance(getContext()).registerCallback(mCallback);
        watchForDevicePolicyChanges();
        watchForAccessibilityChanges();
        updateCameraVisibility();
        updatePhoneVisibility();
        updateTrust();
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
    }

    public void setActivityStarter(ActivityStarter activityStarter) {
        mActivityStarter = activityStarter;
    }

    private void updateCameraVisibility() {
        boolean visible = !isCameraDisabledByDpm();
        mCameraButton.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void updatePhoneVisibility() {
        boolean visible = isPhoneVisible();
        mPhoneButton.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private boolean isPhoneVisible() {
        PackageManager pm = mContext.getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
                && pm.resolveActivity(PHONE_INTENT, 0) != null;
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
        mCameraButton.enableAccessibility(touchExplorationEnabled);
        mPhoneButton.enableAccessibility(touchExplorationEnabled);
    }

    private void launchCamera() {
        mContext.startActivityAsUser(
                new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE),
                UserHandle.CURRENT);
    }

    private void launchPhone() {
        mActivityStarter.startActivity(PHONE_INTENT);
    }

    @Override
    public void onUserActivity(long when) {
        mPowerManager.userActivity(when, false);
    }

    @Override
    public void onActionPerformed(SwipeAffordanceView view) {
        if (view == mCameraButton) {
            launchCamera();
        } else if (view == mPhoneButton) {
            launchPhone();
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView == this && visibility == VISIBLE) {
            updateTrust();
        }
    }

    private void updateTrust() {
        if (getVisibility() != VISIBLE) {
            return;
        }
        int user = mLockPatternUtils.getCurrentUser();
        boolean trust = !mLockPatternUtils.isSecure() ||
                mKeyguardUpdateMonitor.getUserHasTrust(user);

        int iconRes = trust ? R.drawable.ic_lock_open_24dp : R.drawable.ic_lock_24dp;
        mLockIcon.setImageResource(iconRes);
    }

    final KeyguardUpdateMonitorCallback mCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onScreenTurnedOn() {
            updateTrust();
        }

        @Override
        public void onUserSwitchComplete(int userId) {
            updateTrust();
        }

        @Override
        public void onTrustChanged(int userId) {
            updateTrust();
        }
    };
}
