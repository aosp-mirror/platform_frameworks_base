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

package com.android.locationtracker.data;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Provides formatting date as string utilities
 */
public class DateUtils {

    private DateUtils() {

    }

    /**
     * Returns timestamp given by param in KML format ie yyyy-mm-ddThh:mm:ssZ,
     * where T is the separator between the date and the time and the time zone
     * is Z (for UTC)
     *
     * @return KML timestamp as String
     */
    public static String getKMLTimestamp(long when) {
        TimeZone tz = TimeZone.getTimeZone("GMT");
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(when);
        return String.format("%tY-%tm-%tdT%tH:%tM:%tSZ", c, c, c, c, c, c);
    }

    /**
     * Helper version of getKMLTimestamp, that returns timestamp for current
     * time
     */
    public static String getCurrentKMLTimestamp() {
        return getKMLTimestamp(System.currentTimeMillis());
    }

    /**
     * Returns timestamp in following format: yyyy-mm-dd-hh-mm-ss
     */
    public static String getCurrentTimestamp() {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(System.currentTimeMillis());
        return String.format("%tY-%tm-%td-%tH-%tM-%tS", c, c, c, c, c, c);
    }
}
