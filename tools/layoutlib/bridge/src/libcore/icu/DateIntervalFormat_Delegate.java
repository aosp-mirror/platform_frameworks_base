/*
 * Copyright (C) 2013 The Android Open Source Project
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

package libcore.icu;

import java.text.FieldPosition;

import com.android.ide.common.rendering.api.LayoutLog;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.impl.DelegateManager;
import com.ibm.icu.text.DateIntervalFormat;
import com.ibm.icu.util.DateInterval;
import com.ibm.icu.util.TimeZone;
import com.ibm.icu.util.ULocale;

public class DateIntervalFormat_Delegate {

    // ---- delegate manager ----
    private static final DelegateManager<DateIntervalFormat_Delegate> sManager =
            new DelegateManager<DateIntervalFormat_Delegate>(DateIntervalFormat_Delegate.class);

    // ---- delegate data ----
    private DateIntervalFormat mFormat;


    // ---- native methods ----

    /*package*/static String formatDateInterval(long address, long fromDate, long toDate) {
        DateIntervalFormat_Delegate delegate = sManager.getDelegate((int)address);
        if (delegate == null) {
            Bridge.getLog().error(LayoutLog.TAG_BROKEN,
                    "Unable for find native DateIntervalFormat", null);
            return null;
        }
        DateInterval interval = new DateInterval(fromDate, toDate);
        StringBuffer sb = new StringBuffer();
        FieldPosition pos = new FieldPosition(0);
        delegate.mFormat.format(interval, sb, pos);
        return sb.toString();
    }

    /*package*/ static long createDateIntervalFormat(String skeleton, String localeName,
            String tzName) {
        TimeZone prevDefaultTz = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(tzName));
        DateIntervalFormat_Delegate newDelegate = new DateIntervalFormat_Delegate();
        newDelegate.mFormat =
                DateIntervalFormat.getInstance(skeleton, new ULocale(localeName));
        TimeZone.setDefault(prevDefaultTz);
        return sManager.addNewDelegate(newDelegate);
    }

    /*package*/ static void destroyDateIntervalFormat(long address) {
        sManager.removeJavaReferenceFor((int)address);
    }

}
