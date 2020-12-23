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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.RemoteException;
import android.speech.IRecognitionListener;
import android.speech.IRecognitionService;
import android.speech.IRecognitionServiceManagerCallback;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.infra.AbstractPerUserSystemService;

final class SpeechRecognitionManagerServiceImpl extends
        AbstractPerUserSystemService<SpeechRecognitionManagerServiceImpl,
            SpeechRecognitionManagerService> {

    private static final String TAG = SpeechRecognitionManagerServiceImpl.class.getSimpleName();

    @GuardedBy("mLock")
    @Nullable
    private RemoteSpeechRecognitionService mRemoteService;

    SpeechRecognitionManagerServiceImpl(
            @NonNull SpeechRecognitionManagerService master,
            @NonNull Object lock, @UserIdInt int userId, boolean disabled) {
        super(master, lock, userId);
        updateRemoteServiceLocked();
    }

    @GuardedBy("mLock")
    @Override // from PerUserSystemService
    protected ServiceInfo newServiceInfoLocked(@NonNull ComponentName serviceComponent)
            throws PackageManager.NameNotFoundException {
        try {
            return AppGlobals.getPackageManager().getServiceInfo(serviceComponent,
                    PackageManager.GET_META_DATA, mUserId);
        } catch (RemoteException e) {
            throw new PackageManager.NameNotFoundException(
                    "Could not get service for " + serviceComponent);
        }
    }

    @GuardedBy("mLock")
    @Override // from PerUserSystemService
    protected boolean updateLocked(boolean disabled) {
        final boolean enabledChanged = super.updateLocked(disabled);
        updateRemoteServiceLocked();
        return enabledChanged;
    }

    /**
     * Updates the reference to the remote service.
     */
    @GuardedBy("mLock")
    private void updateRemoteServiceLocked() {
        if (mRemoteService != null) {
            if (mMaster.debug) {
                Slog.d(TAG, "updateRemoteService(): destroying old remote service");
            }
            mRemoteService.unbind();
            mRemoteService = null;
        }
    }

    void createSessionLocked(IRecognitionServiceManagerCallback callback) {
        // TODO(b/176578753): check clients have record audio permission.
        // TODO(b/176578753): verify caller package is the one supplied

        RemoteSpeechRecognitionService service = ensureRemoteServiceLocked();

        if (service == null) {
            tryRespondWithError(callback);
            return;
        }

        service.connect().thenAccept(binderService -> {
            if (binderService != null) {
                try {
                    callback.onSuccess(new IRecognitionService.Stub() {
                        @Override
                        public void startListening(Intent recognizerIntent,
                                IRecognitionListener listener,
                                String packageName, String featureId) throws RemoteException {
                            service.startListening(
                                    recognizerIntent, listener, packageName, featureId);
                        }

                        @Override
                        public void stopListening(IRecognitionListener listener,
                                String packageName,
                                String featureId) throws RemoteException {
                            service.stopListening(listener, packageName, featureId);
                        }

                        @Override
                        public void cancel(IRecognitionListener listener,
                                String packageName,
                                String featureId) throws RemoteException {
                            service.cancel(listener, packageName, featureId);
                        }
                    });
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error creating a speech recognition session", e);
                    tryRespondWithError(callback);
                }
            } else {
                tryRespondWithError(callback);
            }
        });
    }

    @GuardedBy("mLock")
    @Nullable
    private RemoteSpeechRecognitionService ensureRemoteServiceLocked() {
        if (mRemoteService == null) {
            final String serviceName = getComponentNameLocked();
            if (serviceName == null) {
                if (mMaster.verbose) {
                    Slog.v(TAG, "ensureRemoteServiceLocked(): no service component name.");
                }
                return null;
            }
            final ComponentName serviceComponent = ComponentName.unflattenFromString(serviceName);
            mRemoteService =
                    new RemoteSpeechRecognitionService(getContext(), serviceComponent, mUserId);
        }
        return mRemoteService;
    }

    private static void tryRespondWithError(IRecognitionServiceManagerCallback callback) {
        try {
            callback.onError();
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to respond with error");
        }
    }
}
