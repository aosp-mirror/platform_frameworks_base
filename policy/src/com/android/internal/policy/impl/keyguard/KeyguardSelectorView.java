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

import android.animation.ObjectAnimator;
import android.app.ActivityManagerNative;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.multiwaveview.GlowPadView;
import com.android.internal.widget.multiwaveview.GlowPadView.OnTriggerListener;
import com.android.internal.R;

public class KeyguardSelectorView extends LinearLayout implements KeyguardSecurityView {
    private static final boolean DEBUG = KeyguardHostView.DEBUG;
    private static final String TAG = "SecuritySelectorView";
    private static final String ASSIST_ICON_METADATA_NAME =
        "com.android.systemui.action_assist_icon";
    private KeyguardSecurityCallback mCallback;
    private GlowPadView mGlowPadView;
    private Button mEmergencyCallButton;
    private ObjectAnimator mAnim;
    private boolean mCameraDisabled;
    private boolean mSearchDisabled;
    private LockPatternUtils mLockPatternUtils;

    OnTriggerListener mOnTriggerListener = new OnTriggerListener() {

        public void onTrigger(View v, int target) {
            final int resId = mGlowPadView.getResourceIdForTarget(target);
            switch (resId) {
                case com.android.internal.R.drawable.ic_action_assist_generic:
                    Intent assistIntent =
                            ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
                            .getAssistIntent(mContext, UserHandle.USER_CURRENT);
                    if (assistIntent != null) {
                        launchActivity(assistIntent, false);
                    } else {
                        Log.w(TAG, "Failed to get intent for assist activity");
                    }
                    mCallback.userActivity(0);
                    break;

                case com.android.internal.R.drawable.ic_lockscreen_camera:
                    launchCamera();
                    mCallback.userActivity(0);
                    break;

                case com.android.internal.R.drawable.ic_lockscreen_unlock_phantom:
                case com.android.internal.R.drawable.ic_lockscreen_unlock:
                    mCallback.dismiss(false);
                break;
            }
        }

        public void onReleased(View v, int handle) {
            doTransition(mEmergencyCallButton, 1.0f);
        }

        public void onGrabbed(View v, int handle) {
            doTransition(mEmergencyCallButton, 0.0f);
        }

        public void onGrabbedStateChange(View v, int handle) {

        }

        public void onFinishFinalAnimation() {

        }

    };

    KeyguardUpdateMonitorCallback mInfoCallback = new KeyguardUpdateMonitorCallback() {

        private boolean mEmergencyDialerDisableBecauseSimLocked;

        @Override
        public void onDevicePolicyManagerStateChanged() {
            updateTargets();
        }

        @Override
        public void onSimStateChanged(IccCardConstants.State simState) {
            // Some carriers aren't capable of handling emergency calls while the SIM is locked
            mEmergencyDialerDisableBecauseSimLocked = KeyguardUpdateMonitor.isSimLocked(simState)
                    && !mLockPatternUtils.isEmergencyCallEnabledWhileSimLocked();
            updateTargets();
        }

        void onPhoneStateChanged(int phoneState) {
            if (mEmergencyCallButton != null) {
                mLockPatternUtils.isEmergencyCallEnabledWhileSimLocked();
                mLockPatternUtils.updateEmergencyCallButtonState(mEmergencyCallButton,
                        phoneState, !mEmergencyDialerDisableBecauseSimLocked);
            }
        };
    };

    public KeyguardSelectorView(Context context) {
        this(context, null);
    }

    protected void launchCamera() {
        if (mLockPatternUtils.isSecure()) {
            // Launch the secure version of the camera
            launchActivity(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE), true);
        } else {
            // Launch the normal camera
            launchActivity(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), false);
        }
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
        mEmergencyCallButton = (Button) findViewById(R.id.emergency_call_button);
        KeyguardUpdateMonitor.getInstance(getContext()).registerCallback(mInfoCallback);
        updateTargets();
    }

    public boolean isTargetPresent(int resId) {
        return mGlowPadView.getTargetPosition(resId) != -1;
    }

    private void updateTargets() {
        boolean disabledByAdmin = mLockPatternUtils.getDevicePolicyManager()
                .getCameraDisabled(null);
        final KeyguardUpdateMonitor monitor = KeyguardUpdateMonitor.getInstance(getContext());
        boolean disabledBySimState = monitor.isSimLocked();
        boolean cameraTargetPresent =
            isTargetPresent(com.android.internal.R.drawable.ic_lockscreen_camera);
        boolean searchTargetPresent =
            isTargetPresent(com.android.internal.R.drawable.ic_action_assist_generic);

        if (disabledByAdmin) {
            Log.v(TAG, "Camera disabled by Device Policy");
        } else if (disabledBySimState) {
            Log.v(TAG, "Camera disabled by Sim State");
        }
        boolean searchActionAvailable =
                ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
                .getAssistIntent(mContext, UserHandle.USER_CURRENT) != null;
        mCameraDisabled = disabledByAdmin || disabledBySimState || !cameraTargetPresent;
        mSearchDisabled = disabledBySimState || !searchActionAvailable || !searchTargetPresent;
        updateResources();
    }

    public void updateResources() {
        // Update the search icon with drawable from the search .apk
        if (!mSearchDisabled) {
            Intent intent = ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
                    .getAssistIntent(mContext, UserHandle.USER_CURRENT);
            if (intent != null) {
                // XXX Hack. We need to substitute the icon here but haven't formalized
                // the public API. The "_google" metadata will be going away, so
                // DON'T USE IT!
                ComponentName component = intent.getComponent();
                boolean replaced = mGlowPadView.replaceTargetDrawablesIfPresent(component,
                        ASSIST_ICON_METADATA_NAME + "_google",
                        com.android.internal.R.drawable.ic_action_assist_generic);

                if (!replaced && !mGlowPadView.replaceTargetDrawablesIfPresent(component,
                            ASSIST_ICON_METADATA_NAME,
                            com.android.internal.R.drawable.ic_action_assist_generic)) {
                        Slog.w(TAG, "Couldn't grab icon from package " + component);
                }
            }
        }

        mGlowPadView.setEnableTarget(com.android.internal.R.drawable
                .ic_lockscreen_camera, !mCameraDisabled);
        mGlowPadView.setEnableTarget(com.android.internal.R.drawable
                .ic_action_assist_generic, !mSearchDisabled);
    }

    void doTransition(Object v, float to) {
        if (mAnim != null) {
            mAnim.cancel();
        }
        mAnim = ObjectAnimator.ofFloat(mEmergencyCallButton, "alpha", to);
        mAnim.start();
    }

    public void setKeyguardCallback(KeyguardSecurityCallback callback) {
        mCallback = callback;
    }

    public void setLockPatternUtils(LockPatternUtils utils) {
        mLockPatternUtils = utils;
    }

    /**
     * Launches the said intent for the current foreground user.
     * @param intent
     * @param showsWhileLocked true if the activity can be run on top of keyguard.
     * See {@link WindowManager#FLAG_SHOW_WHEN_LOCKED}
     */
    private void launchActivity(final Intent intent, boolean showsWhileLocked) {
        intent.setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        boolean isSecure = mLockPatternUtils.isSecure();
        if (!isSecure || showsWhileLocked) {
            if (!isSecure) try {
                ActivityManagerNative.getDefault().dismissKeyguardOnNextActivity();
            } catch (RemoteException e) {
                Log.w(TAG, "can't dismiss keyguard on launch");
            }
            try {
                mContext.startActivityAsUser(intent, new UserHandle(UserHandle.USER_CURRENT));
            } catch (ActivityNotFoundException e) {
                Log.w(TAG, "Activity not found for intent + " + intent.getAction());
            }
        } else {
            // Create a runnable to start the activity and ask the user to enter their
            // credentials.
            mCallback.setOnDismissRunnable(new Runnable() {
                @Override
                public void run() {
                    mContext.startActivityAsUser(intent, new UserHandle(UserHandle.USER_CURRENT));
                }
            });
            mCallback.dismiss(false);
        }
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
        KeyguardUpdateMonitor.getInstance(getContext()).removeCallback(mInfoCallback);
    }

    @Override
    public void onResume() {
        KeyguardUpdateMonitor.getInstance(getContext()).registerCallback(mInfoCallback);
    }

    @Override
    public KeyguardSecurityCallback getCallback() {
        return mCallback;
    }

}
