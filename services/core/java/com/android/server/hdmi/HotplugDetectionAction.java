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

import android.hardware.hdmi.HdmiCec;
import android.hardware.hdmi.HdmiCecDeviceInfo;
import android.hardware.hdmi.HdmiCecMessage;
import android.util.Slog;

import com.android.server.hdmi.HdmiControlService.DevicePollingCallback;

import java.util.BitSet;
import java.util.List;

/**
 * Feature action that handles hot-plug detection mechanism.
 * Hot-plug event is initiated by timer after device discovery action.
 *
 * <p>Check all devices every 15 secs except for system audio.
 * If system audio is on, check hot-plug for audio system every 5 secs.
 * For other devices, keep 15 secs period.
 */
final class HotplugDetectionAction extends FeatureAction {
    private static final String TAG = "HotPlugDetectionAction";

    private static final int POLLING_INTERVAL_MS = 5000;
    private static final int TIMEOUT_COUNT = 3;
    private static final int POLL_RETRY_COUNT = 2;

    // State in which waits for next polling
    private static final int STATE_WAIT_FOR_NEXT_POLLING = 1;

    // All addresses except for broadcast (unregistered address).
    private static final int NUM_OF_ADDRESS = HdmiCec.ADDR_SPECIFIC_USE - HdmiCec.ADDR_TV + 1;

    private int mTimeoutCount = 0;

    /**
     * Constructor
     *
     * @param source {@link HdmiCecLocalDevice} instance
     */
    HotplugDetectionAction(HdmiCecLocalDevice source) {
        super(source);
    }

    @Override
    boolean start() {
        Slog.v(TAG, "Hot-plug dection started.");

        mState = STATE_WAIT_FOR_NEXT_POLLING;
        mTimeoutCount = 0;

        // Start timer without polling.
        // The first check for all devices will be initiated 15 seconds later.
        addTimer(mState, POLLING_INTERVAL_MS);
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
            mTimeoutCount = (mTimeoutCount + 1) % TIMEOUT_COUNT;
            pollDevices();
        }
    }

    /**
     * Start device polling immediately.
     */
    void pollAllDevicesNow() {
        // Clear existing timer to avoid overlapped execution
        mActionTimer.clearTimerMessage();

        mTimeoutCount = 0;
        mState = STATE_WAIT_FOR_NEXT_POLLING;
        pollAllDevices();

        addTimer(mState, POLLING_INTERVAL_MS);
    }

    // This method is called every 5 seconds.
    private void pollDevices() {
        // All device check called every 15 seconds.
        if (mTimeoutCount == 0) {
            pollAllDevices();
        } else {
            if (tv().getSystemAudioMode()) {
                pollAudioSystem();
            }
        }

        addTimer(mState, POLLING_INTERVAL_MS);
    }

    private void pollAllDevices() {
        Slog.v(TAG, "Poll all devices.");

        pollDevices(new DevicePollingCallback() {
            @Override
            public void onPollingFinished(List<Integer> ackedAddress) {
                checkHotplug(ackedAddress, false);
            }
        }, HdmiControlService.POLL_ITERATION_IN_ORDER
                | HdmiControlService.POLL_STRATEGY_REMOTES_DEVICES, POLL_RETRY_COUNT);
    }

    private void pollAudioSystem() {
        Slog.v(TAG, "Poll audio system.");

        pollDevices(new DevicePollingCallback() {
            @Override
            public void onPollingFinished(List<Integer> ackedAddress) {
                checkHotplug(ackedAddress, true);
            }
        }, HdmiControlService.POLL_ITERATION_IN_ORDER
                | HdmiControlService.POLL_STRATEGY_SYSTEM_AUDIO, POLL_RETRY_COUNT);
    }

    private void checkHotplug(List<Integer> ackedAddress, boolean audioOnly) {
        BitSet currentInfos = infoListToBitSet(tv().getDeviceInfoList(false), audioOnly);
        BitSet polledResult = addressListToBitSet(ackedAddress);

        // At first, check removed devices.
        BitSet removed = complement(currentInfos, polledResult);
        int index = -1;
        while ((index = removed.nextSetBit(index + 1)) != -1) {
            Slog.v(TAG, "Remove device by hot-plug detection:" + index);
            removeDevice(index);
        }

        // Next, check added devices.
        BitSet added = complement(polledResult, currentInfos);
        index = -1;
        while ((index = added.nextSetBit(index + 1)) != -1) {
            Slog.v(TAG, "Add device by hot-plug detection:" + index);
            addDevice(index);
        }
    }

    private static BitSet infoListToBitSet(List<HdmiCecDeviceInfo> infoList, boolean audioOnly) {
        BitSet set = new BitSet(NUM_OF_ADDRESS);
        for (HdmiCecDeviceInfo info : infoList) {
            if (audioOnly) {
                if (info.getDeviceType() == HdmiCec.DEVICE_AUDIO_SYSTEM) {
                    set.set(info.getLogicalAddress());
                }
            } else {
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
        // Send <Give Physical Address>.
        sendCommand(HdmiCecMessageBuilder.buildGivePhysicalAddress(getSourceAddress(),
                addedAddress));
    }

    private void removeDevice(int removedAddress) {
        // TODO: move the following logic to local device once move many logic to
        //       local device.
        mayChangeRoutingPath(removedAddress);
        mayCancelDeviceSelect(removedAddress);
        mayCancelOneTouchRecord(removedAddress);
        mayDisableSystemAudioAndARC(removedAddress);

        tv().removeCecDevice(removedAddress);
    }

    private void mayChangeRoutingPath(int address) {
        // TODO: if removed address is current active source, it should change active source
        // path new one. we can keep previous selection or can choose default input,
        // such as internal tuner. Consider send intent to app so that app
        // can handle it.
    }

    private void mayCancelDeviceSelect(int address) {
        List<DeviceSelectAction> actions = getActions(DeviceSelectAction.class);
        if (actions.isEmpty()) {
            return;
        }

        // Should ave only one Device Select Action
        DeviceSelectAction action = actions.get(0);
        if (action.getTargetAddress() == address) {
            removeAction(DeviceSelectAction.class);
        }
    }

    private void mayCancelOneTouchRecord(int address) {
        // TODO: implement this.
    }

    private void mayDisableSystemAudioAndARC(int address) {
        if (HdmiCec.getTypeFromAddress(address) != HdmiCec.DEVICE_AUDIO_SYSTEM) {
            return;
        }

        // Turn off system audio mode.
        tv().setSystemAudioMode(false);
        if (tv().getArcStatus()) {
            addAndStartAction(new RequestArcTerminationAction(localDevice(), address));
        }
    }
}
