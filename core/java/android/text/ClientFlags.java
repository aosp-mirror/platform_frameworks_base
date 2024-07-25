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

import com.android.text.flags.Flags;

/**
 * An aconfig feature flags that can be accessible from application process without
 * ContentProvider IPCs.
 *
 * When you add new flags, you have to add flag string to {@link TextFlags#TEXT_ACONFIGS_FLAGS}.
 *
 * @hide
 */
public class ClientFlags {
    /**
     * @see Flags#noBreakNoHyphenationSpan()
     */
    public static boolean noBreakNoHyphenationSpan() {
        return TextFlags.isFeatureEnabled(Flags.FLAG_NO_BREAK_NO_HYPHENATION_SPAN);
    }

    /**
     * @see Flags#phraseStrictFallback()
     */
    public static boolean phraseStrictFallback() {
        return TextFlags.isFeatureEnabled(Flags.FLAG_PHRASE_STRICT_FALLBACK);
    }

    /**
     * @see Flags#useBoundsForWidth()
     */
    public static boolean useBoundsForWidth() {
        return TextFlags.isFeatureEnabled(Flags.FLAG_USE_BOUNDS_FOR_WIDTH);
    }

    /**
     * @see Flags#fixLineHeightForLocale()
     */
    public static boolean fixLineHeightForLocale() {
        return TextFlags.isFeatureEnabled(Flags.FLAG_FIX_LINE_HEIGHT_FOR_LOCALE);
    }

    /**
     * @see Flags#icuBidiMigration()
     */
    public static boolean icuBidiMigration() {
        return TextFlags.isFeatureEnabled(Flags.FLAG_ICU_BIDI_MIGRATION);
    }

    /**
     * @see Flags#fixMisalignedContextMenu()
     */
    public static boolean fixMisalignedContextMenu() {
        return TextFlags.isFeatureEnabled(Flags.FLAG_FIX_MISALIGNED_CONTEXT_MENU);
    }
}
