/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.bluetooth;

import android.bluetooth.RfcommSocket;

import android.util.Log;

import java.io.*;
import java.util.*;

/**
 * The Android Bluetooth API is not finalized, and *will* change. Use at your
 * own risk.
 * 
 * A low-level API to the Service Discovery Protocol (SDP) Database.
 *
 * Allows service records to be added to the local SDP database. Once added,
 * these services will be advertised to remote devices when they make SDP
 * queries on this device.
 *
 * Currently this API is a thin wrapper to the bluez SDP Database API. See:
 * http://wiki.bluez.org/wiki/Database
 * http://wiki.bluez.org/wiki/HOWTO/ManagingServiceRecords
 * @hide
 */
public final class Database {
    private static Database mInstance;

    private static final String sLogName = "android.bluetooth.Database";

    /**
     * Class load time initialization
     */
    static {
        classInitNative();
    }
    private native static void classInitNative();

    /**
     * Private to enforce singleton property
     */
    private Database() {
        initializeNativeDataNative();
    }
    private native void initializeNativeDataNative();

    protected void finalize() throws Throwable {
        try {
            cleanupNativeDataNative();
        } finally {
            super.finalize();
        }
    }
    private native void cleanupNativeDataNative();

    /**
     * Singelton accessor
     * @return The singleton instance of Database
     */
    public static synchronized Database getInstance() {
        if (mInstance == null) {
            mInstance = new Database();
        }
        return mInstance;
    }

    /**
     * Advertise a service with an RfcommSocket.
     *
     * This adds the service the SDP Database with the following attributes
     * set: Service Name, Protocol Descriptor List, Service Class ID List
     * TODO: Construct a byte[] record directly, rather than via XML.
     * @param       socket The rfcomm socket to advertise (by channel).
     * @param       serviceName A short name for this service
     * @param       uuid
     *                  Unique identifier for this service, by which clients
     *                  can search for your service
     * @return      Handle to the new service record
     */
    public int advertiseRfcommService(RfcommSocket socket,
                                      String serviceName,
                                      UUID uuid) throws IOException {
        String xmlRecord =
                "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n" +
                "<record>\n" +
                "  <attribute id=\"0x0001\">\n" + // ServiceClassIDList
                "    <sequence>\n" +
                "      <uuid value=\""
                        + uuid.toString() +       // UUID for this service
                        "\"/>\n" +
                "    </sequence>\n" +
                "  </attribute>\n" +
                "  <attribute id=\"0x0004\">\n" + // ProtocolDescriptorList
                "    <sequence>\n" +
                "      <sequence>\n" +
                "        <uuid value=\"0x0100\"/>\n" +  // L2CAP
                "      </sequence>\n" +
                "      <sequence>\n" +
                "        <uuid value=\"0x0003\"/>\n" +  // RFCOMM
                "        <uint8 value=\"" +
                        socket.getPort() +              // RFCOMM port
                        "\" name=\"channel\"/>\n" +
                "      </sequence>\n" +
                "    </sequence>\n" +
                "  </attribute>\n" +
                "  <attribute id=\"0x0100\">\n" + // ServiceName
                "    <text value=\"" + serviceName + "\"/>\n" +
                "  </attribute>\n" +
                "</record>\n";
        Log.i(sLogName, xmlRecord);
        return addServiceRecordFromXml(xmlRecord);
    }


    /**
     * Add a new service record.
     * @param record The byte[] record
     * @return       A handle to the new record
     */
    public synchronized int addServiceRecord(byte[] record) throws IOException {
        int handle = addServiceRecordNative(record);
        Log.i(sLogName, "Added SDP record: " + Integer.toHexString(handle));
        return handle;
    }
    private native int addServiceRecordNative(byte[] record)
            throws IOException;

    /**
     * Add a new service record, using XML.
     * @param record The record as an XML string
     * @return       A handle to the new record
     */
    public synchronized int addServiceRecordFromXml(String record) throws IOException {
        int handle = addServiceRecordFromXmlNative(record);
        Log.i(sLogName, "Added SDP record: " + Integer.toHexString(handle));
        return handle;
    }
    private native int addServiceRecordFromXmlNative(String record)
            throws IOException;

    /**
     * Update an exisiting service record.
     * @param handle Handle to exisiting record
     * @param record The updated byte[] record
     */
    public synchronized void updateServiceRecord(int handle, byte[] record) {
        try {
            updateServiceRecordNative(handle, record);
        } catch (IOException e) {
            Log.e(getClass().toString(), e.getMessage());
        }
    }
    private native void updateServiceRecordNative(int handle, byte[] record)
            throws IOException;

    /**
     * Update an exisiting record, using XML.
     * @param handle Handle to exisiting record
     * @param record The record as an XML string.
     */
    public synchronized void updateServiceRecordFromXml(int handle, String record) {
        try {
            updateServiceRecordFromXmlNative(handle, record);
        } catch (IOException e) {
            Log.e(getClass().toString(), e.getMessage());
        }
    }
    private native void updateServiceRecordFromXmlNative(int handle, String record)
            throws IOException;

    /**
     * Remove a service record.
     * It is only possible to remove service records that were added by the
     * current connection.
     * @param handle Handle to exisiting record to be removed
     */
    public synchronized void removeServiceRecord(int handle) {
        try {
            removeServiceRecordNative(handle);
        } catch (IOException e) {
            Log.e(getClass().toString(), e.getMessage());
        }
    }
    private native void removeServiceRecordNative(int handle) throws IOException;
}
