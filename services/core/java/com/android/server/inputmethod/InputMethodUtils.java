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
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.os.Build;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Slog;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;
import android.view.textservice.SpellCheckerInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.inputmethod.StartInputFlags;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;
import com.android.server.textservices.TextServicesManagerInternal;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.function.Consumer;

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

    // The string for enabled input method is saved as follows:
    // example: ("ime0;subtype0;subtype1;subtype2:ime1:ime2;subtype0")
    static final char INPUT_METHOD_SEPARATOR = ':';
    static final char INPUT_METHOD_SUBTYPE_SEPARATOR = ';';

    private InputMethodUtils() {
        // This utility class is not publicly instantiable.
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
            ApplicationInfo ai;
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

    /**
     * Returns true if a package name belongs to a UID.
     *
     * <p>This is a simple wrapper of
     * {@link PackageManagerInternal#getPackageUid(String, long, int)}.</p>
     * @param packageManagerInternal the {@link PackageManagerInternal} object to be used for the
     *                               validation.
     * @param uid the UID to be validated.
     * @param packageName the package name.
     * @return {@code true} if the package name belongs to the UID.
     */
    static boolean checkIfPackageBelongsToUid(PackageManagerInternal packageManagerInternal,
            int uid, String packageName) {
        // PackageManagerInternal#getPackageUid() doesn't check MATCH_INSTANT/MATCH_APEX as of
        // writing. So setting 0 should be fine.
        return packageManagerInternal.isSameApp(packageName, /* flags= */ 0, uid,
            UserHandle.getUserId(uid));
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

    /**
     * Returns a list of enabled IME IDs to address Bug 261723412.
     *
     * <p>This is a temporary workaround until we come up with a better solution. Do not use this
     * for anything other than Bug 261723412.</p>
     *
     * @param context {@link Context} object to query secure settings.
     * @param userId User ID to query about.
     * @return A list of enabled IME IDs.
     */
    @NonNull
    static List<String> getEnabledInputMethodIdsForFiltering(@NonNull Context context,
            @UserIdInt int userId) {
        final String enabledInputMethodsStr = TextUtils.nullIfEmpty(
                SecureSettingsWrapper.getString(Settings.Secure.ENABLED_INPUT_METHODS, null,
                        userId));
        final ArrayList<String> result = new ArrayList<>();
        splitEnabledImeStr(enabledInputMethodsStr, result::add);
        return result;
    }

    /**
     * Split enabled IME string ({@link Settings.Secure#ENABLED_INPUT_METHODS}) into IME IDs.
     *
     * @param text a text formatted with {@link Settings.Secure#ENABLED_INPUT_METHODS}.
     * @param consumer {@link Consumer} called back when a new IME ID is found.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    static void splitEnabledImeStr(@Nullable String text, @NonNull Consumer<String> consumer) {
        if (TextUtils.isEmpty(text)) {
            return;
        }
        final TextUtils.SimpleStringSplitter inputMethodSplitter =
                new TextUtils.SimpleStringSplitter(INPUT_METHOD_SEPARATOR);
        final TextUtils.SimpleStringSplitter subtypeSplitter =
                new TextUtils.SimpleStringSplitter(INPUT_METHOD_SUBTYPE_SEPARATOR);
        inputMethodSplitter.setString(text);
        while (inputMethodSplitter.hasNext()) {
            String nextImsStr = inputMethodSplitter.next();
            subtypeSplitter.setString(nextImsStr);
            if (subtypeSplitter.hasNext()) {
                // The first element is ime id.
                consumer.accept(subtypeSplitter.next());
            }
        }
    }

    /**
     * Concat given IME IDs with an existing enabled IME
     * ({@link Settings.Secure#ENABLED_INPUT_METHODS}).
     *
     * @param existingEnabledImeId an existing {@link Settings.Secure#ENABLED_INPUT_METHODS} to
     *                             which {@code imeIDs} will be added.
     * @param imeIds an array of IME IDs to be added. For IME IDs that are already seen in
     *               {@code existingEnabledImeId} will be skipped.
     * @return a new enabled IME ID string that can be stored in
     *         {@link Settings.Secure#ENABLED_INPUT_METHODS}.
     */
    @NonNull
    static String concatEnabledImeIds(@NonNull String existingEnabledImeId,
            @NonNull String... imeIds) {
        final ArraySet<String> alreadyEnabledIds = new ArraySet<>();
        final StringJoiner joiner = new StringJoiner(Character.toString(INPUT_METHOD_SEPARATOR));
        if (!TextUtils.isEmpty(existingEnabledImeId)) {
            splitEnabledImeStr(existingEnabledImeId, alreadyEnabledIds::add);
            joiner.add(existingEnabledImeId);
        }
        for (String id : imeIds) {
            if (!alreadyEnabledIds.contains(id)) {
                joiner.add(id);
                alreadyEnabledIds.add(id);
            }
        }
        return joiner.toString();
    }

    /**
     * Convert the input method ID to a component name
     *
     * @param id A unique ID for this input method.
     * @return The component name of the input method.
     * @see InputMethodInfo#computeId(ResolveInfo)
     */
    @Nullable
    public static ComponentName convertIdToComponentName(@NonNull String id) {
        return ComponentName.unflattenFromString(id);
    }
}
