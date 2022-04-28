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

import static android.hardware.usb.UsbOperationInternal.USB_OPERATION_ERROR_PORT_MISMATCH;
import static android.hardware.usb.UsbOperationInternal.USB_OPERATION_ERROR_INTERNAL;
import static android.hardware.usb.UsbPortStatus.CONTAMINANT_DETECTION_NOT_SUPPORTED;
import static android.hardware.usb.UsbPortStatus.CONTAMINANT_PROTECTION_NONE;
import static android.hardware.usb.UsbPortStatus.DATA_ROLE_DEVICE;
import static android.hardware.usb.UsbPortStatus.DATA_ROLE_HOST;
import static android.hardware.usb.UsbPortStatus.MODE_DFP;
import static android.hardware.usb.UsbPortStatus.MODE_DUAL;
import static android.hardware.usb.UsbPortStatus.MODE_UFP;
import static android.hardware.usb.UsbPortStatus.POWER_ROLE_SINK;
import static android.hardware.usb.UsbPortStatus.POWER_ROLE_SOURCE;
import static com.android.server.usb.hal.port.UsbPortHal.HAL_POWER_ROLE_SOURCE;
import static com.android.server.usb.hal.port.UsbPortHal.HAL_POWER_ROLE_SINK;
import static com.android.server.usb.hal.port.UsbPortHal.HAL_DATA_ROLE_HOST;
import static com.android.server.usb.hal.port.UsbPortHal.HAL_DATA_ROLE_DEVICE;
import static com.android.server.usb.hal.port.UsbPortHal.HAL_MODE_DFP;
import static com.android.server.usb.hal.port.UsbPortHal.HAL_MODE_UFP;

import static com.android.internal.usb.DumpUtils.writePort;
import static com.android.internal.usb.DumpUtils.writePortStatus;

import android.Manifest;
import android.annotation.NonNull;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.hardware.usb.IUsbOperationInternal;
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
import android.service.ServiceProtoEnums;
import android.service.usb.UsbPortInfoProto;
import android.service.usb.UsbPortManagerProto;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.server.FgThread;
import com.android.server.usb.hal.port.RawPortInfo;
import com.android.server.usb.hal.port.UsbPortHal;
import com.android.server.usb.hal.port.UsbPortHalInstance;

import java.util.ArrayList;
import java.util.NoSuchElementException;
import java.util.Objects;

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

    // Callback when the UsbPort status is changed by the kernel.
    // Mostly due a command sent by the remote Usb device.
    //private HALCallback mHALCallback = new HALCallback(null, this);

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

    private UsbPortHal mUsbPortHal;

    private long mTransactionId;

    public UsbPortManager(Context context) {
        mContext = context;
        mUsbPortHal = UsbPortHalInstance.getInstance(this, null);
        logAndPrint(Log.DEBUG, null, "getInstance done");
    }

    public void systemReady() {
        mSystemReady = true;
        if (mUsbPortHal != null) {
            mUsbPortHal.systemReady();
            try {
                mUsbPortHal.queryPortStatus(++mTransactionId);
            } catch (Exception e) {
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
            intent.setComponent(ComponentName.unflattenFromString(r.getString(
                    com.android.internal.R.string.config_usbContaminantActivity)));
            intent.putExtra(UsbManager.EXTRA_PORT, ParcelableUsbPort.of(currentPortInfo.mUsbPort));
            intent.putExtra(UsbManager.EXTRA_PORT_STATUS, currentPortInfo.mUsbPortStatus);

            // Simple notification clicks are immutable
            PendingIntent pi = PendingIntent.getActivityAsUser(mContext, 0,
                                intent, PendingIntent.FLAG_IMMUTABLE, null, UserHandle.CURRENT);

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
            mUsbPortHal.enableContaminantPresenceDetection(portId, enable, ++mTransactionId);
        } catch (Exception e) {
            logAndPrintException(pw, "Failed to set contaminant detection", e);
        }
    }

    /**
     * Limits power transfer in/out of USB-C port.
     *
     * @param portId port identifier.
     * @param limit limit power transfer when true.
     */
    public void enableLimitPowerTransfer(@NonNull String portId, boolean limit, long transactionId,
            IUsbOperationInternal callback, IndentingPrintWriter pw) {
        Objects.requireNonNull(portId);
        final PortInfo portInfo = mPorts.get(portId);
        if (portInfo == null) {
            logAndPrint(Log.ERROR, pw, "enableLimitPowerTransfer: No such port: " + portId
                    + " opId:" + transactionId);
            try {
                if (callback != null) {
                    callback.onOperationComplete(USB_OPERATION_ERROR_PORT_MISMATCH);
                }
            } catch (RemoteException e) {
                logAndPrintException(pw,
                        "enableLimitPowerTransfer: Failed to call OperationComplete. opId:"
                        + transactionId, e);
            }
            return;
        }

        try {
            try {
                mUsbPortHal.enableLimitPowerTransfer(portId, limit, transactionId, callback);
            } catch (Exception e) {
                logAndPrintException(pw,
                    "enableLimitPowerTransfer: Failed to limit power transfer. opId:"
                    + transactionId , e);
                if (callback != null) {
                    callback.onOperationComplete(USB_OPERATION_ERROR_INTERNAL);
                }
            }
        } catch (RemoteException e) {
            logAndPrintException(pw,
                    "enableLimitPowerTransfer:Failed to call onOperationComplete. opId:"
                    + transactionId, e);
        }
    }

    /**
     * Enables USB data when disabled due to {@link UsbPortStatus#DATA_STATUS_DISABLED_DOCK}
     */
    public void enableUsbDataWhileDocked(@NonNull String portId, long transactionId,
            IUsbOperationInternal callback, IndentingPrintWriter pw) {
        Objects.requireNonNull(portId);
        final PortInfo portInfo = mPorts.get(portId);
        if (portInfo == null) {
            logAndPrint(Log.ERROR, pw, "enableUsbDataWhileDocked: No such port: " + portId
                    + " opId:" + transactionId);
            try {
                if (callback != null) {
                    callback.onOperationComplete(USB_OPERATION_ERROR_PORT_MISMATCH);
                }
            } catch (RemoteException e) {
                logAndPrintException(pw,
                        "enableUsbDataWhileDocked: Failed to call OperationComplete. opId:"
                        + transactionId, e);
            }
            return;
        }

        try {
            try {
                mUsbPortHal.enableUsbDataWhileDocked(portId, transactionId, callback);
            } catch (Exception e) {
                logAndPrintException(pw,
                    "enableUsbDataWhileDocked: Failed to limit power transfer. opId:"
                    + transactionId , e);
                if (callback != null) {
                    callback.onOperationComplete(USB_OPERATION_ERROR_INTERNAL);
                }
            }
        } catch (RemoteException e) {
            logAndPrintException(pw,
                    "enableUsbDataWhileDocked:Failed to call onOperationComplete. opId:"
                    + transactionId, e);
        }
    }

    /**
     * Enable/disable the USB data signaling
     *
     * @param enable enable or disable USB data signaling
     */
    public boolean enableUsbData(@NonNull String portId, boolean enable, int transactionId,
            @NonNull IUsbOperationInternal callback, IndentingPrintWriter pw) {
        Objects.requireNonNull(callback);
        Objects.requireNonNull(portId);
        final PortInfo portInfo = mPorts.get(portId);
        if (portInfo == null) {
            logAndPrint(Log.ERROR, pw, "enableUsbData: No such port: " + portId
                    + " opId:" + transactionId);
            try {
                callback.onOperationComplete(USB_OPERATION_ERROR_PORT_MISMATCH);
            } catch (RemoteException e) {
                logAndPrintException(pw,
                        "enableUsbData: Failed to call OperationComplete. opId:"
                        + transactionId, e);
            }
            return false;
        }

        try {
            try {
                return mUsbPortHal.enableUsbData(portId, enable, transactionId, callback);
            } catch (Exception e) {
                logAndPrintException(pw,
                    "enableUsbData: Failed to invoke enableUsbData. opId:"
                    + transactionId , e);
                callback.onOperationComplete(USB_OPERATION_ERROR_INTERNAL);
            }
        } catch (RemoteException e) {
            logAndPrintException(pw,
                    "enableUsbData: Failed to call onOperationComplete. opId:"
                    + transactionId, e);
        }

        return false;
    }

    /**
     * Get USB HAL version
     *
     * @param none
     * @return {@link UsbManager#USB_HAL_RETRY} returned when hal version
     *         is yet to be determined.
     */
    public int getUsbHalVersion() {
        if (mUsbPortHal != null) {
            try {
                return mUsbPortHal.getUsbHalVersion();
            } catch (RemoteException e) {
                return UsbManager.USB_HAL_RETRY;
            }
        }
        return UsbManager.USB_HAL_RETRY;
    }

    private int toHalUsbDataRole(int usbDataRole) {
        if (usbDataRole == DATA_ROLE_DEVICE)
            return HAL_DATA_ROLE_DEVICE;
        else
            return HAL_DATA_ROLE_HOST;
    }

    private int toHalUsbPowerRole(int usbPowerRole) {
        if (usbPowerRole == POWER_ROLE_SINK)
            return HAL_POWER_ROLE_SINK;
        else
            return HAL_POWER_ROLE_SOURCE;
    }

    private int toHalUsbMode(int usbMode) {
        if (usbMode == MODE_UFP)
            return HAL_MODE_UFP;
        else
            return HAL_MODE_DFP;
    }

    /**
     * Reset USB port.
     *
     * @param portId port identifier.
     */
    public void resetUsbPort(@NonNull String portId, int transactionId,
            @NonNull IUsbOperationInternal callback, IndentingPrintWriter pw) {
        synchronized (mLock) {
            Objects.requireNonNull(callback);
            Objects.requireNonNull(portId);
            final PortInfo portInfo = mPorts.get(portId);
            if (portInfo == null) {
                logAndPrint(Log.ERROR, pw, "resetUsbPort: No such port: " + portId
                    + " opId:" + transactionId);
                try {
                    callback.onOperationComplete(
                            USB_OPERATION_ERROR_PORT_MISMATCH);
                } catch (RemoteException e) {
                    logAndPrintException(pw,
                            "resetUsbPort: Failed to call OperationComplete. opId:"
                            + transactionId, e);
                }
            }

            try {
                try {
                    mUsbPortHal.resetUsbPort(portId, transactionId, callback);
                } catch (Exception e) {
                    logAndPrintException(pw,
                        "reseetUsbPort: Failed to resetUsbPort. opId:"
                        + transactionId , e);
                    callback.onOperationComplete(USB_OPERATION_ERROR_INTERNAL);
                }
            } catch (RemoteException e) {
                logAndPrintException(pw,
                        "resetUsbPort: Failed to call onOperationComplete. opId:"
                        + transactionId, e);
            }
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
            } else if (mUsbPortHal != null) {
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
                    try {
                        mUsbPortHal.switchMode(portId, toHalUsbMode(newMode), ++mTransactionId);
                    } catch (Exception e) {
                        logAndPrintException(pw, "Failed to set the USB port mode: "
                                + "portId=" + portId
                                + ", newMode=" + UsbPort.modeToString(newMode), e);
                    }
                } else {
                    // Change power and data role independently as needed.
                    if (currentPowerRole != newPowerRole) {
                        try {
                            mUsbPortHal.switchPowerRole(portId, toHalUsbPowerRole(newPowerRole),
                                    ++mTransactionId);
                        } catch (Exception e) {
                            logAndPrintException(pw, "Failed to set the USB port power role: "
                                            + "portId=" + portId
                                            + ", newPowerRole=" + UsbPort.powerRoleToString
                                            (newPowerRole),
                                    e);
                            return;
                        }
                    }
                    if (currentDataRole != newDataRole) {
                        try {
                            mUsbPortHal.switchDataRole(portId, toHalUsbDataRole(newDataRole),
                                    ++mTransactionId);
                        } catch (Exception e) {
                            logAndPrintException(pw, "Failed to set the USB port data role: "
                                            + "portId=" + portId
                                            + ", newDataRole=" + UsbPort.dataRoleToString
                                            (newDataRole),
                                    e);
                        }
                    }
                }
            }
        }
    }

    public void updatePorts(ArrayList<RawPortInfo> newPortInfo) {
        Message message = mHandler.obtainMessage();
        Bundle bundle = new Bundle();
        bundle.putParcelableArrayList(PORT_INFO, newPortInfo);
        message.what = MSG_UPDATE_PORTS;
        message.setData(bundle);
        mHandler.sendMessage(message);
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

            dump.write("usb_hal_version", UsbPortManagerProto.HAL_VERSION, getUsbHalVersion());
        }

        dump.end(token);
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
                        portInfo.contaminantDetectionStatus,
                        portInfo.usbDataStatus,
                        portInfo.powerTransferLimited,
                        portInfo.powerBrickConnectionStatus, pw);
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
                        currentPortInfo.contaminantDetectionStatus,
                        currentPortInfo.usbDataStatus,
                        currentPortInfo.powerTransferLimited,
                        currentPortInfo.powerBrickConnectionStatus, pw);
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
            int usbDataStatus,
            boolean powerTransferLimited,
            int powerBrickConnectionStatus,
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
                    contaminantDetectionStatus, usbDataStatus,
                    powerTransferLimited, powerBrickConnectionStatus);
            mPorts.put(portId, portInfo);
        } else {
            // Validate that ports aren't changing definition out from under us.
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
                    contaminantDetectionStatus, usbDataStatus,
                    powerTransferLimited, powerBrickConnectionStatus)) {
                portInfo.mDisposition = PortInfo.DISPOSITION_CHANGED;
            } else {
                portInfo.mDisposition = PortInfo.DISPOSITION_READY;
            }
        }
    }

    private void handlePortLocked(PortInfo portInfo, IndentingPrintWriter pw) {
        sendPortChangedBroadcastLocked(portInfo);
        logToStatsd(portInfo, pw);
        updateContaminantNotification();
    }

    private void handlePortAddedLocked(PortInfo portInfo, IndentingPrintWriter pw) {
        logAndPrint(Log.INFO, pw, "USB port added: " + portInfo);
        handlePortLocked(portInfo, pw);
    }

    private void handlePortChangedLocked(PortInfo portInfo, IndentingPrintWriter pw) {
        logAndPrint(Log.INFO, pw, "USB port changed: " + portInfo);
        enableContaminantDetectionIfNeeded(portInfo, pw);
        disableLimitPowerTransferIfNeeded(portInfo, pw);
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
                return ServiceProtoEnums.CONTAMINANT_STATUS_NOT_SUPPORTED;
            case UsbPortStatus.CONTAMINANT_DETECTION_DISABLED:
                return ServiceProtoEnums.CONTAMINANT_STATUS_DISABLED;
            case UsbPortStatus.CONTAMINANT_DETECTION_NOT_DETECTED:
                return ServiceProtoEnums.CONTAMINANT_STATUS_NOT_DETECTED;
            case UsbPortStatus.CONTAMINANT_DETECTION_DETECTED:
                return ServiceProtoEnums.CONTAMINANT_STATUS_DETECTED;
            default:
                return ServiceProtoEnums.CONTAMINANT_STATUS_UNKNOWN;
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

    private void disableLimitPowerTransferIfNeeded(PortInfo portInfo, IndentingPrintWriter pw) {
        if (!mConnected.containsKey(portInfo.mUsbPort.getId())) {
            return;
        }

        if (mConnected.get(portInfo.mUsbPort.getId())
                && !portInfo.mUsbPortStatus.isConnected()
                && portInfo.mUsbPortStatus.isPowerTransferLimited()) {
            // Relax enableLimitPowerTransfer upon unplug.
            enableLimitPowerTransfer(portInfo.mUsbPort.getId(), false, ++mTransactionId, null, pw);
        }
    }

    private void logToStatsd(PortInfo portInfo, IndentingPrintWriter pw) {
        // Port is removed
        if (portInfo.mUsbPortStatus == null) {
            if (mConnected.containsKey(portInfo.mUsbPort.getId())) {
                //Previous logged a connected. Set it to disconnected.
                if (mConnected.get(portInfo.mUsbPort.getId())) {
                    FrameworkStatsLog.write(FrameworkStatsLog.USB_CONNECTOR_STATE_CHANGED,
                            FrameworkStatsLog
                                    .USB_CONNECTOR_STATE_CHANGED__STATE__STATE_DISCONNECTED,
                            portInfo.mUsbPort.getId(), portInfo.mLastConnectDurationMillis);
                }
                mConnected.remove(portInfo.mUsbPort.getId());
            }

            if (mContaminantStatus.containsKey(portInfo.mUsbPort.getId())) {
                //Previous logged a contaminant detected. Set it to not detected.
                if ((mContaminantStatus.get(portInfo.mUsbPort.getId())
                        == UsbPortStatus.CONTAMINANT_DETECTION_DETECTED)) {
                    FrameworkStatsLog.write(FrameworkStatsLog.USB_CONTAMINANT_REPORTED,
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
            FrameworkStatsLog.write(FrameworkStatsLog.USB_CONNECTOR_STATE_CHANGED,
                    portInfo.mUsbPortStatus.isConnected()
                    ? FrameworkStatsLog.USB_CONNECTOR_STATE_CHANGED__STATE__STATE_CONNECTED :
                    FrameworkStatsLog.USB_CONNECTOR_STATE_CHANGED__STATE__STATE_DISCONNECTED,
                    portInfo.mUsbPort.getId(), portInfo.mLastConnectDurationMillis);
        }

        if (!mContaminantStatus.containsKey(portInfo.mUsbPort.getId())
                || (mContaminantStatus.get(portInfo.mUsbPort.getId())
                != portInfo.mUsbPortStatus.getContaminantDetectionStatus())) {
            mContaminantStatus.put(portInfo.mUsbPort.getId(),
                    portInfo.mUsbPortStatus.getContaminantDetectionStatus());
            FrameworkStatsLog.write(FrameworkStatsLog.USB_CONTAMINANT_REPORTED,
                    portInfo.mUsbPort.getId(),
                    convertContaminantDetectionStatusToProto(
                            portInfo.mUsbPortStatus.getContaminantDetectionStatus()));
        }
    }

    public static void logAndPrint(int priority, IndentingPrintWriter pw, String msg) {
        Slog.println(priority, TAG, msg);
        if (pw != null) {
            pw.println(msg);
        }
    }

    public static void logAndPrintException(IndentingPrintWriter pw, String msg, Exception e) {
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
    public static final class PortInfo {
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
                        UsbPortStatus.CONTAMINANT_DETECTION_NOT_SUPPORTED,
                        UsbPortStatus.DATA_STATUS_UNKNOWN, false,
                        UsbPortStatus.POWER_BRICK_STATUS_UNKNOWN);
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
                int contaminantDetectionStatus, int usbDataStatus,
                boolean powerTransferLimited, int powerBrickConnectionStatus) {
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
                    != contaminantDetectionStatus
                    || mUsbPortStatus.getUsbDataStatus()
                    != usbDataStatus
                    || mUsbPortStatus.isPowerTransferLimited()
                    != powerTransferLimited
                    || mUsbPortStatus.getPowerBrickConnectionStatus()
                    != powerBrickConnectionStatus) {
                mUsbPortStatus = new UsbPortStatus(currentMode, currentPowerRole, currentDataRole,
                        supportedRoleCombinations, contaminantProtectionStatus,
                        contaminantDetectionStatus, usbDataStatus,
                        powerTransferLimited, powerBrickConnectionStatus);
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
}
