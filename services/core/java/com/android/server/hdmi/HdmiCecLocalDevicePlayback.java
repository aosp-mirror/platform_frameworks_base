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

import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.IHdmiControlCallback;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.provider.Settings.Global;
import android.util.Slog;

import com.android.internal.app.LocalePicker;
import com.android.internal.app.LocalePicker.LocaleInfo;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.hdmi.HdmiAnnotations.ServiceThreadOnly;

import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Locale;

import java.util.List;

/**
 * Represent a logical device of type Playback residing in Android system.
 */
final class HdmiCecLocalDevicePlayback extends HdmiCecLocalDevice {
    private static final String TAG = "HdmiCecLocalDevicePlayback";

    private static final boolean WAKE_ON_HOTPLUG =
            SystemProperties.getBoolean(Constants.PROPERTY_WAKE_ON_HOTPLUG, true);

    private static final boolean SET_MENU_LANGUAGE =
            SystemProperties.getBoolean(Constants.PROPERTY_SET_MENU_LANGUAGE, false);

    private boolean mIsActiveSource = false;

    // Used to keep the device awake while it is the active source. For devices that
    // cannot wake up via CEC commands, this address the inconvenience of having to
    // turn them on. True by default, and can be disabled (i.e. device can go to sleep
    // in active device status) by explicitly setting the system property
    // persist.sys.hdmi.keep_awake to false.
    // Lazily initialized - should call getWakeLock() to get the instance.
    private ActiveWakeLock mWakeLock;

    // If true, turn off TV upon standby. False by default.
    private boolean mAutoTvOff;

    HdmiCecLocalDevicePlayback(HdmiControlService service) {
        super(service, HdmiDeviceInfo.DEVICE_PLAYBACK);

        mAutoTvOff = mService.readBooleanSetting(Global.HDMI_CONTROL_AUTO_DEVICE_OFF_ENABLED, false);

        // The option is false by default. Update settings db as well to have the right
        // initial setting on UI.
        mService.writeBooleanSetting(Global.HDMI_CONTROL_AUTO_DEVICE_OFF_ENABLED, mAutoTvOff);
    }

    @Override
    @ServiceThreadOnly
    protected void onAddressAllocated(int logicalAddress, int reason) {
        assertRunOnServiceThread();
        mService.sendCecCommand(HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                mAddress, mService.getPhysicalAddress(), mDeviceType));
        mService.sendCecCommand(HdmiCecMessageBuilder.buildDeviceVendorIdCommand(
                mAddress, mService.getVendorId()));
        startQueuedActions();
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
        SystemProperties.set(Constants.PROPERTY_PREFERRED_ADDRESS_PLAYBACK,
                String.valueOf(addr));
    }

    @ServiceThreadOnly
    void oneTouchPlay(IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        List<OneTouchPlayAction> actions = getActions(OneTouchPlayAction.class);
        if (!actions.isEmpty()) {
            Slog.i(TAG, "oneTouchPlay already in progress");
            actions.get(0).addCallback(callback);
            return;
        }
        OneTouchPlayAction action = OneTouchPlayAction.create(this, Constants.ADDR_TV,
                callback);
        if (action == null) {
            Slog.w(TAG, "Cannot initiate oneTouchPlay");
            invokeCallback(callback, HdmiControlManager.RESULT_EXCEPTION);
            return;
        }
        addAndStartAction(action);
    }

    @ServiceThreadOnly
    void queryDisplayStatus(IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        List<DevicePowerStatusAction> actions = getActions(DevicePowerStatusAction.class);
        if (!actions.isEmpty()) {
            Slog.i(TAG, "queryDisplayStatus already in progress");
            actions.get(0).addCallback(callback);
            return;
        }
        DevicePowerStatusAction action = DevicePowerStatusAction.create(this, Constants.ADDR_TV,
                callback);
        if (action == null) {
            Slog.w(TAG, "Cannot initiate queryDisplayStatus");
            invokeCallback(callback, HdmiControlManager.RESULT_EXCEPTION);
            return;
        }
        addAndStartAction(action);
    }

    @ServiceThreadOnly
    private void invokeCallback(IHdmiControlCallback callback, int result) {
        assertRunOnServiceThread();
        try {
            callback.onComplete(result);
        } catch (RemoteException e) {
            Slog.e(TAG, "Invoking callback failed:" + e);
        }
    }

    @Override
    @ServiceThreadOnly
    void onHotplug(int portId, boolean connected) {
        assertRunOnServiceThread();
        mCecMessageCache.flushAll();
        // We'll not clear mIsActiveSource on the hotplug event to pass CETC 11.2.2-2 ~ 3.
        if (WAKE_ON_HOTPLUG && connected && mService.isPowerStandbyOrTransient()) {
            mService.wakeUp();
        }
        if (!connected) {
            getWakeLock().release();
        }
    }

    @Override
    @ServiceThreadOnly
    protected void onStandby(boolean initiatedByCec, int standbyAction) {
        assertRunOnServiceThread();
        if (!mService.isControlEnabled() || initiatedByCec || !mAutoTvOff) {
            return;
        }
        switch (standbyAction) {
            case HdmiControlService.STANDBY_SCREEN_OFF:
                mService.sendCecCommand(
                        HdmiCecMessageBuilder.buildStandby(mAddress, Constants.ADDR_TV));
                break;
            case HdmiControlService.STANDBY_SHUTDOWN:
                // ACTION_SHUTDOWN is taken as a signal to power off all the devices.
                mService.sendCecCommand(
                        HdmiCecMessageBuilder.buildStandby(mAddress, Constants.ADDR_BROADCAST));
                break;
        }
    }

    @Override
    @ServiceThreadOnly
    void setAutoDeviceOff(boolean enabled) {
        assertRunOnServiceThread();
        mAutoTvOff = enabled;
    }

    @ServiceThreadOnly
    void setActiveSource(boolean on) {
        assertRunOnServiceThread();
        mIsActiveSource = on;
        if (on) {
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
                // Create a dummy lock object that doesn't do anything about wake lock,
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
    protected boolean handleActiveSource(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int physicalAddress = HdmiUtils.twoBytesToInt(message.getParams());
        mayResetActiveSource(physicalAddress);
        return true;  // Broadcast message.
    }

    private void mayResetActiveSource(int physicalAddress) {
        if (physicalAddress != mService.getPhysicalAddress()) {
            setActiveSource(false);
        }
    }

    @ServiceThreadOnly
    protected boolean handleUserControlPressed(HdmiCecMessage message) {
        assertRunOnServiceThread();
        wakeUpIfActiveSource();
        return super.handleUserControlPressed(message);
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleSetStreamPath(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int physicalAddress = HdmiUtils.twoBytesToInt(message.getParams());
        maySetActiveSource(physicalAddress);
        maySendActiveSource(message.getSource());
        wakeUpIfActiveSource();
        return true;  // Broadcast message.
    }

    // Samsung model we tested sends <Routing Change> and <Request Active Source>
    // in a row, and then changes the input to the internal source if there is no
    // <Active Source> in response. To handle this, we'll set ActiveSource aggressively.
    @Override
    @ServiceThreadOnly
    protected boolean handleRoutingChange(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int newPath = HdmiUtils.twoBytesToInt(message.getParams(), 2);
        maySetActiveSource(newPath);
        return true;  // Broadcast message.
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleRoutingInformation(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int physicalAddress = HdmiUtils.twoBytesToInt(message.getParams());
        maySetActiveSource(physicalAddress);
        return true;  // Broadcast message.
    }

    private void maySetActiveSource(int physicalAddress) {
        setActiveSource(physicalAddress == mService.getPhysicalAddress());
    }

    private void wakeUpIfActiveSource() {
        if (!mIsActiveSource) {
            return;
        }
        // Wake up the device if the power is in standby mode, or its screen is off -
        // which can happen if the device is holding a partial lock.
        if (mService.isPowerStandbyOrTransient() || !mService.getPowerManager().isScreenOn()) {
            mService.wakeUp();
        }
    }

    private void maySendActiveSource(int dest) {
        if (mIsActiveSource) {
            mService.sendCecCommand(HdmiCecMessageBuilder.buildActiveSource(
                    mAddress, mService.getPhysicalAddress()));
            // Always reports menu-status active to receive RCP.
            mService.sendCecCommand(HdmiCecMessageBuilder.buildReportMenuStatus(
                    mAddress, dest, Constants.MENU_STATE_ACTIVATED));
        }
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleRequestActiveSource(HdmiCecMessage message) {
        assertRunOnServiceThread();
        maySendActiveSource(message.getSource());
        return true;  // Broadcast message.
    }

    @ServiceThreadOnly
    protected boolean handleSetMenuLanguage(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (!SET_MENU_LANGUAGE) {
            return false;
        }

        try {
            String iso3Language = new String(message.getParams(), 0, 3, "US-ASCII");
            Locale currentLocale = mService.getContext().getResources().getConfiguration().locale;
            if (currentLocale.getISO3Language().equals(iso3Language)) {
                // Do not switch language if the new language is the same as the current one.
                // This helps avoid accidental country variant switching from en_US to en_AU
                // due to the limitation of CEC. See the warning below.
                return true;
            }

            // Don't use Locale.getAvailableLocales() since it returns a locale
            // which is not available on Settings.
            final List<LocaleInfo> localeInfos = LocalePicker.getAllAssetLocales(
                    mService.getContext(), false);
            for (LocaleInfo localeInfo : localeInfos) {
                if (localeInfo.getLocale().getISO3Language().equals(iso3Language)) {
                    // WARNING: CEC adopts ISO/FDIS-2 for language code, while Android requires
                    // additional country variant to pinpoint the locale. This keeps the right
                    // locale from being chosen. 'eng' in the CEC command, for instance,
                    // will always be mapped to en-AU among other variants like en-US, en-GB,
                    // an en-IN, which may not be the expected one.
                    LocalePicker.updateLocale(localeInfo.getLocale());
                    return true;
                }
            }
            Slog.w(TAG, "Can't handle <Set Menu Language> of " + iso3Language);
            return false;
        } catch (UnsupportedEncodingException e) {
            Slog.w(TAG, "Can't handle <Set Menu Language>", e);
            return false;
        }
    }

    @Override
    protected int findKeyReceiverAddress() {
        return Constants.ADDR_TV;
    }

    @Override
    @ServiceThreadOnly
    protected void sendStandby(int deviceId) {
        assertRunOnServiceThread();

        // Playback device can send <Standby> to TV only. Ignore the parameter.
        int targetAddress = Constants.ADDR_TV;
        mService.sendCecCommand(HdmiCecMessageBuilder.buildStandby(mAddress, targetAddress));
    }

    @Override
    @ServiceThreadOnly
    protected void disableDevice(boolean initiatedByCec, PendingActionClearedCallback callback) {
        super.disableDevice(initiatedByCec, callback);

        assertRunOnServiceThread();
        if (!initiatedByCec && mIsActiveSource) {
            mService.sendCecCommand(HdmiCecMessageBuilder.buildInactiveSource(
                    mAddress, mService.getPhysicalAddress()));
        }
        setActiveSource(false);
        checkIfPendingActionsCleared();
    }

    @Override
    protected void dump(final IndentingPrintWriter pw) {
        super.dump(pw);
        pw.println("mIsActiveSource: " + mIsActiveSource);
        pw.println("mAutoTvOff:" + mAutoTvOff);
    }

    // Wrapper interface over PowerManager.WakeLock
    private interface ActiveWakeLock {
        void acquire();
        void release();
        boolean isHeld();
    }

    private class SystemWakeLock implements ActiveWakeLock {
        private final WakeLock mWakeLock;
        public SystemWakeLock() {
            mWakeLock = mService.getPowerManager().newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
            mWakeLock.setReferenceCounted(false);
        }

        @Override
        public void acquire() {
            mWakeLock.acquire();
            HdmiLogger.debug("active source: %b. Wake lock acquired", mIsActiveSource);
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
