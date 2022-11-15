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
package com.android.server.pm;

import static android.content.pm.UserInfo.NO_PROFILE_GROUP_ID;
import static android.os.UserHandle.USER_NULL;
import static android.os.UserHandle.USER_SYSTEM;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.server.pm.UserManagerInternal.USER_ASSIGNMENT_RESULT_FAILURE;
import static com.android.server.pm.UserManagerInternal.USER_ASSIGNMENT_RESULT_SUCCESS_INVISIBLE;
import static com.android.server.pm.UserManagerInternal.USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE;
import static com.android.server.pm.UserManagerInternal.userAssignmentResultToString;

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Dumpable;
import android.util.IndentingPrintWriter;
import android.util.SparseIntArray;
import android.view.Display;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.server.pm.UserManagerInternal.UserAssignmentResult;
import com.android.server.utils.Slogf;

import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Class responsible for deciding whether a user is visible (or visible for a given display).
 *
 * <p>This class is thread safe.
 */
// TODO(b/244644281): improve javadoc (for example, explain all cases / modes)
public final class UserVisibilityMediator implements Dumpable {

    private static final boolean DBG = false; // DO NOT SUBMIT WITH TRUE

    private static final String TAG = UserVisibilityMediator.class.getSimpleName();

    // TODO(b/242195409): might need to change this if boot logic is refactored for HSUM devices
    @VisibleForTesting
    static final int INITIAL_CURRENT_USER_ID = USER_SYSTEM;

    private final Object mLock = new Object();

    private final boolean mUsersOnSecondaryDisplaysEnabled;

    @UserIdInt
    @GuardedBy("mLock")
    private int mCurrentUserId = INITIAL_CURRENT_USER_ID;

    @Nullable
    @GuardedBy("mLock")
    private final SparseIntArray mUsersOnSecondaryDisplays = new SparseIntArray();

    /**
     * Mapping from each started user to its profile group.
     */
    @GuardedBy("mLock")
    private final SparseIntArray mStartedProfileGroupIds = new SparseIntArray();

    UserVisibilityMediator() {
        this(UserManager.isUsersOnSecondaryDisplaysEnabled());
    }

    @VisibleForTesting
    UserVisibilityMediator(boolean usersOnSecondaryDisplaysEnabled) {
        mUsersOnSecondaryDisplaysEnabled = usersOnSecondaryDisplaysEnabled;
    }

    /**
     * See {@link UserManagerInternal#assignUserToDisplayOnStart(int, int, boolean, int)}.
     */
    public @UserAssignmentResult int startUser(@UserIdInt int userId, @UserIdInt int profileGroupId,
            boolean foreground, int displayId) {
        // TODO(b/244644281): this method need to perform 4 actions:
        //
        // 1. Check if the user can be started given the provided arguments
        // 2. If it can, decide whether it's visible or not (which is the return value)
        // 3. Update the current user / profiles state
        // 4. Update the users on secondary display state (if applicable)
        //
        // Ideally, they should be done "atomically" (i.e, only changing state while holding the
        // mLock), but the initial implementation is just calling the existing methods, as the
        // focus is to change the UserController startUser() workflow (so it relies on this class
        // for the logic above).
        //
        // The next CL will refactor it (and the unit tests) to achieve that atomicity.
        int result = startOnly(userId, profileGroupId, foreground, displayId);
        if (result != USER_ASSIGNMENT_RESULT_FAILURE) {
            assignUserToDisplay(userId, profileGroupId, displayId);
        }
        return result;
    }

    /**
     * @deprecated - see comment inside {@link #startUser(int, int, boolean, int)}
     */
    @Deprecated
    @VisibleForTesting
    @UserAssignmentResult int startOnly(@UserIdInt int userId,
            @UserIdInt int profileGroupId, boolean foreground, int displayId) {
        int actualProfileGroupId = profileGroupId == NO_PROFILE_GROUP_ID
                ? userId
                : profileGroupId;
        if (DBG) {
            Slogf.d(TAG, "startUser(%d, %d, %b, %d): actualProfileGroupId=%d",
                    userId, profileGroupId, foreground, displayId, actualProfileGroupId);
        }
        if (foreground && displayId != DEFAULT_DISPLAY) {
            Slogf.w(TAG, "startUser(%d, %d, %b, %d) failed: cannot start foreground user on "
                    + "secondary display", userId, actualProfileGroupId, foreground, displayId);
            return USER_ASSIGNMENT_RESULT_FAILURE;
        }

        int visibility;
        synchronized (mLock) {
            if (isProfile(userId, actualProfileGroupId)) {
                if (displayId != DEFAULT_DISPLAY) {
                    Slogf.w(TAG, "startUser(%d, %d, %b, %d) failed: cannot start profile user on "
                            + "secondary display", userId, actualProfileGroupId, foreground,
                            displayId);
                    return USER_ASSIGNMENT_RESULT_FAILURE;
                }
                if (foreground) {
                    Slogf.w(TAG, "startUser(%d, %d, %b, %d) failed: cannot start profile user in "
                            + "foreground", userId, actualProfileGroupId, foreground, displayId);
                    return USER_ASSIGNMENT_RESULT_FAILURE;
                } else {
                    boolean isParentRunning = mStartedProfileGroupIds
                            .get(actualProfileGroupId) == actualProfileGroupId;
                    if (DBG) {
                        Slogf.d(TAG, "profile parent running: %b", isParentRunning);
                    }
                    visibility = isParentRunning
                            ? USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE
                            : USER_ASSIGNMENT_RESULT_SUCCESS_INVISIBLE;
                }
            } else if (foreground) {
                mCurrentUserId = userId;
                visibility = USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE;
            } else {
                visibility = USER_ASSIGNMENT_RESULT_SUCCESS_INVISIBLE;
            }
            if (DBG) {
                Slogf.d(TAG, "adding user / profile mapping (%d -> %d) and returning %s",
                        userId, actualProfileGroupId, userAssignmentResultToString(visibility));
            }
            mStartedProfileGroupIds.put(userId, actualProfileGroupId);
        }
        return visibility;
    }

    /**
     * @deprecated - see comment inside {@link #startUser(int, int, boolean, int)}
     */
    @Deprecated
    @VisibleForTesting
    void assignUserToDisplay(int userId, int profileGroupId, int displayId) {
        if (DBG) {
            Slogf.d(TAG, "assignUserToDisplay(%d, %d): mUsersOnSecondaryDisplaysEnabled=%b",
                    userId, displayId, mUsersOnSecondaryDisplaysEnabled);
        }

        if (displayId == DEFAULT_DISPLAY
                && (!mUsersOnSecondaryDisplaysEnabled || !isProfile(userId, profileGroupId))) {
            // Don't need to do anything because methods (such as isUserVisible()) already
            // know that the current user (and their profiles) is assigned to the default display.
            // But on MUMD devices, it profiles are only supported in the default display, so it
            // cannot return yet as it needs to check if the parent is also assigned to the
            // DEFAULT_DISPLAY (this is done indirectly below when it checks that the profile parent
            // is the current user, as the current user is always assigned to the DEFAULT_DISPLAY).
            if (DBG) {
                Slogf.d(TAG, "ignoring on default display");
            }
            return;
        }

        if (!mUsersOnSecondaryDisplaysEnabled) {
            throw new UnsupportedOperationException("assignUserToDisplay(" + userId + ", "
                    + displayId + ") called on device that doesn't support multiple "
                    + "users on multiple displays");
        }

        Preconditions.checkArgument(userId != UserHandle.USER_SYSTEM, "Cannot assign system "
                + "user to secondary display (%d)", displayId);
        Preconditions.checkArgument(displayId != Display.INVALID_DISPLAY,
                "Cannot assign to INVALID_DISPLAY (%d)", displayId);

        int currentUserId = getCurrentUserId();
        Preconditions.checkArgument(userId != currentUserId,
                "Cannot assign current user (%d) to other displays", currentUserId);

        if (isProfile(userId, profileGroupId)) {
            // Profile can only start in the same display as parent. And for simplicity,
            // that display must be the DEFAULT_DISPLAY.
            Preconditions.checkArgument(displayId == Display.DEFAULT_DISPLAY,
                    "Profile user can only be started in the default display");
            int parentUserId = getStartedProfileGroupId(userId);
            Preconditions.checkArgument(parentUserId == currentUserId,
                    "Only profile of current user can be assigned to a display");
            if (DBG) {
                Slogf.d(TAG, "Ignoring profile user %d on default display", userId);
            }
            return;
        }

        synchronized (mLock) {
            // Check if display is available
            for (int i = 0; i < mUsersOnSecondaryDisplays.size(); i++) {
                int assignedUserId = mUsersOnSecondaryDisplays.keyAt(i);
                int assignedDisplayId = mUsersOnSecondaryDisplays.valueAt(i);
                if (DBG) {
                    Slogf.d(TAG, "%d: assignedUserId=%d, assignedDisplayId=%d",
                            i, assignedUserId, assignedDisplayId);
                }
                if (displayId == assignedDisplayId) {
                    throw new IllegalStateException("Cannot assign user " + userId + " to "
                            + "display " + displayId + " because such display is already "
                            + "assigned to user " + assignedUserId);
                }
                if (userId == assignedUserId) {
                    throw new IllegalStateException("Cannot assign user " + userId + " to "
                            + "display " + displayId + " because such user is as already "
                            + "assigned to display " + assignedDisplayId);
                }
            }

            if (DBG) {
                Slogf.d(TAG, "Adding full user %d -> display %d", userId, displayId);
            }
            mUsersOnSecondaryDisplays.put(userId, displayId);
        }
    }

    /**
     * See {@link UserManagerInternal#unassignUserFromDisplayOnStop(int)}.
     */
    public void stopUser(int userId) {
        if (DBG) {
            Slogf.d(TAG, "stopUser(%d)", userId);
        }
        synchronized (mLock) {
            if (DBG) {
                Slogf.d(TAG, "Removing %d from mStartedProfileGroupIds (%s)", userId,
                        mStartedProfileGroupIds);
            }
            mStartedProfileGroupIds.delete(userId);

            if (!mUsersOnSecondaryDisplaysEnabled) {
                // Don't need to do update mUsersOnSecondaryDisplays because methods (such as
                // isUserVisible()) already know that the current user (and their profiles) is
                // assigned to the default display.
                return;
            }
            if (DBG) {
                Slogf.d(TAG, "Removing %d from mUsersOnSecondaryDisplays (%s)", userId,
                        mUsersOnSecondaryDisplays);
            }
            mUsersOnSecondaryDisplays.delete(userId);
        }
    }

    /**
     * See {@link UserManagerInternal#isUserVisible(int)}.
     */
    public boolean isUserVisible(int userId) {
        // First check current foreground user and their profiles (on main display)
        if (isCurrentUserOrRunningProfileOfCurrentUser(userId)) {
            return true;
        }

        // Device doesn't support multiple users on multiple displays, so only users checked above
        // can be visible
        if (!mUsersOnSecondaryDisplaysEnabled) {
            return false;
        }

        synchronized (mLock) {
            return mUsersOnSecondaryDisplays.indexOfKey(userId) >= 0;
        }
    }

    /**
     * See {@link UserManagerInternal#isUserVisible(int, int)}.
     */
    public boolean isUserVisible(int userId, int displayId) {
        if (displayId == Display.INVALID_DISPLAY) {
            return false;
        }
        if (!mUsersOnSecondaryDisplaysEnabled) {
            return isCurrentUserOrRunningProfileOfCurrentUser(userId);
        }

        // TODO(b/244644281): temporary workaround to let WM use this API without breaking current
        // behavior - return true for current user / profile for any display (other than those
        // explicitly assigned to another users), otherwise they wouldn't be able to launch
        // activities on other non-passenger displays, like cluster, display, or virtual displays).
        // In the long-term, it should rely just on mUsersOnSecondaryDisplays, which
        // would be updated by DisplayManagerService when displays are created / initialized.
        if (isCurrentUserOrRunningProfileOfCurrentUser(userId)) {
            synchronized (mLock) {
                boolean assignedToUser = false;
                boolean assignedToAnotherUser = false;
                for (int i = 0; i < mUsersOnSecondaryDisplays.size(); i++) {
                    if (mUsersOnSecondaryDisplays.valueAt(i) == displayId) {
                        if (mUsersOnSecondaryDisplays.keyAt(i) == userId) {
                            assignedToUser = true;
                            break;
                        } else {
                            assignedToAnotherUser = true;
                            // Cannot break because it could be assigned to a profile of the user
                            // (and we better not assume that the iteration will check for the
                            // parent user before its profiles)
                        }
                    }
                }
                if (DBG) {
                    Slogf.d(TAG, "isUserVisibleOnDisplay(%d, %d): assignedToUser=%b, "
                            + "assignedToAnotherUser=%b, mUsersOnSecondaryDisplays=%s",
                            userId, displayId, assignedToUser, assignedToAnotherUser,
                            mUsersOnSecondaryDisplays);
                }
                return assignedToUser || !assignedToAnotherUser;
            }
        }

        synchronized (mLock) {
            return mUsersOnSecondaryDisplays.get(userId, Display.INVALID_DISPLAY) == displayId;
        }
    }

    /**
     * See {@link UserManagerInternal#getDisplayAssignedToUser(int)}.
     */
    public int getDisplayAssignedToUser(int userId) {
        if (isCurrentUserOrRunningProfileOfCurrentUser(userId)) {
            return Display.DEFAULT_DISPLAY;
        }

        if (!mUsersOnSecondaryDisplaysEnabled) {
            return Display.INVALID_DISPLAY;
        }

        synchronized (mLock) {
            return mUsersOnSecondaryDisplays.get(userId, Display.INVALID_DISPLAY);
        }
    }

    /**
     * See {@link UserManagerInternal#getUserAssignedToDisplay(int)}.
     */
    public int getUserAssignedToDisplay(int displayId) {
        if (displayId == Display.DEFAULT_DISPLAY || !mUsersOnSecondaryDisplaysEnabled) {
            return getCurrentUserId();
        }

        synchronized (mLock) {
            for (int i = 0; i < mUsersOnSecondaryDisplays.size(); i++) {
                if (mUsersOnSecondaryDisplays.valueAt(i) != displayId) {
                    continue;
                }
                int userId = mUsersOnSecondaryDisplays.keyAt(i);
                if (!isStartedProfile(userId)) {
                    return userId;
                } else if (DBG) {
                    Slogf.d(TAG, "getUserAssignedToDisplay(%d): skipping user %d because it's "
                            + "a profile", displayId, userId);
                }
            }
        }

        int currentUserId = getCurrentUserId();
        if (DBG) {
            Slogf.d(TAG, "getUserAssignedToDisplay(%d): no user assigned to display, returning "
                    + "current user (%d) instead", displayId, currentUserId);
        }
        return currentUserId;
    }

    private void dump(IndentingPrintWriter ipw) {
        ipw.println("UserVisibilityMediator");
        ipw.increaseIndent();

        synchronized (mLock) {
            ipw.print("Current user id: ");
            ipw.println(mCurrentUserId);

            dumpIntArray(ipw, mStartedProfileGroupIds, "started user / profile group", "u", "pg");

            ipw.print("Supports background users on secondary displays: ");
            ipw.println(mUsersOnSecondaryDisplaysEnabled);

            if (mUsersOnSecondaryDisplaysEnabled) {
                dumpIntArray(ipw, mUsersOnSecondaryDisplays, "background user / secondary display",
                        "u", "d");
            }
        }

        ipw.decreaseIndent();
    }

    private static void dumpIntArray(IndentingPrintWriter ipw, SparseIntArray array,
            String arrayDescription, String keyName, String valueName) {
        ipw.print("Number of ");
        ipw.print(arrayDescription);
        ipw.print(" mappings: ");
        ipw.println(array.size());
        if (array.size() <= 0) {
            return;
        }
        ipw.increaseIndent();
        for (int i = 0; i < array.size(); i++) {
            ipw.print(keyName); ipw.print(':');
            ipw.print(array.keyAt(i));
            ipw.print(" -> ");
            ipw.print(valueName); ipw.print(':');
            ipw.println(array.valueAt(i));
        }
        ipw.decreaseIndent();
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        if (pw instanceof IndentingPrintWriter) {
            dump((IndentingPrintWriter) pw);
            return;
        }
        dump(new IndentingPrintWriter(pw));
    }

    @VisibleForTesting
    Map<Integer, Integer> getUsersOnSecondaryDisplays() {
        Map<Integer, Integer> map;
        synchronized (mLock) {
            int size = mUsersOnSecondaryDisplays.size();
            map = new LinkedHashMap<>(size);
            for (int i = 0; i < size; i++) {
                map.put(mUsersOnSecondaryDisplays.keyAt(i), mUsersOnSecondaryDisplays.valueAt(i));
            }
        }
        Slogf.v(TAG, "getUsersOnSecondaryDisplays(): returning %s", map);
        return map;
    }

    // TODO(b/244644281): methods below are needed because some APIs use the current users (full and
    // profiles) state to decide whether a user is visible or not. If we decide to always store that
    // info into intermediate maps, we should remove them.

    @VisibleForTesting
    @UserIdInt int getCurrentUserId() {
        synchronized (mLock) {
            return mCurrentUserId;
        }
    }

    @VisibleForTesting
    boolean isCurrentUserOrRunningProfileOfCurrentUser(@UserIdInt int userId) {
        synchronized (mLock) {
            // Special case as NO_PROFILE_GROUP_ID == USER_NULL
            if (userId == USER_NULL || mCurrentUserId == USER_NULL) {
                return false;
            }
            if (mCurrentUserId == userId) {
                return true;
            }
            return mStartedProfileGroupIds.get(userId, NO_PROFILE_GROUP_ID) == mCurrentUserId;
        }
    }

    private static boolean isProfile(@UserIdInt int userId, @UserIdInt int profileGroupId) {
        return profileGroupId != NO_PROFILE_GROUP_ID && profileGroupId != userId;
    }

    @VisibleForTesting
    boolean isStartedUser(@UserIdInt int userId) {
        synchronized (mLock) {
            return mStartedProfileGroupIds.get(userId,
                    INITIAL_CURRENT_USER_ID) != INITIAL_CURRENT_USER_ID;
        }
    }

    @VisibleForTesting
    boolean isStartedProfile(@UserIdInt int userId) {
        int profileGroupId;
        synchronized (mLock) {
            profileGroupId = mStartedProfileGroupIds.get(userId, NO_PROFILE_GROUP_ID);
        }
        return isProfile(userId, profileGroupId);
    }

    @VisibleForTesting
    @UserIdInt int getStartedProfileGroupId(@UserIdInt int userId) {
        synchronized (mLock) {
            return mStartedProfileGroupIds.get(userId, NO_PROFILE_GROUP_ID);
        }
    }
}
