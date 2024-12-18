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

import android.hardware.hdmi.HdmiDeviceInfo;
import android.util.Slog;

import com.android.server.hdmi.HdmiControlService.DevicePollingCallback;

import java.util.BitSet;
import java.util.List;

/**
 * Feature action that handles hot-plug detection mechanism.
 * Hot-plug event is initiated by timer after device discovery action.
 *
 * <p>TV checks all devices every 15 secs except for system audio.
 * If system audio is on, check hot-plug for audio system every 5 secs.
 * For other devices, keep 15 secs period.
 *
 * <p>Playback devices check all devices every 1 minute.
 */
// Seq #3
final class HotplugDetectionAction extends HdmiCecFeatureAction {
    private static final String TAG = "HotPlugDetectionAction";

    public static final long POLLING_MESSAGE_INTERVAL_MS_FOR_TV = 0;
    public static final long POLLING_MESSAGE_INTERVAL_MS_FOR_PLAYBACK = 500;
    public static final int POLLING_BATCH_INTERVAL_MS_FOR_TV = 5000;
    public static final int POLLING_BATCH_INTERVAL_MS_FOR_PLAYBACK = 60000;
    public static final int TIMEOUT_COUNT = 3;
    private static final int AVR_COUNT_MAX = 3;

    // State in which waits for next polling
    private static final int STATE_WAIT_FOR_NEXT_POLLING = 1;

    // All addresses except for broadcast (unregistered address).
    private static final int NUM_OF_ADDRESS = Constants.ADDR_SPECIFIC_USE
            - Constants.ADDR_TV + 1;

    private int mTimeoutCount = 0;

    // Counter used to ensure the connection to AVR is stable. Occasional failure to get
    // polling response from AVR despite its presence leads to unstable status flipping.
    // This is a workaround to deal with it, by removing the device only if the removal
    // is detected {@code AVR_COUNT_MAX} times in a row.
    private int mAvrStatusCount = 0;

    private final boolean mIsTvDevice = localDevice().mService.isTvDevice();

    /**
     * Constructor
     *
     * @param source {@link HdmiCecLocalDevice} instance
     */
    HotplugDetectionAction(HdmiCecLocalDevice source) {
        super(source);
    }

    private int getPollingBatchInterval() {
        return mIsTvDevice ? POLLING_BATCH_INTERVAL_MS_FOR_TV
                           : POLLING_BATCH_INTERVAL_MS_FOR_PLAYBACK;
    }

    @Override
    boolean start() {
        Slog.v(TAG, "Hot-plug detection started.");

        mState = STATE_WAIT_FOR_NEXT_POLLING;
        mTimeoutCount = 0;

        // Start timer without polling.
        // The first check for all devices will be initiated 15 seconds later for TV panels and 60
        // seconds later for playback devices.
        addTimer(mState, getPollingBatchInterval());
        return true;
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        // No-op
        return false;
    }

    @Override
    void handleTimerEvent(int state) {
        if (mState != state) {
            return;
        }

        if (mState == STATE_WAIT_FOR_NEXT_POLLING) {
            if (mIsTvDevice) {
                mTimeoutCount = (mTimeoutCount + 1) % TIMEOUT_COUNT;
                if (mTimeoutCount == 0) {
                    pollAllDevices();
                } else if (tv().isSystemAudioActivated()) {
                    pollAudioSystem();
                }
                addTimer(mState, POLLING_BATCH_INTERVAL_MS_FOR_TV);
                return;
            }
            pollAllDevices();
            addTimer(mState, POLLING_BATCH_INTERVAL_MS_FOR_PLAYBACK);
        }
    }

    /**
     * Start device polling immediately. This method is called only by
     * {@link HdmiCecLocalDeviceTv#onHotplug}.
     */
    void pollAllDevicesNow() {
        // Clear existing timer to avoid overlapped execution
        mActionTimer.clearTimerMessage();

        mTimeoutCount = 0;
        mState = STATE_WAIT_FOR_NEXT_POLLING;
        pollAllDevices();

        addTimer(mState, getPollingBatchInterval());
    }

    private void pollAllDevices() {
        Slog.v(TAG, "Poll all devices.");

        pollDevices(
                new DevicePollingCallback() {
                    @Override
                    public void onPollingFinished(List<Integer> ackedAddress) {
                        checkHotplug(ackedAddress, false);
                        Slog.v(TAG, "Finish poll all devices.");
                    }
                },
                Constants.POLL_ITERATION_IN_ORDER | Constants.POLL_STRATEGY_REMOTES_DEVICES,
                HdmiConfig.HOTPLUG_DETECTION_RETRY,
                mIsTvDevice ? POLLING_MESSAGE_INTERVAL_MS_FOR_TV
                            : POLLING_MESSAGE_INTERVAL_MS_FOR_PLAYBACK);
    }

    private void pollAudioSystem() {
        Slog.v(TAG, "Poll audio system.");

        pollDevices(new DevicePollingCallback() {
            @Override
            public void onPollingFinished(List<Integer> ackedAddress) {
                checkHotplug(ackedAddress, true);
            }
        }, Constants.POLL_ITERATION_IN_ORDER
                | Constants.POLL_STRATEGY_SYSTEM_AUDIO, HdmiConfig.HOTPLUG_DETECTION_RETRY);
    }

    private void checkHotplug(List<Integer> ackedAddress, boolean audioOnly) {
        List<HdmiDeviceInfo> deviceInfoList =
                localDevice().mService.getHdmiCecNetwork().getDeviceInfoList(false);
        BitSet currentInfos = infoListToBitSet(deviceInfoList, audioOnly, false);
        BitSet polledResult = addressListToBitSet(ackedAddress);

        // At first, check removed devices.
        BitSet removed = complement(currentInfos, polledResult);
        int index = -1;
        while ((index = removed.nextSetBit(index + 1)) != -1) {
            if (mIsTvDevice && index == Constants.ADDR_AUDIO_SYSTEM) {
                HdmiDeviceInfo avr = tv().getAvrDeviceInfo();
                if (avr != null && tv().isConnected(avr.getPortId())) {
                    ++mAvrStatusCount;
                    Slog.w(TAG, "Ack not returned from AVR. count: " + mAvrStatusCount);
                    if (mAvrStatusCount < AVR_COUNT_MAX) {
                        continue;
                    }
                }
            }
            Slog.v(TAG, "Remove device by hot-plug detection:" + index);
            removeDevice(index);
        }

        // Reset the counter if the ack is returned from AVR.
        if (!removed.get(Constants.ADDR_AUDIO_SYSTEM)) {
            mAvrStatusCount = 0;
        }

        // Next, check added devices.
        BitSet currentInfosWithPhysicalAddress = infoListToBitSet(deviceInfoList, audioOnly, true);
        BitSet added = complement(polledResult, currentInfosWithPhysicalAddress);
        index = -1;
        while ((index = added.nextSetBit(index + 1)) != -1) {
            Slog.v(TAG, "Add device by hot-plug detection:" + index);
            addDevice(index);
        }
    }

    private static BitSet infoListToBitSet(
            List<HdmiDeviceInfo> infoList, boolean audioOnly, boolean requirePhysicalAddress) {
        BitSet set = new BitSet(NUM_OF_ADDRESS);
        for (HdmiDeviceInfo info : infoList) {
            boolean audioOnlyConditionMet = !audioOnly
                    || (info.getDeviceType() == HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);
            boolean requirePhysicalAddressConditionMet = !requirePhysicalAddress
                    || (info.getPhysicalAddress() != HdmiDeviceInfo.PATH_INVALID);
            if (audioOnlyConditionMet && requirePhysicalAddressConditionMet) {
                set.set(info.getLogicalAddress());
            }
        }
        return set;
    }

    private static BitSet addressListToBitSet(List<Integer> list) {
        BitSet set = new BitSet(NUM_OF_ADDRESS);
        for (Integer value : list) {
            set.set(value);
        }
        return set;
    }

    // A - B = A & ~B
    private static BitSet complement(BitSet first, BitSet second) {
        // Need to clone it so that it doesn't touch original set.
        BitSet clone = (BitSet) first.clone();
        clone.andNot(second);
        return clone;
    }

    private void addDevice(int addedAddress) {
        // Sending <Give Physical Address> will initiate new device action.
        sendCommand(HdmiCecMessageBuilder.buildGivePhysicalAddress(getSourceAddress(),
                addedAddress));
    }

    private void removeDevice(int removedAddress) {
        if (mIsTvDevice) {
            mayChangeRoutingPath(removedAddress);
            mayCancelOneTouchRecord(removedAddress);
            mayDisableSystemAudioAndARC(removedAddress);
        }
        mayCancelDeviceSelect(removedAddress);
        localDevice().mService.getHdmiCecNetwork().removeCecDevice(localDevice(), removedAddress);
    }

    private void mayChangeRoutingPath(int address) {
        HdmiDeviceInfo info = localDevice().mService.getHdmiCecNetwork().getCecDeviceInfo(address);
        if (info != null) {
            tv().handleRemoveActiveRoutingPath(info.getPhysicalAddress());
        }
    }

    private void mayCancelDeviceSelect(int address) {
        List<DeviceSelectActionFromTv> actionsFromTv = getActions(DeviceSelectActionFromTv.class);
        for (DeviceSelectActionFromTv action : actionsFromTv) {
            if (action.getTargetAddress() == address) {
                removeAction(DeviceSelectActionFromTv.class);
            }
        }

        List<DeviceSelectActionFromPlayback> actionsFromPlayback = getActions(
                DeviceSelectActionFromPlayback.class);
        for (DeviceSelectActionFromPlayback action : actionsFromPlayback) {
            if (action.getTargetAddress() == address) {
                removeAction(DeviceSelectActionFromTv.class);
            }
        }
    }

    private void mayCancelOneTouchRecord(int address) {
        List<OneTouchRecordAction> actions = getActions(OneTouchRecordAction.class);
        for (OneTouchRecordAction action : actions) {
            if (action.getRecorderAddress() == address) {
                removeAction(action);
            }
        }
    }

    private void mayDisableSystemAudioAndARC(int address) {
        if (!HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM, address)) {
            return;
        }

        tv().setSystemAudioMode(false);
        if (tv().isArcEstablished()) {
            tv().enableAudioReturnChannel(false);
            addAndStartAction(new RequestArcTerminationAction(localDevice(), address));
        }
    }
}
