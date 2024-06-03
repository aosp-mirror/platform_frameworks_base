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

package com.android.server.credentials;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.credentials.Credential;
import android.credentials.CredentialManager;
import android.credentials.CredentialProviderInfo;
import android.credentials.selection.ProviderData;
import android.credentials.selection.ProviderPendingIntentResponse;
import android.os.ICancellationSignal;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.credentials.metrics.ProviderSessionMetric;

import java.util.UUID;

/**
 * Provider session storing the state of provider response and ui entries.
 *
 * @param <T> The request to be sent to the provider
 * @param <R> The response to be expected from the provider
 */
public abstract class ProviderSession<T, R>
        implements RemoteCredentialService.ProviderCallbacks<R> {

    private static final String TAG = CredentialManager.TAG;

    @NonNull
    protected final Context mContext;
    @NonNull
    protected final ComponentName mComponentName;
    @Nullable
    protected final CredentialProviderInfo mProviderInfo;
    @Nullable
    protected final RemoteCredentialService mRemoteCredentialService;
    @NonNull
    protected final int mUserId;
    @NonNull
    protected Status mStatus = Status.NOT_STARTED;
    @Nullable
    protected final ProviderInternalCallback mCallbacks;
    @Nullable
    protected Credential mFinalCredentialResponse;
    @Nullable
    protected ICancellationSignal mProviderCancellationSignal;
    @NonNull
    protected final T mProviderRequest;
    @Nullable
    protected R mProviderResponse;
    @NonNull
    protected Boolean mProviderResponseSet = false;
    @NonNull
    protected final ProviderSessionMetric mProviderSessionMetric;
    @NonNull
    private int mProviderSessionUid;

    enum CredentialsSource {
        REMOTE_PROVIDER,
        REGISTRY,
        AUTH_ENTRY
    }

    /**
     * Returns true if the given status reflects that the provider state is ready to be shown
     * on the credMan UI.
     */
    public static boolean isUiInvokingStatus(Status status) {
        return status == Status.CREDENTIALS_RECEIVED || status == Status.SAVE_ENTRIES_RECEIVED
                || status == Status.NO_CREDENTIALS_FROM_AUTH_ENTRY;
    }

    /**
     * Returns true if the given status reflects that the provider is waiting for a remote
     * response.
     */
    public static boolean isStatusWaitingForRemoteResponse(Status status) {
        return status == Status.PENDING;
    }

    /**
     * Returns true if the given status means that the provider session must be terminated.
     */
    public static boolean isTerminatingStatus(Status status) {
        return status == Status.CANCELED || status == Status.SERVICE_DEAD;
    }

    /**
     * Returns true if the given status reflects that the provider is done getting the response,
     * and is ready to return the final credential back to the user.
     */
    public static boolean isCompletionStatus(Status status) {
        return status == Status.COMPLETE || status == Status.EMPTY_RESPONSE;
    }

    /**
     * Gives access to the objects metric collectors.
     */
    public ProviderSessionMetric getProviderSessionMetric() {
        return this.mProviderSessionMetric;
    }

    /**
     * Interface to be implemented by any class that wishes to get a callback when a particular
     * provider session's status changes. Typically, implemented by the {@link RequestSession}
     * class.
     *
     * @param <V> the type of the final response expected
     */
    public interface ProviderInternalCallback<V> {
        /** Called when status changes. */
        void onProviderStatusChanged(Status status, ComponentName componentName,
                CredentialsSource source);

        /** Called when the final credential is received through an entry selection. */
        void onFinalResponseReceived(ComponentName componentName, V response);

        /** Called when an error is received through an entry selection. */
        void onFinalErrorReceived(ComponentName componentName, String errorType,
                @Nullable String message);
    }

    protected ProviderSession(@NonNull Context context,
            @NonNull T providerRequest,
            @Nullable ProviderInternalCallback callbacks,
            @NonNull ComponentName componentName,
            @NonNull int userId,
            @Nullable RemoteCredentialService remoteCredentialService) {
        mContext = context;
        mProviderInfo = null;
        mProviderRequest = providerRequest;
        mCallbacks = callbacks;
        mUserId = userId;
        mComponentName = componentName;
        mRemoteCredentialService = remoteCredentialService;
        mProviderSessionUid = MetricUtilities.getPackageUid(mContext, mComponentName, userId);
        mProviderSessionMetric = new ProviderSessionMetric(
                ((RequestSession) mCallbacks).mRequestSessionMetric.getSessionIdTrackTwo());
    }

    /** Provider status at various states of the provider session. */
    enum Status {
        NOT_STARTED,
        PENDING,
        CREDENTIALS_RECEIVED,
        SERVICE_DEAD,
        SAVE_ENTRIES_RECEIVED,
        CANCELED,
        EMPTY_RESPONSE,
        NO_CREDENTIALS_FROM_AUTH_ENTRY,
        COMPLETE
    }

    protected static String generateUniqueId() {
        return UUID.randomUUID().toString();
    }

    public Credential getFinalCredentialResponse() {
        return mFinalCredentialResponse;
    }

    /** Propagates cancellation signal to the remote provider service. */
    public void cancelProviderRemoteSession() {
        try {
            if (mProviderCancellationSignal != null) {
                mProviderCancellationSignal.cancel();
            }
            setStatus(Status.CANCELED);
        } catch (RemoteException e) {
            Slog.e(TAG, "Issue while cancelling provider session: ", e);
        }
    }

    protected void setStatus(@NonNull Status status) {
        mStatus = status;
    }

    @NonNull
    protected Status getStatus() {
        return mStatus;
    }

    @NonNull
    protected ComponentName getComponentName() {
        return mComponentName;
    }

    @Nullable
    protected RemoteCredentialService getRemoteCredentialService() {
        return mRemoteCredentialService;
    }

    /** Updates the status . */
    protected void updateStatusAndInvokeCallback(@NonNull Status status,
            CredentialsSource source) {
        setStatus(status);
        boolean isPrimary = mProviderInfo != null && mProviderInfo.isPrimary();
        mProviderSessionMetric.collectCandidateMetricUpdate(isTerminatingStatus(status)
                        || isStatusWaitingForRemoteResponse(status),
                isCompletionStatus(status) || isUiInvokingStatus(status),
                mProviderSessionUid,
                /*isAuthEntry*/source == CredentialsSource.AUTH_ENTRY,
                /*isPrimary*/isPrimary);
        mCallbacks.onProviderStatusChanged(status, mComponentName, source);
    }
    /** Common method that transfers metrics from the init phase to candidates */
    protected void startCandidateMetrics() {
        mProviderSessionMetric.collectCandidateMetricSetupViaInitialMetric(
                ((RequestSession) mCallbacks).mRequestSessionMetric.getInitialPhaseMetric());
    }

    /** Get the request to be sent to the provider. */
    protected T getProviderRequest() {
        return mProviderRequest;
    }

    /** Returns whether the provider response is set. */
    protected Boolean isProviderResponseSet() {
        return mProviderResponse != null || mProviderResponseSet;
    }

    protected void invokeCallbackWithError(String errorType, @Nullable String errorMessage) {
        // TODO: Determine what the error message should be
        mCallbacks.onFinalErrorReceived(mComponentName, errorType, errorMessage);
    }

    /** Update the response state stored with the provider session. */
    @Nullable
    protected R getProviderResponse() {
        return mProviderResponse;
    }

    protected boolean enforceRemoteEntryRestrictions(
            @Nullable ComponentName expectedRemoteEntryProviderService) {
        // Check if the service is the one set by the OEM. If not silently reject this entry
        if (!mComponentName.equals(expectedRemoteEntryProviderService)) {
            Slog.w(TAG, "Remote entry being dropped as it is not from the service "
                    + "configured by the OEM.");
            return false;
        }
        // Check if the service has the hybrid permission .If not, silently reject this entry.
        // This check is in addition to the permission check happening in the provider's process.
        try {
            ApplicationInfo appInfo = mContext.getPackageManager().getApplicationInfo(
                    mComponentName.getPackageName(),
                    PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_SYSTEM_ONLY));
            if (appInfo != null
                    && mContext.checkPermission(
                    Manifest.permission.PROVIDE_REMOTE_CREDENTIALS,
                    /*pId=*/-1, appInfo.uid) == PackageManager.PERMISSION_GRANTED) {
                return true;
            }
        } catch (SecurityException | PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Error getting info for " + mComponentName.flattenToString(), e);
            return false;
        }
        return false;
    }

    /**
     * Should be overridden to prepare, and stores state for {@link ProviderData} to be
     * shown on the UI.
     */
    @Nullable
    protected abstract ProviderData prepareUiData();

    /** Should be overridden to handle the selected entry from the UI. */
    protected abstract void onUiEntrySelected(String entryType, String entryId,
            ProviderPendingIntentResponse providerPendingIntentResponse);

    /**
     * Should be overridden to invoke the provider at a defined location. Helpful for
     * situations such as metric generation.
     */
    protected abstract void invokeSession();
}
