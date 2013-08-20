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
package com.android.keyguard;

import android.animation.ObjectAnimator;
import android.app.SearchManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.view.View;
import android.widget.LinearLayout;

import com.android.internal.telephony.IccCardConstants.State;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.multiwaveview.GlowPadView;
import com.android.internal.widget.multiwaveview.GlowPadView.OnTriggerListener;
import com.android.keyguard.KeyguardHostView.OnDismissAction;

public class KeyguardSelectorView extends LinearLayout implements KeyguardSecurityView {
    private static final boolean DEBUG = KeyguardHostView.DEBUG;
    private static final String TAG = "SecuritySelectorView";
    private static final String ASSIST_ICON_METADATA_NAME =
        "com.android.systemui.action_assist_icon";
    // Flag to enable/disable hotword detection on lock screen.
    private static final boolean FLAG_HOTWORD = true;

    private KeyguardSecurityCallback mCallback;
    private GlowPadView mGlowPadView;
    private ObjectAnimator mAnim;
    private View mFadeView;
    private boolean mIsBouncing;
    private boolean mCameraDisabled;
    private boolean mSearchDisabled;
    private LockPatternUtils mLockPatternUtils;
    private SecurityMessageDisplay mSecurityMessageDisplay;
    private Drawable mBouncerFrame;
    private HotwordServiceClient mHotwordClient;

    OnTriggerListener mOnTriggerListener = new OnTriggerListener() {

        public void onTrigger(View v, int target) {
            final int resId = mGlowPadView.getResourceIdForTarget(target);
            if (FLAG_HOTWORD) {
                maybeStopHotwordDetector();
            }
            switch (resId) {
                case R.drawable.ic_action_assist_generic:
                    Intent assistIntent =
                            ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
                            .getAssistIntent(mContext, true, UserHandle.USER_CURRENT);
                    if (assistIntent != null) {
                        mActivityLauncher.launchActivity(assistIntent, false, true, null, null);
                    } else {
                        Log.w(TAG, "Failed to get intent for assist activity");
                    }
                    mCallback.userActivity(0);
                    break;

                case R.drawable.ic_lockscreen_camera:
                    mActivityLauncher.launchCamera(null, null);
                    mCallback.userActivity(0);
                    break;

                case R.drawable.ic_lockscreen_unlock_phantom:
                case R.drawable.ic_lockscreen_unlock:
                    mCallback.userActivity(0);
                    mCallback.dismiss(false);
                break;
            }
        }

        public void onReleased(View v, int handle) {
            if (!mIsBouncing) {
                doTransition(mFadeView, 1.0f);
            }
        }

        public void onGrabbed(View v, int handle) {
            mCallback.userActivity(0);
            doTransition(mFadeView, 0.0f);
        }

        public void onGrabbedStateChange(View v, int handle) {

        }

        public void onFinishFinalAnimation() {

        }

    };

    KeyguardUpdateMonitorCallback mUpdateCallback = new KeyguardUpdateMonitorCallback() {

        @Override
        public void onDevicePolicyManagerStateChanged() {
            updateTargets();
        }

        @Override
        public void onSimStateChanged(State simState) {
            updateTargets();
        }

        @Override
        public void onPhoneStateChanged(int phoneState) {
            if (FLAG_HOTWORD) {
                // We need to stop the hotwording when a phone call comes in
                // TODO(sansid): This is not really needed if onPause triggers
                // when we navigate away from the keyguard
                if (phoneState == TelephonyManager.CALL_STATE_RINGING) {
                    if (DEBUG) Log.d(TAG, "Stopping due to CALL_STATE_RINGING");
                    maybeStopHotwordDetector();
                }
            }
        }

        @Override
        public void onUserSwitching(int userId) {
            maybeStopHotwordDetector();
        }
    };

    private final KeyguardActivityLauncher mActivityLauncher = new KeyguardActivityLauncher() {

        @Override
        KeyguardSecurityCallback getCallback() {
            return mCallback;
        }

        @Override
        LockPatternUtils getLockPatternUtils() {
            return mLockPatternUtils;
        }

        @Override
        Context getContext() {
            return mContext;
        }};

    public KeyguardSelectorView(Context context) {
        this(context, null);
    }

    public KeyguardSelectorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mLockPatternUtils = new LockPatternUtils(getContext());
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mGlowPadView = (GlowPadView) findViewById(R.id.glow_pad_view);
        mGlowPadView.setOnTriggerListener(mOnTriggerListener);
        updateTargets();

        mSecurityMessageDisplay = new KeyguardMessageArea.Helper(this);
        View bouncerFrameView = findViewById(R.id.keyguard_selector_view_frame);
        mBouncerFrame = bouncerFrameView.getBackground();
        if (FLAG_HOTWORD) {
            mHotwordClient = new HotwordServiceClient(getContext(), mHotwordCallback);
        }
    }

    public void setCarrierArea(View carrierArea) {
        mFadeView = carrierArea;
    }

    public boolean isTargetPresent(int resId) {
        return mGlowPadView.getTargetPosition(resId) != -1;
    }

    @Override
    public void showUsabilityHint() {
        mGlowPadView.ping();
    }

    private void updateTargets() {
        int currentUserHandle = mLockPatternUtils.getCurrentUser();
        DevicePolicyManager dpm = mLockPatternUtils.getDevicePolicyManager();
        int disabledFeatures = dpm.getKeyguardDisabledFeatures(null, currentUserHandle);
        boolean secureCameraDisabled = mLockPatternUtils.isSecure()
                && (disabledFeatures & DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA) != 0;
        boolean cameraDisabledByAdmin = dpm.getCameraDisabled(null, currentUserHandle)
                || secureCameraDisabled;
        final KeyguardUpdateMonitor monitor = KeyguardUpdateMonitor.getInstance(getContext());
        boolean disabledBySimState = monitor.isSimLocked();
        boolean cameraTargetPresent =
            isTargetPresent(R.drawable.ic_lockscreen_camera);
        boolean searchTargetPresent =
            isTargetPresent(R.drawable.ic_action_assist_generic);

        if (cameraDisabledByAdmin) {
            Log.v(TAG, "Camera disabled by Device Policy");
        } else if (disabledBySimState) {
            Log.v(TAG, "Camera disabled by Sim State");
        }
        boolean currentUserSetup = 0 != Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE,
                0 /*default */,
                currentUserHandle);
        boolean searchActionAvailable =
                ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
                .getAssistIntent(mContext, false, UserHandle.USER_CURRENT) != null;
        mCameraDisabled = cameraDisabledByAdmin || disabledBySimState || !cameraTargetPresent
                || !currentUserSetup;
        mSearchDisabled = disabledBySimState || !searchActionAvailable || !searchTargetPresent
                || !currentUserSetup;
        updateResources();
    }

    public void updateResources() {
        // Update the search icon with drawable from the search .apk
        if (!mSearchDisabled) {
            Intent intent = ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
                    .getAssistIntent(mContext, false, UserHandle.USER_CURRENT);
            if (intent != null) {
                // XXX Hack. We need to substitute the icon here but haven't formalized
                // the public API. The "_google" metadata will be going away, so
                // DON'T USE IT!
                ComponentName component = intent.getComponent();
                boolean replaced = mGlowPadView.replaceTargetDrawablesIfPresent(component,
                        ASSIST_ICON_METADATA_NAME + "_google", R.drawable.ic_action_assist_generic);

                if (!replaced && !mGlowPadView.replaceTargetDrawablesIfPresent(component,
                            ASSIST_ICON_METADATA_NAME, R.drawable.ic_action_assist_generic)) {
                        Slog.w(TAG, "Couldn't grab icon from package " + component);
                }
            }
        }

        mGlowPadView.setEnableTarget(R.drawable.ic_lockscreen_camera, !mCameraDisabled);
        mGlowPadView.setEnableTarget(R.drawable.ic_action_assist_generic, !mSearchDisabled);
    }

    void doTransition(View view, float to) {
        if (mAnim != null) {
            mAnim.cancel();
        }
        mAnim = ObjectAnimator.ofFloat(view, "alpha", to);
        mAnim.start();
    }

    public void setKeyguardCallback(KeyguardSecurityCallback callback) {
        mCallback = callback;
    }

    public void setLockPatternUtils(LockPatternUtils utils) {
        mLockPatternUtils = utils;
    }

    @Override
    public void reset() {
        mGlowPadView.reset(false);
    }

    @Override
    public boolean needsInput() {
        return false;
    }

    @Override
    public void onPause() {
        KeyguardUpdateMonitor.getInstance(getContext()).removeCallback(mUpdateCallback);
    }

    @Override
    public void onResume(int reason) {
        KeyguardUpdateMonitor.getInstance(getContext()).registerCallback(mUpdateCallback);
        // TODO: Figure out if there's a better way to do it.
        // Right now we don't get onPause at all, and onResume gets called
        // multiple times (even when the screen is turned off with VIEW_REVEALED)
        if (reason == SCREEN_ON) {
            if (!KeyguardUpdateMonitor.getInstance(getContext()).isSwitchingUser()) {
                maybeStartHotwordDetector();
            }
        } else {
            maybeStopHotwordDetector();
        }
    }

    @Override
    public KeyguardSecurityCallback getCallback() {
        return mCallback;
    }

    @Override
    public void showBouncer(int duration) {
        mIsBouncing = true;
        KeyguardSecurityViewHelper.
                showBouncer(mSecurityMessageDisplay, mFadeView, mBouncerFrame, duration);
    }

    @Override
    public void hideBouncer(int duration) {
        mIsBouncing = false;
        KeyguardSecurityViewHelper.
                hideBouncer(mSecurityMessageDisplay, mFadeView, mBouncerFrame, duration);
    }

    /**
     * Start the hotword detector if:
     * <li> HOTWORDING_ENABLED is true and
     * <li> HotwordUnlock is initialized and
     * <li> TelephonyManager is in CALL_STATE_IDLE
     *
     * If this method is called when the screen is off,
     * it attempts to stop hotwording if it's running.
     */
    private void maybeStartHotwordDetector() {
        if (FLAG_HOTWORD) {
            if (DEBUG) Log.d(TAG, "maybeStartHotwordDetector()");
            // Don't start it if the screen is off or not showing
            PowerManager powerManager = (PowerManager) getContext().getSystemService(
                    Context.POWER_SERVICE);
            if (!powerManager.isScreenOn()) {
                if (DEBUG) Log.d(TAG, "screen was off, not starting");
                return;
            }

            KeyguardUpdateMonitor monitor = KeyguardUpdateMonitor.getInstance(getContext());
            if (monitor.getPhoneState() != TelephonyManager.CALL_STATE_IDLE) {
                if (DEBUG) Log.d(TAG, "Call underway, not starting");
                return;
            }
            if (!mHotwordClient.start()) {
                Log.w(TAG, "Failed to start the hotword detector");
            }
        }
    }

    /**
     * Stop hotword detector if HOTWORDING_ENABLED is true.
     */
    private void maybeStopHotwordDetector() {
        if (FLAG_HOTWORD) {
            if (DEBUG) Log.d(TAG, "maybeStopHotwordDetector()");
            mHotwordClient.stop();
        }
    }

    private final HotwordServiceClient.Callback mHotwordCallback =
            new HotwordServiceClient.Callback() {
        private static final String TAG = "HotwordServiceClient.Callback";

        @Override
        public void onServiceConnected() {
            if (DEBUG) Log.d(TAG, "onServiceConnected()");
        }

        @Override
        public void onServiceDisconnected() {
            if (DEBUG) Log.d(TAG, "onServiceDisconnected()");
        }

        @Override
        public void onHotwordDetectionStarted() {
            if (DEBUG) Log.d(TAG, "onHotwordDetectionStarted()");
            // TODO: Change the usage of SecurityMessageDisplay to a better visual indication.
            mSecurityMessageDisplay.setMessage("\"Ok Google...\"", true);
        }

        @Override
        public void onHotwordDetectionStopped() {
            if (DEBUG) Log.d(TAG, "onHotwordDetectionStopped()");
            // TODO: Change the usage of SecurityMessageDisplay to a better visual indication.
        }

        @Override
        public void onHotwordDetected(String action) {
            if (DEBUG) Log.d(TAG, "onHotwordDetected(" + action + ")");
            if (action != null) {
                Intent intent = new Intent(action);
                mActivityLauncher.launchActivity(intent, true, true, null, null);
            }
            mCallback.userActivity(0);
        }
    };
}
