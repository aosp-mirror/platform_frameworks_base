/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.bluetooth.le.ScanResult;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This class provides a set of callbacks that are invoked when scanning for Broadcast Sources is
 * offloaded to a Broadcast Assistant.
 *
 * <p>An LE Audio Broadcast Assistant can help a Broadcast Sink to scan for available Broadcast
 * Sources. The Broadcast Sink achieves this by offloading the scan to a Broadcast Assistant. This
 * is facilitated by the Broadcast Audio Scan Service (BASS). A BASS server is a GATT server that is
 * part of the Scan Delegator on a Broadcast Sink. A BASS client instead runs on the Broadcast
 * Assistant.
 *
 * <p>Once a GATT connection is established between the BASS client and the BASS server, the
 * Broadcast Sink can offload the scans to the Broadcast Assistant. Upon finding new Broadcast
 * Sources, the Broadcast Assistant then notifies the Broadcast Sink about these over the
 * established GATT connection. The Scan Delegator on the Broadcast Sink can also notify the
 * Assistant about changes such as addition and removal of Broadcast Sources.
 *
 * @hide
 */
public abstract class BluetoothLeBroadcastAssistantCallback {

    /**
     * Broadcast Audio Scan Service (BASS) codes returned by a BASS Server
     *
     * @hide
     */
    @IntDef(
            prefix = "BASS_STATUS_",
            value = {
                BASS_STATUS_SUCCESS,
                BASS_STATUS_FAILURE,
                BASS_STATUS_INVALID_GATT_HANDLE,
                BASS_STATUS_TXN_TIMEOUT,
                BASS_STATUS_INVALID_SOURCE_ID,
                BASS_STATUS_COLOCATED_SRC_UNAVAILABLE,
                BASS_STATUS_INVALID_SOURCE_SELECTED,
                BASS_STATUS_SOURCE_UNAVAILABLE,
                BASS_STATUS_DUPLICATE_ADDITION,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface BassStatus {}

    public static final int BASS_STATUS_SUCCESS = 0x00;
    public static final int BASS_STATUS_FAILURE = 0x01;
    public static final int BASS_STATUS_INVALID_GATT_HANDLE = 0x02;
    public static final int BASS_STATUS_TXN_TIMEOUT = 0x03;

    public static final int BASS_STATUS_INVALID_SOURCE_ID = 0x04;
    public static final int BASS_STATUS_COLOCATED_SRC_UNAVAILABLE = 0x05;
    public static final int BASS_STATUS_INVALID_SOURCE_SELECTED = 0x06;
    public static final int BASS_STATUS_SOURCE_UNAVAILABLE = 0x07;
    public static final int BASS_STATUS_DUPLICATE_ADDITION = 0x08;
    public static final int BASS_STATUS_NO_EMPTY_SLOT = 0x09;
    public static final int BASS_STATUS_INVALID_GROUP_OP = 0x10;

    /**
     * Callback invoked when a new LE Audio Broadcast Source is found.
     *
     * @param result {@link ScanResult} scan result representing a Broadcast Source
     */
    public void onBluetoothLeBroadcastSourceFound(@NonNull ScanResult result) {}

    /**
     * Callback invoked when the Broadcast Assistant synchronizes with Periodic Advertisements (PAs)
     * of an LE Audio Broadcast Source.
     *
     * @param source the selected Broadcast Source
     */
    public void onBluetoothLeBroadcastSourceSelected(
            @NonNull BluetoothLeBroadcastSourceInfo source, @BassStatus int status) {}

    /**
     * Callback invoked when the Broadcast Assistant loses synchronization with an LE Audio
     * Broadcast Source.
     *
     * @param source the Broadcast Source with which synchronization was lost
     */
    public void onBluetoothLeBroadcastSourceLost(
            @NonNull BluetoothLeBroadcastSourceInfo source, @BassStatus int status) {}

    /**
     * Callback invoked when a new LE Audio Broadcast Source has been successfully added to the Scan
     * Delegator (within a Broadcast Sink, for example).
     *
     * @param sink Scan Delegator device on which a new Broadcast Source has been added
     * @param source the added Broadcast Source
     */
    public void onBluetoothLeBroadcastSourceAdded(
            @NonNull BluetoothDevice sink,
            @NonNull BluetoothLeBroadcastSourceInfo source,
            @BassStatus int status) {}

    /**
     * Callback invoked when an existing LE Audio Broadcast Source within a remote Scan Delegator
     * has been updated.
     *
     * @param sink Scan Delegator device on which a Broadcast Source has been updated
     * @param source the updated Broadcast Source
     */
    public void onBluetoothLeBroadcastSourceUpdated(
            @NonNull BluetoothDevice sink,
            @NonNull BluetoothLeBroadcastSourceInfo source,
            @BassStatus int status) {}

    /**
     * Callback invoked when an LE Audio Broadcast Source has been successfully removed from the
     * Scan Delegator (within a Broadcast Sink, for example).
     *
     * @param sink Scan Delegator device from which a Broadcast Source has been removed
     * @param source the removed Broadcast Source
     */
    public void onBluetoothLeBroadcastSourceRemoved(
            @NonNull BluetoothDevice sink,
            @NonNull BluetoothLeBroadcastSourceInfo source,
            @BassStatus int status) {}
}
