/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.cloudsearch;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppGlobals;
import android.app.cloudsearch.ICloudSearchManagerCallback;
import android.app.cloudsearch.SearchRequest;
import android.app.cloudsearch.SearchResponse;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.RemoteException;
import android.service.cloudsearch.CloudSearchService;
import android.service.cloudsearch.ICloudSearchService;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.infra.AbstractRemoteService;
import com.android.server.CircularQueue;
import com.android.server.infra.AbstractPerUserSystemService;

/**
 * Per-user instance of {@link CloudSearchManagerService}.
 */
public class CloudSearchPerUserService extends
        AbstractPerUserSystemService<CloudSearchPerUserService, CloudSearchManagerService>
        implements RemoteCloudSearchService.RemoteCloudSearchServiceCallbacks {

    private static final String TAG = CloudSearchPerUserService.class.getSimpleName();
    private static final int QUEUE_SIZE = 10;
    @GuardedBy("mLock")
    private final CircularQueue<String, CloudSearchCallbackInfo> mCallbackQueue =
            new CircularQueue<>(QUEUE_SIZE);
    private final String mServiceName;
    private final ComponentName mRemoteComponentName;
    @Nullable
    @GuardedBy("mLock")
    private RemoteCloudSearchService mRemoteService;
    /**
     * When {@code true}, remote service died but service state is kept so it's restored after
     * the system re-binds to it.
     */
    @GuardedBy("mLock")
    private boolean mZombie;

    protected CloudSearchPerUserService(CloudSearchManagerService master,
            Object lock, int userId, String serviceName) {
        super(master, lock, userId);
        mServiceName = serviceName;
        mRemoteComponentName = ComponentName.unflattenFromString(mServiceName);
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
     * Notifies the service of a new cloudsearch session.
     */
    @GuardedBy("mLock")
    public void onSearchLocked(@NonNull SearchRequest searchRequest,
            @NonNull ICloudSearchManagerCallback callback) {
        if (mRemoteComponentName == null) {
            return;
        }

        String filterList = searchRequest.getSearchConstraints().containsKey(
                SearchRequest.CONSTRAINT_SEARCH_PROVIDER_FILTER)
                ? searchRequest.getSearchConstraints().getString(
                SearchRequest.CONSTRAINT_SEARCH_PROVIDER_FILTER) : "";

        String remoteServicePackageName = mRemoteComponentName.getPackageName();
        // By default, all providers are marked as wanted.
        boolean wantedProvider = true;
        if (filterList.length() > 0) {
            // If providers are specified by the client,
            wantedProvider = false;
            String[] providersSpecified = filterList.split(";");
            for (int i = 0; i < providersSpecified.length; i++) {
                if (providersSpecified[i].equals(remoteServicePackageName)) {
                    wantedProvider = true;
                    break;
                }
            }
        }
        // If the provider was not requested by the Client, the request will not be sent to the
        // provider.
        if (!wantedProvider) {
            // TODO(216520546) Send a failure callback to the client.
            return;
        }
        final boolean serviceExists = resolveService(searchRequest,
                s -> s.onSearch(searchRequest));
        String requestId = searchRequest.getRequestId();
        if (serviceExists && !mCallbackQueue.containsKey(requestId)) {
            final CloudSearchCallbackInfo sessionInfo = new CloudSearchCallbackInfo(
                    requestId, searchRequest, callback, callback.asBinder(), () -> {
                synchronized (mLock) {
                    onDestroyLocked(requestId);
                }
            });
            if (sessionInfo.linkToDeath()) {
                CloudSearchCallbackInfo removedInfo = mCallbackQueue.put(requestId, sessionInfo);
                if (removedInfo != null) {
                    removedInfo.destroy();
                }
            } else {
                // destroy the session if calling process is already dead
                onDestroyLocked(requestId);
            }
        }
    }

    /**
     * Used to return results back to the clients.
     */
    @GuardedBy("mLock")
    public void onReturnResultsLocked(@NonNull IBinder token,
            @NonNull String requestId,
            @NonNull SearchResponse response) {
        if (mRemoteService == null) {
            return;
        }
        ICloudSearchService serviceInterface = mRemoteService.getServiceInterface();
        if (serviceInterface == null || token != serviceInterface.asBinder()) {
            return;
        }
        if (mCallbackQueue.containsKey(requestId)) {
            response.setSource(mServiceName);
            final CloudSearchCallbackInfo sessionInfo = mCallbackQueue.getElement(requestId);
            try {
                if (response.getStatusCode() == SearchResponse.SEARCH_STATUS_OK) {
                    sessionInfo.mCallback.onSearchSucceeded(response);
                } else {
                    sessionInfo.mCallback.onSearchFailed(response);
                }
            } catch (RemoteException e) {
                if (mMaster.debug) {
                    Slog.e(TAG, "Exception in posting results");
                    e.printStackTrace();
                }
                onDestroyLocked(requestId);
            }
        }
    }

    /**
     * Notifies the server about the end of an existing cloudsearch session.
     */
    @GuardedBy("mLock")
    public void onDestroyLocked(@NonNull String requestId) {
        if (isDebug()) {
            Slog.d(TAG, "onDestroyLocked(): requestId=" + requestId);
        }
        final CloudSearchCallbackInfo sessionInfo = mCallbackQueue.removeElement(requestId);
        if (sessionInfo != null) {
            sessionInfo.destroy();
        }
    }

    @Override
    public void onFailureOrTimeout(boolean timedOut) {
        if (isDebug()) {
            Slog.d(TAG, "onFailureOrTimeout(): timed out=" + timedOut);
        }
        // Do nothing, we are just proxying to the cloudsearch service
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
    public void onServiceDied(RemoteCloudSearchService service) {
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
        final int numCallbacks = mCallbackQueue.size();
        if (isDebug()) {
            Slog.d(TAG, "Resurrecting remote service (" + mRemoteService + ") on "
                    + numCallbacks + " requests.");
        }

        for (CloudSearchCallbackInfo callbackInfo : mCallbackQueue.values()) {
            callbackInfo.resurrectSessionLocked(this, callbackInfo.mToken);
        }
    }

    @GuardedBy("mLock")
    @Nullable
    protected boolean resolveService(
            @NonNull final SearchRequest requestId,
            @NonNull final AbstractRemoteService.AsyncRequest<ICloudSearchService> cb) {

        final RemoteCloudSearchService service = getRemoteServiceLocked();
        if (service != null) {
            service.executeOnResolvedService(cb);
        }
        return service != null;
    }

    @GuardedBy("mLock")
    @Nullable
    private RemoteCloudSearchService getRemoteServiceLocked() {
        if (mRemoteService == null) {
            final String serviceName = getComponentNameForMultipleLocked(mServiceName);
            if (serviceName == null) {
                if (mMaster.verbose) {
                    Slog.v(TAG, "getRemoteServiceLocked(): not set");
                }
                return null;
            }
            ComponentName serviceComponent = ComponentName.unflattenFromString(serviceName);

            mRemoteService = new RemoteCloudSearchService(getContext(),
                    CloudSearchService.SERVICE_INTERFACE, serviceComponent, mUserId, this,
                    mMaster.isBindInstantServiceAllowed(), mMaster.verbose);
        }

        return mRemoteService;
    }

    private static final class CloudSearchCallbackInfo {
        private static final boolean DEBUG = false;  // Do not submit with true
        @NonNull
        final IBinder mToken;
        @NonNull
        final IBinder.DeathRecipient mDeathRecipient;
        @NonNull
        private final String mRequestId;
        @NonNull
        private final SearchRequest mSearchRequest;
        private final ICloudSearchManagerCallback mCallback;

        CloudSearchCallbackInfo(
                @NonNull final String id,
                @NonNull final SearchRequest request,
                @NonNull final ICloudSearchManagerCallback callback,
                @NonNull final IBinder token,
                @NonNull final IBinder.DeathRecipient deathRecipient) {
            if (DEBUG) {
                Slog.d(TAG, "Creating CloudSearchSessionInfo for session Id=" + id);
            }
            mRequestId = id;
            mSearchRequest = request;
            mCallback = callback;
            mToken = token;
            mDeathRecipient = deathRecipient;
        }

        boolean linkToDeath() {
            try {
                mToken.linkToDeath(mDeathRecipient, 0);
            } catch (RemoteException e) {
                if (DEBUG) {
                    Slog.w(TAG, "Caller is dead before session can be started, requestId: "
                            + mRequestId);
                }
                return false;
            }
            return true;
        }

        void destroy() {
            if (DEBUG) {
                Slog.d(TAG, "Removing callback for Request Id=" + mRequestId);
            }
            if (mToken != null) {
                mToken.unlinkToDeath(mDeathRecipient, 0);
            }
            mCallback.asBinder().unlinkToDeath(mDeathRecipient, 0);
        }

        void resurrectSessionLocked(CloudSearchPerUserService service, IBinder token) {
            if (DEBUG) {
                Slog.d(TAG, "Resurrecting remote service (" + service.getRemoteServiceLocked()
                        + ") for request Id=" + mRequestId);
            }
            service.onSearchLocked(mSearchRequest, mCallback);
        }
    }
}
