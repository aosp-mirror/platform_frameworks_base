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

import android.app.ListActivity;
import android.content.Context;
import android.database.Cursor;
import android.view.View;
import android.widget.ResourceCursorAdapter;
import android.widget.TextView;

import com.android.locationtracker.R;

/**
 * Used to bind Tracker data to a list view UI
 */
public class TrackerListHelper extends TrackerDataHelper {

    private ListActivity mActivity;

    // sort entries by most recent first
    private static final String SORT_ORDER = TrackerEntry.ID_COL + " DESC";

    public TrackerListHelper(ListActivity activity) {
        super(activity, TrackerDataHelper.CSV_FORMATTER);
        mActivity = activity;
    }

    /**
     * Helper method for binding the list activities UI to the tracker data
     * Tracker data will be sorted in most-recent first order
     * Will enable automatic UI changes as tracker data changes
     *
     * @param layout - layout to populate data
     */
    public void bindListUI(int layout) {
        Cursor cursor = mActivity.managedQuery(TrackerProvider.CONTENT_URI,
                TrackerEntry.ATTRIBUTES, null, null, SORT_ORDER);
        // Used to map tracker entries from the database to views
        TrackerAdapter adapter = new TrackerAdapter(mActivity, layout, cursor);
        mActivity.setListAdapter(adapter);
        cursor.setNotificationUri(mActivity.getContentResolver(),
                TrackerProvider.CONTENT_URI);

    }

    private class TrackerAdapter extends ResourceCursorAdapter {

        public TrackerAdapter(Context context, int layout, Cursor c) {
            super(context, layout, c);
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            final TextView v = (TextView) view
                    .findViewById(R.id.entrylist_item);
            String rowText = mFormatter.getOutput(TrackerEntry
                    .createEntry(cursor));
            v.setText(rowText);
        }
    }
}
