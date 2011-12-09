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

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.telephony.gsm.UsimPhoneBookManager;

import java.util.ArrayList;
import java.util.Iterator;

/**
 * {@hide}
 */
public final class AdnRecordCache extends Handler implements IccConstants {
    //***** Instance Variables

    private IccFileHandler mFh;
    private UsimPhoneBookManager mUsimPhoneBookManager;

    // Indexed by EF ID
    SparseArray<ArrayList<AdnRecord>> adnLikeFiles
        = new SparseArray<ArrayList<AdnRecord>>();

    // People waiting for ADN-like files to be loaded
    SparseArray<ArrayList<Message>> adnLikeWaiters
        = new SparseArray<ArrayList<Message>>();

    // People waiting for adn record to be updated
    SparseArray<Message> userWriteResponse = new SparseArray<Message>();

    //***** Event Constants

    static final int EVENT_LOAD_ALL_ADN_LIKE_DONE = 1;
    static final int EVENT_UPDATE_ADN_DONE = 2;

    //***** Constructor



    public AdnRecordCache(IccFileHandler fh) {
        mFh = fh;
        mUsimPhoneBookManager = new UsimPhoneBookManager(mFh, this);
    }

    //***** Called from SIMRecords

    /**
     * Called from SIMRecords.onRadioNotAvailable and SIMRecords.handleSimRefresh.
     */
    public void reset() {
        adnLikeFiles.clear();
        mUsimPhoneBookManager.reset();

        clearWaiters();
        clearUserWriters();

    }

    private void clearWaiters() {
        int size = adnLikeWaiters.size();
        for (int i = 0; i < size; i++) {
            ArrayList<Message> waiters = adnLikeWaiters.valueAt(i);
            AsyncResult ar = new AsyncResult(null, null, new RuntimeException("AdnCache reset"));
            notifyWaiters(waiters, ar);
        }
        adnLikeWaiters.clear();
    }

    private void clearUserWriters() {
        int size = userWriteResponse.size();
        for (int i = 0; i < size; i++) {
            sendErrorResponse(userWriteResponse.valueAt(i), "AdnCace reset");
        }
        userWriteResponse.clear();
    }

    /**
     * @return List of AdnRecords for efid if we've already loaded them this
     * radio session, or null if we haven't
     */
    public ArrayList<AdnRecord>
    getRecordsIfLoaded(int efid) {
        return adnLikeFiles.get(efid);
    }

    /**
     * Returns extension ef associated with ADN-like EF or -1 if
     * we don't know.
     *
     * See 3GPP TS 51.011 for this mapping
     */
    int extensionEfForEf(int efid) {
        switch (efid) {
            case EF_MBDN: return EF_EXT6;
            case EF_ADN: return EF_EXT1;
            case EF_SDN: return EF_EXT3;
            case EF_FDN: return EF_EXT2;
            case EF_MSISDN: return EF_EXT1;
            case EF_PBR: return 0; // The EF PBR doesn't have an extension record
            default: return -1;
        }
    }

    private void sendErrorResponse(Message response, String errString) {
        if (response != null) {
            Exception e = new RuntimeException(errString);
            AsyncResult.forMessage(response).exception = e;
            response.sendToTarget();
        }
    }

    /**
     * Update an ADN-like record in EF by record index
     *
     * @param efid must be one among EF_ADN, EF_FDN, and EF_SDN
     * @param adn is the new adn to be stored
     * @param recordIndex is the 1-based adn record index
     * @param pin2 is required to update EF_FDN, otherwise must be null
     * @param response message to be posted when done
     *        response.exception hold the exception in error
     */
    public void updateAdnByIndex(int efid, AdnRecord adn, int recordIndex, String pin2,
            Message response) {

        int extensionEF = extensionEfForEf(efid);
        if (extensionEF < 0) {
            sendErrorResponse(response, "EF is not known ADN-like EF:" + efid);
            return;
        }

        Message pendingResponse = userWriteResponse.get(efid);
        if (pendingResponse != null) {
            sendErrorResponse(response, "Have pending update for EF:" + efid);
            return;
        }

        userWriteResponse.put(efid, response);

        new AdnRecordLoader(mFh).updateEF(adn, efid, extensionEF,
                recordIndex, pin2,
                obtainMessage(EVENT_UPDATE_ADN_DONE, efid, recordIndex, adn));
    }

    /**
     * Replace oldAdn with newAdn in ADN-like record in EF
     *
     * The ADN-like records must be read through requestLoadAllAdnLike() before
     *
     * @param efid must be one of EF_ADN, EF_FDN, and EF_SDN
     * @param oldAdn is the adn to be replaced
     *        If oldAdn.isEmpty() is ture, it insert the newAdn
     * @param newAdn is the adn to be stored
     *        If newAdn.isEmpty() is true, it delete the oldAdn
     * @param pin2 is required to update EF_FDN, otherwise must be null
     * @param response message to be posted when done
     *        response.exception hold the exception in error
     */
    public void updateAdnBySearch(int efid, AdnRecord oldAdn, AdnRecord newAdn,
            String pin2, Message response) {

        int extensionEF;
        extensionEF = extensionEfForEf(efid);

        if (extensionEF < 0) {
            sendErrorResponse(response, "EF is not known ADN-like EF:" + efid);
            return;
        }

        ArrayList<AdnRecord>  oldAdnList;

        if (efid == EF_PBR) {
            oldAdnList = mUsimPhoneBookManager.loadEfFilesFromUsim();
        } else {
            oldAdnList = getRecordsIfLoaded(efid);
        }

        if (oldAdnList == null) {
            sendErrorResponse(response, "Adn list not exist for EF:" + efid);
            return;
        }

        int index = -1;
        int count = 1;
        for (Iterator<AdnRecord> it = oldAdnList.iterator(); it.hasNext(); ) {
            if (oldAdn.isEqual(it.next())) {
                index = count;
                break;
            }
            count++;
        }

        if (index == -1) {
            sendErrorResponse(response, "Adn record don't exist for " + oldAdn);
            return;
        }

        if (efid == EF_PBR) {
            AdnRecord foundAdn = oldAdnList.get(index-1);
            efid = foundAdn.efid;
            extensionEF = foundAdn.extRecord;
            index = foundAdn.recordNumber;

            newAdn.efid = efid;
            newAdn.extRecord = extensionEF;
            newAdn.recordNumber = index;
        }

        Message pendingResponse = userWriteResponse.get(efid);

        if (pendingResponse != null) {
            sendErrorResponse(response, "Have pending update for EF:" + efid);
            return;
        }

        userWriteResponse.put(efid, response);

        new AdnRecordLoader(mFh).updateEF(newAdn, efid, extensionEF,
                index, pin2,
                obtainMessage(EVENT_UPDATE_ADN_DONE, efid, index, newAdn));
    }


    /**
     * Responds with exception (in response) if efid is not a known ADN-like
     * record
     */
    public void
    requestLoadAllAdnLike (int efid, int extensionEf, Message response) {
        ArrayList<Message> waiters;
        ArrayList<AdnRecord> result;

        if (efid == EF_PBR) {
            result = mUsimPhoneBookManager.loadEfFilesFromUsim();
        } else {
            result = getRecordsIfLoaded(efid);
        }

        // Have we already loaded this efid?
        if (result != null) {
            if (response != null) {
                AsyncResult.forMessage(response).result = result;
                response.sendToTarget();
            }

            return;
        }

        // Have we already *started* loading this efid?

        waiters = adnLikeWaiters.get(efid);

        if (waiters != null) {
            // There's a pending request for this EF already
            // just add ourselves to it

            waiters.add(response);
            return;
        }

        // Start loading efid

        waiters = new ArrayList<Message>();
        waiters.add(response);

        adnLikeWaiters.put(efid, waiters);


        if (extensionEf < 0) {
            // respond with error if not known ADN-like record

            if (response != null) {
                AsyncResult.forMessage(response).exception
                    = new RuntimeException("EF is not known ADN-like EF:" + efid);
                response.sendToTarget();
            }

            return;
        }

        new AdnRecordLoader(mFh).loadAllFromEF(efid, extensionEf,
            obtainMessage(EVENT_LOAD_ALL_ADN_LIKE_DONE, efid, 0));
    }

    //***** Private methods

    private void
    notifyWaiters(ArrayList<Message> waiters, AsyncResult ar) {

        if (waiters == null) {
            return;
        }

        for (int i = 0, s = waiters.size() ; i < s ; i++) {
            Message waiter = waiters.get(i);

            AsyncResult.forMessage(waiter, ar.result, ar.exception);
            waiter.sendToTarget();
        }
    }

    //***** Overridden from Handler

    public void
    handleMessage(Message msg) {
        AsyncResult ar;
        int efid;

        switch(msg.what) {
            case EVENT_LOAD_ALL_ADN_LIKE_DONE:
                /* arg1 is efid, obj.result is ArrayList<AdnRecord>*/
                ar = (AsyncResult) msg.obj;
                efid = msg.arg1;
                ArrayList<Message> waiters;

                waiters = adnLikeWaiters.get(efid);
                adnLikeWaiters.delete(efid);

                if (ar.exception == null) {
                    adnLikeFiles.put(efid, (ArrayList<AdnRecord>) ar.result);
                }
                notifyWaiters(waiters, ar);
                break;
            case EVENT_UPDATE_ADN_DONE:
                ar = (AsyncResult)msg.obj;
                efid = msg.arg1;
                int index = msg.arg2;
                AdnRecord adn = (AdnRecord) (ar.userObj);

                if (ar.exception == null) {
                    adnLikeFiles.get(efid).set(index - 1, adn);
                    mUsimPhoneBookManager.invalidateCache();
                }

                Message response = userWriteResponse.get(efid);
                userWriteResponse.delete(efid);

                AsyncResult.forMessage(response, null, ar.exception);
                response.sendToTarget();
                break;
        }

    }


}
