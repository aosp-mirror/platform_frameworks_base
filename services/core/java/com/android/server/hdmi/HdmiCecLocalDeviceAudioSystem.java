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

import static com.android.server.hdmi.Constants.ALWAYS_SYSTEM_AUDIO_CONTROL_ON_POWER_ON;
import static com.android.server.hdmi.Constants.PROPERTY_SYSTEM_AUDIO_CONTROL_ON_POWER_ON;
import static com.android.server.hdmi.Constants.USE_LAST_STATE_SYSTEM_AUDIO_CONTROL_ON_POWER_ON;

import android.annotation.Nullable;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiPortInfo;
import android.hardware.hdmi.IHdmiControlCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.media.tv.TvContract;
import android.os.SystemProperties;
import android.provider.Settings.Global;
import android.util.Slog;
import android.util.SparseArray;

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
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;


/**
 * Represent a logical device of type {@link HdmiDeviceInfo#DEVICE_AUDIO_SYSTEM} residing in Android
 * system.
 */
public class HdmiCecLocalDeviceAudioSystem extends HdmiCecLocalDeviceSource {

    private static final String TAG = "HdmiCecLocalDeviceAudioSystem";

    // Whether the System Audio Control feature is enabled or not. True by default.
    @GuardedBy("mLock")
    private boolean mSystemAudioControlFeatureEnabled;

    private boolean mTvSystemAudioModeSupport;

    // Whether ARC is available or not. "true" means that ARC is established between TV and
    // AVR as audio receiver.
    @ServiceThreadOnly private boolean mArcEstablished = false;

    // If the current device uses TvInput for ARC. We assume all other inputs also use TvInput
    // when ARC is using TvInput.
    private boolean mArcIntentUsed = SystemProperties
            .get(Constants.PROPERTY_SYSTEM_AUDIO_DEVICE_ARC_PORT, "0").contains("tvinput");

    // Keeps the mapping (HDMI port ID to TV input URI) to keep track of the TV inputs ready to
    // accept input switching request from HDMI devices. Requests for which the corresponding
    // input ID is not yet registered by TV input framework need to be buffered for delayed
    // processing.
    private final HashMap<Integer, String> mTvInputs = new HashMap<>();

    // Copy of mDeviceInfos to guarantee thread-safety.
    @GuardedBy("mLock")
    private List<HdmiDeviceInfo> mSafeAllDeviceInfos = Collections.emptyList();

    // Map-like container of all cec devices.
    // device id is used as key of container.
    private final SparseArray<HdmiDeviceInfo> mDeviceInfos = new SparseArray<>();

    protected HdmiCecLocalDeviceAudioSystem(HdmiControlService service) {
        super(service, HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);
        mRoutingControlFeatureEnabled =
            mService.readBooleanSetting(Global.HDMI_CEC_SWITCH_ENABLED, false);
        mSystemAudioControlFeatureEnabled =
            mService.readBooleanSetting(Global.HDMI_SYSTEM_AUDIO_CONTROL_ENABLED, true);
        // TODO(amyjojo): Maintain a portId to TvinputId map.
        mTvInputs.put(2, "com.droidlogic.tvinput/.services.Hdmi1InputService/HW5");
        mTvInputs.put(4, "com.droidlogic.tvinput/.services.Hdmi2InputService/HW6");
        mTvInputs.put(1, "com.droidlogic.tvinput/.services.Hdmi3InputService/HW7");
    }

    private static final String SHORT_AUDIO_DESCRIPTOR_CONFIG_PATH = "/vendor/etc/sadConfig.xml";

    /**
     * Called when a device is newly added or a new device is detected or
     * an existing device is updated.
     *
     * @param info device info of a new device.
     */
    @ServiceThreadOnly
    final void addCecDevice(HdmiDeviceInfo info) {
        assertRunOnServiceThread();
        HdmiDeviceInfo old = addDeviceInfo(info);
        if (info.getPhysicalAddress() == mService.getPhysicalAddress()) {
            // The addition of the device itself should not be notified.
            // Note that different logical address could still be the same local device.
            return;
        }
        if (old == null) {
            invokeDeviceEventListener(info, HdmiControlManager.DEVICE_EVENT_ADD_DEVICE);
        } else if (!old.equals(info)) {
            invokeDeviceEventListener(old, HdmiControlManager.DEVICE_EVENT_REMOVE_DEVICE);
            invokeDeviceEventListener(info, HdmiControlManager.DEVICE_EVENT_ADD_DEVICE);
        }
    }

    /**
     * Called when a device is removed or removal of device is detected.
     *
     * @param address a logical address of a device to be removed
     */
    @ServiceThreadOnly
    final void removeCecDevice(int address) {
        assertRunOnServiceThread();
        HdmiDeviceInfo info = removeDeviceInfo(HdmiDeviceInfo.idForCecDevice(address));

        mCecMessageCache.flushMessagesFrom(address);
        invokeDeviceEventListener(info, HdmiControlManager.DEVICE_EVENT_REMOVE_DEVICE);
    }

    /**
     * Called when a device is updated.
     *
     * @param info device info of the updating device.
     */
    @ServiceThreadOnly
    final void updateCecDevice(HdmiDeviceInfo info) {
        assertRunOnServiceThread();
        HdmiDeviceInfo old = addDeviceInfo(info);

        if (old == null) {
            invokeDeviceEventListener(info, HdmiControlManager.DEVICE_EVENT_ADD_DEVICE);
        } else if (!old.equals(info)) {
            invokeDeviceEventListener(info, HdmiControlManager.DEVICE_EVENT_UPDATE_DEVICE);
        }
    }

    /**
    * Add a new {@link HdmiDeviceInfo}. It returns old device info which has the same
     * logical address as new device info's.
     *
     * @param deviceInfo a new {@link HdmiDeviceInfo} to be added.
     * @return {@code null} if it is new device. Otherwise, returns old {@HdmiDeviceInfo}
     *         that has the same logical address as new one has.
     */
    @ServiceThreadOnly
    @VisibleForTesting
    protected HdmiDeviceInfo addDeviceInfo(HdmiDeviceInfo deviceInfo) {
        assertRunOnServiceThread();
        HdmiDeviceInfo oldDeviceInfo = getCecDeviceInfo(deviceInfo.getLogicalAddress());
        if (oldDeviceInfo != null) {
            removeDeviceInfo(deviceInfo.getId());
        }
        mDeviceInfos.append(deviceInfo.getId(), deviceInfo);
        updateSafeDeviceInfoList();
        return oldDeviceInfo;
    }

    /**
     * Remove a device info corresponding to the given {@code logicalAddress}.
     * It returns removed {@link HdmiDeviceInfo} if exists.
     *
     * @param id id of device to be removed
     * @return removed {@link HdmiDeviceInfo} it exists. Otherwise, returns {@code null}
     */
    @ServiceThreadOnly
    private HdmiDeviceInfo removeDeviceInfo(int id) {
        assertRunOnServiceThread();
        HdmiDeviceInfo deviceInfo = mDeviceInfos.get(id);
        if (deviceInfo != null) {
            mDeviceInfos.remove(id);
        }
        updateSafeDeviceInfoList();
        return deviceInfo;
    }

    /**
     * Return a {@link HdmiDeviceInfo} corresponding to the given {@code logicalAddress}.
     *
     * @param logicalAddress logical address of the device to be retrieved
     * @return {@link HdmiDeviceInfo} matched with the given {@code logicalAddress}.
     *         Returns null if no logical address matched
     */
    @ServiceThreadOnly
    HdmiDeviceInfo getCecDeviceInfo(int logicalAddress) {
        assertRunOnServiceThread();
        return mDeviceInfos.get(HdmiDeviceInfo.idForCecDevice(logicalAddress));
    }

    @ServiceThreadOnly
    private void updateSafeDeviceInfoList() {
        assertRunOnServiceThread();
        List<HdmiDeviceInfo> copiedDevices = HdmiUtils.sparseArrayToList(mDeviceInfos);
        synchronized (mLock) {
            mSafeAllDeviceInfos = copiedDevices;
        }
    }

    @GuardedBy("mLock")
    List<HdmiDeviceInfo> getSafeCecDevicesLocked() {
        ArrayList<HdmiDeviceInfo> infoList = new ArrayList<>();
        for (HdmiDeviceInfo info : mSafeAllDeviceInfos) {
            infoList.add(info);
        }
        return infoList;
    }

    private void invokeDeviceEventListener(HdmiDeviceInfo info, int status) {
        mService.invokeDeviceEventListeners(info, status);
    }

    @Override
    @ServiceThreadOnly
    void onHotplug(int portId, boolean connected) {
        assertRunOnServiceThread();
        if (connected) {
            mService.wakeUp();
        }
        if (mService.getPortInfo(portId).getType() == HdmiPortInfo.PORT_OUTPUT) {
            mCecMessageCache.flushAll();
        } else {
            if (connected) {
                launchDeviceDiscovery();
            } else {
                // TODO(amyjojo): remove device from mDeviceInfo
            }
        }
    }

    @Override
    @ServiceThreadOnly
    protected void onStandby(boolean initiatedByCec, int standbyAction) {
        assertRunOnServiceThread();
        mTvSystemAudioModeSupport = false;
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
                    getDeviceInfo().getDeviceType(), Constants.ADDR_BROADCAST);
        }
        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                        mAddress, mService.getPhysicalAddress(), mDeviceType));
        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildDeviceVendorIdCommand(mAddress, mService.getVendorId()));
        int systemAudioControlOnPowerOnProp =
                SystemProperties.getInt(
                        PROPERTY_SYSTEM_AUDIO_CONTROL_ON_POWER_ON,
                        ALWAYS_SYSTEM_AUDIO_CONTROL_ON_POWER_ON);
        boolean lastSystemAudioControlStatus =
                SystemProperties.getBoolean(Constants.PROPERTY_LAST_SYSTEM_AUDIO_CONTROL, true);
        systemAudioControlOnPowerOn(systemAudioControlOnPowerOnProp, lastSystemAudioControlStatus);
        clearDeviceInfoList();
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

    @Override
    @ServiceThreadOnly
    protected boolean handleReportPhysicalAddress(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int path = HdmiUtils.twoBytesToInt(message.getParams());
        int address = message.getSource();
        int type = message.getParams()[2];

        // Ignore if [Device Discovery Action] is going on.
        if (hasAction(DeviceDiscoveryAction.class)) {
            Slog.i(TAG, "Ignored while Device Discovery Action is in progress: " + message);
            return true;
        }

        // Update the device info with TIF, note that the same device info could have added in
        // device discovery and we do not want to override it with default OSD name. Therefore we
        // need the following check to skip redundant device info updating.
        HdmiDeviceInfo oldDevice = getCecDeviceInfo(address);
        if (oldDevice == null || oldDevice.getPhysicalAddress() != path) {
            addCecDevice(new HdmiDeviceInfo(
                    address, path, mService.pathToPortId(path), type,
                    Constants.UNKNOWN_VENDOR_ID, HdmiUtils.getDefaultDeviceName(address)));
            // if we are adding a new device info, send out a give osd name command
            // to update the name of the device in TIF
            mService.sendCecCommand(
                    HdmiCecMessageBuilder.buildGiveOsdNameCommand(mAddress, address));
            return true;
        }

        Slog.w(TAG, "Device info exists. Not updating on Physical Address.");
        return true;
    }

    @Override
    protected boolean handleReportPowerStatus(HdmiCecMessage command) {
        int newStatus = command.getParams()[0] & 0xFF;
        updateDevicePowerStatus(command.getSource(), newStatus);
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleSetOsdName(HdmiCecMessage message) {
        int source = message.getSource();
        String osdName;
        HdmiDeviceInfo deviceInfo = getCecDeviceInfo(source);
        // If the device is not in device list, ignore it.
        if (deviceInfo == null) {
            Slog.i(TAG, "No source device info for <Set Osd Name>." + message);
            return true;
        }
        try {
            osdName = new String(message.getParams(), "US-ASCII");
        } catch (UnsupportedEncodingException e) {
            Slog.e(TAG, "Invalid <Set Osd Name> request:" + message, e);
            return true;
        }

        if (deviceInfo.getDisplayName().equals(osdName)) {
            Slog.d(TAG, "Ignore incoming <Set Osd Name> having same osd name:" + message);
            return true;
        }

        Slog.d(TAG, "Updating device OSD name from "
                + deviceInfo.getDisplayName()
                + " to " + osdName);
        updateCecDevice(new HdmiDeviceInfo(deviceInfo.getLogicalAddress(),
                deviceInfo.getPhysicalAddress(), deviceInfo.getPortId(),
                deviceInfo.getDeviceType(), deviceInfo.getVendorId(), osdName));
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleReportAudioStatus(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // TODO(amyjojo): implement report audio status handler
        HdmiLogger.debug(TAG + "Stub handleReportAudioStatus");
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleInitiateArc(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // TODO(amyjojo): implement initiate arc handler
        HdmiLogger.debug(TAG + "Stub handleInitiateArc");
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleReportArcInitiate(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // TODO(amyjojo): implement report arc initiate handler
        HdmiLogger.debug(TAG + "Stub handleReportArcInitiate");
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleReportArcTermination(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // TODO(amyjojo): implement report arc terminate handler
        HdmiLogger.debug(TAG + "Stub handleReportArcTermination");
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleGiveAudioStatus(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (isSystemAudioControlFeatureEnabled()) {
            reportAudioStatus(message.getSource());
        } else {
            mService.maySendFeatureAbortCommand(message, Constants.ABORT_REFUSED);
        }
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleGiveSystemAudioModeStatus(HdmiCecMessage message) {
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
                        mAddress, message.getSource(), isSystemAudioModeOnOrTurningOn));
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleRequestArcInitiate(HdmiCecMessage message) {
        assertRunOnServiceThread();
        removeAction(ArcInitiationActionFromAvr.class);
        if (!mService.readBooleanSystemProperty(Constants.PROPERTY_ARC_SUPPORT, true)) {
            mService.maySendFeatureAbortCommand(message, Constants.ABORT_UNRECOGNIZED_OPCODE);
        } else if (!isDirectConnectToTv()) {
            HdmiLogger.debug("AVR device is not directly connected with TV");
            mService.maySendFeatureAbortCommand(message, Constants.ABORT_NOT_IN_CORRECT_MODE);
        } else {
            addAndStartAction(new ArcInitiationActionFromAvr(this));
        }
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleRequestArcTermination(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (!SystemProperties.getBoolean(Constants.PROPERTY_ARC_SUPPORT, true)) {
            mService.maySendFeatureAbortCommand(message, Constants.ABORT_UNRECOGNIZED_OPCODE);
        } else if (!isArcEnabled()) {
            HdmiLogger.debug("ARC is not established between TV and AVR device");
            mService.maySendFeatureAbortCommand(message, Constants.ABORT_NOT_IN_CORRECT_MODE);
        } else {
            removeAction(ArcTerminationActionFromAvr.class);
            addAndStartAction(new ArcTerminationActionFromAvr(this));
        }
        return true;
    }

    @ServiceThreadOnly
    protected boolean handleRequestShortAudioDescriptor(HdmiCecMessage message) {
        assertRunOnServiceThread();
        HdmiLogger.debug(TAG + "Stub handleRequestShortAudioDescriptor");
        if (!isSystemAudioControlFeatureEnabled()) {
            mService.maySendFeatureAbortCommand(message, Constants.ABORT_REFUSED);
            return true;
        }
        if (!isSystemAudioActivated()) {
            mService.maySendFeatureAbortCommand(message, Constants.ABORT_NOT_IN_CORRECT_MODE);
            return true;
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
                mService.maySendFeatureAbortCommand(message, Constants.ABORT_UNABLE_TO_DETERMINE);
                return true;
            }

            sadBytes = getSupportedShortAudioDescriptors(deviceInfo, audioFormatCodes);
        }

        if (sadBytes.length == 0) {
            mService.maySendFeatureAbortCommand(message, Constants.ABORT_INVALID_OPERAND);
        } else {
            mService.sendCecCommand(
                    HdmiCecMessageBuilder.buildReportShortAudioDescriptor(
                            mAddress, message.getSource(), sadBytes));
        }
        return true;
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
        for (DeviceConfig device : deviceConfig) {
            // TODO(amyjojo) use PROPERTY_SYSTEM_AUDIO_MODE_AUDIO_PORT to get the audio device name
            if (device.name.equals("VX_AUDIO_DEVICE_IN_HDMI_ARC")) {
                deviceConfigToUse = device;
                break;
            }
        }
        if (deviceConfigToUse == null) {
            // TODO(amyjojo) use PROPERTY_SYSTEM_AUDIO_MODE_AUDIO_PORT to get the audio device name
            Slog.w(TAG, "sadConfig.xml does not have required device info for "
                        + "VX_AUDIO_DEVICE_IN_HDMI_ARC");
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
            // TODO(b/80297701) use the actual device type that system audio mode is connected to.
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
    protected boolean handleSystemAudioModeRequest(HdmiCecMessage message) {
        assertRunOnServiceThread();
        boolean systemAudioStatusOn = message.getParams().length != 0;
        // Check if the request comes from a non-TV device.
        // Need to check if TV supports System Audio Control
        // if non-TV device tries to turn on the feature
        if (message.getSource() != Constants.ADDR_TV) {
            if (systemAudioStatusOn) {
                handleSystemAudioModeOnFromNonTvDevice(message);
                return true;
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
            mService.maySendFeatureAbortCommand(message, Constants.ABORT_REFUSED);
            return true;
        }

        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildSetSystemAudioMode(
                        mAddress, Constants.ADDR_BROADCAST, systemAudioStatusOn));
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleSetSystemAudioMode(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (!checkSupportAndSetSystemAudioMode(
                HdmiUtils.parseCommandParamSystemAudioStatus(message))) {
            mService.maySendFeatureAbortCommand(message, Constants.ABORT_REFUSED);
        }
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleSystemAudioModeStatus(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (!checkSupportAndSetSystemAudioMode(
                HdmiUtils.parseCommandParamSystemAudioStatus(message))) {
            mService.maySendFeatureAbortCommand(message, Constants.ABORT_REFUSED);
        }
        return true;
    }

    @ServiceThreadOnly
    void setArcStatus(boolean enabled) {
        // TODO(shubang): add tests
        assertRunOnServiceThread();

        HdmiLogger.debug("Set Arc Status[old:%b new:%b]", mArcEstablished, enabled);
        // 1. Enable/disable ARC circuit.
        enableAudioReturnChannel(enabled);
        // 2. Notify arc status to audio service.
        notifyArcStatusToAudioService(enabled);
        // 3. Update arc status;
        mArcEstablished = enabled;
    }

    /** Switch hardware ARC circuit in the system. */
    @ServiceThreadOnly
    private void enableAudioReturnChannel(boolean enabled) {
        assertRunOnServiceThread();
        mService.enableAudioReturnChannel(
                SystemProperties.getInt(Constants.PROPERTY_SYSTEM_AUDIO_DEVICE_ARC_PORT, 0),
                enabled);
    }

    private void notifyArcStatusToAudioService(boolean enabled) {
        // Note that we don't set any name to ARC.
        mService.getAudioManager()
            .setWiredDeviceConnectionState(AudioSystem.DEVICE_IN_HDMI, enabled ? 1 : 0, "", "");
    }

    void reportAudioStatus(int source) {
        assertRunOnServiceThread();

        int volume = mService.getAudioManager().getStreamVolume(AudioManager.STREAM_MUSIC);
        boolean mute = mService.getAudioManager().isStreamMute(AudioManager.STREAM_MUSIC);
        int maxVolume = mService.getAudioManager().getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int minVolume = mService.getAudioManager().getStreamMinVolume(AudioManager.STREAM_MUSIC);
        int scaledVolume = VolumeControlAction.scaleToCecVolume(volume, maxVolume);
        HdmiLogger.debug("Reporting volume %d (%d-%d) as CEC volume %d", volume,
                minVolume, maxVolume, scaledVolume);

        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildReportAudioStatus(
                        mAddress, source, scaledVolume, mute));
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
        // PROPERTY_SYSTEM_AUDIO_MODE_MUTING_ENABLE is false when device never needs to be muted.
        boolean currentMuteStatus =
                mService.getAudioManager().isStreamMute(AudioManager.STREAM_MUSIC);
        if (currentMuteStatus == newSystemAudioMode) {
            if (mService.readBooleanSystemProperty(
                    Constants.PROPERTY_SYSTEM_AUDIO_MODE_MUTING_ENABLE, true)
                            || newSystemAudioMode) {
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
        // Init arc whenever System Audio Mode is on
        // Terminate arc when System Audio Mode is off
        // Since some TVs don't request ARC on with System Audio Mode on request
        if (SystemProperties.getBoolean(Constants.PROPERTY_ARC_SUPPORT, true)
                && isDirectConnectToTv()) {
            if (newSystemAudioMode && !isArcEnabled()) {
                removeAction(ArcInitiationActionFromAvr.class);
                addAndStartAction(new ArcInitiationActionFromAvr(this));
            } else if (!newSystemAudioMode && isArcEnabled()) {
                removeAction(ArcTerminationActionFromAvr.class);
                addAndStartAction(new ArcTerminationActionFromAvr(this));
            }
        }
    }

    protected void switchToAudioInput() {
        // TODO(b/111396634): switch input according to PROPERTY_SYSTEM_AUDIO_MODE_AUDIO_PORT
    }

    protected boolean isDirectConnectToTv() {
        int myPhysicalAddress = mService.getPhysicalAddress();
        return (myPhysicalAddress & Constants.ROUTING_PATH_TOP_MASK) == myPhysicalAddress;
    }

    private void updateAudioManagerForSystemAudio(boolean on) {
        int device = mService.getAudioManager().setHdmiSystemAudioSupported(on);
        HdmiLogger.debug("[A]UpdateSystemAudio mode[on=%b] output=[%X]", on, device);
    }

    void onSystemAduioControlFeatureSupportChanged(boolean enabled) {
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
    void setRoutingControlFeatureEnables(boolean enabled) {
        assertRunOnServiceThread();
        synchronized (mLock) {
            mRoutingControlFeatureEnabled = enabled;
        }
    }

    @ServiceThreadOnly
    void doManualPortSwitching(int portId, IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        // TODO: validate port ID
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
                HdmiCecMessageBuilder.buildRoutingChange(mAddress, oldPath, newPath);
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
                            mAddress, Constants.ADDR_BROADCAST, false));
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
    // TODO(amyjojo): making mTvSystemAudioModeSupport null originally and fix the logic.
    void queryTvSystemAudioModeSupport(TvSystemAudioModeSupportedCallback callback) {
        if (!mTvSystemAudioModeSupport) {
            addAndStartAction(new DetectTvSystemAudioModeSupportAction(this, callback));
        } else {
            callback.onResult(true);
        }
    }

    /**
     * Handler of System Audio Mode Request on from non TV device
     */
    void handleSystemAudioModeOnFromNonTvDevice(HdmiCecMessage message) {
        if (!isSystemAudioControlFeatureEnabled()) {
            HdmiLogger.debug(
                    "Cannot turn on" + "system audio mode "
                            + "because the System Audio Control feature is disabled.");
            mService.maySendFeatureAbortCommand(message, Constants.ABORT_REFUSED);
            return;
        }
        // Wake up device
        mService.wakeUp();
        // Check if TV supports System Audio Control.
        // Handle broadcasting setSystemAudioMode on or aborting message on callback.
        queryTvSystemAudioModeSupport(new TvSystemAudioModeSupportedCallback() {
            public void onResult(boolean supported) {
                if (supported) {
                    setSystemAudioMode(true);
                    mService.sendCecCommand(
                            HdmiCecMessageBuilder.buildSetSystemAudioMode(
                                    mAddress, Constants.ADDR_BROADCAST, true));
                } else {
                    mService.maySendFeatureAbortCommand(message, Constants.ABORT_REFUSED);
                }
            }
        });
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

    @Override
    protected void switchInputOnReceivingNewActivePath(int physicalAddress) {
        int port = mService.pathToPortId(physicalAddress);
        if (isSystemAudioActivated() && port < 0) {
            // If system audio mode is on and the new active source is not under the current device,
            // Will switch to ARC input.
            // TODO(b/115637145): handle system aduio without ARC
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
        if (portId == Constants.CEC_SWITCH_HOME && mService.isPlaybackDevice()) {
            switchToHomeTvInput();
        } else if (portId == Constants.CEC_SWITCH_ARC) {
            switchToTvInput(SystemProperties.get(Constants.PROPERTY_SYSTEM_AUDIO_DEVICE_ARC_PORT));
            setLocalActivePort(portId);
            return;
        } else {
            String uri = mTvInputs.get(portId);
            if (uri != null) {
                switchToTvInput(mTvInputs.get(portId));
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
        // TODO(b/115637145): handle system aduio without ARC
        routeToInputFromPortId(Constants.CEC_SWITCH_ARC);
    }

    // Handle the routing control part of the logic on receiving routing change or information.
    private void handleRoutingChangeAndInformationForSwitch(HdmiCecMessage message) {
        if (getRoutingPort() == Constants.CEC_SWITCH_HOME && mService.isPlaybackDevice()) {
            routeToInputFromPortId(Constants.CEC_SWITCH_HOME);
            mService.setAndBroadcastActiveSourceFromOneDeviceType(
                    message.getSource(), mService.getPhysicalAddress());
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
        mService.sendCecCommand(HdmiCecMessageBuilder.buildRoutingInformation(
                mAddress, routingInformationPath));
        routeToInputFromPortId(getRoutingPort());
    }

    protected void updateDevicePowerStatus(int logicalAddress, int newPowerStatus) {
        HdmiDeviceInfo info = getCecDeviceInfo(logicalAddress);
        if (info == null) {
            Slog.w(TAG, "Can not update power status of non-existing device:" + logicalAddress);
            return;
        }

        if (info.getDevicePowerStatus() == newPowerStatus) {
            return;
        }

        HdmiDeviceInfo newInfo = HdmiUtils.cloneHdmiDeviceInfo(info, newPowerStatus);
        // addDeviceInfo replaces old device info with new one if exists.
        addDeviceInfo(newInfo);

        invokeDeviceEventListener(newInfo, HdmiControlManager.DEVICE_EVENT_UPDATE_DEVICE);
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
                            addCecDevice(info);
                        }
                    }
                });
        addAndStartAction(action);
    }

    // Clear all device info.
    @ServiceThreadOnly
    private void clearDeviceInfoList() {
        assertRunOnServiceThread();
        for (HdmiDeviceInfo info : HdmiUtils.sparseArrayToList(mDeviceInfos)) {
            if (info.getPhysicalAddress() == mService.getPhysicalAddress()) {
                continue;
            }
            invokeDeviceEventListener(info, HdmiControlManager.DEVICE_EVENT_REMOVE_DEVICE);
        }
        mDeviceInfos.clear();
        updateSafeDeviceInfoList();
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
        HdmiUtils.dumpMap(pw, "mTvInputs:", mTvInputs);
        HdmiUtils.dumpSparseArray(pw, "mDeviceInfos:", mDeviceInfos);
        pw.decreaseIndent();
        super.dump(pw);
    }
}
