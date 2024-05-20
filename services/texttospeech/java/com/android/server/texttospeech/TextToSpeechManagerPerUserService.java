/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.texttospeech;

import static com.android.internal.infra.AbstractRemoteService.PERMANENT_BOUND_TIMEOUT_MS;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;
import android.speech.tts.ITextToSpeechService;
import android.speech.tts.ITextToSpeechSession;
import android.speech.tts.ITextToSpeechSessionCallback;
import android.speech.tts.TextToSpeech;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.infra.ServiceConnector;
import com.android.server.infra.AbstractPerUserSystemService;

import java.util.NoSuchElementException;

/**
 * Manages per-user text to speech session activated by {@link TextToSpeechManagerService}.
 * Creates {@link TtsClient} interface object with direct connection to
 * {@link android.speech.tts.TextToSpeechService} provider.
 *
 * @see ITextToSpeechSession
 * @see TextToSpeech
 */
final class TextToSpeechManagerPerUserService extends
        AbstractPerUserSystemService<TextToSpeechManagerPerUserService,
                TextToSpeechManagerService> {

    private static final String TAG = TextToSpeechManagerPerUserService.class.getSimpleName();

    TextToSpeechManagerPerUserService(
            @NonNull TextToSpeechManagerService master,
            @NonNull Object lock, @UserIdInt int userId) {
        super(master, lock, userId);
    }

    void createSessionLocked(String engine, ITextToSpeechSessionCallback sessionCallback) {
        TextToSpeechSessionConnection.start(getContext(), mUserId, engine, sessionCallback);
    }

    @GuardedBy("mLock")
    @Override // from PerUserSystemService
    @NonNull
    protected ServiceInfo newServiceInfoLocked(
            @SuppressWarnings("unused") @NonNull ComponentName serviceComponent)
            throws PackageManager.NameNotFoundException {
        try {
            return AppGlobals.getPackageManager().getServiceInfo(serviceComponent,
                    PackageManager.GET_META_DATA, mUserId);
        } catch (RemoteException e) {
            throw new PackageManager.NameNotFoundException(
                    "Could not get service for " + serviceComponent);
        }
    }

    private static class TextToSpeechSessionConnection extends
            ServiceConnector.Impl<ITextToSpeechService> {

        private final String mEngine;
        private final ITextToSpeechSessionCallback mCallback;
        private final DeathRecipient mUnbindOnDeathHandler;

        static void start(Context context, @UserIdInt int userId, String engine,
                ITextToSpeechSessionCallback callback) {
            new TextToSpeechSessionConnection(context, userId, engine, callback).start();
        }

        private TextToSpeechSessionConnection(Context context, @UserIdInt int userId, String engine,
                ITextToSpeechSessionCallback callback) {
            super(context,
                    new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE).setPackage(engine),
                    Context.BIND_AUTO_CREATE | Context.BIND_DENY_ACTIVITY_STARTS,
                    userId,
                    ITextToSpeechService.Stub::asInterface);
            mEngine = engine;
            mCallback = callback;
            mUnbindOnDeathHandler = () -> unbindEngine("client process death is reported");
        }

        private void start() {
            Slog.d(TAG, "Trying to start connection to TTS engine: " + mEngine);

            connect()
                    .thenAccept(
                            serviceBinder -> {
                                if (serviceBinder != null) {
                                    Slog.d(TAG,
                                            "Connected successfully to TTS engine: " + mEngine);
                                    try {
                                        mCallback.onConnected(new ITextToSpeechSession.Stub() {
                                            @Override
                                            public void disconnect() {
                                                unbindEngine("client disconnection request");
                                            }
                                        }, serviceBinder.asBinder());

                                        mCallback.asBinder().linkToDeath(mUnbindOnDeathHandler, 0);
                                    } catch (RemoteException ex) {
                                        Slog.w(TAG, "Error notifying the client on connection", ex);

                                        unbindEngine(
                                                "failed communicating with the client - process "
                                                        + "is dead");
                                    }
                                } else {
                                    Slog.w(TAG, "Failed to obtain TTS engine binder");
                                    runSessionCallbackMethod(
                                            () -> mCallback.onError("Failed creating TTS session"));
                                }
                            })
                    .exceptionally(ex -> {
                        Slog.w(TAG, "TTS engine binding error", ex);
                        runSessionCallbackMethod(
                                () -> mCallback.onError(
                                        "Failed creating TTS session: " + ex.getCause()));

                        return null;
                    });
        }

        @Override // from ServiceConnector.Impl
        protected void onServiceConnectionStatusChanged(
                ITextToSpeechService service, boolean connected) {
            if (!connected) {
                Slog.w(TAG, "Disconnected from TTS engine");
                runSessionCallbackMethod(mCallback::onDisconnected);

                try {
                    mCallback.asBinder().unlinkToDeath(mUnbindOnDeathHandler, 0);
                } catch (NoSuchElementException ex) {
                    Slog.d(TAG, "The death recipient was not linked.");
                }
            }
        }

        @Override // from ServiceConnector.Impl
        protected long getAutoDisconnectTimeoutMs() {
            return PERMANENT_BOUND_TIMEOUT_MS;
        }

        private void unbindEngine(String reason) {
            Slog.d(TAG, "Unbinding TTS engine: " + mEngine + ". Reason: " + reason);
            unbind();
        }
    }

    static void runSessionCallbackMethod(ThrowingRunnable callbackRunnable) {
        try {
            callbackRunnable.runOrThrow();
        } catch (RemoteException ex) {
            Slog.i(TAG, "Failed running callback method: " + ex);
        }
    }

    interface ThrowingRunnable {
        void runOrThrow() throws RemoteException;
    }
}
