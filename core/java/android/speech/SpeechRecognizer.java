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

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.TestApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;

import com.android.internal.R;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * This class provides access to the speech recognition service. This service allows access to the
 * speech recognizer. Do not instantiate this class directly, instead, call
 * {@link SpeechRecognizer#createSpeechRecognizer(Context)}, or
 * {@link SpeechRecognizer#createOnDeviceSpeechRecognizer(Context)}. This class's methods must be
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
    private static final boolean DBG = false;

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

    /**
     * The reason speech recognition failed.
     *
     * @hide
     */
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"ERROR_"}, value = {
            ERROR_NETWORK_TIMEOUT,
            ERROR_NETWORK,
            ERROR_AUDIO,
            ERROR_SERVER,
            ERROR_CLIENT,
            ERROR_SPEECH_TIMEOUT,
            ERROR_NO_MATCH,
            ERROR_RECOGNIZER_BUSY,
            ERROR_INSUFFICIENT_PERMISSIONS,
            ERROR_TOO_MANY_REQUESTS,
            ERROR_SERVER_DISCONNECTED,
            ERROR_LANGUAGE_NOT_SUPPORTED,
            ERROR_LANGUAGE_UNAVAILABLE,
            ERROR_CANNOT_CHECK_SUPPORT,
    })
    public @interface RecognitionError {}

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

    /** Too many requests from the same client. */
    public static final int ERROR_TOO_MANY_REQUESTS = 10;

    /** Server has been disconnected, e.g. because the app has crashed. */
    public static final int ERROR_SERVER_DISCONNECTED = 11;

    /** Requested language is not available to be used with the current recognizer. */
    public static final int ERROR_LANGUAGE_NOT_SUPPORTED = 12;

    /** Requested language is supported, but not available currently (e.g. not downloaded yet). */
    public static final int ERROR_LANGUAGE_UNAVAILABLE = 13;

    /** The service does not allow to check for support. */
    public static final int ERROR_CANNOT_CHECK_SUPPORT = 14;

    /** action codes */
    private static final int MSG_START = 1;
    private static final int MSG_STOP = 2;
    private static final int MSG_CANCEL = 3;
    private static final int MSG_CHANGE_LISTENER = 4;
    private static final int MSG_SET_TEMPORARY_ON_DEVICE_COMPONENT = 5;
    private static final int MSG_CHECK_RECOGNITION_SUPPORT = 6;
    private static final int MSG_TRIGGER_MODEL_DOWNLOAD = 7;

    /** The actual RecognitionService endpoint */
    private IRecognitionService mService;

    /** Context with which the manager was created */
    private final Context mContext;

    /** Component to direct service intent to */
    private final ComponentName mServiceComponent;

    /** Whether to use on-device speech recognizer. */
    private final boolean mOnDevice;

    private IRecognitionServiceManager mManagerService;

    /** Handler that will execute the main tasks */
    private Handler mHandler = new Handler(Looper.getMainLooper()) {

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
                case MSG_SET_TEMPORARY_ON_DEVICE_COMPONENT:
                    handleSetTemporaryComponent((ComponentName) msg.obj);
                    break;
                case MSG_CHECK_RECOGNITION_SUPPORT:
                    CheckRecognitionSupportArgs args = (CheckRecognitionSupportArgs) msg.obj;
                    handleCheckRecognitionSupport(
                            args.mIntent, args.mCallbackExecutor, args.mCallback);
                    break;
                case MSG_TRIGGER_MODEL_DOWNLOAD:
                    handleTriggerModelDownload((Intent) msg.obj);
                    break;
            }
        }
    };

    /**
     * Temporary queue, saving the messages until the connection will be established, afterwards,
     * only mHandler will receive the messages
     */
    private final Queue<Message> mPendingTasks = new LinkedBlockingQueue<>();

    /** The Listener that will receive all the callbacks */
    private final InternalRecognitionListener mListener = new InternalRecognitionListener();

    private final IBinder mClientToken = new Binder();

    /**
     * The right way to create a {@code SpeechRecognizer} is by using
     * {@link #createSpeechRecognizer} static factory method
     */
    private SpeechRecognizer(final Context context, final ComponentName serviceComponent) {
        mContext = context;
        mServiceComponent = serviceComponent;
        mOnDevice = false;
    }

    /**
     * The right way to create a {@code SpeechRecognizer} is by using
     * {@link #createOnDeviceSpeechRecognizer} static factory method
     */
    private SpeechRecognizer(final Context context, boolean onDevice) {
        mContext = context;
        mServiceComponent = null;
        mOnDevice = onDevice;
    }

    /**
     * Checks whether a speech recognition service is available on the system. If this method
     * returns {@code false}, {@link SpeechRecognizer#createSpeechRecognizer(Context)} will
     * fail.
     * 
     * @param context with which {@code SpeechRecognizer} will be created
     * @return {@code true} if recognition is available, {@code false} otherwise
     */
    public static boolean isRecognitionAvailable(@NonNull final Context context) {
        // TODO(b/176578753): make sure this works well with system speech recognizers.
        final List<ResolveInfo> list = context.getPackageManager().queryIntentServices(
                new Intent(RecognitionService.SERVICE_INTERFACE), 0);
        return list != null && list.size() != 0;
    }

    /**
     * Checks whether an on-device speech recognition service is available on the system. If this
     * method returns {@code false},
     * {@link SpeechRecognizer#createOnDeviceSpeechRecognizer(Context)} will
     * fail.
     *
     * @param context with which on-device {@code SpeechRecognizer} will be created
     * @return {@code true} if on-device recognition is available, {@code false} otherwise
     */
    public static boolean isOnDeviceRecognitionAvailable(@NonNull final Context context) {
        ComponentName componentName =
                ComponentName.unflattenFromString(
                        context.getString(R.string.config_defaultOnDeviceSpeechRecognitionService));
        return componentName != null;
    }

    /**
     * Factory method to create a new {@code SpeechRecognizer}. Please note that
     * {@link #setRecognitionListener(RecognitionListener)} should be called before dispatching any
     * command to the created {@code SpeechRecognizer}, otherwise no notifications will be
     * received.
     *
     * <p>For apps targeting Android 11 (API level 30) interaction with a speech recognition
     * service requires <queries> element to be added to the manifest file:
     * <pre>{@code
     * <queries>
     *   <intent>
     *     <action
     *        android:name="android.speech.RecognitionService" />
     *   </intent>
     * </queries>
     * }</pre>
     *
     * @param context in which to create {@code SpeechRecognizer}
     * @return a new {@code SpeechRecognizer}
     */
    @MainThread
    public static SpeechRecognizer createSpeechRecognizer(final Context context) {
        return createSpeechRecognizer(context, null);
    }

    /**
     * Factory method to create a new {@code SpeechRecognizer}. Please note that
     * {@link #setRecognitionListener(RecognitionListener)} should be called before dispatching any
     * command to the created {@code SpeechRecognizer}, otherwise no notifications will be
     * received.
     * Use this version of the method to specify a specific service to direct this
     * {@link SpeechRecognizer} to.
     *
     * <p><strong>Important</strong>: before calling this method, please check via
     * {@link android.content.pm.PackageManager#queryIntentServices(Intent, int)} that {@code
     * serviceComponent} actually exists and provides
     * {@link RecognitionService#SERVICE_INTERFACE}. Normally you would not use this; call
     * {@link #createSpeechRecognizer(Context)} to use the system default recognition
     * service instead or {@link #createOnDeviceSpeechRecognizer(Context)} to use on-device
     * recognition.</p>
     *
     * <p>For apps targeting Android 11 (API level 30) interaction with a speech recognition
     * service requires <queries> element to be added to the manifest file:
     * <pre>{@code
     * <queries>
     *   <intent>
     *     <action
     *        android:name="android.speech.RecognitionService" />
     *   </intent>
     * </queries>
     * }</pre>
     *
     * @param context in which to create {@code SpeechRecognizer}
     * @param serviceComponent the {@link ComponentName} of a specific service to direct this
     *        {@code SpeechRecognizer} to
     * @return a new {@code SpeechRecognizer}
     */
    @MainThread
    public static SpeechRecognizer createSpeechRecognizer(final Context context,
            final ComponentName serviceComponent) {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        checkIsCalledFromMainThread();
        return new SpeechRecognizer(context, serviceComponent);
    }

    /**
     * Factory method to create a new {@code SpeechRecognizer}.
     *
     * <p>Please note that {@link #setRecognitionListener(RecognitionListener)} should be called
     * before dispatching any command to the created {@code SpeechRecognizer}, otherwise no
     * notifications will be received.
     *
     * @param context in which to create {@code SpeechRecognizer}
     * @return a new on-device {@code SpeechRecognizer}.
     * @throws UnsupportedOperationException iff {@link #isOnDeviceRecognitionAvailable(Context)}
     *                                       is false
     */
    @NonNull
    @MainThread
    public static SpeechRecognizer createOnDeviceSpeechRecognizer(@NonNull final Context context) {
        if (!isOnDeviceRecognitionAvailable(context)) {
            throw new UnsupportedOperationException("On-device recognition is not available");
        }
        return lenientlyCreateOnDeviceSpeechRecognizer(context);
    }

    /**
     * Helper method to create on-device SpeechRecognizer in tests even when the device does not
     * support on-device speech recognition.
     *
     * @hide
     */
    @TestApi
    @NonNull
    @MainThread
    public static SpeechRecognizer createOnDeviceTestingSpeechRecognizer(
            @NonNull final Context context) {
        return lenientlyCreateOnDeviceSpeechRecognizer(context);
    }

    @NonNull
    @MainThread
    private static SpeechRecognizer lenientlyCreateOnDeviceSpeechRecognizer(
            @NonNull final Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        checkIsCalledFromMainThread();
        return new SpeechRecognizer(context, /* onDevice */ true);
    }

    /**
     * Sets the listener that will receive all the callbacks. The previous unfinished commands will
     * be executed with the old listener, while any following command will be executed with the new
     * listener.
     * 
     * @param listener listener that will receive all the callbacks from the created
     *        {@link SpeechRecognizer}, this must not be null.
     */
    @MainThread
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
    @MainThread
    public void startListening(final Intent recognizerIntent) {
        if (recognizerIntent == null) {
            throw new IllegalArgumentException("intent must not be null");
        }
        checkIsCalledFromMainThread();

        if (DBG) {
            Slog.i(TAG, "#startListening called");
            if (mService == null) {
                Slog.i(TAG, "Connection is not established yet");
            }
        }

        if (mService == null) {
            // First time connection: first establish a connection, then dispatch #startListening.
            connectToSystemService();
        }
        putMessage(Message.obtain(mHandler, MSG_START, recognizerIntent));
    }

    /**
     * Stops listening for speech. Speech captured so far will be recognized as if the user had
     * stopped speaking at this point.
     *
     * <p>Note that in the default case, this does not need to be called, as the speech endpointer
     * will automatically stop the recognizer listening when it determines speech has completed.
     * However, you can manipulate endpointer parameters directly using the intent extras defined in
     * {@link RecognizerIntent}, in which case you may sometimes want to manually call this method
     * to stop listening sooner.
     *
     * <p>Upon invocation clients must wait until {@link RecognitionListener#onResults} or
     * {@link RecognitionListener#onError} are invoked before calling
     * {@link SpeechRecognizer#startListening} again. Otherwise such an attempt would be rejected by
     * recognition service.
     *
     * <p>Please note that
     * {@link #setRecognitionListener(RecognitionListener)} should be called beforehand, otherwise
     * no notifications will be received.
     */
    @MainThread
    public void stopListening() {
        checkIsCalledFromMainThread();

        if (DBG) {
            Slog.i(TAG, "#stopListening called");
            if (mService == null) {
                Slog.i(TAG, "Connection is not established yet");
            }
        }

        putMessage(Message.obtain(mHandler, MSG_STOP));
    }

    /**
     * Cancels the speech recognition. Please note that
     * {@link #setRecognitionListener(RecognitionListener)} should be called beforehand, otherwise
     * no notifications will be received.
     */
    @MainThread
    public void cancel() {
        checkIsCalledFromMainThread();
        putMessage(Message.obtain(mHandler, MSG_CANCEL));
    }

    /**
     * Checks whether {@code recognizerIntent} is supported by
     * {@link SpeechRecognizer#startListening(Intent)}.
     *
     * @param recognizerIntent contains parameters for the recognition to be performed. The intent
     *        may also contain optional extras. See {@link RecognizerIntent} for the list of
     *        supported extras, any unlisted extra might be ignored.
     * @param supportListener the listener on which to receive the support query results.
     */
    public void checkRecognitionSupport(
            @NonNull Intent recognizerIntent,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull RecognitionSupportCallback supportListener) {
        Objects.requireNonNull(recognizerIntent, "intent must not be null");
        Objects.requireNonNull(supportListener, "listener must not be null");

        if (DBG) {
            Slog.i(TAG, "#checkRecognitionSupport called");
            if (mService == null) {
                Slog.i(TAG, "Connection is not established yet");
            }
        }

        if (mService == null) {
            // First time connection: first establish a connection, then dispatch.
            connectToSystemService();
        }
        putMessage(Message.obtain(mHandler, MSG_CHECK_RECOGNITION_SUPPORT,
                new CheckRecognitionSupportArgs(recognizerIntent, executor, supportListener)));
    }

    /**
     * Attempts to download the support for the given {@code recognizerIntent}. This might trigger
     * user interaction to approve the download. Callers can verify the status of the request via
     * {@link #checkRecognitionSupport(Intent, Executor, RecognitionSupportCallback)}.
     *
     * @param recognizerIntent contains parameters for the recognition to be performed. The intent
     *        may also contain optional extras, see {@link RecognizerIntent}.
     */
    public void triggerModelDownload(@NonNull Intent recognizerIntent) {
        Objects.requireNonNull(recognizerIntent, "intent must not be null");
        if (DBG) {
            Slog.i(TAG, "#triggerModelDownload called");
            if (mService == null) {
                Slog.i(TAG, "Connection is not established yet");
            }
        }
        if (mService == null) {
            // First time connection: first establish a connection, then dispatch.
            connectToSystemService();
        }
        putMessage(Message.obtain(mHandler, MSG_TRIGGER_MODEL_DOWNLOAD, recognizerIntent));
    }

    /**
     * Sets a temporary component to power on-device speech recognizer.
     *
     * <p>This is only expected to be called in tests, system would reject calls from client apps.
     *
     * @param componentName name of the component to set temporary replace speech recognizer. {@code
     *        null} value resets the recognizer to default.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MANAGE_SPEECH_RECOGNITION)
    public void setTemporaryOnDeviceRecognizer(@Nullable ComponentName componentName) {
        mHandler.sendMessage(
                Message.obtain(mHandler, MSG_SET_TEMPORARY_ON_DEVICE_COMPONENT, componentName));
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
            mService.startListening(recognizerIntent, mListener, mContext.getAttributionSource());
            if (DBG) Log.d(TAG, "service start listening command succeeded");
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
            mService.stopListening(mListener);
            if (DBG) Log.d(TAG, "service stop listening command succeeded");
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
            mService.cancel(mListener, /*isShutdown*/ false);
            if (DBG) Log.d(TAG, "service cancel command succeeded");
        } catch (final RemoteException e) {
            Log.e(TAG, "cancel() failed", e);
            mListener.onError(ERROR_CLIENT);
        }
    }

    private void handleSetTemporaryComponent(ComponentName componentName) {
        if (DBG) {
            Log.d(TAG, "handleSetTemporaryComponent, componentName=" + componentName);
        }

        if (!maybeInitializeManagerService()) {
            return;
        }

        try {
            mManagerService.setTemporaryComponent(componentName);
        } catch (final RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    private void handleCheckRecognitionSupport(
            Intent recognizerIntent,
            Executor callbackExecutor,
            RecognitionSupportCallback recognitionSupportCallback) {
        if (!maybeInitializeManagerService()) {
            return;
        }
        try {
            mService.checkRecognitionSupport(
                    recognizerIntent,
                    new InternalSupportCallback(callbackExecutor, recognitionSupportCallback));
            if (DBG) Log.d(TAG, "service support command succeeded");
        } catch (final RemoteException e) {
            Log.e(TAG, "checkRecognitionSupport() failed", e);
            callbackExecutor.execute(() -> recognitionSupportCallback.onError(ERROR_CLIENT));
        }
    }

    private void handleTriggerModelDownload(Intent recognizerIntent) {
        if (!maybeInitializeManagerService()) {
            return;
        }
        try {
            mService.triggerModelDownload(recognizerIntent);
        } catch (final RemoteException e) {
            Log.e(TAG, "downloadModel() failed", e);
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

    /** Destroys the {@code SpeechRecognizer} object. */
    public void destroy() {
        if (mService != null) {
            try {
                mService.cancel(mListener, /*isShutdown*/ true);
            } catch (final RemoteException e) {
                // Not important
            }
        }

        mService = null;
        mPendingTasks.clear();
        mListener.mInternalListener = null;
    }

    /** Establishes a connection to system server proxy and initializes the session. */
    private void connectToSystemService() {
        if (!maybeInitializeManagerService()) {
            return;
        }

        ComponentName componentName = getSpeechRecognizerComponentName();

        if (!mOnDevice && componentName == null) {
            mListener.onError(ERROR_CLIENT);
            return;
        }

        try {
            mManagerService.createSession(
                    componentName,
                    mClientToken,
                    mOnDevice,
                    new IRecognitionServiceManagerCallback.Stub(){
                        @Override
                        public void onSuccess(IRecognitionService service) throws RemoteException {
                            if (DBG) {
                                Log.i(TAG, "Connected to speech recognition service");
                            }
                            mService = service;
                            while (!mPendingTasks.isEmpty()) {
                                mHandler.sendMessage(mPendingTasks.poll());
                            }
                        }

                        @Override
                        public void onError(int errorCode) throws RemoteException {
                            Log.e(TAG, "Bind to system recognition service failed with error "
                                    + errorCode);
                            mListener.onError(errorCode);
                        }
                    });
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    private synchronized boolean maybeInitializeManagerService() {
        if (DBG) {
            Log.i(TAG, "#maybeInitializeManagerService found = " + mManagerService);
        }
        if (mManagerService != null) {
            return true;
        }

        mManagerService = IRecognitionServiceManager.Stub.asInterface(
                ServiceManager.getService(Context.SPEECH_RECOGNITION_SERVICE));

        if (DBG) {
            Log.i(TAG, "#maybeInitializeManagerService instantiated =" + mManagerService);
        }
        if (mManagerService == null) {
            if (mListener != null) {
                mListener.onError(ERROR_CLIENT);
            }
            return false;
        }
        return true;
    }

    /**
     * Returns the component name to be used for establishing a connection, based on the parameters
     * used during initialization.
     *
     * <p>Note the 3 different scenarios:
     * <ol>
     *     <li>On-device speech recognizer which is determined by the manufacturer and not
     *     changeable by the user
     *     <li>Default user-selected speech recognizer as specified by
     *     {@code Settings.Secure.VOICE_RECOGNITION_SERVICE}
     *     <li>Custom speech recognizer supplied by the client.
     */
    private ComponentName getSpeechRecognizerComponentName() {
        if (mOnDevice) {
            return null;
        }

        if (mServiceComponent != null) {
            return mServiceComponent;
        }

        String serviceComponent = Settings.Secure.getString(mContext.getContentResolver(),
                Settings.Secure.VOICE_RECOGNITION_SERVICE);

        if (TextUtils.isEmpty(serviceComponent)) {
            Log.e(TAG, "no selected voice recognition service");
            mListener.onError(ERROR_CLIENT);
            return null;
        }

        return ComponentName.unflattenFromString(serviceComponent);
    }

    private static class CheckRecognitionSupportArgs {
        final Intent mIntent;
        final Executor mCallbackExecutor;
        final RecognitionSupportCallback mCallback;

        private CheckRecognitionSupportArgs(
                Intent intent,
                Executor callbackExecutor,
                RecognitionSupportCallback callback) {
            mIntent = intent;
            mCallbackExecutor = callbackExecutor;
            mCallback = callback;
        }
    }

    /**
     * Internal wrapper of IRecognitionListener which will propagate the results to
     * RecognitionListener
     */
    private static class InternalRecognitionListener extends IRecognitionListener.Stub {
        private RecognitionListener mInternalListener;

        private static final int MSG_BEGINNING_OF_SPEECH = 1;
        private static final int MSG_BUFFER_RECEIVED = 2;
        private static final int MSG_END_OF_SPEECH = 3;
        private static final int MSG_ERROR = 4;
        private static final int MSG_READY_FOR_SPEECH = 5;
        private static final int MSG_RESULTS = 6;
        private static final int MSG_PARTIAL_RESULTS = 7;
        private static final int MSG_RMS_CHANGED = 8;
        private static final int MSG_ON_EVENT = 9;
        private static final int MSG_SEGMENT_RESULTS = 10;
        private static final int MSG_SEGMENT_END_SESSION = 11;

        private final Handler mInternalHandler = new Handler(Looper.getMainLooper()) {
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
                    case MSG_SEGMENT_RESULTS:
                        mInternalListener.onSegmentResults((Bundle) msg.obj);
                        break;
                    case MSG_SEGMENT_END_SESSION:
                        mInternalListener.onEndOfSegmentedSession();
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

        public void onSegmentResults(final Bundle bundle) {
            Message.obtain(mInternalHandler, MSG_SEGMENT_RESULTS, bundle).sendToTarget();
        }

        public void onEndOfSegmentedSession() {
            Message.obtain(mInternalHandler, MSG_SEGMENT_END_SESSION).sendToTarget();
        }

        public void onEvent(final int eventType, final Bundle params) {
            Message.obtain(mInternalHandler, MSG_ON_EVENT, eventType, eventType, params)
                    .sendToTarget();
        }
    }

    private static class InternalSupportCallback extends IRecognitionSupportCallback.Stub {
        private final Executor mExecutor;
        private final RecognitionSupportCallback mCallback;

        private InternalSupportCallback(Executor executor, RecognitionSupportCallback callback) {
            this.mExecutor = executor;
            this.mCallback = callback;
        }

        @Override
        public void onSupportResult(RecognitionSupport recognitionSupport) throws RemoteException {
            mExecutor.execute(() -> mCallback.onSupportResult(recognitionSupport));
        }

        @Override
        public void onError(int errorCode) throws RemoteException {
            mExecutor.execute(() -> mCallback.onError(errorCode));
        }
    }
}
