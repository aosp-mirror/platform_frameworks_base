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

package com.android.server.inputmethod;

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
import android.os.UserHandle;
import android.os.UserManagerInternal;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;
import android.util.Printer;
import android.util.Slog;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;
import android.view.textservice.SpellCheckerInfo;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.inputmethod.StartInputFlags;
import com.android.server.LocalServices;
import com.android.server.textservices.TextServicesManagerInternal;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * This class provides random static utility methods for {@link InputMethodManagerService} and its
 * utility classes.
 *
 * <p>This class is intentionally package-private.  Utility methods here are tightly coupled with
 * implementation details in {@link InputMethodManagerService}.  Hence this class is not suitable
 * for other components to directly use.</p>
 */
final class InputMethodUtils {
    public static final boolean DEBUG = false;
    static final int NOT_A_SUBTYPE_ID = -1;
    private static final String SUBTYPE_MODE_ANY = null;
    static final String SUBTYPE_MODE_KEYBOARD = "keyboard";
    private static final String TAG = "InputMethodUtils";
    private static final Locale ENGLISH_LOCALE = new Locale("en");
    private static final String NOT_A_SUBTYPE_ID_STR = String.valueOf(NOT_A_SUBTYPE_ID);
    private static final String TAG_ENABLED_WHEN_DEFAULT_IS_NOT_ASCII_CAPABLE =
            "EnabledWhenDefaultIsNotAsciiCapable";

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
    static String getApiCallStack() {
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

    private static boolean isSystemImeThatHasSubtypeOf(InputMethodInfo imi, Context context,
            boolean checkDefaultAttribute, @Nullable Locale requiredLocale, boolean checkCountry,
            String requiredSubtypeMode) {
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
    private static Locale getFallbackLocaleForDefaultIme(ArrayList<InputMethodInfo> imis,
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

    private static Locale getSystemLocaleFromContext(Context context) {
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

        InputMethodListBuilder fillImes(ArrayList<InputMethodInfo> imis, Context context,
                boolean checkDefaultAttribute, @Nullable Locale locale, boolean checkCountry,
                String requiredSubtypeMode) {
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
        InputMethodListBuilder fillAuxiliaryImes(ArrayList<InputMethodInfo> imis, Context context) {
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
            ArrayList<InputMethodInfo> imis, Context context, @Nullable Locale systemLocale,
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

    static ArrayList<InputMethodInfo> getDefaultEnabledImes(
            Context context, ArrayList<InputMethodInfo> imis) {
        return getDefaultEnabledImes(context, imis, false /* onlyMinimum */);
    }

    static boolean containsSubtypeOf(InputMethodInfo imi, @Nullable Locale locale,
            boolean checkCountry, String mode) {
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

    static ArrayList<InputMethodSubtype> getSubtypes(InputMethodInfo imi) {
        ArrayList<InputMethodSubtype> subtypes = new ArrayList<>();
        final int subtypeCount = imi.getSubtypeCount();
        for (int i = 0; i < subtypeCount; ++i) {
            subtypes.add(imi.getSubtypeAt(i));
        }
        return subtypes;
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
            new LocaleUtils.LocaleExtractor<InputMethodSubtype>() {
                @Override
                public Locale get(InputMethodSubtype source) {
                    return source != null ? source.getLocaleObject() : null;
                }
            };

    @VisibleForTesting
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
        final List<InputMethodSubtype> subtypes = InputMethodUtils.getSubtypes(imi);
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
     * Returns the language component of a given locale string.
     * TODO: Use {@link Locale#toLanguageTag()} and {@link Locale#forLanguageTag(String)}
     */
    private static String getLanguageFromLocaleString(String locale) {
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
    static InputMethodSubtype findLastResortApplicableSubtypeLocked(
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

    static boolean canAddToLastInputMethod(InputMethodSubtype subtype) {
        if (subtype == null) return true;
        return !subtype.isAuxiliary();
    }

    static void setNonSelectedSystemImesDisabledUntilUsed(IPackageManager packageManager,
            List<InputMethodInfo> enabledImis, @UserIdInt int userId, String callingPackage) {
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
                TextServicesManagerInternal.get().getCurrentSpellCheckerForUser(userId);
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

    static CharSequence getImeAndSubtypeDisplayName(Context context, InputMethodInfo imi,
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
    static boolean checkIfPackageBelongsToUid(AppOpsManager appOpsManager,
            @UserIdInt int uid, String packageName) {
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
        private final ArrayMap<String, InputMethodInfo> mMethodMap;

        /**
         * On-memory data store to emulate when {@link #mCopyOnWrite} is {@code true}.
         */
        private final ArrayMap<String, String> mCopyOnWriteDataStore = new ArrayMap<>();

        private static final ArraySet<String> CLONE_TO_MANAGED_PROFILE = new ArraySet<>();
        static {
            Settings.Secure.getCloneToManagedProfileSettings(CLONE_TO_MANAGED_PROFILE);
        }

        private static final UserManagerInternal sUserManagerInternal =
                LocalServices.getService(UserManagerInternal.class);

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

        InputMethodSettings(Resources res, ContentResolver resolver,
                ArrayMap<String, InputMethodInfo> methodMap, @UserIdInt int userId,
                boolean copyOnWrite) {
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
        void switchCurrentUser(@UserIdInt int userId, boolean copyOnWrite) {
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

        private void putString(@NonNull String key, @Nullable String str) {
            if (mCopyOnWrite) {
                mCopyOnWriteDataStore.put(key, str);
            } else {
                final int userId = CLONE_TO_MANAGED_PROFILE.contains(key)
                        ? sUserManagerInternal.getProfileParentId(mCurrentUserId) : mCurrentUserId;
                Settings.Secure.putStringForUser(mResolver, key, str, userId);
            }
        }

        @Nullable
        private String getString(@NonNull String key, @Nullable String defaultValue) {
            final String result;
            if (mCopyOnWrite && mCopyOnWriteDataStore.containsKey(key)) {
                result = mCopyOnWriteDataStore.get(key);
            } else {
                result = Settings.Secure.getStringForUser(mResolver, key, mCurrentUserId);
            }
            return result != null ? result : defaultValue;
        }

        private void putInt(String key, int value) {
            if (mCopyOnWrite) {
                mCopyOnWriteDataStore.put(key, String.valueOf(value));
            } else {
                final int userId = CLONE_TO_MANAGED_PROFILE.contains(key)
                        ? sUserManagerInternal.getProfileParentId(mCurrentUserId) : mCurrentUserId;
                Settings.Secure.putIntForUser(mResolver, key, value, userId);
            }
        }

        private int getInt(String key, int defaultValue) {
            if (mCopyOnWrite && mCopyOnWriteDataStore.containsKey(key)) {
                final String result = mCopyOnWriteDataStore.get(key);
                return result != null ? Integer.parseInt(result) : defaultValue;
            }
            return Settings.Secure.getIntForUser(mResolver, key, defaultValue, mCurrentUserId);
        }

        private void putBoolean(String key, boolean value) {
            putInt(key, value ? 1 : 0);
        }

        private boolean getBoolean(String key, boolean defaultValue) {
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

        ArrayList<InputMethodInfo> getEnabledInputMethodListLocked() {
            return createEnabledInputMethodListLocked(
                    getEnabledInputMethodsAndSubtypeListLocked());
        }

        List<InputMethodSubtype> getEnabledInputMethodSubtypeListLocked(
                Context context, InputMethodInfo imi, boolean allowsImplicitlySelectedSubtypes) {
            List<InputMethodSubtype> enabledSubtypes =
                    getEnabledInputMethodSubtypeListLocked(imi);
            if (allowsImplicitlySelectedSubtypes && enabledSubtypes.isEmpty()) {
                enabledSubtypes = InputMethodUtils.getImplicitlyApplicableSubtypesLocked(
                        context.getResources(), imi);
            }
            return InputMethodSubtype.sort(context, 0, imi, enabledSubtypes);
        }

        List<InputMethodSubtype> getEnabledInputMethodSubtypeListLocked(InputMethodInfo imi) {
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

        List<Pair<String, ArrayList<String>>> getEnabledInputMethodsAndSubtypeListLocked() {
            return buildInputMethodsAndSubtypeList(getEnabledInputMethodsStr(),
                    mInputMethodSplitter,
                    mSubtypeSplitter);
        }

        void appendAndPutEnabledInputMethodLocked(String id, boolean reloadInputMethodStr) {
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
        boolean buildAndPutEnabledInputMethodsStrRemovingIdLocked(
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

        void putEnabledInputMethodsStr(@Nullable String str) {
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
        String getEnabledInputMethodsStr() {
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

        Pair<String, String> getLastInputMethodAndSubtypeLocked() {
            // Gets the first one from the history
            return getLastSubtypeForInputMethodLockedInternal(null);
        }

        String getLastSubtypeForInputMethodLocked(String imeId) {
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

        void putSelectedInputMethod(String imeId) {
            if (DEBUG) {
                Slog.d(TAG, "putSelectedInputMethodStr: " + imeId + ", "
                        + mCurrentUserId);
            }
            putString(Settings.Secure.DEFAULT_INPUT_METHOD, imeId);
        }

        void putSelectedSubtype(int subtypeId) {
            if (DEBUG) {
                Slog.d(TAG, "putSelectedInputMethodSubtypeStr: " + subtypeId + ", "
                        + mCurrentUserId);
            }
            putInt(Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE, subtypeId);
        }

        @Nullable
        String getSelectedInputMethod() {
            final String imi = getString(Settings.Secure.DEFAULT_INPUT_METHOD, null);
            if (DEBUG) {
                Slog.d(TAG, "getSelectedInputMethodStr: " + imi);
            }
            return imi;
        }

        boolean isSubtypeSelected() {
            return getSelectedInputMethodSubtypeHashCode() != NOT_A_SUBTYPE_ID;
        }

        private int getSelectedInputMethodSubtypeHashCode() {
            return getInt(Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE, NOT_A_SUBTYPE_ID);
        }

        boolean isShowImeWithHardKeyboardEnabled() {
            return getBoolean(Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD, false);
        }

        void setShowImeWithHardKeyboard(boolean show) {
            putBoolean(Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD, show);
        }

        @UserIdInt
        public int getCurrentUserId() {
            return mCurrentUserId;
        }

        int getSelectedInputMethodSubtypeId(String selectedImiId) {
            final InputMethodInfo imi = mMethodMap.get(selectedImiId);
            if (imi == null) {
                return NOT_A_SUBTYPE_ID;
            }
            final int subtypeHashCode = getSelectedInputMethodSubtypeHashCode();
            return getSubtypeIdFromHashCode(imi, subtypeHashCode);
        }

        void saveCurrentInputMethodAndSubtypeToHistory(String curMethodId,
                InputMethodSubtype currentSubtype) {
            String subtypeId = NOT_A_SUBTYPE_ID_STR;
            if (currentSubtype != null) {
                subtypeId = String.valueOf(currentSubtype.hashCode());
            }
            if (canAddToLastInputMethod(currentSubtype)) {
                addSubtypeToHistory(curMethodId, subtypeId);
            }
        }

        public void dumpLocked(final Printer pw, final String prefix) {
            pw.println(prefix + "mCurrentUserId=" + mCurrentUserId);
            pw.println(prefix + "mCurrentProfileIds=" + Arrays.toString(mCurrentProfileIds));
            pw.println(prefix + "mCopyOnWrite=" + mCopyOnWrite);
            pw.println(prefix + "mEnabledInputMethodsStrCache=" + mEnabledInputMethodsStrCache);
        }
    }

    static boolean isSoftInputModeStateVisibleAllowed(int targetSdkVersion,
            @StartInputFlags int startInputFlags) {
        if (targetSdkVersion < Build.VERSION_CODES.P) {
            // for compatibility.
            return true;
        }
        if ((startInputFlags & StartInputFlags.VIEW_HAS_FOCUS) == 0) {
            return false;
        }
        if ((startInputFlags & StartInputFlags.IS_TEXT_EDITOR) == 0) {
            return false;
        }
        return true;
    }

    /**
     * Converts a user ID, which can be a pseudo user ID such as {@link UserHandle#USER_ALL} to a
     * list of real user IDs.
     *
     * @param userIdToBeResolved A user ID. Two pseudo user ID {@link UserHandle#USER_CURRENT} and
     *                           {@link UserHandle#USER_ALL} are also supported
     * @param currentUserId A real user ID, which will be used when {@link UserHandle#USER_CURRENT}
     *                      is specified in {@code userIdToBeResolved}.
     * @param warningWriter A {@link PrintWriter} to output some debug messages. {@code null} if
     *                      no debug message is required.
     * @return An integer array that contain user IDs.
     */
    static int[] resolveUserId(@UserIdInt int userIdToBeResolved,
            @UserIdInt int currentUserId, @Nullable PrintWriter warningWriter) {
        final UserManagerInternal userManagerInternal =
                LocalServices.getService(UserManagerInternal.class);

        if (userIdToBeResolved == UserHandle.USER_ALL) {
            return userManagerInternal.getUserIds();
        }

        final int sourceUserId;
        if (userIdToBeResolved == UserHandle.USER_CURRENT) {
            sourceUserId = currentUserId;
        } else if (userIdToBeResolved < 0) {
            if (warningWriter != null) {
                warningWriter.print("Pseudo user ID ");
                warningWriter.print(userIdToBeResolved);
                warningWriter.println(" is not supported.");
            }
            return new int[]{};
        } else if (userManagerInternal.exists(userIdToBeResolved)) {
            sourceUserId = userIdToBeResolved;
        } else {
            if (warningWriter != null) {
                warningWriter.print("User #");
                warningWriter.print(userIdToBeResolved);
                warningWriter.println(" does not exit.");
            }
            return new int[]{};
        }
        return new int[]{sourceUserId};
    }
}
