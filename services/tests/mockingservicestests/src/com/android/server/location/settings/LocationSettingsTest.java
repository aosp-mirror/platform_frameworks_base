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

package com.android.server.location.settings;

import static android.content.pm.PackageManager.FEATURE_AUTOMOTIVE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.platform.test.annotations.Presubmit;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.io.File;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class LocationSettingsTest {

    private @Mock Context mContext;
    private @Mock Resources mResources;
    private @Mock PackageManager mPackageManager;

    private LocationSettings mLocationSettings;

    @Before
    public void setUp() {
        initMocks(this);

        doReturn(mResources).when(mContext).getResources();
        doReturn(mPackageManager).when(mContext).getPackageManager();
        doReturn(true).when(mPackageManager).hasSystemFeature(FEATURE_AUTOMOTIVE);

        resetLocationSettings();
    }

    @After
    public void tearDown() throws Exception {
        mLocationSettings.deleteFiles();
    }

    private void resetLocationSettings() {
        mLocationSettings = new LocationSettings(mContext) {
            @Override
            protected File getUserSettingsDir(int userId) {
                return ApplicationProvider.getApplicationContext().getCacheDir();
            }
        };
    }

    @Test
    public void testLoadDefaults() {
        doReturn(true).when(mResources).getBoolean(R.bool.config_defaultAdasGnssLocationEnabled);
        assertThat(mLocationSettings.getUserSettings(1).isAdasGnssLocationEnabled()).isTrue();

        doReturn(false).when(mResources).getBoolean(R.bool.config_defaultAdasGnssLocationEnabled);
        assertThat(mLocationSettings.getUserSettings(2).isAdasGnssLocationEnabled()).isFalse();
    }

    @Test
    public void testUpdate() {
        doReturn(false).when(mResources).getBoolean(R.bool.config_defaultAdasGnssLocationEnabled);
        mLocationSettings.updateUserSettings(1,
                settings -> settings.withAdasGnssLocationEnabled(true));
        assertThat(mLocationSettings.getUserSettings(1).isAdasGnssLocationEnabled()).isTrue();

        mLocationSettings.updateUserSettings(1,
                settings -> settings.withAdasGnssLocationEnabled(false));
        assertThat(mLocationSettings.getUserSettings(1).isAdasGnssLocationEnabled()).isFalse();
    }

    @Test
    public void testSerialization() throws Exception {
        doReturn(false).when(mResources).getBoolean(R.bool.config_defaultAdasGnssLocationEnabled);
        mLocationSettings.updateUserSettings(1,
                settings -> settings.withAdasGnssLocationEnabled(true));
        assertThat(mLocationSettings.getUserSettings(1).isAdasGnssLocationEnabled()).isTrue();

        mLocationSettings.flushFiles();
        resetLocationSettings();
        assertThat(mLocationSettings.getUserSettings(1).isAdasGnssLocationEnabled()).isTrue();
    }

    @Test
    public void testListeners() {
        doReturn(false).when(mResources).getBoolean(R.bool.config_defaultAdasGnssLocationEnabled);
        LocationSettings.LocationUserSettingsListener listener = mock(
                LocationSettings.LocationUserSettingsListener.class);

        mLocationSettings.registerLocationUserSettingsListener(listener);

        ArgumentCaptor<LocationUserSettings> oldCaptor = ArgumentCaptor.forClass(
                LocationUserSettings.class);
        ArgumentCaptor<LocationUserSettings> newCaptor = ArgumentCaptor.forClass(
                LocationUserSettings.class);
        mLocationSettings.updateUserSettings(1,
                settings -> settings.withAdasGnssLocationEnabled(true));
        verify(listener, timeout(500).times(1)).onLocationUserSettingsChanged(eq(1),
                oldCaptor.capture(), newCaptor.capture());
        assertThat(oldCaptor.getValue().isAdasGnssLocationEnabled()).isFalse();
        assertThat(newCaptor.getValue().isAdasGnssLocationEnabled()).isTrue();

        oldCaptor = ArgumentCaptor.forClass(LocationUserSettings.class);
        newCaptor = ArgumentCaptor.forClass(LocationUserSettings.class);
        mLocationSettings.updateUserSettings(1,
                settings -> settings.withAdasGnssLocationEnabled(false));
        verify(listener, timeout(500).times(2)).onLocationUserSettingsChanged(eq(1),
                oldCaptor.capture(), newCaptor.capture());
        assertThat(oldCaptor.getValue().isAdasGnssLocationEnabled()).isTrue();
        assertThat(newCaptor.getValue().isAdasGnssLocationEnabled()).isFalse();

        mLocationSettings.unregisterLocationUserSettingsListener(listener);
        mLocationSettings.updateUserSettings(1,
                settings -> settings.withAdasGnssLocationEnabled(true));
        verify(listener, after(500).times(2)).onLocationUserSettingsChanged(anyInt(), any(), any());
    }

    @Test
    public void testNonAutomotive() {
        doReturn(false).when(mPackageManager).hasSystemFeature(FEATURE_AUTOMOTIVE);
        doReturn(true).when(mResources).getBoolean(R.bool.config_defaultAdasGnssLocationEnabled);

        LocationSettings.LocationUserSettingsListener listener = mock(
                LocationSettings.LocationUserSettingsListener.class);
        mLocationSettings.registerLocationUserSettingsListener(listener);

        assertThat(mLocationSettings.getUserSettings(1).isAdasGnssLocationEnabled()).isFalse();
        mLocationSettings.updateUserSettings(1,
                settings -> settings.withAdasGnssLocationEnabled(true));
        assertThat(mLocationSettings.getUserSettings(1).isAdasGnssLocationEnabled()).isFalse();
        verify(listener, after(500).never()).onLocationUserSettingsChanged(anyInt(), any(), any());
    }
}
