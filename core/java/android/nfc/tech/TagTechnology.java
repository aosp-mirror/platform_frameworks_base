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

import java.io.Closeable;
import java.io.IOException;

/**
 * {@link TagTechnology} is an interface to a technology in a {@link Tag}.
 * <p>
 * Obtain a {@link TagTechnology} implementation by calling the static method <code>get()</code>
 * on the implementation class.
 * <p>
 * NFC tags are based on a number of independently developed technologies and offer a
 * wide range of capabilities. The
 * {@link TagTechnology} implementations provide access to these different
 * technologies and capabilities. Some sub-classes map to technology
 * specification (for example {@link NfcA}, {@link IsoDep}, others map to
 * pseudo-technologies or capabilities (for example {@link Ndef}, {@link NdefFormatable}).
 * <p>
 * It is mandatory for all Android NFC devices to provide the following
 * {@link TagTechnology} implementations.
 * <ul>
 * <li>{@link NfcA} (also known as ISO 14443-3A)
 * <li>{@link NfcB} (also known as ISO 14443-3B)
 * <li>{@link NfcF} (also known as JIS 6319-4)
 * <li>{@link NfcV} (also known as ISO 15693)
 * <li>{@link IsoDep}
 * <li>{@link Ndef} on NFC Forum Type 1, Type 2, Type 3 or Type 4 compliant tags
 * </ul>
 * It is optional for Android NFC devices to provide the following
 * {@link TagTechnology} implementations. If it is not provided, the
 * Android device will never enumerate that class via {@link Tag#getTechList}.
 * <ul>
 * <li>{@link MifareClassic}
 * <li>{@link MifareUltralight}
 * <li>{@link NdefFormatable} must only be enumerated on tags for which this Android device
 * is capable of formatting. Proprietary knowledge is often required to format a tag
 * to make it NDEF compatible.
 * </ul>
 * <p>
 * {@link TagTechnology} implementations provide methods that fall into two classes:
 * <em>cached getters</em> and <em>I/O operations</em>.
 * <h4>Cached getters</h4>
 * These methods (usually prefixed by <code>get</code> or <code>is</code>) return
 * properties of the tag, as determined at discovery time. These methods will never
 * block or cause RF activity, and do not require {@link #connect} to have been called.
 * They also never update, for example if a property is changed by an I/O operation with a tag
 * then the cached getter will still return the result from tag discovery time.
 * <h4>I/O operations</h4>
 * I/O operations may require RF activity, and may block. They have the following semantics.
 * <ul>
 * <li>{@link #connect} must be called before using any other I/O operation.
 * <li>{@link #close} must be called after completing I/O operations with a
 * {@link TagTechnology}, and it will cancel all other blocked I/O operations on other threads
 * (including {@link #connect} with {@link IOException}.
 * <li>Only one {@link TagTechnology} can be connected at a time. Other calls to
 * {@link #connect} will return {@link IOException}.
 * <li>I/O operations may block, and should never be called on the main application
 * thread.
 * </ul>
 *
 * <p class="note"><strong>Note:</strong> Methods that perform I/O operations
 * require the {@link android.Manifest.permission#NFC} permission.
 */
public interface TagTechnology extends Closeable {
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
     * Get the {@link Tag} object backing this {@link TagTechnology} object.
     * @return the {@link Tag} backing this {@link TagTechnology} object.
     */
    public Tag getTag();

    /**
     * Enable I/O operations to the tag from this {@link TagTechnology} object.
     * <p>May cause RF activity and may block. Must not be called
     * from the main application thread. A blocked call will be canceled with
     * {@link IOException} by calling {@link #close} from another thread.
     * <p>Only one {@link TagTechnology} object can be connected to a {@link Tag} at a time.
     * <p>Applications must call {@link #close} when I/O operations are complete.
     *
     * <p class="note">Requires the {@link android.Manifest.permission#NFC} permission.
     *
     * @see #close()
     * @throws TagLostException if the tag leaves the field
     * @throws IOException if there is an I/O failure, or connect is canceled
     */
    public void connect() throws IOException;

    /**
     * Re-connect to the {@link Tag} associated with this connection. Reconnecting to a tag can be
     * used to reset the state of the tag itself.
     *
     * <p>May cause RF activity and may block. Must not be called
     * from the main application thread. A blocked call will be canceled with
     * {@link IOException} by calling {@link #close} from another thread.
     *
     * <p class="note">Requires the {@link android.Manifest.permission#NFC} permission.
     *
     * @see #connect()
     * @see #close()
     * @throws TagLostException if the tag leaves the field
     * @throws IOException if there is an I/O failure, or connect is canceled
     * @hide
     */
    public void reconnect() throws IOException;

    /**
     * Disable I/O operations to the tag from this {@link TagTechnology} object, and release resources.
     * <p>Also causes all blocked I/O operations on other thread to be canceled and
     * return with {@link IOException}.
     *
     * <p class="note">Requires the {@link android.Manifest.permission#NFC} permission.
     *
     * @see #connect()
     */
    public void close() throws IOException;

    /**
     * Helper to indicate if I/O operations should be possible.
     *
     * <p>Returns true if {@link #connect} has completed, and {@link #close} has not been
     * called, and the {@link Tag} is not known to be out of range.
     * <p>Does not cause RF activity, and does not block.
     *
     * @return true if I/O operations should be possible
     */
    public boolean isConnected();
}
