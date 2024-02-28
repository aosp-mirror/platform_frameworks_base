/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.speech;

import static com.android.internal.infra.AbstractRemoteService.PERMANENT_BOUND_TIMEOUT_MS;

import android.annotation.NonNull;
import android.content.AttributionSource;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.speech.IModelDownloadListener;
import android.speech.IRecognitionListener;
import android.speech.IRecognitionService;
import android.speech.IRecognitionSupportCallback;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.infra.ServiceConnector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class RemoteSpeechRecognitionService extends ServiceConnector.Impl<IRecognitionService> {
    private static final String TAG = RemoteSpeechRecognitionService.class.getSimpleName();
    private static final boolean DEBUG = false;

    /** Maximum number of clients connected to this object at the same time. */
    private static final int MAX_CONCURRENT_CLIENTS = 100;

    private final Object mLock = new Object();

    private boolean mConnected = false;

    /** Map containing info about connected clients indexed by the their listeners. */
    @GuardedBy("mLock")
    private final Map<IBinder, ClientState> mClients = new HashMap<>();

    /** List of pairs associating clients' binder tokens with corresponding listeners. */
    @GuardedBy("mLock")
    private final List<Pair<IBinder, IRecognitionListener>> mClientListeners = new ArrayList<>();

    private final int mCallingUid;
    private final ComponentName mComponentName;

    RemoteSpeechRecognitionService(
            Context context,
            ComponentName serviceName,
            int userId,
            int callingUid,
            boolean isPrivileged) {
        super(context,
                new Intent(RecognitionService.SERVICE_INTERFACE).setComponent(serviceName),
                getBindingFlags(isPrivileged),
                userId,
                IRecognitionService.Stub::asInterface);

        mCallingUid = callingUid;
        mComponentName = serviceName;

        if (DEBUG) {
            Slog.i(TAG, "Bound to recognition service at: " + serviceName.flattenToString() + ".");
        }
    }

    private static int getBindingFlags(boolean isPrivileged) {
        int bindingFlags = Context.BIND_AUTO_CREATE;
        if (isPrivileged) {
            bindingFlags |= Context.BIND_INCLUDE_CAPABILITIES | Context.BIND_FOREGROUND_SERVICE;
        }
        return bindingFlags;
    }

    ComponentName getServiceComponentName() {
        return mComponentName;
    }

    void startListening(Intent recognizerIntent, IRecognitionListener listener,
            @NonNull AttributionSource attributionSource) {
        if (DEBUG) {
            Slog.i(TAG, TextUtils.formatSimple("#startListening for package: "
                            + "%s, feature=%s, callingUid=%d.",
                    attributionSource.getPackageName(), attributionSource.getAttributionTag(),
                    mCallingUid));
        }

        if (listener == null) {
            Slog.w(TAG, "#startListening called with no preceding #setListening - ignoring.");
            return;
        }

        if (!mConnected) {
            tryRespondWithError(listener, SpeechRecognizer.ERROR_SERVER_DISCONNECTED);
            return;
        }

        synchronized (mLock) {
            ClientState clientState = mClients.get(listener.asBinder());

            if (clientState == null) {
                if (mClients.size() >= MAX_CONCURRENT_CLIENTS) {
                    tryRespondWithError(listener, SpeechRecognizer.ERROR_RECOGNIZER_BUSY);
                    Log.i(TAG, "#startListening received "
                            + "when the recognizer's capacity is full - ignoring this call.");
                    return;
                }

                final ClientState newClientState = new ClientState();
                newClientState.mDelegatingListener = new DelegatingListener(listener,
                        () -> {
                            // To be invoked in terminal calls on success.
                            if (DEBUG) {
                                Slog.i(TAG, "Recognition session completed successfully.");
                            }
                            synchronized (mLock) {
                                newClientState.mRecordingInProgress = false;
                            }
                        },
                        () -> {
                            // To be invoked in terminal calls on failure.
                            if (DEBUG) {
                                Slog.i(TAG, "Recognition session failed.");
                            }
                            removeClient(listener);
                        });

                if (DEBUG) {
                    Log.d(TAG, "Added a new client to the map.");
                }
                mClients.put(listener.asBinder(), newClientState);
                clientState = newClientState;
            } else {
                if (clientState.mRecordingInProgress) {
                    Slog.i(TAG, "#startListening called "
                            + "while listening is in progress for this caller.");
                    tryRespondWithError(listener, SpeechRecognizer.ERROR_CLIENT);
                    return;
                }
                clientState.mRecordingInProgress = true;
            }

            // Eager local evaluation to avoid reading a different or null value at closure runtime.
            final DelegatingListener listenerToStart = clientState.mDelegatingListener;
            run(service ->
                    service.startListening(
                            recognizerIntent,
                            listenerToStart,
                            attributionSource));
        }
    }

    void stopListening(IRecognitionListener listener) {
        if (DEBUG) {
            Slog.i(TAG, "#stopListening");
        }

        if (!mConnected) {
            tryRespondWithError(listener, SpeechRecognizer.ERROR_SERVER_DISCONNECTED);
            return;
        }

        synchronized (mLock) {
            ClientState clientState = mClients.get(listener.asBinder());

            if (clientState == null) {
                Slog.w(TAG, "#stopListening called with no preceding #startListening - ignoring.");
                tryRespondWithError(listener, SpeechRecognizer.ERROR_CLIENT);
                return;
            }
            if (!clientState.mRecordingInProgress) {
                tryRespondWithError(listener, SpeechRecognizer.ERROR_CLIENT);
                Slog.i(TAG, "#stopListening called while listening isn't in progress - ignoring.");
                return;
            }
            clientState.mRecordingInProgress = false;

            // Eager local evaluation to avoid reading a different or null value at closure runtime.
            final DelegatingListener listenerToStop = clientState.mDelegatingListener;
            run(service -> service.stopListening(listenerToStop));
        }
    }

    void cancel(IRecognitionListener listener, boolean isShutdown) {
        if (DEBUG) {
            Slog.i(TAG, "#cancel");
        }

        if (!mConnected) {
            tryRespondWithError(listener, SpeechRecognizer.ERROR_SERVER_DISCONNECTED);
        }

        synchronized (mLock) {
            ClientState clientState = mClients.get(listener.asBinder());

            if (clientState != null) {
                clientState.mRecordingInProgress = false;
                // Temporary reference to allow for resetting mDelegatingListener to null.
                final IRecognitionListener delegatingListener = clientState.mDelegatingListener;
                run(service -> service.cancel(delegatingListener, isShutdown));
            }

            // If shutdown, remove the client info from the map. Unbind if that was the last client.
            if (isShutdown) {
                removeClient(listener);
                if (mClients.isEmpty()) {
                    if (DEBUG) {
                        Slog.d(TAG, "Unbinding from the recognition service.");
                    }
                    run(service -> unbind());
                }
            }
        }
    }

    void checkRecognitionSupport(
            Intent recognizerIntent,
            AttributionSource attributionSource,
            IRecognitionSupportCallback callback) {

        if (!mConnected) {
            try {
                callback.onError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to report the connection broke to the caller.", e);
                e.printStackTrace();
            }
            return;
        }
        run(service ->
                service.checkRecognitionSupport(recognizerIntent, attributionSource, callback));
    }

    void triggerModelDownload(
            Intent recognizerIntent,
            AttributionSource attributionSource,
            IModelDownloadListener listener) {
        if (!mConnected) {
            try {
                listener.onError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED);
            } catch (RemoteException e) {
                Slog.w(TAG, "#downloadModel failed due to connection.", e);
                e.printStackTrace();
            }
            return;
        }
        run(service -> service.triggerModelDownload(recognizerIntent, attributionSource, listener));
    }

    void shutdown(IBinder clientToken) {
        synchronized (mLock) {
            for (Pair<IBinder, IRecognitionListener> clientListener : mClientListeners) {
                if (clientListener.first == clientToken) {
                    cancel(clientListener.second, /* isShutdown */ true);
                }
            }
        }
    }

    @Override // from ServiceConnector.Impl
    protected void onServiceConnectionStatusChanged(
            IRecognitionService service, boolean connected) {
        mConnected = connected;

        if (DEBUG) {
            if (connected) {
                Slog.i(TAG, "Connected to speech recognition service");
            } else {
                Slog.w(TAG, "Disconnected from speech recognition service");
            }
        }

        synchronized (mLock) {
            if (!connected) {
                if (mClients.isEmpty()) {
                    Slog.i(TAG, "Connection to speech recognition service lost, but no "
                            + "#startListening has been invoked yet.");
                    return;
                }


                for (ClientState clientState : mClients.values().toArray(new ClientState[0])) {
                    tryRespondWithError(
                            clientState.mDelegatingListener.mRemoteListener,
                            SpeechRecognizer.ERROR_SERVER_DISCONNECTED);
                    removeClient(clientState.mDelegatingListener.mRemoteListener);
                }
            }
        }
    }

    @Override // from AbstractRemoteService
    protected long getAutoDisconnectTimeoutMs() {
        return PERMANENT_BOUND_TIMEOUT_MS;
    }

    private void removeClient(IRecognitionListener listener) {
        synchronized (mLock) {
            ClientState clientState = mClients.remove(listener.asBinder());
            if (clientState != null) {
                if (DEBUG) {
                    Slog.d(TAG, "Removed a client from the map with listener = "
                            + listener.asBinder() + ".");
                }
                clientState.reset();
            }
            mClientListeners.removeIf(clientListener -> clientListener.second == listener);
        }
    }

    private static void tryRespondWithError(IRecognitionListener listener, int errorCode) {
        if (DEBUG) {
            Slog.i(TAG, "Responding with error " + errorCode);
        }

        try {
            if (listener != null) {
                listener.onError(errorCode);
            }
        } catch (RemoteException e) {
            Slog.w(TAG,
                    TextUtils.formatSimple("Failed to respond with an error %d to the client",
                            errorCode), e);
        }
    }

    boolean hasActiveSessions() {
        synchronized (mLock) {
            return !mClients.isEmpty();
        }
    }

    void associateClientWithActiveListener(IBinder clientToken, IRecognitionListener listener) {
        synchronized (mLock) {
            if (mClients.containsKey(listener.asBinder())) {
                mClientListeners.add(new Pair<>(clientToken, listener));
            }
        }
    }

    private static class DelegatingListener extends IRecognitionListener.Stub {
        private final IRecognitionListener mRemoteListener;
        private final Runnable mOnSessionSuccess;
        private final Runnable mOnSessionFailure;

        DelegatingListener(IRecognitionListener listener,
                Runnable onSessionSuccess, Runnable onSessionFailure) {
            mRemoteListener = listener;
            mOnSessionSuccess = onSessionSuccess;
            mOnSessionFailure = onSessionFailure;
        }

        @Override
        public void onReadyForSpeech(Bundle params) throws RemoteException {
            mRemoteListener.onReadyForSpeech(params);
        }

        @Override
        public void onBeginningOfSpeech() throws RemoteException {
            mRemoteListener.onBeginningOfSpeech();
        }

        @Override
        public void onRmsChanged(float rmsdB) throws RemoteException {
            mRemoteListener.onRmsChanged(rmsdB);
        }

        @Override
        public void onBufferReceived(byte[] buffer) throws RemoteException {
            mRemoteListener.onBufferReceived(buffer);
        }

        @Override
        public void onEndOfSpeech() throws RemoteException {
            mRemoteListener.onEndOfSpeech();
        }

        @Override
        public void onError(int error) throws RemoteException {
            if (DEBUG) {
                Slog.i(TAG, TextUtils.formatSimple("Error %d during recognition session.", error));
            }
            mOnSessionFailure.run();
            mRemoteListener.onError(error);
        }

        @Override
        public void onResults(Bundle results) throws RemoteException {
            if (DEBUG) {
                Slog.i(TAG, "#onResults invoked for a recognition session.");
            }
            mOnSessionSuccess.run();
            mRemoteListener.onResults(results);
        }

        @Override
        public void onPartialResults(Bundle results) throws RemoteException {
            mRemoteListener.onPartialResults(results);
        }

        @Override
        public void onSegmentResults(Bundle results) throws RemoteException {
            mRemoteListener.onSegmentResults(results);
        }

        @Override
        public void onEndOfSegmentedSession() throws RemoteException {
            if (DEBUG) {
                Slog.i(TAG, "#onEndOfSegmentedSession invoked for a recognition session.");
            }
            mOnSessionSuccess.run();
            mRemoteListener.onEndOfSegmentedSession();
        }

        @Override
        public void onLanguageDetection(Bundle results) throws RemoteException {
            mRemoteListener.onLanguageDetection(results);
        }

        @Override
        public void onEvent(int eventType, Bundle params) throws RemoteException {
            mRemoteListener.onEvent(eventType, params);
        }
    }

    /**
     * Data class holding info about a connected client:
     * <ul>
     *   <li> {@link ClientState#mDelegatingListener}
     *   - object holding callbacks to be invoked after the session is complete;
     *   <li> {@link ClientState#mRecordingInProgress}
     *   - flag denoting if the client is currently recording.
     */
    static class ClientState {
        DelegatingListener mDelegatingListener;
        boolean mRecordingInProgress;

        ClientState(DelegatingListener delegatingListener, boolean recordingInProgress) {
            mDelegatingListener = delegatingListener;
            mRecordingInProgress = recordingInProgress;
        }

        ClientState(DelegatingListener delegatingListener) {
            this(delegatingListener, true);
        }

        ClientState() {
            this(null, true);
        }

        void reset() {
            mDelegatingListener = null;
            mRecordingInProgress = false;
        }
    }
}
