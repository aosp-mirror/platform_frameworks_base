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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import static java.util.Collections.emptySet;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;

import com.android.internal.R;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;

public class AuthenticationStatsCollectorTest {

    private AuthenticationStatsCollector mAuthenticationStatsCollector;
    private static final float FRR_THRESHOLD = 0.2f;
    private static final int USER_ID_1 = 1;

    @Mock
    private Context mContext;
    @Mock
    private Resources mResources;
    @Mock
    private SharedPreferences mSharedPreferences;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mContext.getResources()).thenReturn(mResources);
        when(mResources.getFraction(R.fraction.config_biometricNotificationFrrThreshold, 1, 1))
                .thenReturn(FRR_THRESHOLD);
        when(mContext.getSharedPreferences(any(File.class), anyInt()))
                .thenReturn(mSharedPreferences);
        when(mSharedPreferences.getStringSet(anyString(), anySet())).thenReturn(emptySet());

        mAuthenticationStatsCollector = new AuthenticationStatsCollector(mContext,
                0 /* modality */);
    }


    @Test
    public void authenticate_authenticationSucceeded_mapShouldBeUpdated() {
        // Assert that the user doesn't exist in the map initially.
        assertThat(mAuthenticationStatsCollector.getAuthenticationStatsForUser(USER_ID_1)).isNull();

        mAuthenticationStatsCollector.authenticate(USER_ID_1, true /* authenticated*/);

        AuthenticationStats authenticationStats =
                mAuthenticationStatsCollector.getAuthenticationStatsForUser(USER_ID_1);
        assertThat(authenticationStats.getUserId()).isEqualTo(USER_ID_1);
        assertThat(authenticationStats.getTotalAttempts()).isEqualTo(1);
        assertThat(authenticationStats.getRejectedAttempts()).isEqualTo(0);
        assertThat(authenticationStats.getEnrollmentNotifications()).isEqualTo(0);
    }

    @Test
    public void authenticate_authenticationFailed_mapShouldBeUpdated() {
        // Assert that the user doesn't exist in the map initially.
        assertThat(mAuthenticationStatsCollector.getAuthenticationStatsForUser(USER_ID_1)).isNull();

        mAuthenticationStatsCollector.authenticate(USER_ID_1, false /* authenticated*/);

        AuthenticationStats authenticationStats =
                mAuthenticationStatsCollector.getAuthenticationStatsForUser(USER_ID_1);

        assertThat(authenticationStats.getUserId()).isEqualTo(USER_ID_1);
        assertThat(authenticationStats.getTotalAttempts()).isEqualTo(1);
        assertThat(authenticationStats.getRejectedAttempts()).isEqualTo(1);
        assertThat(authenticationStats.getEnrollmentNotifications()).isEqualTo(0);
    }
}
