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

package com.android.internal.policy.impl.keyguard_obsolete;

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
import android.os.Looper;
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
    // TODO: now that the code has been restructure to do almost all operations from a handler, this
    // lock may no longer be necessary.
    private final Object mServiceRunningLock = new Object();
    private IFaceLockInterface mService;
    private boolean mBoundToService = false;
    private View mFaceUnlockView;

    private Handler mHandler;
    private final int MSG_SHOW_FACE_UNLOCK_VIEW = 0;
    private final int MSG_HIDE_FACE_UNLOCK_VIEW = 1;
    private final int MSG_SERVICE_CONNECTED = 2;
    private final int MSG_SERVICE_DISCONNECTED = 3;
    private final int MSG_UNLOCK = 4;
    private final int MSG_CANCEL = 5;
    private final int MSG_REPORT_FAILED_ATTEMPT = 6;
    //private final int MSG_EXPOSE_FALLBACK = 7;
    private final int MSG_POKE_WAKELOCK = 8;

    // TODO: This was added for the purpose of adhering to what the biometric interface expects
    // the isRunning() function to return.  However, it is probably not necessary to have both
    // mRunning and mServiceRunning.  I'd just rather wait to change that logic.
    private volatile boolean mIsRunning = false;

    // Long enough to stay visible while the service starts
    // Short enough to not have to wait long for backup if service fails to start or crashes
    // The service can take a couple of seconds to start on the first try after boot
    private final int SERVICE_STARTUP_VIEW_TIMEOUT = 3000;

    // So the user has a consistent amount of time when brought to the backup method from Face
    // Unlock
    private final int BACKUP_LOCK_TIMEOUT = 5000;

    KeyguardScreenCallback mKeyguardScreenCallback;

    /**
     * Stores some of the structures that Face Unlock will need to access and creates the handler
     * will be used to execute messages on the UI thread.
     */
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
        Log.d(TAG, "initializeView()");
        mFaceUnlockView = biometricUnlockView;
    }

    /**
     * Indicates whether Face Unlock is currently running.
     */
    public boolean isRunning() {
        return mIsRunning;
    }

    /**
     * Sets the Face Unlock view to visible, hiding it after the specified amount of time.  If
     * timeoutMillis is 0, no hide is performed.  Called on the UI thread.
     */
    public void show(long timeoutMillis) {
        if (DEBUG) Log.d(TAG, "show()");
        if (mHandler.getLooper() != Looper.myLooper()) {
            Log.e(TAG, "show() called off of the UI thread");
        }

        removeDisplayMessages();
        if (mFaceUnlockView != null) {
            mFaceUnlockView.setVisibility(View.VISIBLE);
        }
        if (timeoutMillis > 0) {
            mHandler.sendEmptyMessageDelayed(MSG_HIDE_FACE_UNLOCK_VIEW, timeoutMillis);
        }
    }

    /**
     * Hides the Face Unlock view.
     */
    public void hide() {
        if (DEBUG) Log.d(TAG, "hide()");
        // Remove messages to prevent a delayed show message from undo-ing the hide
        removeDisplayMessages();
        mHandler.sendEmptyMessage(MSG_HIDE_FACE_UNLOCK_VIEW);
    }

    /**
     * Binds to the Face Unlock service.  Face Unlock will be started when the bind completes.  The
     * Face Unlock view is displayed to hide the backup lock while the service is starting up.
     * Called on the UI thread.
     */
    public boolean start() {
        if (DEBUG) Log.d(TAG, "start()");
        if (mHandler.getLooper() != Looper.myLooper()) {
            Log.e(TAG, "start() called off of the UI thread");
        }

        if (mIsRunning) {
            Log.w(TAG, "start() called when already running");
        }

        // Show Face Unlock view, but only for a little bit so lockpattern will become visible if
        // Face Unlock fails to start or crashes
        // This must show before bind to guarantee that Face Unlock has a place to display
        show(SERVICE_STARTUP_VIEW_TIMEOUT);
        if (!mBoundToService) {
            Log.d(TAG, "Binding to Face Unlock service");
            mContext.bindService(new Intent(IFaceLockInterface.class.getName()),
                    mConnection,
                    Context.BIND_AUTO_CREATE,
                    mLockPatternUtils.getCurrentUser());
            mBoundToService = true;
        } else {
            Log.w(TAG, "Attempt to bind to Face Unlock when already bound");
        }

        mIsRunning = true;
        return true;
    }

    /**
     * Stops Face Unlock and unbinds from the service.  Called on the UI thread.
     */
    public boolean stop() {
        if (DEBUG) Log.d(TAG, "stop()");
        if (mHandler.getLooper() != Looper.myLooper()) {
            Log.e(TAG, "stop() called off of the UI thread");
        }

        boolean mWasRunning = mIsRunning;
        stopUi();

        if (mBoundToService) {
            if (mService != null) {
                try {
                    mService.unregisterCallback(mFaceUnlockCallback);
                } catch (RemoteException e) {
                    // Not much we can do
                }
            }
            Log.d(TAG, "Unbinding from Face Unlock service");
            mContext.unbindService(mConnection);
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
        if (DEBUG) Log.d(TAG, "cleanUp()");
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
     * Handles messages such that everything happens on the UI thread in a deterministic order.
     * Calls from the Face Unlock service come from binder threads.  Calls from lockscreen typically
     * come from the UI thread.  This makes sure there are no race conditions between those calls.
     */
    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_SHOW_FACE_UNLOCK_VIEW:
                handleShowFaceUnlockView();
                break;
            case MSG_HIDE_FACE_UNLOCK_VIEW:
                handleHideFaceUnlockView();
                break;
            case MSG_SERVICE_CONNECTED:
                handleServiceConnected();
                break;
            case MSG_SERVICE_DISCONNECTED:
                handleServiceDisconnected();
                break;
            case MSG_UNLOCK:
                handleUnlock();
                break;
            case MSG_CANCEL:
                handleCancel();
                break;
            case MSG_REPORT_FAILED_ATTEMPT:
                handleReportFailedAttempt();
                break;
                //case MSG_EXPOSE_FALLBACK:
                //handleExposeFallback();
                //break;
            case MSG_POKE_WAKELOCK:
                handlePokeWakelock(msg.arg1);
                break;
            default:
                Log.e(TAG, "Unhandled message");
                return false;
        }
        return true;
    }

    /**
     * Sets the Face Unlock view to visible, thus covering the backup lock.
     */
    void handleShowFaceUnlockView() {
        if (DEBUG) Log.d(TAG, "handleShowFaceUnlockView()");
        if (mFaceUnlockView != null) {
            mFaceUnlockView.setVisibility(View.VISIBLE);
        } else {
            Log.e(TAG, "mFaceUnlockView is null in handleShowFaceUnlockView()");
        }
    }

    /**
     * Sets the Face Unlock view to invisible, thus exposing the backup lock.
     */
    void handleHideFaceUnlockView() {
        if (DEBUG) Log.d(TAG, "handleHideFaceUnlockView()");
        if (mFaceUnlockView != null) {
            mFaceUnlockView.setVisibility(View.INVISIBLE);
        } else {
            Log.e(TAG, "mFaceUnlockView is null in handleHideFaceUnlockView()");
        }
    }

    /**
     * Tells the service to start its UI via an AIDL interface.  Called when the
     * onServiceConnected() callback is received.
     */
    void handleServiceConnected() {
        Log.d(TAG, "handleServiceConnected()");

        // It is possible that an unbind has occurred in the time between the bind and when this
        // function is reached.  If an unbind has already occurred, proceeding on to call startUi()
        // can result in a fatal error.  Note that the onServiceConnected() callback is
        // asynchronous, so this possibility would still exist if we executed this directly in
        // onServiceConnected() rather than using a handler.
        if (!mBoundToService) {
            Log.d(TAG, "Dropping startUi() in handleServiceConnected() because no longer bound");
            return;
        }

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
            IBinder windowToken = mFaceUnlockView.getWindowToken();
            if (windowToken != null) {
                // When switching between portrait and landscape view while Face Unlock is running,
                // the screen will eventually go dark unless we poke the wakelock when Face Unlock
                // is restarted.
                mKeyguardScreenCallback.pokeWakelock();

                int[] position;
                position = new int[2];
                mFaceUnlockView.getLocationInWindow(position);
                startUi(windowToken, position[0], position[1], mFaceUnlockView.getWidth(),
                        mFaceUnlockView.getHeight());
            } else {
                Log.e(TAG, "windowToken is null in handleServiceConnected()");
            }
        }
    }

    /**
     * Called when the onServiceDisconnected() callback is received.  This should not happen during
     * normal operation.  It indicates an error has occurred.
     */
    void handleServiceDisconnected() {
        Log.e(TAG, "handleServiceDisconnected()");
        // TODO: this lock may no longer be needed now that everything is being called from a
        // handler
        synchronized (mServiceRunningLock) {
            mService = null;
            mServiceRunning = false;
        }
        mBoundToService = false;
        mIsRunning = false;
    }

    /**
     * Stops the Face Unlock service and tells the device to grant access to the user.  Shows the
     * Face Unlock view to keep the backup lock covered while the device unlocks.
     */
    void handleUnlock() {
        if (DEBUG) Log.d(TAG, "handleUnlock()");
        removeDisplayMessages();
        if (mFaceUnlockView != null) {
            mFaceUnlockView.setVisibility(View.VISIBLE);
        } else {
            Log.e(TAG, "mFaceUnlockView is null in handleUnlock()");
        }
        stop();
        mKeyguardScreenCallback.keyguardDone(true);
        mKeyguardScreenCallback.reportSuccessfulUnlockAttempt();
    }

    /**
     * Stops the Face Unlock service and exposes the backup lock.
     */
    void handleCancel() {
        if (DEBUG) Log.d(TAG, "handleCancel()");
        if (mFaceUnlockView != null) {
            mFaceUnlockView.setVisibility(View.INVISIBLE);
        } else {
            Log.e(TAG, "mFaceUnlockView is null in handleCancel()");
        }
        stop();
        mKeyguardScreenCallback.pokeWakelock(BACKUP_LOCK_TIMEOUT);
    }

    /**
     * Increments the number of failed Face Unlock attempts.
     */
    void handleReportFailedAttempt() {
        if (DEBUG) Log.d(TAG, "handleReportFailedAttempt()");
        mUpdateMonitor.reportFailedBiometricUnlockAttempt();
    }

    /**
     * Hides the Face Unlock view to expose the backup lock.  Called when the Face Unlock service UI
     * is started, indicating there is no need to continue displaying the underlying view because
     * the service UI is now covering the backup lock.
     */
    //void handleExposeFallback() {
    //    if (DEBUG) Log.d(TAG, "handleExposeFallback()");
    //    if (mFaceUnlockView != null) {
    //        mFaceUnlockView.setVisibility(View.INVISIBLE);
    //    } else {
    //        Log.e(TAG, "mFaceUnlockView is null in handleExposeFallback()");
    //    }
    //}

    /**
     * Pokes the wakelock to keep the screen alive and active for a specific amount of time.
     */
    void handlePokeWakelock(int millis) {
        mKeyguardScreenCallback.pokeWakelock(millis);
    }

    /**
     * Removes show and hide messages from the message queue.  Called to prevent delayed show/hide
     * messages from undoing a new message.
     */
    private void removeDisplayMessages() {
        mHandler.removeMessages(MSG_SHOW_FACE_UNLOCK_VIEW);
        mHandler.removeMessages(MSG_HIDE_FACE_UNLOCK_VIEW);
    }

    /**
     * Implements service connection methods.
     */
    private ServiceConnection mConnection = new ServiceConnection() {
        /**
         * Called when the Face Unlock service connects after calling bind().
         */
        @Override
        public void onServiceConnected(ComponentName className, IBinder iservice) {
            Log.d(TAG, "Connected to Face Unlock service");
            mService = IFaceLockInterface.Stub.asInterface(iservice);
            mHandler.sendEmptyMessage(MSG_SERVICE_CONNECTED);
        }

        /**
         * Called if the Face Unlock service unexpectedly disconnects.  This indicates an error.
         */
        @Override
        public void onServiceDisconnected(ComponentName className) {
            Log.e(TAG, "Unexpected disconnect from Face Unlock service");
            mHandler.sendEmptyMessage(MSG_SERVICE_DISCONNECTED);
        }
    };

    /**
     * Tells the Face Unlock service to start displaying its UI and start processing.
     */
    private void startUi(IBinder windowToken, int x, int y, int w, int h) {
        if (DEBUG) Log.d(TAG, "startUi()");
        synchronized (mServiceRunningLock) {
            if (!mServiceRunning) {
                Log.d(TAG, "Starting Face Unlock");
                try {
                    mService.startUi(windowToken, x, y, w, h,
                            mLockPatternUtils.isBiometricWeakLivelinessEnabled());
                } catch (RemoteException e) {
                    Log.e(TAG, "Caught exception starting Face Unlock: " + e.toString());
                    return;
                }
                mServiceRunning = true;
            } else {
                Log.w(TAG, "startUi() attempted while running");
            }
        }
    }

    /**
     * Tells the Face Unlock service to stop displaying its UI and stop processing.
     */
    private void stopUi() {
        if (DEBUG) Log.d(TAG, "stopUi()");
        // Note that attempting to stop Face Unlock when it's not running is not an issue.
        // Face Unlock can return, which stops it and then we try to stop it when the
        // screen is turned off.  That's why we check.
        synchronized (mServiceRunningLock) {
            if (mServiceRunning) {
                Log.d(TAG, "Stopping Face Unlock");
                try {
                    mService.stopUi();
                } catch (RemoteException e) {
                    Log.e(TAG, "Caught exception stopping Face Unlock: " + e.toString());
                }
                mServiceRunning = false;
            } else {
                // This is usually not an error when this happens.  Sometimes we will tell it to
                // stop multiple times because it's called from both onWindowFocusChanged and
                // onDetachedFromWindow.
                if (DEBUG) Log.d(TAG, "stopUi() attempted while not running");
            }
        }
    }

    /**
     * Implements the AIDL biometric unlock service callback interface.
     */
    private final IFaceLockCallback mFaceUnlockCallback = new IFaceLockCallback.Stub() {
        /**
         * Called when Face Unlock wants to grant access to the user.
         */
        @Override
        public void unlock() {
            if (DEBUG) Log.d(TAG, "unlock()");
            mHandler.sendEmptyMessage(MSG_UNLOCK);
        }

        /**
         * Called when Face Unlock wants to go to the backup.
         */
        @Override
        public void cancel() {
            if (DEBUG) Log.d(TAG, "cancel()");
            mHandler.sendEmptyMessage(MSG_CANCEL);
        }

        /**
         * Called when Face Unlock wants to increment the number of failed attempts.
         */
        @Override
        public void reportFailedAttempt() {
            if (DEBUG) Log.d(TAG, "reportFailedAttempt()");
            mHandler.sendEmptyMessage(MSG_REPORT_FAILED_ATTEMPT);
        }

        /**
         * Called when the Face Unlock service starts displaying the UI, indicating that the backup
         * unlock can be exposed because the Face Unlock service is now covering the backup with its
         * UI.
         **/
        //@Override
        //public void exposeFallback() {
        //    if (DEBUG) Log.d(TAG, "exposeFallback()");
        //    mHandler.sendEmptyMessage(MSG_EXPOSE_FALLBACK);
        //}

        /**
         * Called when Face Unlock wants to keep the screen alive and active for a specific amount
         * of time.
         */
        public void pokeWakelock(int millis) {
            if (DEBUG) Log.d(TAG, "pokeWakelock() for " + millis + "ms");
            Message message = mHandler.obtainMessage(MSG_POKE_WAKELOCK, millis, -1);
            mHandler.sendMessage(message);
        }

    };
}
