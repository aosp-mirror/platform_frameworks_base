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

/**
 * File            : NDEFMessage.java
 * Original-Author : Trusted Logic S.A. (Jeremie Corbier)
 * Created         : 05-10-2009
 */

package com.trustedlogic.trustednfc.android;

import java.util.LinkedList;
import java.util.ListIterator;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Represents an NDEF message as specified by the <a
 * href="http://www.nfc-forum.org/">NFC Forum</a>.
 * 
 * @see NdefRecord
 * 
 * @since AA01.04
 * @hide
 */
public class NdefMessage implements Parcelable {
	/* Flag values */
	private static final int FLAG_MB = 0x80;
	private static final int FLAG_ME = 0x40;
	private static final int FLAG_CF = 0x20;
	private static final int FLAG_SR = 0x10;
	private static final int FLAG_IL = 0x08;

	/**
	 * Array of {@link NdefRecord} composing this message.
	 */
	private NdefRecord[] mRecords;

	/**
	 * Builds an NDEF message.
	 * 
	 * @param data raw NDEF message data
	 * 
	 * @throws NFCException
	 */
	public NdefMessage(byte[] data) throws NfcException {
		if (parseNdefMessage(data) == -1)
			throw new NfcException("Error while parsing NDEF message");
	}

	/**
	 * Builds an NDEF message.
	 * 
	 * @param records
	 *            an array of already created NDEF records
	 */
	public NdefMessage(NdefRecord[] records) {
		mRecords = new NdefRecord[records.length];

		System.arraycopy(records, 0, mRecords, 0, records.length);
	}

	/**
	 * Returns the NDEF message as a byte array.
	 * 
	 * @return the message as a byte array
	 */
	public byte[] toByteArray() {
		if ((mRecords == null) || (mRecords.length == 0))
			return null;

		byte[] msg = {};

		for (int i = 0; i < mRecords.length; i++) {
			byte[] record = mRecords[i].toByteArray();
			byte[] tmp = new byte[msg.length + record.length];

			/* Make sure the Message Begin flag is set only for the first record */
			if (i == 0)
				record[0] |= FLAG_MB;
			else
				record[0] &= ~FLAG_MB;

			/* Make sure the Message End flag is set only for the last record */
			if (i == (mRecords.length - 1))
				record[0] |= FLAG_ME;
			else
				record[0] &= ~FLAG_ME;

			System.arraycopy(msg, 0, tmp, 0, msg.length);
			System.arraycopy(record, 0, tmp, msg.length, record.length);

			msg = tmp;
		}

		return msg;
	}
	
	/**
	* Returns an array of {@link NdefRecord} composing this message.
	*
	* @return mRecords
	* 
	* @since AA02.01
	*/
	public NdefRecord[] getRecords(){
		return mRecords;
	}
	
	private native int parseNdefMessage(byte[] data);

    /**
     * (Parcelable) Describe the parcel
     * {@hide}
     */
    public int describeContents() {
        return 0;
    }

    /**
     * (Parcelable) Convert current object to a Parcel
     * {@hide}
     */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mRecords.length);
        dest.writeTypedArray(mRecords, 0);
    }

    /**
     * Creator class, needed when implementing from Parcelable
     * {@hide}
     */
    public static final Parcelable.Creator<NdefMessage> CREATOR = new Parcelable.Creator<NdefMessage>() {
        public NdefMessage createFromParcel(Parcel in) {
            int recordsLength = in.readInt();
            NdefRecord[] records = new NdefRecord[recordsLength];
            in.readTypedArray(records, NdefRecord.CREATOR);
            return new NdefMessage(records);
        }

        public NdefMessage[] newArray(int size) {
            return new NdefMessage[size];
        }
    };
    
}
