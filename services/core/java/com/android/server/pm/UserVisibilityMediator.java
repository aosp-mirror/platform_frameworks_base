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
import static android.view.Display.INVALID_DISPLAY;

import static com.android.server.pm.UserManagerInternal.USER_ASSIGNMENT_RESULT_FAILURE;
import static com.android.server.pm.UserManagerInternal.USER_ASSIGNMENT_RESULT_SUCCESS_ALREADY_VISIBLE;
import static com.android.server.pm.UserManagerInternal.USER_ASSIGNMENT_RESULT_SUCCESS_INVISIBLE;
import static com.android.server.pm.UserManagerInternal.USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE;
import static com.android.server.pm.UserManagerInternal.USER_START_MODE_BACKGROUND;
import static com.android.server.pm.UserManagerInternal.USER_START_MODE_BACKGROUND_VISIBLE;
import static com.android.server.pm.UserManagerInternal.USER_START_MODE_FOREGROUND;
import static com.android.server.pm.UserManagerInternal.userAssignmentResultToString;
import static com.android.server.pm.UserManagerInternal.userStartModeToString;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.DebugUtils;
import android.util.Dumpable;
import android.util.EventLog;
import android.util.IndentingPrintWriter;
import android.util.IntArray;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.Display;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.server.am.EventLogTags;
import com.android.server.pm.UserManagerInternal.UserAssignmentResult;
import com.android.server.pm.UserManagerInternal.UserStartMode;
import com.android.server.pm.UserManagerInternal.UserVisibilityListener;
import com.android.server.utils.Slogf;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Class responsible for deciding whether a user is visible (or visible for a given display).
 *
 * <p>Currently, it has 3 "modes" (set on constructor), which defines the class behavior (i.e, the
 * logic that dictates the result of methods such as {@link #isUserVisible(int)} and
 * {@link #isUserVisible(int, int)}):
 *
 * <ul>
 *   <li>default (A.K.A {@code SUSD} - Single User on Single Display): this is the most common mode
 *   (used by phones, tablets, foldables, cars with just cluster and driver displays, etc.),
 *   where just the current foreground user and its profiles are visible; hence, most methods are
 *   optimized to just check for the current user / profile. This mode is unit tested by
 *   {@link com.android.server.pm.UserVisibilityMediatorSUSDTest} and CTS tested by
 *   {@link android.multiuser.cts.UserVisibilityTest}.
 *   <li>concurrent users (A.K.A. {@code MUMD} - Multiple Users on Multiple Displays): typically
 *   used on automotive builds where the car has additional displays for passengers, it allows users
 *   to be started in the background but visible on these displays; hence, it contains additional
 *   maps to account for the visibility state. This mode is unit tested by
 *   {@link com.android.server.pm.UserVisibilityMediatorMUMDTest} and CTS tested by
 *   {@link android.multiuser.cts.UserVisibilityTest}.
 *   <li>no driver (A.K.A. {@code MUPAND} - MUltiple PAssengers, No Driver): extension of the
 *   previous mode and typically used on automotive builds where the car has additional displays for
 *   passengers but uses a secondary Android system for the back passengers, so all "human" users
 *   are started in the background (and the current foreground user is the system user), hence the
 *   "no driver name". This mode is unit tested by
 *   {@link com.android.server.pm.UserVisibilityMediatorMUPANDTest} and CTS tested by
 *   {@link android.multiuser.cts.UserVisibilityVisibleBackgroundUsersOnDefaultDisplayTest}.
 * </ul>
 *
 * <p>When you make changes in this class, you should run at least the 3 unit tests and
 * {@link android.multiuser.cts.UserVisibilityTest} (which actually applies for all modes); for
 * example, by calling {@code atest UserVisibilityMediatorSUSDTest UserVisibilityMediatorMUMDTest
 * UserVisibilityMediatorMUPANDTest UserVisibilityTest}. Ideally, you should run the other 2 CTS
 * tests as well (you can emulate these modes using {@code adb} commands; their javadoc provides
 * instructions on how to do so).
 *
 * <p>This class is thread safe.
 */
public final class UserVisibilityMediator implements Dumpable {

    private static final String TAG = UserVisibilityMediator.class.getSimpleName();

    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private static final boolean VERBOSE = false; // DO NOT SUBMIT WITH TRUE

    private static final String PREFIX_SECONDARY_DISPLAY_MAPPING = "SECONDARY_DISPLAY_MAPPING_";
    public static final int SECONDARY_DISPLAY_MAPPING_NEEDED = 1;
    public static final int SECONDARY_DISPLAY_MAPPING_NOT_NEEDED = 2;
    public static final int SECONDARY_DISPLAY_MAPPING_FAILED = -1;

    /**
     * Whether a user / display assignment requires adding an entry to the
     * {@code mUsersOnSecondaryDisplays} map.
     */
    @IntDef(flag = false, prefix = {PREFIX_SECONDARY_DISPLAY_MAPPING}, value = {
            SECONDARY_DISPLAY_MAPPING_NEEDED,
            SECONDARY_DISPLAY_MAPPING_NOT_NEEDED,
            SECONDARY_DISPLAY_MAPPING_FAILED
    })
    public @interface SecondaryDisplayMappingStatus {}

    /**
     * ProfileGroupId representing always-visible users (e.g. a communal profile).
     * This is an implementation detail of this class only; it may have nothing to do with the
     * actual user's profile group id in UserManagerService.
     */
    public static final int ALWAYS_VISIBLE_PROFILE_GROUP_ID = UserHandle.USER_ALL;

    // TODO(b/266158156): might need to change this if boot logic is refactored for HSUM devices
    @VisibleForTesting
    static final int INITIAL_CURRENT_USER_ID = USER_SYSTEM;

    private final Object mLock = new Object();

    private final boolean mVisibleBackgroundUsersEnabled;
    private final boolean mVisibleBackgroundUserOnDefaultDisplayEnabled;

    @UserIdInt
    @GuardedBy("mLock")
    private int mCurrentUserId = INITIAL_CURRENT_USER_ID;

    /**
     * Map of background users started visible on displays (key is user id, value is display id).
     *
     * <p>Only set when {@code mVisibleBackgroundUsersEnabled} is {@code true}.
     */
    @Nullable
    @GuardedBy("mLock")
    private final SparseIntArray mUsersAssignedToDisplayOnStart;

    /**
     * Map of extra (i.e., not assigned on start, but by explicit calls to
     * {@link #assignUserToExtraDisplay(int, int)}) displays assigned to user (key is display id,
     * value is user id).
     *
     * <p>Only set when {@code mVisibleBackgroundUsersEnabled} is {@code true}.
     */
    @Nullable
    @GuardedBy("mLock")
    private final SparseIntArray mExtraDisplaysAssignedToUsers;

    /**
     * Mapping of each user that started visible (key) to its profile group id (value).
     *
     * <p>It's used to determine not just if the user is visible, but also
     * {@link #isProfile(int, int) if it's a profile}.
     *
     * <p>Note that these profile group ids might not be identical to those used in
     * UserManagerService, as different conventions are used (such as how to treat users with no
     * profiles, or how to treat the communal profile).
     */
    @GuardedBy("mLock")
    private final SparseIntArray mStartedVisibleProfileGroupIds = new SparseIntArray();

    /**
     * List of profiles that have explicitly started invisible.
     *
     * <p>Only used for debugging purposes (and set when {@link #DBG} is {@code true}), hence we
     * don't care about autoboxing.
     */
    @GuardedBy("mLock")
    @Nullable
    private final List<Integer> mStartedInvisibleProfileUserIds;

    /**
     * Handler user to call listeners
     */
    private final Handler mHandler;

    // @GuardedBy("mLock") - hold lock for writes, no lock necessary for simple reads
    final CopyOnWriteArrayList<UserVisibilityListener> mListeners =
            new CopyOnWriteArrayList<>();

    UserVisibilityMediator(Handler handler) {
        this(UserManager.isVisibleBackgroundUsersEnabled(),
                UserManager.isVisibleBackgroundUsersOnDefaultDisplayEnabled(), handler);
    }

    @VisibleForTesting
    UserVisibilityMediator(boolean visibleBackgroundUsersOnDisplaysEnabled,
            boolean visibleBackgroundUserOnDefaultDisplayEnabled, Handler handler) {
        mVisibleBackgroundUsersEnabled = visibleBackgroundUsersOnDisplaysEnabled;
        if (visibleBackgroundUserOnDefaultDisplayEnabled
                && !visibleBackgroundUsersOnDisplaysEnabled) {
            throw new IllegalArgumentException("Cannot have "
                    + "visibleBackgroundUserOnDefaultDisplayEnabled without "
                    + "visibleBackgroundUsersOnDisplaysEnabled");
        }
        mVisibleBackgroundUserOnDefaultDisplayEnabled =
                visibleBackgroundUserOnDefaultDisplayEnabled;
        if (mVisibleBackgroundUsersEnabled) {
            mUsersAssignedToDisplayOnStart = new SparseIntArray();
            mExtraDisplaysAssignedToUsers = new SparseIntArray();
        } else {
            mUsersAssignedToDisplayOnStart = null;
            mExtraDisplaysAssignedToUsers = null;
        }
        mStartedInvisibleProfileUserIds = DBG ? new ArrayList<>(4) : null;
        mHandler = handler;
        // TODO(b/266158156): might need to change this if boot logic is refactored for HSUM devices
        mStartedVisibleProfileGroupIds.put(INITIAL_CURRENT_USER_ID, INITIAL_CURRENT_USER_ID);

        if (DBG) {
            Slogf.i(TAG, "UserVisibilityMediator created with DBG on");
        }
    }

    /**
     * See {@link UserManagerInternal#assignUserToDisplayOnStart(int, int, int, int)}.
     */
    public @UserAssignmentResult int assignUserToDisplayOnStart(@UserIdInt int userId,
            @UserIdInt int unResolvedProfileGroupId, @UserStartMode int userStartMode,
            int displayId, boolean isAlwaysVisible) {
        Preconditions.checkArgument(!isSpecialUserId(userId), "user id cannot be generic: %d",
                userId);
        validateUserStartMode(userStartMode);

        // This method needs to perform 4 actions:
        //
        // 1. Check if the user can be started given the provided arguments
        // 2. If it can, decide whether it's visible or not (which is the return value)
        // 3. Update the current user / profiles state
        // 4. Update the users on secondary display state (if applicable)
        //
        // Notice that steps 3 and 4 should be done atomically (i.e., while holding mLock), so the
        // previous steps are delegated to other methods (canAssignUserToDisplayLocked() and
        // getUserVisibilityOnStartLocked() respectively).


        int profileGroupId
                = resolveProfileGroupId(userId, unResolvedProfileGroupId, isAlwaysVisible);
        if (DBG) {
            Slogf.d(TAG, "assignUserToDisplayOnStart(%d, %d, %s, %d): actualProfileGroupId=%d",
                    userId, unResolvedProfileGroupId, userStartModeToString(userStartMode),
                    displayId, profileGroupId);
        }

        int result;
        IntArray visibleUsersBefore, visibleUsersAfter;
        synchronized (mLock) {
            result = getUserVisibilityOnStartLocked(userId, profileGroupId, userStartMode,
                    displayId);
            if (DBG) {
                Slogf.d(TAG, "result of getUserVisibilityOnStartLocked(%s)",
                        userAssignmentResultToString(result));
            }
            if (result == USER_ASSIGNMENT_RESULT_FAILURE
                    || result == USER_ASSIGNMENT_RESULT_SUCCESS_ALREADY_VISIBLE) {
                return result;
            }

            int mappingResult = canAssignUserToDisplayLocked(userId, profileGroupId, userStartMode,
                    displayId);
            if (DBG) {
                Slogf.d(TAG, "mapping result: %s",
                        secondaryDisplayMappingStatusToString(mappingResult));
            }
            if (mappingResult == SECONDARY_DISPLAY_MAPPING_FAILED) {
                return USER_ASSIGNMENT_RESULT_FAILURE;
            }

            visibleUsersBefore = getVisibleUsers();

            // Set current user / started users state
            switch (userStartMode) {
                case USER_START_MODE_FOREGROUND:
                    mCurrentUserId = userId;
                    // Fallthrough
                case USER_START_MODE_BACKGROUND_VISIBLE:
                    if (DBG) {
                        Slogf.d(TAG, "adding visible user / profile group id mapping (%d -> %d)",
                                userId, profileGroupId);
                    }
                    mStartedVisibleProfileGroupIds.put(userId, profileGroupId);
                    break;
                case USER_START_MODE_BACKGROUND:
                    if (mStartedInvisibleProfileUserIds != null
                            && isProfile(userId, profileGroupId)) {
                        Slogf.d(TAG, "adding user %d to list of invisible profiles", userId);
                        mStartedInvisibleProfileUserIds.add(userId);
                    }
                    break;
                default:
                    Slogf.wtf(TAG,  "invalid userStartMode passed to assignUserToDisplayOnStart: "
                            + "%d", userStartMode);
            }

            //  Set user / display state
            switch (mappingResult) {
                case SECONDARY_DISPLAY_MAPPING_NEEDED:
                    if (DBG) {
                        Slogf.d(TAG, "adding user / display mapping (%d -> %d)", userId, displayId);
                    }
                    mUsersAssignedToDisplayOnStart.put(userId, displayId);
                    break;
                case SECONDARY_DISPLAY_MAPPING_NOT_NEEDED:
                    if (DBG) {
                        // Don't need to do set state because methods (such as isUserVisible())
                        // already know that the current user (and their profiles) is assigned to
                        // the default display.
                        Slogf.d(TAG, "don't need to update mUsersOnSecondaryDisplays");
                    }
                    break;
                default:
                    Slogf.wtf(TAG,  "invalid resut from canAssignUserToDisplayLocked: %d",
                            mappingResult);
            }

            visibleUsersAfter = getVisibleUsers();
        }

        dispatchVisibilityChanged(visibleUsersBefore, visibleUsersAfter);

        if (DBG) {
            Slogf.d(TAG, "returning %s", userAssignmentResultToString(result));
        }

        return result;
    }

    private int resolveProfileGroupId(
            @UserIdInt int userId, @UserIdInt int unResolvedProfileGroupId,
            boolean isAlwaysVisible) {

        if (isAlwaysVisible) {
            return ALWAYS_VISIBLE_PROFILE_GROUP_ID;
        }
        return unResolvedProfileGroupId == NO_PROFILE_GROUP_ID
                ? userId
                : unResolvedProfileGroupId;
    }

    @GuardedBy("mLock")
    @UserAssignmentResult
    private int getUserVisibilityOnStartLocked(@UserIdInt int userId, @UserIdInt int profileGroupId,
            @UserStartMode int userStartMode, int displayId) {

        // Check for invalid combinations first
        if (userStartMode == USER_START_MODE_BACKGROUND && displayId != DEFAULT_DISPLAY) {
            Slogf.wtf(TAG, "cannot start user (%d) as BACKGROUND_USER on secondary display (%d) "
                    + "(it should be BACKGROUND_USER_VISIBLE", userId, displayId);
            return USER_ASSIGNMENT_RESULT_FAILURE;
        }

        boolean visibleBackground = userStartMode == USER_START_MODE_BACKGROUND_VISIBLE;
        if (displayId == DEFAULT_DISPLAY && visibleBackground) {
            if (mVisibleBackgroundUserOnDefaultDisplayEnabled && isCurrentUserLocked(userId)) {
                // Shouldn't happen - UserController returns before calling this method
                Slogf.wtf(TAG, "trying to start current user (%d) visible in background on default"
                        + " display", userId);
                return USER_ASSIGNMENT_RESULT_SUCCESS_ALREADY_VISIBLE;

            }
            if (!mVisibleBackgroundUserOnDefaultDisplayEnabled
                    && !isProfile(userId, profileGroupId)) {
                Slogf.wtf(TAG, "cannot start full user (%d) visible on default display", userId);
                return USER_ASSIGNMENT_RESULT_FAILURE;
            }
        }

        boolean foreground = userStartMode == USER_START_MODE_FOREGROUND;
        if (displayId != DEFAULT_DISPLAY) {
            if (foreground) {
                Slogf.w(TAG, "getUserVisibilityOnStartLocked(%d, %d, %s, %d) failed: cannot start "
                        + "foreground user on secondary display", userId, profileGroupId,
                        userStartModeToString(userStartMode), displayId);
                return USER_ASSIGNMENT_RESULT_FAILURE;
            }
            if (!mVisibleBackgroundUsersEnabled) {
                Slogf.w(TAG, "getUserVisibilityOnStartLocked(%d, %d, %s, %d) failed: called on "
                        + "device that doesn't support multiple users on multiple displays",
                        userId, profileGroupId, userStartModeToString(userStartMode), displayId);
                return USER_ASSIGNMENT_RESULT_FAILURE;
            }
        }

        if (isProfile(userId, profileGroupId)) {
            if (displayId != DEFAULT_DISPLAY) {
                Slogf.w(TAG, "canStartUserLocked(%d, %d, %s, %d) failed: cannot start profile user "
                        + "on secondary display", userId, profileGroupId,
                        userStartModeToString(userStartMode), displayId);
                return USER_ASSIGNMENT_RESULT_FAILURE;
            }
            switch (userStartMode) {
                case USER_START_MODE_FOREGROUND:
                    Slogf.w(TAG, "startUser(%d, %d, %s, %d) failed: cannot start profile user in "
                            + "foreground", userId, profileGroupId,
                            userStartModeToString(userStartMode), displayId);
                    return USER_ASSIGNMENT_RESULT_FAILURE;
                case USER_START_MODE_BACKGROUND_VISIBLE:
                    if (!isParentVisibleOnDisplay(profileGroupId, displayId)) {
                        Slogf.w(TAG, "getUserVisibilityOnStartLocked(%d, %d, %s, %d) failed: cannot"
                                + " start profile user visible when its parent is not visible in "
                                + "that display", userId, profileGroupId,
                                userStartModeToString(userStartMode), displayId);
                        return USER_ASSIGNMENT_RESULT_FAILURE;
                    }
                    return USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE;
                case USER_START_MODE_BACKGROUND:
                    return USER_ASSIGNMENT_RESULT_SUCCESS_INVISIBLE;
            }
        } else if (mUsersAssignedToDisplayOnStart != null
                && isUserAssignedToDisplayOnStartLocked(userId, displayId)) {
            if (DBG) {
                Slogf.d(TAG, "full user %d is already visible on display %d", userId, displayId);
            }
            return USER_ASSIGNMENT_RESULT_SUCCESS_ALREADY_VISIBLE;
        }

        return foreground || displayId != DEFAULT_DISPLAY
                || (visibleBackground && mVisibleBackgroundUserOnDefaultDisplayEnabled)
                        ? USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE
                        : USER_ASSIGNMENT_RESULT_SUCCESS_INVISIBLE;
    }

    @GuardedBy("mLock")
    @SecondaryDisplayMappingStatus
    private int canAssignUserToDisplayLocked(@UserIdInt int userId,
            @UserIdInt int profileGroupId, @UserStartMode int userStartMode, int displayId) {
        if (displayId == DEFAULT_DISPLAY) {
            boolean mappingNeeded = false;
            if (mVisibleBackgroundUserOnDefaultDisplayEnabled
                    && userStartMode == USER_START_MODE_BACKGROUND_VISIBLE) {
                int userStartedOnDefaultDisplay = getUserStartedOnDisplay(DEFAULT_DISPLAY);
                if (userStartedOnDefaultDisplay != USER_NULL
                        && userStartedOnDefaultDisplay != profileGroupId) {
                    Slogf.w(TAG, "canAssignUserToDisplayLocked(): cannot start user %d visible on"
                            + " default display because user %d already did so", userId,
                            userStartedOnDefaultDisplay);
                    return SECONDARY_DISPLAY_MAPPING_FAILED;
                }
                mappingNeeded = true;
            }
            if (!mappingNeeded && mVisibleBackgroundUsersEnabled
                    && isProfile(userId, profileGroupId)) {
                mappingNeeded = true;
            }

            if (!mappingNeeded) {
                // Don't need to do anything because methods (such as isUserVisible()) already
                // know that the current user (and its profiles) is assigned to the default display.
                // But on MUMD devices, profiles are only supported in the default display, so it
                // cannot return yet as it needs to check if the parent is also assigned to the
                // DEFAULT_DISPLAY (this is done indirectly below when it checks that the profile
                // parent is the current user, as the current user is always assigned to the
                // DEFAULT_DISPLAY).
                if (DBG) {
                    Slogf.d(TAG, "Ignoring mapping for default display for user %d starting as %s",
                            userId, userStartModeToString(userStartMode));
                }
                return SECONDARY_DISPLAY_MAPPING_NOT_NEEDED;
            }
        }

        if (userId == UserHandle.USER_SYSTEM) {
            Slogf.w(TAG, "Cannot assign system user to secondary display (%d)", displayId);
            return SECONDARY_DISPLAY_MAPPING_FAILED;
        }
        if (displayId == Display.INVALID_DISPLAY) {
            Slogf.w(TAG, "Cannot assign to INVALID_DISPLAY (%d)", displayId);
            return SECONDARY_DISPLAY_MAPPING_FAILED;
        }
        if (userId == mCurrentUserId) {
            Slogf.w(TAG, "Cannot assign current user (%d) to other displays", userId);
            return SECONDARY_DISPLAY_MAPPING_FAILED;
        }

        if (isProfile(userId, profileGroupId)) {
            // Profile can only start in the same display as parent. And for simplicity,
            // that display must be the DEFAULT_DISPLAY.
            if (displayId != Display.DEFAULT_DISPLAY) {
                Slogf.w(TAG, "Profile user can only be started in the default display");
                return SECONDARY_DISPLAY_MAPPING_FAILED;

            }
            if (DBG) {
                Slogf.d(TAG, "Don't need to map profile user %d to default display", userId);
            }
            return SECONDARY_DISPLAY_MAPPING_NOT_NEEDED;
        }

        if (mUsersAssignedToDisplayOnStart == null) {
            // Should never have reached this point
            Slogf.wtf(TAG, "canAssignUserToDisplayLocked(%d, %d, %d, %d) is trying to check "
                    + "mUsersAssignedToDisplayOnStart when it's not set",
                    userId, profileGroupId, userStartMode, displayId);
            return SECONDARY_DISPLAY_MAPPING_FAILED;
        }

        // Check if display is available and user is not assigned to any display
        for (int i = 0; i < mUsersAssignedToDisplayOnStart.size(); i++) {
            int assignedUserId = mUsersAssignedToDisplayOnStart.keyAt(i);
            int assignedDisplayId = mUsersAssignedToDisplayOnStart.valueAt(i);
            if (DBG) {
                Slogf.d(TAG, "%d: assignedUserId=%d, assignedDisplayId=%d",
                        i, assignedUserId, assignedDisplayId);
            }
            if (displayId == assignedDisplayId) {
                Slogf.w(TAG, "Cannot assign user %d to display %d because such display is already "
                        + "assigned to user %d", userId, displayId, assignedUserId);
                return SECONDARY_DISPLAY_MAPPING_FAILED;
            }
            if (userId == assignedUserId) {
                Slogf.w(TAG, "Cannot assign user %d to display %d because such user is as already "
                        + "assigned to display %d", userId, displayId, assignedUserId);
                return SECONDARY_DISPLAY_MAPPING_FAILED;
            }
        }
        return SECONDARY_DISPLAY_MAPPING_NEEDED;
    }

    /**
     * See {@link UserManagerInternal#assignUserToExtraDisplay(int, int)}.
     */
    public boolean assignUserToExtraDisplay(@UserIdInt int userId, int displayId) {
        if (DBG) {
            Slogf.d(TAG, "assignUserToExtraDisplay(%d, %d)", userId, displayId);
        }
        if (!mVisibleBackgroundUsersEnabled) {
            Slogf.w(TAG, "assignUserToExtraDisplay(%d, %d): called when not supported", userId,
                    displayId);
            return false;
        }
        if (displayId == INVALID_DISPLAY) {
            Slogf.w(TAG, "assignUserToExtraDisplay(%d, %d): called with INVALID_DISPLAY", userId,
                    displayId);
            return false;
        }
        if (displayId == DEFAULT_DISPLAY) {
            Slogf.w(TAG, "assignUserToExtraDisplay(%d, %d): DEFAULT_DISPLAY is automatically "
                    + "assigned to current user", userId, displayId);
            return false;
        }

        synchronized (mLock) {
            if (!isUserVisible(userId)) {
                Slogf.w(TAG, "assignUserToExtraDisplay(%d, %d): failed because user is not visible",
                        userId, displayId);
                return false;
            }
            if (isStartedVisibleProfileLocked(userId)) {
                Slogf.w(TAG, "assignUserToExtraDisplay(%d, %d): failed because user is a profile",
                        userId, displayId);
                return false;
            }

            if (mExtraDisplaysAssignedToUsers.get(displayId, USER_NULL) == userId) {
                Slogf.w(TAG, "assignUserToExtraDisplay(%d, %d): failed because user is already "
                        + "assigned to that display", userId, displayId);
                return false;
            }

            // First check if the user is assigned to a display
            int userAssignedToDisplay = getUserStartedOnDisplay(displayId);
            if (userAssignedToDisplay != USER_NULL) {
                Slogf.w(TAG, "assignUserToExtraDisplay(%d, %d): failed because display was assigned"
                        + " to user %d on start", userId, displayId, userAssignedToDisplay);
                return false;
            }
            // Then if the display was assigned before
            userAssignedToDisplay = mExtraDisplaysAssignedToUsers.get(displayId, USER_NULL);
            if (userAssignedToDisplay != USER_NULL) {
                Slogf.w(TAG, "assignUserToExtraDisplay(%d, %d): failed because user %d was already "
                        + "assigned to extra display", userId, displayId, userAssignedToDisplay);
                return false;
            }
            if (DBG) {
                Slogf.d(TAG, "addding %d -> %d to mExtraDisplaysAssignedToUsers", displayId,
                        userId);
            }
            mExtraDisplaysAssignedToUsers.put(displayId, userId);
        }
        return true;
    }

    /**
     * See {@link UserManagerInternal#unassignUserFromExtraDisplay(int, int)}.
     */
    public boolean unassignUserFromExtraDisplay(@UserIdInt int userId, int displayId) {
        if (DBG) {
            Slogf.d(TAG, "unassignUserFromExtraDisplay(%d, %d)", userId, displayId);
        }
        if (!mVisibleBackgroundUsersEnabled) {
            Slogf.w(TAG, "unassignUserFromExtraDisplay(%d, %d): called when not supported",
                    userId, displayId);
            return false;
        }
        synchronized (mLock) {
            int assignedUserId = mExtraDisplaysAssignedToUsers.get(displayId, USER_NULL);
            if (assignedUserId == USER_NULL) {
                Slogf.w(TAG, "unassignUserFromExtraDisplay(%d, %d): not assigned to any user",
                        userId, displayId);
                return false;
            }
            if (assignedUserId != userId) {
                Slogf.w(TAG, "unassignUserFromExtraDisplay(%d, %d): was assigned to user %d",
                        userId, displayId, assignedUserId);
                return false;
            }
            if (DBG) {
                Slogf.d(TAG, "removing %d from map", displayId);
            }
            mExtraDisplaysAssignedToUsers.delete(displayId);
        }
        return true;
    }

    /**
     * See {@link UserManagerInternal#unassignUserFromDisplayOnStop(int)}.
     */
    public void unassignUserFromDisplayOnStop(@UserIdInt int userId) {
        if (DBG) {
            Slogf.d(TAG, "unassignUserFromDisplayOnStop(%d)", userId);
        }
        IntArray visibleUsersBefore, visibleUsersAfter;
        synchronized (mLock) {
            visibleUsersBefore = getVisibleUsers();

            unassignUserFromAllDisplaysOnStopLocked(userId);

            visibleUsersAfter = getVisibleUsers();
        }
        dispatchVisibilityChanged(visibleUsersBefore, visibleUsersAfter);
    }

    @GuardedBy("mLock")
    private void unassignUserFromAllDisplaysOnStopLocked(@UserIdInt int userId) {
        if (DBG) {
            Slogf.d(TAG, "Removing %d from mStartedVisibleProfileGroupIds (%s)", userId,
                    mStartedVisibleProfileGroupIds);
        }
        mStartedVisibleProfileGroupIds.delete(userId);
        if (mStartedInvisibleProfileUserIds != null) {
            Slogf.d(TAG, "Removing %d from list of invisible profiles", userId);
            mStartedInvisibleProfileUserIds.remove(Integer.valueOf(userId));
        }

        if (!mVisibleBackgroundUsersEnabled) {
            // Don't need to update mUsersAssignedToDisplayOnStart because methods (such as
            // isUserVisible()) already know that the current user (and their profiles) is
            // assigned to the default display.
            return;
        }
        if (DBG) {
            Slogf.d(TAG, "Removing user %d from mUsersOnDisplaysMap (%s)", userId,
                    mUsersAssignedToDisplayOnStart);
        }
        mUsersAssignedToDisplayOnStart.delete(userId);

        // Remove extra displays as well
        for (int i = mExtraDisplaysAssignedToUsers.size() - 1; i >= 0; i--) {
            if (mExtraDisplaysAssignedToUsers.valueAt(i) == userId) {
                if (DBG) {
                    Slogf.d(TAG, "Removing display %d from mExtraDisplaysAssignedToUsers (%s)",
                            mExtraDisplaysAssignedToUsers.keyAt(i), mExtraDisplaysAssignedToUsers);
                }
                mExtraDisplaysAssignedToUsers.removeAt(i);
            }
        }
    }

    /**
     * See {@link UserManagerInternal#isUserVisible(int)}.
     */
    public boolean isUserVisible(@UserIdInt int userId) {
        // For optimization (as most devices don't support visible background users), check for
        // current foreground user and their profiles first
        if (isCurrentUserOrRunningProfileOfCurrentUser(userId)) {
            if (VERBOSE) {
                Slogf.v(TAG, "isUserVisible(%d): true to current user or profile", userId);
            }
            return true;
        }

        if (!mVisibleBackgroundUsersEnabled) {
            if (VERBOSE) {
                Slogf.v(TAG, "isUserVisible(%d): false for non-current user (or its profiles) when"
                        + " device doesn't support visible background users", userId);
            }
            return false;
        }


        synchronized (mLock) {
            int profileGroupId;
            synchronized (mLock) {
                profileGroupId = mStartedVisibleProfileGroupIds.get(userId, NO_PROFILE_GROUP_ID);
            }
            if (isProfile(userId, profileGroupId)) {
                return isUserAssignedToDisplayOnStartLocked(profileGroupId);
            }
            return isUserAssignedToDisplayOnStartLocked(userId);
        }
    }

    @GuardedBy("mLock")
    private boolean isUserAssignedToDisplayOnStartLocked(@UserIdInt int userId) {
        boolean visible = mUsersAssignedToDisplayOnStart.indexOfKey(userId) >= 0;
        if (VERBOSE) {
            Slogf.v(TAG, "isUserAssignedToDisplayOnStartLocked(%d): %b", userId, visible);
        }
        return visible;
    }

    @GuardedBy("mLock")
    private boolean isUserAssignedToDisplayOnStartLocked(@UserIdInt int userId, int displayId) {
        if (mUsersAssignedToDisplayOnStart == null) {
            // Shouldn't have been called in this case
            Slogf.wtf(TAG, "isUserAssignedToDisplayOnStartLocked(%d, %d): called when "
                    + "mUsersAssignedToDisplayOnStart is null", userId, displayId);
            return false;
        }
        boolean isIt = displayId != INVALID_DISPLAY
                && mUsersAssignedToDisplayOnStart.get(userId, INVALID_DISPLAY) == displayId;
        if (VERBOSE) {
            Slogf.v(TAG, "isUserAssignedToDisplayOnStartLocked(%d, %d): %b", userId, displayId,
                    isIt);
        }
        return isIt;
    }

    /**
     * Returns whether the given profileGroupId - i.e. the user (for a non-profile), or its parent
     * (for a profile) - is visible on the given display.
     */
    private boolean isParentVisibleOnDisplay(@UserIdInt int profileGroupId, int displayId) {
        if (profileGroupId == ALWAYS_VISIBLE_PROFILE_GROUP_ID) {
            return true;
        }
        // The profileGroupId is the user (for a non-profile) or its parent (for a profile),
        // so query whether it is visible.
        return isUserVisible(profileGroupId, displayId);
    }

    /**
     * See {@link UserManagerInternal#isUserVisible(int, int)}.
     */
    public boolean isUserVisible(@UserIdInt int userId, int displayId) {
        if (displayId == INVALID_DISPLAY) {
            return false;
        }

        // For optimization (as most devices don't support visible background users), check for
        // current user and profile first. Current user is always visible on:
        // - Default display
        // - Secondary displays when device doesn't support visible bg users
        //   - Or when explicitly added (which is checked below)
        if (isCurrentUserOrRunningProfileOfCurrentUser(userId)
                && (displayId == DEFAULT_DISPLAY || !mVisibleBackgroundUsersEnabled)) {
            if (VERBOSE) {
                Slogf.v(TAG, "isUserVisible(%d, %d): returning true for current user/profile",
                        userId, displayId);
            }
            return true;
        }

        if (!mVisibleBackgroundUsersEnabled) {
            if (DBG) {
                Slogf.d(TAG, "isUserVisible(%d, %d): returning false as device does not support"
                        + " visible background users", userId, displayId);
            }
            return false;
        }

        synchronized (mLock) {
            int profileGroupId;
            synchronized (mLock) {
                profileGroupId = mStartedVisibleProfileGroupIds.get(userId, NO_PROFILE_GROUP_ID);
            }
            if (isProfile(userId, profileGroupId)) {
                return isFullUserVisibleOnBackgroundLocked(profileGroupId, displayId);
            }
            return isFullUserVisibleOnBackgroundLocked(userId, displayId);
        }
    }

    // NOTE: it doesn't check if the userId is a full user, it's up to the caller to check that
    @GuardedBy("mLock")
    private boolean isFullUserVisibleOnBackgroundLocked(@UserIdInt int userId, int displayId) {
        if (mUsersAssignedToDisplayOnStart.get(userId, Display.INVALID_DISPLAY) == displayId) {
            // User assigned to display on start
            return true;
        }
        // Check for extra display assignment
        return mExtraDisplaysAssignedToUsers.get(displayId, USER_NULL) == userId;
    }

    /**
     * See {@link UserManagerInternal#getMainDisplayAssignedToUser(int)}.
     */
    public int getMainDisplayAssignedToUser(@UserIdInt int userId) {
        if (isCurrentUserOrRunningProfileOfCurrentUser(userId)) {
            if (mVisibleBackgroundUserOnDefaultDisplayEnabled) {
                // When device supports visible bg users on default display, the default display is
                // assigned to the current user, unless a user is started visible on it
                int userStartedOnDefaultDisplay;
                synchronized (mLock) {
                    userStartedOnDefaultDisplay = getUserStartedOnDisplay(DEFAULT_DISPLAY);
                }
                if (userStartedOnDefaultDisplay != USER_NULL) {
                    if (DBG) {
                        Slogf.d(TAG, "getMainDisplayAssignedToUser(%d): returning INVALID_DISPLAY "
                                        + "for current user user %d was started on DEFAULT_DISPLAY",
                                userId, userStartedOnDefaultDisplay);
                    }
                    return INVALID_DISPLAY;
                }
            }
            return DEFAULT_DISPLAY;
        }

        if (!mVisibleBackgroundUsersEnabled) {
            return INVALID_DISPLAY;
        }

        synchronized (mLock) {
            return mUsersAssignedToDisplayOnStart.get(userId, INVALID_DISPLAY);
        }
    }

    /** See {@link UserManagerInternal#getDisplaysAssignedToUser(int)}. */
    @Nullable
    public int[] getDisplaysAssignedToUser(@UserIdInt int userId) {
        int mainDisplayId = getMainDisplayAssignedToUser(userId);
        if (mainDisplayId == INVALID_DISPLAY) {
            // The user will not have any extra displays if they have no main display.
            // Return null if no display is assigned to the user.
            if (DBG) {
                Slogf.d(TAG, "getDisplaysAssignedToUser(): returning null"
                        + " because there is no display assigned to user %d", userId);
            }
            return null;
        }

        synchronized (mLock) {
            if (mExtraDisplaysAssignedToUsers == null
                    || mExtraDisplaysAssignedToUsers.size() == 0) {
                return new int[]{mainDisplayId};
            }

            int count = 0;
            int[] displayIds = new int[mExtraDisplaysAssignedToUsers.size() + 1];
            displayIds[count++] = mainDisplayId;
            for (int i = 0; i < mExtraDisplaysAssignedToUsers.size(); ++i) {
                if (mExtraDisplaysAssignedToUsers.valueAt(i) == userId) {
                    displayIds[count++] = mExtraDisplaysAssignedToUsers.keyAt(i);
                }
            }
            // Return the array if the array length happens to be correct.
            if (displayIds.length == count) {
                return displayIds;
            }

            // Copy the results to a new array with the exact length. The size of displayIds[] is
            // initialized to `1 + mExtraDisplaysAssignedToUsers.size()`, which is usually larger
            // than the actual length, because mExtraDisplaysAssignedToUsers contains displayIds for
            // other users. Therefore, we need to copy to a new array with the correct length.
            int[] results = new int[count];
            System.arraycopy(displayIds, 0, results, 0, count);
            return results;
        }
    }

    /**
     * See {@link UserManagerInternal#getUserAssignedToDisplay(int)}.
     */
    public @UserIdInt int getUserAssignedToDisplay(@UserIdInt int displayId) {
        return getUserAssignedToDisplay(displayId, /* returnCurrentUserByDefault= */ true);
    }

    /**
     * Gets the user explicitly assigned to a display.
     */
    private @UserIdInt int getUserStartedOnDisplay(@UserIdInt int displayId) {
        return getUserAssignedToDisplay(displayId, /* returnCurrentUserByDefault= */ false);
    }

    /**
     * Gets the user explicitly assigned to a display, or the current user when no user is assigned
     * to it (and {@code returnCurrentUserByDefault} is {@code true}).
     */
    private @UserIdInt int getUserAssignedToDisplay(@UserIdInt int displayId,
            boolean returnCurrentUserByDefault) {
        if (returnCurrentUserByDefault
                && ((displayId == DEFAULT_DISPLAY && !mVisibleBackgroundUserOnDefaultDisplayEnabled
                || !mVisibleBackgroundUsersEnabled))) {
            return getCurrentUserId();
        }

        synchronized (mLock) {
            for (int i = 0; i < mUsersAssignedToDisplayOnStart.size(); i++) {
                if (mUsersAssignedToDisplayOnStart.valueAt(i) != displayId) {
                    continue;
                }
                int userId = mUsersAssignedToDisplayOnStart.keyAt(i);
                if (!isStartedVisibleProfileLocked(userId)) {
                    return userId;
                } else if (DBG) {
                    Slogf.d(TAG,
                            "getUserAssignedToDisplay(%d): skipping user %d because it's a profile",
                            displayId, userId);
                }
            }
            int userAssignedToExtraDisplay = mExtraDisplaysAssignedToUsers.get(displayId,
                    USER_NULL);
            if (userAssignedToExtraDisplay != USER_NULL) {
                return userAssignedToExtraDisplay;
            }
        }
        if (!returnCurrentUserByDefault) {
            if (DBG) {
                Slogf.d(TAG, "getUserAssignedToDisplay(%d): no user assigned to display, returning "
                        + "USER_NULL instead", displayId);
            }
            return USER_NULL;
        }

        int currentUserId = getCurrentUserId();
        if (DBG) {
            Slogf.d(TAG, "getUserAssignedToDisplay(%d): no user assigned to display, returning "
                    + "current user (%d) instead", displayId, currentUserId);
        }
        return currentUserId;
    }

    /**
     * Gets the ids of the visible users.
     */
    public IntArray getVisibleUsers() {
        // TODO(b/258054362): this method's performance is O(n2), as it interacts through all users
        // here, then again on isUserVisible(). We could "fix" it to be O(n), but given that the
        // number of users is too small, the gain is probably not worth the increase on complexity.
        IntArray visibleUsers = new IntArray();
        synchronized (mLock) {
            for (int i = 0; i < mStartedVisibleProfileGroupIds.size(); i++) {
                int userId = mStartedVisibleProfileGroupIds.keyAt(i);
                if (isUserVisible(userId)) {
                    visibleUsers.add(userId);
                }
            }
        }
        return visibleUsers;
    }

    /**
     * Adds a {@link UserVisibilityListener listener}.
     */
    public void addListener(UserVisibilityListener listener) {
        if (DBG) {
            Slogf.d(TAG, "adding listener %s", listener);
        }
        synchronized (mLock) {
            mListeners.add(listener);
        }
    }

    /**
     * Removes a {@link UserVisibilityListener listener}.
     */
    public void removeListener(UserVisibilityListener listener) {
        if (DBG) {
            Slogf.d(TAG, "removing listener %s", listener);
        }
        synchronized (mLock) {
            mListeners.remove(listener);
        }
    }

    // TODO(b/266158156): remove this method if not needed anymore
    /**
     * Nofify all listeners that the system user visibility changed.
     */
    void onSystemUserVisibilityChanged(boolean visible) {
        dispatchVisibilityChanged(mListeners, USER_SYSTEM, visible);
    }

    /**
     * Nofify all listeners about the visibility changes from before / after a change of state.
     */
    private void dispatchVisibilityChanged(IntArray visibleUsersBefore,
            IntArray visibleUsersAfter) {
        if (visibleUsersBefore == null) {
            // Optimization - it's only null when listeners is empty
            if (DBG) {
                Slogf.d(TAG,  "dispatchVisibilityChanged(): ignoring, no listeners");
            }
            return;
        }
        CopyOnWriteArrayList<UserVisibilityListener> listeners = mListeners;
        if (DBG) {
            Slogf.d(TAG,
                    "dispatchVisibilityChanged(): visibleUsersBefore=%s, visibleUsersAfter=%s, "
                    + "%d listeners (%s)", visibleUsersBefore, visibleUsersAfter, listeners.size(),
                    listeners);
        }
        for (int i = 0; i < visibleUsersBefore.size(); i++) {
            int userId = visibleUsersBefore.get(i);
            if (visibleUsersAfter.indexOf(userId) == -1) {
                dispatchVisibilityChanged(listeners, userId, /* visible= */ false);
            }
        }
        for (int i = 0; i < visibleUsersAfter.size(); i++) {
            int userId = visibleUsersAfter.get(i);
            if (visibleUsersBefore.indexOf(userId) == -1) {
                dispatchVisibilityChanged(listeners, userId, /* visible= */ true);
            }
        }
    }

    private void dispatchVisibilityChanged(CopyOnWriteArrayList<UserVisibilityListener> listeners,
            @UserIdInt int userId, boolean visible) {
        EventLog.writeEvent(EventLogTags.UM_USER_VISIBILITY_CHANGED, userId, visible ? 1 : 0);
        if (DBG) {
            Slogf.d(TAG, "dispatchVisibilityChanged(%d -> %b): sending to %d listeners",
                    userId, visible, listeners.size());
        }
        for (int i = 0; i < mListeners.size(); i++) {
            UserVisibilityListener listener =  mListeners.get(i);
            if (VERBOSE) {
                Slogf.v(TAG, "dispatchVisibilityChanged(%d -> %b): sending to %s",
                        userId, visible, listener);
            }
            mHandler.post(() -> listener.onUserVisibilityChanged(userId, visible));
        }
    }

    private void dump(IndentingPrintWriter ipw) {
        ipw.println("UserVisibilityMediator");
        ipw.increaseIndent();

        ipw.print("DBG: ");
        ipw.println(DBG);

        synchronized (mLock) {
            ipw.print("Current user id: ");
            ipw.println(mCurrentUserId);

            ipw.print("Visible users: ");
            ipw.println(getVisibleUsers());

            dumpSparseIntArray(ipw, mStartedVisibleProfileGroupIds,
                    "started visible user / profile group", "u", "pg");
            if (mStartedInvisibleProfileUserIds != null) {
                ipw.print("Profiles started invisible: ");
                ipw.println(mStartedInvisibleProfileUserIds);
            }

            ipw.print("Supports visible background users on displays: ");
            ipw.println(mVisibleBackgroundUsersEnabled);

            ipw.print("Supports visible background users on default display: ");
            ipw.println(mVisibleBackgroundUserOnDefaultDisplayEnabled);

            dumpSparseIntArray(ipw, mUsersAssignedToDisplayOnStart, "user / display", "u", "d");
            dumpSparseIntArray(ipw, mExtraDisplaysAssignedToUsers, "extra display / user",
                    "d", "u");

            int numberListeners = mListeners.size();
            ipw.print("Number of listeners: ");
            ipw.println(numberListeners);
            if (numberListeners > 0) {
                ipw.increaseIndent();
                for (int i = 0; i < numberListeners; i++) {
                    ipw.print(i);
                    ipw.print(": ");
                    ipw.println(mListeners.get(i));
                }
                ipw.decreaseIndent();
            }
        }

        ipw.decreaseIndent();
    }

    private static void dumpSparseIntArray(IndentingPrintWriter ipw, @Nullable SparseIntArray array,
            String arrayDescription, String keyName, String valueName) {
        if (array == null) {
            ipw.print("No ");
            ipw.print(arrayDescription);
            ipw.println(" mappings");
            return;
        }
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

    private static boolean isSpecialUserId(@UserIdInt int userId) {
        switch (userId) {
            case UserHandle.USER_ALL:
            case UserHandle.USER_CURRENT:
            case UserHandle.USER_CURRENT_OR_SELF:
            case UserHandle.USER_NULL:
                return true;
            default:
                return false;
        }
    }

    private static boolean isProfile(@UserIdInt int userId, @UserIdInt int profileGroupId) {
        return profileGroupId != NO_PROFILE_GROUP_ID && profileGroupId != userId;
    }

    // NOTE: methods below are needed because some APIs use the current users (full and profiles)
    // state to decide whether a user is visible or not. If we decide to always store that info into
    // mUsersOnSecondaryDisplays, we should remove them.

    private @UserIdInt int getCurrentUserId() {
        synchronized (mLock) {
            return mCurrentUserId;
        }
    }

    @GuardedBy("mLock")
    private boolean isCurrentUserLocked(@UserIdInt int userId) {
        // Special case as NO_PROFILE_GROUP_ID == USER_NULL
        if (userId == USER_NULL || mCurrentUserId == USER_NULL) {
            return false;
        }
        return mCurrentUserId == userId;
    }

    private boolean isCurrentUserOrRunningProfileOfCurrentUser(@UserIdInt int userId) {
        synchronized (mLock) {
            // Special case as NO_PROFILE_GROUP_ID == USER_NULL
            if (userId == USER_NULL || mCurrentUserId == USER_NULL) {
                return false;
            }
            if (mCurrentUserId == userId) {
                return true;
            }
            int profileGroupId = mStartedVisibleProfileGroupIds.get(userId, NO_PROFILE_GROUP_ID);
            return profileGroupId ==
                    mCurrentUserId || profileGroupId == ALWAYS_VISIBLE_PROFILE_GROUP_ID;
        }
    }

    @GuardedBy("mLock")
    private boolean isStartedVisibleProfileLocked(@UserIdInt int userId) {
        int profileGroupId = mStartedVisibleProfileGroupIds.get(userId, NO_PROFILE_GROUP_ID);
        return isProfile(userId, profileGroupId);
    }

    private void validateUserStartMode(@UserStartMode int userStartMode) {
        switch (userStartMode) {
            case USER_START_MODE_FOREGROUND:
            case USER_START_MODE_BACKGROUND:
            case USER_START_MODE_BACKGROUND_VISIBLE:
                return;
        }
        throw new IllegalArgumentException("Invalid user start mode: " + userStartMode);
    }

    private static String secondaryDisplayMappingStatusToString(
            @SecondaryDisplayMappingStatus int status) {
        return DebugUtils.constantToString(UserVisibilityMediator.class,
                PREFIX_SECONDARY_DISPLAY_MAPPING, status);
    }
}
