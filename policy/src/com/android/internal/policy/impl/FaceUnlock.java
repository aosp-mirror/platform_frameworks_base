/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.internal.policy.impl;

import com.android.internal.R;
import com.android.internal.policy.IFaceLockCallback;
import com.android.internal.policy.IFaceLockInterface;
import com.android.internal.widget.LockPatternUtils;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;

public class FaceUnlock implements BiometricSensorUnlock, Handler.Callback {

    private static final boolean DEBUG = false;
    private static final String TAG = "FULLockscreen";

    private final Context mContext;
    private final KeyguardUpdateMonitor mUpdateMonitor;

    private IFaceLockInterface mService;
    private boolean mBoundToService = false;
    private View mAreaView;

    private Handler mHandler;
    private final int MSG_SHOW_AREA_VIEW = 0;
    private final int MSG_HIDE_AREA_VIEW = 1;

    private boolean mServiceRunning = false;
    private final Object mServiceRunningLock = new Object();

    // Long enough to stay visible while the service starts
    // Short enough to not have to wait long for backup if service fails to start or crashes
    // The service can take a couple of seconds to start on the first try after boot
    private final int VIEW_AREA_SERVICE_TIMEOUT = 3000;

    // So the user has a consistent amount of time when brought to the backup method from FaceLock
    private final int BACKUP_LOCK_TIMEOUT = 5000;

    /**
     * Used to lookup the state of the lock pattern
     */
    private final LockPatternUtils mLockPatternUtils;

    KeyguardScreenCallback mKeyguardScreenCallback;

    public FaceUnlock(Context context, KeyguardUpdateMonitor updateMonitor,
            LockPatternUtils lockPatternUtils, KeyguardScreenCallback keyguardScreenCallback) {
        mContext = context;
        mUpdateMonitor = updateMonitor;
        mLockPatternUtils = lockPatternUtils;
        mKeyguardScreenCallback = keyguardScreenCallback;
        mHandler = new Handler(this);
    }

    public boolean isRunning() {
        return mServiceRunning;
    }

    // Shows the FaceLock area for a period of time
    // Called on the UI thread
    public void show(long timeoutMillis) {
        removeAreaDisplayMessages();
        showArea();
        if (timeoutMillis > 0)
            mHandler.sendEmptyMessageDelayed(MSG_HIDE_AREA_VIEW, timeoutMillis);
    }

    // Hides the FaceLock area immediately
    public void hide() {
        // Remove messages to prevent a delayed show message from undo-ing the hide
        removeAreaDisplayMessages();
        mHandler.sendEmptyMessage(MSG_HIDE_AREA_VIEW);
    }

    // Tells FaceLock to stop and then unbinds from the FaceLock service
    public boolean stop() {
        boolean wasRunning = false;
        stopUi();

        if (mBoundToService) {
            wasRunning = true;
            if (DEBUG) Log.d(TAG, "before unbind from FaceLock service");
            if (mService != null) {
                try {
                    mService.unregisterCallback(mFaceLockCallback);
                } catch (RemoteException e) {
                    // Not much we can do
                }
            }
            mContext.unbindService(mConnection);
            if (DEBUG) Log.d(TAG, "after unbind from FaceLock service");
            mBoundToService = false;
        } else {
            // This is usually not an error when this happens.  Sometimes we will tell it to
            // unbind multiple times because it's called from both onWindowFocusChanged and
            // onDetachedFromWindow.
            if (DEBUG) Log.d(TAG, "Attempt to unbind from FaceLock when not bound");
        }

        return wasRunning;
    }

    /**
     * When screen is turned on and focused, need to bind to FaceLock service if we are using
     * FaceLock, but only if we're not dealing with a call
     * Called on the UI thread
     */
    public boolean start(boolean suppressBiometricUnlock) {
        final boolean tooManyFaceUnlockTries = mUpdateMonitor.getMaxFaceUnlockAttemptsReached();
        final int failedBackupAttempts = mUpdateMonitor.getFailedAttempts();
        final boolean backupIsTimedOut =
                (failedBackupAttempts >= LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT);
        if (tooManyFaceUnlockTries) Log.i(TAG, "tooManyFaceUnlockTries: " + tooManyFaceUnlockTries);
        if (mUpdateMonitor.getPhoneState() == TelephonyManager.CALL_STATE_IDLE
                && !suppressBiometricUnlock
                && !tooManyFaceUnlockTries
                && !backupIsTimedOut) {
            // Show FaceLock area, but only for a little bit so lockpattern will become visible if
            // FaceLock fails to start or crashes
            // This must show before bind to guarantee that Face Unlock has a place to display
            show(VIEW_AREA_SERVICE_TIMEOUT);

            bind();

            // When switching between portrait and landscape view while FaceLock is running, the
            // screen will eventually go dark unless we poke the wakelock when FaceLock is
            // restarted
            mKeyguardScreenCallback.pokeWakelock();
        } else {
            hide();
            return false;
        }

        return true;
    }

    // Takes care of FaceLock area when layout is created
    public void initializeAreaView(View topView) {
        mAreaView = topView.findViewById(R.id.faceLockAreaView);
        if (mAreaView == null) {
            Log.e(TAG, "Layout does not have areaView and FaceLock is enabled");
        } else {
            show(0);
        }
    }

    public void cleanUp() {
        if (mService != null) {
            try {
                mService.unregisterCallback(mFaceLockCallback);
            } catch (RemoteException e) {
                // Not much we can do
            }
            stopUi();
            mService = null;
        }
    }

    public int getQuality() {
        return DevicePolicyManager.PASSWORD_QUALITY_BIOMETRIC_WEAK;
    }

    // Shows the FaceLock area
    // Called on the UI thread
    private void showArea() {
        if (mAreaView != null) {
            mAreaView.setVisibility(View.VISIBLE);
        }
    }

    // Handles covering or exposing FaceLock area on the client side when FaceLock starts or stops
    // This needs to be done in a handler because the call could be coming from a callback from the
    // FaceLock service that is in a thread that can't modify the UI
    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
        case MSG_SHOW_AREA_VIEW:
            showArea();
            break;
        case MSG_HIDE_AREA_VIEW:
            if (mAreaView != null) {
                mAreaView.setVisibility(View.INVISIBLE);
            }
            break;
        default:
            Log.w(TAG, "Unhandled message");
            return false;
        }
        return true;
    }

    // Removes show and hide messages from the message queue
    private void removeAreaDisplayMessages() {
        mHandler.removeMessages(MSG_SHOW_AREA_VIEW);
        mHandler.removeMessages(MSG_HIDE_AREA_VIEW);
    }

    // Binds to FaceLock service.  This call does not tell it to start, but it causes the service
    // to call the onServiceConnected callback, which then starts FaceLock.
    private void bind() {
        if (!mBoundToService) {
            if (DEBUG) Log.d(TAG, "before bind to FaceLock service");
            mContext.bindService(new Intent(IFaceLockInterface.class.getName()),
                    mConnection,
                    Context.BIND_AUTO_CREATE,
                    mLockPatternUtils.getCurrentUser());
            if (DEBUG) Log.d(TAG, "after bind to FaceLock service");
            mBoundToService = true;
        } else {
            Log.w(TAG, "Attempt to bind to FaceLock when already bound");
        }
    }

    private ServiceConnection mConnection = new ServiceConnection() {
        // Completes connection, registers callback and starts FaceLock when service is bound
        @Override
        public void onServiceConnected(ComponentName className, IBinder iservice) {
            mService = IFaceLockInterface.Stub.asInterface(iservice);
            if (DEBUG) Log.d(TAG, "Connected to FaceLock service");
            try {
                mService.registerCallback(mFaceLockCallback);
            } catch (RemoteException e) {
                Log.e(TAG, "Caught exception connecting to FaceLock: " + e.toString());
                mService = null;
                mBoundToService = false;
                return;
            }

            if (mAreaView != null) {
                int[] position;
                position = new int[2];
                mAreaView.getLocationInWindow(position);
                startUi(mAreaView.getWindowToken(), position[0], position[1],
                        mAreaView.getWidth(), mAreaView.getHeight());
            }
        }

        // Cleans up if FaceLock service unexpectedly disconnects
        @Override
        public void onServiceDisconnected(ComponentName className) {
            synchronized(mServiceRunningLock) {
                mService = null;
                mServiceRunning = false;
            }
            mBoundToService = false;
            Log.w(TAG, "Unexpected disconnect from FaceLock service");
        }
    };

    // Tells the FaceLock service to start displaying its UI and perform recognition
    private void startUi(IBinder windowToken, int x, int y, int w, int h) {
        synchronized (mServiceRunningLock) {
            if (!mServiceRunning) {
                if (DEBUG) Log.d(TAG, "Starting FaceLock");
                try {
                    mService.startUi(windowToken, x, y, w, h,
                            mLockPatternUtils.isBiometricWeakLivelinessEnabled());
                } catch (RemoteException e) {
                    Log.e(TAG, "Caught exception starting FaceLock: " + e.toString());
                    return;
                }
                mServiceRunning = true;
            } else {
                if (DEBUG) Log.w(TAG, "startUi() attempted while running");
            }
        }
    }

    // Tells the FaceLock service to stop displaying its UI and stop recognition
    private void stopUi() {
        // Note that attempting to stop FaceLock when it's not running is not an issue.
        // FaceLock can return, which stops it and then we try to stop it when the
        // screen is turned off.  That's why we check.
        synchronized (mServiceRunningLock) {
            if (mServiceRunning) {
                try {
                    if (DEBUG) Log.d(TAG, "Stopping FaceLock");
                    mService.stopUi();
                } catch (RemoteException e) {
                    Log.e(TAG, "Caught exception stopping FaceLock: " + e.toString());
                }
                mServiceRunning = false;
            }
        }
    }

    // Implements the FaceLock service callback interface defined in AIDL
    private final IFaceLockCallback mFaceLockCallback = new IFaceLockCallback.Stub() {
        // Stops the FaceLock UI and indicates that the phone should be unlocked
        @Override
        public void unlock() {
            if (DEBUG) Log.d(TAG, "FaceLock unlock()");

            // Keep fallback covered
            removeAreaDisplayMessages();
            mHandler.sendEmptyMessage(MSG_SHOW_AREA_VIEW);

            stop();

            mKeyguardScreenCallback.keyguardDone(true);
            mKeyguardScreenCallback.reportSuccessfulUnlockAttempt();
        }

        // Stops the FaceLock UI and exposes the backup method without unlocking
        // This means the user has cancelled out
        @Override
        public void cancel() {
            if (DEBUG) Log.d(TAG, "FaceLock cancel()");
            hide(); // Expose fallback
            stop();
            mKeyguardScreenCallback.pokeWakelock(BACKUP_LOCK_TIMEOUT);
        }

        // Stops the FaceLock UI and exposes the backup method without unlocking
        // This means FaceLock failed to recognize them
        @Override
        public void reportFailedAttempt() {
            if (DEBUG) Log.d(TAG, "FaceLock reportFailedAttempt()");
            mUpdateMonitor.reportFailedFaceUnlockAttempt();
            hide(); // Expose fallback
            stop();
            mKeyguardScreenCallback.pokeWakelock(BACKUP_LOCK_TIMEOUT);
        }

        // Removes the black area that covers the backup unlock method
        @Override
        public void exposeFallback() {
            if (DEBUG) Log.d(TAG, "FaceLock exposeFallback()");
            hide(); // Expose fallback
        }

        // Allows the Face Unlock service to poke the wake lock to keep the lockscreen alive
        @Override
        public void pokeWakelock() {
            if (DEBUG) Log.d(TAG, "FaceLock pokeWakelock()");
            mKeyguardScreenCallback.pokeWakelock();
        }
    };
}
