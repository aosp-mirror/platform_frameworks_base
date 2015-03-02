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

import android.hardware.hdmi.HdmiPortInfo;
import android.util.SparseArray;

import com.android.internal.util.IndentingPrintWriter;

/**
 * A handler class for MHL control command. It converts user's command into MHL command and pass it
 * to MHL HAL layer.
 * <p>
 * It can be created only by {@link HdmiMhlControllerStub#create}.
 */
final class HdmiMhlControllerStub {

    private static final SparseArray<HdmiMhlLocalDeviceStub> mLocalDevices = new SparseArray<>();
    private static final HdmiPortInfo[] EMPTY_PORT_INFO = new HdmiPortInfo[0];
    private static final int INVALID_MHL_VERSION = 0;
    private static final int NO_SUPPORTED_FEATURES = 0;
    private static final int INVALID_DEVICE_ROLES = 0;

    // Private constructor. Use HdmiMhlControllerStub.create().
    private HdmiMhlControllerStub(HdmiControlService service) {
    }

    // Returns true if MHL controller is initialized and ready to use.
    boolean isReady() {
        return false;
    }

    static HdmiMhlControllerStub create(HdmiControlService service) {
        return new HdmiMhlControllerStub(service);
    }

    HdmiPortInfo[] getPortInfos() {
        return EMPTY_PORT_INFO;
    }

    /**
     * Return {@link HdmiMhlLocalDeviceStub} matched with the given port id.
     *
     * @return null if has no matched port id
     */
    HdmiMhlLocalDeviceStub getLocalDevice(int portId) {
        return null;
    }

    /**
     * Return {@link HdmiMhlLocalDeviceStub} matched with the given device id.
     *
     * @return null if has no matched id
     */
    HdmiMhlLocalDeviceStub getLocalDeviceById(int deviceId) {
        return null;
    }

    SparseArray<HdmiMhlLocalDeviceStub> getAllLocalDevices() {
        return mLocalDevices;
    }

    /**
     * Remove a {@link HdmiMhlLocalDeviceStub} matched with the given port id.
     *
     * @return removed {@link HdmiMhlLocalDeviceStub}. Return null if no matched port id.
     */
    HdmiMhlLocalDeviceStub removeLocalDevice(int portId) {
        return null;
    }

    /**
     * Add a new {@link HdmiMhlLocalDeviceStub}.
     *
     * @return old {@link HdmiMhlLocalDeviceStub} having same port id
     */
    HdmiMhlLocalDeviceStub addLocalDevice(HdmiMhlLocalDeviceStub device) {
        return null;
    }

    void clearAllLocalDevices() {
    }

    void sendVendorCommand(int portId, int offset, int length, byte[] data) {
    }

    void setOption(int flag, int value) {
    }

    /**
     * Get the MHL version supported by underlying hardware port of the given {@code portId}.
     * MHL specification version 2.0 returns 0x20, 3.0 will return 0x30 respectively.
     * The return value is stored in 'version'. Return INVALID_VERSION if MHL hardware layer
     * is not ready.
     */
    int getMhlVersion(int portId) {
        return INVALID_MHL_VERSION;
    }

    /**
     * Get MHL version of a device which is connected to a port of the given {@code portId}.
     * MHL specification version 2.0 returns 0x20, 3.0 will return 0x30 respectively.
     * The return value is stored in 'version'.
     */
    int getPeerMhlVersion(int portId) {
        return INVALID_MHL_VERSION;
    }

    /**
     * Get the bit flags describing the features supported by the system. Refer to feature support
     * flag register info in MHL specification.
     */
    int getSupportedFeatures(int portId) {
        return NO_SUPPORTED_FEATURES;
    }

    /**
     * Get the bit flags describing the roles which ECBUS device can play. Refer to the
     * ECBUS_DEV_ROLES Register info MHL3.0 specification
     */
    int getEcbusDeviceRoles(int portId) {
        return INVALID_DEVICE_ROLES;
    }

    void dump(IndentingPrintWriter pw) {
    }
}
