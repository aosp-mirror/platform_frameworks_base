/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.pm.PackageManagerInternal;
import android.os.LocaleList;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.IntArray;
import android.util.Pair;
import android.util.Printer;
import android.util.Slog;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Utility class for putting and getting settings for InputMethod.
 *
 * <p>This is used in two ways:</p>
 * <ul>
 *     <li>Singleton instance in {@link InputMethodManagerService}, which is updated on
 *     user-switch to follow the current user.</li>
 *     <li>On-demand instances when we need settings for non-current users.</li>
 * </ul>
 */
final class InputMethodSettings {
    public static final boolean DEBUG = false;
    private static final String TAG = "InputMethodSettings";

    private static final int NOT_A_SUBTYPE_ID = InputMethodUtils.NOT_A_SUBTYPE_ID;
    private static final String NOT_A_SUBTYPE_ID_STR = String.valueOf(NOT_A_SUBTYPE_ID);
    private static final char INPUT_METHOD_SEPARATOR = InputMethodUtils.INPUT_METHOD_SEPARATOR;
    private static final char INPUT_METHOD_SUBTYPE_SEPARATOR =
            InputMethodUtils.INPUT_METHOD_SUBTYPE_SEPARATOR;

    private final InputMethodMap mMethodMap;
    private final List<InputMethodInfo> mMethodList;

    @UserIdInt
    private final int mUserId;

    private static void buildEnabledInputMethodsSettingString(
            StringBuilder builder, Pair<String, ArrayList<String>> ime) {
        builder.append(ime.first);
        // Inputmethod and subtypes are saved in the settings as follows:
        // ime0;subtype0;subtype1:ime1;subtype0:ime2:ime3;subtype0;subtype1
        for (int i = 0; i < ime.second.size(); ++i) {
            final String subtypeId = ime.second.get(i);
            builder.append(INPUT_METHOD_SUBTYPE_SEPARATOR).append(subtypeId);
        }
    }

    static InputMethodSettings createEmptyMap(@UserIdInt int userId) {
        return new InputMethodSettings(InputMethodMap.emptyMap(), userId);
    }

    static InputMethodSettings create(InputMethodMap methodMap, @UserIdInt int userId) {
        return new InputMethodSettings(methodMap, userId);
    }

    private InputMethodSettings(InputMethodMap methodMap, @UserIdInt int userId) {
        mMethodMap = methodMap;
        mMethodList = methodMap.values();
        mUserId = userId;
    }

    @AnyThread
    @NonNull
    InputMethodMap getMethodMap() {
        return mMethodMap;
    }

    @AnyThread
    @NonNull
    List<InputMethodInfo> getMethodList() {
        return mMethodList;
    }

    private void putString(@NonNull String key, @Nullable String str) {
        SecureSettingsWrapper.putString(key, str, mUserId);
    }

    @Nullable
    private String getString(@NonNull String key, @Nullable String defaultValue) {
        return SecureSettingsWrapper.getString(key, defaultValue, mUserId);
    }

    private void putInt(String key, int value) {
        SecureSettingsWrapper.putInt(key, value, mUserId);
    }

    private int getInt(String key, int defaultValue) {
        return SecureSettingsWrapper.getInt(key, defaultValue, mUserId);
    }

    ArrayList<InputMethodInfo> getEnabledInputMethodList() {
        return getEnabledInputMethodListWithFilter(null /* matchingCondition */);
    }

    @NonNull
    ArrayList<InputMethodInfo> getEnabledInputMethodListWithFilter(
            @Nullable Predicate<InputMethodInfo> matchingCondition) {
        return createEnabledInputMethodList(
                getEnabledInputMethodsAndSubtypeList(), matchingCondition);
    }

    List<InputMethodSubtype> getEnabledInputMethodSubtypeList(
            InputMethodInfo imi, boolean allowsImplicitlyEnabledSubtypes) {
        List<InputMethodSubtype> enabledSubtypes =
                getEnabledInputMethodSubtypeList(imi);
        if (allowsImplicitlyEnabledSubtypes && enabledSubtypes.isEmpty()) {
            enabledSubtypes = SubtypeUtils.getImplicitlyApplicableSubtypes(
                    SystemLocaleWrapper.get(mUserId), imi);
        }
        return InputMethodSubtype.sort(imi, enabledSubtypes);
    }

    List<InputMethodSubtype> getEnabledInputMethodSubtypeList(InputMethodInfo imi) {
        List<Pair<String, ArrayList<String>>> imsList =
                getEnabledInputMethodsAndSubtypeList();
        ArrayList<InputMethodSubtype> enabledSubtypes = new ArrayList<>();
        if (imi != null) {
            for (int i = 0; i < imsList.size(); ++i) {
                final Pair<String, ArrayList<String>> imsPair = imsList.get(i);
                final InputMethodInfo info = mMethodMap.get(imsPair.first);
                if (info != null && info.getId().equals(imi.getId())) {
                    final int subtypeCount = info.getSubtypeCount();
                    for (int j = 0; j < subtypeCount; ++j) {
                        final InputMethodSubtype ims = info.getSubtypeAt(j);
                        for (int k = 0; k < imsPair.second.size(); ++k) {
                            final String s = imsPair.second.get(k);
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

    List<Pair<String, ArrayList<String>>> getEnabledInputMethodsAndSubtypeList() {
        final String enabledInputMethodsStr = getEnabledInputMethodsStr();
        final TextUtils.SimpleStringSplitter inputMethodSplitter =
                new TextUtils.SimpleStringSplitter(INPUT_METHOD_SEPARATOR);
        final TextUtils.SimpleStringSplitter subtypeSplitter =
                new TextUtils.SimpleStringSplitter(INPUT_METHOD_SUBTYPE_SEPARATOR);
        final ArrayList<Pair<String, ArrayList<String>>> imsList = new ArrayList<>();
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

    /**
     * Build and put a string of EnabledInputMethods with removing specified Id.
     *
     * @return the specified id was removed or not.
     */
    boolean buildAndPutEnabledInputMethodsStrRemovingId(
            StringBuilder builder, List<Pair<String, ArrayList<String>>> imsList, String id) {
        boolean isRemoved = false;
        boolean needsAppendSeparator = false;
        for (int i = 0; i < imsList.size(); ++i) {
            final Pair<String, ArrayList<String>> ims = imsList.get(i);
            final String curId = ims.first;
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

    private ArrayList<InputMethodInfo> createEnabledInputMethodList(
            List<Pair<String, ArrayList<String>>> imsList,
            Predicate<InputMethodInfo> matchingCondition) {
        final ArrayList<InputMethodInfo> res = new ArrayList<>();
        for (int i = 0; i < imsList.size(); ++i) {
            final Pair<String, ArrayList<String>> ims = imsList.get(i);
            final InputMethodInfo info = mMethodMap.get(ims.first);
            if (info != null && !info.isVrOnly()
                    && (matchingCondition == null || matchingCondition.test(info))) {
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
    }

    @NonNull
    String getEnabledInputMethodsStr() {
        return getString(Settings.Secure.ENABLED_INPUT_METHODS, "");
    }

    private void saveSubtypeHistory(
            List<Pair<String, String>> savedImes, String newImeId, String newSubtypeId) {
        final StringBuilder builder = new StringBuilder();
        boolean isImeAdded = false;
        if (!TextUtils.isEmpty(newImeId) && !TextUtils.isEmpty(newSubtypeId)) {
            builder.append(newImeId).append(INPUT_METHOD_SUBTYPE_SEPARATOR).append(
                    newSubtypeId);
            isImeAdded = true;
        }
        for (int i = 0; i < savedImes.size(); ++i) {
            final Pair<String, String> ime = savedImes.get(i);
            final String imeId = ime.first;
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
        final List<Pair<String, String>> subtypeHistory = loadInputMethodAndSubtypeHistory();
        for (int i = 0; i < subtypeHistory.size(); ++i) {
            final Pair<String, String> ime = subtypeHistory.get(i);
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

    Pair<String, String> getLastInputMethodAndSubtype() {
        // Gets the first one from the history
        return getLastSubtypeForInputMethodInternal(null);
    }

    @Nullable
    InputMethodSubtype getLastInputMethodSubtype() {
        final Pair<String, String> lastIme = getLastInputMethodAndSubtype();
        // TODO: Handle the case of the last IME with no subtypes
        if (lastIme == null || TextUtils.isEmpty(lastIme.first)
                || TextUtils.isEmpty(lastIme.second)) {
            return null;
        }
        final InputMethodInfo lastImi = mMethodMap.get(lastIme.first);
        if (lastImi == null) return null;
        try {
            final int lastSubtypeHash = Integer.parseInt(lastIme.second);
            final int lastSubtypeId = SubtypeUtils.getSubtypeIdFromHashCode(lastImi,
                    lastSubtypeHash);
            if (lastSubtypeId < 0 || lastSubtypeId >= lastImi.getSubtypeCount()) {
                return null;
            }
            return lastImi.getSubtypeAt(lastSubtypeId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    String getLastSubtypeForInputMethod(String imeId) {
        Pair<String, String> ime = getLastSubtypeForInputMethodInternal(imeId);
        if (ime != null) {
            return ime.second;
        } else {
            return null;
        }
    }

    private Pair<String, String> getLastSubtypeForInputMethodInternal(String imeId) {
        final List<Pair<String, ArrayList<String>>> enabledImes =
                getEnabledInputMethodsAndSubtypeList();
        final List<Pair<String, String>> subtypeHistory = loadInputMethodAndSubtypeHistory();
        for (int i = 0; i < subtypeHistory.size(); ++i) {
            final Pair<String, String> imeAndSubtype = subtypeHistory.get(i);
            final String imeInTheHistory = imeAndSubtype.first;
            // If imeId is empty, returns the first IME and subtype in the history
            if (TextUtils.isEmpty(imeId) || imeInTheHistory.equals(imeId)) {
                final String subtypeInTheHistory = imeAndSubtype.second;
                final String subtypeHashCode =
                        getEnabledSubtypeHashCodeForInputMethodAndSubtype(
                                enabledImes, imeInTheHistory, subtypeInTheHistory);
                if (!TextUtils.isEmpty(subtypeHashCode)) {
                    if (DEBUG) {
                        Slog.d(TAG,
                                "Enabled subtype found in the history: " + subtypeHashCode);
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

    private String getEnabledSubtypeHashCodeForInputMethodAndSubtype(List<Pair<String,
            ArrayList<String>>> enabledImes, String imeId, String subtypeHashCode) {
        final LocaleList localeList = SystemLocaleWrapper.get(mUserId);
        for (int i = 0; i < enabledImes.size(); ++i) {
            final Pair<String, ArrayList<String>> enabledIme = enabledImes.get(i);
            if (enabledIme.first.equals(imeId)) {
                final ArrayList<String> explicitlyEnabledSubtypes = enabledIme.second;
                final InputMethodInfo imi = mMethodMap.get(imeId);
                if (explicitlyEnabledSubtypes.isEmpty()) {
                    // If there are no explicitly enabled subtypes, applicable subtypes are
                    // enabled implicitly.
                    // If IME is enabled and no subtypes are enabled, applicable subtypes
                    // are enabled implicitly, so needs to treat them to be enabled.
                    if (imi != null && imi.getSubtypeCount() > 0) {
                        List<InputMethodSubtype> implicitlyEnabledSubtypes =
                                SubtypeUtils.getImplicitlyApplicableSubtypes(localeList,
                                        imi);
                        final int numSubtypes = implicitlyEnabledSubtypes.size();
                        for (int j = 0; j < numSubtypes; ++j) {
                            final InputMethodSubtype st = implicitlyEnabledSubtypes.get(j);
                            if (String.valueOf(st.hashCode()).equals(subtypeHashCode)) {
                                return subtypeHashCode;
                            }
                        }
                    }
                } else {
                    for (int j = 0; j < explicitlyEnabledSubtypes.size(); ++j) {
                        final String s = explicitlyEnabledSubtypes.get(j);
                        if (s.equals(subtypeHashCode)) {
                            // If both imeId and subtypeId are enabled, return subtypeId.
                            try {
                                final int hashCode = Integer.parseInt(subtypeHashCode);
                                // Check whether the subtype id is valid or not
                                if (SubtypeUtils.isValidSubtypeHashCode(imi, hashCode)) {
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

    private List<Pair<String, String>> loadInputMethodAndSubtypeHistory() {
        ArrayList<Pair<String, String>> imsList = new ArrayList<>();
        final String subtypeHistoryStr = getSubtypeHistoryStr();
        if (TextUtils.isEmpty(subtypeHistoryStr)) {
            return imsList;
        }
        final TextUtils.SimpleStringSplitter inputMethodSplitter =
                new TextUtils.SimpleStringSplitter(INPUT_METHOD_SEPARATOR);
        final TextUtils.SimpleStringSplitter subtypeSplitter =
                new TextUtils.SimpleStringSplitter(INPUT_METHOD_SUBTYPE_SEPARATOR);
        inputMethodSplitter.setString(subtypeHistoryStr);
        while (inputMethodSplitter.hasNext()) {
            String nextImsStr = inputMethodSplitter.next();
            subtypeSplitter.setString(nextImsStr);
            if (subtypeSplitter.hasNext()) {
                String subtypeId = NOT_A_SUBTYPE_ID_STR;
                // The first element is ime id.
                String imeId = subtypeSplitter.next();
                while (subtypeSplitter.hasNext()) {
                    subtypeId = subtypeSplitter.next();
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
            Slog.d(TAG, "putSelectedInputMethodStr: " + imeId + ", " + mUserId);
        }
        putString(Settings.Secure.DEFAULT_INPUT_METHOD, imeId);
    }

    void putSelectedSubtype(int subtypeId) {
        if (DEBUG) {
            Slog.d(TAG, "putSelectedInputMethodSubtypeStr: " + subtypeId + ", " + mUserId);
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

    @Nullable
    String getSelectedDefaultDeviceInputMethod() {
        final String imi = getString(Settings.Secure.DEFAULT_DEVICE_INPUT_METHOD, null);
        if (DEBUG) {
            Slog.d(TAG, "getSelectedDefaultDeviceInputMethodStr: " + imi + ", " + mUserId);
        }
        return imi;
    }

    void putSelectedDefaultDeviceInputMethod(String imeId) {
        if (DEBUG) {
            Slog.d(TAG, "putSelectedDefaultDeviceInputMethodStr: " + imeId + ", " + mUserId);
        }
        putString(Settings.Secure.DEFAULT_DEVICE_INPUT_METHOD, imeId);
    }

    void putDefaultVoiceInputMethod(String imeId) {
        if (DEBUG) {
            Slog.d(TAG, "putDefaultVoiceInputMethodStr: " + imeId + ", " + mUserId);
        }
        putString(Settings.Secure.DEFAULT_VOICE_INPUT_METHOD, imeId);
    }

    @Nullable
    String getDefaultVoiceInputMethod() {
        final String imi = getString(Settings.Secure.DEFAULT_VOICE_INPUT_METHOD, null);
        if (DEBUG) {
            Slog.d(TAG, "getDefaultVoiceInputMethodStr: " + imi);
        }
        return imi;
    }

    private int getSelectedInputMethodSubtypeHashCode() {
        return getInt(Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE,
                NOT_A_SUBTYPE_ID);
    }

    @UserIdInt
    public int getUserId() {
        return mUserId;
    }

    int getSelectedInputMethodSubtypeId(String selectedImiId) {
        final InputMethodInfo imi = mMethodMap.get(selectedImiId);
        if (imi == null) {
            return NOT_A_SUBTYPE_ID;
        }
        final int subtypeHashCode = getSelectedInputMethodSubtypeHashCode();
        return SubtypeUtils.getSubtypeIdFromHashCode(imi, subtypeHashCode);
    }

    void saveCurrentInputMethodAndSubtypeToHistory(String curMethodId,
            InputMethodSubtype currentSubtype) {
        String subtypeId = NOT_A_SUBTYPE_ID_STR;
        if (currentSubtype != null) {
            subtypeId = String.valueOf(currentSubtype.hashCode());
        }
        if (InputMethodUtils.canAddToLastInputMethod(currentSubtype)) {
            addSubtypeToHistory(curMethodId, subtypeId);
        }
    }

    /**
     * A variant of {@link InputMethodManagerService#getCurrentInputMethodSubtypeLocked()} for
     * non-current users.
     *
     * <p>TODO: Address code duplication between this and
     * {@link InputMethodManagerService#getCurrentInputMethodSubtypeLocked()}.</p>
     *
     * @return {@link InputMethodSubtype} if exists. {@code null} otherwise.
     */
    @Nullable
    InputMethodSubtype getCurrentInputMethodSubtypeForNonCurrentUsers() {
        final String selectedMethodId = getSelectedInputMethod();
        if (selectedMethodId == null) {
            return null;
        }
        final InputMethodInfo imi = mMethodMap.get(selectedMethodId);
        if (imi == null || imi.getSubtypeCount() == 0) {
            return null;
        }

        final int subtypeHashCode = getSelectedInputMethodSubtypeHashCode();
        if (subtypeHashCode != NOT_A_SUBTYPE_ID) {
            final int subtypeIndex = SubtypeUtils.getSubtypeIdFromHashCode(imi,
                    subtypeHashCode);
            if (subtypeIndex >= 0) {
                return imi.getSubtypeAt(subtypeIndex);
            }
        }

        // If there are no selected subtypes, the framework will try to find the most applicable
        // subtype from explicitly or implicitly enabled subtypes.
        final List<InputMethodSubtype> explicitlyOrImplicitlyEnabledSubtypes =
                getEnabledInputMethodSubtypeList(imi, true);
        // If there is only one explicitly or implicitly enabled subtype, just returns it.
        if (explicitlyOrImplicitlyEnabledSubtypes.isEmpty()) {
            return null;
        }
        if (explicitlyOrImplicitlyEnabledSubtypes.size() == 1) {
            return explicitlyOrImplicitlyEnabledSubtypes.get(0);
        }
        final String locale = SystemLocaleWrapper.get(mUserId).get(0).toString();
        final InputMethodSubtype subtype = SubtypeUtils.findLastResortApplicableSubtype(
                explicitlyOrImplicitlyEnabledSubtypes, SubtypeUtils.SUBTYPE_MODE_KEYBOARD,
                locale, true);
        if (subtype != null) {
            return subtype;
        }
        return SubtypeUtils.findLastResortApplicableSubtype(
                explicitlyOrImplicitlyEnabledSubtypes, null, locale, true);
    }

    @NonNull
    AdditionalSubtypeMap getNewAdditionalSubtypeMap(@NonNull String imeId,
            @NonNull ArrayList<InputMethodSubtype> subtypes,
            @NonNull AdditionalSubtypeMap additionalSubtypeMap,
            @NonNull PackageManagerInternal packageManagerInternal, int callingUid) {
        final InputMethodInfo imi = mMethodMap.get(imeId);
        if (imi == null) {
            return additionalSubtypeMap;
        }
        if (!InputMethodUtils.checkIfPackageBelongsToUid(packageManagerInternal, callingUid,
                imi.getPackageName())) {
            return additionalSubtypeMap;
        }

        final AdditionalSubtypeMap newMap;
        if (subtypes.isEmpty()) {
            newMap = additionalSubtypeMap.cloneWithRemoveOrSelf(imi.getId());
        } else {
            newMap = additionalSubtypeMap.cloneWithPut(imi.getId(), subtypes);
        }
        return newMap;
    }

    boolean setEnabledInputMethodSubtypes(@NonNull String imeId,
            @NonNull int[] subtypeHashCodes) {
        final InputMethodInfo imi = mMethodMap.get(imeId);
        if (imi == null) {
            return false;
        }

        final IntArray validSubtypeHashCodes = new IntArray(subtypeHashCodes.length);
        for (int subtypeHashCode : subtypeHashCodes) {
            if (subtypeHashCode == NOT_A_SUBTYPE_ID) {
                continue;  // NOT_A_SUBTYPE_ID must not be saved
            }
            if (!SubtypeUtils.isValidSubtypeHashCode(imi, subtypeHashCode)) {
                continue;  // this subtype does not exist in InputMethodInfo.
            }
            if (validSubtypeHashCodes.indexOf(subtypeHashCode) >= 0) {
                continue;  // The entry is already added.  No need to add anymore.
            }
            validSubtypeHashCodes.add(subtypeHashCode);
        }

        final String originalEnabledImesString = getEnabledInputMethodsStr();
        final String updatedEnabledImesString = updateEnabledImeString(
                originalEnabledImesString, imi.getId(), validSubtypeHashCodes);
        if (TextUtils.equals(originalEnabledImesString, updatedEnabledImesString)) {
            return false;
        }

        putEnabledInputMethodsStr(updatedEnabledImesString);
        return true;
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    static String updateEnabledImeString(@NonNull String enabledImesString,
            @NonNull String imeId, @NonNull IntArray enabledSubtypeHashCodes) {
        final TextUtils.SimpleStringSplitter imeSplitter =
                new TextUtils.SimpleStringSplitter(INPUT_METHOD_SEPARATOR);
        final TextUtils.SimpleStringSplitter imeSubtypeSplitter =
                new TextUtils.SimpleStringSplitter(INPUT_METHOD_SUBTYPE_SEPARATOR);

        final StringBuilder sb = new StringBuilder();

        imeSplitter.setString(enabledImesString);
        boolean needsImeSeparator = false;
        while (imeSplitter.hasNext()) {
            final String nextImsStr = imeSplitter.next();
            imeSubtypeSplitter.setString(nextImsStr);
            if (imeSubtypeSplitter.hasNext()) {
                if (needsImeSeparator) {
                    sb.append(INPUT_METHOD_SEPARATOR);
                }
                if (TextUtils.equals(imeId, imeSubtypeSplitter.next())) {
                    sb.append(imeId);
                    for (int i = 0; i < enabledSubtypeHashCodes.size(); ++i) {
                        sb.append(INPUT_METHOD_SUBTYPE_SEPARATOR);
                        sb.append(enabledSubtypeHashCodes.get(i));
                    }
                } else {
                    sb.append(nextImsStr);
                }
                needsImeSeparator = true;
            }
        }
        return sb.toString();
    }

    void dump(final Printer pw, final String prefix) {
        pw.println(prefix + "mUserId=" + mUserId);
    }
}
