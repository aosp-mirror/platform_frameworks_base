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

import static com.android.server.accessibility.Flags.FLAG_ENABLE_A11Y_CHECKER_LOGGING;
import static com.android.server.accessibility.a11ychecker.AccessibilityCheckerConstants.MIN_DURATION_BETWEEN_CHECKS;
import static com.android.server.accessibility.a11ychecker.TestUtils.QUALIFIED_TEST_ACTIVITY_NAME;
import static com.android.server.accessibility.a11ychecker.TestUtils.TEST_A11Y_SERVICE_CLASS_NAME;
import static com.android.server.accessibility.a11ychecker.TestUtils.TEST_A11Y_SERVICE_SOURCE_PACKAGE_NAME;
import static com.android.server.accessibility.a11ychecker.TestUtils.TEST_ACTIVITY_NAME;
import static com.android.server.accessibility.a11ychecker.TestUtils.TEST_DEFAULT_BROWSER;
import static com.android.server.accessibility.a11ychecker.TestUtils.createResult;
import static com.android.server.accessibility.a11ychecker.TestUtils.getMockPackageManagerWithInstalledApps;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.accessibility.AccessibilityCheckClass;
import android.accessibility.AccessibilityCheckResultType;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.runner.AndroidJUnit4;

import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult;
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityHierarchyCheck;
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityHierarchyCheckResult;
import com.google.android.apps.common.testing.accessibility.framework.checks.TouchTargetSizeCheck;
import com.google.android.apps.common.testing.accessibility.framework.uielement.AccessibilityHierarchy;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class AccessibilityCheckerManagerTest {
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private AccessibilityCheckerManager mAccessibilityCheckerManager;

    @Before
    public void setup() throws PackageManager.NameNotFoundException {
        PackageManager mockPackageManager = getMockPackageManagerWithInstalledApps();
        mAccessibilityCheckerManager = new AccessibilityCheckerManager(setupMockChecks(),
                nodeInfo -> mock(AccessibilityHierarchy.class), mockPackageManager);
    }


    @Test
    @EnableFlags(FLAG_ENABLE_A11Y_CHECKER_LOGGING)
    public void shouldRunA11yChecker_firstUpdate() {
        assertThat(mAccessibilityCheckerManager.shouldRunA11yChecker()).isTrue();
    }

    @Test
    @EnableFlags(FLAG_ENABLE_A11Y_CHECKER_LOGGING)
    public void shouldRunA11yChecker_minDurationPassed() {
        mAccessibilityCheckerManager.mTimer.setLastCheckTime(
                Instant.now().minus(MIN_DURATION_BETWEEN_CHECKS.plus(Duration.ofSeconds(2))));
        assertThat(mAccessibilityCheckerManager.shouldRunA11yChecker()).isTrue();
    }

    @Test
    @EnableFlags(FLAG_ENABLE_A11Y_CHECKER_LOGGING)
    public void shouldRunA11yChecker_tooEarly() {
        mAccessibilityCheckerManager.mTimer.setLastCheckTime(
                Instant.now().minus(MIN_DURATION_BETWEEN_CHECKS.minus(Duration.ofSeconds(2))));
        assertThat(mAccessibilityCheckerManager.shouldRunA11yChecker()).isFalse();
    }

    @Test
    @DisableFlags(FLAG_ENABLE_A11Y_CHECKER_LOGGING)
    public void shouldRunA11yChecker_featureDisabled() {
        assertThat(mAccessibilityCheckerManager.shouldRunA11yChecker()).isFalse();
    }

    @Test
    @EnableFlags(FLAG_ENABLE_A11Y_CHECKER_LOGGING)
    public void maybeRunA11yChecker_happyPath() {
        AccessibilityNodeInfo mockNodeInfo1 =
                new MockAccessibilityNodeInfoBuilder()
                        .setViewIdResourceName("node1")
                        .build();
        AccessibilityNodeInfo mockNodeInfo2 =
                new MockAccessibilityNodeInfoBuilder()
                        .setViewIdResourceName("node2")
                        .build();

        Set<AndroidAccessibilityCheckerResult> results =
                mAccessibilityCheckerManager.maybeRunA11yChecker(
                        List.of(mockNodeInfo1, mockNodeInfo2), QUALIFIED_TEST_ACTIVITY_NAME,
                        new ComponentName(TEST_A11Y_SERVICE_SOURCE_PACKAGE_NAME,
                                TEST_A11Y_SERVICE_CLASS_NAME), /*userId=*/ 0);

        assertThat(results).containsExactly(
                createResult(/*viewIdResourceName=*/ "node1", TEST_ACTIVITY_NAME,
                        AccessibilityCheckClass.TOUCH_TARGET_SIZE_CHECK,
                        AccessibilityCheckResultType.ERROR_CHECK_RESULT_TYPE, /*resultId=*/ 2),
                createResult(/*viewIdResourceName=*/ "node2", TEST_ACTIVITY_NAME,
                        AccessibilityCheckClass.TOUCH_TARGET_SIZE_CHECK,
                        AccessibilityCheckResultType.ERROR_CHECK_RESULT_TYPE, /*resultId=*/ 2)
        );
    }

    @Test
    @EnableFlags(FLAG_ENABLE_A11Y_CHECKER_LOGGING)
    public void maybeRunA11yChecker_skipsNodesFromDefaultBrowser() {
        AccessibilityNodeInfo mockNodeInfo =
                new MockAccessibilityNodeInfoBuilder()
                        .setPackageName(TEST_DEFAULT_BROWSER)
                        .setViewIdResourceName("node1")
                        .build();

        Set<AndroidAccessibilityCheckerResult> results =
                mAccessibilityCheckerManager.maybeRunA11yChecker(
                        List.of(mockNodeInfo), QUALIFIED_TEST_ACTIVITY_NAME,
                        new ComponentName(TEST_A11Y_SERVICE_SOURCE_PACKAGE_NAME,
                                TEST_A11Y_SERVICE_CLASS_NAME), /*userId=*/ 0);

        assertThat(results).isEmpty();
    }

    @Test
    @EnableFlags(FLAG_ENABLE_A11Y_CHECKER_LOGGING)
    public void maybeRunA11yChecker_doesNotStoreDuplicates() {
        AccessibilityNodeInfo mockNodeInfo =
                new MockAccessibilityNodeInfoBuilder()
                        .setViewIdResourceName("node1")
                        .build();
        AccessibilityNodeInfo mockNodeInfoDuplicate =
                new MockAccessibilityNodeInfoBuilder()
                        .setViewIdResourceName("node1")
                        .build();

        Set<AndroidAccessibilityCheckerResult> results =
                mAccessibilityCheckerManager.maybeRunA11yChecker(
                        List.of(mockNodeInfo, mockNodeInfoDuplicate), QUALIFIED_TEST_ACTIVITY_NAME,
                        new ComponentName(TEST_A11Y_SERVICE_SOURCE_PACKAGE_NAME,
                                TEST_A11Y_SERVICE_CLASS_NAME), /*userId=*/ 0);

        assertThat(results).containsExactly(
                createResult(/*viewIdResourceName=*/ "node1", TEST_ACTIVITY_NAME,
                        AccessibilityCheckClass.TOUCH_TARGET_SIZE_CHECK,
                        AccessibilityCheckResultType.ERROR_CHECK_RESULT_TYPE, /*resultId=*/
                        2)
        );
    }

    private Set<AccessibilityHierarchyCheck> setupMockChecks() {
        AccessibilityHierarchyCheck mockCheck1 = mock(AccessibilityHierarchyCheck.class);
        AccessibilityHierarchyCheckResult infoTypeResult =
                new AccessibilityHierarchyCheckResult(
                        TouchTargetSizeCheck.class,
                        AccessibilityCheckResult.AccessibilityCheckResultType.INFO, null, 1, null);
        when(mockCheck1.runCheckOnHierarchy(any())).thenReturn(List.of(infoTypeResult));

        AccessibilityHierarchyCheck mockCheck2 = mock(AccessibilityHierarchyCheck.class);
        AccessibilityHierarchyCheckResult errorTypeResult =
                new AccessibilityHierarchyCheckResult(
                        TouchTargetSizeCheck.class,
                        AccessibilityCheckResult.AccessibilityCheckResultType.ERROR, null, 2,
                        null);
        when(mockCheck2.runCheckOnHierarchy(any())).thenReturn(List.of(errorTypeResult));

        return Set.of(mockCheck1, mockCheck2);
    }
}
