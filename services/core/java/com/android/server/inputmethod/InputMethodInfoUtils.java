/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.inputmethod;

import static com.android.server.inputmethod.SubtypeUtils.SUBTYPE_MODE_ANY;
import static com.android.server.inputmethod.SubtypeUtils.SUBTYPE_MODE_KEYBOARD;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.Parcel;
import android.text.TextUtils;
import android.util.Slog;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * This class provides utility methods to generate or filter {@link InputMethodInfo} for
 * {@link InputMethodManagerService}.
 *
 * <p>This class is intentionally package-private.  Utility methods here are tightly coupled with
 * implementation details in {@link InputMethodManagerService}.  Hence this class is not suitable
 * for other components to directly use.</p>
 */
final class InputMethodInfoUtils {
    private static final String TAG = "InputMethodInfoUtils";

    /**
     * Used in {@link #getFallbackLocaleForDefaultIme(List, Context)} to find the fallback IMEs
     * that are mainly used until the system becomes ready. Note that {@link Locale} in this array
     * is checked with {@link Locale#equals(Object)}, which means that {@code Locale.ENGLISH}
     * doesn't automatically match {@code Locale("en", "IN")}.
     */
    private static final Locale[] SEARCH_ORDER_OF_FALLBACK_LOCALES = {
            Locale.ENGLISH, // "en"
            Locale.US, // "en_US"
            Locale.UK, // "en_GB"
    };
    private static final Locale ENGLISH_LOCALE = new Locale("en");

    private static final class InputMethodListBuilder {
        // Note: We use LinkedHashSet instead of android.util.ArraySet because the enumeration
        // order can have non-trivial effect in the call sites.
        @NonNull
        private final LinkedHashSet<InputMethodInfo> mInputMethodSet = new LinkedHashSet<>();

        InputMethodListBuilder fillImes(List<InputMethodInfo> imis, Context context,
                boolean checkDefaultAttribute, @Nullable Locale locale, boolean checkCountry,
                String requiredSubtypeMode) {
            for (int i = 0; i < imis.size(); ++i) {
                final InputMethodInfo imi = imis.get(i);
                if (isSystemImeThatHasSubtypeOf(imi, context,
                        checkDefaultAttribute, locale, checkCountry, requiredSubtypeMode)) {
                    mInputMethodSet.add(imi);
                }
            }
            return this;
        }

        InputMethodListBuilder fillAuxiliaryImes(List<InputMethodInfo> imis, Context context) {
            // If one or more auxiliary input methods are available, OK to stop populating the list.
            for (final InputMethodInfo imi : mInputMethodSet) {
                if (imi.isAuxiliaryIme()) {
                    return this;
                }
            }
            boolean added = false;
            for (int i = 0; i < imis.size(); ++i) {
                final InputMethodInfo imi = imis.get(i);
                if (isSystemAuxilialyImeThatHasAutomaticSubtype(imi, context,
                        true /* checkDefaultAttribute */)) {
                    mInputMethodSet.add(imi);
                    added = true;
                }
            }
            if (added) {
                return this;
            }
            for (int i = 0; i < imis.size(); ++i) {
                final InputMethodInfo imi = imis.get(i);
                if (isSystemAuxilialyImeThatHasAutomaticSubtype(imi, context,
                        false /* checkDefaultAttribute */)) {
                    mInputMethodSet.add(imi);
                }
            }
            return this;

        }

        public boolean isEmpty() {
            return mInputMethodSet.isEmpty();
        }

        @NonNull
        public ArrayList<InputMethodInfo> build() {
            return new ArrayList<>(mInputMethodSet);
        }
    }

    private static InputMethodListBuilder getMinimumKeyboardSetWithSystemLocale(
            List<InputMethodInfo> imis, Context context, @Nullable Locale systemLocale,
            @Nullable Locale fallbackLocale) {
        // Once the system becomes ready, we pick up at least one keyboard in the following order.
        // Secondary users fall into this category in general.
        // 1. checkDefaultAttribute: true, locale: systemLocale, checkCountry: true
        // 2. checkDefaultAttribute: true, locale: systemLocale, checkCountry: false
        // 3. checkDefaultAttribute: true, locale: fallbackLocale, checkCountry: true
        // 4. checkDefaultAttribute: true, locale: fallbackLocale, checkCountry: false
        // 5. checkDefaultAttribute: false, locale: fallbackLocale, checkCountry: true
        // 6. checkDefaultAttribute: false, locale: fallbackLocale, checkCountry: false
        // TODO: We should check isAsciiCapable instead of relying on fallbackLocale.

        final InputMethodListBuilder builder = new InputMethodListBuilder();
        builder.fillImes(imis, context, true /* checkDefaultAttribute */, systemLocale,
                true /* checkCountry */, SUBTYPE_MODE_KEYBOARD);
        if (!builder.isEmpty()) {
            return builder;
        }
        builder.fillImes(imis, context, true /* checkDefaultAttribute */, systemLocale,
                false /* checkCountry */, SUBTYPE_MODE_KEYBOARD);
        if (!builder.isEmpty()) {
            return builder;
        }
        builder.fillImes(imis, context, true /* checkDefaultAttribute */, fallbackLocale,
                true /* checkCountry */, SUBTYPE_MODE_KEYBOARD);
        if (!builder.isEmpty()) {
            return builder;
        }
        builder.fillImes(imis, context, true /* checkDefaultAttribute */, fallbackLocale,
                false /* checkCountry */, SUBTYPE_MODE_KEYBOARD);
        if (!builder.isEmpty()) {
            return builder;
        }
        builder.fillImes(imis, context, false /* checkDefaultAttribute */, fallbackLocale,
                true /* checkCountry */, SUBTYPE_MODE_KEYBOARD);
        if (!builder.isEmpty()) {
            return builder;
        }
        builder.fillImes(imis, context, false /* checkDefaultAttribute */, fallbackLocale,
                false /* checkCountry */, SUBTYPE_MODE_KEYBOARD);
        if (!builder.isEmpty()) {
            return builder;
        }
        Slog.w(TAG, "No software keyboard is found. imis=" + Arrays.toString(imis.toArray())
                + " systemLocale=" + systemLocale + " fallbackLocale=" + fallbackLocale);
        return builder;
    }

    static ArrayList<InputMethodInfo> getDefaultEnabledImes(
            Context context, List<InputMethodInfo> imis, boolean onlyMinimum) {
        final Locale fallbackLocale = getFallbackLocaleForDefaultIme(imis, context);
        // We will primarily rely on the system locale, but also keep relying on the fallback locale
        // as a last resort.
        // Also pick up suitable IMEs regardless of the software keyboard support (e.g. Voice IMEs),
        // then pick up suitable auxiliary IMEs when necessary (e.g. Voice IMEs with "automatic"
        // subtype)
        final Locale systemLocale = LocaleUtils.getSystemLocaleFromContext(context);
        final InputMethodListBuilder builder =
                getMinimumKeyboardSetWithSystemLocale(imis, context, systemLocale, fallbackLocale);
        if (!onlyMinimum) {
            builder.fillImes(imis, context, true /* checkDefaultAttribute */, systemLocale,
                            true /* checkCountry */, SUBTYPE_MODE_ANY)
                    .fillAuxiliaryImes(imis, context);
        }
        return builder.build();
    }

    static ArrayList<InputMethodInfo> getDefaultEnabledImes(
            Context context, List<InputMethodInfo> imis) {
        return getDefaultEnabledImes(context, imis, false /* onlyMinimum */);
    }

    /**
     * Chooses an eligible system voice IME from the given IMEs.
     *
     * @param methodMap Map from the IME ID to {@link InputMethodInfo}.
     * @param systemSpeechRecognizerPackageName System speech recognizer configured by the system
     *                                          config.
     * @param currentDefaultVoiceImeId the default voice IME id, which may be {@code null} or
     *                                 the value assigned for
     *                                 {@link Settings.Secure#DEFAULT_VOICE_INPUT_METHOD}
     * @return {@link InputMethodInfo} that is found in {@code methodMap} and most suitable for
     *                                 the system voice IME.
     */
    @Nullable
    static InputMethodInfo chooseSystemVoiceIme(
            @NonNull InputMethodMap methodMap,
            @Nullable String systemSpeechRecognizerPackageName,
            @Nullable String currentDefaultVoiceImeId) {
        if (TextUtils.isEmpty(systemSpeechRecognizerPackageName)) {
            return null;
        }
        final InputMethodInfo defaultVoiceIme = methodMap.get(currentDefaultVoiceImeId);
        // If the config matches the package of the setting, use the current one.
        if (defaultVoiceIme != null && defaultVoiceIme.isSystem()
                && defaultVoiceIme.getPackageName().equals(systemSpeechRecognizerPackageName)) {
            return defaultVoiceIme;
        }
        InputMethodInfo firstMatchingIme = null;
        final int methodCount = methodMap.size();
        for (int i = 0; i < methodCount; ++i) {
            final InputMethodInfo imi = methodMap.valueAt(i);
            if (!imi.isSystem()) {
                continue;
            }
            if (!TextUtils.equals(imi.getPackageName(), systemSpeechRecognizerPackageName)) {
                continue;
            }
            if (firstMatchingIme != null) {
                Slog.e(TAG, "At most one InputMethodService can be published in "
                        + "systemSpeechRecognizer: " + systemSpeechRecognizerPackageName
                        + ". Ignoring all of them.");
                return null;
            }
            firstMatchingIme = imi;
        }
        return firstMatchingIme;
    }

    static InputMethodInfo getMostApplicableDefaultIME(List<InputMethodInfo> enabledImes) {
        if (enabledImes == null || enabledImes.isEmpty()) {
            return null;
        }
        // We'd prefer to fall back on a system IME, since that is safer.
        int i = enabledImes.size();
        int firstFoundSystemIme = -1;
        while (i > 0) {
            i--;
            final InputMethodInfo imi = enabledImes.get(i);
            if (imi.isAuxiliaryIme()) {
                continue;
            }
            if (imi.isSystem() && SubtypeUtils.containsSubtypeOf(imi, ENGLISH_LOCALE,
                    false /* checkCountry */, SUBTYPE_MODE_KEYBOARD)) {
                return imi;
            }
            if (firstFoundSystemIme < 0 && imi.isSystem()) {
                firstFoundSystemIme = i;
            }
        }
        return enabledImes.get(Math.max(firstFoundSystemIme, 0));
    }

    private static boolean isSystemAuxilialyImeThatHasAutomaticSubtype(InputMethodInfo imi,
            Context context, boolean checkDefaultAttribute) {
        if (!imi.isSystem()) {
            return false;
        }
        if (checkDefaultAttribute && !imi.isDefault(context)) {
            return false;
        }
        if (!imi.isAuxiliaryIme()) {
            return false;
        }
        final int subtypeCount = imi.getSubtypeCount();
        for (int i = 0; i < subtypeCount; ++i) {
            final InputMethodSubtype s = imi.getSubtypeAt(i);
            if (s.overridesImplicitlyEnabledSubtype()) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static Locale getFallbackLocaleForDefaultIme(List<InputMethodInfo> imis,
            Context context) {
        // At first, find the fallback locale from the IMEs that are declared as "default" in the
        // current locale.  Note that IME developers can declare an IME as "default" only for
        // some particular locales but "not default" for other locales.
        for (final Locale fallbackLocale : SEARCH_ORDER_OF_FALLBACK_LOCALES) {
            for (int i = 0; i < imis.size(); ++i) {
                if (isSystemImeThatHasSubtypeOf(imis.get(i), context,
                        true /* checkDefaultAttribute */, fallbackLocale,
                        true /* checkCountry */, SUBTYPE_MODE_KEYBOARD)) {
                    return fallbackLocale;
                }
            }
        }
        // If no fallback locale is found in the above condition, find fallback locales regardless
        // of the "default" attribute as a last resort.
        for (final Locale fallbackLocale : SEARCH_ORDER_OF_FALLBACK_LOCALES) {
            for (int i = 0; i < imis.size(); ++i) {
                if (isSystemImeThatHasSubtypeOf(imis.get(i), context,
                        false /* checkDefaultAttribute */, fallbackLocale,
                        true /* checkCountry */, SUBTYPE_MODE_KEYBOARD)) {
                    return fallbackLocale;
                }
            }
        }
        Slog.w(TAG, "Found no fallback locale. imis=" + Arrays.toString(imis.toArray()));
        return null;
    }

    private static boolean isSystemImeThatHasSubtypeOf(InputMethodInfo imi, Context context,
            boolean checkDefaultAttribute, @Nullable Locale requiredLocale, boolean checkCountry,
            String requiredSubtypeMode) {
        if (!imi.isSystem()) {
            return false;
        }
        if (checkDefaultAttribute && !imi.isDefault(context)) {
            return false;
        }
        return SubtypeUtils.containsSubtypeOf(imi, requiredLocale, checkCountry,
                requiredSubtypeMode);
    }

    /**
     * Marshals the given {@link InputMethodInfo} into a byte array.
     *
     * @param imi {@link InputMethodInfo} to be marshalled
     * @return a byte array where the given {@link InputMethodInfo} is marshalled
     */
    @NonNull
    static byte[] marshal(@NonNull InputMethodInfo imi) {
        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            parcel.writeTypedObject(imi, 0);
            return parcel.marshall();
        } finally {
            if (parcel != null) {
                parcel.recycle();
            }
        }
    }
}
