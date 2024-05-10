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

import static com.android.server.pm.UserJourneyLogger.ERROR_CODE_ABORTED;
import static com.android.server.pm.UserJourneyLogger.ERROR_CODE_INCOMPLETE_OR_TIMEOUT;
import static com.android.server.pm.UserJourneyLogger.ERROR_CODE_NULL_USER_INFO;
import static com.android.server.pm.UserJourneyLogger.ERROR_CODE_UNSPECIFIED;
import static com.android.server.pm.UserJourneyLogger.EVENT_STATE_BEGIN;
import static com.android.server.pm.UserJourneyLogger.EVENT_STATE_CANCEL;
import static com.android.server.pm.UserJourneyLogger.EVENT_STATE_ERROR;
import static com.android.server.pm.UserJourneyLogger.EVENT_STATE_FINISH;
import static com.android.server.pm.UserJourneyLogger.EVENT_STATE_NONE;
import static com.android.server.pm.UserJourneyLogger.USER_JOURNEY_GRANT_ADMIN;
import static com.android.server.pm.UserJourneyLogger.USER_JOURNEY_USER_CREATE;
import static com.android.server.pm.UserJourneyLogger.USER_JOURNEY_USER_LIFECYCLE;
import static com.android.server.pm.UserJourneyLogger.USER_JOURNEY_USER_REMOVE;
import static com.android.server.pm.UserJourneyLogger.USER_JOURNEY_USER_START;
import static com.android.server.pm.UserJourneyLogger.USER_JOURNEY_USER_STOP;
import static com.android.server.pm.UserJourneyLogger.USER_LIFECYCLE_EVENT_CREATE_USER;
import static com.android.server.pm.UserJourneyLogger.USER_LIFECYCLE_EVENT_REMOVE_USER;
import static com.android.server.pm.UserJourneyLogger.USER_LIFECYCLE_EVENT_REVOKE_ADMIN;
import static com.android.server.pm.UserJourneyLogger.USER_LIFECYCLE_EVENT_START_USER;
import static com.android.server.pm.UserJourneyLogger.USER_LIFECYCLE_EVENT_STOP_USER;
import static com.android.server.pm.UserJourneyLogger.USER_LIFECYCLE_EVENT_USER_RUNNING_LOCKED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.pm.UserInfo;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.FrameworkStatsLog;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class UserJourneyLoggerTest {

    public static final int FULL_USER_ADMIN_FLAG = 0x00000402;
    private UserJourneyLogger mUserJourneyLogger;

    @Before
    public void setup() throws Exception {
        mUserJourneyLogger = spy(new UserJourneyLogger());
    }

    @Test
    public void testUserStartLifecycleJourneyReported() {
        final UserLifecycleJourneyReportedCaptor report1 = new UserLifecycleJourneyReportedCaptor();
        final UserJourneyLogger.UserJourneySession session = new UserJourneyLogger
                .UserJourneySession(10, USER_JOURNEY_USER_START);

        report1.captureLogAndAssert(mUserJourneyLogger, session,
                USER_JOURNEY_USER_START, 0, 10,
                FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__USER_TYPE__FULL_SECONDARY, 1,
                ERROR_CODE_UNSPECIFIED);
    }


    @Test
    public void testUserLifecycleEventOccurred() {
        final UserLifecycleEventOccurredCaptor report1 = new UserLifecycleEventOccurredCaptor();
        final UserJourneyLogger.UserJourneySession session = new UserJourneyLogger
                .UserJourneySession(10, USER_JOURNEY_USER_START);

        report1.captureLogAndAssert(mUserJourneyLogger, session, 0,
                USER_LIFECYCLE_EVENT_START_USER, EVENT_STATE_BEGIN, ERROR_CODE_UNSPECIFIED);
    }

    @Test
    public void testLogUserLifecycleEvent() {
        final UserLifecycleEventOccurredCaptor report1 = new UserLifecycleEventOccurredCaptor();
        final UserJourneyLogger.UserJourneySession session = mUserJourneyLogger
                .logUserJourneyBegin(10, USER_JOURNEY_USER_START);

        report1.captureAndAssert(mUserJourneyLogger, session.mSessionId, 10,
                USER_LIFECYCLE_EVENT_START_USER, EVENT_STATE_BEGIN, ERROR_CODE_UNSPECIFIED, 1);

        mUserJourneyLogger.logUserLifecycleEvent(10, USER_LIFECYCLE_EVENT_USER_RUNNING_LOCKED,
                EVENT_STATE_NONE);

        report1.captureAndAssert(mUserJourneyLogger, session.mSessionId, 10,
                USER_LIFECYCLE_EVENT_USER_RUNNING_LOCKED,
                EVENT_STATE_NONE, ERROR_CODE_UNSPECIFIED, 2);
    }


    @Test
    public void testCreateUserJourney() {
        final UserLifecycleEventOccurredCaptor report1 = new UserLifecycleEventOccurredCaptor();
        final UserJourneyLogger.UserJourneySession session = mUserJourneyLogger
                .logUserJourneyBegin(-1, USER_JOURNEY_USER_CREATE);

        report1.captureAndAssert(mUserJourneyLogger, session.mSessionId, -1,
                USER_LIFECYCLE_EVENT_CREATE_USER,
                EVENT_STATE_BEGIN, ERROR_CODE_UNSPECIFIED, 1);

        final UserLifecycleJourneyReportedCaptor report2 = new UserLifecycleJourneyReportedCaptor();
        UserInfo targetUser = new UserInfo(10, "test target user",
                UserInfo.FLAG_ADMIN | UserInfo.FLAG_FULL);
        mUserJourneyLogger.logUserCreateJourneyFinish(0, targetUser);

        report1.captureAndAssert(mUserJourneyLogger, session.mSessionId, 10,
                USER_LIFECYCLE_EVENT_CREATE_USER,
                EVENT_STATE_FINISH, ERROR_CODE_UNSPECIFIED, 2);

        report2.captureAndAssert(mUserJourneyLogger, session.mSessionId,
                USER_JOURNEY_USER_CREATE, 0, 10,
                FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__USER_TYPE__FULL_SECONDARY,
                0x00000402, ERROR_CODE_UNSPECIFIED, 1);
    }

    @Test
    public void testCreatePrivateProfileUserJourney() {
        final UserLifecycleEventOccurredCaptor report1 = new UserLifecycleEventOccurredCaptor();
        final UserJourneyLogger.UserJourneySession session =
                mUserJourneyLogger.logUserJourneyBegin(-1, USER_JOURNEY_USER_CREATE);

        report1.captureAndAssert(
                mUserJourneyLogger,
                session.mSessionId,
                -1,
                USER_LIFECYCLE_EVENT_CREATE_USER,
                EVENT_STATE_BEGIN,
                ERROR_CODE_UNSPECIFIED,
                1);

        final UserLifecycleJourneyReportedCaptor report2 = new UserLifecycleJourneyReportedCaptor();
        final int profileUserId = 10;
        UserInfo targetUser =
                new UserInfo(
                        profileUserId,
                        "test private target user",
                        /* iconPath= */ null,
                        UserInfo.FLAG_PROFILE,
                        UserManager.USER_TYPE_PROFILE_PRIVATE);
        mUserJourneyLogger.logUserCreateJourneyFinish(0, targetUser);

        report1.captureAndAssert(
                mUserJourneyLogger,
                session.mSessionId,
                profileUserId,
                USER_LIFECYCLE_EVENT_CREATE_USER,
                EVENT_STATE_FINISH,
                ERROR_CODE_UNSPECIFIED,
                2);

        report2.captureAndAssert(
                mUserJourneyLogger,
                session.mSessionId,
                USER_JOURNEY_USER_CREATE,
                0,
                profileUserId,
                FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__USER_TYPE__PROFILE_PRIVATE,
                UserInfo.FLAG_PROFILE,
                ERROR_CODE_UNSPECIFIED,
                1);
    }

    @Test
    public void testRemoveUserJourney() {
        final UserLifecycleEventOccurredCaptor report1 = new UserLifecycleEventOccurredCaptor();
        final UserJourneyLogger.UserJourneySession session = mUserJourneyLogger
                .logUserJourneyBegin(10, USER_JOURNEY_USER_REMOVE);

        report1.captureAndAssert(mUserJourneyLogger, session.mSessionId, 10,
                USER_LIFECYCLE_EVENT_REMOVE_USER,
                EVENT_STATE_BEGIN, ERROR_CODE_UNSPECIFIED, 1);

        final UserLifecycleJourneyReportedCaptor report2 = new UserLifecycleJourneyReportedCaptor();
        final UserInfo targetUser = new UserInfo(10, "test target user",
                UserInfo.FLAG_ADMIN | UserInfo.FLAG_FULL);
        mUserJourneyLogger.logUserJourneyFinish(0, targetUser,
                USER_JOURNEY_USER_REMOVE);

        report1.captureAndAssert(mUserJourneyLogger, session.mSessionId, 10,
                USER_LIFECYCLE_EVENT_REMOVE_USER,
                EVENT_STATE_FINISH, ERROR_CODE_UNSPECIFIED, 2);

        report2.captureAndAssert(mUserJourneyLogger, session.mSessionId,
                USER_JOURNEY_USER_REMOVE, 0, 10,
                FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__USER_TYPE__FULL_SECONDARY,
                0x00000402, ERROR_CODE_UNSPECIFIED, 1);
    }

    @Test
    public void testRemovePrivateProfileUserJourneyWithError() {
        final UserLifecycleEventOccurredCaptor report1 = new UserLifecycleEventOccurredCaptor();
        final int profileUserId = 10;
        final UserJourneyLogger.UserJourneySession session =
                mUserJourneyLogger.logUserJourneyBegin(profileUserId, USER_JOURNEY_USER_REMOVE);

        report1.captureAndAssert(
                mUserJourneyLogger,
                session.mSessionId,
                profileUserId,
                USER_LIFECYCLE_EVENT_REMOVE_USER,
                EVENT_STATE_BEGIN,
                ERROR_CODE_UNSPECIFIED,
                1);

        final UserLifecycleJourneyReportedCaptor report2 = new UserLifecycleJourneyReportedCaptor();
        final UserInfo targetUser =
                new UserInfo(
                        profileUserId,
                        "test private target user",
                        /* iconPath= */ null,
                        UserInfo.FLAG_PROFILE,
                        UserManager.USER_TYPE_PROFILE_PRIVATE);
        mUserJourneyLogger.logUserJourneyFinishWithError(
                0, targetUser, USER_JOURNEY_USER_REMOVE, ERROR_CODE_INCOMPLETE_OR_TIMEOUT);

        report1.captureAndAssert(
                mUserJourneyLogger,
                session.mSessionId,
                profileUserId,
                USER_LIFECYCLE_EVENT_REMOVE_USER,
                EVENT_STATE_ERROR,
                ERROR_CODE_INCOMPLETE_OR_TIMEOUT,
                2);

        report2.captureAndAssert(
                mUserJourneyLogger,
                session.mSessionId,
                USER_JOURNEY_USER_REMOVE,
                0,
                profileUserId,
                FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__USER_TYPE__PROFILE_PRIVATE,
                UserInfo.FLAG_PROFILE,
                ERROR_CODE_INCOMPLETE_OR_TIMEOUT,
                1);
    }

    @Test
    public void testStartUserJourney() {
        final UserLifecycleEventOccurredCaptor report1 = new UserLifecycleEventOccurredCaptor();
        final UserJourneyLogger.UserJourneySession session = mUserJourneyLogger
                .logUserJourneyBegin(10, USER_JOURNEY_USER_START);

        report1.captureAndAssert(mUserJourneyLogger, session.mSessionId, 10,
                USER_LIFECYCLE_EVENT_START_USER,
                EVENT_STATE_BEGIN, ERROR_CODE_UNSPECIFIED, 1);

        final UserLifecycleJourneyReportedCaptor report2 = new UserLifecycleJourneyReportedCaptor();
        final UserInfo targetUser = new UserInfo(10, "test target user",
                UserInfo.FLAG_ADMIN | UserInfo.FLAG_FULL);
        mUserJourneyLogger.logUserJourneyFinish(0, targetUser,
                USER_JOURNEY_USER_START);

        report1.captureAndAssert(mUserJourneyLogger, session.mSessionId, 10,
                USER_LIFECYCLE_EVENT_START_USER,
                EVENT_STATE_FINISH, ERROR_CODE_UNSPECIFIED, 2);

        report2.captureAndAssert(mUserJourneyLogger, session.mSessionId,
                USER_JOURNEY_USER_START, 0, 10,
                FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__USER_TYPE__FULL_SECONDARY,
                0x00000402, ERROR_CODE_UNSPECIFIED, 1);
    }

    @Test
    public void testStopUserJourney() {
        final UserLifecycleEventOccurredCaptor report1 = new UserLifecycleEventOccurredCaptor();
        final UserJourneyLogger.UserJourneySession session = mUserJourneyLogger
                .logUserJourneyBegin(10, USER_JOURNEY_USER_STOP);

        report1.captureAndAssert(mUserJourneyLogger, session.mSessionId, 10,
                USER_LIFECYCLE_EVENT_STOP_USER, EVENT_STATE_BEGIN, ERROR_CODE_UNSPECIFIED, 1);

        final UserLifecycleJourneyReportedCaptor report2 = new UserLifecycleJourneyReportedCaptor();
        final UserInfo targetUser = new UserInfo(10, "test target user",
                UserInfo.FLAG_ADMIN | UserInfo.FLAG_FULL);
        mUserJourneyLogger.logUserJourneyFinish(0, targetUser,
                USER_JOURNEY_USER_STOP);

        report1.captureAndAssert(mUserJourneyLogger, session.mSessionId, 10,
                USER_LIFECYCLE_EVENT_STOP_USER, EVENT_STATE_FINISH, ERROR_CODE_UNSPECIFIED, 2);

        report2.captureAndAssert(mUserJourneyLogger, session.mSessionId,
                USER_JOURNEY_USER_STOP, 0, 10,
                FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__USER_TYPE__FULL_SECONDARY,
                0x00000402, ERROR_CODE_UNSPECIFIED, 1);
    }

    @Test
    public void testAbortStopUserJourney() {
        final UserLifecycleEventOccurredCaptor report1 = new UserLifecycleEventOccurredCaptor();
        final UserJourneyLogger.UserJourneySession session = mUserJourneyLogger
                .logUserJourneyBegin(10, USER_JOURNEY_USER_STOP);

        report1.captureAndAssert(mUserJourneyLogger, session.mSessionId, 10,
                USER_LIFECYCLE_EVENT_STOP_USER, EVENT_STATE_BEGIN, ERROR_CODE_UNSPECIFIED, 1);

        final UserLifecycleJourneyReportedCaptor report2 = new UserLifecycleJourneyReportedCaptor();
        final UserInfo targetUser = new UserInfo(10, "test target user",
                UserInfo.FLAG_ADMIN | UserInfo.FLAG_FULL);

        mUserJourneyLogger.logUserJourneyFinishWithError(-1, targetUser,
                USER_JOURNEY_USER_STOP, ERROR_CODE_ABORTED);
        report1.captureAndAssert(mUserJourneyLogger, session.mSessionId, 10,
                USER_LIFECYCLE_EVENT_STOP_USER,
                EVENT_STATE_CANCEL, ERROR_CODE_ABORTED, 2);

        report2.captureAndAssert(mUserJourneyLogger, session.mSessionId,
                USER_JOURNEY_USER_STOP, -1, 10,
                FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__USER_TYPE__FULL_SECONDARY,
                0x00000402, ERROR_CODE_ABORTED, 1);
    }

    @Test
    public void testIncompleteStopUserJourney() {
        final UserLifecycleEventOccurredCaptor report1 = new UserLifecycleEventOccurredCaptor();
        final UserLifecycleJourneyReportedCaptor report2 = new UserLifecycleJourneyReportedCaptor();

        final UserJourneyLogger.UserJourneySession session = mUserJourneyLogger
                .logUserJourneyBegin(10, USER_JOURNEY_USER_STOP);
        report1.captureAndAssert(mUserJourneyLogger, session.mSessionId, 10,
                USER_LIFECYCLE_EVENT_STOP_USER,
                EVENT_STATE_BEGIN, ERROR_CODE_UNSPECIFIED, 1);

        mUserJourneyLogger.finishAndClearIncompleteUserJourney(10, USER_JOURNEY_USER_STOP);
        report1.captureAndAssert(mUserJourneyLogger, session.mSessionId, 10,
                USER_LIFECYCLE_EVENT_STOP_USER,
                EVENT_STATE_ERROR,
                ERROR_CODE_INCOMPLETE_OR_TIMEOUT, 2);

        report2.captureAndAssert(mUserJourneyLogger, session.mSessionId,
                USER_JOURNEY_USER_STOP, -1, 10,
                FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__USER_TYPE__TYPE_UNKNOWN,
                -1, // information about user are incomplete
                ERROR_CODE_INCOMPLETE_OR_TIMEOUT, 1);
    }

    @Test
    public void testGrantAdminUserJourney() {
        final UserLifecycleEventOccurredCaptor report1 = new UserLifecycleEventOccurredCaptor();
        final UserJourneyLogger.UserJourneySession session = mUserJourneyLogger
                .logUserJourneyBegin(10, USER_JOURNEY_GRANT_ADMIN);

        report1.captureAndAssert(mUserJourneyLogger, session.mSessionId, 10,
                UserJourneyLogger.USER_LIFECYCLE_EVENT_GRANT_ADMIN,
                EVENT_STATE_BEGIN, ERROR_CODE_UNSPECIFIED, 1);

        final UserLifecycleJourneyReportedCaptor report2 = new UserLifecycleJourneyReportedCaptor();
        final UserInfo targetUser = new UserInfo(10, "test target user",
                UserInfo.FLAG_ADMIN | UserInfo.FLAG_FULL);
        mUserJourneyLogger.logUserJourneyFinish(0, targetUser,
                USER_JOURNEY_GRANT_ADMIN);

        report1.captureAndAssert(mUserJourneyLogger, session.mSessionId, 10,
                UserJourneyLogger.USER_LIFECYCLE_EVENT_GRANT_ADMIN,
                EVENT_STATE_FINISH, ERROR_CODE_UNSPECIFIED, 2);

        report2.captureAndAssert(mUserJourneyLogger, session.mSessionId,
                USER_JOURNEY_GRANT_ADMIN, 0, 10,
                FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__USER_TYPE__FULL_SECONDARY,
                0x00000402, ERROR_CODE_UNSPECIFIED, 1);
    }

    @Test
    public void testNullUserErrorGrantAdminUserJourney() {
        final UserLifecycleEventOccurredCaptor report1 = new UserLifecycleEventOccurredCaptor();
        final UserLifecycleJourneyReportedCaptor report2 = new UserLifecycleJourneyReportedCaptor();

        UserJourneyLogger.UserJourneySession session = mUserJourneyLogger
                .logUserJourneyBegin(10, USER_JOURNEY_GRANT_ADMIN);

        report1.captureAndAssert(mUserJourneyLogger, session.mSessionId, 10,
                UserJourneyLogger.USER_LIFECYCLE_EVENT_GRANT_ADMIN,
                EVENT_STATE_BEGIN, ERROR_CODE_UNSPECIFIED, 1);

        mUserJourneyLogger.logNullUserJourneyError(USER_JOURNEY_GRANT_ADMIN,
                0, 10, "", -1);

        report1.captureAndAssert(mUserJourneyLogger, session.mSessionId, 10,
                UserJourneyLogger.USER_LIFECYCLE_EVENT_GRANT_ADMIN,
                EVENT_STATE_ERROR, ERROR_CODE_NULL_USER_INFO, 2);

        report2.captureAndAssert(mUserJourneyLogger, session.mSessionId,
                USER_JOURNEY_GRANT_ADMIN, 0, 10,
                FrameworkStatsLog.USER_LIFECYCLE_EVENT_OCCURRED__EVENT__UNKNOWN,
                -1, ERROR_CODE_NULL_USER_INFO, 1);
    }

    @Test
    public void testRevokeAdminUserJourney() {
        final UserLifecycleEventOccurredCaptor report1 = new UserLifecycleEventOccurredCaptor();
        final UserJourneyLogger.UserJourneySession session = mUserJourneyLogger
                .logUserJourneyBegin(10, UserJourneyLogger.USER_JOURNEY_REVOKE_ADMIN);

        report1.captureAndAssert(mUserJourneyLogger, session.mSessionId, 10,
                USER_LIFECYCLE_EVENT_REVOKE_ADMIN,
                EVENT_STATE_BEGIN, ERROR_CODE_UNSPECIFIED, 1);

        final UserLifecycleJourneyReportedCaptor report2 = new UserLifecycleJourneyReportedCaptor();
        final UserInfo targetUser = new UserInfo(10, "test target user", UserInfo.FLAG_FULL);
        mUserJourneyLogger.logUserJourneyFinish(0, targetUser,
                UserJourneyLogger.USER_JOURNEY_REVOKE_ADMIN);

        report1.captureAndAssert(mUserJourneyLogger, session.mSessionId, 10,
                USER_LIFECYCLE_EVENT_REVOKE_ADMIN,
                EVENT_STATE_FINISH, ERROR_CODE_UNSPECIFIED, 2);

        report2.captureAndAssert(mUserJourneyLogger, session.mSessionId,
                UserJourneyLogger.USER_JOURNEY_REVOKE_ADMIN, 0, 10,
                FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__USER_TYPE__FULL_SECONDARY,
                0x00000400, ERROR_CODE_UNSPECIFIED, 1);
    }

    @Test
    public void testSwitchFGUserJourney() {
        final UserLifecycleEventOccurredCaptor report1 = new UserLifecycleEventOccurredCaptor();
        final UserJourneyLogger.UserJourneySession session = mUserJourneyLogger
                .logUserJourneyBegin(11, UserJourneyLogger.USER_JOURNEY_USER_SWITCH_FG);

        report1.captureAndAssert(mUserJourneyLogger, session.mSessionId, 11,
                UserJourneyLogger.USER_LIFECYCLE_EVENT_SWITCH_USER,
                EVENT_STATE_BEGIN, ERROR_CODE_UNSPECIFIED, 1);

        final UserJourneyLogger.UserJourneySession session2 = mUserJourneyLogger
                .logUserJourneyBegin(11, USER_JOURNEY_USER_START);

        report1.captureAndAssert(mUserJourneyLogger, session2.mSessionId, 11,
                USER_LIFECYCLE_EVENT_START_USER,
                EVENT_STATE_BEGIN, ERROR_CODE_UNSPECIFIED, 2);

        final UserLifecycleJourneyReportedCaptor report2 = new UserLifecycleJourneyReportedCaptor();
        final UserInfo targetUser = new UserInfo(11, "test target user",
                UserInfo.FLAG_ADMIN | UserInfo.FLAG_FULL);
        mUserJourneyLogger.logUserJourneyFinish(10, targetUser,
                USER_JOURNEY_USER_START);

        report1.captureAndAssert(mUserJourneyLogger, session2.mSessionId, 11,
                USER_LIFECYCLE_EVENT_START_USER,
                EVENT_STATE_FINISH, ERROR_CODE_UNSPECIFIED, 3);

        report2.captureAndAssert(mUserJourneyLogger, session2.mSessionId,
                USER_JOURNEY_USER_START, 10, 11,
                FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__USER_TYPE__FULL_SECONDARY,
                0x00000402, ERROR_CODE_UNSPECIFIED, 1);

        mUserJourneyLogger.logUserSwitchJourneyFinish(10, targetUser);

        report1.captureAndAssert(mUserJourneyLogger, session.mSessionId, 11,
                UserJourneyLogger.USER_LIFECYCLE_EVENT_SWITCH_USER,
                EVENT_STATE_FINISH, ERROR_CODE_UNSPECIFIED, 4);

        report2.captureAndAssert(mUserJourneyLogger, session.mSessionId,
                UserJourneyLogger.USER_JOURNEY_USER_SWITCH_FG, 10, 11,
                FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__USER_TYPE__FULL_SECONDARY,
                0x00000402, ERROR_CODE_UNSPECIFIED, 2);
    }


    @Test
    public void testSwitchUIUserJourney() {
        final UserLifecycleEventOccurredCaptor report1 = new UserLifecycleEventOccurredCaptor();
        final UserJourneyLogger.UserJourneySession session = mUserJourneyLogger
                .logUserJourneyBegin(11, UserJourneyLogger.USER_JOURNEY_USER_SWITCH_UI);

        report1.captureAndAssert(mUserJourneyLogger, session.mSessionId, 11,
                UserJourneyLogger.USER_LIFECYCLE_EVENT_SWITCH_USER,
                EVENT_STATE_BEGIN, ERROR_CODE_UNSPECIFIED, 1);

        final UserJourneyLogger.UserJourneySession session2 = mUserJourneyLogger
                .logUserJourneyBegin(11, USER_JOURNEY_USER_START);

        report1.captureAndAssert(mUserJourneyLogger, session2.mSessionId, 11,
                USER_LIFECYCLE_EVENT_START_USER,
                EVENT_STATE_BEGIN, ERROR_CODE_UNSPECIFIED, 2);

        final UserLifecycleJourneyReportedCaptor report2 = new UserLifecycleJourneyReportedCaptor();
        UserInfo targetUser = new UserInfo(11, "test target user",
                UserInfo.FLAG_ADMIN | UserInfo.FLAG_FULL);
        mUserJourneyLogger.logUserJourneyFinish(10, targetUser,
                USER_JOURNEY_USER_START);

        report1.captureAndAssert(mUserJourneyLogger, session2.mSessionId, 11,
                USER_LIFECYCLE_EVENT_START_USER,
                EVENT_STATE_FINISH, ERROR_CODE_UNSPECIFIED, 3);

        report2.captureAndAssert(mUserJourneyLogger, session2.mSessionId,
                USER_JOURNEY_USER_START, 10, 11,
                FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__USER_TYPE__FULL_SECONDARY,
                0x00000402, ERROR_CODE_UNSPECIFIED, 1);

        mUserJourneyLogger.logUserSwitchJourneyFinish(10, targetUser);

        report1.captureAndAssert(mUserJourneyLogger, session.mSessionId, 11,
                UserJourneyLogger.USER_LIFECYCLE_EVENT_SWITCH_USER,
                EVENT_STATE_FINISH,
                ERROR_CODE_UNSPECIFIED, 4);

        report2.captureAndAssert(mUserJourneyLogger, session.mSessionId,
                UserJourneyLogger.USER_JOURNEY_USER_SWITCH_UI, 10, 11,
                FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__USER_TYPE__FULL_SECONDARY,
                0x00000402, ERROR_CODE_UNSPECIFIED, 2);
    }


    @Test
    public void testSwitchWithStopUIUserJourney() {
        final UserLifecycleEventOccurredCaptor report1 = new UserLifecycleEventOccurredCaptor();

        // BEGIN USER SWITCH
        final UserJourneyLogger.UserJourneySession session = mUserJourneyLogger
                .logUserJourneyBegin(11, UserJourneyLogger.USER_JOURNEY_USER_SWITCH_UI);

        report1.captureAndAssert(mUserJourneyLogger, session.mSessionId, 11,
                UserJourneyLogger.USER_LIFECYCLE_EVENT_SWITCH_USER,
                EVENT_STATE_BEGIN, ERROR_CODE_UNSPECIFIED, 1);

        // BEGIN USER STOP
        final  UserJourneyLogger.UserJourneySession session2 = mUserJourneyLogger
                .logUserJourneyBegin(10, USER_JOURNEY_USER_STOP);

        report1.captureAndAssert(mUserJourneyLogger, session2.mSessionId, 10,
                USER_LIFECYCLE_EVENT_STOP_USER,
                EVENT_STATE_BEGIN, ERROR_CODE_UNSPECIFIED, 2);

        // BEGIN USER START
        UserJourneyLogger.UserJourneySession session3 = mUserJourneyLogger
                .logUserJourneyBegin(11, USER_JOURNEY_USER_START);

        report1.captureAndAssert(mUserJourneyLogger, session3.mSessionId, 11,
                USER_LIFECYCLE_EVENT_START_USER,
                EVENT_STATE_BEGIN, ERROR_CODE_UNSPECIFIED, 3);


        // FINISH USER STOP
        final UserLifecycleJourneyReportedCaptor report2 = new UserLifecycleJourneyReportedCaptor();
        final UserInfo targetUser = new UserInfo(10, "test target user",
                UserInfo.FLAG_ADMIN | UserInfo.FLAG_FULL);
        mUserJourneyLogger.logUserJourneyFinish(-1, targetUser,
                USER_JOURNEY_USER_STOP);

        report1.captureAndAssert(mUserJourneyLogger, session2.mSessionId, 10,
                USER_LIFECYCLE_EVENT_STOP_USER,
                EVENT_STATE_FINISH, ERROR_CODE_UNSPECIFIED, 4);

        report2.captureAndAssert(mUserJourneyLogger, session2.mSessionId,
                USER_JOURNEY_USER_STOP, -1, 10,
                FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__USER_TYPE__FULL_SECONDARY,
                FULL_USER_ADMIN_FLAG, ERROR_CODE_UNSPECIFIED, 1);

        // FINISH USER START
        final UserInfo targetUser2 = new UserInfo(11, "test target user",
                UserInfo.FLAG_ADMIN | UserInfo.FLAG_FULL);
        mUserJourneyLogger.logUserJourneyFinish(10, targetUser2,
                USER_JOURNEY_USER_START);

        report1.captureAndAssert(mUserJourneyLogger, session3.mSessionId, 11,
                USER_LIFECYCLE_EVENT_START_USER,
                EVENT_STATE_FINISH, ERROR_CODE_UNSPECIFIED, 5);

        report2.captureAndAssert(mUserJourneyLogger, session3.mSessionId,
                USER_JOURNEY_USER_START, 10, 11,
                FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__USER_TYPE__FULL_SECONDARY,
                FULL_USER_ADMIN_FLAG, ERROR_CODE_UNSPECIFIED, 2);


        // FINISH USER SWITCH
        mUserJourneyLogger.logUserSwitchJourneyFinish(10, targetUser2);

        report1.captureAndAssert(mUserJourneyLogger, session.mSessionId, 11,
                UserJourneyLogger.USER_LIFECYCLE_EVENT_SWITCH_USER,
                EVENT_STATE_FINISH, ERROR_CODE_UNSPECIFIED, 6);

        report2.captureAndAssert(mUserJourneyLogger, session.mSessionId,
                UserJourneyLogger.USER_JOURNEY_USER_SWITCH_UI, 10, 11,
                FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__USER_TYPE__FULL_SECONDARY,
                0x00000402, ERROR_CODE_UNSPECIFIED, 3);
    }

    @Test
    public void testUserLifecycleJourney() {
        final long startTime = System.currentTimeMillis();
        final UserJourneyLogger.UserJourneySession session = mUserJourneyLogger
                .startSessionForDelayedJourney(10, USER_JOURNEY_USER_LIFECYCLE, startTime);


        final UserLifecycleJourneyReportedCaptor report = new UserLifecycleJourneyReportedCaptor();
        final UserInfo targetUser = new UserInfo(10, "test target user",
                UserInfo.FLAG_ADMIN | UserInfo.FLAG_FULL);
        mUserJourneyLogger.logDelayedUserJourneyFinishWithError(0, targetUser,
                USER_JOURNEY_USER_LIFECYCLE, ERROR_CODE_UNSPECIFIED);


        report.captureAndAssert(mUserJourneyLogger, session.mSessionId,
                USER_JOURNEY_USER_LIFECYCLE, 0, 10,
                FrameworkStatsLog.USER_LIFECYCLE_JOURNEY_REPORTED__USER_TYPE__FULL_SECONDARY,
                0x00000402, ERROR_CODE_UNSPECIFIED, 1);
        assertThat(report.mElapsedTime.getValue() > 0L).isTrue();
    }

    static class UserLifecycleJourneyReportedCaptor {
        ArgumentCaptor<Long> mSessionId = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Integer> mJourney = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> mOriginalUserId = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> mTargetUserId = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> mUserType = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> mUserFlags = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> mErrorCode = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Long> mElapsedTime = ArgumentCaptor.forClass(Long.class);

        public void captureAndAssert(UserJourneyLogger mUserJourneyLogger,
                long sessionId, int journey, int originalUserId,
                int targetUserId, int userType, int userFlags, int errorCode, int times) {
            verify(mUserJourneyLogger, times(times))
                    .writeUserLifecycleJourneyReported(mSessionId.capture(),
                            mJourney.capture(),
                            mOriginalUserId.capture(),
                            mTargetUserId.capture(),
                            mUserType.capture(),
                            mUserFlags.capture(),
                            mErrorCode.capture(),
                            mElapsedTime.capture());

            assertThat(mSessionId.getValue()).isEqualTo(sessionId);
            assertThat(mJourney.getValue()).isEqualTo(journey);
            assertThat(mOriginalUserId.getValue()).isEqualTo(originalUserId);
            assertThat(mTargetUserId.getValue()).isEqualTo(targetUserId);
            assertThat(mUserType.getValue()).isEqualTo(userType);
            assertThat(mUserFlags.getValue()).isEqualTo(userFlags);
            assertThat(mErrorCode.getValue()).isEqualTo(errorCode);
        }


        public void captureLogAndAssert(UserJourneyLogger mUserJourneyLogger,
                UserJourneyLogger.UserJourneySession session, int journey, int originalUserId,
                int targetUserId, int userType, int userFlags, int errorCode) {
            mUserJourneyLogger.logUserLifecycleJourneyReported(session, journey, originalUserId,
                    targetUserId, userType, userFlags, errorCode);

            captureAndAssert(mUserJourneyLogger, session.mSessionId, journey, originalUserId,
                    targetUserId, userType, userFlags, errorCode, 1);
        }
    }


    static class UserLifecycleEventOccurredCaptor {
        ArgumentCaptor<Long> mSessionId = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Integer> mTargetUserId = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> mEvent = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> mStste = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> mErrorCode = ArgumentCaptor.forClass(Integer.class);


        public void captureAndAssert(UserJourneyLogger mUserJourneyLogger,
                long sessionId, int targetUserId, int event, int state, int errorCode, int times) {
            verify(mUserJourneyLogger, times(times))
                    .writeUserLifecycleEventOccurred(mSessionId.capture(),
                            mTargetUserId.capture(),
                            mEvent.capture(),
                            mStste.capture(),
                            mErrorCode.capture());

            assertThat(mSessionId.getValue()).isEqualTo(sessionId);
            assertThat(mTargetUserId.getValue()).isEqualTo(targetUserId);
            assertThat(mEvent.getValue()).isEqualTo(event);
            assertThat(mStste.getValue()).isEqualTo(state);
            assertThat(mErrorCode.getValue()).isEqualTo(errorCode);
        }


        public void captureLogAndAssert(UserJourneyLogger mUserJourneyLogger,
                UserJourneyLogger.UserJourneySession session, int targetUserId, int event,
                int state, int errorCode) {
            mUserJourneyLogger.logUserLifecycleEventOccurred(session, targetUserId, event,
                    state, errorCode);

            captureAndAssert(mUserJourneyLogger, session.mSessionId, targetUserId, event,
                    state, errorCode, 1);
        }
    }
}