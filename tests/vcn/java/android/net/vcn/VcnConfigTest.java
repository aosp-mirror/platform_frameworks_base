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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.os.Parcel;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class VcnConfigTest {
    private static final Set<VcnGatewayConnectionConfig> GATEWAY_CONNECTION_CONFIGS =
            Collections.singleton(VcnGatewayConnectionConfigTest.buildTestConfig());

    // Public visibility for VcnManagementServiceTest
    public static VcnConfig buildTestConfig() {
        VcnConfig.Builder builder = new VcnConfig.Builder();

        for (VcnGatewayConnectionConfig gatewayConnectionConfig : GATEWAY_CONNECTION_CONFIGS) {
            builder.addGatewayConnectionConfig(gatewayConnectionConfig);
        }

        return builder.build();
    }

    @Test
    public void testBuilderRequiresGatewayConnectionConfig() {
        try {
            new VcnConfig.Builder().build();
            fail("Expected exception due to no VcnGatewayConnectionConfigs provided");
        } catch (IllegalArgumentException e) {
        }
    }

    @Test
    public void testBuilderAndGetters() {
        final VcnConfig config = buildTestConfig();

        assertEquals(GATEWAY_CONNECTION_CONFIGS, config.getGatewayConnectionConfigs());
    }

    @Test
    public void testPersistableBundle() {
        final VcnConfig config = buildTestConfig();

        assertEquals(config, new VcnConfig(config.toPersistableBundle()));
    }

    @Test
    public void testParceling() {
        final VcnConfig config = buildTestConfig();

        Parcel parcel = Parcel.obtain();
        config.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        assertEquals(config, VcnConfig.CREATOR.createFromParcel(parcel));
    }
}
