/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.usb;

import static com.android.internal.usb.DumpUtils.writePort;
import static com.android.internal.usb.DumpUtils.writePortStatus;

import android.annotation.NonNull;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbPort;
import android.hardware.usb.UsbPortStatus;
import android.hardware.usb.V1_0.IUsb;
import android.hardware.usb.V1_0.PortRole;
import android.hardware.usb.V1_0.PortRoleType;
import android.hardware.usb.V1_0.PortStatus;
import android.hardware.usb.V1_0.Status;
import android.hardware.usb.V1_1.IUsbCallback;
import android.hardware.usb.V1_1.PortStatus_1_1;
import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
import android.os.Bundle;
import android.os.Handler;
import android.os.HwBinder;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.usb.UsbPortInfoProto;
import android.service.usb.UsbPortManagerProto;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.server.FgThread;

import java.util.ArrayList;
import java.util.NoSuchElementException;

/**
 * Allows trusted components to control the properties of physical USB ports
 * via the IUsb.hal.
 * <p>
 * Note: This interface may not be supported on all chipsets since the USB drivers
 * must be changed to publish this information through the module.  At the moment
 * we only need this for devices with USB Type C ports to allow the System UI to
 * control USB charging and data direction.  On devices that do not support this
 * interface the list of ports may incorrectly appear to be empty
 * (but we don't care today).
 * </p>
 */
public class UsbPortManager {
    private static final String TAG = "UsbPortManager";

    private static final int MSG_UPDATE_PORTS = 1;

    // All non-trivial role combinations.
    private static final int COMBO_SOURCE_HOST =
            UsbPort.combineRolesAsBit(UsbPort.POWER_ROLE_SOURCE, UsbPort.DATA_ROLE_HOST);
    private static final int COMBO_SOURCE_DEVICE =
            UsbPort.combineRolesAsBit(UsbPort.POWER_ROLE_SOURCE, UsbPort.DATA_ROLE_DEVICE);
    private static final int COMBO_SINK_HOST =
            UsbPort.combineRolesAsBit(UsbPort.POWER_ROLE_SINK, UsbPort.DATA_ROLE_HOST);
    private static final int COMBO_SINK_DEVICE =
            UsbPort.combineRolesAsBit(UsbPort.POWER_ROLE_SINK, UsbPort.DATA_ROLE_DEVICE);

    // The system context.
    private final Context mContext;

    // Proxy object for the usb hal daemon.
    @GuardedBy("mLock")
    private IUsb mProxy = null;

    // Callback when the UsbPort status is changed by the kernel.
    // Mostly due a command sent by the remote Usb device.
    private HALCallback mHALCallback = new HALCallback(null, this);

    // Cookie sent for usb hal death notification.
    private static final int USB_HAL_DEATH_COOKIE = 1000;

    // Used as the key while sending the bundle to Main thread.
    private static final String PORT_INFO = "port_info";

    // This is monitored to prevent updating the protInfo before the system
    // is ready.
    private boolean mSystemReady;

    // Mutex for all mutable shared state.
    private final Object mLock = new Object();

    // List of all ports, indexed by id.
    // Ports may temporarily have different dispositions as they are added or removed
    // but the class invariant is that this list will only contain ports with DISPOSITION_READY
    // except while updatePortsLocked() is in progress.
    private final ArrayMap<String, PortInfo> mPorts = new ArrayMap<>();

    // List of all simulated ports, indexed by id.
    private final ArrayMap<String, RawPortInfo> mSimulatedPorts =
            new ArrayMap<>();

    public UsbPortManager(Context context) {
        mContext = context;
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
        connectToProxy(null);
    }

    public void systemReady() {
	mSystemReady = true;
        if (mProxy != null) {
            try {
                mProxy.queryPortStatus();
            } catch (RemoteException e) {
                logAndPrintException(null,
                        "ServiceStart: Failed to query port status", e);
            }
        }
    }

    public UsbPort[] getPorts() {
        synchronized (mLock) {
            final int count = mPorts.size();
            final UsbPort[] result = new UsbPort[count];
            for (int i = 0; i < count; i++) {
                result[i] = mPorts.valueAt(i).mUsbPort;
            }
            return result;
        }
    }

    public UsbPortStatus getPortStatus(String portId) {
        synchronized (mLock) {
            final PortInfo portInfo = mPorts.get(portId);
            return portInfo != null ? portInfo.mUsbPortStatus : null;
        }
    }

    public void setPortRoles(String portId, int newPowerRole, int newDataRole,
            IndentingPrintWriter pw) {
        synchronized (mLock) {
            final PortInfo portInfo = mPorts.get(portId);
            if (portInfo == null) {
                if (pw != null) {
                    pw.println("No such USB port: " + portId);
                }
                return;
            }

            // Check whether the new role is actually supported.
            if (!portInfo.mUsbPortStatus.isRoleCombinationSupported(newPowerRole, newDataRole)) {
                logAndPrint(Log.ERROR, pw, "Attempted to set USB port into unsupported "
                        + "role combination: portId=" + portId
                        + ", newPowerRole=" + UsbPort.powerRoleToString(newPowerRole)
                        + ", newDataRole=" + UsbPort.dataRoleToString(newDataRole));
                return;
            }

            // Check whether anything actually changed.
            final int currentDataRole = portInfo.mUsbPortStatus.getCurrentDataRole();
            final int currentPowerRole = portInfo.mUsbPortStatus.getCurrentPowerRole();
            if (currentDataRole == newDataRole && currentPowerRole == newPowerRole) {
                if (pw != null) {
                    pw.println("No change.");
                }
                return;
            }

            // Determine whether we need to change the mode in order to accomplish this goal.
            // We prefer not to do this since it's more likely to fail.
            //
            // Note: Arguably it might be worth allowing the client to influence this policy
            // decision so that we could show more powerful developer facing UI but let's
            // see how far we can get without having to do that.
            final boolean canChangeMode = portInfo.mCanChangeMode;
            final boolean canChangePowerRole = portInfo.mCanChangePowerRole;
            final boolean canChangeDataRole = portInfo.mCanChangeDataRole;
            final int currentMode = portInfo.mUsbPortStatus.getCurrentMode();
            final int newMode;
            if ((!canChangePowerRole && currentPowerRole != newPowerRole)
                    || (!canChangeDataRole && currentDataRole != newDataRole)) {
                if (canChangeMode && newPowerRole == UsbPort.POWER_ROLE_SOURCE
                        && newDataRole == UsbPort.DATA_ROLE_HOST) {
                    newMode = UsbPort.MODE_DFP;
                } else if (canChangeMode && newPowerRole == UsbPort.POWER_ROLE_SINK
                        && newDataRole == UsbPort.DATA_ROLE_DEVICE) {
                    newMode = UsbPort.MODE_UFP;
                } else {
                    logAndPrint(Log.ERROR, pw, "Found mismatch in supported USB role combinations "
                            + "while attempting to change role: " + portInfo
                            + ", newPowerRole=" + UsbPort.powerRoleToString(newPowerRole)
                            + ", newDataRole=" + UsbPort.dataRoleToString(newDataRole));
                    return;
                }
            } else {
                newMode = currentMode;
            }

            // Make it happen.
            logAndPrint(Log.INFO, pw, "Setting USB port mode and role: portId=" + portId
                    + ", currentMode=" + UsbPort.modeToString(currentMode)
                    + ", currentPowerRole=" + UsbPort.powerRoleToString(currentPowerRole)
                    + ", currentDataRole=" + UsbPort.dataRoleToString(currentDataRole)
                    + ", newMode=" + UsbPort.modeToString(newMode)
                    + ", newPowerRole=" + UsbPort.powerRoleToString(newPowerRole)
                    + ", newDataRole=" + UsbPort.dataRoleToString(newDataRole));

            RawPortInfo sim = mSimulatedPorts.get(portId);
            if (sim != null) {
                // Change simulated state.
                sim.currentMode = newMode;
                sim.currentPowerRole = newPowerRole;
                sim.currentDataRole = newDataRole;
                updatePortsLocked(pw, null);
            } else if (mProxy != null) {
                if (currentMode != newMode) {
                    // Changing the mode will have the side-effect of also changing
                    // the power and data roles but it might take some time to apply
                    // and the renegotiation might fail.  Due to limitations of the USB
                    // hardware, we have no way of knowing whether it will work apriori
                    // which is why we would prefer to set the power and data roles
                    // directly instead.

                    logAndPrint(Log.ERROR, pw, "Trying to set the USB port mode: "
                            + "portId=" + portId
                            + ", newMode=" + UsbPort.modeToString(newMode));
                    PortRole newRole = new PortRole();
                    newRole.type = PortRoleType.MODE;
                    newRole.role = newMode;
                    try {
                        mProxy.switchRole(portId, newRole);
                    } catch (RemoteException e) {
                        logAndPrintException(pw, "Failed to set the USB port mode: "
                                + "portId=" + portId
                                + ", newMode=" + UsbPort.modeToString(newRole.role), e);
                    }
                } else {
                    // Change power and data role independently as needed.
                    if (currentPowerRole != newPowerRole) {
                        PortRole newRole = new PortRole();
                        newRole.type = PortRoleType.POWER_ROLE;
                        newRole.role = newPowerRole;
                        try {
                            mProxy.switchRole(portId, newRole);
                        } catch (RemoteException e) {
                            logAndPrintException(pw, "Failed to set the USB port power role: "
                                            + "portId=" + portId
                                            + ", newPowerRole=" + UsbPort.powerRoleToString
                                            (newRole.role),
                                    e);
                            return;
                        }
                    }
                    if (currentDataRole != newDataRole) {
                        PortRole newRole = new PortRole();
                        newRole.type = PortRoleType.DATA_ROLE;
                        newRole.role = newDataRole;
                        try {
                            mProxy.switchRole(portId, newRole);
                        } catch (RemoteException e) {
                            logAndPrintException(pw, "Failed to set the USB port data role: "
                                            + "portId=" + portId
                                            + ", newDataRole=" + UsbPort.dataRoleToString(newRole
                                            .role),
                                    e);
                        }
                    }
                }
            }
        }
    }

    public void addSimulatedPort(String portId, int supportedModes, IndentingPrintWriter pw) {
        synchronized (mLock) {
            if (mSimulatedPorts.containsKey(portId)) {
                pw.println("Port with same name already exists.  Please remove it first.");
                return;
            }

            pw.println("Adding simulated port: portId=" + portId
                    + ", supportedModes=" + UsbPort.modeToString(supportedModes));
            mSimulatedPorts.put(portId,
                    new RawPortInfo(portId, supportedModes));
            updatePortsLocked(pw, null);
        }
    }

    public void connectSimulatedPort(String portId, int mode, boolean canChangeMode,
            int powerRole, boolean canChangePowerRole,
            int dataRole, boolean canChangeDataRole, IndentingPrintWriter pw) {
        synchronized (mLock) {
            final RawPortInfo portInfo = mSimulatedPorts.get(portId);
            if (portInfo == null) {
                pw.println("Cannot connect simulated port which does not exist.");
                return;
            }

            if (mode == 0 || powerRole == 0 || dataRole == 0) {
                pw.println("Cannot connect simulated port in null mode, "
                        + "power role, or data role.");
                return;
            }

            if ((portInfo.supportedModes & mode) == 0) {
                pw.println("Simulated port does not support mode: " + UsbPort.modeToString(mode));
                return;
            }

            pw.println("Connecting simulated port: portId=" + portId
                    + ", mode=" + UsbPort.modeToString(mode)
                    + ", canChangeMode=" + canChangeMode
                    + ", powerRole=" + UsbPort.powerRoleToString(powerRole)
                    + ", canChangePowerRole=" + canChangePowerRole
                    + ", dataRole=" + UsbPort.dataRoleToString(dataRole)
                    + ", canChangeDataRole=" + canChangeDataRole);
            portInfo.currentMode = mode;
            portInfo.canChangeMode = canChangeMode;
            portInfo.currentPowerRole = powerRole;
            portInfo.canChangePowerRole = canChangePowerRole;
            portInfo.currentDataRole = dataRole;
            portInfo.canChangeDataRole = canChangeDataRole;
            updatePortsLocked(pw, null);
        }
    }

    public void disconnectSimulatedPort(String portId, IndentingPrintWriter pw) {
        synchronized (mLock) {
            final RawPortInfo portInfo = mSimulatedPorts.get(portId);
            if (portInfo == null) {
                pw.println("Cannot disconnect simulated port which does not exist.");
                return;
            }

            pw.println("Disconnecting simulated port: portId=" + portId);
            portInfo.currentMode = 0;
            portInfo.canChangeMode = false;
            portInfo.currentPowerRole = 0;
            portInfo.canChangePowerRole = false;
            portInfo.currentDataRole = 0;
            portInfo.canChangeDataRole = false;
            updatePortsLocked(pw, null);
        }
    }

    public void removeSimulatedPort(String portId, IndentingPrintWriter pw) {
        synchronized (mLock) {
            final int index = mSimulatedPorts.indexOfKey(portId);
            if (index < 0) {
                pw.println("Cannot remove simulated port which does not exist.");
                return;
            }

            pw.println("Disconnecting simulated port: portId=" + portId);
            mSimulatedPorts.removeAt(index);
            updatePortsLocked(pw, null);
        }
    }

    public void resetSimulation(IndentingPrintWriter pw) {
        synchronized (mLock) {
            pw.println("Removing all simulated ports and ending simulation.");
            if (!mSimulatedPorts.isEmpty()) {
                mSimulatedPorts.clear();
                updatePortsLocked(pw, null);
            }
        }
    }

    /**
     * Dump the USB port state.
     */
    public void dump(DualDumpOutputStream dump, String idName, long id) {
        long token = dump.start(idName, id);

        synchronized (mLock) {
            dump.write("is_simulation_active", UsbPortManagerProto.IS_SIMULATION_ACTIVE,
                    !mSimulatedPorts.isEmpty());

            for (PortInfo portInfo : mPorts.values()) {
                portInfo.dump(dump, "usb_ports", UsbPortManagerProto.USB_PORTS);
            }
        }

        dump.end(token);
    }

    private static class HALCallback extends IUsbCallback.Stub {
        public IndentingPrintWriter pw;
        public UsbPortManager portManager;

        HALCallback(IndentingPrintWriter pw, UsbPortManager portManager) {
            this.pw = pw;
            this.portManager = portManager;
        }

        public void notifyPortStatusChange(ArrayList<PortStatus> currentPortStatus, int retval) {
            if (!portManager.mSystemReady) {
                return;
            }

            if (retval != Status.SUCCESS) {
                logAndPrint(Log.ERROR, pw, "port status enquiry failed");
                return;
            }

            ArrayList<RawPortInfo> newPortInfo = new ArrayList<>();

            for (PortStatus current : currentPortStatus) {
                RawPortInfo temp = new RawPortInfo(current.portName,
                        current.supportedModes, current.currentMode,
                        current.canChangeMode, current.currentPowerRole,
                        current.canChangePowerRole,
                        current.currentDataRole, current.canChangeDataRole);
                newPortInfo.add(temp);
                logAndPrint(Log.INFO, pw, "ClientCallback: " + current.portName);
            }

            Message message = portManager.mHandler.obtainMessage();
            Bundle bundle = new Bundle();
            bundle.putParcelableArrayList(PORT_INFO, newPortInfo);
            message.what = MSG_UPDATE_PORTS;
            message.setData(bundle);
            portManager.mHandler.sendMessage(message);
        }


        public void notifyPortStatusChange_1_1(ArrayList<PortStatus_1_1> currentPortStatus,
                int retval) {
            if (!portManager.mSystemReady) {
                return;
            }

            if (retval != Status.SUCCESS) {
                logAndPrint(Log.ERROR, pw, "port status enquiry failed");
                return;
            }

            ArrayList<RawPortInfo> newPortInfo = new ArrayList<>();

            for (PortStatus_1_1 current : currentPortStatus) {
                RawPortInfo temp = new RawPortInfo(current.status.portName,
                        current.supportedModes, current.currentMode,
                        current.status.canChangeMode, current.status.currentPowerRole,
                        current.status.canChangePowerRole,
                        current.status.currentDataRole, current.status.canChangeDataRole);
                newPortInfo.add(temp);
                logAndPrint(Log.INFO, pw, "ClientCallback: " + current.status.portName);
            }

            Message message = portManager.mHandler.obtainMessage();
            Bundle bundle = new Bundle();
            bundle.putParcelableArrayList(PORT_INFO, newPortInfo);
            message.what = MSG_UPDATE_PORTS;
            message.setData(bundle);
            portManager.mHandler.sendMessage(message);
        }

        public void notifyRoleSwitchStatus(String portName, PortRole role, int retval) {
            if (retval == Status.SUCCESS) {
                logAndPrint(Log.INFO, pw, portName + " role switch successful");
            } else {
                logAndPrint(Log.ERROR, pw, portName + " role switch failed");
            }
        }
    }

    final class DeathRecipient implements HwBinder.DeathRecipient {
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
            } catch (NoSuchElementException e) {
                logAndPrintException(pw, "connectToProxy: usb hal service not found."
                        + " Did the service fail to start?", e);
            } catch (RemoteException e) {
                logAndPrintException(pw, "connectToProxy: usb hal service not responding", e);
            }
        }
    }

    /**
     * Simulated ports directly add the new roles to mSimulatedPorts before calling.
     * USB hal callback populates and sends the newPortInfo.
     */
    private void updatePortsLocked(IndentingPrintWriter pw, ArrayList<RawPortInfo> newPortInfo) {
        for (int i = mPorts.size(); i-- > 0; ) {
            mPorts.valueAt(i).mDisposition = PortInfo.DISPOSITION_REMOVED;
        }

        // Enumerate all extant ports.
        if (!mSimulatedPorts.isEmpty()) {
            final int count = mSimulatedPorts.size();
            for (int i = 0; i < count; i++) {
                final RawPortInfo portInfo = mSimulatedPorts.valueAt(i);
                addOrUpdatePortLocked(portInfo.portId, portInfo.supportedModes,
                        portInfo.currentMode, portInfo.canChangeMode,
                        portInfo.currentPowerRole, portInfo.canChangePowerRole,
                        portInfo.currentDataRole, portInfo.canChangeDataRole, pw);
            }
        } else {
            for (RawPortInfo currentPortInfo : newPortInfo) {
                addOrUpdatePortLocked(currentPortInfo.portId, currentPortInfo.supportedModes,
                        currentPortInfo.currentMode, currentPortInfo.canChangeMode,
                        currentPortInfo.currentPowerRole, currentPortInfo.canChangePowerRole,
                        currentPortInfo.currentDataRole, currentPortInfo.canChangeDataRole, pw);
            }
        }

        // Process the updates.
        // Once finished, the list of ports will only contain ports in DISPOSITION_READY.
        for (int i = mPorts.size(); i-- > 0; ) {
            final PortInfo portInfo = mPorts.valueAt(i);
            switch (portInfo.mDisposition) {
                case PortInfo.DISPOSITION_ADDED:
                    handlePortAddedLocked(portInfo, pw);
                    portInfo.mDisposition = PortInfo.DISPOSITION_READY;
                    break;
                case PortInfo.DISPOSITION_CHANGED:
                    handlePortChangedLocked(portInfo, pw);
                    portInfo.mDisposition = PortInfo.DISPOSITION_READY;
                    break;
                case PortInfo.DISPOSITION_REMOVED:
                    mPorts.removeAt(i);
                    portInfo.mUsbPortStatus = null; // must do this early
                    handlePortRemovedLocked(portInfo, pw);
                    break;
            }
        }
    }


    // Must only be called by updatePortsLocked.
    private void addOrUpdatePortLocked(String portId, int supportedModes,
            int currentMode, boolean canChangeMode,
            int currentPowerRole, boolean canChangePowerRole,
            int currentDataRole, boolean canChangeDataRole,
            IndentingPrintWriter pw) {
        // Only allow mode switch capability for dual role ports.
        // Validate that the current mode matches the supported modes we expect.
        if ((supportedModes & UsbPort.MODE_DUAL) != UsbPort.MODE_DUAL) {
            canChangeMode = false;
            if (currentMode != 0 && currentMode != supportedModes) {
                logAndPrint(Log.WARN, pw, "Ignoring inconsistent current mode from USB "
                        + "port driver: supportedModes=" + UsbPort.modeToString(supportedModes)
                        + ", currentMode=" + UsbPort.modeToString(currentMode));
                currentMode = 0;
            }
        }

        // Determine the supported role combinations.
        // Note that the policy is designed to prefer setting the power and data
        // role independently rather than changing the mode.
        int supportedRoleCombinations = UsbPort.combineRolesAsBit(
                currentPowerRole, currentDataRole);
        if (currentMode != 0 && currentPowerRole != 0 && currentDataRole != 0) {
            if (canChangePowerRole && canChangeDataRole) {
                // Can change both power and data role independently.
                // Assume all combinations are possible.
                supportedRoleCombinations |=
                        COMBO_SOURCE_HOST | COMBO_SOURCE_DEVICE
                                | COMBO_SINK_HOST | COMBO_SINK_DEVICE;
            } else if (canChangePowerRole) {
                // Can only change power role.
                // Assume data role must remain at its current value.
                supportedRoleCombinations |= UsbPort.combineRolesAsBit(
                        UsbPort.POWER_ROLE_SOURCE, currentDataRole);
                supportedRoleCombinations |= UsbPort.combineRolesAsBit(
                        UsbPort.POWER_ROLE_SINK, currentDataRole);
            } else if (canChangeDataRole) {
                // Can only change data role.
                // Assume power role must remain at its current value.
                supportedRoleCombinations |= UsbPort.combineRolesAsBit(
                        currentPowerRole, UsbPort.DATA_ROLE_HOST);
                supportedRoleCombinations |= UsbPort.combineRolesAsBit(
                        currentPowerRole, UsbPort.DATA_ROLE_DEVICE);
            } else if (canChangeMode) {
                // Can only change the mode.
                // Assume both standard UFP and DFP configurations will become available
                // when this happens.
                supportedRoleCombinations |= COMBO_SOURCE_HOST | COMBO_SINK_DEVICE;
            }
        }

        // Update the port data structures.
        PortInfo portInfo = mPorts.get(portId);
        if (portInfo == null) {
            portInfo = new PortInfo(portId, supportedModes);
            portInfo.setStatus(currentMode, canChangeMode,
                    currentPowerRole, canChangePowerRole,
                    currentDataRole, canChangeDataRole,
                    supportedRoleCombinations);
            mPorts.put(portId, portInfo);
        } else {
            // Sanity check that ports aren't changing definition out from under us.
            if (supportedModes != portInfo.mUsbPort.getSupportedModes()) {
                logAndPrint(Log.WARN, pw, "Ignoring inconsistent list of supported modes from "
                        + "USB port driver (should be immutable): "
                        + "previous=" + UsbPort.modeToString(
                        portInfo.mUsbPort.getSupportedModes())
                        + ", current=" + UsbPort.modeToString(supportedModes));
            }

            if (portInfo.setStatus(currentMode, canChangeMode,
                    currentPowerRole, canChangePowerRole,
                    currentDataRole, canChangeDataRole,
                    supportedRoleCombinations)) {
                portInfo.mDisposition = PortInfo.DISPOSITION_CHANGED;
            } else {
                portInfo.mDisposition = PortInfo.DISPOSITION_READY;
            }
        }
    }

    private void handlePortAddedLocked(PortInfo portInfo, IndentingPrintWriter pw) {
        logAndPrint(Log.INFO, pw, "USB port added: " + portInfo);
        sendPortChangedBroadcastLocked(portInfo);
    }

    private void handlePortChangedLocked(PortInfo portInfo, IndentingPrintWriter pw) {
        logAndPrint(Log.INFO, pw, "USB port changed: " + portInfo);
        sendPortChangedBroadcastLocked(portInfo);
    }

    private void handlePortRemovedLocked(PortInfo portInfo, IndentingPrintWriter pw) {
        logAndPrint(Log.INFO, pw, "USB port removed: " + portInfo);
        sendPortChangedBroadcastLocked(portInfo);
    }

    private void sendPortChangedBroadcastLocked(PortInfo portInfo) {
        final Intent intent = new Intent(UsbManager.ACTION_USB_PORT_CHANGED);
        intent.addFlags(
                Intent.FLAG_RECEIVER_FOREGROUND |
                        Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        intent.putExtra(UsbManager.EXTRA_PORT, portInfo.mUsbPort);
        intent.putExtra(UsbManager.EXTRA_PORT_STATUS, portInfo.mUsbPortStatus);

        // Guard against possible reentrance by posting the broadcast from the handler
        // instead of from within the critical section.
        mHandler.post(() -> mContext.sendBroadcastAsUser(intent, UserHandle.ALL));
    }

    private static void logAndPrint(int priority, IndentingPrintWriter pw, String msg) {
        Slog.println(priority, TAG, msg);
        if (pw != null) {
            pw.println(msg);
        }
    }

    private static void logAndPrintException(IndentingPrintWriter pw, String msg, Exception e) {
        Slog.e(TAG, msg, e);
        if (pw != null) {
            pw.println(msg + e);
        }
    }

    private final Handler mHandler = new Handler(FgThread.get().getLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_PORTS: {
                    Bundle b = msg.getData();
                    ArrayList<RawPortInfo> PortInfo = b.getParcelableArrayList(PORT_INFO);
                    synchronized (mLock) {
                        updatePortsLocked(null, PortInfo);
                    }
                    break;
                }
            }
        }
    };

    /**
     * Describes a USB port.
     */
    private static final class PortInfo {
        public static final int DISPOSITION_ADDED = 0;
        public static final int DISPOSITION_CHANGED = 1;
        public static final int DISPOSITION_READY = 2;
        public static final int DISPOSITION_REMOVED = 3;

        public final UsbPort mUsbPort;
        public UsbPortStatus mUsbPortStatus;
        public boolean mCanChangeMode;
        public boolean mCanChangePowerRole;
        public boolean mCanChangeDataRole;
        public int mDisposition; // default initialized to 0 which means added

        public PortInfo(String portId, int supportedModes) {
            mUsbPort = new UsbPort(portId, supportedModes);
        }

        public boolean setStatus(int currentMode, boolean canChangeMode,
                int currentPowerRole, boolean canChangePowerRole,
                int currentDataRole, boolean canChangeDataRole,
                int supportedRoleCombinations) {
            mCanChangeMode = canChangeMode;
            mCanChangePowerRole = canChangePowerRole;
            mCanChangeDataRole = canChangeDataRole;
            if (mUsbPortStatus == null
                    || mUsbPortStatus.getCurrentMode() != currentMode
                    || mUsbPortStatus.getCurrentPowerRole() != currentPowerRole
                    || mUsbPortStatus.getCurrentDataRole() != currentDataRole
                    || mUsbPortStatus.getSupportedRoleCombinations()
                    != supportedRoleCombinations) {
                mUsbPortStatus = new UsbPortStatus(currentMode, currentPowerRole, currentDataRole,
                        supportedRoleCombinations);
                return true;
            }
            return false;
        }

        void dump(@NonNull DualDumpOutputStream dump, @NonNull String idName, long id) {
            long token = dump.start(idName, id);

            writePort(dump, "port", UsbPortInfoProto.PORT, mUsbPort);
            writePortStatus(dump, "status", UsbPortInfoProto.STATUS, mUsbPortStatus);
            dump.write("can_change_mode", UsbPortInfoProto.CAN_CHANGE_MODE, mCanChangeMode);
            dump.write("can_change_power_role", UsbPortInfoProto.CAN_CHANGE_POWER_ROLE,
                    mCanChangePowerRole);
            dump.write("can_change_data_role", UsbPortInfoProto.CAN_CHANGE_DATA_ROLE,
                    mCanChangeDataRole);

            dump.end(token);
        }

        @Override
        public String toString() {
            return "port=" + mUsbPort + ", status=" + mUsbPortStatus
                    + ", canChangeMode=" + mCanChangeMode
                    + ", canChangePowerRole=" + mCanChangePowerRole
                    + ", canChangeDataRole=" + mCanChangeDataRole;
        }
    }

    /**
     * Used for storing the raw data from the kernel
     * Values of the member variables mocked directly incase of emulation.
     */
    private static final class RawPortInfo implements Parcelable {
        public final String portId;
        public final int supportedModes;
        public int currentMode;
        public boolean canChangeMode;
        public int currentPowerRole;
        public boolean canChangePowerRole;
        public int currentDataRole;
        public boolean canChangeDataRole;

        RawPortInfo(String portId, int supportedModes) {
            this.portId = portId;
            this.supportedModes = supportedModes;
        }

        RawPortInfo(String portId, int supportedModes,
                int currentMode, boolean canChangeMode,
                int currentPowerRole, boolean canChangePowerRole,
                int currentDataRole, boolean canChangeDataRole) {
            this.portId = portId;
            this.supportedModes = supportedModes;
            this.currentMode = currentMode;
            this.canChangeMode = canChangeMode;
            this.currentPowerRole = currentPowerRole;
            this.canChangePowerRole = canChangePowerRole;
            this.currentDataRole = currentDataRole;
            this.canChangeDataRole = canChangeDataRole;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(portId);
            dest.writeInt(supportedModes);
            dest.writeInt(currentMode);
            dest.writeByte((byte) (canChangeMode ? 1 : 0));
            dest.writeInt(currentPowerRole);
            dest.writeByte((byte) (canChangePowerRole ? 1 : 0));
            dest.writeInt(currentDataRole);
            dest.writeByte((byte) (canChangeDataRole ? 1 : 0));
        }

        public static final Parcelable.Creator<RawPortInfo> CREATOR =
                new Parcelable.Creator<RawPortInfo>() {
                    @Override
                    public RawPortInfo createFromParcel(Parcel in) {
                        String id = in.readString();
                        int supportedModes = in.readInt();
                        int currentMode = in.readInt();
                        boolean canChangeMode = in.readByte() != 0;
                        int currentPowerRole = in.readInt();
                        boolean canChangePowerRole = in.readByte() != 0;
                        int currentDataRole = in.readInt();
                        boolean canChangeDataRole = in.readByte() != 0;
                        return new RawPortInfo(id, supportedModes, currentMode, canChangeMode,
                                currentPowerRole, canChangePowerRole,
                                currentDataRole, canChangeDataRole);
                    }

                    @Override
                    public RawPortInfo[] newArray(int size) {
                        return new RawPortInfo[size];
                    }
                };
    }
}
