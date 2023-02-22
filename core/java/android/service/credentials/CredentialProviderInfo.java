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

import static java.util.Objects.requireNonNull;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppGlobals;
import android.app.admin.DevicePolicyManager;
import android.app.admin.PackagePolicy;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.credentials.CredentialManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

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
        this(
                context,
                getServiceInfoOrThrow(serviceComponent, userId),
                isSystemProvider,
                /* disableSystemAppVerificationForTests= */ false);
    }

    /**
     * Constructs an information instance of the credential provider.
     *
     * @param context the context object
     * @param serviceInfo the service info for the provider app. This must be retrieved from the
     *     {@code PackageManager}
     * @param isSystemProvider whether the provider app is a system provider
     * @param disableSystemAppVerificationForTests whether to disable system app permission
     *     verification so that tests can install system providers
     * @throws SecurityException If provider does not require the relevant permission
     */
    public CredentialProviderInfo(
            @NonNull Context context,
            @NonNull ServiceInfo serviceInfo,
            boolean isSystemProvider,
            boolean disableSystemAppVerificationForTests) {
        verifyProviderPermission(serviceInfo);
        if (isSystemProvider) {
            if (!isValidSystemProvider(
                    context, serviceInfo, disableSystemAppVerificationForTests)) {
                Slog.e(TAG, "Provider is not a valid system provider: " + serviceInfo);
                throw new SecurityException(
                        "Provider is not a valid system provider: " + serviceInfo);
            }
        }
        mIsSystemProvider = isSystemProvider;
        mContext =  requireNonNull(context, "context must not be null");
        mServiceInfo = requireNonNull(serviceInfo, "serviceInfo must not be null");
        mCapabilities = new ArrayList<>();
        mIcon = mServiceInfo.loadIcon(mContext.getPackageManager());
        mLabel =
                mServiceInfo.loadSafeLabel(
                        mContext.getPackageManager(),
                        0 /* do not ellipsize */,
                        TextUtils.SAFE_STRING_FLAG_FIRST_LINE | TextUtils.SAFE_STRING_FLAG_TRIM);
        Log.i(
                TAG,
                "mLabel is : "
                        + mLabel
                        + ", for: "
                        + mServiceInfo.getComponentName().flattenToString());
        populateProviderCapabilities(context, serviceInfo);
    }

    private static void verifyProviderPermission(ServiceInfo serviceInfo) throws SecurityException {
        final String permission = Manifest.permission.BIND_CREDENTIAL_PROVIDER_SERVICE;
        if (permission.equals(serviceInfo.permission)) {
            return;
        }

        Slog.e(
                TAG,
                "Credential Provider Service from : "
                        + serviceInfo.packageName
                        + "does not require permission"
                        + permission);
        throw new SecurityException(
                "Service does not require the expected permission : " + permission);
    }

    private static boolean isSystemProviderWithValidPermission(
            ServiceInfo serviceInfo, Context context) {
        final String permission = Manifest.permission.PROVIDE_DEFAULT_ENABLED_CREDENTIAL_SERVICE;
        try {
            ApplicationInfo appInfo =
                    context.getPackageManager()
                            .getApplicationInfo(
                                    serviceInfo.packageName,
                                    PackageManager.ApplicationInfoFlags.of(
                                            PackageManager.MATCH_SYSTEM_ONLY));
            if (appInfo != null
                    && context.checkPermission(permission, /* pid= */ -1, appInfo.uid)
                            == PackageManager.PERMISSION_GRANTED) {
                Slog.i(TAG, "SYS permission granted for: " + serviceInfo.packageName);
                return true;
            } else {
                Slog.i(TAG, "SYS permission failed for: " + serviceInfo.packageName);
            }
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Error getting info for " + serviceInfo + ": " + e);
        }
        return false;
    }

    private static boolean isValidSystemProvider(
            Context context,
            ServiceInfo serviceInfo,
            boolean disableSystemAppVerificationForTests) {
        boolean isValidSystemTestProvider =
                isTestSystemProvider(serviceInfo, disableSystemAppVerificationForTests);
        if (isValidSystemTestProvider) {
            return true;
        }
        return isSystemProviderWithValidPermission(serviceInfo, context);
    }

    private static boolean isTestSystemProvider(
            ServiceInfo serviceInfo, boolean disableSystemAppVerificationForTests) {
        if (!disableSystemAppVerificationForTests) {
            return false;
        }

        Bundle metadata = serviceInfo.metaData;
        if (metadata == null) {
            Slog.e(TAG, "metadata is null: " + serviceInfo);
            return false;
        }
        return metadata.getBoolean(CredentialProviderService.TEST_SYSTEM_PROVIDER_META_DATA_KEY);
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
            Slog.e(TAG, e.getMessage());
        } catch (Resources.NotFoundException e) {
            Slog.e(TAG, e.getMessage());
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
     * Returns the valid credential provider services available for the user with the given {@code
     * userId}.
     */
    @NonNull
    private static List<ServiceInfo> getAvailableSystemServiceInfos(
            @NonNull Context context,
            @UserIdInt int userId,
            boolean disableSystemAppVerificationForTests) {
        final List<ServiceInfo> services = new ArrayList<>();
        final List<ResolveInfo> resolveInfos = new ArrayList<>();

        resolveInfos.addAll(
                context.getPackageManager()
                        .queryIntentServicesAsUser(
                                new Intent(CredentialProviderService.SYSTEM_SERVICE_INTERFACE),
                                PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA),
                                userId));

        for (ResolveInfo resolveInfo : resolveInfos) {
            final ServiceInfo serviceInfo = resolveInfo.serviceInfo;
            try {
                PackageManager.ApplicationInfoFlags appInfoFlags =
                        disableSystemAppVerificationForTests
                                ? PackageManager.ApplicationInfoFlags.of(0)
                                : PackageManager.ApplicationInfoFlags.of(
                                        PackageManager.MATCH_SYSTEM_ONLY);
                ApplicationInfo appInfo =
                        context.getPackageManager()
                                .getApplicationInfo(serviceInfo.packageName, appInfoFlags);

                if (appInfo == null || serviceInfo == null) {
                    continue;
                }

                services.add(serviceInfo);
            } catch (SecurityException e) {
                Slog.e(TAG, "Error getting info for " + serviceInfo + ": " + e);
            } catch (PackageManager.NameNotFoundException e) {
                Slog.e(TAG, "Error getting info for " + serviceInfo + ": " + e);
            }
        }
        return services;
    }

    /**
     * Returns the valid credential provider services available for the user with the given {@code
     * userId}.
     */
    @NonNull
    public static List<CredentialProviderInfo> getAvailableSystemServices(
            @NonNull Context context,
            @UserIdInt int userId,
            boolean disableSystemAppVerificationForTests) {
        requireNonNull(context, "context must not be null");
        final List<CredentialProviderInfo> providerInfos = new ArrayList<>();
        for (ServiceInfo si :
                getAvailableSystemServiceInfos(
                        context, userId, disableSystemAppVerificationForTests)) {
            try {
                CredentialProviderInfo cpi =
                        new CredentialProviderInfo(
                                context,
                                si,
                                /* isSystemProvider= */ true,
                                disableSystemAppVerificationForTests);
                if (cpi.isSystemProvider()) {
                    providerInfos.add(cpi);
                } else {
                    Slog.e(TAG, "Non system provider was in system provider list.");
                }
            } catch (SecurityException e) {
                Slog.e(TAG, "Failed to create CredentialProviderInfo: " + e);
            }
        }
        return providerInfos;
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

    private static @Nullable PackagePolicy getDeviceManagerPolicy(@NonNull Context context) {
        try {
            DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
            return dpm.getCredentialManagerPolicy();
        } catch (SecurityException e) {
            // If the current user is not enrolled in DPM then this can throw a security error.
            Log.e(TAG, "Failed to get device policy: " + e);
        }

        return null;
    }

    /**
     * Returns the valid credential provider services available for the user with the given {@code
     * userId}.
     */
    @NonNull
    public static List<CredentialProviderInfo> getCredentialProviderServices(
            @NonNull Context context,
            int userId,
            boolean disableSystemAppVerificationForTests,
            int providerFilter) {
        requireNonNull(context, "context must not be null");

        // Get the device policy.
        PackagePolicy pp = getDeviceManagerPolicy(context);

        // Generate the provider list.
        ProviderGenerator generator =
                new ProviderGenerator(
                        context, pp, disableSystemAppVerificationForTests, providerFilter);
        generator.addUserProviders(
                getUserProviders(context, userId, disableSystemAppVerificationForTests));
        generator.addSystemProviders(
                getAvailableSystemServices(context, userId, disableSystemAppVerificationForTests));
        return generator.getProviders();
    }

    private static class ProviderGenerator {
        private final Context mContext;
        private final PackagePolicy mPp;
        private final boolean mDisableSystemAppVerificationForTests;
        private final Map<String, CredentialProviderInfo> mServices = new HashMap();
        private final int mProviderFilter;

        ProviderGenerator(
                Context context,
                PackagePolicy pp,
                boolean disableSystemAppVerificationForTests,
                int providerFilter) {
            this.mContext = context;
            this.mPp = pp;
            this.mDisableSystemAppVerificationForTests = disableSystemAppVerificationForTests;
            this.mProviderFilter = providerFilter;
        }

        private boolean isPackageAllowed(boolean isSystemProvider, String packageName) {
            if (mPp == null) {
                return true;
            }

            if (isSystemProvider) {
                return mPp.getPolicyType() == PackagePolicy.PACKAGE_POLICY_ALLOWLIST_AND_SYSTEM;
            }

            return mPp.isPackageAllowed(packageName, new HashSet<>());
        }

        public List<CredentialProviderInfo> getProviders() {
            return new ArrayList<>(mServices.values());
        }

        public void addUserProviders(List<CredentialProviderInfo> providers) {
            for (CredentialProviderInfo cpi : providers) {
                if (!cpi.isSystemProvider()) {
                    addProvider(cpi);
                }
            }
        }

        public void addSystemProviders(List<CredentialProviderInfo> providers) {
            for (CredentialProviderInfo cpi : providers) {
                if (cpi.isSystemProvider()) {
                    addProvider(cpi);
                }
            }
        }

        private boolean isProviderAllowedWithFilter(CredentialProviderInfo cpi) {
            if (mProviderFilter == CredentialManager.PROVIDER_FILTER_ALL_PROVIDERS) {
                return true;
            }

            if (cpi.isSystemProvider()) {
                return mProviderFilter == CredentialManager.PROVIDER_FILTER_SYSTEM_PROVIDERS_ONLY;
            } else {
                return mProviderFilter == CredentialManager.PROVIDER_FILTER_USER_PROVIDERS_ONLY;
            }
        }

        private void addProvider(CredentialProviderInfo cpi) {
            final String componentNameString =
                    cpi.getServiceInfo().getComponentName().flattenToString();
            if (!isProviderAllowedWithFilter(cpi)) {
                return;
            }

            if (!isPackageAllowed(cpi.isSystemProvider(), cpi.getServiceInfo().packageName)) {
                return;
            }

            mServices.put(componentNameString, cpi);
        }
    }

    /**
     * Returns the valid credential provider services available for the user with the given {@code
     * userId}.
     */
    @NonNull
    private static List<CredentialProviderInfo> getUserProviders(
            @NonNull Context context,
            @UserIdInt int userId,
            boolean disableSystemAppVerificationForTests) {
        final List<CredentialProviderInfo> services = new ArrayList<>();
        final List<ResolveInfo> resolveInfos =
                context.getPackageManager()
                        .queryIntentServicesAsUser(
                                new Intent(CredentialProviderService.SERVICE_INTERFACE),
                                PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA),
                                userId);
        for (ResolveInfo resolveInfo : resolveInfos) {
            final ServiceInfo serviceInfo = resolveInfo.serviceInfo;
            try {
                CredentialProviderInfo cpi =
                        new CredentialProviderInfo(
                                context,
                                serviceInfo,
                                /* isSystemProvider= */ false,
                                disableSystemAppVerificationForTests);
                if (!cpi.isSystemProvider()) {
                    services.add(cpi);
                }
            } catch (SecurityException e) {
                Slog.e(TAG, "Error getting info for " + serviceInfo + ": " + e);
            } catch (Exception e) {
                Slog.e(TAG, "Error getting info for " + serviceInfo + ": " + e);
            }
        }
        return services;
    }
}
