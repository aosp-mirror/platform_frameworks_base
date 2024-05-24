/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.text;

import android.annotation.NonNull;
import android.app.AppGlobals;

import com.android.text.flags.Flags;

/**
 * Flags in the "text" namespace.
 *
 * @hide
 */
public final class TextFlags {

    /**
     * The name space of the "text" feature.
     *
     * This needs to move to DeviceConfig constant.
     */
    public static final String NAMESPACE = "text";

    /**
     * Whether we use the new design of context menu.
     */
    public static final String ENABLE_NEW_CONTEXT_MENU =
            "TextEditing__enable_new_context_menu";

    /**
     * The key name used in app core settings for {@link #ENABLE_NEW_CONTEXT_MENU}.
     */
    public static final String KEY_ENABLE_NEW_CONTEXT_MENU = "text__enable_new_context_menu";

    /**
     * Default value for the flag {@link #ENABLE_NEW_CONTEXT_MENU}.
     */
    public static final boolean ENABLE_NEW_CONTEXT_MENU_DEFAULT = true;

    /**
     * List of text flags to be transferred to the application process.
     */
    public static final String[] TEXT_ACONFIGS_FLAGS = {
            Flags.FLAG_NO_BREAK_NO_HYPHENATION_SPAN,
            Flags.FLAG_PHRASE_STRICT_FALLBACK,
            Flags.FLAG_USE_BOUNDS_FOR_WIDTH,
            Flags.FLAG_FIX_LINE_HEIGHT_FOR_LOCALE,
            Flags.FLAG_ICU_BIDI_MIGRATION,
    };

    /**
     * List of the default values of the text flags.
     *
     * The order must be the same to the TEXT_ACONFIG_FLAGS.
     */
    public static final boolean[] TEXT_ACONFIG_DEFAULT_VALUE = {
            Flags.noBreakNoHyphenationSpan(),
            Flags.phraseStrictFallback(),
            Flags.useBoundsForWidth(),
            Flags.fixLineHeightForLocale(),
            Flags.icuBidiMigration(),
    };

    /**
     * Get a key for the feature flag.
     */
    public static String getKeyForFlag(@NonNull String flag) {
        return "text__" + flag;
    }

    /**
     * Return true if the feature flag is enabled.
     */
    public static boolean isFeatureEnabled(@NonNull String flag) {
        return AppGlobals.getIntCoreSetting(
                getKeyForFlag(flag), 0 /* aconfig is false by default */) != 0;
    }
}
