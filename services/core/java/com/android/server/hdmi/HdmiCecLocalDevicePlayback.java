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

import android.annotation.CallSuper;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.IHdmiControlCallback;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.os.Binder;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.sysprop.HdmiProperties;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.LocalePicker;
import com.android.internal.app.LocalePicker.LocaleInfo;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.hdmi.HdmiAnnotations.ServiceThreadOnly;
import com.android.server.hdmi.HdmiControlService.SendMessageCallback;

import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Locale;

/**
 * Represent a logical device of type Playback residing in Android system.
 */
public class HdmiCecLocalDevicePlayback extends HdmiCecLocalDeviceSource {
    private static final String TAG = "HdmiCecLocalDevicePlayback";

    // How long to wait after hotplug out before possibly going to Standby.
    @VisibleForTesting
    static final long STANDBY_AFTER_HOTPLUG_OUT_DELAY_MS = 30_000;

    // Used to keep the device awake while it is the active source. For devices that
    // cannot wake up via CEC commands, this address the inconvenience of having to
    // turn them on. True by default, and can be disabled (i.e. device can go to sleep
    // in active device status) by explicitly setting the system property
    // persist.sys.hdmi.keep_awake to false.
    // Lazily initialized - should call getWakeLock() to get the instance.
    private ActiveWakeLock mWakeLock;

    // Handler for queueing a delayed Standby runnable after hotplug out.
    private Handler mDelayedStandbyHandler;

    // Determines what action should be taken upon receiving Routing Control messages.
    @VisibleForTesting
    protected HdmiProperties.playback_device_action_on_routing_control_values
            mPlaybackDeviceActionOnRoutingControl = HdmiProperties
                    .playback_device_action_on_routing_control()
                    .orElse(HdmiProperties.playback_device_action_on_routing_control_values.NONE);

    HdmiCecLocalDevicePlayback(HdmiControlService service) {
        super(service, HdmiDeviceInfo.DEVICE_PLAYBACK);

        mDelayedStandbyHandler = new Handler(service.getServiceLooper());
        mStandbyHandler = new HdmiCecStandbyModeHandler(service, this);
    }

    @Override
    @ServiceThreadOnly
    protected void onAddressAllocated(int logicalAddress, int reason) {
        assertRunOnServiceThread();
        if (reason == mService.INITIATED_BY_ENABLE_CEC) {
            mService.setAndBroadcastActiveSource(mService.getPhysicalAddress(),
                    getDeviceInfo().getDeviceType(), Constants.ADDR_BROADCAST,
                    "HdmiCecLocalDevicePlayback#onAddressAllocated()");
        }
        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                        getDeviceInfo().getLogicalAddress(),
                        mService.getPhysicalAddress(),
                        mDeviceType));
        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildDeviceVendorIdCommand(
                        getDeviceInfo().getLogicalAddress(), mService.getVendorId()));
        // Actively send out an OSD name to the TV to update the TV panel in case the TV
        // does not query the OSD name on time. This is not a required behavior by the spec.
        // It is used for some TVs that need the OSD name update but don't query it themselves.
        buildAndSendSetOsdName(Constants.ADDR_TV);
        if (mService.audioSystem() == null) {
            // If current device is not a functional audio system device,
            // send message to potential audio system device in the system to get the system
            // audio mode status. If no response, set to false.
            mService.sendCecCommand(
                    HdmiCecMessageBuilder.buildGiveSystemAudioModeStatus(
                            getDeviceInfo().getLogicalAddress(), Constants.ADDR_AUDIO_SYSTEM),
                    new SendMessageCallback() {
                        @Override
                        public void onSendCompleted(int error) {
                            // In consideration of occasional transmission failures.
                            if (error == SendMessageResult.NACK) {
                                HdmiLogger.debug(
                                        "AVR did not respond to <Give System Audio Mode Status>");
                                mService.setSystemAudioActivated(false);
                            }
                        }
                    });
        }
        launchDeviceDiscovery();
        startQueuedActions();
    }

    @ServiceThreadOnly
    private void launchDeviceDiscovery() {
        assertRunOnServiceThread();
        clearDeviceInfoList();
        if (hasAction(DeviceDiscoveryAction.class)) {
            Slog.i(TAG, "Device Discovery Action is in progress. Restarting.");
            removeAction(DeviceDiscoveryAction.class);
        }
        DeviceDiscoveryAction action = new DeviceDiscoveryAction(this,
                new DeviceDiscoveryAction.DeviceDiscoveryCallback() {
                    @Override
                    public void onDeviceDiscoveryDone(List<HdmiDeviceInfo> deviceInfos) {
                        for (HdmiDeviceInfo info : deviceInfos) {
                            mService.getHdmiCecNetwork().addCecDevice(info);
                        }

                        // Since we removed all devices when it starts and device discovery action
                        // does not poll local devices, we should put device info of local device
                        // manually here.
                        for (HdmiCecLocalDevice device : mService.getAllCecLocalDevices()) {
                            mService.getHdmiCecNetwork().addCecDevice(device.getDeviceInfo());
                        }

                        List<HotplugDetectionAction> hotplugActions =
                                getActions(HotplugDetectionAction.class);
                        if (hotplugActions.isEmpty()) {
                            addAndStartAction(
                                    new HotplugDetectionAction(HdmiCecLocalDevicePlayback.this));
                        }
                    }
                });
        addAndStartAction(action);
    }

    @Override
    @ServiceThreadOnly
    protected int getPreferredAddress() {
        assertRunOnServiceThread();
        return SystemProperties.getInt(Constants.PROPERTY_PREFERRED_ADDRESS_PLAYBACK,
                Constants.ADDR_UNREGISTERED);
    }

    @Override
    @ServiceThreadOnly
    protected void setPreferredAddress(int addr) {
        assertRunOnServiceThread();
        mService.writeStringSystemProperty(Constants.PROPERTY_PREFERRED_ADDRESS_PLAYBACK,
                String.valueOf(addr));
    }

    /**
     * Performs the action 'device select' or 'one touch play' initiated by a Playback device.
     *
     * @param id id of HDMI device to select
     * @param callback callback object to report the result with
     */
    @ServiceThreadOnly
    void deviceSelect(int id, IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        if (id == getDeviceInfo().getId()) {
            mService.oneTouchPlay(callback);
            return;
        }
        HdmiDeviceInfo targetDevice = mService.getHdmiCecNetwork().getDeviceInfo(id);
        if (targetDevice == null) {
            invokeCallback(callback, HdmiControlManager.RESULT_TARGET_NOT_AVAILABLE);
            return;
        }
        int targetAddress = targetDevice.getLogicalAddress();
        if (isAlreadyActiveSource(targetDevice, targetAddress, callback)) {
            return;
        }
        if (!mService.isCecControlEnabled()) {
            setActiveSource(targetDevice, "HdmiCecLocalDevicePlayback#deviceSelect()");
            invokeCallback(callback, HdmiControlManager.RESULT_INCORRECT_MODE);
            return;
        }
        removeAction(DeviceSelectActionFromPlayback.class);
        addAndStartAction(new DeviceSelectActionFromPlayback(this, targetDevice, callback));
    }

    @Override
    @ServiceThreadOnly
    void onHotplug(int portId, boolean connected) {
        assertRunOnServiceThread();
        mCecMessageCache.flushAll();

        if (connected) {
            mDelayedStandbyHandler.removeCallbacksAndMessages(null);
        } else {
            // We'll not invalidate the active source on the hotplug event to pass CETC 11.2.2-2 ~ 3
            getWakeLock().release();
            mService.getHdmiCecNetwork().removeDevicesConnectedToPort(portId);

            mDelayedStandbyHandler.removeCallbacksAndMessages(null);
            mDelayedStandbyHandler.postDelayed(new DelayedStandbyRunnable(),
                    STANDBY_AFTER_HOTPLUG_OUT_DELAY_MS);
        }
    }

    /**
     * Runnable for going to Standby if the device has been inactive for a certain amount of time.
     * Posts a new instance of itself as a delayed message if the device was active.
     */
    private class DelayedStandbyRunnable implements Runnable {
        @Override
        public void run() {
            if (mService.getPowerManagerInternal().wasDeviceIdleFor(
                    STANDBY_AFTER_HOTPLUG_OUT_DELAY_MS)) {
                mService.standby();
            } else {
                mDelayedStandbyHandler.postDelayed(new DelayedStandbyRunnable(),
                        STANDBY_AFTER_HOTPLUG_OUT_DELAY_MS);
            }
        }
    }

    @Override
    @ServiceThreadOnly
    protected void onStandby(boolean initiatedByCec, int standbyAction,
            StandbyCompletedCallback callback) {
        assertRunOnServiceThread();
        if (!mService.isCecControlEnabled()) {
            invokeStandbyCompletedCallback(callback);
            return;
        }
        boolean wasActiveSource = isActiveSource();
        // Invalidate the internal active source record when going to standby
        mService.setActiveSource(Constants.ADDR_INVALID, Constants.INVALID_PHYSICAL_ADDRESS,
                "HdmiCecLocalDevicePlayback#onStandby()");
        if (!wasActiveSource) {
            invokeStandbyCompletedCallback(callback);
            return;
        }
        SendMessageCallback sendMessageCallback = new SendMessageCallback() {
            @Override
            public void onSendCompleted(int error) {
                invokeStandbyCompletedCallback(callback);
            }
        };
        if (initiatedByCec) {
            mService.sendCecCommand(
                    HdmiCecMessageBuilder.buildInactiveSource(
                            getDeviceInfo().getLogicalAddress(), mService.getPhysicalAddress()),
                    sendMessageCallback);
            return;
        }
        switch (standbyAction) {
            case HdmiControlService.STANDBY_SCREEN_OFF:
                // Get latest setting value
                @HdmiControlManager.PowerControlMode
                String powerControlMode = mService.getHdmiCecConfig().getStringValue(
                        HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE);
                switch (powerControlMode) {
                    case HdmiControlManager.POWER_CONTROL_MODE_TV:
                        mService.sendCecCommand(
                                HdmiCecMessageBuilder.buildStandby(
                                        getDeviceInfo().getLogicalAddress(), Constants.ADDR_TV),
                                sendMessageCallback);
                        break;
                    case HdmiControlManager.POWER_CONTROL_MODE_TV_AND_AUDIO_SYSTEM:
                        mService.sendCecCommand(
                                HdmiCecMessageBuilder.buildStandby(
                                        getDeviceInfo().getLogicalAddress(), Constants.ADDR_TV));
                        mService.sendCecCommand(
                                HdmiCecMessageBuilder.buildStandby(
                                        getDeviceInfo().getLogicalAddress(),
                                        Constants.ADDR_AUDIO_SYSTEM), sendMessageCallback);
                        break;
                    case HdmiControlManager.POWER_CONTROL_MODE_BROADCAST:
                        mService.sendCecCommand(
                                HdmiCecMessageBuilder.buildStandby(
                                        getDeviceInfo().getLogicalAddress(),
                                        Constants.ADDR_BROADCAST), sendMessageCallback);
                        break;
                    case HdmiControlManager.POWER_CONTROL_MODE_NONE:
                        mService.sendCecCommand(
                                HdmiCecMessageBuilder.buildInactiveSource(
                                        getDeviceInfo().getLogicalAddress(),
                                        mService.getPhysicalAddress()), sendMessageCallback);
                        break;
                }
                break;
            case HdmiControlService.STANDBY_SHUTDOWN:
                // ACTION_SHUTDOWN is taken as a signal to power off all the devices.
                mService.sendCecCommand(
                        HdmiCecMessageBuilder.buildStandby(
                                getDeviceInfo().getLogicalAddress(), Constants.ADDR_BROADCAST),
                        sendMessageCallback);
                break;
        }
    }

    @Override
    @ServiceThreadOnly
    protected void onInitializeCecComplete(int initiatedBy) {
        if (initiatedBy != HdmiControlService.INITIATED_BY_SCREEN_ON) {
            return;
        }
        @HdmiControlManager.PowerControlMode
        String powerControlMode = mService.getHdmiCecConfig().getStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE);
        if (powerControlMode.equals(HdmiControlManager.POWER_CONTROL_MODE_NONE)) {
            return;
        }
        oneTouchPlay(new IHdmiControlCallback.Stub() {
            @Override
            public void onComplete(int result) {
                if (result != HdmiControlManager.RESULT_SUCCESS) {
                    Slog.w(TAG, "Failed to complete One Touch Play. result=" + result);
                }
            }
        });
    }

    @Override
    @CallSuper
    @ServiceThreadOnly
    @VisibleForTesting
    protected void setActiveSource(int logicalAddress, int physicalAddress, String caller) {
        assertRunOnServiceThread();
        super.setActiveSource(logicalAddress, physicalAddress, caller);
        if (isActiveSource()) {
            getWakeLock().acquire();
        } else {
            getWakeLock().release();
        }
    }

    @ServiceThreadOnly
    private ActiveWakeLock getWakeLock() {
        assertRunOnServiceThread();
        if (mWakeLock == null) {
            if (SystemProperties.getBoolean(Constants.PROPERTY_KEEP_AWAKE, true)) {
                mWakeLock = new SystemWakeLock();
            } else {
                // Create a stub lock object that doesn't do anything about wake lock,
                // hence allows the device to go to sleep even if it's the active source.
                mWakeLock = new ActiveWakeLock() {
                    @Override
                    public void acquire() { }
                    @Override
                    public void release() { }
                    @Override
                    public boolean isHeld() { return false; }
                };
                HdmiLogger.debug("No wakelock is used to keep the display on.");
            }
        }
        return mWakeLock;
    }

    @Override
    protected boolean canGoToStandby() {
        return !getWakeLock().isHeld();
    }

    @Override
    @ServiceThreadOnly
    protected void onActiveSourceLost() {
        assertRunOnServiceThread();
        mService.pauseActiveMediaSessions();
        switch (mService.getHdmiCecConfig().getStringValue(
                    HdmiControlManager.CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST)) {
            case HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW:
                mService.standby();
                return;
            case HdmiControlManager.POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_NONE:
                return;
        }
    }

    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleUserControlPressed(HdmiCecMessage message) {
        assertRunOnServiceThread();
        wakeUpIfActiveSource();
        return super.handleUserControlPressed(message);
    }

    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleSetMenuLanguage(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (mService.getHdmiCecConfig().getIntValue(
                HdmiControlManager.CEC_SETTING_NAME_SET_MENU_LANGUAGE)
                    == HdmiControlManager.SET_MENU_LANGUAGE_DISABLED) {
            return Constants.ABORT_UNRECOGNIZED_OPCODE;
        }

        try {
            String iso3Language = new String(message.getParams(), 0, 3, "US-ASCII");
            Locale currentLocale = mService.getContext().getResources().getConfiguration().locale;
            String curIso3Language = mService.localeToMenuLanguage(currentLocale);
            HdmiLogger.debug("handleSetMenuLanguage " + iso3Language + " cur:" + curIso3Language);
            if (curIso3Language.equals(iso3Language)) {
                // Do not switch language if the new language is the same as the current one.
                // This helps avoid accidental country variant switching from en_US to en_AU
                // due to the limitation of CEC. See the warning below.
                return Constants.HANDLED;
            }

            // Don't use Locale.getAvailableLocales() since it returns a locale
            // which is not available on Settings.
            final List<LocaleInfo> localeInfos = LocalePicker.getAllAssetLocales(
                    mService.getContext(), false);
            for (LocaleInfo localeInfo : localeInfos) {
                if (mService.localeToMenuLanguage(localeInfo.getLocale()).equals(iso3Language)) {
                    // WARNING: CEC adopts ISO/FDIS-2 for language code, while Android requires
                    // additional country variant to pinpoint the locale. This keeps the right
                    // locale from being chosen. 'eng' in the CEC command, for instance,
                    // will always be mapped to en-AU among other variants like en-US, en-GB,
                    // an en-IN, which may not be the expected one.
                    startSetMenuLanguageActivity(localeInfo.getLocale());
                    return Constants.HANDLED;
                }
            }
            Slog.w(TAG, "Can't handle <Set Menu Language> of " + iso3Language);
            return Constants.ABORT_INVALID_OPERAND;
        } catch (UnsupportedEncodingException e) {
            Slog.w(TAG, "Can't handle <Set Menu Language>", e);
            return Constants.ABORT_INVALID_OPERAND;
        }
    }

    private void startSetMenuLanguageActivity(Locale locale) {
        final long identity = Binder.clearCallingIdentity();
        try {
            Context context = mService.getContext();
            Intent intent = new Intent();
            intent.putExtra(HdmiControlManager.EXTRA_LOCALE, locale.toLanguageTag());
            intent.setComponent(
                    ComponentName.unflattenFromString(context.getResources().getString(
                            com.android.internal.R.string.config_hdmiCecSetMenuLanguageActivity)));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivityAsUser(intent, context.getUser());
        } catch (ActivityNotFoundException e) {
            Slog.e(TAG, "unable to start HdmiCecSetMenuLanguageActivity");
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    @Constants.HandleMessageResult
    protected int handleSetSystemAudioMode(HdmiCecMessage message) {
        // System Audio Mode only turns on/off when Audio System broadcasts on/off message.
        // For device with type 4 and 5, it can set system audio mode on/off
        // when there is another audio system device connected into the system first.
        if (message.getDestination() != Constants.ADDR_BROADCAST
                || message.getSource() != Constants.ADDR_AUDIO_SYSTEM
                || mService.audioSystem() != null) {
            return Constants.HANDLED;
        }
        boolean setSystemAudioModeOn = HdmiUtils.parseCommandParamSystemAudioStatus(message);
        if (mService.isSystemAudioActivated() != setSystemAudioModeOn) {
            mService.setSystemAudioActivated(setSystemAudioModeOn);
        }
        return Constants.HANDLED;
    }

    @Override
    @Constants.HandleMessageResult
    protected int handleSystemAudioModeStatus(HdmiCecMessage message) {
        // Only directly addressed System Audio Mode Status message can change internal
        // system audio mode status.
        if (message.getDestination() == getDeviceInfo().getLogicalAddress()
                && message.getSource() == Constants.ADDR_AUDIO_SYSTEM) {
            boolean setSystemAudioModeOn = HdmiUtils.parseCommandParamSystemAudioStatus(message);
            if (mService.isSystemAudioActivated() != setSystemAudioModeOn) {
                mService.setSystemAudioActivated(setSystemAudioModeOn);
            }
        }
        return Constants.HANDLED;
    }

    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleRoutingChange(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int physicalAddress = HdmiUtils.twoBytesToInt(message.getParams(), 2);
        handleRoutingChangeAndInformation(physicalAddress, message);
        return Constants.HANDLED;
    }

    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleRoutingInformation(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int physicalAddress = HdmiUtils.twoBytesToInt(message.getParams());
        HdmiDeviceInfo sourceDevice = mService.getHdmiCecNetwork()
                .getCecDeviceInfo(message.getSource());
        // Ignore <Routing Information> messages pointing to the same physical address as the
        // message sender. In this case, we shouldn't consider the sender to be the active source.
        // See more b/321771821#comment7.
        if (sourceDevice != null
                && sourceDevice.getLogicalAddress() != Constants.ADDR_TV
                && sourceDevice.getPhysicalAddress() == physicalAddress) {
            Slog.d(TAG, "<Routing Information> is ignored, it is pointing to the same physical"
                    + " address as the message sender");
            return Constants.HANDLED;
        }
        handleRoutingChangeAndInformation(physicalAddress, message);
        return Constants.HANDLED;
    }

    @Override
    @ServiceThreadOnly
    protected void handleRoutingChangeAndInformation(int physicalAddress, HdmiCecMessage message) {
        assertRunOnServiceThread();
        // If the device is active source and received a <Routing Change> or <Routing Information>
        // message to a physical address in the same active path do not change the Active Source
        // status.
        // E.g. TV [0.0.0.0] ------ Switch [2.0.0.0] ------ OTT [2.1.0.0] (Active Source)
        // TV sends <Routing Change>[2.0.0.0] -> OTT is still Active Source
        // TV sends <Routing Change>[0.0.0.0] -> OTT is not Active Source anymore.
        // TV sends <Routing Change>[3.0.0.0] -> OTT is not Active Source anymore.
        if (HdmiUtils.isInActiveRoutingPath(mService.getPhysicalAddress(), physicalAddress)
                && physicalAddress != Constants.TV_PHYSICAL_ADDRESS
                && isActiveSource()) {
            return;
        }
        if (physicalAddress != mService.getPhysicalAddress()) {
            setActiveSource(physicalAddress,
                    "HdmiCecLocalDevicePlayback#handleRoutingChangeAndInformation()");
            return;
        }
        if (!isActiveSource()) {
            // If routing is changed to the device while Active Source, don't invalidate the
            // Active Source
            setActiveSource(physicalAddress,
                    "HdmiCecLocalDevicePlayback#handleRoutingChangeAndInformation()");
        }
        switch (mPlaybackDeviceActionOnRoutingControl) {
            case WAKE_UP_AND_SEND_ACTIVE_SOURCE:
                setAndBroadcastActiveSource(message, physicalAddress,
                        "HdmiCecLocalDevicePlayback#handleRoutingChangeAndInformation()");
                break;
            case WAKE_UP_ONLY:
                mService.wakeUp();
                break;
            case NONE:
                break;
        }
    }

    /**
     * Called after logical address allocation is finished, allowing a local device to react to
     * messages in the buffer before they are processed. This method may be used to cancel deferred
     * actions.
     */
    @Override
    protected void preprocessBufferedMessages(List<HdmiCecMessage> bufferedMessages) {
        for (HdmiCecMessage message: bufferedMessages) {
            // Prevent the device from broadcasting <Active Source> message if the active path
            // changed during address allocation.
            if (message.getOpcode() == Constants.MESSAGE_ROUTING_CHANGE
                    || message.getOpcode() == Constants.MESSAGE_SET_STREAM_PATH
                    || message.getOpcode() == Constants.MESSAGE_ACTIVE_SOURCE) {
                removeAction(ActiveSourceAction.class);
                removeAction(OneTouchPlayAction.class);
                return;
            }
        }
    }

    @Override
    protected int findKeyReceiverAddress() {
        return Constants.ADDR_TV;
    }

    @Override
    protected int findAudioReceiverAddress() {
        if (mService.isSystemAudioActivated()) {
            return Constants.ADDR_AUDIO_SYSTEM;
        }
        return Constants.ADDR_TV;
    }

    @Override
    @ServiceThreadOnly
    protected void disableDevice(boolean initiatedByCec, PendingActionClearedCallback callback) {
        assertRunOnServiceThread();
        removeAction(DeviceDiscoveryAction.class);
        removeAction(HotplugDetectionAction.class);
        removeAction(NewDeviceAction.class);
        super.disableDevice(initiatedByCec, callback);
        clearDeviceInfoList();
        checkIfPendingActionsCleared();
    }

    @Override
    protected void dump(final IndentingPrintWriter pw) {
        super.dump(pw);
        pw.println("isActiveSource(): " + isActiveSource());
    }

    // Wrapper interface over PowerManager.WakeLock
    private interface ActiveWakeLock {
        void acquire();
        void release();
        boolean isHeld();
    }

    private class SystemWakeLock implements ActiveWakeLock {
        private final WakeLockWrapper mWakeLock;
        public SystemWakeLock() {
            mWakeLock = mService.getPowerManager().newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
            mWakeLock.setReferenceCounted(false);
        }

        @Override
        public void acquire() {
            mWakeLock.acquire();
            HdmiLogger.debug("active source: %b. Wake lock acquired", isActiveSource());
        }

        @Override
        public void release() {
            mWakeLock.release();
            HdmiLogger.debug("Wake lock released");
        }

        @Override
        public boolean isHeld() {
            return mWakeLock.isHeld();
        }
    }
}
