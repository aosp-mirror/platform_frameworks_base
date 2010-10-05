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

package android.nfc;

import android.nfc.NdefRecord;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.UnsupportedOperationException;

/**
 * NDEF Message data.
 * <p>
 * Immutable data class. An NDEF message always contains zero or more NDEF
 * records.
 */
public class NdefMessage implements Parcelable {
    /**
     * Create an NDEF message from raw bytes.
     * <p>
     * Validation is performed to make sure the Record format headers are valid,
     * and the ID + TYPE + PAYLOAD fields are of the correct size.
     *
     * @hide
     */
    public NdefMessage(byte[] data) {
        throw new UnsupportedOperationException();
    }

    /**
     * Create an NDEF message from NDEF records.
     */
    public NdefMessage(NdefRecord[] records) {
        throw new UnsupportedOperationException();
    }

    /**
     * Get the NDEF records inside this NDEF message.
     *
     * @return array of zero or more NDEF records.
     */
    public NdefRecord[] getRecords() {
        throw new UnsupportedOperationException();
    }

    /**
     * Get a byte array representation of this NDEF message.
     *
     * @return byte array
     * @hide
     */
    public byte[] toByteArray() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        throw new UnsupportedOperationException();
    }

    public static final Parcelable.Creator<NdefMessage> CREATOR =
            new Parcelable.Creator<NdefMessage>() {
        public NdefMessage createFromParcel(Parcel in) {
            throw new UnsupportedOperationException();
        }
        public NdefMessage[] newArray(int size) {
            throw new UnsupportedOperationException();
        }
    };
}