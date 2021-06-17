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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.annotation.NonNull;
import android.content.Context;
import android.os.Parcel;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class VcnConfigTest {
    private static final String TEST_PACKAGE_NAME = VcnConfigTest.class.getPackage().getName();
    private static final Set<VcnGatewayConnectionConfig> GATEWAY_CONNECTION_CONFIGS =
            Collections.singleton(VcnGatewayConnectionConfigTest.buildTestConfig());

    private final Context mContext = mock(Context.class);

    // Public visibility for VcnManagementServiceTest
    public static VcnConfig buildTestConfig(@NonNull Context context) {
        VcnConfig.Builder builder = new VcnConfig.Builder(context);

        for (VcnGatewayConnectionConfig gatewayConnectionConfig : GATEWAY_CONNECTION_CONFIGS) {
            builder.addGatewayConnectionConfig(gatewayConnectionConfig);
        }

        return builder.build();
    }

    @Before
    public void setUp() throws Exception {
        doReturn(TEST_PACKAGE_NAME).when(mContext).getOpPackageName();
    }

    @Test
    public void testBuilderConstructorRequiresContext() {
        try {
            new VcnConfig.Builder(null);
            fail("Expected exception due to null context");
        } catch (NullPointerException e) {
        }
    }

    @Test
    public void testBuilderRequiresGatewayConnectionConfig() {
        try {
            new VcnConfig.Builder(mContext).build();
            fail("Expected exception due to no VcnGatewayConnectionConfigs provided");
        } catch (IllegalArgumentException e) {
        }
    }

    @Test
    public void testBuilderRequiresUniqueGatewayConnectionNames() {
        final VcnGatewayConnectionConfig config = VcnGatewayConnectionConfigTest.buildTestConfig();
        try {
            new VcnConfig.Builder(mContext)
                    .addGatewayConnectionConfig(config)
                    .addGatewayConnectionConfig(config);
            fail("Expected exception due to duplicate gateway connection name");
        } catch (IllegalArgumentException e) {
        }
    }

    @Test
    public void testBuilderAndGetters() {
        final VcnConfig config = buildTestConfig(mContext);

        assertEquals(TEST_PACKAGE_NAME, config.getProvisioningPackageName());
        assertEquals(GATEWAY_CONNECTION_CONFIGS, config.getGatewayConnectionConfigs());
    }

    @Test
    public void testPersistableBundle() {
        final VcnConfig config = buildTestConfig(mContext);

        assertEquals(config, new VcnConfig(config.toPersistableBundle()));
    }

    @Test
    public void testParceling() {
        final VcnConfig config = buildTestConfig(mContext);

        Parcel parcel = Parcel.obtain();
        config.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        assertEquals(config, VcnConfig.CREATOR.createFromParcel(parcel));
    }
}
