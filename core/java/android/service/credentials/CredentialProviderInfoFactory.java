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
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.credentials.CredentialManager;
import android.credentials.CredentialProviderInfo;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link CredentialProviderInfo} generator.
 *
 * @hide
 */
public final class CredentialProviderInfoFactory {
    private static final String TAG = "CredentialProviderInfoFactory";

    private static final String TAG_CREDENTIAL_PROVIDER = "credential-provider";
    private static final String TAG_CAPABILITIES = "capabilities";
    private static final String TAG_CAPABILITY = "capability";
    private static final String ATTR_NAME = "name";

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
    public static CredentialProviderInfo create(
            @NonNull Context context,
            @NonNull ComponentName serviceComponent,
            int userId,
            boolean isSystemProvider)
            throws PackageManager.NameNotFoundException {
        return create(
                context,
                getServiceInfoOrThrow(serviceComponent, userId),
                isSystemProvider,
                /* disableSystemAppVerificationForTests= */ false,
                /* isEnabled= */ false,
                /* isPrimary= */ false);
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
     * @param isEnabled whether the user enabled this provider
     * @throws SecurityException If provider does not require the relevant permission
     */
    public static CredentialProviderInfo create(
            @NonNull Context context,
            @NonNull ServiceInfo serviceInfo,
            boolean isSystemProvider,
            boolean disableSystemAppVerificationForTests,
            boolean isEnabled,
            boolean isPrimary)
            throws SecurityException {
        verifyProviderPermission(serviceInfo);
        if (isSystemProvider) {
            if (!isValidSystemProvider(
                    context, serviceInfo, disableSystemAppVerificationForTests)) {
                Slog.e(TAG, "Provider is not a valid system provider: " + serviceInfo);
                throw new SecurityException(
                        "Provider is not a valid system provider: " + serviceInfo);
            }
        }

        return populateMetadata(context, serviceInfo)
                .setSystemProvider(isSystemProvider)
                .setEnabled(isEnabled)
                .setPrimary(isPrimary)
                .build();
    }

    /**
     * Constructs an information instance of the credential provider for testing purposes. Does not
     * run any verifications and passes parameters as is.
     */
    @VisibleForTesting
    public static CredentialProviderInfo createForTests(
            @NonNull ServiceInfo serviceInfo,
            @NonNull CharSequence overrideLabel,
            boolean isSystemProvider,
            boolean isEnabled,
            @NonNull List<String> capabilities) {
        return new CredentialProviderInfo.Builder(serviceInfo)
                .setEnabled(isEnabled)
                .setOverrideLabel(overrideLabel)
                .setSystemProvider(isSystemProvider)
                .addCapabilities(capabilities)
                .build();
    }

    private static void verifyProviderPermission(ServiceInfo serviceInfo) throws SecurityException {
        final String permission = Manifest.permission.BIND_CREDENTIAL_PROVIDER_SERVICE;
        if (permission.equals(serviceInfo.permission)) {
            return;
        }
        throw new SecurityException(
                "Service does not require the expected permission : " + permission);
    }

    private static boolean isSystemProviderWithValidPermission(
            ServiceInfo serviceInfo, Context context) {
        if (context == null) {
            Slog.w(TAG, "Context is null in isSystemProviderWithValidPermission");
            return false;
        }
        return PermissionUtils.hasPermission(
                context,
                serviceInfo.packageName,
                Manifest.permission.PROVIDE_DEFAULT_ENABLED_CREDENTIAL_SERVICE);
    }

    private static boolean isValidSystemProvider(
            Context context,
            ServiceInfo serviceInfo,
            boolean disableSystemAppVerificationForTests) {
        requireNonNull(context, "context must not be null");

        if (disableSystemAppVerificationForTests) {
            Bundle metadata = serviceInfo.metaData;
            if (metadata == null) {
                Slog.w(
                        TAG,
                        "metadata is null while reading "
                                + "TEST_SYSTEM_PROVIDER_META_DATA_KEY: "
                                + serviceInfo);
                return false;
            }
            return metadata.getBoolean(
                    CredentialProviderService.TEST_SYSTEM_PROVIDER_META_DATA_KEY);
        }

        return isSystemProviderWithValidPermission(serviceInfo, context);
    }

    private static CredentialProviderInfo.Builder populateMetadata(
            @NonNull Context context, ServiceInfo serviceInfo) {
        requireNonNull(context, "context must not be null");
        final PackageManager pm = context.getPackageManager();
        CredentialProviderInfo.Builder builder = new CredentialProviderInfo.Builder(serviceInfo);

        // 1. Get the metadata for the service.
        final Bundle metadata = serviceInfo.metaData;
        if (metadata == null) {
            Slog.w(TAG, "Metadata is null for provider: " + serviceInfo.getComponentName());
            return builder;
        }

        // 2. Get the resources for the application.
        Resources resources = null;
        try {
            resources = pm.getResourcesForApplication(serviceInfo.applicationInfo);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Failed to get app resources", e);
        }

        // 3. Stop if we are missing data.
        if (resources == null) {
            Slog.w(
                    TAG,
                    "Resources are null for the serviceInfo being processed: "
                            + serviceInfo.getComponentName());
            return builder;
        }

        // 4. Extract the XML metadata.
        try {
            builder = extractXmlMetadata(context, serviceInfo, pm, resources);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to get XML metadata", e);
        }

        return builder;
    }

    private static CredentialProviderInfo.Builder extractXmlMetadata(
            @NonNull Context context,
            @NonNull ServiceInfo serviceInfo,
            @NonNull PackageManager pm,
            @NonNull Resources resources) {
        final CredentialProviderInfo.Builder builder =
                new CredentialProviderInfo.Builder(serviceInfo);
        final XmlResourceParser parser =
                serviceInfo.loadXmlMetaData(pm, CredentialProviderService.SERVICE_META_DATA);
        if (parser == null) {
            return builder;
        }

        try {
            int type = 0;
            while (type != XmlPullParser.END_DOCUMENT && type != XmlPullParser.START_TAG) {
                type = parser.next();
            }

            // This is matching a <credential-provider /> tag in the XML.
            if (TAG_CREDENTIAL_PROVIDER.equals(parser.getName())) {
                final AttributeSet allAttributes = Xml.asAttributeSet(parser);
                TypedArray afsAttributes = null;
                try {
                    afsAttributes =
                            resources.obtainAttributes(
                                    allAttributes,
                                    com.android.internal.R.styleable.CredentialProvider);
                    builder.setSettingsSubtitle(
                            getAfsAttributeSafe(
                                    afsAttributes,
                                    R.styleable.CredentialProvider_settingsSubtitle));
                    builder.setSettingsActivity(
                            getAfsAttributeSafe(
                                    afsAttributes,
                                    R.styleable.CredentialProvider_settingsActivity));
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to get XML attr for metadata", e);
                } finally {
                    if (afsAttributes != null) {
                        afsAttributes.recycle();
                    }
                }

                builder.addCapabilities(parseXmlProviderOuterCapabilities(parser, resources));
            } else {
                Slog.w(TAG, "Meta-data does not start with credential-provider-service tag");
            }
        } catch (IOException | XmlPullParserException e) {
            Slog.e(TAG, "Error parsing credential provider service meta-data", e);
        }

        return builder;
    }

    private static @Nullable String getAfsAttributeSafe(
            @Nullable TypedArray afsAttributes, int resId) {
        if (afsAttributes == null) {
            return null;
        }

        try {
            return afsAttributes.getString(resId);
        } catch (Exception e) {
            Slog.w(TAG, "Failed to get XML attr from afs attributes", e);
        }

        return null;
    }

    private static List<String> parseXmlProviderOuterCapabilities(
            XmlPullParser parser, Resources resources) throws IOException, XmlPullParserException {
        final List<String> capabilities = new ArrayList<>();
        final int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (TAG_CAPABILITIES.equals(parser.getName())) {
                capabilities.addAll(parseXmlProviderInnerCapabilities(parser, resources));
            }
        }

        return capabilities;
    }

    private static List<String> parseXmlProviderInnerCapabilities(
            XmlPullParser parser, Resources resources) throws IOException, XmlPullParserException {
        List<String> capabilities = new ArrayList<>();

        final int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (TAG_CAPABILITY.equals(parser.getName())) {
                String name = parser.getAttributeValue(null, ATTR_NAME);
                if (name != null && !TextUtils.isEmpty(name)) {
                    capabilities.add(name);
                }
            }
        }

        return capabilities;
    }

    private static ServiceInfo getServiceInfoOrThrow(
            @NonNull ComponentName serviceComponent, int userId)
            throws PackageManager.NameNotFoundException {
        try {
            ServiceInfo si =
                    AppGlobals.getPackageManager()
                            .getServiceInfo(serviceComponent, PackageManager.GET_META_DATA, userId);
            if (si != null) {
                return si;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to get serviceInfo", e);
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
        requireNonNull(context, "context must not be null");

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
            if (disableSystemAppVerificationForTests) {
                if (serviceInfo != null) {
                    services.add(serviceInfo);
                }
                continue;
            }

            try {
                ApplicationInfo appInfo =
                        context.getPackageManager()
                                .getApplicationInfo(
                                        serviceInfo.packageName,
                                        PackageManager.ApplicationInfoFlags.of(
                                                PackageManager.MATCH_SYSTEM_ONLY));

                if (appInfo == null || serviceInfo == null) {
                    continue;
                }
                services.add(serviceInfo);
            } catch (SecurityException | PackageManager.NameNotFoundException e) {
                Slog.e(TAG, "Error getting info for " + serviceInfo, e);
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
            boolean disableSystemAppVerificationForTests,
            Set<ComponentName> enabledServices) {
        requireNonNull(context, "context must not be null");

        final List<CredentialProviderInfo> providerInfos = new ArrayList<>();
        for (ServiceInfo si :
                getAvailableSystemServiceInfos(
                        context, userId, disableSystemAppVerificationForTests)) {
            try {
                CredentialProviderInfo cpi =
                        CredentialProviderInfoFactory.create(
                                context,
                                si,
                                /* isSystemProvider= */ true,
                                disableSystemAppVerificationForTests,
                                enabledServices.contains(si.getComponentName()),
                                false);
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

    private static @Nullable PackagePolicy getDeviceManagerPolicy(
            @NonNull Context context, int userId) {
        Context newContext = context.createContextAsUser(UserHandle.of(userId), 0);

        try {
            DevicePolicyManager dpm = newContext.getSystemService(DevicePolicyManager.class);
            PackagePolicy pp = dpm.getCredentialManagerPolicy();
            return pp;
        } catch (SecurityException e) {
            // If the current user is not enrolled in DPM then this can throw a security error.
            Slog.e(TAG, "Failed to get device policy: " + e);
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
            int providerFilter,
            Set<ComponentName> enabledServices,
            Set<ComponentName> primaryServices) {
        requireNonNull(context, "context must not be null");

        // Get the device policy.
        PackagePolicy pp = getDeviceManagerPolicy(context, userId);

        // Generate the provider list.
        final boolean disableSystemAppVerificationForTests = false;
        ProviderGenerator generator =
                new ProviderGenerator(
                        context, pp, disableSystemAppVerificationForTests, providerFilter);
        generator.addUserProviders(
                getUserProviders(
                        context,
                        userId,
                        disableSystemAppVerificationForTests,
                        enabledServices,
                        primaryServices));
        generator.addSystemProviders(
                getAvailableSystemServices(
                        context, userId, disableSystemAppVerificationForTests, enabledServices));
        return generator.getProviders();
    }

    /**
     * Returns the valid credential provider services available for the user with the given {@code
     * userId}. Includes test providers.
     */
    @NonNull
    public static List<CredentialProviderInfo> getCredentialProviderServicesForTesting(
            @NonNull Context context,
            int userId,
            int providerFilter,
            Set<ComponentName> enabledServices,
            Set<ComponentName> primaryServices) {
        requireNonNull(context, "context must not be null");

        // Get the device policy.
        PackagePolicy pp = getDeviceManagerPolicy(context, userId);

        // Generate the provider list.
        final boolean disableSystemAppVerificationForTests = true;
        ProviderGenerator generator =
                new ProviderGenerator(
                        context, pp, disableSystemAppVerificationForTests, providerFilter);
        generator.addUserProviders(
                getUserProviders(
                        context,
                        userId,
                        disableSystemAppVerificationForTests,
                        enabledServices,
                        primaryServices));
        generator.addSystemProviders(
                getAvailableSystemServices(
                        context, userId, disableSystemAppVerificationForTests, enabledServices));
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
            boolean disableSystemAppVerificationForTests,
            Set<ComponentName> enabledServices,
            Set<ComponentName> primaryServices) {
        final List<CredentialProviderInfo> services = new ArrayList<>();
        final List<ResolveInfo> resolveInfos =
                context.getPackageManager()
                        .queryIntentServicesAsUser(
                                new Intent(CredentialProviderService.SERVICE_INTERFACE),
                                PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA),
                                userId);
        for (ResolveInfo resolveInfo : resolveInfos) {
            final ServiceInfo serviceInfo = resolveInfo.serviceInfo;
            if (serviceInfo == null) {
                Slog.d(TAG, "No serviceInfo found for resolveInfo, so skipping provider");
                continue;
            }

            try {
                CredentialProviderInfo cpi =
                        CredentialProviderInfoFactory.create(
                                context,
                                serviceInfo,
                                /* isSystemProvider= */ false,
                                disableSystemAppVerificationForTests,
                                enabledServices.contains(serviceInfo.getComponentName()),
                                primaryServices.contains(serviceInfo.getComponentName()));
                if (!cpi.isSystemProvider()) {
                    services.add(cpi);
                }
            } catch (Exception e) {
                Slog.e(TAG, "Error getting info for " + serviceInfo, e);
            }
        }
        return services;
    }
}
