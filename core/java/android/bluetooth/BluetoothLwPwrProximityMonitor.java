/*
 * Copyright (c) 2013, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *   * Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *   * Redistributions in binary form must reproduce the above
 *     copyright notice, this list of conditions and the following
 *     disclaimer in the documentation and/or other materials provided
 *     with the distribution.
 *   * Neither the name of The Linux Foundation nor the names of its
 *     contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */


package android.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.QBluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.IBluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothRssiMonitorCallback;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.IBluetoothManager;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.os.RemoteException;
import android.util.Log;
import java.util.Timer;
import java.util.TimerTask;

/** @hide */
public final class  BluetoothLwPwrProximityMonitor implements QBluetoothAdapter.LeLppCallback {
    private static final     String TAG = "BluetoothLwPwrProximityMonitor";
    private static final     boolean DBG = true;
    private BluetoothDevice  mDevice = null;
    private Context          mContext = null;
    private BluetoothGatt    mGattProfile = null;
    private QBluetoothAdapter     mQAdapter = null;
    private BluetoothRssiMonitorCallback mMonitorCbk;
    private boolean   mAutoConnect = false;
    private int       mState;
    private final Object mStateLock = new Object();
    /* This timer is triggered in case that BluetoothGatt does not callback when we perform connect/disconnect */
    private Timer     mTimer = null;
    private final int mTimeOutValue = 10*1000;
    private final class ConnectTimeOutTask extends TimerTask {
        public void run() {
            if (DBG) Log.d(TAG, "connect timer triggered!");
            boolean notify = false;
            synchronized(mStateLock) {
                if (mState == MONITOR_STATE_STARTING) {
                    mState = MONITOR_STATE_IDLE;
                    if (mQAdapter != null && mDevice != null) {
                        mQAdapter.registerLppClient(BluetoothLwPwrProximityMonitor.this, mDevice.getAddress(), false);
                    }
                    notify = true;
                }
            }
            if (notify && mMonitorCbk != null) {
                mMonitorCbk.onStopped();
            }
        }
    };

    private final class DisconnectTimeOutTask extends TimerTask {
        public void run() {
            if (DBG) Log.d(TAG, "disconnect timer triggered");
            boolean notify = false;
            synchronized(mStateLock) {
                if (mState == MONITOR_STATE_STOPPING) {
                    mState = MONITOR_STATE_IDLE;
                    if (mQAdapter != null && mDevice != null) {
                        mQAdapter.registerLppClient(BluetoothLwPwrProximityMonitor.this, mDevice.getAddress(), false);
                    }
                    notify = true;
                }
            }
            if (notify && mMonitorCbk != null) {
                mMonitorCbk.onStopped();
            }
        }
    };
    /* Monitor state constants */
    private static final int MONITOR_STATE_IDLE     = 0;
    private static final int MONITOR_STATE_STARTING = 1;
    private static final int MONITOR_STATE_STOPPING = 2;
    private static final int MONITOR_STATE_STARTED  = 3;
    private static final int MONITOR_STATE_CLOSED   = 4;

    /* constants for rssi threshold event */
    /** @hide */
    public static final int RSSI_MONITOR_DISABLED = 0x00;
    /** @hide */
    public static final int RSSI_HIGH_ALERT       = 0x01;
    /** @hide */
    public static final int RSSI_MILD_ALERT       = 0x02;
    /** @hide */
    public static final int RSSI_NO_ALERT         = 0x03;

    /* command status */
    /** @hide */
    public static final int COMMAND_STATUS_SUCCESS = 0x00;
    /** @hide */
    public static final int COMMAND_STATUS_FAILED  = 0x01;
    private int mLowerLimit;
    private int mUpperLimit;

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback () {
        public void onConnectionStateChange (BluetoothGatt gatt, int status,
                                             int newState) {
            if (DBG) Log.d(TAG, "onConnectionStateChange() + newState=" + newState);
            if (mDevice == null) return;
            if (mGattProfile != gatt) return;
            if(mQAdapter == null) return;
            boolean stop = false;
            synchronized(mStateLock){
                cancelTimer();
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (mState != MONITOR_STATE_CLOSED) {
                        mQAdapter.registerLppClient(BluetoothLwPwrProximityMonitor.this,mDevice.getAddress(), false);
                        mState = MONITOR_STATE_IDLE;
                        stop = true;
                    }
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (mState == MONITOR_STATE_STARTING) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            if(!mQAdapter.writeRssiThreshold(BluetoothLwPwrProximityMonitor.this, mLowerLimit, mUpperLimit)) {
                                mGattProfile.disconnect();
                                mState = MONITOR_STATE_STOPPING;
                                setTimer(BluetoothLwPwrProximityMonitor.this.new DisconnectTimeOutTask(), mTimeOutValue);
                            }
                        } else {
                            stop = true;
                        }
                    }
                }
            }
            if (stop && mMonitorCbk != null){
                mMonitorCbk.onStopped();
                if (DBG) Log.d(TAG, "Monitor is stopped");
            }
        }
    };

    public BluetoothLwPwrProximityMonitor (Context cxt, String device, BluetoothRssiMonitorCallback cbk) {
        mContext    = cxt;
        mState      = MONITOR_STATE_CLOSED;
        mMonitorCbk = cbk;

        try {
            mDevice     = new BluetoothDevice(device);
        } catch (IllegalArgumentException e) {
            mDevice = null;
            if (DBG) Log.e(TAG, "", e);
        }

        mQAdapter  = QBluetoothAdapter.getDefaultAdapter();
        if (mDevice != null && mQAdapter != null) {
            mState = MONITOR_STATE_IDLE;
        } else {
            mDevice = null;
            mQAdapter = null;
        }
    }

    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }

    private void setTimer(TimerTask task, int delay) {
        if (DBG) Log.d(TAG, "setTimer() delay=" + delay);
        mTimer = new Timer();
        mTimer.schedule(task, delay);
    }

    private void cancelTimer() {
        if (DBG) Log.d(TAG, "cancelTimer()");
        if (mTimer != null) {
            mTimer.cancel();
        }
        mTimer = null;
    }

    /** @hide */
    public boolean start (int thresh_min, int thresh_max) {
        if (DBG) Log.d(TAG, "start() low=" + thresh_min + ", upper=" + thresh_max);
        synchronized(mStateLock){
            if (mState != MONITOR_STATE_IDLE) {
                if (DBG) Log.d(TAG, "start() invalid state, monitor is not idle");
                return false;
            }
            if (!mQAdapter.registerLppClient(this, mDevice.getAddress(), true)) {
                if (DBG) Log.d(TAG, "cannot register LPP Client");
                return false;
            }
            mState = MONITOR_STATE_STARTING;
            mLowerLimit = thresh_min;
            mUpperLimit = thresh_max;

            try {
                if (mGattProfile == null) {
                    mGattProfile = mDevice.connectGatt(mContext, mAutoConnect, mGattCallback);
                    if (mGattProfile == null){
                        mQAdapter.registerLppClient(this, mDevice.getAddress(), false);
                        mState = MONITOR_STATE_IDLE;
                        return false;
                    }
                } else {
                    if (!mGattProfile.connect()) {
                        mQAdapter.registerLppClient(this, mDevice.getAddress(), false);
                        mState = MONITOR_STATE_IDLE;
                        return false;
                    }
                }
            } catch (IllegalStateException e) {
                mQAdapter.registerLppClient(this, mDevice.getAddress(), false);
                mState = MONITOR_STATE_IDLE;
                return false;
            }
            setTimer(this.new ConnectTimeOutTask(), mTimeOutValue);
        }
        if (DBG) Log.d(TAG, "Monitor is starting");
        return true;
    }

    /** @hide */
    public boolean readRssiThreshold() {
        if (DBG) Log.d(TAG, "readRssiThreshold()");
        synchronized(mStateLock) {
            if (mState == MONITOR_STATE_STARTED) {
                if (mQAdapter != null) {
                    mQAdapter.readRssiThreshold(this);
                    return true;
                }
            }
        }
        if (DBG) Log.e(TAG, "readRssiThreshold() fail");
        return false;
    }

    /** @hide */
    public void stop() {
        if (DBG) Log.d(TAG, "stop()");
        synchronized(mStateLock) {
            if(mDevice != null && mQAdapter != null && mGattProfile != null &&
               (mState == MONITOR_STATE_STARTING ||
                 mState == MONITOR_STATE_STARTED)) {
                cancelTimer();
                mQAdapter.enableRssiMonitor(this, false);
                mGattProfile.disconnect();
                mState = MONITOR_STATE_STOPPING;
                setTimer(this.new DisconnectTimeOutTask(), mTimeOutValue);
                mQAdapter.registerLppClient(this, mDevice.getAddress(), false);
                if (DBG) Log.d(TAG, "Monitor is stopping");
            }
        }
    }
    /** @hide */
    public void close() {
        if (DBG) Log.d(TAG, "close()");
        if(MONITOR_STATE_CLOSED == mState)
            return;

        synchronized(mStateLock) {
            cancelTimer();
            if(mDevice != null && mGattProfile != null && mQAdapter != null){
               if(mState == MONITOR_STATE_STARTING ||
                 mState == MONITOR_STATE_STARTED) {
                    if(mState == MONITOR_STATE_STARTED)  mQAdapter.enableRssiMonitor(this, false);
                    mGattProfile.disconnect();
                }
                mGattProfile.close();
                mQAdapter.registerLppClient(this, mDevice.getAddress(), false);
            }
            if (DBG) Log.d(TAG, "Monitor is closed");
            mState = MONITOR_STATE_CLOSED;
            mDevice = null;
            mQAdapter = null;
            mGattProfile = null;
            mMonitorCbk = null;
        }
    }
    /** @hide */
    public void onWriteRssiThreshold(int status) {
        if (DBG) Log.d(TAG, "onWriteRssiThreshold() status=" + status);
        synchronized (mStateLock) {
            if (mState == MONITOR_STATE_STARTING){
                if (status == BluetoothGatt.GATT_SUCCESS){
                    if (mQAdapter != null) {
                        mQAdapter.enableRssiMonitor(this, true);
                    }
                } else {
                    if (mGattProfile != null) {
                        mGattProfile.disconnect();
                        mState = MONITOR_STATE_STOPPING;
                        setTimer(this.new DisconnectTimeOutTask(), mTimeOutValue);
                    }
                }
            }
        }
    }
    /** @hide */
    public void onReadRssiThreshold(int low, int upper, int alert, int status) {
        if (DBG) Log.d(TAG, "onReadRssiThreshold() LowerLimit=" + low +
                       ", UpperLimit=" + upper + ", Alert=" + alert + ", status=" + status);
        if (mMonitorCbk != null) {
            mMonitorCbk.onReadRssiThreshold(low, upper, alert, (status == 0)?COMMAND_STATUS_SUCCESS:COMMAND_STATUS_FAILED);
        }
    }
    /** @hide */
    public void onEnableRssiMonitor(int enable, int status) {
        if (DBG) Log.d(TAG, "onEnableRssiMonitor() enable=" + enable + ", status=" + status);
        synchronized(mStateLock) {
            if (mState == MONITOR_STATE_STARTING) {
                if (status == BluetoothGatt.GATT_SUCCESS && (enable != 0)) {
                    mState = MONITOR_STATE_STARTED;
                    if (DBG) Log.d(TAG, "Monitor is started successfully");
                } else {
                    if (mGattProfile != null) {
                        mGattProfile.disconnect();
                        mState = MONITOR_STATE_STOPPING;
                        setTimer(this.new DisconnectTimeOutTask(), mTimeOutValue);
                    }
                }
            }
        }

        if (mState == MONITOR_STATE_STARTED && mMonitorCbk != null) {
            if (DBG) Log.d(TAG, "Notify users that monitor has been started successfully");
            mMonitorCbk.onStarted();
        }
    }
    /** @hide */
    public void onRssiThresholdEvent(int evtType, int rssi) {
        if (DBG) Log.d(TAG, "onRssiThresholdEvent() event=" + evtType + ", rssi=" + rssi);
        if (mMonitorCbk != null) mMonitorCbk.onAlert(evtType, rssi);
    }

    /** @hide */
    public boolean onUpdateLease() {
        if (DBG) Log.d(TAG, "onUpdateLease()");
        synchronized(mStateLock) {
            return (mState != MONITOR_STATE_IDLE && mState != MONITOR_STATE_CLOSED);
        }
    }
}
