/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import static com.android.systemui.Flags.registerBatteryControllerReceiversInCorestartable;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.os.PowerManager;

import com.android.systemui.CoreStartable;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;

import java.util.concurrent.Executor;

import javax.inject.Inject;

/** A {@link CoreStartable} responsible for registering the receivers for
 * {@link BatteryControllerImpl}.
 */
@SysUISingleton
public class BatteryControllerStartable implements CoreStartable {

    private final BatteryController mBatteryController;
    private final Executor mBackgroundExecutor;

    private static final String ACTION_LEVEL_TEST = "com.android.systemui.BATTERY_LEVEL_TEST";

    protected final BroadcastDispatcher mBroadcastDispatcher;
    @Inject
    public BatteryControllerStartable(
            BatteryController batteryController,
            BroadcastDispatcher broadcastDispatcher,
            @Background Executor backgroundExecutor) {
        mBatteryController = batteryController;
        mBroadcastDispatcher = broadcastDispatcher;
        mBackgroundExecutor = backgroundExecutor;
    }

    private void registerReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        filter.addAction(ACTION_LEVEL_TEST);
        filter.addAction(UsbManager.ACTION_USB_PORT_COMPLIANCE_CHANGED);
        mBroadcastDispatcher.registerReceiver((BroadcastReceiver) mBatteryController, filter);
    }

    @Override
    public void start() {
        if (registerBatteryControllerReceiversInCorestartable()
                && mBatteryController instanceof BatteryControllerImpl) {
            mBackgroundExecutor.execute(() -> registerReceiver());
        }
    }
}
