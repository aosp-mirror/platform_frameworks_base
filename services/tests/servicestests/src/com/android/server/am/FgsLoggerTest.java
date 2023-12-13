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

package com.android.server.am;

import static android.app.ActivityManager.FOREGROUND_SERVICE_API_TYPE_CAMERA;
import static android.app.ActivityManager.FOREGROUND_SERVICE_API_TYPE_MICROPHONE;

import static com.android.server.am.ForegroundServiceTypeLoggerModule.FGS_API_BEGIN_WITH_FGS;
import static com.android.server.am.ForegroundServiceTypeLoggerModule.FGS_API_END_WITHOUT_FGS;
import static com.android.server.am.ForegroundServiceTypeLoggerModule.FGS_API_END_WITH_FGS;
import static com.android.server.am.ForegroundServiceTypeLoggerModule.FGS_STATE_CHANGED_API_CALL;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.pm.ServiceInfo;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;

/**
 * Test class for {@link ForegroundServiceTypeLoggerModule}.
 *
 * Build/Install/Run:
 * atest FrameworksServicesTests:FgsLoggerTest
 */
@Presubmit
@SmallTest
public class FgsLoggerTest {

    ForegroundServiceTypeLoggerModule mFgsLogger;

    @Before
    public void setUp() {
        ForegroundServiceTypeLoggerModule logger = new ForegroundServiceTypeLoggerModule();
        mFgsLogger = spy(logger);
        doNothing().when(mFgsLogger)
                .logFgsApiEvent(any(ServiceRecord.class),
                        anyInt(), anyInt(), anyInt(), anyLong());
        doNothing().when(mFgsLogger)
                .logFgsApiEventWithNoFgs(anyInt(),
                        anyInt(), anyInt(), anyLong());
    }

    @Test
    public void testFgsStartThenApiStart() {
        ServiceRecord record = ServiceRecord.newEmptyInstanceForTest(null);
        record.foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        mFgsLogger.logForegroundServiceStart(1, 1, record);
        mFgsLogger.logForegroundServiceApiEventBegin(1, 1, 1, "aPackageHasNoName");
        int expectedTypes = 1;
        verify(mFgsLogger).logFgsApiEvent(any(ServiceRecord.class),
                eq(FGS_STATE_CHANGED_API_CALL), eq(FGS_API_BEGIN_WITH_FGS),
                eq(expectedTypes), anyLong());
        reset(mFgsLogger);
        mFgsLogger.logForegroundServiceApiEventEnd(1, 1, 1);

        resetAndVerifyZeroInteractions();

        mFgsLogger.logForegroundServiceStop(1, record);
        verify(mFgsLogger).logFgsApiEvent(any(ServiceRecord.class),
                eq(FGS_STATE_CHANGED_API_CALL), eq(FGS_API_END_WITH_FGS),
                eq(expectedTypes), anyLong());
    }

    @Test
    public void testApiStartThenFgsStart() {
        ServiceRecord record = ServiceRecord.newEmptyInstanceForTest(null);
        record.foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        mFgsLogger.logForegroundServiceApiEventBegin(1, 1, 1, "aPackageHasNoName");

        resetAndVerifyZeroInteractions();

        mFgsLogger.logForegroundServiceStart(1, 1, record);
        int expectedTypes = 1;
        verify(mFgsLogger).logFgsApiEvent(any(ServiceRecord.class),
                eq(FGS_STATE_CHANGED_API_CALL), eq(FGS_API_BEGIN_WITH_FGS),
                eq(expectedTypes), anyLong());
        reset(mFgsLogger);
        mFgsLogger.logForegroundServiceApiEventEnd(1, 1, 1);

        resetAndVerifyZeroInteractions();

        mFgsLogger.logForegroundServiceStop(1, record);
        verify(mFgsLogger).logFgsApiEvent(any(ServiceRecord.class),
                eq(FGS_STATE_CHANGED_API_CALL), eq(FGS_API_END_WITH_FGS),
                eq(expectedTypes), anyLong());
    }

    @Test
    public void testFgsStartApiStartFgsStopApiStop() {
        ServiceRecord record = ServiceRecord.newEmptyInstanceForTest(null);
        record.foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        reset(mFgsLogger);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA,
                1, 1, "aPackageHasNoName");

        resetAndVerifyZeroInteractions();

        int expectedTypes = 1;

        mFgsLogger.logForegroundServiceStart(1, 1, record);
        verify(mFgsLogger).logFgsApiEvent(any(ServiceRecord.class),
                eq(FGS_STATE_CHANGED_API_CALL), eq(FGS_API_BEGIN_WITH_FGS),
                eq(expectedTypes), anyLong());
        reset(mFgsLogger);
        mFgsLogger.logForegroundServiceStop(1, record);

        resetAndVerifyZeroInteractions();

        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        verify(mFgsLogger).logFgsApiEventWithNoFgs(eq(1), eq(FGS_API_END_WITHOUT_FGS),
                eq(expectedTypes), anyLong());
    }

    @Test
    public void testApiStartStopNoFgs() throws InterruptedException {
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA,
                1, 1, "aPackageHasNoName");
        Thread.sleep(2000);

        resetAndVerifyZeroInteractions();

        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);

        resetAndVerifyZeroInteractions();
    }

    @Test
    public void testApiStartStopFgs() throws InterruptedException {
        ServiceRecord record = ServiceRecord.newEmptyInstanceForTest(null);
        record.foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;

        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA,
                1, 1, "aPackageHasNoName");
        Thread.sleep(2000);

        resetAndVerifyZeroInteractions();

        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);

        resetAndVerifyZeroInteractions();

        mFgsLogger.logForegroundServiceStart(1, 1, record);

        resetAndVerifyZeroInteractions();
    }

    @Test
    public void testFgsStartStopApiStartStop() throws InterruptedException {
        ServiceRecord record = ServiceRecord.newEmptyInstanceForTest(null);
        record.foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;

        mFgsLogger.logForegroundServiceStart(1, 1, record);

        resetAndVerifyZeroInteractions();

        mFgsLogger.logForegroundServiceStop(1, record);

        resetAndVerifyZeroInteractions();

        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA,
                1, 1, "aPackageHasNoName");
        Thread.sleep(2000);

        resetAndVerifyZeroInteractions();

        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);

        resetAndVerifyZeroInteractions();
    }

    @Test
    public void testMultipleStartStopApis() throws InterruptedException {
        ServiceRecord record = ServiceRecord.newEmptyInstanceForTest(null);
        record.foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        long timeStamp = mFgsLogger.logForegroundServiceApiEventBegin(
                FOREGROUND_SERVICE_API_TYPE_CAMERA,
                1, 1, "aPackageHasNoName");
        Thread.sleep(1000);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA,
                1, 1, "aPackageHasNoName");
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA,
                1, 1, "aPackageHasNoName");
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA,
                1, 1, "aPackageHasNoName");
        Thread.sleep(1000);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA,
                1, 1, "aPackageHasNoName");

        resetAndVerifyZeroInteractions();

        // now start an FGS
        mFgsLogger.logForegroundServiceStart(1, 1, record);
        // now we should see exactly one call logged
        // and this should be the very first call
        // we also try to verify the time as being the very first call
        int expectedTypes = 1;
        long expectedTimestamp = timeStamp;

        verify(mFgsLogger, times(1))
                .logFgsApiEvent(any(ServiceRecord.class),
                        eq(FGS_STATE_CHANGED_API_CALL),
                        eq(FGS_API_BEGIN_WITH_FGS),
                        eq(expectedTypes),
                        eq(expectedTimestamp));
        reset(mFgsLogger);
        // now we do multiple stops
        // only the last one should be logged
        mFgsLogger.logForegroundServiceStop(1, record);

        resetAndVerifyZeroInteractions();

        // log the API stops
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        Thread.sleep(1000);
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        Thread.sleep(1000);
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        timeStamp = mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA,
                1, 1);
        expectedTimestamp = timeStamp;
        verify(mFgsLogger, times(1))
                .logFgsApiEventWithNoFgs(eq(1), eq(FGS_API_END_WITHOUT_FGS),
                        eq(expectedTypes),
                        eq(expectedTimestamp));
    }

    @Test
    public void testMultipleStartStops() throws InterruptedException {
        ServiceRecord record = ServiceRecord.newEmptyInstanceForTest(null);
        record.foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        long timeStamp = mFgsLogger.logForegroundServiceApiEventBegin(
                FOREGROUND_SERVICE_API_TYPE_CAMERA,
                1, 1, "aPackageHasNoName");
        Thread.sleep(1000);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA,
                1, 1, "aPackageHasNoName");
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA,
                1, 1, "aPackageHasNoName");
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA,
                1, 1, "aPackageHasNoName");
        Thread.sleep(1000);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA,
                1, 1, "aPackageHasNoName");
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA,
                1, 1);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA,
                1, 1, "aPackageHasNoName");
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA,
                1, 1);

        resetAndVerifyZeroInteractions();

        // now start an FGS
        mFgsLogger.logForegroundServiceStart(1, 1, record);
        // now we should see exactly one call logged
        // and this should be the very first call
        // we also try to verify the time as being the very first call
        int expectedTypes = 1;
        long expectedTimestamp = timeStamp;

        verify(mFgsLogger, times(1))
                .logFgsApiEvent(any(ServiceRecord.class),
                        eq(FGS_STATE_CHANGED_API_CALL),
                        eq(FGS_API_BEGIN_WITH_FGS),
                        eq(expectedTypes),
                        eq(expectedTimestamp));
        reset(mFgsLogger);
        // now we do multiple stops
        // only the last one should be logged
        mFgsLogger.logForegroundServiceStop(1, record);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");

        resetAndVerifyZeroInteractions();

        // log the API stops
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");
        Thread.sleep(1000);
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        Thread.sleep(1000);
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        timeStamp = mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA,
                1, 1);
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        expectedTimestamp = timeStamp;
        verify(mFgsLogger, times(1))
                .logFgsApiEventWithNoFgs(eq(1), eq(FGS_API_END_WITHOUT_FGS),
                        eq(expectedTypes),
                        eq(expectedTimestamp));
    }

    @Test
    public void testMultiStartStopThroughout() throws InterruptedException {
        ServiceRecord record = ServiceRecord.newEmptyInstanceForTest(null);
        record.foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        long timeStamp = mFgsLogger.logForegroundServiceApiEventBegin(
                FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1, "aPackageHasNoName");
        Thread.sleep(1000);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");
        Thread.sleep(1000);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);

        resetAndVerifyZeroInteractions();

        // now start an FGS
        mFgsLogger.logForegroundServiceStart(1, 1, record);
        // now we should see exactly one call logged
        // and this should be the very first call
        // we also try to verify the time as being the very first call
        int expectedTypes = 1;
        long expectedTimestamp = timeStamp;

        verify(mFgsLogger, times(1))
                .logFgsApiEvent(any(ServiceRecord.class),
                        eq(FGS_STATE_CHANGED_API_CALL),
                        eq(FGS_API_BEGIN_WITH_FGS),
                        eq(expectedTypes),
                        eq(expectedTimestamp));
        reset(mFgsLogger);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);

        resetAndVerifyZeroInteractions();

        // now we do multiple stops
        // only the last one should be logged
        mFgsLogger.logForegroundServiceStop(1, record);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");
        resetAndVerifyZeroInteractions();
        // log the API stops
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");
        Thread.sleep(1000);
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        Thread.sleep(1000);
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        timeStamp = mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA,
                1, 1);
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        expectedTimestamp = timeStamp;
        verify(mFgsLogger, times(1))
                .logFgsApiEventWithNoFgs(eq(1), eq(FGS_API_END_WITHOUT_FGS),
                        eq(expectedTypes),
                        eq(expectedTimestamp));
    }

    @Test
    public void testMultipleFGS() throws InterruptedException {
        ServiceRecord record = ServiceRecord.newEmptyInstanceForTest(null);
        record.foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        ActivityManagerService ams = mock(ActivityManagerService.class);
        ServiceRecord record2 = ServiceRecord.newEmptyInstanceForTest(ams);
        record2.foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        long timeStamp = mFgsLogger.logForegroundServiceApiEventBegin(
                FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1, "aPackageHasNoName");
        Thread.sleep(1000);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");
        Thread.sleep(1000);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        resetAndVerifyZeroInteractions();
        // now start an FGS
        mFgsLogger.logForegroundServiceStart(1, 1, record);
        mFgsLogger.logForegroundServiceStart(1, 1, record2);
        // now we should see exactly one call logged
        // and this should be the very first call
        // we also try to verify the time as being the very first call
        int expectedTypes = 1;
        long expectedTimestamp = timeStamp;

        verify(mFgsLogger, times(1))
                .logFgsApiEvent(any(ServiceRecord.class),
                        eq(FGS_STATE_CHANGED_API_CALL),
                        eq(FGS_API_BEGIN_WITH_FGS),
                        eq(expectedTypes),
                        eq(expectedTimestamp));
        reset(mFgsLogger);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);

        resetAndVerifyZeroInteractions();

        // now we do multiple stops
        // only the last one should be logged
        mFgsLogger.logForegroundServiceStop(1, record);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");

        resetAndVerifyZeroInteractions();

        // log the API stops
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");
        Thread.sleep(1000);
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        Thread.sleep(1000);
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        timeStamp = mFgsLogger.logForegroundServiceApiEventEnd(1, 1, 1);
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        expectedTimestamp = timeStamp;
        verify(mFgsLogger, times(1))
                .logFgsApiEventWithNoFgs(eq(1), eq(FGS_API_END_WITHOUT_FGS),
                        eq(expectedTypes),
                        eq(expectedTimestamp));
    }

    @Test
    public void testMultipleUid() throws InterruptedException {
        int expectedTypes = 1;
        ServiceRecord record = ServiceRecord.newEmptyInstanceForTest(null);
        record.foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        ActivityManagerService ams = mock(ActivityManagerService.class);
        ServiceRecord record2 = ServiceRecord.newEmptyInstanceForTest(ams);
        record2.foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        long timeStamp = mFgsLogger.logForegroundServiceApiEventBegin(1, 1, 1, "aPackageHasNoName");
        Thread.sleep(1000);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");
        Thread.sleep(1000);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);

        ServiceRecord recordUid2 = ServiceRecord.newEmptyInstanceForTest(null);
        recordUid2.foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        ServiceRecord recordUid22 = ServiceRecord.newEmptyInstanceForTest(ams);
        recordUid22.foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        long timeStamp2 = mFgsLogger.logForegroundServiceApiEventBegin(1, 2, 1,
                "aPackageHasNoName");
        Thread.sleep(1000);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 2, 1,
                "aPackageHasNoName");
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 2, 1,
                "aPackageHasNoName");
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 2, 1,
                "aPackageHasNoName");
        Thread.sleep(1000);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 2, 1,
                "aPackageHasNoName");
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 2, 1);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 2, 1,
                "aPackageHasNoName");
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 2, 1);

        resetAndVerifyZeroInteractions();

        // now start an FGS
        mFgsLogger.logForegroundServiceStart(1, 1, record);
        mFgsLogger.logForegroundServiceStart(1, 1, record2);

        mFgsLogger.logForegroundServiceStart(2, 1, recordUid2);
        mFgsLogger.logForegroundServiceStart(2, 1, recordUid22);
        // now we should see exactly two calls logged
        // and this should be the very first call
        // we also try to verify the time as being the very first call
        verify(mFgsLogger, times(2))
                .logFgsApiEvent(any(ServiceRecord.class),
                        eq(FGS_STATE_CHANGED_API_CALL),
                        eq(FGS_API_BEGIN_WITH_FGS),
                        eq(expectedTypes), anyLong());
        reset(mFgsLogger);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);

        resetAndVerifyZeroInteractions();

        // now we do multiple stops
        // only the last one should be logged
        mFgsLogger.logForegroundServiceStop(1, record);
        mFgsLogger.logForegroundServiceStop(2, recordUid2);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");

        resetAndVerifyZeroInteractions();

        // log the API stops
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");
        Thread.sleep(1000);
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        Thread.sleep(1000);
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        timeStamp = mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA,
                1, 1);
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 2, 1);
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 2, 1);
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 2, 1);
        timeStamp2 = mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA,
                2, 1);
        long expectedTimestamp = timeStamp;
        long expectedTimestamp2 = timeStamp2;
        verify(mFgsLogger, times(1))
                .logFgsApiEventWithNoFgs(eq(1), eq(FGS_API_END_WITHOUT_FGS),
                        eq(expectedTypes),
                        eq(expectedTimestamp));
        verify(mFgsLogger, times(1))
                .logFgsApiEventWithNoFgs(eq(2), eq(FGS_API_END_WITHOUT_FGS),
                        eq(expectedTypes),
                        eq(expectedTimestamp2));
    }

    @Test
    public void testMultipleStartStopWithinFgsWindow() throws InterruptedException {
        ServiceRecord record = ServiceRecord.newEmptyInstanceForTest(null);
        record.foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;

        resetAndVerifyZeroInteractions();

        // now start an FGS
        mFgsLogger.logForegroundServiceStart(1, 1, record);
        long timeStamp = mFgsLogger.logForegroundServiceApiEventBegin(
                FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1, "aPackageHasNoName");
        Thread.sleep(1000);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");
        Thread.sleep(1000);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        // now we should see exactly one call logged
        // and this should be the very first call
        // we also try to verify the time as being the very first call
        int expectedTypes = 1;
        long expectedTimestamp = timeStamp;

        verify(mFgsLogger, times(1))
                .logFgsApiEvent(any(ServiceRecord.class),
                        eq(FGS_STATE_CHANGED_API_CALL),
                        eq(FGS_API_BEGIN_WITH_FGS),
                        eq(expectedTypes),
                        eq(expectedTimestamp));
        reset(mFgsLogger);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);

        resetAndVerifyZeroInteractions();

        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");

        resetAndVerifyZeroInteractions();

        // log the API stops
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        mFgsLogger.logForegroundServiceApiEventBegin(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1,
                "aPackageHasNoName");
        Thread.sleep(1000);
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        Thread.sleep(1000);
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1);
        timeStamp = mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA,
                1, 1);

        resetAndVerifyZeroInteractions();

        // now we do multiple stops
        // only the last one should be logged
        mFgsLogger.logForegroundServiceStop(1, record);
        expectedTimestamp = timeStamp;
        verify(mFgsLogger, times(1))
                .logFgsApiEvent(any(ServiceRecord.class),
                        eq(FGS_STATE_CHANGED_API_CALL),
                        eq(FGS_API_END_WITH_FGS),
                        eq(expectedTypes),
                        eq(expectedTimestamp));
    }

    @Test
    public void multipleTypesOneFgsTest() {
        ServiceRecord record = ServiceRecord.newEmptyInstanceForTest(null);
        record.foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;

        long timestamp1 = mFgsLogger.logForegroundServiceApiEventBegin(
                FOREGROUND_SERVICE_API_TYPE_CAMERA, 1, 1, "aPackageHasNoName");
        long timestamp2 = mFgsLogger.logForegroundServiceApiEventBegin(
                FOREGROUND_SERVICE_API_TYPE_MICROPHONE,
                1, 1, "aPackageHasNoName");
        int expectedTypes = 1;
        int expectedType2 = FOREGROUND_SERVICE_API_TYPE_MICROPHONE;
        long expectedTimestamp = timestamp1;
        long expectedTimestamp2 = timestamp2;
        mFgsLogger.logForegroundServiceStart(1, 1, record);

        verify(mFgsLogger, times(1))
                .logFgsApiEvent(any(ServiceRecord.class),
                        eq(FGS_STATE_CHANGED_API_CALL),
                        eq(FGS_API_BEGIN_WITH_FGS),
                        eq(expectedTypes),
                        eq(expectedTimestamp));

        verify(mFgsLogger, times(1))
                .logFgsApiEvent(any(ServiceRecord.class),
                        eq(FGS_STATE_CHANGED_API_CALL),
                        eq(FGS_API_BEGIN_WITH_FGS),
                        eq(expectedType2),
                        eq(expectedTimestamp2));

        reset(mFgsLogger);
        resetAndVerifyZeroInteractions();

        timestamp1 = mFgsLogger.logForegroundServiceApiEventEnd(FOREGROUND_SERVICE_API_TYPE_CAMERA,
                1, 1);
        timestamp2 = mFgsLogger.logForegroundServiceApiEventEnd(
                FOREGROUND_SERVICE_API_TYPE_MICROPHONE,
                1, 1);

        mFgsLogger.logForegroundServiceStop(1, record);

        expectedTimestamp = timestamp1;
        expectedTimestamp2 = timestamp2;

        verify(mFgsLogger, times(1))
                .logFgsApiEvent(any(ServiceRecord.class),
                        eq(FGS_STATE_CHANGED_API_CALL),
                        eq(FGS_API_END_WITH_FGS),
                        eq(expectedTypes),
                        eq(expectedTimestamp));
        verify(mFgsLogger, times(1))
                .logFgsApiEvent(any(ServiceRecord.class),
                        eq(FGS_STATE_CHANGED_API_CALL),
                        eq(FGS_API_END_WITH_FGS),
                        eq(expectedType2),
                        eq(expectedTimestamp2));

    }

    private void resetAndVerifyZeroInteractions() {
        doNothing().when(mFgsLogger)
                .logFgsApiEvent(any(ServiceRecord.class),
                        anyInt(), anyInt(), anyInt(), anyLong());
        doNothing().when(mFgsLogger)
                .logFgsApiEventWithNoFgs(anyInt(), anyInt(), anyInt(), anyLong());
        verify(mFgsLogger, times(0))
                .logFgsApiEvent(any(ServiceRecord.class),
                        anyInt(), anyInt(), anyInt(), anyLong());
        verify(mFgsLogger, times(0))
                .logFgsApiEventWithNoFgs(anyInt(), anyInt(), anyInt(), anyLong());
    }
}
