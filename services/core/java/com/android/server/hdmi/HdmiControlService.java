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
import static android.hardware.hdmi.HdmiControlManager.EARC_FEATURE_DISABLED;
import static android.hardware.hdmi.HdmiControlManager.EARC_FEATURE_ENABLED;
import static android.hardware.hdmi.HdmiControlManager.HDMI_CEC_CONTROL_ENABLED;
import static android.hardware.hdmi.HdmiControlManager.POWER_CONTROL_MODE_NONE;
import static android.hardware.hdmi.HdmiControlManager.SOUNDBAR_MODE_DISABLED;
import static android.hardware.hdmi.HdmiControlManager.SOUNDBAR_MODE_ENABLED;
import static android.hardware.hdmi.HdmiControlManager.TV_SEND_STANDBY_ON_SLEEP_ENABLED;

import static com.android.server.hdmi.Constants.ADDR_UNREGISTERED;
import static com.android.server.hdmi.Constants.DISABLED;
import static com.android.server.hdmi.Constants.ENABLED;
import static com.android.server.hdmi.Constants.HDMI_EARC_STATUS_ARC_PENDING;
import static com.android.server.hdmi.Constants.HDMI_EARC_STATUS_UNKNOWN;
import static com.android.server.hdmi.Constants.OPTION_MHL_ENABLE;
import static com.android.server.hdmi.Constants.OPTION_MHL_INPUT_SWITCHING;
import static com.android.server.hdmi.Constants.OPTION_MHL_POWER_CHARGE;
import static com.android.server.hdmi.Constants.OPTION_MHL_SERVICE_CONTROL;
import static com.android.server.power.ShutdownThread.SHUTDOWN_ACTION_PROPERTY;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.hardware.hdmi.DeviceFeatures;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiHotplugEvent;
import android.hardware.hdmi.HdmiPortInfo;
import android.hardware.hdmi.IHdmiCecSettingChangeListener;
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
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.media.AudioAttributes;
import android.media.AudioDescriptor;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioDeviceVolumeManager;
import android.media.AudioManager;
import android.media.AudioProfile;
import android.media.VolumeInfo;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
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
import android.provider.DeviceConfig;
import android.provider.Settings.Global;
import android.stats.hdmi.HdmiStatsEnums;
import android.sysprop.HdmiProperties;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.KeyEvent;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.SystemService;
import com.android.server.hdmi.HdmiAnnotations.ServiceThreadOnly;
import com.android.server.hdmi.HdmiCecController.AllocateAddressCallback;
import com.android.server.hdmi.HdmiCecLocalDevice.ActiveSource;
import com.android.server.hdmi.HdmiCecLocalDevice.PendingActionClearedCallback;
import com.android.server.hdmi.HdmiCecLocalDevice.StandbyCompletedCallback;

import libcore.util.EmptyArray;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * Provides a service for sending and processing HDMI control messages,
 * HDMI-CEC and MHL control command, and providing the information on both standard.
 *
 * Additionally takes care of establishing and managing an eARC connection.
 */
public class HdmiControlService extends SystemService {
    private static final String TAG = "HdmiControlService";
    private static final Locale HONG_KONG = new Locale("zh", "HK");
    private static final Locale MACAU = new Locale("zh", "MO");
    private static final String TAIWAN_HantLanguageTag = "zh-Hant-TW";
    private static final String HONG_KONG_HantLanguageTag = "zh-Hant-HK";
    private static final String HONG_KONG_YUE_HantLanguageTag = "yue-Hant-HK";
    private static final String MACAU_HantLanguageTag = "zh-Hant-MO";

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
        if (locale.equals(Locale.TAIWAN) || locale.equals(HONG_KONG) || locale.equals(MACAU) ||
                locale.toLanguageTag().equals(TAIWAN_HantLanguageTag) ||
                locale.toLanguageTag().equals(HONG_KONG_HantLanguageTag) ||
                locale.toLanguageTag().equals(HONG_KONG_YUE_HantLanguageTag) ||
                locale.toLanguageTag().equals(MACAU_HantLanguageTag)) {
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

    // The reason code to initiate initializeCec() and initializeEarc().
    static final int INITIATED_BY_ENABLE_CEC = 0;
    static final int INITIATED_BY_BOOT_UP = 1;
    static final int INITIATED_BY_SCREEN_ON = 2;
    static final int INITIATED_BY_WAKE_UP_MESSAGE = 3;
    static final int INITIATED_BY_HOTPLUG = 4;
    static final int INITIATED_BY_SOUNDBAR_MODE = 5;
    static final int INITIATED_BY_ENABLE_EARC = 6;

    // The reason code representing the intent action that drives the standby
    // procedure. The procedure starts either by Intent.ACTION_SCREEN_OFF or
    // Intent.ACTION_SHUTDOWN.
    static final int STANDBY_SCREEN_OFF = 0;
    static final int STANDBY_SHUTDOWN = 1;

    private HdmiCecNetwork mHdmiCecNetwork;

    static final int WAKE_UP_SCREEN_ON = 0;
    static final int WAKE_UP_BOOT_UP = 1;

    // The reason code for starting the wake-up procedure. This procedure starts either by
    // Intent.ACTION_SCREEN_ON or after boot-up.
    @IntDef({
            WAKE_UP_SCREEN_ON,
            WAKE_UP_BOOT_UP
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface WakeReason {
    }

    // Timeout in millisecond for device clean up (5s).
    // Normal actions timeout is 2s but some of them would have several sequences of timeout.
    static final int DEVICE_CLEANUP_TIMEOUT = 5000;

    @VisibleForTesting
    static final AudioDeviceAttributes AUDIO_OUTPUT_DEVICE_HDMI = new AudioDeviceAttributes(
            AudioDeviceAttributes.ROLE_OUTPUT, AudioDeviceInfo.TYPE_HDMI, "");
    @VisibleForTesting
    static final AudioDeviceAttributes AUDIO_OUTPUT_DEVICE_HDMI_ARC =
            new AudioDeviceAttributes(AudioDeviceAttributes.ROLE_OUTPUT,
                    AudioDeviceInfo.TYPE_HDMI_ARC, "");
    static final AudioDeviceAttributes AUDIO_OUTPUT_DEVICE_HDMI_EARC =
            new AudioDeviceAttributes(AudioDeviceAttributes.ROLE_OUTPUT,
                    AudioDeviceInfo.TYPE_HDMI_EARC, "");

    // Audio output devices used for absolute volume behavior
    private static final List<AudioDeviceAttributes> AVB_AUDIO_OUTPUT_DEVICES =
            List.of(AUDIO_OUTPUT_DEVICE_HDMI,
                    AUDIO_OUTPUT_DEVICE_HDMI_ARC,
                    AUDIO_OUTPUT_DEVICE_HDMI_EARC);

    // Audio output devices used for absolute volume behavior on TV panels
    private static final List<AudioDeviceAttributes> TV_AVB_AUDIO_OUTPUT_DEVICES =
            List.of(AUDIO_OUTPUT_DEVICE_HDMI_ARC,
                    AUDIO_OUTPUT_DEVICE_HDMI_EARC);

    // Audio output devices used for absolute volume behavior on Playback devices
    private static final List<AudioDeviceAttributes> PLAYBACK_AVB_AUDIO_OUTPUT_DEVICES =
            List.of(AUDIO_OUTPUT_DEVICE_HDMI);

    private static final List<Integer> ABSOLUTE_VOLUME_BEHAVIORS =
            List.of(AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE,
                    AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_ADJUST_ONLY);

    private static final List<Integer> FULL_AND_ABSOLUTE_VOLUME_BEHAVIORS =
            List.of(AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL,
                    AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE,
                    AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_ADJUST_ONLY);

    // AudioAttributes for STREAM_MUSIC
    @VisibleForTesting
    static final AudioAttributes STREAM_MUSIC_ATTRIBUTES =
            new AudioAttributes.Builder().setLegacyStreamType(AudioManager.STREAM_MUSIC).build();

    private final Executor mServiceThreadExecutor = new Executor() {
        @Override
        public void execute(Runnable r) {
            runOnServiceThread(r);
        }
    };

    Executor getServiceThreadExecutor() {
        return mServiceThreadExecutor;
    }

    // Logical address of the active source.
    @GuardedBy("mLock")
    protected final ActiveSource mActiveSource = new ActiveSource();

    // Whether System Audio Mode is activated or not.
    @GuardedBy("mLock")
    private boolean mSystemAudioActivated = false;

    // Whether HDMI CEC volume control is enabled or not.
    @GuardedBy("mLock")
    @HdmiControlManager.VolumeControl
    private int mHdmiCecVolumeControl;

    // Caches the volume behaviors of all audio output devices in AVB_AUDIO_OUTPUT_DEVICES.
    @GuardedBy("mLock")
    private Map<AudioDeviceAttributes, Integer> mAudioDeviceVolumeBehaviors = new HashMap<>();

    // Maximum volume of AudioManager.STREAM_MUSIC. Set upon gaining access to system services.
    private int mStreamMusicMaxVolume;

    // Make sure HdmiCecConfig is instantiated and the XMLs are read.
    private HdmiCecConfig mHdmiCecConfig;

    // Timeout value for start ARC action after an established eARC connection was terminated,
    // e.g. because eARC was disabled in Settings.
    private static final int EARC_TRIGGER_START_ARC_ACTION_DELAY = 500;

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
                        onWakeUp(WAKE_UP_SCREEN_ON);
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

    // Type of CEC logical devices hosted in the system. Stored in the unmodifiable list.
    private final List<Integer> mCecLocalDevices;

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

    // List of records for CEC setting change listener to handle the caller killed in action.
    @GuardedBy("mLock")
    private final ArrayMap<String, RemoteCallbackList<IHdmiCecSettingChangeListener>>
            mHdmiCecSettingChangeListenerRecords = new ArrayMap<>();

    @GuardedBy("mLock")
    private InputChangeListenerRecord mInputChangeListenerRecord;

    @GuardedBy("mLock")
    private HdmiRecordListenerRecord mRecordListenerRecord;

    // Set to true while HDMI control is enabled. If set to false, HDMI-CEC/MHL protocol
    // handling will be disabled and no request will be handled.
    @GuardedBy("mLock")
    @HdmiControlManager.HdmiCecControl
    private int mHdmiControlEnabled;

    // Set to true while the eARC feature is supported by the hardware on at least one port
    // and the eARC HAL is present.
    @GuardedBy("mLock")
    @VisibleForTesting
    private boolean mEarcSupported;

    // Set to true while the eARC feature is enabled.
    @GuardedBy("mLock")
    private boolean mEarcEnabled;

    private int mEarcPortId = -1;

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

    private HdmiCecPowerStatusController mPowerStatusController;

    @Nullable
    private HdmiEarcController mEarcController;

    @Nullable
    private HdmiEarcLocalDevice mEarcLocalDevice;

    @ServiceThreadOnly
    private String mMenuLanguage = localeToMenuLanguage(Locale.getDefault());

    @ServiceThreadOnly
    private boolean mStandbyMessageReceived = false;

    @ServiceThreadOnly
    private boolean mWakeUpMessageReceived = false;

    @ServiceThreadOnly
    private boolean mSoundbarModeFeatureFlagEnabled = false;

    @ServiceThreadOnly
    private boolean mEarcTxFeatureFlagEnabled = false;

    @ServiceThreadOnly
    private boolean mNumericSoundbarVolumeUiOnTvFeatureFlagEnabled = false;

    @ServiceThreadOnly
    private boolean mTransitionFromArcToEarcTxEnabled = false;

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
    private DeviceConfigWrapper mDeviceConfig;

    @Nullable
    private WakeLockWrapper mWakeLock;

    @Nullable
    private PowerManagerWrapper mPowerManager;

    @Nullable
    private PowerManagerInternalWrapper mPowerManagerInternal;

    @Nullable
    private AudioManagerWrapper mAudioManager;

    @Nullable
    private AudioDeviceVolumeManagerWrapper mAudioDeviceVolumeManager;

    @Nullable
    private Looper mIoLooper;

    @Nullable
    private DisplayManager mDisplayManager;

    @HdmiControlManager.HdmiCecVersion
    private int mCecVersion;

    // Last input port before switching to the MHL port. Should switch back to this port
    // when the mobile device sends the request one touch play with off.
    // Gets invalidated if we go to other port/input.
    @ServiceThreadOnly
    private int mLastInputMhl = Constants.INVALID_PORT_ID;

    // Set to true if the logical address allocation is completed.
    private boolean mAddressAllocated = false;

    // Whether a CEC-enabled sink is connected to the playback device
    private boolean mIsCecAvailable = false;

    // Object that handles logging statsd atoms.
    // Use getAtomWriter() instead of accessing directly, to allow dependency injection for testing.
    private HdmiCecAtomWriter mAtomWriter = new HdmiCecAtomWriter();

    private CecMessageBuffer mCecMessageBuffer;

    private final SelectRequestBuffer mSelectRequestBuffer = new SelectRequestBuffer();

    /**
     * Constructor for testing.
     *
     * Takes fakes for AudioManager and AudioDeviceVolumeManager.
     *
     * This is especially important for AudioDeviceVolumeManager because a normally instantiated
     * AudioDeviceVolumeManager can access the "real" AudioService on the DUT.
     */
    @VisibleForTesting HdmiControlService(Context context, List<Integer> deviceTypes,
            AudioManagerWrapper audioManager,
            AudioDeviceVolumeManagerWrapper audioDeviceVolumeManager) {
        super(context);
        mCecLocalDevices = deviceTypes;
        mSettingsObserver = new SettingsObserver(mHandler);
        mHdmiCecConfig = new HdmiCecConfig(context);
        mDeviceConfig = new DeviceConfigWrapper();
        mAudioManager = audioManager;
        mAudioDeviceVolumeManager = audioDeviceVolumeManager;
    }

    public HdmiControlService(Context context) {
        super(context);
        mCecLocalDevices = readDeviceTypes();
        mSettingsObserver = new SettingsObserver(mHandler);
        mHdmiCecConfig = new HdmiCecConfig(context);
        mDeviceConfig = new DeviceConfigWrapper();
    }

    @VisibleForTesting
    protected List<HdmiProperties.cec_device_types_values> getCecDeviceTypes() {
        return HdmiProperties.cec_device_types();
    }

    @VisibleForTesting
    protected List<Integer> getDeviceTypes() {
        return HdmiProperties.device_type();
    }

    /**
     * Extracts a list of integer device types from the sysprop ro.hdmi.cec_device_types.
     * If ro.hdmi.cec_device_types is not set, reads from ro.hdmi.device.type instead.
     * @return the list of integer device types
     */
    @VisibleForTesting
    protected List<Integer> readDeviceTypes() {
        List<HdmiProperties.cec_device_types_values> cecDeviceTypes = getCecDeviceTypes();
        if (!cecDeviceTypes.isEmpty()) {
            if (cecDeviceTypes.contains(null)) {
                Slog.w(TAG, "Error parsing ro.hdmi.cec_device_types: " + SystemProperties.get(
                        "ro.hdmi.cec_device_types"));
            }
            return cecDeviceTypes.stream()
                    .map(HdmiControlService::enumToIntDeviceType)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } else {
            // If ro.hdmi.cec_device_types isn't set, fall back to reading ro.hdmi.device_type
            List<Integer> deviceTypes = getDeviceTypes();
            if (deviceTypes.contains(null)) {
                Slog.w(TAG, "Error parsing ro.hdmi.device_type: " + SystemProperties.get(
                        "ro.hdmi.device_type"));
            }
            return deviceTypes.stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
    }

    /**
     * Converts an enum representing a value in ro.hdmi.cec_device_types to an integer device type.
     * Returns null if the input is null or an unrecognized device type.
     */
    @Nullable
    private static Integer enumToIntDeviceType(
            @Nullable HdmiProperties.cec_device_types_values cecDeviceType) {
        if (cecDeviceType == null) {
            return null;
        }
        switch (cecDeviceType) {
            case TV:
                return HdmiDeviceInfo.DEVICE_TV;
            case RECORDING_DEVICE:
                return HdmiDeviceInfo.DEVICE_RECORDER;
            case RESERVED:
                return HdmiDeviceInfo.DEVICE_RESERVED;
            case TUNER:
                return HdmiDeviceInfo.DEVICE_TUNER;
            case PLAYBACK_DEVICE:
                return HdmiDeviceInfo.DEVICE_PLAYBACK;
            case AUDIO_SYSTEM:
                return HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM;
            case PURE_CEC_SWITCH:
                return HdmiDeviceInfo.DEVICE_PURE_CEC_SWITCH;
            case VIDEO_PROCESSOR:
                return HdmiDeviceInfo.DEVICE_VIDEO_PROCESSOR;
            default:
                Slog.w(TAG, "Unrecognized device type in ro.hdmi.cec_device_types: "
                        + cecDeviceType.getPropValue());
                return null;
        }
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
        initService();
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

    @VisibleForTesting
    void initService() {
        if (mIoLooper == null) {
            mIoThread.start();
            mIoLooper = mIoThread.getLooper();
        }

        if (mPowerStatusController == null) {
            mPowerStatusController = new HdmiCecPowerStatusController(this);
        }
        mPowerStatusController.setPowerStatus(getInitialPowerStatus());
        setProhibitMode(false);
        mHdmiControlEnabled = mHdmiCecConfig.getIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED);

        mSoundbarModeFeatureFlagEnabled = mDeviceConfig.getBoolean(
                Constants.DEVICE_CONFIG_FEATURE_FLAG_SOUNDBAR_MODE, true);
        mEarcTxFeatureFlagEnabled = mDeviceConfig.getBoolean(
                Constants.DEVICE_CONFIG_FEATURE_FLAG_ENABLE_EARC_TX, true);
        mTransitionFromArcToEarcTxEnabled = mDeviceConfig.getBoolean(
                Constants.DEVICE_CONFIG_FEATURE_FLAG_TRANSITION_ARC_TO_EARC_TX, true);
        mNumericSoundbarVolumeUiOnTvFeatureFlagEnabled = mDeviceConfig.getBoolean(
                Constants.DEVICE_CONFIG_FEATURE_FLAG_TV_NUMERIC_SOUNDBAR_VOLUME_UI, true);

        synchronized (mLock) {
            mEarcEnabled = (mHdmiCecConfig.getIntValue(
                    HdmiControlManager.SETTING_NAME_EARC_ENABLED) == EARC_FEATURE_ENABLED);
            if (isTvDevice()) {
                mEarcEnabled &= mEarcTxFeatureFlagEnabled;
            }
        }
        setHdmiCecVolumeControlEnabledInternal(getHdmiCecConfig().getIntValue(
                HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE));
        mMhlInputChangeEnabled = readBooleanSetting(Global.MHL_INPUT_SWITCHING_ENABLED, true);

        if (mCecMessageBuffer == null) {
            mCecMessageBuffer = new CecMessageBuffer(this);
        }
        if (mCecController == null) {
            mCecController = HdmiCecController.create(this, getAtomWriter());
        }
        if (mCecController == null) {
            Slog.i(TAG, "Device does not support HDMI-CEC.");
            return;
        }
        if (mMhlController == null) {
            mMhlController = HdmiMhlControllerStub.create(this);
        }
        if (!mMhlController.isReady()) {
            Slog.i(TAG, "Device does not support MHL-control.");
        }
        if (mEarcController == null) {
            mEarcController = HdmiEarcController.create(this);
        }
        if (mEarcController == null) {
            Slog.i(TAG, "Device does not support eARC.");
        }
        mHdmiCecNetwork = new HdmiCecNetwork(this, mCecController, mMhlController);
        if (isCecControlEnabled()) {
            initializeCec(INITIATED_BY_BOOT_UP);
        } else {
            mCecController.enableCec(false);
        }

        synchronized (mLock) {
            mMhlDevices = Collections.emptyList();
        }

        mHdmiCecNetwork.initPortInfo();
        List<HdmiPortInfo> ports = getPortInfo();
        synchronized (mLock) {
            mEarcSupported = false;
            for (HdmiPortInfo port : ports) {
                boolean earcSupportedOnPort = port.isEarcSupported();
                if (earcSupportedOnPort && mEarcSupported) {
                    // This means that more than 1 port supports eARC.
                    // The HDMI specification only allows 1 active eARC connection.
                    // Android does not support devices with multiple eARC-enabled ports.
                    // Consider eARC not supported in this case.
                    Slog.e(TAG, "HDMI eARC supported on more than 1 port.");
                    mEarcSupported = false;
                    mEarcPortId = -1;
                    break;
                } else if (earcSupportedOnPort) {
                    mEarcPortId = port.getId();
                    mEarcSupported = earcSupportedOnPort;
                }
            }
            mEarcSupported &= (mEarcController != null);
        }
        if (isEarcSupported()) {
            if (isEarcEnabled()) {
                initializeEarc(INITIATED_BY_BOOT_UP);
            } else {
                setEarcEnabledInHal(false, false);
            }
        }

        mHdmiCecConfig.registerChangeListener(HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED,
                new HdmiCecConfig.SettingChangeListener() {
                    @Override
                    public void onChange(String setting) {
                        @HdmiControlManager.HdmiCecControl int enabled = mHdmiCecConfig.getIntValue(
                                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED);
                        setCecEnabled(enabled);
                    }
                }, mServiceThreadExecutor);
        mHdmiCecConfig.registerChangeListener(HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                new HdmiCecConfig.SettingChangeListener() {
                    @Override
                    public void onChange(String setting) {
                        initializeCec(INITIATED_BY_ENABLE_CEC);
                    }
                }, mServiceThreadExecutor);
        mHdmiCecConfig.registerChangeListener(HdmiControlManager.CEC_SETTING_NAME_ROUTING_CONTROL,
                new HdmiCecConfig.SettingChangeListener() {
                    @Override
                    public void onChange(String setting) {
                        boolean enabled = mHdmiCecConfig.getIntValue(
                                HdmiControlManager.CEC_SETTING_NAME_ROUTING_CONTROL)
                                    == HdmiControlManager.ROUTING_CONTROL_ENABLED;
                        if (isAudioSystemDevice()) {
                            if (audioSystem() == null) {
                                Slog.w(TAG, "Switch device has not registered yet."
                                        + " Can't turn routing on.");
                            } else {
                                audioSystem().setRoutingControlFeatureEnabled(enabled);
                            }
                        }
                    }
                }, mServiceThreadExecutor);
        mHdmiCecConfig.registerChangeListener(
                HdmiControlManager.CEC_SETTING_NAME_SYSTEM_AUDIO_CONTROL,
                new HdmiCecConfig.SettingChangeListener() {
                    @Override
                    public void onChange(String setting) {
                        boolean enabled = mHdmiCecConfig.getIntValue(
                                HdmiControlManager.CEC_SETTING_NAME_SYSTEM_AUDIO_CONTROL)
                                    == HdmiControlManager.SYSTEM_AUDIO_CONTROL_ENABLED;
                        if (isTvDeviceEnabled()) {
                            tv().setSystemAudioControlFeatureEnabled(enabled);
                        }
                        if (isAudioSystemDevice()) {
                            if (audioSystem() == null) {
                                Slog.e(TAG, "Audio System device has not registered yet."
                                        + " Can't turn system audio mode on.");
                            } else {
                                audioSystem().onSystemAudioControlFeatureSupportChanged(enabled);
                            }
                        }
                    }
                }, mServiceThreadExecutor);
        mHdmiCecConfig.registerChangeListener(
                HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE,
                new HdmiCecConfig.SettingChangeListener() {
                    @Override
                    public void onChange(String setting) {
                        setHdmiCecVolumeControlEnabledInternal(getHdmiCecConfig().getIntValue(
                                HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE));
                    }
                }, mServiceThreadExecutor);
        mHdmiCecConfig.registerChangeListener(
                HdmiControlManager.CEC_SETTING_NAME_TV_WAKE_ON_ONE_TOUCH_PLAY,
                new HdmiCecConfig.SettingChangeListener() {
                    @Override
                    public void onChange(String setting) {
                        if (isTvDeviceEnabled()) {
                            mCecController.enableWakeupByOtp(tv().getAutoWakeup());
                        }
                    }
                }, mServiceThreadExecutor);
        mHdmiCecConfig.registerChangeListener(HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_TV,
                new HdmiCecConfig.SettingChangeListener() {
                    @Override
                    public void onChange(String setting) {
                        reportFeatures(true);
                    }
                },
                mServiceThreadExecutor);
        mHdmiCecConfig.registerChangeListener(
                HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_ROOT_MENU,
                new HdmiCecConfig.SettingChangeListener() {
                    @Override
                    public void onChange(String setting) {
                        reportFeatures(false);
                    }
                },
                mServiceThreadExecutor);
        mHdmiCecConfig.registerChangeListener(
                HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_SETUP_MENU,
                new HdmiCecConfig.SettingChangeListener() {
                    @Override
                    public void onChange(String setting) {
                        reportFeatures(false);
                    }
                },
                mServiceThreadExecutor);
        mHdmiCecConfig.registerChangeListener(
                HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_CONTENTS_MENU,
                new HdmiCecConfig.SettingChangeListener() {
                    @Override
                    public void onChange(String setting) {
                        reportFeatures(false);
                    }
                },
                mServiceThreadExecutor);
        mHdmiCecConfig.registerChangeListener(
                HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_TOP_MENU,
                new HdmiCecConfig.SettingChangeListener() {
                    @Override
                    public void onChange(String setting) {
                        reportFeatures(false);
                    }
                },
                mServiceThreadExecutor);
        mHdmiCecConfig.registerChangeListener(
                HdmiControlManager
                        .CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_MEDIA_CONTEXT_SENSITIVE_MENU,
                new HdmiCecConfig.SettingChangeListener() {
                    @Override
                    public void onChange(String setting) {
                        reportFeatures(false);
                    }
                },
                mServiceThreadExecutor);

        if (isTvDevice()) {
            mDeviceConfig.addOnPropertiesChangedListener(getContext().getMainExecutor(),
                    new DeviceConfig.OnPropertiesChangedListener() {
                        @Override
                        public void onPropertiesChanged(DeviceConfig.Properties properties) {
                            mEarcTxFeatureFlagEnabled = properties.getBoolean(
                                    Constants.DEVICE_CONFIG_FEATURE_FLAG_ENABLE_EARC_TX,
                                    true);
                            boolean earcEnabledSetting = mHdmiCecConfig.getIntValue(
                                    HdmiControlManager.SETTING_NAME_EARC_ENABLED)
                                    == EARC_FEATURE_ENABLED;
                            setEarcEnabled(earcEnabledSetting && mEarcTxFeatureFlagEnabled
                                    ? EARC_FEATURE_ENABLED : EARC_FEATURE_DISABLED);
                        }
                    });
        }

        mHdmiCecConfig.registerChangeListener(HdmiControlManager.SETTING_NAME_EARC_ENABLED,
                new HdmiCecConfig.SettingChangeListener() {
                    @Override
                    public void onChange(String setting) {
                        if (isTvDevice()) {
                            boolean earcEnabledSetting = mHdmiCecConfig.getIntValue(
                                    HdmiControlManager.SETTING_NAME_EARC_ENABLED)
                                    == EARC_FEATURE_ENABLED;
                            setEarcEnabled(earcEnabledSetting && mEarcTxFeatureFlagEnabled
                                    ? EARC_FEATURE_ENABLED : EARC_FEATURE_DISABLED);
                        } else {
                            setEarcEnabled(mHdmiCecConfig.getIntValue(
                                    HdmiControlManager.SETTING_NAME_EARC_ENABLED));
                        }
                    }
                },
                mServiceThreadExecutor);

        mDeviceConfig.addOnPropertiesChangedListener(getContext().getMainExecutor(),
                new DeviceConfig.OnPropertiesChangedListener() {
                    @Override
                    public void onPropertiesChanged(DeviceConfig.Properties properties) {
                        mSoundbarModeFeatureFlagEnabled = properties.getBoolean(
                                Constants.DEVICE_CONFIG_FEATURE_FLAG_SOUNDBAR_MODE,
                                true);
                        boolean soundbarModeSetting = mHdmiCecConfig.getIntValue(
                                HdmiControlManager.CEC_SETTING_NAME_SOUNDBAR_MODE)
                                == SOUNDBAR_MODE_ENABLED;
                        setSoundbarMode(soundbarModeSetting && mSoundbarModeFeatureFlagEnabled
                                ? SOUNDBAR_MODE_ENABLED : SOUNDBAR_MODE_DISABLED);
                    }
                });
        mHdmiCecConfig.registerChangeListener(HdmiControlManager.CEC_SETTING_NAME_SOUNDBAR_MODE,
                new HdmiCecConfig.SettingChangeListener() {
                    @Override
                    public void onChange(String setting) {
                        boolean soundbarModeSetting = mHdmiCecConfig.getIntValue(
                                HdmiControlManager.CEC_SETTING_NAME_SOUNDBAR_MODE)
                                == SOUNDBAR_MODE_ENABLED;
                        setSoundbarMode(soundbarModeSetting && mSoundbarModeFeatureFlagEnabled
                                ? SOUNDBAR_MODE_ENABLED : SOUNDBAR_MODE_DISABLED);
                    }
                }, mServiceThreadExecutor);

        mDeviceConfig.addOnPropertiesChangedListener(getContext().getMainExecutor(),
                new DeviceConfig.OnPropertiesChangedListener() {
                    @Override
                    public void onPropertiesChanged(DeviceConfig.Properties properties) {
                        mTransitionFromArcToEarcTxEnabled = properties.getBoolean(
                                Constants.DEVICE_CONFIG_FEATURE_FLAG_TRANSITION_ARC_TO_EARC_TX,
                                true);
                    }
                });

        mDeviceConfig.addOnPropertiesChangedListener(getContext().getMainExecutor(),
                new DeviceConfig.OnPropertiesChangedListener() {
                    @Override
                    public void onPropertiesChanged(DeviceConfig.Properties properties) {
                        mNumericSoundbarVolumeUiOnTvFeatureFlagEnabled = properties.getBoolean(
                                Constants.DEVICE_CONFIG_FEATURE_FLAG_TV_NUMERIC_SOUNDBAR_VOLUME_UI,
                                true);
                        checkAndUpdateAbsoluteVolumeBehavior();
                    }
                });
    }
    /** Returns true if the device screen is off */
    boolean isScreenOff() {
        return mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY).getState() == Display.STATE_OFF;
    }

    private void bootCompleted() {
        // on boot, if device is interactive, set HDMI CEC state as powered on as well
        if (mPowerManager.isInteractive() && isPowerStandbyOrTransient()) {
            mPowerStatusController.setPowerStatus(HdmiControlManager.POWER_STATUS_ON);
            // Start all actions that were queued because the device was in standby
            if (mAddressAllocated) {
                for (HdmiCecLocalDevice localDevice : getAllCecLocalDevices()) {
                    localDevice.startQueuedActions();
                }
            }
        }
    }

    /** Helper method for sending feature discovery command */
    private void reportFeatures(boolean isTvDeviceSetting) {
        // <Report Features> should only be sent for HDMI 2.0
        if (getCecVersion() < HdmiControlManager.HDMI_CEC_VERSION_2_0) {
            return;
        }
        // check if tv device is enabled for tv device specific RC profile setting
        if (isTvDeviceSetting) {
            if (isTvDeviceEnabled()) {
                tv().reportFeatures();
            }
        } else { // check for source device setting
            HdmiCecLocalDeviceSource source = isAudioSystemDevice() ? audioSystem() : playback();
            if (source != null) {
                source.reportFeatures();
            }
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
    void setEarcController(HdmiEarcController earcController) {
        mEarcController = earcController;
    }

    @VisibleForTesting
    void setHdmiCecNetwork(HdmiCecNetwork hdmiCecNetwork) {
        mHdmiCecNetwork = hdmiCecNetwork;
    }

    @VisibleForTesting
    void setHdmiCecConfig(HdmiCecConfig hdmiCecConfig) {
        mHdmiCecConfig = hdmiCecConfig;
    }

    public HdmiCecNetwork getHdmiCecNetwork() {
        return mHdmiCecNetwork;
    }

    @VisibleForTesting
    void setHdmiMhlController(HdmiMhlControllerStub hdmiMhlController) {
        mMhlController = hdmiMhlController;
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            mDisplayManager = getContext().getSystemService(DisplayManager.class);
            mTvInputManager = (TvInputManager) getContext().getSystemService(
                    Context.TV_INPUT_SERVICE);
            mPowerManager = new PowerManagerWrapper(getContext());
            mPowerManagerInternal = new PowerManagerInternalWrapper();
            if (mAudioManager == null) {
                mAudioManager = new DefaultAudioManagerWrapper(getContext());
            }
            mStreamMusicMaxVolume = getAudioManager().getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (mAudioDeviceVolumeManager == null) {
                mAudioDeviceVolumeManager =
                        new DefaultAudioDeviceVolumeManagerWrapper(getContext());
            }
            getAudioDeviceVolumeManager().addOnDeviceVolumeBehaviorChangedListener(
                    mServiceThreadExecutor, this::onDeviceVolumeBehaviorChanged);
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

    @VisibleForTesting
    void setDeviceConfig(DeviceConfigWrapper deviceConfig) {
        mDeviceConfig = deviceConfig;
    }

    @VisibleForTesting
    void setPowerManager(PowerManagerWrapper powerManager) {
        mPowerManager = powerManager;
    }

    @VisibleForTesting
    void setPowerManagerInternal(PowerManagerInternalWrapper powerManagerInternal) {
        mPowerManagerInternal = powerManagerInternal;
    }

    DeviceConfigWrapper getDeviceConfig() {
        return mDeviceConfig;
    }

    PowerManagerWrapper getPowerManager() {
        return mPowerManager;
    }

    PowerManagerInternalWrapper getPowerManagerInternal() {
        return mPowerManagerInternal;
    }

    /**
     * Triggers the address allocation that states the presence of a local device audio system in
     * the network.
     */
    @VisibleForTesting
    public void setSoundbarMode(final int settingValue) {
        boolean isArcSupported = isArcSupported();
        HdmiCecLocalDevicePlayback playback = playback();
        HdmiCecLocalDeviceAudioSystem audioSystem = audioSystem();
        getAtomWriter().dsmStatusChanged(isArcSupported,
                settingValue == SOUNDBAR_MODE_ENABLED,
                HdmiStatsEnums.LOG_REASON_DSM_SETTING_TOGGLED);

        if (playback == null) {
            Slog.w(TAG, "Device type not compatible to change soundbar mode.");
            return;
        }
        if (!isArcSupported) {
            Slog.w(TAG, "Device type doesn't support ARC.");
            return;
        }
        boolean isArcEnabled = false;
        if (settingValue == SOUNDBAR_MODE_DISABLED && audioSystem != null) {
            isArcEnabled = audioSystem.isArcEnabled();
            if (isSystemAudioActivated()) {
                audioSystem.terminateSystemAudioMode();
            }
            if (isArcEnabled) {
                if (audioSystem.hasAction(ArcTerminationActionFromAvr.class)) {
                    audioSystem.removeAction(ArcTerminationActionFromAvr.class);
                }
                audioSystem.addAndStartAction(new ArcTerminationActionFromAvr(audioSystem,
                        new IHdmiControlCallback.Stub() {
                            @Override
                            public void onComplete(int result) {
                                mAddressAllocated = false;
                                initializeCecLocalDevices(INITIATED_BY_SOUNDBAR_MODE);
                            }
                        }));
            }
        }
        if (!isArcEnabled) {
            mAddressAllocated = false;
            initializeCecLocalDevices(INITIATED_BY_SOUNDBAR_MODE);
        }
    }

    /**
     * Checks if the Device Discovery is handled by the local device playback.
     * See {@link HdmiCecLocalDeviceAudioSystem#launchDeviceDiscovery}.
     */
    public boolean isDeviceDiscoveryHandledByPlayback() {
        HdmiCecLocalDevicePlayback playback = playback();
        if (playback != null && (playback.hasAction(DeviceDiscoveryAction.class)
                || playback.hasAction(HotplugDetectionAction.class))) {
            return true;
        }
        return false;
    }

    /**
     * Called when the initialization of local devices is complete.
     */
    private void onInitializeCecComplete(int initiatedBy) {
        updatePowerStatusOnInitializeCecComplete();
        mWakeUpMessageReceived = false;

        if (isTvDeviceEnabled()) {
            mCecController.enableWakeupByOtp(tv().getAutoWakeup());
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
                reason = HdmiControlManager.CONTROL_STATE_CHANGED_REASON_WAKEUP;
                final List<HdmiCecLocalDevice> devices = getAllCecLocalDevices();
                for (HdmiCecLocalDevice device : devices) {
                    device.onInitializeCecComplete(initiatedBy);
                }
                break;
            case INITIATED_BY_WAKE_UP_MESSAGE:
                reason = HdmiControlManager.CONTROL_STATE_CHANGED_REASON_WAKEUP;
                break;
        }
        if (reason != -1) {
            invokeVendorCommandListenersOnControlStateChanged(true, reason);
            announceHdmiControlStatusChange(HDMI_CEC_CONTROL_ENABLED);
        }
    }

    /**
     * Updates the power status once the initialization of local devices is complete.
     */
    private void updatePowerStatusOnInitializeCecComplete() {
        if (mPowerStatusController.isPowerStatusTransientToOn()) {
            mHandler.post(() -> mPowerStatusController.setPowerStatus(
                    HdmiControlManager.POWER_STATUS_ON));
        } else if (mPowerStatusController.isPowerStatusTransientToStandby()) {
            mHandler.post(() -> mPowerStatusController.setPowerStatus(
                    HdmiControlManager.POWER_STATUS_STANDBY));
        }
    }

    private void registerContentObserver() {
        ContentResolver resolver = getContext().getContentResolver();
        String[] settings = new String[] {
                Global.MHL_INPUT_SWITCHING_ENABLED,
                Global.MHL_POWER_CHARGE_ENABLED,
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

    @VisibleForTesting
    int readIntSetting(String key, int defVal) {
        ContentResolver cr = getContext().getContentResolver();
        return Global.getInt(cr, key, defVal);
    }

    void writeBooleanSetting(String key, boolean value) {
        ContentResolver cr = getContext().getContentResolver();
        Global.putInt(cr, key, toInt(value));
    }

    @VisibleForTesting
    protected void writeStringSystemProperty(String key, String value) {
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

    void writeStringSetting(String key, String value) {
        ContentResolver cr = getContext().getContentResolver();
        Global.putString(cr, key, value);
    }

    private void initializeCec(int initiatedBy) {
        mAddressAllocated = false;
        int settingsCecVersion = getHdmiCecConfig().getIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION);
        int supportedCecVersion = mCecController.getVersion();

        // Limit the used CEC version to the highest supported version by HAL and selected
        // version in settings (but at least v1.4b).
        mCecVersion = Math.max(HdmiControlManager.HDMI_CEC_VERSION_1_4_B,
                Math.min(settingsCecVersion, supportedCecVersion));

        mCecController.enableSystemCecControl(true);
        mCecController.setLanguage(mMenuLanguage);
        initializeCecLocalDevices(initiatedBy);
    }

    /**
     * If the Soundbar mode is turned on, adds the local device type audio system in the list of
     * local devices types. This method is called when the local devices are initialized such that
     * the list of local devices is in sync with the Soundbar mode setting.
     * @return the list of integer device types
     */
    @ServiceThreadOnly
    private List<Integer> getCecLocalDeviceTypes() {
        ArrayList<Integer> allLocalDeviceTypes = new ArrayList<>(mCecLocalDevices);
        if (isDsmEnabled() && !allLocalDeviceTypes.contains(HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM)
                && isArcSupported() && mSoundbarModeFeatureFlagEnabled) {
            allLocalDeviceTypes.add(HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);
        }
        return allLocalDeviceTypes;
    }

    @ServiceThreadOnly
    @VisibleForTesting
    protected void initializeCecLocalDevices(final int initiatedBy) {
        assertRunOnServiceThread();
        // A container for [Device type, Local device info].
        ArrayList<HdmiCecLocalDevice> localDevices = new ArrayList<>();
        for (int type : getCecLocalDeviceTypes()) {
            HdmiCecLocalDevice localDevice = mHdmiCecNetwork.getLocalDevice(type);
            if (localDevice == null) {
                localDevice = HdmiCecLocalDevice.create(this, type);
            }
            localDevice.init();
            localDevices.add(localDevice);
        }
        mHdmiCecNetwork.clearDeviceList();
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
                                Slog.e(TAG, "Failed to allocate address:[device_type:" + deviceType
                                        + "]");
                                mHdmiCecNetwork.removeLocalDeviceWithType(deviceType);
                            } else {
                                // Set POWER_STATUS_ON to all local devices because they share
                                // lifetime
                                // with system.
                                HdmiDeviceInfo deviceInfo = createDeviceInfo(logicalAddress,
                                        deviceType,
                                        HdmiControlManager.POWER_STATUS_ON, getCecVersion());
                                localDevice.setDeviceInfo(deviceInfo);
                                // If a local device of the same type already exists, it will be
                                // replaced.
                                mHdmiCecNetwork.addLocalDevice(deviceType, localDevice);
                                mHdmiCecNetwork.addCecDevice(localDevice.getDeviceInfo());
                                mCecController.addLogicalAddress(logicalAddress);
                                allocatedDevices.add(localDevice);
                            }

                            // Address allocation completed for all devices. Notify each device.
                            if (allocatingDevices.size() == ++finished[0]) {
                                if (initiatedBy != INITIATED_BY_HOTPLUG
                                        && initiatedBy != INITIATED_BY_SOUNDBAR_MODE) {
                                    // In case of the hotplug or soundbar mode setting toggle
                                    // we don't call onInitializeCecComplete()
                                    // since we reallocate the logical address only.
                                    onInitializeCecComplete(initiatedBy);
                                } else if (initiatedBy == INITIATED_BY_HOTPLUG
                                        && mDisplayStatusCallback == null) {
                                    // Force to update display status for hotplug event.
                                    synchronized (mLock) {
                                        announceHdmiControlStatusChange(mHdmiControlEnabled);
                                    }
                                }
                                // We remove local devices here, instead of before the start of
                                // address allocation, to prevent multiple local devices of the
                                // same type from existing simultaneously.
                                mHdmiCecNetwork.removeUnusedLocalDevices(allocatedDevices);
                                mAddressAllocated = true;
                                notifyAddressAllocated(allocatedDevices, initiatedBy);
                                // Reinvoke the saved display status callback once the local
                                // device is ready.
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

    /**
     * Notifies local devices that address allocation finished.
     * @param devices - list of local devices allocated.
     * @param initiatedBy - reason for the address allocation.
     */
    @VisibleForTesting
    @ServiceThreadOnly
    public void notifyAddressAllocated(ArrayList<HdmiCecLocalDevice> devices, int initiatedBy) {
        assertRunOnServiceThread();
        if (devices == null || devices.isEmpty()) {
            Slog.w(TAG, "No local device to notify.");
            return;
        }
        List<HdmiCecMessage> bufferedMessages = mCecMessageBuffer.getBuffer();
        for (HdmiCecLocalDevice device : devices) {
            int address = device.getDeviceInfo().getLogicalAddress();
            device.handleAddressAllocated(address, bufferedMessages, initiatedBy);
        }
        if (isTvDeviceEnabled()) {
            tv().setSelectRequestBuffer(mSelectRequestBuffer);
        }
    }

    boolean isAddressAllocated() {
        return mAddressAllocated;
    }

    List<HdmiPortInfo> getPortInfo() {
        synchronized (mLock) {
            return mHdmiCecNetwork.getPortInfo();
        }
    }

    HdmiPortInfo getPortInfo(int portId) {
        return mHdmiCecNetwork.getPortInfo(portId);
    }

    /**
     * Returns the routing path (physical address) of the HDMI port for the given
     * port id.
     */
    int portIdToPath(int portId) {
        return mHdmiCecNetwork.portIdToPath(portId);
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
        return mHdmiCecNetwork.physicalAddressToPortId(path);
    }

    boolean isValidPortId(int portId) {
        return mHdmiCecNetwork.getPortInfo(portId) != null;
    }

    /**
     * Returns {@link Looper} for IO operation.
     */
    @Nullable
    @VisibleForTesting
    protected Looper getIoLooper() {
        return mIoLooper;
    }

    @VisibleForTesting
    void setIoLooper(Looper ioLooper) {
        mIoLooper = ioLooper;
    }

    @VisibleForTesting
    void setCecMessageBuffer(CecMessageBuffer cecMessageBuffer) {
        this.mCecMessageBuffer = cecMessageBuffer;
    }

    /**
     * Returns {@link Looper} of main thread. Use this {@link Looper} instance
     * for tasks that are running on main service thread.
     */
    protected Looper getServiceLooper() {
        return mHandler.getLooper();
    }

    /**
     * Returns physical address of the device.
     */
    int getPhysicalAddress() {
        return mHdmiCecNetwork.getPhysicalAddress();
    }

    /**
     * Returns vendor id of CEC service.
     */
    int getVendorId() {
        return mCecController.getVendorId();
    }

    @Nullable
    @ServiceThreadOnly
    HdmiDeviceInfo getDeviceInfo(int logicalAddress) {
        assertRunOnServiceThread();
        return mHdmiCecNetwork.getCecDeviceInfo(logicalAddress);
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
    @VisibleForTesting
    @HdmiControlManager.HdmiCecVersion
    protected int getCecVersion() {
        return mCecVersion;
    }

    /**
     * Whether a device of the specified physical address is connected to ARC enabled port.
     */
    boolean isConnectedToArcPort(int physicalAddress) {
        return mHdmiCecNetwork.isConnectedToArcPort(physicalAddress);
    }

    @ServiceThreadOnly
    boolean isConnected(int portId) {
        assertRunOnServiceThread();
        return mCecController.isConnected(portId);
    }

    /**
     * Executes a Runnable on the service thread.
     * During execution, sets the work source UID to the parent's work source UID.
     *
     * @param runnable The runnable to execute on the service thread
     */
    void runOnServiceThread(Runnable runnable) {
        mHandler.post(new WorkSourceUidPreservingRunnable(runnable));
    }

    private void assertRunOnServiceThread() {
        if (Looper.myLooper() != mHandler.getLooper()) {
            throw new IllegalStateException("Should run on service thread.");
        }
    }

    @ServiceThreadOnly
    void sendCecCommand(HdmiCecMessage command) {
        sendCecCommand(command, null);
    }

    @ServiceThreadOnly
    void sendCecCommand(HdmiCecMessage command, @Nullable SendMessageCallback callback) {
        switch (command.getOpcode()) {
            case Constants.MESSAGE_ACTIVE_SOURCE:
            case Constants.MESSAGE_IMAGE_VIEW_ON:
            case Constants.MESSAGE_INACTIVE_SOURCE:
            case Constants.MESSAGE_ROUTING_CHANGE:
            case Constants.MESSAGE_SET_STREAM_PATH:
            case Constants.MESSAGE_TEXT_VIEW_ON:
                // RequestActiveSourceAction is started after the TV finished logical address
                // allocation. This action is used by the TV to get the active source from the CEC
                // network. If the TV sent a source changing CEC message, this action does not have
                // to continue anymore.
                if (isTvDeviceEnabled()) {
                    tv().removeAction(RequestActiveSourceAction.class);
                }
                sendCecCommandWithRetries(command, callback);
                break;
            default:
                sendCecCommandWithoutRetries(command, callback);
        }
    }

    /**
     * Create a {@link ResendCecCommandAction} that will retry sending the CEC message if it fails.
     * @param command  command to be sent on the CEC bus.
     * @param callback callback for handling the result of sending the command.
     */
    @ServiceThreadOnly
    private void sendCecCommandWithRetries(HdmiCecMessage command,
            @Nullable SendMessageCallback callback) {
        assertRunOnServiceThread();
        HdmiCecLocalDevice localDevice = getAllCecLocalDevices().get(0);
        if (localDevice != null) {
            sendCecCommandWithoutRetries(command, new SendMessageCallback() {
                @Override
                public void onSendCompleted(int result) {
                    if (result != SendMessageResult.SUCCESS) {
                        localDevice.addAndStartAction(new
                                ResendCecCommandAction(localDevice, command, callback));
                    }
                }
            });
        }
    }


    /**
     * Transmit a CEC command to CEC bus.
     *
     * @param command CEC command to send out
     * @param callback interface used to the result of send command
     */
    @ServiceThreadOnly
    void sendCecCommandWithoutRetries(HdmiCecMessage command,
            @Nullable SendMessageCallback callback) {
        assertRunOnServiceThread();
        if (command.getValidationResult() == HdmiCecMessageValidator.OK
                && verifyPhysicalAddresses(command)) {
            mCecController.sendCommand(command, callback);
        } else {
            HdmiLogger.error("Invalid message type:" + command);
            if (callback != null) {
                callback.onSendCompleted(SendMessageResult.FAIL);
            }
        }
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

    /**
     * Returns whether all of the physical addresses in a message could exist in this CEC network.
     */
    boolean verifyPhysicalAddresses(HdmiCecMessage message) {
        byte[] params = message.getParams();
        switch (message.getOpcode()) {
            case Constants.MESSAGE_ROUTING_CHANGE:
                return verifyPhysicalAddress(params, 0)
                        && verifyPhysicalAddress(params, 2);
            case Constants.MESSAGE_SYSTEM_AUDIO_MODE_REQUEST:
                return params.length == 0 || verifyPhysicalAddress(params, 0);
            case Constants.MESSAGE_ACTIVE_SOURCE:
            case Constants.MESSAGE_INACTIVE_SOURCE:
            case Constants.MESSAGE_REPORT_PHYSICAL_ADDRESS:
            case Constants.MESSAGE_ROUTING_INFORMATION:
            case Constants.MESSAGE_SET_STREAM_PATH:
                return verifyPhysicalAddress(params, 0);
            case Constants.MESSAGE_CLEAR_EXTERNAL_TIMER:
            case Constants.MESSAGE_SET_EXTERNAL_TIMER:
                return verifyExternalSourcePhysicalAddress(params, 7);
            default:
                return true;
        }
    }

    /**
     * Returns whether a given physical address could exist in this CEC network.
     * For a TV, the physical address must either be the address of the TV itself,
     * or the address of a device connected to one of its ports (possibly indirectly).
     */
    private boolean verifyPhysicalAddress(byte[] params, int offset) {
        if (!isTvDevice()) {
            // If the device is not TV, we can't convert path to port-id, so stop here.
            return true;
        }
        // Invalidate the physical address if parameters length is too short.
        if (params.length < offset + 2) {
            return false;
        }
        int path = HdmiUtils.twoBytesToInt(params, offset);
        if (path != Constants.INVALID_PHYSICAL_ADDRESS && path == getPhysicalAddress()) {
            return true;
        }
        int portId = pathToPortId(path);
        if (portId == Constants.INVALID_PORT_ID) {
            return false;
        }
        return true;
    }

    /**
     * Returns whether the physical address of an external source could exist in this network.
     */
    private boolean verifyExternalSourcePhysicalAddress(byte[] params, int offset) {
        int externalSourceSpecifier = params[offset];
        offset = offset + 1;
        if (externalSourceSpecifier == 0x05) {
            if (params.length - offset >= 2) {
                return verifyPhysicalAddress(params, offset);
            }
        }
        return true;
    }

    /**
     * Returns whether the source address of a message is a local logical address.
     */
    private boolean sourceAddressIsLocal(HdmiCecMessage message) {
        for (HdmiCecLocalDevice device : getAllCecLocalDevices()) {
            if (message.getSource() == device.getDeviceInfo().getLogicalAddress()
                    && message.getSource() != Constants.ADDR_UNREGISTERED) {
                HdmiLogger.warning(
                        "Unexpected source: message sent from device itself, " + message);
                return true;
            }
        }
        return false;
    }

    @ServiceThreadOnly
    @VisibleForTesting
    @Constants.HandleMessageResult
    protected int handleCecCommand(HdmiCecMessage message) {
        assertRunOnServiceThread();

        @HdmiCecMessageValidator.ValidationResult
        int validationResult = message.getValidationResult();
        if (validationResult == HdmiCecMessageValidator.ERROR_PARAMETER
                || validationResult == HdmiCecMessageValidator.ERROR_PARAMETER_LONG
                || !verifyPhysicalAddresses(message)) {
            return Constants.ABORT_INVALID_OPERAND;
        } else if (validationResult != HdmiCecMessageValidator.OK
                || sourceAddressIsLocal(message)) {
            return Constants.HANDLED;
        }

        getHdmiCecNetwork().handleCecMessage(message);

        @Constants.HandleMessageResult int handleMessageResult =
                dispatchMessageToLocalDevice(message);
        // mAddressAllocated is false during address allocation, meaning there is no device to
        // handle the message, so it should be buffered, if possible.
        if (!mAddressAllocated
                && mCecMessageBuffer.bufferMessage(message)) {
            return Constants.HANDLED;
        }

        return handleMessageResult;
    }

    void enableAudioReturnChannel(int portId, boolean enabled) {
        if (!mTransitionFromArcToEarcTxEnabled && enabled && mEarcController != null) {
            // If the feature flag is set to false, prevent eARC from establishing if ARC is already
            // established.
            setEarcEnabledInHal(false, false);
        }
        mCecController.enableAudioReturnChannel(portId, enabled);
    }

    @ServiceThreadOnly
    @VisibleForTesting
    @Constants.HandleMessageResult
    protected int dispatchMessageToLocalDevice(HdmiCecMessage message) {
        assertRunOnServiceThread();
        for (HdmiCecLocalDevice device : mHdmiCecNetwork.getLocalDeviceList()) {
            @Constants.HandleMessageResult int messageResult = device.dispatchMessage(message);
            if (messageResult != Constants.NOT_HANDLED
                    && message.getDestination() != Constants.ADDR_BROADCAST) {
                return messageResult;
            }
        }

        // We should never respond <Feature Abort> to a broadcast message
        if (message.getDestination() == Constants.ADDR_BROADCAST) {
            return Constants.HANDLED;
        } else {
            HdmiLogger.warning("Unhandled cec command:" + message);
            return Constants.NOT_HANDLED;
        }
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
        // initPortInfo at hotplug event.
        mHdmiCecNetwork.initPortInfo();

        HdmiPortInfo portInfo = getPortInfo(portId);
        if (connected && !isTvDevice()
                && portInfo != null && portInfo.getType() == HdmiPortInfo.PORT_OUTPUT) {
            ArrayList<HdmiCecLocalDevice> localDevices = new ArrayList<>();
            for (int type : getCecLocalDeviceTypes()) {
                HdmiCecLocalDevice localDevice = mHdmiCecNetwork.getLocalDevice(type);
                if (localDevice == null) {
                    localDevice = HdmiCecLocalDevice.create(this, type);
                    localDevice.init();
                }
                localDevices.add(localDevice);
            }
            allocateLogicalAddress(localDevices, INITIATED_BY_HOTPLUG);
        }

        for (HdmiCecLocalDevice device : mHdmiCecNetwork.getLocalDeviceList()) {
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
            int retryCount, long pollingMessageInterval) {
        assertRunOnServiceThread();
        mCecController.pollDevices(callback, sourceAddress, checkPollStrategy(pickStrategy),
                retryCount, pollingMessageInterval);
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

    List<HdmiCecLocalDevice> getAllCecLocalDevices() {
        assertRunOnServiceThread();
        return mHdmiCecNetwork.getLocalDeviceList();
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
     * @param physicalAddress physical address of the given device.
     */
    protected void checkLogicalAddressConflictAndReallocate(int logicalAddress,
            int physicalAddress) {
        // The given device is a local device. No logical address conflict.
        if (physicalAddress == getPhysicalAddress()) {
            return;
        }
        for (HdmiCecLocalDevice device : getAllCecLocalDevices()) {
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
                || getHdmiCecVolumeControl()
                == HdmiControlManager.VOLUME_CONTROL_DISABLED) {
            return;
        }
        AudioManagerWrapper audioManager = getAudioManager();
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

    private HdmiDeviceInfo createDeviceInfo(int logicalAddress, int deviceType, int powerStatus,
            int cecVersion) {
        String displayName = readStringSetting(Global.DEVICE_NAME, Build.MODEL);
        return HdmiDeviceInfo.cecDeviceBuilder()
                .setLogicalAddress(logicalAddress)
                .setPhysicalAddress(getPhysicalAddress())
                .setPortId(pathToPortId(getPhysicalAddress()))
                .setDeviceType(deviceType)
                .setVendorId(getVendorId())
                .setDisplayName(displayName)
                .setDevicePowerStatus(powerStatus)
                .setCecVersion(cecVersion)
                .build();
    }

    // Set the display name in HdmiDeviceInfo of the current devices to content provided by
    // Global.DEVICE_NAME. Only set and broadcast if the new name is different.
    private void setDisplayName(String newDisplayName) {
        for (HdmiCecLocalDevice device : getAllCecLocalDevices()) {
            HdmiDeviceInfo deviceInfo = device.getDeviceInfo();
            if (deviceInfo.getDisplayName().equals(newDisplayName)) {
                continue;
            }
            device.setDeviceInfo(deviceInfo.toBuilder().setDisplayName(newDisplayName).build());
            sendCecCommand(
                    HdmiCecMessageBuilder.buildSetOsdNameCommand(
                            deviceInfo.getLogicalAddress(), Constants.ADDR_TV, newDisplayName));
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
        private final int mVendorId;

        VendorCommandListenerRecord(IHdmiVendorCommandListener listener, int vendorId) {
            mListener = listener;
            mVendorId = vendorId;
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

    /**
     * Sets the work source UID to the Binder calling UID.
     * Work source UID allows access to the original calling UID of a Binder call in the Runnables
     * that it spawns.
     * This is necessary because Runnables that are executed on the service thread
     * take on the calling UID of the service thread.
     */
    private void setWorkSourceUidToCallingUid() {
        Binder.setCallingWorkSourceUid(Binder.getCallingUid());
    }

    private void enforceAccessPermission() {
        getContext().enforceCallingOrSelfPermission(PERMISSION, TAG);
    }

    private void initBinderCall() {
        enforceAccessPermission();
        setWorkSourceUidToCallingUid();
    }

    private final class BinderService extends IHdmiControlService.Stub {
        @Override
        public int[] getSupportedTypes() {
            initBinderCall();
            // mCecLocalDevices is an unmodifiable list - no lock necessary.
            int[] localDevices = new int[mCecLocalDevices.size()];
            for (int i = 0; i < localDevices.length; ++i) {
                localDevices[i] = mCecLocalDevices.get(i);
            }
            return localDevices;
        }

        @Override
        @Nullable
        public HdmiDeviceInfo getActiveSource() {
            initBinderCall();

            return HdmiControlService.this.getActiveSource();
        }

        @Override
        public void deviceSelect(final int deviceId, final IHdmiControlCallback callback) {
            initBinderCall();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    if (callback == null) {
                        Slog.e(TAG, "Callback cannot be null");
                        return;
                    }
                    HdmiCecLocalDeviceTv tv = tv();
                    HdmiCecLocalDevicePlayback playback = playback();
                    if (tv == null && playback == null) {
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
                    if (tv != null) {
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
                        return;
                    }
                    playback.deviceSelect(deviceId, callback);
                }
            });
        }

        @Override
        public void portSelect(final int portId, final IHdmiControlCallback callback) {
            initBinderCall();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    if (callback == null) {
                        Slog.e(TAG, "Callback cannot be null");
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
            initBinderCall();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiMhlLocalDeviceStub device = mMhlController.getLocalDevice(mActivePortId);
                    if (device != null) {
                        device.sendKeyEvent(keyCode, isPressed);
                        return;
                    }
                    if (mCecController != null) {
                        HdmiCecLocalDevice localDevice = mHdmiCecNetwork.getLocalDevice(deviceType);
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
            initBinderCall();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    if (mCecController == null) {
                        Slog.w(TAG, "CEC controller not available to send volume key event.");
                        return;
                    }
                    HdmiCecLocalDevice localDevice = mHdmiCecNetwork.getLocalDevice(deviceType);
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
            initBinderCall();
            int pid = Binder.getCallingPid();
            Slog.d(TAG, "Process pid: " + pid + " is calling oneTouchPlay.");
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiControlService.this.oneTouchPlay(callback);
                }
            });
        }

        @Override
        public void toggleAndFollowTvPower() {
            initBinderCall();
            int pid = Binder.getCallingPid();
            Slog.d(TAG, "Process pid: " + pid + " is calling toggleAndFollowTvPower.");
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiControlService.this.toggleAndFollowTvPower();
                }
            });
        }

        @Override
        public boolean shouldHandleTvPowerKey() {
            initBinderCall();
            return HdmiControlService.this.shouldHandleTvPowerKey();
        }

        @Override
        public void queryDisplayStatus(final IHdmiControlCallback callback) {
            initBinderCall();
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
            initBinderCall();
            HdmiControlService.this.addHdmiControlStatusChangeListener(listener);
        }

        @Override
        public void removeHdmiControlStatusChangeListener(
                final IHdmiControlStatusChangeListener listener) {
            initBinderCall();
            HdmiControlService.this.removeHdmiControlStatusChangeListener(listener);
        }

        @Override
        public void addHdmiCecVolumeControlFeatureListener(
                final IHdmiCecVolumeControlFeatureListener listener) {
            initBinderCall();
            HdmiControlService.this.addHdmiCecVolumeControlFeatureListener(listener);
        }

        @Override
        public void removeHdmiCecVolumeControlFeatureListener(
                final IHdmiCecVolumeControlFeatureListener listener) {
            initBinderCall();
            HdmiControlService.this.removeHdmiControlVolumeControlStatusChangeListener(listener);
        }


        @Override
        public void addHotplugEventListener(final IHdmiHotplugEventListener listener) {
            initBinderCall();
            HdmiControlService.this.addHotplugEventListener(listener);
        }

        @Override
        public void removeHotplugEventListener(final IHdmiHotplugEventListener listener) {
            initBinderCall();
            HdmiControlService.this.removeHotplugEventListener(listener);
        }

        @Override
        public void addDeviceEventListener(final IHdmiDeviceEventListener listener) {
            initBinderCall();
            HdmiControlService.this.addDeviceEventListener(listener);
        }

        @Override
        public List<HdmiPortInfo> getPortInfo() {
            initBinderCall();
            return HdmiControlService.this.getPortInfo() == null
                ? Collections.<HdmiPortInfo>emptyList()
                : HdmiControlService.this.getPortInfo();
        }

        @Override
        public boolean canChangeSystemAudioMode() {
            initBinderCall();
            HdmiCecLocalDeviceTv tv = tv();
            if (tv == null) {
                return false;
            }
            return tv.hasSystemAudioDevice();
        }

        @Override
        public boolean getSystemAudioMode() {
            // TODO(shubang): handle getSystemAudioMode() for all device types
            initBinderCall();
            HdmiCecLocalDeviceTv tv = tv();
            HdmiCecLocalDeviceAudioSystem audioSystem = audioSystem();
            return (tv != null && tv.isSystemAudioActivated())
                    || (audioSystem != null && audioSystem.isSystemAudioActivated());
        }

        @Override
        public int getPhysicalAddress() {
            initBinderCall();
            synchronized (mLock) {
                return mHdmiCecNetwork.getPhysicalAddress();
            }
        }

        @Override
        public void setSystemAudioMode(final boolean enabled, final IHdmiControlCallback callback) {
            initBinderCall();
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
            initBinderCall();
            HdmiControlService.this.addSystemAudioModeChangeListner(listener);
        }

        @Override
        public void removeSystemAudioModeChangeListener(
                final IHdmiSystemAudioModeChangeListener listener) {
            initBinderCall();
            HdmiControlService.this.removeSystemAudioModeChangeListener(listener);
        }

        @Override
        public void setInputChangeListener(final IHdmiInputChangeListener listener) {
            initBinderCall();
            HdmiControlService.this.setInputChangeListener(listener);
        }

        @Override
        public List<HdmiDeviceInfo> getInputDevices() {
            initBinderCall();
            // No need to hold the lock for obtaining TV device as the local device instance
            // is preserved while the HDMI control is enabled.
            return HdmiUtils.mergeToUnmodifiableList(mHdmiCecNetwork.getSafeExternalInputsLocked(),
                    getMhlDevicesLocked());
        }

        // Returns all the CEC devices on the bus including system audio, switch,
        // even those of reserved type.
        @Override
        public List<HdmiDeviceInfo> getDeviceList() {
            initBinderCall();
            return mHdmiCecNetwork.getSafeCecDevicesLocked();
        }

        @Override
        public void powerOffRemoteDevice(int logicalAddress, int powerStatus) {
            initBinderCall();
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
            initBinderCall();
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
            initBinderCall();
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
            initBinderCall();
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
            initBinderCall();
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
            initBinderCall();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiCecLocalDeviceTv tv = tv();
                    if (tv == null) {
                        Slog.w(TAG, "Local tv device not available to change arc mode.");
                        return;
                    }
                    tv.startArcAction(enabled);
                }
            });
        }

        @Override
        public void setProhibitMode(final boolean enabled) {
            initBinderCall();
            if (!isTvDevice()) {
                return;
            }
            HdmiControlService.this.setProhibitMode(enabled);
        }

        @Override
        public void addVendorCommandListener(
                final IHdmiVendorCommandListener listener, final int vendorId) {
            initBinderCall();
            HdmiControlService.this.addVendorCommandListener(listener, vendorId);
        }

        @Override
        public void sendVendorCommand(final int deviceType, final int targetAddress,
                final byte[] params, final boolean hasVendorId) {
            initBinderCall();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiCecLocalDevice device = mHdmiCecNetwork.getLocalDevice(deviceType);
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
            initBinderCall();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiMhlLocalDeviceStub mhlDevice = mMhlController.getLocalDeviceById(deviceId);
                    if (mhlDevice != null) {
                        mhlDevice.sendStandby();
                        return;
                    }
                    HdmiCecLocalDevice device = mHdmiCecNetwork.getLocalDevice(deviceType);
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
            initBinderCall();
            HdmiControlService.this.setHdmiRecordListener(listener);
        }

        @Override
        public void startOneTouchRecord(final int recorderAddress, final byte[] recordSource) {
            initBinderCall();
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
            initBinderCall();
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
            initBinderCall();
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
            initBinderCall();
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
            initBinderCall();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    if (!isCecControlEnabled()) {
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
            initBinderCall();
            HdmiControlService.this.addHdmiMhlVendorCommandListener(listener);
        }

        @Override
        public void setStandbyMode(final boolean isStandbyModeOn) {
            initBinderCall();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiControlService.this.setStandbyMode(isStandbyModeOn);
                }
            });
        }

        @Override
        public void reportAudioStatus(final int deviceType, final int volume, final int maxVolume,
                final boolean isMute) {
            initBinderCall();
            runOnServiceThread(new Runnable() {
                @Override
                public void run() {
                    HdmiCecLocalDevice device = mHdmiCecNetwork.getLocalDevice(deviceType);
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
            initBinderCall();
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
                                    audioSystem().getDeviceInfo().getLogicalAddress(),
                                    Constants.ADDR_BROADCAST,
                                    true));
                }
            });
        }

        @Override
        public void onShellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out,
                @Nullable FileDescriptor err, String[] args,
                @Nullable ShellCallback callback, ResultReceiver resultReceiver)
                throws RemoteException {
            initBinderCall();
            new HdmiControlShellCommand(this)
                    .exec(this, in, out, err, args, callback, resultReceiver);
        }

        @Override
        protected void dump(FileDescriptor fd, final PrintWriter writer, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, writer)) return;
            final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");

            synchronized (mLock) {
                pw.println("mProhibitMode: " + mProhibitMode);
            }
            pw.println("mPowerStatus: " + mPowerStatusController.getPowerStatus());
            pw.println("mIsCecAvailable: " + mIsCecAvailable);
            pw.println("mCecVersion: " + mCecVersion);
            pw.println("mIsAbsoluteVolumeBehaviorEnabled: " + isAbsoluteVolumeBehaviorEnabled());

            // System settings
            pw.println("System_settings:");
            pw.increaseIndent();
            pw.println("mMhlInputChangeEnabled: " + isMhlInputChangeEnabled());
            pw.println("mSystemAudioActivated: " + isSystemAudioActivated());
            pw.println("mHdmiCecVolumeControlEnabled: " + getHdmiCecVolumeControl());
            pw.decreaseIndent();

            // CEC settings
            pw.println("CEC settings:");
            pw.increaseIndent();
            HdmiCecConfig hdmiCecConfig = HdmiControlService.this.getHdmiCecConfig();
            List<String> allSettings = hdmiCecConfig.getAllSettings();
            Set<String> userSettings = new HashSet<>(hdmiCecConfig.getUserSettings());
            for (String setting : allSettings) {
                if (hdmiCecConfig.isStringValueType(setting)) {
                    pw.println(setting + " (string): " + hdmiCecConfig.getStringValue(setting)
                            + " (default: " + hdmiCecConfig.getDefaultStringValue(setting) + ")"
                            + (userSettings.contains(setting) ? " [modifiable]" : ""));
                } else if (hdmiCecConfig.isIntValueType(setting)) {
                    pw.println(setting + " (int): " + hdmiCecConfig.getIntValue(setting)
                            + " (default: " + hdmiCecConfig.getDefaultIntValue(setting) + ")"
                            + (userSettings.contains(setting) ? " [modifiable]" : ""));
                }
            }
            pw.decreaseIndent();

            pw.println("mMhlController: ");
            pw.increaseIndent();
            mMhlController.dump(pw);
            pw.decreaseIndent();
            pw.print("eARC local device: ");
            pw.increaseIndent();
            if (mEarcLocalDevice == null) {
                pw.println("None. eARC is either disabled or not available.");
            } else {
                mEarcLocalDevice.dump(pw);
            }
            pw.decreaseIndent();
            mHdmiCecNetwork.dump(pw);
            if (mCecController != null) {
                pw.println("mCecController: ");
                pw.increaseIndent();
                mCecController.dump(pw);
                pw.decreaseIndent();
            }
        }

        @Override
        public boolean setMessageHistorySize(int newSize) {
            enforceAccessPermission();
            if (mCecController == null) {
                return false;
            }
            return mCecController.setMessageHistorySize(newSize);
        }

        @Override
        public int getMessageHistorySize() {
            enforceAccessPermission();
            if (mCecController != null) {
                return mCecController.getMessageHistorySize();
            } else {
                return 0;
            }
        }

        @Override
        public void addCecSettingChangeListener(String name,
                final IHdmiCecSettingChangeListener listener) {
            enforceAccessPermission();
            HdmiControlService.this.addCecSettingChangeListener(name, listener);
        }

        @Override
        public void removeCecSettingChangeListener(String name,
                final IHdmiCecSettingChangeListener listener) {
            enforceAccessPermission();
            HdmiControlService.this.removeCecSettingChangeListener(name, listener);
        }

        @Override
        public List<String> getUserCecSettings() {
            initBinderCall();
            final long token = Binder.clearCallingIdentity();
            try {
                return HdmiControlService.this.getHdmiCecConfig().getUserSettings();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public List<String> getAllowedCecSettingStringValues(String name) {
            initBinderCall();
            final long token = Binder.clearCallingIdentity();
            try {
                return HdmiControlService.this.getHdmiCecConfig().getAllowedStringValues(name);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public int[] getAllowedCecSettingIntValues(String name) {
            initBinderCall();
            final long token = Binder.clearCallingIdentity();
            try {
                List<Integer> allowedValues =
                        HdmiControlService.this.getHdmiCecConfig().getAllowedIntValues(name);
                return allowedValues.stream().mapToInt(i->i).toArray();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public String getCecSettingStringValue(String name) {
            initBinderCall();
            final long token = Binder.clearCallingIdentity();
            try {
                return HdmiControlService.this.getHdmiCecConfig().getStringValue(name);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void setCecSettingStringValue(String name, String value) {
            initBinderCall();
            final long token = Binder.clearCallingIdentity();
            try {
                HdmiControlService.this.getHdmiCecConfig().setStringValue(name, value);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public int getCecSettingIntValue(String name) {
            initBinderCall();
            final long token = Binder.clearCallingIdentity();
            try {
                return HdmiControlService.this.getHdmiCecConfig().getIntValue(name);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void setCecSettingIntValue(String name, int value) {
            initBinderCall();
            final long token = Binder.clearCallingIdentity();
            try {
                HdmiControlService.this.getHdmiCecConfig().setIntValue(name, value);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    @VisibleForTesting
    void setHdmiCecVolumeControlEnabledInternal(
            @HdmiControlManager.VolumeControl int hdmiCecVolumeControl) {
        mHdmiCecVolumeControl = hdmiCecVolumeControl;
        announceHdmiCecVolumeControlFeatureChange(hdmiCecVolumeControl);
        runOnServiceThread(this::checkAndUpdateAbsoluteVolumeBehavior);
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
    @VisibleForTesting
    protected void toggleAndFollowTvPower() {
        assertRunOnServiceThread();
        HdmiCecLocalDeviceSource source = playback();
        if (source == null) {
            source = audioSystem();
        }

        if (source == null) {
            Slog.w(TAG, "Local source device not available");
            return;
        }
        source.toggleAndFollowTvPower();
    }

    @VisibleForTesting
    protected boolean shouldHandleTvPowerKey() {
        if (isTvDevice()) {
            return false;
        }
        String powerControlMode = getHdmiCecConfig().getStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE);
        if (powerControlMode.equals(POWER_CONTROL_MODE_NONE)) {
            return false;
        }
        int hdmiCecEnabled = getHdmiCecConfig().getIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED);
        if (hdmiCecEnabled != HdmiControlManager.HDMI_CEC_CONTROL_ENABLED) {
            return false;
        }
        return mIsCecAvailable;
    }

    /**
     * Queries the display status of the TV and calls {@code callback} upon completion.
     *
     * If this is a non-source device, or if the query fails for any reason, the callback will
     * be called with {@link HdmiControlManager.POWER_STATUS_UNKNOWN}.
     *
     * If the query succeeds, the callback will be called with one of the other power status
     * constants.
     */
    @ServiceThreadOnly
    protected void queryDisplayStatus(final IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        if (!mAddressAllocated) {
            mDisplayStatusCallback = callback;
            Slog.d(TAG, "Local device is under address allocation. "
                        + "Queue display callback for later process.");
            return;
        }

        HdmiCecLocalDeviceSource source = playback();
        if (source == null) {
            source = audioSystem();
        }

        if (source == null) {
            Slog.w(TAG, "Local source device not available");
            invokeCallback(callback, HdmiControlManager.POWER_STATUS_UNKNOWN);
            return;
        }
        source.queryDisplayStatus(callback);
    }

    protected HdmiDeviceInfo getActiveSource() {
        // If a the device is a playback device that is the current active source, return the
        // local device info
        if (playback() != null && playback().isActiveSource()) {
            return playback().getDeviceInfo();
        }

        // Otherwise get the active source and look for it from the device list
        ActiveSource activeSource = getLocalActiveSource();

        if (activeSource.isValid()) {
            HdmiDeviceInfo activeSourceInfo = mHdmiCecNetwork.getSafeCecDeviceInfo(
                    activeSource.logicalAddress);
            if (activeSourceInfo != null) {
                return activeSourceInfo;
            }

            return HdmiDeviceInfo.hardwarePort(activeSource.physicalAddress,
                    pathToPortId(activeSource.physicalAddress));
        }

        if (tv() != null) {
            int activePath = tv().getActivePath();
            if (activePath != HdmiDeviceInfo.PATH_INVALID) {
                HdmiDeviceInfo info = mHdmiCecNetwork.getSafeDeviceInfoByPath(activePath);
                return (info != null) ? info : HdmiDeviceInfo.hardwarePort(activePath,
                        tv().getActivePortId());
            }
        }

        return null;
    }

    @VisibleForTesting
    void addHdmiControlStatusChangeListener(
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
                        listener.onHdmiCecVolumeControlFeature(mHdmiCecVolumeControl);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Failed to report HdmiControlVolumeControlStatusChange: "
                                + mHdmiCecVolumeControl, e);
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
        if (callback == null) {
            return;
        }
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

    private void announceHdmiControlStatusChange(@HdmiControlManager.HdmiCecControl int isEnabled) {
        assertRunOnServiceThread();
        synchronized (mLock) {
            List<IHdmiControlStatusChangeListener> listeners = new ArrayList<>(
                    mHdmiControlStatusChangeListenerRecords.size());
            for (HdmiControlStatusChangeListenerRecord record :
                    mHdmiControlStatusChangeListenerRecords) {
                listeners.add(record.mListener);
            }
            invokeHdmiControlStatusChangeListenerLocked(listeners, isEnabled);
        }
    }

    private void invokeHdmiControlStatusChangeListenerLocked(
            IHdmiControlStatusChangeListener listener,
            @HdmiControlManager.HdmiCecControl int isEnabled) {
        invokeHdmiControlStatusChangeListenerLocked(Collections.singletonList(listener), isEnabled);
    }

    private void invokeHdmiControlStatusChangeListenerLocked(
            Collection<IHdmiControlStatusChangeListener> listeners,
            @HdmiControlManager.HdmiCecControl int isEnabled) {
        if (isEnabled == HdmiControlManager.HDMI_CEC_CONTROL_ENABLED) {
            queryDisplayStatus(new IHdmiControlCallback.Stub() {
                public void onComplete(int status) {
                    mIsCecAvailable = status != HdmiControlManager.POWER_STATUS_UNKNOWN;
                    if (!listeners.isEmpty()) {
                        invokeHdmiControlStatusChangeListenerLocked(listeners,
                                isEnabled, mIsCecAvailable);
                    }
                }
            });
        } else {
            mIsCecAvailable = false;
            if (!listeners.isEmpty()) {
                invokeHdmiControlStatusChangeListenerLocked(listeners, isEnabled, mIsCecAvailable);
            }
        }
    }

    private void invokeHdmiControlStatusChangeListenerLocked(
            Collection<IHdmiControlStatusChangeListener> listeners,
            @HdmiControlManager.HdmiCecControl int isEnabled,
            boolean isCecAvailable) {
        for (IHdmiControlStatusChangeListener listener : listeners) {
            try {
                listener.onStatusChange(isEnabled, isCecAvailable);
            } catch (RemoteException e) {
                Slog.e(TAG,
                        "Failed to report HdmiControlStatusChange: " + isEnabled + " isAvailable: "
                                + isCecAvailable, e);
            }
        }
    }

    private void announceHdmiCecVolumeControlFeatureChange(
            @HdmiControlManager.VolumeControl int hdmiCecVolumeControl) {
        assertRunOnServiceThread();
        synchronized (mLock) {
            mHdmiCecVolumeControlFeatureListenerRecords.broadcast(listener -> {
                try {
                    listener.onHdmiCecVolumeControlFeature(hdmiCecVolumeControl);
                } catch (RemoteException e) {
                    Slog.e(TAG,
                            "Failed to report HdmiControlVolumeControlStatusChange: "
                                    + hdmiCecVolumeControl);
                }
            });
        }
    }

    public HdmiCecLocalDeviceTv tv() {
        return (HdmiCecLocalDeviceTv) mHdmiCecNetwork.getLocalDevice(HdmiDeviceInfo.DEVICE_TV);
    }

    boolean isTvDevice() {
        return mCecLocalDevices.contains(HdmiDeviceInfo.DEVICE_TV);
    }

    boolean isAudioSystemDevice() {
        return mCecLocalDevices.contains(HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);
    }

    boolean isPlaybackDevice() {
        return mCecLocalDevices.contains(HdmiDeviceInfo.DEVICE_PLAYBACK);
    }

    boolean isSwitchDevice() {
        return HdmiProperties.is_switch().orElse(false);
    }

    boolean isTvDeviceEnabled() {
        return isTvDevice() && tv() != null;
    }

    protected HdmiCecLocalDevicePlayback playback() {
        return (HdmiCecLocalDevicePlayback)
                mHdmiCecNetwork.getLocalDevice(HdmiDeviceInfo.DEVICE_PLAYBACK);
    }

    public HdmiCecLocalDeviceAudioSystem audioSystem() {
        return (HdmiCecLocalDeviceAudioSystem) mHdmiCecNetwork.getLocalDevice(
                HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);
    }

    /**
     * Returns null before the boot phase {@link SystemService#PHASE_SYSTEM_SERVICES_READY}.
     */
    @Nullable
    AudioManagerWrapper getAudioManager() {
        return mAudioManager;
    }

    /**
     * Returns null before the boot phase {@link SystemService#PHASE_SYSTEM_SERVICES_READY}.
     */
    @Nullable
    private AudioDeviceVolumeManagerWrapper getAudioDeviceVolumeManager() {
        return mAudioDeviceVolumeManager;
    }

    boolean isCecControlEnabled() {
        synchronized (mLock) {
            return mHdmiControlEnabled == HdmiControlManager.HDMI_CEC_CONTROL_ENABLED;
        }
    }

    public boolean isEarcEnabled() {
        synchronized (mLock) {
            return mEarcEnabled;
        }
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    protected boolean isEarcSupported() {
        synchronized (mLock) {
            return mEarcSupported;
        }
    }

    private boolean isDsmEnabled() {
        return mHdmiCecConfig.getIntValue(HdmiControlManager.CEC_SETTING_NAME_SOUNDBAR_MODE)
                == SOUNDBAR_MODE_ENABLED;
    }

    @VisibleForTesting
    protected boolean isArcSupported() {
        return SystemProperties.getBoolean(Constants.PROPERTY_ARC_SUPPORT, true);
    }

    @ServiceThreadOnly
    int getPowerStatus() {
        assertRunOnServiceThread();
        return mPowerStatusController.getPowerStatus();
    }

    @ServiceThreadOnly
    @VisibleForTesting
    void setPowerStatus(int powerStatus) {
        assertRunOnServiceThread();
        mPowerStatusController.setPowerStatus(powerStatus);
    }

    @ServiceThreadOnly
    boolean isPowerOnOrTransient() {
        assertRunOnServiceThread();
        return mPowerStatusController.isPowerStatusOn()
                || mPowerStatusController.isPowerStatusTransientToOn();
    }

    @ServiceThreadOnly
    boolean isPowerStandbyOrTransient() {
        assertRunOnServiceThread();
        return mPowerStatusController.isPowerStatusStandby()
                || mPowerStatusController.isPowerStatusTransientToStandby();
    }

    @ServiceThreadOnly
    boolean isPowerStandby() {
        assertRunOnServiceThread();
        return mPowerStatusController.isPowerStatusStandby();
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

    protected boolean isStandbyMessageReceived() {
        return mStandbyMessageReceived;
    }

    @ServiceThreadOnly
    @VisibleForTesting
    protected void onWakeUp(@WakeReason final int wakeUpAction) {
        assertRunOnServiceThread();
        mPowerStatusController.setPowerStatus(HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON,
                false);
        if (mCecController != null) {
            if (isCecControlEnabled()) {
                int startReason = -1;
                switch (wakeUpAction) {
                    case WAKE_UP_SCREEN_ON:
                        startReason = INITIATED_BY_SCREEN_ON;
                        if (mWakeUpMessageReceived) {
                            startReason = INITIATED_BY_WAKE_UP_MESSAGE;
                        }
                        break;
                    case WAKE_UP_BOOT_UP:
                        startReason = INITIATED_BY_BOOT_UP;
                        break;
                    default:
                        Slog.e(TAG, "wakeUpAction " + wakeUpAction + " not defined.");
                        return;

                }
                initializeCec(startReason);
            }
        } else {
            Slog.i(TAG, "Device does not support HDMI-CEC.");
        }
        if (isEarcSupported()) {
            if (isEarcEnabled()) {
                int startReason = -1;
                switch (wakeUpAction) {
                    case WAKE_UP_SCREEN_ON:
                        startReason = INITIATED_BY_SCREEN_ON;
                        break;
                    case WAKE_UP_BOOT_UP:
                        startReason = INITIATED_BY_BOOT_UP;
                        break;
                    default:
                        Slog.e(TAG, "wakeUpAction " + wakeUpAction + " not defined.");
                        return;
                }
                initializeEarc(startReason);
            } else {
                setEarcEnabledInHal(false, false);
            }
        }
        if (isTvDevice()) {
            int earcStatus = getEarcStatus();
            getAtomWriter().earcStatusChanged(isEarcSupported(), isEarcEnabled(),
                    earcStatus, earcStatus, HdmiStatsEnums.LOG_REASON_WAKE);
        } else if (isPlaybackDevice()) {
            getAtomWriter().dsmStatusChanged(isArcSupported(), isDsmEnabled(),
                    HdmiStatsEnums.LOG_REASON_DSM_WAKE);
        }
        // TODO: Initialize MHL local devices.
    }

    @ServiceThreadOnly
    @VisibleForTesting
    protected void onStandby(final int standbyAction) {
        if (shouldAcquireWakeLockOnStandby()) {
            acquireWakeLock();
        }
        mWakeUpMessageReceived = false;
        assertRunOnServiceThread();
        mPowerStatusController.setPowerStatus(HdmiControlManager.POWER_STATUS_TRANSIENT_TO_STANDBY,
                false);
        invokeVendorCommandListenersOnControlStateChanged(false,
                HdmiControlManager.CONTROL_STATE_CHANGED_REASON_STANDBY);

        final List<HdmiCecLocalDevice> devices = getAllCecLocalDevices();

        if (!isStandbyMessageReceived() && !canGoToStandby()) {
            mPowerStatusController.setPowerStatus(HdmiControlManager.POWER_STATUS_STANDBY);
            for (HdmiCecLocalDevice device : devices) {
                device.onStandby(mStandbyMessageReceived, standbyAction);
            }
            return;
        }

        disableCecLocalDevices(new PendingActionClearedCallback() {
            @Override
            public void onCleared(HdmiCecLocalDevice device) {
                Slog.v(TAG, "On standby-action cleared:" + device.mDeviceType);
                devices.remove(device);
                if (devices.isEmpty()) {
                    onPendingActionsCleared(standbyAction);
                    // We will not clear local devices here, since some OEM/SOC will keep passing
                    // the received packets until the application processor enters to the sleep
                    // actually.
                }
            }
        });

        // Make sure we switch away from absolute volume behavior (AVB) when entering standby.
        // We do this because AVB should not be used unless AbsoluteVolumeAudioStatusAction exists,
        // and the action cannot exist in standby because there are no local devices.
        checkAndUpdateAbsoluteVolumeBehavior();
    }

    boolean canGoToStandby() {
        for (HdmiCecLocalDevice device : mHdmiCecNetwork.getLocalDeviceList()) {
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

    @VisibleForTesting
    protected void disableCecLocalDevices(PendingActionClearedCallback callback) {
        if (mCecController != null) {
            for (HdmiCecLocalDevice device : mHdmiCecNetwork.getLocalDeviceList()) {
                device.disableDevice(mStandbyMessageReceived, callback);
            }
        }
        mMhlController.clearAllLocalDevices();
    }

    @ServiceThreadOnly
    @VisibleForTesting
    protected void clearCecLocalDevices() {
        assertRunOnServiceThread();
        if (mCecController == null) {
            return;
        }
        mCecController.clearLogicalAddress();
        mHdmiCecNetwork.clearLocalDevices();
    }

    /**
     * Normally called after all devices have cleared their pending actions, to execute the final
     * phase of the standby flow.
     *
     * This can also be called during wakeup, when pending actions are cleared after failing to be
     * cleared during standby. In this case, it does not execute the standby flow.
     */
    @ServiceThreadOnly
    @VisibleForTesting
    protected void onPendingActionsCleared(int standbyAction) {
        assertRunOnServiceThread();
        Slog.v(TAG, "onPendingActionsCleared");
        int localDevicesCount = getAllCecLocalDevices().size();
        final int[] countStandbyCompletedDevices = new int[1];
        StandbyCompletedCallback callback = new StandbyCompletedCallback() {
            @Override
            public void onStandbyCompleted() {
                if (localDevicesCount < ++countStandbyCompletedDevices[0]) {
                    return;
                }

                releaseWakeLock();
                if (isAudioSystemDevice() || !isPowerStandby()) {
                    return;
                }
                mCecController.enableSystemCecControl(false);
                mMhlController.setOption(OPTION_MHL_SERVICE_CONTROL, DISABLED);
            }
        };

        if (mPowerStatusController.isPowerStatusTransientToStandby()) {
            mPowerStatusController.setPowerStatus(HdmiControlManager.POWER_STATUS_STANDBY);
            for (HdmiCecLocalDevice device : mHdmiCecNetwork.getLocalDeviceList()) {
                device.onStandby(mStandbyMessageReceived, standbyAction, callback);
            }
        }
        // Always reset this flag to set up for the next standby
        mStandbyMessageReceived = false;
    }

    private boolean shouldAcquireWakeLockOnStandby() {
        boolean sendStandbyOnSleep = false;
        if (tv() != null) {
            sendStandbyOnSleep = mHdmiCecConfig.getIntValue(
                    HdmiControlManager.CEC_SETTING_NAME_TV_SEND_STANDBY_ON_SLEEP)
                    == TV_SEND_STANDBY_ON_SLEEP_ENABLED;
        } else if (playback() != null) {
            sendStandbyOnSleep = !mHdmiCecConfig.getStringValue(
                            HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE)
                    .equals(POWER_CONTROL_MODE_NONE);
        }

        return isCecControlEnabled() && isPowerOnOrTransient() && sendStandbyOnSleep;
    }

    /**
     * Acquire the wake lock used to hold the system awake until the standby process is finished.
     */
    @VisibleForTesting
    protected void acquireWakeLock() {
        releaseWakeLock();
        mWakeLock = mPowerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mWakeLock.acquire(DEVICE_CLEANUP_TIMEOUT);
    }

    /**
     * Release the wake lock acquired when the standby process started.
     */
    @VisibleForTesting
    protected void releaseWakeLock() {
        if (mWakeLock != null) {
            try {
                if (mWakeLock.isHeld()) {
                    mWakeLock.release();
                }
            } catch (RuntimeException e) {
                Slog.w(TAG, "Exception when releasing wake lock.");
            }
            mWakeLock = null;
        }
    }

    @VisibleForTesting
    void addVendorCommandListener(IHdmiVendorCommandListener listener, int vendorId) {
        VendorCommandListenerRecord record = new VendorCommandListenerRecord(listener, vendorId);
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
            boolean notifiedVendorCommandToListeners = false;
            for (VendorCommandListenerRecord record : mVendorCommandListenerRecords) {
                if (hasVendorId) {
                    int vendorId =
                            ((params[0] & 0xFF) << 16)
                                    + ((params[1] & 0xFF) << 8)
                                    + (params[2] & 0xFF);
                    if (record.mVendorId != vendorId) {
                        continue;
                    }
                }
                try {
                    record.mListener.onReceived(srcAddress, destAddress, params, hasVendorId);
                    notifiedVendorCommandToListeners = true;
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to notify vendor command reception", e);
                }
            }
            return notifiedVendorCommandToListeners;
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

    @HdmiControlManager.VolumeControl
    int getHdmiCecVolumeControl() {
        synchronized (mLock) {
            return mHdmiCecVolumeControl;
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
        runOnServiceThread(this::checkAndUpdateAbsoluteVolumeBehavior);
    }

    @ServiceThreadOnly
    void setCecEnabled(@HdmiControlManager.HdmiCecControl int enabled) {
        assertRunOnServiceThread();

        synchronized (mLock) {
            mHdmiControlEnabled = enabled;
        }

        if (enabled == HDMI_CEC_CONTROL_ENABLED) {
            onEnableCec();
            setHdmiCecVolumeControlEnabledInternal(getHdmiCecConfig().getIntValue(
                    HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE));
            return;
        }

        setHdmiCecVolumeControlEnabledInternal(HdmiControlManager.VOLUME_CONTROL_DISABLED);
        // Call the vendor handler before the service is disabled.
        invokeVendorCommandListenersOnControlStateChanged(false,
                HdmiControlManager.CONTROL_STATE_CHANGED_REASON_SETTING);
        // Post the remained tasks in the service thread again to give the vendor-issued-tasks
        // a chance to run.
        runOnServiceThread(new Runnable() {
            @Override
            public void run() {
                onDisableCec();
            }
        });
        announceHdmiControlStatusChange(enabled);

        return;
    }

    @ServiceThreadOnly
    private void onEnableCec() {
        mCecController.enableCec(true);
        mCecController.enableSystemCecControl(true);
        mMhlController.setOption(OPTION_MHL_ENABLE, ENABLED);

        initializeCec(INITIATED_BY_ENABLE_CEC);
    }

    @ServiceThreadOnly
    private void onDisableCec() {
        disableCecLocalDevices(
                new PendingActionClearedCallback() {
                    @Override
                    public void onCleared(HdmiCecLocalDevice device) {
                        assertRunOnServiceThread();
                        mCecController.flush(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        mCecController.enableCec(false);
                                        mCecController.enableSystemCecControl(false);
                                        mMhlController.setOption(OPTION_MHL_ENABLE, DISABLED);
                                        clearCecLocalDevices();
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

    @VisibleForTesting
    void pauseActiveMediaSessions() {
        MediaSessionManager mediaSessionManager = getContext()
                .getSystemService(MediaSessionManager.class);
        List<MediaController> mediaControllers = mediaSessionManager.getActiveSessions(null);
        for (MediaController mediaController : mediaControllers) {
            mediaController.getTransportControls().pause();
        }
    }

    void setActiveSource(int logicalAddress, int physicalAddress, String caller) {
        synchronized (mLock) {
            mActiveSource.logicalAddress = logicalAddress;
            mActiveSource.physicalAddress = physicalAddress;
        }

        getAtomWriter().activeSourceChanged(logicalAddress, physicalAddress,
                HdmiUtils.pathRelationship(getPhysicalAddress(), physicalAddress));

        // If the current device is a source device, check if the current Active Source matches
        // the local device info.
        for (HdmiCecLocalDevice device : getAllCecLocalDevices()) {
            boolean deviceIsActiveSource =
                    logicalAddress == device.getDeviceInfo().getLogicalAddress()
                            && physicalAddress == getPhysicalAddress();

            device.addActiveSourceHistoryItem(new ActiveSource(logicalAddress, physicalAddress),
                    deviceIsActiveSource, caller);
        }

        runOnServiceThread(this::checkAndUpdateAbsoluteVolumeBehavior);
    }

    // This method should only be called when the device can be the active source
    // and all the device types call into this method.
    // For example, when receiving broadcast messages, all the device types will call this
    // method but only one of them will be the Active Source.
    protected void setAndBroadcastActiveSource(
            int physicalAddress, int deviceType, int source, String caller) {
        // If the device has both playback and audio system logical addresses,
        // playback will claim active source. Otherwise audio system will.
        if (deviceType == HdmiDeviceInfo.DEVICE_PLAYBACK) {
            HdmiCecLocalDevicePlayback playback = playback();
            playback.dismissUiOnActiveSourceStatusRecovered();
            playback.setActiveSource(playback.getDeviceInfo().getLogicalAddress(), physicalAddress,
                    caller);
            playback.wakeUpIfActiveSource();
            playback.maySendActiveSource(source);
            playback.mDelayedStandbyOnActiveSourceLostHandler.removeCallbacksAndMessages(null);
        }

        if (deviceType == HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM) {
            HdmiCecLocalDeviceAudioSystem audioSystem = audioSystem();
            if (playback() == null) {
                audioSystem.setActiveSource(audioSystem.getDeviceInfo().getLogicalAddress(),
                        physicalAddress, caller);
                audioSystem.wakeUpIfActiveSource();
                audioSystem.maySendActiveSource(source);
            }
        }
    }

    // This method should only be called when the device can be the active source
    // and only one of the device types calls into this method.
    // For example, when receiving One Touch Play, only playback device handles it
    // and this method updates Active Source in all the device types sharing the same
    // Physical Address.
    protected void setAndBroadcastActiveSourceFromOneDeviceType(
            int sourceAddress, int physicalAddress, String caller) {
        // If the device has both playback and audio system logical addresses,
        // playback will claim active source. Otherwise audio system will.
        HdmiCecLocalDevicePlayback playback = playback();
        HdmiCecLocalDeviceAudioSystem audioSystem = audioSystem();
        if (playback != null) {
            playback.setActiveSource(playback.getDeviceInfo().getLogicalAddress(), physicalAddress,
                    caller);
            playback.wakeUpIfActiveSource();
            playback.maySendActiveSource(sourceAddress);
        } else if (audioSystem != null) {
            audioSystem.setActiveSource(audioSystem.getDeviceInfo().getLogicalAddress(),
                    physicalAddress, caller);
            audioSystem.wakeUpIfActiveSource();
            audioSystem.maySendActiveSource(sourceAddress);
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
                : mHdmiCecNetwork.getDeviceForPortId(portId);
        invokeInputChangeListener(info);
    }

    void setMhlInputChangeEnabled(boolean enabled) {
        mMhlController.setOption(OPTION_MHL_INPUT_SWITCHING, toInt(enabled));

        synchronized (mLock) {
            mMhlInputChangeEnabled = enabled;
        }
    }

    @VisibleForTesting
    protected HdmiCecAtomWriter getAtomWriter() {
        return mAtomWriter;
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
        sendBroadcastAsUser(intent);
    }

    @ServiceThreadOnly
    void displayOsd(int messageId, int extra) {
        assertRunOnServiceThread();
        Intent intent = new Intent(HdmiControlManager.ACTION_OSD_MESSAGE);
        intent.putExtra(HdmiControlManager.EXTRA_MESSAGE_ID, messageId);
        intent.putExtra(HdmiControlManager.EXTRA_MESSAGE_EXTRA_PARAM1, extra);
        sendBroadcastAsUser(intent);
    }

    // This method is used such that we can override it inside unit tests to avoid a
    // SecurityException.
    @ServiceThreadOnly
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS)
    protected void sendBroadcastAsUser(@RequiresPermission Intent intent) {
        assertRunOnServiceThread();
        getContext().sendBroadcastAsUser(intent, UserHandle.ALL, HdmiControlService.PERMISSION);
    }

    @VisibleForTesting
    protected HdmiCecConfig getHdmiCecConfig() {
        return mHdmiCecConfig;
    }

    private HdmiCecConfig.SettingChangeListener mSettingChangeListener =
            new HdmiCecConfig.SettingChangeListener() {
                @Override
                public void onChange(String name) {
                    synchronized (mLock) {
                        if (!mHdmiCecSettingChangeListenerRecords.containsKey(name)) {
                            return;
                        }
                        mHdmiCecSettingChangeListenerRecords.get(name).broadcast(listener -> {
                            invokeCecSettingChangeListenerLocked(name, listener);
                        });
                    }
                }
            };

    private void addCecSettingChangeListener(String name,
            final IHdmiCecSettingChangeListener listener) {
        synchronized (mLock) {
            if (!mHdmiCecSettingChangeListenerRecords.containsKey(name)) {
                mHdmiCecSettingChangeListenerRecords.put(name, new RemoteCallbackList<>());
                mHdmiCecConfig.registerChangeListener(name, mSettingChangeListener);
            }
            mHdmiCecSettingChangeListenerRecords.get(name).register(listener);
        }
    }

    private void removeCecSettingChangeListener(String name,
            final IHdmiCecSettingChangeListener listener) {
        synchronized (mLock) {
            if (!mHdmiCecSettingChangeListenerRecords.containsKey(name)) {
                return;
            }
            mHdmiCecSettingChangeListenerRecords.get(name).unregister(listener);
            if (mHdmiCecSettingChangeListenerRecords.get(name).getRegisteredCallbackCount() == 0) {
                mHdmiCecSettingChangeListenerRecords.remove(name);
                mHdmiCecConfig.removeChangeListener(name, mSettingChangeListener);
            }
        }
    }

    private void invokeCecSettingChangeListenerLocked(String name,
            final IHdmiCecSettingChangeListener listener) {
        try {
            listener.onChange(name);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to report setting change", e);
        }
    }

    /**
     * Listener for changes to the volume behavior of an audio output device. Caches the
     * volume behavior of devices used for absolute volume behavior.
     */
    @VisibleForTesting
    @ServiceThreadOnly
    void onDeviceVolumeBehaviorChanged(AudioDeviceAttributes device, int volumeBehavior) {
        assertRunOnServiceThread();
        if (AVB_AUDIO_OUTPUT_DEVICES.contains(device)) {
            synchronized (mLock) {
                mAudioDeviceVolumeBehaviors.put(device, volumeBehavior);
            }
            checkAndUpdateAbsoluteVolumeBehavior();
        }
    }

    /**
     * Wrapper for {@link AudioManager#getDeviceVolumeBehavior} that takes advantage of cached
     * results for the volume behaviors of HDMI audio devices.
     */
    @AudioManager.DeviceVolumeBehavior
    private int getDeviceVolumeBehavior(AudioDeviceAttributes device) {
        if (AVB_AUDIO_OUTPUT_DEVICES.contains(device)) {
            synchronized (mLock) {
                if (mAudioDeviceVolumeBehaviors.containsKey(device)) {
                    return mAudioDeviceVolumeBehaviors.get(device);
                }
            }
        }
        return getAudioManager().getDeviceVolumeBehavior(device);
    }

    /**
     * Returns whether absolute volume behavior is enabled or not. This is true if any AVB-capable
     * audio output device is using absolute volume behavior.
     */
    public boolean isAbsoluteVolumeBehaviorEnabled() {
        if (!isTvDevice() && !isPlaybackDevice()) {
            return false;
        }
        for (AudioDeviceAttributes device : getAvbCapableAudioOutputDevices()) {
            if (ABSOLUTE_VOLUME_BEHAVIORS.contains(getDeviceVolumeBehavior(device))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a list of audio output devices that may adopt absolute volume behavior.
     */
    private List<AudioDeviceAttributes> getAvbCapableAudioOutputDevices() {
        if (tv() != null) {
            return TV_AVB_AUDIO_OUTPUT_DEVICES;
        } else if (playback() != null) {
            return PLAYBACK_AVB_AUDIO_OUTPUT_DEVICES;
        } else {
            return Collections.emptyList();
        }
    }

    /**
     * This method is responsible for adopting or disabling absolute volume behavior and
     * adjust-only absolute volume behavior in AudioService. These volume behaviors are adopted on
     * specific audio output devices: HDMI for playback devices, and HDMI_ARC or HDMI_EARC for TVs.
     *
     * This method enables absolute volume behavior on a Playback device or TV panel when it is
     * playing audio on an external device (the System Audio device) that supports the feature.
     * This allows the volume level of the System Audio device to be tracked and set by Android.
     *
     * Absolute volume behavior requires the following conditions:
     * 1. The device is not in standby or transient to standby
     * 2. If the System Audio Device is an Audio System: System Audio Mode is active
     * 3. All AVB-capable audio output devices are already using full/absolute volume behavior
     * 4. CEC volume is enabled
     * 5. The System Audio device supports the <Set Audio Volume Level> message
     *
     * This method enables adjust-only absolute volume behavior on TV panels when conditions
     * 1, 2, and 3 are met, but condition 4 is not. This allows TVs to track the volume level of
     * the System Audio device and display numeric volume UI for it, even if the System Audio device
     * does not support <Set Audio Volume Level>.
     */
    @ServiceThreadOnly
    void checkAndUpdateAbsoluteVolumeBehavior() {
        assertRunOnServiceThread();

        // Can't set volume behavior before we have access to system services
        if (getAudioManager() == null) {
            return;
        }

        // Condition 1: The device is not in standby or transient to standby
        if (mPowerStatusController != null && isPowerStandbyOrTransient()) {
            switchToFullVolumeBehavior();
            return;
        }

        HdmiCecLocalDevice localCecDevice;
        if (isTvDevice() && tv() != null) {
            localCecDevice = tv();
            // Condition 2: TVs need System Audio Mode to be active
            // (Doesn't apply to Playback Devices, where if SAM isn't active, we assume the
            // TV is the System Audio Device instead.)
            if (!isSystemAudioActivated()) {
                switchToFullVolumeBehavior();
                return;
            }
        } else if (isPlaybackDevice() && playback() != null) {
            localCecDevice = playback();
        } else {
            // Either this device type doesn't support AVB, or it hasn't fully initialized yet
            return;
        }

        HdmiDeviceInfo systemAudioDeviceInfo = getDeviceInfo(
                localCecDevice.findAudioReceiverAddress());

        // Condition 3: All AVB-capable audio outputs already use full/absolute volume behavior
        // We only need to check the first AVB-capable audio output because only TV panels
        // have more than one of them, and they always have the same volume behavior.
        @AudioManager.DeviceVolumeBehavior int currentVolumeBehavior =
                getDeviceVolumeBehavior(getAvbCapableAudioOutputDevices().get(0));
        boolean alreadyUsingFullOrAbsoluteVolume =
                FULL_AND_ABSOLUTE_VOLUME_BEHAVIORS.contains(currentVolumeBehavior);

        // Condition 4: CEC volume is enabled
        boolean cecVolumeEnabled =
                getHdmiCecVolumeControl() == HdmiControlManager.VOLUME_CONTROL_ENABLED;

        if (!cecVolumeEnabled || !alreadyUsingFullOrAbsoluteVolume) {
            switchToFullVolumeBehavior();
            return;
        }

        // Check for safety: if the System Audio device is a candidate for AVB, we should already
        // have received messages from it to trigger the other conditions.
        if (systemAudioDeviceInfo == null) {
            switchToFullVolumeBehavior();
            return;
        }

        // Condition 5: The System Audio device supports <Set Audio Volume Level>
        switch (systemAudioDeviceInfo.getDeviceFeatures().getSetAudioVolumeLevelSupport()) {
            case DeviceFeatures.FEATURE_SUPPORTED:
                if (currentVolumeBehavior != AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE) {
                    // Start an action that will call enableAbsoluteVolumeBehavior
                    // once the System Audio device sends <Report Audio Status>
                    localCecDevice.startNewAvbAudioStatusAction(
                            systemAudioDeviceInfo.getLogicalAddress());
                }
                return;
            case DeviceFeatures.FEATURE_NOT_SUPPORTED:
                // TVs may adopt adjust-only absolute volume behavior if condition 4 isn't met.
                // This allows the device to display numeric volume UI for the System Audio device.
                if (tv() != null && mNumericSoundbarVolumeUiOnTvFeatureFlagEnabled) {
                    if (currentVolumeBehavior
                            != AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_ADJUST_ONLY) {
                        // If we're currently using absolute volume behavior, switch to full volume
                        // behavior until we successfully adopt adjust-only absolute volume behavior
                        if (currentVolumeBehavior == AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE) {
                            for (AudioDeviceAttributes device : getAvbCapableAudioOutputDevices()) {
                                getAudioManager().setDeviceVolumeBehavior(device,
                                        AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL);
                            }
                        }
                        // Start an action that will call enableAbsoluteVolumeBehavior
                        // once the System Audio device sends <Report Audio Status>
                        localCecDevice.startNewAvbAudioStatusAction(
                                systemAudioDeviceInfo.getLogicalAddress());
                    }
                } else {
                    switchToFullVolumeBehavior();
                }
                return;
            case DeviceFeatures.FEATURE_SUPPORT_UNKNOWN:
                if (currentVolumeBehavior == AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE) {
                    switchToFullVolumeBehavior();
                }
                localCecDevice.querySetAudioVolumeLevelSupport(
                        systemAudioDeviceInfo.getLogicalAddress());
        }
    }

    /**
     * Switches to full volume behavior, if either absolute or adjust-only absolute volume behavior
     * are currently used. Removes the action for handling volume updates for these behaviors.
     */
    private void switchToFullVolumeBehavior() {
        Slog.d(TAG, "Switching to full volume behavior");

        if (playback() != null) {
            playback().removeAvbAudioStatusAction();
        } else if (tv() != null) {
            tv().removeAvbAudioStatusAction();
        }

        for (AudioDeviceAttributes device : getAvbCapableAudioOutputDevices()) {
            if (ABSOLUTE_VOLUME_BEHAVIORS.contains(getDeviceVolumeBehavior(device))) {
                getAudioManager().setDeviceVolumeBehavior(device,
                        AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL);
            }
        }
    }

    /**
     * Enables absolute volume behavior or adjust-only absolute volume behavior. Should only be
     * called when the conditions for one of these behaviors is met -
     * see {@link #checkAndUpdateAbsoluteVolumeBehavior}.
     *
     * @param audioStatus The initial audio status to set the audio output device to
     */
    void enableAbsoluteVolumeBehavior(AudioStatus audioStatus) {
        HdmiCecLocalDevice localDevice = isPlaybackDevice() ? playback() : tv();
        HdmiDeviceInfo systemAudioDevice = getDeviceInfo(localDevice.findAudioReceiverAddress());
        VolumeInfo volumeInfo = new VolumeInfo.Builder(AudioManager.STREAM_MUSIC)
                .setMuted(audioStatus.getMute())
                .setVolumeIndex(audioStatus.getVolume())
                .setMaxVolumeIndex(AudioStatus.MAX_VOLUME)
                .setMinVolumeIndex(AudioStatus.MIN_VOLUME)
                .build();
        mAbsoluteVolumeChangedListener = new AbsoluteVolumeChangedListener(
                localDevice, systemAudioDevice);

        // AudioService sets the volume of the stream and device based on the input VolumeInfo
        // when enabling absolute volume behavior, but not the mute state
        notifyAvbMuteChange(audioStatus.getMute());

        // If <Set Audio Volume Level> is supported, enable absolute volume behavior.
        // Otherwise, enable adjust-only AVB on TVs only.
        if (systemAudioDevice.getDeviceFeatures().getSetAudioVolumeLevelSupport()
                == DeviceFeatures.FEATURE_SUPPORTED) {
            Slog.d(TAG, "Enabling absolute volume behavior");
            for (AudioDeviceAttributes device : getAvbCapableAudioOutputDevices()) {
                getAudioDeviceVolumeManager().setDeviceAbsoluteVolumeBehavior(
                        device, volumeInfo, mServiceThreadExecutor,
                        mAbsoluteVolumeChangedListener, true);
            }
        } else if (tv() != null) {
            Slog.d(TAG, "Enabling adjust-only absolute volume behavior");
            for (AudioDeviceAttributes device : getAvbCapableAudioOutputDevices()) {
                getAudioDeviceVolumeManager().setDeviceAbsoluteVolumeAdjustOnlyBehavior(
                        device, volumeInfo, mServiceThreadExecutor,
                        mAbsoluteVolumeChangedListener, true);
            }
        }

    }

    private AbsoluteVolumeChangedListener mAbsoluteVolumeChangedListener;

    @VisibleForTesting
    AbsoluteVolumeChangedListener getAbsoluteVolumeChangedListener() {
        return mAbsoluteVolumeChangedListener;
    }

    /**
     * Listeners for changes reported by AudioService to the state of an audio output device using
     * absolute volume behavior.
     */
    @VisibleForTesting
    class AbsoluteVolumeChangedListener implements
            AudioDeviceVolumeManager.OnAudioDeviceVolumeChangedListener {
        private HdmiCecLocalDevice mLocalDevice;
        private HdmiDeviceInfo mSystemAudioDevice;

        private AbsoluteVolumeChangedListener(HdmiCecLocalDevice localDevice,
                HdmiDeviceInfo systemAudioDevice) {
            mLocalDevice = localDevice;
            mSystemAudioDevice = systemAudioDevice;
        }

        /**
         * Called when AudioService sets the volume level of an absolute volume audio output device
         * to a numeric value.
         */
        @Override
        public void onAudioDeviceVolumeChanged(
                @NonNull AudioDeviceAttributes audioDevice,
                @NonNull VolumeInfo volumeInfo) {
            int localDeviceAddress = mLocalDevice.getDeviceInfo().getLogicalAddress();

            // We can't send <Set Audio Volume Level> if the System Audio device doesn't support it.
            // But AudioService has already updated its volume and expects us to set it.
            // So the best we can do is to send <Give Audio Status>, which triggers
            // <Report Audio Status>, which should update AudioService with its correct volume.
            if (mSystemAudioDevice.getDeviceFeatures().getSetAudioVolumeLevelSupport()
                    != DeviceFeatures.FEATURE_SUPPORTED) {
                // Update the volume tracked in AbsoluteVolumeAudioStatusAction
                // so it correctly processes the next <Report Audio Status>
                HdmiCecLocalDevice avbDevice = isTvDevice() ? tv() : playback();
                avbDevice.updateAvbVolume(volumeInfo.getVolumeIndex());
                // Send <Give Audio Status>
                sendCecCommand(HdmiCecMessageBuilder.buildGiveAudioStatus(
                        localDeviceAddress,
                        mSystemAudioDevice.getLogicalAddress()
                ));
                return;
            }

            // Send <Set Audio Volume Level> to notify the System Audio device of the volume change
            sendCecCommand(SetAudioVolumeLevelMessage.build(
                            localDeviceAddress,
                            mSystemAudioDevice.getLogicalAddress(),
                            volumeInfo.getVolumeIndex()),
                    // If sending the message fails, ask the System Audio device for its
                    // audio status so that we can update AudioService
                    (int errorCode) -> {
                        if (errorCode == SendMessageResult.SUCCESS) {
                            // Update the volume tracked in our AbsoluteVolumeAudioStatusAction
                            // so it correctly processes incoming <Report Audio Status> messages
                            HdmiCecLocalDevice avbDevice = isTvDevice() ? tv() : playback();
                            avbDevice.updateAvbVolume(volumeInfo.getVolumeIndex());
                        } else {
                            sendCecCommand(HdmiCecMessageBuilder.buildGiveAudioStatus(
                                    localDeviceAddress,
                                    mSystemAudioDevice.getLogicalAddress()
                            ));
                        }
                    });
        }

        /**
         * Called when AudioService adjusts the volume or mute state of an absolute volume
         * audio output device
         */
        @Override
        public void onAudioDeviceVolumeAdjusted(
                @NonNull AudioDeviceAttributes audioDevice,
                @NonNull VolumeInfo volumeInfo,
                @AudioManager.VolumeAdjustment int direction,
                @AudioDeviceVolumeManager.VolumeAdjustmentMode int mode
        ) {
            int keyCode;
            switch (direction) {
                case AudioManager.ADJUST_RAISE:
                    keyCode = KeyEvent.KEYCODE_VOLUME_UP;
                    break;
                case AudioManager.ADJUST_LOWER:
                    keyCode = KeyEvent.KEYCODE_VOLUME_DOWN;
                    break;
                case AudioManager.ADJUST_TOGGLE_MUTE:
                case AudioManager.ADJUST_MUTE:
                case AudioManager.ADJUST_UNMUTE:
                    // Many CEC devices only support toggle mute. Therefore, we send the
                    // same keycode for all three mute options.
                    keyCode = KeyEvent.KEYCODE_VOLUME_MUTE;
                    break;
                case AudioManager.ADJUST_SAME:
                    // Query the current audio status of the Audio System and display UI for it
                    // Only for TVs, because Playback devices don't display UI when using AVB
                    if (tv() != null) {
                        tv().requestAndUpdateAvbAudioStatus();
                    }
                    return;
                default:
                    return;
            }
            switch (mode) {
                case AudioDeviceVolumeManager.ADJUST_MODE_NORMAL:
                    mLocalDevice.sendVolumeKeyEvent(keyCode, true);
                    mLocalDevice.sendVolumeKeyEvent(keyCode, false);
                    break;
                case AudioDeviceVolumeManager.ADJUST_MODE_START:
                    mLocalDevice.sendVolumeKeyEvent(keyCode, true);
                    break;
                case AudioDeviceVolumeManager.ADJUST_MODE_END:
                    mLocalDevice.sendVolumeKeyEvent(keyCode, false);
                    break;
                default:
                    return;
            }
        }
    }

    /**
     * Notifies AudioService of a change in the volume of the System Audio device. Has no effect if
     * AVB is disabled, or STREAM_MUSIC is not playing on any AVB device.
     */
    void notifyAvbVolumeChange(int volume) {
        if (!isAbsoluteVolumeBehaviorEnabled()) return;
        List<AudioDeviceAttributes> streamMusicDevices =
                getAudioManager().getDevicesForAttributes(STREAM_MUSIC_ATTRIBUTES);
        for (AudioDeviceAttributes streamMusicDevice : streamMusicDevices) {
            if (getAvbCapableAudioOutputDevices().contains(streamMusicDevice)) {
                int flags = AudioManager.FLAG_ABSOLUTE_VOLUME;
                if (isTvDevice()) {
                    flags |= AudioManager.FLAG_SHOW_UI;
                }
                setStreamMusicVolume(volume, flags);
                return;
            }
        }
    }

    /**
     * Notifies AudioService of a change in the mute status of the System Audio device. Has no
     * effect if AVB is disabled, or the audio output device for AVB is not playing for STREAM_MUSIC
     */
    void notifyAvbMuteChange(boolean mute) {
        if (!isAbsoluteVolumeBehaviorEnabled()) return;
        List<AudioDeviceAttributes> streamMusicDevices =
                getAudioManager().getDevicesForAttributes(STREAM_MUSIC_ATTRIBUTES);
        for (AudioDeviceAttributes streamMusicDevice : streamMusicDevices) {
            if (getAvbCapableAudioOutputDevices().contains(streamMusicDevice)) {
                int direction = mute ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE;
                int flags = AudioManager.FLAG_ABSOLUTE_VOLUME;
                if (isTvDevice()) {
                    flags |= AudioManager.FLAG_SHOW_UI;
                }
                getAudioManager().adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, flags);
                return;
            }
        }
    }

    /**
     * Sets the volume index of {@link AudioManager#STREAM_MUSIC}. Rescales the input volume index
     * from HDMI-CEC volume range to STREAM_MUSIC's.
     */
    void setStreamMusicVolume(int volume, int flags) {
        getAudioManager().setStreamVolume(AudioManager.STREAM_MUSIC,
                volume * mStreamMusicMaxVolume / AudioStatus.MAX_VOLUME, flags);
    }

    private void initializeEarc(int initiatedBy) {
        Slog.i(TAG, "eARC initialized, reason = " + initiatedBy);
        initializeEarcLocalDevice(initiatedBy);

        if (initiatedBy == INITIATED_BY_ENABLE_EARC) {
            // Since ARC and eARC cannot be connected simultaneously, we need to terminate ARC
            // before even enabling eARC.
            setEarcEnabledInHal(true, true);
        } else {
            // On boot, wake-up, and hotplug in, eARC will always be attempted before ARC.
            // So there is no need to explicitly terminate ARC before enabling eARC.
            setEarcEnabledInHal(true, false);
        }
    }

    @ServiceThreadOnly
    @VisibleForTesting
    protected void initializeEarcLocalDevice(final int initiatedBy) {
        // TODO remove initiatedBy argument if it stays unused
        assertRunOnServiceThread();
        if (mEarcLocalDevice == null) {
            mEarcLocalDevice = HdmiEarcLocalDevice.create(this, HdmiDeviceInfo.DEVICE_TV);
        }
        // TODO create HdmiEarcLocalDeviceRx if we're an audio system device.
    }

    @ServiceThreadOnly
    @VisibleForTesting
    protected void setEarcEnabled(@HdmiControlManager.EarcFeature int enabled) {
        assertRunOnServiceThread();
        synchronized (mLock) {
            mEarcEnabled = (enabled == EARC_FEATURE_ENABLED);

            if (!isEarcSupported()) {
                Slog.i(TAG, "Enabled/disabled eARC setting, but the hardware doesn´t support eARC. "
                        + "This settings change doesn´t have an effect.");
                return;
            }

            if (mEarcEnabled) {
                onEnableEarc();
                return;
            }
        }
        runOnServiceThread(new Runnable() {
            @Override
            public void run() {
                onDisableEarc();
            }
        });
    }

    @VisibleForTesting
    protected void setEarcSupported(boolean supported) {
        synchronized (mLock) {
            mEarcSupported = supported;
        }
    }

    @ServiceThreadOnly
    private void onEnableEarc() {
        // This will terminate ARC as well.
        initializeEarc(INITIATED_BY_ENABLE_EARC);
    }

    @ServiceThreadOnly
    private void onDisableEarc() {
        disableEarcLocalDevice();
        setEarcEnabledInHal(false, false);
        clearEarcLocalDevice();
    }

    @ServiceThreadOnly
    @VisibleForTesting
    protected void clearEarcLocalDevice() {
        assertRunOnServiceThread();
        mEarcLocalDevice = null;
    }

    @ServiceThreadOnly
    @VisibleForTesting
    protected void addEarcLocalDevice(HdmiEarcLocalDevice localDevice) {
        assertRunOnServiceThread();
        mEarcLocalDevice = localDevice;
    }

    @ServiceThreadOnly
    private int getEarcStatus() {
        assertRunOnServiceThread();
        if (mEarcLocalDevice != null) {
            synchronized (mLock) {
                return mEarcLocalDevice.mEarcStatus;
            }
        }
        return HDMI_EARC_STATUS_UNKNOWN;
    }

    @ServiceThreadOnly
    @VisibleForTesting
    HdmiEarcLocalDevice getEarcLocalDevice() {
        assertRunOnServiceThread();
        return mEarcLocalDevice;
    }

    private void disableEarcLocalDevice() {
        if (mEarcLocalDevice == null) {
            return;
        }
        mEarcLocalDevice.disableDevice();
    }

    @ServiceThreadOnly
    @VisibleForTesting
    protected void setEarcEnabledInHal(boolean enabled, boolean terminateArcFirst) {
        assertRunOnServiceThread();
        if (terminateArcFirst) {
            startArcAction(false, new IHdmiControlCallback.Stub() {
                @Override
                public void onComplete(int result) throws RemoteException {
                    if (result != HdmiControlManager.RESULT_SUCCESS) {
                        Slog.w(TAG,
                                "ARC termination before enabling eARC in the HAL failed with "
                                        + "result: " + result);
                    }
                    // Independently of the result (i.e. independently of whether the ARC RX device
                    // responded with <Terminate ARC> or not), we always end up terminating ARC in
                    // the HAL. As soon as we do that, we can enable eARC in the HAL.
                    mEarcController.setEarcEnabled(enabled);
                    mCecController.setHpdSignalType(
                            enabled ? Constants.HDMI_HPD_TYPE_STATUS_BIT
                                    : Constants.HDMI_HPD_TYPE_PHYSICAL,
                            mEarcPortId);
                }
            });
        } else {
            mEarcController.setEarcEnabled(enabled);
            mCecController.setHpdSignalType(
                    enabled ? Constants.HDMI_HPD_TYPE_STATUS_BIT : Constants.HDMI_HPD_TYPE_PHYSICAL,
                    mEarcPortId);
        }
    }

    @ServiceThreadOnly
    void handleEarcStateChange(int status, int portId) {
        assertRunOnServiceThread();
        int oldEarcStatus = getEarcStatus();
        if (!getPortInfo(portId).isEarcSupported()) {
            Slog.w(TAG, "Tried to update eARC status on a port that doesn't support eARC.");
            getAtomWriter().earcStatusChanged(isEarcSupported(), isEarcEnabled(), oldEarcStatus,
                    status, HdmiStatsEnums.LOG_REASON_EARC_STATUS_CHANGED_UNSUPPORTED_PORT);
            return;
        }
        if (mEarcLocalDevice != null) {
            mEarcLocalDevice.handleEarcStateChange(status);
            getAtomWriter().earcStatusChanged(isEarcSupported(), isEarcEnabled(),
                    oldEarcStatus, status, HdmiStatsEnums.LOG_REASON_EARC_STATUS_CHANGED);
        } else if (status == HDMI_EARC_STATUS_ARC_PENDING) {
            // If eARC is disabled, the local device is null. This is why we notify
            // AudioService here that the eARC connection is terminated.
            HdmiLogger.debug("eARC state change [new: HDMI_EARC_STATUS_ARC_PENDING(2)]");
            notifyEarcStatusToAudioService(false, new ArrayList<>());
            mHandler.postDelayed( new Runnable() {
                @Override
                public void run() {
                    startArcAction(true, null);
                }
            }, EARC_TRIGGER_START_ARC_ACTION_DELAY);
            getAtomWriter().earcStatusChanged(isEarcSupported(), isEarcEnabled(),
                    oldEarcStatus, status, HdmiStatsEnums.LOG_REASON_EARC_STATUS_CHANGED);
        } else {
            getAtomWriter().earcStatusChanged(isEarcSupported(), isEarcEnabled(),
                    oldEarcStatus, status,
                    HdmiStatsEnums.LOG_REASON_EARC_STATUS_CHANGED_WRONG_STATE);
        }
    }

    protected void notifyEarcStatusToAudioService(
            boolean enabled, List<AudioDescriptor> audioDescriptors) {
        AudioDeviceAttributes attributes = new AudioDeviceAttributes(
                AudioDeviceAttributes.ROLE_OUTPUT, AudioDeviceInfo.TYPE_HDMI_EARC, "", "",
                new ArrayList<AudioProfile>(), audioDescriptors);
        // Set SAM to ON whenever CEC is disabled. Failure to do so may result in the absence
        // of sound when CEC is disabled and eARC is enabled due to SAM being in the off state.
        if (!isCecControlEnabled()) {
            setSystemAudioActivated(true);
        }
        getAudioManager().setWiredDeviceConnectionState(attributes, enabled ? 1 : 0);
    }

    @ServiceThreadOnly
    void handleEarcCapabilitiesReported(byte[] rawCapabilities, int portId) {
        assertRunOnServiceThread();
        if (!getPortInfo(portId).isEarcSupported()) {
            Slog.w(TAG,
                    "Tried to process eARC capabilities from a port that doesn't support eARC.");
            return;
        }
        // If eARC is disabled, the local device is null. In this case, the HAL shouldn't have
        // reported eARC capabilities, but even if it did, it won't take effect.
        if (mEarcLocalDevice != null) {
            mEarcLocalDevice.handleEarcCapabilitiesReported(rawCapabilities);
        }
    }

    protected boolean earcBlocksArcConnection() {
        if (mEarcLocalDevice == null) {
            return false;
        }
        synchronized (mLock) {
            return mEarcLocalDevice.mEarcStatus != HDMI_EARC_STATUS_ARC_PENDING;
        }
    }

    protected void startArcAction(boolean enabled, IHdmiControlCallback callback) {
        if (!isTvDeviceEnabled()) {
            invokeCallback(callback, HdmiControlManager.RESULT_INCORRECT_MODE);
        } else {
            tv().startArcAction(enabled, callback);
        }
    }
}
