/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.hardware.hdmi.DeviceFeatures.FEATURE_NOT_SUPPORTED;
import static android.hardware.hdmi.DeviceFeatures.FEATURE_SUPPORTED;

import static com.android.server.hdmi.Constants.ALWAYS_SYSTEM_AUDIO_CONTROL_ON_POWER_ON;
import static com.android.server.hdmi.Constants.PROPERTY_SYSTEM_AUDIO_CONTROL_ON_POWER_ON;
import static com.android.server.hdmi.Constants.USE_LAST_STATE_SYSTEM_AUDIO_CONTROL_ON_POWER_ON;

import android.annotation.Nullable;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.hardware.hdmi.DeviceFeatures;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiPortInfo;
import android.hardware.hdmi.IHdmiControlCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager.TvInputCallback;
import android.os.SystemProperties;
import android.sysprop.HdmiProperties;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.hdmi.Constants.AudioCodec;
import com.android.server.hdmi.DeviceDiscoveryAction.DeviceDiscoveryCallback;
import com.android.server.hdmi.HdmiAnnotations.ServiceThreadOnly;
import com.android.server.hdmi.HdmiUtils.CodecSad;
import com.android.server.hdmi.HdmiUtils.DeviceConfig;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Represent a logical device of type {@link HdmiDeviceInfo#DEVICE_AUDIO_SYSTEM} residing in Android
 * system.
 */
public class HdmiCecLocalDeviceAudioSystem extends HdmiCecLocalDeviceSource {

    private static final String TAG = "HdmiCecLocalDeviceAudioSystem";

    private static final boolean WAKE_ON_HOTPLUG = false;

    // Whether the System Audio Control feature is enabled or not. True by default.
    @GuardedBy("mLock")
    private boolean mSystemAudioControlFeatureEnabled;

    /**
     * Indicates if the TV that the current device is connected to supports System Audio Mode or not
     *
     * <p>If the current device has no information on this, keep mTvSystemAudioModeSupport null
     *
     * <p>The boolean will be reset to null every time when the current device goes to standby
     * or loses its physical address.
     */
    private Boolean mTvSystemAudioModeSupport = null;

    // Whether ARC is available or not. "true" means that ARC is established between TV and
    // AVR as audio receiver.
    @ServiceThreadOnly private boolean mArcEstablished = false;

    // If the current device uses TvInput for ARC. We assume all other inputs also use TvInput
    // when ARC is using TvInput.
    private boolean mArcIntentUsed = HdmiProperties.arc_port().orElse("0").contains("tvinput");

    // Keeps the mapping (HDMI port ID to TV input URI) to keep track of the TV inputs ready to
    // accept input switching request from HDMI devices.
    @GuardedBy("mLock")
    private final HashMap<Integer, String> mPortIdToTvInputs = new HashMap<>();

    // A map from TV input id to HDMI device info.
    @GuardedBy("mLock")
    private final HashMap<String, HdmiDeviceInfo> mTvInputsToDeviceInfo = new HashMap<>();

    // Message buffer used to buffer selected messages to process later. <Active Source>
    // from a source device, for instance, needs to be buffered if the device is not
    // discovered yet. The buffered commands are taken out and when they are ready to
    // handle.
    private final DelayedMessageBuffer mDelayedMessageBuffer = new DelayedMessageBuffer(this);

    protected HdmiCecLocalDeviceAudioSystem(HdmiControlService service) {
        super(service, HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);
        mRoutingControlFeatureEnabled = mService.getHdmiCecConfig().getIntValue(
                HdmiControlManager.CEC_SETTING_NAME_ROUTING_CONTROL)
                    == HdmiControlManager.ROUTING_CONTROL_ENABLED;
        mSystemAudioControlFeatureEnabled = mService.getHdmiCecConfig().getIntValue(
                HdmiControlManager.CEC_SETTING_NAME_SYSTEM_AUDIO_CONTROL)
                    == HdmiControlManager.SYSTEM_AUDIO_CONTROL_ENABLED;
        mStandbyHandler = new HdmiCecStandbyModeHandler(service, this);
    }

    private static final String SHORT_AUDIO_DESCRIPTOR_CONFIG_PATH = "/vendor/etc/sadConfig.xml";

    private final TvInputCallback mTvInputCallback = new TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            addOrUpdateTvInput(inputId);
        }

        @Override
        public void onInputRemoved(String inputId) {
            removeTvInput(inputId);
        }

        @Override
        public void onInputUpdated(String inputId) {
            addOrUpdateTvInput(inputId);
        }
    };

    @ServiceThreadOnly
    private void addOrUpdateTvInput(String inputId) {
        assertRunOnServiceThread();
        synchronized (mLock) {
            TvInputInfo tvInfo = mService.getTvInputManager().getTvInputInfo(inputId);
            if (tvInfo == null) {
                return;
            }
            HdmiDeviceInfo info = tvInfo.getHdmiDeviceInfo();
            if (info == null) {
                return;
            }
            mPortIdToTvInputs.put(info.getPortId(), inputId);
            mTvInputsToDeviceInfo.put(inputId, info);
            if (info.isCecDevice()) {
                processDelayedActiveSource(info.getLogicalAddress());
            }
        }
    }

    @ServiceThreadOnly
    private void removeTvInput(String inputId) {
        assertRunOnServiceThread();
        synchronized (mLock) {
            if (mTvInputsToDeviceInfo.get(inputId) == null) {
                return;
            }
            int portId = mTvInputsToDeviceInfo.get(inputId).getPortId();
            mPortIdToTvInputs.remove(portId);
            mTvInputsToDeviceInfo.remove(inputId);
        }
    }

    @Override
    @ServiceThreadOnly
    protected boolean isInputReady(int portId) {
        assertRunOnServiceThread();
        String tvInputId = mPortIdToTvInputs.get(portId);
        HdmiDeviceInfo info = mTvInputsToDeviceInfo.get(tvInputId);
        return info != null;
    }

    @Override
    protected DeviceFeatures computeDeviceFeatures() {
        boolean arcSupport = SystemProperties.getBoolean(Constants.PROPERTY_ARC_SUPPORT, true);

        return DeviceFeatures.NO_FEATURES_SUPPORTED.toBuilder()
                .setArcRxSupport(arcSupport ? FEATURE_SUPPORTED : FEATURE_NOT_SUPPORTED)
                .build();
    }

    @Override
    @ServiceThreadOnly
    void onHotplug(int portId, boolean connected) {
        assertRunOnServiceThread();
        if (WAKE_ON_HOTPLUG && connected) {
            mService.wakeUp();
        }
        if (mService.getPortInfo(portId).getType() == HdmiPortInfo.PORT_OUTPUT) {
            mCecMessageCache.flushAll();
            if (!connected) {
                if (isSystemAudioActivated()) {
                    mTvSystemAudioModeSupport = null;
                    checkSupportAndSetSystemAudioMode(false);
                }
                if (isArcEnabled()) {
                    setArcStatus(false);
                }
            }
        } else if (!connected && mPortIdToTvInputs.get(portId) != null) {
            String tvInputId = mPortIdToTvInputs.get(portId);
            HdmiDeviceInfo info = mTvInputsToDeviceInfo.get(tvInputId);
            if (info == null) {
                return;
            }
            // Update with TIF on the device removal. TIF callback will update
            // mPortIdToTvInputs and mPortIdToTvInputs.
            mService.getHdmiCecNetwork().removeCecDevice(this, info.getLogicalAddress());
        }
    }

    @Override
    @ServiceThreadOnly
    protected void disableDevice(boolean initiatedByCec, PendingActionClearedCallback callback) {
        super.disableDevice(initiatedByCec, callback);
        assertRunOnServiceThread();
        mService.unregisterTvInputCallback(mTvInputCallback);
    }

    @Override
    @ServiceThreadOnly
    protected void onStandby(boolean initiatedByCec, int standbyAction) {
        assertRunOnServiceThread();
        // Invalidate the internal active source record when goes to standby
        // This set will also update mIsActiveSource
        mService.setActiveSource(Constants.ADDR_INVALID, Constants.INVALID_PHYSICAL_ADDRESS,
                "HdmiCecLocalDeviceAudioSystem#onStandby()");
        mTvSystemAudioModeSupport = null;
        // Record the last state of System Audio Control before going to standby
        synchronized (mLock) {
            mService.writeStringSystemProperty(
                    Constants.PROPERTY_LAST_SYSTEM_AUDIO_CONTROL,
                    isSystemAudioActivated() ? "true" : "false");
        }
        terminateSystemAudioMode();
    }

    @Override
    @ServiceThreadOnly
    protected void onAddressAllocated(int logicalAddress, int reason) {
        assertRunOnServiceThread();
        if (reason == mService.INITIATED_BY_ENABLE_CEC) {
            mService.setAndBroadcastActiveSource(mService.getPhysicalAddress(),
                    getDeviceInfo().getDeviceType(), Constants.ADDR_BROADCAST,
                    "HdmiCecLocalDeviceAudioSystem#onAddressAllocated()");
        }
        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                        getDeviceInfo().getLogicalAddress(),
                        mService.getPhysicalAddress(),
                        mDeviceType));
        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildDeviceVendorIdCommand(
                        getDeviceInfo().getLogicalAddress(), mService.getVendorId()));
        mService.registerTvInputCallback(mTvInputCallback);
        // Some TVs, for example Mi TV, need ARC on before turning System Audio Mode on
        // to request Short Audio Descriptor. Since ARC and SAM are independent,
        // we can turn on ARC anyways when audio system device just boots up.
        initArcOnFromAvr();

        // This prevents turning on of System Audio Mode during a quiescent boot. If the quiescent
        // boot is exited just after this check, this code will be executed only at the next
        // wake-up.
        if (!mService.isScreenOff()) {
            int systemAudioControlOnPowerOnProp =
                    SystemProperties.getInt(
                            PROPERTY_SYSTEM_AUDIO_CONTROL_ON_POWER_ON,
                            ALWAYS_SYSTEM_AUDIO_CONTROL_ON_POWER_ON);
            boolean lastSystemAudioControlStatus =
                    SystemProperties.getBoolean(Constants.PROPERTY_LAST_SYSTEM_AUDIO_CONTROL, true);
            systemAudioControlOnPowerOn(
                    systemAudioControlOnPowerOnProp, lastSystemAudioControlStatus);
        }
        mService.getHdmiCecNetwork().clearDeviceList();
        launchDeviceDiscovery();
        startQueuedActions();
    }

    @Override
    protected int findKeyReceiverAddress() {
        if (getActiveSource().isValid()) {
            return getActiveSource().logicalAddress;
        }
        return Constants.ADDR_INVALID;
    }

    @VisibleForTesting
    protected void systemAudioControlOnPowerOn(
            int systemAudioOnPowerOnProp, boolean lastSystemAudioControlStatus) {
        if ((systemAudioOnPowerOnProp == ALWAYS_SYSTEM_AUDIO_CONTROL_ON_POWER_ON)
                || ((systemAudioOnPowerOnProp == USE_LAST_STATE_SYSTEM_AUDIO_CONTROL_ON_POWER_ON)
                && lastSystemAudioControlStatus && isSystemAudioControlFeatureEnabled())) {
            addAndStartAction(new SystemAudioInitiationActionFromAvr(this));
        }
    }

    @Override
    @ServiceThreadOnly
    protected int getPreferredAddress() {
        assertRunOnServiceThread();
        return SystemProperties.getInt(
            Constants.PROPERTY_PREFERRED_ADDRESS_AUDIO_SYSTEM, Constants.ADDR_UNREGISTERED);
    }

    @Override
    @ServiceThreadOnly
    protected void setPreferredAddress(int addr) {
        assertRunOnServiceThread();
        mService.writeStringSystemProperty(
                Constants.PROPERTY_PREFERRED_ADDRESS_AUDIO_SYSTEM, String.valueOf(addr));
    }

    @ServiceThreadOnly
    void processDelayedActiveSource(int address) {
        assertRunOnServiceThread();
        mDelayedMessageBuffer.processActiveSource(address);
    }

    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleActiveSource(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int logicalAddress = message.getSource();
        int physicalAddress = HdmiUtils.twoBytesToInt(message.getParams());
        if (HdmiUtils.getLocalPortFromPhysicalAddress(
            physicalAddress, mService.getPhysicalAddress())
                == HdmiUtils.TARGET_NOT_UNDER_LOCAL_DEVICE) {
            return super.handleActiveSource(message);
        }
        // If the new Active Source is under the current device, check if the device info and the TV
        // input is ready to switch to the new Active Source. If not ready, buffer the cec command
        // to handle later when the device is ready.
        HdmiDeviceInfo info = mService.getHdmiCecNetwork().getCecDeviceInfo(logicalAddress);
        if (info == null) {
            HdmiLogger.debug("Device info %X not found; buffering the command", logicalAddress);
            mDelayedMessageBuffer.add(message);
        } else if (!isInputReady(info.getPortId())){
            HdmiLogger.debug("Input not ready for device: %X; buffering the command", info.getId());
            mDelayedMessageBuffer.add(message);
        } else {
            mDelayedMessageBuffer.removeActiveSource();
            return super.handleActiveSource(message);
        }
        return Constants.HANDLED;
    }

    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleInitiateArc(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // TODO(amyjojo): implement initiate arc handler
        HdmiLogger.debug(TAG + "Stub handleInitiateArc");
        return Constants.HANDLED;
    }

    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleReportArcInitiate(HdmiCecMessage message) {
        assertRunOnServiceThread();
        /*
         * Ideally, we should have got this response before the {@link ArcInitiationActionFromAvr}
         * has timed out. Even if the response is late, {@link ArcInitiationActionFromAvr
         * #handleInitiateArcTimeout()} would not have disabled ARC. So nothing needs to be done
         * here.
         */
        return Constants.HANDLED;
    }

    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleReportArcTermination(HdmiCecMessage message) {
        assertRunOnServiceThread();
        processArcTermination();
        return Constants.HANDLED;
    }

    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleGiveAudioStatus(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (isSystemAudioControlFeatureEnabled() && mService.getHdmiCecVolumeControl()
                == HdmiControlManager.VOLUME_CONTROL_ENABLED) {
            reportAudioStatus(message.getSource());
            return Constants.HANDLED;
        }
        return Constants.ABORT_REFUSED;
    }

    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleGiveSystemAudioModeStatus(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // If the audio system is initiating the system audio mode on and TV asks the sam status at
        // the same time, respond with true. Since we know TV supports sam in this situation.
        // If the query comes from STB, we should respond with the current sam status and the STB
        // should listen to the <Set System Audio Mode> broadcasting.
        boolean isSystemAudioModeOnOrTurningOn = isSystemAudioActivated();
        if (!isSystemAudioModeOnOrTurningOn
                && message.getSource() == Constants.ADDR_TV
                && hasAction(SystemAudioInitiationActionFromAvr.class)) {
            isSystemAudioModeOnOrTurningOn = true;
        }
        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildReportSystemAudioMode(
                        getDeviceInfo().getLogicalAddress(),
                        message.getSource(),
                        isSystemAudioModeOnOrTurningOn));
        return Constants.HANDLED;
    }

    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleRequestArcInitiate(HdmiCecMessage message) {
        assertRunOnServiceThread();
        removeAction(ArcInitiationActionFromAvr.class);
        if (!mService.readBooleanSystemProperty(Constants.PROPERTY_ARC_SUPPORT, true)) {
            return Constants.ABORT_UNRECOGNIZED_OPCODE;
        } else if (!isDirectConnectToTv()) {
            HdmiLogger.debug("AVR device is not directly connected with TV");
            return Constants.ABORT_NOT_IN_CORRECT_MODE;
        } else {
            addAndStartAction(new ArcInitiationActionFromAvr(this));
            return Constants.HANDLED;
        }
    }

    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleRequestArcTermination(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (!SystemProperties.getBoolean(Constants.PROPERTY_ARC_SUPPORT, true)) {
            return Constants.ABORT_UNRECOGNIZED_OPCODE;
        } else if (!isArcEnabled()) {
            HdmiLogger.debug("ARC is not established between TV and AVR device");
            return Constants.ABORT_NOT_IN_CORRECT_MODE;
        } else {
            removeAction(ArcTerminationActionFromAvr.class);
            addAndStartAction(new ArcTerminationActionFromAvr(this));
            return Constants.HANDLED;
        }
    }

    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleRequestShortAudioDescriptor(HdmiCecMessage message) {
        assertRunOnServiceThread();
        HdmiLogger.debug(TAG + "Stub handleRequestShortAudioDescriptor");
        if (!isSystemAudioControlFeatureEnabled()) {
            return Constants.ABORT_REFUSED;
        }
        if (!isSystemAudioActivated()) {
            return Constants.ABORT_NOT_IN_CORRECT_MODE;
        }

        List<DeviceConfig> config = null;
        File file = new File(SHORT_AUDIO_DESCRIPTOR_CONFIG_PATH);
        if (file.exists()) {
            try {
                InputStream in = new FileInputStream(file);
                config = HdmiUtils.ShortAudioDescriptorXmlParser.parse(in);
                in.close();
            } catch (IOException e) {
                Slog.e(TAG, "Error reading file: " + file, e);
            } catch (XmlPullParserException e) {
                Slog.e(TAG, "Unable to parse file: " + file, e);
            }
        }

        @AudioCodec int[] audioFormatCodes = parseAudioFormatCodes(message.getParams());
        byte[] sadBytes;
        if (config != null && config.size() > 0) {
            sadBytes = getSupportedShortAudioDescriptorsFromConfig(config, audioFormatCodes);
        } else {
            AudioDeviceInfo deviceInfo = getSystemAudioDeviceInfo();
            if (deviceInfo == null) {
                return Constants.ABORT_UNABLE_TO_DETERMINE;
            }

            sadBytes = getSupportedShortAudioDescriptors(deviceInfo, audioFormatCodes);
        }

        if (sadBytes.length == 0) {
            return Constants.ABORT_INVALID_OPERAND;
        } else {
            mService.sendCecCommand(
                    HdmiCecMessageBuilder.buildReportShortAudioDescriptor(
                            getDeviceInfo().getLogicalAddress(), message.getSource(), sadBytes));
            return Constants.HANDLED;
        }
    }

    private byte[] getSupportedShortAudioDescriptors(
            AudioDeviceInfo deviceInfo, @AudioCodec int[] audioFormatCodes) {
        ArrayList<byte[]> sads = new ArrayList<>(audioFormatCodes.length);
        for (@AudioCodec int audioFormatCode : audioFormatCodes) {
            byte[] sad = getSupportedShortAudioDescriptor(deviceInfo, audioFormatCode);
            if (sad != null) {
                if (sad.length == 3) {

                    sads.add(sad);
                } else {
                    HdmiLogger.warning(
                            "Dropping Short Audio Descriptor with length %d for requested codec %x",
                            sad.length, audioFormatCode);
                }
            }
        }
        return getShortAudioDescriptorBytes(sads);
    }

    private byte[] getSupportedShortAudioDescriptorsFromConfig(
            List<DeviceConfig> deviceConfig, @AudioCodec int[] audioFormatCodes) {
        DeviceConfig deviceConfigToUse = null;
        String audioDeviceName = SystemProperties.get(
                Constants.PROPERTY_SYSTEM_AUDIO_MODE_AUDIO_PORT,
                "VX_AUDIO_DEVICE_IN_HDMI_ARC");
        for (DeviceConfig device : deviceConfig) {
            if (device.name.equals(audioDeviceName)) {
                deviceConfigToUse = device;
                break;
            }
        }
        if (deviceConfigToUse == null) {
            Slog.w(TAG, "sadConfig.xml does not have required device info for " + audioDeviceName);
            return new byte[0];
        }
        HashMap<Integer, byte[]> map = new HashMap<>();
        ArrayList<byte[]> sads = new ArrayList<>(audioFormatCodes.length);
        for (CodecSad codecSad : deviceConfigToUse.supportedCodecs) {
            map.put(codecSad.audioCodec, codecSad.sad);
        }
        for (int i = 0; i < audioFormatCodes.length; i++) {
            if (map.containsKey(audioFormatCodes[i])) {
                byte[] sad = map.get(audioFormatCodes[i]);
                if (sad != null && sad.length == 3) {
                    sads.add(sad);
                }
            }
        }
        return getShortAudioDescriptorBytes(sads);
    }

    private byte[] getShortAudioDescriptorBytes(ArrayList<byte[]> sads) {
        // Short Audio Descriptors are always 3 bytes long.
        byte[] bytes = new byte[sads.size() * 3];
        int index = 0;
        for (byte[] sad : sads) {
            System.arraycopy(sad, 0, bytes, index, 3);
            index += 3;
        }
        return bytes;
    }

    /**
     * Returns a 3 byte short audio descriptor as described in CEC 1.4 table 29 or null if the
     * audioFormatCode is not supported.
     */
    @Nullable
    private byte[] getSupportedShortAudioDescriptor(
            AudioDeviceInfo deviceInfo, @AudioCodec int audioFormatCode) {
        switch (audioFormatCode) {
            case Constants.AUDIO_CODEC_NONE: {
                return null;
            }
            case Constants.AUDIO_CODEC_LPCM: {
                return getLpcmShortAudioDescriptor(deviceInfo);
            }
            // TODO(b/80297701): implement the rest of the codecs
            case Constants.AUDIO_CODEC_DD:
            case Constants.AUDIO_CODEC_MPEG1:
            case Constants.AUDIO_CODEC_MP3:
            case Constants.AUDIO_CODEC_MPEG2:
            case Constants.AUDIO_CODEC_AAC:
            case Constants.AUDIO_CODEC_DTS:
            case Constants.AUDIO_CODEC_ATRAC:
            case Constants.AUDIO_CODEC_ONEBITAUDIO:
            case Constants.AUDIO_CODEC_DDP:
            case Constants.AUDIO_CODEC_DTSHD:
            case Constants.AUDIO_CODEC_TRUEHD:
            case Constants.AUDIO_CODEC_DST:
            case Constants.AUDIO_CODEC_WMAPRO:
            default: {
                return null;
            }
        }
    }

    @Nullable
    private byte[] getLpcmShortAudioDescriptor(AudioDeviceInfo deviceInfo) {
        // TODO(b/80297701): implement
        return null;
    }

    @Nullable
    private AudioDeviceInfo getSystemAudioDeviceInfo() {
        AudioManager audioManager = mService.getContext().getSystemService(AudioManager.class);
        if (audioManager == null) {
            HdmiLogger.error(
                    "Error getting system audio device because AudioManager not available.");
            return null;
        }
        AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        HdmiLogger.debug("Found %d audio input devices", devices.length);
        for (AudioDeviceInfo device : devices) {
            HdmiLogger.debug("%s at port %s", device.getProductName(), device.getPort());
            HdmiLogger.debug("Supported encodings are %s",
                    Arrays.stream(device.getEncodings()).mapToObj(
                            AudioFormat::toLogFriendlyEncoding
                    ).collect(Collectors.joining(", ")));
            if (device.getType() == AudioDeviceInfo.TYPE_HDMI_ARC) {
                return device;
            }
        }
        return null;
    }

    @AudioCodec
    private int[] parseAudioFormatCodes(byte[] params) {
        @AudioCodec int[] audioFormatCodes = new int[params.length];
        for (int i = 0; i < params.length; i++) {
            byte val = params[i];
            audioFormatCodes[i] =
                val >= 1 && val <= Constants.AUDIO_CODEC_MAX ? val : Constants.AUDIO_CODEC_NONE;
        }
        return audioFormatCodes;
    }

    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleSystemAudioModeRequest(HdmiCecMessage message) {
        assertRunOnServiceThread();
        boolean systemAudioStatusOn = message.getParams().length != 0;
        // Check if the request comes from a non-TV device.
        // Need to check if TV supports System Audio Control
        // if non-TV device tries to turn on the feature
        if (message.getSource() != Constants.ADDR_TV) {
            if (systemAudioStatusOn) {
                return handleSystemAudioModeOnFromNonTvDevice(message);
            }
        } else {
            // If TV request the feature on
            // cache TV supporting System Audio Control
            // until Audio System loses its physical address.
            setTvSystemAudioModeSupport(true);
        }
        // If TV or Audio System does not support the feature,
        // will send abort command.
        if (!checkSupportAndSetSystemAudioMode(systemAudioStatusOn)) {
            return Constants.ABORT_REFUSED;
        }

        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildSetSystemAudioMode(
                        getDeviceInfo().getLogicalAddress(),
                        Constants.ADDR_BROADCAST,
                        systemAudioStatusOn));

        if (systemAudioStatusOn) {
            // If TV sends out SAM Request with a path of a non-CEC device, which should not show
            // up in the CEC device list and not under the current AVR device, the AVR would switch
            // to ARC.
            int sourcePhysicalAddress = HdmiUtils.twoBytesToInt(message.getParams());
            if (HdmiUtils.getLocalPortFromPhysicalAddress(
                    sourcePhysicalAddress, getDeviceInfo().getPhysicalAddress())
                            != HdmiUtils.TARGET_NOT_UNDER_LOCAL_DEVICE) {
                return Constants.HANDLED;
            }
            HdmiDeviceInfo safeDeviceInfoByPath =
                    mService.getHdmiCecNetwork().getSafeDeviceInfoByPath(sourcePhysicalAddress);
            if (safeDeviceInfoByPath == null) {
                switchInputOnReceivingNewActivePath(sourcePhysicalAddress);
            }
        }
        return Constants.HANDLED;
    }

    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleSetSystemAudioMode(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (!checkSupportAndSetSystemAudioMode(
                HdmiUtils.parseCommandParamSystemAudioStatus(message))) {
            return Constants.ABORT_REFUSED;
        }
        return Constants.HANDLED;
    }

    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleSystemAudioModeStatus(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (!checkSupportAndSetSystemAudioMode(
                HdmiUtils.parseCommandParamSystemAudioStatus(message))) {
            return Constants.ABORT_REFUSED;
        }
        return Constants.HANDLED;
    }

    @ServiceThreadOnly
    void setArcStatus(boolean enabled) {
        assertRunOnServiceThread();

        HdmiLogger.debug("Set Arc Status[old:%b new:%b]", mArcEstablished, enabled);
        // 1. Enable/disable ARC circuit.
        enableAudioReturnChannel(enabled);
        // 2. Notify arc status to audio service.
        notifyArcStatusToAudioService(enabled);
        // 3. Update arc status;
        mArcEstablished = enabled;
    }

    void processArcTermination() {
        setArcStatus(false);
        // Switch away from ARC input when ARC is terminated.
        if (getLocalActivePort() == Constants.CEC_SWITCH_ARC) {
            routeToInputFromPortId(getRoutingPort());
        }
    }

    /** Switch hardware ARC circuit in the system. */
    @ServiceThreadOnly
    private void enableAudioReturnChannel(boolean enabled) {
        assertRunOnServiceThread();
        mService.enableAudioReturnChannel(
                Integer.parseInt(HdmiProperties.arc_port().orElse("0")),
                enabled);
    }

    private void notifyArcStatusToAudioService(boolean enabled) {
        // Note that we don't set any name to ARC.
        mService.getAudioManager()
            .setWiredDeviceConnectionState(AudioSystem.DEVICE_IN_HDMI, enabled ? 1 : 0, "", "");
    }

    void reportAudioStatus(int source) {
        assertRunOnServiceThread();
        if (mService.getHdmiCecVolumeControl()
                == HdmiControlManager.VOLUME_CONTROL_DISABLED) {
            return;
        }

        int volume = mService.getAudioManager().getStreamVolume(AudioManager.STREAM_MUSIC);
        boolean mute = mService.getAudioManager().isStreamMute(AudioManager.STREAM_MUSIC);
        int maxVolume = mService.getAudioManager().getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int minVolume = mService.getAudioManager().getStreamMinVolume(AudioManager.STREAM_MUSIC);
        int scaledVolume = VolumeControlAction.scaleToCecVolume(volume, maxVolume);
        HdmiLogger.debug("Reporting volume %d (%d-%d) as CEC volume %d", volume,
                minVolume, maxVolume, scaledVolume);

        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildReportAudioStatus(
                        getDeviceInfo().getLogicalAddress(), source, scaledVolume, mute));
    }

    /**
     * Method to check if device support System Audio Control. If so, wake up device if necessary.
     *
     * <p> then call {@link #setSystemAudioMode(boolean)} to turn on or off System Audio Mode
     * @param newSystemAudioMode turning feature on or off. True is on. False is off.
     * @return true or false.
     *
     * <p>False when device does not support the feature. Otherwise returns true.
     */
    protected boolean checkSupportAndSetSystemAudioMode(boolean newSystemAudioMode) {
        if (!isSystemAudioControlFeatureEnabled()) {
            HdmiLogger.debug(
                    "Cannot turn "
                            + (newSystemAudioMode ? "on" : "off")
                            + "system audio mode "
                            + "because the System Audio Control feature is disabled.");
            return false;
        }
        HdmiLogger.debug(
                "System Audio Mode change[old:%b new:%b]",
                isSystemAudioActivated(), newSystemAudioMode);
        // Wake up device if System Audio Control is turned on
        if (newSystemAudioMode) {
            mService.wakeUp();
        }
        setSystemAudioMode(newSystemAudioMode);
        return true;
    }

    /**
     * Real work to turn on or off System Audio Mode.
     *
     * Use {@link #checkSupportAndSetSystemAudioMode(boolean)}
     * if trying to turn on or off the feature.
     */
    private void setSystemAudioMode(boolean newSystemAudioMode) {
        int targetPhysicalAddress = getActiveSource().physicalAddress;
        int port = mService.pathToPortId(targetPhysicalAddress);
        if (newSystemAudioMode && port >= 0) {
            switchToAudioInput();
        }
        // Mute device when feature is turned off and unmute device when feature is turned on.
        // CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING is false when device never needs to be muted.
        boolean systemAudioModeMutingEnabled = mService.getHdmiCecConfig().getIntValue(
                    HdmiControlManager.CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING)
                        == HdmiControlManager.SYSTEM_AUDIO_MODE_MUTING_ENABLED;
        boolean currentMuteStatus =
                mService.getAudioManager().isStreamMute(AudioManager.STREAM_MUSIC);
        if (currentMuteStatus == newSystemAudioMode) {
            if (systemAudioModeMutingEnabled || newSystemAudioMode) {
                mService.getAudioManager()
                        .adjustStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                newSystemAudioMode
                                        ? AudioManager.ADJUST_UNMUTE
                                        : AudioManager.ADJUST_MUTE,
                                0);
            }
        }
        updateAudioManagerForSystemAudio(newSystemAudioMode);
        synchronized (mLock) {
            if (isSystemAudioActivated() != newSystemAudioMode) {
                mService.setSystemAudioActivated(newSystemAudioMode);
                mService.announceSystemAudioModeChange(newSystemAudioMode);
            }
        }
        // Since ARC is independent from System Audio Mode control, when the TV requests
        // System Audio Mode off, it does not need to terminate ARC at the same time.
        // When the current audio device is using ARC as a TV input and disables muting,
        // it needs to automatically switch to the previous active input source when System
        // Audio Mode is off even without terminating the ARC. This can stop the current
        // audio device from playing audio when system audio mode is off.
        if (mArcIntentUsed
                && !systemAudioModeMutingEnabled
                && !newSystemAudioMode
                && getLocalActivePort() == Constants.CEC_SWITCH_ARC) {
            routeToInputFromPortId(getRoutingPort());
        }
        // Init arc whenever System Audio Mode is on
        // Since some TVs don't request ARC on with System Audio Mode on request
        if (SystemProperties.getBoolean(Constants.PROPERTY_ARC_SUPPORT, true)
                && isDirectConnectToTv() && mService.isSystemAudioActivated()) {
            if (!hasAction(ArcInitiationActionFromAvr.class)) {
                addAndStartAction(new ArcInitiationActionFromAvr(this));
            }
        }
    }

    protected void switchToAudioInput() {
    }

    protected boolean isDirectConnectToTv() {
        int myPhysicalAddress = mService.getPhysicalAddress();
        return (myPhysicalAddress & Constants.ROUTING_PATH_TOP_MASK) == myPhysicalAddress;
    }

    private void updateAudioManagerForSystemAudio(boolean on) {
        int device = mService.getAudioManager().setHdmiSystemAudioSupported(on);
        HdmiLogger.debug("[A]UpdateSystemAudio mode[on=%b] output=[%X]", on, device);
    }

    void onSystemAudioControlFeatureSupportChanged(boolean enabled) {
        setSystemAudioControlFeatureEnabled(enabled);
        if (enabled) {
            addAndStartAction(new SystemAudioInitiationActionFromAvr(this));
        }
    }

    @ServiceThreadOnly
    void setSystemAudioControlFeatureEnabled(boolean enabled) {
        assertRunOnServiceThread();
        synchronized (mLock) {
            mSystemAudioControlFeatureEnabled = enabled;
        }
    }

    @ServiceThreadOnly
    void setRoutingControlFeatureEnabled(boolean enabled) {
        assertRunOnServiceThread();
        synchronized (mLock) {
            mRoutingControlFeatureEnabled = enabled;
        }
    }

    @ServiceThreadOnly
    void doManualPortSwitching(int portId, IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        if (!mService.isValidPortId(portId)) {
            invokeCallback(callback, HdmiControlManager.RESULT_TARGET_NOT_AVAILABLE);
            return;
        }
        if (portId == getLocalActivePort()) {
            invokeCallback(callback, HdmiControlManager.RESULT_SUCCESS);
            return;
        }
        if (!mService.isControlEnabled()) {
            setRoutingPort(portId);
            setLocalActivePort(portId);
            invokeCallback(callback, HdmiControlManager.RESULT_INCORRECT_MODE);
            return;
        }
        int oldPath = getRoutingPort() != Constants.CEC_SWITCH_HOME
                ? mService.portIdToPath(getRoutingPort())
                : getDeviceInfo().getPhysicalAddress();
        int newPath = mService.portIdToPath(portId);
        if (oldPath == newPath) {
            return;
        }
        setRoutingPort(portId);
        setLocalActivePort(portId);
        HdmiCecMessage routingChange =
                HdmiCecMessageBuilder.buildRoutingChange(
                        getDeviceInfo().getLogicalAddress(), oldPath, newPath);
        mService.sendCecCommand(routingChange);
    }

    boolean isSystemAudioControlFeatureEnabled() {
        synchronized (mLock) {
            return mSystemAudioControlFeatureEnabled;
        }
    }

    protected boolean isSystemAudioActivated() {
        return mService.isSystemAudioActivated();
    }

    protected void terminateSystemAudioMode() {
        // remove pending initiation actions
        removeAction(SystemAudioInitiationActionFromAvr.class);
        if (!isSystemAudioActivated()) {
            return;
        }

        if (checkSupportAndSetSystemAudioMode(false)) {
            // send <Set System Audio Mode> [“Off”]
            mService.sendCecCommand(
                    HdmiCecMessageBuilder.buildSetSystemAudioMode(
                            getDeviceInfo().getLogicalAddress(), Constants.ADDR_BROADCAST, false));
        }
    }

    /** Reports if System Audio Mode is supported by the connected TV */
    interface TvSystemAudioModeSupportedCallback {

        /** {@code supported} is true if the TV is connected and supports System Audio Mode. */
        void onResult(boolean supported);
    }

    /**
     * Queries the connected TV to detect if System Audio Mode is supported by the TV.
     *
     * <p>This query may take up to 2 seconds to complete.
     *
     * <p>The result of the query may be cached until Audio device type is put in standby or loses
     * its physical address.
     */
    void queryTvSystemAudioModeSupport(TvSystemAudioModeSupportedCallback callback) {
        if (mTvSystemAudioModeSupport == null) {
            addAndStartAction(new DetectTvSystemAudioModeSupportAction(this, callback));
        } else {
            callback.onResult(mTvSystemAudioModeSupport);
        }
    }

    /**
     * Handler of System Audio Mode Request on from non TV device
     */
    @Constants.HandleMessageResult
    int handleSystemAudioModeOnFromNonTvDevice(HdmiCecMessage message) {
        if (!isSystemAudioControlFeatureEnabled()) {
            HdmiLogger.debug(
                    "Cannot turn on" + "system audio mode "
                            + "because the System Audio Control feature is disabled.");
            return Constants.ABORT_REFUSED;
        }
        // Wake up device
        mService.wakeUp();
        // If Audio device is the active source or is on the active path,
        // enable system audio mode without querying TV's support on sam.
        // This is per HDMI spec 1.4b CEC 13.15.4.2.
        if (mService.pathToPortId(getActiveSource().physicalAddress)
                != Constants.INVALID_PORT_ID) {
            setSystemAudioMode(true);
            mService.sendCecCommand(
                HdmiCecMessageBuilder.buildSetSystemAudioMode(
                    getDeviceInfo().getLogicalAddress(), Constants.ADDR_BROADCAST, true));
            return Constants.HANDLED;
        }
        // Check if TV supports System Audio Control.
        // Handle broadcasting setSystemAudioMode on or aborting message on callback.
        queryTvSystemAudioModeSupport(new TvSystemAudioModeSupportedCallback() {
            public void onResult(boolean supported) {
                if (supported) {
                    setSystemAudioMode(true);
                    mService.sendCecCommand(
                            HdmiCecMessageBuilder.buildSetSystemAudioMode(
                                    getDeviceInfo().getLogicalAddress(),
                                    Constants.ADDR_BROADCAST,
                                    true));
                } else {
                    mService.maySendFeatureAbortCommand(message, Constants.ABORT_REFUSED);
                }
            }
        });
        return Constants.HANDLED;
    }

    void setTvSystemAudioModeSupport(boolean supported) {
        mTvSystemAudioModeSupport = supported;
    }

    @VisibleForTesting
    protected boolean isArcEnabled() {
        synchronized (mLock) {
            return mArcEstablished;
        }
    }

    private void initArcOnFromAvr() {
        removeAction(ArcTerminationActionFromAvr.class);
        if (SystemProperties.getBoolean(Constants.PROPERTY_ARC_SUPPORT, true)
                && isDirectConnectToTv() && !isArcEnabled()) {
            removeAction(ArcInitiationActionFromAvr.class);
            addAndStartAction(new ArcInitiationActionFromAvr(this));
        }
    }

    @Override
    protected void switchInputOnReceivingNewActivePath(int physicalAddress) {
        int port = mService.pathToPortId(physicalAddress);
        if (isSystemAudioActivated() && port < 0) {
            // If system audio mode is on and the new active source is not under the current device,
            // Will switch to ARC input.
            routeToInputFromPortId(Constants.CEC_SWITCH_ARC);
        } else if (mIsSwitchDevice && port >= 0) {
            // If current device is a switch and the new active source is under it,
            // will switch to the corresponding active path.
            routeToInputFromPortId(port);
        }
    }

    protected void routeToInputFromPortId(int portId) {
        if (!isRoutingControlFeatureEnabled()) {
            HdmiLogger.debug("Routing Control Feature is not enabled.");
            return;
        }
        if (mArcIntentUsed) {
            routeToTvInputFromPortId(portId);
        } else {
            // TODO(): implement input switching for devices not using TvInput.
        }
    }

    protected void routeToTvInputFromPortId(int portId) {
        if (portId < 0 || portId >= Constants.CEC_SWITCH_PORT_MAX) {
            HdmiLogger.debug("Invalid port number for Tv Input switching.");
            return;
        }
        // Wake up if the current device if ready to route.
        mService.wakeUp();
        if ((getLocalActivePort() == portId) && (portId != Constants.CEC_SWITCH_ARC)) {
            HdmiLogger.debug("Not switching to the same port " + portId + " except for arc");
            return;
        }
        // Switch to HOME if the current active port is not HOME yet
        if (portId == Constants.CEC_SWITCH_HOME && mService.isPlaybackDevice()) {
            switchToHomeTvInput();
        } else if (portId == Constants.CEC_SWITCH_ARC) {
            switchToTvInput(HdmiProperties.arc_port().orElse("0"));
            setLocalActivePort(portId);
            return;
        } else {
            String uri = mPortIdToTvInputs.get(portId);
            if (uri != null) {
                switchToTvInput(uri);
            } else {
                HdmiLogger.debug("Port number does not match any Tv Input.");
                return;
            }
        }

        setLocalActivePort(portId);
        setRoutingPort(portId);
    }

    // For device to switch to specific TvInput with corresponding URI.
    private void switchToTvInput(String uri) {
        try {
            mService.getContext().startActivity(new Intent(Intent.ACTION_VIEW,
                    TvContract.buildChannelUriForPassthroughInput(uri))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Slog.e(TAG, "Can't find activity to switch to " + uri, e);
        }
    }

    // For device using TvInput to switch to Home.
    private void switchToHomeTvInput() {
        try {
            Intent activityIntent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            mService.getContext().startActivity(activityIntent);
        } catch (ActivityNotFoundException e) {
            Slog.e(TAG, "Can't find activity to switch to HOME", e);
        }
    }

    @Override
    protected void handleRoutingChangeAndInformation(int physicalAddress, HdmiCecMessage message) {
        int port = mService.pathToPortId(physicalAddress);
        // Routing change or information sent from switches under the current device can be ignored.
        if (port > 0) {
            return;
        }
        // When other switches route to some other devices not under the current device,
        // check system audio mode status and do ARC switch if needed.
        if (port < 0 && isSystemAudioActivated()) {
            handleRoutingChangeAndInformationForSystemAudio();
            return;
        }
        // When other switches route to the current device
        // and the current device is also a switch.
        if (port == 0) {
            handleRoutingChangeAndInformationForSwitch(message);
        }
    }

    // Handle the system audio(ARC) part of the logic on receiving routing change or information.
    private void handleRoutingChangeAndInformationForSystemAudio() {
        routeToInputFromPortId(Constants.CEC_SWITCH_ARC);
    }

    // Handle the routing control part of the logic on receiving routing change or information.
    private void handleRoutingChangeAndInformationForSwitch(HdmiCecMessage message) {
        if (getRoutingPort() == Constants.CEC_SWITCH_HOME && mService.isPlaybackDevice()) {
            routeToInputFromPortId(Constants.CEC_SWITCH_HOME);
            mService.setAndBroadcastActiveSourceFromOneDeviceType(
                    message.getSource(), mService.getPhysicalAddress(),
                    "HdmiCecLocalDeviceAudioSystem#handleRoutingChangeAndInformationForSwitch()");
            return;
        }

        int routingInformationPath = mService.portIdToPath(getRoutingPort());
        // If current device is already the leaf of the whole HDMI system, will do nothing.
        if (routingInformationPath == mService.getPhysicalAddress()) {
            HdmiLogger.debug("Current device can't assign valid physical address"
                    + "to devices under it any more. "
                    + "It's physical address is "
                    + routingInformationPath);
            return;
        }
        // Otherwise will switch to the current active port and broadcast routing information.
        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildRoutingInformation(
                        getDeviceInfo().getLogicalAddress(), routingInformationPath));
        routeToInputFromPortId(getRoutingPort());
    }

    @ServiceThreadOnly
    private void launchDeviceDiscovery() {
        assertRunOnServiceThread();
        if (hasAction(DeviceDiscoveryAction.class)) {
            Slog.i(TAG, "Device Discovery Action is in progress. Restarting.");
            removeAction(DeviceDiscoveryAction.class);
        }
        DeviceDiscoveryAction action = new DeviceDiscoveryAction(this,
                new DeviceDiscoveryCallback() {
                    @Override
                    public void onDeviceDiscoveryDone(List<HdmiDeviceInfo> deviceInfos) {
                        for (HdmiDeviceInfo info : deviceInfos) {
                            mService.getHdmiCecNetwork().addCecDevice(info);
                        }
                    }
                });
        addAndStartAction(action);
    }

    @Override
    protected void dump(IndentingPrintWriter pw) {
        pw.println("HdmiCecLocalDeviceAudioSystem:");
        pw.increaseIndent();
        pw.println("isRoutingFeatureEnabled " + isRoutingControlFeatureEnabled());
        pw.println("mSystemAudioControlFeatureEnabled: " + mSystemAudioControlFeatureEnabled);
        pw.println("mTvSystemAudioModeSupport: " + mTvSystemAudioModeSupport);
        pw.println("mArcEstablished: " + mArcEstablished);
        pw.println("mArcIntentUsed: " + mArcIntentUsed);
        pw.println("mRoutingPort: " + getRoutingPort());
        pw.println("mLocalActivePort: " + getLocalActivePort());
        HdmiUtils.dumpMap(pw, "mPortIdToTvInputs:", mPortIdToTvInputs);
        HdmiUtils.dumpMap(pw, "mTvInputsToDeviceInfo:", mTvInputsToDeviceInfo);
        pw.decreaseIndent();
        super.dump(pw);
    }
}
