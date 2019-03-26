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

import static android.hardware.usb.UsbPortStatus.CONTAMINANT_DETECTION_NOT_SUPPORTED;
import static android.hardware.usb.UsbPortStatus.CONTAMINANT_PROTECTION_NONE;
import static android.hardware.usb.UsbPortStatus.DATA_ROLE_DEVICE;
import static android.hardware.usb.UsbPortStatus.DATA_ROLE_HOST;
import static android.hardware.usb.UsbPortStatus.MODE_DFP;
import static android.hardware.usb.UsbPortStatus.MODE_DUAL;
import static android.hardware.usb.UsbPortStatus.MODE_UFP;
import static android.hardware.usb.UsbPortStatus.POWER_ROLE_SINK;
import static android.hardware.usb.UsbPortStatus.POWER_ROLE_SOURCE;

import static com.android.internal.usb.DumpUtils.writePort;
import static com.android.internal.usb.DumpUtils.writePortStatus;

import android.Manifest;
import android.annotation.NonNull;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.hardware.usb.ParcelableUsbPort;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbPort;
import android.hardware.usb.UsbPortStatus;
import android.hardware.usb.V1_0.IUsb;
import android.hardware.usb.V1_0.PortRole;
import android.hardware.usb.V1_0.PortRoleType;
import android.hardware.usb.V1_0.Status;
import android.hardware.usb.V1_1.PortStatus_1_1;
import android.hardware.usb.V1_2.IUsbCallback;
import android.hardware.usb.V1_2.PortStatus;
import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
import android.os.Bundle;
import android.os.Handler;
import android.os.HwBinder;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.service.usb.UsbPortInfoProto;
import android.service.usb.UsbPortManagerProto;
import android.service.usb.UsbServiceProto;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import android.util.StatsLog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;
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
    private static final int MSG_SYSTEM_READY = 2;

    // All non-trivial role combinations.
    private static final int COMBO_SOURCE_HOST =
            UsbPort.combineRolesAsBit(POWER_ROLE_SOURCE, DATA_ROLE_HOST);
    private static final int COMBO_SOURCE_DEVICE = UsbPort.combineRolesAsBit(
            POWER_ROLE_SOURCE, DATA_ROLE_DEVICE);
    private static final int COMBO_SINK_HOST =
            UsbPort.combineRolesAsBit(POWER_ROLE_SINK, DATA_ROLE_HOST);
    private static final int COMBO_SINK_DEVICE = UsbPort.combineRolesAsBit(
            POWER_ROLE_SINK, DATA_ROLE_DEVICE);

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

    // Maintains the current connected status of the port.
    // Uploads logs only when the connection status is changes.
    private final ArrayMap<String, Boolean> mConnected = new ArrayMap<>();

    // Maintains the USB contaminant status that was previously logged.
    // Logs get uploaded only when contaminant presence status changes.
    private final ArrayMap<String, Integer> mContaminantStatus = new ArrayMap<>();

    private NotificationManager mNotificationManager;

    /**
     * If there currently is a notification related to contaminated USB port management
     * shown the id of the notification, or 0 if there is none.
     */
    private int mIsPortContaminatedNotificationId;

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
        mHandler.sendEmptyMessage(MSG_SYSTEM_READY);
    }

    private void updateContaminantNotification() {
        PortInfo currentPortInfo = null;
        Resources r = mContext.getResources();
        int contaminantStatus = UsbPortStatus.CONTAMINANT_DETECTION_NOT_DETECTED;

        // Not handling multiple ports here. Showing the notification
        // for the first port that returns CONTAMINANT_PRESENCE_DETECTED.
        for (PortInfo portInfo : mPorts.values()) {
            contaminantStatus = portInfo.mUsbPortStatus.getContaminantDetectionStatus();
            if (contaminantStatus == UsbPortStatus.CONTAMINANT_DETECTION_DETECTED
                    || contaminantStatus == UsbPortStatus.CONTAMINANT_DETECTION_DISABLED) {
                currentPortInfo = portInfo;
                break;
            }
        }

        // Current contminant status is detected while "safe to use usb port"
        // notification is displayed. Remove safe to use usb port notification
        // and push contaminant detected notification.
        if (contaminantStatus == UsbPortStatus.CONTAMINANT_DETECTION_DETECTED
                    && mIsPortContaminatedNotificationId
                    != SystemMessage.NOTE_USB_CONTAMINANT_DETECTED) {
            if (mIsPortContaminatedNotificationId
                    == SystemMessage.NOTE_USB_CONTAMINANT_NOT_DETECTED) {
                mNotificationManager.cancelAsUser(null, mIsPortContaminatedNotificationId,
                        UserHandle.ALL);
            }

            mIsPortContaminatedNotificationId = SystemMessage.NOTE_USB_CONTAMINANT_DETECTED;
            int titleRes = com.android.internal.R.string.usb_contaminant_detected_title;
            CharSequence title = r.getText(titleRes);
            String channel = SystemNotificationChannels.ALERTS;
            CharSequence message = r.getText(
                    com.android.internal.R.string.usb_contaminant_detected_message);

            Intent intent = new Intent();
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setClassName("com.android.systemui",
                    "com.android.systemui.usb.UsbContaminantActivity");
            intent.putExtra(UsbManager.EXTRA_PORT, ParcelableUsbPort.of(currentPortInfo.mUsbPort));

            PendingIntent pi = PendingIntent.getActivityAsUser(mContext, 0,
                                intent, 0, null, UserHandle.CURRENT);

            Notification.Builder builder = new Notification.Builder(mContext, channel)
                    .setOngoing(true)
                    .setTicker(title)
                    .setColor(mContext.getColor(
                           com.android.internal.R.color
                           .system_notification_accent_color))
                    .setContentIntent(pi)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setStyle(new Notification.BigTextStyle()
                    .bigText(message));
            Notification notification = builder.build();
            mNotificationManager.notifyAsUser(null, mIsPortContaminatedNotificationId, notification,
                    UserHandle.ALL);
        // No contaminant is detected but contaminant detection notification is displayed.
        // Remove contaminant detection notification and push safe to use USB port notification.
        } else if (contaminantStatus != UsbPortStatus.CONTAMINANT_DETECTION_DETECTED
                && mIsPortContaminatedNotificationId
                == SystemMessage.NOTE_USB_CONTAMINANT_DETECTED) {
            mNotificationManager.cancelAsUser(null, mIsPortContaminatedNotificationId,
                    UserHandle.ALL);
            mIsPortContaminatedNotificationId = 0;

            // Dont show safe to use notification when contaminant detection is disabled.
            // Show only when the status is changing from detected to not detected.
            if (contaminantStatus == UsbPortStatus.CONTAMINANT_DETECTION_NOT_DETECTED) {
                mIsPortContaminatedNotificationId =
                        SystemMessage.NOTE_USB_CONTAMINANT_NOT_DETECTED;
                int titleRes = com.android.internal.R.string.usb_contaminant_not_detected_title;
                CharSequence title = r.getText(titleRes);
                String channel = SystemNotificationChannels.ALERTS;
                CharSequence message = r.getText(
                        com.android.internal.R.string.usb_contaminant_not_detected_message);

                Notification.Builder builder = new Notification.Builder(mContext, channel)
                        .setSmallIcon(com.android.internal.R.drawable.ic_usb_48dp)
                        .setTicker(title)
                        .setColor(mContext.getColor(
                               com.android.internal.R.color
                               .system_notification_accent_color))
                        .setContentTitle(title)
                        .setContentText(message)
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .setStyle(new Notification.BigTextStyle()
                        .bigText(message));
                Notification notification = builder.build();
                mNotificationManager.notifyAsUser(null, mIsPortContaminatedNotificationId,
                        notification, UserHandle.ALL);
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

    /**
     * Enables/disables contaminant detection.
     *
     * @param portId port identifier.
     * @param enable enable contaminant detection when set to true.
     */
    public void enableContaminantDetection(@NonNull String portId, boolean enable,
            @NonNull IndentingPrintWriter pw) {
        final PortInfo portInfo = mPorts.get(portId);
        if (portInfo == null) {
            if (pw != null) {
                pw.println("No such USB port: " + portId);
            }
            return;
        }

        if (!portInfo.mUsbPort.supportsEnableContaminantPresenceDetection()) {
            return;
        }

        if ((enable && portInfo.mUsbPortStatus.getContaminantDetectionStatus()
                != UsbPortStatus.CONTAMINANT_DETECTION_DISABLED) || (!enable
                && portInfo.mUsbPortStatus.getContaminantDetectionStatus()
                == UsbPortStatus.CONTAMINANT_DETECTION_DISABLED)
                || (portInfo.mUsbPortStatus.getContaminantDetectionStatus()
                == UsbPortStatus.CONTAMINANT_DETECTION_NOT_SUPPORTED)) {
            return;
        }

        try {
            // Oneway call into the hal. Use the castFrom method from HIDL.
            android.hardware.usb.V1_2.IUsb proxy = android.hardware.usb.V1_2.IUsb.castFrom(mProxy);
            proxy.enableContaminantPresenceDetection(portId, enable);
        } catch (RemoteException e) {
            logAndPrintException(pw, "Failed to set contaminant detection", e);
        } catch (ClassCastException e) {
            logAndPrintException(pw, "Method only applicable to V1.2 or above implementation", e);
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
                if (canChangeMode && newPowerRole == POWER_ROLE_SOURCE
                        && newDataRole == DATA_ROLE_HOST) {
                    newMode = MODE_DFP;
                } else if (canChangeMode && newPowerRole == POWER_ROLE_SINK
                        && newDataRole == DATA_ROLE_DEVICE) {
                    newMode = MODE_UFP;
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

    /**
     * Sets contaminant status for simulated USB port objects.
     */
    public void simulateContaminantStatus(String portId, boolean detected,
            IndentingPrintWriter pw) {
        synchronized (mLock) {
            final RawPortInfo portInfo = mSimulatedPorts.get(portId);
            if (portInfo == null) {
                pw.println("Simulated port not found.");
                return;
            }

            pw.println("Simulating wet port: portId=" + portId
                    + ", wet=" + detected);
            portInfo.contaminantDetectionStatus = detected
                    ? UsbPortStatus.CONTAMINANT_DETECTION_DETECTED
                    : UsbPortStatus.CONTAMINANT_DETECTION_NOT_DETECTED;
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

        public void notifyPortStatusChange(
                ArrayList<android.hardware.usb.V1_0.PortStatus> currentPortStatus, int retval) {
            if (!portManager.mSystemReady) {
                return;
            }

            if (retval != Status.SUCCESS) {
                logAndPrint(Log.ERROR, pw, "port status enquiry failed");
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
                        false, CONTAMINANT_DETECTION_NOT_SUPPORTED);
                newPortInfo.add(temp);
                logAndPrint(Log.INFO, pw, "ClientCallback V1_0: " + current.portName);
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
                        false, CONTAMINANT_DETECTION_NOT_SUPPORTED);
                newPortInfo.add(temp);
                logAndPrint(Log.INFO, pw, "ClientCallback V1_1: " + current.status.portName);
            }

            Message message = portManager.mHandler.obtainMessage();
            Bundle bundle = new Bundle();
            bundle.putParcelableArrayList(PORT_INFO, newPortInfo);
            message.what = MSG_UPDATE_PORTS;
            message.setData(bundle);
            portManager.mHandler.sendMessage(message);
        }

        public void notifyPortStatusChange_1_2(
                ArrayList<PortStatus> currentPortStatus, int retval) {
            if (!portManager.mSystemReady) {
                return;
            }

            if (retval != Status.SUCCESS) {
                logAndPrint(Log.ERROR, pw, "port status enquiry failed");
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
                        current.contaminantDetectionStatus);
                newPortInfo.add(temp);
                logAndPrint(Log.INFO, pw, "ClientCallback V1_2: "
                        + current.status_1_1.status.portName);
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
                        portInfo.supportedContaminantProtectionModes,
                        portInfo.currentMode, portInfo.canChangeMode,
                        portInfo.currentPowerRole, portInfo.canChangePowerRole,
                        portInfo.currentDataRole, portInfo.canChangeDataRole,
                        portInfo.supportsEnableContaminantPresenceProtection,
                        portInfo.contaminantProtectionStatus,
                        portInfo.supportsEnableContaminantPresenceDetection,
                        portInfo.contaminantDetectionStatus, pw);
            }
        } else {
            for (RawPortInfo currentPortInfo : newPortInfo) {
                addOrUpdatePortLocked(currentPortInfo.portId, currentPortInfo.supportedModes,
                        currentPortInfo.supportedContaminantProtectionModes,
                        currentPortInfo.currentMode, currentPortInfo.canChangeMode,
                        currentPortInfo.currentPowerRole, currentPortInfo.canChangePowerRole,
                        currentPortInfo.currentDataRole, currentPortInfo.canChangeDataRole,
                        currentPortInfo.supportsEnableContaminantPresenceProtection,
                        currentPortInfo.contaminantProtectionStatus,
                        currentPortInfo.supportsEnableContaminantPresenceDetection,
                        currentPortInfo.contaminantDetectionStatus, pw);
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
            int supportedContaminantProtectionModes,
            int currentMode, boolean canChangeMode,
            int currentPowerRole, boolean canChangePowerRole,
            int currentDataRole, boolean canChangeDataRole,
            boolean supportsEnableContaminantPresenceProtection,
            int contaminantProtectionStatus,
            boolean supportsEnableContaminantPresenceDetection,
            int contaminantDetectionStatus,
            IndentingPrintWriter pw) {
        // Only allow mode switch capability for dual role ports.
        // Validate that the current mode matches the supported modes we expect.
        if ((supportedModes & MODE_DUAL) != MODE_DUAL) {
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
                        POWER_ROLE_SOURCE, currentDataRole);
                supportedRoleCombinations |= UsbPort.combineRolesAsBit(
                        POWER_ROLE_SINK, currentDataRole);
            } else if (canChangeDataRole) {
                // Can only change data role.
                // Assume power role must remain at its current value.
                supportedRoleCombinations |= UsbPort.combineRolesAsBit(
                        currentPowerRole, DATA_ROLE_HOST);
                supportedRoleCombinations |= UsbPort.combineRolesAsBit(
                        currentPowerRole, DATA_ROLE_DEVICE);
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
            portInfo = new PortInfo(mContext.getSystemService(UsbManager.class),
                portId, supportedModes, supportedContaminantProtectionModes,
                supportsEnableContaminantPresenceProtection,
                supportsEnableContaminantPresenceDetection);
            portInfo.setStatus(currentMode, canChangeMode,
                    currentPowerRole, canChangePowerRole,
                    currentDataRole, canChangeDataRole,
                    supportedRoleCombinations, contaminantProtectionStatus,
                    contaminantDetectionStatus);
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

            if (supportsEnableContaminantPresenceProtection
                    != portInfo.mUsbPort.supportsEnableContaminantPresenceProtection()) {
                logAndPrint(Log.WARN, pw,
                        "Ignoring inconsistent supportsEnableContaminantPresenceProtection"
                        + "USB port driver (should be immutable): "
                        + "previous="
                        + portInfo.mUsbPort.supportsEnableContaminantPresenceProtection()
                        + ", current=" + supportsEnableContaminantPresenceProtection);
            }

            if (supportsEnableContaminantPresenceDetection
                    != portInfo.mUsbPort.supportsEnableContaminantPresenceDetection()) {
                logAndPrint(Log.WARN, pw,
                        "Ignoring inconsistent supportsEnableContaminantPresenceDetection "
                        + "USB port driver (should be immutable): "
                        + "previous="
                        + portInfo.mUsbPort.supportsEnableContaminantPresenceDetection()
                        + ", current=" + supportsEnableContaminantPresenceDetection);
            }


            if (portInfo.setStatus(currentMode, canChangeMode,
                    currentPowerRole, canChangePowerRole,
                    currentDataRole, canChangeDataRole,
                    supportedRoleCombinations, contaminantProtectionStatus,
                    contaminantDetectionStatus)) {
                portInfo.mDisposition = PortInfo.DISPOSITION_CHANGED;
            } else {
                portInfo.mDisposition = PortInfo.DISPOSITION_READY;
            }
        }
    }

    private void handlePortLocked(PortInfo portInfo, IndentingPrintWriter pw) {
        sendPortChangedBroadcastLocked(portInfo);
        enableContaminantDetectionIfNeeded(portInfo, pw);
        logToStatsd(portInfo, pw);
        updateContaminantNotification();
    }

    private void handlePortAddedLocked(PortInfo portInfo, IndentingPrintWriter pw) {
        logAndPrint(Log.INFO, pw, "USB port added: " + portInfo);
        handlePortLocked(portInfo, pw);
    }

    private void handlePortChangedLocked(PortInfo portInfo, IndentingPrintWriter pw) {
        logAndPrint(Log.INFO, pw, "USB port changed: " + portInfo);
        handlePortLocked(portInfo, pw);
    }

    private void handlePortRemovedLocked(PortInfo portInfo, IndentingPrintWriter pw) {
        logAndPrint(Log.INFO, pw, "USB port removed: " + portInfo);
        handlePortLocked(portInfo, pw);
    }

    // Constants have to be converted between USB HAL V1.2 ContaminantDetectionStatus
    // to usb.proto as proto guidelines recommends 0 to be UNKNOWN/UNSUPPORTTED
    // whereas HAL policy is against a loosely defined constant.
    private static int convertContaminantDetectionStatusToProto(int contaminantDetectionStatus) {
        switch (contaminantDetectionStatus) {
            case UsbPortStatus.CONTAMINANT_DETECTION_NOT_SUPPORTED:
                return UsbServiceProto.CONTAMINANT_STATUS_NOT_SUPPORTED;
            case UsbPortStatus.CONTAMINANT_DETECTION_DISABLED:
                return UsbServiceProto.CONTAMINANT_STATUS_DISABLED;
            case UsbPortStatus.CONTAMINANT_DETECTION_NOT_DETECTED:
                return UsbServiceProto.CONTAMINANT_STATUS_NOT_DETECTED;
            case UsbPortStatus.CONTAMINANT_DETECTION_DETECTED:
                return UsbServiceProto.CONTAMINANT_STATUS_DETECTED;
            default:
                return UsbServiceProto.CONTAMINANT_STATUS_UNKNOWN;
        }
    }

    private void sendPortChangedBroadcastLocked(PortInfo portInfo) {
        final Intent intent = new Intent(UsbManager.ACTION_USB_PORT_CHANGED);
        intent.addFlags(
                Intent.FLAG_RECEIVER_FOREGROUND |
                        Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        intent.putExtra(UsbManager.EXTRA_PORT, ParcelableUsbPort.of(portInfo.mUsbPort));
        intent.putExtra(UsbManager.EXTRA_PORT_STATUS, portInfo.mUsbPortStatus);

        // Guard against possible reentrance by posting the broadcast from the handler
        // instead of from within the critical section.
        mHandler.post(() -> mContext.sendBroadcastAsUser(intent, UserHandle.ALL,
                Manifest.permission.MANAGE_USB));
    }

    private void enableContaminantDetectionIfNeeded(PortInfo portInfo, IndentingPrintWriter pw) {
        if (!mConnected.containsKey(portInfo.mUsbPort.getId())) {
            return;
        }

        if (mConnected.get(portInfo.mUsbPort.getId())
                && !portInfo.mUsbPortStatus.isConnected()
                && portInfo.mUsbPortStatus.getContaminantDetectionStatus()
                == UsbPortStatus.CONTAMINANT_DETECTION_DISABLED) {
            // Contaminant detection might have been temporarily disabled by the user
            // through SystemUI.
            // Re-enable contaminant detection when the accessory is unplugged.
            enableContaminantDetection(portInfo.mUsbPort.getId(), true, pw);
        }
    }

    private void logToStatsd(PortInfo portInfo, IndentingPrintWriter pw) {
        // Port is removed
        if (portInfo.mUsbPortStatus == null) {
            if (mConnected.containsKey(portInfo.mUsbPort.getId())) {
                //Previous logged a connected. Set it to disconnected.
                if (mConnected.get(portInfo.mUsbPort.getId())) {
                    StatsLog.write(StatsLog.USB_CONNECTOR_STATE_CHANGED,
                            StatsLog.USB_CONNECTOR_STATE_CHANGED__STATE__STATE_DISCONNECTED,
                            portInfo.mUsbPort.getId(), portInfo.mLastConnectDurationMillis);
                }
                mConnected.remove(portInfo.mUsbPort.getId());
            }

            if (mContaminantStatus.containsKey(portInfo.mUsbPort.getId())) {
                //Previous logged a contaminant detected. Set it to not detected.
                if ((mContaminantStatus.get(portInfo.mUsbPort.getId())
                        == UsbPortStatus.CONTAMINANT_DETECTION_DETECTED)) {
                    StatsLog.write(StatsLog.USB_CONTAMINANT_REPORTED,
                            portInfo.mUsbPort.getId(),
                            convertContaminantDetectionStatusToProto(
                                    UsbPortStatus.CONTAMINANT_DETECTION_NOT_DETECTED));
                }
                mContaminantStatus.remove(portInfo.mUsbPort.getId());
            }
            return;
        }

        if (!mConnected.containsKey(portInfo.mUsbPort.getId())
                || (mConnected.get(portInfo.mUsbPort.getId())
                != portInfo.mUsbPortStatus.isConnected())) {
            mConnected.put(portInfo.mUsbPort.getId(), portInfo.mUsbPortStatus.isConnected());
            StatsLog.write(StatsLog.USB_CONNECTOR_STATE_CHANGED,
                    portInfo.mUsbPortStatus.isConnected()
                    ? StatsLog.USB_CONNECTOR_STATE_CHANGED__STATE__STATE_CONNECTED :
                    StatsLog.USB_CONNECTOR_STATE_CHANGED__STATE__STATE_DISCONNECTED,
                    portInfo.mUsbPort.getId(), portInfo.mLastConnectDurationMillis);
        }

        if (!mContaminantStatus.containsKey(portInfo.mUsbPort.getId())
                || (mContaminantStatus.get(portInfo.mUsbPort.getId())
                != portInfo.mUsbPortStatus.getContaminantDetectionStatus())) {
            mContaminantStatus.put(portInfo.mUsbPort.getId(),
                    portInfo.mUsbPortStatus.getContaminantDetectionStatus());
            StatsLog.write(StatsLog.USB_CONTAMINANT_REPORTED,
                    portInfo.mUsbPort.getId(),
                    convertContaminantDetectionStatusToProto(
                            portInfo.mUsbPortStatus.getContaminantDetectionStatus()));
        }
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
                case MSG_SYSTEM_READY: {
                    mNotificationManager = (NotificationManager)
                            mContext.getSystemService(Context.NOTIFICATION_SERVICE);
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
        // default initialized to 0 which means added
        public int mDisposition;
        // Tracks elapsedRealtime() of when the port was connected
        public long mConnectedAtMillis;
        // 0 when port is connected. Else reports the last connected duration
        public long mLastConnectDurationMillis;

        PortInfo(@NonNull UsbManager usbManager, @NonNull String portId, int supportedModes,
                int supportedContaminantProtectionModes,
                boolean supportsEnableContaminantPresenceDetection,
                boolean supportsEnableContaminantPresenceProtection) {
            mUsbPort = new UsbPort(usbManager, portId, supportedModes,
                    supportedContaminantProtectionModes,
                    supportsEnableContaminantPresenceDetection,
                    supportsEnableContaminantPresenceProtection);
        }

        public boolean setStatus(int currentMode, boolean canChangeMode,
                int currentPowerRole, boolean canChangePowerRole,
                int currentDataRole, boolean canChangeDataRole,
                int supportedRoleCombinations) {
            boolean dispositionChanged = false;

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
                        supportedRoleCombinations, UsbPortStatus.CONTAMINANT_PROTECTION_NONE,
                        UsbPortStatus.CONTAMINANT_DETECTION_NOT_SUPPORTED);
                dispositionChanged = true;
            }

            if (mUsbPortStatus.isConnected() && mConnectedAtMillis == 0) {
                mConnectedAtMillis = SystemClock.elapsedRealtime();
                mLastConnectDurationMillis = 0;
            } else if (!mUsbPortStatus.isConnected() && mConnectedAtMillis != 0) {
                mLastConnectDurationMillis = SystemClock.elapsedRealtime() - mConnectedAtMillis;
                mConnectedAtMillis = 0;
            }

            return dispositionChanged;
        }

        public boolean setStatus(int currentMode, boolean canChangeMode,
                int currentPowerRole, boolean canChangePowerRole,
                int currentDataRole, boolean canChangeDataRole,
                int supportedRoleCombinations, int contaminantProtectionStatus,
                int contaminantDetectionStatus) {
            boolean dispositionChanged = false;

            mCanChangeMode = canChangeMode;
            mCanChangePowerRole = canChangePowerRole;
            mCanChangeDataRole = canChangeDataRole;
            if (mUsbPortStatus == null
                    || mUsbPortStatus.getCurrentMode() != currentMode
                    || mUsbPortStatus.getCurrentPowerRole() != currentPowerRole
                    || mUsbPortStatus.getCurrentDataRole() != currentDataRole
                    || mUsbPortStatus.getSupportedRoleCombinations()
                    != supportedRoleCombinations
                    || mUsbPortStatus.getContaminantProtectionStatus()
                    != contaminantProtectionStatus
                    || mUsbPortStatus.getContaminantDetectionStatus()
                    != contaminantDetectionStatus) {
                mUsbPortStatus = new UsbPortStatus(currentMode, currentPowerRole, currentDataRole,
                        supportedRoleCombinations, contaminantProtectionStatus,
                        contaminantDetectionStatus);
                dispositionChanged = true;
            }

            if (mUsbPortStatus.isConnected() && mConnectedAtMillis == 0) {
                mConnectedAtMillis = SystemClock.elapsedRealtime();
                mLastConnectDurationMillis = 0;
            } else if (!mUsbPortStatus.isConnected() && mConnectedAtMillis != 0) {
                mLastConnectDurationMillis = SystemClock.elapsedRealtime() - mConnectedAtMillis;
                mConnectedAtMillis = 0;
            }

            return dispositionChanged;
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
            dump.write("connected_at_millis",
                    UsbPortInfoProto.CONNECTED_AT_MILLIS, mConnectedAtMillis);
            dump.write("last_connect_duration_millis",
                    UsbPortInfoProto.LAST_CONNECT_DURATION_MILLIS, mLastConnectDurationMillis);

            dump.end(token);
        }

        @Override
        public String toString() {
            return "port=" + mUsbPort + ", status=" + mUsbPortStatus
                    + ", canChangeMode=" + mCanChangeMode
                    + ", canChangePowerRole=" + mCanChangePowerRole
                    + ", canChangeDataRole=" + mCanChangeDataRole
                    + ", connectedAtMillis=" + mConnectedAtMillis
                    + ", lastConnectDurationMillis=" + mLastConnectDurationMillis;
        }
    }

    /**
     * Used for storing the raw data from the kernel
     * Values of the member variables mocked directly incase of emulation.
     */
    private static final class RawPortInfo implements Parcelable {
        public final String portId;
        public final int supportedModes;
        public final int supportedContaminantProtectionModes;
        public int currentMode;
        public boolean canChangeMode;
        public int currentPowerRole;
        public boolean canChangePowerRole;
        public int currentDataRole;
        public boolean canChangeDataRole;
        public boolean supportsEnableContaminantPresenceProtection;
        public int contaminantProtectionStatus;
        public boolean supportsEnableContaminantPresenceDetection;
        public int contaminantDetectionStatus;

        RawPortInfo(String portId, int supportedModes) {
            this.portId = portId;
            this.supportedModes = supportedModes;
            this.supportedContaminantProtectionModes = UsbPortStatus.CONTAMINANT_PROTECTION_NONE;
            this.supportsEnableContaminantPresenceProtection = false;
            this.contaminantProtectionStatus = UsbPortStatus.CONTAMINANT_PROTECTION_NONE;
            this.supportsEnableContaminantPresenceDetection = false;
            this.contaminantDetectionStatus = UsbPortStatus.CONTAMINANT_DETECTION_NOT_SUPPORTED;
        }

        RawPortInfo(String portId, int supportedModes, int supportedContaminantProtectionModes,
                int currentMode, boolean canChangeMode,
                int currentPowerRole, boolean canChangePowerRole,
                int currentDataRole, boolean canChangeDataRole,
                boolean supportsEnableContaminantPresenceProtection,
                int contaminantProtectionStatus,
                boolean supportsEnableContaminantPresenceDetection,
                int contaminantDetectionStatus) {
            this.portId = portId;
            this.supportedModes = supportedModes;
            this.supportedContaminantProtectionModes = supportedContaminantProtectionModes;
            this.currentMode = currentMode;
            this.canChangeMode = canChangeMode;
            this.currentPowerRole = currentPowerRole;
            this.canChangePowerRole = canChangePowerRole;
            this.currentDataRole = currentDataRole;
            this.canChangeDataRole = canChangeDataRole;
            this.supportsEnableContaminantPresenceProtection =
                    supportsEnableContaminantPresenceProtection;
            this.contaminantProtectionStatus = contaminantProtectionStatus;
            this.supportsEnableContaminantPresenceDetection =
                    supportsEnableContaminantPresenceDetection;
            this.contaminantDetectionStatus = contaminantDetectionStatus;
        }


        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(portId);
            dest.writeInt(supportedModes);
            dest.writeInt(supportedContaminantProtectionModes);
            dest.writeInt(currentMode);
            dest.writeByte((byte) (canChangeMode ? 1 : 0));
            dest.writeInt(currentPowerRole);
            dest.writeByte((byte) (canChangePowerRole ? 1 : 0));
            dest.writeInt(currentDataRole);
            dest.writeByte((byte) (canChangeDataRole ? 1 : 0));
            dest.writeBoolean(supportsEnableContaminantPresenceProtection);
            dest.writeInt(contaminantProtectionStatus);
            dest.writeBoolean(supportsEnableContaminantPresenceDetection);
            dest.writeInt(contaminantDetectionStatus);
        }

        public static final Parcelable.Creator<RawPortInfo> CREATOR =
                new Parcelable.Creator<RawPortInfo>() {
            @Override
            public RawPortInfo createFromParcel(Parcel in) {
                String id = in.readString();
                int supportedModes = in.readInt();
                int supportedContaminantProtectionModes = in.readInt();
                int currentMode = in.readInt();
                boolean canChangeMode = in.readByte() != 0;
                int currentPowerRole = in.readInt();
                boolean canChangePowerRole = in.readByte() != 0;
                int currentDataRole = in.readInt();
                boolean canChangeDataRole = in.readByte() != 0;
                boolean supportsEnableContaminantPresenceProtection = in.readBoolean();
                int contaminantProtectionStatus = in.readInt();
                boolean supportsEnableContaminantPresenceDetection = in.readBoolean();
                int contaminantDetectionStatus = in.readInt();
                return new RawPortInfo(id, supportedModes,
                        supportedContaminantProtectionModes, currentMode, canChangeMode,
                        currentPowerRole, canChangePowerRole,
                        currentDataRole, canChangeDataRole,
                        supportsEnableContaminantPresenceProtection,
                        contaminantProtectionStatus,
                        supportsEnableContaminantPresenceDetection,
                        contaminantDetectionStatus);
            }

            @Override
            public RawPortInfo[] newArray(int size) {
                return new RawPortInfo[size];
            }
        };
    }
}
