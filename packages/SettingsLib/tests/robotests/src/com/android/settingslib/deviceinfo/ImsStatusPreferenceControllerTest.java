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

package com.android.settingslib.deviceinfo;

import static org.mockito.Mockito.doReturn;

import android.content.Context;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceScreen;

import com.android.settingslib.SettingsLibRobolectricTestRunner;
import com.android.settingslib.TestConfig;
import com.android.settingslib.core.lifecycle.Lifecycle;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.annotation.Config;

@RunWith(SettingsLibRobolectricTestRunner.class)
@Config(manifest = TestConfig.MANIFEST_PATH, sdk = TestConfig.SDK_VERSION)
public class ImsStatusPreferenceControllerTest {
    @Mock
    private Context mContext;
    @Mock
    private Lifecycle mLifecycle;
    @Mock
    private PreferenceScreen mScreen;
    @Mock
    private Preference mPreference;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        doReturn(mPreference).when(mScreen)
                .findPreference(AbstractImsStatusPreferenceController.KEY_IMS_REGISTRATION_STATE);
    }

    @Test
    public void dummyTest() {
        // do nothing
    }

    /* TODO: need robolectric with API >24 support for this to work
    @Test
    @Config(shadows = ShadowSubscriptionManager.class)
    public void testIsAvailable() {
        CarrierConfigManager carrierConfigManager = mock(CarrierConfigManager.class);
        doReturn(carrierConfigManager).when(mContext).getSystemService(CarrierConfigManager.class);

        PersistableBundle config = new PersistableBundle(1);
        config.putBoolean(CarrierConfigManager.KEY_SHOW_IMS_REGISTRATION_STATUS_BOOL, true);
        doReturn(config).when(carrierConfigManager).getConfigForSubId(anyInt());

        final AbstractImsStatusPreferenceController imsStatusPreferenceController =
                new ConcreteImsStatusPreferenceController(mContext, mLifecycle);

        assertTrue("Should be available when IMS registration is true",
                imsStatusPreferenceController.isAvailable());

        config.putBoolean(CarrierConfigManager.KEY_SHOW_IMS_REGISTRATION_STATUS_BOOL, false);

        assertFalse("Should not be available when IMS registration is false",
                imsStatusPreferenceController.isAvailable());

        doReturn(null).when(carrierConfigManager).getConfigForSubId(anyInt());

        assertFalse("Should not be available when config is null",
                imsStatusPreferenceController.isAvailable());

        doReturn(null).when(mContext).getSystemService(CarrierConfigManager.class);

        assertFalse("Should not be available when CarrierConfigManager service is null",
                imsStatusPreferenceController.isAvailable());
    }

    @Implements(SubscriptionManager.class)
    public static class ShadowSubscriptionManager {
        @Implementation
        public static int getDefaultDataSubscriptionId() {
            return 1234;
        }
    }
    */

    private static class ConcreteImsStatusPreferenceController
            extends AbstractImsStatusPreferenceController {

        public ConcreteImsStatusPreferenceController(Context context,
                Lifecycle lifecycle) {
            super(context, lifecycle);
        }
    }
}
