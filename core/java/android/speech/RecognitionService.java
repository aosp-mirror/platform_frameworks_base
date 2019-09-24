/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.speech;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.app.Service;
import android.content.Intent;
import android.content.PermissionChecker;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import java.lang.ref.WeakReference;

/**
 * This class provides a base class for recognition service implementations. This class should be
 * extended only in case you wish to implement a new speech recognizer. Please note that the
 * implementation of this service is stateless.
 */
public abstract class RecognitionService extends Service {
    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE = "android.speech.RecognitionService";
    
    /**
     * Name under which a RecognitionService component publishes information about itself.
     * This meta-data should reference an XML resource containing a
     * <code>&lt;{@link android.R.styleable#RecognitionService recognition-service}&gt;</code> tag.
     */
    public static final String SERVICE_META_DATA = "android.speech";

    /** Log messages identifier */
    private static final String TAG = "RecognitionService";

    /** Debugging flag */
    private static final boolean DBG = false;

    /** Binder of the recognition service */
    private RecognitionServiceBinder mBinder = new RecognitionServiceBinder(this);

    /**
     * The current callback of an application that invoked the
     * {@link RecognitionService#onStartListening(Intent, Callback)} method
     */
    private Callback mCurrentCallback = null;

    private static final int MSG_START_LISTENING = 1;

    private static final int MSG_STOP_LISTENING = 2;

    private static final int MSG_CANCEL = 3;

    private static final int MSG_RESET = 4;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_START_LISTENING:
                    StartListeningArgs args = (StartListeningArgs) msg.obj;
                    dispatchStartListening(args.mIntent, args.mListener, args.mCallingUid);
                    break;
                case MSG_STOP_LISTENING:
                    dispatchStopListening((IRecognitionListener) msg.obj);
                    break;
                case MSG_CANCEL:
                    dispatchCancel((IRecognitionListener) msg.obj);
                    break;
                case MSG_RESET:
                    dispatchClearCallback();
                    break;
            }
        }
    };

    private void dispatchStartListening(Intent intent, final IRecognitionListener listener,
            int callingUid) {
        if (mCurrentCallback == null) {
            if (DBG) Log.d(TAG, "created new mCurrentCallback, listener = " + listener.asBinder());
            try {
                listener.asBinder().linkToDeath(new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        mHandler.sendMessage(mHandler.obtainMessage(MSG_CANCEL, listener));
                    }
                }, 0);
            } catch (RemoteException re) {
                Log.e(TAG, "dead listener on startListening");
                return;
            }
            mCurrentCallback = new Callback(listener, callingUid);
            RecognitionService.this.onStartListening(intent, mCurrentCallback);
        } else {
            try {
                listener.onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY);
            } catch (RemoteException e) {
                Log.d(TAG, "onError call from startListening failed");
            }
            Log.i(TAG, "concurrent startListening received - ignoring this call");
        }
    }

    private void dispatchStopListening(IRecognitionListener listener) {
        try {
            if (mCurrentCallback == null) {
                listener.onError(SpeechRecognizer.ERROR_CLIENT);
                Log.w(TAG, "stopListening called with no preceding startListening - ignoring");
            } else if (mCurrentCallback.mListener.asBinder() != listener.asBinder()) {
                listener.onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY);
                Log.w(TAG, "stopListening called by other caller than startListening - ignoring");
            } else { // the correct state
                RecognitionService.this.onStopListening(mCurrentCallback);
            }
        } catch (RemoteException e) { // occurs if onError fails
            Log.d(TAG, "onError call from stopListening failed");
        }
    }

    private void dispatchCancel(IRecognitionListener listener) {
        if (mCurrentCallback == null) {
            if (DBG) Log.d(TAG, "cancel called with no preceding startListening - ignoring");
        } else if (mCurrentCallback.mListener.asBinder() != listener.asBinder()) {
            Log.w(TAG, "cancel called by client who did not call startListening - ignoring");
        } else { // the correct state
            RecognitionService.this.onCancel(mCurrentCallback);
            mCurrentCallback = null;
            if (DBG) Log.d(TAG, "canceling - setting mCurrentCallback to null");
        }
    }

    private void dispatchClearCallback() {
        mCurrentCallback = null;
    }

    private class StartListeningArgs {
        public final Intent mIntent;

        public final IRecognitionListener mListener;
        public final int mCallingUid;

        public StartListeningArgs(Intent intent, IRecognitionListener listener, int callingUid) {
            this.mIntent = intent;
            this.mListener = listener;
            this.mCallingUid = callingUid;
        }
    }

    /**
     * Checks whether the caller has sufficient permissions
     * 
     * @param listener to send the error message to in case of error
     * @param forDataDelivery If the permission check is for delivering the sensitive data.
     * @return {@code true} if the caller has enough permissions, {@code false} otherwise
     */
    private boolean checkPermissions(IRecognitionListener listener, boolean forDataDelivery) {
        if (DBG) Log.d(TAG, "checkPermissions");
        if (forDataDelivery) {
            if (PermissionChecker.checkCallingOrSelfPermissionForDataDelivery(this,
                    android.Manifest.permission.RECORD_AUDIO)
                             == PermissionChecker.PERMISSION_GRANTED) {
                return true;
            }
        } else {
            if (PermissionChecker.checkCallingOrSelfPermissionForPreflight(this,
                    android.Manifest.permission.RECORD_AUDIO)
                            == PermissionChecker.PERMISSION_GRANTED) {
                return true;
            }
        }
        try {
            Log.e(TAG, "call for recognition service without RECORD_AUDIO permissions");
            listener.onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS);
        } catch (RemoteException re) {
            Log.e(TAG, "sending ERROR_INSUFFICIENT_PERMISSIONS message failed", re);
        }
        return false;
    }

    /**
     * Notifies the service that it should start listening for speech.
     * 
     * @param recognizerIntent contains parameters for the recognition to be performed. The intent
     *        may also contain optional extras, see {@link RecognizerIntent}. If these values are
     *        not set explicitly, default values should be used by the recognizer.
     * @param listener that will receive the service's callbacks
     */
    protected abstract void onStartListening(Intent recognizerIntent, Callback listener);

    /**
     * Notifies the service that it should cancel the speech recognition.
     */
    protected abstract void onCancel(Callback listener);

    /**
     * Notifies the service that it should stop listening for speech. Speech captured so far should
     * be recognized as if the user had stopped speaking at this point. This method is only called
     * if the application calls it explicitly.
     */
    protected abstract void onStopListening(Callback listener);

    @Override
    public final IBinder onBind(final Intent intent) {
        if (DBG) Log.d(TAG, "onBind, intent=" + intent);
        return mBinder;
    }

    @Override
    public void onDestroy() {
        if (DBG) Log.d(TAG, "onDestroy");
        mCurrentCallback = null;
        mBinder.clearReference();
        super.onDestroy();
    }

    /**
     * This class receives callbacks from the speech recognition service and forwards them to the
     * user. An instance of this class is passed to the
     * {@link RecognitionService#onStartListening(Intent, Callback)} method. Recognizers may call
     * these methods on any thread.
     */
    public class Callback {
        private final IRecognitionListener mListener;
        private final int mCallingUid;

        private Callback(IRecognitionListener listener, int callingUid) {
            mListener = listener;
            mCallingUid = callingUid;
        }

        /**
         * The service should call this method when the user has started to speak.
         */
        public void beginningOfSpeech() throws RemoteException {
            if (DBG) Log.d(TAG, "beginningOfSpeech");
            mListener.onBeginningOfSpeech();
        }

        /**
         * The service should call this method when sound has been received. The purpose of this
         * function is to allow giving feedback to the user regarding the captured audio.
         * 
         * @param buffer a buffer containing a sequence of big-endian 16-bit integers representing a
         *        single channel audio stream. The sample rate is implementation dependent.
         */
        public void bufferReceived(byte[] buffer) throws RemoteException {
            mListener.onBufferReceived(buffer);
        }

        /**
         * The service should call this method after the user stops speaking.
         */
        public void endOfSpeech() throws RemoteException {
            mListener.onEndOfSpeech();
        }

        /**
         * The service should call this method when a network or recognition error occurred.
         * 
         * @param error code is defined in {@link SpeechRecognizer}
         */
        public void error(int error) throws RemoteException {
            Message.obtain(mHandler, MSG_RESET).sendToTarget();
            mListener.onError(error);
        }

        /**
         * The service should call this method when partial recognition results are available. This
         * method can be called at any time between {@link #beginningOfSpeech()} and
         * {@link #results(Bundle)} when partial results are ready. This method may be called zero,
         * one or multiple times for each call to {@link SpeechRecognizer#startListening(Intent)},
         * depending on the speech recognition service implementation.
         * 
         * @param partialResults the returned results. To retrieve the results in
         *        ArrayList&lt;String&gt; format use {@link Bundle#getStringArrayList(String)} with
         *        {@link SpeechRecognizer#RESULTS_RECOGNITION} as a parameter
         */
        public void partialResults(Bundle partialResults) throws RemoteException {
            mListener.onPartialResults(partialResults);
        }

        /**
         * The service should call this method when the endpointer is ready for the user to start
         * speaking.
         * 
         * @param params parameters set by the recognition service. Reserved for future use.
         */
        public void readyForSpeech(Bundle params) throws RemoteException {
            mListener.onReadyForSpeech(params);
        }

        /**
         * The service should call this method when recognition results are ready.
         * 
         * @param results the recognition results. To retrieve the results in {@code
         *        ArrayList<String>} format use {@link Bundle#getStringArrayList(String)} with
         *        {@link SpeechRecognizer#RESULTS_RECOGNITION} as a parameter
         */
        public void results(Bundle results) throws RemoteException {
            Message.obtain(mHandler, MSG_RESET).sendToTarget();
            mListener.onResults(results);
        }

        /**
         * The service should call this method when the sound level in the audio stream has changed.
         * There is no guarantee that this method will be called.
         * 
         * @param rmsdB the new RMS dB value
         */
        public void rmsChanged(float rmsdB) throws RemoteException {
            mListener.onRmsChanged(rmsdB);
        }

        /**
         * Return the Linux uid assigned to the process that sent you the current transaction that
         * is being processed. This is obtained from {@link Binder#getCallingUid()}.
         */
        public int getCallingUid() {
            return mCallingUid;
        }
    }

    /** Binder of the recognition service */
    private static final class RecognitionServiceBinder extends IRecognitionService.Stub {
        private final WeakReference<RecognitionService> mServiceRef;

        public RecognitionServiceBinder(RecognitionService service) {
            mServiceRef = new WeakReference<RecognitionService>(service);
        }

        @Override
        public void startListening(Intent recognizerIntent, IRecognitionListener listener) {
            if (DBG) Log.d(TAG, "startListening called by:" + listener.asBinder());
            final RecognitionService service = mServiceRef.get();
            if (service != null && service.checkPermissions(listener, true /*forDataDelivery*/)) {
                service.mHandler.sendMessage(Message.obtain(service.mHandler,
                        MSG_START_LISTENING, service.new StartListeningArgs(
                                recognizerIntent, listener, Binder.getCallingUid())));
            }
        }

        @Override
        public void stopListening(IRecognitionListener listener) {
            if (DBG) Log.d(TAG, "stopListening called by:" + listener.asBinder());
            final RecognitionService service = mServiceRef.get();
            if (service != null && service.checkPermissions(listener, false /*forDataDelivery*/)) {
                service.mHandler.sendMessage(Message.obtain(service.mHandler,
                        MSG_STOP_LISTENING, listener));
            }
        }

        @Override
        public void cancel(IRecognitionListener listener) {
            if (DBG) Log.d(TAG, "cancel called by:" + listener.asBinder());
            final RecognitionService service = mServiceRef.get();
            if (service != null && service.checkPermissions(listener, false /*forDataDelivery*/)) {
                service.mHandler.sendMessage(Message.obtain(service.mHandler,
                        MSG_CANCEL, listener));
            }
        }

        public void clearReference() {
            mServiceRef.clear();
        }
    }
}
