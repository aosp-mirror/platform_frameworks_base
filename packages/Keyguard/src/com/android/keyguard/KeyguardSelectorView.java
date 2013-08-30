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
import android.app.ActivityManager;
import android.app.PendingIntent;
import android.app.SearchManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.speech.hotword.HotwordRecognitionListener;
import android.speech.hotword.HotwordRecognizer;
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

public class KeyguardSelectorView extends LinearLayout implements KeyguardSecurityView {
    private static final boolean DEBUG = KeyguardHostView.DEBUG;
    private static final String TAG = "SecuritySelectorView";
    private static final String ASSIST_ICON_METADATA_NAME =
        "com.android.systemui.action_assist_icon";

    // Don't enable hotword on limited-memory devices.
    private static final boolean ENABLE_HOTWORD = !ActivityManager.isLowRamDeviceStatic();

    // TODO: Fix this to be non-static.
    private static HotwordRecognizer sHotwordClient;

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

    OnTriggerListener mOnTriggerListener = new OnTriggerListener() {

        public void onTrigger(View v, int target) {
            final int resId = mGlowPadView.getResourceIdForTarget(target);
            maybeStopHotwordDetector();

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
            if (ENABLE_HOTWORD) {
                // We need to stop hotword detection when a call state is not idle anymore.
                if (phoneState != TelephonyManager.CALL_STATE_IDLE) {
                    if (DEBUG) Log.d(TAG, "Stopping due to call state not being idle");
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
        if (ENABLE_HOTWORD && sHotwordClient == null) {
            sHotwordClient = HotwordRecognizer.createHotwordRecognizer(getContext());
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
        maybeStopHotwordDetector();
    }

    @Override
    public void onResume(int reason) {
        KeyguardUpdateMonitor.getInstance(getContext()).registerCallback(mUpdateCallback);
        // TODO: Figure out if there's a better way to do it.
        // onResume gets called multiple times, however we are interested in
        // the reason to figure out when to start/stop hotword detection.
        if (reason == SCREEN_ON) {
            if (!KeyguardUpdateMonitor.getInstance(getContext()).isSwitchingUser()) {
                maybeStartHotwordDetector();
            }
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
     * <li> FLAG_HOTWORD is true and
     * <li> Hotword detection is not already running and
     * <li> TelephonyManager is in CALL_STATE_IDLE
     *
     * If this method is called when the screen is off,
     * it attempts to stop hotword detection if it's running.
     */
    private void maybeStartHotwordDetector() {
        if (ENABLE_HOTWORD && sHotwordClient != null) {
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

            try {
                sHotwordClient.startRecognition(mHotwordCallback);
            } catch(Exception ex) {
                // Don't allow hotword errors to make the keyguard unusable
                Log.e(TAG, "Failed to start hotword recognition", ex);
                sHotwordClient = null;
            }
        }
    }

    /**
     * Stop hotword detector if HOTWORDING_ENABLED is true.
     */
    private void maybeStopHotwordDetector() {
        if (ENABLE_HOTWORD && sHotwordClient != null) {
            if (DEBUG) Log.d(TAG, "maybeStopHotwordDetector()");
            try {
                sHotwordClient.stopRecognition();
            } catch(Exception ex) {
                // Don't allow hotword errors to make the keyguard unusable
                Log.e(TAG, "Failed to start hotword recognition", ex);
            } finally {
                sHotwordClient = null;
            }
        }
    }

    private final HotwordRecognitionListener mHotwordCallback = new HotwordRecognitionListener() {
        private static final String TAG = "HotwordRecognitionListener";

        public void onHotwordRecognitionStarted() {
            if (DEBUG) Log.d(TAG, "onHotwordRecognitionStarted()");
        }

        public void onHotwordRecognitionStopped() {
            if (DEBUG) Log.d(TAG, "onHotwordRecognitionStopped()");
        }

        public void onHotwordEvent(int eventType, Bundle eventBundle) {
            if (DEBUG) Log.d(TAG, "onHotwordEvent: " + eventType);
            if (eventType == HotwordRecognizer.EVENT_TYPE_STATE_CHANGED) {
                if (eventBundle != null && eventBundle.containsKey(HotwordRecognizer.PROMPT_TEXT)) {
                    mSecurityMessageDisplay.setMessage(
                            eventBundle.getString(HotwordRecognizer.PROMPT_TEXT), true);
                }
            }
        }

        public void onHotwordRecognized(PendingIntent intent) {
            if (DEBUG) Log.d(TAG, "onHotwordRecognized");
            maybeStopHotwordDetector();
            if (intent != null) {
                try {
                    intent.send();
                } catch (PendingIntent.CanceledException e) {
                    Log.w(TAG, "Failed to launch PendingIntent. Encountered CanceledException");
                }
            }
            mCallback.userActivity(0);
            mCallback.dismiss(false);
        }

        public void onHotwordError(int errorCode) {
            if (DEBUG) Log.d(TAG, "onHotwordError: " + errorCode);
            // TODO: Inspect the error code and handle the errors appropriately
            // instead of blindly failing.
            maybeStopHotwordDetector();
        }
    };
}
