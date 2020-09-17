/*
 * Copyright 2020 The Android Open Source Project
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
package com.android.server.blob;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.fail;

import android.content.Context;
import android.os.Environment;
import android.platform.test.annotations.Presubmit;
import android.provider.DeviceConfig;
import android.util.DataUnit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.testables.TestableDeviceConfig;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class BlobStoreConfigTest {
    private static final long TIMEOUT_UPDATE_PROPERTIES_MS = 1_000;

    @Rule
    public TestableDeviceConfig.TestableDeviceConfigRule
            mDeviceConfigRule = new TestableDeviceConfig.TestableDeviceConfigRule();

    private Context mContext;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        BlobStoreConfig.initialize(mContext);
    }

    @Test
    public void testGetAppDataBytesLimit() throws Exception {
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_BLOBSTORE,
                BlobStoreConfig.DeviceConfigProperties.KEY_TOTAL_BYTES_PER_APP_LIMIT_FLOOR,
                String.valueOf(DataUnit.MEBIBYTES.toBytes(1000)),
                false /* makeDefault */);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_BLOBSTORE,
                BlobStoreConfig.DeviceConfigProperties.KEY_TOTAL_BYTES_PER_APP_LIMIT_FRACTION,
                String.valueOf(0.002f),
                false /* makeDefault */);
        waitForListenerToHandle();
        assertThat(BlobStoreConfig.getAppDataBytesLimit()).isEqualTo(
                DataUnit.MEBIBYTES.toBytes(1000));

        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_BLOBSTORE,
                BlobStoreConfig.DeviceConfigProperties.KEY_TOTAL_BYTES_PER_APP_LIMIT_FLOOR,
                String.valueOf(DataUnit.MEBIBYTES.toBytes(100)),
                false /* makeDefault */);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_BLOBSTORE,
                BlobStoreConfig.DeviceConfigProperties.KEY_TOTAL_BYTES_PER_APP_LIMIT_FRACTION,
                String.valueOf(0.1f),
                false /* makeDefault */);
        waitForListenerToHandle();
        final long expectedLimit = (long) (Environment.getDataDirectory().getTotalSpace() * 0.1f);
        assertThat(BlobStoreConfig.getAppDataBytesLimit()).isEqualTo(expectedLimit);
    }

    private void waitForListenerToHandle() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        mContext.getMainExecutor().execute(latch::countDown);
        if (!latch.await(TIMEOUT_UPDATE_PROPERTIES_MS, TimeUnit.MILLISECONDS)) {
            fail("Timed out waiting for properties to get updated");
        }
    }
}
