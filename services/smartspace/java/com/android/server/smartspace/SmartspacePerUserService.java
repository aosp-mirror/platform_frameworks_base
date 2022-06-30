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

package com.android.server.smartspace;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppGlobals;
import android.app.smartspace.ISmartspaceCallback;
import android.app.smartspace.SmartspaceConfig;
import android.app.smartspace.SmartspaceSessionId;
import android.app.smartspace.SmartspaceTargetEvent;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.service.smartspace.ISmartspaceService;
import android.service.smartspace.SmartspaceService;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.infra.AbstractRemoteService;
import com.android.server.infra.AbstractPerUserSystemService;

/**
 * Per-user instance of {@link SmartspaceManagerService}.
 */
public class SmartspacePerUserService extends
        AbstractPerUserSystemService<SmartspacePerUserService, SmartspaceManagerService>
        implements RemoteSmartspaceService.RemoteSmartspaceServiceCallbacks {

    private static final String TAG = SmartspacePerUserService.class.getSimpleName();
    @GuardedBy("mLock")
    private final ArrayMap<SmartspaceSessionId, SmartspaceSessionInfo> mSessionInfos =
            new ArrayMap<>();
    @Nullable
    @GuardedBy("mLock")
    private RemoteSmartspaceService mRemoteService;
    /**
     * When {@code true}, remote service died but service state is kept so it's restored after
     * the system re-binds to it.
     */
    @GuardedBy("mLock")
    private boolean mZombie;

    protected SmartspacePerUserService(SmartspaceManagerService master,
            Object lock, int userId) {
        super(master, lock, userId);
    }

    @Override // from PerUserSystemService
    protected ServiceInfo newServiceInfoLocked(@NonNull ComponentName serviceComponent)
            throws NameNotFoundException {

        ServiceInfo si;
        try {
            si = AppGlobals.getPackageManager().getServiceInfo(serviceComponent,
                    PackageManager.GET_META_DATA, mUserId);
        } catch (RemoteException e) {
            throw new NameNotFoundException("Could not get service for " + serviceComponent);
        }
        // TODO(b/177858728): must check that either the service is from a system component,
        // or it matches a service set by shell cmd (so it can be used on CTS tests and when
        // OEMs are implementing the real service and also verify the proper permissions
        return si;
    }

    @GuardedBy("mLock")
    @Override // from PerUserSystemService
    protected boolean updateLocked(boolean disabled) {
        final boolean enabledChanged = super.updateLocked(disabled);
        if (enabledChanged) {
            if (isEnabledLocked()) {
                // Send the pending sessions over to the service
                resurrectSessionsLocked();
            } else {
                // Clear the remote service for the next call
                updateRemoteServiceLocked();
            }
        }
        return enabledChanged;
    }

    /**
     * Notifies the service of a new smartspace session.
     */
    @GuardedBy("mLock")
    public void onCreateSmartspaceSessionLocked(@NonNull SmartspaceConfig smartspaceConfig,
            @NonNull SmartspaceSessionId sessionId, @NonNull IBinder token) {
        final boolean serviceExists = resolveService(sessionId,
                s -> s.onCreateSmartspaceSession(smartspaceConfig, sessionId));

        if (serviceExists && !mSessionInfos.containsKey(sessionId)) {
            final SmartspaceSessionInfo sessionInfo = new SmartspaceSessionInfo(
                    sessionId, smartspaceConfig, token, () -> {
                synchronized (mLock) {
                    onDestroyLocked(sessionId);
                }
            });
            if (sessionInfo.linkToDeath()) {
                mSessionInfos.put(sessionId, sessionInfo);
            } else {
                // destroy the session if calling process is already dead
                onDestroyLocked(sessionId);
            }
        }
    }

    /**
     * Records an smartspace event to the service.
     */
    @GuardedBy("mLock")
    public void notifySmartspaceEventLocked(@NonNull SmartspaceSessionId sessionId,
            @NonNull SmartspaceTargetEvent event) {
        final SmartspaceSessionInfo sessionInfo = mSessionInfos.get(sessionId);
        if (sessionInfo == null) return;
        resolveService(sessionId, s -> s.notifySmartspaceEvent(sessionId, event));
    }

    /**
     * Requests the service to return smartspace results of an input query.
     */
    @GuardedBy("mLock")
    public void requestSmartspaceUpdateLocked(@NonNull SmartspaceSessionId sessionId) {
        final SmartspaceSessionInfo sessionInfo = mSessionInfos.get(sessionId);
        if (sessionInfo == null) return;
        resolveService(sessionId,
                s -> s.requestSmartspaceUpdate(sessionId));
    }

    /**
     * Registers a callback for continuous updates of predicted apps or shortcuts.
     */
    @GuardedBy("mLock")
    public void registerSmartspaceUpdatesLocked(@NonNull SmartspaceSessionId sessionId,
            @NonNull ISmartspaceCallback callback) {
        final SmartspaceSessionInfo sessionInfo = mSessionInfos.get(sessionId);
        if (sessionInfo == null) return;
        final boolean serviceExists = resolveService(sessionId,
                s -> s.registerSmartspaceUpdates(sessionId, callback));
        if (serviceExists) {
            sessionInfo.addCallbackLocked(callback);
        }
    }

    /**
     * Unregisters a callback for continuous updates of predicted apps or shortcuts.
     */
    @GuardedBy("mLock")
    public void unregisterSmartspaceUpdatesLocked(@NonNull SmartspaceSessionId sessionId,
            @NonNull ISmartspaceCallback callback) {
        final SmartspaceSessionInfo sessionInfo = mSessionInfos.get(sessionId);
        if (sessionInfo == null) return;
        final boolean serviceExists = resolveService(sessionId,
                s -> s.unregisterSmartspaceUpdates(sessionId, callback));
        if (serviceExists) {
            sessionInfo.removeCallbackLocked(callback);
        }
    }

    /**
     * Notifies the service of the end of an existing smartspace session.
     */
    @GuardedBy("mLock")
    public void onDestroyLocked(@NonNull SmartspaceSessionId sessionId) {
        if (isDebug()) {
            Slog.d(TAG, "onDestroyLocked(): sessionId=" + sessionId);
        }
        final SmartspaceSessionInfo sessionInfo = mSessionInfos.remove(sessionId);
        if (sessionInfo == null) return;
        resolveService(sessionId, s -> s.onDestroySmartspaceSession(sessionId));
        sessionInfo.destroy();
    }

    @Override
    public void onFailureOrTimeout(boolean timedOut) {
        if (isDebug()) {
            Slog.d(TAG, "onFailureOrTimeout(): timed out=" + timedOut);
        }
        // Do nothing, we are just proxying to the smartspace ui service
    }

    @Override
    public void onConnectedStateChanged(boolean connected) {
        if (isDebug()) {
            Slog.d(TAG, "onConnectedStateChanged(): connected=" + connected);
        }
        if (connected) {
            synchronized (mLock) {
                if (mZombie) {
                    // Validation check - shouldn't happen
                    if (mRemoteService == null) {
                        Slog.w(TAG, "Cannot resurrect sessions because remote service is null");
                        return;
                    }
                    mZombie = false;
                    resurrectSessionsLocked();
                }
            }
        }
    }

    @Override
    public void onServiceDied(RemoteSmartspaceService service) {
        if (isDebug()) {
            Slog.w(TAG, "onServiceDied(): service=" + service);
        }
        synchronized (mLock) {
            mZombie = true;
        }
        updateRemoteServiceLocked();
    }

    @GuardedBy("mLock")
    private void updateRemoteServiceLocked() {
        if (mRemoteService != null) {
            mRemoteService.destroy();
            mRemoteService = null;
        }
    }

    void onPackageUpdatedLocked() {
        if (isDebug()) {
            Slog.v(TAG, "onPackageUpdatedLocked()");
        }
        destroyAndRebindRemoteService();
    }

    void onPackageRestartedLocked() {
        if (isDebug()) {
            Slog.v(TAG, "onPackageRestartedLocked()");
        }
        destroyAndRebindRemoteService();
    }

    private void destroyAndRebindRemoteService() {
        if (mRemoteService == null) {
            return;
        }

        if (isDebug()) {
            Slog.d(TAG, "Destroying the old remote service.");
        }
        mRemoteService.destroy();
        mRemoteService = null;

        synchronized (mLock) {
            mZombie = true;
        }
        mRemoteService = getRemoteServiceLocked();
        if (mRemoteService != null) {
            if (isDebug()) {
                Slog.d(TAG, "Rebinding to the new remote service.");
            }
            mRemoteService.reconnect();
        }
    }

    /**
     * Called after the remote service connected, it's used to restore state from a 'zombie'
     * service (i.e., after it died).
     */
    private void resurrectSessionsLocked() {
        final int numSessions = mSessionInfos.size();
        if (isDebug()) {
            Slog.d(TAG, "Resurrecting remote service (" + mRemoteService + ") on "
                    + numSessions + " sessions.");
        }

        for (SmartspaceSessionInfo sessionInfo : mSessionInfos.values()) {
            sessionInfo.resurrectSessionLocked(this, sessionInfo.mToken);
        }
    }

    @GuardedBy("mLock")
    @Nullable
    protected boolean resolveService(
            @NonNull final SmartspaceSessionId sessionId,
            @NonNull final AbstractRemoteService.AsyncRequest<ISmartspaceService> cb) {

        final RemoteSmartspaceService service = getRemoteServiceLocked();
        if (service != null) {
            service.executeOnResolvedService(cb);
        }
        return service != null;
    }

    @GuardedBy("mLock")
    @Nullable
    private RemoteSmartspaceService getRemoteServiceLocked() {
        if (mRemoteService == null) {
            final String serviceName = getComponentNameLocked();
            if (serviceName == null) {
                if (mMaster.verbose) {
                    Slog.v(TAG, "getRemoteServiceLocked(): not set");
                }
                return null;
            }
            ComponentName serviceComponent = ComponentName.unflattenFromString(serviceName);

            mRemoteService = new RemoteSmartspaceService(getContext(),
                    SmartspaceService.SERVICE_INTERFACE, serviceComponent, mUserId, this,
                    mMaster.isBindInstantServiceAllowed(), mMaster.verbose);
        }

        return mRemoteService;
    }

    private static final class SmartspaceSessionInfo {
        private static final boolean DEBUG = false;  // Do not submit with true
        @NonNull
        final IBinder mToken;
        @NonNull
        final IBinder.DeathRecipient mDeathRecipient;
        @NonNull
        private final SmartspaceSessionId mSessionId;
        @NonNull
        private final SmartspaceConfig mSmartspaceConfig;
        private final RemoteCallbackList<ISmartspaceCallback> mCallbacks =
                new RemoteCallbackList<>();

        SmartspaceSessionInfo(
                @NonNull final SmartspaceSessionId id,
                @NonNull final SmartspaceConfig context,
                @NonNull final IBinder token,
                @NonNull final IBinder.DeathRecipient deathRecipient) {
            if (DEBUG) {
                Slog.d(TAG, "Creating SmartspaceSessionInfo for session Id=" + id);
            }
            mSessionId = id;
            mSmartspaceConfig = context;
            mToken = token;
            mDeathRecipient = deathRecipient;
        }

        void addCallbackLocked(ISmartspaceCallback callback) {
            if (DEBUG) {
                Slog.d(TAG, "Storing callback for session Id=" + mSessionId
                        + " and callback=" + callback.asBinder());
            }
            mCallbacks.register(callback);
        }

        void removeCallbackLocked(ISmartspaceCallback callback) {
            if (DEBUG) {
                Slog.d(TAG, "Removing callback for session Id=" + mSessionId
                        + " and callback=" + callback.asBinder());
            }
            mCallbacks.unregister(callback);
        }

        boolean linkToDeath() {
            try {
                mToken.linkToDeath(mDeathRecipient, 0);
            } catch (RemoteException e) {
                if (DEBUG) {
                    Slog.w(TAG, "Caller is dead before session can be started, sessionId: "
                            + mSessionId);
                }
                return false;
            }
            return true;
        }

        void destroy() {
            if (DEBUG) {
                Slog.d(TAG, "Removing all callbacks for session Id=" + mSessionId
                        + " and " + mCallbacks.getRegisteredCallbackCount() + " callbacks.");
            }
            if (mToken != null) {
                mToken.unlinkToDeath(mDeathRecipient, 0);
            }
            mCallbacks.kill();
        }

        void resurrectSessionLocked(SmartspacePerUserService service, IBinder token) {
            int callbackCount = mCallbacks.getRegisteredCallbackCount();
            if (DEBUG) {
                Slog.d(TAG, "Resurrecting remote service (" + service.getRemoteServiceLocked()
                        + ") for session Id=" + mSessionId + " and "
                        + callbackCount + " callbacks.");
            }
            service.onCreateSmartspaceSessionLocked(mSmartspaceConfig, mSessionId, token);
            mCallbacks.broadcast(
                    callback -> service.registerSmartspaceUpdatesLocked(mSessionId, callback));
        }
    }
}
