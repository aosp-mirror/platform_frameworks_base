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

import android.annotation.Nullable;
import android.content.Context;
import android.hardware.hdmi.HdmiCec;
import android.hardware.hdmi.HdmiCecDeviceInfo;
import android.hardware.hdmi.HdmiCecMessage;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Slog;

import com.android.server.SystemService;

import java.util.Locale;

/**
 * Provides a service for sending and processing HDMI control messages,
 * HDMI-CEC and MHL control command, and providing the information on both standard.
 */
public final class HdmiControlService extends SystemService {
    private static final String TAG = "HdmiControlService";

    // A thread to handle synchronous IO of CEC and MHL control service.
    // Since all of CEC and MHL HAL interfaces processed in short time (< 200ms)
    // and sparse call it shares a thread to handle IO operations.
    private final HandlerThread mIoThread = new HandlerThread("Hdmi Control Io Thread");

    @Nullable
    private HdmiCecController mCecController;

    @Nullable
    private HdmiMhlController mMhlController;

    public HdmiControlService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        mIoThread.start();
        mCecController = HdmiCecController.create(this);
        if (mCecController != null) {
            mCecController.initializeLocalDevices(getContext().getResources()
                    .getIntArray(com.android.internal.R.array.config_hdmiCecLogicalDeviceType));
        } else {
            Slog.i(TAG, "Device does not support HDMI-CEC.");
        }

        mMhlController = HdmiMhlController.create(this);
        if (mMhlController == null) {
            Slog.i(TAG, "Device does not support MHL-control.");
        }
    }

    /**
     * Returns {@link Looper} for IO operation.
     *
     * <p>Declared as package-private.
     */
    Looper getIoLooper() {
        return mIoThread.getLooper();
    }

    /**
     * Returns {@link Looper} of main thread. Use this {@link Looper} instance
     * for tasks that are running on main service thread.
     *
     * <p>Declared as package-private.
     */
    Looper getServiceLooper() {
        return Looper.myLooper();
    }

    /**
     * Add a new {@link FeatureAction} to the action queue.
     *
     * @param action {@link FeatureAction} to add
     */
    void addAction(FeatureAction action) {
        // TODO: Implement this.
    }


    /**
     * Remove the given {@link FeatureAction} object from the action queue.
     *
     * @param action {@link FeatureAction} to add
     */
    void removeAction(FeatureAction action) {
        // TODO: Implement this.
    }

    /**
     * Transmit a CEC command to CEC bus.
     *
     * @param command CEC command to send out
     */
    void sendCecCommand(HdmiCecMessage command) {
        mCecController.sendCommand(command);
    }

    /**
     * Add a new {@link HdmiCecDeviceInfo} to controller.
     *
     * @param deviceInfo new device information object to add
     */
    void addDeviceInfo(HdmiCecDeviceInfo deviceInfo) {
        // TODO: Implement this.
    }

    boolean handleCecCommand(HdmiCecMessage message) {
        // Commands that queries system information replies directly instead
        // of creating FeatureAction because they are state-less.
        switch (message.getOpcode()) {
            case HdmiCec.MESSAGE_GET_MENU_LANGUAGE:
                handleGetMenuLanguage(message);
                return true;
            case HdmiCec.MESSAGE_GIVE_OSD_NAME:
                handleGiveOsdName(message);
                return true;
            case HdmiCec.MESSAGE_GIVE_PHYSICAL_ADDRESS:
                handleGivePhysicalAddress(message);
                return true;
            case HdmiCec.MESSAGE_GIVE_DEVICE_VENDOR_ID:
                handleGiveDeviceVendorId(message);
                return true;
            case HdmiCec.MESSAGE_GET_CEC_VERSION:
                handleGetCecVersion(message);
                return true;
            // TODO: Add remaining system information query such as
            // <Give Device Power Status> and <Request Active Source> handler.
            default:
                Slog.w(TAG, "Unsupported cec command:" + message.toString());
                return false;
        }
    }

    private void handleGetCecVersion(HdmiCecMessage message) {
        int version = mCecController.getVersion();
        HdmiCecMessage cecMessage = HdmiCecMessageBuilder.buildCecVersion(message.getDestination(),
                message.getSource(),
                version);
        sendCecCommand(cecMessage);
    }

    private void handleGiveDeviceVendorId(HdmiCecMessage message) {
        int vendorId = mCecController.getVendorId();
        HdmiCecMessage cecMessage = HdmiCecMessageBuilder.buildDeviceVendorIdCommand(
                message.getDestination(), vendorId);
        sendCecCommand(cecMessage);
    }

    private void handleGivePhysicalAddress(HdmiCecMessage message) {
        int physicalAddress = mCecController.getPhysicalAddress();
        int deviceType = HdmiCec.getTypeFromAddress(message.getDestination());
        HdmiCecMessage cecMessage = HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                message.getDestination(), physicalAddress, deviceType);
        sendCecCommand(cecMessage);
    }

    private void handleGiveOsdName(HdmiCecMessage message) {
        // TODO: read device name from settings or property.
        String name = HdmiCec.getDefaultDeviceName(message.getDestination());
        HdmiCecMessage cecMessage = HdmiCecMessageBuilder.buildSetOsdNameCommand(
                message.getDestination(), message.getSource(), name);
        if (cecMessage != null) {
            sendCecCommand(cecMessage);
        } else {
            Slog.w(TAG, "Failed to build <Get Osd Name>:" + name);
        }
    }

    private void handleGetMenuLanguage(HdmiCecMessage message) {
        // Only 0 (TV), 14 (specific use) can answer.
        if (message.getDestination() != HdmiCec.ADDR_TV
                && message.getDestination() != HdmiCec.ADDR_SPECIFIC_USE) {
            Slog.w(TAG, "Only TV can handle <Get Menu Language>:" + message.toString());
            sendCecCommand(
                    HdmiCecMessageBuilder.buildFeatureAbortCommand(message.getDestination(),
                            message.getSource(), HdmiCec.MESSAGE_GET_MENU_LANGUAGE,
                            HdmiCecMessageBuilder.ABORT_UNRECOGNIZED_MODE));
            return;
        }

        HdmiCecMessage command = HdmiCecMessageBuilder.buildSetMenuLanguageCommand(
                message.getDestination(),
                Locale.getDefault().getISO3Language());
        // TODO: figure out how to handle failed to get language code.
        if (command != null) {
            sendCecCommand(command);
        } else {
            Slog.w(TAG, "Failed to respond to <Get Menu Language>: " + message.toString());
        }
    }
}
