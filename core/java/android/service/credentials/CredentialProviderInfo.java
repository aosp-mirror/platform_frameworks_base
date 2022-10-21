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

package android.service.credentials;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@link ServiceInfo} and meta-data about a credential provider.
 *
 * @hide
 */
public final class CredentialProviderInfo {
    private static final String TAG = "CredentialProviderInfo";

    @NonNull
    private final ServiceInfo mServiceInfo;
    @NonNull
    private final List<String> mCapabilities;

    // TODO: Move the two strings below to CredentialProviderService when ready.
    private static final String CAPABILITY_META_DATA_KEY = "android.credentials.capabilities";
    private static final String SERVICE_INTERFACE =
            "android.service.credentials.CredentialProviderService";


    /**
     * Constructs an information instance of the credential provider.
     *
     * @param context The context object
     * @param serviceComponent The serviceComponent of the provider service
     * @param userId The android userId for which the current process is running
     * @throws PackageManager.NameNotFoundException If provider service is not found
     */
    public CredentialProviderInfo(@NonNull Context context,
            @NonNull ComponentName serviceComponent, int userId)
            throws PackageManager.NameNotFoundException {
        this(context, getServiceInfoOrThrow(serviceComponent, userId));
    }

    private CredentialProviderInfo(@NonNull Context context, @NonNull ServiceInfo serviceInfo) {
        if (!Manifest.permission.BIND_CREDENTIAL_PROVIDER_SERVICE.equals(serviceInfo.permission)) {
            Log.i(TAG, "Credential Provider Service from : " + serviceInfo.packageName
                    + "does not require permission"
                    + Manifest.permission.BIND_CREDENTIAL_PROVIDER_SERVICE);
            throw new SecurityException("Service does not require the expected permission : "
                    + Manifest.permission.BIND_CREDENTIAL_PROVIDER_SERVICE);
        }
        mServiceInfo = serviceInfo;
        mCapabilities = new ArrayList<>();
        populateProviderCapabilities(context);
    }

    private void populateProviderCapabilities(@NonNull Context context) {
        if (mServiceInfo.applicationInfo.metaData == null) {
            return;
        }
        try {
            final int resourceId = mServiceInfo.applicationInfo.metaData.getInt(
                    CAPABILITY_META_DATA_KEY);
            String[] capabilities = context.getResources().getStringArray(resourceId);
            if (capabilities == null) {
                Log.w(TAG, "No capabilities found for provider: " + mServiceInfo.packageName);
                return;
            }
            for (String capability : capabilities) {
                if (capability.isEmpty()) {
                    Log.w(TAG, "Skipping empty capability");
                    continue;
                }
                mCapabilities.add(capability);
            }
        } catch (Resources.NotFoundException e) {
            Log.w(TAG, "Exception while populating provider capabilities: " + e.getMessage());
        }
    }

    private static ServiceInfo getServiceInfoOrThrow(@NonNull ComponentName serviceComponent,
            int userId) throws PackageManager.NameNotFoundException {
        try {
            ServiceInfo si = AppGlobals.getPackageManager().getServiceInfo(
                    serviceComponent,
                    PackageManager.GET_META_DATA,
                    userId);
            if (si != null) {
                return si;
            }
        } catch (RemoteException e) {
            Slog.v(TAG, e.getMessage());
        }
        throw new PackageManager.NameNotFoundException(serviceComponent.toString());
    }

    /**
     * Returns true if the service supports the given {@code credentialType}, false otherwise.
     */
    @NonNull
    public boolean hasCapability(@NonNull String credentialType) {
        return mCapabilities.contains(credentialType);
    }

    /** Returns the service info. */
    @NonNull
    public ServiceInfo getServiceInfo() {
        return mServiceInfo;
    }

    /** Returns an immutable list of capabilities this provider service can support. */
    @NonNull
    public List<String> getCapabilities() {
        return Collections.unmodifiableList(mCapabilities);
    }

    /**
     * Returns the valid credential provider services available for the user with the
     * given {@code userId}.
     */
    public static List<CredentialProviderInfo> getAvailableServices(@NonNull Context context,
            @UserIdInt int userId) {
        final List<CredentialProviderInfo> services = new ArrayList<>();

        final List<ResolveInfo> resolveInfos =
                context.getPackageManager().queryIntentServicesAsUser(
                        new Intent(SERVICE_INTERFACE),
                        PackageManager.GET_META_DATA,
                        userId);
        for (ResolveInfo resolveInfo : resolveInfos) {
            final ServiceInfo serviceInfo = resolveInfo.serviceInfo;
            try {
                services.add(new CredentialProviderInfo(context, serviceInfo));
            } catch (SecurityException e) {
                Log.w(TAG, "Error getting info for " + serviceInfo + ": " + e);
            }
        }
        return services;
    }

    /**
     * Returns the valid credential provider services available for the user, that can
     * support the given {@code credentialType}.
     */
    public static List<CredentialProviderInfo> getAvailableServicesForCapability(
            Context context, @UserIdInt int userId, String credentialType) {
        List<CredentialProviderInfo> servicesForCapability = new ArrayList<>();
        final List<CredentialProviderInfo> services = getAvailableServices(context, userId);

        for (CredentialProviderInfo service : services) {
            if (service.hasCapability(credentialType)) {
                servicesForCapability.add(service);
            }
        }
        return servicesForCapability;
    }
}
