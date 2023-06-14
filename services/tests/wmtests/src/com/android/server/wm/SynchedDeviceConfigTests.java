/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.wm;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;

import android.app.ActivityThread;
import android.platform.test.annotations.Presubmit;
import android.provider.DeviceConfig;

import androidx.test.filters.SmallTest;

import com.android.modules.utils.testing.TestableDeviceConfig;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Test class for {@link SynchedDeviceConfig}.
 *
 * atest WmTests:SynchedDeviceConfigTests
 */
@SmallTest
@Presubmit
public class SynchedDeviceConfigTests {

    private static final long WAIT_FOR_PROPERTY_CHANGE_TIMEOUT_MILLIS = 2000; // 2 sec
    private static final String NAMESPACE_FOR_TEST = "TestingNameSpace";

    private SynchedDeviceConfig mDeviceConfig;

    private Executor mExecutor;

    @Rule
    public final TestableDeviceConfig.TestableDeviceConfigRule
            mDeviceConfigRule = new TestableDeviceConfig.TestableDeviceConfigRule();

    @Before
    public void setUp() {
        mExecutor = Objects.requireNonNull(ActivityThread.currentApplication()).getMainExecutor();
        mDeviceConfig = SynchedDeviceConfig
                .builder(/* nameSpace */ NAMESPACE_FOR_TEST, /* executor */ mExecutor)
                .addDeviceConfigEntry(/* key */ "key1", /* default */ true, /* enabled */ true)
                .addDeviceConfigEntry(/* key */ "key2", /* default */ false, /* enabled */ true)
                .addDeviceConfigEntry(/* key */ "key3",  /* default */ true, /* enabled */ false)
                .addDeviceConfigEntry(/* key */ "key4",  /* default */ false, /* enabled */ false)
                .addDeviceConfigEntry(/* key */ "key5",  /* default */ true, /* enabled */ false)
                .addDeviceConfigEntry(/* key */ "key6",  /* default */ false, /* enabled */ false)
                .build();
    }

    @After
    public void tearDown() {
        DeviceConfig.removeOnPropertiesChangedListener(mDeviceConfig);
    }

    @Test
    public void testWhenStarted_initialValuesAreDefaultOrFalseIfDisabled() {
        assertFlagValue(/* key */ "key1", /* expected */ true); // enabled
        assertFlagValue(/* key */ "key2", /* expected */ false); // enabled
        assertFlagValue(/* key */ "key3", /* expected */ false); // disabled
        assertFlagValue(/* key */ "key4", /* expected */ false); // disabled
        assertFlagValue(/* key */ "key5", /* expected */ false); // disabled
        assertFlagValue(/* key */ "key6", /* expected */ false); // disabled
    }

    @Test
    public void testIsEnabled() {
        assertFlagEnabled(/* key */ "key1", /* expected */ true);
        assertFlagEnabled(/* key */ "key2", /* expected */ true);
        assertFlagEnabled(/* key */ "key3", /* expected */ false);
        assertFlagEnabled(/* key */ "key4", /* expected */ false);
        assertFlagEnabled(/* key */ "key5", /* expected */ false);
        assertFlagEnabled(/* key */ "key6", /* expected */ false);
    }

    @Test
    public void testWhenUpdated_onlyEnabledChanges() {
        final CountDownLatch countDownLatch = new CountDownLatch(4);
        final DeviceConfig.OnPropertiesChangedListener countDownLatchListener =
                properties -> countDownLatch.countDown();
        DeviceConfig.addOnPropertiesChangedListener(NAMESPACE_FOR_TEST, mExecutor,
                countDownLatchListener);

        try {
            // We update all the keys
            updateProperty(/* key */ "key1", /* value */ false);
            updateProperty(/* key */ "key2", /* value */ true);
            updateProperty(/* key */ "key3", /* value */ false);
            updateProperty(/* key */ "key4", /* value */ true);

            assertThat(countDownLatch.await(
                    WAIT_FOR_PROPERTY_CHANGE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)).isTrue();

            // We update all the flags but only the enabled ones change
            assertFlagValue(/* key */ "key1", /* expected */ false); // changes
            assertFlagValue(/* key */ "key2", /* expected */ true); // changes
            assertFlagValue(/* key */ "key3", /* expected */ false); // disabled
            assertFlagValue(/* key */ "key4", /* expected */ false); // disabled
        } catch (InterruptedException e) {
            Assert.fail(e.getMessage());
        } finally {
            DeviceConfig.removeOnPropertiesChangedListener(countDownLatchListener);
        }
    }

    @Test
    public void testWhenEnabled_updatesAreUsed() {
        final CountDownLatch countDownLatchBefore = new CountDownLatch(2);
        final CountDownLatch countDownLatchAfter = new CountDownLatch(2);
        final DeviceConfig.OnPropertiesChangedListener countDownLatchBeforeListener =
                properties -> countDownLatchBefore.countDown();
        final DeviceConfig.OnPropertiesChangedListener countDownLatchAfterListener =
                properties -> countDownLatchAfter.countDown();
        DeviceConfig.addOnPropertiesChangedListener(NAMESPACE_FOR_TEST, mExecutor,
                countDownLatchBeforeListener);

        try {
            // We update disabled values
            updateProperty(/* key */ "key3", /* value */ false);
            updateProperty(/* key */ "key4", /* value */ true);

            assertThat(countDownLatchBefore.await(
                    WAIT_FOR_PROPERTY_CHANGE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)).isTrue();

            // We check they haven't been updated
            assertFlagValue(/* key */ "key3", /* expected */ false);
            assertFlagValue(/* key */ "key4", /* expected */ false);


            DeviceConfig.removeOnPropertiesChangedListener(countDownLatchBeforeListener);
            DeviceConfig.addOnPropertiesChangedListener(NAMESPACE_FOR_TEST, mExecutor,
                    countDownLatchAfterListener);

            // We update enabled flags
            updateProperty(/* key */ "key1", /* value */ false);
            updateProperty(/* key */ "key2", /* value */ true);

            assertThat(countDownLatchAfter.await(
                    WAIT_FOR_PROPERTY_CHANGE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)).isTrue();

            // Value have been updated
            assertFlagValue(/* key */ "key1", /* expected */ false);
            assertFlagValue(/* key */ "key2", /* expected */ true);

        } catch (InterruptedException e) {
            Assert.fail(e.getMessage());
        } finally {
            DeviceConfig.removeOnPropertiesChangedListener(countDownLatchAfterListener);
        }
    }


    private void assertFlagValue(String key, boolean expectedValue) {
        assertEquals(/* message */"Flag " + key + " value is not " + expectedValue, /* expected */
                expectedValue, /* actual */ mDeviceConfig.getFlagValue(key));
    }


    private void assertFlagEnabled(String key, boolean expectedValue) {
        assertEquals(/* message */
                "Flag " + key + " enabled is not " + expectedValue, /* expected */
                expectedValue, /* actual */ mDeviceConfig.isBuildTimeFlagEnabled(key));
    }

    private void updateProperty(String key, Boolean value) {
        DeviceConfig.setProperty(NAMESPACE_FOR_TEST, key, /* value */
                value.toString(), /* makeDefault */ false);
    }
}
