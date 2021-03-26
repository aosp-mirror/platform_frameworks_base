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
parcelable CompatibilityOverrideConfig;
parcelable CompatibilityChangeInfo;
/**
 * Platform private API for talking with the PlatformCompat service.
 *
 * <p>Should be used for gating and logging from non-app processes.
 *
 * <p>Note: for app processes please use {@code android.compat.Compatibility} API.
 *
 * {@hide}
 */
interface IPlatformCompat {

    /**
     * Reports that a compatibility change is affecting an app process now.
     *
     * <p>Note: for changes that are gated using {@link #isChangeEnabled(long, ApplicationInfo)},
     * you do not need to call this API directly. The change will be reported for you.
     *
     * @param changeId the ID of the compatibility change taking effect
     * @param appInfo  representing the affected app
     * @throws SecurityException if logging is not allowed
     */
    void reportChange(long changeId, in ApplicationInfo appInfo);

    /**
     * Reports that a compatibility change is affecting an app process now.
     *
     * <p>Note: for changes that are gated using {@link #isChangeEnabled(long, String)},
     * you do not need to call this API directly. The change will be reported for you.
     *
     * @param changeId    the ID of the compatibility change taking effect
     * @param userId      the ID of the user that the operation is done for
     * @param packageName the package name of the app in question
     * @throws SecurityException if logging is not allowed
     */
    void reportChangeByPackageName(long changeId, in String packageName, int userId);

    /**
     * Reports that a compatibility change is affecting an app process now.
     *
     * <p>Note: for changes that are gated using {@link #isChangeEnabled(long, int)},
     * you do not need to call this API directly. The change will be reported for you.
     *
     * @param changeId the ID of the compatibility change taking effect
     * @param uid      the UID of the app in question
     * @throws SecurityException if logging is not allowed
     */
    void reportChangeByUid(long changeId, int uid);

    /**
     * Queries if a given compatibility change is enabled for an app process. This method should
     * be called when implementing functionality on behalf of the affected app.
     *
     * <p>If this method returns {@code true}, the calling code should implement the compatibility
     * change, resulting in differing behaviour compared to earlier releases. If this method returns
     * {@code false}, the calling code should behave as it did in earlier releases.
     *
     * <p>It will also report the change as {@link #reportChange(long, ApplicationInfo)} would, so
     * there is no need to call that method directly.
     *
     * @param changeId the ID of the compatibility change in question
     * @param appInfo  representing the app in question
     * @return {@code true} if the change is enabled for the current app
     * @throws SecurityException if logging or reading compat confis is not allowed
     */
    boolean isChangeEnabled(long changeId, in ApplicationInfo appInfo);

    /**
     * Queries if a given compatibility change is enabled for an app process. This method should
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
     * @param changeId    the ID of the compatibility change in question
     * @param packageName the package name of the app in question
     * @param userId      the ID of the user that the operation is done for
     * @return {@code true} if the change is enabled for the current app
     * @throws SecurityException if logging or reading compat confis is not allowed
     */
    boolean isChangeEnabledByPackageName(long changeId, in String packageName, int userId);

    /**
     * Queries if a given compatibility change is enabled for an app process. This method should
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
     * @param changeId the ID of the compatibility change in question
     * @param uid      the UID of the app in question
     * @return {@code true} if the change is enabled for the current app
     * @throws SecurityException if logging or reading compat confis is not allowed
     */
    boolean isChangeEnabledByUid(long changeId, int uid);

    /**
     * Adds overrides to compatibility changes.
     *
     * <p>Kills the app to allow the changes to take effect.
     *
     * @param overrides   parcelable containing the compat change overrides to be applied
     * @param packageName the package name of the app whose changes will be overridden
     * @throws SecurityException if overriding changes is not permitted
     */
    void setOverrides(in CompatibilityChangeConfig overrides, in String packageName);

    /**
     * Adds overrides to compatibility changes on release builds.
     *
     * <p>The caller to this API needs to hold
     * {@code android.permission.OVERRIDE_COMPAT_CHANGE_CONFIG_ON_RELEASE_BUILD} and all change ids
     * in {@code overrides} need to annotated with {@link android.compat.annotation.Overridable}.
     *
     * A release build in this definition means that {@link android.os.Build#IS_DEBUGGABLE} needs to
     * be {@code false}.
     *
     * <p>Note that this does not kill the app, and therefore overrides read from the app process
     * will not be updated. Overrides read from the system process do take effect.
     *
     * @param overrides   parcelable containing the compat change overrides to be applied
     * @param packageName the package name of the app whose changes will be overridden
     * @throws SecurityException if overriding changes is not permitted
     */
    void setOverridesOnReleaseBuilds(in CompatibilityOverrideConfig overrides, in String packageName);

    /**
     * Adds overrides to compatibility changes.
     *
     * <p>Does not kill the app, to be only used in tests.
     *
     * @param overrides   parcelable containing the compat change overrides to be applied
     * @param packageName the package name of the app whose changes will be overridden
     * @throws SecurityException if overriding changes is not permitted.
     */
    void setOverridesForTest(in CompatibilityChangeConfig overrides, in String packageName);

    /**
     * Restores the default behaviour for the given change and app.
     *
     * <p>Kills the app to allow the changes to take effect.
     *
     * @param changeId    the ID of the change that was overridden
     * @param packageName the app package name that was overridden
     * @return {@code true} if an override existed
     * @throws SecurityException if overriding changes is not permitted
     */
    boolean clearOverride(long changeId, String packageName);

    /**
     * Restores the default behaviour for the given change and app.
     *
     * <p>Does not kill the app; to be only used in tests.
     *
     * @param changeId    the ID of the change that was overridden
     * @param packageName the app package name that was overridden
     * @return {@code true} if an override existed
     * @throws SecurityException if overriding changes is not permitted
     */
    boolean clearOverrideForTest(long changeId, String packageName);

    /**
     * Enables all compatibility changes that have enabledSinceTargetSdk ==
     * {@param targetSdkVersion} for an app, subject to the policy.
     *
     * <p>Kills the app to allow the changes to take effect.
     *
     * @param packageName      The package name of the app whose compatibility changes will be
     *                         enabled.
     * @param targetSdkVersion The targetSdkVersion for filtering the changes to be enabled.
     * @return The number of changes that were enabled.
     * @throws SecurityException if overriding changes is not permitted.
     */
    int enableTargetSdkChanges(in String packageName, int targetSdkVersion);

    /**
     * Disables all compatibility changes that have enabledAfterTargetSdk ==
     * {@param targetSdkVersion} for an app, subject to the policy.
     *
     * <p>Kills the app to allow the changes to take effect.
     *
     * @param packageName      the package name of the app whose compatibility changes will be
     *                         disabled
     * @param targetSdkVersion the targetSdkVersion for filtering the changes to be disabled
     * @return the number of changes that were disabled
     * @throws SecurityException if overriding changes is not permitted.
     */
    int disableTargetSdkChanges(in String packageName, int targetSdkVersion);

    /**
     * Restores the default behaviour for the given app.
     *
     * <p>Kills the app to allow the changes to take effect.
     *
     * @param packageName the package name of the app whose overrides will be cleared
     * @throws SecurityException if overriding changes is not permitted
     */
    void clearOverrides(in String packageName);

    /**
     * Restores the default behaviour for the given app.
     *
     * <p>Does not kill the app; to be only used in tests.
     *
     * @param packageName the package name of the app whose overrides will be cleared
     * @throws SecurityException if overriding changes is not permitted
     */
    void clearOverridesForTest(in String packageName);

    /**
     * Get configs for an application.
     *
     * @param appInfo the application whose config will be returned
     * @return a {@link CompatibilityChangeConfig}, representing whether a change is enabled for
     * the given app or not
     */
    CompatibilityChangeConfig getAppConfig(in ApplicationInfo appInfo);

    /**
     * List all compatibility changes.
     *
     * @return an array of {@link CompatibilityChangeInfo} known to the service
     */
    CompatibilityChangeInfo[] listAllChanges();

    /**
     * List the compatibility changes that should be present in the UI.
     * Filters out certain changes like e.g. logging only.
     *
     * @return an array of {@link CompatibilityChangeInfo}
     */
    CompatibilityChangeInfo[] listUIChanges();

    /**
     * Gets an instance that can determine whether a changeid can be overridden for a package name.
     */
    IOverrideValidator getOverrideValidator();
}
