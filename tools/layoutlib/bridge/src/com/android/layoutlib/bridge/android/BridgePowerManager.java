/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.layoutlib.bridge.android;

import android.os.IBinder;
import android.os.IPowerManager;
import android.os.RemoteException;
import android.os.WorkSource;

/**
 * Fake implementation of IPowerManager.
 *
 */
public class BridgePowerManager implements IPowerManager {

    @Override
    public boolean isScreenOn() throws RemoteException {
        return true;
    }

    @Override
    public IBinder asBinder() {
        // pass for now.
        return null;
    }

    @Override
    public void acquireWakeLock(IBinder arg0, int arg1, String arg2, String arg2_5, WorkSource arg3)
            throws RemoteException {
        // pass for now.
    }

    @Override
    public void acquireWakeLockWithUid(IBinder arg0, int arg1, String arg2, String arg2_5, int arg3)
            throws RemoteException {
        // pass for now.
    }

    @Override
    public void crash(String arg0) throws RemoteException {
        // pass for now.
    }

    @Override
    public void goToSleep(long arg0, int arg1) throws RemoteException {
        // pass for now.
    }

    @Override
    public void nap(long arg0) throws RemoteException {
        // pass for now.
    }

    @Override
    public void reboot(boolean confirm, String reason, boolean wait) {
        // pass for now.
    }

    @Override
    public void shutdown(boolean confirm, boolean wait) {
        // pass for now.
    }

    @Override
    public void releaseWakeLock(IBinder arg0, int arg1) throws RemoteException {
        // pass for now.
    }

    @Override
    public void updateWakeLockUids(IBinder arg0, int[] arg1) throws RemoteException {
        // pass for now.
    }

    @Override
    public void setAttentionLight(boolean arg0, int arg1) throws RemoteException {
        // pass for now.
    }

    @Override
    public void setTemporaryScreenAutoBrightnessAdjustmentSettingOverride(float arg0) throws RemoteException {
        // pass for now.
    }

    @Override
    public void setTemporaryScreenBrightnessSettingOverride(int arg0) throws RemoteException {
        // pass for now.
    }

    @Override
    public void setMaximumScreenOffTimeoutFromDeviceAdmin(int arg0) throws RemoteException {
        // pass for now.
    }

    @Override
    public void setStayOnSetting(int arg0) throws RemoteException {
        // pass for now.
    }

    @Override
    public void updateWakeLockWorkSource(IBinder arg0, WorkSource arg1) throws RemoteException {
        // pass for now.
    }

    @Override
    public boolean isWakeLockLevelSupported(int level) throws RemoteException {
        // pass for now.
        return true;
    }

    @Override
    public void userActivity(long time, int event, int flags) throws RemoteException {
        // pass for now.
    }

    @Override
    public void wakeUp(long time) throws RemoteException {
        // pass for now.
    }
}
