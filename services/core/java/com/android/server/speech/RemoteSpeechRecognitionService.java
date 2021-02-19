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

import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.RemoteException;
import android.speech.IRecognitionListener;
import android.speech.IRecognitionService;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.infra.ServiceConnector;

final class RemoteSpeechRecognitionService extends ServiceConnector.Impl<IRecognitionService> {
    private static final String TAG = RemoteSpeechRecognitionService.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final String APP_OP_MESSAGE = "Recording audio for speech recognition";
    private static final String RECORD_AUDIO_APP_OP =
            AppOpsManager.permissionToOp(android.Manifest.permission.RECORD_AUDIO);

    private final Object mLock = new Object();

    private boolean mConnected = false;

    @Nullable
    private IRecognitionListener mListener;

    @Nullable
    @GuardedBy("mLock")
    private String mPackageName;

    @Nullable
    @GuardedBy("mLock")
    private String mFeatureId;

    @Nullable
    @GuardedBy("mLock")
    private DelegatingListener mDelegatingListener;

    // Makes sure we can block startListening() if session is still in progress.
    @GuardedBy("mLock")
    private boolean mSessionInProgress = false;

    // Makes sure we call startProxyOp / finishProxyOp at right times and only once per session.
    @GuardedBy("mLock")
    private boolean mRecordingInProgress = false;

    private final int mCallingUid;
    private final AppOpsManager mAppOpsManager;
    private final ComponentName mComponentName;

    RemoteSpeechRecognitionService(
            Context context, ComponentName serviceName, int userId, int callingUid) {
        super(context,
                new Intent(RecognitionService.SERVICE_INTERFACE).setComponent(serviceName),
                Context.BIND_AUTO_CREATE
                        | Context.BIND_FOREGROUND_SERVICE
                        | Context.BIND_INCLUDE_CAPABILITIES
                        | Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS,
                userId,
                IRecognitionService.Stub::asInterface);

        mCallingUid = callingUid;
        mAppOpsManager = mContext.getSystemService(AppOpsManager.class);
        mComponentName = serviceName;

        if (DEBUG) {
            Slog.i(TAG, "Bound to recognition service at: " + serviceName.flattenToString());
        }
    }

    ComponentName getServiceComponentName() {
        return mComponentName;
    }

    void startListening(Intent recognizerIntent, IRecognitionListener listener, String packageName,
            String featureId) {
        if (DEBUG) {
            Slog.i(TAG, String.format("#startListening for package: %s, feature=%s, callingUid=%d",
                    packageName, featureId, mCallingUid));
        }

        if (listener == null) {
            Log.w(TAG, "#startListening called with no preceding #setListening - ignoring");
            return;
        }

        if (!mConnected) {
            tryRespondWithError(listener, SpeechRecognizer.ERROR_SERVER_DISCONNECTED);
            return;
        }

        synchronized (mLock) {
            if (mSessionInProgress) {
                Slog.i(TAG, "#startListening called while listening is in progress.");
                tryRespondWithError(listener, SpeechRecognizer.ERROR_RECOGNIZER_BUSY);
                return;
            }

            if (startProxyOp(packageName, featureId) != AppOpsManager.MODE_ALLOWED) {
                tryRespondWithError(listener, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS);
                return;
            }
            mSessionInProgress = true;
            mRecordingInProgress = true;

            mListener = listener;
            mDelegatingListener = new DelegatingListener(listener, () -> {
                // To be invoked in terminal calls of the callback: results() or error()
                if (DEBUG) {
                    Slog.i(TAG, "Recognition session complete");
                }

                synchronized (mLock) {
                    resetStateLocked();
                }
            });
            mPackageName = packageName;
            mFeatureId = featureId;

            run(service ->
                    service.startListening(
                            recognizerIntent,
                            mDelegatingListener,
                            packageName,
                            featureId,
                            mCallingUid));
        }
    }

    void stopListening(
            IRecognitionListener listener, String packageName, String featureId) {
        if (DEBUG) {
            Slog.i(TAG, "#stopListening for package: " + packageName + ", feature=" + featureId);
        }

        if (!mConnected) {
            tryRespondWithError(listener, SpeechRecognizer.ERROR_SERVER_DISCONNECTED);
            return;
        }

        synchronized (mLock) {
            if (mListener == null) {
                Log.w(TAG, "#stopListening called with no preceding #startListening - ignoring");
                tryRespondWithError(listener, SpeechRecognizer.ERROR_CLIENT);
                return;
            }

            if (mListener.asBinder() != listener.asBinder()) {
                Log.w(TAG, "#stopListening called with an unexpected listener");
                tryRespondWithError(listener, SpeechRecognizer.ERROR_CLIENT);
                return;
            }

            if (!mRecordingInProgress) {
                Slog.i(TAG, "#stopListening called while listening isn't in progress, ignoring.");
                return;
            }
            mRecordingInProgress = false;

            finishProxyOp(packageName, featureId);

            run(service -> service.stopListening(mDelegatingListener, packageName, featureId));
        }
    }

    void cancel(
            IRecognitionListener listener,
            String packageName,
            String featureId,
            boolean isShutdown) {
        if (DEBUG) {
            Slog.i(TAG, "#cancel for package: " + packageName + ", feature=" + featureId);
        }

        if (!mConnected) {
            tryRespondWithError(listener, SpeechRecognizer.ERROR_SERVER_DISCONNECTED);
        }

        synchronized (mLock) {
            if (mListener == null) {
                if (DEBUG) {
                    Log.w(TAG, "#cancel called with no preceding #startListening - ignoring");
                }
                return;
            }

            if (mListener.asBinder() != listener.asBinder()) {
                Log.w(TAG, "#cancel called with an unexpected listener");
                tryRespondWithError(listener, SpeechRecognizer.ERROR_CLIENT);
                return;
            }

            // Temporary reference to allow for resetting the hard link mDelegatingListener to null.
            IRecognitionListener delegatingListener = mDelegatingListener;

            run(service -> service.cancel(delegatingListener, packageName, featureId, isShutdown));

            if (mRecordingInProgress) {
                finishProxyOp(packageName, featureId);
            }
            mRecordingInProgress = false;
            mSessionInProgress = false;

            mDelegatingListener = null;
            mListener = null;

            // Schedule to unbind after cancel is delivered.
            if (isShutdown) {
                run(service -> unbind());
            }
        }
    }

    void shutdown() {
        synchronized (mLock) {
            if (this.mListener == null) {
                if (DEBUG) {
                    Slog.i(TAG, "Package died, but session wasn't initialized. "
                            + "Not invoking #cancel");
                }
                return;
            }
        }

        cancel(mListener, mPackageName, mFeatureId, true /* isShutdown */);
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
                if (mListener == null) {
                    Slog.i(TAG, "Connection to speech recognition service lost, but no "
                            + "#startListening has been invoked yet.");
                    return;
                }

                tryRespondWithError(mListener, SpeechRecognizer.ERROR_SERVER_DISCONNECTED);

                resetStateLocked();
            }
        }
    }

    @Override // from AbstractRemoteService
    protected long getAutoDisconnectTimeoutMs() {
        return PERMANENT_BOUND_TIMEOUT_MS;
    }

    private void resetStateLocked() {
        if (mRecordingInProgress && mPackageName != null && mFeatureId != null) {
            finishProxyOp(mPackageName, mFeatureId);
        }

        mListener = null;
        mDelegatingListener = null;
        mSessionInProgress = false;
        mRecordingInProgress = false;
    }

    private int startProxyOp(String packageName, String featureId) {
        final long identity = Binder.clearCallingIdentity();
        try {
            return mAppOpsManager.startProxyOp(
                    RECORD_AUDIO_APP_OP,
                    mCallingUid,
                    packageName,
                    featureId,
                    APP_OP_MESSAGE);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void finishProxyOp(String packageName, String featureId) {
        final long identity = Binder.clearCallingIdentity();
        try {
            mAppOpsManager.finishProxyOp(
                    RECORD_AUDIO_APP_OP, mCallingUid, packageName, featureId);
        } finally {
            Binder.restoreCallingIdentity(identity);
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
                    String.format("Failed to respond with an error %d to the client", errorCode),
                    e);
        }
    }

    private static class DelegatingListener extends IRecognitionListener.Stub {

        private final IRecognitionListener mRemoteListener;
        private final Runnable mOnSessionComplete;

        DelegatingListener(IRecognitionListener listener, Runnable onSessionComplete) {
            mRemoteListener = listener;
            mOnSessionComplete = onSessionComplete;
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
                Slog.i(TAG, String.format("Error %d during recognition session", error));
            }
            mOnSessionComplete.run();
            mRemoteListener.onError(error);
        }

        @Override
        public void onResults(Bundle results) throws RemoteException {
            if (DEBUG) {
                Slog.i(TAG, "#onResults invoked for a recognition session");
            }
            mOnSessionComplete.run();
            mRemoteListener.onResults(results);
        }

        @Override
        public void onPartialResults(Bundle results) throws RemoteException {
            mRemoteListener.onPartialResults(results);
        }

        @Override
        public void onEvent(int eventType, Bundle params) throws RemoteException {
            mRemoteListener.onEvent(eventType, params);
        }
    }
}
