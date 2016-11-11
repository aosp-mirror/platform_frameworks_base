/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.support.test.runner.AndroidJUnit4;

import com.android.systemui.FragmentTestCase;
import com.android.systemui.statusbar.phone.PhoneStatusBar;
import com.android.systemui.statusbar.phone.QSTileHost;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.FlashlightController;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.RotationLockController;
import com.android.systemui.statusbar.policy.SecurityController;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.statusbar.policy.ZenModeController;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

@RunWith(AndroidJUnit4.class)
public class QSFragmentTest extends FragmentTestCase {

    public QSFragmentTest() {
        super(QSFragment.class);
    }

    @Test
    public void testListening() {
        QSFragment qs = (QSFragment) mFragment;
        postAndWait(() -> mFragments.dispatchResume());
        UserSwitcherController userSwitcher = mock(UserSwitcherController.class);
        KeyguardMonitor keyguardMonitor = getLeakChecker(KeyguardMonitor.class);
        when(userSwitcher.getKeyguardMonitor()).thenReturn(keyguardMonitor);
        when(userSwitcher.getUsers()).thenReturn(new ArrayList<>());
        QSTileHost host = new QSTileHost(getTrackedContext(),
                mock(PhoneStatusBar.class),
                getLeakChecker(BluetoothController.class),
                getLeakChecker(LocationController.class),
                getLeakChecker(RotationLockController.class),
                getLeakChecker(NetworkController.class),
                getLeakChecker(ZenModeController.class),
                getLeakChecker(HotspotController.class),
                getLeakChecker(CastController.class),
                getLeakChecker(FlashlightController.class),
                userSwitcher,
                getLeakChecker(UserInfoController.class),
                keyguardMonitor,
                getLeakChecker(SecurityController.class),
                getLeakChecker(BatteryController.class),
                mock(StatusBarIconController.class),
                getLeakChecker(NextAlarmController.class));
        qs.setHost(host);
        Handler h = new Handler(host.getLooper());

        qs.setListening(true);
        waitForIdleSync(h);

        qs.setListening(false);
        waitForIdleSync(h);

        host.destroy();
    }
}
