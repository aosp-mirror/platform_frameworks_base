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
import android.util.KeyValueListParser;
import android.util.Slog;

/**
 * TextClassifier specific settings.
 * This is encoded as a key=value list, separated by commas. Ex:
 *
 * <pre>
 * smart_selection_dark_launch              (boolean)
 * smart_selection_enabled_for_edit_text    (boolean)
 * </pre>
 *
 * <p>
 * Type: string
 * see also android.provider.Settings.Global.TEXT_CLASSIFIER_CONSTANTS
 *
 * Example of setting the values for testing.
 * adb shell settings put global text_classifier_constants smart_selection_dark_launch=true,smart_selection_enabled_for_edit_text=true
 * @hide
 */
public final class TextClassifierConstants {

    private static final String LOG_TAG = "TextClassifierConstants";

    private static final String SMART_SELECTION_DARK_LAUNCH =
            "smart_selection_dark_launch";
    private static final String SMART_SELECTION_ENABLED_FOR_EDIT_TEXT =
            "smart_selection_enabled_for_edit_text";

    private static final boolean SMART_SELECTION_DARK_LAUNCH_DEFAULT = false;
    private static final boolean SMART_SELECTION_ENABLED_FOR_EDIT_TEXT_DEFAULT = true;

    /** Default settings. */
    static final TextClassifierConstants DEFAULT = new TextClassifierConstants();

    private final boolean mDarkLaunch;
    private final boolean mSuggestSelectionEnabledForEditableText;

    private TextClassifierConstants() {
        mDarkLaunch = SMART_SELECTION_DARK_LAUNCH_DEFAULT;
        mSuggestSelectionEnabledForEditableText = SMART_SELECTION_ENABLED_FOR_EDIT_TEXT_DEFAULT;
    }

    private TextClassifierConstants(@Nullable String settings) {
        final KeyValueListParser parser = new KeyValueListParser(',');
        try {
            parser.setString(settings);
        } catch (IllegalArgumentException e) {
            // Failed to parse the settings string, log this and move on with defaults.
            Slog.e(LOG_TAG, "Bad TextClassifier settings: " + settings);
        }
        mDarkLaunch = parser.getBoolean(
                SMART_SELECTION_DARK_LAUNCH,
                SMART_SELECTION_DARK_LAUNCH_DEFAULT);
        mSuggestSelectionEnabledForEditableText = parser.getBoolean(
                SMART_SELECTION_ENABLED_FOR_EDIT_TEXT,
                SMART_SELECTION_ENABLED_FOR_EDIT_TEXT_DEFAULT);
    }

    static TextClassifierConstants loadFromString(String settings) {
        return new TextClassifierConstants(settings);
    }

    public boolean isDarkLaunch() {
        return mDarkLaunch;
    }

    public boolean isSuggestSelectionEnabledForEditableText() {
        return mSuggestSelectionEnabledForEditableText;
    }
}
