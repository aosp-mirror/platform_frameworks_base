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

package android.hardware.usb;

import static android.hardware.usb.UsbOperationInternal.USB_OPERATION_ERROR_INTERNAL;
import static android.hardware.usb.UsbOperationInternal.USB_OPERATION_ERROR_NOT_SUPPORTED;
import static android.hardware.usb.UsbOperationInternal.USB_OPERATION_ERROR_PORT_MISMATCH;
import static android.hardware.usb.UsbOperationInternal.USB_OPERATION_SUCCESS;
import static android.hardware.usb.UsbPortStatus.CONTAMINANT_DETECTION_DETECTED;
import static android.hardware.usb.UsbPortStatus.CONTAMINANT_DETECTION_DISABLED;
import static android.hardware.usb.UsbPortStatus.CONTAMINANT_DETECTION_NOT_DETECTED;
import static android.hardware.usb.UsbPortStatus.CONTAMINANT_DETECTION_NOT_SUPPORTED;
import static android.hardware.usb.UsbPortStatus.DATA_ROLE_DEVICE;
import static android.hardware.usb.UsbPortStatus.DATA_ROLE_HOST;
import static android.hardware.usb.UsbPortStatus.DATA_ROLE_NONE;
import static android.hardware.usb.UsbPortStatus.MODE_AUDIO_ACCESSORY;
import static android.hardware.usb.UsbPortStatus.MODE_DEBUG_ACCESSORY;
import static android.hardware.usb.UsbPortStatus.MODE_DFP;
import static android.hardware.usb.UsbPortStatus.MODE_DUAL;
import static android.hardware.usb.UsbPortStatus.MODE_NONE;
import static android.hardware.usb.UsbPortStatus.MODE_UFP;
import static android.hardware.usb.UsbPortStatus.POWER_BRICK_STATUS_DISCONNECTED;
import static android.hardware.usb.UsbPortStatus.POWER_BRICK_STATUS_UNKNOWN;
import static android.hardware.usb.UsbPortStatus.POWER_BRICK_STATUS_CONNECTED;
import static android.hardware.usb.UsbPortStatus.POWER_ROLE_NONE;
import static android.hardware.usb.UsbPortStatus.POWER_ROLE_SINK;
import static android.hardware.usb.UsbPortStatus.POWER_ROLE_SOURCE;
import static android.hardware.usb.UsbPortStatus.DATA_STATUS_UNKNOWN;
import static android.hardware.usb.UsbPortStatus.DATA_STATUS_ENABLED;
import static android.hardware.usb.UsbPortStatus.DATA_STATUS_DISABLED_OVERHEAT;
import static android.hardware.usb.UsbPortStatus.DATA_STATUS_DISABLED_CONTAMINANT;
import static android.hardware.usb.UsbPortStatus.DATA_STATUS_DISABLED_DOCK;
import static android.hardware.usb.UsbPortStatus.DATA_STATUS_DISABLED_FORCE;
import static android.hardware.usb.UsbPortStatus.DATA_STATUS_DISABLED_DEBUG;
import static android.hardware.usb.UsbPortStatus.DATA_STATUS_DISABLED_DOCK_HOST_MODE;
import static android.hardware.usb.UsbPortStatus.DATA_STATUS_DISABLED_DOCK_DEVICE_MODE;
import static android.hardware.usb.UsbPortStatus.COMPLIANCE_WARNING_DEBUG_ACCESSORY;
import static android.hardware.usb.UsbPortStatus.COMPLIANCE_WARNING_BC_1_2;
import static android.hardware.usb.UsbPortStatus.COMPLIANCE_WARNING_MISSING_RP;
import static android.hardware.usb.UsbPortStatus.COMPLIANCE_WARNING_OTHER;
import static android.hardware.usb.UsbPortStatus.COMPLIANCE_WARNING_INPUT_POWER_LIMITED;
import static android.hardware.usb.UsbPortStatus.COMPLIANCE_WARNING_MISSING_DATA_LINES;
import static android.hardware.usb.UsbPortStatus.COMPLIANCE_WARNING_ENUMERATION_FAIL;
import static android.hardware.usb.UsbPortStatus.COMPLIANCE_WARNING_FLAKY_CONNECTION;
import static android.hardware.usb.UsbPortStatus.COMPLIANCE_WARNING_UNRELIABLE_IO;
import static android.hardware.usb.DisplayPortAltModeInfo.DISPLAYPORT_ALT_MODE_STATUS_UNKNOWN;
import static android.hardware.usb.DisplayPortAltModeInfo.DISPLAYPORT_ALT_MODE_STATUS_NOT_CAPABLE;
import static android.hardware.usb.DisplayPortAltModeInfo.DISPLAYPORT_ALT_MODE_STATUS_CAPABLE_DISABLED;
import static android.hardware.usb.DisplayPortAltModeInfo.DISPLAYPORT_ALT_MODE_STATUS_ENABLED;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.CheckResult;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.hardware.usb.flags.Flags;
import android.hardware.usb.UsbOperationInternal;
import android.hardware.usb.V1_0.Constants;
import android.os.Binder;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Represents a physical USB port and describes its characteristics.
 *
 * @hide
 */
@SystemApi
public final class UsbPort {
    private static final String TAG = "UsbPort";
    private final String mId;
    private final int mSupportedModes;
    private final UsbManager mUsbManager;
    private final int mSupportedContaminantProtectionModes;
    private final boolean mSupportsEnableContaminantPresenceProtection;
    private final boolean mSupportsEnableContaminantPresenceDetection;
    private final boolean mSupportsComplianceWarnings;
    private final @AltModeType int mSupportedAltModes;

    private static final int NUM_DATA_ROLES = Constants.PortDataRole.NUM_DATA_ROLES;
    /**
     * Points to the first power role in the IUsb HAL.
     */
    private static final int POWER_ROLE_OFFSET = Constants.PortPowerRole.NONE;

    /**
     * Counter for tracking UsbOperation operations.
     */
    private static final AtomicInteger sUsbOperationCount = new AtomicInteger();

    /**
     * The {@link #enableUsbData} request was successfully completed.
     */
    public static final int ENABLE_USB_DATA_SUCCESS = 0;

    /**
     * The {@link #enableUsbData} request failed due to internal error.
     */
    public static final int ENABLE_USB_DATA_ERROR_INTERNAL = 1;

    /**
     * The {@link #enableUsbData} request failed as it's not supported.
     */
    public static final int ENABLE_USB_DATA_ERROR_NOT_SUPPORTED = 2;

    /**
     * The {@link #enableUsbData} request failed as port id mismatched.
     */
    public static final int ENABLE_USB_DATA_ERROR_PORT_MISMATCH = 3;

    /**
     * The {@link #enableUsbData} request failed due to other reasons.
     */
    public static final int ENABLE_USB_DATA_ERROR_OTHER = 4;

    /** @hide */
    @IntDef(prefix = { "ENABLE_USB_DATA_" }, value = {
            ENABLE_USB_DATA_SUCCESS,
            ENABLE_USB_DATA_ERROR_INTERNAL,
            ENABLE_USB_DATA_ERROR_NOT_SUPPORTED,
            ENABLE_USB_DATA_ERROR_PORT_MISMATCH,
            ENABLE_USB_DATA_ERROR_OTHER
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface EnableUsbDataStatus{}

    /**
     * The {@link #enableLimitPowerTransfer} request was successfully completed.
     */
    public static final int ENABLE_LIMIT_POWER_TRANSFER_SUCCESS = 0;

    /**
     * The {@link #enableLimitPowerTransfer} request failed due to internal error.
     */
    public static final int ENABLE_LIMIT_POWER_TRANSFER_ERROR_INTERNAL = 1;

    /**
     * The {@link #enableLimitPowerTransfer} request failed as it's not supported.
     */
    public static final int ENABLE_LIMIT_POWER_TRANSFER_ERROR_NOT_SUPPORTED = 2;

    /**
     * The {@link #enableLimitPowerTransfer} request failed as port id mismatched.
     */
    public static final int ENABLE_LIMIT_POWER_TRANSFER_ERROR_PORT_MISMATCH = 3;

    /**
     * The {@link #enableLimitPowerTransfer} request failed due to other reasons.
     */
    public static final int ENABLE_LIMIT_POWER_TRANSFER_ERROR_OTHER = 4;

    /** @hide */
    @IntDef(prefix = { "ENABLE_LIMIT_POWER_TRANSFER_" }, value = {
            ENABLE_LIMIT_POWER_TRANSFER_SUCCESS,
            ENABLE_LIMIT_POWER_TRANSFER_ERROR_INTERNAL,
            ENABLE_LIMIT_POWER_TRANSFER_ERROR_NOT_SUPPORTED,
            ENABLE_LIMIT_POWER_TRANSFER_ERROR_PORT_MISMATCH,
            ENABLE_LIMIT_POWER_TRANSFER_ERROR_OTHER
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface EnableLimitPowerTransferStatus{}

    /**
     * The {@link #enableUsbDataWhileDocked} request was successfully completed.
     */
    public static final int ENABLE_USB_DATA_WHILE_DOCKED_SUCCESS = 0;

    /**
     * The {@link #enableUsbDataWhileDocked} request failed due to internal error.
     */
    public static final int ENABLE_USB_DATA_WHILE_DOCKED_ERROR_INTERNAL = 1;

    /**
     * The {@link #enableUsbDataWhileDocked} request failed as it's not supported.
     */
    public static final int ENABLE_USB_DATA_WHILE_DOCKED_ERROR_NOT_SUPPORTED = 2;

    /**
     * The {@link #enableUsbDataWhileDocked} request failed as port id mismatched.
     */
    public static final int ENABLE_USB_DATA_WHILE_DOCKED_ERROR_PORT_MISMATCH = 3;

    /**
     * The {@link #enableUsbDataWhileDocked} request failed as data is still enabled.
     */
    public static final int ENABLE_USB_DATA_WHILE_DOCKED_ERROR_DATA_ENABLED = 4;

    /**
     * The {@link #enableUsbDataWhileDocked} request failed due to other reasons.
     */
    public static final int ENABLE_USB_DATA_WHILE_DOCKED_ERROR_OTHER = 5;

    /**
     * The {@link #resetUsbPort} request was successfully completed.
     */
    public static final int RESET_USB_PORT_SUCCESS = 0;

    /**
     * The {@link #resetUsbPort} request failed due to internal error.
     */
    public static final int RESET_USB_PORT_ERROR_INTERNAL = 1;

    /**
     * The {@link #resetUsbPort} request failed as it's not supported.
     */
    public static final int RESET_USB_PORT_ERROR_NOT_SUPPORTED = 2;

    /**
     * The {@link #resetUsbPort} request failed as port id mismatched.
     */
    public static final int RESET_USB_PORT_ERROR_PORT_MISMATCH = 3;

    /**
     * The {@link #resetUsbPort} request failed due to other reasons.
     */
    public static final int RESET_USB_PORT_ERROR_OTHER = 4;

    /** @hide */
    @IntDef(prefix = { "RESET_USB_PORT_" }, value = {
            RESET_USB_PORT_SUCCESS,
            RESET_USB_PORT_ERROR_INTERNAL,
            RESET_USB_PORT_ERROR_NOT_SUPPORTED,
            RESET_USB_PORT_ERROR_PORT_MISMATCH,
            RESET_USB_PORT_ERROR_OTHER
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface ResetUsbPortStatus{}

    /** @hide */
    @IntDef(prefix = { "ENABLE_USB_DATA_WHILE_DOCKED_" }, value = {
            ENABLE_USB_DATA_WHILE_DOCKED_SUCCESS,
            ENABLE_USB_DATA_WHILE_DOCKED_ERROR_INTERNAL,
            ENABLE_USB_DATA_WHILE_DOCKED_ERROR_NOT_SUPPORTED,
            ENABLE_USB_DATA_WHILE_DOCKED_ERROR_PORT_MISMATCH,
            ENABLE_USB_DATA_WHILE_DOCKED_ERROR_DATA_ENABLED,
            ENABLE_USB_DATA_WHILE_DOCKED_ERROR_OTHER
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface EnableUsbDataWhileDockedStatus{}

    /**
     * Indicates that the Alt Mode being described is DisplayPort.
     */
    public static final int FLAG_ALT_MODE_TYPE_DISPLAYPORT = 1 << 0;

    /** @hide */
    @IntDef(prefix = { "FLAG_ALT_MODE_TYPE_" }, flag = true, value = {
        FLAG_ALT_MODE_TYPE_DISPLAYPORT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AltModeType {}

    /** @hide */
    public UsbPort(@NonNull UsbManager usbManager, @NonNull String id, int supportedModes,
            int supportedContaminantProtectionModes,
            boolean supportsEnableContaminantPresenceProtection,
            boolean supportsEnableContaminantPresenceDetection) {
        this(usbManager, id, supportedModes, supportedContaminantProtectionModes,
                supportsEnableContaminantPresenceProtection,
                supportsEnableContaminantPresenceDetection,
                false, 0);
    }

    /** @hide */
    public UsbPort(@NonNull UsbManager usbManager, @NonNull String id, int supportedModes,
            int supportedContaminantProtectionModes,
            boolean supportsEnableContaminantPresenceProtection,
            boolean supportsEnableContaminantPresenceDetection,
            boolean supportsComplianceWarnings,
            int supportedAltModes) {
        Objects.requireNonNull(id);
        Preconditions.checkFlagsArgument(supportedModes,
                MODE_DFP | MODE_UFP | MODE_AUDIO_ACCESSORY | MODE_DEBUG_ACCESSORY);

        mUsbManager = usbManager;
        mId = id;
        mSupportedModes = supportedModes;
        mSupportedContaminantProtectionModes = supportedContaminantProtectionModes;
        mSupportsEnableContaminantPresenceProtection =
                supportsEnableContaminantPresenceProtection;
        mSupportsEnableContaminantPresenceDetection =
                supportsEnableContaminantPresenceDetection;
        mSupportsComplianceWarnings = supportsComplianceWarnings;
        mSupportedAltModes = supportedAltModes;
    }

    /**
     * Gets the unique id of the port.
     *
     * @return The unique id of the port; not intended for display.
     *
     * @hide
     */
    public String getId() {
        return mId;
    }

    /**
     * Gets the supported modes of the port.
     * <p>
     * The actual mode of the port may vary depending on what is plugged into it.
     * </p>
     *
     * @return The supported modes: one of {@link UsbPortStatus#MODE_DFP},
     * {@link UsbPortStatus#MODE_UFP}, or {@link UsbPortStatus#MODE_DUAL}.
     *
     * @hide
     */
    public int getSupportedModes() {
        return mSupportedModes;
    }

   /**
     * Gets the supported port proctection modes when the port is contaminated.
     * <p>
     * The actual mode of the port is decided by the hardware
     * </p>
     *
     * @hide
     */
    public int getSupportedContaminantProtectionModes() {
        return mSupportedContaminantProtectionModes;
    }

   /**
     * Tells if UsbService can enable/disable contaminant presence protection.
     *
     * @hide
     */
    public boolean supportsEnableContaminantPresenceProtection() {
        return mSupportsEnableContaminantPresenceProtection;
    }

   /**
     * Tells if UsbService can enable/disable contaminant presence detection.
     *
     * @hide
     */
    public boolean supportsEnableContaminantPresenceDetection() {
        return mSupportsEnableContaminantPresenceDetection;
    }

    /**
     * Gets the status of this USB port.
     *
     * @return The status of the this port, or {@code null} if port is unknown.
     */
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    public @Nullable UsbPortStatus getStatus() {
        return mUsbManager.getPortStatus(this);
    }

    /**
     * Returns whether this USB port supports mode change
     *
     * @return true if mode change is supported.
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    @FlaggedApi(Flags.FLAG_ENABLE_IS_MODE_CHANGE_SUPPORTED_API)
    public boolean isModeChangeSupported() {
        return mUsbManager.isModeChangeSupported(this);
    }

    /**
     * Queries USB Port to see if the port is capable of identifying
     * non compliant USB power source/cable/accessory.
     *
     * @return true when the UsbPort is capable of identifying
     *             non compliant USB power
     *             source/cable/accessory.
     * @return false otherwise.
     */
    @CheckResult
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    public boolean supportsComplianceWarnings() {
        return mSupportsComplianceWarnings;
    }

    /**
     * Returns all Alt Modes supported by the port.
     *
     * @hide
     */
    public @AltModeType int getSupportedAltModesMask() {
        return mSupportedAltModes;
    }

    /**
     * Returns whether all Alt Mode types in a given mask are supported
     * by the port.
     *
     * @return true if all given Alt Modes are supported, false otherwise.
     *
     */
    public boolean isAltModeSupported(@AltModeType int typeMask) {
        return (mSupportedAltModes & typeMask) == typeMask;
    }


    /**
     * Sets the desired role combination of the port.
     * <p>
     * The supported role combinations depend on what is connected to the port and may be
     * determined by consulting
     * {@link UsbPortStatus#isRoleCombinationSupported UsbPortStatus.isRoleCombinationSupported}.
     * </p><p>
     * Note: This function is asynchronous and may fail silently without applying
     * the operationed changes.  If this function does cause a status change to occur then
     * a {@link UsbManager#ACTION_USB_PORT_CHANGED} broadcast will be sent.
     * </p>
     *
     * @param powerRole The desired power role: {@link UsbPortStatus#POWER_ROLE_SOURCE} or
     *                  {@link UsbPortStatus#POWER_ROLE_SINK}, or
     *                  {@link UsbPortStatus#POWER_ROLE_NONE} if no power role.
     * @param dataRole The desired data role: {@link UsbPortStatus#DATA_ROLE_HOST} or
     *                 {@link UsbPortStatus#DATA_ROLE_DEVICE}, or
     *                 {@link UsbPortStatus#DATA_ROLE_NONE} if no data role.
     */
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    public void setRoles(@UsbPortStatus.UsbPowerRole int powerRole,
            @UsbPortStatus.UsbDataRole int dataRole) {
        UsbPort.checkRoles(powerRole, dataRole);

        mUsbManager.setPortRoles(this, powerRole, dataRole);
    }

    /**
     * Reset Usb data on the port.
     *
     * @param executor Executor for the callback.
     * @param consumer A consumer that consumes the reset result.
     *                 {@link #RESET_USB_PORT_SUCCESS} when request completes
     *                 successfully or
     *                 {@link #RESET_USB_PORT_ERROR_INTERNAL} when request
     *                 fails due to internal error or
     *                 {@link RESET_USB_PORT_ERROR_NOT_SUPPORTED} when not
     *                 supported or
     *                 {@link RESET_USB_PORT_ERROR_PORT_MISMATCH} when request
     *                 fails due to port id mismatch or
     *                 {@link RESET_USB_PORT_ERROR_OTHER} when fails due to
     *                  other reasons.
     */
    @CheckResult
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    public void resetUsbPort(@NonNull @CallbackExecutor Executor executor,
            @NonNull @ResetUsbPortStatus Consumer<Integer> consumer) {
        // UID is added To minimize operationID overlap between two different packages.
        int operationId = sUsbOperationCount.incrementAndGet() + Binder.getCallingUid();
        Log.i(TAG, "resetUsbPort opId:" + operationId);
        UsbOperationInternal opCallback =
                new UsbOperationInternal(operationId, mId, executor, consumer);
        mUsbManager.resetUsbPort(this, operationId, opCallback);
    }

    /**
     * Enables/Disables Usb data on the port.
     *
     * @param enable When true enables USB data if disabled.
     *               When false disables USB data if enabled.
     * @return       {@link #ENABLE_USB_DATA_SUCCESS} when request completes successfully or
     *               {@link #ENABLE_USB_DATA_ERROR_INTERNAL} when request fails due to internal
     *               error or
     *               {@link ENABLE_USB_DATA_ERROR_NOT_SUPPORTED} when not supported or
     *               {@link ENABLE_USB_DATA_ERROR_PORT_MISMATCH} when request fails due to port id
     *               mismatch or
     *               {@link ENABLE_USB_DATA_ERROR_OTHER} when fails due to other reasons.
     */
    @CheckResult
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    public @EnableUsbDataStatus int enableUsbData(boolean enable) {
        // UID is added To minimize operationID overlap between two different packages.
        int operationId = sUsbOperationCount.incrementAndGet() + Binder.getCallingUid();
        Log.i(TAG, "enableUsbData opId:" + operationId
                + " callingUid:" + Binder.getCallingUid());
        UsbOperationInternal opCallback =
                new UsbOperationInternal(operationId, mId);
        if (mUsbManager.enableUsbData(this, enable, operationId, opCallback) == true) {
            opCallback.waitForOperationComplete();
        }

        int result = opCallback.getStatus();
        switch (result) {
            case USB_OPERATION_SUCCESS:
                return ENABLE_USB_DATA_SUCCESS;
            case USB_OPERATION_ERROR_INTERNAL:
                return ENABLE_USB_DATA_ERROR_INTERNAL;
            case USB_OPERATION_ERROR_NOT_SUPPORTED:
                return ENABLE_USB_DATA_ERROR_NOT_SUPPORTED;
            case USB_OPERATION_ERROR_PORT_MISMATCH:
                return ENABLE_USB_DATA_ERROR_PORT_MISMATCH;
            default:
                return ENABLE_USB_DATA_ERROR_OTHER;
        }
    }

    /**
     * Enables Usb data when disabled due to {@link UsbPort#DATA_STATUS_DISABLED_DOCK}
     *
     * @return {@link #ENABLE_USB_DATA_WHILE_DOCKED_SUCCESS} when request completes successfully or
     *         {@link #ENABLE_USB_DATA_WHILE_DOCKED_ERROR_INTERNAL} when request fails due to
     *         internal error or
     *         {@link ENABLE_USB_DATA_WHILE_DOCKED_ERROR_NOT_SUPPORTED} when not supported or
     *         {@link ENABLE_USB_DATA_WHILE_DOCKED_ERROR_PORT_MISMATCH} when request fails due to
     *         port id mismatch or
     *         {@link ENABLE_USB_DATA_WHILE_DOCKED_ERROR_DATA_ENABLED} when request fails as data
     *         is still enabled or
     *         {@link ENABLE_USB_DATA_WHILE_DOCKED_ERROR_OTHER} when fails due to other reasons.
     */
    @CheckResult
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    public @EnableUsbDataWhileDockedStatus int enableUsbDataWhileDocked() {
        // UID is added To minimize operationID overlap between two different packages.
        int operationId = sUsbOperationCount.incrementAndGet() + Binder.getCallingUid();
        Log.i(TAG, "enableUsbData opId:" + operationId
                + " callingUid:" + Binder.getCallingUid());
        UsbPortStatus portStatus = getStatus();
        if (portStatus != null &&
                (portStatus.getUsbDataStatus() & DATA_STATUS_DISABLED_DOCK) !=
                 DATA_STATUS_DISABLED_DOCK) {
            return ENABLE_USB_DATA_WHILE_DOCKED_ERROR_DATA_ENABLED;
        }

        UsbOperationInternal opCallback =
                new UsbOperationInternal(operationId, mId);
        mUsbManager.enableUsbDataWhileDocked(this, operationId, opCallback);
                opCallback.waitForOperationComplete();
        int result = opCallback.getStatus();
        switch (result) {
            case USB_OPERATION_SUCCESS:
                return ENABLE_USB_DATA_WHILE_DOCKED_SUCCESS;
            case USB_OPERATION_ERROR_INTERNAL:
                return ENABLE_USB_DATA_WHILE_DOCKED_ERROR_INTERNAL;
            case USB_OPERATION_ERROR_NOT_SUPPORTED:
                return ENABLE_USB_DATA_WHILE_DOCKED_ERROR_NOT_SUPPORTED;
            case USB_OPERATION_ERROR_PORT_MISMATCH:
                return ENABLE_USB_DATA_WHILE_DOCKED_ERROR_PORT_MISMATCH;
            default:
                return ENABLE_USB_DATA_WHILE_DOCKED_ERROR_OTHER;
        }
    }

    /**
     * Limits power transfer In and out of the port.
     * <p>
     * Disables charging and limits sourcing power(when permitted by the USB spec) until
     * port disconnect event.
     * </p>
     * @param enable limits power transfer when true.
     * @return {@link #ENABLE_LIMIT_POWER_TRANSFER_SUCCESS} when request completes successfully or
     *         {@link #ENABLE_LIMIT_POWER_TRANSFER_ERROR_INTERNAL} when request fails due to
     *         internal error or
     *         {@link ENABLE_LIMIT_POWER_TRANSFER_ERROR_NOT_SUPPORTED} when not supported or
     *         {@link ENABLE_LIMIT_POWER_TRANSFER_ERROR_PORT_MISMATCH} when request fails due to
     *         port id mismatch or
     *         {@link ENABLE_LIMIT_POWER_TRANSFER_ERROR_OTHER} when fails due to other reasons.
     */
    @CheckResult
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    public @EnableLimitPowerTransferStatus int enableLimitPowerTransfer(boolean enable) {
        // UID is added To minimize operationID overlap between two different packages.
        int operationId = sUsbOperationCount.incrementAndGet() + Binder.getCallingUid();
        Log.i(TAG, "enableLimitPowerTransfer opId:" + operationId
                + " callingUid:" + Binder.getCallingUid());
        UsbOperationInternal opCallback =
                new UsbOperationInternal(operationId, mId);
        mUsbManager.enableLimitPowerTransfer(this, enable, operationId, opCallback);
        opCallback.waitForOperationComplete();
        int result = opCallback.getStatus();
        switch (result) {
            case USB_OPERATION_SUCCESS:
                return ENABLE_LIMIT_POWER_TRANSFER_SUCCESS;
            case USB_OPERATION_ERROR_INTERNAL:
                return ENABLE_LIMIT_POWER_TRANSFER_ERROR_INTERNAL;
            case USB_OPERATION_ERROR_NOT_SUPPORTED:
                return ENABLE_LIMIT_POWER_TRANSFER_ERROR_NOT_SUPPORTED;
            case USB_OPERATION_ERROR_PORT_MISMATCH:
                return ENABLE_LIMIT_POWER_TRANSFER_ERROR_PORT_MISMATCH;
            default:
                return ENABLE_LIMIT_POWER_TRANSFER_ERROR_OTHER;
        }
    }

    /**
     * @hide
     **/
    public void enableContaminantDetection(boolean enable) {
        mUsbManager.enableContaminantDetection(this, enable);
    }
    /**
     * Combines one power and one data role together into a unique value with
     * exactly one bit set.  This can be used to efficiently determine whether
     * a combination of roles is supported by testing whether that bit is present
     * in a bit-field.
     *
     * @param powerRole The desired power role: {@link UsbPortStatus#POWER_ROLE_SOURCE}
     *                  or {@link UsbPortStatus#POWER_ROLE_SINK}, or 0 if no power role.
     * @param dataRole  The desired data role: {@link UsbPortStatus#DATA_ROLE_HOST}
     *                  or {@link UsbPortStatus#DATA_ROLE_DEVICE}, or 0 if no data role.
     * @hide
     */
    public static int combineRolesAsBit(int powerRole, int dataRole) {
        checkRoles(powerRole, dataRole);
        final int index = ((powerRole - POWER_ROLE_OFFSET) * NUM_DATA_ROLES) + dataRole;
        return 1 << index;
    }

    /** @hide */
    public static String modeToString(int mode) {
        StringBuilder modeString = new StringBuilder();
        if (mode == MODE_NONE) {
            return "none";
        }

        if ((mode & MODE_DUAL) == MODE_DUAL) {
            modeString.append("dual, ");
        } else {
            if ((mode & MODE_DFP) == MODE_DFP) {
                modeString.append("dfp, ");
            } else if ((mode & MODE_UFP) == MODE_UFP) {
                modeString.append("ufp, ");
            }
        }
        if ((mode & MODE_AUDIO_ACCESSORY) == MODE_AUDIO_ACCESSORY) {
            modeString.append("audio_acc, ");
        }
        if ((mode & MODE_DEBUG_ACCESSORY) == MODE_DEBUG_ACCESSORY) {
            modeString.append("debug_acc, ");
        }

        if (modeString.length() == 0) {
            return Integer.toString(mode);
        }
        return modeString.substring(0, modeString.length() - 2);
    }

    /** @hide */
    public static String powerRoleToString(int role) {
        switch (role) {
            case POWER_ROLE_NONE:
                return "no-power";
            case POWER_ROLE_SOURCE:
                return "source";
            case POWER_ROLE_SINK:
                return "sink";
            default:
                return Integer.toString(role);
        }
    }

    /** @hide */
    public static String dataRoleToString(int role) {
        switch (role) {
            case DATA_ROLE_NONE:
                return "no-data";
            case DATA_ROLE_HOST:
                return "host";
            case DATA_ROLE_DEVICE:
                return "device";
            default:
                return Integer.toString(role);
        }
    }

    /** @hide */
    public static String contaminantPresenceStatusToString(int contaminantPresenceStatus) {
        switch (contaminantPresenceStatus) {
            case CONTAMINANT_DETECTION_NOT_SUPPORTED:
                return "not-supported";
            case CONTAMINANT_DETECTION_DISABLED:
                return "disabled";
            case CONTAMINANT_DETECTION_DETECTED:
                return "detected";
            case CONTAMINANT_DETECTION_NOT_DETECTED:
                return "not detected";
            default:
                return Integer.toString(contaminantPresenceStatus);
        }
    }

    /** @hide */
    public static String usbDataStatusToString(int usbDataStatus) {
        StringBuilder statusString = new StringBuilder();

        if (usbDataStatus == DATA_STATUS_UNKNOWN) {
            return "unknown";
        }

        if ((usbDataStatus & DATA_STATUS_ENABLED) == DATA_STATUS_ENABLED) {
            return "enabled";
        }

        if ((usbDataStatus & DATA_STATUS_DISABLED_OVERHEAT) == DATA_STATUS_DISABLED_OVERHEAT) {
            statusString.append("disabled-overheat, ");
        }

        if ((usbDataStatus & DATA_STATUS_DISABLED_CONTAMINANT)
                == DATA_STATUS_DISABLED_CONTAMINANT) {
            statusString.append("disabled-contaminant, ");
        }

        if ((usbDataStatus & DATA_STATUS_DISABLED_DOCK) == DATA_STATUS_DISABLED_DOCK) {
            statusString.append("disabled-dock, ");
        }

        if ((usbDataStatus & DATA_STATUS_DISABLED_FORCE) == DATA_STATUS_DISABLED_FORCE) {
            statusString.append("disabled-force, ");
        }

        if ((usbDataStatus & DATA_STATUS_DISABLED_DEBUG) == DATA_STATUS_DISABLED_DEBUG) {
            statusString.append("disabled-debug, ");
        }

        if ((usbDataStatus & DATA_STATUS_DISABLED_DOCK_HOST_MODE) ==
            DATA_STATUS_DISABLED_DOCK_HOST_MODE) {
            statusString.append("disabled-host-dock, ");
        }

        if ((usbDataStatus & DATA_STATUS_DISABLED_DOCK_DEVICE_MODE) ==
            DATA_STATUS_DISABLED_DOCK_DEVICE_MODE) {
            statusString.append("disabled-device-dock, ");
        }
        return statusString.toString().replaceAll(", $", "");
    }

    /** @hide */
    public static String powerBrickConnectionStatusToString(int powerBrickConnectionStatus) {
        switch (powerBrickConnectionStatus) {
            case POWER_BRICK_STATUS_UNKNOWN:
                return "unknown";
            case POWER_BRICK_STATUS_CONNECTED:
                return "connected";
            case POWER_BRICK_STATUS_DISCONNECTED:
                return "disconnected";
            default:
                return Integer.toString(powerBrickConnectionStatus);
        }
    }

    /** @hide */
    public static String roleCombinationsToString(int combo) {
        StringBuilder result = new StringBuilder();
        result.append("[");

        boolean first = true;
        while (combo != 0) {
            final int index = Integer.numberOfTrailingZeros(combo);
            combo &= ~(1 << index);
            final int powerRole = (index / NUM_DATA_ROLES + POWER_ROLE_OFFSET);
            final int dataRole = index % NUM_DATA_ROLES;
            if (first) {
                first = false;
            } else {
                result.append(", ");
            }
            result.append(powerRoleToString(powerRole));
            result.append(':');
            result.append(dataRoleToString(dataRole));
        }

        result.append("]");
        return result.toString();
    }

    /** @hide */
    public static String complianceWarningsToString(@NonNull int[] complianceWarnings) {
        StringBuilder complianceWarningString = new StringBuilder();
        complianceWarningString.append("[");

        if (complianceWarnings != null) {
            for (int warning : complianceWarnings) {
                switch (warning) {
                    case UsbPortStatus.COMPLIANCE_WARNING_OTHER:
                        complianceWarningString.append("other, ");
                        break;
                    case UsbPortStatus.COMPLIANCE_WARNING_DEBUG_ACCESSORY:
                        complianceWarningString.append("debug accessory, ");
                        break;
                    case UsbPortStatus.COMPLIANCE_WARNING_BC_1_2:
                        complianceWarningString.append("bc12, ");
                        break;
                    case UsbPortStatus.COMPLIANCE_WARNING_MISSING_RP:
                        complianceWarningString.append("missing rp, ");
                        break;
                    case UsbPortStatus.COMPLIANCE_WARNING_INPUT_POWER_LIMITED:
                        complianceWarningString.append("input power limited, ");
                        break;
                    case UsbPortStatus.COMPLIANCE_WARNING_MISSING_DATA_LINES:
                        complianceWarningString.append("missing data lines, ");
                        break;
                    case UsbPortStatus.COMPLIANCE_WARNING_ENUMERATION_FAIL:
                        complianceWarningString.append("enumeration fail, ");
                        break;
                    case UsbPortStatus.COMPLIANCE_WARNING_FLAKY_CONNECTION:
                        complianceWarningString.append("flaky connection, ");
                        break;
                    case UsbPortStatus.COMPLIANCE_WARNING_UNRELIABLE_IO:
                        complianceWarningString.append("unreliable io, ");
                        break;
                    default:
                        complianceWarningString.append(String.format("Unknown(%d), ", warning));
                        break;
                }
            }
        }

        complianceWarningString.append("]");
        return complianceWarningString.toString().replaceAll(", ]$", "]");
    }

    /** @hide */
    public static String dpAltModeStatusToString(int dpAltModeStatus) {
        switch (dpAltModeStatus) {
            case DISPLAYPORT_ALT_MODE_STATUS_UNKNOWN:
                return "Unknown";
            case DISPLAYPORT_ALT_MODE_STATUS_NOT_CAPABLE:
                return "Not Capable";
            case DISPLAYPORT_ALT_MODE_STATUS_CAPABLE_DISABLED:
                return "Capable-Disabled";
            case DISPLAYPORT_ALT_MODE_STATUS_ENABLED:
                return "Enabled";
            default:
                return Integer.toString(dpAltModeStatus);
        }
    }

    /** @hide */
    public static void checkMode(int powerRole) {
        Preconditions.checkArgumentInRange(powerRole, Constants.PortMode.NONE,
                Constants.PortMode.NUM_MODES - 1, "portMode");
    }

    /** @hide */
    public static void checkPowerRole(int dataRole) {
        Preconditions.checkArgumentInRange(dataRole, Constants.PortPowerRole.NONE,
                Constants.PortPowerRole.NUM_POWER_ROLES - 1, "powerRole");
    }

    /** @hide */
    public static void checkDataRole(int mode) {
        Preconditions.checkArgumentInRange(mode, Constants.PortDataRole.NONE,
                Constants.PortDataRole.NUM_DATA_ROLES - 1, "powerRole");
    }

    /** @hide */
    public static void checkRoles(int powerRole, int dataRole) {
        Preconditions.checkArgumentInRange(powerRole, POWER_ROLE_NONE, POWER_ROLE_SINK,
                "powerRole");
        Preconditions.checkArgumentInRange(dataRole, DATA_ROLE_NONE, DATA_ROLE_DEVICE, "dataRole");
    }

    /** @hide */
    public boolean isModeSupported(int mode) {
        if ((mSupportedModes & mode) == mode) return true;
        return false;
    }

    @NonNull
    @Override
    public String toString() {
        return "UsbPort{id=" + mId + ", supportedModes=" + modeToString(mSupportedModes)
                + ", supportedContaminantProtectionModes=" + mSupportedContaminantProtectionModes
                + ", supportsEnableContaminantPresenceProtection="
                + mSupportsEnableContaminantPresenceProtection
                + ", supportsEnableContaminantPresenceDetection="
                + mSupportsEnableContaminantPresenceDetection
                + ", supportsComplianceWarnings="
                + mSupportsComplianceWarnings;
    }
}
