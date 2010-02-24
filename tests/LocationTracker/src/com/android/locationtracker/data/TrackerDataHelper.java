/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.locationtracker.data;

import android.content.Context;
import android.database.Cursor;
import android.location.Location;

/**
 * Helper class for writing and retrieving data using the TrackerProvider
 * content provider
 *
 */
public class TrackerDataHelper {

    private Context mContext;
    /** formats data output */
    protected IFormatter mFormatter;

    /** formats output as Comma separated value CSV file */
    public static final IFormatter CSV_FORMATTER = new CSVFormatter();
    /** formats output as KML file */
    public static final IFormatter KML_FORMATTER = new KMLFormatter();
    /** provides no formatting */
    public static final IFormatter NO_FORMATTER = new IFormatter() {
        public String getFooter() {
            return "";
        }

        public String getHeader() {
            return "";
        }

        public String getOutput(TrackerEntry entry) {
            return "";
        }
    };

    /**
     * Creates instance
     *
     * @param context - content context
     * @param formatter - formats the output from the get*Output* methods
     */
    public TrackerDataHelper(Context context, IFormatter formatter) {
        mContext = context;
        mFormatter = formatter;
    }

    /**
     * Creates a instance with no output formatting capabilities. Useful for
     * clients that require write-only access
     */
    public TrackerDataHelper(Context context) {
        this(context, NO_FORMATTER);
    }

    /**
     * insert given TrackerEntry into content provider
     */
    void writeEntry(TrackerEntry entry) {
        mContext.getContentResolver().insert(TrackerProvider.CONTENT_URI,
                entry.getAsContentValues());
    }

    /**
     * insert given location into tracker data
     */
    public void writeEntry(Location loc, float distFromNetLoc) {
        writeEntry(TrackerEntry.createEntry(loc, distFromNetLoc));
    }

    /**
     * insert given log message into tracker data
     */
    public void writeEntry(String tag, String logMsg) {
        writeEntry(TrackerEntry.createEntry(tag, logMsg));
    }

    /**
     * Deletes all tracker entries
     */
    public void deleteAll() {
        mContext.getContentResolver().delete(TrackerProvider.CONTENT_URI, null,
                null);
    }

    /**
     * Query tracker data, filtering by given tag
     *
     * @param tag
     * @return Cursor to data
     */
    public Cursor query(String tag, int limit) {
        String selection = (tag == null ? null : TrackerEntry.TAG + "=?");
        String[] selectionArgs = (tag == null ? null : new String[] {tag});
        Cursor cursor = mContext.getContentResolver().query(
                TrackerProvider.CONTENT_URI, TrackerEntry.ATTRIBUTES,
                selection, selectionArgs, null);
        if (cursor == null) {
            return cursor;
        }
        int pos = (cursor.getCount() < limit ? 0 : cursor.getCount() - limit);
        cursor.moveToPosition(pos);
        return cursor;
    }

    /**
     * Retrieves a cursor that starts at the last limit rows
     *
     * @param limit
     * @return a cursor, null if bad things happened
     */
    public Cursor query(int limit) {
        return query(null, limit);
    }

    /**
     * Query tracker data, filtering by given tag. mo limit to number of rows
     * returned
     *
     * @param tag
     * @return Cursor to data
     */
    public Cursor query(String tag) {
        return query(tag, Integer.MAX_VALUE);
    }

    /**
     * Returns the output header particular to the associated formatter
     */
    public String getOutputHeader() {
        return mFormatter.getHeader();
    }

    /**
     * Returns the output footer particular to the associated formatter
     */
    public String getOutputFooter() {
        return mFormatter.getFooter();
    }

    /**
     * Helper method which converts row referenced by given cursor to a string
     * output
     *
     * @param cursor
     * @return CharSequence output, null if given cursor is invalid or no more
     *         data
     */
    public String getNextOutput(Cursor cursor) {
        if (cursor == null || cursor.isAfterLast()) {
            return null;
        }
        String output = mFormatter.getOutput(TrackerEntry.createEntry(cursor));
        cursor.moveToNext();
        return output;
    }
}
