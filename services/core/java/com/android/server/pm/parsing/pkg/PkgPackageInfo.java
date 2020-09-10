/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.pm.parsing.pkg;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ActivityInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureGroupInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.parsing.component.ParsedActivity;
import android.content.pm.parsing.component.ParsedInstrumentation;
import android.content.pm.parsing.component.ParsedPermission;
import android.content.pm.parsing.component.ParsedProvider;
import android.content.pm.parsing.component.ParsedService;

import com.android.internal.R;

import java.util.List;

/**
 * Container for fields that are eventually exposed through {@link PackageInfo}.
 *
 * Done to separate the meaningless, re-directed JavaDoc for methods and to separate what's
 * exposed vs not exposed to core.
 *
 * @hide
 */
interface PkgPackageInfo {

    /**
     * @see PackageInfo#overlayCategory
     * @see R.styleable#AndroidManifestResourceOverlay_category
     */
    @Nullable
    String getOverlayCategory();

    /**
     * @see PackageInfo#overlayPriority
     * @see R.styleable#AndroidManifestResourceOverlay_priority
     */
    int getOverlayPriority();

    /**
     * @see PackageInfo#overlayTarget
     * @see R.styleable#AndroidManifestResourceOverlay_targetPackage
     */
    @Nullable
    String getOverlayTarget();

    /**
     * @see PackageInfo#targetOverlayableName
     * @see R.styleable#AndroidManifestResourceOverlay_targetName
     */
    @Nullable
    String getOverlayTargetName();

    /**
     * @see PackageInfo#sharedUserId
     * @see R.styleable#AndroidManifest_sharedUserId
     */
    @Deprecated
    @Nullable
    String getSharedUserId();

    /**
     * @see PackageInfo#sharedUserLabel
     * @see R.styleable#AndroidManifest_sharedUserLabel
     */
    @Deprecated
    int getSharedUserLabel();

    /**
     * The required account type without which this application will not function.
     *
     * @see PackageInfo#requiredAccountType
     * @see R.styleable#AndroidManifestApplication_requiredAccountType
     */
    @Nullable
    String getRequiredAccountType();

    /**
     * The restricted account authenticator type that is used by this application
     *
     * @see PackageInfo#restrictedAccountType
     * @see R.styleable#AndroidManifestApplication_restrictedAccountType
     */
    @Nullable
    String getRestrictedAccountType();

    /** @see PackageInfo#splitRevisionCodes */
    int[] getSplitRevisionCodes();

    /** @see PackageInfo#getLongVersionCode() */
    long getLongVersionCode();

    /** @see PackageInfo#versionCode */
    @Deprecated
    int getVersionCode();

    /** @see PackageInfo#versionCodeMajor */
    int getVersionCodeMajor();

    /** @see PackageInfo#versionName */
    @Nullable
    String getVersionName();

    /** @see PackageInfo#mOverlayIsStatic */
    boolean isOverlayIsStatic();

    /**
     * @see PackageInfo#requiredForAllUsers
     * @see R.styleable#AndroidManifestApplication_requiredForAllUsers
     */
    boolean isRequiredForAllUsers();

    /**
     * @see PackageInfo#reqFeatures
     * @see R.styleable#AndroidManifestUsesFeature
     */
    @NonNull
    List<FeatureInfo> getReqFeatures();

    /**
     * @see PackageInfo#configPreferences
     * @see R.styleable#AndroidManifestUsesConfiguration
     */
    @NonNull
    List<ConfigurationInfo> getConfigPreferences();

    /**
     * @see PackageInfo#featureGroups
     * @see R.styleable#AndroidManifestUsesFeature
     */
    @NonNull
    List<FeatureGroupInfo> getFeatureGroups();

    /**
     * Whether or not the package is a stub and must be replaced by the full version.
     *
     * @see PackageInfo#isStub
     */
    boolean isStub();

    /**
     * For marking packages required for a minimal boot state, through the "coreApp" manifest
     * attribute.
     * @see PackageInfo#coreApp
     */
    boolean isCoreApp();

    /**
     * All the permissions declared. This is an effective set, and may include permissions
     * transformed from split/migrated permissions from previous versions, so may not be exactly
     * what the package declares in its manifest.
     * @see PackageInfo#requestedPermissions
     * @see R.styleable#AndroidManifestUsesPermission
     */
    @NonNull
    List<String> getRequestedPermissions();

    /**
     * @see ActivityInfo
     * @see PackageInfo#activities
     */
    @NonNull
    List<ParsedActivity> getActivities();

    /**
     * @see InstrumentationInfo
     * @see PackageInfo#instrumentation
     */
    @NonNull
    List<ParsedInstrumentation> getInstrumentations();

    /**
     * @see PermissionInfo
     * @see PackageInfo#permissions
     */
    @NonNull
    List<ParsedPermission> getPermissions();

    /**
     * @see ProviderInfo
     * @see PackageInfo#providers
     */
    @NonNull
    List<ParsedProvider> getProviders();

    /**
     * Since they share several attributes, receivers are parsed as {@link ParsedActivity}, even
     * though they represent different functionality.
     * TODO(b/135203078): Reconsider this and maybe make ParsedReceiver so it's not so confusing
     * @see ActivityInfo
     * @see PackageInfo#receivers
     */
    @NonNull
    List<ParsedActivity> getReceivers();

    /**
     * @see ServiceInfo
     * @see PackageInfo#services
     */
    @NonNull
    List<ParsedService> getServices();
}
