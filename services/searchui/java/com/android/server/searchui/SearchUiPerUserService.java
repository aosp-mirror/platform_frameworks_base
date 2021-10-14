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

package com.android.server.searchui;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppGlobals;
import android.app.search.ISearchCallback;
import android.app.search.Query;
import android.app.search.SearchContext;
import android.app.search.SearchSessionId;
import android.app.search.SearchTargetEvent;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.service.search.ISearchUiService;
import android.service.search.SearchUiService;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.infra.AbstractRemoteService;
import com.android.server.infra.AbstractPerUserSystemService;

/**
 * Per-user instance of {@link SearchUiManagerService}.
 */
public class SearchUiPerUserService extends
        AbstractPerUserSystemService<SearchUiPerUserService, SearchUiManagerService>
             implements RemoteSearchUiService.RemoteSearchUiServiceCallbacks {

    private static final String TAG = SearchUiPerUserService.class.getSimpleName();

    @Nullable
    @GuardedBy("mLock")
    private RemoteSearchUiService mRemoteService;

    /**
     * When {@code true}, remote service died but service state is kept so it's restored after
     * the system re-binds to it.
     */
    @GuardedBy("mLock")
    private boolean mZombie;

    @GuardedBy("mLock")
    private final ArrayMap<SearchSessionId, SearchSessionInfo> mSessionInfos =
            new ArrayMap<>();

    protected SearchUiPerUserService(SearchUiManagerService master,
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
                updateRemoteServiceLocked();
            }
        }
        return enabledChanged;
    }

    /**
     * Notifies the service of a new search session.
     */
    @GuardedBy("mLock")
    public void onCreateSearchSessionLocked(@NonNull SearchContext context,
            @NonNull SearchSessionId sessionId, @NonNull IBinder token) {
        final boolean serviceExists = resolveService(sessionId,
                s -> s.onCreateSearchSession(context, sessionId));

        if (serviceExists && !mSessionInfos.containsKey(sessionId)) {
            final SearchSessionInfo sessionInfo = new SearchSessionInfo(
                    sessionId, context, token, () -> {
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
     * Records an app target event to the service.
     */
    @GuardedBy("mLock")
    public void notifyLocked(@NonNull SearchSessionId sessionId, @NonNull Query query,
            @NonNull SearchTargetEvent event) {
        final SearchSessionInfo sessionInfo = mSessionInfos.get(sessionId);
        if (sessionInfo == null) return;
        resolveService(sessionId,
                s -> s.onNotifyEvent(sessionId, query, event));
    }

    /**
     * Requests the service to return search results of an input query.
     */
    @GuardedBy("mLock")
    public void queryLocked(@NonNull SearchSessionId sessionId,
            @NonNull Query input, @NonNull ISearchCallback callback) {
        final SearchSessionInfo sessionInfo = mSessionInfos.get(sessionId);
        if (sessionInfo == null) return;
        resolveService(sessionId,
                s -> s.onQuery(sessionId, input, callback));
    }

    /**
     * Notifies the service of the end of an existing search session.
     */
    @GuardedBy("mLock")
    public void onDestroyLocked(@NonNull SearchSessionId sessionId) {
        if (isDebug()) {
            Slog.d(TAG, "onDestroyLocked(): sessionId=" + sessionId);
        }
        final SearchSessionInfo sessionInfo = mSessionInfos.remove(sessionId);
        if (sessionInfo == null) return;
        resolveService(sessionId,
                s -> s.onDestroy(sessionId));
        sessionInfo.destroy();
    }

    @Override
    public void onFailureOrTimeout(boolean timedOut) {
        if (isDebug()) {
            Slog.d(TAG, "onFailureOrTimeout(): timed out=" + timedOut);
        }
        // Do nothing, we are just proxying to the search ui service
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
    public void onServiceDied(RemoteSearchUiService service) {
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

        for (SearchSessionInfo sessionInfo : mSessionInfos.values()) {
            sessionInfo.resurrectSessionLocked(this, sessionInfo.mToken);
        }
    }

    @GuardedBy("mLock")
    @Nullable
    protected boolean resolveService(
            @NonNull final SearchSessionId sessionId,
            @NonNull final AbstractRemoteService.AsyncRequest<ISearchUiService> cb) {

        final RemoteSearchUiService service = getRemoteServiceLocked();
        if (service != null) {
            service.executeOnResolvedService(cb);
        }
        return service != null;
    }

    @GuardedBy("mLock")
    @Nullable
    private RemoteSearchUiService getRemoteServiceLocked() {
        if (mRemoteService == null) {
            final String serviceName = getComponentNameLocked();
            if (serviceName == null) {
                if (mMaster.verbose) {
                    Slog.v(TAG, "getRemoteServiceLocked(): not set");
                }
                return null;
            }
            ComponentName serviceComponent = ComponentName.unflattenFromString(serviceName);

            mRemoteService = new RemoteSearchUiService(getContext(),
                    SearchUiService.SERVICE_INTERFACE, serviceComponent, mUserId, this,
                    mMaster.isBindInstantServiceAllowed(), mMaster.verbose);
        }

        return mRemoteService;
    }

    private static final class SearchSessionInfo {
        private static final boolean DEBUG = true;  // Do not submit with true

        @NonNull
        private final SearchSessionId mSessionId;
        @NonNull
        private final SearchContext mSearchContext;
        @NonNull
        final IBinder mToken;
        @NonNull
        final IBinder.DeathRecipient mDeathRecipient;

        private final RemoteCallbackList<ISearchCallback> mCallbacks =
                new RemoteCallbackList<ISearchCallback>() {
                    @Override
                    public void onCallbackDied(ISearchCallback callback) {
                        if (DEBUG) {
                            Slog.d(TAG, "Binder died for session Id=" + mSessionId
                                    + " and callback=" + callback.asBinder());
                        }
                        if (mCallbacks.getRegisteredCallbackCount() == 0) {
                            destroy();
                        }
                    }
                };

        SearchSessionInfo(
                @NonNull final SearchSessionId id,
                @NonNull final SearchContext context,
                @NonNull final IBinder token,
                @NonNull final IBinder.DeathRecipient deathRecipient) {
            if (DEBUG) {
                Slog.d(TAG, "Creating SearchSessionInfo for session Id=" + id);
            }
            mSessionId = id;
            mSearchContext = context;
            mToken = token;
            mDeathRecipient = deathRecipient;
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

        void resurrectSessionLocked(SearchUiPerUserService service, IBinder token) {
            int callbackCount = mCallbacks.getRegisteredCallbackCount();
            if (DEBUG) {
                Slog.d(TAG, "Resurrecting remote service (" + service.getRemoteServiceLocked()
                        + ") for session Id=" + mSessionId + " and "
                        + callbackCount + " callbacks.");
            }
            service.onCreateSearchSessionLocked(mSearchContext, mSessionId, token);
        }
    }
}
