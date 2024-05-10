/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.os.UserManager.USER_TYPE_FULL_DEMO;
import static android.os.UserManager.USER_TYPE_FULL_GUEST;
import static android.os.UserManager.USER_TYPE_FULL_RESTRICTED;
import static android.os.UserManager.USER_TYPE_FULL_SECONDARY;
import static android.os.UserManager.USER_TYPE_FULL_SYSTEM;
import static android.os.UserManager.USER_TYPE_PROFILE_CLONE;
import static android.os.UserManager.USER_TYPE_PROFILE_MANAGED;
import static android.os.UserManager.USER_TYPE_PROFILE_PRIVATE;
import static android.os.UserManager.USER_TYPE_SYSTEM_HEADLESS;

import static com.android.internal.util.FrameworkStatsLog.USER_LIFECYCLE_EVENT_OCCURRED__EVENT__UNKNOWN;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.pm.UserInfo;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;

import java.util.concurrent.ThreadLocalRandom;

/**
 * This class is logging User Lifecycle statsd events and synchronise User Lifecycle Journeys
 * by making sure all events are called in correct order and errors are reported in case of
 * unexpected journeys. This class also makes sure that all user sub-journeys are logged so
 * for example User Switch also log User Start Journey.
 */
public class UserJourneyLogger {

    public static final int ERROR_CODE_INVALID_SESSION_ID = 0;
    public static final int ERROR_CODE_UNSPECIFIED = -1;
    /*
     * Possible reasons for ERROR_CODE_INCOMPLETE_OR_TIMEOUT to occur:
     * - A user switch journey is received while another user switch journey is in
     *   process for the same user.
     * - A user switch journey is received while user start journey is in process for
     *   the same user.
     * - A user start journey is received while another user start journey is in process
     *   for the same user.
     * In all cases potentially an incomplete, timed-out session or multiple
     * simultaneous requests. It is not possible to keep track of multiple sessions for
     * the same user, so previous session is abandoned.
     */
    public static final int ERROR_CODE_INCOMPLETE_OR_TIMEOUT = 2;
    public static final int ERROR_CODE_ABORTED = 3;
    public static final int ERROR_CODE_NULL_USER_INFO = 4;
    public static final int ERROR_CODE_USER_ALREADY_AN_ADMIN = 5;
    public static final int ERROR_CODE_USER_IS_NOT_AN_ADMIN = 6;

    @IntDef(prefix = {"ERROR_CODE"}, value = {
            ERROR_CODE_UNSPECIFIED,
            ERROR_CODE_INCOMPLETE_OR_TIMEOUT,
            ERROR_CODE_ABORTED,
            ERROR_CODE_NULL_USER_INFO,
            ERROR_CODE_USER_ALREADY_AN_ADMIN,
            ERROR_CODE_USER_IS_NOT_AN_ADMIN,
            ERROR_CODE_INVALID_SESSION_ID
    })
    public @interface UserJourneyErrorCode {
    }

    // The various user journeys, defined in the UserLifecycleJourneyReported atom for statsd
    public static final int USER_JOURNEY_UNKNOWN =
            FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__JOURNEY__UNKNOWN;
    public static final int USER_JOURNEY_USER_SWITCH_FG =
            FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__JOURNEY__USER_SWITCH_FG;
    public static final int USER_JOURNEY_USER_SWITCH_UI =
            FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__JOURNEY__USER_SWITCH_UI;
    public static final int USER_JOURNEY_USER_START =
            FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__JOURNEY__USER_START;
    public static final int USER_JOURNEY_USER_CREATE =
            FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__JOURNEY__USER_CREATE;
    public static final int USER_JOURNEY_USER_STOP =
            FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__JOURNEY__USER_STOP;
    public static final int USER_JOURNEY_USER_REMOVE =
            FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__JOURNEY__USER_REMOVE;
    public static final int USER_JOURNEY_GRANT_ADMIN =
            FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__JOURNEY__GRANT_ADMIN;
    public static final int USER_JOURNEY_REVOKE_ADMIN =
            FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__JOURNEY__REVOKE_ADMIN;
    public static final int USER_JOURNEY_USER_LIFECYCLE =
            FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__JOURNEY__USER_LIFECYCLE;

    @IntDef(prefix = {"USER_JOURNEY"}, value = {
            USER_JOURNEY_UNKNOWN,
            USER_JOURNEY_USER_SWITCH_FG,
            USER_JOURNEY_USER_SWITCH_UI,
            USER_JOURNEY_USER_START,
            USER_JOURNEY_USER_STOP,
            USER_JOURNEY_USER_CREATE,
            USER_JOURNEY_USER_REMOVE,
            USER_JOURNEY_GRANT_ADMIN,
            USER_JOURNEY_REVOKE_ADMIN,
            USER_JOURNEY_USER_LIFECYCLE
    })
    public @interface UserJourney {
    }


    // The various user lifecycle events, defined in the UserLifecycleEventOccurred atom for statsd
    public static final int USER_LIFECYCLE_EVENT_UNKNOWN =
            USER_LIFECYCLE_EVENT_OCCURRED__EVENT__UNKNOWN;
    public static final int USER_LIFECYCLE_EVENT_SWITCH_USER =
            FrameworkStatsLog.USER_LIFECYCLE_EVENT_OCCURRED__EVENT__SWITCH_USER;
    public static final int USER_LIFECYCLE_EVENT_START_USER =
            FrameworkStatsLog.USER_LIFECYCLE_EVENT_OCCURRED__EVENT__START_USER;
    public static final int USER_LIFECYCLE_EVENT_CREATE_USER =
            FrameworkStatsLog.USER_LIFECYCLE_EVENT_OCCURRED__EVENT__CREATE_USER;
    public static final int USER_LIFECYCLE_EVENT_REMOVE_USER =
            FrameworkStatsLog.USER_LIFECYCLE_EVENT_OCCURRED__EVENT__REMOVE_USER;
    public static final int USER_LIFECYCLE_EVENT_USER_RUNNING_LOCKED =
            FrameworkStatsLog.USER_LIFECYCLE_EVENT_OCCURRED__EVENT__USER_RUNNING_LOCKED;
    public static final int USER_LIFECYCLE_EVENT_UNLOCKING_USER =
            FrameworkStatsLog.USER_LIFECYCLE_EVENT_OCCURRED__EVENT__UNLOCKING_USER;
    public static final int USER_LIFECYCLE_EVENT_UNLOCKED_USER =
            FrameworkStatsLog.USER_LIFECYCLE_EVENT_OCCURRED__EVENT__UNLOCKED_USER;
    public static final int USER_LIFECYCLE_EVENT_STOP_USER =
            FrameworkStatsLog.USER_LIFECYCLE_EVENT_OCCURRED__EVENT__STOP_USER;
    public static final int USER_LIFECYCLE_EVENT_GRANT_ADMIN =
            FrameworkStatsLog.USER_LIFECYCLE_EVENT_OCCURRED__EVENT__GRANT_ADMIN;
    public static final int USER_LIFECYCLE_EVENT_REVOKE_ADMIN =
            FrameworkStatsLog.USER_LIFECYCLE_EVENT_OCCURRED__EVENT__REVOKE_ADMIN;

    @IntDef(prefix = {"USER_LIFECYCLE_EVENT"}, value = {
            USER_LIFECYCLE_EVENT_UNKNOWN,
            USER_LIFECYCLE_EVENT_SWITCH_USER,
            USER_LIFECYCLE_EVENT_START_USER,
            USER_LIFECYCLE_EVENT_CREATE_USER,
            USER_LIFECYCLE_EVENT_REMOVE_USER,
            USER_LIFECYCLE_EVENT_USER_RUNNING_LOCKED,
            USER_LIFECYCLE_EVENT_UNLOCKING_USER,
            USER_LIFECYCLE_EVENT_UNLOCKED_USER,
            USER_LIFECYCLE_EVENT_STOP_USER,
            USER_LIFECYCLE_EVENT_GRANT_ADMIN,
            USER_LIFECYCLE_EVENT_REVOKE_ADMIN
    })
    public @interface UserLifecycleEvent {
    }

    // User lifecycle event state, defined in the UserLifecycleEventOccurred atom for statsd
    public static final int EVENT_STATE_BEGIN =
            FrameworkStatsLog.USER_LIFECYCLE_EVENT_OCCURRED__STATE__BEGIN;
    public static final int EVENT_STATE_FINISH =
            FrameworkStatsLog.USER_LIFECYCLE_EVENT_OCCURRED__STATE__FINISH;
    public static final int EVENT_STATE_NONE =
            FrameworkStatsLog.USER_LIFECYCLE_EVENT_OCCURRED__STATE__NONE;
    public static final int EVENT_STATE_CANCEL =
            FrameworkStatsLog.USER_LIFECYCLE_EVENT_OCCURRED__STATE__CANCEL;
    public static final int EVENT_STATE_ERROR =
            FrameworkStatsLog.USER_LIFECYCLE_EVENT_OCCURRED__STATE__ERROR;

    @IntDef(prefix = {"EVENT_STATE"}, value = {
            EVENT_STATE_BEGIN,
            EVENT_STATE_FINISH,
            EVENT_STATE_NONE,
            EVENT_STATE_CANCEL,
            EVENT_STATE_ERROR,
    })
    public @interface UserLifecycleEventState {
    }

    private static final int USER_ID_KEY_MULTIPLICATION = 100;

    private final Object mLock = new Object();

    /**
     * {@link UserIdInt} and {@link UserJourney} to {@link UserJourneySession} mapping used for
     * statsd logging for the UserLifecycleJourneyReported and UserLifecycleEventOccurred atoms.
     */
    @GuardedBy("mLock")
    private final SparseArray<UserJourneySession> mUserIdToUserJourneyMap = new SparseArray<>();

    /**
     * Returns event equivalent of given journey
     */
    @UserLifecycleEvent
    private static int journeyToEvent(@UserJourney int journey) {
        switch (journey) {
            case USER_JOURNEY_USER_SWITCH_UI:
            case USER_JOURNEY_USER_SWITCH_FG:
                return USER_LIFECYCLE_EVENT_SWITCH_USER;
            case USER_JOURNEY_USER_START:
                return USER_LIFECYCLE_EVENT_START_USER;
            case USER_JOURNEY_USER_CREATE:
                return USER_LIFECYCLE_EVENT_CREATE_USER;
            case USER_JOURNEY_USER_STOP:
                return USER_LIFECYCLE_EVENT_STOP_USER;
            case USER_JOURNEY_USER_REMOVE:
                return USER_LIFECYCLE_EVENT_REMOVE_USER;
            case USER_JOURNEY_GRANT_ADMIN:
                return USER_LIFECYCLE_EVENT_GRANT_ADMIN;
            case USER_JOURNEY_REVOKE_ADMIN:
                return USER_LIFECYCLE_EVENT_REVOKE_ADMIN;
            default:
                return USER_LIFECYCLE_EVENT_UNKNOWN;
        }
    }

    /**
     * Returns the enum defined in the statsd UserLifecycleJourneyReported atom corresponding to
     * the user type.
     * Changes to this method require changes in CTS file
     * com.android.cts.packagemanager.stats.device.UserInfoUtil
     * which is duplicate for CTS tests purposes.
     */
    public static int getUserTypeForStatsd(@NonNull String userType) {
        switch (userType) {
            case USER_TYPE_FULL_SYSTEM:
                return FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__USER_TYPE__FULL_SYSTEM;
            case USER_TYPE_FULL_SECONDARY:
                return FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__USER_TYPE__FULL_SECONDARY;
            case USER_TYPE_FULL_GUEST:
                return FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__USER_TYPE__FULL_GUEST;
            case USER_TYPE_FULL_DEMO:
                return FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__USER_TYPE__FULL_DEMO;
            case USER_TYPE_FULL_RESTRICTED:
                return FrameworkStatsLog
                        .USER_LIFECYCLE_JOURNEY_REPORTED__USER_TYPE__FULL_RESTRICTED;
            case USER_TYPE_PROFILE_MANAGED:
                return FrameworkStatsLog
                        .USER_LIFECYCLE_JOURNEY_REPORTED__USER_TYPE__PROFILE_MANAGED;
            case USER_TYPE_SYSTEM_HEADLESS:
                return FrameworkStatsLog
                        .USER_LIFECYCLE_JOURNEY_REPORTED__USER_TYPE__SYSTEM_HEADLESS;
            case USER_TYPE_PROFILE_CLONE:
                return FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__USER_TYPE__PROFILE_CLONE;
            case USER_TYPE_PROFILE_PRIVATE:
                return FrameworkStatsLog
                        .USER_LIFECYCLE_JOURNEY_REPORTED__USER_TYPE__PROFILE_PRIVATE;
            default:
                return FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__USER_TYPE__TYPE_UNKNOWN;
        }
    }

    /**
     * Map error code to the event finish state.
     */
    @UserLifecycleEventState
    private static int errorToFinishState(@UserJourneyErrorCode int errorCode) {
        switch (errorCode) {
            case ERROR_CODE_ABORTED:
                return EVENT_STATE_CANCEL;
            case ERROR_CODE_UNSPECIFIED:
                return EVENT_STATE_FINISH;
            default:
                return EVENT_STATE_ERROR;
        }
    }

    /**
     * Simply logging USER_LIFECYCLE_JOURNEY_REPORTED if session exists.
     * If session does not exist then it logs ERROR_CODE_INVALID_SESSION_ID
     */
    @VisibleForTesting
    public void logUserLifecycleJourneyReported(@Nullable UserJourneySession session,
            @UserJourney int journey, @UserIdInt int originalUserId, @UserIdInt int targetUserId,
            int userType, int userFlags, @UserJourneyErrorCode int errorCode) {
        if (session == null) {
            writeUserLifecycleJourneyReported(-1, journey, originalUserId, targetUserId,
                    userType, userFlags, ERROR_CODE_INVALID_SESSION_ID, -1);
        } else {
            final long elapsedTime = System.currentTimeMillis() - session.mStartTimeInMills;
            writeUserLifecycleJourneyReported(
                    session.mSessionId, journey, originalUserId, targetUserId, userType, userFlags,
                    errorCode, elapsedTime);
        }
    }

    /**
     * Helper method for spy testing
     */
    @VisibleForTesting
    public void writeUserLifecycleJourneyReported(long sessionId, int journey, int originalUserId,
            int targetUserId, int userType, int userFlags, int errorCode, long elapsedTime) {
        FrameworkStatsLog.write(FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED,
                sessionId, journey, originalUserId, targetUserId, userType, userFlags,
                errorCode, elapsedTime);
    }

    /**
     * Simply logging USER_LIFECYCLE_EVENT_OCCURRED if session exists.
     * If session does not exist then it logs ERROR_CODE_INVALID_SESSION_ID
     * and EVENT_STATE_ERROR
     */
    @VisibleForTesting
    public void logUserLifecycleEventOccurred(UserJourneySession session,
            @UserIdInt int targetUserId, @UserLifecycleEvent int event,
            @UserLifecycleEventState int state, @UserJourneyErrorCode int errorCode) {
        if (session == null) {
            writeUserLifecycleEventOccurred(-1, targetUserId, event,
                    EVENT_STATE_ERROR, ERROR_CODE_INVALID_SESSION_ID);
        } else {
            writeUserLifecycleEventOccurred(session.mSessionId, targetUserId, event, state,
                    errorCode);
        }
    }

    /**
     * Helper method for spy testing
     */
    @VisibleForTesting
    public void writeUserLifecycleEventOccurred(long sessionId, int userId, int event, int state,
            int errorCode) {
        FrameworkStatsLog.write(FrameworkStatsLog.USER_LIFECYCLE_EVENT_OCCURRED,
                sessionId, userId, event, state, errorCode);
    }

    /**
     * statsd helper method for logging the given event for the UserLifecycleEventOccurred statsd
     * atom. It finds the user journey session for target user id and logs it as that journey.
     */
    public void logUserLifecycleEvent(@UserIdInt int userId, @UserLifecycleEvent int event,
            @UserLifecycleEventState int eventState) {
        final UserJourneySession userJourneySession = findUserJourneySession(userId);
        logUserLifecycleEventOccurred(userJourneySession, userId,
                event, eventState, UserJourneyLogger.ERROR_CODE_UNSPECIFIED);
    }

    /**
     * Returns first user session from mUserIdToUserJourneyMap for given user id,
     * or null if user id was not found in mUserIdToUserJourneyMap.
     */
    private @Nullable UserJourneySession findUserJourneySession(@UserIdInt int userId) {
        synchronized (mLock) {
            final int keyMapSize = mUserIdToUserJourneyMap.size();
            for (int i = 0; i < keyMapSize; i++) {
                int key = mUserIdToUserJourneyMap.keyAt(i);
                if (key / USER_ID_KEY_MULTIPLICATION == userId) {
                    return mUserIdToUserJourneyMap.get(key);
                }
            }
        }
        return null;
    }

    /**
     * Returns unique id for user and journey. For example if user id = 11 and journey = 7
     * then unique key = 11 * 100 + 7 = 1107
     */
    private int getUserJourneyKey(@UserIdInt int targetUserId, @UserJourney int journey) {
        // We leave 99 for user journeys ids.
        return (targetUserId * USER_ID_KEY_MULTIPLICATION) + journey;
    }

    /**
     * Special use case when user journey incomplete or timeout and current user is unclear
     */
    @VisibleForTesting
    public UserJourneySession finishAndClearIncompleteUserJourney(@UserIdInt int targetUserId,
            @UserJourney int journey) {
        synchronized (mLock) {
            final int key = getUserJourneyKey(targetUserId, journey);
            final UserJourneySession userJourneySession = mUserIdToUserJourneyMap.get(key);
            if (userJourneySession != null) {
                logUserLifecycleEventOccurred(
                        userJourneySession,
                        targetUserId,
                        journeyToEvent(userJourneySession.mJourney),
                        EVENT_STATE_ERROR,
                        UserJourneyLogger.ERROR_CODE_INCOMPLETE_OR_TIMEOUT);

                logUserLifecycleJourneyReported(
                        userJourneySession,
                        journey,
                        /* originalUserId= */ -1,
                        targetUserId,
                        getUserTypeForStatsd(""), -1,
                        ERROR_CODE_INCOMPLETE_OR_TIMEOUT);
                mUserIdToUserJourneyMap.remove(key);

                return userJourneySession;
            }
        }
        return null;
    }

    /**
     * Log user journey event and report finishing without error
     */
    public UserJourneySession logUserJourneyFinish(@UserIdInt int originalUserId,
            UserInfo targetUser, @UserJourney int journey) {
        return logUserJourneyFinishWithError(originalUserId, targetUser, journey,
                ERROR_CODE_UNSPECIFIED);
    }

    /**
     * Special case when it is unknown which user switch  journey was used and checking both
     */
    @VisibleForTesting
    public UserJourneySession logUserSwitchJourneyFinish(@UserIdInt int originalUserId,
            UserInfo targetUser) {
        synchronized (mLock) {
            final int key_fg = getUserJourneyKey(targetUser.id, USER_JOURNEY_USER_SWITCH_FG);
            final int key_ui = getUserJourneyKey(targetUser.id, USER_JOURNEY_USER_SWITCH_UI);

            if (mUserIdToUserJourneyMap.contains(key_fg)) {
                return logUserJourneyFinish(originalUserId, targetUser,
                        USER_JOURNEY_USER_SWITCH_FG);
            }

            if (mUserIdToUserJourneyMap.contains(key_ui)) {
                return logUserJourneyFinish(originalUserId, targetUser,
                        USER_JOURNEY_USER_SWITCH_UI);
            }

            return null;
        }
    }

    /**
     * Log user journey event and report finishing with error
     */
    public UserJourneySession logUserJourneyFinishWithError(@UserIdInt int originalUserId,
            UserInfo targetUser, @UserJourney int journey, @UserJourneyErrorCode int errorCode) {
        synchronized (mLock) {
            final int state = errorToFinishState(errorCode);
            final int key = getUserJourneyKey(targetUser.id, journey);
            final UserJourneySession userJourneySession = mUserIdToUserJourneyMap.get(key);
            if (userJourneySession != null) {
                logUserLifecycleEventOccurred(
                        userJourneySession, targetUser.id,
                        journeyToEvent(userJourneySession.mJourney),
                        state,
                        errorCode);

                logUserLifecycleJourneyReported(
                        userJourneySession,
                        journey, originalUserId, targetUser.id,
                        getUserTypeForStatsd(targetUser.userType),
                        targetUser.flags,
                        errorCode);
                mUserIdToUserJourneyMap.remove(key);

                return userJourneySession;
            }
        }
        return null;
    }

    /**
     * Log user journey event and report finishing with error
     */
    public UserJourneySession logDelayedUserJourneyFinishWithError(@UserIdInt int originalUserId,
            UserInfo targetUser, @UserJourney int journey, @UserJourneyErrorCode int errorCode) {
        synchronized (mLock) {
            final int key = getUserJourneyKey(targetUser.id, journey);
            final UserJourneySession userJourneySession = mUserIdToUserJourneyMap.get(key);
            if (userJourneySession != null) {
                logUserLifecycleJourneyReported(
                        userJourneySession,
                        journey, originalUserId, targetUser.id,
                        getUserTypeForStatsd(targetUser.userType),
                        targetUser.flags,
                        errorCode);
                mUserIdToUserJourneyMap.remove(key);

                return userJourneySession;
            }
        }
        return null;
    }

    /**
     * Log event and report finish when user is null. This is edge case when UserInfo
     * can not be passed because it is null, therefore all information are passed as arguments.
     */
    public UserJourneySession logNullUserJourneyError(@UserJourney int journey,
            @UserIdInt int currentUserId, @UserIdInt int targetUserId, String targetUserType,
            int targetUserFlags) {
        synchronized (mLock) {
            final int key = getUserJourneyKey(targetUserId, journey);
            final UserJourneySession session = mUserIdToUserJourneyMap.get(key);

            logUserLifecycleEventOccurred(
                    session, targetUserId, journeyToEvent(journey),
                    EVENT_STATE_ERROR,
                    ERROR_CODE_NULL_USER_INFO);

            logUserLifecycleJourneyReported(
                    session, journey, currentUserId, targetUserId,
                    getUserTypeForStatsd(targetUserType), targetUserFlags,
                    ERROR_CODE_NULL_USER_INFO);

            mUserIdToUserJourneyMap.remove(key);
            return session;
        }
    }

    /**
     * Log for user creation finish event and report. This is edge case when target user id is
     * different in begin event and finish event as it is unknown what is user id
     * until it has been created.
     */
    public UserJourneySession logUserCreateJourneyFinish(@UserIdInt int originalUserId,
            UserInfo targetUser) {
        synchronized (mLock) {
            // we do not know user id until we create new user which is why we use -1
            // as user id to create and find session, but we log correct id.
            final int key = getUserJourneyKey(-1, USER_JOURNEY_USER_CREATE);
            final UserJourneySession userJourneySession = mUserIdToUserJourneyMap.get(key);
            if (userJourneySession != null) {
                logUserLifecycleEventOccurred(
                        userJourneySession, targetUser.id,
                        USER_LIFECYCLE_EVENT_CREATE_USER,
                        EVENT_STATE_FINISH,
                        ERROR_CODE_UNSPECIFIED);

                logUserLifecycleJourneyReported(
                        userJourneySession,
                        USER_JOURNEY_USER_CREATE, originalUserId, targetUser.id,
                        getUserTypeForStatsd(targetUser.userType),
                        targetUser.flags,
                        ERROR_CODE_UNSPECIFIED);
                mUserIdToUserJourneyMap.remove(key);

                return userJourneySession;
            }
        }
        return null;
    }

    /**
     * Adds new UserJourneySession to mUserIdToUserJourneyMap and log UserJourneyEvent Begin state
     */
    public UserJourneySession logUserJourneyBegin(@UserIdInt int targetId,
            @UserJourney int journey) {
        final long newSessionId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        synchronized (mLock) {
            final int key = getUserJourneyKey(targetId, journey);
            final UserJourneySession userJourneySession =
                    new UserJourneySession(newSessionId, journey);
            mUserIdToUserJourneyMap.append(key, userJourneySession);

            logUserLifecycleEventOccurred(
                    userJourneySession, targetId,
                    journeyToEvent(userJourneySession.mJourney),
                    EVENT_STATE_BEGIN,
                    ERROR_CODE_UNSPECIFIED);

            return userJourneySession;
        }
    }

    /**
     * This keeps the start time when finishing extensively long journey was began.
     * For instance full user lifecycle ( from creation to deletion )when user is about to delete
     * we need to get user creation time before it was deleted.
     */
    public UserJourneySession startSessionForDelayedJourney(@UserIdInt int targetId,
            @UserJourney int journey, long startTime) {
        final long newSessionId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        synchronized (mLock) {
            final int key = getUserJourneyKey(targetId, journey);
            final UserJourneySession userJourneySession =
                    new UserJourneySession(newSessionId, journey, startTime);
            mUserIdToUserJourneyMap.append(key, userJourneySession);
            return userJourneySession;
        }
    }

    /**
     * Helper class to store user journey and session id.
     *
     * <p> User journey tracks a chain of user lifecycle events occurring during different user
     * activities such as user start, user switch, and user creation.
     */
    public static class UserJourneySession {
        public final long mSessionId;
        @UserJourney
        public final int mJourney;
        public final long mStartTimeInMills;

        @VisibleForTesting
        public UserJourneySession(long sessionId, @UserJourney int journey) {
            mJourney = journey;
            mSessionId = sessionId;
            mStartTimeInMills = System.currentTimeMillis();
        }
        @VisibleForTesting
        public UserJourneySession(long sessionId, @UserJourney int journey, long startTimeInMills) {
            mJourney = journey;
            mSessionId = sessionId;
            mStartTimeInMills = startTimeInMills;
        }
    }
}