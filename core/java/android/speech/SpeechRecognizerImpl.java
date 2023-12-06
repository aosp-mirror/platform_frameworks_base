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
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @hide
 */
class SpeechRecognizerImpl extends SpeechRecognizer {
    /** DEBUG value to enable verbose debug prints */
    private static final boolean DBG = false;

    /** Log messages identifier */
    private static final String TAG = "SpeechRecognizer";

    /** action codes */
    private static final int MSG_START = 1;
    private static final int MSG_STOP = 2;
    private static final int MSG_CANCEL = 3;
    private static final int MSG_CHANGE_LISTENER = 4;
    private static final int MSG_SET_TEMPORARY_ON_DEVICE_COMPONENT = 5;
    private static final int MSG_CHECK_RECOGNITION_SUPPORT = 6;
    private static final int MSG_TRIGGER_MODEL_DOWNLOAD = 7;
    private static final int MSG_DESTROY = 8;

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
    private final Handler mHandler = new Handler(Looper.getMainLooper()) {

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_START -> handleStartListening((Intent) msg.obj);
                case MSG_STOP -> handleStopMessage();
                case MSG_CANCEL -> handleCancelMessage();
                case MSG_CHANGE_LISTENER -> handleChangeListener((RecognitionListener) msg.obj);
                case MSG_SET_TEMPORARY_ON_DEVICE_COMPONENT ->
                        handleSetTemporaryComponent((ComponentName) msg.obj);
                case MSG_CHECK_RECOGNITION_SUPPORT -> {
                    CheckRecognitionSupportArgs args = (CheckRecognitionSupportArgs) msg.obj;
                    handleCheckRecognitionSupport(
                            args.mIntent, args.mCallbackExecutor, args.mCallback);
                }
                case MSG_TRIGGER_MODEL_DOWNLOAD -> {
                    ModelDownloadListenerArgs modelDownloadListenerArgs =
                            (ModelDownloadListenerArgs) msg.obj;
                    handleTriggerModelDownload(
                            modelDownloadListenerArgs.mIntent,
                            modelDownloadListenerArgs.mExecutor,
                            modelDownloadListenerArgs.mModelDownloadListener);
                }
                case MSG_DESTROY -> handleDestroy();
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
    /* package */ SpeechRecognizerImpl(
            final Context context,
            final ComponentName serviceComponent) {
        this(context, serviceComponent, false);
    }

    /**
     * The right way to create a {@code SpeechRecognizer} is by using
     * {@link #createOnDeviceSpeechRecognizer} static factory method
     */
    /* package */ SpeechRecognizerImpl(final Context context, boolean onDevice) {
        this(context, null, onDevice);
    }

    private SpeechRecognizerImpl(
            final Context context,
            final ComponentName serviceComponent,
            final boolean onDevice) {
        mContext = context;
        mServiceComponent = serviceComponent;
        mOnDevice = onDevice;
    }

    @NonNull
    @MainThread
    /* package */ static SpeechRecognizerImpl lenientlyCreateOnDeviceSpeechRecognizer(
            @NonNull final Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        checkIsCalledFromMainThread();
        return new SpeechRecognizerImpl(context, /* onDevice */ true);
    }

    @Override
    @MainThread
    public void setRecognitionListener(RecognitionListener listener) {
        checkIsCalledFromMainThread();
        if (mListener.mInternalListener == null) {
            // This shortcut is needed because otherwise, if there's an error connecting, it never
            // gets delivered. I.e., the onSuccess callback set up in connectToSystemService does
            // not get called, MSG_CHANGE_LISTENER does not get executed, so the onError in the same
            // place does not get forwarded anywhere.
            // Thread-wise, this is safe as both this method and the handler are on the UI thread.
            handleChangeListener(listener);
        } else {
            putMessage(Message.obtain(mHandler, MSG_CHANGE_LISTENER, listener));
        }
    }

    @Override
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

    @Override
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

    @Override
    @MainThread
    public void cancel() {
        checkIsCalledFromMainThread();
        putMessage(Message.obtain(mHandler, MSG_CANCEL));
    }

    @Override
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

    @Override
    public void triggerModelDownload(@NonNull Intent recognizerIntent) {
        Objects.requireNonNull(recognizerIntent, "intent must not be null");
        if (DBG) {
            Slog.i(TAG, "#triggerModelDownload without a listener called");
            if (mService == null) {
                Slog.i(TAG, "Connection is not established yet");
            }
        }
        if (mService == null) {
            // First time connection: first establish a connection, then dispatch.
            connectToSystemService();
        }
        putMessage(Message.obtain(
                mHandler, MSG_TRIGGER_MODEL_DOWNLOAD,
                new ModelDownloadListenerArgs(recognizerIntent, null, null)));
    }

    @Override
    public void triggerModelDownload(
            @NonNull Intent recognizerIntent,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull ModelDownloadListener listener) {
        Objects.requireNonNull(recognizerIntent, "intent must not be null");
        if (DBG) {
            Slog.i(TAG, "#triggerModelDownload with a listener called");
            if (mService == null) {
                Slog.i(TAG, "Connection is not established yet");
            }
        }
        if (mService == null) {
            // First time connection: first establish a connection, then dispatch.
            connectToSystemService();
        }
        putMessage(Message.obtain(
                mHandler, MSG_TRIGGER_MODEL_DOWNLOAD,
                new ModelDownloadListenerArgs(recognizerIntent, executor, listener)));
    }

    @Override
    @RequiresPermission(Manifest.permission.MANAGE_SPEECH_RECOGNITION)
    public void setTemporaryOnDeviceRecognizer(@Nullable ComponentName componentName) {
        mHandler.sendMessage(
                Message.obtain(mHandler, MSG_SET_TEMPORARY_ON_DEVICE_COMPONENT, componentName));
    }

    /* package */ static void checkIsCalledFromMainThread() {
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
        } catch (final Exception e) {
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
        } catch (final Exception e) {
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
        } catch (final Exception e) {
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
        if (!maybeInitializeManagerService() || !checkOpenConnection()) {
            return;
        }
        try {
            mService.checkRecognitionSupport(
                    recognizerIntent,
                    mContext.getAttributionSource(),
                    new InternalSupportCallback(callbackExecutor, recognitionSupportCallback));
            if (DBG) Log.d(TAG, "service support command succeeded");
        } catch (final Exception e) {
            Log.e(TAG, "checkRecognitionSupport() failed", e);
            callbackExecutor.execute(() -> recognitionSupportCallback.onError(ERROR_CLIENT));
        }
    }

    private void handleTriggerModelDownload(
            Intent recognizerIntent,
            @Nullable Executor callbackExecutor,
            @Nullable ModelDownloadListener modelDownloadListener) {
        if (!maybeInitializeManagerService() || !checkOpenConnection()) {
            return;
        }

        if (modelDownloadListener == null) {
            // Trigger model download without a listener.
            try {
                mService.triggerModelDownload(
                        recognizerIntent, mContext.getAttributionSource(), null);
                if (DBG) Log.d(TAG, "triggerModelDownload() without a listener");
            } catch (final Exception e) {
                Log.e(TAG, "triggerModelDownload() without a listener failed", e);
                mListener.onError(ERROR_CLIENT);
            }
        } else {
            // Trigger model download with a listener.
            try {
                mService.triggerModelDownload(
                        recognizerIntent, mContext.getAttributionSource(),
                        new InternalModelDownloadListener(callbackExecutor, modelDownloadListener));
                if (DBG) Log.d(TAG, "triggerModelDownload() with a listener");
            } catch (final Exception e) {
                Log.e(TAG, "triggerModelDownload() with a listener failed", e);
                callbackExecutor.execute(() -> modelDownloadListener.onError(ERROR_CLIENT));
            }
        }
    }

    private boolean checkOpenConnection() {
        if (mService != null && mService.asBinder().isBinderAlive()) {
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

    @Override
    public void destroy() {
        putMessage(mHandler.obtainMessage(MSG_DESTROY));
    }

    private void handleDestroy() {
        if (mService != null) {
            try {
                mService.cancel(mListener, /*isShutdown*/ true);
            } catch (final Exception e) {
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

        IBinder service = ServiceManager.getService(Context.SPEECH_RECOGNITION_SERVICE);
        if (service == null && mOnDevice) {
            service = (IBinder) mContext.getSystemService(Context.SPEECH_RECOGNITION_SERVICE);
        }
        mManagerService = IRecognitionServiceManager.Stub.asInterface(service);

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
     * </ol>
     */
    @SuppressWarnings("NonUserGetterCalled")
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

    private static class ModelDownloadListenerArgs {
        final Intent mIntent;
        final Executor mExecutor;
        final ModelDownloadListener mModelDownloadListener;

        private ModelDownloadListenerArgs(Intent intent, Executor executor,
                ModelDownloadListener modelDownloadListener) {
            mIntent = intent;
            mExecutor = executor;
            mModelDownloadListener = modelDownloadListener;
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
        private static final int MSG_LANGUAGE_DETECTION = 12;

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
                    case MSG_LANGUAGE_DETECTION:
                        mInternalListener.onLanguageDetection((Bundle) msg.obj);
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

        public void onLanguageDetection(final Bundle results) {
            Message.obtain(mInternalHandler, MSG_LANGUAGE_DETECTION, results).sendToTarget();
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

    private static class InternalModelDownloadListener extends IModelDownloadListener.Stub {
        private final Executor mExecutor;
        private final ModelDownloadListener mModelDownloadListener;

        private InternalModelDownloadListener(
                Executor executor,
                @NonNull ModelDownloadListener modelDownloadListener) {
            mExecutor = executor;
            mModelDownloadListener = modelDownloadListener;
        }

        @Override
        public void onProgress(int completedPercent) throws RemoteException {
            mExecutor.execute(() -> mModelDownloadListener.onProgress(completedPercent));
        }

        @Override
        public void onSuccess() throws RemoteException {
            mExecutor.execute(() -> mModelDownloadListener.onSuccess());
        }

        @Override
        public void onScheduled() throws RemoteException {
            mExecutor.execute(() -> mModelDownloadListener.onScheduled());
        }

        @Override
        public void onError(int error) throws RemoteException {
            mExecutor.execute(() -> mModelDownloadListener.onError(error));
        }
    }
}
