/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.net.wifi.WifiManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class AccessPointPreferenceTest {

    private Context mContext;

    @Mock
    private AccessPoint mockAccessPoint;
    @Mock
    private AccessPointPreference.UserBadgeCache mockUserBadgeCache;
    @Mock
    private AccessPointPreference.IconInjector mockIconInjector;

    private AccessPointPreference createWithAccessPoint(AccessPoint accessPoint) {
        return new AccessPointPreference(accessPoint, mContext, mockUserBadgeCache,
                0, true, null, -1, mockIconInjector);
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = spy(ApplicationProvider.getApplicationContext());
        when(mContext.getSystemService(Context.WIFI_SERVICE)).thenReturn(mock(WifiManager.class));

        when(mockIconInjector.getIcon(anyInt())).thenReturn(new ColorDrawable());
    }

    @Test
    public void refresh_openNetwork_updateContentDescription() {
        final String ssid = "ssid";
        final String summary = "connected";
        final int security = AccessPoint.SECURITY_WEP;
        final AccessPoint ap = new TestAccessPointBuilder(mContext)
                .setSsid(ssid)
                .setSecurity(security)
                .build();
        final AccessPointPreference pref = mock(AccessPointPreference.class);
        when(pref.getTitle()).thenReturn(ssid);
        when(pref.getSummary()).thenReturn(summary);

        assertThat(AccessPointPreference.buildContentDescription(
                RuntimeEnvironment.application, pref, ap))
                .isEqualTo("ssid,connected,Wifi disconnected.,Secure network");
    }

    @Test
    public void refresh_shouldUpdateIcon() {
        int level = 1;
        when(mockAccessPoint.getSpeed()).thenReturn(0);
        when(mockAccessPoint.getLevel()).thenReturn(level);

        AccessPointPreference pref = createWithAccessPoint(mockAccessPoint);
        pref.refresh();

        verify(mockIconInjector).getIcon(level);
    }

    @Test
    public void refresh_setTitle_shouldUseSsidString() {
        final String ssid = "ssid";
        final String summary = "connected";
        final int security = AccessPoint.SECURITY_WEP;
        final AccessPoint ap = new TestAccessPointBuilder(mContext)
                .setSsid(ssid)
                .setSecurity(security)
                .build();
        final AccessPointPreference preference = mock(AccessPointPreference.class);

        AccessPointPreference.setTitle(preference, ap);
        verify(preference).setTitle(ssid);
    }
}
