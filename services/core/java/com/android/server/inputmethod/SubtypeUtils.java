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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Resources;
import android.os.LocaleList;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * This class provides utility methods to handle and manage {@link InputMethodSubtype} for
 * {@link InputMethodManagerService}.
 *
 * <p>This class is intentionally package-private.  Utility methods here are tightly coupled with
 * implementation details in {@link InputMethodManagerService}.  Hence this class is not suitable
 * for other components to directly use.</p>
 */
final class SubtypeUtils {
    private static final String TAG = "SubtypeUtils";
    public static final boolean DEBUG = false;

    static final String SUBTYPE_MODE_ANY = null;
    static final String SUBTYPE_MODE_KEYBOARD = "keyboard";

    static final int NOT_A_SUBTYPE_ID = -1;
    private static final String TAG_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE =
            "EnabledWhenDefaultIsNotAsciiCapable";

    // A temporary workaround for the performance concerns in
    // #getImplicitlyApplicableSubtypesLocked(Resources, InputMethodInfo).
    // TODO: Optimize all the critical paths including this one.
    // TODO(b/235661780): Make the cache supports multi-users.
    private static final Object sCacheLock = new Object();
    @GuardedBy("sCacheLock")
    private static LocaleList sCachedSystemLocales;
    @GuardedBy("sCacheLock")
    private static InputMethodInfo sCachedInputMethodInfo;
    @GuardedBy("sCacheLock")
    private static ArrayList<InputMethodSubtype> sCachedResult;

    static boolean containsSubtypeOf(InputMethodInfo imi, @Nullable Locale locale,
            boolean checkCountry, String mode) {
        if (locale == null) {
            return false;
        }
        final int numSubtypes = imi.getSubtypeCount();
        for (int i = 0; i < numSubtypes; ++i) {
            final InputMethodSubtype subtype = imi.getSubtypeAt(i);
            if (checkCountry) {
                final Locale subtypeLocale = subtype.getLocaleObject();
                if (subtypeLocale == null
                        || !TextUtils.equals(subtypeLocale.getLanguage(), locale.getLanguage())
                        || !TextUtils.equals(subtypeLocale.getCountry(), locale.getCountry())) {
                    continue;
                }
            } else {
                final Locale subtypeLocale = new Locale(LocaleUtils.getLanguageFromLocaleString(
                        subtype.getLocale()));
                if (!TextUtils.equals(subtypeLocale.getLanguage(), locale.getLanguage())) {
                    continue;
                }
            }
            if (TextUtils.isEmpty(mode) || mode.equalsIgnoreCase(subtype.getMode())) {
                return true;
            }
        }
        return false;
    }

    static ArrayList<InputMethodSubtype> getSubtypes(InputMethodInfo imi) {
        ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
        final int subtypeCount = imi.getSubtypeCount();
        for (int i = 0; i < subtypeCount; ++i) {
            subtypes.add(imi.getSubtypeAt(i));
        }
        return subtypes;
    }

    static boolean isValidSubtypeId(InputMethodInfo imi, int subtypeHashCode) {
        return getSubtypeIdFromHashCode(imi, subtypeHashCode) != NOT_A_SUBTYPE_ID;
    }

    static int getSubtypeIdFromHashCode(InputMethodInfo imi, int subtypeHashCode) {
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
            source -> source != null ? source.getLocaleObject() : null;

    @VisibleForTesting
    @NonNull
    static ArrayList<InputMethodSubtype> getImplicitlyApplicableSubtypesLocked(
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
        final List<InputMethodSubtype> subtypes = getSubtypes(imi);
        final LocaleList systemLocales = res.getConfiguration().getLocales();
        final String systemLocale = systemLocales.get(0).toString();
        if (TextUtils.isEmpty(systemLocale)) return new ArrayList<>();
        final int numSubtypes = subtypes.size();

        // Handle overridesImplicitlyEnabledSubtype mechanism.
        final ArrayMap<String, InputMethodSubtype> applicableModeAndSubtypesMap = new ArrayMap<>();
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

        final ArrayMap<String, ArrayList<InputMethodSubtype>> nonKeyboardSubtypesMap =
                new ArrayMap<>();
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
                if (subtype.isAsciiCapable()) {
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
     * If there are no selected subtypes, tries finding the most applicable one according to the
     * given locale.
     *
     * @param subtypes                    a list of {@link InputMethodSubtype} to search
     * @param mode                        the mode used for filtering subtypes
     * @param locale                      the locale used for filtering subtypes
     * @param canIgnoreLocaleAsLastResort when set to {@code true}, if this function can't find the
     *                                    most applicable subtype, it will return the first subtype
     *                                    matched with mode
     *
     * @return the most applicable subtypeId
     */
    static InputMethodSubtype findLastResortApplicableSubtypeLocked(
            Resources res, List<InputMethodSubtype> subtypes, String mode, String locale,
            boolean canIgnoreLocaleAsLastResort) {
        if (subtypes == null || subtypes.isEmpty()) {
            return null;
        }
        if (TextUtils.isEmpty(locale)) {
            locale = res.getConfiguration().locale.toString();
        }
        final String language = LocaleUtils.getLanguageFromLocaleString(locale);
        boolean partialMatchFound = false;
        InputMethodSubtype applicableSubtype = null;
        InputMethodSubtype firstMatchedModeSubtype = null;
        final int numSubtypes = subtypes.size();
        for (int i = 0; i < numSubtypes; ++i) {
            InputMethodSubtype subtype = subtypes.get(i);
            final String subtypeLocale = subtype.getLocale();
            final String subtypeLanguage = LocaleUtils.getLanguageFromLocaleString(subtypeLocale);
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
}
