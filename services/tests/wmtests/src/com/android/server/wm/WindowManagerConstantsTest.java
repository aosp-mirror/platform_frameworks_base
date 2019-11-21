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

package com.android.server.wm;

import static android.provider.AndroidDeviceConfig.KEY_SYSTEM_GESTURES_EXCLUDED_BY_PRE_Q_STICKY_IMMERSIVE;
import static android.provider.AndroidDeviceConfig.KEY_SYSTEM_GESTURE_EXCLUSION_LIMIT_DP;
import static android.provider.DeviceConfig.NAMESPACE_ANDROID;
import static android.provider.DeviceConfig.NAMESPACE_WINDOW_MANAGER;

import static com.android.server.wm.WindowManagerConstants.KEY_SYSTEM_GESTURE_EXCLUSION_LOG_DEBOUNCE_MILLIS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.server.wm.utils.FakeDeviceConfigInterface;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Presubmit
@SmallTest
public class WindowManagerConstantsTest {

    ExecutorService mExecutor;
    WindowManagerConstants mConstants;
    FakeDeviceConfigInterface mDeviceConfig;
    Runnable mUpdateSystemGestureExclusionCallback;

    @Before
    public void setUp() throws Exception {
        mExecutor = Executors.newSingleThreadExecutor();
        mDeviceConfig = new FakeDeviceConfigInterface();
        mUpdateSystemGestureExclusionCallback = mock(Runnable.class);
        mConstants = new WindowManagerConstants(new WindowManagerGlobalLock(),
                mUpdateSystemGestureExclusionCallback, mDeviceConfig);
    }

    @After
    public void tearDown() throws Exception {
        mExecutor.shutdown();
    }

    @Test
    public void test_constantsAreLoaded_initially() {
        mDeviceConfig.putProperty(NAMESPACE_ANDROID, KEY_SYSTEM_GESTURE_EXCLUSION_LIMIT_DP, "400");
        mDeviceConfig.putProperty(NAMESPACE_ANDROID,
                KEY_SYSTEM_GESTURES_EXCLUDED_BY_PRE_Q_STICKY_IMMERSIVE, "true");
        mDeviceConfig.putProperty(NAMESPACE_WINDOW_MANAGER,
                KEY_SYSTEM_GESTURE_EXCLUSION_LOG_DEBOUNCE_MILLIS, "10000");

        mConstants.start(mExecutor);

        assertEquals(400, mConstants.mSystemGestureExclusionLimitDp);
        assertTrue(mConstants.mSystemGestureExcludedByPreQStickyImmersive);
        assertEquals(10000, mConstants.mSystemGestureExclusionLogDebounceTimeoutMillis);
    }

    @Test
    public void test_constantsAreLoaded_afterChange() {
        mConstants.start(mExecutor);

        mDeviceConfig.putPropertyAndNotify(
                NAMESPACE_ANDROID, KEY_SYSTEM_GESTURE_EXCLUSION_LIMIT_DP, "400");
        mDeviceConfig.putPropertyAndNotify(NAMESPACE_ANDROID,
                KEY_SYSTEM_GESTURES_EXCLUDED_BY_PRE_Q_STICKY_IMMERSIVE, "true");
        mDeviceConfig.putPropertyAndNotify(NAMESPACE_WINDOW_MANAGER,
                KEY_SYSTEM_GESTURE_EXCLUSION_LOG_DEBOUNCE_MILLIS, "10000");

        assertEquals(400, mConstants.mSystemGestureExclusionLimitDp);
        assertTrue(mConstants.mSystemGestureExcludedByPreQStickyImmersive);
        assertEquals(10000, mConstants.mSystemGestureExclusionLogDebounceTimeoutMillis);
    }

    @Test
    public void test_changedGestureExclusionLimit_invokesCallback() {
        mConstants.start(mExecutor);

        mDeviceConfig.putPropertyAndNotify(
                NAMESPACE_ANDROID, KEY_SYSTEM_GESTURE_EXCLUSION_LIMIT_DP, "400");

        verify(mUpdateSystemGestureExclusionCallback).run();
    }

    @Test
    public void test_changedPreQStickyImmersive_invokesCallback() {
        mConstants.start(mExecutor);

        mDeviceConfig.putPropertyAndNotify(NAMESPACE_ANDROID,
                KEY_SYSTEM_GESTURES_EXCLUDED_BY_PRE_Q_STICKY_IMMERSIVE, "true");

        verify(mUpdateSystemGestureExclusionCallback).run();
    }

    @Test
    public void test_minimumExclusionLimitIs_200dp() {
        mDeviceConfig.putProperty(NAMESPACE_ANDROID, KEY_SYSTEM_GESTURE_EXCLUSION_LIMIT_DP, "20");

        mConstants.start(mExecutor);
        assertEquals(200, mConstants.mSystemGestureExclusionLimitDp);

        mDeviceConfig.putPropertyAndNotify(NAMESPACE_ANDROID,
                KEY_SYSTEM_GESTURE_EXCLUSION_LIMIT_DP, "21");
        assertEquals(200, mConstants.mSystemGestureExclusionLimitDp);
    }
}
