/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.nfc.tech;

import android.nfc.Tag;

import java.io.IOException;

public interface TagTechnology {
    /**
     * This technology is an instance of {@link NfcA}.
     * <p>Support for this technology type is mandatory.
     * @hide
     */
    public static final int NFC_A = 1;

    /**
     * This technology is an instance of {@link NfcB}.
     * <p>Support for this technology type is mandatory.
     * @hide
     */
    public static final int NFC_B = 2;

    /**
     * This technology is an instance of {@link IsoDep}.
     * <p>Support for this technology type is mandatory.
     * @hide
     */
    public static final int ISO_DEP = 3;

    /**
     * This technology is an instance of {@link NfcF}.
     * <p>Support for this technology type is mandatory.
     * @hide
     */
    public static final int NFC_F = 4;

    /**
     * This technology is an instance of {@link NfcV}.
     * <p>Support for this technology type is mandatory.
     * @hide
     */
    public static final int NFC_V = 5;

    /**
     * This technology is an instance of {@link Ndef}.
     * <p>Support for this technology type is mandatory.
     * @hide
     */
    public static final int NDEF = 6;

    /**
     * This technology is an instance of {@link NdefFormatable}.
     * <p>Support for this technology type is mandatory.
     * @hide
     */
    public static final int NDEF_FORMATABLE = 7;

    /**
     * This technology is an instance of {@link MifareClassic}.
     * <p>Support for this technology type is optional. If a stack doesn't support this technology
     * type tags using it must still be discovered and present the lower level radio interface
     * technologies in use.
     * @hide
     */
    public static final int MIFARE_CLASSIC = 8;

    /**
     * This technology is an instance of {@link MifareUltralight}.
     * <p>Support for this technology type is optional. If a stack doesn't support this technology
     * type tags using it must still be discovered and present the lower level radio interface
     * technologies in use.
     * @hide
     */
    public static final int MIFARE_ULTRALIGHT = 9;

    /**
     * Get the {@link Tag} object this technology came from.
     */
    public Tag getTag();

    /**
     * Opens a connection to the {@link Tag} enabling interactive commands. The command set
     * varies by the technology type.
     *
     * <p>This method blocks until the connection has been established.
     *
     * <p>A call to {@link #close} from another thread will cancel a blocked call and cause an
     * IOException to be thrown on the thread that is blocked.
     *
     * @see #reconnect()
     * @see #close()
     * @throws IOException if the target is lost, or connect canceled
     */
    public void connect() throws IOException;

    /**
     * Re-connect to the {@link Tag} associated with this connection. Reconnecting to a tag can be
     * used to reset the state of the tag itself.
     *
     * <p>This method blocks until the connection is re-established.
     *
     * <p>A call to {@link #close} from another thread will cancel a blocked call and cause an
     * IOException to be thrown on the thread that is blocked.
     *
     * @see #connect()
     * @see #close()
     * @throws IOException
     */
    public void reconnect() throws IOException;

    /**
     * Closes the connection to the {@link Tag}. This call is non-blocking and causes all blocking
     * operations such as {@link #connect} to be canceled and immediately throw
     * {@link java.io.IOException} on the thread that is blocked.
     *
     * <p>
     * Once this method is called, this object cannot be re-used and should be discarded. Further
     * calls to {@link #connect} will fail.
     *
     * @see #connect()
     * @see #reconnect()
     */
    public void close();
}
