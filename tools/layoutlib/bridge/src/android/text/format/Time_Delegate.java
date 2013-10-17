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

package android.text.format;

import java.util.Calendar;
import java.util.UnknownFormatConversionException;
import java.util.regex.Pattern;

import com.android.ide.common.rendering.api.LayoutLog;
import com.android.layoutlib.bridge.Bridge;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

/**
 * Delegate used to provide new implementation for native methods of {@link Time}
 *
 * Through the layoutlib_create tool, some native methods of Time have been replaced by calls to
 * methods of the same name in this delegate class.
 */
public class Time_Delegate {

    // Regex to match odd number of '%'.
    private static final Pattern p = Pattern.compile("(?<!%)(%%)*%(?!%)");

    @LayoutlibDelegate
    /*package*/ static String format1(Time thisTime, String format) {

        try {
            // Change the format by adding changing '%' to "%1$t". This is required to tell the
            // formatter which argument to use from the argument list. '%%' is left as is. In the
            // replacement string, $0 refers to matched pattern. \\1 means '1', written this way to
            // separate it from 0. \\$ means '$', written this way to suppress the special meaning
            // of $.
            return String.format(
                    p.matcher(format).replaceAll("$0\\1\\$t"),
                    timeToCalendar(thisTime, Calendar.getInstance()));
        } catch (UnknownFormatConversionException e) {
            Bridge.getLog().fidelityWarning(LayoutLog.TAG_STRFTIME, "Unrecognized format", e, format);
            return format;
        }
    }

    private static Calendar timeToCalendar(Time time, Calendar calendar) {
        calendar.set(time.year, time.month, time.monthDay, time.hour, time.minute, time.second);
        return calendar;
    }

}
