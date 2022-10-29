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

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.IndentingPrintWriter;
import android.util.SparseIntArray;
import android.view.Display;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.server.utils.Slogf;

import java.io.PrintWriter;

/**
 * Class responsible for deciding whether a user is visible (or visible for a given display).
 *
 * <p>This class is thread safe.
 */
// TODO(b/244644281): improve javadoc (for example, explain all cases / modes)
public final class UserVisibilityMediator {

    private static final boolean DBG = false; // DO NOT SUBMIT WITH TRUE

    private static final String TAG = UserVisibilityMediator.class.getSimpleName();

    private final Object mLock = new Object();

    // TODO(b/244644281): should not depend on service, but keep its own internal state (like
    // current user and profile groups), but it is initially as the code was just moved from UMS
    // "as is". Similarly, it shouldn't need to pass the SparseIntArray on constructor (which was
    // added to UMS for testing purposes)
    private final UserManagerService mService;

    private final boolean mUsersOnSecondaryDisplaysEnabled;

    @Nullable
    @GuardedBy("mLock")
    private final SparseIntArray mUsersOnSecondaryDisplays;

    UserVisibilityMediator(UserManagerService service) {
        this(service, UserManager.isUsersOnSecondaryDisplaysEnabled(),
                /* usersOnSecondaryDisplays= */ null);
    }

    @VisibleForTesting
    UserVisibilityMediator(UserManagerService service, boolean usersOnSecondaryDisplaysEnabled,
            @Nullable SparseIntArray usersOnSecondaryDisplays) {
        mService = service;
        mUsersOnSecondaryDisplaysEnabled = usersOnSecondaryDisplaysEnabled;
        if (mUsersOnSecondaryDisplaysEnabled) {
            mUsersOnSecondaryDisplays = usersOnSecondaryDisplays == null
                    ? new SparseIntArray() // default behavior
                    : usersOnSecondaryDisplays; // passed by unit test
        } else {
            mUsersOnSecondaryDisplays = null;
        }
    }

    /**
     * See {@link UserManagerInternal#assignUserToDisplay(int, int)}.
     */
    public void assignUserToDisplay(int userId, int displayId) {
        if (DBG) {
            Slogf.d(TAG, "assignUserToDisplay(%d, %d)", userId, displayId);
        }

        // NOTE: Using Boolean instead of boolean as it will be re-used below
        Boolean isProfile = null;
        if (displayId == Display.DEFAULT_DISPLAY) {
            if (mUsersOnSecondaryDisplaysEnabled) {
                // Profiles are only supported in the default display, but it cannot return yet
                // as it needs to check if the parent is also assigned to the DEFAULT_DISPLAY
                // (this is done indirectly below when it checks that the profile parent is the
                // current user, as the current user is always assigned to the DEFAULT_DISPLAY).
                isProfile = isProfileUnchecked(userId);
            }
            if (isProfile == null || !isProfile) {
                // Don't need to do anything because methods (such as isUserVisible()) already
                // know that the current user (and their profiles) is assigned to the default
                // display.
                if (DBG) {
                    Slogf.d(TAG, "ignoring on default display");
                }
                return;
            }
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

        if (isProfile == null) {
            isProfile = isProfileUnchecked(userId);
        }
        synchronized (mLock) {
            if (isProfile) {
                // Profile can only start in the same display as parent. And for simplicity,
                // that display must be the DEFAULT_DISPLAY.
                Preconditions.checkArgument(displayId == Display.DEFAULT_DISPLAY,
                        "Profile user can only be started in the default display");
                int parentUserId = getProfileParentId(userId);
                Preconditions.checkArgument(parentUserId == currentUserId,
                        "Only profile of current user can be assigned to a display");
                if (DBG) {
                    Slogf.d(TAG, "Ignoring profile user %d on default display", userId);
                }
                return;
            }

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
     * See {@link UserManagerInternal#unassignUserFromDisplay(int)}.
     */
    public void unassignUserFromDisplay(int userId) {
        if (DBG) {
            Slogf.d(TAG, "unassignUserFromDisplay(%d)", userId);
        }
        if (!mUsersOnSecondaryDisplaysEnabled) {
            // Don't need to do anything because methods (such as isUserVisible()) already know
            // that the current user (and their profiles) is assigned to the default display.
            if (DBG) {
                Slogf.d(TAG, "ignoring when device doesn't support MUMD");
            }
            return;
        }

        synchronized (mLock) {
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
                if (!isProfileUnchecked(userId)) {
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
        ipw.println("UserVisibilityManager");
        ipw.increaseIndent();

        ipw.print("Supports users on secondary displays: ");
        ipw.println(mUsersOnSecondaryDisplaysEnabled);

        if (mUsersOnSecondaryDisplaysEnabled) {
            ipw.print("Users on secondary displays: ");
            synchronized (mLock) {
                ipw.println(mUsersOnSecondaryDisplays);
            }
        }

        ipw.decreaseIndent();
    }

    void dump(PrintWriter pw) {
        if (pw instanceof IndentingPrintWriter) {
            dump((IndentingPrintWriter) pw);
            return;
        }
        dump(new IndentingPrintWriter(pw));
    }

    // TODO(b/244644281): remove methods below once this class caches that state
    private @UserIdInt int getCurrentUserId() {
        return mService.getCurrentUserId();
    }

    private boolean isCurrentUserOrRunningProfileOfCurrentUser(@UserIdInt int userId) {
        return mService.isCurrentUserOrRunningProfileOfCurrentUser(userId);
    }

    private boolean isProfileUnchecked(@UserIdInt int userId) {
        return mService.isProfileUnchecked(userId);
    }

    private @UserIdInt int getProfileParentId(@UserIdInt int userId) {
        return mService.getProfileParentId(userId);
    }
}
