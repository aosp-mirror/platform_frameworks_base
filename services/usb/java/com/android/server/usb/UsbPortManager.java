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

import com.android.internal.util.IndentingPrintWriter;
import com.android.server.FgThread;

import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbPort;
import android.hardware.usb.UsbPortStatus;
import android.os.Handler;
import android.os.Message;
import android.os.UEventObserver;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import libcore.io.IoUtils;

/**
 * Allows trusted components to control the properties of physical USB ports
 * via the "/sys/class/dual_role_usb" kernel interface.
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

    // UEvent path to watch.
    private static final String UEVENT_FILTER = "SUBSYSTEM=dual_role_usb";

    // SysFS directory that contains USB ports as subdirectories.
    private static final String SYSFS_CLASS = "/sys/class/dual_role_usb";

    // SysFS file that contains a USB port's supported modes.  (read-only)
    // Contents: "", "ufp", "dfp", or "ufp dfp".
    private static final String SYSFS_PORT_SUPPORTED_MODES = "supported_modes";

    // SysFS file that contains a USB port's current mode.  (read-write if configurable)
    // Contents: "", "ufp", or "dfp".
    private static final String SYSFS_PORT_MODE = "mode";

    // SysFS file that contains a USB port's current power role.  (read-write if configurable)
    // Contents: "", "source", or "sink".
    private static final String SYSFS_PORT_POWER_ROLE = "power_role";

    // SysFS file that contains a USB port's current data role.  (read-write if configurable)
    // Contents: "", "host", or "device".
    private static final String SYSFS_PORT_DATA_ROLE = "data_role";

    // Port modes: upstream facing port or downstream facing port.
    private static final String PORT_MODE_DFP = "dfp";
    private static final String PORT_MODE_UFP = "ufp";

    // Port power roles: source or sink.
    private static final String PORT_POWER_ROLE_SOURCE = "source";
    private static final String PORT_POWER_ROLE_SINK = "sink";

    // Port data roles: host or device.
    private static final String PORT_DATA_ROLE_HOST = "host";
    private static final String PORT_DATA_ROLE_DEVICE = "device";

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

    // True if we have kernel support.
    private final boolean mHaveKernelSupport;

    // Mutex for all mutable shared state.
    private final Object mLock = new Object();

    // List of all ports, indexed by id.
    // Ports may temporarily have different dispositions as they are added or removed
    // but the class invariant is that this list will only contain ports with DISPOSITION_READY
    // except while updatePortsLocked() is in progress.
    private final ArrayMap<String, PortInfo> mPorts = new ArrayMap<String, PortInfo>();

    // List of all simulated ports, indexed by id.
    private final ArrayMap<String, SimulatedPortInfo> mSimulatedPorts =
            new ArrayMap<String, SimulatedPortInfo>();

    public UsbPortManager(Context context) {
        mContext = context;
        mHaveKernelSupport = new File(SYSFS_CLASS).exists();
    }

    public void systemReady() {
        mUEventObserver.startObserving(UEVENT_FILTER);
        scheduleUpdatePorts();
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

            SimulatedPortInfo sim = mSimulatedPorts.get(portId);
            if (sim != null) {
                // Change simulated state.
                sim.mCurrentMode = newMode;
                sim.mCurrentPowerRole = newPowerRole;
                sim.mCurrentDataRole = newDataRole;
            } else if (mHaveKernelSupport) {
                // Change actual state.
                final File portDir = new File(SYSFS_CLASS, portId);
                if (!portDir.exists()) {
                    logAndPrint(Log.ERROR, pw, "USB port not found: portId=" + portId);
                    return;
                }

                if (currentMode != newMode) {
                    // Changing the mode will have the side-effect of also changing
                    // the power and data roles but it might take some time to apply
                    // and the renegotiation might fail.  Due to limitations of the USB
                    // hardware, we have no way of knowing whether it will work apriori
                    // which is why we would prefer to set the power and data roles
                    // directly instead.
                    if (!writeFile(portDir, SYSFS_PORT_MODE,
                            newMode == UsbPort.MODE_DFP ? PORT_MODE_DFP : PORT_MODE_UFP)) {
                        logAndPrint(Log.ERROR, pw, "Failed to set the USB port mode: "
                                + "portId=" + portId
                                + ", newMode=" + UsbPort.modeToString(newMode));
                        return;
                    }
                } else {
                    // Change power and data role independently as needed.
                    if (currentPowerRole != newPowerRole) {
                        if (!writeFile(portDir, SYSFS_PORT_POWER_ROLE,
                                newPowerRole == UsbPort.POWER_ROLE_SOURCE
                                ? PORT_POWER_ROLE_SOURCE : PORT_POWER_ROLE_SINK)) {
                            logAndPrint(Log.ERROR, pw, "Failed to set the USB port power role: "
                                    + "portId=" + portId
                                    + ", newPowerRole=" + UsbPort.powerRoleToString(newPowerRole));
                            return;
                        }
                    }
                    if (currentDataRole != newDataRole) {
                        if (!writeFile(portDir, SYSFS_PORT_DATA_ROLE,
                                newDataRole == UsbPort.DATA_ROLE_HOST
                                ? PORT_DATA_ROLE_HOST : PORT_DATA_ROLE_DEVICE)) {
                            logAndPrint(Log.ERROR, pw, "Failed to set the USB port data role: "
                                    + "portId=" + portId
                                    + ", newDataRole=" + UsbPort.dataRoleToString(newDataRole));
                            return;
                        }
                    }
                }
            }
            updatePortsLocked(pw);
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
                    new SimulatedPortInfo(portId, supportedModes));
            updatePortsLocked(pw);
        }
    }

    public void connectSimulatedPort(String portId, int mode, boolean canChangeMode,
            int powerRole, boolean canChangePowerRole,
            int dataRole, boolean canChangeDataRole, IndentingPrintWriter pw) {
        synchronized (mLock) {
            final SimulatedPortInfo portInfo = mSimulatedPorts.get(portId);
            if (portInfo == null) {
                pw.println("Cannot connect simulated port which does not exist.");
                return;
            }

            if (mode == 0 || powerRole == 0 || dataRole == 0) {
                pw.println("Cannot connect simulated port in null mode, "
                        + "power role, or data role.");
                return;
            }

            if ((portInfo.mSupportedModes & mode) == 0) {
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
            portInfo.mCurrentMode = mode;
            portInfo.mCanChangeMode = canChangeMode;
            portInfo.mCurrentPowerRole = powerRole;
            portInfo.mCanChangePowerRole = canChangePowerRole;
            portInfo.mCurrentDataRole = dataRole;
            portInfo.mCanChangeDataRole = canChangeDataRole;
            updatePortsLocked(pw);
        }
    }

    public void disconnectSimulatedPort(String portId, IndentingPrintWriter pw) {
        synchronized (mLock) {
            final SimulatedPortInfo portInfo = mSimulatedPorts.get(portId);
            if (portInfo == null) {
                pw.println("Cannot disconnect simulated port which does not exist.");
                return;
            }

            pw.println("Disconnecting simulated port: portId=" + portId);
            portInfo.mCurrentMode = 0;
            portInfo.mCanChangeMode = false;
            portInfo.mCurrentPowerRole = 0;
            portInfo.mCanChangePowerRole = false;
            portInfo.mCurrentDataRole = 0;
            portInfo.mCanChangeDataRole = false;
            updatePortsLocked(pw);
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
            updatePortsLocked(pw);
        }
    }

    public void resetSimulation(IndentingPrintWriter pw) {
        synchronized (mLock) {
            pw.println("Removing all simulated ports and ending simulation.");
            if (!mSimulatedPorts.isEmpty()) {
                mSimulatedPorts.clear();
                updatePortsLocked(pw);
            }
        }
    }

    public void dump(IndentingPrintWriter pw) {
        synchronized (mLock) {
            pw.print("USB Port State:");
            if (!mSimulatedPorts.isEmpty()) {
                pw.print(" (simulation active; end with 'dumpsys usb reset')");
            }
            pw.println();

            if (mPorts.isEmpty()) {
                pw.println("  <no ports>");
            } else {
                for (PortInfo portInfo : mPorts.values()) {
                    pw.println("  " + portInfo.mUsbPort.getId() + ": " + portInfo);
                }
            }
        }
    }

    private void updatePortsLocked(IndentingPrintWriter pw) {
        // Assume all ports are gone unless informed otherwise.
        // Kind of pessimistic but simple.
        for (int i = mPorts.size(); i-- > 0; ) {
            mPorts.valueAt(i).mDisposition = PortInfo.DISPOSITION_REMOVED;
        }

        // Enumerate all extant ports.
        if (!mSimulatedPorts.isEmpty()) {
            final int count = mSimulatedPorts.size();
            for (int i = 0; i < count; i++) {
                final SimulatedPortInfo portInfo = mSimulatedPorts.valueAt(i);
                addOrUpdatePortLocked(portInfo.mPortId, portInfo.mSupportedModes,
                        portInfo.mCurrentMode, portInfo.mCanChangeMode,
                        portInfo.mCurrentPowerRole, portInfo.mCanChangePowerRole,
                        portInfo.mCurrentDataRole, portInfo.mCanChangeDataRole, pw);
            }
        } else if (mHaveKernelSupport) {
            final File[] portDirs = new File(SYSFS_CLASS).listFiles();
            if (portDirs != null) {
                for (File portDir : portDirs) {
                    if (!portDir.isDirectory()) {
                        continue;
                    }

                    // Parse the sysfs file contents.
                    final String portId = portDir.getName();
                    final int supportedModes = readSupportedModes(portDir);
                    final int currentMode = readCurrentMode(portDir);
                    final boolean canChangeMode = canChangeMode(portDir);
                    final int currentPowerRole = readCurrentPowerRole(portDir);
                    final boolean canChangePowerRole = canChangePowerRole(portDir);
                    final int currentDataRole = readCurrentDataRole(portDir);
                    final boolean canChangeDataRole = canChangeDataRole(portDir);
                    addOrUpdatePortLocked(portId, supportedModes,
                            currentMode, canChangeMode,
                            currentPowerRole, canChangePowerRole,
                            currentDataRole, canChangeDataRole, pw);
                 }
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
        if (supportedModes != UsbPort.MODE_DUAL) {
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
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        intent.putExtra(UsbManager.EXTRA_PORT, portInfo.mUsbPort);
        intent.putExtra(UsbManager.EXTRA_PORT_STATUS, portInfo.mUsbPortStatus);

        // Guard against possible reentrance by posting the broadcast from the handler
        // instead of from within the critical section.
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
            }
        });
    }

    private void scheduleUpdatePorts() {
        if (!mHandler.hasMessages(MSG_UPDATE_PORTS)) {
            mHandler.sendEmptyMessage(MSG_UPDATE_PORTS);
        }
    }

    private static int readSupportedModes(File portDir) {
        int modes = 0;
        final String contents = readFile(portDir, SYSFS_PORT_SUPPORTED_MODES);
        if (contents != null) {
            if (contents.contains(PORT_MODE_DFP)) {
                modes |= UsbPort.MODE_DFP;
            }
            if (contents.contains(PORT_MODE_UFP)) {
                modes |= UsbPort.MODE_UFP;
            }
        }
        return modes;
    }

    private static int readCurrentMode(File portDir) {
        final String contents = readFile(portDir, SYSFS_PORT_MODE);
        if (contents != null) {
            if (contents.equals(PORT_MODE_DFP)) {
                return UsbPort.MODE_DFP;
            }
            if (contents.equals(PORT_MODE_UFP)) {
                return UsbPort.MODE_UFP;
            }
        }
        return 0;
    }

    private static int readCurrentPowerRole(File portDir) {
        final String contents = readFile(portDir, SYSFS_PORT_POWER_ROLE);
        if (contents != null) {
            if (contents.equals(PORT_POWER_ROLE_SOURCE)) {
                return UsbPort.POWER_ROLE_SOURCE;
            }
            if (contents.equals(PORT_POWER_ROLE_SINK)) {
                return UsbPort.POWER_ROLE_SINK;
            }
        }
        return 0;
    }

    private static int readCurrentDataRole(File portDir) {
        final String contents = readFile(portDir, SYSFS_PORT_DATA_ROLE);
        if (contents != null) {
            if (contents.equals(PORT_DATA_ROLE_HOST)) {
                return UsbPort.DATA_ROLE_HOST;
            }
            if (contents.equals(PORT_DATA_ROLE_DEVICE)) {
                return UsbPort.DATA_ROLE_DEVICE;
            }
        }
        return 0;
    }

    private static boolean canChangeMode(File portDir) {
        return new File(portDir, SYSFS_PORT_MODE).canWrite();
    }

    private static boolean canChangePowerRole(File portDir) {
        return new File(portDir, SYSFS_PORT_POWER_ROLE).canWrite();
    }

    private static boolean canChangeDataRole(File portDir) {
        return new File(portDir, SYSFS_PORT_DATA_ROLE).canWrite();
    }

    private static String readFile(File dir, String filename) {
        final File file = new File(dir, filename);
        try {
            return IoUtils.readFileAsString(file.getAbsolutePath()).trim();
        } catch (IOException ex) {
            return null;
        }
    }

    private static boolean writeFile(File dir, String filename, String contents) {
        final File file = new File(dir, filename);
        try {
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(contents);
            }
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    private static void logAndPrint(int priority, IndentingPrintWriter pw, String msg) {
        Slog.println(priority, TAG, msg);
        if (pw != null) {
            pw.println(msg);
        }
    }

    private final Handler mHandler = new Handler(FgThread.get().getLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_PORTS: {
                    synchronized (mLock) {
                        updatePortsLocked(null);
                    }
                    break;
                }
            }
        }
    };

    private final UEventObserver mUEventObserver = new UEventObserver() {
        @Override
        public void onUEvent(UEvent event) {
            scheduleUpdatePorts();
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

        @Override
        public String toString() {
            return "port=" + mUsbPort + ", status=" + mUsbPortStatus
                    + ", canChangeMode=" + mCanChangeMode
                    + ", canChangePowerRole=" + mCanChangePowerRole
                    + ", canChangeDataRole=" + mCanChangeDataRole;
        }
    }

    /**
     * Describes a simulated USB port.
     * Roughly mirrors the information we would ordinarily get from the kernel.
     */
    private static final class SimulatedPortInfo {
        public final String mPortId;
        public final int mSupportedModes;
        public int mCurrentMode;
        public boolean mCanChangeMode;
        public int mCurrentPowerRole;
        public boolean mCanChangePowerRole;
        public int mCurrentDataRole;
        public boolean mCanChangeDataRole;

        public SimulatedPortInfo(String portId, int supportedModes) {
            mPortId = portId;
            mSupportedModes = supportedModes;
        }
    }
}
