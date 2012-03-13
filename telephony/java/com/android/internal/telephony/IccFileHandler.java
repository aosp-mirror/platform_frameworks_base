/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.os.*;
import android.util.Log;
import java.util.ArrayList;

/**
 * {@hide}
 */
public abstract class IccFileHandler extends Handler implements IccConstants {

    //from TS 11.11 9.1 or elsewhere
    static protected final int COMMAND_READ_BINARY = 0xb0;
    static protected final int COMMAND_UPDATE_BINARY = 0xd6;
    static protected final int COMMAND_READ_RECORD = 0xb2;
    static protected final int COMMAND_UPDATE_RECORD = 0xdc;
    static protected final int COMMAND_SEEK = 0xa2;
    static protected final int COMMAND_GET_RESPONSE = 0xc0;

    // from TS 11.11 9.2.5
    static protected final int READ_RECORD_MODE_ABSOLUTE = 4;

    //***** types of files  TS 11.11 9.3
    static protected final int EF_TYPE_TRANSPARENT = 0;
    static protected final int EF_TYPE_LINEAR_FIXED = 1;
    static protected final int EF_TYPE_CYCLIC = 3;

    //***** types of files  TS 11.11 9.3
    static protected final int TYPE_RFU = 0;
    static protected final int TYPE_MF  = 1;
    static protected final int TYPE_DF  = 2;
    static protected final int TYPE_EF  = 4;

    // size of GET_RESPONSE for EF's
    static protected final int GET_RESPONSE_EF_SIZE_BYTES = 15;
    static protected final int GET_RESPONSE_EF_IMG_SIZE_BYTES = 10;

    // Byte order received in response to COMMAND_GET_RESPONSE
    // Refer TS 51.011 Section 9.2.1
    static protected final int RESPONSE_DATA_RFU_1 = 0;
    static protected final int RESPONSE_DATA_RFU_2 = 1;

    static protected final int RESPONSE_DATA_FILE_SIZE_1 = 2;
    static protected final int RESPONSE_DATA_FILE_SIZE_2 = 3;

    static protected final int RESPONSE_DATA_FILE_ID_1 = 4;
    static protected final int RESPONSE_DATA_FILE_ID_2 = 5;
    static protected final int RESPONSE_DATA_FILE_TYPE = 6;
    static protected final int RESPONSE_DATA_RFU_3 = 7;
    static protected final int RESPONSE_DATA_ACCESS_CONDITION_1 = 8;
    static protected final int RESPONSE_DATA_ACCESS_CONDITION_2 = 9;
    static protected final int RESPONSE_DATA_ACCESS_CONDITION_3 = 10;
    static protected final int RESPONSE_DATA_FILE_STATUS = 11;
    static protected final int RESPONSE_DATA_LENGTH = 12;
    static protected final int RESPONSE_DATA_STRUCTURE = 13;
    static protected final int RESPONSE_DATA_RECORD_LENGTH = 14;


    //***** Events

    /** Finished retrieving size of transparent EF; start loading. */
    static protected final int EVENT_GET_BINARY_SIZE_DONE = 4;
    /** Finished loading contents of transparent EF; post result. */
    static protected final int EVENT_READ_BINARY_DONE = 5;
    /** Finished retrieving size of records for linear-fixed EF; now load. */
    static protected final int EVENT_GET_RECORD_SIZE_DONE = 6;
    /** Finished loading single record from a linear-fixed EF; post result. */
    static protected final int EVENT_READ_RECORD_DONE = 7;
    /** Finished retrieving record size; post result. */
    static protected final int EVENT_GET_EF_LINEAR_RECORD_SIZE_DONE = 8;
    /** Finished retrieving image instance record; post result. */
    static protected final int EVENT_READ_IMG_DONE = 9;
    /** Finished retrieving icon data; post result. */
    static protected final int EVENT_READ_ICON_DONE = 10;

     // member variables
    protected final CommandsInterface mCi;
    protected final IccCard mParentCard;
    protected String mAid;

    static class LoadLinearFixedContext {

        int efid;
        int recordNum, recordSize, countRecords;
        boolean loadAll;

        Message onLoaded;

        ArrayList<byte[]> results;

        LoadLinearFixedContext(int efid, int recordNum, Message onLoaded) {
            this.efid = efid;
            this.recordNum = recordNum;
            this.onLoaded = onLoaded;
            this.loadAll = false;
        }

        LoadLinearFixedContext(int efid, Message onLoaded) {
            this.efid = efid;
            this.recordNum = 1;
            this.loadAll = true;
            this.onLoaded = onLoaded;
        }
    }

    /**
     * Default constructor
     */
    protected IccFileHandler(IccCard card, String aid, CommandsInterface ci) {
        mParentCard = card;
        mAid = aid;
        mCi = ci;
    }

    public void dispose() {
    }

    //***** Public Methods

    /**
     * Load a record from a SIM Linear Fixed EF
     *
     * @param fileid EF id
     * @param recordNum 1-based (not 0-based) record number
     * @param onLoaded
     *
     * ((AsyncResult)(onLoaded.obj)).result is the byte[]
     *
     */
    public void loadEFLinearFixed(int fileid, int recordNum, Message onLoaded) {
        Message response
            = obtainMessage(EVENT_GET_RECORD_SIZE_DONE,
                        new LoadLinearFixedContext(fileid, recordNum, onLoaded));

        mCi.iccIOForApp(COMMAND_GET_RESPONSE, fileid, getEFPath(fileid),
                        0, 0, GET_RESPONSE_EF_SIZE_BYTES, null, null, mAid, response);
    }

    /**
     * Load a image instance record from a SIM Linear Fixed EF-IMG
     *
     * @param recordNum 1-based (not 0-based) record number
     * @param onLoaded
     *
     * ((AsyncResult)(onLoaded.obj)).result is the byte[]
     *
     */
    public void loadEFImgLinearFixed(int recordNum, Message onLoaded) {
        Message response = obtainMessage(EVENT_READ_IMG_DONE,
                new LoadLinearFixedContext(IccConstants.EF_IMG, recordNum,
                        onLoaded));

        // TODO(): Verify when path changes are done.
        mCi.iccIOForApp(COMMAND_GET_RESPONSE, IccConstants.EF_IMG, "img",
                recordNum, READ_RECORD_MODE_ABSOLUTE,
                GET_RESPONSE_EF_IMG_SIZE_BYTES, null, null, mAid, response);
    }

    /**
     * get record size for a linear fixed EF
     *
     * @param fileid EF id
     * @param onLoaded ((AsnyncResult)(onLoaded.obj)).result is the recordSize[]
     *        int[0] is the record length int[1] is the total length of the EF
     *        file int[3] is the number of records in the EF file So int[0] *
     *        int[3] = int[1]
     */
    public void getEFLinearRecordSize(int fileid, Message onLoaded) {
        Message response
                = obtainMessage(EVENT_GET_EF_LINEAR_RECORD_SIZE_DONE,
                        new LoadLinearFixedContext(fileid, onLoaded));
        mCi.iccIOForApp(COMMAND_GET_RESPONSE, fileid, getEFPath(fileid),
                    0, 0, GET_RESPONSE_EF_SIZE_BYTES, null, null, mAid, response);
    }

    /**
     * Load all records from a SIM Linear Fixed EF
     *
     * @param fileid EF id
     * @param onLoaded
     *
     * ((AsyncResult)(onLoaded.obj)).result is an ArrayList<byte[]>
     *
     */
    public void loadEFLinearFixedAll(int fileid, Message onLoaded) {
        Message response = obtainMessage(EVENT_GET_RECORD_SIZE_DONE,
                        new LoadLinearFixedContext(fileid,onLoaded));

        mCi.iccIOForApp(COMMAND_GET_RESPONSE, fileid, getEFPath(fileid),
                        0, 0, GET_RESPONSE_EF_SIZE_BYTES, null, null, mAid, response);
    }

    /**
     * Load a SIM Transparent EF
     *
     * @param fileid EF id
     * @param onLoaded
     *
     * ((AsyncResult)(onLoaded.obj)).result is the byte[]
     *
     */

    public void loadEFTransparent(int fileid, Message onLoaded) {
        Message response = obtainMessage(EVENT_GET_BINARY_SIZE_DONE,
                        fileid, 0, onLoaded);

        mCi.iccIOForApp(COMMAND_GET_RESPONSE, fileid, getEFPath(fileid),
                        0, 0, GET_RESPONSE_EF_SIZE_BYTES, null, null, mAid, response);
    }

    /**
     * Load a SIM Transparent EF-IMG. Used right after loadEFImgLinearFixed to
     * retrive STK's icon data.
     *
     * @param fileid EF id
     * @param onLoaded
     *
     * ((AsyncResult)(onLoaded.obj)).result is the byte[]
     *
     */
    public void loadEFImgTransparent(int fileid, int highOffset, int lowOffset,
            int length, Message onLoaded) {
        Message response = obtainMessage(EVENT_READ_ICON_DONE, fileid, 0,
                onLoaded);

        mCi.iccIOForApp(COMMAND_READ_BINARY, fileid, "img", highOffset, lowOffset,
                length, null, null, mAid, response);
    }

    /**
     * Update a record in a linear fixed EF
     * @param fileid EF id
     * @param recordNum 1-based (not 0-based) record number
     * @param data must be exactly as long as the record in the EF
     * @param pin2 for CHV2 operations, otherwist must be null
     * @param onComplete onComplete.obj will be an AsyncResult
     *                   onComplete.obj.userObj will be a IccIoResult on success
     */
    public void updateEFLinearFixed(int fileid, int recordNum, byte[] data,
            String pin2, Message onComplete) {
        mCi.iccIOForApp(COMMAND_UPDATE_RECORD, fileid, getEFPath(fileid),
                        recordNum, READ_RECORD_MODE_ABSOLUTE, data.length,
                        IccUtils.bytesToHexString(data), pin2, mAid, onComplete);
    }

    /**
     * Update a transparent EF
     * @param fileid EF id
     * @param data must be exactly as long as the EF
     */
    public void updateEFTransparent(int fileid, byte[] data, Message onComplete) {
        mCi.iccIOForApp(COMMAND_UPDATE_BINARY, fileid, getEFPath(fileid),
                        0, 0, data.length,
                        IccUtils.bytesToHexString(data), null, mAid, onComplete);
    }


    //***** Abstract Methods


    //***** Private Methods

    private void sendResult(Message response, Object result, Throwable ex) {
        if (response == null) {
            return;
        }

        AsyncResult.forMessage(response, result, ex);

        response.sendToTarget();
    }

    //***** Overridden from Handler

    public void handleMessage(Message msg) {
        AsyncResult ar;
        IccIoResult result;
        Message response = null;
        String str;
        LoadLinearFixedContext lc;

        IccException iccException;
        byte data[];
        int size;
        int fileid;
        int recordNum;
        int recordSize[];

        try {
            switch (msg.what) {
            case EVENT_READ_IMG_DONE:
                ar = (AsyncResult) msg.obj;
                lc = (LoadLinearFixedContext) ar.userObj;
                result = (IccIoResult) ar.result;
                response = lc.onLoaded;

                iccException = result.getException();
                if (iccException != null) {
                    sendResult(response, result.payload, ar.exception);
                }
                break;
            case EVENT_READ_ICON_DONE:
                ar = (AsyncResult) msg.obj;
                response = (Message) ar.userObj;
                result = (IccIoResult) ar.result;

                iccException = result.getException();
                if (iccException != null) {
                    sendResult(response, result.payload, ar.exception);
                }
                break;
            case EVENT_GET_EF_LINEAR_RECORD_SIZE_DONE:
                ar = (AsyncResult)msg.obj;
                lc = (LoadLinearFixedContext) ar.userObj;
                result = (IccIoResult) ar.result;
                response = lc.onLoaded;

                if (ar.exception != null) {
                    sendResult(response, null, ar.exception);
                    break;
                }

                iccException = result.getException();
                if (iccException != null) {
                    sendResult(response, null, iccException);
                    break;
                }

                data = result.payload;

                if (TYPE_EF != data[RESPONSE_DATA_FILE_TYPE] ||
                    EF_TYPE_LINEAR_FIXED != data[RESPONSE_DATA_STRUCTURE]) {
                    throw new IccFileTypeMismatch();
                }

                recordSize = new int[3];
                recordSize[0] = data[RESPONSE_DATA_RECORD_LENGTH] & 0xFF;
                recordSize[1] = ((data[RESPONSE_DATA_FILE_SIZE_1] & 0xff) << 8)
                       + (data[RESPONSE_DATA_FILE_SIZE_2] & 0xff);
                recordSize[2] = recordSize[1] / recordSize[0];

                sendResult(response, recordSize, null);
                break;
             case EVENT_GET_RECORD_SIZE_DONE:
                ar = (AsyncResult)msg.obj;
                lc = (LoadLinearFixedContext) ar.userObj;
                result = (IccIoResult) ar.result;
                response = lc.onLoaded;

                if (ar.exception != null) {
                    sendResult(response, null, ar.exception);
                    break;
                }

                iccException = result.getException();

                if (iccException != null) {
                    sendResult(response, null, iccException);
                    break;
                }

                data = result.payload;
                fileid = lc.efid;
                recordNum = lc.recordNum;

                if (TYPE_EF != data[RESPONSE_DATA_FILE_TYPE]) {
                    throw new IccFileTypeMismatch();
                }

                if (EF_TYPE_LINEAR_FIXED != data[RESPONSE_DATA_STRUCTURE]) {
                    throw new IccFileTypeMismatch();
                }

                lc.recordSize = data[RESPONSE_DATA_RECORD_LENGTH] & 0xFF;

                size = ((data[RESPONSE_DATA_FILE_SIZE_1] & 0xff) << 8)
                       + (data[RESPONSE_DATA_FILE_SIZE_2] & 0xff);

                lc.countRecords = size / lc.recordSize;

                 if (lc.loadAll) {
                     lc.results = new ArrayList<byte[]>(lc.countRecords);
                 }

                 mCi.iccIOForApp(COMMAND_READ_RECORD, lc.efid, getEFPath(lc.efid),
                         lc.recordNum,
                         READ_RECORD_MODE_ABSOLUTE,
                         lc.recordSize, null, null, mAid,
                         obtainMessage(EVENT_READ_RECORD_DONE, lc));
                 break;
            case EVENT_GET_BINARY_SIZE_DONE:
                ar = (AsyncResult)msg.obj;
                response = (Message) ar.userObj;
                result = (IccIoResult) ar.result;

                if (ar.exception != null) {
                    sendResult(response, null, ar.exception);
                    break;
                }

                iccException = result.getException();

                if (iccException != null) {
                    sendResult(response, null, iccException);
                    break;
                }

                data = result.payload;

                fileid = msg.arg1;

                if (TYPE_EF != data[RESPONSE_DATA_FILE_TYPE]) {
                    throw new IccFileTypeMismatch();
                }

                if (EF_TYPE_TRANSPARENT != data[RESPONSE_DATA_STRUCTURE]) {
                    throw new IccFileTypeMismatch();
                }

                size = ((data[RESPONSE_DATA_FILE_SIZE_1] & 0xff) << 8)
                       + (data[RESPONSE_DATA_FILE_SIZE_2] & 0xff);

                mCi.iccIOForApp(COMMAND_READ_BINARY, fileid, getEFPath(fileid),
                                0, 0, size, null, null, mAid,
                                obtainMessage(EVENT_READ_BINARY_DONE,
                                              fileid, 0, response));
            break;

            case EVENT_READ_RECORD_DONE:

                ar = (AsyncResult)msg.obj;
                lc = (LoadLinearFixedContext) ar.userObj;
                result = (IccIoResult) ar.result;
                response = lc.onLoaded;

                if (ar.exception != null) {
                    sendResult(response, null, ar.exception);
                    break;
                }

                iccException = result.getException();

                if (iccException != null) {
                    sendResult(response, null, iccException);
                    break;
                }

                if (!lc.loadAll) {
                    sendResult(response, result.payload, null);
                } else {
                    lc.results.add(result.payload);

                    lc.recordNum++;

                    if (lc.recordNum > lc.countRecords) {
                        sendResult(response, lc.results, null);
                    } else {
                        mCi.iccIOForApp(COMMAND_READ_RECORD, lc.efid, getEFPath(lc.efid),
                                    lc.recordNum,
                                    READ_RECORD_MODE_ABSOLUTE,
                                    lc.recordSize, null, null, mAid,
                                    obtainMessage(EVENT_READ_RECORD_DONE, lc));
                    }
                }

            break;

            case EVENT_READ_BINARY_DONE:
                ar = (AsyncResult)msg.obj;
                response = (Message) ar.userObj;
                result = (IccIoResult) ar.result;

                if (ar.exception != null) {
                    sendResult(response, null, ar.exception);
                    break;
                }

                iccException = result.getException();

                if (iccException != null) {
                    sendResult(response, null, iccException);
                    break;
                }

                sendResult(response, result.payload, null);
            break;

        }} catch (Exception exc) {
            if (response != null) {
                sendResult(response, null, exc);
            } else {
                loge("uncaught exception" + exc);
            }
        }
    }

    /**
     * Returns the root path of the EF file.
     * i.e returns MasterFile + DFfile as a string.
     * Ex: For EF_ADN on a SIM, it will return "3F007F10"
     * This function handles only EFids that are common to
     * RUIM, SIM, USIM and other types of Icc cards.
     *
     * @param efId
     * @return root path of the file.
     */
    protected String getCommonIccEFPath(int efid) {
        switch(efid) {
        case EF_ADN:
        case EF_FDN:
        case EF_MSISDN:
        case EF_SDN:
        case EF_EXT1:
        case EF_EXT2:
        case EF_EXT3:
            return MF_SIM + DF_TELECOM;

        case EF_ICCID:
        case EF_PL:
            return MF_SIM;
        case EF_IMG:
            return MF_SIM + DF_TELECOM + DF_GRAPHICS;
        }
        return null;
    }

    protected abstract String getEFPath(int efid);
    protected abstract void logd(String s);

    protected abstract void loge(String s);
    protected void setAid(String aid) {
        mAid = aid;
    }

}
