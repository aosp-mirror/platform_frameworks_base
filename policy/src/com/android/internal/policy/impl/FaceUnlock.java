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
    private final LockPatternUtils mLockPatternUtils;
    private final KeyguardUpdateMonitor mUpdateMonitor;

    // TODO: is mServiceRunning needed or can we just use mIsRunning or check if mService is null?
    private boolean mServiceRunning = false;
    private final Object mServiceRunningLock = new Object();
    private IFaceLockInterface mService;
    private boolean mBoundToService = false;
    private View mFaceUnlockView;

    private Handler mHandler;
    private final int MSG_SHOW_AREA_VIEW = 0;
    private final int MSG_HIDE_AREA_VIEW = 1;

    // TODO: This was added for the purpose of adhering to what the biometric interface expects
    // the isRunning() function to return.  However, it is probably not necessary to have both
    // mRunning and mServiceRunning.  I'd just rather wait to change that logic.
    private boolean mIsRunning = false;

    // Long enough to stay visible while the service starts
    // Short enough to not have to wait long for backup if service fails to start or crashes
    // The service can take a couple of seconds to start on the first try after boot
    private final int VIEW_AREA_SERVICE_TIMEOUT = 3000;

    // So the user has a consistent amount of time when brought to the backup method from Face
    // Unlock
    private final int BACKUP_LOCK_TIMEOUT = 5000;


    KeyguardScreenCallback mKeyguardScreenCallback;

    public FaceUnlock(Context context, KeyguardUpdateMonitor updateMonitor,
            LockPatternUtils lockPatternUtils, KeyguardScreenCallback keyguardScreenCallback) {
        mContext = context;
        mUpdateMonitor = updateMonitor;
        mLockPatternUtils = lockPatternUtils;
        mKeyguardScreenCallback = keyguardScreenCallback;
        mHandler = new Handler(this);
    }

    /**
     * Stores and displays the view that Face Unlock is allowed to draw within.
     * TODO: since the layout object will eventually be shared by multiple biometric unlock
     * methods, we will have to add our other views (background, cancel button) here.
     */
    public void initializeView(View biometricUnlockView) {
        mFaceUnlockView = biometricUnlockView;
        show(0);
    }

    /**
     * Indicates whether Face Unlock is currently running.
     */
    public boolean isRunning() {
        return mIsRunning;
    }

    /**
     * Sets the Face Unlock view to visible, hiding it after the specified amount of time.  If
     * timeoutMillis is 0, no hide is performed.
     */
    public void show(long timeoutMillis) {
        removeAreaDisplayMessages();
        if (mFaceUnlockView != null) {
            mFaceUnlockView.setVisibility(View.VISIBLE);
        }
        if (timeoutMillis > 0) {
            mHandler.sendEmptyMessageDelayed(MSG_HIDE_AREA_VIEW, timeoutMillis);
        }
    }

    /**
     * Hides the Face Unlock view.
     */
    public void hide() {
        // Remove messages to prevent a delayed show message from undo-ing the hide
        removeAreaDisplayMessages();
        mHandler.sendEmptyMessage(MSG_HIDE_AREA_VIEW);
    }

    /**
     * Binds to the Face Unlock service.  Face Unlock will be started when the bind completes.  The
     * Face Unlock area is displayed to hide the backup while the service is starting up.
     */
    public boolean start() {
        if (mIsRunning) {
            Log.w(TAG, "start() called when already running");
        }

        // Show Face Unlock view, but only for a little bit so lockpattern will become visible if
        // Face Unlock fails to start or crashes
        // This must show before bind to guarantee that Face Unlock has a place to display
        show(VIEW_AREA_SERVICE_TIMEOUT);
        if (!mBoundToService) {
            if (DEBUG) Log.d(TAG, "before bind to Face Unlock service");
            mContext.bindService(new Intent(IFaceLockInterface.class.getName()),
                    mConnection,
                    Context.BIND_AUTO_CREATE,
                    mLockPatternUtils.getCurrentUser());
            if (DEBUG) Log.d(TAG, "after bind to Face Unlock service");
            mBoundToService = true;
        } else {
            Log.w(TAG, "Attempt to bind to Face Unlock when already bound");
        }

        // When switching between portrait and landscape view while Face Unlock is running, the
        // screen will eventually go dark unless we poke the wakelock when Face Unlock is
        // restarted
        mKeyguardScreenCallback.pokeWakelock();

        mIsRunning = true;
        return true;
    }

    /**
     * Stops Face Unlock and unbinds from the service.
     */
    public boolean stop() {
        boolean mWasRunning = mIsRunning;
        stopUi();

        if (mBoundToService) {
            if (DEBUG) Log.d(TAG, "before unbind from Face Unlock service");
            if (mService != null) {
                try {
                    mService.unregisterCallback(mFaceUnlockCallback);
                } catch (RemoteException e) {
                    // Not much we can do
                }
            }
            mContext.unbindService(mConnection);
            if (DEBUG) Log.d(TAG, "after unbind from Face Unlock service");
            mBoundToService = false;
        } else {
            // This is usually not an error when this happens.  Sometimes we will tell it to
            // unbind multiple times because it's called from both onWindowFocusChanged and
            // onDetachedFromWindow.
            if (DEBUG) Log.d(TAG, "Attempt to unbind from Face Unlock when not bound");
        }
        mIsRunning = false;
        return mWasRunning;
    }

    /**
     * Frees up resources used by Face Unlock and stops it if it is still running.
     */
    public void cleanUp() {
        if (mService != null) {
            try {
                mService.unregisterCallback(mFaceUnlockCallback);
            } catch (RemoteException e) {
                // Not much we can do
            }
            stopUi();
            mService = null;
        }
    }

    /**
     * Returns the Device Policy Manager quality for Face Unlock, which is BIOMETRIC_WEAK.
     */
    public int getQuality() {
        return DevicePolicyManager.PASSWORD_QUALITY_BIOMETRIC_WEAK;
    }

    /**
     * Handles showing the Face Unlock view (hiding the backup lock) and hiding the Face Unlock view
     * (exposing the backup lock).  In cases where 'show' needs to happen immediately,
     * setVisibility() is called directly (without using this handler).  This handler is used when
     * 'show' needs to happen from a non-UI thread.  It also handles hide() messages since they
     * often require a delay.
     */
    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
        case MSG_SHOW_AREA_VIEW:
            if (mFaceUnlockView != null) {
                mFaceUnlockView.setVisibility(View.VISIBLE);
            }
            break;
        case MSG_HIDE_AREA_VIEW:
            if (mFaceUnlockView != null) {
                mFaceUnlockView.setVisibility(View.INVISIBLE);
            }
            break;
        default:
            Log.w(TAG, "Unhandled message");
            return false;
        }
        return true;
    }

    /**
     * Removes show and hide messages from the message queue
     */
    private void removeAreaDisplayMessages() {
        mHandler.removeMessages(MSG_SHOW_AREA_VIEW);
        mHandler.removeMessages(MSG_HIDE_AREA_VIEW);
    }

    private ServiceConnection mConnection = new ServiceConnection() {
        /**
         * Completes connection, registers callback, and starts Face Unlock when service is bound
         */
        @Override
        public void onServiceConnected(ComponentName className, IBinder iservice) {
            mService = IFaceLockInterface.Stub.asInterface(iservice);
            if (DEBUG) Log.d(TAG, "Connected to Face Unlock service");
            try {
                mService.registerCallback(mFaceUnlockCallback);
            } catch (RemoteException e) {
                Log.e(TAG, "Caught exception connecting to Face Unlock: " + e.toString());
                mService = null;
                mBoundToService = false;
                mIsRunning = false;
                return;
            }

            if (mFaceUnlockView != null) {
                int[] position;
                position = new int[2];
                mFaceUnlockView.getLocationInWindow(position);
                startUi(mFaceUnlockView.getWindowToken(), position[0], position[1],
                        mFaceUnlockView.getWidth(), mFaceUnlockView.getHeight());
            }
        }

        /**
         * Cleans up if Face Unlock service unexpectedly disconnects
         */
        @Override
        public void onServiceDisconnected(ComponentName className) {
            synchronized(mServiceRunningLock) {
                mService = null;
                mServiceRunning = false;
            }
            mBoundToService = false;
            mIsRunning = false;
            Log.w(TAG, "Unexpected disconnect from Face Unlock service");
        }
    };

    /**
     * Tells the Face Unlock service to start displaying its UI and perform recognition
     */
    private void startUi(IBinder windowToken, int x, int y, int w, int h) {
        synchronized (mServiceRunningLock) {
            if (!mServiceRunning) {
                if (DEBUG) Log.d(TAG, "Starting Face Unlock");
                try {
                    mService.startUi(windowToken, x, y, w, h,
                            mLockPatternUtils.isBiometricWeakLivelinessEnabled());
                } catch (RemoteException e) {
                    Log.e(TAG, "Caught exception starting Face Unlock: " + e.toString());
                    return;
                }
                mServiceRunning = true;
            } else {
                if (DEBUG) Log.w(TAG, "startUi() attempted while running");
            }
        }
    }

    /**
     * Tells the Face Unlock service to stop displaying its UI and stop recognition
     */
    private void stopUi() {
        // Note that attempting to stop Face Unlock when it's not running is not an issue.
        // Face Unlock can return, which stops it and then we try to stop it when the
        // screen is turned off.  That's why we check.
        synchronized (mServiceRunningLock) {
            if (mServiceRunning) {
                try {
                    if (DEBUG) Log.d(TAG, "Stopping Face Unlock");
                    mService.stopUi();
                } catch (RemoteException e) {
                    Log.e(TAG, "Caught exception stopping Face Unlock: " + e.toString());
                }
                mServiceRunning = false;
            }
        }
    }

    /**
     * Implements the biometric unlock service callback interface defined in AIDL
     */
    private final IFaceLockCallback mFaceUnlockCallback = new IFaceLockCallback.Stub() {
        /**
         * Stops the Face Unlock UI and indicates that the phone should be unlocked
         */
        @Override
        public void unlock() {
            if (DEBUG) Log.d(TAG, "unlock()");

            // Keep fallback covered
            removeAreaDisplayMessages();
            mHandler.sendEmptyMessage(MSG_SHOW_AREA_VIEW);

            stop();

            mKeyguardScreenCallback.keyguardDone(true);
            mKeyguardScreenCallback.reportSuccessfulUnlockAttempt();
        }

        /**
         * Stops the Face Unlock UI and exposes the backup method without unlocking
         * This means the user has cancelled out
         */
        @Override
        public void cancel() {
            if (DEBUG) Log.d(TAG, "cancel()");
            hide(); // Expose fallback
            stop();
            mKeyguardScreenCallback.pokeWakelock(BACKUP_LOCK_TIMEOUT);
        }

        /**
         * Stops the Face Unlock UI and exposes the backup method without unlocking
         * This means Face Unlock failed to recognize them
         */
        @Override
        public void reportFailedAttempt() {
            if (DEBUG) Log.d(TAG, "reportFailedAttempt()");
            mUpdateMonitor.reportFailedBiometricUnlockAttempt();
            hide(); // Expose fallback
            stop();
            mKeyguardScreenCallback.pokeWakelock(BACKUP_LOCK_TIMEOUT);
        }

        /**
         * Removes the black area that covers the backup unlock method
         **/
        @Override
        public void exposeFallback() {
            if (DEBUG) Log.d(TAG, "exposeFallback()");
            hide(); // Expose fallback
        }

        /**
         * Allows the Face Unlock service to poke the wake lock to keep the lockscreen alive
         */
        @Override
        public void pokeWakelock() {
            if (DEBUG) Log.d(TAG, "pokeWakelock()");
            mKeyguardScreenCallback.pokeWakelock();
        }
    };
}
