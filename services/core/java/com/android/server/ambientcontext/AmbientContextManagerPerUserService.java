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

package com.android.server.ambientcontext;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.AppGlobals;
import android.app.BroadcastOptions;
import android.app.PendingIntent;
import android.app.ambientcontext.AmbientContextEvent;
import android.app.ambientcontext.AmbientContextEventRequest;
import android.app.ambientcontext.AmbientContextManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.service.ambientcontext.AmbientContextDetectionResult;
import android.service.ambientcontext.AmbientContextDetectionServiceStatus;
import android.text.TextUtils;
import android.util.IndentingPrintWriter;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.infra.AbstractPerUserSystemService;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Per-user manager service for {@link AmbientContextEvent}s.
 */
final class AmbientContextManagerPerUserService extends
        AbstractPerUserSystemService<AmbientContextManagerPerUserService,
                AmbientContextManagerService> {
    private static final String TAG = AmbientContextManagerPerUserService.class.getSimpleName();

    @Nullable
    @VisibleForTesting
    RemoteAmbientContextDetectionService mRemoteService;

    private ComponentName mComponentName;

    AmbientContextManagerPerUserService(
            @NonNull AmbientContextManagerService master, Object lock, @UserIdInt int userId) {
        super(master, lock, userId);
    }

    void destroyLocked() {
        Slog.d(TAG, "Trying to cancel the remote request. Reason: Service destroyed.");
        if (mRemoteService != null) {
            synchronized (mLock) {
                mRemoteService.unbind();
                mRemoteService = null;
            }
        }
    }

    @GuardedBy("mLock")
    private void ensureRemoteServiceInitiated() {
        if (mRemoteService == null) {
            mRemoteService = new RemoteAmbientContextDetectionService(
                    getContext(), mComponentName, getUserId());
        }
    }

    /**
     * get the currently bound component name.
     */
    @VisibleForTesting
    ComponentName getComponentName() {
        return mComponentName;
    }


    /**
     * Resolves and sets up the service if it had not been done yet. Returns true if the service
     * is available.
     */
    @GuardedBy("mLock")
    @VisibleForTesting
    boolean setUpServiceIfNeeded() {
        if (mComponentName == null) {
            mComponentName = updateServiceInfoLocked();
        }
        if (mComponentName == null) {
            return false;
        }

        ServiceInfo serviceInfo;
        try {
            serviceInfo = AppGlobals.getPackageManager().getServiceInfo(
                    mComponentName, 0, mUserId);
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException while setting up service");
            return false;
        }
        return serviceInfo != null;
    }

    @Override
    protected ServiceInfo newServiceInfoLocked(@NonNull ComponentName serviceComponent)
            throws PackageManager.NameNotFoundException {
        ServiceInfo serviceInfo;
        try {
            serviceInfo = AppGlobals.getPackageManager().getServiceInfo(serviceComponent,
                    0, mUserId);
            if (serviceInfo != null) {
                final String permission = serviceInfo.permission;
                if (!Manifest.permission.BIND_AMBIENT_CONTEXT_DETECTION_SERVICE.equals(
                        permission)) {
                    throw new SecurityException(String.format(
                            "Service %s requires %s permission. Found %s permission",
                            serviceInfo.getComponentName(),
                            Manifest.permission.BIND_AMBIENT_CONTEXT_DETECTION_SERVICE,
                            serviceInfo.permission));
                }
            }
        } catch (RemoteException e) {
            throw new PackageManager.NameNotFoundException(
                    "Could not get service for " + serviceComponent);
        }
        return serviceInfo;
    }

    @Override
    protected void dumpLocked(@NonNull String prefix, @NonNull PrintWriter pw) {
        synchronized (super.mLock) {
            super.dumpLocked(prefix, pw);
        }
        if (mRemoteService != null) {
            mRemoteService.dump("", new IndentingPrintWriter(pw, "  "));
        }
    }

    /**
     * Handles client registering as an observer. Only one registration is supported per app
     * package. A new registration from the same package will overwrite the previous registration.
     */
    public void onRegisterObserver(AmbientContextEventRequest request,
            PendingIntent pendingIntent, RemoteCallback clientStatusCallback) {
        synchronized (mLock) {
            if (!setUpServiceIfNeeded()) {
                Slog.w(TAG, "Detection service is not available at this moment.");
                sendStatusCallback(
                        clientStatusCallback,
                        AmbientContextManager.STATUS_SERVICE_UNAVAILABLE);
                return;
            }

            // Register package and add to existing ClientRequests cache
            startDetection(request, pendingIntent.getCreatorPackage(),
                    createDetectionResultRemoteCallback(), clientStatusCallback);
            mMaster.newClientAdded(mUserId, request, pendingIntent, clientStatusCallback);
        }
    }

    /**
     * Returns a RemoteCallback that handles the status from the detection service, and
     * sends results to the client callback.
     */
    private RemoteCallback getServerStatusCallback(RemoteCallback clientStatusCallback) {
        return new RemoteCallback(result -> {
            AmbientContextDetectionServiceStatus serviceStatus =
                    (AmbientContextDetectionServiceStatus) result.get(
                            AmbientContextDetectionServiceStatus.STATUS_RESPONSE_BUNDLE_KEY);
            final long token = Binder.clearCallingIdentity();
            try {
                String packageName = serviceStatus.getPackageName();
                Bundle bundle = new Bundle();
                bundle.putInt(
                        AmbientContextManager.STATUS_RESPONSE_BUNDLE_KEY,
                        serviceStatus.getStatusCode());
                clientStatusCallback.sendResult(bundle);
                int statusCode = serviceStatus.getStatusCode();
                Slog.i(TAG, "Got detection status of " + statusCode
                        + " for " + packageName);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        });
    }

    @VisibleForTesting
    void startDetection(AmbientContextEventRequest request, String callingPackage,
            RemoteCallback detectionResultCallback, RemoteCallback clientStatusCallback) {
        Slog.d(TAG, "Requested detection of " + request.getEventTypes());
        synchronized (mLock) {
            if (setUpServiceIfNeeded()) {
                ensureRemoteServiceInitiated();
                mRemoteService.startDetection(request, callingPackage, detectionResultCallback,
                        getServerStatusCallback(clientStatusCallback));
            } else {
                Slog.w(TAG, "No valid component found for AmbientContextDetectionService");
                sendStatusToCallback(clientStatusCallback,
                        AmbientContextManager.STATUS_NOT_SUPPORTED);
            }
        }
    }

    /**
     * Sends an intent with a status code and empty events.
     */
    void sendStatusCallback(RemoteCallback statusCallback, int statusCode) {
        Bundle bundle = new Bundle();
        bundle.putInt(
                AmbientContextManager.STATUS_RESPONSE_BUNDLE_KEY,
                statusCode);
        statusCallback.sendResult(bundle);
    }

    /**
     * Unregisters the client from all previously registered events by removing from the
     * mExistingRequests map, and unregister events from the service if those events are not
     * requested by other apps.
     */
    public void onUnregisterObserver(String callingPackage) {
        synchronized (mLock) {
            stopDetection(callingPackage);
            mMaster.clientRemoved(mUserId, callingPackage);
        }
    }

    public void onQueryServiceStatus(int[] eventTypes, String callingPackage,
            RemoteCallback statusCallback) {
        Slog.d(TAG, "Query event status of " + Arrays.toString(eventTypes)
                + " for " + callingPackage);
        synchronized (mLock) {
            if (!setUpServiceIfNeeded()) {
                Slog.w(TAG, "Detection service is not available at this moment.");
                sendStatusToCallback(statusCallback,
                        AmbientContextManager.STATUS_NOT_SUPPORTED);
                return;
            }
            ensureRemoteServiceInitiated();
            mRemoteService.queryServiceStatus(
                    eventTypes,
                    callingPackage,
                    getServerStatusCallback(statusCallback));
        }
    }

    public void onStartConsentActivity(int[] eventTypes, String callingPackage) {
        Slog.d(TAG, "Opening consent activity of " + Arrays.toString(eventTypes)
                + " for " + callingPackage);

        // Look up the recent task from the callingPackage
        ActivityManager.RecentTaskInfo task;
        ParceledListSlice<ActivityManager.RecentTaskInfo> recentTasks;
        int userId = getUserId();
        try {
            recentTasks = ActivityTaskManager.getService().getRecentTasks(/*maxNum*/1,
                    /*flags*/ 0, userId);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to query recent tasks!");
            return;
        }

        if ((recentTasks == null) || recentTasks.getList().isEmpty()) {
            Slog.e(TAG, "Recent task list is empty!");
            return;
        }

        task = recentTasks.getList().get(0);
        if (!callingPackage.equals(task.topActivityInfo.packageName)) {
            Slog.e(TAG, "Recent task package name: " + task.topActivityInfo.packageName
                    + " doesn't match with client package name: " + callingPackage);
            return;
        }

        // Start activity as the same task from the callingPackage
        ComponentName consentComponent = getConsentComponent();
        if (consentComponent == null) {
            Slog.e(TAG, "Consent component not found!");
            return;
        }

        Slog.d(TAG, "Starting consent activity for " + callingPackage);
        Intent intent = new Intent();
        final long identity = Binder.clearCallingIdentity();
        try {
            Context context = getContext();
            String packageNameExtraKey = context.getResources().getString(
                    com.android.internal.R.string.config_ambientContextPackageNameExtraKey);
            String eventArrayExtraKey = context.getResources().getString(
                    com.android.internal.R.string.config_ambientContextEventArrayExtraKey);

            // Create consent activity intent with the calling package name and requested events
            intent.setComponent(consentComponent);
            if (packageNameExtraKey != null) {
                intent.putExtra(packageNameExtraKey, callingPackage);
            } else {
                Slog.d(TAG, "Missing packageNameExtraKey for consent activity");
            }
            if (eventArrayExtraKey != null) {
                intent.putExtra(eventArrayExtraKey, eventTypes);
            } else {
                Slog.d(TAG, "Missing eventArrayExtraKey for consent activity");
            }

            // Set parent to the calling app's task
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchTaskId(task.taskId);
            context.startActivityAsUser(intent, options.toBundle(), context.getUser());
        } catch (ActivityNotFoundException e) {
            Slog.e(TAG, "unable to start consent activity");
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Returns the consent activity component from config lookup.
     */
    private ComponentName getConsentComponent() {
        Context context = getContext();
        String consentComponent = context.getResources().getString(
                    com.android.internal.R.string.config_defaultAmbientContextConsentComponent);
        if (TextUtils.isEmpty(consentComponent)) {
            return null;
        }
        Slog.i(TAG, "Consent component name: " + consentComponent);
        return ComponentName.unflattenFromString(consentComponent);
    }

    /**
     * Sends the result response with the specified status to the callback.
     */
    void sendStatusToCallback(RemoteCallback callback,
                    @AmbientContextManager.StatusCode int status) {
        Bundle bundle = new Bundle();
        bundle.putInt(
                AmbientContextManager.STATUS_RESPONSE_BUNDLE_KEY,
                status);
        callback.sendResult(bundle);
    }

    @VisibleForTesting
    void stopDetection(String packageName) {
        Slog.d(TAG, "Stop detection for " + packageName);
        synchronized (mLock) {
            if (mComponentName != null) {
                ensureRemoteServiceInitiated();
                mRemoteService.stopDetection(packageName);
            }
        }
    }

    /**
     * Sends out the Intent to the client after the event is detected.
     *
     * @param pendingIntent Client's PendingIntent for callback
     * @param result result from the detection service
     */
    private void sendDetectionResultIntent(PendingIntent pendingIntent,
            AmbientContextDetectionResult result) {
        Intent intent = new Intent();
        intent.putExtra(AmbientContextManager.EXTRA_AMBIENT_CONTEXT_EVENTS,
                new ArrayList(result.getEvents()));
        // Explicitly disallow the receiver from starting activities, to prevent apps from utilizing
        // the PendingIntent as a backdoor to do this.
        BroadcastOptions options = BroadcastOptions.makeBasic();
        options.setPendingIntentBackgroundActivityLaunchAllowed(false);
        try {
            pendingIntent.send(getContext(), 0, intent, null, null, null,
                    options.toBundle());
            Slog.i(TAG, "Sending PendingIntent to " + pendingIntent.getCreatorPackage() + ": "
                    + result);
        } catch (PendingIntent.CanceledException e) {
            Slog.w(TAG, "Couldn't deliver pendingIntent:" + pendingIntent);
        }
    }

    @NonNull
    RemoteCallback createDetectionResultRemoteCallback() {
        return new RemoteCallback(result -> {
            AmbientContextDetectionResult detectionResult =
                    (AmbientContextDetectionResult) result.get(
                            AmbientContextDetectionResult.RESULT_RESPONSE_BUNDLE_KEY);
            String packageName = detectionResult.getPackageName();
            PendingIntent pendingIntent = mMaster.getPendingIntent(mUserId, packageName);
            if (pendingIntent == null) {
                return;
            }

            final long token = Binder.clearCallingIdentity();
            try {
                sendDetectionResultIntent(pendingIntent, detectionResult);
                Slog.i(TAG, "Got detection result of " + detectionResult.getEvents()
                        + " for " + packageName);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        });
    }
}
