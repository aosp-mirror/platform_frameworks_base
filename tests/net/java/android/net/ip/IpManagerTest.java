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

package android.net.ip;

import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.os.INetworkManagementService;
import android.provider.Settings;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.test.mock.MockContentResolver;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.R;

import org.junit.Before;
import org.junit.runner.RunWith;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for IpManager.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class IpManagerTest {
    private static final int DEFAULT_AVOIDBADWIFI_CONFIG_VALUE = 1;

    @Mock private Context mContext;
    @Mock private INetworkManagementService mNMService;
    @Mock private Resources mResources;
    private MockContentResolver mContentResolver;

    @Before public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mContext.getResources()).thenReturn(mResources);
        when(mResources.getInteger(R.integer.config_networkAvoidBadWifi))
                .thenReturn(DEFAULT_AVOIDBADWIFI_CONFIG_VALUE);

        mContentResolver = new MockContentResolver();
        mContentResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        when(mContext.getContentResolver()).thenReturn(mContentResolver);
    }

    @Test
    public void testNullCallbackDoesNotThrow() throws Exception {
        final IpManager ipm = new IpManager(mContext, "lo", null, mNMService);
    }

    @Test
    public void testInvalidInterfaceDoesNotThrow() throws Exception {
        final IpManager.Callback cb = new IpManager.Callback();
        final IpManager ipm = new IpManager(mContext, "test_wlan0", cb, mNMService);
    }
}
