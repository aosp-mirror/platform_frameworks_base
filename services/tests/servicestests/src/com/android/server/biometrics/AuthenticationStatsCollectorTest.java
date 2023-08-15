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

package com.android.server.biometrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;

import com.android.internal.R;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class AuthenticationStatsCollectorTest {

    private AuthenticationStatsCollector mAuthenticationStatsCollector;
    private static final float FRR_THRESHOLD = 0.2f;
    private static final int USER_ID_1 = 1;

    @Mock
    private Context mContext;
    @Mock
    private Resources mResources;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mContext.getResources()).thenReturn(mResources);
        when(mResources.getFraction(R.fraction.config_biometricNotificationFrrThreshold, 1, 1))
                .thenReturn(FRR_THRESHOLD);

        mAuthenticationStatsCollector = new AuthenticationStatsCollector(mContext,
                0 /* modality */);
    }


    @Test
    public void authenticate_authenticationSucceeded_mapShouldBeUpdated() {
        // Assert that the user doesn't exist in the map initially.
        assertNull(mAuthenticationStatsCollector.getAuthenticationStatsForUser(USER_ID_1));

        mAuthenticationStatsCollector.authenticate(USER_ID_1, true /* authenticated*/);

        AuthenticationStats authenticationStats =
                mAuthenticationStatsCollector.getAuthenticationStatsForUser(USER_ID_1);
        assertEquals(USER_ID_1, authenticationStats.getUserId());
        assertEquals(1, authenticationStats.getTotalAttempts());
        assertEquals(0, authenticationStats.getRejectedAttempts());
        assertEquals(0, authenticationStats.getEnrollmentNotifications());
    }

    @Test
    public void authenticate_authenticationFailed_mapShouldBeUpdated() {
        // Assert that the user doesn't exist in the map initially.
        assertNull(mAuthenticationStatsCollector.getAuthenticationStatsForUser(USER_ID_1));

        mAuthenticationStatsCollector.authenticate(USER_ID_1, false /* authenticated*/);

        AuthenticationStats authenticationStats =
                mAuthenticationStatsCollector.getAuthenticationStatsForUser(USER_ID_1);
        assertEquals(USER_ID_1, authenticationStats.getUserId());
        assertEquals(1, authenticationStats.getTotalAttempts());
        assertEquals(1, authenticationStats.getRejectedAttempts());
        assertEquals(0, authenticationStats.getEnrollmentNotifications());
    }
}
