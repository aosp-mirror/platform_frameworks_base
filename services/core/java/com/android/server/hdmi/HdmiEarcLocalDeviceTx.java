/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.android.server.hdmi.Constants.HDMI_EARC_STATUS_ARC_PENDING;
import static com.android.server.hdmi.Constants.HDMI_EARC_STATUS_EARC_CONNECTED;
import static com.android.server.hdmi.Constants.HDMI_EARC_STATUS_IDLE;

import android.hardware.hdmi.HdmiDeviceInfo;
import android.media.AudioDescriptor;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioProfile;
import android.os.Handler;
import android.util.IndentingPrintWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a local eARC device of type TX residing in the Android system.
 * Only TV panel devices can have a local eARC TX device.
 */
public class HdmiEarcLocalDeviceTx extends HdmiEarcLocalDevice {
    private static final String TAG = "HdmiEarcLocalDeviceTx";

    // How long to wait for the audio system to report its capabilities after eARC was connected
    static final long REPORT_CAPS_MAX_DELAY_MS = 2_000;

    // Handler and runnable for waiting for the audio system to report its capabilities after eARC
    // was connected
    private Handler mReportCapsHandler;
    private ReportCapsRunnable mReportCapsRunnable;

    HdmiEarcLocalDeviceTx(HdmiControlService service) {
        super(service, HdmiDeviceInfo.DEVICE_TV);

        mReportCapsHandler = new Handler(service.getServiceLooper());
        mReportCapsRunnable = new ReportCapsRunnable();
    }

    protected void handleEarcStateChange(@Constants.EarcStatus int status) {
        synchronized (mLock) {
            HdmiLogger.debug(TAG, "eARC state change [old:%b new %b]", mEarcStatus,
                    status);
            mEarcStatus = status;
        }

        mReportCapsHandler.removeCallbacksAndMessages(null);
        if (status == HDMI_EARC_STATUS_IDLE) {
            notifyEarcStatusToAudioService(false, new ArrayList<>());
        } else if (status == HDMI_EARC_STATUS_ARC_PENDING) {
            notifyEarcStatusToAudioService(false, new ArrayList<>());
        } else if (status == HDMI_EARC_STATUS_EARC_CONNECTED) {
            mReportCapsHandler.postDelayed(mReportCapsRunnable, REPORT_CAPS_MAX_DELAY_MS);
        }
    }

    protected void handleEarcCapabilitiesReported(List<byte[]> capabilities) {
        synchronized (mLock) {
            if (mEarcStatus == HDMI_EARC_STATUS_EARC_CONNECTED
                    && mReportCapsHandler.hasCallbacks(mReportCapsRunnable)) {
                mReportCapsHandler.removeCallbacksAndMessages(null);
                notifyEarcStatusToAudioService(true, capabilities);
            }
        }
    }

    private void notifyEarcStatusToAudioService(boolean enabled, List<byte[]> capabilities) {
        AudioDeviceAttributes attributes = new AudioDeviceAttributes(
                AudioDeviceAttributes.ROLE_OUTPUT, AudioDeviceInfo.TYPE_HDMI_EARC, "", "",
                new ArrayList<AudioProfile>(), capabilities.stream()
                .map(cap -> new AudioDescriptor(AudioDescriptor.STANDARD_EDID,
                        AudioProfile.AUDIO_ENCAPSULATION_TYPE_NONE, cap))
                .collect(Collectors.toList()));
        mService.getAudioManager().setWiredDeviceConnectionState(attributes, enabled ? 1 : 0);
    }

    /**
     * Runnable for waiting for a certain amount of time for the audio system to report its
     * capabilities after eARC was connected. If the audio system doesn´t report its capabilities in
     * this time, we inform AudioService about the connection state only, without any specified
     * capabilities.
     */
    private class ReportCapsRunnable implements Runnable {
        @Override
        public void run() {
            synchronized (mLock) {
                if (mEarcStatus == HDMI_EARC_STATUS_EARC_CONNECTED) {
                    notifyEarcStatusToAudioService(true, new ArrayList<>());
                }
            }
        }
    }

    /** Dump internal status of HdmiEarcLocalDeviceTx object */
    protected void dump(final IndentingPrintWriter pw) {
        synchronized (mLock) {
            pw.println("TX, mEarcStatus: " + mEarcStatus);
        }
    }
}
