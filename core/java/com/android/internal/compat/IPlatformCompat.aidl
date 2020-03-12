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

package com.android.internal.compat;

import android.content.pm.ApplicationInfo;
import com.android.internal.compat.IOverrideValidator;
import java.util.Map;

parcelable CompatibilityChangeConfig;
parcelable CompatibilityChangeInfo;

/**
 * Platform private API for talking with the PlatformCompat service.
 *
 * <p> Should be used for gating and logging from non-app processes.
 * For app processes please use android.compat.Compatibility API.
 *
 * {@hide}
 */
interface IPlatformCompat
{

    /**
     * Reports that a compatibility change is affecting an app process now.
     *
     * <p>Note: for changes that are gated using {@link #isChangeEnabled(long, ApplicationInfo)},
     * you do not need to call this API directly. The change will be reported for you.
     *
     * @param changeId The ID of the compatibility change taking effect.
     * @param appInfo  Representing the affected app.
     */
    void reportChange(long changeId, in ApplicationInfo appInfo);

    /**
     * Reports that a compatibility change is affecting an app process now.
     *
     * <p>Note: for changes that are gated using {@link #isChangeEnabled(long, String)},
     * you do not need to call this API directly. The change will be reported for you.
     *
     * @param changeId    The ID of the compatibility change taking effect.
     * @param userId      The ID of the user that the operation is done for.
     * @param packageName The package name of the app in question.
     */
     void reportChangeByPackageName(long changeId, in String packageName, int userId);

    /**
     * Reports that a compatibility change is affecting an app process now.
     *
     * <p>Note: for changes that are gated using {@link #isChangeEnabled(long, int)},
     * you do not need to call this API directly. The change will be reported for you.
     *
     * @param changeId The ID of the compatibility change taking effect.
     * @param uid      The UID of the app in question.
     */
    void reportChangeByUid(long changeId, int uid);

    /**
     * Query if a given compatibility change is enabled for an app process. This method should
     * be called when implementing functionality on behalf of the affected app.
     *
     * <p>If this method returns {@code true}, the calling code should implement the compatibility
     * change, resulting in differing behaviour compared to earlier releases. If this method returns
     * {@code false}, the calling code should behave as it did in earlier releases.
     *
     * <p>It will also report the change as {@link #reportChange(long, ApplicationInfo)} would, so
     * there is no need to call that method directly.
     *
     * @param changeId The ID of the compatibility change in question.
     * @param appInfo  Representing the app in question.
     * @return {@code true} if the change is enabled for the current app.
     */
    boolean isChangeEnabled(long changeId, in ApplicationInfo appInfo);

    /**
     * Query if a given compatibility change is enabled for an app process. This method should
     * be called when implementing functionality on behalf of the affected app.
     *
     * <p>Same as {@link #isChangeEnabled(long, ApplicationInfo)}, except it receives a package name
     * and userId instead of an {@link ApplicationInfo}
     * object, and finds an app info object based on the package name. Returns {@code true} if
     * there is no installed package by that name.
     *
     * <p>If this method returns {@code true}, the calling code should implement the compatibility
     * change, resulting in differing behaviour compared to earlier releases. If this method
     * returns
     * {@code false}, the calling code should behave as it did in earlier releases.
     *
     * <p>It will also report the change as {@link #reportChange(long, String)} would, so there is
     * no need to call that method directly.
     *
     * @param changeId    The ID of the compatibility change in question.
     * @param packageName The package name of the app in question.
     * @param userId      The ID of the user that the operation is done for.
     * @return {@code true} if the change is enabled for the current app.
     */
    boolean isChangeEnabledByPackageName(long changeId, in String packageName, int userId);

    /**
     * Query if a given compatibility change is enabled for an app process. This method should
     * be called when implementing functionality on behalf of the affected app.
     *
     * <p>Same as {@link #isChangeEnabled(long, ApplicationInfo)}, except it receives a uid
     * instead of an {@link ApplicationInfo} object, and finds an app info object based on the
     * uid (or objects if there's more than one package associated with the UID).
     * Returns {@code true} if there are no installed packages for the required UID, or if the
     * change is enabled for ALL of the installed packages associated with the provided UID. Please
     * use a more specific API if you want a different behaviour for multi-package UIDs.
     *
     * <p>If this method returns {@code true}, the calling code should implement the compatibility
     * change, resulting in differing behaviour compared to earlier releases. If this method
     * returns {@code false}, the calling code should behave as it did in earlier releases.
     *
     * <p>It will also report the change as {@link #reportChange(long, int)} would, so there is
     * no need to call that method directly.
     *
     * @param changeId The ID of the compatibility change in question.
     * @param uid      The UID of the app in question.
     * @return {@code true} if the change is enabled for the current app.
     */
    boolean isChangeEnabledByUid(long changeId, int uid);

    /**
     * Add overrides to compatibility changes. Kills the app to allow the changes to take effect.
     *
     * @param overrides Parcelable containing the compat change overrides to be applied.
     * @param packageName The package name of the app whose changes will be overridden.
     *
     */
    void setOverrides(in CompatibilityChangeConfig overrides, in String packageName);

    /**
     * Add overrides to compatibility changes. Doesn't kill the app, to be only used in tests.
     *
     * @param overrides Parcelable containing the compat change overrides to be applied.
     * @param packageName The package name of the app whose changes will be overridden.
     *
     */
    void setOverridesForTest(in CompatibilityChangeConfig overrides, in String packageName);

    /**
     * Removes an override previously added via {@link #setOverrides(CompatibilityChangeConfig,
     * String)}. This restores the default behaviour for the given change and app, once any app
     * processes have been restarted.
     * Kills the app to allow the changes to take effect.
     *
     * @param changeId    The ID of the change that was overridden.
     * @param packageName The app package name that was overridden.
     * @return {@code true} if an override existed;
     */
    boolean clearOverride(long changeId, String packageName);

    /**
     * Enable all compatibility changes which have enabledAfterTargetSdk ==
     * {@param targetSdkVersion} for an app, subject to the policy. Kills the app to allow the
     * changes to take effect.
     *
     * @param packageName The package name of the app whose compatibility changes will be enabled.
     * @param targetSdkVersion The targetSdkVersion for filtering the changes to be enabled.
     *
     * @return The number of changes that were enabled.
     */
    int enableTargetSdkChanges(in String packageName, int targetSdkVersion);

    /**
     * Disable all compatibility changes which have enabledAfterTargetSdk ==
     * {@param targetSdkVersion} for an app, subject to the policy. Kills the app to allow the
     * changes to take effect.
     *
     * @param packageName The package name of the app whose compatibility changes will be disabled.
     * @param targetSdkVersion The targetSdkVersion for filtering the changes to be disabled.
     *
     * @return The number of changes that were disabled.
     */
    int disableTargetSdkChanges(in String packageName, int targetSdkVersion);

    /**
     * Revert overrides to compatibility changes. Kills the app to allow the changes to take effect.
     *
     * @param packageName The package name of the app whose overrides will be cleared.
     *
     */
    void clearOverrides(in String packageName);

    /**
     * Revert overrides to compatibility changes. Doesn't kill the app, to be only used in tests.
     *
     * @param packageName The package name of the app whose overrides will be cleared.
     *
     */
    void clearOverridesForTest(in String packageName);


    /**
     * Get configs for an application.
     *
     * @param appInfo The application whose config will be returned.
     *
     * @return A {@link CompatibilityChangeConfig}, representing whether a change is enabled for
     *         the given app or not.
     */
    CompatibilityChangeConfig getAppConfig(in ApplicationInfo appInfo);

    /**
     * List all compatibility changes.
     *
     * @return An array of {@link CompatChangeInfo} known to the service.
     */
    CompatibilityChangeInfo[] listAllChanges();

    /**
     * Get an instance that can determine whether a changeid can be overridden for a package name.
     */
    IOverrideValidator getOverrideValidator();
}
