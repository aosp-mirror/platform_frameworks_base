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

import android.content.ContentProvider;
import android.content.UriMatcher;
import android.content.ContentValues;
import com.android.internal.database.ArrayListCursor;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemProperties;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import com.android.internal.telephony.IccConstants;
import com.android.internal.telephony.AdnRecord;
import com.android.internal.telephony.IIccPhoneBook;


/**
 * {@hide}
 */
public class IccProvider extends ContentProvider {
    private static final String TAG = "IccProvider";
    private static final boolean DBG = false;


    private static final String[] ADDRESS_BOOK_COLUMN_NAMES = new String[] {
        "name",
        "number",
        "emails"
    };

    private static final int ADN = 1;
    private static final int FDN = 2;
    private static final int SDN = 3;

    private static final String STR_TAG = "tag";
    private static final String STR_NUMBER = "number";
    private static final String STR_EMAILS = "emails";
    private static final String STR_PIN2 = "pin2";

    private static final UriMatcher URL_MATCHER =
                            new UriMatcher(UriMatcher.NO_MATCH);

    static {
        URL_MATCHER.addURI("icc", "adn", ADN);
        URL_MATCHER.addURI("icc", "fdn", FDN);
        URL_MATCHER.addURI("icc", "sdn", SDN);
    }


    private boolean mSimulator;

    @Override
    public boolean onCreate() {
        String device = SystemProperties.get("ro.product.device");
        if (!TextUtils.isEmpty(device)) {
            mSimulator = false;
        } else {
            // simulator
            mSimulator = true;
        }

        return true;
    }

    @Override
    public Cursor query(Uri url, String[] projection, String selection,
            String[] selectionArgs, String sort) {
        ArrayList<ArrayList> results;

        if (!mSimulator) {
            switch (URL_MATCHER.match(url)) {
                case ADN:
                    results = loadFromEf(IccConstants.EF_ADN);
                    break;

                case FDN:
                    results = loadFromEf(IccConstants.EF_FDN);
                    break;

                case SDN:
                    results = loadFromEf(IccConstants.EF_SDN);
                    break;

                default:
                    throw new IllegalArgumentException("Unknown URL " + url);
            }
        } else {
            // Fake up some data for the simulator
            results = new ArrayList<ArrayList>(4);
            ArrayList<String> contact;

            contact = new ArrayList<String>();
            contact.add("Ron Stevens/H");
            contact.add("512-555-5038");
            results.add(contact);

            contact = new ArrayList<String>();
            contact.add("Ron Stevens/M");
            contact.add("512-555-8305");
            results.add(contact);

            contact = new ArrayList<String>();
            contact.add("Melissa Owens");
            contact.add("512-555-8305");
            results.add(contact);

            contact = new ArrayList<String>();
            contact.add("Directory Assistence");
            contact.add("411");
            results.add(contact);
        }

        return new ArrayListCursor(ADDRESS_BOOK_COLUMN_NAMES, results);
    }

    @Override
    public String getType(Uri url) {
        switch (URL_MATCHER.match(url)) {
            case ADN:
            case FDN:
            case SDN:
                return "vnd.android.cursor.dir/sim-contact";

            default:
                throw new IllegalArgumentException("Unknown URL " + url);
        }
    }

    @Override
    public Uri insert(Uri url, ContentValues initialValues) {
        Uri resultUri;
        int efType;
        String pin2 = null;

        if (DBG) log("insert");

        int match = URL_MATCHER.match(url);
        switch (match) {
            case ADN:
                efType = IccConstants.EF_ADN;
                break;

            case FDN:
                efType = IccConstants.EF_FDN;
                pin2 = initialValues.getAsString("pin2");
                break;

            default:
                throw new UnsupportedOperationException(
                        "Cannot insert into URL: " + url);
        }

        String tag = initialValues.getAsString("tag");
        String number = initialValues.getAsString("number");
        // TODO(): Read email instead of sending null.
        boolean success = addIccRecordToEf(efType, tag, number, null, pin2);

        if (!success) {
            return null;
        }

        StringBuilder buf = new StringBuilder("content://icc/");
        switch (match) {
            case ADN:
                buf.append("adn/");
                break;

            case FDN:
                buf.append("fdn/");
                break;
        }

        // TODO: we need to find out the rowId for the newly added record
        buf.append(0);

        resultUri = Uri.parse(buf.toString());

        /*
        // notify interested parties that an insertion happened
        getContext().getContentResolver().notifyInsert(
                resultUri, rowID, null);
        */

        return resultUri;
    }

    private String normalizeValue(String inVal) {
        int len = inVal.length();
        String retVal = inVal;

        if (inVal.charAt(0) == '\'' && inVal.charAt(len-1) == '\'') {
            retVal = inVal.substring(1, len-1);
        }

        return retVal;
    }

    @Override
    public int delete(Uri url, String where, String[] whereArgs) {
        int efType;

        if (DBG) log("delete");

        int match = URL_MATCHER.match(url);
        switch (match) {
            case ADN:
                efType = IccConstants.EF_ADN;
                break;

            case FDN:
                efType = IccConstants.EF_FDN;
                break;

            default:
                throw new UnsupportedOperationException(
                        "Cannot insert into URL: " + url);
        }

        // parse where clause
        String tag = null;
        String number = null;
        String[] emails = null;
        String pin2 = null;

        String[] tokens = where.split("AND");
        int n = tokens.length;

        while (--n >= 0) {
            String param = tokens[n];
            if (DBG) log("parsing '" + param + "'");

            String[] pair = param.split("=");

            if (pair.length != 2) {
                Log.e(TAG, "resolve: bad whereClause parameter: " + param);
                continue;
            }

            String key = pair[0].trim();
            String val = pair[1].trim();

            if (STR_TAG.equals(key)) {
                tag = normalizeValue(val);
            } else if (STR_NUMBER.equals(key)) {
                number = normalizeValue(val);
            } else if (STR_EMAILS.equals(key)) {
                //TODO(): Email is null.
                emails = null;
            } else if (STR_PIN2.equals(key)) {
                pin2 = normalizeValue(val);
            }
        }

        if (TextUtils.isEmpty(number)) {
            return 0;
        }

        if (efType == FDN && TextUtils.isEmpty(pin2)) {
            return 0;
        }

        boolean success = deleteIccRecordFromEf(efType, tag, number, emails, pin2);
        if (!success) {
            return 0;
        }

        return 1;
    }

    @Override
    public int update(Uri url, ContentValues values, String where, String[] whereArgs) {
        int efType;
        String pin2 = null;

        if (DBG) log("update");

        int match = URL_MATCHER.match(url);
        switch (match) {
            case ADN:
                efType = IccConstants.EF_ADN;
                break;

            case FDN:
                efType = IccConstants.EF_FDN;
                pin2 = values.getAsString("pin2");
                break;

            default:
                throw new UnsupportedOperationException(
                        "Cannot insert into URL: " + url);
        }

        String tag = values.getAsString("tag");
        String number = values.getAsString("number");
        String[] emails = null;
        String newTag = values.getAsString("newTag");
        String newNumber = values.getAsString("newNumber");
        String[] newEmails = null;
        // TODO(): Update for email.
        boolean success = updateIccRecordInEf(efType, tag, number,
                newTag, newNumber, pin2);

        if (!success) {
            return 0;
        }

        return 1;
    }

    private ArrayList<ArrayList> loadFromEf(int efType) {
        ArrayList<ArrayList> results = new ArrayList<ArrayList>();
        List<AdnRecord> adnRecords = null;

        if (DBG) log("loadFromEf: efType=" + efType);

        try {
            IIccPhoneBook iccIpb = IIccPhoneBook.Stub.asInterface(
                    ServiceManager.getService("simphonebook"));
            if (iccIpb != null) {
                adnRecords = iccIpb.getAdnRecordsInEf(efType);
            }
        } catch (RemoteException ex) {
            // ignore it
        } catch (SecurityException ex) {
            if (DBG) log(ex.toString());
        }
        if (adnRecords != null) {
            // Load the results

            int N = adnRecords.size();
            if (DBG) log("adnRecords.size=" + N);
            for (int i = 0; i < N ; i++) {
                loadRecord(adnRecords.get(i), results);
            }
        } else {
            // No results to load
            Log.w(TAG, "Cannot load ADN records");
            results.clear();
        }
        if (DBG) log("loadFromEf: return results");
        return results;
    }

    private boolean
    addIccRecordToEf(int efType, String name, String number, String[] emails, String pin2) {
        if (DBG) log("addIccRecordToEf: efType=" + efType + ", name=" + name +
                ", number=" + number + ", emails=" + emails);

        boolean success = false;

        // TODO: do we need to call getAdnRecordsInEf() before calling
        // updateAdnRecordsInEfBySearch()? In any case, we will leave
        // the UI level logic to fill that prereq if necessary. But
        // hopefully, we can remove this requirement.

        try {
            IIccPhoneBook iccIpb = IIccPhoneBook.Stub.asInterface(
                    ServiceManager.getService("simphonebook"));
            if (iccIpb != null) {
                success = iccIpb.updateAdnRecordsInEfBySearch(efType, "", "",
                        name, number, pin2);
            }
        } catch (RemoteException ex) {
            // ignore it
        } catch (SecurityException ex) {
            if (DBG) log(ex.toString());
        }
        if (DBG) log("addIccRecordToEf: " + success);
        return success;
    }

    private boolean
    updateIccRecordInEf(int efType, String oldName, String oldNumber,
            String newName, String newNumber, String pin2) {
        if (DBG) log("updateIccRecordInEf: efType=" + efType +
                ", oldname=" + oldName + ", oldnumber=" + oldNumber +
                ", newname=" + newName + ", newnumber=" + newNumber);
        boolean success = false;

        try {
            IIccPhoneBook iccIpb = IIccPhoneBook.Stub.asInterface(
                    ServiceManager.getService("simphonebook"));
            if (iccIpb != null) {
                success = iccIpb.updateAdnRecordsInEfBySearch(efType,
                        oldName, oldNumber, newName, newNumber, pin2);
            }
        } catch (RemoteException ex) {
            // ignore it
        } catch (SecurityException ex) {
            if (DBG) log(ex.toString());
        }
        if (DBG) log("updateIccRecordInEf: " + success);
        return success;
    }


    private boolean deleteIccRecordFromEf(int efType, String name, String number, String[] emails,
            String pin2) {
        if (DBG) log("deleteIccRecordFromEf: efType=" + efType +
                ", name=" + name + ", number=" + number + ", emails=" + emails + ", pin2=" + pin2);

        boolean success = false;

        try {
            IIccPhoneBook iccIpb = IIccPhoneBook.Stub.asInterface(
                    ServiceManager.getService("simphonebook"));
            if (iccIpb != null) {
                success = iccIpb.updateAdnRecordsInEfBySearch(efType,
                        name, number, "", "", pin2);
            }
        } catch (RemoteException ex) {
            // ignore it
        } catch (SecurityException ex) {
            if (DBG) log(ex.toString());
        }
        if (DBG) log("deleteIccRecordFromEf: " + success);
        return success;
    }

    /**
     * Loads an AdnRecord into an ArrayList. Must be called with mLock held.
     *
     * @param record the ADN record to load from
     * @param results the array list to put the results in
     */
    private void loadRecord(AdnRecord record,
            ArrayList<ArrayList> results) {
        if (!record.isEmpty()) {
            ArrayList<String> contact = new ArrayList<String>();
            String alphaTag = record.getAlphaTag();
            String number = record.getNumber();
            String[] emails = record.getEmails();

            if (DBG) log("loadRecord: " + alphaTag + ", " + number + ",");
            contact.add(alphaTag);
            contact.add(number);
            StringBuilder emailString = new StringBuilder();

            if (emails != null) {
                for (String email: emails) {
                    if (DBG) log("Adding email:" + email);
                    emailString.append(email);
                    emailString.append(",");
                }
                contact.add(emailString.toString());
            } else {
                contact.add(null);
            }
            results.add(contact);
        }
    }

    private void log(String msg) {
        Log.d(TAG, "[IccProvider] " + msg);
    }

}
