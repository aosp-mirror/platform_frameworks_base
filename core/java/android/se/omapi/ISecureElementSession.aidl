/*
 * Copyright (C) 2017, The Android Open Source Project
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
/*
 * Copyright (c) 2015-2017, The Linux Foundation.
 */
/*
 * Contributed by: Giesecke & Devrient GmbH.
 */

package android.se.omapi;

import android.se.omapi.ISecureElementChannel;
import android.se.omapi.ISecureElementReader;
import android.se.omapi.ISecureElementListener;

/** @hide */
interface ISecureElementSession {

    /**
     * Returns the ATR of the connected card or null if the ATR is not available
     */
    byte[] getAtr();

    /**
     * Close the connection with the Secure Element. This will close any
     * channels opened by this application with this Secure Element.
     */
    void close();

    /**
     * Close any channel opened on this session.
     */
    void closeChannels();


    /**
     * Tells if this session is closed.
     *
     * @return <code>true</code> if the session is closed, false otherwise.
     */
    boolean isClosed();

    /**
     * Opens a connection using the basic channel of the card in the
     * specified reader and returns a channel handle. Selects the specified
     * applet if aid != null.
     * Logical channels cannot be opened with this connection.
     * Use interface method openLogicalChannel() to open a logical channel.
     */
    ISecureElementChannel openBasicChannel(in byte[] aid, in byte p2,
            ISecureElementListener listener);

    /**
     * Opens a connection using the next free logical channel of the card in the
     * specified reader. Selects the specified applet.
     * Selection of other applets with this connection is not supported.
     */
    ISecureElementChannel openLogicalChannel(in byte[] aid, in byte p2,
            ISecureElementListener listener);
}
