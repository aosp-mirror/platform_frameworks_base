/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net;

import static org.mockito.Mockito.mock;

import android.test.mock.MockContext;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link VpnManager}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class VpnManagerTest {
    private static final String VPN_PROFILE_KEY = "KEY";

    private IConnectivityManager mMockCs;
    private VpnManager mVpnManager;
    private final MockContext mMockContext =
            new MockContext() {
                @Override
                public String getOpPackageName() {
                    return "fooPackage";
                }
            };

    @Before
    public void setUp() throws Exception {
        mMockCs = mock(IConnectivityManager.class);
        mVpnManager = new VpnManager(mMockContext, mMockCs);
    }

    @Test
    public void testProvisionVpnProfile() throws Exception {
        try {
            mVpnManager.provisionVpnProfile(mock(PlatformVpnProfile.class));
        } catch (UnsupportedOperationException expected) {
        }
    }

    @Test
    public void testDeleteProvisionedVpnProfile() throws Exception {
        try {
            mVpnManager.deleteProvisionedVpnProfile();
        } catch (UnsupportedOperationException expected) {
        }
    }

    @Test
    public void testStartProvisionedVpnProfile() throws Exception {
        try {
            mVpnManager.startProvisionedVpnProfile();
        } catch (UnsupportedOperationException expected) {
        }
    }

    @Test
    public void testStopProvisionedVpnProfile() throws Exception {
        try {
            mVpnManager.stopProvisionedVpnProfile();
        } catch (UnsupportedOperationException expected) {
        }
    }
}
