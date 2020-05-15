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
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.provider.DeviceConfig;
import android.service.appprediction.AppPredictionService;
import android.service.appprediction.IPredictionService;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.infra.AbstractRemoteService;
import com.android.server.LocalServices;
import com.android.server.infra.AbstractPerUserSystemService;
import com.android.server.people.PeopleServiceInternal;

import java.util.function.Consumer;

/**
 * Per-user instance of {@link AppPredictionManagerService}.
 */
public class AppPredictionPerUserService extends
        AbstractPerUserSystemService<AppPredictionPerUserService, AppPredictionManagerService>
             implements RemoteAppPredictionService.RemoteAppPredictionServiceCallbacks {

    private static final String TAG = AppPredictionPerUserService.class.getSimpleName();
    private static final String PREDICT_USING_PEOPLE_SERVICE_PREFIX =
            "predict_using_people_service_";

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
            @NonNull AppPredictionSessionId sessionId) {
        if (!mSessionInfos.containsKey(sessionId)) {
            mSessionInfos.put(sessionId, new AppPredictionSessionInfo(sessionId, context,
                    DeviceConfig.getBoolean(NAMESPACE_SYSTEMUI,
                            PREDICT_USING_PEOPLE_SERVICE_PREFIX + context.getUiSurface(), false),
                    this::removeAppPredictionSessionInfo));
        }
        final boolean serviceExists = resolveService(sessionId, s ->
                s.onCreatePredictionSession(context, sessionId), true);
        if (!serviceExists) {
            mSessionInfos.remove(sessionId);
        }
    }

    /**
     * Records an app target event to the service.
     */
    @GuardedBy("mLock")
    public void notifyAppTargetEventLocked(@NonNull AppPredictionSessionId sessionId,
            @NonNull AppTargetEvent event) {
        resolveService(sessionId, s -> s.notifyAppTargetEvent(sessionId, event), false);
    }

    /**
     * Records when a launch location is shown.
     */
    @GuardedBy("mLock")
    public void notifyLaunchLocationShownLocked(@NonNull AppPredictionSessionId sessionId,
            @NonNull String launchLocation, @NonNull ParceledListSlice targetIds) {
        resolveService(sessionId, s ->
                s.notifyLaunchLocationShown(sessionId, launchLocation, targetIds), false);
    }

    /**
     * Requests the service to sort a list of apps or shortcuts.
     */
    @GuardedBy("mLock")
    public void sortAppTargetsLocked(@NonNull AppPredictionSessionId sessionId,
            @NonNull ParceledListSlice targets, @NonNull IPredictionCallback callback) {
        resolveService(sessionId, s -> s.sortAppTargets(sessionId, targets, callback), true);
    }

    /**
     * Registers a callback for continuous updates of predicted apps or shortcuts.
     */
    @GuardedBy("mLock")
    public void registerPredictionUpdatesLocked(@NonNull AppPredictionSessionId sessionId,
            @NonNull IPredictionCallback callback) {
        final boolean serviceExists = resolveService(sessionId, s ->
                s.registerPredictionUpdates(sessionId, callback), false);
        final AppPredictionSessionInfo sessionInfo = mSessionInfos.get(sessionId);
        if (serviceExists && sessionInfo != null) {
            sessionInfo.addCallbackLocked(callback);
        }
    }

    /**
     * Unregisters a callback for continuous updates of predicted apps or shortcuts.
     */
    @GuardedBy("mLock")
    public void unregisterPredictionUpdatesLocked(@NonNull AppPredictionSessionId sessionId,
            @NonNull IPredictionCallback callback) {
        final boolean serviceExists = resolveService(sessionId, s ->
                s.unregisterPredictionUpdates(sessionId, callback), false);
        final AppPredictionSessionInfo sessionInfo = mSessionInfos.get(sessionId);
        if (serviceExists && sessionInfo != null) {
            sessionInfo.removeCallbackLocked(callback);
        }
    }

    /**
     * Requests a new set of predicted apps or shortcuts.
     */
    @GuardedBy("mLock")
    public void requestPredictionUpdateLocked(@NonNull AppPredictionSessionId sessionId) {
        resolveService(sessionId, s -> s.requestPredictionUpdate(sessionId), true);
    }

    /**
     * Notifies the service of the end of an existing prediction session.
     */
    @GuardedBy("mLock")
    public void onDestroyPredictionSessionLocked(@NonNull AppPredictionSessionId sessionId) {
        final boolean serviceExists = resolveService(sessionId, s ->
                s.onDestroyPredictionSession(sessionId), false);
        final AppPredictionSessionInfo sessionInfo = mSessionInfos.get(sessionId);
        if (serviceExists && sessionInfo != null) {
            sessionInfo.destroy();
        }
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
                    // Sanity check - shouldn't happen
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
            sessionInfo.resurrectSessionLocked(this);
        }
    }

    private void removeAppPredictionSessionInfo(AppPredictionSessionId sessionId) {
        if (isDebug()) {
            Slog.d(TAG, "removeAppPredictionSessionInfo(): sessionId=" + sessionId);
        }
        synchronized (mLock) {
            mSessionInfos.remove(sessionId);
        }
    }

    @GuardedBy("mLock")
    @Nullable
    protected boolean resolveService(@NonNull final AppPredictionSessionId sessionId,
            @NonNull final AbstractRemoteService.AsyncRequest<IPredictionService> cb,
            boolean sendImmediately) {
        final AppPredictionSessionInfo sessionInfo = mSessionInfos.get(sessionId);
        if (sessionInfo == null) return false;
        if (sessionInfo.mUsesPeopleService) {
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
        private final Consumer<AppPredictionSessionId> mRemoveSessionInfoAction;

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
                @NonNull final Consumer<AppPredictionSessionId> removeSessionInfoAction) {
            if (DEBUG) {
                Slog.d(TAG, "Creating AppPredictionSessionInfo for session Id=" + id);
            }
            mSessionId = id;
            mPredictionContext = predictionContext;
            mUsesPeopleService = usesPeopleService;
            mRemoveSessionInfoAction = removeSessionInfoAction;
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

        void destroy() {
            if (DEBUG) {
                Slog.d(TAG, "Removing all callbacks for session Id=" + mSessionId
                        + " and " + mCallbacks.getRegisteredCallbackCount() + " callbacks.");
            }
            mCallbacks.kill();
            mRemoveSessionInfoAction.accept(mSessionId);
        }

        void resurrectSessionLocked(AppPredictionPerUserService service) {
            int callbackCount = mCallbacks.getRegisteredCallbackCount();
            if (DEBUG) {
                Slog.d(TAG, "Resurrecting remote service (" + service.getRemoteServiceLocked()
                        + ") for session Id=" + mSessionId + " and "
                        + callbackCount + " callbacks.");
            }
            service.onCreatePredictionSessionLocked(mPredictionContext, mSessionId);
            mCallbacks.broadcast(
                    callback -> service.registerPredictionUpdatesLocked(mSessionId, callback));
        }
    }
}
