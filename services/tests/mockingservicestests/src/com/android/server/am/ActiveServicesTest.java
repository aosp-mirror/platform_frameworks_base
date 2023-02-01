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
package com.android.server.am;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.internal.app.procstats.ProcessStats.ADJ_MEM_FACTOR_CRITICAL;
import static com.android.internal.app.procstats.ProcessStats.ADJ_MEM_FACTOR_LOW;
import static com.android.internal.app.procstats.ProcessStats.ADJ_MEM_FACTOR_MODERATE;
import static com.android.internal.app.procstats.ProcessStats.ADJ_MEM_FACTOR_NORMAL;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

import android.app.compat.CompatChanges;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.os.SystemClock;
import android.util.ArraySet;

import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;

@RunWith(AndroidJUnit4.class)
public class ActiveServicesTest {

    private static final long DEFAULT_SERVICE_MIN_RESTART_TIME_BETWEEN = 10 * 1000;
    private static final long[] DEFAULT_EXTRA_SERVICE_RESTART_DELAY_ON_MEM_PRESSURE = {
            0,
            DEFAULT_SERVICE_MIN_RESTART_TIME_BETWEEN,
            DEFAULT_SERVICE_MIN_RESTART_TIME_BETWEEN * 2,
            DEFAULT_SERVICE_MIN_RESTART_TIME_BETWEEN * 3
    };

    private MockitoSession mMockingSession;
    private ActivityManagerService mService;
    private ActiveServices mActiveServices;
    private AppProfiler mProfiler;

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .mockStatic(CompatChanges.class)
                .startMocking();
        prepareTestRescheduleServiceRestarts();
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testRescheduleServiceRestartsOnChanges() throws Exception {
        final long now = SystemClock.uptimeMillis();
        final long btwn = mService.mConstants.SERVICE_MIN_RESTART_TIME_BETWEEN;
        final long rd0 = 0;
        final long rd1 = 1000;
        final long rd2 = rd1 + btwn;
        final long rd3 = rd2 + btwn;
        final long rd4 = rd3 + btwn * 10;
        final long rd5 = rd4 + btwn;
        int memFactor = ADJ_MEM_FACTOR_MODERATE;
        when(mService.mAppProfiler.getLastMemoryLevelLocked()).thenReturn(memFactor);
        fillInRestartingServices(now, new long[] {rd0, rd1, rd2, rd3, rd4, rd5});

        // Test enable/disable.
        mActiveServices.rescheduleServiceRestartOnMemoryPressureIfNeededLocked(false, true, now);
        long extra = mService.mConstants.mExtraServiceRestartDelayOnMemPressure[memFactor];
        verifyDelays(now, new long[] {rd0, extra, btwn + extra * 2, btwn * 2 + extra * 3, rd4,
                rd5 + extra});
        mActiveServices.rescheduleServiceRestartOnMemoryPressureIfNeededLocked(true, false, now);
        verifyDelays(now, new long[] {rd0, rd1, rd2, rd3, rd4, rd5});

        final long elapsed = 10;
        final long now2 = now + elapsed;
        mActiveServices.rescheduleServiceRestartOnMemoryPressureIfNeededLocked(false, true, now2);
        verifyDelays(now2, new long[] {rd0 - elapsed, extra - elapsed,
                btwn + extra * 2 - elapsed, btwn * 2 + extra * 3 - elapsed, rd4 - elapsed,
                rd5 + extra - elapsed});

        mActiveServices.rescheduleServiceRestartOnMemoryPressureIfNeededLocked(true, false, now2);
        verifyDelays(now2, new long[] {rd0 - elapsed, rd1 - elapsed, rd2 - elapsed, rd3 - elapsed,
                rd4 - elapsed, rd5 - elapsed});

        // Test memory level changes.
        memFactor = ADJ_MEM_FACTOR_LOW;
        when(mService.mAppProfiler.getLastMemoryLevelLocked()).thenReturn(memFactor);
        extra = mService.mConstants.mExtraServiceRestartDelayOnMemPressure[memFactor];
        final long elapsed3 = elapsed * 2;
        final long now3 = now + elapsed3;
        mActiveServices.rescheduleServiceRestartOnMemoryPressureIfNeededLocked(
                ADJ_MEM_FACTOR_MODERATE, memFactor, "test", now3);
        verifyDelays(now3, new long[] {rd0 - elapsed3, extra - elapsed3,
                btwn + extra * 2 - elapsed3, btwn * 2 + extra * 3 - elapsed3, rd4 - elapsed3,
                rd5 + extra - elapsed3});

        memFactor = ADJ_MEM_FACTOR_CRITICAL;
        when(mService.mAppProfiler.getLastMemoryLevelLocked()).thenReturn(memFactor);
        extra = mService.mConstants.mExtraServiceRestartDelayOnMemPressure[memFactor];
        final long elapsed4 = elapsed * 3;
        final long now4 = now + elapsed4;
        mActiveServices.rescheduleServiceRestartOnMemoryPressureIfNeededLocked(
                ADJ_MEM_FACTOR_LOW, memFactor, "test", now4);
        verifyDelays(now4, new long[] {rd0 - elapsed4, extra - elapsed4,
                btwn + extra * 2 - elapsed4, btwn * 2 + extra * 3 - elapsed4,
                btwn * 3 + extra * 4 - elapsed4, btwn * 4 + extra * 5 - elapsed4});

        memFactor = ADJ_MEM_FACTOR_MODERATE;
        when(mService.mAppProfiler.getLastMemoryLevelLocked()).thenReturn(memFactor);
        extra = mService.mConstants.mExtraServiceRestartDelayOnMemPressure[memFactor];
        final long elapsed5 = elapsed * 4;
        final long now5 = now + elapsed5;
        mActiveServices.rescheduleServiceRestartOnMemoryPressureIfNeededLocked(
                ADJ_MEM_FACTOR_CRITICAL, memFactor, "test", now5);
        verifyDelays(now5, new long[] {rd0 - elapsed5, extra - elapsed5,
                btwn + extra * 2 - elapsed5, btwn * 2 + extra * 3 - elapsed5,
                rd4 - elapsed5, rd5 + extra - elapsed5});
    }

    @SuppressWarnings("GuardedBy")
    @Test
    public void testRescheduleServiceRestartsOnOtherChanges() throws Exception {
        final long now = SystemClock.uptimeMillis();
        final long btwn = mService.mConstants.SERVICE_MIN_RESTART_TIME_BETWEEN;
        final long rd0 = 1000;
        final long rd1 = 2000;
        final long rd2 = btwn * 10;
        final long rd3 = 5000;
        final long rd4 = btwn * 11 + 5000;
        final long rd5 = 3000;
        int memFactor = ADJ_MEM_FACTOR_CRITICAL;
        long extra = mService.mConstants.mExtraServiceRestartDelayOnMemPressure[memFactor];
        when(mService.mAppProfiler.getLastMemoryLevelLocked()).thenReturn(memFactor);

        fillInRestartingServices(now, new long[] {rd0, rd1, rd2, rd3, rd4, rd5});
        setNextRestarts(now, new long[] {extra, btwn + extra * 2, btwn * 2 + extra * 3,
                btwn * 3 + extra * 4, btwn * 4 + extra * 5, btwn * 5 + extra * 6});
        mActiveServices.mRestartingServices.remove(1);
        mActiveServices.rescheduleServiceRestartIfPossibleLocked(extra, btwn, "test", now);
        verifyDelays(now, new long[] {extra, rd2, rd2 + btwn +  extra,
                rd2 + (btwn + extra) * 2, rd2 + (btwn + extra) * 3});
        mActiveServices.mRestartingServices.remove(0);
        mActiveServices.rescheduleServiceRestartIfPossibleLocked(extra, btwn, "test", now);
        verifyDelays(now, new long[] {extra, rd2, rd2 + btwn + extra, rd2 + (btwn + extra) * 2});
        mActiveServices.mRestartingServices.remove(1);
        mActiveServices.rescheduleServiceRestartIfPossibleLocked(extra, btwn, "test", now);
        verifyDelays(now, new long[] {extra, btwn + extra * 2, rd4});

        fillInRestartingServices(now, new long[] {rd0, rd1, rd2, rd3, rd4, rd5});
        setNextRestarts(now, new long[] {extra, btwn + extra * 2, btwn * 2 + extra * 3,
                btwn * 3 + extra * 4, btwn * 4 + extra * 5, btwn * 5 + extra * 6});
        mActiveServices.mRestartingServices.remove(1);
        mActiveServices.rescheduleServiceRestartIfPossibleLocked(extra, btwn, "test", now);
        memFactor = ADJ_MEM_FACTOR_LOW;
        extra = mService.mConstants.mExtraServiceRestartDelayOnMemPressure[memFactor];
        when(mService.mAppProfiler.getLastMemoryLevelLocked()).thenReturn(memFactor);
        mActiveServices.rescheduleServiceRestartIfPossibleLocked(extra, btwn, "test", now);
        verifyDelays(now, new long[] {extra, btwn + extra * 2, rd2,
                rd2 + btwn + extra, rd2 + (btwn + extra) * 2});
    }

    private void prepareTestRescheduleServiceRestarts() {
        mService = mock(ActivityManagerService.class);
        mService.mConstants = mock(ActivityManagerConstants.class);
        mService.mConstants.mEnableExtraServiceRestartDelayOnMemPressure = true;
        mService.mConstants.mExtraServiceRestartDelayOnMemPressure =
                DEFAULT_EXTRA_SERVICE_RESTART_DELAY_ON_MEM_PRESSURE;
        mService.mConstants.SERVICE_MIN_RESTART_TIME_BETWEEN =
                DEFAULT_SERVICE_MIN_RESTART_TIME_BETWEEN;
        mProfiler = mock(AppProfiler.class);
        setFieldValue(ActivityManagerService.class, mService, "mAppProfiler", mProfiler);
        when(mProfiler.getLastMemoryLevelLocked()).thenReturn(ADJ_MEM_FACTOR_NORMAL);
        mActiveServices = mock(ActiveServices.class);
        setFieldValue(ActiveServices.class, mActiveServices, "mAm", mService);
        setFieldValue(ActiveServices.class, mActiveServices, "mRestartingServices",
                new ArrayList<>());
        setFieldValue(ActiveServices.class, mActiveServices, "mRestartBackoffDisabledPackages",
                new ArraySet<>());
        doNothing().when(mActiveServices).performScheduleRestartLocked(any(ServiceRecord.class),
                any(String.class), any(String.class), anyLong());
        doCallRealMethod().when(mActiveServices)
                .rescheduleServiceRestartOnMemoryPressureIfNeededLocked(
                        anyBoolean(), anyBoolean(), anyLong());
        doCallRealMethod().when(mActiveServices)
                .rescheduleServiceRestartOnMemoryPressureIfNeededLocked(
                        anyInt(), anyInt(), any(String.class), anyLong());
        doCallRealMethod().when(mActiveServices)
                .rescheduleServiceRestartIfPossibleLocked(
                        anyLong(), anyLong(), any(String.class), anyLong());
        doCallRealMethod().when(mActiveServices)
                .performRescheduleServiceRestartOnMemoryPressureLocked(
                        anyLong(), anyLong(), any(String.class), anyLong());
        doCallRealMethod().when(mActiveServices).getExtraRestartTimeInBetweenLocked();
        doCallRealMethod().when(mActiveServices)
                .isServiceRestartBackoffEnabledLocked(any(String.class));
    }

    private static <T> void setFieldValue(Class clazz, Object obj, String fieldName, T val) {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            Field mfield = Field.class.getDeclaredField("accessFlags");
            mfield.setAccessible(true);
            mfield.setInt(field, mfield.getInt(field) & ~(Modifier.FINAL | Modifier.PRIVATE));
            field.set(obj, val);
        } catch (NoSuchFieldException | IllegalAccessException e) {
        }
    }

    private void fillInRestartingServices(long now, long[] delays) {
        mActiveServices.mRestartingServices.clear();
        for (int i = 0; i < delays.length; i++) {
            mActiveServices.mRestartingServices.add(
                    createRestartingService("testpackage" + i, now, delays[i]));
        }
    }

    private void setNextRestarts(long now, long[] nextRestartDelays) {
        for (int i = 0; i < nextRestartDelays.length; i++) {
            final ServiceRecord r = mActiveServices.mRestartingServices.get(i);
            r.restartDelay = nextRestartDelays[i];
            r.nextRestartTime = now + r.restartDelay;
        }
    }

    private ServiceRecord createRestartingService(String packageName, long now, long delay) {
        final ServiceRecord r = mock(ServiceRecord.class);
        r.appInfo = new ApplicationInfo();
        r.appInfo.flags = delay == 0 ? ApplicationInfo.FLAG_PERSISTENT : 0;
        final ServiceInfo si = new ServiceInfo();
        setFieldValue(ServiceRecord.class, r, "serviceInfo", si);
        setFieldValue(ServiceRecord.class, r, "packageName", packageName);
        si.applicationInfo = r.appInfo;
        r.nextRestartTime = r.mEarliestRestartTime = now + delay;
        r.mRestartSchedulingTime = now;
        r.restartDelay = delay;
        return r;
    }

    private void verifyDelays(long now, long[] delays) throws Exception {
        for (int i = 0; i < delays.length; i++) {
            final ServiceRecord r = mActiveServices.mRestartingServices.get(i);
            assertEquals("Expected restart delay=" + delays[i],
                    Math.max(0, delays[i]), r.restartDelay);
            assertEquals("Expected next restart time=" + (now + delays[i]),
                    now + delays[i], r.nextRestartTime);
        }
    }
}
