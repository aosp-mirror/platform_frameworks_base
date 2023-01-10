/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.server.usb.hal.port;

import static android.hardware.usb.UsbManager.USB_HAL_NOT_SUPPORTED;
import static android.hardware.usb.UsbManager.USB_HAL_V1_0;
import static android.hardware.usb.UsbManager.USB_HAL_V1_1;
import static android.hardware.usb.UsbManager.USB_HAL_V1_2;
import static android.hardware.usb.UsbManager.USB_HAL_V1_3;
import static android.hardware.usb.UsbOperationInternal.USB_OPERATION_ERROR_INTERNAL;
import static android.hardware.usb.UsbOperationInternal.USB_OPERATION_ERROR_NOT_SUPPORTED;
import static android.hardware.usb.UsbOperationInternal.USB_OPERATION_SUCCESS;
import static android.hardware.usb.UsbPortStatus.CONTAMINANT_DETECTION_NOT_SUPPORTED;
import static android.hardware.usb.UsbPortStatus.CONTAMINANT_PROTECTION_NONE;
import static android.hardware.usb.UsbPortStatus.DATA_ROLE_DEVICE;
import static android.hardware.usb.UsbPortStatus.DATA_ROLE_HOST;
import static android.hardware.usb.UsbPortStatus.MODE_DFP;
import static android.hardware.usb.UsbPortStatus.MODE_DUAL;
import static android.hardware.usb.UsbPortStatus.MODE_UFP;
import static android.hardware.usb.UsbPortStatus.POWER_BRICK_STATUS_UNKNOWN;
import static android.hardware.usb.UsbPortStatus.POWER_ROLE_SINK;
import static android.hardware.usb.UsbPortStatus.POWER_ROLE_SOURCE;
import static android.hardware.usb.UsbPortStatus.DATA_STATUS_DISABLED_FORCE;
import static android.hardware.usb.UsbPortStatus.DATA_STATUS_UNKNOWN;
import static android.hardware.usb.UsbPortStatus.PLUG_STATE_UNKNOWN;
import static android.hardware.usb.DisplayPortAltModeInfo.DISPLAYPORT_ALT_MODE_STATUS_UNKNOWN;

import static com.android.server.usb.UsbPortManager.logAndPrint;
import static com.android.server.usb.UsbPortManager.logAndPrintException;

import android.annotation.Nullable;
import android.hardware.usb.IUsbOperationInternal;
import android.hardware.usb.UsbManager.UsbHalVersion;
import android.hardware.usb.UsbPort;
import android.hardware.usb.UsbPortStatus;
import android.hardware.usb.V1_0.IUsb;
import android.hardware.usb.V1_0.PortRoleType;
import android.hardware.usb.V1_0.Status;
import android.hardware.usb.V1_1.PortStatus_1_1;
import android.hardware.usb.V1_2.IUsbCallback;
import android.hardware.usb.V1_0.PortRole;
import android.hardware.usb.V1_2.PortStatus;
import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
import android.os.IHwBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.usb.UsbPortManager;
import com.android.server.usb.hal.port.RawPortInfo;

import java.util.ArrayList;
import java.util.NoSuchElementException;
import java.util.Objects;
/**
 *
 */
public final class UsbPortHidl implements UsbPortHal {
    private static final String TAG = UsbPortHidl.class.getSimpleName();
    // Cookie sent for usb hal death notification.
    private static final int USB_HAL_DEATH_COOKIE = 1000;
    // Proxy object for the usb hal daemon.
    @GuardedBy("mLock")
    private IUsb mProxy;
    private UsbPortManager mPortManager;
    public IndentingPrintWriter mPw;
    // Mutex for all mutable shared state.
    private final Object mLock = new Object();
    // Callback when the UsbPort status is changed by the kernel.
    private HALCallback mHALCallback;
    private boolean mSystemReady;
    // Workaround since HIDL HAL versions report UsbDataEnabled status in UsbPortStatus;
    private static int sUsbDataStatus = DATA_STATUS_UNKNOWN;

    public @UsbHalVersion int getUsbHalVersion() throws RemoteException {
        int version;
        synchronized(mLock) {
            if (mProxy == null) {
                throw new RemoteException("IUsb not initialized yet");
            }
            if (android.hardware.usb.V1_3.IUsb.castFrom(mProxy) != null) {
                version = USB_HAL_V1_3;
            } else if (android.hardware.usb.V1_2.IUsb.castFrom(mProxy) != null) {
                version = USB_HAL_V1_2;
            } else if (android.hardware.usb.V1_1.IUsb.castFrom(mProxy) != null) {
                version = USB_HAL_V1_1;
            } else {
                version = USB_HAL_V1_0;
            }
            logAndPrint(Log.INFO, null, "USB HAL HIDL version: " + version);
            return version;
        }
    }

    final class DeathRecipient implements IHwBinder.DeathRecipient {
        public IndentingPrintWriter pw;

        DeathRecipient(IndentingPrintWriter pw) {
            this.pw = pw;
        }

        @Override
        public void serviceDied(long cookie) {
            if (cookie == USB_HAL_DEATH_COOKIE) {
                logAndPrint(Log.ERROR, pw, "Usb hal service died cookie: " + cookie);
                synchronized (mLock) {
                    mProxy = null;
                }
            }
        }
    }

    final class ServiceNotification extends IServiceNotification.Stub {
        @Override
        public void onRegistration(String fqName, String name, boolean preexisting) {
            logAndPrint(Log.INFO, null, "Usb hal service started " + fqName + " " + name);
            connectToProxy(null);
        }
    }

    private void connectToProxy(IndentingPrintWriter pw) {
        synchronized (mLock) {
            if (mProxy != null) {
                return;
            }

            try {
                mProxy = IUsb.getService();
                mProxy.linkToDeath(new DeathRecipient(pw), USB_HAL_DEATH_COOKIE);
                mProxy.setCallback(mHALCallback);
                mProxy.queryPortStatus();
                //updateUsbHalVersion();
            } catch (NoSuchElementException e) {
                logAndPrintException(pw, "connectToProxy: usb hal service not found."
                        + " Did the service fail to start?", e);
            } catch (RemoteException e) {
                logAndPrintException(pw, "connectToProxy: usb hal service not responding", e);
            }
        }
    }

    @Override
    public void systemReady() {
        mSystemReady = true;
    }

    static boolean isServicePresent(IndentingPrintWriter pw) {
        try {
            IUsb.getService(true);
        } catch (NoSuchElementException e) {
            logAndPrintException(pw, "connectToProxy: usb hidl hal service not found.", e);
            return false;
        } catch (RemoteException e) {
            logAndPrintException(pw, "IUSB hal service present but failed to get service", e);
        }

        return true;
    }

    public UsbPortHidl(UsbPortManager portManager, IndentingPrintWriter pw) {
        mPortManager = Objects.requireNonNull(portManager);
        mPw = pw;
        mHALCallback = new HALCallback(null, mPortManager, this);
        try {
            ServiceNotification serviceNotification = new ServiceNotification();

            boolean ret = IServiceManager.getService()
                    .registerForNotifications("android.hardware.usb@1.0::IUsb",
                            "", serviceNotification);
            if (!ret) {
                logAndPrint(Log.ERROR, null,
                        "Failed to register service start notification");
            }
        } catch (RemoteException e) {
            logAndPrintException(null,
                    "Failed to register service start notification", e);
            return;
        }
        connectToProxy(mPw);
    }

    @Override
    public void enableContaminantPresenceDetection(String portName, boolean enable,
            long transactionId) {
        synchronized (mLock) {
            if (mProxy == null) {
                logAndPrint(Log.ERROR, mPw, "Proxy is null. Retry !");
                return;
            }

            try {
                // Oneway call into the hal. Use the castFrom method from HIDL.
                android.hardware.usb.V1_2.IUsb proxy =
                        android.hardware.usb.V1_2.IUsb.castFrom(mProxy);
                proxy.enableContaminantPresenceDetection(portName, enable);
            } catch (RemoteException e) {
                logAndPrintException(mPw, "Failed to set contaminant detection", e);
            } catch (ClassCastException e)  {
                logAndPrintException(mPw, "Method only applicable to V1.2 or above implementation",
                    e);
            }
        }
    }

    @Override
    public void queryPortStatus(long transactionId) {
        synchronized (mLock) {
            if (mProxy == null) {
                logAndPrint(Log.ERROR, mPw, "Proxy is null. Retry !");
                return;
            }

            try {
                mProxy.queryPortStatus();
            } catch (RemoteException e) {
                logAndPrintException(null, "ServiceStart: Failed to query port status", e);
            }
       }
    }

    @Override
    public void switchMode(String portId, @HalUsbPortMode int newMode, long transactionId) {
        synchronized (mLock) {
            if (mProxy == null) {
                logAndPrint(Log.ERROR, mPw, "Proxy is null. Retry !");
                return;
            }

            PortRole newRole = new PortRole();
            newRole.type = PortRoleType.MODE;
            newRole.role = newMode;
            try {
                mProxy.switchRole(portId, newRole);
            } catch (RemoteException e) {
                logAndPrintException(mPw, "Failed to set the USB port mode: "
                    + "portId=" + portId
                    + ", newMode=" + UsbPort.modeToString(newRole.role), e);
            }
        }
    }

    @Override
    public void switchPowerRole(String portId, @HalUsbPowerRole int newPowerRole,
            long transactionId) {
        synchronized (mLock) {
            if (mProxy == null) {
                logAndPrint(Log.ERROR, mPw, "Proxy is null. Retry !");
                return;
            }

            PortRole newRole = new PortRole();
            newRole.type = PortRoleType.POWER_ROLE;
            newRole.role = newPowerRole;
            try {
                mProxy.switchRole(portId, newRole);
            } catch (RemoteException e) {
                logAndPrintException(mPw, "Failed to set the USB power role: portId=" + portId
                    + ", newPowerRole=" + UsbPort.powerRoleToString(newRole.role), e);
            }
        }
    }

    @Override
    public void enableLimitPowerTransfer(String portName, boolean limit, long transactionId,
            IUsbOperationInternal callback) {
        /* Not supported in HIDL hals*/
        try {
            callback.onOperationComplete(USB_OPERATION_ERROR_NOT_SUPPORTED);
        } catch (RemoteException e) {
            logAndPrintException(mPw, "Failed to call onOperationComplete", e);
        }
    }

    @Override
    public void enableUsbDataWhileDocked(String portName, long transactionId,
            IUsbOperationInternal callback) {
        /* Not supported in HIDL hals*/
        try {
            callback.onOperationComplete(USB_OPERATION_ERROR_NOT_SUPPORTED);
        } catch (RemoteException e) {
            logAndPrintException(mPw, "Failed to call onOperationComplete", e);
        }
    }

    @Override
    public void switchDataRole(String portId, @HalUsbDataRole int newDataRole, long transactionId) {
        synchronized (mLock) {
            if (mProxy == null) {
                logAndPrint(Log.ERROR, mPw, "Proxy is null. Retry !");
                return;
            }

            PortRole newRole = new PortRole();
            newRole.type = PortRoleType.DATA_ROLE;
            newRole.role = newDataRole;
            try {
                mProxy.switchRole(portId, newRole);
            } catch (RemoteException e) {
                logAndPrintException(mPw, "Failed to set the USB data role: portId=" + portId
                    + ", newDataRole=" + UsbPort.dataRoleToString(newRole.role), e);
            }
        }
    }

    @Override
    public void resetUsbPort(String portName, long transactionId,
            IUsbOperationInternal callback) {
        try {
            callback.onOperationComplete(USB_OPERATION_ERROR_NOT_SUPPORTED);
        } catch (RemoteException e) {
            logAndPrintException(mPw, "Failed to call onOperationComplete. opID:"
                    + transactionId
                    + " portId:" + portName, e);
        }
    }

    @Override
    public boolean enableUsbData(String portName, boolean enable, long transactionId,
            IUsbOperationInternal callback) {
        int halVersion;

        try {
            halVersion = getUsbHalVersion();
        } catch (RemoteException e) {
            logAndPrintException(mPw, "Failed to query USB HAL version. opID:"
                    + transactionId
                    + " portId:" + portName, e);
            return false;
        }

        if (halVersion != USB_HAL_V1_3) {
            try {
                callback.onOperationComplete(USB_OPERATION_ERROR_NOT_SUPPORTED);
            } catch (RemoteException e) {
                logAndPrintException(mPw, "Failed to call onOperationComplete. opID:"
                        + transactionId
                        + " portId:" + portName, e);
            }
            return false;
        }

        boolean success;
        synchronized(mLock) {
            try {
                android.hardware.usb.V1_3.IUsb proxy
                        = android.hardware.usb.V1_3.IUsb.castFrom(mProxy);
                success = proxy.enableUsbDataSignal(enable);
           } catch (RemoteException e) {
                logAndPrintException(mPw, "Failed enableUsbData: opId:" + transactionId
                        + " portId=" + portName , e);
                try {
                    callback.onOperationComplete(USB_OPERATION_ERROR_INTERNAL);
                } catch (RemoteException r) {
                    logAndPrintException(mPw, "Failed to call onOperationComplete. opID:"
                            + transactionId
                            + " portId:" + portName, r);
                }
                return false;
            }
        }
        if (success) {
            sUsbDataStatus = enable ? DATA_STATUS_UNKNOWN : DATA_STATUS_DISABLED_FORCE;
        }
        try {
            callback.onOperationComplete(success
                    ? USB_OPERATION_SUCCESS
                    : USB_OPERATION_ERROR_INTERNAL);
        } catch (RemoteException r) {
            logAndPrintException(mPw, "Failed to call onOperationComplete. opID:"
                + transactionId
                + " portId:" + portName, r);
        }
        return false;
    }

    private static class HALCallback extends IUsbCallback.Stub {
        public IndentingPrintWriter mPw;
        public UsbPortManager mPortManager;
        public UsbPortHidl mUsbPortHidl;

        HALCallback(IndentingPrintWriter pw, UsbPortManager portManager, UsbPortHidl usbPortHidl) {
            this.mPw = pw;
            this.mPortManager = portManager;
            this.mUsbPortHidl = usbPortHidl;
        }

        public void notifyPortStatusChange(
                ArrayList<android.hardware.usb.V1_0.PortStatus> currentPortStatus, int retval) {
            if (!mUsbPortHidl.mSystemReady) {
                return;
            }

            if (retval != Status.SUCCESS) {
                UsbPortManager.logAndPrint(Log.ERROR, mPw, "port status enquiry failed");
                return;
            }

            ArrayList<RawPortInfo> newPortInfo = new ArrayList<>();

            for (android.hardware.usb.V1_0.PortStatus current : currentPortStatus) {
                RawPortInfo temp = new RawPortInfo(current.portName,
                        current.supportedModes, CONTAMINANT_PROTECTION_NONE,
                        current.currentMode,
                        current.canChangeMode, current.currentPowerRole,
                        current.canChangePowerRole,
                        current.currentDataRole, current.canChangeDataRole,
                        false, CONTAMINANT_PROTECTION_NONE,
                        false, CONTAMINANT_DETECTION_NOT_SUPPORTED, sUsbDataStatus,
                        false, POWER_BRICK_STATUS_UNKNOWN,
                        false, new int[] {},
                        PLUG_STATE_UNKNOWN,
                        0,
                        null);
                newPortInfo.add(temp);
                UsbPortManager.logAndPrint(Log.INFO, mPw, "ClientCallback V1_0: "
                        + current.portName);
            }

            mPortManager.updatePorts(newPortInfo);
        }


        public void notifyPortStatusChange_1_1(ArrayList<PortStatus_1_1> currentPortStatus,
                int retval) {
            if (!mUsbPortHidl.mSystemReady) {
                return;
            }

            if (retval != Status.SUCCESS) {
                UsbPortManager.logAndPrint(Log.ERROR, mPw, "port status enquiry failed");
                return;
            }

            ArrayList<RawPortInfo> newPortInfo = new ArrayList<>();

            int numStatus = currentPortStatus.size();
            for (int i = 0; i < numStatus; i++) {
                PortStatus_1_1 current = currentPortStatus.get(i);
                RawPortInfo temp = new RawPortInfo(current.status.portName,
                        current.supportedModes, CONTAMINANT_PROTECTION_NONE,
                        current.currentMode,
                        current.status.canChangeMode, current.status.currentPowerRole,
                        current.status.canChangePowerRole,
                        current.status.currentDataRole, current.status.canChangeDataRole,
                        false, CONTAMINANT_PROTECTION_NONE,
                        false, CONTAMINANT_DETECTION_NOT_SUPPORTED, sUsbDataStatus,
                        false, POWER_BRICK_STATUS_UNKNOWN,
                        false, new int[] {},
                        PLUG_STATE_UNKNOWN,
                        0,
                        null);
                newPortInfo.add(temp);
                UsbPortManager.logAndPrint(Log.INFO, mPw, "ClientCallback V1_1: "
                        + current.status.portName);
            }
            mPortManager.updatePorts(newPortInfo);
        }

        public void notifyPortStatusChange_1_2(
                ArrayList<PortStatus> currentPortStatus, int retval) {
            if (!mUsbPortHidl.mSystemReady) {
                return;
            }

            if (retval != Status.SUCCESS) {
                UsbPortManager.logAndPrint(Log.ERROR, mPw, "port status enquiry failed");
                return;
            }

            ArrayList<RawPortInfo> newPortInfo = new ArrayList<>();

            int numStatus = currentPortStatus.size();
            for (int i = 0; i < numStatus; i++) {
                PortStatus current = currentPortStatus.get(i);
                RawPortInfo temp = new RawPortInfo(current.status_1_1.status.portName,
                        current.status_1_1.supportedModes,
                        current.supportedContaminantProtectionModes,
                        current.status_1_1.currentMode,
                        current.status_1_1.status.canChangeMode,
                        current.status_1_1.status.currentPowerRole,
                        current.status_1_1.status.canChangePowerRole,
                        current.status_1_1.status.currentDataRole,
                        current.status_1_1.status.canChangeDataRole,
                        current.supportsEnableContaminantPresenceProtection,
                        current.contaminantProtectionStatus,
                        current.supportsEnableContaminantPresenceDetection,
                        current.contaminantDetectionStatus,
                        sUsbDataStatus,
                        false, POWER_BRICK_STATUS_UNKNOWN,
                        false, new int[] {},
                        PLUG_STATE_UNKNOWN,
                        0,
                        null);
                newPortInfo.add(temp);
                UsbPortManager.logAndPrint(Log.INFO, mPw, "ClientCallback V1_2: "
                        + current.status_1_1.status.portName);
            }
            mPortManager.updatePorts(newPortInfo);
        }

        public void notifyRoleSwitchStatus(String portName, PortRole role, int retval) {
            if (retval == Status.SUCCESS) {
                UsbPortManager.logAndPrint(Log.INFO, mPw, portName + " role switch successful");
            } else {
                UsbPortManager.logAndPrint(Log.ERROR, mPw, portName + " role switch failed");
            }
        }
    }
}
