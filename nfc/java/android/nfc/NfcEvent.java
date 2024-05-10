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

package android.nfc;

/**
 * Wraps information associated with any NFC event.
 *
 * <p>Immutable object, with direct access to the (final) fields.
 *
 * <p>An {@link NfcEvent} object is usually included in callbacks from
 * {@link NfcAdapter}. Check the documentation of the callback to see
 * which fields may be set.
 *
 * <p>This wrapper object is used (instead of parameters
 * in the callback) because it allows new fields to be added without breaking
 * API compatibility.
 *
 * @see NfcAdapter.OnNdefPushCompleteCallback#onNdefPushComplete
 * @see NfcAdapter.CreateNdefMessageCallback#createNdefMessage
 */
public final class NfcEvent {
    /**
     * The {@link NfcAdapter} associated with the NFC event.
     */
    public final NfcAdapter nfcAdapter;

    /**
     * The major LLCP version number of the peer associated with the NFC event.
     */
    public final int peerLlcpMajorVersion;

    /**
     * The minor LLCP version number of the peer associated with the NFC event.
     */
    public final int peerLlcpMinorVersion;

    NfcEvent(NfcAdapter nfcAdapter, byte peerLlcpVersion) {
        this.nfcAdapter = nfcAdapter;
        this.peerLlcpMajorVersion = (peerLlcpVersion & 0xF0) >> 4;
        this.peerLlcpMinorVersion = peerLlcpVersion & 0x0F;
    }
}
