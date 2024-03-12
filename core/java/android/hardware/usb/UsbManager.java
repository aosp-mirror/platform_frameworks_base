/*
 * Copyright (C) 2010 The Android Open Source Project
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

import static android.hardware.usb.UsbPortStatus.DATA_STATUS_DISABLED_FORCE;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.LongDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.app.PendingIntent;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.hardware.usb.gadget.GadgetFunction;
import android.hardware.usb.gadget.UsbSpeed;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class allows you to access the state of USB and communicate with USB devices.
 * Currently only host mode is supported in the public API.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about communicating with USB hardware, read the
 * <a href="{@docRoot}guide/topics/connectivity/usb/index.html">USB developer guide</a>.</p>
 * </div>
 */
@SystemService(Context.USB_SERVICE)
public class UsbManager {
    private static final String TAG = "UsbManager";

    /**
     * Broadcast Action:  A sticky broadcast for USB state change events when in device mode.
     *
     * This is a sticky broadcast for clients that includes USB connected/disconnected state,
     * <ul>
     * <li> {@link #USB_CONNECTED} boolean indicating whether USB is connected or disconnected.
     * <li> {@link #USB_HOST_CONNECTED} boolean indicating whether USB is connected or
     *     disconnected as host.
     * <li> {@link #USB_CONFIGURED} boolean indicating whether USB is configured.
     * currently zero if not configured, one for configured.
     * <li> {@link #USB_FUNCTION_ADB} boolean extra indicating whether the
     * adb function is enabled
     * <li> {@link #USB_FUNCTION_RNDIS} boolean extra indicating whether the
     * RNDIS ethernet function is enabled
     * <li> {@link #USB_FUNCTION_MTP} boolean extra indicating whether the
     * MTP function is enabled
     * <li> {@link #USB_FUNCTION_PTP} boolean extra indicating whether the
     * PTP function is enabled
     * <li> {@link #USB_FUNCTION_ACCESSORY} boolean extra indicating whether the
     * accessory function is enabled
     * <li> {@link #USB_FUNCTION_AUDIO_SOURCE} boolean extra indicating whether the
     * audio source function is enabled
     * <li> {@link #USB_FUNCTION_MIDI} boolean extra indicating whether the
     * MIDI function is enabled
     * <li> {@link #USB_FUNCTION_UVC} boolean extra indicating whether the
     * UVC function is enabled
     * </ul>
     * If the sticky intent has not been found, that indicates USB is disconnected,
     * USB is not configured, MTP function is enabled, and all the other functions are disabled.
     *
     * @hide
     */
    @SystemApi
    public static final String ACTION_USB_STATE =
            "android.hardware.usb.action.USB_STATE";

    /**
     * Broadcast Action: A broadcast for USB port changes.
     *
     * This intent is sent when a USB port is added, removed, or changes state.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    public static final String ACTION_USB_PORT_CHANGED =
            "android.hardware.usb.action.USB_PORT_CHANGED";

    /**
     * Broadcast Action: A broadcast for USB compliance warning changes.
     *
     * This intent is sent when a port partner's
     * (USB power source/cable/accessory) compliance warnings change state.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    public static final String ACTION_USB_PORT_COMPLIANCE_CHANGED =
            "android.hardware.usb.action.USB_PORT_COMPLIANCE_CHANGED";

    /**
     * Activity intent sent when user attaches a USB device.
     *
     * This intent is sent when a USB device is attached to the USB bus when in host mode.
     * <ul>
     * <li> {@link #EXTRA_DEVICE} containing the {@link android.hardware.usb.UsbDevice}
     * for the attached device
     * </ul>
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_USB_DEVICE_ATTACHED =
            "android.hardware.usb.action.USB_DEVICE_ATTACHED";

    /**
     * Broadcast Action:  A broadcast for USB device detached event.
     *
     * This intent is sent when a USB device is detached from the USB bus when in host mode.
     * <ul>
     * <li> {@link #EXTRA_DEVICE} containing the {@link android.hardware.usb.UsbDevice}
     * for the detached device
     * </ul>
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_USB_DEVICE_DETACHED =
            "android.hardware.usb.action.USB_DEVICE_DETACHED";

    /**
     * Activity intent sent when user attaches a USB accessory.
     *
     * <ul>
     * <li> {@link #EXTRA_ACCESSORY} containing the {@link android.hardware.usb.UsbAccessory}
     * for the attached accessory
     * </ul>
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_USB_ACCESSORY_ATTACHED =
            "android.hardware.usb.action.USB_ACCESSORY_ATTACHED";

    /**
     * Broadcast Action:  A broadcast for USB accessory detached event.
     *
     * This intent is sent when a USB accessory is detached.
     * <ul>
     * <li> {@link #EXTRA_ACCESSORY} containing the {@link UsbAccessory}
     * for the attached accessory that was detached
     * </ul>
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_USB_ACCESSORY_DETACHED =
            "android.hardware.usb.action.USB_ACCESSORY_DETACHED";

    /**
     * Broadcast Action:  A broadcast for USB accessory handshaking information delivery
     *
     * This intent is sent when a USB accessory connect attempt
     *
     * <p>For more information about communicating with USB accessory handshake, refer to
     * <a href="https://source.android.com/devices/accessories/aoa">AOA</a> developer guide.</p>
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    public static final String ACTION_USB_ACCESSORY_HANDSHAKE =
            "android.hardware.usb.action.USB_ACCESSORY_HANDSHAKE";

    /**
     * Boolean extra indicating whether USB is connected or disconnected.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast.
     *
     * @hide
     */
    @SystemApi
    public static final String USB_CONNECTED = "connected";

    /**
     * Boolean extra indicating whether USB is connected or disconnected as host.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast.
     *
     * @hide
     */
    public static final String USB_HOST_CONNECTED = "host_connected";

    /**
     * Boolean extra indicating whether USB is configured.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast.
     *
     * @hide
     */
    @SystemApi
    public static final String USB_CONFIGURED = "configured";

    /**
     * Boolean extra indicating whether confidential user data, such as photos, should be
     * made available on the USB connection. This variable will only be set when the user
     * has explicitly asked for this data to be unlocked.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast.
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final String USB_DATA_UNLOCKED = "unlocked";

    /**
     * A placeholder indicating that no USB function is being specified.
     * Used for compatibility with old init scripts to indicate no functions vs. charging function.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static final String USB_FUNCTION_NONE = "none";

    /**
     * Name of the adb USB function.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast
     *
     * @hide
     */
    public static final String USB_FUNCTION_ADB = "adb";

    /**
     * Name of the RNDIS ethernet USB function.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast
     *
     * @hide
     */
    @SystemApi
    public static final String USB_FUNCTION_RNDIS = "rndis";

    /**
     * Name of the MTP USB function.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast
     *
     * @hide
     */
    public static final String USB_FUNCTION_MTP = "mtp";

    /**
     * Name of the PTP USB function.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast
     *
     * @hide
     */
    public static final String USB_FUNCTION_PTP = "ptp";

    /**
     * Name of the audio source USB function.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast
     *
     * @hide
     */
    public static final String USB_FUNCTION_AUDIO_SOURCE = "audio_source";

    /**
     * Name of the MIDI USB function.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast
     *
     * @hide
     */
    public static final String USB_FUNCTION_MIDI = "midi";

    /**
     * Name of the Accessory USB function.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast
     *
     * @hide
     */
    public static final String USB_FUNCTION_ACCESSORY = "accessory";

    /**
     * Name of the NCM USB function.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast
     *
     * @hide
     */
    @SystemApi
    public static final String USB_FUNCTION_NCM = "ncm";

    /**
     * Name of the UVC USB function.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast
     *
     * @hide
     */
    public static final String USB_FUNCTION_UVC = "uvc";

    /**
     * Name of Gadget Hal Not Present;
     *
     * @hide
     */
    public static final String GADGET_HAL_UNKNOWN = "unknown";

    /**
     * Name of the USB Gadget Hal Version v1.0;
     *
     * @hide
     */
    public static final String GADGET_HAL_VERSION_1_0 = "V1_0";

    /**
     * Name of the USB Gadget Hal Version v1.1;
     *
     * @hide
     */
    public static final String GADGET_HAL_VERSION_1_1 = "V1_1";

    /**
     * Name of the USB Gadget Hal Version v1.2;
     *
     * @hide
     */
    public static final String GADGET_HAL_VERSION_1_2 = "V1_2";

    /**
     * Name of the USB Gadget Hal Version v2.0;
     *
     * @hide
     */
    public static final String GADGET_HAL_VERSION_2_0 = "V2_0";

    /**
     * Name of extra for {@link #ACTION_USB_PORT_CHANGED}
     * containing the {@link UsbPort} object for the port.
     *
     * @hide
     */
    public static final String EXTRA_PORT = "port";

    /**
     * Name of extra for {@link #ACTION_USB_PORT_CHANGED}
     * containing the {@link UsbPortStatus} object for the port, or null if the port
     * was removed.
     *
     * @hide
     */
    public static final String EXTRA_PORT_STATUS = "portStatus";

    /**
     * Name of extra for {@link #ACTION_USB_DEVICE_ATTACHED} and
     * {@link #ACTION_USB_DEVICE_DETACHED} broadcasts
     * containing the {@link UsbDevice} object for the device.
     */
    public static final String EXTRA_DEVICE = "device";

    /**
     * Name of extra for {@link #ACTION_USB_ACCESSORY_ATTACHED} and
     * {@link #ACTION_USB_ACCESSORY_DETACHED} broadcasts
     * containing the {@link UsbAccessory} object for the accessory.
     */
    public static final String EXTRA_ACCESSORY = "accessory";

    /**
     * A long extra indicating ms from boot to get get_protocol UEvent
     * This is obtained with SystemClock.elapsedRealtime()
     * Used in extras for {@link #ACTION_USB_ACCESSORY_HANDSHAKE} broadcasts.
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_ACCESSORY_UEVENT_TIME =
            "android.hardware.usb.extra.ACCESSORY_UEVENT_TIME";

    /**
     * An integer extra indicating numbers of send string during handshake
     * Used in extras for {@link #ACTION_USB_ACCESSORY_HANDSHAKE} broadcasts
     *
     * <p>For more information about control request with identifying string information
     * between communicating with USB accessory handshake, refer to
     * <a href="https://source.android.com/devices/accessories/aoa">AOA</a> developer guide.</p>
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_ACCESSORY_STRING_COUNT =
            "android.hardware.usb.extra.ACCESSORY_STRING_COUNT";

    /**
     * Boolean extra indicating whether got start accessory or not
     * Used in extras for {@link #ACTION_USB_ACCESSORY_HANDSHAKE} broadcasts.
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_ACCESSORY_START =
            "android.hardware.usb.extra.ACCESSORY_START";

    /**

     * A long extra indicating the timestamp just before
     * sending {@link #ACTION_USB_ACCESSORY_HANDSHAKE}.
     * Used in extras for {@link #ACTION_USB_ACCESSORY_HANDSHAKE} broadcasts.
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_ACCESSORY_HANDSHAKE_END =
            "android.hardware.usb.extra.ACCESSORY_HANDSHAKE_END";

    /**
     * Name of extra added to the {@link android.app.PendingIntent}
     * passed into {@link #requestPermission(UsbDevice, PendingIntent)}
     * or {@link #requestPermission(UsbAccessory, PendingIntent)}
     * containing a boolean value indicating whether the user granted permission or not.
     */
    public static final String EXTRA_PERMISSION_GRANTED = "permission";

    /**
     * Name of extra added to start systemui.usb.UsbPermissionActivity
     * containing package name of the app which requests USB permission.
     *
     * @hide
     */
    public static final String EXTRA_PACKAGE = "android.hardware.usb.extra.PACKAGE";

    /**
     * Name of extra added to start systemui.usb.UsbPermissionActivity
     * containing the whether the app which requests USB permission can be set as default handler
     * for USB device attach event or USB accessory attach event or not.
     *
     * @hide
     */
    public static final String EXTRA_CAN_BE_DEFAULT = "android.hardware.usb.extra.CAN_BE_DEFAULT";

    /**
     * The Value for USB gadget hal is not presented.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final int GADGET_HAL_NOT_SUPPORTED = -1;

    /**
     * Value for Gadget Hal Version v1.0.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final int GADGET_HAL_V1_0 = 10;

    /**
     * Value for Gadget Hal Version v1.1.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final int GADGET_HAL_V1_1 = 11;

    /**
     * Value for Gadget Hal Version v1.2.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final int GADGET_HAL_V1_2 = 12;

    /**
     * Value for Gadget Hal Version v2.0.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final int GADGET_HAL_V2_0 = 20;

    /**
     * Value for USB_STATE is not configured.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final int USB_DATA_TRANSFER_RATE_UNKNOWN = -1;

    /**
     * Value for USB Transfer Rate of Low Speed in Mbps (real value is 1.5Mbps).
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final int USB_DATA_TRANSFER_RATE_LOW_SPEED = 2;

    /**
     * Value for USB Transfer Rate of Full Speed in Mbps.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final int USB_DATA_TRANSFER_RATE_FULL_SPEED = 12;

    /**
     * Value for USB Transfer Rate of High Speed in Mbps.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final int USB_DATA_TRANSFER_RATE_HIGH_SPEED = 480;

    /**
     * Value for USB Transfer Rate of Super Speed in Mbps.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final int USB_DATA_TRANSFER_RATE_5G = 5 * 1024;

    /**
     * Value for USB Transfer Rate of 10G.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final int USB_DATA_TRANSFER_RATE_10G = 10 * 1024;

    /**
     * Value for USB Transfer Rate of 20G.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final int USB_DATA_TRANSFER_RATE_20G = 20 * 1024;

    /**
     * Value for USB Transfer Rate of 40G.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final int USB_DATA_TRANSFER_RATE_40G = 40 * 1024;

    /**
     * Returned when the client has to retry querying the version.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final int USB_HAL_RETRY = -2;

    /**
     * The Value for USB hal is not presented.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final int USB_HAL_NOT_SUPPORTED = -1;

    /**
     * Value for USB Hal Version v1.0.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final int USB_HAL_V1_0 = 10;

    /**
     * Value for USB Hal Version v1.1.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final int USB_HAL_V1_1 = 11;

    /**
     * Value for USB Hal Version v1.2.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final int USB_HAL_V1_2 = 12;

    /**
     * Value for USB Hal Version v1.3.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final int USB_HAL_V1_3 = 13;

    /**
     * Value for USB Hal Version v2.0.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final int USB_HAL_V2_0 = 20;

    /**
     * Code for the charging usb function. Passed into {@link #setCurrentFunctions(long)}
     * Must be equal to {@link GadgetFunction#NONE}
     * @hide
     */
    @SystemApi
    public static final long FUNCTION_NONE = 0;

    /**
     * Code for the mtp usb function. Passed as a mask into {@link #setCurrentFunctions(long)}
     * Must be equal to {@link GadgetFunction#MTP}
     * @hide
     */
    @SystemApi
    public static final long FUNCTION_MTP = 1 << 2;

    /**
     * Code for the ptp usb function. Passed as a mask into {@link #setCurrentFunctions(long)}
     * Must be equal to {@link GadgetFunction#PTP}
     * @hide
     */
    @SystemApi
    public static final long FUNCTION_PTP = 1 << 4;

    /**
     * Code for the rndis usb function. Passed as a mask into {@link #setCurrentFunctions(long)}
     * Must be equal to {@link GadgetFunction#RNDIS}
     * @hide
     */
    @SystemApi
    public static final long FUNCTION_RNDIS = 1 << 5;

    /**
     * Code for the midi usb function. Passed as a mask into {@link #setCurrentFunctions(long)}
     * Must be equal to {@link GadgetFunction#MIDI}
     * @hide
     */
    @SystemApi
    public static final long FUNCTION_MIDI = 1 << 3;

    /**
     * Code for the accessory usb function.
     * Must be equal to {@link GadgetFunction#ACCESSORY}
     * @hide
     */
    @SystemApi
    public static final long FUNCTION_ACCESSORY = 1 << 1;

    /**
     * Code for the audio source usb function.
     * Must be equal to {@link GadgetFunction#AUDIO_SOURCE}
     * @hide
     */
    @SystemApi
    public static final long FUNCTION_AUDIO_SOURCE = 1 << 6;

    /**
     * Code for the adb usb function.
     * Must be equal to {@link GadgetFunction#ADB}
     * @hide
     */
    @SystemApi
    public static final long FUNCTION_ADB = 1;

    /**
     * Code for the ncm source usb function.
     * Must be equal to {@link GadgetFunction#NCM}
     * @hide
     */
    @SystemApi
    public static final long FUNCTION_NCM = 1 << 10;

    /**
     * Code for the uvc usb function. Passed as a mask into {@link #setCurrentFunctions(long)}
     * Only supported if {@link #isUvcSupportEnabled()} returns true.
     * Must be equal to {@link GadgetFunction#UVC}
     * @hide
     */
    @SystemApi
    public static final long FUNCTION_UVC = 1 << 7;

    private static final long SETTABLE_FUNCTIONS = FUNCTION_MTP | FUNCTION_PTP | FUNCTION_RNDIS
            | FUNCTION_MIDI | FUNCTION_NCM | FUNCTION_UVC;

    private static final Map<String, Long> FUNCTION_NAME_TO_CODE = new HashMap<>();

    /**
     * Counter for tracking UsbOperation operations.
     */
    private static final AtomicInteger sUsbOperationCount = new AtomicInteger();

    static {
        FUNCTION_NAME_TO_CODE.put(UsbManager.USB_FUNCTION_MTP, FUNCTION_MTP);
        FUNCTION_NAME_TO_CODE.put(UsbManager.USB_FUNCTION_PTP, FUNCTION_PTP);
        FUNCTION_NAME_TO_CODE.put(UsbManager.USB_FUNCTION_RNDIS, FUNCTION_RNDIS);
        FUNCTION_NAME_TO_CODE.put(UsbManager.USB_FUNCTION_MIDI, FUNCTION_MIDI);
        FUNCTION_NAME_TO_CODE.put(UsbManager.USB_FUNCTION_ACCESSORY, FUNCTION_ACCESSORY);
        FUNCTION_NAME_TO_CODE.put(UsbManager.USB_FUNCTION_AUDIO_SOURCE, FUNCTION_AUDIO_SOURCE);
        FUNCTION_NAME_TO_CODE.put(UsbManager.USB_FUNCTION_ADB, FUNCTION_ADB);
        FUNCTION_NAME_TO_CODE.put(UsbManager.USB_FUNCTION_NCM, FUNCTION_NCM);
        FUNCTION_NAME_TO_CODE.put(UsbManager.USB_FUNCTION_UVC, FUNCTION_UVC);
    }

    /** @hide */
    @LongDef(flag = true, prefix = { "FUNCTION_" }, value = {
            FUNCTION_NONE,
            FUNCTION_MTP,
            FUNCTION_PTP,
            FUNCTION_RNDIS,
            FUNCTION_MIDI,
            FUNCTION_ACCESSORY,
            FUNCTION_AUDIO_SOURCE,
            FUNCTION_ADB,
            FUNCTION_NCM,
            FUNCTION_UVC,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface UsbFunctionMode {}

    /** @hide */
    @IntDef(prefix = { "GADGET_HAL_" }, value = {
            GADGET_HAL_NOT_SUPPORTED,
            GADGET_HAL_V1_0,
            GADGET_HAL_V1_1,
            GADGET_HAL_V1_2,
            GADGET_HAL_V2_0,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface UsbGadgetHalVersion {}

    /** @hide */
    @IntDef(prefix = { "USB_HAL_" }, value = {
            USB_HAL_NOT_SUPPORTED,
            USB_HAL_V1_0,
            USB_HAL_V1_1,
            USB_HAL_V1_2,
            USB_HAL_V1_3,
            USB_HAL_V2_0,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface UsbHalVersion {}

    /**
     * Listener to register for when the {@link DisplayPortAltModeInfo} changes on a
     * {@link UsbPort}.
     *
     * @hide
     */
    @SystemApi
    public interface DisplayPortAltModeInfoListener {
        /**
         * Callback to be executed when the {@link DisplayPortAltModeInfo} changes on a
         * {@link UsbPort}.
         *
         * @param portId    String describing the {@link UsbPort} that was changed.
         * @param info      New {@link DisplayPortAltModeInfo} for the corresponding portId.
         */
        public void onDisplayPortAltModeInfoChanged(@NonNull String portId,
                @NonNull DisplayPortAltModeInfo info);
    }

    /**
     * Holds callback and executor data to be passed across UsbService.
     */
    private class DisplayPortAltModeInfoDispatchingListener extends
            IDisplayPortAltModeInfoListener.Stub {

        public void onDisplayPortAltModeInfoChanged(String portId,
                DisplayPortAltModeInfo displayPortAltModeInfo) {
            synchronized (mDisplayPortListenersLock) {
                for (Map.Entry<DisplayPortAltModeInfoListener, Executor> entry :
                        mDisplayPortListeners.entrySet()) {
                    Executor executor = entry.getValue();
                    DisplayPortAltModeInfoListener callback = entry.getKey();
                    final long token = Binder.clearCallingIdentity();
                    try {
                        executor.execute(() -> callback.onDisplayPortAltModeInfoChanged(portId,
                                displayPortAltModeInfo));
                    } catch (Exception e) {
                        Slog.e(TAG, "Exception during onDisplayPortAltModeInfoChanged from "
                                + "executor: " + executor, e);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                }
            }
        }
    }

    private final Context mContext;
    private final IUsbManager mService;
    private final Object mDisplayPortListenersLock = new Object();
    @GuardedBy("mDisplayPortListenersLock")
    private ArrayMap<DisplayPortAltModeInfoListener, Executor> mDisplayPortListeners;
    @GuardedBy("mDisplayPortListenersLock")
    private DisplayPortAltModeInfoDispatchingListener mDisplayPortServiceListener;

    /**
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public UsbManager(Context context, IUsbManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Returns a HashMap containing all USB devices currently attached.
     * USB device name is the key for the returned HashMap.
     * The result will be empty if no devices are attached, or if
     * USB host mode is inactive or unsupported.
     *
     * @return HashMap containing all connected USB devices.
     */
    @RequiresFeature(PackageManager.FEATURE_USB_HOST)
    public HashMap<String,UsbDevice> getDeviceList() {
        HashMap<String,UsbDevice> result = new HashMap<String,UsbDevice>();
        if (mService == null) {
            return result;
        }
        Bundle bundle = new Bundle();
        try {
            mService.getDeviceList(bundle);
            for (String name : bundle.keySet()) {
                result.put(name, (UsbDevice)bundle.get(name));
            }
            return result;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Opens the device so it can be used to send and receive
     * data using {@link android.hardware.usb.UsbRequest}.
     *
     * @param device the device to open
     * @return a {@link UsbDeviceConnection}, or {@code null} if open failed
     */
    @RequiresFeature(PackageManager.FEATURE_USB_HOST)
    public UsbDeviceConnection openDevice(UsbDevice device) {
        try {
            String deviceName = device.getDeviceName();
            ParcelFileDescriptor pfd = mService.openDevice(deviceName, mContext.getPackageName());
            if (pfd != null) {
                UsbDeviceConnection connection = new UsbDeviceConnection(device);
                boolean result = connection.open(deviceName, pfd, mContext);
                pfd.close();
                if (result) {
                    return connection;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "exception in UsbManager.openDevice", e);
        }
        return null;
    }

    /**
     * Returns a list of currently attached USB accessories.
     * (in the current implementation there can be at most one)
     *
     * @return list of USB accessories, or null if none are attached.
     */
    @RequiresFeature(PackageManager.FEATURE_USB_ACCESSORY)
    public UsbAccessory[] getAccessoryList() {
        if (mService == null) {
            return null;
        }
        try {
            UsbAccessory accessory = mService.getCurrentAccessory();
            if (accessory == null) {
                return null;
            } else {
                return new UsbAccessory[] { accessory };
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Opens a file descriptor for reading and writing data to the USB accessory.
     *
     * <p>If data is read from the {@link java.io.InputStream} created from this file descriptor all
     * data of a USB transfer should be read at once. If only a partial request is read the rest of
     * the transfer is dropped.
     *
     * @param accessory the USB accessory to open
     * @return file descriptor, or null if the accessory could not be opened.
     */
    @RequiresFeature(PackageManager.FEATURE_USB_ACCESSORY)
    public ParcelFileDescriptor openAccessory(UsbAccessory accessory) {
        try {
            return mService.openAccessory(accessory);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the functionfs control file descriptor for the given function, with
     * the usb descriptors and strings already written. The file descriptor is used
     * by the function implementation to handle events and control requests.
     *
     * @param function to get control fd for. Currently {@link #FUNCTION_MTP} and
     * {@link #FUNCTION_PTP} are supported.
     * @return A ParcelFileDescriptor holding the valid fd, or null if the fd was not found.
     *
     * @hide
     */
    public ParcelFileDescriptor getControlFd(long function) {
        try {
            return mService.getControlFd(function);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns true if the caller has permission to access the device.
     * Permission might have been granted temporarily via
     * {@link #requestPermission(UsbDevice, PendingIntent)} or
     * by the user choosing the caller as the default application for the device.
     * Permission for USB devices of class {@link UsbConstants#USB_CLASS_VIDEO} for clients that
     * target SDK {@link android.os.Build.VERSION_CODES#P} and above can be granted only if they
     * have additionally the {@link android.Manifest.permission#CAMERA} permission.
     *
     * @param device to check permissions for
     * @return true if caller has permission
     */
    @RequiresFeature(PackageManager.FEATURE_USB_HOST)
    public boolean hasPermission(UsbDevice device) {
        if (mService == null) {
            return false;
        }
        try {
            return mService.hasDevicePermission(device, mContext.getPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns true if the caller has permission to access the device. It's similar to the
     * {@link #hasPermission(UsbDevice)} but allows to specify a different package/uid/pid.
     *
     * <p>Not for third-party apps.</p>
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    @RequiresFeature(PackageManager.FEATURE_USB_HOST)
    public boolean hasPermission(@NonNull UsbDevice device, @NonNull String packageName,
            int pid, int uid) {
        if (mService == null) {
            return false;
        }
        try {
            return mService.hasDevicePermissionWithIdentity(device, packageName, pid, uid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns true if the caller has permission to access the accessory.
     * Permission might have been granted temporarily via
     * {@link #requestPermission(UsbAccessory, PendingIntent)} or
     * by the user choosing the caller as the default application for the accessory.
     *
     * @param accessory to check permissions for
     * @return true if caller has permission
     */
    @RequiresFeature(PackageManager.FEATURE_USB_ACCESSORY)
    public boolean hasPermission(UsbAccessory accessory) {
        if (mService == null) {
            return false;
        }
        try {
            return mService.hasAccessoryPermission(accessory);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns true if the caller has permission to access the accessory. It's similar to the
     * {@link #hasPermission(UsbAccessory)} but allows to specify a different uid/pid.
     *
     * <p>Not for third-party apps.</p>
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    @RequiresFeature(PackageManager.FEATURE_USB_ACCESSORY)
    public boolean hasPermission(@NonNull UsbAccessory accessory, int pid, int uid) {
        if (mService == null) {
            return false;
        }
        try {
            return mService.hasAccessoryPermissionWithIdentity(accessory, pid, uid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Requests temporary permission for the given package to access the device.
     * This may result in a system dialog being displayed to the user
     * if permission had not already been granted.
     * Success or failure is returned via the {@link android.app.PendingIntent} pi.
     * If successful, this grants the caller permission to access the device only
     * until the device is disconnected.
     *
     * The following extras will be added to pi:
     * <ul>
     * <li> {@link #EXTRA_DEVICE} containing the device passed into this call
     * <li> {@link #EXTRA_PERMISSION_GRANTED} containing boolean indicating whether
     * permission was granted by the user
     * </ul>
     *
     * Permission for USB devices of class {@link UsbConstants#USB_CLASS_VIDEO} for clients that
     * target SDK {@link android.os.Build.VERSION_CODES#P} and above can be granted only if they
     * have additionally the {@link android.Manifest.permission#CAMERA} permission.
     *
     * @param device to request permissions for
     * @param pi PendingIntent for returning result
     */
    @RequiresFeature(PackageManager.FEATURE_USB_HOST)
    public void requestPermission(UsbDevice device, PendingIntent pi) {
        try {
            mService.requestDevicePermission(device, mContext.getPackageName(), pi);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Requests temporary permission for the given package to access the accessory.
     * This may result in a system dialog being displayed to the user
     * if permission had not already been granted.
     * Success or failure is returned via the {@link android.app.PendingIntent} pi.
     * If successful, this grants the caller permission to access the accessory only
     * until the device is disconnected.
     *
     * The following extras will be added to pi:
     * <ul>
     * <li> {@link #EXTRA_ACCESSORY} containing the accessory passed into this call
     * <li> {@link #EXTRA_PERMISSION_GRANTED} containing boolean indicating whether
     * permission was granted by the user
     * </ul>
     *
     * @param accessory to request permissions for
     * @param pi PendingIntent for returning result
     */
    @RequiresFeature(PackageManager.FEATURE_USB_ACCESSORY)
    public void requestPermission(UsbAccessory accessory, PendingIntent pi) {
        try {
            mService.requestAccessoryPermission(accessory, mContext.getPackageName(), pi);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Grants permission for USB device without showing system dialog.
     * Only system components can call this function.
     * @param device to request permissions for
     *
     * @hide
     */
    public void grantPermission(UsbDevice device) {
        grantPermission(device, Process.myUid());
    }

    /**
     * Grants permission for USB device to given uid without showing system dialog.
     * Only system components can call this function.
     * @param device to request permissions for
     * @uid uid to give permission
     *
     * @hide
     */
    public void grantPermission(UsbDevice device, int uid) {
        try {
            mService.grantDevicePermission(device, uid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Grants permission to specified package for USB device without showing system dialog.
     * Only system components can call this function, as it requires the MANAGE_USB permission.
     * @param device to request permissions for
     * @param packageName of package to grant permissions
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    public void grantPermission(UsbDevice device, String packageName) {
        try {
            int uid = mContext.getPackageManager()
                .getPackageUidAsUser(packageName, mContext.getUserId());
            grantPermission(device, uid);
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Package " + packageName + " not found.", e);
        }
    }

    /**
     * Returns true if the specified USB function is currently enabled when in device mode.
     * <p>
     * USB functions represent interfaces which are published to the host to access
     * services offered by the device.
     * </p>
     *
     * @deprecated use getCurrentFunctions() instead.
     * @param function name of the USB function
     * @return true if the USB function is enabled
     *
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isFunctionEnabled(String function) {
        try {
            return mService.isFunctionEnabled(function);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the current USB functions when in device mode.
     * <p>
     * USB functions represent interfaces which are published to the host to access
     * services offered by the device.
     * </p><p>
     * This method is intended to select among primary USB functions.  The system may
     * automatically activate additional functions such as {@link #USB_FUNCTION_ADB}
     * or {@link #USB_FUNCTION_ACCESSORY} based on other settings and states.
     * </p><p>
     * An argument of 0 indicates that the device is charging, and can pick any
     * appropriate function for that purpose.
     * </p><p>
     * Note: This function is asynchronous and may fail silently without applying
     * the requested changes.
     * </p>
     *
     * @param functions the USB function(s) to set, as a bitwise mask.
     *                  Must satisfy {@link UsbManager#areSettableFunctions}
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    public void setCurrentFunctions(@UsbFunctionMode long functions) {
        int operationId = sUsbOperationCount.incrementAndGet() + Binder.getCallingUid();
        try {
            mService.setCurrentFunctions(functions, operationId);
        } catch (RemoteException e) {
            Log.e(TAG, "setCurrentFunctions: failed to call setCurrentFunctions. functions:"
                        + functions + ", opId:" + operationId, e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the current USB functions when in device mode.
     *
     * @deprecated use setCurrentFunctions(long) instead.
     * @param functions the USB function(s) to set.
     * @param usbDataUnlocked unused

     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage
    public void setCurrentFunction(String functions, boolean usbDataUnlocked) {
        int operationId = sUsbOperationCount.incrementAndGet() + Binder.getCallingUid();
        try {
            mService.setCurrentFunction(functions, usbDataUnlocked, operationId);
        } catch (RemoteException e) {
            Log.e(TAG, "setCurrentFunction: failed to call setCurrentFunction. functions:"
                        + functions + ", opId:" + operationId, e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the current USB functions in device mode.
     * <p>
     * This function returns the state of primary USB functions and can return a
     * mask containing any usb function(s) except for ADB.
     * </p>
     *
     * @return The currently enabled functions, in a bitwise mask.
     * A zero mask indicates that the current function is the charging function.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    public long getCurrentFunctions() {
        try {
            return mService.getCurrentFunctions();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the screen unlocked functions, which are persisted and set as the current functions
     * whenever the screen is unlocked.
     * <p>
     * A zero mask has the effect of switching off this feature, so functions
     * no longer change on screen unlock.
     * </p><p>
     * Note: When the screen is on, this method will apply given functions as current functions,
     * which is asynchronous and may fail silently without applying the requested changes.
     * </p>
     *
     * @param functions functions to set, in a bitwise mask.
     *                  Must satisfy {@link UsbManager#areSettableFunctions}
     *
     * @hide
     */
    public void setScreenUnlockedFunctions(long functions) {
        try {
            mService.setScreenUnlockedFunctions(functions);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the current screen unlocked functions.
     *
     * @return The currently set screen enabled functions.
     * A zero mask indicates that the screen unlocked functions feature is not enabled.
     *
     * @hide
     */
    public long getScreenUnlockedFunctions() {
        try {
            return mService.getScreenUnlockedFunctions();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the Current USB Bandwidth.
     * <p>
     * This function returns the current USB bandwidth through USB Gadget HAL.
     * It should be used when Android device is in USB peripheral mode and
     * connects to a USB host. If USB state is not configued, API will return
     * {@value #USB_DATA_TRANSFER_RATE_UNKNOWN}. In addition, the unit of the
     * return value is Mbps.
     * </p>
     *
     * @return The value of currently USB Bandwidth.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    public int getUsbBandwidthMbps() {
        int usbSpeed;
        try {
            usbSpeed = mService.getCurrentUsbSpeed();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return usbSpeedToBandwidth(usbSpeed);
    }

    /**
     * Get the Current Gadget Hal Version.
     * <p>
     * This function returns the current Gadget Hal Version.
     * </p>
     *
     * @return a integer {@code GADGET_HAL_*} represent hal version.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    public @UsbGadgetHalVersion int getGadgetHalVersion() {
        try {
            return mService.getGadgetHalVersion();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the Current USB Hal Version.
     * <p>
     * This function returns the current USB Hal Version.
     * </p>
     *
     * @return a integer {@code USB_HAL_*} represent hal version.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    public @UsbHalVersion int getUsbHalVersion() {
        try {
            return mService.getUsbHalVersion();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Resets the USB Gadget.
     * <p>
     * Performs USB data stack reset through USB Gadget HAL.
     * It will force USB data connection reset. The connection will disconnect and reconnect.
     * </p>
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    public void resetUsbGadget() {
        try {
            mService.resetUsbGadget();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether UVC is advertised to be supported or not. SELinux
     * enforces that this function returns {@code false} when called from a
     * process that doesn't belong either to a system app, or the
     * DeviceAsWebcam Service.
     *
     * @return true if UVC is supported, false if UVC is not supported or if
     *         called from a non-system app that isn't DeviceAsWebcam Service.
     * @hide
     */
    @SystemApi
    public static boolean isUvcSupportEnabled() {
        return SystemProperties.getBoolean("ro.usb.uvc.enabled", false);
    }

    /**
     * Enable/Disable the USB data signaling.
     * <p>
     * Enables/Disables USB data path of all USB ports.
     * It will force to stop or restore USB data signaling.
     * </p>
     *
     * @param enable enable or disable USB data signaling
     * @return true enable or disable USB data successfully
     *         false if something wrong
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    public boolean enableUsbDataSignal(boolean enable) {
        return setUsbDataSignal(getPorts(), !enable, /* revertOnFailure= */ true);
    }

    private boolean setUsbDataSignal(List<UsbPort> usbPorts, boolean disable,
            boolean revertOnFailure) {
        List<UsbPort> changedPorts = new ArrayList<>();
        for (int i = 0; i < usbPorts.size(); i++) {
            UsbPort port = usbPorts.get(i);
            if (isPortDisabled(port) != disable) {
                changedPorts.add(port);
                if (port.enableUsbData(!disable) != UsbPort.ENABLE_USB_DATA_SUCCESS
                        && revertOnFailure) {
                    Log.e(TAG, "Failed to set usb data signal for portID(" + port.getId() + ")");
                    setUsbDataSignal(changedPorts, !disable, /* revertOnFailure= */ false);
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isPortDisabled(UsbPort usbPort) {
        return (getPortStatus(usbPort).getUsbDataStatus() & DATA_STATUS_DISABLED_FORCE)
                == DATA_STATUS_DISABLED_FORCE;
    }

    /**
     * Returns a list of physical USB ports on the device.
     * <p>
     * This list is guaranteed to contain all dual-role USB Type C ports but it might
     * be missing other ports depending on whether the kernel USB drivers have been
     * updated to publish all of the device's ports through the new "dual_role_usb"
     * device class (which supports all types of ports despite its name).
     * </p>
     *
     * @return The list of USB ports
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    public @NonNull List<UsbPort> getPorts() {
        if (mService == null) {
            return Collections.emptyList();
        }

        List<ParcelableUsbPort> parcelablePorts;
        try {
            parcelablePorts = mService.getPorts();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        if (parcelablePorts == null) {
            return Collections.emptyList();
        } else {
            int numPorts = parcelablePorts.size();

            ArrayList<UsbPort> ports = new ArrayList<>(numPorts);
            for (int i = 0; i < numPorts; i++) {
                ports.add(parcelablePorts.get(i).getUsbPort(this));
            }

            return ports;
        }
    }

    /**
     * Should only be called by {@link UsbPort#getStatus}.
     *
     * @hide
     */
    UsbPortStatus getPortStatus(UsbPort port) {
        try {
            return mService.getPortStatus(port.getId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Should only be called by {@link UsbPort#setRoles}.
     *
     * @hide
     */
    void setPortRoles(UsbPort port, int powerRole, int dataRole) {
        Log.d(TAG, "setPortRoles Package:" + mContext.getPackageName());
        try {
            mService.setPortRoles(port.getId(), powerRole, dataRole);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Enables USB port contaminant detection algorithm.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    void enableContaminantDetection(@NonNull UsbPort port, boolean enable) {
        try {
            mService.enableContaminantDetection(port.getId(), enable);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Should only be called by {@link UsbPort#enableLimitPowerTransfer}.
     * <p>
     * limits or restores power transfer in and out of USB port.
     *
     * @param port USB port for which power transfer has to be limited or restored.
     * @param limit limit power transfer when true.
     *              relax power transfer restrictions when false.
     * @param operationId operationId for the request.
     * @param callback callback object to be invoked when the operation is complete.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    void enableLimitPowerTransfer(@NonNull UsbPort port, boolean limit, int operationId,
            IUsbOperationInternal callback) {
        Objects.requireNonNull(port, "enableLimitPowerTransfer:port must not be null. opId:"
                + operationId);
        try {
            mService.enableLimitPowerTransfer(port.getId(), limit, operationId, callback);
        } catch (RemoteException e) {
            Log.e(TAG, "enableLimitPowerTransfer failed. opId:" + operationId, e);
            try {
                callback.onOperationComplete(UsbOperationInternal.USB_OPERATION_ERROR_INTERNAL);
            } catch (RemoteException r) {
                Log.e(TAG, "enableLimitPowerTransfer failed to call onOperationComplete. opId:"
                        + operationId, r);
            }
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Should only be called by {@link UsbPort#resetUsbPort}.
     * <p>
     * Disable and then re-enable USB data signaling.
     *
     * Reset USB first port..
     * It will force to stop and restart USB data signaling.
     * Call UsbPort API if the device has more than one UsbPort.
     * </p>
     *
     * @param port reset the USB Port
     * @return true enable or disable USB data successfully
     *         false if something wrong
     *
     * Should only be called by {@link UsbPort#resetUsbPort}.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    void resetUsbPort(@NonNull UsbPort port, int operationId,
            IUsbOperationInternal callback) {
        Objects.requireNonNull(port, "resetUsbPort: port must not be null. opId:" + operationId);
        try {
            mService.resetUsbPort(port.getId(), operationId, callback);
        } catch (RemoteException e) {
            Log.e(TAG, "resetUsbPort: failed. ", e);
            try {
                callback.onOperationComplete(UsbOperationInternal.USB_OPERATION_ERROR_INTERNAL);
            } catch (RemoteException r) {
                Log.e(TAG, "resetUsbPort: failed to call onOperationComplete. opId:"
                        + operationId, r);
            }
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Should only be called by {@link UsbPort#enableUsbData}.
     * <p>
     * Enables or disables USB data on the specific port.
     *
     * @param port USB port for which USB data needs to be enabled or disabled.
     * @param enable Enable USB data when true.
     *               Disable USB data when false.
     * @param operationId operationId for the request.
     * @param callback callback object to be invoked when the operation is complete.
     * @return True when the operation is asynchronous. The caller must therefore call
     *         {@link UsbOperationInternal#waitForOperationComplete} for processing
     *         the result.
     *         False when the operation is synchronous. Caller can proceed reading the result
     *         through {@link UsbOperationInternal#getStatus}
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    boolean enableUsbData(@NonNull UsbPort port, boolean enable, int operationId,
            IUsbOperationInternal callback) {
        Objects.requireNonNull(port, "enableUsbData: port must not be null. opId:" + operationId);
        try {
            return mService.enableUsbData(port.getId(), enable, operationId, callback);
        } catch (RemoteException e) {
            Log.e(TAG, "enableUsbData: failed. opId:" + operationId, e);
            try {
                callback.onOperationComplete(UsbOperationInternal.USB_OPERATION_ERROR_INTERNAL);
            } catch (RemoteException r) {
                Log.e(TAG, "enableUsbData: failed to call onOperationComplete. opId:"
                        + operationId, r);
            }
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Should only be called by {@link UsbPort#enableUsbDataWhileDocked}.
     * <p>
     * Enables or disables USB data when disabled due to docking event.
     *
     * @param port USB port for which USB data needs to be enabled.
     * @param operationId operationId for the request.
     * @param callback callback object to be invoked when the operation is complete.
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    void enableUsbDataWhileDocked(@NonNull UsbPort port, int operationId,
            IUsbOperationInternal callback) {
        Objects.requireNonNull(port, "enableUsbDataWhileDocked: port must not be null. opId:"
                + operationId);
        try {
            mService.enableUsbDataWhileDocked(port.getId(), operationId, callback);
        } catch (RemoteException e) {
            Log.e(TAG, "enableUsbDataWhileDocked: failed. opId:" + operationId, e);
            try {
                callback.onOperationComplete(UsbOperationInternal.USB_OPERATION_ERROR_INTERNAL);
            } catch (RemoteException r) {
                Log.e(TAG, "enableUsbDataWhileDocked: failed to call onOperationComplete. opId:"
                        + operationId, r);
            }
            throw e.rethrowFromSystemServer();
        }
    }

    @GuardedBy("mDisplayPortListenersLock")
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    private boolean registerDisplayPortAltModeEventsIfNeededLocked() {
        DisplayPortAltModeInfoDispatchingListener displayPortDispatchingListener =
                new DisplayPortAltModeInfoDispatchingListener();
        try {
            if (mService.registerForDisplayPortEvents(displayPortDispatchingListener)) {
                mDisplayPortServiceListener = displayPortDispatchingListener;
                return true;
            }
            return false;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers the given listener to listen for DisplayPort Alt Mode changes.
     * <p>
     * If this method returns without Exceptions, the caller should ensure to call
     * {@link #unregisterDisplayPortAltModeListener} when it no longer requires updates.
     *
     * @param executor          Executor on which to run the listener.
     * @param listener          DisplayPortAltModeInfoListener invoked on DisplayPortAltModeInfo
     *                          changes. See {@link #DisplayPortAltModeInfoListener} for listener
     *                          details.
     *
     * @throws IllegalStateException if listener has already been registered previously but not
     * unregistered or an unexpected system failure occurs.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    public void registerDisplayPortAltModeInfoListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull DisplayPortAltModeInfoListener listener) {
        Objects.requireNonNull(executor, "registerDisplayPortAltModeInfoListener: "
                + "executor must not be null.");
        Objects.requireNonNull(listener, "registerDisplayPortAltModeInfoListener: "
                + "listener must not be null.");

        synchronized (mDisplayPortListenersLock) {
            if (mDisplayPortListeners == null) {
                mDisplayPortListeners = new ArrayMap<DisplayPortAltModeInfoListener,
                        Executor>();
            }

            if (mDisplayPortServiceListener == null) {
                if (!registerDisplayPortAltModeEventsIfNeededLocked()) {
                    throw new IllegalStateException("Unexpected failure registering service "
                            + "listener");
                }
            }
            if (mDisplayPortListeners.containsKey(listener)) {
                throw new IllegalStateException("Listener has already been registered.");
            }

            mDisplayPortListeners.put(listener, executor);
        }
    }

    @GuardedBy("mDisplayPortListenersLock")
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    private void unregisterDisplayPortAltModeEventsLocked() {
        if (mDisplayPortServiceListener != null) {
            try {
                mService.unregisterForDisplayPortEvents(mDisplayPortServiceListener);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            } finally {
                // If there was a RemoteException, the system server may have died,
                // and this listener probably became unregistered, so clear it for re-registration.
                mDisplayPortServiceListener = null;
            }
        }
    }

    /**
     * Unregisters the given listener if it was previously passed to
     * registerDisplayPortAltModeInfoListener.
     *
     * @param listener          DisplayPortAltModeInfoListener used to register the listener
     *                          in registerDisplayPortAltModeInfoListener.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_USB)
    public void unregisterDisplayPortAltModeInfoListener(
            @NonNull DisplayPortAltModeInfoListener listener) {
        synchronized (mDisplayPortListenersLock) {
            if (mDisplayPortListeners == null) {
                return;
            }
            mDisplayPortListeners.remove(listener);
            if (mDisplayPortListeners.isEmpty()) {
                unregisterDisplayPortAltModeEventsLocked();
            }
        }
        return;
    }

    /**
     * Sets the component that will handle USB device connection.
     * <p>
     * Setting component allows to specify external USB host manager to handle use cases, where
     * selection dialog for an activity that will handle USB device is undesirable.
     * Only system components can call this function, as it requires the MANAGE_USB permission.
     *
     * @param usbDeviceConnectionHandler The component to handle usb connections,
     * {@code null} to unset.
     *
     * @hide
     */
    public void setUsbDeviceConnectionHandler(@Nullable ComponentName usbDeviceConnectionHandler) {
        try {
            mService.setUsbDeviceConnectionHandler(usbDeviceConnectionHandler);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the given functions are valid inputs to UsbManager.
     * Currently the empty functions or any of MTP, PTP, RNDIS, MIDI, NCM, UVC are accepted.
     *
     * Only one function may be set at a time, except for RNDIS and NCM, which can be set together
     * because from a user perspective they are the same function (tethering).
     *
     * @return Whether the mask is settable.
     *
     * @hide
     */
    public static boolean areSettableFunctions(long functions) {
        return functions == FUNCTION_NONE
                || ((~SETTABLE_FUNCTIONS & functions) == 0
                        && ((Long.bitCount(functions) == 1)
                                || (functions == (FUNCTION_RNDIS | FUNCTION_NCM))));
    }

    /**
     * Converts the given function mask to string. Maintains ordering with respect to init scripts.
     *
     * @return String representation of given mask
     *
     * @hide
     */
    public static String usbFunctionsToString(long functions) {
        StringJoiner joiner = new StringJoiner(",");
        if ((functions & FUNCTION_MTP) != 0) {
            joiner.add(UsbManager.USB_FUNCTION_MTP);
        }
        if ((functions & FUNCTION_PTP) != 0) {
            joiner.add(UsbManager.USB_FUNCTION_PTP);
        }
        if ((functions & FUNCTION_RNDIS) != 0) {
            joiner.add(UsbManager.USB_FUNCTION_RNDIS);
        }
        if ((functions & FUNCTION_MIDI) != 0) {
            joiner.add(UsbManager.USB_FUNCTION_MIDI);
        }
        if ((functions & FUNCTION_ACCESSORY) != 0) {
            joiner.add(UsbManager.USB_FUNCTION_ACCESSORY);
        }
        if ((functions & FUNCTION_AUDIO_SOURCE) != 0) {
            joiner.add(UsbManager.USB_FUNCTION_AUDIO_SOURCE);
        }
        if ((functions & FUNCTION_NCM) != 0) {
            joiner.add(UsbManager.USB_FUNCTION_NCM);
        }
        if ((functions & FUNCTION_UVC) != 0) {
            joiner.add(UsbManager.USB_FUNCTION_UVC);
        }
        if ((functions & FUNCTION_ADB) != 0) {
            joiner.add(UsbManager.USB_FUNCTION_ADB);
        }
        return joiner.toString();
    }

    /**
     * Parses a string of usb functions that are comma separated.
     *
     * @return A mask of all valid functions in the string
     *
     * @hide
     */
    public static long usbFunctionsFromString(String functions) {
        if (functions == null || functions.equals(USB_FUNCTION_NONE)) {
            return FUNCTION_NONE;
        }
        long ret = 0;
        for (String function : functions.split(",")) {
            if (FUNCTION_NAME_TO_CODE.containsKey(function)) {
                ret |= FUNCTION_NAME_TO_CODE.get(function);
            } else if (function.length() > 0) {
                throw new IllegalArgumentException("Invalid usb function " + functions);
            }
        }
        return ret;
    }

    /**
     * Converts the given integer of USB speed to corresponding bandwidth.
     *
     * @return a value of USB bandwidth
     *
     * @hide
     */
    public static int usbSpeedToBandwidth(int speed) {
        switch (speed) {
            case UsbSpeed.USB4_GEN3_40Gb:
                return USB_DATA_TRANSFER_RATE_40G;
            case UsbSpeed.USB4_GEN3_20Gb:
                return USB_DATA_TRANSFER_RATE_20G;
            case UsbSpeed.USB4_GEN2_20Gb:
                return USB_DATA_TRANSFER_RATE_20G;
            case UsbSpeed.USB4_GEN2_10Gb:
                return USB_DATA_TRANSFER_RATE_10G;
            case UsbSpeed.SUPERSPEED_20Gb:
                return USB_DATA_TRANSFER_RATE_20G;
            case UsbSpeed.SUPERSPEED_10Gb:
                return USB_DATA_TRANSFER_RATE_10G;
            case UsbSpeed.SUPERSPEED:
                return USB_DATA_TRANSFER_RATE_5G;
            case UsbSpeed.HIGHSPEED:
                return USB_DATA_TRANSFER_RATE_HIGH_SPEED;
            case UsbSpeed.FULLSPEED:
                return USB_DATA_TRANSFER_RATE_FULL_SPEED;
            case UsbSpeed.LOWSPEED:
                return USB_DATA_TRANSFER_RATE_LOW_SPEED;
            default:
                return USB_DATA_TRANSFER_RATE_UNKNOWN;
        }
    }

    /**
     * Converts the given usb gadgdet hal version to String
     *
     * @return String representation of Usb Gadget Hal Version
     *
     * @hide
     */
    public static @NonNull String usbGadgetHalVersionToString(int version) {
        String halVersion;

        if (version == GADGET_HAL_V2_0) {
            halVersion = GADGET_HAL_VERSION_2_0;
        } else if (version == GADGET_HAL_V1_2) {
            halVersion = GADGET_HAL_VERSION_1_2;
        } else if (version == GADGET_HAL_V1_1) {
            halVersion = GADGET_HAL_VERSION_1_1;
        } else if (version == GADGET_HAL_V1_0) {
            halVersion = GADGET_HAL_VERSION_1_0;
        } else {
            halVersion = GADGET_HAL_UNKNOWN;
        }

        return halVersion;
    }
}
