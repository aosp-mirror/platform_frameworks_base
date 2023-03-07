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

import static android.hardware.usb.UsbManager.GADGET_HAL_NOT_SUPPORTED;
import static android.hardware.usb.UsbManager.GADGET_HAL_V1_0;
import static android.hardware.usb.UsbManager.GADGET_HAL_V1_1;
import static android.hardware.usb.UsbManager.GADGET_HAL_V1_2;

import static com.android.server.usb.UsbDeviceManager.logAndPrint;
import static com.android.server.usb.UsbDeviceManager.logAndPrintException;

import android.annotation.Nullable;
import android.hardware.usb.gadget.V1_0.Status;
import android.hardware.usb.gadget.V1_0.IUsbGadget;
import android.hardware.usb.gadget.V1_2.IUsbGadgetCallback;
import android.hardware.usb.gadget.V1_2.UsbSpeed;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbManager.UsbGadgetHalVersion;
import android.hardware.usb.UsbManager.UsbHalVersion;
import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
import android.os.IHwBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.usb.UsbDeviceManager;

import java.util.ArrayList;
import java.util.NoSuchElementException;
import java.util.Objects;
/**
 *
 */
public final class UsbGadgetHidl implements UsbGadgetHal {
    // Cookie sent for usb gadget hal death notification.
    private static final int USB_GADGET_HAL_DEATH_COOKIE = 2000;
    // Proxy object for the usb gadget hal daemon.
    @GuardedBy("mGadgetProxyLock")
    private IUsbGadget mGadgetProxy;
    private UsbDeviceManager mDeviceManager;
    private final IndentingPrintWriter mPw;
    // Mutex for all mutable shared state.
    private final Object mGadgetProxyLock = new Object();
    private UsbGadgetCallback mUsbGadgetCallback;

    public @UsbGadgetHalVersion int getGadgetHalVersion() throws RemoteException {
        int version;
        synchronized(mGadgetProxyLock) {
            if (mGadgetProxy == null) {
                throw new RemoteException("IUsbGadget not initialized yet");
            }
            if (android.hardware.usb.gadget.V1_2.IUsbGadget.castFrom(mGadgetProxy) != null) {
                version = UsbManager.GADGET_HAL_V1_2;
            } else if (android.hardware.usb.gadget.V1_1.IUsbGadget.castFrom(mGadgetProxy) != null) {
                version = UsbManager.GADGET_HAL_V1_1;
            } else {
                version = UsbManager.GADGET_HAL_V1_0;
            }
            logAndPrint(Log.INFO, mPw, "USB Gadget HAL HIDL version: " + version);
            return version;
        }
    }

    final class DeathRecipient implements IHwBinder.DeathRecipient {
        private final IndentingPrintWriter mPw;

        DeathRecipient(IndentingPrintWriter pw) {
            mPw = pw;
        }

        @Override
        public void serviceDied(long cookie) {
            if (cookie == USB_GADGET_HAL_DEATH_COOKIE) {
                logAndPrint(Log.ERROR, mPw, "Usb Gadget hal service died cookie: " + cookie);
                synchronized (mGadgetProxyLock) {
                    mGadgetProxy = null;
                }
            }
        }
    }

    final class ServiceNotification extends IServiceNotification.Stub {
        @Override
        public void onRegistration(String fqName, String name, boolean preexisting) {
            logAndPrint(Log.INFO, mPw, "Usb gadget hal service started " + fqName + " " + name);
            connectToProxy(null);
        }
    }

    private void connectToProxy(IndentingPrintWriter pw) {
        synchronized (mGadgetProxyLock) {
            if (mGadgetProxy != null) {
                return;
            }

            try {
                mGadgetProxy = IUsbGadget.getService();
                mGadgetProxy.linkToDeath(new DeathRecipient(pw), USB_GADGET_HAL_DEATH_COOKIE);
            } catch (NoSuchElementException e) {
                logAndPrintException(pw, "connectToProxy: usb gadget hal service not found."
                        + " Did the service fail to start?", e);
            } catch (RemoteException e) {
                logAndPrintException(pw, "connectToProxy: usb gadget hal service not responding"
                        , e);
            }
        }
    }

    @Override
    public void systemReady() {
    }

    static boolean isServicePresent(IndentingPrintWriter pw) {
        try {
            IUsbGadget.getService(true);
        } catch (NoSuchElementException e) {
            logAndPrintException(pw, "connectToProxy: usb gadget hidl hal service not found.", e);
            return false;
        } catch (RemoteException e) {
            logAndPrintException(pw, "IUSBGadget hal service present but failed to get service", e);
        }

        return true;
    }

    public UsbGadgetHidl(UsbDeviceManager deviceManager, IndentingPrintWriter pw) {
        mDeviceManager = Objects.requireNonNull(deviceManager);
        mPw = pw;
        try {
            ServiceNotification serviceNotification = new ServiceNotification();

            boolean ret = IServiceManager.getService()
                    .registerForNotifications("android.hardware.usb.gadget@1.0::IUsbGadget",
                            "", serviceNotification);
            if (!ret) {
                logAndPrint(Log.ERROR, pw, "Failed to register service start notification");
            }
        } catch (RemoteException e) {
            logAndPrintException(pw, "Failed to register service start notification", e);
            return;
        }
        connectToProxy(mPw);
    }

    @Override
    public void getCurrentUsbFunctions(long transactionId) {
        try {
            synchronized(mGadgetProxyLock) {
                mGadgetProxy.getCurrentUsbFunctions(new UsbGadgetCallback());
            }
        } catch (RemoteException e) {
            logAndPrintException(mPw,
                    "RemoteException while calling getCurrentUsbFunctions", e);
            return;
        }
    }

    @Override
    public void getUsbSpeed(long transactionId) {
        try {
            synchronized(mGadgetProxyLock) {
                if (android.hardware.usb.gadget.V1_2.IUsbGadget.castFrom(mGadgetProxy) != null) {
                    android.hardware.usb.gadget.V1_2.IUsbGadget gadgetProxy =
                    android.hardware.usb.gadget.V1_2.IUsbGadget.castFrom(mGadgetProxy);
                    gadgetProxy.getUsbSpeed(new UsbGadgetCallback());
                }
            }
        } catch (RemoteException e) {
            logAndPrintException(mPw, "get UsbSpeed failed", e);
        }
    }

    @Override
    public void reset(long transactionId) {
        try {
            synchronized(mGadgetProxyLock) {
                if (android.hardware.usb.gadget.V1_1.IUsbGadget.castFrom(mGadgetProxy) != null) {
                    android.hardware.usb.gadget.V1_1.IUsbGadget gadgetProxy =
                    android.hardware.usb.gadget.V1_1.IUsbGadget.castFrom(mGadgetProxy);
                    gadgetProxy.reset();
                }
            }
        } catch (RemoteException e) {
            logAndPrintException(mPw,
                    "RemoteException while calling reset", e);
            return;
        }
    }

    @Override
    public void setCurrentUsbFunctions(int mRequest, long mFunctions,
            boolean mChargingFunctions, int timeout, long operationId) {
        try {
            mUsbGadgetCallback = new UsbGadgetCallback(null, mRequest,
                                      mFunctions, mChargingFunctions);
            synchronized(mGadgetProxyLock) {
                mGadgetProxy.setCurrentUsbFunctions(mFunctions, mUsbGadgetCallback, timeout);
            }
        } catch (RemoteException e) {
            logAndPrintException(mPw,
                    "RemoteException while calling setCurrentUsbFunctions"
                    + " mRequest = " + mRequest
                    + ", mFunctions = " + mFunctions
                    + ", timeout = " + timeout
                    + ", mChargingFunctions = " + mChargingFunctions
                    + ", operationId =" + operationId, e);
            return;
        }
    }

    private class UsbGadgetCallback extends IUsbGadgetCallback.Stub {
        public int mRequest;
        public long mFunctions;
        public boolean mChargingFunctions;

        UsbGadgetCallback() {
        }
        UsbGadgetCallback(IndentingPrintWriter pw, int request,
                long functions, boolean chargingFunctions) {
            mRequest = request;
            mFunctions = functions;
            mChargingFunctions = chargingFunctions;
        }

        @Override
        public void setCurrentUsbFunctionsCb(long functions,
                int status) {
            mDeviceManager.setCurrentUsbFunctionsCb(functions, status,
                    mRequest, mFunctions, mChargingFunctions);
        }

        @Override
        public void getCurrentUsbFunctionsCb(long functions,
                int status) {
            mDeviceManager.getCurrentUsbFunctionsCb(functions, status);
        }

        @Override
        public void getUsbSpeedCb(int speed) {
            mDeviceManager.getUsbSpeedCb(speed);
        }
    }
}

