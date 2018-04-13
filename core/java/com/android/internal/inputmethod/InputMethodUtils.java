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

import static android.view.inputmethod.InputMethodManager.CONTROL_WINDOW_IS_TEXT_EDITOR;
import static android.view.inputmethod.InputMethodManager.CONTROL_WINDOW_VIEW_HAS_FOCUS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppOpsManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;
import android.os.RemoteException;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Pair;
import android.util.Printer;
import android.util.Slog;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;
import android.view.textservice.SpellCheckerInfo;
import android.view.textservice.TextServicesManager;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

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

    // The string for enabled input method is saved as follows:
    // example: ("ime0;subtype0;subtype1;subtype2:ime1:ime2;subtype0")
    private static final char INPUT_METHOD_SEPARATOR = ':';
    private static final char INPUT_METHOD_SUBTYPE_SEPARATOR = ';';
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

    // A temporary workaround for the performance concerns in
    // #getImplicitlyApplicableSubtypesLocked(Resources, InputMethodInfo).
    // TODO: Optimize all the critical paths including this one.
    private static final Object sCacheLock = new Object();
    @GuardedBy("sCacheLock")
    private static LocaleList sCachedSystemLocales;
    @GuardedBy("sCacheLock")
    private static InputMethodInfo sCachedInputMethodInfo;
    @GuardedBy("sCacheLock")
    private static ArrayList<InputMethodSubtype> sCachedResult;

    private InputMethodUtils() {
        // This utility class is not publicly instantiable.
    }

    // ----------------------------------------------------------------------
    // Utilities for debug
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

    public static boolean isSystemImeThatHasSubtypeOf(final InputMethodInfo imi,
            final Context context, final boolean checkDefaultAttribute,
            @Nullable final Locale requiredLocale, final boolean checkCountry,
            final String requiredSubtypeMode) {
        if (!imi.isSystem()) {
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

    public static ArrayList<InputMethodInfo> getDefaultEnabledImes(
            Context context, ArrayList<InputMethodInfo> imis, boolean onlyMinimum) {
        final Locale fallbackLocale = getFallbackLocaleForDefaultIme(imis, context);
        // We will primarily rely on the system locale, but also keep relying on the fallback locale
        // as a last resort.
        // Also pick up suitable IMEs regardless of the software keyboard support (e.g. Voice IMEs),
        // then pick up suitable auxiliary IMEs when necessary (e.g. Voice IMEs with "automatic"
        // subtype)
        final Locale systemLocale = getSystemLocaleFromContext(context);
        final InputMethodListBuilder builder =
                getMinimumKeyboardSetWithSystemLocale(imis, context, systemLocale, fallbackLocale);
        if (!onlyMinimum) {
            builder.fillImes(imis, context, true /* checkDefaultAttribute */, systemLocale,
                    true /* checkCountry */, SUBTYPE_MODE_ANY)
                    .fillAuxiliaryImes(imis, context);
        }
        return builder.build();
    }

    public static ArrayList<InputMethodInfo> getDefaultEnabledImes(
            Context context, ArrayList<InputMethodInfo> imis) {
        return getDefaultEnabledImes(context, imis, false /* onlyMinimum */);
    }

    public static Locale constructLocaleFromString(String localeStr) {
        if (TextUtils.isEmpty(localeStr)) {
            return null;
        }
        // TODO: Use {@link Locale#toLanguageTag()} and {@link Locale#forLanguageTag(languageTag)}.
        String[] localeParams = localeStr.split("_", 3);
        if (localeParams.length >= 1 && "tl".equals(localeParams[0])) {
             // Convert a locale whose language is "tl" to one whose language is "fil".
             // For example, "tl_PH" will get converted to "fil_PH".
             // Versions of Android earlier than Lollipop did not support three letter language
             // codes, and used "tl" (Tagalog) as the language string for "fil" (Filipino).
             // On Lollipop and above, the current three letter version must be used.
             localeParams[0] = "fil";
        }
        // The length of localeStr is guaranteed to always return a 1 <= value <= 3
        // because localeStr is not empty.
        if (localeParams.length == 1) {
            return new Locale(localeParams[0]);
        } else if (localeParams.length == 2) {
            return new Locale(localeParams[0], localeParams[1]);
        } else if (localeParams.length == 3) {
            return new Locale(localeParams[0], localeParams[1], localeParams[2]);
        }
        return null;
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
                final Locale subtypeLocale = subtype.getLocaleObject();
                if (subtypeLocale == null ||
                        !TextUtils.equals(subtypeLocale.getLanguage(), locale.getLanguage()) ||
                        !TextUtils.equals(subtypeLocale.getCountry(), locale.getCountry())) {
                    continue;
                }
            } else {
                final Locale subtypeLocale = new Locale(getLanguageFromLocaleString(
                        subtype.getLocale()));
                if (!TextUtils.equals(subtypeLocale.getLanguage(), locale.getLanguage())) {
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

    public static ArrayList<InputMethodSubtype> getSubtypes(InputMethodInfo imi) {
        ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
        final int subtypeCount = imi.getSubtypeCount();
        for (int i = 0; i < subtypeCount; ++i) {
            subtypes.add(imi.getSubtypeAt(i));
        }
        return subtypes;
    }

    public static ArrayList<InputMethodSubtype> getOverridingImplicitlyEnabledSubtypes(
            InputMethodInfo imi, String mode) {
        ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
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
            if (imi.isAuxiliaryIme()) {
                continue;
            }
            if (imi.isSystem() && containsSubtypeOf(
                    imi, ENGLISH_LOCALE, false /* checkCountry */, SUBTYPE_MODE_KEYBOARD)) {
                return imi;
            }
            if (firstFoundSystemIme < 0 && imi.isSystem()) {
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

    private static final LocaleUtils.LocaleExtractor<InputMethodSubtype> sSubtypeToLocale =
            new LocaleUtils.LocaleExtractor<InputMethodSubtype>() {
                @Override
                public Locale get(InputMethodSubtype source) {
                    return source != null ? source.getLocaleObject() : null;
                }
            };

    @VisibleForTesting
    public static ArrayList<InputMethodSubtype> getImplicitlyApplicableSubtypesLocked(
            Resources res, InputMethodInfo imi) {
        final LocaleList systemLocales = res.getConfiguration().getLocales();

        synchronized (sCacheLock) {
            // We intentionally do not use InputMethodInfo#equals(InputMethodInfo) here because
            // it does not check if subtypes are also identical.
            if (systemLocales.equals(sCachedSystemLocales) && sCachedInputMethodInfo == imi) {
                return new ArrayList<>(sCachedResult);
            }
        }

        // Note: Only resource info in "res" is used in getImplicitlyApplicableSubtypesLockedImpl().
        // TODO: Refactor getImplicitlyApplicableSubtypesLockedImpl() so that it can receive
        // LocaleList rather than Resource.
        final ArrayList<InputMethodSubtype> result =
                getImplicitlyApplicableSubtypesLockedImpl(res, imi);
        synchronized (sCacheLock) {
            // Both LocaleList and InputMethodInfo are immutable. No need to copy them here.
            sCachedSystemLocales = systemLocales;
            sCachedInputMethodInfo = imi;
            sCachedResult = new ArrayList<>(result);
        }
        return result;
    }

    private static ArrayList<InputMethodSubtype> getImplicitlyApplicableSubtypesLockedImpl(
            Resources res, InputMethodInfo imi) {
        final List<InputMethodSubtype> subtypes = InputMethodUtils.getSubtypes(imi);
        final LocaleList systemLocales = res.getConfiguration().getLocales();
        final String systemLocale = systemLocales.get(0).toString();
        if (TextUtils.isEmpty(systemLocale)) return new ArrayList<>();
        final int numSubtypes = subtypes.size();

        // Handle overridesImplicitlyEnabledSubtype mechanism.
        final HashMap<String, InputMethodSubtype> applicableModeAndSubtypesMap = new HashMap<>();
        for (int i = 0; i < numSubtypes; ++i) {
            // scan overriding implicitly enabled subtypes.
            final InputMethodSubtype subtype = subtypes.get(i);
            if (subtype.overridesImplicitlyEnabledSubtype()) {
                final String mode = subtype.getMode();
                if (!applicableModeAndSubtypesMap.containsKey(mode)) {
                    applicableModeAndSubtypesMap.put(mode, subtype);
                }
            }
        }
        if (applicableModeAndSubtypesMap.size() > 0) {
            return new ArrayList<>(applicableModeAndSubtypesMap.values());
        }

        final HashMap<String, ArrayList<InputMethodSubtype>> nonKeyboardSubtypesMap =
                new HashMap<>();
        final ArrayList<InputMethodSubtype> keyboardSubtypes = new ArrayList<>();

        for (int i = 0; i < numSubtypes; ++i) {
            final InputMethodSubtype subtype = subtypes.get(i);
            final String mode = subtype.getMode();
            if (SUBTYPE_MODE_KEYBOARD.equals(mode)) {
                keyboardSubtypes.add(subtype);
            } else {
                if (!nonKeyboardSubtypesMap.containsKey(mode)) {
                    nonKeyboardSubtypesMap.put(mode, new ArrayList<>());
                }
                nonKeyboardSubtypesMap.get(mode).add(subtype);
            }
        }

        final ArrayList<InputMethodSubtype> applicableSubtypes = new ArrayList<>();
        LocaleUtils.filterByLanguage(keyboardSubtypes, sSubtypeToLocale, systemLocales,
                applicableSubtypes);

        if (!applicableSubtypes.isEmpty()) {
            boolean hasAsciiCapableKeyboard = false;
            final int numApplicationSubtypes = applicableSubtypes.size();
            for (int i = 0; i < numApplicationSubtypes; ++i) {
                final InputMethodSubtype subtype = applicableSubtypes.get(i);
                if (subtype.containsExtraValueKey(TAG_ASCII_CAPABLE)) {
                    hasAsciiCapableKeyboard = true;
                    break;
                }
            }
            if (!hasAsciiCapableKeyboard) {
                final int numKeyboardSubtypes = keyboardSubtypes.size();
                for (int i = 0; i < numKeyboardSubtypes; ++i) {
                    final InputMethodSubtype subtype = keyboardSubtypes.get(i);
                    final String mode = subtype.getMode();
                    if (SUBTYPE_MODE_KEYBOARD.equals(mode) && subtype.containsExtraValueKey(
                            TAG_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE)) {
                        applicableSubtypes.add(subtype);
                    }
                }
            }
        }

        if (applicableSubtypes.isEmpty()) {
            InputMethodSubtype lastResortKeyboardSubtype = findLastResortApplicableSubtypeLocked(
                    res, subtypes, SUBTYPE_MODE_KEYBOARD, systemLocale, true);
            if (lastResortKeyboardSubtype != null) {
                applicableSubtypes.add(lastResortKeyboardSubtype);
            }
        }

        // For each non-keyboard mode, extract subtypes with system locales.
        for (final ArrayList<InputMethodSubtype> subtypeList : nonKeyboardSubtypesMap.values()) {
            LocaleUtils.filterByLanguage(subtypeList, sSubtypeToLocale, systemLocales,
                    applicableSubtypes);
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
            IPackageManager packageManager, List<InputMethodInfo> enabledImis,
            int userId, String callingPackage) {
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
                        PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS, userId);
            } catch (RemoteException e) {
                Slog.w(TAG, "getApplicationInfo failed. packageName=" + packageName
                        + " userId=" + userId, e);
                continue;
            }
            if (ai == null) {
                // No app found for packageName
                continue;
            }
            final boolean isSystemPackage = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (!isSystemPackage) {
                continue;
            }
            setDisabledUntilUsed(packageManager, packageName, userId, callingPackage);
        }
    }

    private static void setDisabledUntilUsed(IPackageManager packageManager, String packageName,
            int userId, String callingPackage) {
        final int state;
        try {
            state = packageManager.getApplicationEnabledSetting(packageName, userId);
        } catch (RemoteException e) {
            Slog.w(TAG, "getApplicationEnabledSetting failed. packageName=" + packageName
                    + " userId=" + userId, e);
            return;
        }
        if (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                || state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            if (DEBUG) {
                Slog.d(TAG, "Update state(" + packageName + "): DISABLED_UNTIL_USED");
            }
            try {
                packageManager.setApplicationEnabledSetting(packageName,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
                        0 /* newState */, userId, callingPackage);
            } catch (RemoteException e) {
                Slog.w(TAG, "setApplicationEnabledSetting failed. packageName=" + packageName
                        + " userId=" + userId + " callingPackage=" + callingPackage, e);
                return;
            }
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
        private final TextUtils.SimpleStringSplitter mInputMethodSplitter =
                new TextUtils.SimpleStringSplitter(INPUT_METHOD_SEPARATOR);

        private final TextUtils.SimpleStringSplitter mSubtypeSplitter =
                new TextUtils.SimpleStringSplitter(INPUT_METHOD_SUBTYPE_SEPARATOR);

        private final Resources mRes;
        private final ContentResolver mResolver;
        private final HashMap<String, InputMethodInfo> mMethodMap;

        /**
         * On-memory data store to emulate when {@link #mCopyOnWrite} is {@code true}.
         */
        private final HashMap<String, String> mCopyOnWriteDataStore = new HashMap<>();

        private boolean mCopyOnWrite = false;
        @NonNull
        private String mEnabledInputMethodsStrCache = "";
        @UserIdInt
        private int mCurrentUserId;
        private int[] mCurrentProfileIds = new int[0];

        private static void buildEnabledInputMethodsSettingString(
                StringBuilder builder, Pair<String, ArrayList<String>> ime) {
            builder.append(ime.first);
            // Inputmethod and subtypes are saved in the settings as follows:
            // ime0;subtype0;subtype1:ime1;subtype0:ime2:ime3;subtype0;subtype1
            for (String subtypeId: ime.second) {
                builder.append(INPUT_METHOD_SUBTYPE_SEPARATOR).append(subtypeId);
            }
        }

        private static List<Pair<String, ArrayList<String>>> buildInputMethodsAndSubtypeList(
                String enabledInputMethodsStr,
                TextUtils.SimpleStringSplitter inputMethodSplitter,
                TextUtils.SimpleStringSplitter subtypeSplitter) {
            ArrayList<Pair<String, ArrayList<String>>> imsList = new ArrayList<>();
            if (TextUtils.isEmpty(enabledInputMethodsStr)) {
                return imsList;
            }
            inputMethodSplitter.setString(enabledInputMethodsStr);
            while (inputMethodSplitter.hasNext()) {
                String nextImsStr = inputMethodSplitter.next();
                subtypeSplitter.setString(nextImsStr);
                if (subtypeSplitter.hasNext()) {
                    ArrayList<String> subtypeHashes = new ArrayList<>();
                    // The first element is ime id.
                    String imeId = subtypeSplitter.next();
                    while (subtypeSplitter.hasNext()) {
                        subtypeHashes.add(subtypeSplitter.next());
                    }
                    imsList.add(new Pair<>(imeId, subtypeHashes));
                }
            }
            return imsList;
        }

        public InputMethodSettings(
                Resources res, ContentResolver resolver,
                HashMap<String, InputMethodInfo> methodMap, ArrayList<InputMethodInfo> methodList,
                @UserIdInt int userId, boolean copyOnWrite) {
            mRes = res;
            mResolver = resolver;
            mMethodMap = methodMap;
            switchCurrentUser(userId, copyOnWrite);
        }

        /**
         * Must be called when the current user is changed.
         *
         * @param userId The user ID.
         * @param copyOnWrite If {@code true}, for each settings key
         * (e.g. {@link Settings.Secure#ACTION_INPUT_METHOD_SUBTYPE_SETTINGS}) we use the actual
         * settings on the {@link Settings.Secure} until we do the first write operation.
         */
        public void switchCurrentUser(@UserIdInt int userId, boolean copyOnWrite) {
            if (DEBUG) {
                Slog.d(TAG, "--- Switch the current user from " + mCurrentUserId + " to " + userId);
            }
            if (mCurrentUserId != userId || mCopyOnWrite != copyOnWrite) {
                mCopyOnWriteDataStore.clear();
                mEnabledInputMethodsStrCache = "";
                // TODO: mCurrentProfileIds should be cleared here.
            }
            mCurrentUserId = userId;
            mCopyOnWrite = copyOnWrite;
            // TODO: mCurrentProfileIds should be updated here.
        }

        private void putString(@NonNull final String key, @Nullable final String str) {
            if (mCopyOnWrite) {
                mCopyOnWriteDataStore.put(key, str);
            } else {
                Settings.Secure.putStringForUser(mResolver, key, str, mCurrentUserId);
            }
        }

        @Nullable
        private String getString(@NonNull final String key, @Nullable final String defaultValue) {
            final String result;
            if (mCopyOnWrite && mCopyOnWriteDataStore.containsKey(key)) {
                result = mCopyOnWriteDataStore.get(key);
            } else {
                result = Settings.Secure.getStringForUser(mResolver, key, mCurrentUserId);
            }
            return result != null ? result : defaultValue;
        }

        private void putInt(final String key, final int value) {
            if (mCopyOnWrite) {
                mCopyOnWriteDataStore.put(key, String.valueOf(value));
            } else {
                Settings.Secure.putIntForUser(mResolver, key, value, mCurrentUserId);
            }
        }

        private int getInt(final String key, final int defaultValue) {
            if (mCopyOnWrite && mCopyOnWriteDataStore.containsKey(key)) {
                final String result = mCopyOnWriteDataStore.get(key);
                return result != null ? Integer.parseInt(result) : 0;
            }
            return Settings.Secure.getIntForUser(mResolver, key, defaultValue, mCurrentUserId);
        }

        private void putBoolean(final String key, final boolean value) {
            putInt(key, value ? 1 : 0);
        }

        private boolean getBoolean(final String key, final boolean defaultValue) {
            return getInt(key, defaultValue ? 1 : 0) == 1;
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

        public ArrayList<InputMethodInfo> getEnabledInputMethodListLocked() {
            return createEnabledInputMethodListLocked(
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
            ArrayList<InputMethodSubtype> enabledSubtypes = new ArrayList<>();
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

        public List<Pair<String, ArrayList<String>>> getEnabledInputMethodsAndSubtypeListLocked() {
            return buildInputMethodsAndSubtypeList(getEnabledInputMethodsStr(),
                    mInputMethodSplitter,
                    mSubtypeSplitter);
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
                        mEnabledInputMethodsStrCache + INPUT_METHOD_SEPARATOR + id);
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
                        builder.append(INPUT_METHOD_SEPARATOR);
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

        private ArrayList<InputMethodInfo> createEnabledInputMethodListLocked(
                List<Pair<String, ArrayList<String>>> imsList) {
            final ArrayList<InputMethodInfo> res = new ArrayList<>();
            for (Pair<String, ArrayList<String>> ims: imsList) {
                InputMethodInfo info = mMethodMap.get(ims.first);
                if (info != null && !info.isVrOnly()) {
                    res.add(info);
                }
            }
            return res;
        }

        private void putEnabledInputMethodsStr(@Nullable String str) {
            if (DEBUG) {
                Slog.d(TAG, "putEnabledInputMethodStr: " + str);
            }
            if (TextUtils.isEmpty(str)) {
                // OK to coalesce to null, since getEnabledInputMethodsStr() can take care of the
                // empty data scenario.
                putString(Settings.Secure.ENABLED_INPUT_METHODS, null);
            } else {
                putString(Settings.Secure.ENABLED_INPUT_METHODS, str);
            }
            // TODO: Update callers of putEnabledInputMethodsStr to make str @NonNull.
            mEnabledInputMethodsStrCache = (str != null ? str : "");
        }

        @NonNull
        public String getEnabledInputMethodsStr() {
            mEnabledInputMethodsStrCache = getString(Settings.Secure.ENABLED_INPUT_METHODS, "");
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
                builder.append(newImeId).append(INPUT_METHOD_SUBTYPE_SEPARATOR).append(
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
                    builder.append(INPUT_METHOD_SEPARATOR);
                } else {
                    isImeAdded = true;
                }
                builder.append(imeId).append(INPUT_METHOD_SUBTYPE_SEPARATOR).append(
                        subtypeId);
            }
            // Remove the last INPUT_METHOD_SEPARATOR
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

        private void putSubtypeHistoryStr(@NonNull String str) {
            if (DEBUG) {
                Slog.d(TAG, "putSubtypeHistoryStr: " + str);
            }
            if (TextUtils.isEmpty(str)) {
                // OK to coalesce to null, since getSubtypeHistoryStr() can take care of the empty
                // data scenario.
                putString(Settings.Secure.INPUT_METHODS_SUBTYPE_HISTORY, null);
            } else {
                putString(Settings.Secure.INPUT_METHODS_SUBTYPE_HISTORY, str);
            }
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
                        return new Pair<>(imeInTheHistory, subtypeHashCode);
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
                                    final int hashCode = Integer.parseInt(subtypeHashCode);
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
            ArrayList<Pair<String, String>> imsList = new ArrayList<>();
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
                    imsList.add(new Pair<>(imeId, subtypeId));
                }
            }
            return imsList;
        }

        @NonNull
        private String getSubtypeHistoryStr() {
            final String history = getString(Settings.Secure.INPUT_METHODS_SUBTYPE_HISTORY, "");
            if (DEBUG) {
                Slog.d(TAG, "getSubtypeHistoryStr: " + history);
            }
            return history;
        }

        public void putSelectedInputMethod(String imeId) {
            if (DEBUG) {
                Slog.d(TAG, "putSelectedInputMethodStr: " + imeId + ", "
                        + mCurrentUserId);
            }
            putString(Settings.Secure.DEFAULT_INPUT_METHOD, imeId);
        }

        public void putSelectedSubtype(int subtypeId) {
            if (DEBUG) {
                Slog.d(TAG, "putSelectedInputMethodSubtypeStr: " + subtypeId + ", "
                        + mCurrentUserId);
            }
            putInt(Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE, subtypeId);
        }

        @Nullable
        public String getSelectedInputMethod() {
            final String imi = getString(Settings.Secure.DEFAULT_INPUT_METHOD, null);
            if (DEBUG) {
                Slog.d(TAG, "getSelectedInputMethodStr: " + imi);
            }
            return imi;
        }

        public boolean isSubtypeSelected() {
            return getSelectedInputMethodSubtypeHashCode() != NOT_A_SUBTYPE_ID;
        }

        private int getSelectedInputMethodSubtypeHashCode() {
            return getInt(Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE, NOT_A_SUBTYPE_ID);
        }

        public boolean isShowImeWithHardKeyboardEnabled() {
            return getBoolean(Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD, false);
        }

        public void setShowImeWithHardKeyboard(boolean show) {
            putBoolean(Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD, show);
        }

        @UserIdInt
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
                    new HashMap<>();
            for (InputMethodInfo imi: getEnabledInputMethodListLocked()) {
                enabledInputMethodAndSubtypes.put(
                        imi, getEnabledInputMethodSubtypeListLocked(context, imi, true));
            }
            return enabledInputMethodAndSubtypes;
        }

        public void dumpLocked(final Printer pw, final String prefix) {
            pw.println(prefix + "mCurrentUserId=" + mCurrentUserId);
            pw.println(prefix + "mCurrentProfileIds=" + Arrays.toString(mCurrentProfileIds));
            pw.println(prefix + "mCopyOnWrite=" + mCopyOnWrite);
            pw.println(prefix + "mEnabledInputMethodsStrCache=" + mEnabledInputMethodsStrCache);
        }
    }

    // For spell checker service manager.
    // TODO: Should we have TextServicesUtils.java?
    private static final Locale LOCALE_EN_US = new Locale("en", "US");
    private static final Locale LOCALE_EN_GB = new Locale("en", "GB");

    /**
     * Returns a list of {@link Locale} in the order of appropriateness for the default spell
     * checker service.
     *
     * <p>If the system language is English, and the region is also explicitly specified in the
     * system locale, the following fallback order will be applied.</p>
     * <ul>
     * <li>(system-locale-language, system-locale-region, system-locale-variant) (if exists)</li>
     * <li>(system-locale-language, system-locale-region)</li>
     * <li>("en", "US")</li>
     * <li>("en", "GB")</li>
     * <li>("en")</li>
     * </ul>
     *
     * <p>If the system language is English, but no region is specified in the system locale,
     * the following fallback order will be applied.</p>
     * <ul>
     * <li>("en")</li>
     * <li>("en", "US")</li>
     * <li>("en", "GB")</li>
     * </ul>
     *
     * <p>If the system language is not English, the following fallback order will be applied.</p>
     * <ul>
     * <li>(system-locale-language, system-locale-region, system-locale-variant) (if exists)</li>
     * <li>(system-locale-language, system-locale-region) (if exists)</li>
     * <li>(system-locale-language) (if exists)</li>
     * <li>("en", "US")</li>
     * <li>("en", "GB")</li>
     * <li>("en")</li>
     * </ul>
     *
     * @param systemLocale the current system locale to be taken into consideration.
     * @return a list of {@link Locale}. The first one is considered to be most appropriate.
     */
    @VisibleForTesting
    public static ArrayList<Locale> getSuitableLocalesForSpellChecker(
            @Nullable final Locale systemLocale) {
        final Locale systemLocaleLanguageCountryVariant;
        final Locale systemLocaleLanguageCountry;
        final Locale systemLocaleLanguage;
        if (systemLocale != null) {
            final String language = systemLocale.getLanguage();
            final boolean hasLanguage = !TextUtils.isEmpty(language);
            final String country = systemLocale.getCountry();
            final boolean hasCountry = !TextUtils.isEmpty(country);
            final String variant = systemLocale.getVariant();
            final boolean hasVariant = !TextUtils.isEmpty(variant);
            if (hasLanguage && hasCountry && hasVariant) {
                systemLocaleLanguageCountryVariant = new Locale(language, country, variant);
            } else {
                systemLocaleLanguageCountryVariant = null;
            }
            if (hasLanguage && hasCountry) {
                systemLocaleLanguageCountry = new Locale(language, country);
            } else {
                systemLocaleLanguageCountry = null;
            }
            if (hasLanguage) {
                systemLocaleLanguage = new Locale(language);
            } else {
                systemLocaleLanguage = null;
            }
        } else {
            systemLocaleLanguageCountryVariant = null;
            systemLocaleLanguageCountry = null;
            systemLocaleLanguage = null;
        }

        final ArrayList<Locale> locales = new ArrayList<>();
        if (systemLocaleLanguageCountryVariant != null) {
            locales.add(systemLocaleLanguageCountryVariant);
        }

        if (Locale.ENGLISH.equals(systemLocaleLanguage)) {
            if (systemLocaleLanguageCountry != null) {
                // If the system language is English, and the region is also explicitly specified,
                // following fallback order will be applied.
                // - systemLocaleLanguageCountry [if systemLocaleLanguageCountry is non-null]
                // - en_US [if systemLocaleLanguageCountry is non-null and not en_US]
                // - en_GB [if systemLocaleLanguageCountry is non-null and not en_GB]
                // - en
                if (systemLocaleLanguageCountry != null) {
                    locales.add(systemLocaleLanguageCountry);
                }
                if (!LOCALE_EN_US.equals(systemLocaleLanguageCountry)) {
                    locales.add(LOCALE_EN_US);
                }
                if (!LOCALE_EN_GB.equals(systemLocaleLanguageCountry)) {
                    locales.add(LOCALE_EN_GB);
                }
                locales.add(Locale.ENGLISH);
            } else {
                // If the system language is English, but no region is specified, following
                // fallback order will be applied.
                // - en
                // - en_US
                // - en_GB
                locales.add(Locale.ENGLISH);
                locales.add(LOCALE_EN_US);
                locales.add(LOCALE_EN_GB);
            }
        } else {
            // If the system language is not English, the fallback order will be
            // - systemLocaleLanguageCountry  [if non-null]
            // - systemLocaleLanguage  [if non-null]
            // - en_US
            // - en_GB
            // - en
            if (systemLocaleLanguageCountry != null) {
                locales.add(systemLocaleLanguageCountry);
            }
            if (systemLocaleLanguage != null) {
                locales.add(systemLocaleLanguage);
            }
            locales.add(LOCALE_EN_US);
            locales.add(LOCALE_EN_GB);
            locales.add(Locale.ENGLISH);
        }
        return locales;
    }

    public static boolean isSoftInputModeStateVisibleAllowed(
            int targetSdkVersion, int controlFlags) {
        if (targetSdkVersion < Build.VERSION_CODES.P) {
            // for compatibility.
            return true;
        }
        if ((controlFlags & CONTROL_WINDOW_VIEW_HAS_FOCUS) == 0) {
            return false;
        }
        if ((controlFlags & CONTROL_WINDOW_IS_TEXT_EDITOR) == 0) {
            return false;
        }
        return true;
    }

}
