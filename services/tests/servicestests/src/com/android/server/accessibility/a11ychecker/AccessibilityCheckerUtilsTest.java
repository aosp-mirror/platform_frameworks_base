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

import static com.android.server.accessibility.a11ychecker.TestUtils.QUALIFIED_TEST_ACTIVITY_NAME;
import static com.android.server.accessibility.a11ychecker.TestUtils.TEST_A11Y_SERVICE_CLASS_NAME;
import static com.android.server.accessibility.a11ychecker.TestUtils.TEST_A11Y_SERVICE_SOURCE_PACKAGE_NAME;
import static com.android.server.accessibility.a11ychecker.TestUtils.TEST_ACTIVITY_NAME;
import static com.android.server.accessibility.a11ychecker.TestUtils.TEST_APP_PACKAGE_NAME;
import static com.android.server.accessibility.a11ychecker.TestUtils.createResult;
import static com.android.server.accessibility.a11ychecker.TestUtils.getMockPackageManagerWithInstalledApps;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.accessibility.AccessibilityCheckClass;
import android.accessibility.AccessibilityCheckResultType;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.runner.AndroidJUnit4;

import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckPreset;
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult;
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityHierarchyCheck;
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityHierarchyCheckResult;
import com.google.android.apps.common.testing.accessibility.framework.checks.ClassNameCheck;
import com.google.android.apps.common.testing.accessibility.framework.checks.ClickableSpanCheck;
import com.google.android.apps.common.testing.accessibility.framework.checks.SpeakableTextPresentCheck;
import com.google.android.apps.common.testing.accessibility.framework.checks.TouchTargetSizeCheck;
import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RunWith(AndroidJUnit4.class)
public class AccessibilityCheckerUtilsTest {

    PackageManager mMockPackageManager;

    @Before
    public void setUp() throws PackageManager.NameNotFoundException {
        mMockPackageManager = getMockPackageManagerWithInstalledApps();
    }

    @Test
    public void processResults_happyPath_setsAllFields() {
        AccessibilityNodeInfo mockNodeInfo =
                new MockAccessibilityNodeInfoBuilder()
                        .setViewIdResourceName("TargetNode")
                        .build();
        AccessibilityHierarchyCheckResult result1 =
                new AccessibilityHierarchyCheckResult(
                        SpeakableTextPresentCheck.class,
                        AccessibilityCheckResult.AccessibilityCheckResultType.WARNING, null, 1,
                        null);
        AccessibilityHierarchyCheckResult result2 =
                new AccessibilityHierarchyCheckResult(
                        TouchTargetSizeCheck.class,
                        AccessibilityCheckResult.AccessibilityCheckResultType.ERROR, null, 2, null);
        AccessibilityHierarchyCheckResult result3 =
                new AccessibilityHierarchyCheckResult(
                        ClassNameCheck.class,
                        AccessibilityCheckResult.AccessibilityCheckResultType.INFO, null, 5, null);
        AccessibilityHierarchyCheckResult result4 =
                new AccessibilityHierarchyCheckResult(
                        ClickableSpanCheck.class,
                        AccessibilityCheckResult.AccessibilityCheckResultType.NOT_RUN, null, 5,
                        null);


        AndroidAccessibilityCheckerResult.Builder resultBuilder =
                AccessibilityCheckerUtils.getCommonResultBuilder(mockNodeInfo, null,
                        mMockPackageManager,
                        new ComponentName(TEST_A11Y_SERVICE_SOURCE_PACKAGE_NAME,
                                TEST_A11Y_SERVICE_CLASS_NAME));
        Set<AndroidAccessibilityCheckerResult> results =
                AccessibilityCheckerUtils.processResults(mockNodeInfo,
                        List.of(result1, result2, result3, result4), resultBuilder);

        assertThat(results).containsExactly(
                createResult("TargetNode", "",
                        AccessibilityCheckClass.SPEAKABLE_TEXT_PRESENT_CHECK,
                        AccessibilityCheckResultType.WARNING_CHECK_RESULT_TYPE, 1),
                createResult("TargetNode", "",
                        AccessibilityCheckClass.TOUCH_TARGET_SIZE_CHECK,
                        AccessibilityCheckResultType.ERROR_CHECK_RESULT_TYPE, 2)
        );
    }

    @Test
    public void processResults_packageNameNotFound_returnsEmptySet()
            throws PackageManager.NameNotFoundException {
        when(mMockPackageManager.getPackageInfo("com.uninstalled.app", 0))
                .thenThrow(PackageManager.NameNotFoundException.class);
        AccessibilityNodeInfo mockNodeInfo =
                new MockAccessibilityNodeInfoBuilder()
                        .setPackageName("com.uninstalled.app")
                        .setViewIdResourceName("TargetNode")
                        .build();
        AccessibilityHierarchyCheckResult result1 =
                new AccessibilityHierarchyCheckResult(
                        TouchTargetSizeCheck.class,
                        AccessibilityCheckResult.AccessibilityCheckResultType.WARNING, null, 1,
                        null);
        AccessibilityHierarchyCheckResult result2 =
                new AccessibilityHierarchyCheckResult(
                        TouchTargetSizeCheck.class,
                        AccessibilityCheckResult.AccessibilityCheckResultType.ERROR, null, 2, null);

        AndroidAccessibilityCheckerResult.Builder resultBuilder =
                AccessibilityCheckerUtils.getCommonResultBuilder(mockNodeInfo, null,
                        mMockPackageManager,
                        new ComponentName(TEST_A11Y_SERVICE_SOURCE_PACKAGE_NAME,
                                TEST_A11Y_SERVICE_CLASS_NAME));
        Set<AndroidAccessibilityCheckerResult> results =
                AccessibilityCheckerUtils.processResults(mockNodeInfo,
                        List.of(result1, result2), resultBuilder);

        assertThat(results).isEmpty();
    }

    @Test
    public void getActivityName_hasValidActivityClassName_returnsActivityName() {
        assertThat(AccessibilityCheckerUtils.getActivityName(mMockPackageManager,
                TEST_APP_PACKAGE_NAME, QUALIFIED_TEST_ACTIVITY_NAME)).isEqualTo(TEST_ACTIVITY_NAME);
    }

    @Test
    public void getActivityName_hasInvalidActivityClassName_returnsActivityName() {
        assertThat(AccessibilityCheckerUtils.getActivityName(mMockPackageManager,
                TEST_APP_PACKAGE_NAME, "com.NonActivityClass")).isEmpty();
    }

    // Makes sure the AccessibilityHierarchyCheck class to enum mapping is up to date with the
    // latest prod preset.
    @Test
    public void checkClassToEnumMap_hasAllLatestPreset() {
        ImmutableSet<AccessibilityHierarchyCheck> checkPreset =
                AccessibilityCheckPreset.getAccessibilityHierarchyChecksForPreset(
                        AccessibilityCheckPreset.LATEST);
        Set<Class<? extends AccessibilityHierarchyCheck>> latestCheckClasses =
                checkPreset.stream().map(AccessibilityHierarchyCheck::getClass).collect(
                        Collectors.toUnmodifiableSet());

        assertThat(AccessibilityCheckerUtils.CHECK_CLASS_TO_ENUM_MAP.keySet())
                .containsExactlyElementsIn(latestCheckClasses);
    }

}
