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

package android.nfc.technology;

import android.nfc.Tag;

import java.io.IOException;

public interface TagTechnology {
    /**
     * This object is an instance of {@link NfcA}
     */
    public static final int NFC_A = 1;

    /**
     * This object is an instance of {@link NfcB}
     */
    public static final int NFC_B = 2;

    /**
     * This object is an instance of {@link IsoDep}
     */
    public static final int ISO_DEP = 3;

    /**
     * This object is an instance of {@link NfcF}
     */
    public static final int NFC_F = 11;

    /**
     * This object is an instance of {@link NfcV}
     */
    public static final int NFC_V = 21;

    /**
     * This object is an instance of {@link Ndef}
     */
    public static final int TYPE_1 = 101;

    /**
     * This object is an instance of {@link Ndef}
     */
    public static final int TYPE_2 = 102;

    /**
     * This object is an instance of {@link Ndef}
     */
    public static final int TYPE_3 = 103;

    /**
     * This object is an instance of {@link Ndef}
     */
    public static final int TYPE_4 = 104;

    /**
     * This object is an instance of {@link MifareClassic}
     */
    public static final int MIFARE_CLASSIC = 200;

    /**
     * A Mifare Classic tag with NDEF data
     */
    public static final int MIFARE_CLASSIC_NDEF = 201;

    /**
     * A Mifare Ultralight tag
     */
    public static final int MIFARE_ULTRALIGHT = 202;

    /**
     * A Mifare DESFire tag
     */
    public static final int MIFARE_DESFIRE = 203;

    /**
     * Returns the technology type for this tag connection.
     */
    public int getTechnologyId();

    /**
     * Get the backing tag object.
     */
    public Tag getTag();

    /**
     * @throws IOException
     */
    public void connect() throws IOException;

    /**
     * Non-blocking. Immediately causes all blocking calls
     * to throw IOException.
     */
    public void close();
}
