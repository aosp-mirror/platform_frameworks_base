/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.internal.inputmethod;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.text.TextUtils;
import android.util.Pair;
import android.util.Slog;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;
import android.view.textservice.SpellCheckerInfo;
import android.view.textservice.TextServicesManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * InputMethodManagerUtils contains some static methods that provides IME informations.
 * This methods are supposed to be used in both the framework and the Settings application.
 */
public class InputMethodUtils {
    public static final boolean DEBUG = false;
    public static final int NOT_A_SUBTYPE_ID = -1;
    public static final String SUBTYPE_MODE_ANY = null;
    public static final String SUBTYPE_MODE_KEYBOARD = "keyboard";
    public static final String SUBTYPE_MODE_VOICE = "voice";
    private static final String TAG = "InputMethodUtils";
    private static final Locale ENGLISH_LOCALE = new Locale("en");
    private static final String NOT_A_SUBTYPE_ID_STR = String.valueOf(NOT_A_SUBTYPE_ID);
    private static final String TAG_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE =
            "EnabledWhenDefaultIsNotAsciiCapable";
    private static final String TAG_ASCII_CAPABLE = "AsciiCapable";
    /**
     * Used in {@link #getFallbackLocaleForDefaultIme(ArrayList, Context)} to find the fallback IMEs
     * that are mainly used until the system becomes ready. Note that {@link Locale} in this array
     * is checked with {@link Locale#equals(Object)}, which means that {@code Locale.ENGLISH}
     * doesn't automatically match {@code Locale("en", "IN")}.
     */
    private static final Locale[] SEARCH_ORDER_OF_FALLBACK_LOCALES = {
        Locale.ENGLISH, // "en"
        Locale.US, // "en_US"
        Locale.UK, // "en_GB"
    };

    private InputMethodUtils() {
        // This utility class is not publicly instantiable.
    }

    // ----------------------------------------------------------------------
    // Utilities for debug
    public static String getStackTrace() {
        final StringBuilder sb = new StringBuilder();
        try {
            throw new RuntimeException();
        } catch (RuntimeException e) {
            final StackTraceElement[] frames = e.getStackTrace();
            // Start at 1 because the first frame is here and we don't care about it
            for (int j = 1; j < frames.length; ++j) {
                sb.append(frames[j].toString() + "\n");
            }
        }
        return sb.toString();
    }

    public static String getApiCallStack() {
        String apiCallStack = "";
        try {
            throw new RuntimeException();
        } catch (RuntimeException e) {
            final StackTraceElement[] frames = e.getStackTrace();
            for (int j = 1; j < frames.length; ++j) {
                final String tempCallStack = frames[j].toString();
                if (TextUtils.isEmpty(apiCallStack)) {
                    // Overwrite apiCallStack if it's empty
                    apiCallStack = tempCallStack;
                } else if (tempCallStack.indexOf("Transact(") < 0) {
                    // Overwrite apiCallStack if it's not a binder call
                    apiCallStack = tempCallStack;
                } else {
                    break;
                }
            }
        }
        return apiCallStack;
    }
    // ----------------------------------------------------------------------

    public static boolean isSystemIme(InputMethodInfo inputMethod) {
        return (inputMethod.getServiceInfo().applicationInfo.flags
                & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    /**
     * @deprecated Use {@link #isSystemImeThatHasSubtypeOf(InputMethodInfo, Context, boolean,
     * Locale, boolean, String)} instead.
     */
    @Deprecated
    public static boolean isSystemImeThatHasEnglishKeyboardSubtype(InputMethodInfo imi) {
        if (!isSystemIme(imi)) {
            return false;
        }
        return containsSubtypeOf(imi, ENGLISH_LOCALE.getLanguage(), SUBTYPE_MODE_KEYBOARD);
    }

    private static boolean isSystemImeThatHasSubtypeOf(final InputMethodInfo imi,
            final Context context, final boolean checkDefaultAttribute,
            @Nullable final Locale requiredLocale, final boolean checkCountry,
            final String requiredSubtypeMode) {
        if (!isSystemIme(imi)) {
            return false;
        }
        if (checkDefaultAttribute && !imi.isDefault(context)) {
            return false;
        }
        if (!containsSubtypeOf(imi, requiredLocale, checkCountry, requiredSubtypeMode)) {
            return false;
        }
        return true;
    }

    @Nullable
    public static Locale getFallbackLocaleForDefaultIme(final ArrayList<InputMethodInfo> imis,
            final Context context) {
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

    private static boolean isSystemAuxilialyImeThatHasAutomaticSubtype(final InputMethodInfo imi,
            final Context context, final boolean checkDefaultAttribute) {
        if (!isSystemIme(imi)) {
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

    public static Locale getSystemLocaleFromContext(final Context context) {
        try {
            return context.getResources().getConfiguration().locale;
        } catch (Resources.NotFoundException ex) {
            return null;
        }
    }

    private static final class InputMethodListBuilder {
        // Note: We use LinkedHashSet instead of android.util.ArraySet because the enumeration
        // order can have non-trivial effect in the call sites.
        @NonNull
        private final LinkedHashSet<InputMethodInfo> mInputMethodSet = new LinkedHashSet<>();

        public InputMethodListBuilder fillImes(final ArrayList<InputMethodInfo> imis,
                final Context context, final boolean checkDefaultAttribute,
                @Nullable final Locale locale, final boolean checkCountry,
                final String requiredSubtypeMode) {
            for (int i = 0; i < imis.size(); ++i) {
                final InputMethodInfo imi = imis.get(i);
                if (isSystemImeThatHasSubtypeOf(imi, context, checkDefaultAttribute, locale,
                        checkCountry, requiredSubtypeMode)) {
                    mInputMethodSet.add(imi);
                }
            }
            return this;
        }

        // TODO: The behavior of InputMethodSubtype#overridesImplicitlyEnabledSubtype() should be
        // documented more clearly.
        public InputMethodListBuilder fillAuxiliaryImes(final ArrayList<InputMethodInfo> imis,
                final Context context) {
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

    private static InputMethodListBuilder getMinimumKeyboardSetWithoutSystemLocale(
            final ArrayList<InputMethodInfo> imis, final Context context,
            @Nullable final Locale fallbackLocale) {
        // Before the system becomes ready, we pick up at least one keyboard in the following order.
        // The first user (device owner) falls into this category.
        // 1. checkDefaultAttribute: true, locale: fallbackLocale, checkCountry: true
        // 2. checkDefaultAttribute: false, locale: fallbackLocale, checkCountry: true
        // 3. checkDefaultAttribute: true, locale: fallbackLocale, checkCountry: false
        // 4. checkDefaultAttribute: false, locale: fallbackLocale, checkCountry: false
        // TODO: We should check isAsciiCapable instead of relying on fallbackLocale.

        final InputMethodListBuilder builder = new InputMethodListBuilder();
        builder.fillImes(imis, context, true /* checkDefaultAttribute */, fallbackLocale,
                true /* checkCountry */, SUBTYPE_MODE_KEYBOARD);
        if (!builder.isEmpty()) {
            return builder;
        }
        builder.fillImes(imis, context, false /* checkDefaultAttribute */, fallbackLocale,
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
                false /* checkCountry */, SUBTYPE_MODE_KEYBOARD);
        if (!builder.isEmpty()) {
            return builder;
        }
        Slog.w(TAG, "No software keyboard is found. imis=" + Arrays.toString(imis.toArray())
                + " fallbackLocale=" + fallbackLocale);
        return builder;
    }

    private static InputMethodListBuilder getMinimumKeyboardSetWithSystemLocale(
            final ArrayList<InputMethodInfo> imis, final Context context,
            @Nullable final Locale systemLocale, @Nullable final Locale fallbackLocale) {
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

    public static ArrayList<InputMethodInfo> getDefaultEnabledImes(final Context context,
            final boolean isSystemReady, final ArrayList<InputMethodInfo> imis) {
        final Locale fallbackLocale = getFallbackLocaleForDefaultIme(imis, context);
        if (!isSystemReady) {
            // When the system is not ready, the system locale is not stable and reliable. Hence
            // we will pick up IMEs that support software keyboard based on the fallback locale.
            // Also pick up suitable IMEs regardless of the software keyboard support.
            // (e.g. Voice IMEs)
            return getMinimumKeyboardSetWithoutSystemLocale(imis, context, fallbackLocale)
                    .fillImes(imis, context, true /* checkDefaultAttribute */, fallbackLocale,
                            true /* checkCountry */, SUBTYPE_MODE_ANY)
                    .build();
        }

        // When the system is ready, we will primarily rely on the system locale, but also keep
        // relying on the fallback locale as a last resort.
        // Also pick up suitable IMEs regardless of the software keyboard support (e.g. Voice IMEs),
        // then pick up suitable auxiliary IMEs when necessary (e.g. Voice IMEs with "automatic"
        // subtype)
        final Locale systemLocale = getSystemLocaleFromContext(context);
        return getMinimumKeyboardSetWithSystemLocale(imis, context, systemLocale, fallbackLocale)
                .fillImes(imis, context, true /* checkDefaultAttribute */, systemLocale,
                        true /* checkCountry */, SUBTYPE_MODE_ANY)
                .fillAuxiliaryImes(imis, context)
                .build();
    }

    /**
     * @deprecated Use {@link #isSystemImeThatHasSubtypeOf(InputMethodInfo, Context, boolean,
     * Locale, boolean, String)} instead.
     */
    @Deprecated
    public static boolean isValidSystemDefaultIme(
            boolean isSystemReady, InputMethodInfo imi, Context context) {
        if (!isSystemReady) {
            return false;
        }
        if (!isSystemIme(imi)) {
            return false;
        }
        if (imi.getIsDefaultResourceId() != 0) {
            try {
                if (imi.isDefault(context) && containsSubtypeOf(
                        imi, context.getResources().getConfiguration().locale.getLanguage(),
                        SUBTYPE_MODE_ANY)) {
                    return true;
                }
            } catch (Resources.NotFoundException ex) {
            }
        }
        if (imi.getSubtypeCount() == 0) {
            Slog.w(TAG, "Found no subtypes in a system IME: " + imi.getPackageName());
        }
        return false;
    }

    public static boolean containsSubtypeOf(final InputMethodInfo imi,
            @Nullable final Locale locale, final boolean checkCountry, final String mode) {
        if (locale == null) {
            return false;
        }
        final int N = imi.getSubtypeCount();
        for (int i = 0; i < N; ++i) {
            final InputMethodSubtype subtype = imi.getSubtypeAt(i);
            if (checkCountry) {
                // TODO: Use {@link Locale#toLanguageTag()} and
                // {@link Locale#forLanguageTag(languageTag)} instead.
                if (!TextUtils.equals(subtype.getLocale(), locale.toString())) {
                    continue;
                }
            } else {
                final Locale subtypeLocale = new Locale(getLanguageFromLocaleString(
                        subtype.getLocale()));
                if (!subtypeLocale.getLanguage().equals(locale.getLanguage())) {
                    continue;
                }
            }
            if (mode == SUBTYPE_MODE_ANY || TextUtils.isEmpty(mode) ||
                    mode.equalsIgnoreCase(subtype.getMode())) {
                return true;
            }
        }
        return false;
    }

    /**
     * @deprecated Use {@link #containsSubtypeOf(InputMethodInfo, Locale, boolean, String)} instead.
     */
    @Deprecated
    public static boolean containsSubtypeOf(InputMethodInfo imi, String language, String mode) {
        final int N = imi.getSubtypeCount();
        for (int i = 0; i < N; ++i) {
            final InputMethodSubtype subtype = imi.getSubtypeAt(i);
            if (!subtype.getLocale().startsWith(language)) {
                continue;
            }
            if (mode == SUBTYPE_MODE_ANY || TextUtils.isEmpty(mode) ||
                    mode.equalsIgnoreCase(subtype.getMode())) {
                return true;
            }
        }
        return false;
    }

    public static ArrayList<InputMethodSubtype> getSubtypes(InputMethodInfo imi) {
        ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
        final int subtypeCount = imi.getSubtypeCount();
        for (int i = 0; i < subtypeCount; ++i) {
            subtypes.add(imi.getSubtypeAt(i));
        }
        return subtypes;
    }

    public static ArrayList<InputMethodSubtype> getOverridingImplicitlyEnabledSubtypes(
            InputMethodInfo imi, String mode) {
        ArrayList<InputMethodSubtype> subtypes = new ArrayList<InputMethodSubtype>();
        final int subtypeCount = imi.getSubtypeCount();
        for (int i = 0; i < subtypeCount; ++i) {
            final InputMethodSubtype subtype = imi.getSubtypeAt(i);
            if (subtype.overridesImplicitlyEnabledSubtype() && subtype.getMode().equals(mode)) {
                subtypes.add(subtype);
            }
        }
        return subtypes;
    }

    public static InputMethodInfo getMostApplicableDefaultIME(List<InputMethodInfo> enabledImes) {
        if (enabledImes == null || enabledImes.isEmpty()) {
            return null;
        }
        // We'd prefer to fall back on a system IME, since that is safer.
        int i = enabledImes.size();
        int firstFoundSystemIme = -1;
        while (i > 0) {
            i--;
            final InputMethodInfo imi = enabledImes.get(i);
            if (InputMethodUtils.isSystemImeThatHasEnglishKeyboardSubtype(imi)
                    && !imi.isAuxiliaryIme()) {
                return imi;
            }
            if (firstFoundSystemIme < 0 && InputMethodUtils.isSystemIme(imi)
                    && !imi.isAuxiliaryIme()) {
                firstFoundSystemIme = i;
            }
        }
        return enabledImes.get(Math.max(firstFoundSystemIme, 0));
    }

    public static boolean isValidSubtypeId(InputMethodInfo imi, int subtypeHashCode) {
        return getSubtypeIdFromHashCode(imi, subtypeHashCode) != NOT_A_SUBTYPE_ID;
    }

    public static int getSubtypeIdFromHashCode(InputMethodInfo imi, int subtypeHashCode) {
        if (imi != null) {
            final int subtypeCount = imi.getSubtypeCount();
            for (int i = 0; i < subtypeCount; ++i) {
                InputMethodSubtype ims = imi.getSubtypeAt(i);
                if (subtypeHashCode == ims.hashCode()) {
                    return i;
                }
            }
        }
        return NOT_A_SUBTYPE_ID;
    }

    private static ArrayList<InputMethodSubtype> getImplicitlyApplicableSubtypesLocked(
            Resources res, InputMethodInfo imi) {
        final List<InputMethodSubtype> subtypes = InputMethodUtils.getSubtypes(imi);
        final String systemLocale = res.getConfiguration().locale.toString();
        if (TextUtils.isEmpty(systemLocale)) return new ArrayList<InputMethodSubtype>();
        final String systemLanguage = res.getConfiguration().locale.getLanguage();
        final HashMap<String, InputMethodSubtype> applicableModeAndSubtypesMap =
                new HashMap<String, InputMethodSubtype>();
        final int N = subtypes.size();
        for (int i = 0; i < N; ++i) {
            // scan overriding implicitly enabled subtypes.
            InputMethodSubtype subtype = subtypes.get(i);
            if (subtype.overridesImplicitlyEnabledSubtype()) {
                final String mode = subtype.getMode();
                if (!applicableModeAndSubtypesMap.containsKey(mode)) {
                    applicableModeAndSubtypesMap.put(mode, subtype);
                }
            }
        }
        if (applicableModeAndSubtypesMap.size() > 0) {
            return new ArrayList<InputMethodSubtype>(applicableModeAndSubtypesMap.values());
        }
        for (int i = 0; i < N; ++i) {
            final InputMethodSubtype subtype = subtypes.get(i);
            final String locale = subtype.getLocale();
            final String mode = subtype.getMode();
            final String language = getLanguageFromLocaleString(locale);
            // When system locale starts with subtype's locale, that subtype will be applicable
            // for system locale. We need to make sure the languages are the same, to prevent
            // locales like "fil" (Filipino) being matched by "fi" (Finnish).
            //
            // For instance, it's clearly applicable for cases like system locale = en_US and
            // subtype = en, but it is not necessarily considered applicable for cases like system
            // locale = en and subtype = en_US.
            //
            // We just call systemLocale.startsWith(locale) in this function because there is no
            // need to find applicable subtypes aggressively unlike
            // findLastResortApplicableSubtypeLocked.
            //
            // TODO: This check is broken. It won't take scripts into account and doesn't
            // account for the mandatory conversions performed by Locale#toString.
            if (language.equals(systemLanguage) && systemLocale.startsWith(locale)) {
                final InputMethodSubtype applicableSubtype = applicableModeAndSubtypesMap.get(mode);
                // If more applicable subtypes are contained, skip.
                if (applicableSubtype != null) {
                    if (systemLocale.equals(applicableSubtype.getLocale())) continue;
                    if (!systemLocale.equals(locale)) continue;
                }
                applicableModeAndSubtypesMap.put(mode, subtype);
            }
        }
        final InputMethodSubtype keyboardSubtype
                = applicableModeAndSubtypesMap.get(SUBTYPE_MODE_KEYBOARD);
        final ArrayList<InputMethodSubtype> applicableSubtypes = new ArrayList<InputMethodSubtype>(
                applicableModeAndSubtypesMap.values());
        if (keyboardSubtype != null && !keyboardSubtype.containsExtraValueKey(TAG_ASCII_CAPABLE)) {
            for (int i = 0; i < N; ++i) {
                final InputMethodSubtype subtype = subtypes.get(i);
                final String mode = subtype.getMode();
                if (SUBTYPE_MODE_KEYBOARD.equals(mode) && subtype.containsExtraValueKey(
                        TAG_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE)) {
                    applicableSubtypes.add(subtype);
                }
            }
        }
        if (keyboardSubtype == null) {
            InputMethodSubtype lastResortKeyboardSubtype = findLastResortApplicableSubtypeLocked(
                    res, subtypes, SUBTYPE_MODE_KEYBOARD, systemLocale, true);
            if (lastResortKeyboardSubtype != null) {
                applicableSubtypes.add(lastResortKeyboardSubtype);
            }
        }
        return applicableSubtypes;
    }

    /**
     * Returns the language component of a given locale string.
     * TODO: Use {@link Locale#toLanguageTag()} and {@link Locale#forLanguageTag(String)}
     */
    public static String getLanguageFromLocaleString(String locale) {
        final int idx = locale.indexOf('_');
        if (idx < 0) {
            return locale;
        } else {
            return locale.substring(0, idx);
        }
    }

    /**
     * If there are no selected subtypes, tries finding the most applicable one according to the
     * given locale.
     * @param subtypes this function will search the most applicable subtype in subtypes
     * @param mode subtypes will be filtered by mode
     * @param locale subtypes will be filtered by locale
     * @param canIgnoreLocaleAsLastResort if this function can't find the most applicable subtype,
     * it will return the first subtype matched with mode
     * @return the most applicable subtypeId
     */
    public static InputMethodSubtype findLastResortApplicableSubtypeLocked(
            Resources res, List<InputMethodSubtype> subtypes, String mode, String locale,
            boolean canIgnoreLocaleAsLastResort) {
        if (subtypes == null || subtypes.size() == 0) {
            return null;
        }
        if (TextUtils.isEmpty(locale)) {
            locale = res.getConfiguration().locale.toString();
        }
        final String language = getLanguageFromLocaleString(locale);
        boolean partialMatchFound = false;
        InputMethodSubtype applicableSubtype = null;
        InputMethodSubtype firstMatchedModeSubtype = null;
        final int N = subtypes.size();
        for (int i = 0; i < N; ++i) {
            InputMethodSubtype subtype = subtypes.get(i);
            final String subtypeLocale = subtype.getLocale();
            final String subtypeLanguage = getLanguageFromLocaleString(subtypeLocale);
            // An applicable subtype should match "mode". If mode is null, mode will be ignored,
            // and all subtypes with all modes can be candidates.
            if (mode == null || subtypes.get(i).getMode().equalsIgnoreCase(mode)) {
                if (firstMatchedModeSubtype == null) {
                    firstMatchedModeSubtype = subtype;
                }
                if (locale.equals(subtypeLocale)) {
                    // Exact match (e.g. system locale is "en_US" and subtype locale is "en_US")
                    applicableSubtype = subtype;
                    break;
                } else if (!partialMatchFound && language.equals(subtypeLanguage)) {
                    // Partial match (e.g. system locale is "en_US" and subtype locale is "en")
                    applicableSubtype = subtype;
                    partialMatchFound = true;
                }
            }
        }

        if (applicableSubtype == null && canIgnoreLocaleAsLastResort) {
            return firstMatchedModeSubtype;
        }

        // The first subtype applicable to the system locale will be defined as the most applicable
        // subtype.
        if (DEBUG) {
            if (applicableSubtype != null) {
                Slog.d(TAG, "Applicable InputMethodSubtype was found: "
                        + applicableSubtype.getMode() + "," + applicableSubtype.getLocale());
            }
        }
        return applicableSubtype;
    }

    public static boolean canAddToLastInputMethod(InputMethodSubtype subtype) {
        if (subtype == null) return true;
        return !subtype.isAuxiliary();
    }

    public static void setNonSelectedSystemImesDisabledUntilUsed(
            PackageManager packageManager, List<InputMethodInfo> enabledImis) {
        if (DEBUG) {
            Slog.d(TAG, "setNonSelectedSystemImesDisabledUntilUsed");
        }
        final String[] systemImesDisabledUntilUsed = Resources.getSystem().getStringArray(
                com.android.internal.R.array.config_disabledUntilUsedPreinstalledImes);
        if (systemImesDisabledUntilUsed == null || systemImesDisabledUntilUsed.length == 0) {
            return;
        }
        // Only the current spell checker should be treated as an enabled one.
        final SpellCheckerInfo currentSpellChecker =
                TextServicesManager.getInstance().getCurrentSpellChecker();
        for (final String packageName : systemImesDisabledUntilUsed) {
            if (DEBUG) {
                Slog.d(TAG, "check " + packageName);
            }
            boolean enabledIme = false;
            for (int j = 0; j < enabledImis.size(); ++j) {
                final InputMethodInfo imi = enabledImis.get(j);
                if (packageName.equals(imi.getPackageName())) {
                    enabledIme = true;
                    break;
                }
            }
            if (enabledIme) {
                // enabled ime. skip
                continue;
            }
            if (currentSpellChecker != null
                    && packageName.equals(currentSpellChecker.getPackageName())) {
                // enabled spell checker. skip
                if (DEBUG) {
                    Slog.d(TAG, packageName + " is the current spell checker. skip");
                }
                continue;
            }
            ApplicationInfo ai = null;
            try {
                ai = packageManager.getApplicationInfo(packageName,
                        PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS);
            } catch (NameNotFoundException e) {
                Slog.w(TAG, "NameNotFoundException: " + packageName, e);
            }
            if (ai == null) {
                // No app found for packageName
                continue;
            }
            final boolean isSystemPackage = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (!isSystemPackage) {
                continue;
            }
            setDisabledUntilUsed(packageManager, packageName);
        }
    }

    private static void setDisabledUntilUsed(PackageManager packageManager, String packageName) {
        final int state = packageManager.getApplicationEnabledSetting(packageName);
        if (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                || state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            if (DEBUG) {
                Slog.d(TAG, "Update state(" + packageName + "): DISABLED_UNTIL_USED");
            }
            packageManager.setApplicationEnabledSetting(packageName,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED, 0);
        } else {
            if (DEBUG) {
                Slog.d(TAG, packageName + " is already DISABLED_UNTIL_USED");
            }
        }
    }

    public static CharSequence getImeAndSubtypeDisplayName(Context context, InputMethodInfo imi,
            InputMethodSubtype subtype) {
        final CharSequence imiLabel = imi.loadLabel(context.getPackageManager());
        return subtype != null
                ? TextUtils.concat(subtype.getDisplayName(context,
                        imi.getPackageName(), imi.getServiceInfo().applicationInfo),
                                (TextUtils.isEmpty(imiLabel) ?
                                        "" : " - " + imiLabel))
                : imiLabel;
    }

    /**
     * Returns true if a package name belongs to a UID.
     *
     * <p>This is a simple wrapper of {@link AppOpsManager#checkPackage(int, String)}.</p>
     * @param appOpsManager the {@link AppOpsManager} object to be used for the validation.
     * @param uid the UID to be validated.
     * @param packageName the package name.
     * @return {@code true} if the package name belongs to the UID.
     */
    public static boolean checkIfPackageBelongsToUid(final AppOpsManager appOpsManager,
            final int uid, final String packageName) {
        try {
            appOpsManager.checkPackage(uid, packageName);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    /**
     * Utility class for putting and getting settings for InputMethod
     * TODO: Move all putters and getters of settings to this class.
     */
    public static class InputMethodSettings {
        // The string for enabled input method is saved as follows:
        // example: ("ime0;subtype0;subtype1;subtype2:ime1:ime2;subtype0")
        private static final char INPUT_METHOD_SEPARATER = ':';
        private static final char INPUT_METHOD_SUBTYPE_SEPARATER = ';';
        private final TextUtils.SimpleStringSplitter mInputMethodSplitter =
                new TextUtils.SimpleStringSplitter(INPUT_METHOD_SEPARATER);

        private final TextUtils.SimpleStringSplitter mSubtypeSplitter =
                new TextUtils.SimpleStringSplitter(INPUT_METHOD_SUBTYPE_SEPARATER);

        private final Resources mRes;
        private final ContentResolver mResolver;
        private final HashMap<String, InputMethodInfo> mMethodMap;
        private final ArrayList<InputMethodInfo> mMethodList;

        private String mEnabledInputMethodsStrCache;
        private int mCurrentUserId;
        private int[] mCurrentProfileIds = new int[0];

        private static void buildEnabledInputMethodsSettingString(
                StringBuilder builder, Pair<String, ArrayList<String>> pair) {
            String id = pair.first;
            ArrayList<String> subtypes = pair.second;
            builder.append(id);
            // Inputmethod and subtypes are saved in the settings as follows:
            // ime0;subtype0;subtype1:ime1;subtype0:ime2:ime3;subtype0;subtype1
            for (String subtypeId: subtypes) {
                builder.append(INPUT_METHOD_SUBTYPE_SEPARATER).append(subtypeId);
            }
        }

        public InputMethodSettings(
                Resources res, ContentResolver resolver,
                HashMap<String, InputMethodInfo> methodMap, ArrayList<InputMethodInfo> methodList,
                int userId) {
            setCurrentUserId(userId);
            mRes = res;
            mResolver = resolver;
            mMethodMap = methodMap;
            mMethodList = methodList;
        }

        public void setCurrentUserId(int userId) {
            if (DEBUG) {
                Slog.d(TAG, "--- Swtich the current user from " + mCurrentUserId + " to " + userId);
            }
            // IMMS settings are kept per user, so keep track of current user
            mCurrentUserId = userId;
        }

        public void setCurrentProfileIds(int[] currentProfileIds) {
            synchronized (this) {
                mCurrentProfileIds = currentProfileIds;
            }
        }

        public boolean isCurrentProfile(int userId) {
            synchronized (this) {
                if (userId == mCurrentUserId) return true;
                for (int i = 0; i < mCurrentProfileIds.length; i++) {
                    if (userId == mCurrentProfileIds[i]) return true;
                }
                return false;
            }
        }

        public List<InputMethodInfo> getEnabledInputMethodListLocked() {
            return createEnabledInputMethodListLocked(
                    getEnabledInputMethodsAndSubtypeListLocked());
        }

        public List<Pair<InputMethodInfo, ArrayList<String>>>
                getEnabledInputMethodAndSubtypeHashCodeListLocked() {
            return createEnabledInputMethodAndSubtypeHashCodeListLocked(
                    getEnabledInputMethodsAndSubtypeListLocked());
        }

        public List<InputMethodSubtype> getEnabledInputMethodSubtypeListLocked(
                Context context, InputMethodInfo imi, boolean allowsImplicitlySelectedSubtypes) {
            List<InputMethodSubtype> enabledSubtypes =
                    getEnabledInputMethodSubtypeListLocked(imi);
            if (allowsImplicitlySelectedSubtypes && enabledSubtypes.isEmpty()) {
                enabledSubtypes = InputMethodUtils.getImplicitlyApplicableSubtypesLocked(
                        context.getResources(), imi);
            }
            return InputMethodSubtype.sort(context, 0, imi, enabledSubtypes);
        }

        public List<InputMethodSubtype> getEnabledInputMethodSubtypeListLocked(
                InputMethodInfo imi) {
            List<Pair<String, ArrayList<String>>> imsList =
                    getEnabledInputMethodsAndSubtypeListLocked();
            ArrayList<InputMethodSubtype> enabledSubtypes =
                    new ArrayList<InputMethodSubtype>();
            if (imi != null) {
                for (Pair<String, ArrayList<String>> imsPair : imsList) {
                    InputMethodInfo info = mMethodMap.get(imsPair.first);
                    if (info != null && info.getId().equals(imi.getId())) {
                        final int subtypeCount = info.getSubtypeCount();
                        for (int i = 0; i < subtypeCount; ++i) {
                            InputMethodSubtype ims = info.getSubtypeAt(i);
                            for (String s: imsPair.second) {
                                if (String.valueOf(ims.hashCode()).equals(s)) {
                                    enabledSubtypes.add(ims);
                                }
                            }
                        }
                        break;
                    }
                }
            }
            return enabledSubtypes;
        }

        // At the initial boot, the settings for input methods are not set,
        // so we need to enable IME in that case.
        public void enableAllIMEsIfThereIsNoEnabledIME() {
            if (TextUtils.isEmpty(getEnabledInputMethodsStr())) {
                StringBuilder sb = new StringBuilder();
                final int N = mMethodList.size();
                for (int i = 0; i < N; i++) {
                    InputMethodInfo imi = mMethodList.get(i);
                    Slog.i(TAG, "Adding: " + imi.getId());
                    if (i > 0) sb.append(':');
                    sb.append(imi.getId());
                }
                putEnabledInputMethodsStr(sb.toString());
            }
        }

        public List<Pair<String, ArrayList<String>>> getEnabledInputMethodsAndSubtypeListLocked() {
            ArrayList<Pair<String, ArrayList<String>>> imsList
                    = new ArrayList<Pair<String, ArrayList<String>>>();
            final String enabledInputMethodsStr = getEnabledInputMethodsStr();
            if (TextUtils.isEmpty(enabledInputMethodsStr)) {
                return imsList;
            }
            mInputMethodSplitter.setString(enabledInputMethodsStr);
            while (mInputMethodSplitter.hasNext()) {
                String nextImsStr = mInputMethodSplitter.next();
                mSubtypeSplitter.setString(nextImsStr);
                if (mSubtypeSplitter.hasNext()) {
                    ArrayList<String> subtypeHashes = new ArrayList<String>();
                    // The first element is ime id.
                    String imeId = mSubtypeSplitter.next();
                    while (mSubtypeSplitter.hasNext()) {
                        subtypeHashes.add(mSubtypeSplitter.next());
                    }
                    imsList.add(new Pair<String, ArrayList<String>>(imeId, subtypeHashes));
                }
            }
            return imsList;
        }

        public void appendAndPutEnabledInputMethodLocked(String id, boolean reloadInputMethodStr) {
            if (reloadInputMethodStr) {
                getEnabledInputMethodsStr();
            }
            if (TextUtils.isEmpty(mEnabledInputMethodsStrCache)) {
                // Add in the newly enabled input method.
                putEnabledInputMethodsStr(id);
            } else {
                putEnabledInputMethodsStr(
                        mEnabledInputMethodsStrCache + INPUT_METHOD_SEPARATER + id);
            }
        }

        /**
         * Build and put a string of EnabledInputMethods with removing specified Id.
         * @return the specified id was removed or not.
         */
        public boolean buildAndPutEnabledInputMethodsStrRemovingIdLocked(
                StringBuilder builder, List<Pair<String, ArrayList<String>>> imsList, String id) {
            boolean isRemoved = false;
            boolean needsAppendSeparator = false;
            for (Pair<String, ArrayList<String>> ims: imsList) {
                String curId = ims.first;
                if (curId.equals(id)) {
                    // We are disabling this input method, and it is
                    // currently enabled.  Skip it to remove from the
                    // new list.
                    isRemoved = true;
                } else {
                    if (needsAppendSeparator) {
                        builder.append(INPUT_METHOD_SEPARATER);
                    } else {
                        needsAppendSeparator = true;
                    }
                    buildEnabledInputMethodsSettingString(builder, ims);
                }
            }
            if (isRemoved) {
                // Update the setting with the new list of input methods.
                putEnabledInputMethodsStr(builder.toString());
            }
            return isRemoved;
        }

        private List<InputMethodInfo> createEnabledInputMethodListLocked(
                List<Pair<String, ArrayList<String>>> imsList) {
            final ArrayList<InputMethodInfo> res = new ArrayList<InputMethodInfo>();
            for (Pair<String, ArrayList<String>> ims: imsList) {
                InputMethodInfo info = mMethodMap.get(ims.first);
                if (info != null) {
                    res.add(info);
                }
            }
            return res;
        }

        private List<Pair<InputMethodInfo, ArrayList<String>>>
                createEnabledInputMethodAndSubtypeHashCodeListLocked(
                        List<Pair<String, ArrayList<String>>> imsList) {
            final ArrayList<Pair<InputMethodInfo, ArrayList<String>>> res
                    = new ArrayList<Pair<InputMethodInfo, ArrayList<String>>>();
            for (Pair<String, ArrayList<String>> ims : imsList) {
                InputMethodInfo info = mMethodMap.get(ims.first);
                if (info != null) {
                    res.add(new Pair<InputMethodInfo, ArrayList<String>>(info, ims.second));
                }
            }
            return res;
        }

        private void putEnabledInputMethodsStr(String str) {
            Settings.Secure.putStringForUser(
                    mResolver, Settings.Secure.ENABLED_INPUT_METHODS, str, mCurrentUserId);
            mEnabledInputMethodsStrCache = str;
            if (DEBUG) {
                Slog.d(TAG, "putEnabledInputMethodStr: " + str);
            }
        }

        public String getEnabledInputMethodsStr() {
            mEnabledInputMethodsStrCache = Settings.Secure.getStringForUser(
                    mResolver, Settings.Secure.ENABLED_INPUT_METHODS, mCurrentUserId);
            if (DEBUG) {
                Slog.d(TAG, "getEnabledInputMethodsStr: " + mEnabledInputMethodsStrCache
                        + ", " + mCurrentUserId);
            }
            return mEnabledInputMethodsStrCache;
        }

        private void saveSubtypeHistory(
                List<Pair<String, String>> savedImes, String newImeId, String newSubtypeId) {
            StringBuilder builder = new StringBuilder();
            boolean isImeAdded = false;
            if (!TextUtils.isEmpty(newImeId) && !TextUtils.isEmpty(newSubtypeId)) {
                builder.append(newImeId).append(INPUT_METHOD_SUBTYPE_SEPARATER).append(
                        newSubtypeId);
                isImeAdded = true;
            }
            for (Pair<String, String> ime: savedImes) {
                String imeId = ime.first;
                String subtypeId = ime.second;
                if (TextUtils.isEmpty(subtypeId)) {
                    subtypeId = NOT_A_SUBTYPE_ID_STR;
                }
                if (isImeAdded) {
                    builder.append(INPUT_METHOD_SEPARATER);
                } else {
                    isImeAdded = true;
                }
                builder.append(imeId).append(INPUT_METHOD_SUBTYPE_SEPARATER).append(
                        subtypeId);
            }
            // Remove the last INPUT_METHOD_SEPARATER
            putSubtypeHistoryStr(builder.toString());
        }

        private void addSubtypeToHistory(String imeId, String subtypeId) {
            List<Pair<String, String>> subtypeHistory = loadInputMethodAndSubtypeHistoryLocked();
            for (Pair<String, String> ime: subtypeHistory) {
                if (ime.first.equals(imeId)) {
                    if (DEBUG) {
                        Slog.v(TAG, "Subtype found in the history: " + imeId + ", "
                                + ime.second);
                    }
                    // We should break here
                    subtypeHistory.remove(ime);
                    break;
                }
            }
            if (DEBUG) {
                Slog.v(TAG, "Add subtype to the history: " + imeId + ", " + subtypeId);
            }
            saveSubtypeHistory(subtypeHistory, imeId, subtypeId);
        }

        private void putSubtypeHistoryStr(String str) {
            if (DEBUG) {
                Slog.d(TAG, "putSubtypeHistoryStr: " + str);
            }
            Settings.Secure.putStringForUser(
                    mResolver, Settings.Secure.INPUT_METHODS_SUBTYPE_HISTORY, str, mCurrentUserId);
        }

        public Pair<String, String> getLastInputMethodAndSubtypeLocked() {
            // Gets the first one from the history
            return getLastSubtypeForInputMethodLockedInternal(null);
        }

        public String getLastSubtypeForInputMethodLocked(String imeId) {
            Pair<String, String> ime = getLastSubtypeForInputMethodLockedInternal(imeId);
            if (ime != null) {
                return ime.second;
            } else {
                return null;
            }
        }

        private Pair<String, String> getLastSubtypeForInputMethodLockedInternal(String imeId) {
            List<Pair<String, ArrayList<String>>> enabledImes =
                    getEnabledInputMethodsAndSubtypeListLocked();
            List<Pair<String, String>> subtypeHistory = loadInputMethodAndSubtypeHistoryLocked();
            for (Pair<String, String> imeAndSubtype : subtypeHistory) {
                final String imeInTheHistory = imeAndSubtype.first;
                // If imeId is empty, returns the first IME and subtype in the history
                if (TextUtils.isEmpty(imeId) || imeInTheHistory.equals(imeId)) {
                    final String subtypeInTheHistory = imeAndSubtype.second;
                    final String subtypeHashCode =
                            getEnabledSubtypeHashCodeForInputMethodAndSubtypeLocked(
                                    enabledImes, imeInTheHistory, subtypeInTheHistory);
                    if (!TextUtils.isEmpty(subtypeHashCode)) {
                        if (DEBUG) {
                            Slog.d(TAG, "Enabled subtype found in the history: " + subtypeHashCode);
                        }
                        return new Pair<String, String>(imeInTheHistory, subtypeHashCode);
                    }
                }
            }
            if (DEBUG) {
                Slog.d(TAG, "No enabled IME found in the history");
            }
            return null;
        }

        private String getEnabledSubtypeHashCodeForInputMethodAndSubtypeLocked(List<Pair<String,
                ArrayList<String>>> enabledImes, String imeId, String subtypeHashCode) {
            for (Pair<String, ArrayList<String>> enabledIme: enabledImes) {
                if (enabledIme.first.equals(imeId)) {
                    final ArrayList<String> explicitlyEnabledSubtypes = enabledIme.second;
                    final InputMethodInfo imi = mMethodMap.get(imeId);
                    if (explicitlyEnabledSubtypes.size() == 0) {
                        // If there are no explicitly enabled subtypes, applicable subtypes are
                        // enabled implicitly.
                        // If IME is enabled and no subtypes are enabled, applicable subtypes
                        // are enabled implicitly, so needs to treat them to be enabled.
                        if (imi != null && imi.getSubtypeCount() > 0) {
                            List<InputMethodSubtype> implicitlySelectedSubtypes =
                                    getImplicitlyApplicableSubtypesLocked(mRes, imi);
                            if (implicitlySelectedSubtypes != null) {
                                final int N = implicitlySelectedSubtypes.size();
                                for (int i = 0; i < N; ++i) {
                                    final InputMethodSubtype st = implicitlySelectedSubtypes.get(i);
                                    if (String.valueOf(st.hashCode()).equals(subtypeHashCode)) {
                                        return subtypeHashCode;
                                    }
                                }
                            }
                        }
                    } else {
                        for (String s: explicitlyEnabledSubtypes) {
                            if (s.equals(subtypeHashCode)) {
                                // If both imeId and subtypeId are enabled, return subtypeId.
                                try {
                                    final int hashCode = Integer.valueOf(subtypeHashCode);
                                    // Check whether the subtype id is valid or not
                                    if (isValidSubtypeId(imi, hashCode)) {
                                        return s;
                                    } else {
                                        return NOT_A_SUBTYPE_ID_STR;
                                    }
                                } catch (NumberFormatException e) {
                                    return NOT_A_SUBTYPE_ID_STR;
                                }
                            }
                        }
                    }
                    // If imeId was enabled but subtypeId was disabled.
                    return NOT_A_SUBTYPE_ID_STR;
                }
            }
            // If both imeId and subtypeId are disabled, return null
            return null;
        }

        private List<Pair<String, String>> loadInputMethodAndSubtypeHistoryLocked() {
            ArrayList<Pair<String, String>> imsList = new ArrayList<Pair<String, String>>();
            final String subtypeHistoryStr = getSubtypeHistoryStr();
            if (TextUtils.isEmpty(subtypeHistoryStr)) {
                return imsList;
            }
            mInputMethodSplitter.setString(subtypeHistoryStr);
            while (mInputMethodSplitter.hasNext()) {
                String nextImsStr = mInputMethodSplitter.next();
                mSubtypeSplitter.setString(nextImsStr);
                if (mSubtypeSplitter.hasNext()) {
                    String subtypeId = NOT_A_SUBTYPE_ID_STR;
                    // The first element is ime id.
                    String imeId = mSubtypeSplitter.next();
                    while (mSubtypeSplitter.hasNext()) {
                        subtypeId = mSubtypeSplitter.next();
                        break;
                    }
                    imsList.add(new Pair<String, String>(imeId, subtypeId));
                }
            }
            return imsList;
        }

        private String getSubtypeHistoryStr() {
            if (DEBUG) {
                Slog.d(TAG, "getSubtypeHistoryStr: " + Settings.Secure.getStringForUser(
                        mResolver, Settings.Secure.INPUT_METHODS_SUBTYPE_HISTORY, mCurrentUserId));
            }
            return Settings.Secure.getStringForUser(
                    mResolver, Settings.Secure.INPUT_METHODS_SUBTYPE_HISTORY, mCurrentUserId);
        }

        public void putSelectedInputMethod(String imeId) {
            if (DEBUG) {
                Slog.d(TAG, "putSelectedInputMethodStr: " + imeId + ", "
                        + mCurrentUserId);
            }
            Settings.Secure.putStringForUser(
                    mResolver, Settings.Secure.DEFAULT_INPUT_METHOD, imeId, mCurrentUserId);
        }

        public void putSelectedSubtype(int subtypeId) {
            if (DEBUG) {
                Slog.d(TAG, "putSelectedInputMethodSubtypeStr: " + subtypeId + ", "
                        + mCurrentUserId);
            }
            Settings.Secure.putIntForUser(mResolver, Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE,
                    subtypeId, mCurrentUserId);
        }

        public String getDisabledSystemInputMethods() {
            return Settings.Secure.getStringForUser(
                    mResolver, Settings.Secure.DISABLED_SYSTEM_INPUT_METHODS, mCurrentUserId);
        }

        public String getSelectedInputMethod() {
            if (DEBUG) {
                Slog.d(TAG, "getSelectedInputMethodStr: " + Settings.Secure.getStringForUser(
                        mResolver, Settings.Secure.DEFAULT_INPUT_METHOD, mCurrentUserId)
                        + ", " + mCurrentUserId);
            }
            return Settings.Secure.getStringForUser(
                    mResolver, Settings.Secure.DEFAULT_INPUT_METHOD, mCurrentUserId);
        }

        public boolean isSubtypeSelected() {
            return getSelectedInputMethodSubtypeHashCode() != NOT_A_SUBTYPE_ID;
        }

        private int getSelectedInputMethodSubtypeHashCode() {
            try {
                return Settings.Secure.getIntForUser(
                        mResolver, Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE, mCurrentUserId);
            } catch (SettingNotFoundException e) {
                return NOT_A_SUBTYPE_ID;
            }
        }

        public boolean isShowImeWithHardKeyboardEnabled() {
                return Settings.Secure.getIntForUser(mResolver,
                        Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD, 0, mCurrentUserId) == 1;
        }

        public void setShowImeWithHardKeyboard(boolean show) {
            Settings.Secure.putIntForUser(mResolver, Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD,
                    show ? 1 : 0, mCurrentUserId);
        }

        public int getCurrentUserId() {
            return mCurrentUserId;
        }

        public int getSelectedInputMethodSubtypeId(String selectedImiId) {
            final InputMethodInfo imi = mMethodMap.get(selectedImiId);
            if (imi == null) {
                return NOT_A_SUBTYPE_ID;
            }
            final int subtypeHashCode = getSelectedInputMethodSubtypeHashCode();
            return getSubtypeIdFromHashCode(imi, subtypeHashCode);
        }

        public void saveCurrentInputMethodAndSubtypeToHistory(
                String curMethodId, InputMethodSubtype currentSubtype) {
            String subtypeId = NOT_A_SUBTYPE_ID_STR;
            if (currentSubtype != null) {
                subtypeId = String.valueOf(currentSubtype.hashCode());
            }
            if (canAddToLastInputMethod(currentSubtype)) {
                addSubtypeToHistory(curMethodId, subtypeId);
            }
        }

        public HashMap<InputMethodInfo, List<InputMethodSubtype>>
                getExplicitlyOrImplicitlyEnabledInputMethodsAndSubtypeListLocked(Context context) {
            HashMap<InputMethodInfo, List<InputMethodSubtype>> enabledInputMethodAndSubtypes =
                    new HashMap<InputMethodInfo, List<InputMethodSubtype>>();
            for (InputMethodInfo imi: getEnabledInputMethodListLocked()) {
                enabledInputMethodAndSubtypes.put(
                        imi, getEnabledInputMethodSubtypeListLocked(context, imi, true));
            }
            return enabledInputMethodAndSubtypes;
        }
    }
}
