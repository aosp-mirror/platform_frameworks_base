/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.net.vcn;

import static com.android.testutils.ParcelUtils.assertParcelSane;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.net.NetworkCapabilities;
import android.os.Build;

import androidx.test.filters.SmallTest;

import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Test;
import org.junit.runner.RunWith;

// TODO: b/374174952 After B finalization, use Sdk36ModuleController to ensure VCN tests only run on
// Android B/B+
@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
@SmallTest
public class VcnUnderlyingNetworkPolicyTest {
    private static final VcnUnderlyingNetworkPolicy DEFAULT_NETWORK_POLICY =
            new VcnUnderlyingNetworkPolicy(
                    false /* isTearDownRequested */, new NetworkCapabilities());
    private static final VcnUnderlyingNetworkPolicy SAMPLE_NETWORK_POLICY =
            new VcnUnderlyingNetworkPolicy(
                    true /* isTearDownRequested */,
                    new NetworkCapabilities.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                            .build());

    @Test
    public void testEquals() {
        assertEquals(DEFAULT_NETWORK_POLICY, DEFAULT_NETWORK_POLICY);
        assertEquals(SAMPLE_NETWORK_POLICY, SAMPLE_NETWORK_POLICY);

        assertNotEquals(DEFAULT_NETWORK_POLICY, SAMPLE_NETWORK_POLICY);
    }

    @Test
    public void testParcelUnparcel() {
        assertParcelSane(SAMPLE_NETWORK_POLICY, 1);
    }
}
