/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.speech;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * This class provides access to the speech recognition service. This service allows access to the
 * speech recognizer. Do not instantiate this class directly, instead, call
 * {@link SpeechRecognizer#createSpeechRecognizer(Context)}. This class's methods must be
 * invoked only from the main application thread. 
 *
 * <p>The implementation of this API is likely to stream audio to remote servers to perform speech
 * recognition. As such this API is not intended to be used for continuous recognition, which would
 * consume a significant amount of battery and bandwidth.
 *
 * <p>Please note that the application must have {@link android.Manifest.permission#RECORD_AUDIO}
 * permission to use this class.
 */
public class SpeechRecognizer {
    /** DEBUG value to enable verbose debug prints */
    private final static boolean DBG = false;

    /** Log messages identifier */
    private static final String TAG = "SpeechRecognizer";

    /**
     * Key used to retrieve an {@code ArrayList<String>} from the {@link Bundle} passed to the
     * {@link RecognitionListener#onResults(Bundle)} and
     * {@link RecognitionListener#onPartialResults(Bundle)} methods. These strings are the possible
     * recognition results, where the first element is the most likely candidate.
     */
    public static final String RESULTS_RECOGNITION = "results_recognition";
    
    /**
     * Key used to retrieve a float array from the {@link Bundle} passed to the
     * {@link RecognitionListener#onResults(Bundle)} and
     * {@link RecognitionListener#onPartialResults(Bundle)} methods. The array should be
     * the same size as the ArrayList provided in {@link #RESULTS_RECOGNITION}, and should contain
     * values ranging from 0.0 to 1.0, or -1 to represent an unavailable confidence score.
     * <p>
     * Confidence values close to 1.0 indicate high confidence (the speech recognizer is confident
     * that the recognition result is correct), while values close to 0.0 indicate low confidence.
     * <p>
     * This value is optional and might not be provided.
     */
    public static final String CONFIDENCE_SCORES = "confidence_scores";

    /** Network operation timed out. */
    public static final int ERROR_NETWORK_TIMEOUT = 1;

    /** Other network related errors. */
    public static final int ERROR_NETWORK = 2;

    /** Audio recording error. */
    public static final int ERROR_AUDIO = 3;

    /** Server sends error status. */
    public static final int ERROR_SERVER = 4;

    /** Other client side errors. */
    public static final int ERROR_CLIENT = 5;

    /** No speech input */
    public static final int ERROR_SPEECH_TIMEOUT = 6;

    /** No recognition result matched. */
    public static final int ERROR_NO_MATCH = 7;

    /** RecognitionService busy. */
    public static final int ERROR_RECOGNIZER_BUSY = 8;

    /** Insufficient permissions */
    public static final int ERROR_INSUFFICIENT_PERMISSIONS = 9;

    /** action codes */
    private final static int MSG_START = 1;
    private final static int MSG_STOP = 2;
    private final static int MSG_CANCEL = 3;
    private final static int MSG_CHANGE_LISTENER = 4;

    /** The actual RecognitionService endpoint */
    private IRecognitionService mService;

    /** The connection to the actual service */
    private Connection mConnection;

    /** Context with which the manager was created */
    private final Context mContext;
    
    /** Component to direct service intent to */
    private final ComponentName mServiceComponent;

    /** Handler that will execute the main tasks */
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_START:
                    handleStartListening((Intent) msg.obj);
                    break;
                case MSG_STOP:
                    handleStopMessage();
                    break;
                case MSG_CANCEL:
                    handleCancelMessage();
                    break;
                case MSG_CHANGE_LISTENER:
                    handleChangeListener((RecognitionListener) msg.obj);
                    break;
            }
        }
    };

    /**
     * Temporary queue, saving the messages until the connection will be established, afterwards,
     * only mHandler will receive the messages
     */
    private final Queue<Message> mPendingTasks = new LinkedList<Message>();

    /** The Listener that will receive all the callbacks */
    private final InternalListener mListener = new InternalListener();

    /**
     * The right way to create a {@code SpeechRecognizer} is by using
     * {@link #createSpeechRecognizer} static factory method
     */
    private SpeechRecognizer(final Context context, final ComponentName serviceComponent) {
        mContext = context;
        mServiceComponent = serviceComponent;
    }

    /**
     * Basic ServiceConnection that records the mService variable. Additionally, on creation it
     * invokes the {@link IRecognitionService#startListening(Intent, IRecognitionListener)}.
     */
    private class Connection implements ServiceConnection {

        public void onServiceConnected(final ComponentName name, final IBinder service) {
            // always done on the application main thread, so no need to send message to mHandler
            mService = IRecognitionService.Stub.asInterface(service);
            if (DBG) Log.d(TAG, "onServiceConnected - Success");
            while (!mPendingTasks.isEmpty()) {
                mHandler.sendMessage(mPendingTasks.poll());
            }
        }

        public void onServiceDisconnected(final ComponentName name) {
            // always done on the application main thread, so no need to send message to mHandler
            mService = null;
            mConnection = null;
            mPendingTasks.clear();
            if (DBG) Log.d(TAG, "onServiceDisconnected - Success");
        }
    }

    /**
     * Checks whether a speech recognition service is available on the system. If this method
     * returns {@code false}, {@link SpeechRecognizer#createSpeechRecognizer(Context)} will
     * fail.
     * 
     * @param context with which {@code SpeechRecognizer} will be created
     * @return {@code true} if recognition is available, {@code false} otherwise
     */
    public static boolean isRecognitionAvailable(final Context context) {
        final List<ResolveInfo> list = context.getPackageManager().queryIntentServices(
                new Intent(RecognitionService.SERVICE_INTERFACE), 0);
        return list != null && list.size() != 0;
    }

    /**
     * Factory method to create a new {@code SpeechRecognizer}. Please note that
     * {@link #setRecognitionListener(RecognitionListener)} should be called before dispatching any
     * command to the created {@code SpeechRecognizer}, otherwise no notifications will be
     * received.
     *
     * @param context in which to create {@code SpeechRecognizer}
     * @return a new {@code SpeechRecognizer}
     */
    public static SpeechRecognizer createSpeechRecognizer(final Context context) {
        return createSpeechRecognizer(context, null);
    }

    /**
     * Factory method to create a new {@code SpeechRecognizer}. Please note that
     * {@link #setRecognitionListener(RecognitionListener)} should be called before dispatching any
     * command to the created {@code SpeechRecognizer}, otherwise no notifications will be
     * received.
     *
     * Use this version of the method to specify a specific service to direct this
     * {@link SpeechRecognizer} to. Normally you would not use this; use
     * {@link #createSpeechRecognizer(Context)} instead to use the system default recognition
     * service.
     * 
     * @param context in which to create {@code SpeechRecognizer}
     * @param serviceComponent the {@link ComponentName} of a specific service to direct this
     *        {@code SpeechRecognizer} to
     * @return a new {@code SpeechRecognizer}
     */
    public static SpeechRecognizer createSpeechRecognizer(final Context context,
            final ComponentName serviceComponent) {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null)");
        }
        checkIsCalledFromMainThread();
        return new SpeechRecognizer(context, serviceComponent);
    }

    /**
     * Sets the listener that will receive all the callbacks. The previous unfinished commands will
     * be executed with the old listener, while any following command will be executed with the new
     * listener.
     * 
     * @param listener listener that will receive all the callbacks from the created
     *        {@link SpeechRecognizer}, this must not be null.
     */
    public void setRecognitionListener(RecognitionListener listener) {
        checkIsCalledFromMainThread();
        putMessage(Message.obtain(mHandler, MSG_CHANGE_LISTENER, listener));
    }

    /**
     * Starts listening for speech. Please note that
     * {@link #setRecognitionListener(RecognitionListener)} should be called beforehand, otherwise
     * no notifications will be received.
     *
     * @param recognizerIntent contains parameters for the recognition to be performed. The intent
     *        may also contain optional extras, see {@link RecognizerIntent}. If these values are
     *        not set explicitly, default values will be used by the recognizer.
     */
    public void startListening(final Intent recognizerIntent) {
        if (recognizerIntent == null) {
            throw new IllegalArgumentException("intent must not be null");
        }
        checkIsCalledFromMainThread();
        if (mConnection == null) { // first time connection
            mConnection = new Connection();
            
            Intent serviceIntent = new Intent(RecognitionService.SERVICE_INTERFACE);
            
            if (mServiceComponent == null) {
                String serviceComponent = Settings.Secure.getString(mContext.getContentResolver(),
                        Settings.Secure.VOICE_RECOGNITION_SERVICE);
                
                if (TextUtils.isEmpty(serviceComponent)) {
                    Log.e(TAG, "no selected voice recognition service");
                    mListener.onError(ERROR_CLIENT);
                    return;
                }
                
                serviceIntent.setComponent(ComponentName.unflattenFromString(serviceComponent));                
            } else {
                serviceIntent.setComponent(mServiceComponent);
            }
            
            if (!mContext.bindService(serviceIntent, mConnection, Context.BIND_AUTO_CREATE)) {
                Log.e(TAG, "bind to recognition service failed");
                mConnection = null;
                mService = null;
                mListener.onError(ERROR_CLIENT);
                return;
            }
        }
        putMessage(Message.obtain(mHandler, MSG_START, recognizerIntent));
    }

    /**
     * Stops listening for speech. Speech captured so far will be recognized as if the user had
     * stopped speaking at this point. Note that in the default case, this does not need to be
     * called, as the speech endpointer will automatically stop the recognizer listening when it
     * determines speech has completed. However, you can manipulate endpointer parameters directly
     * using the intent extras defined in {@link RecognizerIntent}, in which case you may sometimes
     * want to manually call this method to stop listening sooner. Please note that
     * {@link #setRecognitionListener(RecognitionListener)} should be called beforehand, otherwise
     * no notifications will be received.
     */
    public void stopListening() {
        checkIsCalledFromMainThread();
        putMessage(Message.obtain(mHandler, MSG_STOP));
    }

    /**
     * Cancels the speech recognition. Please note that
     * {@link #setRecognitionListener(RecognitionListener)} should be called beforehand, otherwise
     * no notifications will be received.
     */
    public void cancel() {
        checkIsCalledFromMainThread();
        putMessage(Message.obtain(mHandler, MSG_CANCEL));
    }

    private static void checkIsCalledFromMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new RuntimeException(
                    "SpeechRecognizer should be used only from the application's main thread");
        }
    }

    private void putMessage(Message msg) {
        if (mService == null) {
            mPendingTasks.offer(msg);
        } else {
            mHandler.sendMessage(msg);
        }
    }

    /** sends the actual message to the service */
    private void handleStartListening(Intent recognizerIntent) {
        if (!checkOpenConnection()) {
            return;
        }
        try {
            mService.startListening(recognizerIntent, mListener, mContext.getOpPackageName(),
                    mContext.getAttributionTag());
            if (DBG) Log.d(TAG, "service start listening command succeded");
        } catch (final RemoteException e) {
            Log.e(TAG, "startListening() failed", e);
            mListener.onError(ERROR_CLIENT);
        }
    }

    /** sends the actual message to the service */
    private void handleStopMessage() {
        if (!checkOpenConnection()) {
            return;
        }
        try {
            mService.stopListening(mListener, mContext.getOpPackageName(),
                    mContext.getAttributionTag());
            if (DBG) Log.d(TAG, "service stop listening command succeded");
        } catch (final RemoteException e) {
            Log.e(TAG, "stopListening() failed", e);
            mListener.onError(ERROR_CLIENT);
        }
    }

    /** sends the actual message to the service */
    private void handleCancelMessage() {
        if (!checkOpenConnection()) {
            return;
        }
        try {
            mService.cancel(mListener, mContext.getOpPackageName(), mContext.getAttributionTag());
            if (DBG) Log.d(TAG, "service cancel command succeded");
        } catch (final RemoteException e) {
            Log.e(TAG, "cancel() failed", e);
            mListener.onError(ERROR_CLIENT);
        }
    }
    
    private boolean checkOpenConnection() {
        if (mService != null) {
            return true;
        }
        mListener.onError(ERROR_CLIENT);
        Log.e(TAG, "not connected to the recognition service");
        return false;
    }

    /** changes the listener */
    private void handleChangeListener(RecognitionListener listener) {
        if (DBG) Log.d(TAG, "handleChangeListener, listener=" + listener);
        mListener.mInternalListener = listener;
    }

    /**
     * Destroys the {@code SpeechRecognizer} object.
     */
    public void destroy() {
        if (mService != null) {
            try {
                mService.cancel(mListener, mContext.getOpPackageName(),
                        mContext.getAttributionTag());
            } catch (final RemoteException e) {
                // Not important
            }
        }

        if (mConnection != null) {
            mContext.unbindService(mConnection);
        }
        mPendingTasks.clear();
        mService = null;
        mConnection = null;
        mListener.mInternalListener = null;
    }

    /**
     * Internal wrapper of IRecognitionListener which will propagate the results to
     * RecognitionListener
     */
    private static class InternalListener extends IRecognitionListener.Stub {
        private RecognitionListener mInternalListener;

        private final static int MSG_BEGINNING_OF_SPEECH = 1;
        private final static int MSG_BUFFER_RECEIVED = 2;
        private final static int MSG_END_OF_SPEECH = 3;
        private final static int MSG_ERROR = 4;
        private final static int MSG_READY_FOR_SPEECH = 5;
        private final static int MSG_RESULTS = 6;
        private final static int MSG_PARTIAL_RESULTS = 7;
        private final static int MSG_RMS_CHANGED = 8;
        private final static int MSG_ON_EVENT = 9;

        private final Handler mInternalHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                if (mInternalListener == null) {
                    return;
                }
                switch (msg.what) {
                    case MSG_BEGINNING_OF_SPEECH:
                        mInternalListener.onBeginningOfSpeech();
                        break;
                    case MSG_BUFFER_RECEIVED:
                        mInternalListener.onBufferReceived((byte[]) msg.obj);
                        break;
                    case MSG_END_OF_SPEECH:
                        mInternalListener.onEndOfSpeech();
                        break;
                    case MSG_ERROR:
                        mInternalListener.onError((Integer) msg.obj);
                        break;
                    case MSG_READY_FOR_SPEECH:
                        mInternalListener.onReadyForSpeech((Bundle) msg.obj);
                        break;
                    case MSG_RESULTS:
                        mInternalListener.onResults((Bundle) msg.obj);
                        break;
                    case MSG_PARTIAL_RESULTS:
                        mInternalListener.onPartialResults((Bundle) msg.obj);
                        break;
                    case MSG_RMS_CHANGED:
                        mInternalListener.onRmsChanged((Float) msg.obj);
                        break;
                    case MSG_ON_EVENT:
                        mInternalListener.onEvent(msg.arg1, (Bundle) msg.obj);
                        break;
                }
            }
        };

        public void onBeginningOfSpeech() {
            Message.obtain(mInternalHandler, MSG_BEGINNING_OF_SPEECH).sendToTarget();
        }

        public void onBufferReceived(final byte[] buffer) {
            Message.obtain(mInternalHandler, MSG_BUFFER_RECEIVED, buffer).sendToTarget();
        }

        public void onEndOfSpeech() {
            Message.obtain(mInternalHandler, MSG_END_OF_SPEECH).sendToTarget();
        }

        public void onError(final int error) {
            Message.obtain(mInternalHandler, MSG_ERROR, error).sendToTarget();
        }

        public void onReadyForSpeech(final Bundle noiseParams) {
            Message.obtain(mInternalHandler, MSG_READY_FOR_SPEECH, noiseParams).sendToTarget();
        }

        public void onResults(final Bundle results) {
            Message.obtain(mInternalHandler, MSG_RESULTS, results).sendToTarget();
        }

        public void onPartialResults(final Bundle results) {
            Message.obtain(mInternalHandler, MSG_PARTIAL_RESULTS, results).sendToTarget();
        }

        public void onRmsChanged(final float rmsdB) {
            Message.obtain(mInternalHandler, MSG_RMS_CHANGED, rmsdB).sendToTarget();
        }

        public void onEvent(final int eventType, final Bundle params) {
            Message.obtain(mInternalHandler, MSG_ON_EVENT, eventType, eventType, params)
                    .sendToTarget();
        }
    }
}
