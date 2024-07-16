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

import static com.android.server.accessibility.a11ychecker.TestUtils.TEST_A11Y_SERVICE_CLASS_NAME;
import static com.android.server.accessibility.a11ychecker.TestUtils.TEST_A11Y_SERVICE_SOURCE_PACKAGE_NAME;
import static com.android.server.accessibility.a11ychecker.TestUtils.TEST_A11Y_SERVICE_SOURCE_VERSION_CODE;
import static com.android.server.accessibility.a11ychecker.TestUtils.TEST_ACTIVITY_NAME;
import static com.android.server.accessibility.a11ychecker.TestUtils.TEST_APP_PACKAGE_NAME;
import static com.android.server.accessibility.a11ychecker.TestUtils.TEST_APP_VERSION_CODE;
import static com.android.server.accessibility.a11ychecker.TestUtils.TEST_WINDOW_TITLE;
import static com.android.server.accessibility.a11ychecker.TestUtils.getMockPackageManagerWithInstalledApps;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.view.accessibility.AccessibilityEvent;
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

        Set<A11yCheckerProto.AccessibilityCheckResultReported> atoms =
                AccessibilityCheckerUtils.processResults(
                        mockNodeInfo,
                        List.of(result1, result2, result3, result4),
                        null,
                        mMockPackageManager,
                        new ComponentName(TEST_A11Y_SERVICE_SOURCE_PACKAGE_NAME,
                                TEST_A11Y_SERVICE_CLASS_NAME));

        assertThat(atoms).containsExactly(
                createAtom(A11yCheckerProto.AccessibilityCheckClass.SPEAKABLE_TEXT_PRESENT_CHECK,
                        A11yCheckerProto.AccessibilityCheckResultType.WARNING, 1),
                createAtom(A11yCheckerProto.AccessibilityCheckClass.TOUCH_TARGET_SIZE_CHECK,
                        A11yCheckerProto.AccessibilityCheckResultType.ERROR, 2)
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

        Set<A11yCheckerProto.AccessibilityCheckResultReported> atoms =
                AccessibilityCheckerUtils.processResults(
                        mockNodeInfo,
                        List.of(result1, result2),
                        null,
                        mMockPackageManager,
                        new ComponentName(TEST_A11Y_SERVICE_SOURCE_PACKAGE_NAME,
                                TEST_A11Y_SERVICE_CLASS_NAME));

        assertThat(atoms).isEmpty();
    }

    @Test
    public void getActivityName_hasWindowStateChangedEvent_returnsActivityName() {
        AccessibilityEvent accessibilityEvent =
                AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        accessibilityEvent.setPackageName(TEST_APP_PACKAGE_NAME);
        accessibilityEvent.setClassName(TEST_ACTIVITY_NAME);

        assertThat(AccessibilityCheckerUtils.getActivityName(mMockPackageManager,
                accessibilityEvent)).isEqualTo("MainActivity");
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


    private static A11yCheckerProto.AccessibilityCheckResultReported createAtom(
            A11yCheckerProto.AccessibilityCheckClass checkClass,
            A11yCheckerProto.AccessibilityCheckResultType resultType,
            int resultId) {
        return A11yCheckerProto.AccessibilityCheckResultReported.newBuilder()
                .setPackageName(TEST_APP_PACKAGE_NAME)
                .setAppVersionCode(TEST_APP_VERSION_CODE)
                .setUiElementPath(TEST_APP_PACKAGE_NAME + ":TargetNode")
                .setWindowTitle(TEST_WINDOW_TITLE)
                .setActivityName("")
                .setSourceComponentName(new ComponentName(TEST_A11Y_SERVICE_SOURCE_PACKAGE_NAME,
                        TEST_A11Y_SERVICE_CLASS_NAME).flattenToString())
                .setSourceVersionCode(TEST_A11Y_SERVICE_SOURCE_VERSION_CODE)
                .setResultCheckClass(checkClass)
                .setResultType(resultType)
                .setResultId(resultId)
                .build();
    }

}
