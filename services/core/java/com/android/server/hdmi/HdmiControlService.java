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

import static com.android.internal.os.RoSystemProperties.PROPERTY_HDMI_IS_DEVICE_HDMI_CEC_SWITCH;
import static com.android.server.hdmi.Constants.ADDR_UNREGISTERED;
import static com.android.server.hdmi.Constants.DISABLED;
import static com.android.server.hdmi.Constants.ENABLED;
import static com.android.server.hdmi.Constants.OPTION_MHL_ENABLE;
import static com.android.server.hdmi.Constants.OPTION_MHL_INPUT_SWITCHING;
import static com.android.server.hdmi.Constants.OPTION_MHL_POWER_CHARGE;
import static com.android.server.hdmi.Constants.OPTION_MHL_SERVICE_CONTROL;
import static com.android.server.power.ShutdownThread.SHUTDOWN_ACTION_PROPERTY;

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
import android.hardware.hdmi.IHdmiCecVolumeControlFeatureListener;
import android.hardware.hdmi.IHdmiControlCallback;
import android.hardware.hdmi.IHdmiControlService;
import android.hardware.hdmi.IHdmiControlStatusChangeListener;
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
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings.Global;
import android.sysprop.HdmiProperties;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.SystemService;
import com.android.server.hdmi.HdmiAnnotations.ServiceThreadOnly;
import com.android.server.hdmi.HdmiCecController.AllocateAddressCallback;
import com.android.server.hdmi.HdmiCecLocalDevice.ActiveSource;
import com.android.server.hdmi.HdmiCecLocalDevice.PendingActionClearedCallback;

import libcore.util.EmptyArray;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Provides a service for sending and processing HDMI control messages,
 * HDMI-CEC and MHL control command, and providing the information on both standard.
 */
public class HdmiControlService extends SystemService {
    private static final String TAG = "HdmiControlService";
    private static final Locale HONG_KONG = new Locale("zh", "HK");
    private static final Locale MACAU = new Locale("zh", "MO");

    private static final Map<String, String> sTerminologyToBibliographicMap =
            createsTerminologyToBibliographicMap();

    private static Map<String, String> createsTerminologyToBibliographicMap() {
        Map<String, String> temp = new HashMap<>();
        // NOTE: (TERMINOLOGY_CODE, BIBLIOGRAPHIC_CODE)
        temp.put("sqi", "alb"); // Albanian
        temp.put("hye", "arm"); // Armenian
        temp.put("eus", "baq"); // Basque
        temp.put("mya", "bur"); // Burmese
        temp.put("ces", "cze"); // Czech
        temp.put("nld", "dut"); // Dutch
        temp.put("kat", "geo"); // Georgian
        temp.put("deu", "ger"); // German
        temp.put("ell", "gre"); // Greek
        temp.put("fra", "fre"); // French
        temp.put("isl", "ice"); // Icelandic
        temp.put("mkd", "mac"); // Macedonian
        temp.put("mri", "mao"); // Maori
        temp.put("msa", "may"); // Malay
        temp.put("fas", "per"); // Persian
        temp.put("ron", "rum"); // Romanian
        temp.put("slk", "slo"); // Slovak
        temp.put("bod", "tib"); // Tibetan
        temp.put("cym", "wel"); // Welsh
        return Collections.unmodifiableMap(temp);
    }

    @VisibleForTesting static String localeToMenuLanguage(Locale locale) {
        if (locale.equals(Locale.TAIWAN) || locale.equals(HONG_KONG) || locale.equals(MACAU)) {
            // Android always returns "zho" for all Chinese variants.
            // Use "bibliographic" code defined in CEC639-2 for traditional
            // Chinese used in Taiwan/Hong Kong/Macau.
            return "chi";
        } else {
            String language = locale.getISO3Language();

            // locale.getISO3Language() returns terminology code and need to
            // send it as bibliographic code instead since the Bibliographic
            // codes of ISO/FDIS 639-2 shall be used.
            // NOTE: Chinese also has terminology/bibliographic code "zho" and "chi"
            // But, as it depends on the locale, is not handled here.
            if (sTerminologyToBibliographicMap.containsKey(language)) {
                language = sTerminologyToBibliographicMap.get(language);
            }

            return language;
        }
    }

    static final String PERMISSION = "android.permission.HDMI_CEC";

    // The reason code to initiate initializeCec().
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

    // Logical address of the active source.
    @GuardedBy("mLock")
    protected final ActiveSource mActiveSource = new ActiveSource();

    // Whether HDMI CEC volume control is enabled or not.
    @GuardedBy("mLock")
    private boolean mHdmiCecVolumeControlEnabled;

    // Whether System Audio Mode is activated or not.
    @GuardedBy("mLock")
    private boolean mSystemAudioActivated = false;

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
            boolean isReboot = SystemProperties.get(SHUTDOWN_ACTION_PROPERTY).contains("1");
            switch (intent.getAction()) {
                case Intent.ACTION_SCREEN_OFF:
                    if (isPowerOnOrTransient() && !isReboot) {
                        onStandby(STANDBY_SCREEN_OFF);
                    }
                    break;
                case Intent.ACTION_SCREEN_ON:
                    if (isPowerStandbyOrTransient()) {
                        onWakeUp();
                    }
                    break;
                case Intent.ACTION_CONFIGURATION_CHANGED:
                    String language = HdmiControlService.localeToMenuLanguage(Locale.getDefault());
                    if (!mMenuLanguage.equals(language)) {
                        onLanguageChanged(language);
                    }
                    break;
                case Intent.ACTION_SHUTDOWN:
                    if (isPowerOnOrTransient() && !isReboot) {
                        onStandby(STANDBY_SHUTDOWN);
                    }
                    break;
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

    // List of records for HDMI control status change listener for death monitoring.
    @GuardedBy("mLock")
    private final ArrayList<HdmiControlStatusChangeListenerRecord>
            mHdmiControlStatusChangeListenerRecords = new ArrayList<>();

    // List of records for HDMI control volume control status change listener for death monitoring.
    @GuardedBy("mLock")
    private final RemoteCallbackList<IHdmiCecVolumeControlFeatureListener>
            mHdmiCecVolumeControlFeatureListenerRecords = new RemoteCallbackList<>();

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
    // Save callback when the device is still under logcial address allocation
    // Invoke once new local device is ready.
    private IHdmiControlCallback mDisplayStatusCallback = null;

    @Nullable
    // Save callback when the device is still under logcial address allocation
    // Invoke once new local device is ready.
    private IHdmiControlCallback mOtpCallbackPendingAddressAllocation = null;

    @Nullable
    private HdmiCecController mCecController;

    // HDMI port information. Stored in the unmodifiable list to keep the static information
    // from being modified.
    // This variable is null if the current device does not have hdmi input.
    @GuardedBy("mLock")
    private List<HdmiPortInfo> mPortInfo = null;

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
    private String mMenuLanguage = localeToMenuLanguage(Locale.getDefault());

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

    @Nullable
    private Looper mIoLooper;

    // Thread safe physical address
    @GuardedBy("mLock")
    private int mPhysicalAddress = Constants.INVALID_PHYSICAL_ADDRESS;

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

        public boolean bufferMessage(HdmiCecMessage message) {
            switch (message.getOpcode()) {
                case Constants.MESSAGE_ACTIVE_SOURCE:
                    bufferActiveSource(message);
                    return true;
                case Constants.MESSAGE_IMAGE_VIEW_ON:
                case Constants.MESSAGE_TEXT_VIEW_ON:
                    bufferImageOrTextViewOn(message);
                    return true;
                case Constants.MESSAGE_SYSTEM_AUDIO_MODE_REQUEST:
                    bufferSystemAudioModeRequest(message);
                    return true;
                    // Add here if new message that needs to buffer
                default:
                    // Do not need to buffer messages other than above
                    return false;
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

        private void bufferSystemAudioModeRequest(HdmiCecMessage message) {
            if (!replaceMessageIfBuffered(message, Constants.MESSAGE_SYSTEM_AUDIO_MODE_REQUEST)) {
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
        List<Integer> deviceTypes = HdmiProperties.device_type();
        if (deviceTypes.contains(null)) {
            Slog.w(TAG, "Error parsing ro.hdmi.device.type: " + SystemProperties.get(
                    "ro.hdmi.device_type"));
            deviceTypes = deviceTypes.stream().filter(Objects::nonNull).collect(
                    Collectors.toList());
        }
        mLocalDevices = deviceTypes;
        mSettingsObserver = new SettingsObserver(mHandler);
    }

    protected static List<Integer> getIntList(String string) {
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
        if (mIoLooper == null) {
            mIoThread.start();
            mIoLooper = mIoThread.getLooper();
        }
        mPowerStatus = getInitialPowerStatus();
        mProhibitMode = false;
        mHdmiControlEnabled = readBooleanSetting(Global.HDMI_CONTROL_ENABLED, true);
        mHdmiCecVolumeControlEnabled = readBooleanSetting(
                Global.HDMI_CONTROL_VOLUME_CONTROL_ENABLED, true);
        mMhlInputChangeEnabled = readBooleanSetting(Global.MHL_INPUT_SWITCHING_ENABLED, true);

        if (mCecController == null) {
            mCecController = HdmiCecController.create(this);
        }
        if (mCecController != null) {
            if (mHdmiControlEnabled) {
                initializeCec(INITIATED_BY_BOOT_UP);
            } else {
                mCecController.setOption(OptionKey.ENABLE_CEC, false);
            }
        } else {
            Slog.i(TAG, "Device does not support HDMI-CEC.");
            return;
        }
        if (mMhlController == null) {
            mMhlController = HdmiMhlControllerStub.create(this);
        }
        if (!mMhlController.isReady()) {
            Slog.i(TAG, "Device does not support MHL-control.");
        }
        mMhlDevices = Collections.emptyList();

        initPortInfo();
        if (mMessageValidator == null) {
            mMessageValidator = new HdmiCecMessageValidator(this);
        }
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

    private void bootCompleted() {
        // on boot, if device is interactive, set HDMI CEC state as powered on as well
        if (mPowerManager.isInteractive() && isPowerStandbyOrTransient()) {
            mPowerStatus = HdmiControlManager.POWER_STATUS_ON;
        }
    }

    /**
     * Returns the initial power status used when the HdmiControlService starts.
     */
    @VisibleForTesting
    int getInitialPowerStatus() {
        // The initial power status is POWER_STATUS_TRANSIENT_TO_STANDBY.
        // Once boot completes the service transitions to POWER_STATUS_ON if the device is
        // interactive.
        // Quiescent boot is a special boot mode, in which the screen stays off during boot
        // and the device goes to sleep after boot has finished.
        // We don't transition to POWER_STATUS_ON initially, as we might be booting in quiescent
        // mode, during which we don't want to appear powered on to avoid being made active source.
        return HdmiControlManager.POWER_STATUS_TRANSIENT_TO_STANDBY;
    }

    @VisibleForTesting
    void setCecController(HdmiCecController cecController) {
        mCecController = cecController;
    }

    @VisibleForTesting
    void setHdmiMhlController(HdmiMhlControllerStub hdmiMhlController) {
        mMhlController = hdmiMhlController;
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            mTvInputManager = (TvInputManager) getContext().getSystemService(
                    Context.TV_INPUT_SERVICE);
            mPowerManager = getContext().getSystemService(PowerManager.class);
        } else if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            runOnServiceThread(this::bootCompleted);
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
        updatePowerStatusOnInitializeCecComplete();
        mWakeUpMessageReceived = false;

        if (isTvDeviceEnabled()) {
            boolean autoWakeupEnabled =
                readBooleanSetting(Global.HDMI_CONTROL_AUTO_WAKEUP_ENABLED, true);
            boolean autoDeviceOffEnabled =
                readBooleanSetting(Global.HDMI_CONTROL_AUTO_DEVICE_OFF_ENABLED, true);

            mCecController.setOption(OptionKey.WAKEUP, autoWakeupEnabled);
            tv().setAutoWakeup(autoWakeupEnabled);
            tv().setAutoDeviceOff(autoDeviceOffEnabled);
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
            announceHdmiControlStatusChange(true);
        }
    }

    /**
     * Updates the power status once the initialization of local devices is complete.
     */
    private void updatePowerStatusOnInitializeCecComplete() {
        if (mPowerStatus == HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON) {
            mPowerStatus = HdmiControlManager.POWER_STATUS_ON;
        } else if (mPowerStatus == HdmiControlManager.POWER_STATUS_TRANSIENT_TO_STANDBY) {
            mPowerStatus = HdmiControlManager.POWER_STATUS_STANDBY;
        }
    }

    private void registerContentObserver() {
        ContentResolver resolver = getContext().getContentResolver();
        String[] settings = new String[] {
                Global.HDMI_CONTROL_ENABLED,
                Global.HDMI_CONTROL_VOLUME_CONTROL_ENABLED,
                Global.HDMI_CONTROL_AUTO_WAKEUP_ENABLED,
                Global.HDMI_CONTROL_AUTO_DEVICE_OFF_ENABLED,
                Global.HDMI_SYSTEM_AUDIO_CONTROL_ENABLED,
                Global.MHL_INPUT_SWITCHING_ENABLED,
                Global.MHL_POWER_CHARGE_ENABLED,
                Global.HDMI_CEC_SWITCH_ENABLED,
                Global.DEVICE_NAME
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
                case Global.HDMI_CONTROL_VOLUME_CONTROL_ENABLED:
                    setHdmiCecVolumeControlEnabled(enabled);
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
                    if (isAudioSystemDevice()) {
                        if (audioSystem() == null) {
                            Slog.e(TAG, "Audio System device has not registered yet."
                                    + " Can't turn system audio mode on.");
                            break;
                        }
                        audioSystem().onSystemAduioControlFeatureSupportChanged(enabled);
                    }
                    break;
                case Global.HDMI_CEC_SWITCH_ENABLED:
                    if (isAudioSystemDevice()) {
                        if (audioSystem() == null) {
                            Slog.w(TAG, "Switch device has not registered yet."
                                    + " Can't turn routing on.");
                            break;
                        }
                        audioSystem().setRoutingControlFeatureEnables(enabled);
                    }
                    break;
                case Global.MHL_INPUT_SWITCHING_ENABLED:
                    setMhlInputChangeEnabled(enabled);
                    break;
                case Global.MHL_POWER_CHARGE_ENABLED:
                    mMhlController.setOption(OPTION_MHL_POWER_CHARGE, toInt(enabled));
                    break;
                case Global.DEVICE_NAME:
                    String deviceName = readStringSetting(option, Build.MODEL);
                    setDisplayName(deviceName);
                    break;
            }
        }
    }

    private static int toInt(boolean enabled) {
        return enabled ? ENABLED : DISABLED;
    }

    @VisibleForTesting
    boolean readBooleanSetting(String key, boolean defVal) {
        ContentResolver cr = getContext().getContentResolver();
        return Global.getInt(cr, key, toInt(defVal)) == ENABLED;
    }

    void writeBooleanSetting(String key, boolean value) {
        ContentResolver cr = getContext().getContentResolver();
        Global.putInt(cr, key, toInt(value));
    }

    void writeStringSystemProperty(String key, String value) {
        SystemProperties.set(key, value);
    }

    @VisibleForTesting
    boolean readBooleanSystemProperty(String key, boolean defVal) {
        return SystemProperties.getBoolean(key, defVal);
    }

    String readStringSetting(String key, String defVal) {
        ContentResolver cr = getContext().getContentResolver();
        String content = Global.getString(cr, key);
        if (TextUtils.isEmpty(content)) {
            return defVal;
        }
        return content;
    }

    private void initializeCec(int initiatedBy) {
        mAddressAllocated = false;
        mCecController.setOption(OptionKey.SYSTEM_CEC_CONTROL, true);
        mCecController.setLanguage(mMenuLanguage);
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
    @VisibleForTesting
    protected void allocateLogicalAddress(final ArrayList<HdmiCecLocalDevice> allocatingDevices,
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
                        // Reinvoke the saved display status callback once the local device is ready.
                        if (mDisplayStatusCallback != null) {
                            queryDisplayStatus(mDisplayStatusCallback);
                            mDisplayStatusCallback = null;
                        }
                        if (mOtpCallbackPendingAddressAllocation != null) {
                            oneTouchPlay(mOtpCallbackPendingAddressAllocation);
                            mOtpCallbackPendingAddressAllocation = null;
                        }
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
    @VisibleForTesting
    protected void initPortInfo() {
        assertRunOnServiceThread();
        HdmiPortInfo[] cecPortInfo = null;

        synchronized (mLock) {
            mPhysicalAddress = getPhysicalAddress();
        }

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

        if (mMhlController == null) {
            return;
        }
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
            setPortInfo(Collections.unmodifiableList(Arrays.asList(cecPortInfo)));
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
        setPortInfo(Collections.unmodifiableList(result));
    }

    List<HdmiPortInfo> getPortInfo() {
        synchronized (mLock) {
            return mPortInfo;
        }
    }

    void setPortInfo(List<HdmiPortInfo> portInfo) {
        synchronized (mLock) {
            mPortInfo = portInfo;
        }
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
     * Returns the id of HDMI port located at the current device that runs this method.
     *
     * For TV with physical address 0x0000, target device 0x1120, we want port physical address
     * 0x1000 to get the correct port id from {@link #mPortIdMap}. For device with Physical Address
     * 0x2000, target device 0x2420, we want port address 0x24000 to get the port id.
     *
     * <p>Return {@link Constants#INVALID_PORT_ID} if target device does not connect to.
     *
     * @param path the target device's physical address.
     * @return the id of the port that the target device eventually connects to
     * on the current device.
     */
    int pathToPortId(int path) {
        int mask = 0xF000;
        int finalMask = 0xF000;
        int physicalAddress;
        synchronized (mLock) {
            physicalAddress = mPhysicalAddress;
        }
        int maskedAddress = physicalAddress;

        while (maskedAddress != 0) {
            maskedAddress = physicalAddress & mask;
            finalMask |= mask;
            mask >>= 4;
        }

        int portAddress = path & finalMask;
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
    @Nullable
    Looper getIoLooper() {
        return mIoLooper;
    }

    @VisibleForTesting
    void setIoLooper(Looper ioLooper) {
        mIoLooper = ioLooper;
    }

    @VisibleForTesting
    void setMessageValidator(HdmiCecMessageValidator messageValidator) {
        mMessageValidator = messageValidator;
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
        int errorCode = mMessageValidator.isValid(message);
        if (errorCode != HdmiCecMessageValidator.OK) {
            // We'll not response on the messages with the invalid source or destination
            // or with parameter length shorter than specified in the standard.
            if (errorCode == HdmiCecMessageValidator.ERROR_PARAMETER) {
                maySendFeatureAbortCommand(message, Constants.ABORT_INVALID_OPERAND);
            }
            return true;
        }

        if (dispatchMessageToLocalDevice(message)) {
            return true;
        }

        return (!mAddressAllocated) ? mCecMessageBuffer.bufferMessage(message) : false;
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

        if (connected && !isTvDevice()
                && getPortInfo(portId).getType() == HdmiPortInfo.PORT_OUTPUT) {
            if (isSwitchDevice()) {
                initPortInfo();
                HdmiLogger.debug("initPortInfo for switch device when onHotplug from tx.");
            }
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
     * @throws IllegalArgumentException if {@code pickStrategy} is invalid value
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

    /**
     * Check if a logical address is conflict with the current device's. Reallocate the logical
     * address of the current device if there is conflict.
     *
     * Android HDMI CEC 1.4 is handling logical address allocation in the framework side. This could
     * introduce delay between the logical address allocation and notifying the driver that the
     * address is occupied. Adding this check to avoid such case.
     *
     * @param logicalAddress logical address of the remote device that might have the same logical
     * address as the current device.
     */
    protected void checkLogicalAddressConflictAndReallocate(int logicalAddress) {
        for (HdmiCecLocalDevice device : getAllLocalDevices()) {
            if (device.getDeviceInfo().getLogicalAddress() == logicalAddress) {
                HdmiLogger.debug("allocate logical address for " + device.getDeviceInfo());
                ArrayList<HdmiCecLocalDevice> localDevices = new ArrayList<>();
                localDevices.add(device);
                allocateLogicalAddress(localDevices, HdmiControlService.INITIATED_BY_HOTPLUG);
                return;
            }
        }
    }

    Object getServiceLock() {
        return mLock;
    }

    void setAudioStatus(boolean mute, int volume) {
        if (!isTvDeviceEnabled()
                || !tv().isSystemAudioActivated()
                || !tv().isArcEstablished() // Don't update TV volume when SAM is on and ARC is off
                || !isHdmiCecVolumeControlEnabled()) {
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
        String displayName = readStringSetting(Global.DEVICE_NAME, Build.MODEL);
        return new HdmiDeviceInfo(logicalAddress,
                getPhysicalAddress(), pathToPortId(getPhysicalAddress()), deviceType,
                getVendorId(), displayName, powerStatus);
    }

    // Set the display name in HdmiDeviceInfo of the current devices to content provided by
    // Global.DEVICE_NAME. Only set and broadcast if the new name is different.
    private void setDisplayName(String newDisplayName) {
        for (HdmiCecLocalDevice device : getAllLocalDevices()) {
            HdmiDeviceInfo deviceInfo = device.getDeviceInfo();
            if (deviceInfo.getDisplayName().equals(newDisplayName)) {
                continue;
            }
            device.setDeviceInfo(new HdmiDeviceInfo(
                    deviceInfo.getLogicalAddress(), deviceInfo.getPhysicalAddress(),
                    deviceInfo.getPortId(), deviceInfo.getDeviceType(), deviceInfo.getVendorId(),
                    newDisplayName, deviceInfo.getDevicePowerStatus()));
            sendCecCommand(HdmiCecMessageBuilder.buildSetOsdNameCommand(
                    device.mAddress, Constants.ADDR_TV, newDisplayName));
        }
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
    private final class HdmiControlStatusChangeListenerRecord implements IBinder.DeathRecipient {
        private final IHdmiControlStatusChangeListener mListener;

        HdmiControlStatusChangeListenerRecord(IHdmiControlStatusChangeListener listener) {
            mListener = listener;
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                mHdmiControlStatusChangeListenerRecords.remove(this);
            }
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof HdmiControlStatusChangeListenerRecord)) return false;
            if (obj == this) return true;
            HdmiControlStatusChangeListenerRecord other =
                    (HdmiControlStatusChangeListenerRecord) obj;
            return other.mListener == this.mListener;
        }

        @Override
        public int hashCode() {
            return mListener.hashCode();
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
        @Nullable
        public HdmiDeviceInfo getActiveSource() {
            enforceAccessPermission();
            HdmiCecLocalDeviceTv tv = tv();
            if (tv == null) {
                if (isTvDevice()) {
                    Slog.e(TAG, "Local tv device not available.");
                    return null;
                }
                if (isPlaybackDevice()) {
                    // if playback device itself is the active source,
                    // return its own device info.
                    if (playback() != null && playback().mIsActiveSource) {
                        return playback().getDeviceInfo();
                    }
                    // Otherwise get the active source and look for it from the device list
                    ActiveSource activeSource = getLocalActiveSource();
                    // If the physical address is not set yet, return null
                    if (activeSource.physicalAddress == Constants.INVALID_PHYSICAL_ADDRESS) {
                        return null;
                    }
                    if (audioSystem() != null) {
                        HdmiCecLocalDeviceAudioSystem audioSystem = audioSystem();
                        for (HdmiDeviceInfo info : audioSystem.getSafeCecDevicesLocked()) {
                            if (info.getPhysicalAddress() == activeSource.physicalAddress) {
                                return info;
                            }
                        }
                    }
                    // If the device info is not in the list yet, return a device info with minimum
                    // information from mActiveSource.
                    // If the Active Source has unregistered logical address, return with an
                    // HdmiDeviceInfo built from physical address information only.
                    return HdmiUtils.isValidAddress(activeSource.logicalAddress)
                        ?
                        new HdmiDeviceInfo(activeSource.logicalAddress,
                            activeSource.physicalAddress,
                            pathToPortId(activeSource.physicalAddress),
                            HdmiUtils.getTypeFromAddress(activeSource.logicalAddress), 0,
                            HdmiUtils.getDefaultDeviceName(activeSource.logicalAddress))
                        :
                            new HdmiDeviceInfo(activeSource.physicalAddress,
                                pathToPortId(activeSource.physicalAddress));

                }
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
                    if (isPowerStandby()) {
                        Slog.e(TAG, "Device is in standby. Not handling deviceSelect");
                        invokeCallback(callback, HdmiControlManager.RESULT_INCORRECT_MODE);
                        return;
                    }
                    HdmiCecLocalDeviceTv tv = tv();
                    if (tv == null) {
                        if (!mAddressAllocated) {
                            mSelectRequestBuffer.set(SelectRequestBuffer.newDeviceSelect(
                                    HdmiControlService.this, deviceId, callback));
                            return;
                        }
                        if (isTvDevice()) {
                            Slog.e(TAG, "Local tv device not available");
                            return;
                        }
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
                    if (isPowerStandby()) {
                        Slog.e(TAG, "Device is in standby. Not handling portSelect");
                        invokeCallback(callback, HdmiControlManager.RESULT_INCORRECT_MODE);
                        return;
                    }
                    HdmiCecLocalDeviceTv tv = tv();
                    if (tv != null) {
                        tv.doManualPortSwitching(portId, callback);
                        return;
                    }
                    HdmiCecLocalDeviceAudioSystem audioSystem = audioSystem();
                    if (audioSystem != null) {
                        audioSystem.doManualPortSwitching(portId, callback);
                        return;
                    }

                    if (!mAddressAllocated) {
                        mSelectRequestBuffer.set(SelectRequestBuffer.newPortSelect(
                                HdmiControlService.this, portId, callback));
                        return;
                    }
                    Slog.w(TAG, "Local device not available");
                    invokeCallback(callback, HdmiControlManager.RESULT_SOURCE_NOT_AVAILABLE);
                    return;
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
                            Slog.w(TAG, "Local device not available to send key event.");
                            return;
                        }
                        localDevice.sendKeyEvent(keyCode, isPressed);
                    }
                }
            });
        }

        @Override
        public void sendVolumeKeyEvent(
            final int deviceType, final int keyCode, final boolean isPressed) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    if (mCecController == null) {
                        Slog.w(TAG, "CEC controller not available to send volume key event.");
                        return;
                    }
                    HdmiCecLocalDevice localDevice = mCecController.getLocalDevice(deviceType);
                    if (localDevice == null) {
                        Slog.w(TAG, "Local device " + deviceType
                              + " not available to send volume key event.");
                        return;
                    }
                    localDevice.sendVolumeKeyEvent(keyCode, isPressed);
                }
            });
        }

        @Override
        public void oneTouchPlay(final IHdmiControlCallback callback) {
            enforceAccessPermission();
            int pid = Binder.getCallingPid();
            Slog.d(TAG, "Proccess pid: " + pid + " is calling oneTouchPlay.");
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
        public void addHdmiControlStatusChangeListener(
                final IHdmiControlStatusChangeListener listener) {
            enforceAccessPermission();
            HdmiControlService.this.addHdmiControlStatusChangeListener(listener);
        }

        @Override
        public void removeHdmiControlStatusChangeListener(
                final IHdmiControlStatusChangeListener listener) {
            enforceAccessPermission();
            HdmiControlService.this.removeHdmiControlStatusChangeListener(listener);
        }

        @Override
        public void addHdmiCecVolumeControlFeatureListener(
                final IHdmiCecVolumeControlFeatureListener listener) {
            enforceAccessPermission();
            HdmiControlService.this.addHdmiCecVolumeControlFeatureListener(listener);
        }

        @Override
        public void removeHdmiCecVolumeControlFeatureListener(
                final IHdmiCecVolumeControlFeatureListener listener) {
            enforceAccessPermission();
            HdmiControlService.this.removeHdmiControlVolumeControlStatusChangeListener(listener);
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
            return HdmiControlService.this.getPortInfo() == null
                ? Collections.<HdmiPortInfo>emptyList()
                : HdmiControlService.this.getPortInfo();
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
            // TODO(shubang): handle getSystemAudioMode() for all device types
            enforceAccessPermission();
            HdmiCecLocalDeviceTv tv = tv();
            HdmiCecLocalDeviceAudioSystem audioSystem = audioSystem();
            return (tv != null && tv.isSystemAudioActivated())
                    || (audioSystem != null && audioSystem.isSystemAudioActivated());
        }

        @Override
        public int getPhysicalAddress() {
            enforceAccessPermission();
            synchronized (mLock) {
                return mPhysicalAddress;
            }
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
            if (tv != null) {
                synchronized (mLock) {
                    return tv.getSafeCecDevicesLocked();
                }
            } else {
                HdmiCecLocalDeviceAudioSystem audioSystem = audioSystem();
                synchronized (mLock) {
                    return (audioSystem == null)
                        ? Collections.<HdmiDeviceInfo>emptyList()
                        : audioSystem.getSafeCecDevicesLocked();
                }
            }
        }

        @Override
        public void powerOffRemoteDevice(int logicalAddress, int powerStatus) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    Slog.w(TAG, "Device "
                            + logicalAddress + " power status is " + powerStatus
                            + " before standby command sent out");
                    sendCecCommand(HdmiCecMessageBuilder.buildStandby(
                            getRemoteControlSourceAddress(), logicalAddress));
                }
            });
        }

        @Override
        public void powerOnRemoteDevice(int logicalAddress, int powerStatus) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    Slog.i(TAG, "Device "
                            + logicalAddress + " power status is " + powerStatus
                            + " before power on command sent out");
                    if (getSwitchDevice() != null) {
                        getSwitchDevice().sendUserControlPressedAndReleased(
                                logicalAddress, HdmiCecKeycode.CEC_KEYCODE_POWER_ON_FUNCTION);
                    } else {
                        Slog.e(TAG, "Can't get the correct local device to handle routing.");
                    }
                }
            });
        }

        @Override
        // TODO(b/128427908): add a result callback
        public void askRemoteDeviceToBecomeActiveSource(int physicalAddress) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiCecMessage setStreamPath = HdmiCecMessageBuilder.buildSetStreamPath(
                            getRemoteControlSourceAddress(), physicalAddress);
                    if (pathToPortId(physicalAddress) != Constants.INVALID_PORT_ID) {
                        if (getSwitchDevice() != null) {
                            getSwitchDevice().handleSetStreamPath(setStreamPath);
                        } else {
                            Slog.e(TAG, "Can't get the correct local device to handle routing.");
                        }
                    }
                    sendCecCommand(setStreamPath);
                }
            });
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
                        device = audioSystem();
                    }
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
        public boolean isHdmiCecVolumeControlEnabled() {
            enforceAccessPermission();
            return HdmiControlService.this.isHdmiCecVolumeControlEnabled();
        }

        @Override
        public void setHdmiCecVolumeControlEnabled(final boolean isHdmiCecVolumeControlEnabled) {
            enforceAccessPermission();
            long token = Binder.clearCallingIdentity();
            try {
                HdmiControlService.this.setHdmiCecVolumeControlEnabled(
                        isHdmiCecVolumeControlEnabled);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void reportAudioStatus(final int deviceType, final int volume, final int maxVolume,
                final boolean isMute) {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiCecLocalDevice device = mCecController.getLocalDevice(deviceType);
                    if (device == null) {
                        Slog.w(TAG, "Local device not available");
                        return;
                    }
                    if (audioSystem() == null) {
                        Slog.w(TAG, "audio system is not available");
                        return;
                    }
                    if (!audioSystem().isSystemAudioActivated()) {
                        Slog.w(TAG, "audio system is not in system audio mode");
                        return;
                    }
                    audioSystem().reportAudioStatus(Constants.ADDR_TV);
                }
            });
        }

        @Override
        public void setSystemAudioModeOnForAudioOnlySource() {
            enforceAccessPermission();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    if (!isAudioSystemDevice()) {
                        Slog.e(TAG, "Not an audio system device. Won't set system audio mode on");
                        return;
                    }
                    if (audioSystem() == null) {
                        Slog.e(TAG, "Audio System local device is not registered");
                        return;
                    }
                    if (!audioSystem().checkSupportAndSetSystemAudioMode(true)) {
                        Slog.e(TAG, "System Audio Mode is not supported.");
                        return;
                    }
                    sendCecCommand(
                            HdmiCecMessageBuilder.buildSetSystemAudioMode(
                                    audioSystem().mAddress, Constants.ADDR_BROADCAST, true));
                }
            });
        }

        @Override
        public void onShellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out,
                @Nullable FileDescriptor err, String[] args,
                @Nullable ShellCallback callback, ResultReceiver resultReceiver)
                throws RemoteException {
            enforceAccessPermission();
            new HdmiControlShellCommand(this)
                    .exec(this, in, out, err, args, callback, resultReceiver);
        }

        @Override
        protected void dump(FileDescriptor fd, final PrintWriter writer, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, writer)) return;
            final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");

            pw.println("mProhibitMode: " + mProhibitMode);
            pw.println("mPowerStatus: " + mPowerStatus);

            // System settings
            pw.println("System_settings:");
            pw.increaseIndent();
            pw.println("mHdmiControlEnabled: " + mHdmiControlEnabled);
            pw.println("mMhlInputChangeEnabled: " + mMhlInputChangeEnabled);
            pw.println("mSystemAudioActivated: " + isSystemAudioActivated());
            pw.println("mHdmiCecVolumeControlEnabled " + mHdmiCecVolumeControlEnabled);
            pw.decreaseIndent();

            pw.println("mMhlController: ");
            pw.increaseIndent();
            mMhlController.dump(pw);
            pw.decreaseIndent();

            HdmiUtils.dumpIterable(pw, "mPortInfo:", mPortInfo);
            if (mCecController != null) {
                pw.println("mCecController: ");
                pw.increaseIndent();
                mCecController.dump(pw);
                pw.decreaseIndent();
            }
        }
    }

    // Get the source address to send out commands to devices connected to the current device
    // when other services interact with HdmiControlService.
    private int getRemoteControlSourceAddress() {
        if (isAudioSystemDevice()) {
            return audioSystem().getDeviceInfo().getLogicalAddress();
        } else if (isPlaybackDevice()) {
            return playback().getDeviceInfo().getLogicalAddress();
        }
        return ADDR_UNREGISTERED;
    }

    // Get the switch device to do CEC routing control
    @Nullable
    private HdmiCecLocalDeviceSource getSwitchDevice() {
        if (isAudioSystemDevice()) {
            return audioSystem();
        }
        if (isPlaybackDevice()) {
            return playback();
        }
        return null;
    }

    @ServiceThreadOnly
    @VisibleForTesting
    protected void oneTouchPlay(final IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        if (!mAddressAllocated) {
            mOtpCallbackPendingAddressAllocation = callback;
            Slog.d(TAG, "Local device is under address allocation. "
                        + "Save OTP callback for later process.");
            return;
        }

        HdmiCecLocalDeviceSource source = playback();
        if (source == null) {
            source = audioSystem();
        }

        if (source == null) {
            Slog.w(TAG, "Local source device not available");
            invokeCallback(callback, HdmiControlManager.RESULT_SOURCE_NOT_AVAILABLE);
            return;
        }
        source.oneTouchPlay(callback);
    }

    @ServiceThreadOnly
    private void queryDisplayStatus(final IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        if (!mAddressAllocated) {
            mDisplayStatusCallback = callback;
            Slog.d(TAG, "Local device is under address allocation. "
                        + "Queue display callback for later process.");
            return;
        }

        HdmiCecLocalDevicePlayback source = playback();
        if (source == null) {
            Slog.w(TAG, "Local playback device not available");
            invokeCallback(callback, HdmiControlManager.RESULT_SOURCE_NOT_AVAILABLE);
            return;
        }
        source.queryDisplayStatus(callback);
    }

    private void addHdmiControlStatusChangeListener(
            final IHdmiControlStatusChangeListener listener) {
        final HdmiControlStatusChangeListenerRecord record =
                new HdmiControlStatusChangeListenerRecord(listener);
        try {
            listener.asBinder().linkToDeath(record, 0);
        } catch (RemoteException e) {
            Slog.w(TAG, "Listener already died");
            return;
        }
        synchronized (mLock) {
            mHdmiControlStatusChangeListenerRecords.add(record);
        }

        // Inform the listener of the initial state of each HDMI port by generating
        // hotplug events.
        runOnServiceThread(new Runnable() {
            @Override
            public void run() {
                synchronized (mLock) {
                    if (!mHdmiControlStatusChangeListenerRecords.contains(record)) return;
                }

                // Return the current status of mHdmiControlEnabled;
                synchronized (mLock) {
                    invokeHdmiControlStatusChangeListenerLocked(listener, mHdmiControlEnabled);
                }
            }
        });
    }

    private void removeHdmiControlStatusChangeListener(
            final IHdmiControlStatusChangeListener listener) {
        synchronized (mLock) {
            for (HdmiControlStatusChangeListenerRecord record :
                    mHdmiControlStatusChangeListenerRecords) {
                if (record.mListener.asBinder() == listener.asBinder()) {
                    listener.asBinder().unlinkToDeath(record, 0);
                    mHdmiControlStatusChangeListenerRecords.remove(record);
                    break;
                }
            }
        }
    }

    @VisibleForTesting
    void addHdmiCecVolumeControlFeatureListener(
            final IHdmiCecVolumeControlFeatureListener listener) {
        mHdmiCecVolumeControlFeatureListenerRecords.register(listener);

        runOnServiceThread(new Runnable() {
            @Override
            public void run() {
                // Return the current status of mHdmiCecVolumeControlEnabled;
                synchronized (mLock) {
                    try {
                        listener.onHdmiCecVolumeControlFeature(mHdmiCecVolumeControlEnabled);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Failed to report HdmiControlVolumeControlStatusChange: "
                                + mHdmiCecVolumeControlEnabled, e);
                    }
                }
            }
        });
    }

    @VisibleForTesting
    void removeHdmiControlVolumeControlStatusChangeListener(
            final IHdmiCecVolumeControlFeatureListener listener) {
        mHdmiCecVolumeControlFeatureListenerRecords.unregister(listener);
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
                for (HdmiPortInfo port : getPortInfo()) {
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

    private void announceHdmiControlStatusChange(boolean isEnabled) {
        assertRunOnServiceThread();
        synchronized (mLock) {
            for (HdmiControlStatusChangeListenerRecord record :
                    mHdmiControlStatusChangeListenerRecords) {
                invokeHdmiControlStatusChangeListenerLocked(record.mListener, isEnabled);
            }
        }
    }

    private void invokeHdmiControlStatusChangeListenerLocked(
            IHdmiControlStatusChangeListener listener, boolean isEnabled) {
        if (isEnabled) {
            queryDisplayStatus(new IHdmiControlCallback.Stub() {
                public void onComplete(int status) {
                    boolean isAvailable = true;
                    if (status == HdmiControlManager.POWER_STATUS_UNKNOWN
                            || status == HdmiControlManager.RESULT_EXCEPTION
                            || status == HdmiControlManager.RESULT_SOURCE_NOT_AVAILABLE) {
                        isAvailable = false;
                    }

                    try {
                        listener.onStatusChange(isEnabled, isAvailable);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Failed to report HdmiControlStatusChange: " + isEnabled
                                + " isAvailable: " + isAvailable, e);
                    }
                }
            });
            return;
        }

        try {
            listener.onStatusChange(isEnabled, false);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to report HdmiControlStatusChange: " + isEnabled
                    + " isAvailable: " + false, e);
        }
    }

    private void announceHdmiCecVolumeControlFeatureChange(boolean isEnabled) {
        assertRunOnServiceThread();
        mHdmiCecVolumeControlFeatureListenerRecords.broadcast(listener -> {
            try {
                listener.onHdmiCecVolumeControlFeature(isEnabled);
            } catch (RemoteException e) {
                Slog.e(TAG,
                        "Failed to report HdmiControlVolumeControlStatusChange: "
                                + isEnabled);
            }
        });
    }

    public HdmiCecLocalDeviceTv tv() {
        return (HdmiCecLocalDeviceTv) mCecController.getLocalDevice(HdmiDeviceInfo.DEVICE_TV);
    }

    boolean isTvDevice() {
        return mLocalDevices.contains(HdmiDeviceInfo.DEVICE_TV);
    }

    boolean isAudioSystemDevice() {
        return mLocalDevices.contains(HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);
    }

    boolean isPlaybackDevice() {
        return mLocalDevices.contains(HdmiDeviceInfo.DEVICE_PLAYBACK);
    }

    boolean isSwitchDevice() {
        return SystemProperties.getBoolean(
            PROPERTY_HDMI_IS_DEVICE_HDMI_CEC_SWITCH, false);
    }

    boolean isTvDeviceEnabled() {
        return isTvDevice() && tv() != null;
    }

    protected HdmiCecLocalDevicePlayback playback() {
        return (HdmiCecLocalDevicePlayback)
                mCecController.getLocalDevice(HdmiDeviceInfo.DEVICE_PLAYBACK);
    }

    public HdmiCecLocalDeviceAudioSystem audioSystem() {
        return (HdmiCecLocalDeviceAudioSystem) mCecController.getLocalDevice(
                HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);
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
    @VisibleForTesting
    void setPowerStatus(int powerStatus) {
        assertRunOnServiceThread();
        mPowerStatus = powerStatus;
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
        mPowerManager.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_HDMI,
                "android.server.hdmi:WAKE");
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

    @VisibleForTesting
    boolean isStandbyMessageReceived() {
        return mStandbyMessageReceived;
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
    @VisibleForTesting
    protected void onStandby(final int standbyAction) {
        assertRunOnServiceThread();
        mPowerStatus = HdmiControlManager.POWER_STATUS_TRANSIENT_TO_STANDBY;
        invokeVendorCommandListenersOnControlStateChanged(false,
                HdmiControlManager.CONTROL_STATE_CHANGED_REASON_STANDBY);

        final List<HdmiCecLocalDevice> devices = getAllLocalDevices();

        if (!isStandbyMessageReceived() && !canGoToStandby()) {
            mPowerStatus = HdmiControlManager.POWER_STATUS_STANDBY;
            for (HdmiCecLocalDevice device : devices) {
                device.onStandby(mStandbyMessageReceived, standbyAction);
            }
            return;
        }

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
        mMenuLanguage = language;

        if (isTvDeviceEnabled()) {
            tv().broadcastMenuLanguage(language);
            mCecController.setLanguage(language);
        }
    }

    /**
     * Gets the CEC menu language.
     *
     * <p>This is the ISO/FDIS 639-2 3 letter language code sent in the CEC message @{code <Set Menu
     * Language>}.
     * See HDMI 1.4b section CEC 13.6.2
     *
     * @see {@link Locale#getISO3Language()}
     */
    @ServiceThreadOnly
    String getLanguage() {
        assertRunOnServiceThread();
        return mMenuLanguage;
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
        if (!isAudioSystemDevice()) {
            mCecController.setOption(OptionKey.SYSTEM_CEC_CONTROL, false);
            mMhlController.setOption(OPTION_MHL_SERVICE_CONTROL, DISABLED);
        }
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
            mPowerManager.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_HDMI,
                    "android.server.hdmi:WAKE");
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

    void setHdmiCecVolumeControlEnabled(boolean isHdmiCecVolumeControlEnabled) {
        synchronized (mLock) {
            mHdmiCecVolumeControlEnabled = isHdmiCecVolumeControlEnabled;

            boolean storedValue = readBooleanSetting(Global.HDMI_CONTROL_VOLUME_CONTROL_ENABLED,
                    true);
            if (storedValue != isHdmiCecVolumeControlEnabled) {
                HdmiLogger.debug("Changing HDMI CEC volume control feature state: %s",
                        isHdmiCecVolumeControlEnabled);
                writeBooleanSetting(Global.HDMI_CONTROL_VOLUME_CONTROL_ENABLED,
                        isHdmiCecVolumeControlEnabled);
            }
        }
        announceHdmiCecVolumeControlFeatureChange(isHdmiCecVolumeControlEnabled);
    }

    boolean isHdmiCecVolumeControlEnabled() {
        synchronized (mLock) {
            return mHdmiCecVolumeControlEnabled;
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

    boolean isSystemAudioActivated() {
        synchronized (mLock) {
            return mSystemAudioActivated;
        }
    }

    void setSystemAudioActivated(boolean on) {
        synchronized (mLock) {
            mSystemAudioActivated = on;
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
            setHdmiCecVolumeControlEnabled(
                    readBooleanSetting(Global.HDMI_CONTROL_VOLUME_CONTROL_ENABLED, true));
            return;
        }

        mHdmiCecVolumeControlEnabled = false;
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
        announceHdmiControlStatusChange(enabled);

        return;
    }

    @ServiceThreadOnly
    private void enableHdmiControlService() {
        mCecController.setOption(OptionKey.ENABLE_CEC, true);
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
                        mCecController.setOption(OptionKey.SYSTEM_CEC_CONTROL, false);
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

    ActiveSource getLocalActiveSource() {
        synchronized (mLock) {
            return mActiveSource;
        }
    }

    void setActiveSource(int logicalAddress, int physicalAddress) {
        synchronized (mLock) {
            mActiveSource.logicalAddress = logicalAddress;
            mActiveSource.physicalAddress = physicalAddress;
        }
        // If the current device is a source device, check if the current Active Source matches
        // the local device info. Set mIsActiveSource of the local device accordingly.
        for (HdmiCecLocalDevice device : getAllLocalDevices()) {
            // mIsActiveSource only exists in source device, ignore this setting if the current
            // device is not an HdmiCecLocalDeviceSource.
            if (!(device instanceof HdmiCecLocalDeviceSource)) {
                continue;
            }
            if (logicalAddress == device.getDeviceInfo().getLogicalAddress()
                && physicalAddress == getPhysicalAddress()) {
                ((HdmiCecLocalDeviceSource) device).setIsActiveSource(true);
            } else {
                ((HdmiCecLocalDeviceSource) device).setIsActiveSource(false);
            }
        }
    }

    // This method should only be called when the device can be the active source
    // and all the device types call into this method.
    // For example, when receiving broadcast messages, all the device types will call this
    // method but only one of them will be the Active Source.
    protected void setAndBroadcastActiveSource(
            int physicalAddress, int deviceType, int source) {
        // If the device has both playback and audio system logical addresses,
        // playback will claim active source. Otherwise audio system will.
        if (deviceType == HdmiDeviceInfo.DEVICE_PLAYBACK) {
            HdmiCecLocalDevicePlayback playback = playback();
            playback.setIsActiveSource(true);
            playback.wakeUpIfActiveSource();
            playback.maySendActiveSource(source);
            setActiveSource(playback.mAddress, physicalAddress);
        }

        if (deviceType == HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM) {
            HdmiCecLocalDeviceAudioSystem audioSystem = audioSystem();
            if (playback() != null) {
                audioSystem.setIsActiveSource(false);
            } else {
                audioSystem.setIsActiveSource(true);
                audioSystem.wakeUpIfActiveSource();
                audioSystem.maySendActiveSource(source);
                setActiveSource(audioSystem.mAddress, physicalAddress);
            }
        }
    }

    // This method should only be called when the device can be the active source
    // and only one of the device types calls into this method.
    // For example, when receiving One Touch Play, only playback device handles it
    // and this method updates Active Source in all the device types sharing the same
    // Physical Address.
    protected void setAndBroadcastActiveSourceFromOneDeviceType(
            int sourceAddress, int physicalAddress) {
        // If the device has both playback and audio system logical addresses,
        // playback will claim active source. Otherwise audio system will.
        HdmiCecLocalDevicePlayback playback = playback();
        HdmiCecLocalDeviceAudioSystem audioSystem = audioSystem();
        if (playback != null) {
            playback.setIsActiveSource(true);
            playback.wakeUpIfActiveSource();
            playback.maySendActiveSource(sourceAddress);
            if (audioSystem != null) {
                audioSystem.setIsActiveSource(false);
            }
            setActiveSource(playback.mAddress, physicalAddress);
        } else {
            if (audioSystem != null) {
                audioSystem.setIsActiveSource(true);
                audioSystem.wakeUpIfActiveSource();
                audioSystem.maySendActiveSource(sourceAddress);
                setActiveSource(audioSystem.mAddress, physicalAddress);
            }
        }
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
