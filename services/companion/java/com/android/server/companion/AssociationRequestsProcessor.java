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

package com.android.server.companion;

import static com.android.internal.util.CollectionUtils.filter;
import static com.android.internal.util.FunctionalUtils.uncheckExceptions;
import static com.android.server.companion.CompanionDeviceManagerService.DEBUG;
import static com.android.server.companion.CompanionDeviceManagerService.LOG_TAG;
import static com.android.server.companion.PermissionsUtils.enforcePermissionsForAssociation;
import static com.android.server.companion.RolesUtils.isRoleHolder;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.CompanionDeviceManager;
import android.companion.IAssociationRequestCallback;
import android.companion.ICompanionDeviceDiscoveryService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.Signature;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.PackageUtils;
import android.util.Slog;

import com.android.internal.infra.AndroidFuture;
import com.android.internal.infra.PerUser;
import com.android.internal.infra.ServiceConnector;
import com.android.internal.util.ArrayUtils;
import com.android.server.FgThread;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

class AssociationRequestsProcessor {
    private static final String TAG = LOG_TAG + ".AssociationRequestsProcessor";

    private static final ComponentName SERVICE_TO_BIND_TO = ComponentName.createRelative(
            CompanionDeviceManager.COMPANION_DEVICE_DISCOVERY_PACKAGE_NAME,
            ".CompanionDeviceDiscoveryService");

    private static final int ASSOCIATE_WITHOUT_PROMPT_MAX_PER_TIME_WINDOW = 5;
    private static final long ASSOCIATE_WITHOUT_PROMPT_WINDOW_MS = 60 * 60 * 1000; // 60 min;

    private final Context mContext;
    private final CompanionDeviceManagerService mService;
    private final PerUser<ServiceConnector<ICompanionDeviceDiscoveryService>> mServiceConnectors;

    private AssociationRequest mRequest;
    private IAssociationRequestCallback mAppCallback;
    private AndroidFuture<?> mOngoingDeviceDiscovery;

    AssociationRequestsProcessor(CompanionDeviceManagerService service) {
        mContext = service.getContext();
        mService = service;

        final Intent serviceIntent = new Intent().setComponent(SERVICE_TO_BIND_TO);
        mServiceConnectors = new PerUser<>() {
            @Override
            protected ServiceConnector<ICompanionDeviceDiscoveryService> create(int userId) {
                return new ServiceConnector.Impl<>(
                    mContext,
                    serviceIntent, 0/* bindingFlags */, userId,
                    ICompanionDeviceDiscoveryService.Stub::asInterface);
            }
        };
    }

    /**
     * Handle incoming {@link AssociationRequest}s, sent via
     * {@link android.companion.ICompanionDeviceManager#associate(AssociationRequest, IAssociationRequestCallback, String, int)}
     */
    void process(@NonNull AssociationRequest request, @NonNull String packageName,
            @UserIdInt int userId, @NonNull IAssociationRequestCallback callback) {
        requireNonNull(request, "Request MUST NOT be null");
        if (request.isSelfManaged()) {
            requireNonNull(request.getDisplayName(), "AssociationRequest.displayName "
                    + "MUST NOT be null.");
        }
        requireNonNull(packageName, "Package name MUST NOT be null");
        requireNonNull(callback, "Callback MUST NOT be null");

        if (DEBUG) {
            Slog.d(TAG, "process() "
                    + "request=" + request + ", "
                    + "package=u" + userId + "/" + packageName);
        }

        // 1. Enforce permissions and other requirements.
        enforcePermissionsForAssociation(mContext, request, packageName, userId);
        mService.checkUsesFeature(packageName, userId);

        // 2. Check if association can be created without launching UI (i.e. CDM needs NEITHER
        // to perform discovery NOR to collect user consent).
        if (request.isSelfManaged() && !request.isForceConfirmation()
                && !willAddRoleHolder(request, packageName, userId)) {
            // 2a. Create association right away.
            final AssociationInfo association = mService.createAssociation(userId, packageName,
                /* macAddress */ null, request.getDisplayName(), request.getDeviceProfile(),
                /* selfManaged */true);
            withCatchingRemoteException(() -> callback.onAssociationCreated(association));
            return;
        }

        // 2b. Launch the UI.
        synchronized (mService.mLock) {
            if (mRequest != null) {
                Slog.w(TAG, "CDM is already processing another AssociationRequest.");

                withCatchingRemoteException(() -> callback.onFailure("Busy."));
            }

            final boolean linked = withCatchingRemoteException(
                    () -> callback.asBinder().linkToDeath(mBinderDeathRecipient, 0));
            if (!linked) {
                // The process has died by now: do not proceed.
                return;
            }

            mRequest = request;
        }

        mAppCallback = callback;
        request.setCallingPackage(packageName);

        if (mayAssociateWithoutPrompt(packageName, userId)) {
            Slog.i(TAG, "setSkipPrompt(true)");
            request.setSkipPrompt(true);
        }

        final String deviceProfile = request.getDeviceProfile();
        mOngoingDeviceDiscovery = getDeviceProfilePermissionDescription(deviceProfile)
                .thenComposeAsync(description -> {
                    if (DEBUG) {
                        Slog.d(TAG, "fetchProfileDescription done: " + description);
                    }

                    request.setDeviceProfilePrivilegesDescription(description);

                    return mServiceConnectors.forUser(userId).postAsync(service -> {
                        if (DEBUG) {
                            Slog.d(TAG, "Connected to CDM service -> "
                                    + "Starting discovery for " + request);
                        }

                        AndroidFuture<String> future = new AndroidFuture<>();
                        service.startDiscovery(request, packageName, callback, future);
                        return future;
                    }).cancelTimeout();

                }, FgThread.getExecutor()).whenComplete(uncheckExceptions((deviceAddress, err) -> {
                    if (err == null) {
                        mService.legacyCreateAssociation(
                                userId, deviceAddress, packageName, deviceProfile);
                        mServiceConnectors.forUser(userId).post(
                                ICompanionDeviceDiscoveryService::onAssociationCreated);
                    } else {
                        Slog.e(TAG, "Failed to discover device(s)", err);
                        callback.onFailure("No devices found: " + err.getMessage());
                    }
                    cleanup();
                }));
    }

    private boolean willAddRoleHolder(@NonNull AssociationRequest request,
            @NonNull String packageName, @UserIdInt int userId) {
        final String deviceProfile = request.getDeviceProfile();
        if (deviceProfile == null) return false;

        final boolean isRoleHolder = Binder.withCleanCallingIdentity(
                () -> isRoleHolder(mContext, userId, packageName, deviceProfile));

        // Don't need to "grant" the role, if the package already holds the role.
        return !isRoleHolder;
    }

    private void cleanup() {
        if (DEBUG) {
            Slog.d(TAG, "cleanup(); discovery = "
                    + mOngoingDeviceDiscovery + ", request = " + mRequest);
        }
        synchronized (mService.mLock) {
            AndroidFuture<?> ongoingDeviceDiscovery = mOngoingDeviceDiscovery;
            if (ongoingDeviceDiscovery != null && !ongoingDeviceDiscovery.isDone()) {
                ongoingDeviceDiscovery.cancel(true);
            }
            if (mAppCallback != null) {
                mAppCallback.asBinder().unlinkToDeath(mBinderDeathRecipient, 0);
                mAppCallback = null;
            }
            mRequest = null;
        }
    }

    private boolean mayAssociateWithoutPrompt(String packageName, int userId) {
        final String deviceProfile = mRequest.getDeviceProfile();
        if (deviceProfile != null) {
            final boolean isRoleHolder = Binder.withCleanCallingIdentity(
                    () -> isRoleHolder(mContext, userId, packageName, deviceProfile));
            if (isRoleHolder) {
                // Don't need to collect user's consent since app already holds the role.
                return true;
            }
        }

        String[] sameOemPackages = mContext.getResources()
                .getStringArray(com.android.internal.R.array.config_companionDevicePackages);
        if (!ArrayUtils.contains(sameOemPackages, packageName)) {
            Slog.w(TAG, packageName
                    + " can not silently create associations due to no package found."
                    + " Packages from OEM: " + Arrays.toString(sameOemPackages)
            );
            return false;
        }

        // Throttle frequent associations
        long now = System.currentTimeMillis();
        Set<AssociationInfo> recentAssociations = filter(
                mService.getAssociations(userId, packageName),
                a -> now - a.getTimeApprovedMs() < ASSOCIATE_WITHOUT_PROMPT_WINDOW_MS);

        if (recentAssociations.size() >= ASSOCIATE_WITHOUT_PROMPT_MAX_PER_TIME_WINDOW) {
            Slog.w(TAG, "Too many associations. " + packageName
                    + " already associated " + recentAssociations.size()
                    + " devices within the last " + ASSOCIATE_WITHOUT_PROMPT_WINDOW_MS
                    + "ms: " + recentAssociations);
            return false;
        }
        String[] sameOemCerts = mContext.getResources()
                .getStringArray(com.android.internal.R.array.config_companionDeviceCerts);

        Signature[] signatures = mService.mPackageManagerInternal
                .getPackage(packageName).getSigningDetails().getSignatures();
        String[] apkCerts = PackageUtils.computeSignaturesSha256Digests(signatures);

        Set<String> sameOemPackageCerts =
                getSameOemPackageCerts(packageName, sameOemPackages, sameOemCerts);

        for (String cert : apkCerts) {
            if (sameOemPackageCerts.contains(cert)) {
                return true;
            }
        }

        Slog.w(TAG, packageName
                + " can not silently create associations. " + packageName
                + " has SHA256 certs from APK: " + Arrays.toString(apkCerts)
                + " and from OEM: " + Arrays.toString(sameOemCerts)
        );

        return false;
    }

    @NonNull
    private AndroidFuture<String> getDeviceProfilePermissionDescription(
            @Nullable String deviceProfile) {
        if (deviceProfile == null) {
            return AndroidFuture.completedFuture(null);
        }

        final AndroidFuture<String> result = new AndroidFuture<>();
        mService.mPermissionControllerManager.getPrivilegesDescriptionStringForProfile(
                deviceProfile, FgThread.getExecutor(), desc -> {
                    try {
                        result.complete(String.valueOf(desc));
                    } catch (Exception e) {
                        result.completeExceptionally(e);
                    }
                });
        return result;
    }


    void dump(@NonNull PrintWriter pw) {
        pw.append("Discovery Service State:").append('\n');
        for (int i = 0, size = mServiceConnectors.size(); i < size; i++) {
            int userId = mServiceConnectors.keyAt(i);
            pw.append("  ")
                    .append("u").append(Integer.toString(userId)).append(": ")
                    .append(Objects.toString(mServiceConnectors.valueAt(i)))
                    .append('\n');
        }
    }

    private final IBinder.DeathRecipient mBinderDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            if (DEBUG) {
                Slog.d(TAG, "binderDied()");
            }
            mService.mMainHandler.post(AssociationRequestsProcessor.this::cleanup);
        }
    };

    private static Set<String> getSameOemPackageCerts(
            String packageName, String[] oemPackages, String[] sameOemCerts) {
        Set<String> sameOemPackageCerts = new HashSet<>();

        // Assume OEM may enter same package name in the parallel string array with
        // multiple APK certs corresponding to it
        for (int i = 0; i < oemPackages.length; i++) {
            if (oemPackages[i].equals(packageName)) {
                sameOemPackageCerts.add(sameOemCerts[i].replaceAll(":", ""));
            }
        }

        return sameOemPackageCerts;
    }

    private static boolean withCatchingRemoteException(ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (RemoteException e) {
            return false;
        }
        return true;
    }

    private interface ThrowingRunnable {
        void run() throws RemoteException;
    }
}
