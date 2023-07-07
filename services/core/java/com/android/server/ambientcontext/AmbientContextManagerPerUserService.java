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

import android.annotation.NonNull;
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
import android.app.ambientcontext.IAmbientContextObserver;
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
import java.util.List;
import java.util.function.Consumer;

/**
 * Base per-user manager service for {@link AmbientContextEvent}s.
 */
abstract class AmbientContextManagerPerUserService extends
        AbstractPerUserSystemService<AmbientContextManagerPerUserService,
                AmbientContextManagerService> {
    private static final String TAG =
            AmbientContextManagerPerUserService.class.getSimpleName();

    /**
     * The type of service.
     */
    enum ServiceType {
        DEFAULT,
        WEARABLE
    }

    AmbientContextManagerPerUserService(
            @NonNull AmbientContextManagerService master, Object lock, @UserIdInt int userId) {
        super(master, lock, userId);
    }

    /**
     * Returns the current bound AmbientContextManagerPerUserService component for this user.
     */
    abstract ComponentName getComponentName();

    /**
     * Sets the component name for the per user service.
     */
    abstract void setComponentName(ComponentName componentName);

    /**
     * Ensures that the remote service is initiated.
     */
    abstract void ensureRemoteServiceInitiated();

    /**
     * Returns the AmbientContextManagerPerUserService {@link ServiceType} for this user.
     */
    abstract ServiceType getServiceType();

    /**
     * Returns the int config for the consent component for the
     * specific AmbientContextManagerPerUserService type
     */
    abstract int getConsentComponentConfig();

    /**
     * Returns the int config for the intent extra key for the
     * caller's package name while requesting ambient context consent.
     */
    abstract int getAmbientContextPackageNameExtraKeyConfig();

    /**
     * Returns the int config for the Intent extra key for the event code int array while
     * requesting ambient context consent.
     */
    abstract int getAmbientContextEventArrayExtraKeyConfig();

    /**
     * Returns the permission that is required to bind to this service.
     */
    abstract String getProtectedBindPermission();

    /**
     * Returns the remote service implementation for this user.
     */
    abstract RemoteAmbientDetectionService getRemoteService();

    /**
     * Clears the remote service.
     */
    abstract void clearRemoteService();

    /**
     * Called when there's an application with the callingPackage name is requesting for
     * the AmbientContextDetection's service status.
     *
     * @param eventTypes the event types to query for
     * @param callingPackage the package query for information
     * @param statusCallback the callback to deliver the status on
     */
    public void onQueryServiceStatus(int[] eventTypes, String callingPackage,
            RemoteCallback statusCallback) {
        Slog.d(TAG, "Query event status of " + Arrays.toString(eventTypes)
                + " for " + callingPackage);
        synchronized (mLock) {
            if (!setUpServiceIfNeeded()) {
                Slog.w(TAG, "Detection service is not available at this moment.");
                sendStatusCallback(statusCallback,
                        AmbientContextManager.STATUS_SERVICE_UNAVAILABLE);
                return;
            }
            ensureRemoteServiceInitiated();
            getRemoteService().queryServiceStatus(
                    eventTypes,
                    callingPackage,
                    getServerStatusCallback(
                            statusCode -> sendStatusCallback(statusCallback, statusCode)));
        }
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

    /**
     * Starts the consent activity for the calling package and event types.
     */
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
                    getAmbientContextPackageNameExtraKeyConfig());
            String eventArrayExtraKey = context.getResources().getString(
                    getAmbientContextEventArrayExtraKeyConfig());

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
     * Handles client registering as an observer. Only one registration is supported per app
     * package. A new registration from the same package will overwrite the previous registration.
     */
    public void onRegisterObserver(AmbientContextEventRequest request,
            String packageName, IAmbientContextObserver observer) {
        synchronized (mLock) {
            if (!setUpServiceIfNeeded()) {
                Slog.w(TAG, "Detection service is not available at this moment.");
                completeRegistration(observer, AmbientContextManager.STATUS_SERVICE_UNAVAILABLE);
                return;
            }

            // Register package and add to existing ClientRequests cache
            startDetection(request, packageName, observer);
            mMaster.newClientAdded(mUserId, request, packageName, observer);
        }
    }

    @Override
    protected ServiceInfo newServiceInfoLocked(@NonNull ComponentName serviceComponent)
            throws PackageManager.NameNotFoundException {
        Slog.d(TAG, "newServiceInfoLocked with component name: "
                + serviceComponent.getClassName());

        if (getComponentName() == null
                || !serviceComponent.getClassName().equals(getComponentName().getClassName())) {
            Slog.d(TAG, "service name does not match this per user, returning...");
            return null;
        }

        ServiceInfo serviceInfo;
        try {
            serviceInfo = AppGlobals.getPackageManager().getServiceInfo(serviceComponent,
                    0, mUserId);
            if (serviceInfo != null) {
                final String permission = serviceInfo.permission;
                if (!getProtectedBindPermission().equals(
                        permission)) {
                    throw new SecurityException(String.format(
                            "Service %s requires %s permission. Found %s permission",
                            serviceInfo.getComponentName(),
                            getProtectedBindPermission(),
                            serviceInfo.permission));
                }
            }
        } catch (RemoteException e) {
            throw new PackageManager.NameNotFoundException(
                    "Could not get service for " + serviceComponent);
        }
        return serviceInfo;
    }

    /**
     * Dumps the remote service.
     */
    protected void dumpLocked(@NonNull String prefix, @NonNull PrintWriter pw) {
        synchronized (super.mLock) {
            super.dumpLocked(prefix, pw);
        }
        RemoteAmbientDetectionService remoteService = getRemoteService();
        if (remoteService != null) {
            remoteService.dump("", new IndentingPrintWriter(pw, "  "));
        }
    }

    /**
     * Send request to the remote AmbientContextDetectionService impl to stop detecting the
     * specified events. Intended for use by shell command for testing.
     * Requires ACCESS_AMBIENT_CONTEXT_EVENT permission.
     */
    @VisibleForTesting
    protected void stopDetection(String packageName) {
        Slog.d(TAG, "Stop detection for " + packageName);
        synchronized (mLock) {
            if (getComponentName() != null) {
                ensureRemoteServiceInitiated();
                RemoteAmbientDetectionService remoteService = getRemoteService();
                remoteService.stopDetection(packageName);
            }
        }
    }

    /**
     * Destroys this service and unbinds from the remote service.
     */
    protected void destroyLocked() {
        Slog.d(TAG, "Trying to cancel the remote request. Reason: Service destroyed.");
        RemoteAmbientDetectionService remoteService = getRemoteService();
        if (remoteService != null) {
            synchronized (mLock) {
                remoteService.unbind();
                clearRemoteService();
            }
        }
    }

    /**
     * Send request to the remote AmbientContextDetectionService impl to start detecting the
     * specified events. Intended for use by shell command for testing.
     * Requires ACCESS_AMBIENT_CONTEXT_EVENT permission.
     */
    protected void startDetection(AmbientContextEventRequest request, String callingPackage,
            IAmbientContextObserver observer) {
        Slog.d(TAG, "Requested detection of " + request.getEventTypes());
        synchronized (mLock) {
            if (setUpServiceIfNeeded()) {
                ensureRemoteServiceInitiated();
                RemoteAmbientDetectionService remoteService = getRemoteService();
                remoteService.startDetection(request, callingPackage,
                        createDetectionResultRemoteCallback(),
                        getServerStatusCallback(
                                statusCode -> completeRegistration(observer, statusCode)));
            } else {
                Slog.w(TAG, "No valid component found for AmbientContextDetectionService");
                completeRegistration(observer,
                        AmbientContextManager.STATUS_NOT_SUPPORTED);
            }
        }
    }

    /**
     * Notifies the observer the status of the registration.
     *
     * @param observer the observer to notify
     * @param statusCode the status to notify
     */
    protected void completeRegistration(IAmbientContextObserver observer, int statusCode) {
        try {
            observer.onRegistrationComplete(statusCode);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to call IAmbientContextObserver.onRegistrationComplete: "
                    + e.getMessage());
        }
    }

    /**
     * Sends the status on the {@link RemoteCallback}.
     *
     * @param statusCallback the callback to send the status on
     * @param statusCode the status to send
     */
    protected void sendStatusCallback(RemoteCallback statusCallback,
            @AmbientContextManager.StatusCode int statusCode) {
        Bundle bundle = new Bundle();
        bundle.putInt(
                AmbientContextManager.STATUS_RESPONSE_BUNDLE_KEY,
                statusCode);
        statusCallback.sendResult(bundle);
    }

    /**
     * Sends out the Intent to the client after the event is detected.
     *
     * @param pendingIntent Client's PendingIntent for callback
     * @param events detected events from the detection service
     */
    protected void sendDetectionResultIntent(PendingIntent pendingIntent,
            List<AmbientContextEvent> events) {
        Intent intent = new Intent();
        intent.putExtra(AmbientContextManager.EXTRA_AMBIENT_CONTEXT_EVENTS,
                new ArrayList(events));
        // Explicitly disallow the receiver from starting activities, to prevent apps from utilizing
        // the PendingIntent as a backdoor to do this.
        BroadcastOptions options = BroadcastOptions.makeBasic();
        options.setPendingIntentBackgroundActivityLaunchAllowed(false);
        try {
            pendingIntent.send(getContext(), 0, intent, null,
                    null, null, options.toBundle());
            Slog.i(TAG, "Sending PendingIntent to " + pendingIntent.getCreatorPackage() + ": "
                    + events);
        } catch (PendingIntent.CanceledException e) {
            Slog.w(TAG, "Couldn't deliver pendingIntent:" + pendingIntent);
        }
    }

    @NonNull
    protected RemoteCallback createDetectionResultRemoteCallback() {
        return new RemoteCallback(result -> {
            AmbientContextDetectionResult detectionResult =
                    (AmbientContextDetectionResult) result.get(
                            AmbientContextDetectionResult.RESULT_RESPONSE_BUNDLE_KEY);
            String packageName = detectionResult.getPackageName();
            IAmbientContextObserver observer = mMaster.getClientRequestObserver(
                    mUserId, packageName);
            if (observer == null) {
                return;
            }

            final long token = Binder.clearCallingIdentity();
            try {
                observer.onEvents(detectionResult.getEvents());
                Slog.i(TAG, "Got detection result of " + detectionResult.getEvents()
                        + " for " + packageName);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to call IAmbientContextObserver.onEvents: " + e.getMessage());
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        });
    }

    /**
     * Resolves and sets up the service if it had not been done yet. Returns true if the service
     * is available.
     */
    @GuardedBy("mLock")
    @VisibleForTesting
    private boolean setUpServiceIfNeeded() {
        if (getComponentName() == null) {
            ComponentName[] componentNames = updateServiceInfoListLocked();
            if (componentNames == null || componentNames.length != 2) {
                Slog.d(TAG, "updateServiceInfoListLocked returned incorrect componentNames");
                return false;
            }

            switch (getServiceType()) {
                case DEFAULT:
                    setComponentName(componentNames[0]);
                    break;
                case WEARABLE:
                    setComponentName(componentNames[1]);
                    break;
                default:
                    Slog.d(TAG, "updateServiceInfoListLocked returned unknown service types.");
                    return false;
            }
        }

        if (getComponentName() == null) {
            return false;
        }

        ServiceInfo serviceInfo;
        try {
            serviceInfo = AppGlobals.getPackageManager().getServiceInfo(
                    getComponentName(), 0, mUserId);
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException while setting up service");
            return false;
        }
        return serviceInfo != null;
    }

    /**
     * Returns a RemoteCallback that handles the status from the detection service, and
     * sends results to the client callback.
     */
    private RemoteCallback getServerStatusCallback(Consumer<Integer> statusConsumer) {
        return new RemoteCallback(result -> {
            AmbientContextDetectionServiceStatus serviceStatus =
                    (AmbientContextDetectionServiceStatus) result.get(
                            AmbientContextDetectionServiceStatus.STATUS_RESPONSE_BUNDLE_KEY);
            final long token = Binder.clearCallingIdentity();
            try {
                int statusCode = serviceStatus.getStatusCode();
                statusConsumer.accept(statusCode);
                Slog.i(TAG, "Got detection status of " + statusCode
                        + " for " + serviceStatus.getPackageName());
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        });
    }

    /**
     * Returns the consent activity component from config lookup.
     */
    private ComponentName getConsentComponent() {
        Context context = getContext();
        String consentComponent = context.getResources().getString(getConsentComponentConfig());
        if (TextUtils.isEmpty(consentComponent)) {
            return null;
        }
        Slog.i(TAG, "Consent component name: " + consentComponent);
        return ComponentName.unflattenFromString(consentComponent);
    }
}
