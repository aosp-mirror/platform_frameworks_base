/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.os;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.power.CpuHeadroomResult;
import android.hardware.power.GpuHeadroomResult;
import android.hardware.power.SupportInfo;
import android.os.health.SystemHealthManager;
import android.platform.test.annotations.DisabledOnRavenwood;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.internal.app.IBatteryStats;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@DisabledOnRavenwood(blockedBy = SystemHealthManager.class)
public class SystemHealthManagerUnitTest {
    @Mock
    private IBatteryStats mBatteryStats;
    @Mock
    private IPowerStatsService mPowerStats;
    @Mock
    private IHintManager mHintManager;
    private SystemHealthManager mSystemHealthManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        IHintManager.HintManagerClientData clientData = new IHintManager.HintManagerClientData();
        clientData.supportInfo = new SupportInfo();
        clientData.maxCpuHeadroomThreads = 10;
        clientData.supportInfo.headroom = new SupportInfo.HeadroomSupportInfo();
        clientData.supportInfo.headroom.isCpuSupported = true;
        clientData.supportInfo.headroom.isGpuSupported = true;
        clientData.supportInfo.headroom.cpuMinCalculationWindowMillis = 45;
        clientData.supportInfo.headroom.cpuMaxCalculationWindowMillis = 9999;
        clientData.supportInfo.headroom.gpuMinCalculationWindowMillis = 46;
        clientData.supportInfo.headroom.gpuMaxCalculationWindowMillis = 9998;
        clientData.supportInfo.headroom.cpuMinIntervalMillis = 999;
        clientData.supportInfo.headroom.gpuMinIntervalMillis = 998;
        when(mHintManager.getClientData()).thenReturn(clientData);
        mSystemHealthManager = new SystemHealthManager(mBatteryStats, mPowerStats, mHintManager);
    }

    @Test
    public void testHeadroomParamsValueRange() {
        assertEquals(999, mSystemHealthManager.getCpuHeadroomMinIntervalMillis());
        assertEquals(998, mSystemHealthManager.getGpuHeadroomMinIntervalMillis());
        assertEquals(45, (int) mSystemHealthManager.getCpuHeadroomCalculationWindowRange().first);
        assertEquals(9999,
                (int) mSystemHealthManager.getCpuHeadroomCalculationWindowRange().second);
        assertEquals(46, (int) mSystemHealthManager.getGpuHeadroomCalculationWindowRange().first);
        assertEquals(9998,
                (int) mSystemHealthManager.getGpuHeadroomCalculationWindowRange().second);
        assertEquals(10, (int) mSystemHealthManager.getMaxCpuHeadroomTidsSize());
    }

    @Test
    public void testGetCpuHeadroom() throws RemoteException, InterruptedException {
        final CpuHeadroomParams params1 = null;
        final CpuHeadroomParamsInternal internalParams1 = new CpuHeadroomParamsInternal();

        final CpuHeadroomParams params2 = new CpuHeadroomParams.Builder()
                .setCalculationWindowMillis(100)
                .build();
        final CpuHeadroomParamsInternal internalParams2 = new CpuHeadroomParamsInternal();
        internalParams2.calculationWindowMillis = 100;

        final CpuHeadroomParams params3 = new CpuHeadroomParams.Builder()
                .setCalculationType(CpuHeadroomParams.CPU_HEADROOM_CALCULATION_TYPE_AVERAGE)
                .build();
        final CpuHeadroomParamsInternal internalParams3 = new CpuHeadroomParamsInternal();
        internalParams3.calculationType =
                (byte) CpuHeadroomParams.CPU_HEADROOM_CALCULATION_TYPE_AVERAGE;

        final CpuHeadroomParams params4 = new CpuHeadroomParams.Builder()
                .setTids(1000, 1001)
                .build();
        final CpuHeadroomParamsInternal internalParams4 = new CpuHeadroomParamsInternal();
        internalParams4.tids = new int[]{1000, 1001};

        when(mHintManager.getCpuHeadroom(internalParams1)).thenReturn(
                CpuHeadroomResult.globalHeadroom(99f));
        when(mHintManager.getCpuHeadroom(internalParams2)).thenReturn(
                CpuHeadroomResult.globalHeadroom(98f));
        when(mHintManager.getCpuHeadroom(internalParams3)).thenReturn(
                CpuHeadroomResult.globalHeadroom(97f));
        when(mHintManager.getCpuHeadroom(internalParams4)).thenReturn(null);

        assertEquals(99f, mSystemHealthManager.getCpuHeadroom(params1), 0.001f);
        assertEquals(98f, mSystemHealthManager.getCpuHeadroom(params2), 0.001f);
        assertEquals(97f, mSystemHealthManager.getCpuHeadroom(params3), 0.001f);
        assertTrue(Float.isNaN(mSystemHealthManager.getCpuHeadroom(params4)));
        verify(mHintManager, times(1)).getCpuHeadroom(internalParams1);
        verify(mHintManager, times(1)).getCpuHeadroom(internalParams2);
        verify(mHintManager, times(1)).getCpuHeadroom(internalParams3);
        verify(mHintManager, times(1)).getCpuHeadroom(internalParams4);
    }

    @Test
    public void testGetGpuHeadroom() throws RemoteException, InterruptedException {
        final GpuHeadroomParams params1 = null;
        final GpuHeadroomParamsInternal internalParams1 = new GpuHeadroomParamsInternal();
        final GpuHeadroomParams params2 = new GpuHeadroomParams.Builder()
                .setCalculationWindowMillis(100)
                .build();
        final GpuHeadroomParamsInternal internalParams2 = new GpuHeadroomParamsInternal();
        internalParams2.calculationWindowMillis = 100;
        final GpuHeadroomParams params3 = new GpuHeadroomParams.Builder()
                .setCalculationType(GpuHeadroomParams.GPU_HEADROOM_CALCULATION_TYPE_AVERAGE)
                .build();
        final GpuHeadroomParamsInternal internalParams3 = new GpuHeadroomParamsInternal();
        internalParams3.calculationType =
                (byte) GpuHeadroomParams.GPU_HEADROOM_CALCULATION_TYPE_AVERAGE;

        when(mHintManager.getGpuHeadroom(internalParams1)).thenReturn(
                GpuHeadroomResult.globalHeadroom(99f));
        when(mHintManager.getGpuHeadroom(internalParams2)).thenReturn(
                GpuHeadroomResult.globalHeadroom(98f));
        when(mHintManager.getGpuHeadroom(internalParams3)).thenReturn(null);

        assertEquals(99f, mSystemHealthManager.getGpuHeadroom(params1), 0.001f);
        assertEquals(98f, mSystemHealthManager.getGpuHeadroom(params2), 0.001f);
        assertTrue(Float.isNaN(mSystemHealthManager.getGpuHeadroom(params3)));
        verify(mHintManager, times(1)).getGpuHeadroom(internalParams1);
        verify(mHintManager, times(1)).getGpuHeadroom(internalParams2);
        verify(mHintManager, times(1)).getGpuHeadroom(internalParams3);
    }
}
