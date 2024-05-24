/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.view.textclassifier;

import android.annotation.Nullable;
import android.provider.DeviceConfig;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;

import java.util.Optional;

/**
 * TextClassifier specific settings.
 *
 * <p>Currently, this class does not guarantee co-diverted flags are updated atomically.
 *
 * <pre>
 * adb shell cmd device_config put textclassifier system_textclassifier_enabled true
 * </pre>
 *
 * @see android.provider.DeviceConfig#NAMESPACE_TEXTCLASSIFIER
 * @hide
 */
// TODO: Rename to TextClassifierSettings.
public final class TextClassificationConstants {
    /** Whether the smart linkify feature is enabled. */
    private static final String SMART_LINKIFY_ENABLED = "smart_linkify_enabled";

    /** Whether SystemTextClassifier is enabled. */
    static final String SYSTEM_TEXT_CLASSIFIER_ENABLED = "system_textclassifier_enabled";

    /** Whether TextClassifierImpl is enabled. */
    @VisibleForTesting
    static final String LOCAL_TEXT_CLASSIFIER_ENABLED = "local_textclassifier_enabled";

    /** Enable smart selection without a visible UI changes. */
    private static final String MODEL_DARK_LAUNCH_ENABLED = "model_dark_launch_enabled";

    /** Whether the smart selection feature is enabled. */
    private static final String SMART_SELECTION_ENABLED = "smart_selection_enabled";

    /** Whether the smart text share feature is enabled. */
    private static final String SMART_TEXT_SHARE_ENABLED = "smart_text_share_enabled";

    /** Whether animation for smart selection is enabled. */
    private static final String SMART_SELECT_ANIMATION_ENABLED = "smart_select_animation_enabled";

    /** Max length of text that generateLinks can accept. */
    @VisibleForTesting
    static final String GENERATE_LINKS_MAX_TEXT_LENGTH = "generate_links_max_text_length";

    /**
     * The TextClassifierService which would like to use. Example of setting the package:
     *
     * <pre>
     * adb shell cmd device_config put textclassifier textclassifier_service_package_override \
     *      com.android.textclassifier
     * </pre>
     */
    @VisibleForTesting
    static final String TEXT_CLASSIFIER_SERVICE_PACKAGE_OVERRIDE =
            "textclassifier_service_package_override";

    /**
     * The timeout value in seconds used by {@link SystemTextClassifier} for each TextClassifier API
     * calls.
     */
    @VisibleForTesting
    static final String SYSTEM_TEXT_CLASSIFIER_API_TIMEOUT_IN_SECOND =
            "system_textclassifier_api_timeout_in_second";

    /**
     * The maximum amount of characters before and after the selected text that is passed to the
     * TextClassifier for the smart selection. e.g. If this value is 100, then 100 characters before
     * the selection and 100 characters after the selection will be passed to the TextClassifier.
     */
    private static final String SMART_SELECTION_TRIM_DELTA = "smart_selection_trim_delta";

    private static final String DEFAULT_TEXT_CLASSIFIER_SERVICE_PACKAGE_OVERRIDE = null;
    private static final boolean LOCAL_TEXT_CLASSIFIER_ENABLED_DEFAULT = true;
    private static final boolean SYSTEM_TEXT_CLASSIFIER_ENABLED_DEFAULT = true;
    private static final boolean MODEL_DARK_LAUNCH_ENABLED_DEFAULT = false;
    private static final boolean SMART_SELECTION_ENABLED_DEFAULT = true;
    private static final boolean SMART_TEXT_SHARE_ENABLED_DEFAULT = true;
    private static final boolean SMART_LINKIFY_ENABLED_DEFAULT = true;
    private static final boolean SMART_SELECT_ANIMATION_ENABLED_DEFAULT = true;
    private static final int GENERATE_LINKS_MAX_TEXT_LENGTH_DEFAULT = 100 * 1000;
    private static final long SYSTEM_TEXT_CLASSIFIER_API_TIMEOUT_IN_SECOND_DEFAULT = 60;
    private static final int SMART_SELECTION_TRIM_DELTA_DEFAULT = 120;

    private static final Object sLock = new Object();
    private static volatile boolean sMemoizedValuesInitialized;
    private static boolean sLocalTextClassifierEnabled;
    private static boolean sSystemTextClassifierEnabled;
    private static boolean sModelDarkLaunchEnabled;
    private static boolean sSmartSelectionEnabled;
    private static boolean sSmartTextShareEnabled;
    private static boolean sSmartLinkifyEnabled;
    private static boolean sSmartSelectAnimationEnabled;
    private static int sGenerateLinksMaxTextLength;
    private static long sSystemTextClassifierApiTimeoutInSecond;
    private static int sSmartSelectionTrimDelta;

    /**
     * For DeviceConfig values where we don't care if they change at runtime, fetch them once and
     * memoize their values.
     */
    private static void ensureMemoizedValues() {
        if (sMemoizedValuesInitialized) {
            return;
        }
        synchronized (sLock) {
            if (sMemoizedValuesInitialized) {
                return;
            }

            // Read all namespace properties so we get a single snapshot (values
            // fetched aren't updated in the interim).
            DeviceConfig.Properties properties =
                    DeviceConfig.getProperties(DeviceConfig.NAMESPACE_TEXTCLASSIFIER);
            sLocalTextClassifierEnabled =
                    properties.getBoolean(
                            LOCAL_TEXT_CLASSIFIER_ENABLED,
                            LOCAL_TEXT_CLASSIFIER_ENABLED_DEFAULT);
            sModelDarkLaunchEnabled =
                    properties.getBoolean(
                            MODEL_DARK_LAUNCH_ENABLED,
                            MODEL_DARK_LAUNCH_ENABLED_DEFAULT);
            sSmartSelectionEnabled =
                    properties.getBoolean(
                            SMART_SELECTION_ENABLED,
                            SMART_SELECTION_ENABLED_DEFAULT);
            sSmartTextShareEnabled =
                    properties.getBoolean(
                            SMART_TEXT_SHARE_ENABLED,
                            SMART_TEXT_SHARE_ENABLED_DEFAULT);
            sSmartLinkifyEnabled =
                    properties.getBoolean(
                            SMART_LINKIFY_ENABLED,
                            SMART_LINKIFY_ENABLED_DEFAULT);
            sSmartSelectAnimationEnabled =
                    properties.getBoolean(
                            SMART_SELECT_ANIMATION_ENABLED,
                            SMART_SELECT_ANIMATION_ENABLED_DEFAULT);
            sGenerateLinksMaxTextLength =
                    properties.getInt(
                            GENERATE_LINKS_MAX_TEXT_LENGTH,
                            GENERATE_LINKS_MAX_TEXT_LENGTH_DEFAULT);
            sSystemTextClassifierApiTimeoutInSecond =
                    properties.getLong(
                            SYSTEM_TEXT_CLASSIFIER_API_TIMEOUT_IN_SECOND,
                            SYSTEM_TEXT_CLASSIFIER_API_TIMEOUT_IN_SECOND_DEFAULT);
            sSmartSelectionTrimDelta =
                    properties.getInt(
                            SMART_SELECTION_TRIM_DELTA,
                            SMART_SELECTION_TRIM_DELTA_DEFAULT);

            sMemoizedValuesInitialized = true;
        }
    }

    @VisibleForTesting
    public static void resetMemoizedValues() {
      sMemoizedValuesInitialized = false;
    }

    @Nullable
    public String getTextClassifierServicePackageOverride() {
        // Don't memoize this value because we want to be able to receive config
        // updates at runtime.
        return DeviceConfig.getString(
                DeviceConfig.NAMESPACE_TEXTCLASSIFIER,
                TEXT_CLASSIFIER_SERVICE_PACKAGE_OVERRIDE,
                DEFAULT_TEXT_CLASSIFIER_SERVICE_PACKAGE_OVERRIDE);
    }

    public boolean isLocalTextClassifierEnabled() {
        ensureMemoizedValues();
        return sLocalTextClassifierEnabled;
    }

    public boolean isSystemTextClassifierEnabled() {
        // Don't memoize this value because we want to be able to receive config
        // updates at runtime.
        return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_TEXTCLASSIFIER,
                SYSTEM_TEXT_CLASSIFIER_ENABLED,
                SYSTEM_TEXT_CLASSIFIER_ENABLED_DEFAULT);
    }

    public boolean isModelDarkLaunchEnabled() {
        ensureMemoizedValues();
        return sModelDarkLaunchEnabled;
    }

    public boolean isSmartSelectionEnabled() {
        ensureMemoizedValues();
        return sSmartSelectionEnabled;
    }

    public boolean isSmartTextShareEnabled() {
        ensureMemoizedValues();
        return sSmartTextShareEnabled;
    }

    public boolean isSmartLinkifyEnabled() {
        ensureMemoizedValues();
        return sSmartLinkifyEnabled;
    }

    public boolean isSmartSelectionAnimationEnabled() {
        ensureMemoizedValues();
        return sSmartSelectAnimationEnabled;
    }

    public int getGenerateLinksMaxTextLength() {
        ensureMemoizedValues();
        return sGenerateLinksMaxTextLength;
    }

    public long getSystemTextClassifierApiTimeoutInSecond() {
        ensureMemoizedValues();
        return sSystemTextClassifierApiTimeoutInSecond;
    }

    public int getSmartSelectionTrimDelta() {
        ensureMemoizedValues();
        return sSmartSelectionTrimDelta;
    }

    void dump(IndentingPrintWriter pw) {
        pw.println("TextClassificationConstants:");
        pw.increaseIndent();
        pw.print(GENERATE_LINKS_MAX_TEXT_LENGTH, getGenerateLinksMaxTextLength()).println();
        pw.print(LOCAL_TEXT_CLASSIFIER_ENABLED, isLocalTextClassifierEnabled()).println();
        pw.print(MODEL_DARK_LAUNCH_ENABLED, isModelDarkLaunchEnabled()).println();
        pw.print(SMART_LINKIFY_ENABLED, isSmartLinkifyEnabled()).println();
        pw.print(SMART_SELECT_ANIMATION_ENABLED, isSmartSelectionAnimationEnabled()).println();
        pw.print(SMART_SELECTION_ENABLED, isSmartSelectionEnabled()).println();
        pw.print(SMART_TEXT_SHARE_ENABLED, isSmartTextShareEnabled()).println();
        pw.print(SYSTEM_TEXT_CLASSIFIER_ENABLED, isSystemTextClassifierEnabled()).println();
        pw.print(
                        TEXT_CLASSIFIER_SERVICE_PACKAGE_OVERRIDE,
                        getTextClassifierServicePackageOverride())
                .println();
        pw.print(
                        SYSTEM_TEXT_CLASSIFIER_API_TIMEOUT_IN_SECOND,
                        getSystemTextClassifierApiTimeoutInSecond())
                .println();
        pw.print(SMART_SELECTION_TRIM_DELTA, getSmartSelectionTrimDelta()).println();
        pw.decreaseIndent();
    }
}
