/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.os;

/**
 * @hide
 */
public final class UserId {
    /**
     * Range of IDs allocated for a user.
     *
     * @hide
     */
    public static final int PER_USER_RANGE = 100000;

    public static final int USER_ALL = -1;

    /**
     * Enable multi-user related side effects. Set this to false if there are problems with single
     * user usecases.
     * */
    public static final boolean MU_ENABLED = true;

    /**
     * Checks to see if the user id is the same for the two uids, i.e., they belong to the same
     * user.
     * @hide
     */
    public static final boolean isSameUser(int uid1, int uid2) {
        return getUserId(uid1) == getUserId(uid2);
    }

    /**
     * Checks to see if both uids are referring to the same app id, ignoring the user id part of the
     * uids.
     * @param uid1 uid to compare
     * @param uid2 other uid to compare
     * @return whether the appId is the same for both uids
     * @hide
     */
    public static final boolean isSameApp(int uid1, int uid2) {
        return getAppId(uid1) == getAppId(uid2);
    }

    public static final boolean isIsolated(int uid) {
        uid = getAppId(uid);
        return uid >= Process.FIRST_ISOLATED_UID && uid <= Process.LAST_ISOLATED_UID;
    }

    public static boolean isApp(int uid) {
        if (uid > 0) {
            uid = UserId.getAppId(uid);
            return uid >= Process.FIRST_APPLICATION_UID && uid <= Process.LAST_APPLICATION_UID;
        } else {
            return false;
        }
    }

    /**
     * Returns the user id for a given uid.
     * @hide
     */
    public static final int getUserId(int uid) {
        if (MU_ENABLED) {
            return uid / PER_USER_RANGE;
        } else {
            return 0;
        }
    }

    public static final int getCallingUserId() {
        return getUserId(Binder.getCallingUid());
    }

    /**
     * Returns the uid that is composed from the userId and the appId.
     * @hide
     */
    public static final int getUid(int userId, int appId) {
        if (MU_ENABLED) {
            return userId * PER_USER_RANGE + (appId % PER_USER_RANGE);
        } else {
            return appId;
        }
    }

    /**
     * Returns the app id (or base uid) for a given uid, stripping out the user id from it.
     * @hide
     */
    public static final int getAppId(int uid) {
        return uid % PER_USER_RANGE;
    }

    /**
     * Returns the user id of the current process
     * @return user id of the current process
     */
    public static final int myUserId() {
        return getUserId(Process.myUid());
    }
}
