/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.hdmi;

import static android.hardware.hdmi.HdmiControlManager.DEVICE_EVENT_ADD_DEVICE;
import static android.hardware.hdmi.HdmiControlManager.DEVICE_EVENT_REMOVE_DEVICE;
import static com.android.server.hdmi.Constants.DISABLED;
import static com.android.server.hdmi.Constants.ENABLED;
import static com.android.server.hdmi.Constants.OPTION_MHL_ENABLE;
import static com.android.server.hdmi.Constants.OPTION_MHL_INPUT_SWITCHING;
import static com.android.server.hdmi.Constants.OPTION_MHL_POWER_CHARGE;
import static com.android.server.hdmi.Constants.OPTION_MHL_SERVICE_CONTROL;

import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiHotplugEvent;
import android.hardware.hdmi.HdmiPortInfo;
import android.hardware.hdmi.IHdmiControlCallback;
import android.hardware.hdmi.IHdmiControlService;
import android.hardware.hdmi.IHdmiDeviceEventListener;
import android.hardware.hdmi.IHdmiHotplugEventListener;
import android.hardware.hdmi.IHdmiInputChangeListener;
import android.hardware.hdmi.IHdmiMhlVendorCommandListener;
import android.hardware.hdmi.IHdmiRecordListener;
import android.hardware.hdmi.IHdmiSystemAudioModeChangeListener;
import android.hardware.hdmi.IHdmiVendorCommandListener;
import android.hardware.tv.cec.V1_0.OptionKey;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.media.AudioManager;
import android.media.tv.TvInputManager;
import android.media.tv.TvInputManager.TvInputCallback;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings.Global;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.SystemService;
import com.android.server.hdmi.HdmiAnnotations.ServiceThreadOnly;
import com.android.server.hdmi.HdmiCecController.AllocateAddressCallback;
import com.android.server.hdmi.HdmiCecLocalDevice.ActiveSource;
import com.android.server.hdmi.HdmiCecLocalDevice.PendingActionClearedCallback;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import libcore.util.EmptyArray;

/**
 * Provides a service for sending and processing HDMI control messages,
 * HDMI-CEC and MHL control command, and providing the information on both standard.
 */
public final class HdmiControlService extends SystemService {
    private static final String TAG = "HdmiControlService";
    private final Locale HONG_KONG = new Locale("zh", "HK");
    private final Locale MACAU = new Locale("zh", "MO");

    static final String PERMISSION = "android.permission.HDMI_CEC";

    // The reason code to initiate intializeCec().
    static final int INITIATED_BY_ENABLE_CEC = 0;
    static final int INITIATED_BY_BOOT_UP = 1;
    static final int INITIATED_BY_SCREEN_ON = 2;
    static final int INITIATED_BY_WAKE_UP_MESSAGE = 3;
    static final int INITIATED_BY_HOTPLUG = 4;

    // The reason code representing the intent action that drives the standby
    // procedure. The procedure starts either by Intent.ACTION_SCREEN_OFF or
    // Intent.ACTION_SHUTDOWN.
    static final int STANDBY_SCREEN_OFF = 0;
    static final int STANDBY_SHUTDOWN = 1;

    /**
     * Interface to report send result.
     */
    interface SendMessageCallback {
        /**
         * Called when {@link HdmiControlService#sendCecCommand} is completed.
         *
         * @param error result of send request.
         * <ul>
         * <li>{@link SendMessageResult#SUCCESS}
         * <li>{@link SendMessageResult#NACK}
         * <li>{@link SendMessageResult#BUSY}
         * <li>{@link SendMessageResult#FAIL}
         * </ul>
         */
        void onSendCompleted(int error);
    }

    /**
     * Interface to get a list of available logical devices.
     */
    interface DevicePollingCallback {
        /**
         * Called when device polling is finished.
         *
         * @param ackedAddress a list of logical addresses of available devices
         */
        void onPollingFinished(List<Integer> ackedAddress);
    }

    private class HdmiControlBroadcastReceiver extends BroadcastReceiver {
        @ServiceThreadOnly
        @Override
        public void onReceive(Context context, Intent intent) {
            assertRunOnServiceThread();
            switch (intent.getAction()) {
                case Intent.ACTION_SCREEN_OFF:
                    if (isPowerOnOrTransient()) {
                        onStandby(STANDBY_SCREEN_OFF);
                    }
                    break;
                case Intent.ACTION_SCREEN_ON:
                    if (isPowerStandbyOrTransient()) {
                        onWakeUp();
                    }
                    break;
                case Intent.ACTION_CONFIGURATION_CHANGED:
                    String language = getMenuLanguage();
                    if (!mLanguage.equals(language)) {
                        onLanguageChanged(language);
                    }
                    break;
                case Intent.ACTION_SHUTDOWN:
                    if (isPowerOnOrTransient()) {
                        onStandby(STANDBY_SHUTDOWN);
                    }
                    break;
            }
        }

        private String getMenuLanguage() {
            Locale locale = Locale.getDefault();
            if (locale.equals(Locale.TAIWAN) || locale.equals(HONG_KONG) || locale.equals(MACAU)) {
                // Android always returns "zho" for all Chinese variants.
                // Use "bibliographic" code defined in CEC639-2 for traditional
                // Chinese used in Taiwan/Hong Kong/Macau.
                return "chi";
            } else {
                return locale.getISO3Language();
            }
        }
    }

    // A thread to handle synchronous IO of CEC and MHL control service.
    // Since all of CEC and MHL HAL interfaces processed in short time (< 200ms)
    // and sparse call it shares a thread to handle IO operations.
    private final HandlerThread mIoThread = new HandlerThread("Hdmi Control Io Thread");

    // Used to synchronize the access to the service.
    private final Object mLock = new Object();

    // Type of logical devices hosted in the system. Stored in the unmodifiable list.
    private final List<Integer> mLocalDevices;

    // List of records for hotplug event listener to handle the the caller killed in action.
    @GuardedBy("mLock")
    private final ArrayList<HotplugEventListenerRecord> mHotplugEventListenerRecords =
            new ArrayList<>();

    // List of records for device event listener to handle the caller killed in action.
    @GuardedBy("mLock")
    private final ArrayList<DeviceEventListenerRecord> mDeviceEventListenerRecords =
            new ArrayList<>();

    // List of records for vendor command listener to handle the caller killed in action.
    @GuardedBy("mLock")
    private final ArrayList<VendorCommandListenerRecord> mVendorCommandListenerRecords =
            new ArrayList<>();

    @GuardedBy("mLock")
    private InputChangeListenerRecord mInputChangeListenerRecord;

    @GuardedBy("mLock")
    private HdmiRecordListenerRecord mRecordListenerRecord;

    // Set to true while HDMI control is enabled. If set to false, HDMI-CEC/MHL protocol
    // handling will be disabled and no request will be handled.
    @GuardedBy("mLock")
    private boolean mHdmiControlEnabled;

    // Set to true while the service is in normal mode. While set to false, no input change is
    // allowed. Used for situations where input change can confuse users such as channel auto-scan,
    // system upgrade, etc., a.k.a. "prohibit mode".
    @GuardedBy("mLock")
    private boolean mProhibitMode;

    // List of records for system audio mode change to handle the the caller killed in action.
    private final ArrayList<SystemAudioModeChangeListenerRecord>
            mSystemAudioModeChangeListenerRecords = new ArrayList<>();

    // Handler used to run a task in service thread.
    private final Handler mHandler = new Handler();

    private final SettingsObserver mSettingsObserver;

    private final HdmiControlBroadcastReceiver
            mHdmiControlBroadcastReceiver = new HdmiControlBroadcastReceiver();

    @Nullable
    private HdmiCecController mCecController;

    // HDMI port information. Stored in the unmodifiable list to keep the static information
    // from being modified.
    private List<HdmiPortInfo> mPortInfo;

    // Map from path(physical address) to port ID.
    private UnmodifiableSparseIntArray mPortIdMap;

    // Map from port ID to HdmiPortInfo.
    private UnmodifiableSparseArray<HdmiPortInfo> mPortInfoMap;

    // Map from port ID to HdmiDeviceInfo.
    private UnmodifiableSparseArray<HdmiDeviceInfo> mPortDeviceMap;

    private HdmiCecMessageValidator mMessageValidator;

    @ServiceThreadOnly
    private int mPowerStatus = HdmiControlManager.POWER_STATUS_STANDBY;

    @ServiceThreadOnly
    private String mLanguage = Locale.getDefault().getISO3Language();

    @ServiceThreadOnly
    private boolean mStandbyMessageReceived = false;

    @ServiceThreadOnly
    private boolean mWakeUpMessageReceived = false;

    @ServiceThreadOnly
    private int mActivePortId = Constants.INVALID_PORT_ID;

    // Set to true while the input change by MHL is allowed.
    @GuardedBy("mLock")
    private boolean mMhlInputChangeEnabled;

    // List of records for MHL Vendor command listener to handle the caller killed in action.
    @GuardedBy("mLock")
    private final ArrayList<HdmiMhlVendorCommandListenerRecord>
            mMhlVendorCommandListenerRecords = new ArrayList<>();

    @GuardedBy("mLock")
    private List<HdmiDeviceInfo> mMhlDevices;

    @Nullable
    private HdmiMhlControllerStub mMhlController;

    @Nullable
    private TvInputManager mTvInputManager;

    @Nullable
    private PowerManager mPowerManager;

    // Last input port before switching to the MHL port. Should switch back to this port
    // when the mobile device sends the request one touch play with off.
    // Gets invalidated if we go to other port/input.
    @ServiceThreadOnly
    private int mLastInputMhl = Constants.INVALID_PORT_ID;

    // Set to true if the logical address allocation is completed.
    private boolean mAddressAllocated = false;

    // Buffer for processing the incoming cec messages while allocating logical addresses.
    private final class CecMessageBuffer {
        private List<HdmiCecMessage> mBuffer = new ArrayList<>();

        public void bufferMessage(HdmiCecMessage message) {
            switch (message.getOpcode()) {
                case Constants.MESSAGE_ACTIVE_SOURCE:
                    bufferActiveSource(message);
                    break;
                case Constants.MESSAGE_IMAGE_VIEW_ON:
                case Constants.MESSAGE_TEXT_VIEW_ON:
                    bufferImageOrTextViewOn(message);
                    break;
                    // Add here if new message that needs to buffer
                default:
                    // Do not need to buffer messages other than above
                    break;
            }
        }

        public void processMessages() {
            for (final HdmiCecMessage message : mBuffer) {
                runOnServiceThread(new Runnable() {
                    @Override
                    public void run() {
                        handleCecCommand(message);
                    }
                });
            }
            mBuffer.clear();
        }

        private void bufferActiveSource(HdmiCecMessage message) {
            if (!replaceMessageIfBuffered(message, Constants.MESSAGE_ACTIVE_SOURCE)) {
                mBuffer.add(message);
            }
        }

        private void bufferImageOrTextViewOn(HdmiCecMessage message) {
            if (!replaceMessageIfBuffered(message, Constants.MESSAGE_IMAGE_VIEW_ON) &&
                !replaceMessageIfBuffered(message, Constants.MESSAGE_TEXT_VIEW_ON)) {
                mBuffer.add(message);
            }
        }

        // Returns true if the message is replaced
        private boolean replaceMessageIfBuffered(HdmiCecMessage message, int opcode) {
            for (int i = 0; i < mBuffer.size(); i++) {
                HdmiCecMessage bufferedMessage = mBuffer.get(i);
                if (bufferedMessage.getOpcode() == opcode) {
                    mBuffer.set(i, message);
                    return true;
                }
            }
            return false;
        }
    }

    private final CecMessageBuffer mCecMessageBuffer = new CecMessageBuffer();

    private final SelectRequestBuffer mSelectRequestBuffer = new SelectRequestBuffer();

    public HdmiControlService(Context context) {
        super(context);
        mLocalDevices = getIntList(SystemProperties.get(Constants.PROPERTY_DEVICE_TYPE));
        mSettingsObserver = new SettingsObserver(mHandler);
    }

    private static List<Integer> getIntList(String string) {
        ArrayList<Integer> list = new ArrayList<>();
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(',');
        splitter.setString(string);
        for (String item : splitter) {
            try {
                list.add(Integer.parseInt(item));
            } catch (NumberFormatException e) {
                Slog.w(TAG, "Can't parseInt: " + item);
            }
        }
        return Collections.unmodifiableList(list);
    }

    @Override
    public void onStart() {
        mIoThread.start();
        mPowerStatus = HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON;
        mProhibitMode = false;
        mHdmiControlEnabled = readBooleanSetting(Global.HDMI_CONTROL_ENABLED, true);
        mMhlInputChangeEnabled = readBooleanSetting(Global.MHL_INPUT_SWITCHING_ENABLED, true);

        mCecController = HdmiCecController.create(this);
        if (mCecController != null) {
            if (mHdmiControlEnabled) {
                initializeCec(INITIATED_BY_BOOT_UP);
            }
        } else {
            Slog.i(TAG, "Device does not support HDMI-CEC.");
            return;
        }

        mMhlController = HdmiMhlControllerStub.create(this);
        if (!mMhlController.isReady()) {
            Slog.i(TAG, "Device does not support MHL-control.");
        }
        mMhlDevices = Collections.emptyList();

        initPortInfo();
        mMessageValidator = new HdmiCecMessageValidator(this);
        publishBinderService(Context.HDMI_CONTROL_SERVICE, new BinderService());

        if (mCecController != null) {
            // Register broadcast receiver for power state change.
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SHUTDOWN);
            filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
            getContext().registerReceiver(mHdmiControlBroadcastReceiver, filter);

            // Register ContentObserver to monitor the settings change.
            registerContentObserver();
        }
        mMhlController.setOption(OPTION_MHL_SERVICE_CONTROL, ENABLED);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            mTvInputManager = (TvInputManager) getContext().getSystemService(
                    Context.TV_INPUT_SERVICE);
            mPowerManager = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
        }
    }

    TvInputManager getTvInputManager() {
        return mTvInputManager;
    }

    void registerTvInputCallback(TvInputCallback callback) {
        if (mTvInputManager == null) return;
        mTvInputManager.registerCallback(callback, mHandler);
    }

    void unregisterTvInputCallback(TvInputCallback callback) {
        if (mTvInputManager == null) return;
        mTvInputManager.unregisterCallback(callback);
    }

    PowerManager getPowerManager() {
        return mPowerManager;
    }

    /**
     * Called when the initialization of local devices is complete.
     */
    private void onInitializeCecComplete(int initiatedBy) {
        if (mPowerStatus == HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON) {
            mPowerStatus = HdmiControlManager.POWER_STATUS_ON;
        }
        mWakeUpMessageReceived = false;

        if (isTvDeviceEnabled()) {
            mCecController.setOption(OptionKey.WAKEUP, tv().getAutoWakeup());
        }
        int reason = -1;
        switch (initiatedBy) {
            case INITIATED_BY_BOOT_UP:
                reason = HdmiControlManager.CONTROL_STATE_CHANGED_REASON_START;
                break;
            case INITIATED_BY_ENABLE_CEC:
                reason = HdmiControlManager.CONTROL_STATE_CHANGED_REASON_SETTING;
                break;
            case INITIATED_BY_SCREEN_ON:
            case INITIATED_BY_WAKE_UP_MESSAGE:
                reason = HdmiControlManager.CONTROL_STATE_CHANGED_REASON_WAKEUP;
                break;
        }
        if (reason != -1) {
            invokeVendorCommandListenersOnControlStateChanged(true, reason);
        }
    }

    private void registerContentObserver() {
        ContentResolver resolver = getContext().getContentResolver();
        String[] settings = new String[] {
                Global.HDMI_CONTROL_ENABLED,
                Global.HDMI_CONTROL_AUTO_WAKEUP_ENABLED,
                Global.HDMI_CONTROL_AUTO_DEVICE_OFF_ENABLED,
                Global.HDMI_SYSTEM_AUDIO_CONTROL_ENABLED,
                Global.MHL_INPUT_SWITCHING_ENABLED,
                Global.MHL_POWER_CHARGE_ENABLED
        };
        for (String s : settings) {
            resolver.registerContentObserver(Global.getUriFor(s), false, mSettingsObserver,
                    UserHandle.USER_ALL);
        }
    }

    private class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        // onChange is set up to run in service thread.
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            String option = uri.getLastPathSegment();
            boolean enabled = readBooleanSetting(option, true);
            switch (option) {
                case Global.HDMI_CONTROL_ENABLED:
                    setControlEnabled(enabled);
                    break;
                case Global.HDMI_CONTROL_AUTO_WAKEUP_ENABLED:
                    if (isTvDeviceEnabled()) {
                        tv().setAutoWakeup(enabled);
                    }
                    setCecOption(OptionKey.WAKEUP, enabled);
                    break;
                case Global.HDMI_CONTROL_AUTO_DEVICE_OFF_ENABLED:
                    for (int type : mLocalDevices) {
                        HdmiCecLocalDevice localDevice = mCecController.getLocalDevice(type);
                        if (localDevice != null) {
                            localDevice.setAutoDeviceOff(enabled);
                        }
                    }
                    // No need to propagate to HAL.
                    break;
                case Global.HDMI_SYSTEM_AUDIO_CONTROL_ENABLED:
                    if (isTvDeviceEnabled()) {
                        tv().setSystemAudioControlFeatureEnabled(enabled);
                    }
                    break;
                case Global.MHL_INPUT_SWITCHING_ENABLED:
                    setMhlInputChangeEnabled(enabled);
                    break;
                case Global.MHL_POWER_CHARGE_ENABLED:
                    mMhlController.setOption(OPTION_MHL_POWER_CHARGE, toInt(enabled));
                    break;
            }
        }
    }

    private static int toInt(boolean enabled) {
        return enabled ? ENABLED : DISABLED;
    }

    boolean readBooleanSetting(String key, boolean defVal) {
        ContentResolver cr = getContext().getContentResolver();
        return Global.getInt(cr, key, toInt(defVal)) == ENABLED;
    }

    void writeBooleanSetting(String key, boolean value) {
        ContentResolver cr = getContext().getContentResolver();
        Global.putInt(cr, key, toInt(value));
    }

    private void initializeCec(int initiatedBy) {
        mAddressAllocated = false;
        mCecController.setOption(OptionKey.SYSTEM_CEC_CONTROL, true);
        mCecController.setLanguage(mLanguage);
        initializeLocalDevices(initiatedBy);
    }

    @ServiceThreadOnly
    private void initializeLocalDevices(final int initiatedBy) {
        assertRunOnServiceThread();
        // A container for [Device type, Local device info].
        ArrayList<HdmiCecLocalDevice> localDevices = new ArrayList<>();
        for (int type : mLocalDevices) {
            HdmiCecLocalDevice localDevice = mCecController.getLocalDevice(type);
            if (localDevice == null) {
                localDevice = HdmiCecLocalDevice.create(this, type);
            }
            localDevice.init();
            localDevices.add(localDevice);
        }
        // It's now safe to flush existing local devices from mCecController since they were
        // already moved to 'localDevices'.
        clearLocalDevices();
        allocateLogicalAddress(localDevices, initiatedBy);
    }

    @ServiceThreadOnly
    private void allocateLogicalAddress(final ArrayList<HdmiCecLocalDevice> allocatingDevices,
            final int initiatedBy) {
        assertRunOnServiceThread();
        mCecController.clearLogicalAddress();
        final ArrayList<HdmiCecLocalDevice> allocatedDevices = new ArrayList<>();
        final int[] finished = new int[1];
        mAddressAllocated = allocatingDevices.isEmpty();

        // For TV device, select request can be invoked while address allocation or device
        // discovery is in progress. Initialize the request here at the start of allocation,
        // and process the collected requests later when the allocation and device discovery
        // is all completed.
        mSelectRequestBuffer.clear();

        for (final HdmiCecLocalDevice localDevice : allocatingDevices) {
            mCecController.allocateLogicalAddress(localDevice.getType(),
                    localDevice.getPreferredAddress(), new AllocateAddressCallback() {
                @Override
                public void onAllocated(int deviceType, int logicalAddress) {
                    if (logicalAddress == Constants.ADDR_UNREGISTERED) {
                        Slog.e(TAG, "Failed to allocate address:[device_type:" + deviceType + "]");
                    } else {
                        // Set POWER_STATUS_ON to all local devices because they share lifetime
                        // with system.
                        HdmiDeviceInfo deviceInfo = createDeviceInfo(logicalAddress, deviceType,
                                HdmiControlManager.POWER_STATUS_ON);
                        localDevice.setDeviceInfo(deviceInfo);
                        mCecController.addLocalDevice(deviceType, localDevice);
                        mCecController.addLogicalAddress(logicalAddress);
                        allocatedDevices.add(localDevice);
                    }

                    // Address allocation completed for all devices. Notify each device.
                    if (allocatingDevices.size() == ++finished[0]) {
                        mAddressAllocated = true;
                        if (initiatedBy != INITIATED_BY_HOTPLUG) {
                            // In case of the hotplug we don't call onInitializeCecComplete()
                            // since we reallocate the logical address only.
                            onInitializeCecComplete(initiatedBy);
                        }
                        notifyAddressAllocated(allocatedDevices, initiatedBy);
                        mCecMessageBuffer.processMessages();
                    }
                }
            });
        }
    }

    @ServiceThreadOnly
    private void notifyAddressAllocated(ArrayList<HdmiCecLocalDevice> devices, int initiatedBy) {
        assertRunOnServiceThread();
        for (HdmiCecLocalDevice device : devices) {
            int address = device.getDeviceInfo().getLogicalAddress();
            device.handleAddressAllocated(address, initiatedBy);
        }
        if (isTvDeviceEnabled()) {
            tv().setSelectRequestBuffer(mSelectRequestBuffer);
        }
    }

    boolean isAddressAllocated() {
        return mAddressAllocated;
    }

    // Initialize HDMI port information. Combine the information from CEC and MHL HAL and
    // keep them in one place.
    @ServiceThreadOnly
    private void initPortInfo() {
        assertRunOnServiceThread();
        HdmiPortInfo[] cecPortInfo = null;

        // CEC HAL provides majority of the info while MHL does only MHL support flag for
        // each port. Return empty array if CEC HAL didn't provide the info.
        if (mCecController != null) {
            cecPortInfo = mCecController.getPortInfos();
        }
        if (cecPortInfo == null) {
            return;
        }

        SparseArray<HdmiPortInfo> portInfoMap = new SparseArray<>();
        SparseIntArray portIdMap = new SparseIntArray();
        SparseArray<HdmiDeviceInfo> portDeviceMap = new SparseArray<>();
        for (HdmiPortInfo info : cecPortInfo) {
            portIdMap.put(info.getAddress(), info.getId());
            portInfoMap.put(info.getId(), info);
            portDeviceMap.put(info.getId(), new HdmiDeviceInfo(info.getAddress(), info.getId()));
        }
        mPortIdMap = new UnmodifiableSparseIntArray(portIdMap);
        mPortInfoMap = new UnmodifiableSparseArray<>(portInfoMap);
        mPortDeviceMap = new UnmodifiableSparseArray<>(portDeviceMap);

        HdmiPortInfo[] mhlPortInfo = mMhlController.getPortInfos();
        ArraySet<Integer> mhlSupportedPorts = new ArraySet<Integer>(mhlPortInfo.length);
        for (HdmiPortInfo info : mhlPortInfo) {
            if (info.isMhlSupported()) {
                mhlSupportedPorts.add(info.getId());
            }
        }

        // Build HDMI port info list with CEC port info plus MHL supported flag. We can just use
        // cec port info if we do not have have port that supports MHL.
        if (mhlSupportedPorts.isEmpty()) {
            mPortInfo = Collections.unmodifiableList(Arrays.asList(cecPortInfo));
            return;
        }
        ArrayList<HdmiPortInfo> result = new ArrayList<>(cecPortInfo.length);
        for (HdmiPortInfo info : cecPortInfo) {
            if (mhlSupportedPorts.contains(info.getId())) {
                result.add(new HdmiPortInfo(info.getId(), info.getType(), info.getAddress(),
                        info.isCecSupported(), true, info.isArcSupported()));
            } else {
                result.add(info);
            }
        }
        mPortInfo = Collections.unmodifiableList(result);
    }

    List<HdmiPortInfo> getPortInfo() {
        return mPortInfo;
    }

    /**
     * Returns HDMI port information for the given port id.
     *
     * @param portId HDMI port id
     * @return {@link HdmiPortInfo} for the given port
     */
    HdmiPortInfo getPortInfo(int portId) {
        return mPortInfoMap.get(portId, null);
    }

    /**
     * Returns the routing path (physical address) of the HDMI port for the given
     * port id.
     */
    int portIdToPath(int portId) {
        HdmiPortInfo portInfo = getPortInfo(portId);
        if (portInfo == null) {
            Slog.e(TAG, "Cannot find the port info: " + portId);
            return Constants.INVALID_PHYSICAL_ADDRESS;
        }
        return portInfo.getAddress();
    }

    /**
     * Returns the id of HDMI port located at the top of the hierarchy of
     * the specified routing path. For the routing path 0x1220 (1.2.2.0), for instance,
     * the port id to be returned is the ID associated with the port address
     * 0x1000 (1.0.0.0) which is the topmost path of the given routing path.
     */
    int pathToPortId(int path) {
        int portAddress = path & Constants.ROUTING_PATH_TOP_MASK;
        return mPortIdMap.get(portAddress, Constants.INVALID_PORT_ID);
    }

    boolean isValidPortId(int portId) {
        return getPortInfo(portId) != null;
    }

    /**
     * Returns {@link Looper} for IO operation.
     *
     * <p>Declared as package-private.
     */
    Looper getIoLooper() {
        return mIoThread.getLooper();
    }

    /**
     * Returns {@link Looper} of main thread. Use this {@link Looper} instance
     * for tasks that are running on main service thread.
     *
     * <p>Declared as package-private.
     */
    Looper getServiceLooper() {
        return mHandler.getLooper();
    }

    /**
     * Returns physical address of the device.
     */
    int getPhysicalAddress() {
        return mCecController.getPhysicalAddress();
    }

    /**
     * Returns vendor id of CEC service.
     */
    int getVendorId() {
        return mCecController.getVendorId();
    }

    @ServiceThreadOnly
    HdmiDeviceInfo getDeviceInfo(int logicalAddress) {
        assertRunOnServiceThread();
        return tv() == null ? null : tv().getCecDeviceInfo(logicalAddress);
    }

    @ServiceThreadOnly
    HdmiDeviceInfo getDeviceInfoByPort(int port) {
        assertRunOnServiceThread();
        HdmiMhlLocalDeviceStub info = mMhlController.getLocalDevice(port);
        if (info != null) {
            return info.getInfo();
        }
        return null;
    }

    /**
     * Returns version of CEC.
     */
    int getCecVersion() {
        return mCecController.getVersion();
    }

    /**
     * Whether a device of the specified physical address is connected to ARC enabled port.
     */
    boolean isConnectedToArcPort(int physicalAddress) {
        int portId = pathToPortId(physicalAddress);
        if (portId != Constants.INVALID_PORT_ID) {
            return mPortInfoMap.get(portId).isArcSupported();
        }
        return false;
    }

    @ServiceThreadOnly
    boolean isConnected(int portId) {
        assertRunOnServiceThread();
        return mCecController.isConnected(portId);
    }

    void runOnServiceThread(Runnable runnable) {
        mHandler.post(runnable);
    }

    void runOnServiceThreadAtFrontOfQueue(Runnable runnable) {
        mHandler.postAtFrontOfQueue(runnable);
    }

    private void assertRunOnServiceThread() {
        if (Looper.myLooper() != mHandler.getLooper()) {
            throw new IllegalStateException("Should run on service thread.");
        }
    }

    /**
     * Transmit a CEC command to CEC bus.
     *
     * @param command CEC command to send out
     * @param callback interface used to the result of send command
     */
    @ServiceThreadOnly
    void sendCecCommand(HdmiCecMessage command, @Nullable SendMessageCallback callback) {
        assertRunOnServiceThread();
        if (mMessageValidator.isValid(command) == HdmiCecMessageValidator.OK) {
            mCecController.sendCommand(command, callback);
        } else {
            HdmiLogger.error("Invalid message type:" + command);
            if (callback != null) {
                callback.onSendCompleted(SendMessageResult.FAIL);
            }
        }
    }

    @ServiceThreadOnly
    void sendCecCommand(HdmiCecMessage command) {
        assertRunOnServiceThread();
        sendCecCommand(command, null);
    }

    /**
     * Send <Feature Abort> command on the given CEC message if possible.
     * If the aborted message is invalid, then it wont send the message.
     * @param command original command to be aborted
     * @param reason reason of feature abort
     */
    @ServiceThreadOnly
    void maySendFeatureAbortCommand(HdmiCecMessage command, int reason) {
        assertRunOnServiceThread();
        mCecController.maySendFeatureAbortCommand(command, reason);
    }

    @ServiceThreadOnly
    boolean handleCecCommand(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (!mAddressAllocated) {
            mCecMessageBuffer.bufferMessage(message);
            return true;
        }
        int errorCode = mMessageValidator.isValid(message);
        if (errorCode != HdmiCecMessageValidator.OK) {
            // We'll not response on the messages with the invalid source or destination
            // or with parameter length shorter than specified in the standard.
            if (errorCode == HdmiCecMessageValidator.ERROR_PARAMETER) {
                maySendFeatureAbortCommand(message, Constants.ABORT_INVALID_OPERAND);
            }
            return true;
        }
        return dispatchMessageToLocalDevice(message);
    }

    void enableAudioReturnChannel(int portId, boolean enabled) {
        mCecController.enableAudioReturnChannel(portId, enabled);
    }

    @ServiceThreadOnly
    private boolean dispatchMessageToLocalDevice(HdmiCecMessage message) {
        assertRunOnServiceThread();
        for (HdmiCecLocalDevice device : mCecController.getLocalDeviceList()) {
            if (device.dispatchMessage(message)
                    && message.getDestination() != Constants.ADDR_BROADCAST) {
                return true;
            }
        }

        if (message.getDestination() != Constants.ADDR_BROADCAST) {
            HdmiLogger.warning("Unhandled cec command:" + message);
        }
        return false;
    }

    /**
     * Called when a new hotplug event is issued.
     *
     * @param portId hdmi port number where hot plug event issued.
     * @param connected whether to be plugged in or not
     */
    @ServiceThreadOnly
    void onHotplug(int portId, boolean connected) {
        assertRunOnServiceThread();

        if (connected && !isTvDevice()) {
            ArrayList<HdmiCecLocalDevice> localDevices = new ArrayList<>();
            for (int type : mLocalDevices) {
                HdmiCecLocalDevice localDevice = mCecController.getLocalDevice(type);
                if (localDevice == null) {
                    localDevice = HdmiCecLocalDevice.create(this, type);
                    localDevice.init();
                }
                localDevices.add(localDevice);
            }
            allocateLogicalAddress(localDevices, INITIATED_BY_HOTPLUG);
        }

        for (HdmiCecLocalDevice device : mCecController.getLocalDeviceList()) {
            device.onHotplug(portId, connected);
        }
        announceHotplugEvent(portId, connected);
    }

    /**
     * Poll all remote devices. It sends &lt;Polling Message&gt; to all remote
     * devices.
     *
     * @param callback an interface used to get a list of all remote devices' address
     * @param sourceAddress a logical address of source device where sends polling message
     * @param pickStrategy strategy how to pick polling candidates
     * @param retryCount the number of retry used to send polling message to remote devices
     * @throw IllegalArgumentException if {@code pickStrategy} is invalid value
     */
    @ServiceThreadOnly
    void pollDevices(DevicePollingCallback callback, int sourceAddress, int pickStrategy,
            int retryCount) {
        assertRunOnServiceThread();
        mCecController.pollDevices(callback, sourceAddress, checkPollStrategy(pickStrategy),
                retryCount);
    }

    private int checkPollStrategy(int pickStrategy) {
        int strategy = pickStrategy & Constants.POLL_STRATEGY_MASK;
        if (strategy == 0) {
            throw new IllegalArgumentException("Invalid poll strategy:" + pickStrategy);
        }
        int iterationStrategy = pickStrategy & Constants.POLL_ITERATION_STRATEGY_MASK;
        if (iterationStrategy == 0) {
            throw new IllegalArgumentException("Invalid iteration strategy:" + pickStrategy);
        }
        return strategy | iterationStrategy;
    }

    List<HdmiCecLocalDevice> getAllLocalDevices() {
        assertRunOnServiceThread();
        return mCecController.getLocalDeviceList();
    }

    Object getServiceLock() {
        return mLock;
    }

    void setAudioStatus(boolean mute, int volume) {
        if (!isTvDeviceEnabled() || !tv().isSystemAudioActivated()) {
            return;
        }
        AudioManager audioManager = getAudioManager();
        boolean muted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC);
        if (mute) {
            if (!muted) {
                audioManager.setStreamMute(AudioManager.STREAM_MUSIC, true);
            }
        } else {
            if (muted) {
                audioManager.setStreamMute(AudioManager.STREAM_MUSIC, false);
            }
            // FLAG_HDMI_SYSTEM_AUDIO_VOLUME prevents audio manager from announcing
            // volume change notification back to hdmi control service.
            int flag = AudioManager.FLAG_HDMI_SYSTEM_AUDIO_VOLUME;
            if (0 <= volume && volume <= 100) {
                Slog.i(TAG, "volume: " + volume);
                flag |= AudioManager.FLAG_SHOW_UI;
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, flag);
            }
        }
    }

    void announceSystemAudioModeChange(boolean enabled) {
        synchronized (mLock) {
            for (SystemAudioModeChangeListenerRecord record :
                    mSystemAudioModeChangeListenerRecords) {
                invokeSystemAudioModeChangeLocked(record.mListener, enabled);
            }
        }
    }

    private HdmiDeviceInfo createDeviceInfo(int logicalAddress, int deviceType, int powerStatus) {
        // TODO: find better name instead of model name.
        String displayName = Build.MODEL;
        return new HdmiDeviceInfo(logicalAddress,
                getPhysicalAddress(), pathToPortId(getPhysicalAddress()), deviceType,
                getVendorId(), displayName);
    }

    @ServiceThreadOnly
    void handleMhlHotplugEvent(int portId, boolean connected) {
        assertRunOnServiceThread();
        // Hotplug event is used to add/remove MHL devices as TV input.
        if (connected) {
            HdmiMhlLocalDeviceStub newDevice = new HdmiMhlLocalDeviceStub(this, portId);
            HdmiMhlLocalDeviceStub oldDevice = mMhlController.addLocalDevice(newDevice);
            if (oldDevice != null) {
                oldDevice.onDeviceRemoved();
                Slog.i(TAG, "Old device of port " + portId + " is removed");
            }
            invokeDeviceEventListeners(newDevice.getInfo(), DEVICE_EVENT_ADD_DEVICE);
            updateSafeMhlInput();
        } else {
            HdmiMhlLocalDeviceStub device = mMhlController.removeLocalDevice(portId);
            if (device != null) {
                device.onDeviceRemoved();
                invokeDeviceEventListeners(device.getInfo(), DEVICE_EVENT_REMOVE_DEVICE);
                updateSafeMhlInput();
            } else {
                Slog.w(TAG, "No device to remove:[portId=" + portId);
            }
        }
        announceHotplugEvent(portId, connected);
    }

    @ServiceThreadOnly
    void handleMhlBusModeChanged(int portId, int busmode) {
        assertRunOnServiceThread();
        HdmiMhlLocalDeviceStub device = mMhlController.getLocalDevice(portId);
        if (device != null) {
            device.setBusMode(busmode);
        } else {
            Slog.w(TAG, "No mhl device exists for bus mode change[portId:" + portId +
                    ", busmode:" + busmode + "]");
        }
    }

    @ServiceThreadOnly
    void handleMhlBusOvercurrent(int portId, boolean on) {
        assertRunOnServiceThread();
        HdmiMhlLocalDeviceStub device = mMhlController.getLocalDevice(portId);
        if (device != null) {
            device.onBusOvercurrentDetected(on);
        } else {
            Slog.w(TAG, "No mhl device exists for bus overcurrent event[portId:" + portId + "]");
        }
    }

    @ServiceThreadOnly
    void handleMhlDeviceStatusChanged(int portId, int adopterId, int deviceId) {
        assertRunOnServiceThread();
        HdmiMhlLocalDeviceStub device = mMhlController.getLocalDevice(portId);

        if (device != null) {
            device.setDeviceStatusChange(adopterId, deviceId);
        } else {
            Slog.w(TAG, "No mhl device exists for device status event[portId:"
                    + portId + ", adopterId:" + adopterId + ", deviceId:" + deviceId + "]");
        }
    }

    @ServiceThreadOnly
    private void updateSafeMhlInput() {
        assertRunOnServiceThread();
        List<HdmiDeviceInfo> inputs = Collections.emptyList();
        SparseArray<HdmiMhlLocalDeviceStub> devices = mMhlController.getAllLocalDevices();
        for (int i = 0; i < devices.size(); ++i) {
            HdmiMhlLocalDeviceStub device = devices.valueAt(i);
            HdmiDeviceInfo info = device.getInfo();
            if (info != null) {
                if (inputs.isEmpty()) {
                    inputs = new ArrayList<>();
                }
                inputs.add(device.getInfo());
            }
        }
        synchronized (mLock) {
            mMhlDevices = inputs;
        }
    }

    @GuardedBy("mLock")
    private List<HdmiDeviceInfo> getMhlDevicesLocked() {
        return mMhlDevices;
    }

    private class HdmiMhlVendorCommandListenerRecord implements IBinder.DeathRecipient {
        private final IHdmiMhlVendorCommandListener mListener;

        public HdmiMhlVendorCommandListenerRecord(IHdmiMhlVendorCommandListener listener) {
            mListener = listener;
        }

        @Override
        public void binderDied() {
            mMhlVendorCommandListenerRecords.remove(this);
        }
    }

    // Record class that monitors the event of the caller of being killed. Used to clean up
    // the listener list and record list accordingly.
    private final class HotplugEventListenerRecord implements IBinder.DeathRecipient {
        private final IHdmiHotplugEventListener mListener;

        public HotplugEventListenerRecord(IHdmiHotplugEventListener listener) {
            mListener = listener;
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                mHotplugEventListenerRecords.remove(this);
            }
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof HotplugEventListenerRecord)) return false;
            if (obj == this) return true;
            HotplugEventListenerRecord other = (HotplugEventListenerRecord) obj;
            return other.mListener == this.mListener;
        }

        @Override
        public int hashCode() {
            return mListener.hashCode();
        }
    }

    private final class DeviceEventListenerRecord implements IBinder.DeathRecipient {
        private final IHdmiDeviceEventListener mListener;

        public DeviceEventListenerRecord(IHdmiDeviceEventListener listener) {
            mListener = listener;
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                mDeviceEventListenerRecords.remove(this);
            }
        }
    }

    private final class SystemAudioModeChangeListenerRecord implements IBinder.DeathRecipient {
        private final IHdmiSystemAudioModeChangeListener mListener;

        public SystemAudioModeChangeListenerRecord(IHdmiSystemAudioModeChangeListener listener) {
            mListener = listener;
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                mSystemAudioModeChangeListenerRecords.remove(this);
            }
        }
    }

    class VendorCommandListenerRecord implements IBinder.DeathRecipient {
        private final IHdmiVendorCommandListener mListener;
        private final int mDeviceType;

        public VendorCommandListenerRecord(IHdmiVendorCommandListener listener, int deviceType) {
            mListener = listener;
            mDeviceType = deviceType;
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                mVendorCommandListenerRecords.remove(this);
            }
        }
    }

    private class HdmiRecordListenerRecord implements IBinder.DeathRecipient {
        private final IHdmiRecordListener mListener;

        public HdmiRecordListenerRecord(IHdmiRecordListener listener) {
            mListener = listener;
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                if (mRecordListenerRecord == this) {
                    mRecordListenerRecord = null;
                }
            }
        }
    }

    private void enforceAccessPermission() {
        getContext().enforceCallingOrSelfPermission(PERMISSION, TAG);
    }

    private final class BinderService extends IHdmiControlService.Stub {
        @Override
        public int[] getSupportedTypes() {
            enforceAccessPermission();
            // mLocalDevices is an unmodifiable list - no lock necesary.
            int[] localDevices = new int[mLocalDevices.size()];
            for (int i = 0; i < localDevices.length; ++i) {
                localDevices[i] = mLocalDevices.get(i);
            }
            return localDevices;
        }

        @Override
        public HdmiDeviceInfo getActiveSource() {
            enforceAccessPermission();
            HdmiCecLocalDeviceTv tv = tv();
            if (tv == null) {
                Slog.w(TAG, "Local tv device not available");
                return null;
            }
            ActiveSource activeSource = tv.getActiveSource();
            if (activeSource.isValid()) {
                return new HdmiDeviceInfo(activeSource.logicalAddress,
                        activeSource.physicalAddress, HdmiDeviceInfo.PORT_INVALID,
                        HdmiDeviceInfo.DEVICE_INACTIVE, 0, "");
            }
            int activePath = tv.getActivePath();
            if (activePath != HdmiDeviceInfo.PATH_INVALID) {
                HdmiDeviceInfo info = tv.getSafeDeviceInfoByPath(activePath);
                return (info != null) ? info : new HdmiDeviceInfo(activePath, tv.getActivePortId());
            }
            return null;
        }

        @Override
        public void deviceSelect(final int deviceId, final IHdmiControlCallback callback) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    if (callback == null) {
                        Slog.e(TAG, "Callback cannot be null");
                        return;
                    }
                    HdmiCecLocalDeviceTv tv = tv();
                    if (tv == null) {
                        if (!mAddressAllocated) {
                            mSelectRequestBuffer.set(SelectRequestBuffer.newDeviceSelect(
                                    HdmiControlService.this, deviceId, callback));
                            return;
                        }
                        Slog.w(TAG, "Local tv device not available");
                        invokeCallback(callback, HdmiControlManager.RESULT_SOURCE_NOT_AVAILABLE);
                        return;
                    }
                    HdmiMhlLocalDeviceStub device = mMhlController.getLocalDeviceById(deviceId);
                    if (device != null) {
                        if (device.getPortId() == tv.getActivePortId()) {
                            invokeCallback(callback, HdmiControlManager.RESULT_SUCCESS);
                            return;
                        }
                        // Upon selecting MHL device, we send RAP[Content On] to wake up
                        // the connected mobile device, start routing control to switch ports.
                        // callback is handled by MHL action.
                        device.turnOn(callback);
                        tv.doManualPortSwitching(device.getPortId(), null);
                        return;
                    }
                    tv.deviceSelect(deviceId, callback);
                }
            });
        }

        @Override
        public void portSelect(final int portId, final IHdmiControlCallback callback) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    if (callback == null) {
                        Slog.e(TAG, "Callback cannot be null");
                        return;
                    }
                    HdmiCecLocalDeviceTv tv = tv();
                    if (tv == null) {
                        if (!mAddressAllocated) {
                            mSelectRequestBuffer.set(SelectRequestBuffer.newPortSelect(
                                    HdmiControlService.this, portId, callback));
                            return;
                        }
                        Slog.w(TAG, "Local tv device not available");
                        invokeCallback(callback, HdmiControlManager.RESULT_SOURCE_NOT_AVAILABLE);
                        return;
                    }
                    tv.doManualPortSwitching(portId, callback);
                }
            });
        }

        @Override
        public void sendKeyEvent(final int deviceType, final int keyCode, final boolean isPressed) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiMhlLocalDeviceStub device = mMhlController.getLocalDevice(mActivePortId);
                    if (device != null) {
                        device.sendKeyEvent(keyCode, isPressed);
                        return;
                    }
                    if (mCecController != null) {
                        HdmiCecLocalDevice localDevice = mCecController.getLocalDevice(deviceType);
                        if (localDevice == null) {
                            Slog.w(TAG, "Local device not available");
                            return;
                        }
                        localDevice.sendKeyEvent(keyCode, isPressed);
                    }
                }
            });
        }

        @Override
        public void oneTouchPlay(final IHdmiControlCallback callback) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiControlService.this.oneTouchPlay(callback);
                }
            });
        }

        @Override
        public void queryDisplayStatus(final IHdmiControlCallback callback) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiControlService.this.queryDisplayStatus(callback);
                }
            });
        }

        @Override
        public void addHotplugEventListener(final IHdmiHotplugEventListener listener) {
            enforceAccessPermission();
            HdmiControlService.this.addHotplugEventListener(listener);
        }

        @Override
        public void removeHotplugEventListener(final IHdmiHotplugEventListener listener) {
            enforceAccessPermission();
            HdmiControlService.this.removeHotplugEventListener(listener);
        }

        @Override
        public void addDeviceEventListener(final IHdmiDeviceEventListener listener) {
            enforceAccessPermission();
            HdmiControlService.this.addDeviceEventListener(listener);
        }

        @Override
        public List<HdmiPortInfo> getPortInfo() {
            enforceAccessPermission();
            return HdmiControlService.this.getPortInfo();
        }

        @Override
        public boolean canChangeSystemAudioMode() {
            enforceAccessPermission();
            HdmiCecLocalDeviceTv tv = tv();
            if (tv == null) {
                return false;
            }
            return tv.hasSystemAudioDevice();
        }

        @Override
        public boolean getSystemAudioMode() {
            enforceAccessPermission();
            HdmiCecLocalDeviceTv tv = tv();
            if (tv == null) {
                return false;
            }
            return tv.isSystemAudioActivated();
        }

        @Override
        public void setSystemAudioMode(final boolean enabled, final IHdmiControlCallback callback) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiCecLocalDeviceTv tv = tv();
                    if (tv == null) {
                        Slog.w(TAG, "Local tv device not available");
                        invokeCallback(callback, HdmiControlManager.RESULT_SOURCE_NOT_AVAILABLE);
                        return;
                    }
                    tv.changeSystemAudioMode(enabled, callback);
                }
            });
        }

        @Override
        public void addSystemAudioModeChangeListener(
                final IHdmiSystemAudioModeChangeListener listener) {
            enforceAccessPermission();
            HdmiControlService.this.addSystemAudioModeChangeListner(listener);
        }

        @Override
        public void removeSystemAudioModeChangeListener(
                final IHdmiSystemAudioModeChangeListener listener) {
            enforceAccessPermission();
            HdmiControlService.this.removeSystemAudioModeChangeListener(listener);
        }

        @Override
        public void setInputChangeListener(final IHdmiInputChangeListener listener) {
            enforceAccessPermission();
            HdmiControlService.this.setInputChangeListener(listener);
        }

        @Override
        public List<HdmiDeviceInfo> getInputDevices() {
            enforceAccessPermission();
            // No need to hold the lock for obtaining TV device as the local device instance
            // is preserved while the HDMI control is enabled.
            HdmiCecLocalDeviceTv tv = tv();
            synchronized (mLock) {
                List<HdmiDeviceInfo> cecDevices = (tv == null)
                        ? Collections.<HdmiDeviceInfo>emptyList()
                        : tv.getSafeExternalInputsLocked();
                return HdmiUtils.mergeToUnmodifiableList(cecDevices, getMhlDevicesLocked());
            }
        }

        // Returns all the CEC devices on the bus including system audio, switch,
        // even those of reserved type.
        @Override
        public List<HdmiDeviceInfo> getDeviceList() {
            enforceAccessPermission();
            HdmiCecLocalDeviceTv tv = tv();
            synchronized (mLock) {
                return (tv == null)
                        ? Collections.<HdmiDeviceInfo>emptyList()
                        : tv.getSafeCecDevicesLocked();
            }
        }

        @Override
        public void setSystemAudioVolume(final int oldIndex, final int newIndex,
                final int maxIndex) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiCecLocalDeviceTv tv = tv();
                    if (tv == null) {
                        Slog.w(TAG, "Local tv device not available");
                        return;
                    }
                    tv.changeVolume(oldIndex, newIndex - oldIndex, maxIndex);
                }
            });
        }

        @Override
        public void setSystemAudioMute(final boolean mute) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiCecLocalDeviceTv tv = tv();
                    if (tv == null) {
                        Slog.w(TAG, "Local tv device not available");
                        return;
                    }
                    tv.changeMute(mute);
                }
            });
        }

        @Override
        public void setArcMode(final boolean enabled) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiCecLocalDeviceTv tv = tv();
                    if (tv == null) {
                        Slog.w(TAG, "Local tv device not available to change arc mode.");
                        return;
                    }
                }
            });
        }

        @Override
        public void setProhibitMode(final boolean enabled) {
            enforceAccessPermission();
            if (!isTvDevice()) {
                return;
            }
            HdmiControlService.this.setProhibitMode(enabled);
        }

        @Override
        public void addVendorCommandListener(final IHdmiVendorCommandListener listener,
                final int deviceType) {
            enforceAccessPermission();
            HdmiControlService.this.addVendorCommandListener(listener, deviceType);
        }

        @Override
        public void sendVendorCommand(final int deviceType, final int targetAddress,
                final byte[] params, final boolean hasVendorId) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiCecLocalDevice device = mCecController.getLocalDevice(deviceType);
                    if (device == null) {
                        Slog.w(TAG, "Local device not available");
                        return;
                    }
                    if (hasVendorId) {
                        sendCecCommand(HdmiCecMessageBuilder.buildVendorCommandWithId(
                                device.getDeviceInfo().getLogicalAddress(), targetAddress,
                                getVendorId(), params));
                    } else {
                        sendCecCommand(HdmiCecMessageBuilder.buildVendorCommand(
                                device.getDeviceInfo().getLogicalAddress(), targetAddress, params));
                    }
                }
            });
        }

        @Override
        public void sendStandby(final int deviceType, final int deviceId) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiMhlLocalDeviceStub mhlDevice = mMhlController.getLocalDeviceById(deviceId);
                    if (mhlDevice != null) {
                        mhlDevice.sendStandby();
                        return;
                    }
                    HdmiCecLocalDevice device = mCecController.getLocalDevice(deviceType);
                    if (device == null) {
                        Slog.w(TAG, "Local device not available");
                        return;
                    }
                    device.sendStandby(deviceId);
                }
            });
        }

        @Override
        public void setHdmiRecordListener(IHdmiRecordListener listener) {
            enforceAccessPermission();
            HdmiControlService.this.setHdmiRecordListener(listener);
        }

        @Override
        public void startOneTouchRecord(final int recorderAddress, final byte[] recordSource) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    if (!isTvDeviceEnabled()) {
                        Slog.w(TAG, "TV device is not enabled.");
                        return;
                    }
                    tv().startOneTouchRecord(recorderAddress, recordSource);
                }
            });
        }

        @Override
        public void stopOneTouchRecord(final int recorderAddress) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    if (!isTvDeviceEnabled()) {
                        Slog.w(TAG, "TV device is not enabled.");
                        return;
                    }
                    tv().stopOneTouchRecord(recorderAddress);
                }
            });
        }

        @Override
        public void startTimerRecording(final int recorderAddress, final int sourceType,
                final byte[] recordSource) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    if (!isTvDeviceEnabled()) {
                        Slog.w(TAG, "TV device is not enabled.");
                        return;
                    }
                    tv().startTimerRecording(recorderAddress, sourceType, recordSource);
                }
            });
        }

        @Override
        public void clearTimerRecording(final int recorderAddress, final int sourceType,
                final byte[] recordSource) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    if (!isTvDeviceEnabled()) {
                        Slog.w(TAG, "TV device is not enabled.");
                        return;
                    }
                    tv().clearTimerRecording(recorderAddress, sourceType, recordSource);
                }
            });
        }

        @Override
        public void sendMhlVendorCommand(final int portId, final int offset, final int length,
                final byte[] data) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    if (!isControlEnabled()) {
                        Slog.w(TAG, "Hdmi control is disabled.");
                        return ;
                    }
                    HdmiMhlLocalDeviceStub device = mMhlController.getLocalDevice(portId);
                    if (device == null) {
                        Slog.w(TAG, "Invalid port id:" + portId);
                        return;
                    }
                    mMhlController.sendVendorCommand(portId, offset, length, data);
                }
            });
        }

        @Override
        public void addHdmiMhlVendorCommandListener(
                IHdmiMhlVendorCommandListener listener) {
            enforceAccessPermission();
            HdmiControlService.this.addHdmiMhlVendorCommandListener(listener);
        }

        @Override
        public void setStandbyMode(final boolean isStandbyModeOn) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiControlService.this.setStandbyMode(isStandbyModeOn);
                }
            });
        }

        @Override
        protected void dump(FileDescriptor fd, final PrintWriter writer, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, writer)) return;
            final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");

            pw.println("mHdmiControlEnabled: " + mHdmiControlEnabled);
            pw.println("mProhibitMode: " + mProhibitMode);
            if (mCecController != null) {
                pw.println("mCecController: ");
                pw.increaseIndent();
                mCecController.dump(pw);
                pw.decreaseIndent();
            }

            pw.println("mMhlController: ");
            pw.increaseIndent();
            mMhlController.dump(pw);
            pw.decreaseIndent();

            pw.println("mPortInfo: ");
            pw.increaseIndent();
            for (HdmiPortInfo hdmiPortInfo : mPortInfo) {
                pw.println("- " + hdmiPortInfo);
            }
            pw.decreaseIndent();
            pw.println("mPowerStatus: " + mPowerStatus);
        }
    }

    @ServiceThreadOnly
    private void oneTouchPlay(final IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        HdmiCecLocalDevicePlayback source = playback();
        if (source == null) {
            Slog.w(TAG, "Local playback device not available");
            invokeCallback(callback, HdmiControlManager.RESULT_SOURCE_NOT_AVAILABLE);
            return;
        }
        source.oneTouchPlay(callback);
    }

    @ServiceThreadOnly
    private void queryDisplayStatus(final IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        HdmiCecLocalDevicePlayback source = playback();
        if (source == null) {
            Slog.w(TAG, "Local playback device not available");
            invokeCallback(callback, HdmiControlManager.RESULT_SOURCE_NOT_AVAILABLE);
            return;
        }
        source.queryDisplayStatus(callback);
    }

    private void addHotplugEventListener(final IHdmiHotplugEventListener listener) {
        final HotplugEventListenerRecord record = new HotplugEventListenerRecord(listener);
        try {
            listener.asBinder().linkToDeath(record, 0);
        } catch (RemoteException e) {
            Slog.w(TAG, "Listener already died");
            return;
        }
        synchronized (mLock) {
            mHotplugEventListenerRecords.add(record);
        }

        // Inform the listener of the initial state of each HDMI port by generating
        // hotplug events.
        runOnServiceThread(new Runnable() {
            @Override
            public void run() {
                synchronized (mLock) {
                    if (!mHotplugEventListenerRecords.contains(record)) return;
                }
                for (HdmiPortInfo port : mPortInfo) {
                    HdmiHotplugEvent event = new HdmiHotplugEvent(port.getId(),
                            mCecController.isConnected(port.getId()));
                    synchronized (mLock) {
                        invokeHotplugEventListenerLocked(listener, event);
                    }
                }
            }
        });
    }

    private void removeHotplugEventListener(IHdmiHotplugEventListener listener) {
        synchronized (mLock) {
            for (HotplugEventListenerRecord record : mHotplugEventListenerRecords) {
                if (record.mListener.asBinder() == listener.asBinder()) {
                    listener.asBinder().unlinkToDeath(record, 0);
                    mHotplugEventListenerRecords.remove(record);
                    break;
                }
            }
        }
    }

    private void addDeviceEventListener(IHdmiDeviceEventListener listener) {
        DeviceEventListenerRecord record = new DeviceEventListenerRecord(listener);
        try {
            listener.asBinder().linkToDeath(record, 0);
        } catch (RemoteException e) {
            Slog.w(TAG, "Listener already died");
            return;
        }
        synchronized (mLock) {
            mDeviceEventListenerRecords.add(record);
        }
    }

    void invokeDeviceEventListeners(HdmiDeviceInfo device, int status) {
        synchronized (mLock) {
            for (DeviceEventListenerRecord record : mDeviceEventListenerRecords) {
                try {
                    record.mListener.onStatusChanged(device, status);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to report device event:" + e);
                }
            }
        }
    }

    private void addSystemAudioModeChangeListner(IHdmiSystemAudioModeChangeListener listener) {
        SystemAudioModeChangeListenerRecord record = new SystemAudioModeChangeListenerRecord(
                listener);
        try {
            listener.asBinder().linkToDeath(record, 0);
        } catch (RemoteException e) {
            Slog.w(TAG, "Listener already died");
            return;
        }
        synchronized (mLock) {
            mSystemAudioModeChangeListenerRecords.add(record);
        }
    }

    private void removeSystemAudioModeChangeListener(IHdmiSystemAudioModeChangeListener listener) {
        synchronized (mLock) {
            for (SystemAudioModeChangeListenerRecord record :
                    mSystemAudioModeChangeListenerRecords) {
                if (record.mListener.asBinder() == listener) {
                    listener.asBinder().unlinkToDeath(record, 0);
                    mSystemAudioModeChangeListenerRecords.remove(record);
                    break;
                }
            }
        }
    }

    private final class InputChangeListenerRecord implements IBinder.DeathRecipient {
        private final IHdmiInputChangeListener mListener;

        public InputChangeListenerRecord(IHdmiInputChangeListener listener) {
            mListener = listener;
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                if (mInputChangeListenerRecord == this) {
                    mInputChangeListenerRecord = null;
                }
            }
        }
    }

    private void setInputChangeListener(IHdmiInputChangeListener listener) {
        synchronized (mLock) {
            mInputChangeListenerRecord = new InputChangeListenerRecord(listener);
            try {
                listener.asBinder().linkToDeath(mInputChangeListenerRecord, 0);
            } catch (RemoteException e) {
                Slog.w(TAG, "Listener already died");
                return;
            }
        }
    }

    void invokeInputChangeListener(HdmiDeviceInfo info) {
        synchronized (mLock) {
            if (mInputChangeListenerRecord != null) {
                try {
                    mInputChangeListenerRecord.mListener.onChanged(info);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Exception thrown by IHdmiInputChangeListener: " + e);
                }
            }
        }
    }

    private void setHdmiRecordListener(IHdmiRecordListener listener) {
        synchronized (mLock) {
            mRecordListenerRecord = new HdmiRecordListenerRecord(listener);
            try {
                listener.asBinder().linkToDeath(mRecordListenerRecord, 0);
            } catch (RemoteException e) {
                Slog.w(TAG, "Listener already died.", e);
            }
        }
    }

    byte[] invokeRecordRequestListener(int recorderAddress) {
        synchronized (mLock) {
            if (mRecordListenerRecord != null) {
                try {
                    return mRecordListenerRecord.mListener.getOneTouchRecordSource(recorderAddress);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to start record.", e);
                }
            }
            return EmptyArray.BYTE;
        }
    }

    void invokeOneTouchRecordResult(int recorderAddress, int result) {
        synchronized (mLock) {
            if (mRecordListenerRecord != null) {
                try {
                    mRecordListenerRecord.mListener.onOneTouchRecordResult(recorderAddress, result);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to call onOneTouchRecordResult.", e);
                }
            }
        }
    }

    void invokeTimerRecordingResult(int recorderAddress, int result) {
        synchronized (mLock) {
            if (mRecordListenerRecord != null) {
                try {
                    mRecordListenerRecord.mListener.onTimerRecordingResult(recorderAddress, result);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to call onTimerRecordingResult.", e);
                }
            }
        }
    }

    void invokeClearTimerRecordingResult(int recorderAddress, int result) {
        synchronized (mLock) {
            if (mRecordListenerRecord != null) {
                try {
                    mRecordListenerRecord.mListener.onClearTimerRecordingResult(recorderAddress,
                            result);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to call onClearTimerRecordingResult.", e);
                }
            }
        }
    }

    private void invokeCallback(IHdmiControlCallback callback, int result) {
        try {
            callback.onComplete(result);
        } catch (RemoteException e) {
            Slog.e(TAG, "Invoking callback failed:" + e);
        }
    }

    private void invokeSystemAudioModeChangeLocked(IHdmiSystemAudioModeChangeListener listener,
            boolean enabled) {
        try {
            listener.onStatusChanged(enabled);
        } catch (RemoteException e) {
            Slog.e(TAG, "Invoking callback failed:" + e);
        }
    }

    private void announceHotplugEvent(int portId, boolean connected) {
        HdmiHotplugEvent event = new HdmiHotplugEvent(portId, connected);
        synchronized (mLock) {
            for (HotplugEventListenerRecord record : mHotplugEventListenerRecords) {
                invokeHotplugEventListenerLocked(record.mListener, event);
            }
        }
    }

    private void invokeHotplugEventListenerLocked(IHdmiHotplugEventListener listener,
            HdmiHotplugEvent event) {
        try {
            listener.onReceived(event);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to report hotplug event:" + event.toString(), e);
        }
    }

    public HdmiCecLocalDeviceTv tv() {
        return (HdmiCecLocalDeviceTv) mCecController.getLocalDevice(HdmiDeviceInfo.DEVICE_TV);
    }

    boolean isTvDevice() {
        return mLocalDevices.contains(HdmiDeviceInfo.DEVICE_TV);
    }

    boolean isTvDeviceEnabled() {
        return isTvDevice() && tv() != null;
    }

    private HdmiCecLocalDevicePlayback playback() {
        return (HdmiCecLocalDevicePlayback)
                mCecController.getLocalDevice(HdmiDeviceInfo.DEVICE_PLAYBACK);
    }

    AudioManager getAudioManager() {
        return (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
    }

    boolean isControlEnabled() {
        synchronized (mLock) {
            return mHdmiControlEnabled;
        }
    }

    @ServiceThreadOnly
    int getPowerStatus() {
        assertRunOnServiceThread();
        return mPowerStatus;
    }

    @ServiceThreadOnly
    boolean isPowerOnOrTransient() {
        assertRunOnServiceThread();
        return mPowerStatus == HdmiControlManager.POWER_STATUS_ON
                || mPowerStatus == HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON;
    }

    @ServiceThreadOnly
    boolean isPowerStandbyOrTransient() {
        assertRunOnServiceThread();
        return mPowerStatus == HdmiControlManager.POWER_STATUS_STANDBY
                || mPowerStatus == HdmiControlManager.POWER_STATUS_TRANSIENT_TO_STANDBY;
    }

    @ServiceThreadOnly
    boolean isPowerStandby() {
        assertRunOnServiceThread();
        return mPowerStatus == HdmiControlManager.POWER_STATUS_STANDBY;
    }

    @ServiceThreadOnly
    void wakeUp() {
        assertRunOnServiceThread();
        mWakeUpMessageReceived = true;
        mPowerManager.wakeUp(SystemClock.uptimeMillis(), "android.server.hdmi:WAKE");
        // PowerManger will send the broadcast Intent.ACTION_SCREEN_ON and after this gets
        // the intent, the sequence will continue at onWakeUp().
    }

    @ServiceThreadOnly
    void standby() {
        assertRunOnServiceThread();
        if (!canGoToStandby()) {
            return;
        }
        mStandbyMessageReceived = true;
        mPowerManager.goToSleep(SystemClock.uptimeMillis(), PowerManager.GO_TO_SLEEP_REASON_HDMI, 0);
        // PowerManger will send the broadcast Intent.ACTION_SCREEN_OFF and after this gets
        // the intent, the sequence will continue at onStandby().
    }

    boolean isWakeUpMessageReceived() {
        return mWakeUpMessageReceived;
    }

    @ServiceThreadOnly
    private void onWakeUp() {
        assertRunOnServiceThread();
        mPowerStatus = HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON;
        if (mCecController != null) {
            if (mHdmiControlEnabled) {
                int startReason = INITIATED_BY_SCREEN_ON;
                if (mWakeUpMessageReceived) {
                    startReason = INITIATED_BY_WAKE_UP_MESSAGE;
                }
                initializeCec(startReason);
            }
        } else {
            Slog.i(TAG, "Device does not support HDMI-CEC.");
        }
        // TODO: Initialize MHL local devices.
    }

    @ServiceThreadOnly
    private void onStandby(final int standbyAction) {
        assertRunOnServiceThread();
        mPowerStatus = HdmiControlManager.POWER_STATUS_TRANSIENT_TO_STANDBY;
        invokeVendorCommandListenersOnControlStateChanged(false,
                HdmiControlManager.CONTROL_STATE_CHANGED_REASON_STANDBY);
        if (!canGoToStandby()) {
            mPowerStatus = HdmiControlManager.POWER_STATUS_STANDBY;
            return;
        }

        final List<HdmiCecLocalDevice> devices = getAllLocalDevices();
        disableDevices(new PendingActionClearedCallback() {
            @Override
            public void onCleared(HdmiCecLocalDevice device) {
                Slog.v(TAG, "On standby-action cleared:" + device.mDeviceType);
                devices.remove(device);
                if (devices.isEmpty()) {
                    onStandbyCompleted(standbyAction);
                    // We will not clear local devices here, since some OEM/SOC will keep passing
                    // the received packets until the application processor enters to the sleep
                    // actually.
                }
            }
        });
    }

    private boolean canGoToStandby() {
        for (HdmiCecLocalDevice device : mCecController.getLocalDeviceList()) {
            if (!device.canGoToStandby()) return false;
        }
        return true;
    }

    @ServiceThreadOnly
    private void onLanguageChanged(String language) {
        assertRunOnServiceThread();
        mLanguage = language;

        if (isTvDeviceEnabled()) {
            tv().broadcastMenuLanguage(language);
            mCecController.setLanguage(language);
        }
    }

    @ServiceThreadOnly
    String getLanguage() {
        assertRunOnServiceThread();
        return mLanguage;
    }

    private void disableDevices(PendingActionClearedCallback callback) {
        if (mCecController != null) {
            for (HdmiCecLocalDevice device : mCecController.getLocalDeviceList()) {
                device.disableDevice(mStandbyMessageReceived, callback);
            }
        }

        mMhlController.clearAllLocalDevices();
    }

    @ServiceThreadOnly
    private void clearLocalDevices() {
        assertRunOnServiceThread();
        if (mCecController == null) {
            return;
        }
        mCecController.clearLogicalAddress();
        mCecController.clearLocalDevices();
    }

    @ServiceThreadOnly
    private void onStandbyCompleted(int standbyAction) {
        assertRunOnServiceThread();
        Slog.v(TAG, "onStandbyCompleted");

        if (mPowerStatus != HdmiControlManager.POWER_STATUS_TRANSIENT_TO_STANDBY) {
            return;
        }
        mPowerStatus = HdmiControlManager.POWER_STATUS_STANDBY;
        for (HdmiCecLocalDevice device : mCecController.getLocalDeviceList()) {
            device.onStandby(mStandbyMessageReceived, standbyAction);
        }
        mStandbyMessageReceived = false;
        mCecController.setOption(OptionKey.SYSTEM_CEC_CONTROL, false);
        mMhlController.setOption(OPTION_MHL_SERVICE_CONTROL, DISABLED);
    }

    private void addVendorCommandListener(IHdmiVendorCommandListener listener, int deviceType) {
        VendorCommandListenerRecord record = new VendorCommandListenerRecord(listener, deviceType);
        try {
            listener.asBinder().linkToDeath(record, 0);
        } catch (RemoteException e) {
            Slog.w(TAG, "Listener already died");
            return;
        }
        synchronized (mLock) {
            mVendorCommandListenerRecords.add(record);
        }
    }

    boolean invokeVendorCommandListenersOnReceived(int deviceType, int srcAddress, int destAddress,
            byte[] params, boolean hasVendorId) {
        synchronized (mLock) {
            if (mVendorCommandListenerRecords.isEmpty()) {
                return false;
            }
            for (VendorCommandListenerRecord record : mVendorCommandListenerRecords) {
                if (record.mDeviceType != deviceType) {
                    continue;
                }
                try {
                    record.mListener.onReceived(srcAddress, destAddress, params, hasVendorId);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to notify vendor command reception", e);
                }
            }
            return true;
        }
    }

    boolean invokeVendorCommandListenersOnControlStateChanged(boolean enabled, int reason) {
        synchronized (mLock) {
            if (mVendorCommandListenerRecords.isEmpty()) {
                return false;
            }
            for (VendorCommandListenerRecord record : mVendorCommandListenerRecords) {
                try {
                    record.mListener.onControlStateChanged(enabled, reason);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to notify control-state-changed to vendor handler", e);
                }
            }
            return true;
        }
    }

    private void addHdmiMhlVendorCommandListener(IHdmiMhlVendorCommandListener listener) {
        HdmiMhlVendorCommandListenerRecord record =
                new HdmiMhlVendorCommandListenerRecord(listener);
        try {
            listener.asBinder().linkToDeath(record, 0);
        } catch (RemoteException e) {
            Slog.w(TAG, "Listener already died.");
            return;
        }

        synchronized (mLock) {
            mMhlVendorCommandListenerRecords.add(record);
        }
    }

    void invokeMhlVendorCommandListeners(int portId, int offest, int length, byte[] data) {
        synchronized (mLock) {
            for (HdmiMhlVendorCommandListenerRecord record : mMhlVendorCommandListenerRecords) {
                try {
                    record.mListener.onReceived(portId, offest, length, data);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to notify MHL vendor command", e);
                }
            }
        }
    }

    void setStandbyMode(boolean isStandbyModeOn) {
        assertRunOnServiceThread();
        if (isPowerOnOrTransient() && isStandbyModeOn) {
            mPowerManager.goToSleep(SystemClock.uptimeMillis(),
                    PowerManager.GO_TO_SLEEP_REASON_HDMI, 0);
            if (playback() != null) {
                playback().sendStandby(0 /* unused */);
            }
        } else if (isPowerStandbyOrTransient() && !isStandbyModeOn) {
            mPowerManager.wakeUp(SystemClock.uptimeMillis(), "android.server.hdmi:WAKE");
            if (playback() != null) {
                oneTouchPlay(new IHdmiControlCallback.Stub() {
                    @Override
                    public void onComplete(int result) {
                        if (result != HdmiControlManager.RESULT_SUCCESS) {
                            Slog.w(TAG, "Failed to complete 'one touch play'. result=" + result);
                        }
                    }
                });
            }
        }
    }

    boolean isProhibitMode() {
        synchronized (mLock) {
            return mProhibitMode;
        }
    }

    void setProhibitMode(boolean enabled) {
        synchronized (mLock) {
            mProhibitMode = enabled;
        }
    }

    @ServiceThreadOnly
    void setCecOption(int key, boolean value) {
        assertRunOnServiceThread();
        mCecController.setOption(key, value);
    }

    @ServiceThreadOnly
    void setControlEnabled(boolean enabled) {
        assertRunOnServiceThread();

        synchronized (mLock) {
            mHdmiControlEnabled = enabled;
        }

        if (enabled) {
            enableHdmiControlService();
            return;
        }
        // Call the vendor handler before the service is disabled.
        invokeVendorCommandListenersOnControlStateChanged(false,
                HdmiControlManager.CONTROL_STATE_CHANGED_REASON_SETTING);
        // Post the remained tasks in the service thread again to give the vendor-issued-tasks
        // a chance to run.
        runOnServiceThread(new Runnable() {
            @Override
            public void run() {
                disableHdmiControlService();
            }
        });
        return;
    }

    @ServiceThreadOnly
    private void enableHdmiControlService() {
        mCecController.setOption(OptionKey.SYSTEM_CEC_CONTROL, true);
        mMhlController.setOption(OPTION_MHL_ENABLE, ENABLED);

        initializeCec(INITIATED_BY_ENABLE_CEC);
    }

    @ServiceThreadOnly
    private void disableHdmiControlService() {
        disableDevices(new PendingActionClearedCallback() {
            @Override
            public void onCleared(HdmiCecLocalDevice device) {
                assertRunOnServiceThread();
                mCecController.flush(new Runnable() {
                    @Override
                    public void run() {
                        mCecController.setOption(OptionKey.ENABLE_CEC, false);
                        mMhlController.setOption(OPTION_MHL_ENABLE, DISABLED);
                        clearLocalDevices();
                    }
                });
            }
        });
    }

    @ServiceThreadOnly
    void setActivePortId(int portId) {
        assertRunOnServiceThread();
        mActivePortId = portId;

        // Resets last input for MHL, which stays valid only after the MHL device was selected,
        // and no further switching is done.
        setLastInputForMhl(Constants.INVALID_PORT_ID);
    }

    @ServiceThreadOnly
    void setLastInputForMhl(int portId) {
        assertRunOnServiceThread();
        mLastInputMhl = portId;
    }

    @ServiceThreadOnly
    int getLastInputForMhl() {
        assertRunOnServiceThread();
        return mLastInputMhl;
    }

    /**
     * Performs input change, routing control for MHL device.
     *
     * @param portId MHL port, or the last port to go back to if {@code contentOn} is false
     * @param contentOn {@code true} if RAP data is content on; otherwise false
     */
    @ServiceThreadOnly
    void changeInputForMhl(int portId, boolean contentOn) {
        assertRunOnServiceThread();
        if (tv() == null) return;
        final int lastInput = contentOn ? tv().getActivePortId() : Constants.INVALID_PORT_ID;
        if (portId != Constants.INVALID_PORT_ID) {
            tv().doManualPortSwitching(portId, new IHdmiControlCallback.Stub() {
                @Override
                public void onComplete(int result) throws RemoteException {
                    // Keep the last input to switch back later when RAP[ContentOff] is received.
                    // This effectively sets the port to invalid one if the switching is for
                    // RAP[ContentOff].
                    setLastInputForMhl(lastInput);
                }
            });
        }
        // MHL device is always directly connected to the port. Update the active port ID to avoid
        // unnecessary post-routing control task.
        tv().setActivePortId(portId);

        // The port is either the MHL-enabled port where the mobile device is connected, or
        // the last port to go back to when turnoff command is received. Note that the last port
        // may not be the MHL-enabled one. In this case the device info to be passed to
        // input change listener should be the one describing the corresponding HDMI port.
        HdmiMhlLocalDeviceStub device = mMhlController.getLocalDevice(portId);
        HdmiDeviceInfo info = (device != null) ? device.getInfo()
                : mPortDeviceMap.get(portId, HdmiDeviceInfo.INACTIVE_DEVICE);
        invokeInputChangeListener(info);
    }

   void setMhlInputChangeEnabled(boolean enabled) {
       mMhlController.setOption(OPTION_MHL_INPUT_SWITCHING, toInt(enabled));

        synchronized (mLock) {
            mMhlInputChangeEnabled = enabled;
        }
    }

    boolean isMhlInputChangeEnabled() {
        synchronized (mLock) {
            return mMhlInputChangeEnabled;
        }
    }

    @ServiceThreadOnly
    void displayOsd(int messageId) {
        assertRunOnServiceThread();
        Intent intent = new Intent(HdmiControlManager.ACTION_OSD_MESSAGE);
        intent.putExtra(HdmiControlManager.EXTRA_MESSAGE_ID, messageId);
        getContext().sendBroadcastAsUser(intent, UserHandle.ALL,
                HdmiControlService.PERMISSION);
    }

    @ServiceThreadOnly
    void displayOsd(int messageId, int extra) {
        assertRunOnServiceThread();
        Intent intent = new Intent(HdmiControlManager.ACTION_OSD_MESSAGE);
        intent.putExtra(HdmiControlManager.EXTRA_MESSAGE_ID, messageId);
        intent.putExtra(HdmiControlManager.EXTRA_MESSAGE_EXTRA_PARAM1, extra);
        getContext().sendBroadcastAsUser(intent, UserHandle.ALL,
                HdmiControlService.PERMISSION);
    }
}
