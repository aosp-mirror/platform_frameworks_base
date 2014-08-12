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

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.TelephonyManager;
import android.util.AttributeSet;
import android.util.Log;
import android.view.IRotationWatcher;
import android.view.IWindowManager;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import com.android.internal.widget.LockPatternUtils;

import java.lang.Math;

public class KeyguardFaceUnlockView extends LinearLayout implements KeyguardSecurityView {

    private static final String TAG = "FULKeyguardFaceUnlockView";
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private KeyguardSecurityCallback mKeyguardSecurityCallback;
    private LockPatternUtils mLockPatternUtils;
    private BiometricSensorUnlock mBiometricUnlock;
    private View mFaceUnlockAreaView;
    private ImageButton mCancelButton;
    private SecurityMessageDisplay mSecurityMessageDisplay;
    private View mEcaView;
    private Drawable mBouncerFrame;

    private boolean mIsBouncerVisibleToUser = false;
    private final Object mIsBouncerVisibleToUserLock = new Object();

    private int mLastRotation;
    private boolean mWatchingRotation;
    private final IWindowManager mWindowManager =
            IWindowManager.Stub.asInterface(ServiceManager.getService("window"));

    private final IRotationWatcher mRotationWatcher = new IRotationWatcher.Stub() {
        public void onRotationChanged(int rotation) {
            if (DEBUG) Log.d(TAG, "onRotationChanged(): " + mLastRotation + "->" + rotation);

            // If the difference between the new rotation value and the previous rotation value is
            // equal to 2, the rotation change was 180 degrees.  This stops the biometric unlock
            // and starts it in the new position.  This is not performed for 90 degree rotations
            // since a 90 degree rotation is a configuration change, which takes care of this for
            // us.
            if (Math.abs(rotation - mLastRotation) == 2) {
                if (mBiometricUnlock != null) {
                    mBiometricUnlock.stop();
                    maybeStartBiometricUnlock();
                }
            }
            mLastRotation = rotation;
        }
    };

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
        if (mWatchingRotation) {
            try {
                mWindowManager.removeRotationWatcher(mRotationWatcher);
                mWatchingRotation = false;
            } catch (RemoteException e) {
                Log.e(TAG, "Remote exception when removing rotation watcher");
            }
        }
    }

    @Override
    public void onPause() {
        if (DEBUG) Log.d(TAG, "onPause()");
        if (mBiometricUnlock != null) {
            mBiometricUnlock.stop();
        }
        KeyguardUpdateMonitor.getInstance(mContext).removeCallback(mUpdateCallback);
        if (mWatchingRotation) {
            try {
                mWindowManager.removeRotationWatcher(mRotationWatcher);
                mWatchingRotation = false;
            } catch (RemoteException e) {
                Log.e(TAG, "Remote exception when removing rotation watcher");
            }
        }
    }

    @Override
    public void onResume(int reason) {
        if (DEBUG) Log.d(TAG, "onResume()");
        synchronized (mIsBouncerVisibleToUserLock) {
            mIsBouncerVisibleToUser = isBouncerVisibleToUser();
        }
        KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mUpdateCallback);

        // Registers a callback which handles stopping the biometric unlock and restarting it in
        // the new position for a 180 degree rotation change.
        if (!mWatchingRotation) {
            try {
                mLastRotation = mWindowManager.watchRotation(mRotationWatcher);
                mWatchingRotation = true;
            } catch (RemoteException e) {
                Log.e(TAG, "Remote exception when adding rotation watcher");
            }
        }
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

            boolean isBouncerVisibleToUser;
            synchronized(mIsBouncerVisibleToUserLock) {
                isBouncerVisibleToUser = mIsBouncerVisibleToUser;
            }

            // Don't start it if the bouncer is not showing, but keep this view up because we want
            // it here and ready for when the bouncer does show.
            if (!isBouncerVisibleToUser) {
                mBiometricUnlock.stop(); // It shouldn't be running but calling this can't hurt.
                return;
            }

            // Although these same conditions are handled in KeyguardSecurityModel, they are still
            // necessary here.  When a tablet is rotated 90 degrees, a configuration change is
            // triggered and everything is torn down and reconstructed.  That means
            // KeyguardSecurityModel gets a chance to take care of the logic and doesn't even
            // reconstruct KeyguardFaceUnlockView if the biometric unlock should be suppressed.
            // However, for a 180 degree rotation, no configuration change is triggered, so only
            // the logic here is capable of suppressing Face Unlock.
            if (monitor.getPhoneState() == TelephonyManager.CALL_STATE_IDLE
                    && monitor.isAlternateUnlockEnabled()
                    && !monitor.getMaxBiometricUnlockAttemptsReached()
                    && !backupIsTimedOut) {
                mBiometricUnlock.start();
            } else {
                mBiometricUnlock.stopAndShowBackup();
            }
        }
    }

    // Returns true if the device is currently in a state where the user is seeing the bouncer.
    // This requires isKeyguardBouncer() to be true, but that doesn't imply that the screen is on or
    // the keyguard visibility is set to true, so we must check those conditions as well.
    private boolean isBouncerVisibleToUser() {
        KeyguardUpdateMonitor updateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
        return updateMonitor.isKeyguardBouncer() && updateMonitor.isKeyguardVisible() &&
                updateMonitor.isScreenOn();
    }

    // Starts the biometric unlock if the bouncer was not previously visible to the user, but is now
    // visibile to the user.  Stops the biometric unlock if the bouncer was previously visible to
    // the user, but is no longer visible to the user.
    private void handleBouncerUserVisibilityChanged() {
        boolean wasBouncerVisibleToUser;
        synchronized(mIsBouncerVisibleToUserLock) {
            wasBouncerVisibleToUser = mIsBouncerVisibleToUser;
            mIsBouncerVisibleToUser = isBouncerVisibleToUser();
        }

        if (mBiometricUnlock != null) {
            if (wasBouncerVisibleToUser && !mIsBouncerVisibleToUser) {
                mBiometricUnlock.stop();
            } else if (!wasBouncerVisibleToUser && mIsBouncerVisibleToUser) {
                maybeStartBiometricUnlock();
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
        public void onUserSwitching(int userId) {
            if (DEBUG) Log.d(TAG, "onUserSwitched(" + userId + ")");
            if (mBiometricUnlock != null) {
                mBiometricUnlock.stop();
            }
            // No longer required; static value set by KeyguardViewMediator
            // mLockPatternUtils.setCurrentUser(userId);
        }

        @Override
        public void onUserSwitchComplete(int userId) {
            if (DEBUG) Log.d(TAG, "onUserSwitchComplete(" + userId + ")");
            if (mBiometricUnlock != null) {
                maybeStartBiometricUnlock();
            }
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            if (DEBUG) Log.d(TAG, "onKeyguardVisibilityChanged(" + showing + ")");
            handleBouncerUserVisibilityChanged();
        }

        @Override
        public void onKeyguardBouncerChanged(boolean bouncer) {
            if (DEBUG) Log.d(TAG, "onKeyguardBouncerChanged(" + bouncer + ")");
            handleBouncerUserVisibilityChanged();
        }

        @Override
        public void onScreenTurnedOn() {
            if (DEBUG) Log.d(TAG, "onScreenTurnedOn()");
            handleBouncerUserVisibilityChanged();
        }

        @Override
        public void onScreenTurnedOff(int why) {
            if (DEBUG) Log.d(TAG, "onScreenTurnedOff()");
            handleBouncerUserVisibilityChanged();
        }

        @Override
        public void onEmergencyCallAction() {
            if (mBiometricUnlock != null) {
                mBiometricUnlock.stop();
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

    @Override
    public void startAppearAnimation() {
        // TODO.
    }

    @Override
    public boolean startDisappearAnimation(Runnable finishRunnable) {
        return false;
    }
}
