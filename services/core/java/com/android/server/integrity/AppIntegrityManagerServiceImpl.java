/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.integrity;

import static android.content.Intent.ACTION_PACKAGE_NEEDS_INTEGRITY_VERIFICATION;
import static android.content.integrity.AppIntegrityManager.EXTRA_STATUS;
import static android.content.integrity.AppIntegrityManager.STATUS_FAILURE;
import static android.content.integrity.AppIntegrityManager.STATUS_SUCCESS;
import static android.content.integrity.IntegrityUtils.getHexDigest;
import static android.content.pm.PackageManager.EXTRA_VERIFICATION_ID;

import android.annotation.BinderThread;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.integrity.IAppIntegrityManager;
import android.content.integrity.Rule;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.integrity.model.RuleMetadata;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Implementation of {@link AppIntegrityManagerService}. */
public class AppIntegrityManagerServiceImpl extends IAppIntegrityManager.Stub {

    private static final String TAG = "AppIntegrityManagerServiceImpl";

    private static final String PACKAGE_MIME_TYPE = "application/vnd.android.package-archive";

    public static final boolean DEBUG_INTEGRITY_COMPONENT = false;

    // Access to files inside mRulesDir is protected by mRulesLock;
    private final Context mContext;
    private final Handler mHandler;
    private final PackageManagerInternal mPackageManagerInternal;
    private final IntegrityFileManager mIntegrityFileManager;

    /** Create an instance of {@link AppIntegrityManagerServiceImpl}. */
    public static AppIntegrityManagerServiceImpl create(Context context) {
        HandlerThread handlerThread = new HandlerThread("AppIntegrityManagerServiceHandler");
        handlerThread.start();

        return new AppIntegrityManagerServiceImpl(
                context,
                LocalServices.getService(PackageManagerInternal.class),
                IntegrityFileManager.getInstance(),
                handlerThread.getThreadHandler());
    }

    @VisibleForTesting
    AppIntegrityManagerServiceImpl(
            Context context,
            PackageManagerInternal packageManagerInternal,
            IntegrityFileManager integrityFileManager,
            Handler handler) {
        mContext = context;
        mPackageManagerInternal = packageManagerInternal;
        mIntegrityFileManager = integrityFileManager;
        mHandler = handler;

        IntentFilter integrityVerificationFilter = new IntentFilter();
        integrityVerificationFilter.addAction(ACTION_PACKAGE_NEEDS_INTEGRITY_VERIFICATION);
        try {
            integrityVerificationFilter.addDataType(PACKAGE_MIME_TYPE);
        } catch (IntentFilter.MalformedMimeTypeException e) {
            throw new RuntimeException("Mime type malformed: should never happen.", e);
        }

        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (!ACTION_PACKAGE_NEEDS_INTEGRITY_VERIFICATION.equals(
                                intent.getAction())) {
                            return;
                        }
                        mHandler.post(() -> handleIntegrityVerification(intent));
                    }
                },
                integrityVerificationFilter,
                /* broadcastPermission= */ null,
                mHandler);
    }

    @Override
    @BinderThread
    public void updateRuleSet(
            String version, ParceledListSlice<Rule> rules, IntentSender statusReceiver) {
        String ruleProvider = getCallerPackageNameOrThrow(Binder.getCallingUid());
        if (DEBUG_INTEGRITY_COMPONENT) {
            Slog.i(TAG, String.format("Calling rule provider name is: %s.", ruleProvider));
        }

        mHandler.post(
                () -> {
                    boolean success = true;
                    try {
                        mIntegrityFileManager.writeRules(version, ruleProvider, rules.getList());
                    } catch (Exception e) {
                        Slog.e(TAG, "Error writing rules.", e);
                        success = false;
                    }

                    if (DEBUG_INTEGRITY_COMPONENT) {
                        Slog.i(
                                TAG,
                                String.format(
                                        "Successfully pushed rule set to version '%s' from '%s'",
                                        version, ruleProvider));
                    }

                    Intent intent = new Intent();
                    intent.putExtra(EXTRA_STATUS, success ? STATUS_SUCCESS : STATUS_FAILURE);
                    try {
                        statusReceiver.sendIntent(
                                mContext,
                                /* code= */ 0,
                                intent,
                                /* onFinished= */ null,
                                /* handler= */ null);
                    } catch (Exception e) {
                        Slog.e(TAG, "Error sending status feedback.", e);
                    }
                });
    }

    @Override
    @BinderThread
    public String getCurrentRuleSetVersion() {
        getCallerPackageNameOrThrow(Binder.getCallingUid());

        RuleMetadata ruleMetadata = mIntegrityFileManager.readMetadata();
        return (ruleMetadata != null && ruleMetadata.getVersion() != null)
                ? ruleMetadata.getVersion()
                : "";
    }

    @Override
    @BinderThread
    public String getCurrentRuleSetProvider() {
        getCallerPackageNameOrThrow(Binder.getCallingUid());

        RuleMetadata ruleMetadata = mIntegrityFileManager.readMetadata();
        return (ruleMetadata != null && ruleMetadata.getRuleProvider() != null)
                ? ruleMetadata.getRuleProvider()
                : "";
    }

    @Override
    public ParceledListSlice<Rule> getCurrentRules() {
        List<Rule> rules = Collections.emptyList();
        try {
            rules = mIntegrityFileManager.readRules(/* appInstallMetadata= */ null);
        } catch (Exception e) {
            Slog.e(TAG, "Error getting current rules", e);
        }
        return new ParceledListSlice<>(rules);
    }

    @Override
    public List<String> getWhitelistedRuleProviders() {
        return getAllowedRuleProviderSystemApps();
    }

    private void handleIntegrityVerification(Intent intent) {
        int verificationId = intent.getIntExtra(EXTRA_VERIFICATION_ID, -1);
        mPackageManagerInternal.setIntegrityVerificationResult(
                verificationId, PackageManagerInternal.INTEGRITY_VERIFICATION_ALLOW);
    }

    /** We will use the SHA256 digest of a package name if it is more than 32 bytes long. */
    private String getPackageNameNormalized(String packageName) {
        if (packageName.length() <= 32) {
            return packageName;
        }

        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = messageDigest.digest(packageName.getBytes(StandardCharsets.UTF_8));
            return getHexDigest(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }

    private String getCallerPackageNameOrThrow(int callingUid) {
        String callerPackageName = getCallingRulePusherPackageName(callingUid);
        if (callerPackageName == null) {
            throw new SecurityException(
                    "Only system packages specified in config_integrityRuleProviderPackages are "
                            + "allowed to call this method.");
        }
        return callerPackageName;
    }

    private String getCallingRulePusherPackageName(int callingUid) {
        // Obtain the system apps that are allowlisted in config_integrityRuleProviderPackages.
        List<String> allowedRuleProviders = getAllowedRuleProviderSystemApps();
        if (DEBUG_INTEGRITY_COMPONENT) {
            Slog.i(
                    TAG,
                    String.format(
                            "Rule provider system app list contains: %s", allowedRuleProviders));
        }

        // Identify the package names in the caller list.
        List<String> callingPackageNames = getPackageListForUid(callingUid);

        // Find the intersection between the allowed and calling packages. Ideally, we will have
        // at most one package name here. But if we have more, it is fine.
        List<String> allowedCallingPackages = new ArrayList<>();
        for (String packageName : callingPackageNames) {
            if (allowedRuleProviders.contains(packageName)) {
                allowedCallingPackages.add(packageName);
            }
        }

        return allowedCallingPackages.isEmpty() ? null : allowedCallingPackages.get(0);
    }

    private List<String> getAllowedRuleProviderSystemApps() {
        List<String> integrityRuleProviders =
                Arrays.asList(
                        mContext.getResources()
                                .getStringArray(R.array.config_integrityRuleProviderPackages));

        // Filter out the rule provider packages that are not system apps.
        List<String> systemAppRuleProviders = new ArrayList<>();
        for (String ruleProvider : integrityRuleProviders) {
            if (isSystemApp(ruleProvider)) {
                systemAppRuleProviders.add(ruleProvider);
            }
        }
        return systemAppRuleProviders;
    }

    private boolean isSystemApp(String packageName) {
        try {
            PackageInfo existingPackageInfo =
                    mContext.getPackageManager().getPackageInfo(packageName, /* flags= */ 0);
            return existingPackageInfo.applicationInfo != null
                    && existingPackageInfo.applicationInfo.isSystemApp();
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private List<String> getPackageListForUid(int uid) {
        try {
            return Arrays.asList(mContext.getPackageManager().getPackagesForUid(uid));
        } catch (NullPointerException e) {
            Slog.w(TAG, String.format("No packages were found for uid: %d", uid));
            return List.of();
        }
    }
}
