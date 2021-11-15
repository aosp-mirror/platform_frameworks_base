/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.appprediction;

import static android.provider.DeviceConfig.NAMESPACE_SYSTEMUI;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppGlobals;
import android.app.prediction.AppPredictionContext;
import android.app.prediction.AppPredictionSessionId;
import android.app.prediction.AppTargetEvent;
import android.app.prediction.IPredictionCallback;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ParceledListSlice;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.provider.DeviceConfig;
import android.service.appprediction.AppPredictionService;
import android.service.appprediction.IPredictionService;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.internal.infra.AbstractRemoteService;
import com.android.server.LocalServices;
import com.android.server.infra.AbstractPerUserSystemService;
import com.android.server.people.PeopleServiceInternal;

/**
 * Per-user instance of {@link AppPredictionManagerService}.
 */
public class AppPredictionPerUserService extends
        AbstractPerUserSystemService<AppPredictionPerUserService, AppPredictionManagerService>
             implements RemoteAppPredictionService.RemoteAppPredictionServiceCallbacks {

    private static final String TAG = AppPredictionPerUserService.class.getSimpleName();
    private static final String PREDICT_USING_PEOPLE_SERVICE_PREFIX =
            "predict_using_people_service_";
    private static final String REMOTE_APP_PREDICTOR_KEY = "remote_app_predictor";


    @Nullable
    @GuardedBy("mLock")
    private RemoteAppPredictionService mRemoteService;

    /**
     * When {@code true}, remote service died but service state is kept so it's restored after
     * the system re-binds to it.
     */
    @GuardedBy("mLock")
    private boolean mZombie;

    @GuardedBy("mLock")
    private final ArrayMap<AppPredictionSessionId, AppPredictionSessionInfo> mSessionInfos =
            new ArrayMap<>();

    protected AppPredictionPerUserService(AppPredictionManagerService master,
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
        // TODO(b/111701043): must check that either the service is from a system component,
        // or it matches a service set by shell cmd (so it can be used on CTS tests and when
        // OEMs are implementing the real service and also verify the proper permissions
        return si;
    }

    @GuardedBy("mLock")
    @Override // from PerUserSystemService
    protected boolean updateLocked(boolean disabled) {
        final boolean enabledChanged = super.updateLocked(disabled);
        if (enabledChanged) {
            if (!isEnabledLocked()) {
                // Clear the remote service for the next call
                mRemoteService = null;
            }
        }
        return enabledChanged;
    }

    /**
     * Notifies the service of a new prediction session.
     */
    @GuardedBy("mLock")
    public void onCreatePredictionSessionLocked(@NonNull AppPredictionContext context,
            @NonNull AppPredictionSessionId sessionId, @NonNull IBinder token) {
        boolean usesPeopleService = DeviceConfig.getBoolean(NAMESPACE_SYSTEMUI,
                PREDICT_USING_PEOPLE_SERVICE_PREFIX + context.getUiSurface(), false);
        if (context.getExtras() != null
                && context.getExtras().getBoolean(REMOTE_APP_PREDICTOR_KEY, false)
                && DeviceConfig.getBoolean(NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.DARK_LAUNCH_REMOTE_PREDICTION_SERVICE_ENABLED, false)
        ) {
            // connect with remote AppPredictionService instead for dark launch
            usesPeopleService = false;
        }
        final boolean serviceExists = resolveService(sessionId, true,
                usesPeopleService, s -> s.onCreatePredictionSession(context, sessionId));
        if (serviceExists && !mSessionInfos.containsKey(sessionId)) {
            final AppPredictionSessionInfo sessionInfo = new AppPredictionSessionInfo(
                    sessionId, context, usesPeopleService, token, () -> {
                synchronized (mLock) {
                    onDestroyPredictionSessionLocked(sessionId);
                }
            });
            if (sessionInfo.linkToDeath()) {
                mSessionInfos.put(sessionId, sessionInfo);
            } else {
                // destroy the session if calling process is already dead
                onDestroyPredictionSessionLocked(sessionId);
            }
        }
    }

    /**
     * Records an app target event to the service.
     */
    @GuardedBy("mLock")
    public void notifyAppTargetEventLocked(@NonNull AppPredictionSessionId sessionId,
            @NonNull AppTargetEvent event) {
        final AppPredictionSessionInfo sessionInfo = mSessionInfos.get(sessionId);
        if (sessionInfo == null) return;
        resolveService(sessionId, false, sessionInfo.mUsesPeopleService,
                s -> s.notifyAppTargetEvent(sessionId, event));
    }

    /**
     * Records when a launch location is shown.
     */
    @GuardedBy("mLock")
    public void notifyLaunchLocationShownLocked(@NonNull AppPredictionSessionId sessionId,
            @NonNull String launchLocation, @NonNull ParceledListSlice targetIds) {
        final AppPredictionSessionInfo sessionInfo = mSessionInfos.get(sessionId);
        if (sessionInfo == null) return;
        resolveService(sessionId, false, sessionInfo.mUsesPeopleService,
                s -> s.notifyLaunchLocationShown(sessionId, launchLocation, targetIds));
    }

    /**
     * Requests the service to sort a list of apps or shortcuts.
     */
    @GuardedBy("mLock")
    public void sortAppTargetsLocked(@NonNull AppPredictionSessionId sessionId,
            @NonNull ParceledListSlice targets, @NonNull IPredictionCallback callback) {
        final AppPredictionSessionInfo sessionInfo = mSessionInfos.get(sessionId);
        if (sessionInfo == null) return;
        resolveService(sessionId, true, sessionInfo.mUsesPeopleService,
                s -> s.sortAppTargets(sessionId, targets, callback));
    }

    /**
     * Registers a callback for continuous updates of predicted apps or shortcuts.
     */
    @GuardedBy("mLock")
    public void registerPredictionUpdatesLocked(@NonNull AppPredictionSessionId sessionId,
            @NonNull IPredictionCallback callback) {
        final AppPredictionSessionInfo sessionInfo = mSessionInfos.get(sessionId);
        if (sessionInfo == null) return;
        final boolean serviceExists = resolveService(sessionId, false,
                sessionInfo.mUsesPeopleService,
                s -> s.registerPredictionUpdates(sessionId, callback));
        if (serviceExists) {
            sessionInfo.addCallbackLocked(callback);
        }
    }

    /**
     * Unregisters a callback for continuous updates of predicted apps or shortcuts.
     */
    @GuardedBy("mLock")
    public void unregisterPredictionUpdatesLocked(@NonNull AppPredictionSessionId sessionId,
            @NonNull IPredictionCallback callback) {
        final AppPredictionSessionInfo sessionInfo = mSessionInfos.get(sessionId);
        if (sessionInfo == null) return;
        final boolean serviceExists = resolveService(sessionId, false,
                sessionInfo.mUsesPeopleService,
                s -> s.unregisterPredictionUpdates(sessionId, callback));
        if (serviceExists) {
            sessionInfo.removeCallbackLocked(callback);
        }
    }

    /**
     * Requests a new set of predicted apps or shortcuts.
     */
    @GuardedBy("mLock")
    public void requestPredictionUpdateLocked(@NonNull AppPredictionSessionId sessionId) {
        final AppPredictionSessionInfo sessionInfo = mSessionInfos.get(sessionId);
        if (sessionInfo == null) return;
        resolveService(sessionId, true, sessionInfo.mUsesPeopleService,
                s -> s.requestPredictionUpdate(sessionId));
    }

    /**
     * Notifies the service of the end of an existing prediction session.
     */
    @GuardedBy("mLock")
    public void onDestroyPredictionSessionLocked(@NonNull AppPredictionSessionId sessionId) {
        if (isDebug()) {
            Slog.d(TAG, "onDestroyPredictionSessionLocked(): sessionId=" + sessionId);
        }
        final AppPredictionSessionInfo sessionInfo = mSessionInfos.remove(sessionId);
        if (sessionInfo == null) return;
        resolveService(sessionId, false, sessionInfo.mUsesPeopleService,
                s -> s.onDestroyPredictionSession(sessionId));
        sessionInfo.destroy();
    }

    @Override
    public void onFailureOrTimeout(boolean timedOut) {
        if (isDebug()) {
            Slog.d(TAG, "onFailureOrTimeout(): timed out=" + timedOut);
        }
        // Do nothing, we are just proxying to the prediction service
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
    public void onServiceDied(RemoteAppPredictionService service) {
        if (isDebug()) {
            Slog.w(TAG, "onServiceDied(): service=" + service);
        }
        synchronized (mLock) {
            mZombie = true;
        }
        // Do nothing, eventually the system will bind to the remote service again...
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

        for (AppPredictionSessionInfo sessionInfo : mSessionInfos.values()) {
            sessionInfo.resurrectSessionLocked(this, sessionInfo.mToken);
        }
    }

    @GuardedBy("mLock")
    @Nullable
    protected boolean resolveService(
            @NonNull final AppPredictionSessionId sessionId,
            boolean sendImmediately,
            boolean usesPeopleService,
            @NonNull final AbstractRemoteService.AsyncRequest<IPredictionService> cb) {
        if (usesPeopleService) {
            final IPredictionService service =
                    LocalServices.getService(PeopleServiceInternal.class);
            if (service != null) {
                try {
                    cb.run(service);
                } catch (RemoteException e) {
                    // Shouldn't happen.
                    Slog.w(TAG, "Failed to invoke service:" + service, e);
                }
            }
            return service != null;
        } else {
            final RemoteAppPredictionService service = getRemoteServiceLocked();
            if (service != null) {
                // TODO(b/155887722): implement a priority system so that latency-sensitive
                // requests gets executed first.
                if (sendImmediately) {
                    service.executeOnResolvedService(cb);
                } else {
                    service.scheduleOnResolvedService(cb);
                }
            }
            return service != null;
        }
    }

    @GuardedBy("mLock")
    @Nullable
    private RemoteAppPredictionService getRemoteServiceLocked() {
        if (mRemoteService == null) {
            final String serviceName = getComponentNameLocked();
            if (serviceName == null) {
                if (mMaster.verbose) {
                    Slog.v(TAG, "getRemoteServiceLocked(): not set");
                }
                return null;
            }
            ComponentName serviceComponent = ComponentName.unflattenFromString(serviceName);

            mRemoteService = new RemoteAppPredictionService(getContext(),
                    AppPredictionService.SERVICE_INTERFACE, serviceComponent, mUserId, this,
                    mMaster.isBindInstantServiceAllowed(), mMaster.verbose);
        }

        return mRemoteService;
    }

    private static final class AppPredictionSessionInfo {
        private static final boolean DEBUG = false;  // Do not submit with true

        @NonNull
        private final AppPredictionSessionId mSessionId;
        @NonNull
        private final AppPredictionContext mPredictionContext;
        private final boolean mUsesPeopleService;
        @NonNull
        final IBinder mToken;
        @NonNull
        final IBinder.DeathRecipient mDeathRecipient;

        private final RemoteCallbackList<IPredictionCallback> mCallbacks =
                new RemoteCallbackList<IPredictionCallback>() {
                    @Override
                    public void onCallbackDied(IPredictionCallback callback) {
                        if (DEBUG) {
                            Slog.d(TAG, "Binder died for session Id=" + mSessionId
                                    + " and callback=" + callback.asBinder());
                        }
                        if (mCallbacks.getRegisteredCallbackCount() == 0) {
                            destroy();
                        }
                    }
                };

        AppPredictionSessionInfo(
                @NonNull final AppPredictionSessionId id,
                @NonNull final AppPredictionContext predictionContext,
                final boolean usesPeopleService,
                @NonNull final IBinder token,
                @NonNull final IBinder.DeathRecipient deathRecipient) {
            if (DEBUG) {
                Slog.d(TAG, "Creating AppPredictionSessionInfo for session Id=" + id);
            }
            mSessionId = id;
            mPredictionContext = predictionContext;
            mUsesPeopleService = usesPeopleService;
            mToken = token;
            mDeathRecipient = deathRecipient;
        }

        void addCallbackLocked(IPredictionCallback callback) {
            if (DEBUG) {
                Slog.d(TAG, "Storing callback for session Id=" + mSessionId
                        + " and callback=" + callback.asBinder());
            }
            mCallbacks.register(callback);
        }

        void removeCallbackLocked(IPredictionCallback callback) {
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

        void resurrectSessionLocked(AppPredictionPerUserService service, IBinder token) {
            int callbackCount = mCallbacks.getRegisteredCallbackCount();
            if (DEBUG) {
                Slog.d(TAG, "Resurrecting remote service (" + service.getRemoteServiceLocked()
                        + ") for session Id=" + mSessionId + " and "
                        + callbackCount + " callbacks.");
            }
            service.onCreatePredictionSessionLocked(mPredictionContext, mSessionId, token);
            mCallbacks.broadcast(
                    callback -> service.registerPredictionUpdatesLocked(mSessionId, callback));
        }
    }
}
