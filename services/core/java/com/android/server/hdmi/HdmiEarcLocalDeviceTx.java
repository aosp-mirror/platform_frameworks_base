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
import static com.android.server.hdmi.Constants.HDMI_EARC_STATUS_EARC_PENDING;
import static com.android.server.hdmi.Constants.HDMI_EARC_STATUS_IDLE;

import android.hardware.hdmi.HdmiDeviceInfo;
import android.media.AudioDescriptor;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioProfile;
import android.os.Handler;
import android.util.IndentingPrintWriter;
import android.util.Slog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Represents a local eARC device of type TX residing in the Android system.
 * Only TV panel devices can have a local eARC TX device.
 */
public class HdmiEarcLocalDeviceTx extends HdmiEarcLocalDevice {
    private static final String TAG = "HdmiEarcLocalDeviceTx";

    // How long to wait for the audio system to report its capabilities after eARC was connected
    static final long REPORT_CAPS_MAX_DELAY_MS = 2_000;

    // eARC Capability Data Structure parameters
    private static final int EARC_CAPS_PAYLOAD_LENGTH = 0x02;
    private static final int EARC_CAPS_DATA_START = 0x03;

    // Table 55 CTA Data Block Tag Codes
    private static final int TAGCODE_AUDIO_DATA_BLOCK = 0x01; // Includes one or more Short Audio
                                                              // Descriptors
    private static final int TAGCODE_SADB_DATA_BLOCK = 0x04;  // Speaker Allocation Data Block
    private static final int TAGCODE_USE_EXTENDED_TAG = 0x07; // Use Extended Tag

    // Table 56 Extended Tag Format (2nd byte of Data Block)
    private static final int EXTENDED_TAGCODE_VSADB = 0x11;   // Vendor-Specific Audio Data Block

    // eARC capability mask and shift
    private static final int EARC_CAPS_TAGCODE_MASK = 0xE0;
    private static final int EARC_CAPS_TAGCODE_SHIFT = 0x05;
    private static final int EARC_CAPS_LENGTH_MASK = 0x1F;

    // Handler and runnable for waiting for the audio system to report its capabilities after eARC
    // was connected
    private Handler mReportCapsHandler;
    private ReportCapsRunnable mReportCapsRunnable;

    HdmiEarcLocalDeviceTx(HdmiControlService service) {
        super(service, HdmiDeviceInfo.DEVICE_TV);

        synchronized (mLock) {
            mEarcStatus = HDMI_EARC_STATUS_EARC_PENDING;
        }
        mReportCapsHandler = new Handler(service.getServiceLooper());
        mReportCapsRunnable = new ReportCapsRunnable();
    }

    protected void handleEarcStateChange(@Constants.EarcStatus int status) {
        int oldEarcStatus;
        synchronized (mLock) {
            HdmiLogger.debug(TAG, "eARC state change [old:%b new %b]", mEarcStatus,
                    status);
            oldEarcStatus = mEarcStatus;
            mEarcStatus = status;
        }

        mReportCapsHandler.removeCallbacksAndMessages(null);
        if (status == HDMI_EARC_STATUS_IDLE) {
            notifyEarcStatusToAudioService(false, new ArrayList<>());
            mService.startArcAction(false, null);
        } else if (status == HDMI_EARC_STATUS_ARC_PENDING) {
            notifyEarcStatusToAudioService(false, new ArrayList<>());
            mService.startArcAction(true, null);
        } else if (status == HDMI_EARC_STATUS_EARC_PENDING
                && oldEarcStatus == HDMI_EARC_STATUS_ARC_PENDING) {
            mService.startArcAction(false, null);
        } else if (status == HDMI_EARC_STATUS_EARC_CONNECTED) {
            if (oldEarcStatus == HDMI_EARC_STATUS_ARC_PENDING) {
                mService.startArcAction(false, null);
            }
            mReportCapsHandler.postDelayed(mReportCapsRunnable, REPORT_CAPS_MAX_DELAY_MS);
        }
    }

    protected void handleEarcCapabilitiesReported(byte[] rawCapabilities) {
        synchronized (mLock) {
            if (mEarcStatus == HDMI_EARC_STATUS_EARC_CONNECTED
                    && mReportCapsHandler.hasCallbacks(mReportCapsRunnable)) {
                mReportCapsHandler.removeCallbacksAndMessages(null);
                List<AudioDescriptor> audioDescriptors = parseCapabilities(rawCapabilities);
                notifyEarcStatusToAudioService(true, audioDescriptors);
            }
        }
    }

    private void notifyEarcStatusToAudioService(
            boolean enabled, List<AudioDescriptor> audioDescriptors) {
        AudioDeviceAttributes attributes = new AudioDeviceAttributes(
                AudioDeviceAttributes.ROLE_OUTPUT, AudioDeviceInfo.TYPE_HDMI_EARC, "", "",
                new ArrayList<AudioProfile>(), audioDescriptors);
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

    private List<AudioDescriptor> parseCapabilities(byte[] rawCapabilities) {
        List<AudioDescriptor> audioDescriptors = new ArrayList<>();
        if (rawCapabilities.length < EARC_CAPS_DATA_START + 1) {
            Slog.i(TAG, "Raw eARC capabilities array doesn´t contain any blocks.");
            return audioDescriptors;
        }
        int earcCapsSize = rawCapabilities[EARC_CAPS_PAYLOAD_LENGTH];
        if (rawCapabilities.length < earcCapsSize) {
            Slog.i(TAG, "Raw eARC capabilities array is shorter than the reported payload length.");
            return audioDescriptors;
        }
        int firstByteOfBlock = EARC_CAPS_DATA_START;
        while (firstByteOfBlock < earcCapsSize) {
            // Tag Code: Bit 5-7
            int tagCode =
                    (rawCapabilities[firstByteOfBlock] & EARC_CAPS_TAGCODE_MASK)
                            >> EARC_CAPS_TAGCODE_SHIFT;
            // Length: Bit 0-4
            int length = rawCapabilities[firstByteOfBlock] & EARC_CAPS_LENGTH_MASK;
            if (length == 0) {
                // End Marker of eARC capability.
                break;
            }
            AudioDescriptor descriptor;
            switch (tagCode) {
                case TAGCODE_AUDIO_DATA_BLOCK:
                    int earcSadLen = length;
                    if (length % 3 != 0) {
                        Slog.e(TAG, "Invalid length of SAD block: expected a factor of 3 but got "
                                + length % 3);
                        break;
                    }
                    byte[] earcSad = new byte[earcSadLen];
                    System.arraycopy(rawCapabilities, firstByteOfBlock + 1, earcSad, 0, earcSadLen);
                    for (int i = 0; i < earcSadLen; i += 3) {
                        descriptor = new AudioDescriptor(
                                AudioDescriptor.STANDARD_EDID,
                                AudioProfile.AUDIO_ENCAPSULATION_TYPE_NONE,
                                Arrays.copyOfRange(earcSad, i, i + 3));
                        audioDescriptors.add(descriptor);
                    }
                    break;
                case TAGCODE_SADB_DATA_BLOCK:
                    //Include Tag code size
                    int earcSadbLen = length + 1;
                    byte[] earcSadb = new byte[earcSadbLen];
                    System.arraycopy(rawCapabilities, firstByteOfBlock, earcSadb, 0, earcSadbLen);
                    descriptor = new AudioDescriptor(
                            AudioDescriptor.STANDARD_SADB,
                            AudioProfile.AUDIO_ENCAPSULATION_TYPE_NONE,
                            earcSadb);
                    audioDescriptors.add(descriptor);
                    break;
                case TAGCODE_USE_EXTENDED_TAG:
                    if (rawCapabilities[firstByteOfBlock + 1] == EXTENDED_TAGCODE_VSADB) {
                        int earcVsadbLen = length + 1; //Include Tag code size
                        byte[] earcVsadb = new byte[earcVsadbLen];
                        System.arraycopy(rawCapabilities, firstByteOfBlock, earcVsadb, 0,
                                earcVsadbLen);
                        descriptor = new AudioDescriptor(
                                AudioDescriptor.STANDARD_VSADB,
                                AudioProfile.AUDIO_ENCAPSULATION_TYPE_NONE,
                                earcVsadb);
                        audioDescriptors.add(descriptor);
                    }
                    break;
                default:
                    Slog.w(TAG, "This tagcode was not handled: " + tagCode);
                    break;
            }
            firstByteOfBlock += (length + 1);
        }
        return audioDescriptors;
    }

    /** Dump internal status of HdmiEarcLocalDeviceTx object */
    protected void dump(final IndentingPrintWriter pw) {
        synchronized (mLock) {
            pw.println("TX, mEarcStatus: " + mEarcStatus);
        }
    }
}
