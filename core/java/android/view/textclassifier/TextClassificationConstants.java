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
    /**
     * Whether the smart linkify feature is enabled.
     */
    private static final String SMART_LINKIFY_ENABLED = "smart_linkify_enabled";
    /**
     * Whether SystemTextClassifier is enabled.
     */
    static final String SYSTEM_TEXT_CLASSIFIER_ENABLED = "system_textclassifier_enabled";
    /**
     * Whether TextClassifierImpl is enabled.
     */
    @VisibleForTesting
    static final String LOCAL_TEXT_CLASSIFIER_ENABLED = "local_textclassifier_enabled";
    /**
     * Enable smart selection without a visible UI changes.
     */
    private static final String MODEL_DARK_LAUNCH_ENABLED = "model_dark_launch_enabled";
    /**
     * Whether the smart selection feature is enabled.
     */
    private static final String SMART_SELECTION_ENABLED = "smart_selection_enabled";
    /**
     * Whether the smart text share feature is enabled.
     */
    private static final String SMART_TEXT_SHARE_ENABLED = "smart_text_share_enabled";
    /**
     * Whether animation for smart selection is enabled.
     */
    private static final String SMART_SELECT_ANIMATION_ENABLED =
            "smart_select_animation_enabled";
    /**
     * Max length of text that generateLinks can accept.
     */
    @VisibleForTesting
    static final String GENERATE_LINKS_MAX_TEXT_LENGTH = "generate_links_max_text_length";
    /**
     * The TextClassifierService which would like to use. Example of setting the package:
     * <pre>
     * adb shell cmd device_config put textclassifier textclassifier_service_package_override \
     *      com.android.textclassifier
     * </pre>
     */
    @VisibleForTesting
    static final String TEXT_CLASSIFIER_SERVICE_PACKAGE_OVERRIDE =
            "textclassifier_service_package_override";

    private static final String DEFAULT_TEXT_CLASSIFIER_SERVICE_PACKAGE_OVERRIDE = null;
    private static final boolean LOCAL_TEXT_CLASSIFIER_ENABLED_DEFAULT = true;
    private static final boolean SYSTEM_TEXT_CLASSIFIER_ENABLED_DEFAULT = true;
    private static final boolean MODEL_DARK_LAUNCH_ENABLED_DEFAULT = false;
    private static final boolean SMART_SELECTION_ENABLED_DEFAULT = true;
    private static final boolean SMART_TEXT_SHARE_ENABLED_DEFAULT = true;
    private static final boolean SMART_LINKIFY_ENABLED_DEFAULT = true;
    private static final boolean SMART_SELECT_ANIMATION_ENABLED_DEFAULT = true;
    private static final int GENERATE_LINKS_MAX_TEXT_LENGTH_DEFAULT = 100 * 1000;

    @Nullable
    public String getTextClassifierServicePackageOverride() {
        return DeviceConfig.getString(DeviceConfig.NAMESPACE_TEXTCLASSIFIER,
                TEXT_CLASSIFIER_SERVICE_PACKAGE_OVERRIDE,
                DEFAULT_TEXT_CLASSIFIER_SERVICE_PACKAGE_OVERRIDE);
    }

    public boolean isLocalTextClassifierEnabled() {
        return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_TEXTCLASSIFIER,
                LOCAL_TEXT_CLASSIFIER_ENABLED, LOCAL_TEXT_CLASSIFIER_ENABLED_DEFAULT);
    }

    public boolean isSystemTextClassifierEnabled() {
        return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_TEXTCLASSIFIER,
                SYSTEM_TEXT_CLASSIFIER_ENABLED,
                SYSTEM_TEXT_CLASSIFIER_ENABLED_DEFAULT);
    }

    public boolean isModelDarkLaunchEnabled() {
        return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_TEXTCLASSIFIER,
                MODEL_DARK_LAUNCH_ENABLED, MODEL_DARK_LAUNCH_ENABLED_DEFAULT);
    }

    public boolean isSmartSelectionEnabled() {
        return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_TEXTCLASSIFIER,
                SMART_SELECTION_ENABLED, SMART_SELECTION_ENABLED_DEFAULT);
    }

    public boolean isSmartTextShareEnabled() {
        return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_TEXTCLASSIFIER,
                SMART_TEXT_SHARE_ENABLED, SMART_TEXT_SHARE_ENABLED_DEFAULT);
    }

    public boolean isSmartLinkifyEnabled() {
        return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_TEXTCLASSIFIER, SMART_LINKIFY_ENABLED,
                SMART_LINKIFY_ENABLED_DEFAULT);
    }

    public boolean isSmartSelectionAnimationEnabled() {
        return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_TEXTCLASSIFIER,
                SMART_SELECT_ANIMATION_ENABLED, SMART_SELECT_ANIMATION_ENABLED_DEFAULT);
    }

    public int getGenerateLinksMaxTextLength() {
        return DeviceConfig.getInt(DeviceConfig.NAMESPACE_TEXTCLASSIFIER,
                GENERATE_LINKS_MAX_TEXT_LENGTH, GENERATE_LINKS_MAX_TEXT_LENGTH_DEFAULT);
    }

    void dump(IndentingPrintWriter pw) {
        pw.println("TextClassificationConstants:");
        pw.increaseIndent();
        pw.printPair("generate_links_max_text_length", getGenerateLinksMaxTextLength())
                .println();
        pw.printPair("local_textclassifier_enabled", isLocalTextClassifierEnabled())
                .println();
        pw.printPair("model_dark_launch_enabled", isModelDarkLaunchEnabled())
                .println();
        pw.printPair("smart_linkify_enabled", isSmartLinkifyEnabled())
                .println();
        pw.printPair("smart_select_animation_enabled", isSmartSelectionAnimationEnabled())
                .println();
        pw.printPair("smart_selection_enabled", isSmartSelectionEnabled())
                .println();
        pw.printPair("smart_text_share_enabled", isSmartTextShareEnabled())
                .println();
        pw.printPair("system_textclassifier_enabled", isSystemTextClassifierEnabled())
                .println();
        pw.printPair("textclassifier_service_package_override",
                getTextClassifierServicePackageOverride()).println();
        pw.decreaseIndent();
    }
}