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
package com.android.server.usb.hal.gadget;

import static android.hardware.usb.UsbManager.GADGET_HAL_V2_0;

import static com.android.server.usb.UsbDeviceManager.logAndPrint;
import static com.android.server.usb.UsbDeviceManager.logAndPrintException;

import android.annotation.Nullable;
import android.hardware.usb.gadget.IUsbGadget;
import android.hardware.usb.gadget.IUsbGadgetCallback;
import android.hardware.usb.UsbManager.UsbGadgetHalVersion;
import android.os.ServiceManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.usb.UsbDeviceManager;

import java.util.ArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Implements the methods to interact with AIDL USB HAL.
 */
public final class UsbGadgetAidl implements UsbGadgetHal {
    private static final String TAG = UsbGadgetAidl.class.getSimpleName();
    private static final String USB_GADGET_AIDL_SERVICE = IUsbGadget.DESCRIPTOR + "/default";
    // Proxy object for the usb gadget hal daemon.
    @GuardedBy("mGadgetProxyLock")
    private IUsbGadget mGadgetProxy;
    private final UsbDeviceManager mDeviceManager;
    public final IndentingPrintWriter mPw;
    // Mutex for all mutable shared state.
    private final Object mGadgetProxyLock = new Object();
    // Callback when the UsbDevice status is changed by the kernel.
    private UsbGadgetCallback mUsbGadgetCallback;

    public @UsbGadgetHalVersion int getGadgetHalVersion() throws RemoteException {
        synchronized (mGadgetProxyLock) {
            if (mGadgetProxy == null) {
                throw new RemoteException("IUsb not initialized yet");
            }
        }
        Slog.i(TAG, "USB Gadget HAL AIDL version: GADGET_HAL_V2_0");
        return GADGET_HAL_V2_0;
    }

    @Override
    public void systemReady() {
    }

    public void serviceDied() {
        logAndPrint(Log.ERROR, mPw, "Usb Gadget AIDL hal service died");
        synchronized (mGadgetProxyLock) {
            mGadgetProxy = null;
        }
        connectToProxy(null);
    }

    private void connectToProxy(IndentingPrintWriter pw) {
        synchronized (mGadgetProxyLock) {
            if (mGadgetProxy != null) {
                return;
            }

            try {
                mGadgetProxy = IUsbGadget.Stub.asInterface(
                        ServiceManager.waitForService(USB_GADGET_AIDL_SERVICE));
            } catch (NoSuchElementException e) {
                logAndPrintException(pw, "connectToProxy: usb gadget hal service not found."
                        + " Did the service fail to start?", e);
            }
        }
    }

    static boolean isServicePresent(IndentingPrintWriter pw) {
        try {
            return ServiceManager.isDeclared(USB_GADGET_AIDL_SERVICE);
        } catch (NoSuchElementException e) {
            logAndPrintException(pw, "connectToProxy: usb gadget Aidl hal service not found.", e);
        }

        return false;
    }

    public UsbGadgetAidl(UsbDeviceManager deviceManager, IndentingPrintWriter pw) {
        mDeviceManager = Objects.requireNonNull(deviceManager);
        mPw = pw;
        connectToProxy(mPw);
    }

    @Override
    public void getCurrentUsbFunctions(long operationId) {
        synchronized (mGadgetProxyLock) {
            try {
                mGadgetProxy.getCurrentUsbFunctions(new UsbGadgetCallback(), operationId);
            } catch (RemoteException e) {
                logAndPrintException(mPw,
                        "RemoteException while calling getCurrentUsbFunctions"
                        + ", opID:" + operationId, e);
                return;
            }
        }
    }

    @Override
    public void getUsbSpeed(long operationId) {
        try {
            synchronized (mGadgetProxyLock) {
                mGadgetProxy.getUsbSpeed(new UsbGadgetCallback(), operationId);
            }
        } catch (RemoteException e) {
            logAndPrintException(mPw,
                    "RemoteException while calling getUsbSpeed"
                    + ", opID:" + operationId, e);
            return;
        }
    }

    @Override
    public void reset() {
        try {
            synchronized (mGadgetProxyLock) {
                mGadgetProxy.reset();
            }
        } catch (RemoteException e) {
            logAndPrintException(mPw,
                    "RemoteException while calling getUsbSpeed", e);
            return;
        }
    }

    @Override
    public void setCurrentUsbFunctions(int mRequest, long mFunctions,
            boolean mChargingFunctions, int timeout, long operationId) {
        try {
            mUsbGadgetCallback = new UsbGadgetCallback(mRequest,
                                      mFunctions, mChargingFunctions);
            synchronized (mGadgetProxyLock) {
                mGadgetProxy.setCurrentUsbFunctions(mFunctions, mUsbGadgetCallback,
                        timeout, operationId);
            }
        } catch (RemoteException e) {
            logAndPrintException(mPw,
                    "RemoteException while calling setCurrentUsbFunctions: "
                    + "mRequest=" + mRequest
                    + ", mFunctions=" + mFunctions
                    + ", mChargingFunctions=" + mChargingFunctions
                    + ", timeout=" + timeout
                    + ", opID:" + operationId, e);
            return;
        }
    }

    private class UsbGadgetCallback extends IUsbGadgetCallback.Stub {
        public int mRequest;
        public long mFunctions;
        public boolean mChargingFunctions;

        UsbGadgetCallback() {
        }

        UsbGadgetCallback(int request, long functions,
                boolean chargingFunctions) {
            mRequest = request;
            mFunctions = functions;
            mChargingFunctions = chargingFunctions;
        }

        @Override
        public void setCurrentUsbFunctionsCb(long functions,
                int status, long transactionId) {
            mDeviceManager.setCurrentUsbFunctionsCb(functions, status,
                    mRequest, mFunctions, mChargingFunctions);
        }

        @Override
        public void getCurrentUsbFunctionsCb(long functions,
                int status, long transactionId) {
            mDeviceManager.getCurrentUsbFunctionsCb(functions, status);
        }

        @Override
        public void getUsbSpeedCb(int speed, long transactionId) {
            mDeviceManager.getUsbSpeedCb(speed);
        }

        @Override
        public String getInterfaceHash() {
            return IUsbGadgetCallback.HASH;
        }

        @Override
        public int getInterfaceVersion() {
            return IUsbGadgetCallback.VERSION;
        }
    }
}

