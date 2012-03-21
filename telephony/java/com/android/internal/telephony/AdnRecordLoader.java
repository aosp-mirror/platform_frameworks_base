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

package com.android.internal.telephony;

import java.util.ArrayList;

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;


public class AdnRecordLoader extends Handler {
    final static String LOG_TAG = "RIL_AdnRecordLoader";

    //***** Instance Variables

    private IccFileHandler mFh;
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

    public AdnRecordLoader(IccFileHandler fh) {
        // The telephony unit-test cases may create AdnRecords
        // in secondary threads
        super(Looper.getMainLooper());
        mFh = fh;
    }

    /**
     * Resulting AdnRecord is placed in response.obj.result
     * or response.obj.exception is set
     */
    public void
    loadFromEF(int ef, int extensionEF, int recordNumber,
                Message response) {
        this.ef = ef;
        this.extensionEF = extensionEF;
        this.recordNumber = recordNumber;
        this.userResponse = response;

        mFh.loadEFLinearFixed(
                    ef, recordNumber,
                    obtainMessage(EVENT_ADN_LOAD_DONE));

    }


    /**
     * Resulting ArrayList&lt;adnRecord> is placed in response.obj.result
     * or response.obj.exception is set
     */
    public void
    loadAllFromEF(int ef, int extensionEF,
                Message response) {
        this.ef = ef;
        this.extensionEF = extensionEF;
        this.userResponse = response;

        mFh.loadEFLinearFixedAll(
                    ef,
                    obtainMessage(EVENT_ADN_LOAD_ALL_DONE));

    }

    /**
     * Write adn to a EF SIM record
     * It will get the record size of EF record and compose hex adn array
     * then write the hex array to EF record
     *
     * @param adn is set with alphaTag and phone number
     * @param ef EF fileid
     * @param extensionEF extension EF fileid
     * @param recordNumber 1-based record index
     * @param pin2 for CHV2 operations, must be null if pin2 is not needed
     * @param response will be sent to its handler when completed
     */
    public void
    updateEF(AdnRecord adn, int ef, int extensionEF, int recordNumber,
            String pin2, Message response) {
        this.ef = ef;
        this.extensionEF = extensionEF;
        this.recordNumber = recordNumber;
        this.userResponse = response;
        this.pin2 = pin2;

        mFh.getEFLinearRecordSize( ef,
            obtainMessage(EVENT_EF_LINEAR_RECORD_SIZE_DONE, adn));
    }

    //***** Overridden from Handler

    public void
    handleMessage(Message msg) {
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
                        throw new RuntimeException("wrong ADN format",
                                ar.exception);
                    }

                    mFh.updateEFLinearFixed(ef, recordNumber,
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
                            + "\n" + IccUtils.bytesToHexString(data));
                    }

                    adn = new AdnRecord(ef, recordNumber, data);
                    result = adn;

                    if (adn.hasExtendedRecord()) {
                        // If we have a valid value in the ext record field,
                        // we're not done yet: we need to read the corresponding
                        // ext record and append it

                        pendingExtLoads = 1;

                        mFh.loadEFLinearFixed(
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

                    Log.d(LOG_TAG,"ADN extension EF: 0x"
                        + Integer.toHexString(extensionEF)
                        + ":" + adn.extRecord
                        + "\n" + IccUtils.bytesToHexString(data));

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

                            mFh.loadEFLinearFixed(
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
