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

package com.android.server.appop;

import static android.app.AppOpsManager.OP_COARSE_LOCATION;
import static android.app.AppOpsManager.OP_FINE_LOCATION;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.os.Handler;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.dx.mockito.inline.extended.StaticMockitoSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.quality.Strictness;

public class AppOpsLegacyRestrictionsTest {
    private static final int UID_ANY = -2;

    final Object mClientToken = new Object();
    final int mUserId1 = 65001;
    final int mUserId2 = 65002;
    final int mOpCode1 = OP_COARSE_LOCATION;
    final int mOpCode2 = OP_FINE_LOCATION;
    final String mPackageName = "com.example.test";
    final String mAttributionTag = "test-attribution-tag";

    StaticMockitoSession mSession;

    @Mock
    AppOpsService.Constants mConstants;

    @Mock
    Context mContext;

    @Mock
    Handler mHandler;

    @Mock
    AppOpsRestrictions.AppOpsRestrictionRemovedListener mRestrictionRemovedListener;

    AppOpsRestrictions mAppOpsRestrictions;

    @Before
    public void setUp() {
        mSession = ExtendedMockito.mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .startMocking();
        mConstants.TOP_STATE_SETTLE_TIME = 10 * 1000L;
        mConstants.FG_SERVICE_STATE_SETTLE_TIME = 5 * 1000L;
        mConstants.BG_STATE_SETTLE_TIME = 1 * 1000L;
        Mockito.when(mHandler.post(Mockito.any(Runnable.class))).then(inv -> {
            Runnable r = inv.getArgument(0);
            r.run();
            return true;
        });
        mAppOpsRestrictions = new AppOpsRestrictionsImpl(mContext, mHandler,
                mRestrictionRemovedListener);
    }

    @After
    public void tearDown() {
        mSession.finishMocking();
    }

    @Test
    public void testSetAndGetSingleGlobalRestriction() {
        // Verify: empty
        assertEquals(false, mAppOpsRestrictions.hasGlobalRestrictions(mClientToken));
        assertEquals(false, mAppOpsRestrictions.getGlobalRestriction(mClientToken, mOpCode1));
        // Act: add a restriction
        assertEquals(true, mAppOpsRestrictions.setGlobalRestriction(mClientToken, mOpCode1, true));
        // Act: add same restriction again (expect false; should be no-op)
        assertEquals(false, mAppOpsRestrictions.setGlobalRestriction(mClientToken, mOpCode1, true));
        // Verify: not empty
        assertEquals(true, mAppOpsRestrictions.hasGlobalRestrictions(mClientToken));
        assertEquals(true, mAppOpsRestrictions.getGlobalRestriction(mClientToken, mOpCode1));
        // Act: remove the restriction
        assertEquals(true, mAppOpsRestrictions.setGlobalRestriction(mClientToken, mOpCode1, false));
        // Act: remove same restriction again (expect false; should be no-op)
        assertEquals(false,
                mAppOpsRestrictions.setGlobalRestriction(mClientToken, mOpCode1, false));
        // Verify: empty
        assertEquals(false, mAppOpsRestrictions.hasGlobalRestrictions(mClientToken));
        assertEquals(false, mAppOpsRestrictions.getGlobalRestriction(mClientToken, mOpCode1));
    }

    @Test
    public void testSetAndGetDoubleGlobalRestriction() {
        // Act: add opCode1 restriction
        assertEquals(true, mAppOpsRestrictions.setGlobalRestriction(mClientToken, mOpCode1, true));
        // Act: add opCode2 restriction
        assertEquals(true, mAppOpsRestrictions.setGlobalRestriction(mClientToken, mOpCode2, true));
        // Verify: not empty
        assertEquals(true, mAppOpsRestrictions.hasGlobalRestrictions(mClientToken));
        // Act: remove opCode1 restriction
        assertEquals(true, mAppOpsRestrictions.setGlobalRestriction(mClientToken, mOpCode1, false));
        // Verify: not empty
        assertEquals(true, mAppOpsRestrictions.hasGlobalRestrictions(mClientToken));
        // Act: remove opCode2 restriction
        assertEquals(true, mAppOpsRestrictions.setGlobalRestriction(mClientToken, mOpCode2, false));
        // Verify: empty
        assertEquals(false, mAppOpsRestrictions.hasGlobalRestrictions(mClientToken));
    }

    @Test
    public void testClearGlobalRestrictions() {
        // Act: clear (should be no-op)
        assertEquals(false, mAppOpsRestrictions.clearGlobalRestrictions(mClientToken));
        // Act: add opCodes
        assertEquals(true, mAppOpsRestrictions.setGlobalRestriction(mClientToken, mOpCode1, true));
        assertEquals(true, mAppOpsRestrictions.setGlobalRestriction(mClientToken, mOpCode2, true));
        // Verify: not empty
        assertEquals(true, mAppOpsRestrictions.hasGlobalRestrictions(mClientToken));
        // Act: clear
        assertEquals(true, mAppOpsRestrictions.clearGlobalRestrictions(mClientToken));
        // Verify: empty
        assertEquals(false, mAppOpsRestrictions.hasGlobalRestrictions(mClientToken));
        // Act: clear (should be no-op)
        assertEquals(false, mAppOpsRestrictions.clearGlobalRestrictions(mClientToken));
    }

    @Test
    public void testSetAndGetSingleUserRestriction() {
        // Verify: empty
        assertEquals(false, mAppOpsRestrictions.hasUserRestrictions(mClientToken));
        assertEquals(false, mAppOpsRestrictions.getUserRestriction(
                mClientToken, mUserId1, mOpCode1, mPackageName, mAttributionTag, false));
        assertEquals(false, mAppOpsRestrictions.getUserRestriction(
                mClientToken, mUserId1, mOpCode1, mPackageName, mAttributionTag, true));
        // Act: add a restriction
        assertEquals(true, mAppOpsRestrictions.setUserRestriction(
                mClientToken, mUserId1, mOpCode1, true, null));
        // Act: add the restriction again (should be no-op)
        assertEquals(false, mAppOpsRestrictions.setUserRestriction(
                mClientToken, mUserId1, mOpCode1, true, null));
        // Verify: not empty
        assertEquals(true, mAppOpsRestrictions.hasUserRestrictions(mClientToken));
        assertEquals(true, mAppOpsRestrictions.getUserRestriction(
                mClientToken, mUserId1, mOpCode1, mPackageName, mAttributionTag, false));
        assertEquals(true, mAppOpsRestrictions.getUserRestriction(
                mClientToken, mUserId1, mOpCode1, mPackageName, mAttributionTag, true));
        // Act: remove the restriction
        assertEquals(true, mAppOpsRestrictions.setUserRestriction(
                mClientToken, mUserId1, mOpCode1, false, null));
        // Act: remove the restriction again (should be no-op)
        assertEquals(false, mAppOpsRestrictions.setUserRestriction(
                mClientToken, mUserId1, mOpCode1, false, null));
        // Verify: empty
        assertEquals(false, mAppOpsRestrictions.hasUserRestrictions(mClientToken));
        assertEquals(false, mAppOpsRestrictions.getUserRestriction(
                mClientToken, mUserId1, mOpCode1, mPackageName, mAttributionTag, false));
    }

    @Test
    public void testSetAndGetDoubleUserRestriction() {
        // Act: add opCode1 restriction
        assertEquals(true, mAppOpsRestrictions.setUserRestriction(
                mClientToken, mUserId1, mOpCode1, true, null));
        // Act: add opCode2 restriction
        assertEquals(true, mAppOpsRestrictions.setUserRestriction(
                mClientToken, mUserId1, mOpCode2, true, null));
        // Verify: not empty
        assertEquals(true, mAppOpsRestrictions.hasUserRestrictions(mClientToken));
        assertEquals(true, mAppOpsRestrictions.getUserRestriction(
                mClientToken, mUserId1, mOpCode1, mPackageName, mAttributionTag, false));
        assertEquals(true, mAppOpsRestrictions.getUserRestriction(
                mClientToken, mUserId1, mOpCode1, mPackageName, mAttributionTag, true));
        assertEquals(true, mAppOpsRestrictions.getUserRestriction(
                mClientToken, mUserId1, mOpCode2, mPackageName, mAttributionTag, false));
        assertEquals(true, mAppOpsRestrictions.getUserRestriction(
                mClientToken, mUserId1, mOpCode2, mPackageName, mAttributionTag, true));
        // Act: remove opCode1 restriction
        assertEquals(true, mAppOpsRestrictions.setUserRestriction(
                mClientToken, mUserId1, mOpCode1, false, null));
        // Verify: opCode1 is removed but not opCode22
        assertEquals(true, mAppOpsRestrictions.hasUserRestrictions(mClientToken));
        assertEquals(false, mAppOpsRestrictions.getUserRestriction(
                mClientToken, mUserId1, mOpCode1, mPackageName, mAttributionTag, false));
        assertEquals(false, mAppOpsRestrictions.getUserRestriction(
                mClientToken, mUserId1, mOpCode1, mPackageName, mAttributionTag, true));
        assertEquals(true, mAppOpsRestrictions.getUserRestriction(
                mClientToken, mUserId1, mOpCode2, mPackageName, mAttributionTag, false));
        assertEquals(true, mAppOpsRestrictions.getUserRestriction(
                mClientToken, mUserId1, mOpCode2, mPackageName, mAttributionTag, true));
        // Act: remove opCode2 restriction
        assertEquals(true, mAppOpsRestrictions.setUserRestriction(
                mClientToken, mUserId1, mOpCode2, false, null));
        // Verify: empty
        assertEquals(false, mAppOpsRestrictions.getUserRestriction(
                mClientToken, mUserId1, mOpCode2, mPackageName, mAttributionTag, false));
        assertEquals(false, mAppOpsRestrictions.getUserRestriction(
                mClientToken, mUserId1, mOpCode2, mPackageName, mAttributionTag, true));
        assertEquals(false, mAppOpsRestrictions.hasUserRestrictions(mClientToken));
    }

    @Test
    public void testClearUserRestrictionsAllUsers() {
        // Act: clear (should be no-op)
        assertEquals(false, mAppOpsRestrictions.clearUserRestrictions(mClientToken));
        // Act: add restrictions
        assertEquals(true, mAppOpsRestrictions.setUserRestriction(
                mClientToken, mUserId1, mOpCode1, true, null));
        assertEquals(true, mAppOpsRestrictions.setUserRestriction(
                mClientToken, mUserId1, mOpCode2, true, null));
        assertEquals(true, mAppOpsRestrictions.setUserRestriction(
                mClientToken, mUserId2, mOpCode1, true, null));
        assertEquals(true, mAppOpsRestrictions.setUserRestriction(
                mClientToken, mUserId2, mOpCode2, true, null));
        // Verify: not empty
        assertEquals(true, mAppOpsRestrictions.hasUserRestrictions(mClientToken));
        // Act: clear all user restrictions
        assertEquals(true, mAppOpsRestrictions.clearUserRestrictions(mClientToken));
        // Verify: empty
        assertEquals(false, mAppOpsRestrictions.hasUserRestrictions(mClientToken));
    }

    @Test
    public void testClearUserRestrictionsSpecificUsers() {
        // Act: clear (should be no-op)
        assertEquals(false, mAppOpsRestrictions.clearUserRestrictions(mClientToken, mUserId1));
        // Act: add restrictions
        assertEquals(true, mAppOpsRestrictions.setUserRestriction(
                mClientToken, mUserId1, mOpCode1, true, null));
        assertEquals(true, mAppOpsRestrictions.setUserRestriction(
                mClientToken, mUserId1, mOpCode2, true, null));
        assertEquals(true, mAppOpsRestrictions.setUserRestriction(
                mClientToken, mUserId2, mOpCode1, true, null));
        assertEquals(true, mAppOpsRestrictions.setUserRestriction(
                mClientToken, mUserId2, mOpCode2, true, null));
        // Verify: not empty
        assertEquals(true, mAppOpsRestrictions.hasUserRestrictions(mClientToken));
        // Act: clear userId1
        assertEquals(true, mAppOpsRestrictions.clearUserRestrictions(mClientToken, mUserId1));
        // Act: clear userId1 again (should be no-op)
        assertEquals(false, mAppOpsRestrictions.clearUserRestrictions(mClientToken, mUserId1));
        // Verify:  userId1 is removed but not userId2
        assertEquals(false, mAppOpsRestrictions.getUserRestriction(
                mClientToken, mUserId1, mOpCode1, mPackageName, mAttributionTag, false));
        assertEquals(true, mAppOpsRestrictions.getUserRestriction(
                mClientToken, mUserId2, mOpCode2, mPackageName, mAttributionTag, false));
        // Act: clear userId2
        assertEquals(true, mAppOpsRestrictions.clearUserRestrictions(mClientToken, mUserId2));
        // Act: clear userId2 again (should be no-op)
        assertEquals(false, mAppOpsRestrictions.clearUserRestrictions(mClientToken, mUserId2));
        // Verify: empty
        assertEquals(false, mAppOpsRestrictions.hasUserRestrictions(mClientToken));
    }

    @Test
    public void testNotify() {
        mAppOpsRestrictions.setUserRestriction(mClientToken, mUserId1, mOpCode1, true, null);
        mAppOpsRestrictions.clearUserRestrictions(mClientToken);
        Mockito.verify(mRestrictionRemovedListener, Mockito.times(1))
                .onAppOpsRestrictionRemoved(mOpCode1);
    }
}
