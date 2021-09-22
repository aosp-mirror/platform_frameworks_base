/*
 * Copyright 2020 The Android Open Source Project
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

package android.hardware.hdmi;

import android.annotation.BinderThread;
import android.annotation.NonNull;
import android.annotation.TestApi;

import java.util.ArrayList;
import java.util.List;

/**
 * A wrapper of the Binder interface that clients running in the application process
 * will use to perform HDMI-CEC features by communicating with other devices
 * on the bus.
 *
 * @hide
 */
@TestApi
public final class HdmiControlServiceWrapper {

    /** Pure CEC switch device type. */
    public static final int DEVICE_PURE_CEC_SWITCH = HdmiDeviceInfo.DEVICE_PURE_CEC_SWITCH;

    private List<HdmiPortInfo> mInfoList = null;
    private int[] mTypes = null;

    /**
     * Create a new HdmiControlManager with the current HdmiControlService wrapper
     *
     * @return the created HdmiControlManager
     */
    @NonNull
    public HdmiControlManager createHdmiControlManager() {
        return new HdmiControlManager(mInterface);
    }

    private final IHdmiControlService mInterface = new IHdmiControlService.Stub() {

        @Override
        public int[] getSupportedTypes() {
            return HdmiControlServiceWrapper.this.getSupportedTypes();
        }

        @Override
        public HdmiDeviceInfo getActiveSource() {
            return HdmiControlServiceWrapper.this.getActiveSource();
        }

        @Override
        public void oneTouchPlay(IHdmiControlCallback callback) {
            HdmiControlServiceWrapper.this.oneTouchPlay(callback);
        }

        @Override
        public void toggleAndFollowTvPower() {
            HdmiControlServiceWrapper.this.toggleAndFollowTvPower();
        }

        @Override
        public boolean shouldHandleTvPowerKey() {
            return HdmiControlServiceWrapper.this.shouldHandleTvPowerKey();
        }

        @Override
        public void queryDisplayStatus(IHdmiControlCallback callback) {
            HdmiControlServiceWrapper.this.queryDisplayStatus(callback);
        }

        @Override
        public void addHdmiControlStatusChangeListener(IHdmiControlStatusChangeListener listener) {
            HdmiControlServiceWrapper.this.addHdmiControlStatusChangeListener(listener);
        }

        @Override
        public void removeHdmiControlStatusChangeListener(
                IHdmiControlStatusChangeListener listener) {
            HdmiControlServiceWrapper.this.removeHdmiControlStatusChangeListener(listener);
        }

        @Override
        public void addHotplugEventListener(IHdmiHotplugEventListener listener) {
            HdmiControlServiceWrapper.this.addHotplugEventListener(listener);
        }

        @Override
        public void removeHotplugEventListener(IHdmiHotplugEventListener listener) {
            HdmiControlServiceWrapper.this.removeHotplugEventListener(listener);
        }

        @Override
        public void addDeviceEventListener(IHdmiDeviceEventListener listener) {
            HdmiControlServiceWrapper.this.addDeviceEventListener(listener);
        }

        @Override
        public void deviceSelect(int deviceId, IHdmiControlCallback callback) {
            HdmiControlServiceWrapper.this.deviceSelect(deviceId, callback);
        }

        @Override
        public void portSelect(int portId, IHdmiControlCallback callback) {
            HdmiControlServiceWrapper.this.portSelect(portId, callback);
        }

        @Override
        public void sendKeyEvent(int deviceType, int keyCode, boolean isPressed) {
            HdmiControlServiceWrapper.this.sendKeyEvent(deviceType, keyCode, isPressed);
        }

        @Override
        public void sendVolumeKeyEvent(int deviceType, int keyCode, boolean isPressed) {
            HdmiControlServiceWrapper.this.sendVolumeKeyEvent(deviceType, keyCode, isPressed);
        }

        @Override
        public List<HdmiPortInfo> getPortInfo() {
            return HdmiControlServiceWrapper.this.getPortInfo();
        }

        @Override
        public boolean canChangeSystemAudioMode() {
            return HdmiControlServiceWrapper.this.canChangeSystemAudioMode();
        }

        @Override
        public boolean getSystemAudioMode() {
            return HdmiControlServiceWrapper.this.getSystemAudioMode();
        }

        @Override
        public int getPhysicalAddress() {
            return HdmiControlServiceWrapper.this.getPhysicalAddress();
        }

        @Override
        public void setSystemAudioMode(boolean enabled, IHdmiControlCallback callback) {
            HdmiControlServiceWrapper.this.setSystemAudioMode(enabled, callback);
        }

        @Override
        public void addSystemAudioModeChangeListener(IHdmiSystemAudioModeChangeListener listener) {
            HdmiControlServiceWrapper.this.addSystemAudioModeChangeListener(listener);
        }

        @Override
        public void removeSystemAudioModeChangeListener(
                IHdmiSystemAudioModeChangeListener listener) {
            HdmiControlServiceWrapper.this.removeSystemAudioModeChangeListener(listener);
        }

        @Override
        public void setArcMode(boolean enabled) {
            HdmiControlServiceWrapper.this.setArcMode(enabled);
        }

        @Override
        public void setProhibitMode(boolean enabled) {
            HdmiControlServiceWrapper.this.setProhibitMode(enabled);
        }

        @Override
        public void setSystemAudioVolume(int oldIndex, int newIndex, int maxIndex) {
            HdmiControlServiceWrapper.this.setSystemAudioVolume(oldIndex, newIndex, maxIndex);
        }

        @Override
        public void setSystemAudioMute(boolean mute) {
            HdmiControlServiceWrapper.this.setSystemAudioMute(mute);
        }

        @Override
        public void setInputChangeListener(IHdmiInputChangeListener listener) {
            HdmiControlServiceWrapper.this.setInputChangeListener(listener);
        }

        @Override
        public List<HdmiDeviceInfo> getInputDevices() {
            return HdmiControlServiceWrapper.this.getInputDevices();
        }

        @Override
        public List<HdmiDeviceInfo> getDeviceList() {
            return HdmiControlServiceWrapper.this.getDeviceList();
        }

        @Override
        public void powerOffRemoteDevice(int logicalAddress, int powerStatus) {
            HdmiControlServiceWrapper.this.powerOffRemoteDevice(logicalAddress, powerStatus);
        }

        @Override
        public void powerOnRemoteDevice(int logicalAddress, int powerStatus) {
            HdmiControlServiceWrapper.this.powerOnRemoteDevice(logicalAddress, powerStatus);
        }

        @Override
        public void askRemoteDeviceToBecomeActiveSource(int physicalAddress) {
            HdmiControlServiceWrapper.this.askRemoteDeviceToBecomeActiveSource(physicalAddress);
        }

        @Override
        public void sendVendorCommand(int deviceType, int targetAddress, byte[] params,
                boolean hasVendorId) {
            HdmiControlServiceWrapper.this.sendVendorCommand(
                    deviceType, targetAddress, params, hasVendorId);
        }

        @Override
        public void addVendorCommandListener(IHdmiVendorCommandListener listener, int deviceType) {
            HdmiControlServiceWrapper.this.addVendorCommandListener(listener, deviceType);
        }

        @Override
        public void sendStandby(int deviceType, int deviceId) {
            HdmiControlServiceWrapper.this.sendStandby(deviceType, deviceId);
        }

        @Override
        public void setHdmiRecordListener(IHdmiRecordListener callback) {
            HdmiControlServiceWrapper.this.setHdmiRecordListener(callback);
        }

        @Override
        public void startOneTouchRecord(int recorderAddress, byte[] recordSource) {
            HdmiControlServiceWrapper.this.startOneTouchRecord(recorderAddress, recordSource);
        }

        @Override
        public void stopOneTouchRecord(int recorderAddress) {
            HdmiControlServiceWrapper.this.stopOneTouchRecord(recorderAddress);
        }

        @Override
        public void startTimerRecording(int recorderAddress, int sourceType, byte[] recordSource) {
            HdmiControlServiceWrapper.this.startTimerRecording(
                    recorderAddress, sourceType, recordSource);
        }

        @Override
        public void clearTimerRecording(int recorderAddress, int sourceType, byte[] recordSource) {
            HdmiControlServiceWrapper.this.clearTimerRecording(
                    recorderAddress, sourceType, recordSource);
        }

        @Override
        public void sendMhlVendorCommand(int portId, int offset, int length, byte[] data) {
            HdmiControlServiceWrapper.this.sendMhlVendorCommand(portId, offset, length, data);
        }

        @Override
        public void addHdmiMhlVendorCommandListener(IHdmiMhlVendorCommandListener listener) {
            HdmiControlServiceWrapper.this.addHdmiMhlVendorCommandListener(listener);
        }

        @Override
        public void setStandbyMode(boolean isStandbyModeOn) {
            HdmiControlServiceWrapper.this.setStandbyMode(isStandbyModeOn);
        }

        @Override
        public void reportAudioStatus(int deviceType, int volume, int maxVolume, boolean isMute) {
            HdmiControlServiceWrapper.this.reportAudioStatus(deviceType, volume, maxVolume, isMute);
        }

        @Override
        public void setSystemAudioModeOnForAudioOnlySource() {
            HdmiControlServiceWrapper.this.setSystemAudioModeOnForAudioOnlySource();
        }

        @Override
        public void addHdmiCecVolumeControlFeatureListener(
                IHdmiCecVolumeControlFeatureListener listener) {
            HdmiControlServiceWrapper.this.addHdmiCecVolumeControlFeatureListener(listener);
        }

        @Override
        public void removeHdmiCecVolumeControlFeatureListener(
                IHdmiCecVolumeControlFeatureListener listener) {
            HdmiControlServiceWrapper.this.removeHdmiCecVolumeControlFeatureListener(listener);
        }

        @Override
        public int getMessageHistorySize() {
            return HdmiControlServiceWrapper.this.getMessageHistorySize();
        }

        @Override
        public boolean setMessageHistorySize(int newSize) {
            return HdmiControlServiceWrapper.this.setMessageHistorySize(newSize);
        }

        @Override
        public void addCecSettingChangeListener(String name,
                IHdmiCecSettingChangeListener listener) {
            HdmiControlServiceWrapper.this.addCecSettingChangeListener(name, listener);
        }

        @Override
        public void removeCecSettingChangeListener(String name,
                IHdmiCecSettingChangeListener listener) {
            HdmiControlServiceWrapper.this.removeCecSettingChangeListener(name, listener);
        }

        @Override
        public List<String> getUserCecSettings() {
            return HdmiControlServiceWrapper.this.getUserCecSettings();
        }

        @Override
        public List<String> getAllowedCecSettingStringValues(String name) {
            return HdmiControlServiceWrapper.this.getAllowedCecSettingStringValues(name);
        }

        @Override
        public int[] getAllowedCecSettingIntValues(String name) {
            return HdmiControlServiceWrapper.this.getAllowedCecSettingIntValues(name);
        }

        @Override
        public String getCecSettingStringValue(String name) {
            return HdmiControlServiceWrapper.this.getCecSettingStringValue(name);
        }

        @Override
        public void setCecSettingStringValue(String name, String value) {
            HdmiControlServiceWrapper.this.setCecSettingStringValue(name, value);
        }

        @Override
        public int getCecSettingIntValue(String name) {
            return HdmiControlServiceWrapper.this.getCecSettingIntValue(name);
        }

        @Override
        public void setCecSettingIntValue(String name, int value) {
            HdmiControlServiceWrapper.this.setCecSettingIntValue(name, value);
        }
    };

    @BinderThread
    public void setPortInfo(@NonNull List<HdmiPortInfo> infoList) {
        mInfoList = infoList;
    }

    @BinderThread
    public void setDeviceTypes(@NonNull int[] types) {
        mTypes = types;
    }

    /** @hide */
    public List<HdmiPortInfo> getPortInfo() {
        return mInfoList;
    }

    /** @hide */
    public int[] getSupportedTypes() {
        return mTypes;
    }

    /** @hide */
    public HdmiDeviceInfo getActiveSource() {
        return null;
    }

    /** @hide */
    public void oneTouchPlay(IHdmiControlCallback callback) {}

    /** @hide */
    public void toggleAndFollowTvPower() {}

    /** @hide */
    public boolean shouldHandleTvPowerKey() {
        return true;
    }

    /** @hide */
    public void queryDisplayStatus(IHdmiControlCallback callback) {}

    /** @hide */
    public void addHdmiControlStatusChangeListener(IHdmiControlStatusChangeListener listener) {}

    /** @hide */
    public void removeHdmiControlStatusChangeListener(IHdmiControlStatusChangeListener listener) {}

    /** @hide */
    public void addHotplugEventListener(IHdmiHotplugEventListener listener) {}

    /** @hide */
    public void removeHotplugEventListener(IHdmiHotplugEventListener listener) {}

    /** @hide */
    public void addDeviceEventListener(IHdmiDeviceEventListener listener) {}

    /** @hide */
    public void deviceSelect(int deviceId, IHdmiControlCallback callback) {}

    /** @hide */
    public void portSelect(int portId, IHdmiControlCallback callback) {}

    /** @hide */
    public void sendKeyEvent(int deviceType, int keyCode, boolean isPressed) {}

    /** @hide */
    public void sendVolumeKeyEvent(int deviceType, int keyCode, boolean isPressed) {}

    /** @hide */
    public boolean canChangeSystemAudioMode() {
        return true;
    }

    /** @hide */
    public boolean getSystemAudioMode() {
        return true;
    }

    /** @hide */
    public int getPhysicalAddress() {
        return 0xffff;
    }

    /** @hide */
    public void setSystemAudioMode(boolean enabled, IHdmiControlCallback callback) {}

    /** @hide */
    public void addSystemAudioModeChangeListener(IHdmiSystemAudioModeChangeListener listener) {}

    /** @hide */
    public void removeSystemAudioModeChangeListener(IHdmiSystemAudioModeChangeListener listener) {}

    /** @hide */
    public void setArcMode(boolean enabled) {}

    /** @hide */
    public void setProhibitMode(boolean enabled) {}

    /** @hide */
    public void setSystemAudioVolume(int oldIndex, int newIndex, int maxIndex) {}

    /** @hide */
    public void setSystemAudioMute(boolean mute) {}

    /** @hide */
    public void setInputChangeListener(IHdmiInputChangeListener listener) {}

    /** @hide */
    public List<HdmiDeviceInfo> getInputDevices() {
        return null;
    }

    /** @hide */
    public List<HdmiDeviceInfo> getDeviceList() {
        return null;
    }

    /** @hide */
    public void powerOffRemoteDevice(int logicalAddress, int powerStatus) {}

    /** @hide */
    public void powerOnRemoteDevice(int logicalAddress, int powerStatus) {}

    /** @hide */
    public void askRemoteDeviceToBecomeActiveSource(int physicalAddress) {}

    /** @hide */
    public void sendVendorCommand(int deviceType, int targetAddress, byte[] params,
            boolean hasVendorId) {}

    /** @hide */
    public void addVendorCommandListener(IHdmiVendorCommandListener listener, int deviceType) {}

    /** @hide */
    public void sendStandby(int deviceType, int deviceId) {}

    /** @hide */
    public void setHdmiRecordListener(IHdmiRecordListener callback) {}

    /** @hide */
    public void startOneTouchRecord(int recorderAddress, byte[] recordSource) {}

    /** @hide */
    public void stopOneTouchRecord(int recorderAddress) {}

    /** @hide */
    public void startTimerRecording(int recorderAddress, int sourceType, byte[] recordSource) {}

    /** @hide */
    public void clearTimerRecording(int recorderAddress, int sourceType, byte[] recordSource) {}

    /** @hide */
    public void sendMhlVendorCommand(int portId, int offset, int length, byte[] data) {}

    /** @hide */
    public void addHdmiMhlVendorCommandListener(IHdmiMhlVendorCommandListener listener) {}

    /** @hide */
    public void setStandbyMode(boolean isStandbyModeOn) {}

    /** @hide */
    public void setHdmiCecVolumeControlEnabled(boolean isHdmiCecVolumeControlEnabled) {}

    /** @hide */
    public boolean isHdmiCecVolumeControlEnabled() {
        return true;
    }

    /** @hide */
    public void reportAudioStatus(int deviceType, int volume, int maxVolume, boolean isMute) {}

    /** @hide */
    public void setSystemAudioModeOnForAudioOnlySource() {}

    /** @hide */
    public void addHdmiCecVolumeControlFeatureListener(
            IHdmiCecVolumeControlFeatureListener listener) {}

    /** @hide */
    public void removeHdmiCecVolumeControlFeatureListener(
            IHdmiCecVolumeControlFeatureListener listener) {}

    /** @hide */
    public int getMessageHistorySize() {
        return 0;
    }

    /** @hide */
    public boolean setMessageHistorySize(int newSize) {
        return true;
    }

    /** @hide */
    public void addCecSettingChangeListener(String name,
            IHdmiCecSettingChangeListener listener) {}

    /** @hide */
    public void removeCecSettingChangeListener(String name,
            IHdmiCecSettingChangeListener listener) {}

    /** @hide */
    public List<String> getUserCecSettings() {
        return new ArrayList<>();
    }

    /** @hide */
    public List<String> getAllowedCecSettingStringValues(String name) {
        return new ArrayList<>();
    }

    /** @hide */
    public int[] getAllowedCecSettingIntValues(String name) {
        return new int[0];
    }

    /** @hide */
    public String getCecSettingStringValue(String name) {
        return "";
    }

    /** @hide */
    public void setCecSettingStringValue(String name, String value) {
    }

    /** @hide */
    public int getCecSettingIntValue(String name) {
        return 0;
    }

    /** @hide */
    public void setCecSettingIntValue(String name, int value) {
    }
}
