/*
**
** Copyright 2014, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/
package com.android.internal.telephony;

import android.net.Uri;
import android.provider.BaseColumns;

/**
 * @hide
 */
public class HbpcdLookup {
    public static final String AUTHORITY = "hbpcd_lookup";

    public static final Uri CONTENT_URI =
        Uri.parse("content://" + AUTHORITY);

    public static final String PATH_MCC_IDD = "idd";
    public static final String PATH_MCC_LOOKUP_TABLE = "lookup";
    public static final String PATH_MCC_SID_CONFLICT = "conflict";
    public static final String PATH_MCC_SID_RANGE = "range";
    public static final String PATH_NANP_AREA_CODE = "nanp";
    public static final String PATH_ARBITRARY_MCC_SID_MATCH = "arbitrary";
    public static final String PATH_USERADD_COUNTRY = "useradd";

    public static final String ID = "_id";
    public static final int IDINDEX = 0;

    /**
     * @hide
     */
    public static class MccIdd implements BaseColumns {
        public static final Uri CONTENT_URI =
            Uri.parse("content://" + AUTHORITY + "/" + PATH_MCC_IDD);
        public static final String DEFAULT_SORT_ORDER = "MCC ASC";

        public static final String MCC = "MCC";
        public static final String IDD = "IDD";

    }

    /**
     * @hide
     */
    public static class MccLookup implements BaseColumns {
        public static final Uri CONTENT_URI =
            Uri.parse("content://" + AUTHORITY + "/" + PATH_MCC_LOOKUP_TABLE);
        public static final String DEFAULT_SORT_ORDER = "MCC ASC";

        public static final String MCC = "MCC";
        public static final String COUNTRY_CODE = "Country_Code";
        public static final String COUNTRY_NAME = "Country_Name";
        public static final String NDD = "NDD";
        public static final String NANPS = "NANPS";
        public static final String GMT_OFFSET_LOW = "GMT_Offset_Low";
        public static final String GMT_OFFSET_HIGH = "GMT_Offset_High";
        public static final String GMT_DST_LOW = "GMT_DST_Low";
        public static final String GMT_DST_HIGH = "GMT_DST_High";

    }

    /**
     * @hide
     */
    public static class MccSidConflicts implements BaseColumns {
        public static final Uri CONTENT_URI =
            Uri.parse("content://" + AUTHORITY + "/" + PATH_MCC_SID_CONFLICT);
        public static final String DEFAULT_SORT_ORDER = "MCC ASC";

        public static final String MCC = "MCC";
        public static final String SID_CONFLICT = "SID_Conflict";

    }

    /**
     * @hide
     */
    public static class MccSidRange implements BaseColumns {
        public static final Uri CONTENT_URI =
            Uri.parse("content://" + AUTHORITY + "/" + PATH_MCC_SID_RANGE);
        public static final String DEFAULT_SORT_ORDER = "MCC ASC";

        public static final String MCC = "MCC";
        public static final String RANGE_LOW = "SID_Range_Low";
        public static final String RANGE_HIGH = "SID_Range_High";
    }

    /**
     * @hide
     */
    public static class ArbitraryMccSidMatch implements BaseColumns {
        public static final Uri CONTENT_URI =
            Uri.parse("content://" + AUTHORITY + "/" + PATH_ARBITRARY_MCC_SID_MATCH);
        public static final String DEFAULT_SORT_ORDER = "MCC ASC";

        public static final String MCC = "MCC";
        public static final String SID = "SID";

    }

    /**
     * @hide
     */
    public static class NanpAreaCode implements BaseColumns {
        public static final Uri CONTENT_URI =
            Uri.parse("content://" + AUTHORITY + "/" + PATH_NANP_AREA_CODE);
        public static final String DEFAULT_SORT_ORDER = "Area_Code ASC";

        public static final String AREA_CODE = "Area_Code";
    }
}
