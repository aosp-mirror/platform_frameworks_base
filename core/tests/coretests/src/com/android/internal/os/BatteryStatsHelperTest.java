/*
 * Copyright (C) 2017 The Android Open Source Project
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
 *
 *
 */

package com.android.internal.os;

import static android.os.BatteryStats.Uid.PROCESS_STATE_TOP;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.BatteryStats;
import android.os.Process;
import android.text.format.DateUtils;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.FrameworkStatsLog;

import junit.framework.TestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class BatteryStatsHelperTest extends TestCase {
    private static final long TIME_FOREGROUND_ACTIVITY_ZERO = 0;
    private static final long TIME_FOREGROUND_ACTIVITY = 100 * DateUtils.MINUTE_IN_MILLIS * 1000;
    private static final long TIME_STATE_FOREGROUND_MS = 10 * DateUtils.MINUTE_IN_MILLIS;
    private static final long TIME_STATE_FOREGROUND_US = TIME_STATE_FOREGROUND_MS * 1000;

    private static final int UID = 123456;
    private static final double BATTERY_SCREEN_USAGE = 300;
    private static final double BATTERY_SYSTEM_USAGE = 600;
    private static final double BATTERY_WIFI_USAGE = 200;
    private static final double BATTERY_IDLE_USAGE = 600;
    private static final double BATTERY_BLUETOOTH_USAGE = 300;
    private static final double BATTERY_OVERACCOUNTED_USAGE = 500;
    private static final double BATTERY_UNACCOUNTED_USAGE = 700;
    private static final double BATTERY_APP_USAGE = 100;
    private static final double TOTAL_BATTERY_USAGE = 1000;
    private static final double PRECISION = 0.001;

    @Mock
    private BatteryStats.Uid mUid;
    @Mock
    private BatterySipper mWifiBatterySipper;
    @Mock
    private BatterySipper mBluetoothBatterySipper;
    @Mock
    private BatterySipper mIdleBatterySipper;
    @Mock
    private BatterySipper mNormalBatterySipper;
    @Mock
    private BatterySipper mScreenBatterySipper;
    @Mock
    private BatterySipper mOvercountedBatterySipper;
    @Mock
    private BatterySipper mUnaccountedBatterySipper;
    @Mock
    private BatterySipper mSystemBatterySipper;
    @Mock
    private BatterySipper mCellBatterySipper;
    @Mock
    private PackageManager mPackageManager;

    private BatteryStatsHelper mBatteryStatsHelper;
    private Context mContext;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mNormalBatterySipper.drainType = BatterySipper.DrainType.APP;
        mNormalBatterySipper.totalPowerMah = TOTAL_BATTERY_USAGE;
        when(mNormalBatterySipper.getUid()).thenReturn(UID);
        mNormalBatterySipper.uidObj = mUid;


        mScreenBatterySipper.drainType = BatterySipper.DrainType.SCREEN;
        mScreenBatterySipper.totalPowerMah = BATTERY_SCREEN_USAGE;

        mSystemBatterySipper.drainType = BatterySipper.DrainType.APP;
        mSystemBatterySipper.totalPowerMah = BATTERY_SYSTEM_USAGE;
        mSystemBatterySipper.uidObj = mUid;
        when(mSystemBatterySipper.getUid()).thenReturn(Process.SYSTEM_UID);

        mOvercountedBatterySipper.drainType = BatterySipper.DrainType.OVERCOUNTED;
        mOvercountedBatterySipper.totalPowerMah = BATTERY_OVERACCOUNTED_USAGE;

        mUnaccountedBatterySipper.drainType = BatterySipper.DrainType.UNACCOUNTED;
        mUnaccountedBatterySipper.totalPowerMah = BATTERY_UNACCOUNTED_USAGE;

        mWifiBatterySipper.drainType = BatterySipper.DrainType.WIFI;
        mWifiBatterySipper.totalPowerMah = BATTERY_WIFI_USAGE;

        mBluetoothBatterySipper.drainType = BatterySipper.DrainType.BLUETOOTH;
        mBluetoothBatterySipper.totalPowerMah = BATTERY_BLUETOOTH_USAGE;

        mIdleBatterySipper.drainType = BatterySipper.DrainType.IDLE;
        mIdleBatterySipper.totalPowerMah = BATTERY_IDLE_USAGE;

        mContext = InstrumentationRegistry.getContext();
        mBatteryStatsHelper = spy(new BatteryStatsHelper(mContext));
        mBatteryStatsHelper.setPackageManager(mPackageManager);
    }

    @Test
    public void testShouldHideSipper_TypeUnAccounted_ReturnTrue() {
        mNormalBatterySipper.drainType = BatterySipper.DrainType.UNACCOUNTED;
        assertThat(mBatteryStatsHelper.shouldHideSipper(mNormalBatterySipper)).isTrue();
    }

    @Test
    public void testShouldHideSipper_TypeOverAccounted_ReturnTrue() {
        mNormalBatterySipper.drainType = BatterySipper.DrainType.OVERCOUNTED;
        assertThat(mBatteryStatsHelper.shouldHideSipper(mNormalBatterySipper)).isTrue();
    }

    @Test
    public void testShouldHideSipper_TypeIdle_ReturnTrue() {
        mNormalBatterySipper.drainType = BatterySipper.DrainType.IDLE;
        assertThat(mBatteryStatsHelper.shouldHideSipper(mNormalBatterySipper)).isTrue();
    }

    @Test
    public void testShouldHideSipper_TypeCell_ReturnTrue() {
        mNormalBatterySipper.drainType = BatterySipper.DrainType.CELL;
        assertThat(mBatteryStatsHelper.shouldHideSipper(mNormalBatterySipper)).isTrue();
    }

    @Test
    public void testShouldHideSipper_TypeScreen_ReturnTrue() {
        mNormalBatterySipper.drainType = BatterySipper.DrainType.SCREEN;
        assertThat(mBatteryStatsHelper.shouldHideSipper(mNormalBatterySipper)).isTrue();
    }

    @Test
    public void testShouldHideSipper_TypeSystem_ReturnTrue() {
        mNormalBatterySipper.drainType = BatterySipper.DrainType.APP;
        when(mNormalBatterySipper.getUid()).thenReturn(Process.ROOT_UID);
        assertThat(mBatteryStatsHelper.shouldHideSipper(mNormalBatterySipper)).isTrue();
    }

    @Test
    public void testShouldHideSipper_UidNormal_ReturnFalse() {
        mNormalBatterySipper.drainType = BatterySipper.DrainType.APP;
        assertThat(mBatteryStatsHelper.shouldHideSipper(mNormalBatterySipper)).isFalse();
    }

    @Test
    public void testRemoveHiddenBatterySippers_ContainsHiddenSippers_RemoveAndReturnValue() {
        final List<BatterySipper> sippers = new ArrayList<>();
        sippers.add(mNormalBatterySipper);
        sippers.add(mScreenBatterySipper);
        sippers.add(mSystemBatterySipper);
        sippers.add(mOvercountedBatterySipper);
        sippers.add(mUnaccountedBatterySipper);
        sippers.add(mWifiBatterySipper);
        sippers.add(mBluetoothBatterySipper);
        sippers.add(mIdleBatterySipper);
        doReturn(true).when(mBatteryStatsHelper).isTypeSystem(mSystemBatterySipper);
        doNothing().when(mBatteryStatsHelper).smearScreenBatterySipper(any(), any());

        final double totalUsage = mBatteryStatsHelper.removeHiddenBatterySippers(sippers);

        assertThat(mNormalBatterySipper.shouldHide).isFalse();
        assertThat(mScreenBatterySipper.shouldHide).isTrue();
        assertThat(mSystemBatterySipper.shouldHide).isTrue();
        assertThat(mOvercountedBatterySipper.shouldHide).isTrue();
        assertThat(mUnaccountedBatterySipper.shouldHide).isTrue();
        assertThat(totalUsage).isWithin(PRECISION).of(BATTERY_SYSTEM_USAGE);
    }

    @Test
    public void testSmearScreenBatterySipper() {
        final BatterySipper sipperNull = createTestSmearBatterySipper(TIME_FOREGROUND_ACTIVITY_ZERO,
                BATTERY_APP_USAGE, 0 /* uid */, true /* isUidNull */);
        final BatterySipper sipperBg = createTestSmearBatterySipper(TIME_FOREGROUND_ACTIVITY_ZERO,
                BATTERY_APP_USAGE, 1 /* uid */, false /* isUidNull */);
        final BatterySipper sipperFg = createTestSmearBatterySipper(TIME_FOREGROUND_ACTIVITY,
                BATTERY_APP_USAGE, 2 /* uid */, false /* isUidNull */);

        final List<BatterySipper> sippers = new ArrayList<>();
        sippers.add(sipperNull);
        sippers.add(sipperBg);
        sippers.add(sipperFg);

        mBatteryStatsHelper.smearScreenBatterySipper(sippers, mScreenBatterySipper);

        assertThat(sipperNull.screenPowerMah).isWithin(PRECISION).of(0);
        assertThat(sipperBg.screenPowerMah).isWithin(PRECISION).of(0);
        assertThat(sipperFg.screenPowerMah).isWithin(PRECISION).of(BATTERY_SCREEN_USAGE);
    }

    @Test
    public void testIsTypeSystem_systemPackage_returnTrue() {
        final String[] systemPackages = {"com.android.system"};
        mBatteryStatsHelper.setSystemPackageArray(systemPackages);
        doReturn(UID).when(mNormalBatterySipper).getUid();
        doReturn(systemPackages).when(mPackageManager).getPackagesForUid(UID);

        assertThat(mBatteryStatsHelper.isTypeSystem(mNormalBatterySipper)).isTrue();
    }

    @Test
    public void testIsTypeService_servicePackage_returnTrue() {
        final String[] servicePackages = {"com.android.service"};
        mBatteryStatsHelper.setServicePackageArray(servicePackages);
        doReturn(UID).when(mNormalBatterySipper).getUid();
        doReturn(servicePackages).when(mPackageManager).getPackagesForUid(UID);

        assertThat(mBatteryStatsHelper.isTypeService(mNormalBatterySipper)).isTrue();
    }

    @Test
    public void testGetProcessForegroundTimeMs_largerActivityTime_returnMinTime() {
        doReturn(TIME_STATE_FOREGROUND_US + 500).when(mBatteryStatsHelper)
                .getForegroundActivityTotalTimeUs(eq(mUid), anyLong());
        doReturn(TIME_STATE_FOREGROUND_US).when(mUid).getProcessStateTime(eq(PROCESS_STATE_TOP),
                anyLong(), anyInt());

        final long time = mBatteryStatsHelper.getProcessForegroundTimeMs(mUid,
                BatteryStats.STATS_SINCE_CHARGED);

        assertThat(time).isEqualTo(TIME_STATE_FOREGROUND_MS);
    }

    @Test
    public void testDrainTypesSyncedWithProto() {
        assertEquals(BatterySipper.DrainType.AMBIENT_DISPLAY.ordinal(),
                FrameworkStatsLog.DEVICE_CALCULATED_POWER_BLAME_OTHER__DRAIN_TYPE__AMBIENT_DISPLAY);
        // AtomsProto has no "APP"
        assertEquals(BatterySipper.DrainType.BLUETOOTH.ordinal(),
                FrameworkStatsLog.DEVICE_CALCULATED_POWER_BLAME_OTHER__DRAIN_TYPE__BLUETOOTH);
        assertEquals(BatterySipper.DrainType.CAMERA.ordinal(),
                FrameworkStatsLog.DEVICE_CALCULATED_POWER_BLAME_OTHER__DRAIN_TYPE__CAMERA);
        assertEquals(BatterySipper.DrainType.CELL.ordinal(),
                FrameworkStatsLog.DEVICE_CALCULATED_POWER_BLAME_OTHER__DRAIN_TYPE__CELL);
        assertEquals(BatterySipper.DrainType.FLASHLIGHT.ordinal(),
                FrameworkStatsLog.DEVICE_CALCULATED_POWER_BLAME_OTHER__DRAIN_TYPE__FLASHLIGHT);
        assertEquals(BatterySipper.DrainType.IDLE.ordinal(),
                FrameworkStatsLog.DEVICE_CALCULATED_POWER_BLAME_OTHER__DRAIN_TYPE__IDLE);
        assertEquals(BatterySipper.DrainType.MEMORY.ordinal(),
                FrameworkStatsLog.DEVICE_CALCULATED_POWER_BLAME_OTHER__DRAIN_TYPE__MEMORY);
        assertEquals(BatterySipper.DrainType.OVERCOUNTED.ordinal(),
                FrameworkStatsLog.DEVICE_CALCULATED_POWER_BLAME_OTHER__DRAIN_TYPE__OVERCOUNTED);
        assertEquals(BatterySipper.DrainType.PHONE.ordinal(),
                FrameworkStatsLog.DEVICE_CALCULATED_POWER_BLAME_OTHER__DRAIN_TYPE__PHONE);
        assertEquals(BatterySipper.DrainType.SCREEN.ordinal(),
                FrameworkStatsLog.DEVICE_CALCULATED_POWER_BLAME_OTHER__DRAIN_TYPE__SCREEN);
        assertEquals(BatterySipper.DrainType.UNACCOUNTED.ordinal(),
                FrameworkStatsLog.DEVICE_CALCULATED_POWER_BLAME_OTHER__DRAIN_TYPE__UNACCOUNTED);
        // AtomsProto has no "USER"
        assertEquals(BatterySipper.DrainType.WIFI.ordinal(),
                FrameworkStatsLog.DEVICE_CALCULATED_POWER_BLAME_OTHER__DRAIN_TYPE__WIFI);
    }

    private BatterySipper createTestSmearBatterySipper(long activityTime, double totalPowerMah,
            int uidCode, boolean isUidNull) {
        final BatterySipper sipper = mock(BatterySipper.class);
        sipper.drainType = BatterySipper.DrainType.APP;
        sipper.totalPowerMah = totalPowerMah;
        doReturn(uidCode).when(sipper).getUid();
        if (!isUidNull) {
            final BatteryStats.Uid uid = mock(BatteryStats.Uid.class, RETURNS_DEEP_STUBS);
            doReturn(activityTime).when(mBatteryStatsHelper).getProcessForegroundTimeMs(eq(uid),
                    anyInt());
            doReturn(uidCode).when(uid).getUid();
            sipper.uidObj = uid;
        }

        return sipper;
    }

}
