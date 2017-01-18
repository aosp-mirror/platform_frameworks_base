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
import android.os.Looper;
import android.support.test.runner.AndroidJUnit4;

import com.android.systemui.Dependency;
import com.android.systemui.FragmentTestCase;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QSTileHost;
import com.android.systemui.statusbar.phone.QuickStatusBarHeader;
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
import com.android.systemui.tuner.TunerService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

@RunWith(AndroidJUnit4.class)
public class QSFragmentTest extends FragmentTestCase {

    public QSFragmentTest() {
        super(QSFragment.class);
    }

    @Before
    public void addLeakCheckDependencies() {
        injectMockDependency(UserSwitcherController.class);
        injectLeakCheckedDependencies(BluetoothController.class, LocationController.class,
                RotationLockController.class, NetworkController.class, ZenModeController.class,
                HotspotController.class, CastController.class, FlashlightController.class,
                UserInfoController.class, KeyguardMonitor.class, SecurityController.class,
                BatteryController.class, NextAlarmController.class);
    }

    @Test
    public void testListening() {
        QSFragment qs = (QSFragment) mFragment;
        postAndWait(() -> mFragments.dispatchResume());
        QSTileHost host = new QSTileHost(mContext, null,
                mock(StatusBarIconController.class));
        qs.setHost(host);
        Handler h = new Handler((Looper) Dependency.get(Dependency.BG_LOOPER));

        qs.setListening(true);
        waitForIdleSync(h);

        qs.setListening(false);
        waitForIdleSync(h);

        // Manually push header through detach so it can handle standard cleanup it does on
        // removed from window.
        ((QuickStatusBarHeader) qs.getView().findViewById(R.id.header)).onDetachedFromWindow();

        host.destroy();
        // Ensure the tuner cleans up its persistent listeners.
        TunerService.get(mContext).destroy();
    }
}
