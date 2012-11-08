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
import android.graphics.drawable.Drawable;
import android.os.PowerManager;
import android.telephony.TelephonyManager;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import com.android.internal.R;

import com.android.internal.widget.LockPatternUtils;

public class KeyguardFaceUnlockView extends LinearLayout implements KeyguardSecurityView {

    private static final String TAG = "FULKeyguardFaceUnlockView";
    private static final boolean DEBUG = false;
    private KeyguardSecurityCallback mKeyguardSecurityCallback;
    private LockPatternUtils mLockPatternUtils;
    private BiometricSensorUnlock mBiometricUnlock;
    private View mFaceUnlockAreaView;
    private ImageButton mCancelButton;
    private SecurityMessageDisplay mSecurityMessageDisplay;
    private View mEcaView;
    private Drawable mBouncerFrame;

    private boolean mIsShowing = false;
    private final Object mIsShowingLock = new Object();

    public KeyguardFaceUnlockView(Context context) {
        this(context, null);
    }

    public KeyguardFaceUnlockView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        initializeBiometricUnlockView();

        mSecurityMessageDisplay = new KeyguardMessageArea.Helper(this);
        mEcaView = findViewById(R.id.keyguard_selector_fade_container);
        View bouncerFrameView = findViewById(R.id.keyguard_bouncer_frame);
        if (bouncerFrameView != null) {
            mBouncerFrame = bouncerFrameView.getBackground();
        }
    }

    @Override
    public void setKeyguardCallback(KeyguardSecurityCallback callback) {
        mKeyguardSecurityCallback = callback;
        // TODO: formalize this in the interface or factor it out
        ((FaceUnlock)mBiometricUnlock).setKeyguardCallback(callback);
    }

    @Override
    public void setLockPatternUtils(LockPatternUtils utils) {
        mLockPatternUtils = utils;
    }

    @Override
    public void reset() {

    }

    @Override
    public void onDetachedFromWindow() {
        if (DEBUG) Log.d(TAG, "onDetachedFromWindow()");
        if (mBiometricUnlock != null) {
            mBiometricUnlock.stop();
        }
        KeyguardUpdateMonitor.getInstance(mContext).removeCallback(mUpdateCallback);
    }

    @Override
    public void onPause() {
        if (DEBUG) Log.d(TAG, "onPause()");
        if (mBiometricUnlock != null) {
            mBiometricUnlock.stop();
        }
        KeyguardUpdateMonitor.getInstance(mContext).removeCallback(mUpdateCallback);
    }

    @Override
    public void onResume(int reason) {
        if (DEBUG) Log.d(TAG, "onResume()");
        mIsShowing = KeyguardUpdateMonitor.getInstance(mContext).isKeyguardVisible();
        maybeStartBiometricUnlock();
        KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mUpdateCallback);
    }

    @Override
    public boolean needsInput() {
        return false;
    }

    @Override
    public KeyguardSecurityCallback getCallback() {
        return mKeyguardSecurityCallback;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        mBiometricUnlock.initializeView(mFaceUnlockAreaView);
    }

    private void initializeBiometricUnlockView() {
        if (DEBUG) Log.d(TAG, "initializeBiometricUnlockView()");
        mFaceUnlockAreaView = findViewById(R.id.face_unlock_area_view);
        if (mFaceUnlockAreaView != null) {
            mBiometricUnlock = new FaceUnlock(mContext);

            mCancelButton = (ImageButton) findViewById(R.id.face_unlock_cancel_button);
            mCancelButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    mBiometricUnlock.stopAndShowBackup();
                }
            });
        } else {
            Log.w(TAG, "Couldn't find biometric unlock view");
        }
    }

    /**
     * Starts the biometric unlock if it should be started based on a number of factors.  If it
     * should not be started, it either goes to the back up, or remains showing to prepare for
     * it being started later.
     */
    private void maybeStartBiometricUnlock() {
        if (DEBUG) Log.d(TAG, "maybeStartBiometricUnlock()");
        if (mBiometricUnlock != null) {
            KeyguardUpdateMonitor monitor = KeyguardUpdateMonitor.getInstance(mContext);
            final boolean backupIsTimedOut = (
                    monitor.getFailedUnlockAttempts() >=
                    LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT);
            PowerManager powerManager = (PowerManager) mContext.getSystemService(
                    Context.POWER_SERVICE);

            boolean isShowing;
            synchronized(mIsShowingLock) {
                isShowing = mIsShowing;
            }

            // Don't start it if the screen is off or if it's not showing, but keep this view up
            // because we want it here and ready for when the screen turns on or when it does start
            // showing.
            if (!powerManager.isScreenOn() || !isShowing) {
                mBiometricUnlock.stop(); // It shouldn't be running but calling this can't hurt.
                return;
            }

            // TODO: Some of these conditions are handled in KeyguardSecurityModel and may not be
            // necessary here.
            if (monitor.getPhoneState() == TelephonyManager.CALL_STATE_IDLE
                    && !monitor.getMaxBiometricUnlockAttemptsReached()
                    && !backupIsTimedOut) {
                mBiometricUnlock.start();
            } else {
                mBiometricUnlock.stopAndShowBackup();
            }
        }
    }

    KeyguardUpdateMonitorCallback mUpdateCallback = new KeyguardUpdateMonitorCallback() {
        // We need to stop the biometric unlock when a phone call comes in
        @Override
        public void onPhoneStateChanged(int phoneState) {
            if (DEBUG) Log.d(TAG, "onPhoneStateChanged(" + phoneState + ")");
            if (phoneState == TelephonyManager.CALL_STATE_RINGING) {
                if (mBiometricUnlock != null) {
                    mBiometricUnlock.stopAndShowBackup();
                }
            }
        }

        @Override
        public void onUserSwitched(int userId) {
            if (DEBUG) Log.d(TAG, "onUserSwitched(" + userId + ")");
            if (mBiometricUnlock != null) {
                mBiometricUnlock.stop();
            }
            // No longer required; static value set by KeyguardViewMediator
            // mLockPatternUtils.setCurrentUser(userId);
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            if (DEBUG) Log.d(TAG, "onKeyguardVisibilityChanged(" + showing + ")");
            boolean wasShowing = false;
            synchronized(mIsShowingLock) {
                wasShowing = mIsShowing;
                mIsShowing = showing;
            }
            PowerManager powerManager = (PowerManager) mContext.getSystemService(
                    Context.POWER_SERVICE);
            if (mBiometricUnlock != null) {
                if (!showing && wasShowing) {
                    mBiometricUnlock.stop();
                } else if (showing && powerManager.isScreenOn() && !wasShowing) {
                    maybeStartBiometricUnlock();
                }
            }
        }
    };

    @Override
    public void showUsabilityHint() {
    }

    @Override
    public void showBouncer(int duration) {
        KeyguardSecurityViewHelper.
                showBouncer(mSecurityMessageDisplay, mEcaView, mBouncerFrame, duration);
    }

    @Override
    public void hideBouncer(int duration) {
        KeyguardSecurityViewHelper.
                hideBouncer(mSecurityMessageDisplay, mEcaView, mBouncerFrame, duration);
    }

}
