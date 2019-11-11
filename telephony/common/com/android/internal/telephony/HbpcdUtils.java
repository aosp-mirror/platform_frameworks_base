/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.telephony.Rlog;

import com.android.internal.telephony.HbpcdLookup.ArbitraryMccSidMatch;
import com.android.internal.telephony.HbpcdLookup.MccIdd;
import com.android.internal.telephony.HbpcdLookup.MccLookup;
import com.android.internal.telephony.HbpcdLookup.MccSidConflicts;
import com.android.internal.telephony.HbpcdLookup.MccSidRange;

public final class HbpcdUtils {
    private static final String LOG_TAG = "HbpcdUtils";
    private static final boolean DBG = false;
    private ContentResolver resolver = null;

    public HbpcdUtils(Context context) {
        resolver = context.getContentResolver();
    }

    /**
     *  Resolves the unknown MCC with SID and Timezone information.
    */
    public int getMcc(int sid, int tz, int DSTflag, boolean isNitzTimeZone) {
        int tmpMcc = 0;

        // check if SID exists in arbitrary_mcc_sid_match table.
        // these SIDs are assigned to more than 1 operators, but they are known to
        // be used by a specific operator, other operators having the same SID are
        // not using it currently, if that SID is in this table, we don't need to
        // check other tables.
        String projection2[] = {ArbitraryMccSidMatch.MCC};
        Cursor c2 = resolver.query(ArbitraryMccSidMatch.CONTENT_URI, projection2,
                            ArbitraryMccSidMatch.SID + "=" + sid, null, null);

        if (c2 != null) {
            int c2Counter = c2.getCount();
            if (DBG) {
                Rlog.d(LOG_TAG, "Query unresolved arbitrary table, entries are " + c2Counter);
            }
            if (c2Counter == 1) {
                if (DBG) {
                    Rlog.d(LOG_TAG, "Query Unresolved arbitrary returned the cursor " + c2);
                }
                c2.moveToFirst();
                tmpMcc = c2.getInt(0);
                if (DBG) {
                    Rlog.d(LOG_TAG, "MCC found in arbitrary_mcc_sid_match: " + tmpMcc);
                }
                c2.close();
                return tmpMcc;
            }
            c2.close();
        }

        // Then check if SID exists in mcc_sid_conflict table.
        // and use the timezone in mcc_lookup table to check which MCC matches.
        String projection3[] = {MccSidConflicts.MCC};
        Cursor c3 = resolver.query(MccSidConflicts.CONTENT_URI, projection3,
                MccSidConflicts.SID_CONFLICT + "=" + sid + " and (((" +
                MccLookup.GMT_OFFSET_LOW + "<=" + tz + ") and (" + tz + "<=" +
                MccLookup.GMT_OFFSET_HIGH + ") and (" + "0=" + DSTflag + ")) or ((" +
                MccLookup.GMT_DST_LOW + "<=" + tz + ") and (" + tz + "<=" +
                MccLookup.GMT_DST_HIGH + ") and (" + "1=" + DSTflag + ")))",
                        null, null);
        if (c3 != null) {
            int c3Counter = c3.getCount();
            if (c3Counter > 0) {
                if (c3Counter > 1) {
                    Rlog.w(LOG_TAG, "something wrong, get more results for 1 conflict SID: " + c3);
                }
                if (DBG) Rlog.d(LOG_TAG, "Query conflict sid returned the cursor " + c3);
                c3.moveToFirst();
                tmpMcc = c3.getInt(0);
                if (DBG) {
                    Rlog.d(LOG_TAG, "MCC found in mcc_lookup_table. Return tmpMcc = " + tmpMcc);
                }
                if (!isNitzTimeZone) {
                    // time zone is not accurate, it may get wrong mcc, ignore it.
                    if (DBG) {
                        Rlog.d(LOG_TAG, "time zone is not accurate, mcc may be " + tmpMcc);
                    }
                    tmpMcc = 0;
                }
                c3.close();
                return tmpMcc;
            } else {
                c3.close();
            }
        }

        // if there is no conflict, then check if SID is in mcc_sid_range.
        String projection5[] = {MccSidRange.MCC};
        Cursor c5 = resolver.query(MccSidRange.CONTENT_URI, projection5,
                MccSidRange.RANGE_LOW + "<=" + sid + " and " +
                MccSidRange.RANGE_HIGH + ">=" + sid,
                null, null);
        if (c5 != null) {
            if (c5.getCount() > 0) {
                if (DBG) Rlog.d(LOG_TAG, "Query Range returned the cursor " + c5);
                c5.moveToFirst();
                tmpMcc = c5.getInt(0);
                if (DBG) Rlog.d(LOG_TAG, "SID found in mcc_sid_range. Return tmpMcc = " + tmpMcc);
                c5.close();
                return tmpMcc;
            }
            c5.close();
        }
        if (DBG) Rlog.d(LOG_TAG, "SID NOT found in mcc_sid_range.");

        if (DBG) Rlog.d(LOG_TAG, "Exit getMccByOtherFactors. Return tmpMcc =  " + tmpMcc);
        // If unknown MCC still could not be resolved,
        return tmpMcc;
    }

    /**
     *  Gets country information with given MCC.
    */
    public String getIddByMcc(int mcc) {
        if (DBG) Rlog.d(LOG_TAG, "Enter getHbpcdInfoByMCC.");
        String idd = "";

        Cursor c = null;

        String projection[] = {MccIdd.IDD};
        Cursor cur = resolver.query(MccIdd.CONTENT_URI, projection,
                MccIdd.MCC + "=" + mcc, null, null);
        if (cur != null) {
            if (cur.getCount() > 0) {
                if (DBG) Rlog.d(LOG_TAG, "Query Idd returned the cursor " + cur);
                // TODO: for those country having more than 1 IDDs, need more information
                // to decide which IDD would be used. currently just use the first 1.
                cur.moveToFirst();
                idd = cur.getString(0);
                if (DBG) Rlog.d(LOG_TAG, "IDD = " + idd);

            }
            cur.close();
        }
        if (c != null) c.close();

        if (DBG) Rlog.d(LOG_TAG, "Exit getHbpcdInfoByMCC.");
        return idd;
    }
}
