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

import com.android.locationtracker.data.TrackerEntry.EntryType;

/**
 * Formats tracker data as CSV output
 */
class CSVFormatter implements IFormatter {

    private static final String DELIMITER = ", ";

    public String getHeader() {
        StringBuilder csvBuilder = new StringBuilder();
        for (String col : TrackerEntry.ATTRIBUTES) {
            // skip type and id column
            if (!TrackerEntry.ENTRY_TYPE.equals(col) &&
                !TrackerEntry.ID_COL.equals(col)) {
                csvBuilder.append(col);
                csvBuilder.append(DELIMITER);
            }
        }
        csvBuilder.append("\n");
        return csvBuilder.toString();
    }

    public String getOutput(TrackerEntry entry) {
        StringBuilder rowOutput = new StringBuilder();
        // these must match order of columns added in getHeader
        rowOutput.append(entry.getTimestamp());
        rowOutput.append(DELIMITER);
        rowOutput.append(entry.getTag());
        rowOutput.append(DELIMITER);
        //rowOutput.append(entry.getType());
        //rowOutput.append(DELIMITER);
        if (entry.getType() == EntryType.LOCATION_TYPE) {
            if (entry.getLocation().hasAccuracy()) {
                rowOutput.append(entry.getLocation().getAccuracy());
            }
            rowOutput.append(DELIMITER);
            rowOutput.append(entry.getLocation().getLatitude());
            rowOutput.append(DELIMITER);
            rowOutput.append(entry.getLocation().getLongitude());
            rowOutput.append(DELIMITER);
            if (entry.getLocation().hasAltitude()) {
                rowOutput.append(entry.getLocation().getAltitude());
            }
            rowOutput.append(DELIMITER);
            if (entry.getLocation().hasSpeed()) {
                rowOutput.append(entry.getLocation().getSpeed());
            }
            rowOutput.append(DELIMITER);
            if (entry.getLocation().hasBearing()) {
                rowOutput.append(entry.getLocation().getBearing());
            }
            rowOutput.append(DELIMITER);
            rowOutput.append(entry.getDistFromNetLocation());
            rowOutput.append(DELIMITER);
            rowOutput.append(DateUtils.getKMLTimestamp(entry.getLocation()
                    .getTime()));
            rowOutput.append(DELIMITER);
        }
        rowOutput.append(entry.getLogMsg());
        rowOutput.append("\n");
        return rowOutput.toString();
    }

    public String getFooter() {
        // not needed, return empty string
        return "";
    }
}
