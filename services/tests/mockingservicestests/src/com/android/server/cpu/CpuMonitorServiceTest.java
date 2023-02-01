/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.cpu;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.server.cpu.CpuAvailabilityMonitoringConfig.CPUSET_ALL;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;

import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.os.ServiceManager;

import com.android.dx.mockito.inline.extended.StaticMockitoSessionBuilder;
import com.android.server.ExtendedMockitoTestCase;
import com.android.server.LocalServices;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

public final class CpuMonitorServiceTest extends ExtendedMockitoTestCase {
    private static final CpuAvailabilityMonitoringConfig TEST_CPU_AVAILABILITY_MONITORING_CONFIG =
            new CpuAvailabilityMonitoringConfig.Builder(CPUSET_ALL)
                    .addThreshold(30).addThreshold(70).build();

    private static final CpuAvailabilityMonitoringConfig TEST_CPU_AVAILABILITY_MONITORING_CONFIG_2 =
            new CpuAvailabilityMonitoringConfig.Builder(CPUSET_ALL)
                    .addThreshold(10).addThreshold(90).build();

    @Mock
    private Context mContext;
    private CpuMonitorService mService;
    private HandlerExecutor mHandlerExecutor;
    private CpuMonitorInternal mLocalService;

    @Override
    protected void initializeSession(StaticMockitoSessionBuilder builder) {
        builder.mockStatic(ServiceManager.class);
    }

    @Before
    public void setUp() {
        mService = new CpuMonitorService(mContext);
        mHandlerExecutor = new HandlerExecutor(new Handler(Looper.getMainLooper()));
        doNothing().when(() -> ServiceManager.addService(eq("cpu_monitor"), any(Binder.class),
                anyBoolean(), anyInt()));
        mService.onStart();
        mLocalService = LocalServices.getService(CpuMonitorInternal.class);
    }

    @After
    public void tearDown() {
        // The CpuMonitorInternal.class service is added by the mService.onStart call.
        // Remove the service to ensure the setUp procedure can add this service again.
        LocalServices.removeServiceForTest(CpuMonitorInternal.class);
    }

    @Test
    public void testAddRemoveCpuAvailabilityCallback() {
        CpuMonitorInternal.CpuAvailabilityCallback mockCallback = mock(
                CpuMonitorInternal.CpuAvailabilityCallback.class);

        mLocalService.addCpuAvailabilityCallback(mHandlerExecutor,
                TEST_CPU_AVAILABILITY_MONITORING_CONFIG, mockCallback);

        // TODO(b/242722241): Verify that {@link mockCallback.onAvailabilityChanged} and
        //  {@link mockCallback.onMonitoringIntervalChanged} are called when the callback is added.

        mLocalService.removeCpuAvailabilityCallback(mockCallback);
    }


    @Test
    public void testDuplicateAddCpuAvailabilityCallback() {
        CpuMonitorInternal.CpuAvailabilityCallback mockCallback = mock(
                CpuMonitorInternal.CpuAvailabilityCallback.class);

        mLocalService.addCpuAvailabilityCallback(mHandlerExecutor,
                TEST_CPU_AVAILABILITY_MONITORING_CONFIG, mockCallback);

        mLocalService.addCpuAvailabilityCallback(mHandlerExecutor,
                TEST_CPU_AVAILABILITY_MONITORING_CONFIG_2, mockCallback);

        // TODO(b/242722241): Verify that {@link mockCallback} is called only when CPU availability
        //  thresholds cross the bounds specified in the
        //  {@link TEST_CPU_AVAILABILITY_MONITORING_CONFIG_2} config.

        mLocalService.removeCpuAvailabilityCallback(mockCallback);
    }

    @Test
    public void testRemoveInvalidCpuAvailabilityCallback() {
        CpuMonitorInternal.CpuAvailabilityCallback mockCallback = mock(
                CpuMonitorInternal.CpuAvailabilityCallback.class);

        mLocalService.removeCpuAvailabilityCallback(mockCallback);
    }
}
