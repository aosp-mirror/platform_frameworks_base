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

package com.android.server.pm.pkg.parsing;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureGroupInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;

import com.android.internal.R;
import com.android.server.pm.pkg.PackageUserState;
import com.android.server.pm.pkg.component.ParsedActivity;
import com.android.server.pm.pkg.component.ParsedInstrumentation;
import com.android.server.pm.pkg.component.ParsedPermission;
import com.android.server.pm.pkg.component.ParsedProvider;
import com.android.server.pm.pkg.component.ParsedService;

import java.util.List;

/**
 * Container for fields that are eventually exposed through {@link PackageInfo}.
 * <p>
 * The following are dependent on system state and explicitly removed from this interface. They must
 * be accessed by other means:
 * <ul>
 *    <li>{@link PackageInfo#firstInstallTime}</li>
 *    <li>{@link PackageInfo#lastUpdateTime}</li>
 *    <li>{@link PackageInfo#gids}</li>
 * </ul>
 * The following are derived from other fields and thus not provided specifically:
 * <ul>
 *    <li>{@link PackageInfo#requestedPermissionsFlags}</li>
 * </ul>
 * The following were deprecated at migration time and thus removed from this interface:
 * <ul>
 *    <li>{@link PackageInfo#mOverlayIsStatic}</li>
 *    <li>{@link PackageInfo#overlayCategory}</li>
 *    <li>{@link PackageInfo#overlayPriority}</li>
 *    <li>{@link PackageInfo#overlayTarget}</li>
 *    <li>{@link PackageInfo#signatures}</li>
 *    <li>{@link PackageInfo#targetOverlayableName}</li>
 *    <li>{@link PackageInfo#versionCodeMajor}</li>
 *    <li>{@link PackageInfo#versionCode}</li>
 * </ul>
 * The following are retrieved through other APIs:
 * <ul>
 *    <li>{@link PackageInfo#signingInfo}</li>
 *    <li>{@link PackageInfo#isApex}</li>
 * </ul>
 *
 * @hide
 */
public interface PkgWithoutStatePackageInfo {

    /**
     * Set of Activities parsed from the manifest.
     *
     * This contains minimal system state and does not
     * provide the same information as {@link ActivityInfo}. Effective state can be queried through
     * {@link android.content.pm.PackageManager#getActivityInfo(ComponentName, int)} or by
     * combining state from from com.android.server.pm.pkg.PackageState and
     * {@link PackageUserState}.
     *
     * @see ActivityInfo
     * @see PackageInfo#activities
     */
    @NonNull
    List<ParsedActivity> getActivities();

    /**
     * @see PackageInfo#baseRevisionCode
     */
    int getBaseRevisionCode();

    /**
     * @see PackageInfo#compileSdkVersion
     * @see R.styleable#AndroidManifest_compileSdkVersion
     */
    int getCompileSdkVersion();

    /**
     * @see ApplicationInfo#compileSdkVersionCodename
     * @see R.styleable#AndroidManifest_compileSdkVersionCodename
     */
    @Nullable
    String getCompileSdkVersionCodeName();

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
     * @see InstrumentationInfo
     * @see PackageInfo#instrumentation
     */
    @NonNull
    List<ParsedInstrumentation> getInstrumentations();

    /**
     * @see PackageInfo#getLongVersionCode()
     */
    long getLongVersionCode();

    /**
     * @see PackageInfo#packageName
     */
    String getPackageName();

    /**
     * @see PermissionInfo
     * @see PackageInfo#permissions
     */
    @NonNull
    List<ParsedPermission> getPermissions();

    /**
     * Set of {@link android.content.ContentProvider ContentProviders} parsed from the manifest.
     *
     * This contains minimal system state and does not
     * provide the same information as {@link ProviderInfo}. Effective state can be queried through
     * {@link android.content.pm.PackageManager#getProviderInfo(ComponentName, int)} or by
     * combining state from from com.android.server.pm.pkg.PackageState and
     * {@link PackageUserState}.
     *
     * @see ProviderInfo
     * @see PackageInfo#providers
     */
    @NonNull
    List<ParsedProvider> getProviders();

    /**
     * Set of {@link android.content.BroadcastReceiver BroadcastReceivers} parsed from the manifest.
     *
     * This contains minimal system state and does not
     * provide the same information as {@link ActivityInfo}. Effective state can be queried through
     * {@link android.content.pm.PackageManager#getReceiverInfo(ComponentName, int)} or by
     * combining state from from com.android.server.pm.pkg.PackageState and
     * {@link PackageUserState}.
     *
     * Since they share several attributes, receivers are parsed as {@link ParsedActivity}, even
     * though they represent different functionality.
     *
     * TODO(b/135203078): Reconsider this and maybe make ParsedReceiver so it's not so confusing
     *
     * @see ActivityInfo
     * @see PackageInfo#receivers
     */
    @NonNull
    List<ParsedActivity> getReceivers();

    /**
     * @see PackageInfo#reqFeatures
     * @see R.styleable#AndroidManifestUsesFeature
     */
    @NonNull
    List<FeatureInfo> getRequestedFeatures();

    /**
     * All the permissions declared. This is an effective set, and may include permissions
     * transformed from split/migrated permissions from previous versions, so may not be exactly
     * what the package declares in its manifest.
     *
     * @see PackageInfo#requestedPermissions
     * @see R.styleable#AndroidManifestUsesPermission
     */
    @NonNull
    List<String> getRequestedPermissions();

    /**
     * @see PackageInfo#requiredAccountType
     * @see R.styleable#AndroidManifestApplication_requiredAccountType
     */
    @Nullable
    String getRequiredAccountType();

    /**
     * The restricted account authenticator type that is used by this application.
     *
     * @see PackageInfo#restrictedAccountType
     * @see R.styleable#AndroidManifestApplication_restrictedAccountType
     */
    @Nullable
    String getRestrictedAccountType();

    /**
     * Set of {@link android.app.Service Services} parsed from the manifest.
     *
     * This contains minimal system state and does not
     * provide the same information as {@link ServiceInfo}. Effective state can be queried through
     * {@link android.content.pm.PackageManager#getServiceInfo(ComponentName, int)} or by
     * combining state from from com.android.server.pm.pkg.PackageState and
     * {@link PackageUserState}.
     *
     * @see ServiceInfo
     * @see PackageInfo#services
     */
    @NonNull
    List<ParsedService> getServices();

    /**
     * @see PackageInfo#sharedUserId
     * @see R.styleable#AndroidManifest_sharedUserId
     */
    @Nullable
    String getSharedUserId();

    /**
     * @see PackageInfo#sharedUserLabel
     * @see R.styleable#AndroidManifest_sharedUserLabel
     */
    int getSharedUserLabel();

    /**
     * TODO(b/135203078): Move split stuff to an inner data class
     *
     * @see ApplicationInfo#splitNames
     * @see PackageInfo#splitNames
     */
    @NonNull
    String[] getSplitNames();

    /**
     * @see PackageInfo#splitRevisionCodes
     */
    @NonNull
    int[] getSplitRevisionCodes();

    /**
     * @see PackageInfo#versionName
     */
    @Nullable
    String getVersionName();

    /**
     * @see PackageInfo#requiredForAllUsers
     * @see R.styleable#AndroidManifestApplication_requiredForAllUsers
     */
    boolean isRequiredForAllUsers();
}
