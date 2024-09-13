/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.server.accessibility.a11ychecker;

import static com.android.server.accessibility.a11ychecker.AccessibilityCheckerConstants.MAX_CACHE_CAPACITY;
import static com.android.server.accessibility.a11ychecker.AccessibilityCheckerConstants.MIN_DURATION_BETWEEN_CHECKS;

import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Slog;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.accessibility.Flags;

import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckPreset;
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityHierarchyCheck;
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityHierarchyCheckResult;
import com.google.android.apps.common.testing.accessibility.framework.uielement.AccessibilityHierarchy;
import com.google.android.apps.common.testing.accessibility.framework.uielement.AccessibilityHierarchyAndroid;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


/**
 * The class responsible for running AccessibilityChecks on cached nodes and caching the results for
 * logging. Results are cached and capped to limit the logging frequency and size.
 *
 * @hide
 */
public final class AccessibilityCheckerManager {
    private static final String LOG_TAG = "AccessibilityCheckerManager";

    private final PackageManager mPackageManager;
    private final Set<AccessibilityHierarchyCheck> mHierarchyChecks;
    private final ATFHierarchyBuilder mATFHierarchyBuilder;
    private final Set<AndroidAccessibilityCheckerResult> mCachedResults = new HashSet<>();

    @VisibleForTesting
    final A11yCheckerTimer mTimer = new A11yCheckerTimer();

    public AccessibilityCheckerManager(Context context) {
        this(AccessibilityCheckPreset.getAccessibilityHierarchyChecksForPreset(
                        AccessibilityCheckPreset.LATEST),
                (nodeInfo) -> AccessibilityHierarchyAndroid.newBuilder(nodeInfo, context).build(),
                context.getPackageManager());
    }

    @VisibleForTesting
    AccessibilityCheckerManager(
            Set<AccessibilityHierarchyCheck> hierarchyChecks,
            ATFHierarchyBuilder atfHierarchyBuilder,
            PackageManager packageManager) {
        this.mHierarchyChecks = hierarchyChecks;
        this.mATFHierarchyBuilder = atfHierarchyBuilder;
        this.mPackageManager = packageManager;
    }

    /**
     * If eligible, runs AccessibilityChecks on the given nodes and caches the results for later
     * logging. Returns the check results for the given nodes.
     */
    @RequiresPermission(allOf = {android.Manifest.permission.INTERACT_ACROSS_USERS_FULL})
    public Set<AndroidAccessibilityCheckerResult> maybeRunA11yChecker(
            List<AccessibilityNodeInfo> nodes, @Nullable String sourceEventClassName,
            ComponentName a11yServiceComponentName, @UserIdInt int userId) {
        if (!shouldRunA11yChecker() || nodes.isEmpty()) {
            return Set.of();
        }

        Set<AndroidAccessibilityCheckerResult> allResults = new HashSet<>();
        String defaultBrowserName = mPackageManager.getDefaultBrowserPackageNameAsUser(userId);

        try {
            AndroidAccessibilityCheckerResult.Builder commonResultBuilder =
                    AccessibilityCheckerUtils.getCommonResultBuilder(nodes.getFirst(),
                            sourceEventClassName, mPackageManager, a11yServiceComponentName);
            if (commonResultBuilder == null) {
                return Set.of();
            }
            for (AccessibilityNodeInfo nodeInfo : nodes) {
                // Skip browser results because they are mostly related to web content and
                // not the browser app itself.
                if (nodeInfo.getPackageName() == null
                        || nodeInfo.getPackageName().toString().equals(defaultBrowserName)) {
                    continue;
                }
                List<AccessibilityHierarchyCheckResult> checkResults = runChecksOnNode(
                        nodeInfo);
                Set<AndroidAccessibilityCheckerResult> filteredResults =
                        AccessibilityCheckerUtils.processResults(nodeInfo, checkResults,
                                commonResultBuilder);
                allResults.addAll(filteredResults);
            }
            mCachedResults.addAll(allResults);
            return allResults;

        } catch (RuntimeException e) {
            Slog.e(LOG_TAG, "An unknown error occurred while running a11y checker.", e);
            return Set.of();
        }
    }

    private List<AccessibilityHierarchyCheckResult> runChecksOnNode(
            AccessibilityNodeInfo nodeInfo) {
        AccessibilityHierarchy checkableHierarchy = mATFHierarchyBuilder.getATFCheckableHierarchy(
                nodeInfo);
        List<AccessibilityHierarchyCheckResult> checkResults = new ArrayList<>();
        for (AccessibilityHierarchyCheck check : mHierarchyChecks) {
            checkResults.addAll(check.runCheckOnHierarchy(checkableHierarchy));
        }
        return checkResults;
    }

    public Set<AndroidAccessibilityCheckerResult> getCachedResults() {
        return Collections.unmodifiableSet(mCachedResults);
    }

    @VisibleForTesting
    boolean shouldRunA11yChecker() {
        if (!Flags.enableA11yCheckerLogging() || mCachedResults.size() == MAX_CACHE_CAPACITY) {
            return false;
        }
        if (mTimer.getLastCheckTime() == null || mTimer.getLastCheckTime().plus(
                MIN_DURATION_BETWEEN_CHECKS).isBefore(Instant.now())) {
            mTimer.setLastCheckTime(Instant.now());
            return true;
        }
        return false;
    }

    /** Timer class to facilitate testing with fake times. */
    @VisibleForTesting
    static class A11yCheckerTimer {
        private Instant mLastCheckTime = null;

        Instant getLastCheckTime() {
            return mLastCheckTime;
        }

        void setLastCheckTime(Instant newTime) {
            mLastCheckTime = newTime;
        }
    }

    /** AccessibilityHierarchy wrapper to facilitate testing with fake hierarchies. */
    @VisibleForTesting
    interface ATFHierarchyBuilder {
        AccessibilityHierarchy getATFCheckableHierarchy(AccessibilityNodeInfo nodeInfo);
    }
}
