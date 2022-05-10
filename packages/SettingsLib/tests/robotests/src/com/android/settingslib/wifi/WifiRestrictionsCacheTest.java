/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.settingslib.wifi;

import static android.os.UserManager.DISALLOW_CONFIG_WIFI;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.Bundle;
import android.os.UserManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class WifiRestrictionsCacheTest {

    private static final int USER_OWNER = 0;
    private static final int USER_1 = 1;
    private static final int USER_2 = 2;
    private static final int USER_3 = 3;
    private static final int USER_GUEST = 10;

    @Rule
    public final MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Mock
    UserManager mUserManager;
    @Mock
    Bundle mUserRestrictionsOwner;
    @Mock
    Bundle mUserRestrictionsGuest;

    private Context mContext;
    private WifiRestrictionsCache mWifiRestrictionsCacheOwner;
    private WifiRestrictionsCache mWifiRestrictionsCacheGuest;

    @Before
    public void setUp() {
        mContext = spy(ApplicationProvider.getApplicationContext());
        when(mContext.getSystemService(UserManager.class)).thenReturn(mUserManager);

        when(mContext.getUserId()).thenReturn(USER_OWNER);
        when(mUserManager.getUserRestrictions()).thenReturn(mUserRestrictionsOwner);
        when(mUserRestrictionsOwner.getBoolean(anyString())).thenReturn(false);
        mWifiRestrictionsCacheOwner = WifiRestrictionsCache.getInstance(mContext);

        when(mContext.getUserId()).thenReturn(USER_GUEST);
        when(mUserManager.getUserRestrictions()).thenReturn(mUserRestrictionsGuest);
        when(mUserRestrictionsGuest.getBoolean(anyString())).thenReturn(true);
        mWifiRestrictionsCacheGuest = WifiRestrictionsCache.getInstance(mContext);
    }

    @After
    public void tearDown() {
        WifiRestrictionsCache.clearInstance();
    }

    @Test
    public void getInstance_sameUserId_sameInstance() {
        when(mContext.getUserId()).thenReturn(USER_OWNER);
        WifiRestrictionsCache instance1 = WifiRestrictionsCache.getInstance(mContext);

        WifiRestrictionsCache instance2 = WifiRestrictionsCache.getInstance(mContext);

        assertThat(instance1).isEqualTo(instance2);
    }

    @Test
    public void getInstance_diffUserId_diffInstance() {
        when(mContext.getUserId()).thenReturn(USER_OWNER);
        WifiRestrictionsCache instance1 = WifiRestrictionsCache.getInstance(mContext);

        when(mContext.getUserId()).thenReturn(USER_GUEST);
        WifiRestrictionsCache instance2 = WifiRestrictionsCache.getInstance(mContext);

        assertThat(instance1).isNotEqualTo(instance2);
    }

    @Test
    public void clearInstance_instanceShouldBeEmpty() {
        WifiRestrictionsCache.clearInstance();

        assertThat(WifiRestrictionsCache.sInstances.size()).isEqualTo(0);
    }

    @Test
    public void getRestriction_firstTime_getFromSystem() {
        Bundle userRestrictions = mock(Bundle.class);
        WifiRestrictionsCache wifiRestrictionsCache = mockInstance(USER_1, userRestrictions);

        wifiRestrictionsCache.getRestriction(DISALLOW_CONFIG_WIFI);

        verify(userRestrictions).getBoolean(DISALLOW_CONFIG_WIFI);
    }

    @Test
    public void getRestriction_secondTime_notGetFromSystem() {
        Bundle userRestrictions = mock(Bundle.class);
        WifiRestrictionsCache wifiRestrictionsCache = mockInstance(USER_2, userRestrictions);
        // First time to get the restriction value
        wifiRestrictionsCache.getRestriction(DISALLOW_CONFIG_WIFI);
        reset(userRestrictions);

        // Second time to get the restriction value
        wifiRestrictionsCache.getRestriction(DISALLOW_CONFIG_WIFI);

        verify(userRestrictions, never()).getBoolean(DISALLOW_CONFIG_WIFI);
    }

    @Test
    public void clearRestrictions_shouldGetRestrictionFromSystemAgain() {
        Bundle userRestrictions = mock(Bundle.class);
        WifiRestrictionsCache wifiRestrictionsCache = mockInstance(USER_3, userRestrictions);
        // First time to get the restriction value
        wifiRestrictionsCache.getRestriction(DISALLOW_CONFIG_WIFI);
        reset(userRestrictions);

        // Clear the cache and then second time to get the restriction value
        wifiRestrictionsCache.clearRestrictions();
        wifiRestrictionsCache.getRestriction(DISALLOW_CONFIG_WIFI);

        verify(userRestrictions).getBoolean(DISALLOW_CONFIG_WIFI);
    }

    @Test
    public void isConfigWifiAllowed_ownerUser_returnTrue() {
        assertThat(mWifiRestrictionsCacheOwner.isConfigWifiAllowed()).isTrue();
    }

    @Test
    public void isConfigWifiAllowed_guestUser_returnFalse() {
        assertThat(mWifiRestrictionsCacheGuest.isConfigWifiAllowed()).isFalse();
    }

    private WifiRestrictionsCache mockInstance(int userId, Bundle userRestrictions) {
        when(mContext.getUserId()).thenReturn(userId);
        when(mUserManager.getUserRestrictions()).thenReturn(userRestrictions);
        return WifiRestrictionsCache.getInstance(mContext);
    }
}
