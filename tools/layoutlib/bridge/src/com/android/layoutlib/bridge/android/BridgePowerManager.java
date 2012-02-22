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
    public void acquireWakeLock(int arg0, IBinder arg1, String arg2, WorkSource arg3)
            throws RemoteException {
        // pass for now.
    }

    @Override
    public void clearUserActivityTimeout(long arg0, long arg1) throws RemoteException {
        // pass for now.
    }

    @Override
    public void crash(String arg0) throws RemoteException {
        // pass for now.
    }

    @Override
    public int getSupportedWakeLockFlags() throws RemoteException {
        // pass for now.
        return 0;
    }

    @Override
    public void goToSleep(long arg0) throws RemoteException {
        // pass for now.
    }

    @Override
    public void goToSleepWithReason(long arg0, int arg1) throws RemoteException {
        // pass for now.
    }

    @Override
    public void preventScreenOn(boolean arg0) throws RemoteException {
        // pass for now.
    }

    @Override
    public void reboot(String arg0) throws RemoteException {
        // pass for now.
    }

    @Override
    public void releaseWakeLock(IBinder arg0, int arg1) throws RemoteException {
        // pass for now.
    }

    @Override
    public void setAttentionLight(boolean arg0, int arg1) throws RemoteException {
        // pass for now.
    }

    @Override
    public void setAutoBrightnessAdjustment(float arg0) throws RemoteException {
        // pass for now.
    }

    @Override
    public void setBacklightBrightness(int arg0) throws RemoteException {
        // pass for now.
    }

    @Override
    public void setMaximumScreenOffTimeount(int arg0) throws RemoteException {
        // pass for now.
    }

    @Override
    public void setPokeLock(int arg0, IBinder arg1, String arg2) throws RemoteException {
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
    public void userActivity(long arg0, boolean arg1) throws RemoteException {
        // pass for now.
    }

    @Override
    public void userActivityWithForce(long arg0, boolean arg1, boolean arg2) throws RemoteException {
        // pass for now.
    }
}
