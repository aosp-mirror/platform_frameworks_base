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

    // Exception types that can be thrown by NfcService
    // 1:1 mapped to EE_ERROR_ types in NfcService
    private static final int EE_ERROR_IO = -1;
    private static final int EE_ERROR_ALREADY_OPEN = -2;
    private static final int EE_ERROR_INIT = -3;
    private static final int EE_ERROR_LISTEN_MODE = -4;
    private static final int EE_ERROR_EXT_FIELD = -5;
    private static final int EE_ERROR_NFC_DISABLED = -6;

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
     * <p>Opening a channel to the the secure element may fail
     * for a number of reasons:
     * <ul>
     * <li>NFC must be enabled for the connection to the SE to be opened.
     * If it is disabled at the time of this call, an {@link EeNfcDisabledException}
     * is thrown.
     *
     * <li>Only one process may open the secure element at a time. Additionally,
     * this method is not reentrant. If the secure element is already opened,
     * either by this process or by a different process, an {@link EeAlreadyOpenException}
     * is thrown.
     *
     * <li>If the connection to the secure element could not be initialized,
     * an {@link EeInitializationException} is thrown.
     *
     * <li>If the secure element or the NFC controller is activated in listen
     * mode - that is, it is talking over the contactless interface - an
     * {@link EeListenModeException} is thrown.
     *
     * <li>If the NFC controller is in a field powered by a remote device,
     * such as a payment terminal, an {@link EeExternalFieldException} is
     * thrown.
     * </ul>
     * <p>All other NFC functionality is disabled while the NFC-EE is open
     * on its contact interface, so make sure to call {@link #close} once complete.
     *
     * <p class="note">
     * Requires the {@link android.Manifest.permission#WRITE_SECURE_SETTINGS} permission.
     *
     * @throws EeAlreadyOpenException if the NFC-EE is already open
     * @throws EeNfcDisabledException if NFC is disabled
     * @throws EeInitializationException if the Secure Element could not be initialized
     * @throws EeListenModeException if the NFCC or Secure Element is activated in listen mode
     * @throws EeExternalFieldException if the NFCC is in the presence of a remote-powered field
     * @throws EeIoException if an unknown error occurs
     */
    public void open() throws EeIOException {
        try {
            Bundle b = mExtras.getService().open(mExtras.mPackageName, mToken);
            throwBundle(b);
        } catch (RemoteException e) {
            mExtras.attemptDeadServiceRecovery(e);
            throw new EeIOException("NFC Service was dead, try again");
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

    private static void throwBundle(Bundle b) throws EeIOException {
        switch (b.getInt("e")) {
            case EE_ERROR_NFC_DISABLED:
                throw new EeNfcDisabledException(b.getString("m"));
            case EE_ERROR_IO:
                throw new EeIOException(b.getString("m"));
            case EE_ERROR_INIT:
                throw new EeInitializationException(b.getString("m"));
            case EE_ERROR_EXT_FIELD:
                throw new EeExternalFieldException(b.getString("m"));
            case EE_ERROR_LISTEN_MODE:
                throw new EeListenModeException(b.getString("m"));
            case EE_ERROR_ALREADY_OPEN:
                throw new EeAlreadyOpenException(b.getString("m"));
        }
    }
}
