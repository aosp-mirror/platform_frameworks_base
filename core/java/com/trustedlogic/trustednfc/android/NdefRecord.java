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
 * File            : NdefRecord.java
 * Original-Author : Trusted Logic S.A. (Jeremie Corbier)
 * Created         : 05-10-2009
 */

package com.trustedlogic.trustednfc.android;

import android.location.Location;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * An NDEF record as specified by the <a href="http://www.nfc-forum.org/">NFC
 * Forum</a>.
 * 
 * @see NdefMessage
 * 
 * @since AA01.04
 * @hide
 */
public class NdefRecord implements Parcelable {

	/**
	 * Type Name Format - Empty record
	 */
	public static final short TNF_EMPTY = 0x0;

	/**
	 * Type Name Format - NFC Forum-defined type
	 */
	public static final short TNF_WELL_KNOWN_TYPE = 0x1;

	/**
	 * Type Name Format - RFC2045 MIME type
	 */
	public static final short TNF_MIME_MEDIA_TYPE = 0x2;

	/**
	 * Type Name Format - Absolute URI
	 */
	public static final short TNF_ABSOLUTE_URI = 0x3;

	/**
	 * Type Name Format - User-defined type
	 */
	public static final short TNF_EXTERNAL_TYPE = 0x4;

	/**
	 * Type Name Format - Unknown type
	 */
	public static final short TNF_UNKNOWN = 0x5;

	/**
	 * Type Name Format - Unchanged. This TNF is used for chunked records, so
	 * that middle records inherits from the first record's type.
	 */
	public static final short TNF_UNCHANGED = 0x6;

	/**
	 * NFC Forum-defined Type - Smart Poster
	 */
	public static final byte[] TYPE_SMART_POSTER = { 0x53, 0x70 };

	/**
	 * NFC Forum-defined Type - Text
	 */
	public static final byte[] TYPE_TEXT = { 0x54 };

	/**
	 * NFC Forum-defined Type - URI
	 */
	public static final byte[] TYPE_URI = { 0x55 };

	/**
	 * NFC Forum-defined Global Type - Connection Handover Request
	 */
	public static final byte[] TYPE_HANDOVER_REQUEST = { 0x48, 0x72 };

	/**
	 * NFC Forum-defined Global Type - Connection Handover Select
	 */
	public static final byte[] TYPE_HANDOVER_SELECT = { 0x48, 0x73 };

	/**
	 * NFC Forum-defined Global Type - Connection Handover Carrier
	 */
	public static final byte[] TYPE_HANDOVER_CARRIER = { 0x48, 0x63 };

	/**
	 * NFC Forum-defined Local Type - Alternative Carrier
	 */
	public static final byte[] TYPE_ALTERNATIVE_CARRIER = { 0x61, 0x63 };

	/* Flag values */
	private static final int FLAG_MB = 0x80;
	private static final int FLAG_ME = 0x40;
	private static final int FLAG_CF = 0x20;
	private static final int FLAG_SR = 0x10;
	private static final int FLAG_IL = 0x08;

	/**
	 * Record Flags
	 */
	private short mFlags = 0;

	/**
	 * Record Type Name Format
	 */
	private short mTnf = 0;

	/**
	 * Record Type
	 */
	private byte[] mType = null;

	/**
	 * Record Identifier
	 */
	private byte[] mId = null;

	/**
	 * Record Payload
	 */
	private byte[] mPayload = null;

	/**
	 * Creates an NdefRecord given its Type Name Format, its type, its id and
	 * its.
	 * 
	 * @param tnf
	 *            Type Name Format
	 * @param type
	 *            record type
	 * @param id
	 *            record id (optional, can be null)
	 * @param data
	 *            record payload
	 */
	public NdefRecord(short tnf, byte[] type, byte[] id, byte[] data) {
		
		/* generate flag */
		mFlags = FLAG_MB | FLAG_ME;
		
		/* Determine if it is a short record */
		if(data.length < 0xFF)
		{
			mFlags |= FLAG_SR;
		}
		
		/* Determine if an id is present */
		if(id.length != 0)
		{
			mFlags |= FLAG_IL;
		}
		
		mTnf = tnf;
		mType = (byte[]) type.clone();
		mId = (byte[]) id.clone();
		mPayload = (byte[]) data.clone();
	}

	/**
	 * Appends data to the record's payload.
	 * 
	 * @param data
	 *            Data to be added to the record.
	 */
	public void appendPayload(byte[] data) {
		byte[] newPayload = new byte[mPayload.length + data.length];

		System.arraycopy(mPayload, 0, newPayload, 0, mPayload.length);
		System.arraycopy(data, 0, newPayload, mPayload.length, data.length);

		mPayload = newPayload;
	}

	/**
	 * Returns record as a byte array.
	 * 
	 * @return record as a byte array.
	 */
	public byte[] toByteArray() {
		return generate(mFlags, mTnf, mType, mId, mPayload);
	}

	private native byte[] generate(short flags, short tnf, byte[] type,
			byte[] id, byte[] data);

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
        dest.writeInt(mTnf);
        dest.writeInt(mType.length);
        dest.writeByteArray(mType);
        dest.writeInt(mId.length);
        dest.writeByteArray(mId);
        dest.writeInt(mPayload.length);
        dest.writeByteArray(mPayload);
    }

    /**
     * Creator class, needed when implementing from Parcelable
     * {@hide}
     */
    public static final Parcelable.Creator<NdefRecord> CREATOR = new Parcelable.Creator<NdefRecord>() {
        public NdefRecord createFromParcel(Parcel in) {
            // TNF
            short tnf = (short)in.readInt();
            // Type
            int typeLength = in.readInt();
            byte[] type = new byte[typeLength];
            in.readByteArray(type);
            // ID
            int idLength = in.readInt();
            byte[] id = new byte[idLength];
            in.readByteArray(id);
            // Payload
            int payloadLength = in.readInt();
            byte[] payload = new byte[payloadLength];
            in.readByteArray(payload);
            
            return new NdefRecord(tnf, type, id, payload);
        }

        public NdefRecord[] newArray(int size) {
            return new NdefRecord[size];
        }
    };
    
    /**
     * Returns record TNF
     * 
     * @return mTnf
     */
    public int getTnf(){
    	return mTnf;
    }
    
    /**
     * Returns record TYPE
     * 
     * @return mType
     */
    public byte[] getType(){
    	return mType;
    }
   
    /**
     * Returns record ID
     * 
     * @return mId
     */
    public byte[] getId(){
    	return mId;
    }
    
    /**
     * Returns record Payload
     * 
     * @return mPayload
     */
    public byte[] getPayload(){
    	return mPayload;
    }
    
}
