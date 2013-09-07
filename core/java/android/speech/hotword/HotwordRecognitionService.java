/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.speech.hotword;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

/**
 * This class provides a base class for hotword detection service implementations.
 * This class should be extended only if you wish to implement a new hotword recognizer.
 */
public abstract class HotwordRecognitionService extends Service {
    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.speech.hotword.HotwordRecognitionService";

    /** Log messages identifier */
    private static final String TAG = "HotwordRecognitionService";

    /** Debugging flag */
    private static final boolean DBG = false;

    private static final int MSG_START_RECOGNITION = 1;
    private static final int MSG_STOP_RECOGNITION = 2;

    /**
     * The current callback of an application that invoked the
     * {@link HotwordRecognitionService#onStartHotwordRecognition(Callback)} method
     */
    private Callback mCurrentCallback = null;

    // Handle the client dying.
    private final IBinder.DeathRecipient mCallbackDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            if (DBG) Log.i(TAG, "HotwordRecognitionService listener died");
            mCurrentCallback = null;
        }
    };

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_START_RECOGNITION:
                    dispatchStartRecognition((IHotwordRecognitionListener) msg.obj);
                    break;
                case MSG_STOP_RECOGNITION:
                    dispatchStopRecognition((IHotwordRecognitionListener) msg.obj);
                    break;
            }
        }
    };

    /** Binder of the hotword recognition service */
    private RecognitionServiceBinder mBinder = new RecognitionServiceBinder(this);

    private void dispatchStartRecognition(IHotwordRecognitionListener listener) {
        if (mCurrentCallback == null) {
            if (DBG) Log.d(TAG, "created new mCurrentCallback, listener = " + listener.asBinder());
            try {
                listener.asBinder().linkToDeath(mCallbackDeathRecipient, 0);
            } catch (RemoteException e) {
                if (DBG) Log.d(TAG, "listener died before linkToDeath()");
            }
            mCurrentCallback = new Callback(listener);
            HotwordRecognitionService.this.onStartHotwordRecognition(mCurrentCallback);
        } else {
            try {
                listener.onHotwordError(HotwordRecognizer.ERROR_RECOGNIZER_BUSY);
            } catch (RemoteException e) {
                if (DBG) Log.d(TAG, "onError call from startRecognition failed");
            }
            if (DBG) Log.d(TAG, "concurrent startRecognition received - ignoring this call");
        }
    }

    private void dispatchStopRecognition(IHotwordRecognitionListener listener) {
        try {
            if (mCurrentCallback == null) {
                listener.onHotwordError(HotwordRecognizer.ERROR_CLIENT);
                Log.w(TAG, "stopRecognition called with no preceding startRecognition - ignoring");
            } else if (mCurrentCallback.mListener.asBinder() != listener.asBinder()) {
                listener.onHotwordError(HotwordRecognizer.ERROR_RECOGNIZER_BUSY);
                Log.w(TAG, "stopRecognition called by a different caller - ignoring");
            } else { // the correct state
                mCurrentCallback.onHotwordRecognitionStopped();
                mCurrentCallback = null;
                HotwordRecognitionService.this.onStopHotwordRecognition();
            }
        } catch (RemoteException e) { // occurs if onError fails
            if (DBG) Log.d(TAG, "onError call from stopRecognition failed");
        }
    }

    @Override
    public IBinder onBind(final Intent intent) {
        if (DBG) Log.d(TAG, "onBind, intent=" + intent);
        return mBinder;
    }

    @Override
    public void onDestroy() {
        if (DBG) Log.d(TAG, "onDestroy");
        if (mCurrentCallback != null) {
            mCurrentCallback.mListener.asBinder().unlinkToDeath(mCallbackDeathRecipient, 0);
            mCurrentCallback = null;
        }
        mBinder.clearReference();
        super.onDestroy();
    }

    /**
     * Notifies the service to start a recognition.
     *
     * @param callback that receives the callbacks from the service.
     */
    public abstract void onStartHotwordRecognition(Callback callback);

    /**
     * Notifies the service to stop recognition.
     */
    public abstract void onStopHotwordRecognition();

    /** Binder of the hotword recognition service */
    private static class RecognitionServiceBinder extends IHotwordRecognitionService.Stub {
        private HotwordRecognitionService mInternalService;

        public RecognitionServiceBinder(HotwordRecognitionService service) {
            mInternalService = service;
        }

        public void startHotwordRecognition(IHotwordRecognitionListener listener) {
            if (DBG) Log.d(TAG, "startRecognition called by: " + listener.asBinder());
            if (mInternalService != null && mInternalService.checkPermissions(listener)) {
                mInternalService.mHandler.sendMessage(
                        Message.obtain(mInternalService.mHandler, MSG_START_RECOGNITION, listener));
            }
        }

        public void stopHotwordRecognition(IHotwordRecognitionListener listener) {
            if (DBG) Log.d(TAG, "stopRecognition called by: " + listener.asBinder());
            if (mInternalService != null && mInternalService.checkPermissions(listener)) {
                mInternalService.mHandler.sendMessage(
                        Message.obtain(mInternalService.mHandler, MSG_STOP_RECOGNITION, listener));
            }
        }

        private void clearReference() {
            mInternalService = null;
        }
    }

    /**
     * Checks whether the caller has sufficient permissions
     *
     * @param listener to send the error message to in case of error.
     * @return {@code true} if the caller has enough permissions, {@code false} otherwise.
     */
    private boolean checkPermissions(IHotwordRecognitionListener listener) {
        if (DBG) Log.d(TAG, "checkPermissions");
        if (checkCallingOrSelfPermission(android.Manifest.permission.HOTWORD_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        try {
            Log.e(TAG, "Recognition service called without HOTWORD_RECOGNITION permissions");
            listener.onHotwordError(HotwordRecognizer.ERROR_FAILED);
        } catch (RemoteException e) {
            Log.e(TAG, "onHotwordError(ERROR_FAILED) message failed", e);
        }
        return false;
    }

    /**
     * This class acts passes on the callbacks received from the Hotword service
     * to the listener.
     */
    public static class Callback {
        private final IHotwordRecognitionListener mListener;

        private Callback(IHotwordRecognitionListener listener) {
            mListener = listener;
        }

        /**
         * Called when the service starts listening for hotword.
         */
        public void onHotwordRecognitionStarted() throws RemoteException {
            mListener.onHotwordRecognitionStarted();
        }

        /**
         * Called when the service starts listening for hotword.
         */
        public void onHotwordRecognitionStopped() throws RemoteException {
            mListener.onHotwordRecognitionStopped();
        }

        /**
         * Called on an event of interest to the client.
         *
         * @param eventType the event type.
         * @param eventBundle a Bundle containing the hotword event(s).
         */
        public void onHotwordEvent(int eventType, Bundle eventBundle) throws RemoteException {
            mListener.onHotwordEvent(eventType, eventBundle);
        }

        /**
         * Called back when hotword is detected.
         *
         * @param activityIntent for the activity to launch, post hotword detection.
         */
        public void onHotwordRecognized(Intent activityIntent) throws RemoteException {
            mListener.onHotwordRecognized(activityIntent);
        }

        /**
         * Called when the HotwordRecognitionService encounters an error.
         *
         * @param errorCode the error code describing the error that was encountered.
         */
        public void onError(int errorCode) throws RemoteException {
            mListener.onHotwordError(errorCode);
        }
    }
}
