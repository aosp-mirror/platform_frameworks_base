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
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.List;

/**
 * This class provides access to the speech recognition service. This service allows access to the
 * speech recognizer. Do not instantiate this class directly, instead, call
 * {@link RecognitionManager#createRecognitionManager(Context, RecognitionListener, Intent)}. This
 * class is not thread safe and must be synchronized externally if accessed from multiple threads.
 */
public class RecognitionManager {
    /** DEBUG value to enable verbose debug prints */
    private final static boolean DBG = false;

    /** Log messages identifier */
    private static final String TAG = "RecognitionManager";

    /**
     * Used to retrieve an {@code ArrayList&lt;String&gt;} from the {@link Bundle} passed to the
     * {@link RecognitionListener#onResults(Bundle)} and
     * {@link RecognitionListener#onPartialResults(Bundle)} methods. These strings are the possible
     * recognition results, where the first element is the most likely candidate.
     */
    public static final String RECOGNITION_RESULTS_STRING_ARRAY =
            "recognition_results_string_array";

    /** The actual RecognitionService endpoint */
    private IRecognitionService mService;

    /** The connection to the actual service */
    private Connection mConnection;

    /** Context with which the manager was created */
    private final Context mContext;

    /** Listener that will receive all the callbacks */
    private final RecognitionListener mListener;

    /** Helper class wrapping the IRecognitionListener */
    private final InternalRecognitionListener mInternalRecognitionListener;

    /** Network operation timed out. */
    public static final int NETWORK_TIMEOUT_ERROR = 1;

    /** Other network related errors. */
    public static final int NETWORK_ERROR = 2;

    /** Audio recording error. */
    public static final int AUDIO_ERROR = 3;

    /** Server sends error status. */
    public static final int SERVER_ERROR = 4;

    /** Other client side errors. */
    public static final int CLIENT_ERROR = 5;

    /** No speech input */
    public static final int SPEECH_TIMEOUT_ERROR = 6;

    /** No recognition result matched. */
    public static final int NO_MATCH_ERROR = 7;

    /** RecognitionService busy. */
    public static final int SERVER_BUSY_ERROR = 8;

    /**
     * RecognitionManager was not initialized yet, most probably because
     * {@link RecognitionListener#onInit()} was not called yet.
     */
    public static final int MANAGER_NOT_INITIALIZED_ERROR = 9;

    /**
     * The right way to create a RecognitionManager is by using
     * {@link #createRecognitionManager} static factory method
     */
    private RecognitionManager(final RecognitionListener listener, final Context context) {
        mInternalRecognitionListener = new InternalRecognitionListener();
        mContext = context;
        mListener = listener;
    }

    /**
     * Basic ServiceConnection which just records mService variable.
     */
    private class Connection implements ServiceConnection {

        public synchronized void onServiceConnected(final ComponentName name,
                final IBinder service) {
            mService = IRecognitionService.Stub.asInterface(service);
            if (mListener != null) {
                mListener.onInit();
            }
            if (DBG) Log.d(TAG, "onServiceConnected - Success");
        }

        public void onServiceDisconnected(final ComponentName name) {
            mService = null;
            mConnection = null;
            if (DBG) Log.d(TAG, "onServiceDisconnected - Success");
        }
    }

    /**
     * Checks whether a speech recognition service is available on the system. If this method
     * returns {@code false},
     * {@link RecognitionManager#createRecognitionManager(Context, RecognitionListener, Intent)}
     * will fail.
     * 
     * @param context with which RecognitionManager will be created
     * @return {@code true} if recognition is available, {@code false} otherwise
     */
    public static boolean isRecognitionAvailable(final Context context) {
        final List<ResolveInfo> list = context.getPackageManager().queryIntentServices(
                new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0);
        return list != null && list.size() != 0;
    }

    /**
     * Factory method to create a new RecognitionManager
     * 
     * @param context in which to create RecognitionManager
     * @param listener that will receive all the callbacks from the created
     *        {@link RecognitionManager}
     * @param recognizerIntent contains initialization parameters for the speech recognizer. The
     *        intent action should be {@link RecognizerIntent#ACTION_RECOGNIZE_SPEECH}. Future
     *        versions of this API may add startup parameters for speech recognizer.
     * @return null if a recognition service implementation is not installed or if speech
     *         recognition is not supported by the device, otherwise a new RecognitionManager is
     *         returned. The created RecognitionManager can only be used after the
     *         {@link RecognitionListener#onInit()} method has been called.
     */
    public static RecognitionManager createRecognitionManager(final Context context,
            final RecognitionListener listener, final Intent recognizerIntent) {
        if (context == null || recognizerIntent == null) {
            throw new IllegalArgumentException(
                    "Context and recognizerListener argument cannot be null)");
        }
        RecognitionManager manager = new RecognitionManager(listener, context);
        manager.mConnection = manager.new Connection();
        if (!context.bindService(recognizerIntent, manager.mConnection, Context.BIND_AUTO_CREATE)) {
            Log.e(TAG, "bind to recognition service failed");
            listener.onError(CLIENT_ERROR);
            return null;
        }
        return manager;
    }

    /**
     * Checks whether the service is connected
     *
     * @param functionName from which the call originated
     * @return {@code true} if the service was successfully initialized, {@code false} otherwise
     */
    private boolean connectToService(final String functionName) {
        if (mService != null) {
            return true;
        }
        if (mConnection == null) {
            if (DBG) Log.d(TAG, "restarting connection to the recognition service");
            mConnection = new Connection();
            mContext.bindService(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), mConnection,
                    Context.BIND_AUTO_CREATE);
        }
        mInternalRecognitionListener.onError(MANAGER_NOT_INITIALIZED_ERROR);
        Log.e(TAG, functionName + " was called before service connection was initialized");
        return false;
    }

    /**
     * Starts listening for speech.
     * 
     * @param recognizerIntent contains parameters for the recognition to be performed. The intent
     *        action should be {@link RecognizerIntent#ACTION_RECOGNIZE_SPEECH}. The intent may also
     *        contain optional extras, see {@link RecognizerIntent}. If these values are not set
     *        explicitly, default values will be used by the recognizer.
     */
    public void startListening(Intent recognizerIntent) {
        if (recognizerIntent == null) {
            throw new IllegalArgumentException("recognizerIntent argument cannot be null");
        }
        if (!connectToService("startListening")) {
            return; // service is not connected yet, reconnect in progress
        }
        try {
            mService.startListening(recognizerIntent, mInternalRecognitionListener);
            if (DBG) Log.d(TAG, "service start listening command succeded");
        } catch (final RemoteException e) {
            Log.e(TAG, "startListening() failed", e);
            mInternalRecognitionListener.onError(CLIENT_ERROR);
        }
    }

    /**
     * Stops listening for speech. Speech captured so far will be recognized as if the user had
     * stopped speaking at this point. Note that in the default case, this does not need to be
     * called, as the speech endpointer will automatically stop the recognizer listening when it
     * determines speech has completed. However, you can manipulate endpointer parameters directly
     * using the intent extras defined in {@link RecognizerIntent}, in which case you may sometimes
     * want to manually call this method to stop listening sooner.
     */
    public void stopListening() {
        if (mService == null) {
            return; // service is not connected, but no need to reconnect at this point
        }
        try {
            mService.stopListening();
            if (DBG) Log.d(TAG, "service stop listening command succeded");
        } catch (final RemoteException e) {
            Log.e(TAG, "stopListening() failed", e);
            mInternalRecognitionListener.onError(CLIENT_ERROR);
        }
    }

    /**
     * Cancels the speech recognition.
     */
    public void cancel() {
        if (mService == null) {
            return; // service is not connected, but no need to reconnect at this point
        }
        try {
            mService.cancel();
            if (DBG) Log.d(TAG, "service cancel command succeded");
        } catch (final RemoteException e) {
            Log.e(TAG, "cancel() failed", e);
            mInternalRecognitionListener.onError(CLIENT_ERROR);
        }
    }

    /**
     * Destroys the RecognitionManager object. Note that after calling this method all method calls
     * on this object will fail, triggering {@link RecognitionListener#onError}.
     */
    public void destroy() {
        if (mConnection != null) {
            mContext.unbindService(mConnection);
        }
        mService = null;
    }

    /**
     * Internal wrapper of IRecognitionListener which will propagate the results
     * to RecognitionListener
     */
    private class InternalRecognitionListener extends IRecognitionListener.Stub {

        public void onBeginningOfSpeech() {
            if (mListener != null) {
                mListener.onBeginningOfSpeech();
            }
        }

        public void onBufferReceived(final byte[] buffer) {
            if (mListener != null) {
                mListener.onBufferReceived(buffer);
            }
        }

        public void onEndOfSpeech() {
            if (mListener != null) {
                mListener.onEndOfSpeech();
            }
        }

        public void onError(final int error) {
            if (mListener != null) {
                mListener.onError(error);
            }
        }

        public void onReadyForSpeech(final Bundle noiseParams) {
            if (mListener != null) {
                mListener.onReadyForSpeech(noiseParams);
            }
        }

        public void onResults(final Bundle results) {
            if (mListener != null) {
                mListener.onResults(results);
            }
        }

        public void onPartialResults(final Bundle results) {
            if (mListener != null) {
                mListener.onPartialResults(results);
            }
        }

        public void onRmsChanged(final float rmsdB) {
            if (mListener != null) {
                mListener.onRmsChanged(rmsdB);
            }
        }
    }
}
