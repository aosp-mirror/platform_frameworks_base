/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.nfc_extras;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.os.Binder;
import android.os.Bundle;
import android.os.RemoteException;

import java.io.IOException;

public class NfcExecutionEnvironment {
    private final NfcAdapterExtras mExtras;
    private final Binder mToken;

    /**
     * Broadcast Action: An ISO-DEP AID was selected.
     *
     * <p>This happens as the result of a 'SELECT AID' command from an
     * external NFC reader/writer.
     *
     * <p>Always contains the extra field {@link #EXTRA_AID}
     *
     * <p class="note">
     * Requires the {@link android.Manifest.permission#WRITE_SECURE_SETTINGS} permission
     * to receive.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_AID_SELECTED =
        "com.android.nfc_extras.action.AID_SELECTED";

    /**
     * Mandatory byte array extra field in {@link #ACTION_AID_SELECTED}.
     *
     * <p>Contains the AID selected.
     * @hide
     */
    public static final String EXTRA_AID = "com.android.nfc_extras.extra.AID";

    /**
     * Broadcast action: A filtered APDU was received.
     *
     * <p>This happens when an APDU of interest was matched by the Nfc adapter,
     * for instance as the result of matching an externally-configured filter.
     *
     * <p>The filter configuration mechanism is not currently defined.
     *
     * <p>Always contains the extra field {@link EXTRA_APDU_BYTES}.
     *
     * @hide
     */
    public static final String ACTION_APDU_RECEIVED =
        "com.android.nfc_extras.action.APDU_RECEIVED";

    /**
     * Mandatory byte array extra field in {@link #ACTION_APDU_RECEIVED}.
     *
     * <p>Contains the bytes of the received APDU.
     *
     * @hide
     */
    public static final String EXTRA_APDU_BYTES =
        "com.android.nfc_extras.extra.APDU_BYTES";

    /**
     * Broadcast action: An EMV card removal event was detected.
     *
     * @hide
     */
    public static final String ACTION_EMV_CARD_REMOVAL =
        "com.android.nfc_extras.action.EMV_CARD_REMOVAL";

    /**
     * Broadcast action: An adapter implementing MIFARE Classic via card
     * emulation detected that a block has been accessed.
     *
     * <p>This may only be issued for the first block that the reader
     * authenticates to.
     *
     * <p>May contain the extra field {@link #EXTRA_MIFARE_BLOCK}.
     *
     * @hide
     */
    public static final String ACTION_MIFARE_ACCESS_DETECTED =
        "com.android.nfc_extras.action.MIFARE_ACCESS_DETECTED";

    /**
     * Optional integer extra field in {@link #ACTION_MIFARE_ACCESS_DETECTED}.
     *
     * <p>Provides the block number being accessed.  If not set, the block
     * number being accessed is unknown.
     *
     * @hide
     */
    public static final String EXTRA_MIFARE_BLOCK =
        "com.android.nfc_extras.extra.MIFARE_BLOCK";

    NfcExecutionEnvironment(NfcAdapterExtras extras) {
        mExtras = extras;
        mToken = new Binder();
    }

    /**
     * Open the NFC Execution Environment on its contact interface.
     *
     * <p>Only one process may open the secure element at a time. If it is
     * already open, an {@link IOException} is thrown.
     *
     * <p>All other NFC functionality is disabled while the NFC-EE is open
     * on its contact interface, so make sure to call {@link #close} once complete.
     *
     * <p class="note">
     * Requires the {@link android.Manifest.permission#WRITE_SECURE_SETTINGS} permission.
     *
     * @throws IOException if the NFC-EE is already open, or some other error occurs
     */
    public void open() throws IOException {
        try {
            Bundle b = mExtras.getService().open(mExtras.mPackageName, mToken);
            throwBundle(b);
        } catch (RemoteException e) {
            mExtras.attemptDeadServiceRecovery(e);
            throw new IOException("NFC Service was dead, try again");
        }
    }

    /**
     * Close the NFC Execution Environment on its contact interface.
     *
     * <p class="note">
     * Requires the {@link android.Manifest.permission#WRITE_SECURE_SETTINGS} permission.
     *
     * @throws IOException if the NFC-EE is already open, or some other error occurs
     */
    public void close() throws IOException {
        try {
            throwBundle(mExtras.getService().close(mExtras.mPackageName, mToken));
        } catch (RemoteException e) {
            mExtras.attemptDeadServiceRecovery(e);
            throw new IOException("NFC Service was dead");
        }
    }

    /**
     * Send raw commands to the NFC-EE and receive the response.
     *
     * <p class="note">
     * Requires the {@link android.Manifest.permission#WRITE_SECURE_SETTINGS} permission.
     *
     * @throws IOException if the NFC-EE is not open, or some other error occurs
     */
    public byte[] transceive(byte[] in) throws IOException {
        Bundle b;
        try {
            b = mExtras.getService().transceive(mExtras.mPackageName, in);
        } catch (RemoteException e) {
            mExtras.attemptDeadServiceRecovery(e);
            throw new IOException("NFC Service was dead, need to re-open");
        }
        throwBundle(b);
        return b.getByteArray("out");
    }

    private static void throwBundle(Bundle b) throws IOException {
        if (b.getInt("e") == -1) {
            throw new IOException(b.getString("m"));
        }
    }
}
