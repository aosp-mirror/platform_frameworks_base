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

package com.android.server.voiceinteraction;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.soundtrigger.IRecognitionStatusCallback;
import android.hardware.soundtrigger.SoundTrigger;
import android.os.RemoteException;
import android.service.voice.AlwaysOnHotwordDetector;
import android.service.voice.HotwordDetectionService;
import android.service.voice.IDspHotwordDetectionCallback;
import android.service.voice.IHotwordDetectionService;
import android.util.Slog;

import com.android.internal.app.IHotwordRecognitionStatusCallback;
import com.android.internal.infra.ServiceConnector;

import java.io.PrintWriter;

/**
 * A class that provides the communication with the HotwordDetectionService.
 */
final class HotwordDetectionConnection {
    static final String TAG = "HotwordDetectionConnection";
    // TODO (b/177502877): Set the Debug flag to false before shipping.
    static final boolean DEBUG = true;

    final Object mLock;
    final ComponentName mDetectionComponentName;
    final int mUser;
    final Context mContext;
    final @NonNull ServiceConnector<IHotwordDetectionService> mRemoteHotwordDetectionService;
    boolean mBound;

    HotwordDetectionConnection(Object lock, Context context, ComponentName serviceName,
            int userId, boolean bindInstantServiceAllowed) {
        mLock = lock;
        mContext = context;
        mDetectionComponentName = serviceName;
        mUser = userId;
        final Intent intent = new Intent(HotwordDetectionService.SERVICE_INTERFACE);
        intent.setComponent(mDetectionComponentName);

        mRemoteHotwordDetectionService = new ServiceConnector.Impl<IHotwordDetectionService>(
                mContext, intent, bindInstantServiceAllowed ? Context.BIND_ALLOW_INSTANT : 0, mUser,
                IHotwordDetectionService.Stub::asInterface) {
            @Override // from ServiceConnector.Impl
            protected void onServiceConnectionStatusChanged(IHotwordDetectionService service,
                    boolean connected) {
                synchronized (mLock) {
                    mBound = connected;
                }
            }

            @Override
            protected long getAutoDisconnectTimeoutMs() {
                return -1;
            }
        };
        mRemoteHotwordDetectionService.connect();
    }

    private boolean isBound() {
        synchronized (mLock) {
            return mBound;
        }
    }

    void cancelLocked() {
        if (DEBUG) {
            Slog.d(TAG, "cancelLocked");
        }
        if (mBound) {
            mRemoteHotwordDetectionService.unbind();
            mBound = false;
        }
    }

    private void detectFromDspSource(int sessionId, IDspHotwordDetectionCallback callback) {
        if (DEBUG) {
            Slog.d(TAG, "detectFromDspSource");
        }
        mRemoteHotwordDetectionService.run(
                service -> service.detectFromDspSource(sessionId, callback));
    }

    static final class SoundTriggerCallback extends IRecognitionStatusCallback.Stub {
        private SoundTrigger.KeyphraseRecognitionEvent mRecognitionEvent;
        private final HotwordDetectionConnection mHotwordDetectionConnection;
        private final IHotwordRecognitionStatusCallback mExternalCallback;
        private final IDspHotwordDetectionCallback mInternalCallback;

        SoundTriggerCallback(IHotwordRecognitionStatusCallback callback,
                HotwordDetectionConnection connection) {
            mHotwordDetectionConnection = connection;
            mExternalCallback = callback;
            mInternalCallback = new IDspHotwordDetectionCallback.Stub() {
                @Override
                public void onDetected() throws RemoteException {
                    if (DEBUG) {
                        Slog.d(TAG, "onDetected");
                    }
                    mExternalCallback.onKeyphraseDetected(mRecognitionEvent);
                }

                @Override
                public void onRejected() throws RemoteException {
                    if (DEBUG) {
                        Slog.d(TAG, "onRejected");
                    }
                    mExternalCallback.onRejected(
                            AlwaysOnHotwordDetector.HOTWORD_DETECTION_FALSE_ALERT);
                }
            };
        }

        @Override
        public void onKeyphraseDetected(SoundTrigger.KeyphraseRecognitionEvent recognitionEvent)
                throws RemoteException {
            if (DEBUG) {
                Slog.d(TAG, "onKeyphraseDetected recognitionEvent : " + recognitionEvent);
            }
            final boolean useHotwordDetectionService = mHotwordDetectionConnection != null
                    && mHotwordDetectionConnection.isBound();
            if (useHotwordDetectionService) {
                mRecognitionEvent = recognitionEvent;
                mHotwordDetectionConnection.detectFromDspSource(
                        recognitionEvent.getCaptureSession(), mInternalCallback);
            } else {
                mExternalCallback.onKeyphraseDetected(recognitionEvent);
            }
        }

        @Override
        public void onGenericSoundTriggerDetected(
                SoundTrigger.GenericRecognitionEvent recognitionEvent)
                throws RemoteException {
            mExternalCallback.onGenericSoundTriggerDetected(recognitionEvent);
        }

        @Override
        public void onError(int status) throws RemoteException {
            mExternalCallback.onError(status);
        }

        @Override
        public void onRecognitionPaused() throws RemoteException {
            mExternalCallback.onRecognitionPaused();
        }

        @Override
        public void onRecognitionResumed() throws RemoteException {
            mExternalCallback.onRecognitionResumed();
        }
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("mBound="); pw.println(mBound);
    }
};
