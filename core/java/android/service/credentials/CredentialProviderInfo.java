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
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.RemoteException;
import android.text.TextUtils;
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

    @NonNull
    private final Context mContext;
    @Nullable
    private final Drawable mIcon;
    @Nullable
    private final CharSequence mLabel;
    private final boolean mIsSystemProvider;

    /**
     * Constructs an information instance of the credential provider.
     *
     * @param context the context object
     * @param serviceComponent the serviceComponent of the provider service
     * @param userId the android userId for which the current process is running
     * @param isSystemProvider whether this provider is a system provider
     * @throws PackageManager.NameNotFoundException If provider service is not found
     * @throws SecurityException If provider does not require the relevant permission
     */
    public CredentialProviderInfo(@NonNull Context context,
            @NonNull ComponentName serviceComponent, int userId, boolean isSystemProvider)
            throws PackageManager.NameNotFoundException {
        this(context, getServiceInfoOrThrow(serviceComponent, userId), isSystemProvider);
    }

    /**
     * Constructs an information instance of the credential provider.
     * @param context the context object
     * @param serviceInfo the service info for the provider app. This must be retrieved from the
     *                    {@code PackageManager}
     * @param isSystemProvider whether the provider is a system app or not
     */
    public CredentialProviderInfo(@NonNull Context context,
            @NonNull ServiceInfo serviceInfo,
            boolean isSystemProvider) {
        if (!Manifest.permission.BIND_CREDENTIAL_PROVIDER_SERVICE.equals(serviceInfo.permission)) {
            Log.i(TAG, "Credential Provider Service from : " + serviceInfo.packageName
                    + "does not require permission"
                    + Manifest.permission.BIND_CREDENTIAL_PROVIDER_SERVICE);
            throw new SecurityException("Service does not require the expected permission : "
                    + Manifest.permission.BIND_CREDENTIAL_PROVIDER_SERVICE);
        }
        mContext = context;
        mServiceInfo = serviceInfo;
        mCapabilities = new ArrayList<>();
        mIcon = mServiceInfo.loadIcon(mContext.getPackageManager());
        mLabel = mServiceInfo.loadSafeLabel(
                mContext.getPackageManager(), 0 /* do not ellipsize */,
                TextUtils.SAFE_STRING_FLAG_FIRST_LINE | TextUtils.SAFE_STRING_FLAG_TRIM);
        mIsSystemProvider = isSystemProvider;
        Log.i(TAG, "mLabel is : " + mLabel + ", for: " + mServiceInfo.getComponentName()
                .flattenToString());
        populateProviderCapabilities(context, serviceInfo);
    }

    private void populateProviderCapabilities(@NonNull Context context, ServiceInfo serviceInfo) {
        final PackageManager pm = context.getPackageManager();
        try {
            Bundle metadata = serviceInfo.metaData;
            Resources resources = pm.getResourcesForApplication(serviceInfo.applicationInfo);
            if (metadata == null || resources == null) {
                Log.i(TAG, "populateProviderCapabilities - metadata or resources is null");
                return;
            }

            String[] capabilities = resources.getStringArray(metadata.getInt(
                    CredentialProviderService.CAPABILITY_META_DATA_KEY));
            if (capabilities == null || capabilities.length == 0) {
                Slog.i(TAG, "No capabilities found for provider:" + serviceInfo.packageName);
                return;
            }

            for (String capability : capabilities) {
                if (capability.isEmpty()) {
                    Slog.i(TAG, "Skipping empty capability");
                    continue;
                }
                Slog.i(TAG, "Capabilities found for provider: " + capability);
                mCapabilities.add(capability);
            }
        } catch (PackageManager.NameNotFoundException e) {
            Slog.i(TAG, e.getMessage());
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
     * Returns the valid credential provider services available for the user with the
     * given {@code userId}.
     */
    @NonNull
    public static List<CredentialProviderInfo> getAvailableSystemServices(
            @NonNull Context context,
            @UserIdInt int userId) {
        final List<CredentialProviderInfo> services = new ArrayList<>();

        final List<ResolveInfo> resolveInfos =
                context.getPackageManager().queryIntentServicesAsUser(
                        new Intent(CredentialProviderService.SYSTEM_SERVICE_INTERFACE),
                        PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA),
                        userId);
        for (ResolveInfo resolveInfo : resolveInfos) {
            final ServiceInfo serviceInfo = resolveInfo.serviceInfo;
            try {
                ApplicationInfo appInfo = context.getPackageManager().getApplicationInfo(
                        serviceInfo.packageName,
                        PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_SYSTEM_ONLY));
                if (appInfo != null
                        && context.checkPermission(Manifest.permission.SYSTEM_CREDENTIAL_PROVIDER,
                        /*pId=*/-1, appInfo.uid) == PackageManager.PERMISSION_GRANTED) {
                    services.add(new CredentialProviderInfo(context, serviceInfo,
                            /*isSystemProvider=*/true));
                }
            } catch (SecurityException e) {
                Log.i(TAG, "Error getting info for " + serviceInfo + ": " + e);
            } catch (PackageManager.NameNotFoundException e) {
                Log.i(TAG, "Error getting info for " + serviceInfo + ": " + e);
            }
        }
        return services;
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

    public boolean isSystemProvider() {
        return mIsSystemProvider;
    }

    /** Returns the service icon. */
    @Nullable
    public Drawable getServiceIcon() {
        return mIcon;
    }

    /** Returns the service label. */
    @Nullable
    public CharSequence getServiceLabel() {
        return mLabel;
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
    @NonNull
    public static List<CredentialProviderInfo> getAvailableServices(@NonNull Context context,
            @UserIdInt int userId) {
        final List<CredentialProviderInfo> services = new ArrayList<>();

        final List<ResolveInfo> resolveInfos =
                context.getPackageManager().queryIntentServicesAsUser(
                        new Intent(CredentialProviderService.SERVICE_INTERFACE),
                        PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA),
                        userId);
        for (ResolveInfo resolveInfo : resolveInfos) {
            final ServiceInfo serviceInfo = resolveInfo.serviceInfo;
            try {
                services.add(new CredentialProviderInfo(context,
                        serviceInfo, false));
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
    @NonNull
    public static List<CredentialProviderInfo> getAvailableServicesForCapability(
            @NonNull Context context, @UserIdInt int userId, @NonNull String credentialType) {
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
