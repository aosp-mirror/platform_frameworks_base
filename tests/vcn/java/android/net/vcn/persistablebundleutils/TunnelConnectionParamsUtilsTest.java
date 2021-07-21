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

package android.net.vcn.persistablebundleutils;

import static org.junit.Assert.assertEquals;

import android.net.ipsec.ike.IkeSessionParams;
import android.net.ipsec.ike.IkeTunnelConnectionParams;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class TunnelConnectionParamsUtilsTest {
    // Public for use in VcnGatewayConnectionConfigTest
    public static IkeTunnelConnectionParams buildTestParams() {
        return buildTestParams(IkeSessionParamsUtilsTest.createBuilderMinimum().build());
    }

    // Public for use in VcnGatewayConnectionConfigTest
    public static IkeTunnelConnectionParams buildTestParams(IkeSessionParams params) {
        return new IkeTunnelConnectionParams(
                params, TunnelModeChildSessionParamsUtilsTest.createBuilderMinimum().build());
    }

    @Test
    public void testIkeTunnelConnectionParamsToFromPersistableBundle() {
        final IkeTunnelConnectionParams params = buildTestParams();

        assertEquals(
                params,
                TunnelConnectionParamsUtils.fromPersistableBundle(
                        TunnelConnectionParamsUtils.toPersistableBundle(params)));
    }
}
