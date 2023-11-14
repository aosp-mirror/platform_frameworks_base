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
import android.content.AttributionSource;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.permission.PermissionManager;
import android.speech.IModelDownloadListener;
import android.speech.IRecognitionListener;
import android.speech.IRecognitionService;
import android.speech.IRecognitionServiceManagerCallback;
import android.speech.IRecognitionSupportCallback;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import android.util.Slog;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.modules.expresslog.Counter;
import com.android.server.infra.AbstractPerUserSystemService;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class SpeechRecognitionManagerServiceImpl extends
        AbstractPerUserSystemService<SpeechRecognitionManagerServiceImpl,
                SpeechRecognitionManagerService> {
    private static final String TAG = SpeechRecognitionManagerServiceImpl.class.getSimpleName();

    private static final int MAX_CONCURRENT_CONNECTIONS_BY_CLIENT = 10;

    private final Object mLock = new Object();

    @NonNull
    @GuardedBy("mLock")
    private final Map<Integer, Set<RemoteSpeechRecognitionService>> mRemoteServicesByUid =
            new HashMap<>();

    @GuardedBy("mLock")
    private final SparseIntArray mSessionCountByUid = new SparseIntArray();

    SpeechRecognitionManagerServiceImpl(
            @NonNull SpeechRecognitionManagerService master,
            @NonNull Object lock, @UserIdInt int userId) {
        super(master, lock, userId);
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
        return enabledChanged;
    }

    void createSessionLocked(
            ComponentName componentName,
            IBinder clientToken,
            boolean onDevice,
            IRecognitionServiceManagerCallback callback) {
        if (mMaster.debug) {
            Slog.i(TAG, String.format("#createSessionLocked, component=%s, onDevice=%s",
                    componentName, onDevice));
        }

        ComponentName serviceComponent = componentName;
        if (onDevice) {
            serviceComponent = getOnDeviceComponentNameLocked();
        }

        if (!onDevice && Process.isIsolated(Binder.getCallingUid())) {
            Slog.w(TAG, "Isolated process can only start on device speech recognizer.");
            tryRespondWithError(callback, SpeechRecognizer.ERROR_CLIENT);
            return;
        }

        if (serviceComponent == null) {
            if (mMaster.debug) {
                Slog.i(TAG, "Service component is undefined, responding with error.");
            }
            tryRespondWithError(callback, SpeechRecognizer.ERROR_CLIENT);
            return;
        }

        final int creatorCallingUid = Binder.getCallingUid();
        RemoteSpeechRecognitionService service = createService(creatorCallingUid, serviceComponent);

        if (service == null) {
            tryRespondWithError(callback, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS);
            return;
        }

        IBinder.DeathRecipient deathRecipient =
                () -> handleClientDeath(
                        clientToken, creatorCallingUid, service, true /* invoke #cancel */);

        try {
            clientToken.linkToDeath(deathRecipient, 0);
        } catch (RemoteException e) {
            // RemoteException == binder already died, schedule disconnect anyway.
            handleClientDeath(clientToken, creatorCallingUid, service, true /* invoke #cancel */);
            return;
        }

        service.connect().thenAccept(binderService -> {
            if (binderService != null) {
                try {
                    callback.onSuccess(new IRecognitionService.Stub() {
                        @Override
                        public void startListening(
                                Intent recognizerIntent,
                                IRecognitionListener listener,
                                @NonNull AttributionSource attributionSource)
                                throws RemoteException {
                            attributionSource.enforceCallingUid();
                            if (!attributionSource.isTrusted(mMaster.getContext())) {
                                attributionSource = mMaster.getContext()
                                        .getSystemService(PermissionManager.class)
                                        .registerAttributionSource(attributionSource);
                            }
                            service.startListening(recognizerIntent, listener, attributionSource);
                            service.associateClientWithActiveListener(clientToken, listener);
                        }

                        @Override
                        public void stopListening(
                                IRecognitionListener listener) throws RemoteException {
                            service.stopListening(listener);
                        }

                        @Override
                        public void cancel(
                                IRecognitionListener listener,
                                boolean isShutdown) throws RemoteException {
                            service.cancel(listener, isShutdown);

                            if (isShutdown) {
                                handleClientDeath(
                                        clientToken,
                                        creatorCallingUid,
                                        service,
                                        false /* invoke #cancel */);
                                clientToken.unlinkToDeath(deathRecipient, 0);
                            }
                        }

                        @Override
                        public void checkRecognitionSupport(
                                Intent recognizerIntent,
                                AttributionSource attributionSource,
                                IRecognitionSupportCallback callback) {
                            service.checkRecognitionSupport(
                                    recognizerIntent, attributionSource, callback);
                        }

                        @Override
                        public void triggerModelDownload(
                                Intent recognizerIntent,
                                AttributionSource attributionSource,
                                IModelDownloadListener listener) {
                            service.triggerModelDownload(
                                    recognizerIntent, attributionSource, listener);
                        }
                    });
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error creating a speech recognition session", e);
                    tryRespondWithError(callback, SpeechRecognizer.ERROR_CLIENT);
                }
            } else {
                tryRespondWithError(callback, SpeechRecognizer.ERROR_CLIENT);
            }
        });
    }

    private void handleClientDeath(
            IBinder clientToken, int callingUid,
            RemoteSpeechRecognitionService service, boolean invokeCancel) {
        if (invokeCancel) {
            service.shutdown(clientToken);
        }
        synchronized (mLock) {
            decrementSessionCountForUidLocked(callingUid);
            if (!service.hasActiveSessions()) {
                removeService(callingUid, service);
            }
        }
    }

    @GuardedBy("mLock")
    @Nullable
    private ComponentName getOnDeviceComponentNameLocked() {
        final String serviceName = getComponentNameLocked();
        if (mMaster.debug) {
            Slog.i(TAG, "Resolved component name: " + serviceName);
        }

        if (serviceName == null) {
            if (mMaster.verbose) {
                Slog.v(TAG, "ensureRemoteServiceLocked(): no service component name.");
            }
            return null;
        }
        return ComponentName.unflattenFromString(serviceName);
    }

    @GuardedBy("mLock")
    private int getSessionCountByUidLocked(int uid) {
        return mSessionCountByUid.get(uid, 0);
    }

    @GuardedBy("mLock")
    private void incrementSessionCountForUidLocked(int uid) {
        mSessionCountByUid.put(uid, mSessionCountByUid.get(uid, 0) + 1);
    }

    @GuardedBy("mLock")
    private void decrementSessionCountForUidLocked(int uid) {
        int newCount = mSessionCountByUid.get(uid, 1) - 1;
        if (newCount > 0) {
            mSessionCountByUid.put(uid, newCount);
        } else {
            mSessionCountByUid.delete(uid);
        }
    }

    private RemoteSpeechRecognitionService createService(
            int callingUid, ComponentName serviceComponent) {
        synchronized (mLock) {
            Set<RemoteSpeechRecognitionService> servicesForClient =
                    mRemoteServicesByUid.get(callingUid);

            if (servicesForClient != null
                    && servicesForClient.size() >= MAX_CONCURRENT_CONNECTIONS_BY_CLIENT) {
                Slog.w(TAG, "Number of remote services exceeded for uid: " + callingUid);
                Counter.logIncrementWithUid(
                        "speech_recognition.value_exceed_service_connections_count",
                        callingUid);
                return null;
            }

            if (getSessionCountByUidLocked(callingUid) == MAX_CONCURRENT_CONNECTIONS_BY_CLIENT) {
                Slog.w(TAG, "Number of sessions exceeded for uid: " + callingUid);
                Counter.logIncrementWithUid(
                        "speech_recognition.value_exceed_session_count",
                        callingUid);
                // TODO(b/297249772): return null early to refuse the new connection
            }

            if (servicesForClient != null) {
                Optional<RemoteSpeechRecognitionService> existingService =
                        servicesForClient
                                .stream()
                                .filter(service ->
                                        service.getServiceComponentName().equals(serviceComponent))
                                .findFirst();
                if (existingService.isPresent()) {
                    if (mMaster.debug) {
                        Slog.i(TAG, "Reused existing connection to " + serviceComponent);
                    }

                    incrementSessionCountForUidLocked(callingUid);
                    return existingService.get();
                }
            }

            if (serviceComponent != null && !componentMapsToRecognitionService(serviceComponent)) {
                return null;
            }

            RemoteSpeechRecognitionService service =
                    new RemoteSpeechRecognitionService(
                            getContext(), serviceComponent, getUserId(), callingUid);

            Set<RemoteSpeechRecognitionService> valuesByCaller =
                    mRemoteServicesByUid.computeIfAbsent(callingUid, key -> new HashSet<>());
            valuesByCaller.add(service);

            if (mMaster.debug) {
                Slog.i(TAG, "Creating a new connection to " + serviceComponent);
            }

            incrementSessionCountForUidLocked(callingUid);
            return service;
        }
    }

    private boolean componentMapsToRecognitionService(@NonNull ComponentName serviceComponent) {
        List<ResolveInfo> resolveInfos;

        final long identityToken = Binder.clearCallingIdentity();
        try {
            resolveInfos =
                    getContext().getPackageManager().queryIntentServicesAsUser(
                            new Intent(RecognitionService.SERVICE_INTERFACE), 0, getUserId());
        } finally {
            Binder.restoreCallingIdentity(identityToken);
        }

        if (resolveInfos == null) {
            return false;
        }

        for (ResolveInfo ri : resolveInfos) {
            if (ri.serviceInfo != null
                    && serviceComponent.equals(ri.serviceInfo.getComponentName())) {
                return true;
            }
        }

        Slog.w(TAG, "serviceComponent is not RecognitionService: " + serviceComponent);
        return false;
    }

    private void removeService(int callingUid, RemoteSpeechRecognitionService service) {
        synchronized (mLock) {
            Set<RemoteSpeechRecognitionService> valuesByCaller =
                    mRemoteServicesByUid.get(callingUid);
            if (valuesByCaller != null) {
                valuesByCaller.remove(service);
            }
        }
    }

    private static void tryRespondWithError(IRecognitionServiceManagerCallback callback,
            int errorCode) {
        try {
            callback.onError(errorCode);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to respond with error");
        }
    }
}
