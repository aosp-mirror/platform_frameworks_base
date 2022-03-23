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

package com.android.internal.usb;

import static android.hardware.usb.UsbPortStatus.MODE_AUDIO_ACCESSORY;
import static android.hardware.usb.UsbPortStatus.MODE_DEBUG_ACCESSORY;
import static android.hardware.usb.UsbPortStatus.MODE_DFP;
import static android.hardware.usb.UsbPortStatus.MODE_DUAL;
import static android.hardware.usb.UsbPortStatus.MODE_NONE;
import static android.hardware.usb.UsbPortStatus.MODE_UFP;

import static com.android.internal.util.dump.DumpUtils.writeStringIfNotNull;

import android.annotation.NonNull;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbPort;
import android.hardware.usb.UsbPortStatus;
import android.hardware.usb.V1_0.Constants;
import android.service.usb.UsbAccessoryProto;
import android.service.usb.UsbConfigurationProto;
import android.service.usb.UsbDeviceProto;
import android.service.usb.UsbEndPointProto;
import android.service.usb.UsbInterfaceProto;
import android.service.usb.UsbPortProto;
import android.service.usb.UsbPortStatusProto;
import android.service.usb.UsbPortStatusRoleCombinationProto;

import com.android.internal.util.dump.DualDumpOutputStream;

/** Dump methods for public USB classes */
public class DumpUtils {
    public static void writeAccessory(@NonNull DualDumpOutputStream dump, @NonNull String idName,
            long id, @NonNull UsbAccessory accessory) {
        long token = dump.start(idName, id);

        dump.write("manufacturer", UsbAccessoryProto.MANUFACTURER, accessory.getManufacturer());
        dump.write("model", UsbAccessoryProto.MODEL, accessory.getModel());
        writeStringIfNotNull(dump, "description", UsbAccessoryProto.DESCRIPTION,
                accessory.getManufacturer());
        dump.write("version", UsbAccessoryProto.VERSION, accessory.getVersion());
        writeStringIfNotNull(dump, "uri", UsbAccessoryProto.URI, accessory.getUri());
        dump.write("serial", UsbAccessoryProto.SERIAL, accessory.getSerial());

        dump.end(token);
    }

    public static void writeDevice(@NonNull DualDumpOutputStream dump, @NonNull String idName,
            long id, @NonNull UsbDevice device) {
        long token = dump.start(idName, id);

        dump.write("name", UsbDeviceProto.NAME, device.getDeviceName());
        dump.write("vendor_id", UsbDeviceProto.VENDOR_ID, device.getVendorId());
        dump.write("product_id", UsbDeviceProto.PRODUCT_ID, device.getProductId());
        dump.write("class", UsbDeviceProto.CLASS, device.getDeviceClass());
        dump.write("subclass", UsbDeviceProto.SUBCLASS, device.getDeviceSubclass());
        dump.write("protocol", UsbDeviceProto.PROTOCOL, device.getDeviceProtocol());
        dump.write("manufacturer_name", UsbDeviceProto.MANUFACTURER_NAME,
                device.getManufacturerName());
        dump.write("product_name", UsbDeviceProto.PRODUCT_NAME, device.getProductName());
        dump.write("version", UsbDeviceProto.VERSION, device.getVersion());
        dump.write("serial_number", UsbDeviceProto.SERIAL_NUMBER, device.getSerialNumber());

        int numConfigurations = device.getConfigurationCount();
        for (int i = 0; i < numConfigurations; i++) {
            writeConfiguration(dump, "configurations", UsbDeviceProto.CONFIGURATIONS,
                    device.getConfiguration(i));
        }

        dump.end(token);
    }

    private static void writeConfiguration(@NonNull DualDumpOutputStream dump,
            @NonNull String idName, long id, @NonNull UsbConfiguration configuration) {
        long token = dump.start(idName, id);
        dump.write("id", UsbConfigurationProto.ID, configuration.getId());
        dump.write("name", UsbConfigurationProto.NAME, configuration.getName());
        dump.write("attributes", UsbConfigurationProto.ATTRIBUTES, configuration.getAttributes());
        dump.write("max_power", UsbConfigurationProto.MAX_POWER, configuration.getMaxPower());

        int numInterfaces = configuration.getInterfaceCount();
        for (int i = 0; i < numInterfaces; i++) {
            writeInterface(dump, "interfaces", UsbConfigurationProto.INTERFACES,
                    configuration.getInterface(i));
        }
        dump.end(token);
    }

    private static void writeInterface(@NonNull DualDumpOutputStream dump, @NonNull String idName,
            long id, @NonNull UsbInterface iface) {
        long token = dump.start(idName, id);

        dump.write("id", UsbInterfaceProto.ID, iface.getId());
        dump.write("alternate_settings", UsbInterfaceProto.ALTERNATE_SETTINGS,
                iface.getAlternateSetting());
        dump.write("name", UsbInterfaceProto.NAME, iface.getName());
        dump.write("class", UsbInterfaceProto.CLASS, iface.getInterfaceClass());
        dump.write("subclass", UsbInterfaceProto.SUBCLASS, iface.getInterfaceSubclass());
        dump.write("protocol", UsbInterfaceProto.PROTOCOL, iface.getInterfaceProtocol());

        int numEndpoints = iface.getEndpointCount();
        for (int i = 0; i < numEndpoints; i++) {
            writeEndpoint(dump, "endpoints", UsbInterfaceProto.ENDPOINTS, iface.getEndpoint(i));
        }
        dump.end(token);
    }

    private static void writeEndpoint(@NonNull DualDumpOutputStream dump, @NonNull String idName,
            long id, @NonNull UsbEndpoint endpoint) {
        long token = dump.start(idName, id);

        dump.write("endpoint_number", UsbEndPointProto.ENDPOINT_NUMBER,
                endpoint.getEndpointNumber());
        dump.write("direction", UsbEndPointProto.DIRECTION, endpoint.getDirection());
        dump.write("address", UsbEndPointProto.ADDRESS, endpoint.getAddress());
        dump.write("type", UsbEndPointProto.TYPE, endpoint.getType());
        dump.write("attributes", UsbEndPointProto.ATTRIBUTES,
                endpoint.getAttributes());
        dump.write("max_packet_size", UsbEndPointProto.MAX_PACKET_SIZE,
                endpoint.getMaxPacketSize());
        dump.write("interval", UsbEndPointProto.INTERVAL, endpoint.getInterval());

        dump.end(token);
    }

    public static void writePort(@NonNull DualDumpOutputStream dump, @NonNull String idName,
            long id, @NonNull UsbPort port) {
        long token = dump.start(idName, id);

        dump.write("id", UsbPortProto.ID, port.getId());

        int mode = port.getSupportedModes();
        if (dump.isProto()) {
            if (mode == MODE_NONE) {
                dump.write("supported_modes", UsbPortProto.SUPPORTED_MODES, MODE_NONE);
            } else {
                if ((mode & MODE_DUAL) == MODE_DUAL) {
                    dump.write("supported_modes", UsbPortProto.SUPPORTED_MODES, MODE_DUAL);
                } else {
                    if ((mode & MODE_DFP) == MODE_DFP) {
                        dump.write("supported_modes", UsbPortProto.SUPPORTED_MODES, MODE_DFP);
                    } else if ((mode & MODE_UFP) == MODE_UFP) {
                        dump.write("supported_modes", UsbPortProto.SUPPORTED_MODES, MODE_UFP);
                    }
                }

                if ((mode & MODE_AUDIO_ACCESSORY) == MODE_AUDIO_ACCESSORY) {
                    dump.write("supported_modes", UsbPortProto.SUPPORTED_MODES,
                            MODE_AUDIO_ACCESSORY);
                }

                if ((mode & MODE_DEBUG_ACCESSORY) == MODE_DEBUG_ACCESSORY) {
                    dump.write("supported_modes", UsbPortProto.SUPPORTED_MODES,
                            MODE_DEBUG_ACCESSORY);
                }
            }
        } else {
            dump.write("supported_modes", UsbPortProto.SUPPORTED_MODES, UsbPort.modeToString(mode));
        }

        dump.end(token);
    }

    private static void writePowerRole(@NonNull DualDumpOutputStream dump, @NonNull String idName,
            long id, int powerRole) {
        if (dump.isProto()) {
            dump.write(idName, id, powerRole);
        } else {
            dump.write(idName, id, UsbPort.powerRoleToString(powerRole));
        }
    }

    private static void writeDataRole(@NonNull DualDumpOutputStream dump, @NonNull String idName,
            long id, int dataRole) {
        if (dump.isProto()) {
            dump.write(idName, id, dataRole);
        } else {
            dump.write(idName, id, UsbPort.dataRoleToString(dataRole));
        }
    }

    private static void writeContaminantPresenceStatus(@NonNull DualDumpOutputStream dump,
            @NonNull String idName, long id, int contaminantPresenceStatus) {
        if (dump.isProto()) {
            dump.write(idName, id, contaminantPresenceStatus);
        } else {
            dump.write(idName, id,
                    UsbPort.contaminantPresenceStatusToString(contaminantPresenceStatus));
        }
    }

    public static void writePortStatus(@NonNull DualDumpOutputStream dump, @NonNull String idName,
            long id, @NonNull UsbPortStatus status) {
        long token = dump.start(idName, id);

        dump.write("connected", UsbPortStatusProto.CONNECTED, status.isConnected());

        if (dump.isProto()) {
            dump.write("current_mode", UsbPortStatusProto.CURRENT_MODE, status.getCurrentMode());
        } else {
            dump.write("current_mode", UsbPortStatusProto.CURRENT_MODE,
                    UsbPort.modeToString(status.getCurrentMode()));
        }

        writePowerRole(dump, "power_role", UsbPortStatusProto.POWER_ROLE,
                status.getCurrentPowerRole());
        writeDataRole(dump, "data_role", UsbPortStatusProto.DATA_ROLE, status.getCurrentDataRole());

        int undumpedCombinations = status.getSupportedRoleCombinations();
        while (undumpedCombinations != 0) {
            int index = Integer.numberOfTrailingZeros(undumpedCombinations);
            undumpedCombinations &= ~(1 << index);

            int powerRole = (index / Constants.PortDataRole.NUM_DATA_ROLES
                    + Constants.PortPowerRole.NONE);
            int dataRole = index % Constants.PortDataRole.NUM_DATA_ROLES;

            long roleCombinationToken = dump.start("role_combinations",
                    UsbPortStatusProto.ROLE_COMBINATIONS);
            writePowerRole(dump, "power_role", UsbPortStatusRoleCombinationProto.POWER_ROLE,
                    powerRole);
            writeDataRole(dump, "data_role", UsbPortStatusRoleCombinationProto.DATA_ROLE,
                    dataRole);
            dump.end(roleCombinationToken);
        }

        writeContaminantPresenceStatus(dump, "contaminant_presence_status",
                UsbPortStatusProto.CONTAMINANT_PRESENCE_STATUS,
                status.getContaminantDetectionStatus());

        dump.end(token);
    }
}
