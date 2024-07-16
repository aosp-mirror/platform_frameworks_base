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


import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Slog;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.accessibility.a11ychecker.A11yCheckerProto.AccessibilityCheckClass;
import com.android.server.accessibility.a11ychecker.A11yCheckerProto.AccessibilityCheckResultReported;
import com.android.server.accessibility.a11ychecker.A11yCheckerProto.AccessibilityCheckResultType;

import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult;
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityHierarchyCheck;
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityHierarchyCheckResult;
import com.google.android.apps.common.testing.accessibility.framework.checks.ClassNameCheck;
import com.google.android.apps.common.testing.accessibility.framework.checks.ClickableSpanCheck;
import com.google.android.apps.common.testing.accessibility.framework.checks.DuplicateClickableBoundsCheck;
import com.google.android.apps.common.testing.accessibility.framework.checks.DuplicateSpeakableTextCheck;
import com.google.android.apps.common.testing.accessibility.framework.checks.EditableContentDescCheck;
import com.google.android.apps.common.testing.accessibility.framework.checks.ImageContrastCheck;
import com.google.android.apps.common.testing.accessibility.framework.checks.LinkPurposeUnclearCheck;
import com.google.android.apps.common.testing.accessibility.framework.checks.RedundantDescriptionCheck;
import com.google.android.apps.common.testing.accessibility.framework.checks.SpeakableTextPresentCheck;
import com.google.android.apps.common.testing.accessibility.framework.checks.TextContrastCheck;
import com.google.android.apps.common.testing.accessibility.framework.checks.TextSizeCheck;
import com.google.android.apps.common.testing.accessibility.framework.checks.TouchTargetSizeCheck;
import com.google.android.apps.common.testing.accessibility.framework.checks.TraversalOrderCheck;

import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Util class to process a11y checker results for logging.
 *
 * @hide
 */
public class AccessibilityCheckerUtils {

    private static final String LOG_TAG = "AccessibilityCheckerUtils";
    @VisibleForTesting
    // LINT.IfChange
    static final Map<Class<? extends AccessibilityHierarchyCheck>, AccessibilityCheckClass>
            CHECK_CLASS_TO_ENUM_MAP =
            Map.ofEntries(
                    classMapEntry(ClassNameCheck.class, AccessibilityCheckClass.CLASS_NAME_CHECK),
                    classMapEntry(ClickableSpanCheck.class,
                            AccessibilityCheckClass.CLICKABLE_SPAN_CHECK),
                    classMapEntry(DuplicateClickableBoundsCheck.class,
                            AccessibilityCheckClass.DUPLICATE_CLICKABLE_BOUNDS_CHECK),
                    classMapEntry(DuplicateSpeakableTextCheck.class,
                            AccessibilityCheckClass.DUPLICATE_SPEAKABLE_TEXT_CHECK),
                    classMapEntry(EditableContentDescCheck.class,
                            AccessibilityCheckClass.EDITABLE_CONTENT_DESC_CHECK),
                    classMapEntry(ImageContrastCheck.class,
                            AccessibilityCheckClass.IMAGE_CONTRAST_CHECK),
                    classMapEntry(LinkPurposeUnclearCheck.class,
                            AccessibilityCheckClass.LINK_PURPOSE_UNCLEAR_CHECK),
                    classMapEntry(RedundantDescriptionCheck.class,
                            AccessibilityCheckClass.REDUNDANT_DESCRIPTION_CHECK),
                    classMapEntry(SpeakableTextPresentCheck.class,
                            AccessibilityCheckClass.SPEAKABLE_TEXT_PRESENT_CHECK),
                    classMapEntry(TextContrastCheck.class,
                            AccessibilityCheckClass.TEXT_CONTRAST_CHECK),
                    classMapEntry(TextSizeCheck.class, AccessibilityCheckClass.TEXT_SIZE_CHECK),
                    classMapEntry(TouchTargetSizeCheck.class,
                            AccessibilityCheckClass.TOUCH_TARGET_SIZE_CHECK),
                    classMapEntry(TraversalOrderCheck.class,
                            AccessibilityCheckClass.TRAVERSAL_ORDER_CHECK));
    // LINT.ThenChange(/services/accessibility/java/com/android/server/accessibility/a11ychecker/proto/a11ychecker.proto)

    static Set<AccessibilityCheckResultReported> processResults(
            Context context,
            AccessibilityNodeInfo nodeInfo,
            List<AccessibilityHierarchyCheckResult> checkResults,
            @Nullable AccessibilityEvent accessibilityEvent,
            ComponentName a11yServiceComponentName) {
        return processResults(nodeInfo, checkResults, accessibilityEvent,
                context.getPackageManager(), a11yServiceComponentName);
    }

    @VisibleForTesting
    static Set<AccessibilityCheckResultReported> processResults(
            AccessibilityNodeInfo nodeInfo,
            List<AccessibilityHierarchyCheckResult> checkResults,
            @Nullable AccessibilityEvent accessibilityEvent,
            PackageManager packageManager,
            ComponentName a11yServiceComponentName) {
        String appPackageName = nodeInfo.getPackageName().toString();
        AccessibilityCheckResultReported.Builder builder;
        try {
            builder = AccessibilityCheckResultReported.newBuilder()
                    .setPackageName(appPackageName)
                    .setAppVersionCode(getAppVersionCode(packageManager, appPackageName))
                    .setUiElementPath(AccessibilityNodePathBuilder.createNodePath(nodeInfo))
                    .setActivityName(getActivityName(packageManager, accessibilityEvent))
                    .setWindowTitle(getWindowTitle(nodeInfo))
                    .setSourceComponentName(a11yServiceComponentName.flattenToString())
                    .setSourceVersionCode(
                            getAppVersionCode(packageManager,
                                    a11yServiceComponentName.getPackageName()));
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(LOG_TAG, "Unknown package name", e);
            return Set.of();
        }

        return checkResults.stream()
                .filter(checkResult -> checkResult.getType()
                        == AccessibilityCheckResult.AccessibilityCheckResultType.ERROR
                        || checkResult.getType()
                        == AccessibilityCheckResult.AccessibilityCheckResultType.WARNING)
                .map(checkResult -> builder.setResultCheckClass(
                        getCheckClass(checkResult)).setResultType(
                        getCheckResultType(checkResult)).setResultId(
                        checkResult.getResultId()).build())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static long getAppVersionCode(PackageManager packageManager, String packageName) throws
            PackageManager.NameNotFoundException {
        PackageInfo packageInfo = packageManager.getPackageInfo(packageName, 0);
        return packageInfo.getLongVersionCode();
    }

    /**
     * Returns the simple class name of the Activity providing the cache update, if available,
     * or an empty String if not.
     */
    @VisibleForTesting
    static String getActivityName(
            PackageManager packageManager, @Nullable AccessibilityEvent accessibilityEvent) {
        if (accessibilityEvent == null) {
            return "";
        }
        CharSequence activityName = accessibilityEvent.getClassName();
        if (accessibilityEvent.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && accessibilityEvent.getPackageName() != null
                && activityName != null) {
            try {
                // Check class is for a valid Activity.
                packageManager
                        .getActivityInfo(
                                new ComponentName(accessibilityEvent.getPackageName().toString(),
                                        activityName.toString()), 0);
                int qualifierEnd = activityName.toString().lastIndexOf('.');
                return activityName.toString().substring(qualifierEnd + 1);
            } catch (PackageManager.NameNotFoundException e) {
                // No need to spam the logs. This is very frequent when the class doesn't match
                // an activity.
            }
        }
        return "";
    }

    /**
     * Returns the title of the window containing the a11y node.
     */
    private static String getWindowTitle(AccessibilityNodeInfo nodeInfo) {
        if (nodeInfo.getWindow() == null) {
            return "";
        }
        CharSequence windowTitle = nodeInfo.getWindow().getTitle();
        return windowTitle == null ? "" : windowTitle.toString();
    }

    /**
     * Maps the {@link AccessibilityHierarchyCheck} class that produced the given result, with the
     * corresponding {@link AccessibilityCheckClass} enum. This enumeration is to avoid relying on
     * String class names in the logging, which can be proguarded. It also reduces the logging size.
     */
    private static AccessibilityCheckClass getCheckClass(
            AccessibilityHierarchyCheckResult checkResult) {
        if (CHECK_CLASS_TO_ENUM_MAP.containsKey(checkResult.getSourceCheckClass())) {
            return CHECK_CLASS_TO_ENUM_MAP.get(checkResult.getSourceCheckClass());
        }
        return AccessibilityCheckClass.UNKNOWN_CHECK;
    }

    private static AccessibilityCheckResultType getCheckResultType(
            AccessibilityHierarchyCheckResult checkResult) {
        return switch (checkResult.getType()) {
            case ERROR -> AccessibilityCheckResultType.ERROR;
            case WARNING -> AccessibilityCheckResultType.WARNING;
            default -> AccessibilityCheckResultType.UNKNOWN_RESULT_TYPE;
        };
    }

    private static Map.Entry<Class<? extends AccessibilityHierarchyCheck>,
            AccessibilityCheckClass> classMapEntry(
            Class<? extends AccessibilityHierarchyCheck> checkClass,
            AccessibilityCheckClass checkClassEnum) {
        return new AbstractMap.SimpleImmutableEntry<>(checkClass, checkClassEnum);
    }
}
