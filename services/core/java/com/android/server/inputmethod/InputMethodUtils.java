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
import android.annotation.UserHandleAware;
import android.annotation.UserIdInt;
import android.app.AppOpsManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.UserHandle;
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

import com.android.internal.inputmethod.StartInputFlags;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;
import com.android.server.textservices.TextServicesManagerInternal;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

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
    private static final String TAG = "InputMethodUtils";
    private static final String NOT_A_SUBTYPE_ID_STR = String.valueOf(NOT_A_SUBTYPE_ID);

    // The string for enabled input method is saved as follows:
    // example: ("ime0;subtype0;subtype1;subtype2:ime1:ime2;subtype0")
    private static final char INPUT_METHOD_SEPARATOR = ':';
    private static final char INPUT_METHOD_SUBTYPE_SEPARATOR = ';';

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

    static boolean canAddToLastInputMethod(InputMethodSubtype subtype) {
        if (subtype == null) return true;
        return !subtype.isAuxiliary();
    }

    @UserHandleAware
    static void setNonSelectedSystemImesDisabledUntilUsed(PackageManager packageManagerForUser,
            List<InputMethodInfo> enabledImis) {
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
                TextServicesManagerInternal.get().getCurrentSpellCheckerForUser(
                        packageManagerForUser.getUserId());
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
                ai = packageManagerForUser.getApplicationInfo(packageName,
                        PackageManager.ApplicationInfoFlags.of(
                                PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS));
            } catch (PackageManager.NameNotFoundException e) {
                // This is not an error.  No need to show scary error messages.
                if (DEBUG) {
                    Slog.d(TAG, packageName
                            + " does not exist for userId=" + packageManagerForUser.getUserId());
                }
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
            setDisabledUntilUsed(packageManagerForUser, packageName);
        }
    }

    private static void setDisabledUntilUsed(PackageManager packageManagerForUser,
            String packageName) {
        final int state;
        try {
            state = packageManagerForUser.getApplicationEnabledSetting(packageName);
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "getApplicationEnabledSetting failed. packageName=" + packageName
                    + " userId=" + packageManagerForUser.getUserId(), e);
            return;
        }
        if (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                || state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            if (DEBUG) {
                Slog.d(TAG, "Update state(" + packageName + "): DISABLED_UNTIL_USED");
            }
            try {
                packageManagerForUser.setApplicationEnabledSetting(packageName,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
                        0 /* newState */);
            } catch (IllegalArgumentException e) {
                Slog.w(TAG, "setApplicationEnabledSetting failed. packageName=" + packageName
                        + " userId=" + packageManagerForUser.getUserId(), e);
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
     * TODO(b/235661780): Make the setting supports multi-users.
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
            return getEnabledInputMethodListWithFilterLocked(null /* matchingCondition */);
        }

        @NonNull
        ArrayList<InputMethodInfo> getEnabledInputMethodListWithFilterLocked(
                @Nullable Predicate<InputMethodInfo> matchingCondition) {
            return createEnabledInputMethodListLocked(
                    getEnabledInputMethodsAndSubtypeListLocked(), matchingCondition);
        }

        List<InputMethodSubtype> getEnabledInputMethodSubtypeListLocked(
                Context context, InputMethodInfo imi, boolean allowsImplicitlySelectedSubtypes) {
            List<InputMethodSubtype> enabledSubtypes =
                    getEnabledInputMethodSubtypeListLocked(imi);
            if (allowsImplicitlySelectedSubtypes && enabledSubtypes.isEmpty()) {
                enabledSubtypes = SubtypeUtils.getImplicitlyApplicableSubtypesLocked(
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
                List<Pair<String, ArrayList<String>>> imsList,
                Predicate<InputMethodInfo> matchingCondition) {
            final ArrayList<InputMethodInfo> res = new ArrayList<>();
            for (Pair<String, ArrayList<String>> ims: imsList) {
                InputMethodInfo info = mMethodMap.get(ims.first);
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
                                    SubtypeUtils.getImplicitlyApplicableSubtypesLocked(mRes, imi);
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
                                    if (SubtypeUtils.isValidSubtypeId(imi, hashCode)) {
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

        void putDefaultVoiceInputMethod(String imeId) {
            if (DEBUG) {
                Slog.d(TAG, "putDefaultVoiceInputMethodStr: " + imeId + ", " + mCurrentUserId);
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
            return SubtypeUtils.getSubtypeIdFromHashCode(imi, subtypeHashCode);
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
