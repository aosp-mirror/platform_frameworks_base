/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony.gsm;

import com.android.internal.telephony.*;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Message;
import android.os.Handler;
import android.os.Looper;
import android.os.AsyncResult;
import android.util.Log;
import android.telephony.PhoneNumberUtils;
import java.util.ArrayList;

class AdnRecordLoader extends Handler
{
    static final String LOG_TAG = "GSM";

    //***** Instance Variables

    GSMPhone phone;
    int ef;
    int extensionEF;
    int pendingExtLoads;
    Message userResponse;
    String pin2;

    // For "load one"
    int recordNumber;

    // for "load all"
    ArrayList<AdnRecord> adns; // only valid after EVENT_ADN_LOAD_ALL_DONE

    // Either an AdnRecord or a reference to adns depending
    // if this is a load one or load all operation
    Object result;

    //***** Event Constants

    static final int EVENT_ADN_LOAD_DONE = 1;
    static final int EVENT_EXT_RECORD_LOAD_DONE = 2;
    static final int EVENT_ADN_LOAD_ALL_DONE = 3;
    static final int EVENT_EF_LINEAR_RECORD_SIZE_DONE = 4;
    static final int EVENT_UPDATE_RECORD_DONE = 5;

    //***** Constructor

    AdnRecordLoader(GSMPhone phone)
    {
        // The telephony unit-test cases may create AdnRecords
        // in secondary threads
        super(phone.h.getLooper());

        this.phone = phone;
    }

    /**
     * Resulting AdnRecord is placed in response.obj.result
     * or response.obj.exception is set
     */
    void
    loadFromEF(int ef, int extensionEF, int recordNumber, 
                Message response)
    {
        this.ef = ef;
        this.extensionEF = extensionEF;
        this.recordNumber = recordNumber;
        this.userResponse = response;
        
        phone.mSIMFileHandler.loadEFLinearFixed(
                    ef, recordNumber, 
                    obtainMessage(EVENT_ADN_LOAD_DONE)); 

    }


    /**
     * Resulting ArrayList&lt;adnRecord> is placed in response.obj.result
     * or response.obj.exception is set
     */
    void
    loadAllFromEF(int ef, int extensionEF, 
                Message response)
    {
        this.ef = ef;
        this.extensionEF = extensionEF;
        this.userResponse = response;
        
        phone.mSIMFileHandler.loadEFLinearFixedAll(
                    ef, 
                    obtainMessage(EVENT_ADN_LOAD_ALL_DONE)); 

    }

    /**
     * Write adn to a EF SIM record
     * It will get the record size of EF record and compose hex adn array
     * then write the hex array to EF record
     *
     * @param adn is set with alphaTag and phoneNubmer
     * @param ef EF fileid
     * @param extensionEF extension EF fileid
     * @param recordNumber 1-based record index
     * @param pin2 for CHV2 operations, must be null if pin2 is not needed
     * @param response will be sent to its handler when completed
     */
    void
    updateEF(AdnRecord adn, int ef, int extensionEF, int recordNumber,
            String pin2, Message response)
    {
        this.ef = ef;
        this.extensionEF = extensionEF;
        this.recordNumber = recordNumber;
        this.userResponse = response;
        this.pin2 = pin2;

        phone.mSIMFileHandler.getEFLinearRecordSize( ef,
            obtainMessage(EVENT_EF_LINEAR_RECORD_SIZE_DONE, adn));
    }

    //***** Overridden from Handler

    public void 
    handleMessage(Message msg)
    {
        AsyncResult ar;
        byte data[];
        AdnRecord adn;

        try {
            switch (msg.what) {
                case EVENT_EF_LINEAR_RECORD_SIZE_DONE:
                    ar = (AsyncResult)(msg.obj);
                    adn = (AdnRecord)(ar.userObj);

                    if (ar.exception != null) {
                        throw new RuntimeException("get EF record size failed",
                                ar.exception);
                    }

                    int[] recordSize = (int[])ar.result;
                    // recordSize is int[3] array
                    // int[0]  is the record length
                    // int[1]  is the total length of the EF file
                    // int[2]  is the number of records in the EF file
                    // So int[0] * int[2] = int[1]
                   if (recordSize.length != 3 || recordNumber > recordSize[2]) {
                        throw new RuntimeException("get wrong EF record size format",
                                ar.exception);
                    }

                    data = adn.buildAdnString(recordSize[0]);

                    if(data == null) {
                        throw new RuntimeException("worong ADN format",
                                ar.exception);
                    }

                    phone.mSIMFileHandler.updateEFLinearFixed(ef, recordNumber,
                            data, pin2, obtainMessage(EVENT_UPDATE_RECORD_DONE));

                    pendingExtLoads = 1;

                    break;
                case EVENT_UPDATE_RECORD_DONE:
                    ar = (AsyncResult)(msg.obj);
                    if (ar.exception != null) {
                        throw new RuntimeException("update EF adn record failed",
                                ar.exception);
                    }
                    pendingExtLoads = 0;
                    result = null;
                    break;
                case EVENT_ADN_LOAD_DONE:
                    ar = (AsyncResult)(msg.obj);
                    data = (byte[])(ar.result);
     
                    if (ar.exception != null) {
                        throw new RuntimeException("load failed", ar.exception);
                    }

                    if (false) {
                        Log.d(LOG_TAG,"ADN EF: 0x" 
                            + Integer.toHexString(ef)
                            + ":" + recordNumber
                            + "\n" + SimUtils.bytesToHexString(data));
                    }
                    
                    adn = new AdnRecord(ef, recordNumber, data);
                    result = adn;

                    if (adn.hasExtendedRecord()) {
                        // If we have a valid value in the ext record field,
                        // we're not done yet: we need to read the corresponding
                        // ext record and append it

                        pendingExtLoads = 1;
                        
                        phone.mSIMFileHandler.loadEFLinearFixed(
                            extensionEF, adn.extRecord, 
                            obtainMessage(EVENT_EXT_RECORD_LOAD_DONE, adn)); 
                    }
                break;

                case EVENT_EXT_RECORD_LOAD_DONE:
                    ar = (AsyncResult)(msg.obj);
                    data = (byte[])(ar.result);
                    adn = (AdnRecord)(ar.userObj);
     
                    if (ar.exception != null) {
                        throw new RuntimeException("load failed", ar.exception);
                    }

                    Log.d(LOG_TAG,"ADN extention EF: 0x" 
                        + Integer.toHexString(extensionEF)
                        + ":" + adn.extRecord
                        + "\n" + SimUtils.bytesToHexString(data));

                    adn.appendExtRecord(data);

                    pendingExtLoads--;
                    // result should have been set in 
                    // EVENT_ADN_LOAD_DONE or EVENT_ADN_LOAD_ALL_DONE
                break;            

                case EVENT_ADN_LOAD_ALL_DONE:
                    ar = (AsyncResult)(msg.obj);
                    ArrayList<byte[]> datas = (ArrayList<byte[]>)(ar.result);
     
                    if (ar.exception != null) {
                        throw new RuntimeException("load failed", ar.exception);
                    }

                    adns = new ArrayList<AdnRecord>(datas.size());
                    result = adns;
                    pendingExtLoads = 0;

                    for(int i = 0, s = datas.size() ; i < s ; i++) {
                        adn = new AdnRecord(ef, 1 + i, datas.get(i));
                        adns.add(adn);

                        if (adn.hasExtendedRecord()) {
                            // If we have a valid value in the ext record field,
                            // we're not done yet: we need to read the corresponding
                            // ext record and append it

                            pendingExtLoads++;
                            
                            phone.mSIMFileHandler.loadEFLinearFixed(
                                extensionEF, adn.extRecord, 
                                obtainMessage(EVENT_EXT_RECORD_LOAD_DONE, adn)); 
                        }
                    }
                break;
            }
        } catch (RuntimeException exc) {            
            if (userResponse != null) {
                AsyncResult.forMessage(userResponse) 
                                .exception = exc;
                userResponse.sendToTarget();
                // Loading is all or nothing--either every load succeeds
                // or we fail the whole thing.
                userResponse = null;
            }
            return;
        }

        if (userResponse != null && pendingExtLoads == 0) {
            AsyncResult.forMessage(userResponse).result 
                = result;

            userResponse.sendToTarget();
            userResponse = null;
        }
    }
    

}

/**
 * 
 * Used to load or store ADNs (Abbreviated Dialing Numbers).
 *
 * {@hide}
 *
 */
public class AdnRecord implements Parcelable
{
    static final String LOG_TAG = "GSM";
    
    //***** Instance Variables

    String alphaTag = "";
    String number = "";
    int extRecord = 0xff;
    int efid;                   // or 0 if none
    int recordNumber;           // or 0 if none


    //***** Constants

    // In an ADN record, everything but the alpha identifier
    // is in a footer that's 14 bytes
    static final int FOOTER_SIZE_BYTES = 14;

    // Maximum size of the un-extended number field
    static final int MAX_NUMBER_SIZE_BYTES = 11;

    static final int EXT_RECORD_LENGTH_BYTES = 13;
    static final int EXT_RECORD_TYPE_ADDITIONAL_DATA = 2;
    static final int EXT_RECORD_TYPE_MASK = 3;
    static final int MAX_EXT_CALLED_PARTY_LENGTH = 0xa;

    // ADN offset
    static final int ADN_BCD_NUMBER_LENGTH = 0;
    static final int ADN_TON_AND_NPI = 1;
    static final int ADN_DAILING_NUMBER_START = 2;
    static final int ADN_DAILING_NUMBER_END = 11;
    static final int ADN_CAPABILITY_ID = 12;
    static final int ADN_EXTENSION_ID = 13;

    //***** Static Methods

    public static final Parcelable.Creator<AdnRecord> CREATOR
            = new Parcelable.Creator<AdnRecord>()
    {
        public AdnRecord createFromParcel(Parcel source)
        {
            int efid;
            int recordNumber;
            String alphaTag;
            String number;

            efid = source.readInt();
            recordNumber = source.readInt();
            alphaTag = source.readString();
            number = source.readString();

            return new AdnRecord(efid, recordNumber, alphaTag, number);
        }

        public AdnRecord[] newArray(int size)
        {
            return new AdnRecord[size];
        }
    };


    //***** Constructor
    public
    AdnRecord (byte[] record)
    {
        this(0, 0, record);
    }

    public
    AdnRecord (int efid, int recordNumber, byte[] record)
    {
        this.efid = efid;
        this.recordNumber = recordNumber;
        parseRecord(record);
    }

    public
    AdnRecord (String alphaTag, String number)
    {
        this(0, 0, alphaTag, number);
    }
    
    public
    AdnRecord (int efid, int recordNumber, String alphaTag, String number)
    {
        this.efid = efid;
        this.recordNumber = recordNumber;
        this.alphaTag = alphaTag;
        this.number = number;
    }
    
    //***** Instance Methods

    public String getAlphaTag()
    {
        return alphaTag;
    }

    public String getNumber()
    {
        return number;
    }

    public String toString()
    {
        return "ADN Record '" + alphaTag + "' '" + number + "'";
    }

    public boolean isEmpty()
    {
        return alphaTag.equals("") && number.equals("");
    }

    public boolean hasExtendedRecord()
    {
        return extRecord != 0 && extRecord != 0xff;
    }

    public boolean isEqual(AdnRecord adn) {
        return ( alphaTag.equals(adn.getAlphaTag()) &&
                number.equals(adn.getNumber()) );
    }
    //***** Parcelable Implementation

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags)
    {
        dest.writeInt(efid);
        dest.writeInt(recordNumber);
        dest.writeString(alphaTag);
        dest.writeString(number);
    }

    /**
     * Build adn hex byte array based on record size
     * The format of byte array is defined in 51.011 10.5.1
     *
     * @param recordSize is the size X of EF record
     * @return hex byte[recordSize] to be written to EF record
     *          return nulll for wrong format of dialing nubmer or tag
     */
    public byte[] buildAdnString(int recordSize) {
        byte[] bcdNumber;
        byte[] byteTag;
        byte[] adnString = null;
        int footerOffset = recordSize - FOOTER_SIZE_BYTES;

        if (number == null || number.equals("") ||
                alphaTag == null || alphaTag.equals("")) {

            Log.w(LOG_TAG, "[buildAdnString] Empty alpha tag or number");
            adnString = new byte[recordSize];
            for (int i = 0; i < recordSize; i++) {
                adnString[i] = (byte) 0xFF;
            }
        } else if (number.length()
                > (ADN_DAILING_NUMBER_END - ADN_DAILING_NUMBER_START + 1) * 2) {
            Log.w(LOG_TAG,
                    "[buildAdnString] Max length of dailing number is 20");
        } else if (alphaTag.length() > footerOffset) {
            Log.w(LOG_TAG,
                    "[buildAdnString] Max length of tag is " + footerOffset);
        } else {

            adnString = new byte[recordSize];
            for (int i = 0; i < recordSize; i++) {
                adnString[i] = (byte) 0xFF;
            }

            bcdNumber = PhoneNumberUtils.numberToCalledPartyBCD(number);

            System.arraycopy(bcdNumber, 0, adnString,
                    footerOffset + ADN_TON_AND_NPI, bcdNumber.length);

            adnString[footerOffset + ADN_BCD_NUMBER_LENGTH]
                    = (byte) (bcdNumber.length);
            adnString[footerOffset + ADN_CAPABILITY_ID]
                    = (byte) 0xFF; // Capacility Id
            adnString[footerOffset + ADN_EXTENSION_ID]
                    = (byte) 0xFF; // Extension Record Id

            byteTag = GsmAlphabet.stringToGsm8BitPacked(alphaTag);
            System.arraycopy(byteTag, 0, adnString, 0, byteTag.length);

        }

        return adnString;
    }

    /**
     * See TS 51.011 10.5.10
     */
    public void
    appendExtRecord (byte[] extRecord) {        
        try {
            if (extRecord.length != EXT_RECORD_LENGTH_BYTES) {
                return;
            }

            if ((extRecord[0] & EXT_RECORD_TYPE_MASK)
                    != EXT_RECORD_TYPE_ADDITIONAL_DATA
            ) {
                return;
            }

            if ((0xff & extRecord[1]) > MAX_EXT_CALLED_PARTY_LENGTH) {
                // invalid or empty record
                return;
            }

            number += PhoneNumberUtils.calledPartyBCDFragmentToString(
                                        extRecord, 2, 0xff & extRecord[1]);

            // We don't support ext record chaining.

        } catch (RuntimeException ex) {



            
            Log.w(LOG_TAG, "Error parsing AdnRecord ext record", ex);
        }
    }

    //***** Private Methods

    /**
     * alphaTag and number are set to null on invalid format
     */
    private void
    parseRecord(byte[] record) {
        try {
            alphaTag = SimUtils.adnStringFieldToString(
                            record, 0, record.length - FOOTER_SIZE_BYTES);

            int footerOffset = record.length - FOOTER_SIZE_BYTES;

            int numberLength = 0xff & record[footerOffset];

            if (numberLength > MAX_NUMBER_SIZE_BYTES) {
                // Invalid number length
                number = "";
                return;
            }

            // Please note 51.011 10.5.1:
            //
            // "If the Dialling Number/SSC String does not contain 
            // a dialling number, e.g. a control string deactivating 
            // a service, the TON/NPI byte shall be set to 'FF' by 
            // the ME (see note 2)."

            number = PhoneNumberUtils.calledPartyBCDToString(
                            record, footerOffset + 1, numberLength);


            extRecord = 0xff & record[record.length - 1];

        } catch (RuntimeException ex) {
            Log.w(LOG_TAG, "Error parsing AdnRecord", ex);
            number = "";
            alphaTag = "";
        }        
    }
}
