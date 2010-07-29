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

package com.android.locationtracker;

import com.android.locationtracker.data.DateUtils;
import com.android.locationtracker.data.TrackerDataHelper;
import com.android.locationtracker.data.TrackerListHelper;

import android.app.AlertDialog;
import android.app.ListActivity;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.database.Cursor;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;

/**
 * Activity for location tracker service
 *
 * Contains facilities for starting and
 * stopping location tracker service, as well as displaying the current location
 * data
 *
 * Future enhancements:
 *   - export data as dB
 *   - enable/disable "start service" and "stop service" menu items as
 *     appropriate
 */
public class TrackerActivity extends ListActivity {

    static final String LOG_TAG = "LocationTracker";

    private TrackerListHelper mDataHelper;

    /**
     * Retrieves and displays the currently logged location data from file
     *
     * @param icicle
     */
    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mDataHelper = new TrackerListHelper(this);
        mDataHelper.bindListUI(R.layout.entrylist_item);
    }

    /**
     * Builds the menu
     *
     * @param menu - menu to add items to
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater menuInflater = getMenuInflater();
        menuInflater.inflate(R.menu.menu, menu);
        return true;
    }

    /**
     * Handles menu item selection
     *
     * @param item - the selected menu item
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.start_service_menu: {
                startService(new Intent(TrackerActivity.this,
                        TrackerService.class));
                break;
            }
            case R.id.stop_service_menu: {
                stopService(new Intent(TrackerActivity.this,
                        TrackerService.class));
                break;
            }
            case R.id.settings_menu: {
                launchSettings();
                break;
            }
            case R.id.export_kml: {
                exportKML();
                break;
            }
            case R.id.export_csv: {
                exportCSV();
                break;
            }
            case R.id.clear_data_menu: {
                clearData();
                break;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    private void clearData() {
        Runnable clearAction = new Runnable() {
            public void run() {
                TrackerDataHelper helper =
                    new TrackerDataHelper(TrackerActivity.this);
                helper.deleteAll();
            }
        };
        showConfirm(R.string.delete_confirm, clearAction);
    }

    private void showConfirm(int textId, final Runnable onConfirmAction) {
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(this);
        dialogBuilder.setTitle(R.string.confirm_title);
        dialogBuilder.setMessage(textId);
        dialogBuilder.setPositiveButton(android.R.string.ok,
                new OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                onConfirmAction.run();
            }
        });
        dialogBuilder.setNegativeButton(android.R.string.cancel,
                new OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                // do nothing
            }
        });
        dialogBuilder.show();
    }

    private void exportCSV() {
        String exportFileName = getUniqueFileName("csv");
        exportFile(null, exportFileName, new TrackerDataHelper(this,
                TrackerDataHelper.CSV_FORMATTER));
    }

    private void exportKML() {
        String exportFileName = getUniqueFileName(
                LocationManager.NETWORK_PROVIDER + ".kml");
        exportFile(LocationManager.NETWORK_PROVIDER, exportFileName,
                new TrackerDataHelper(this, TrackerDataHelper.KML_FORMATTER));
        exportFileName = getUniqueFileName(
                LocationManager.GPS_PROVIDER + ".kml");
        exportFile(LocationManager.GPS_PROVIDER, exportFileName,
                new TrackerDataHelper(this, TrackerDataHelper.KML_FORMATTER));
    }

    private void exportFile(String tagFilter,
                            String exportFileName,
                            TrackerDataHelper trackerData) {
        BufferedWriter exportWriter = null;
        Cursor cursor = trackerData.query(tagFilter);
        try {
            exportWriter = new BufferedWriter(new FileWriter(exportFileName));
            exportWriter.write(trackerData.getOutputHeader());

            String line = null;

            while ((line = trackerData.getNextOutput(cursor)) != null) {
                exportWriter.write(line);
            }
            exportWriter.write(trackerData.getOutputFooter());
            Toast.makeText(this, "Successfully exported data to " +
                    exportFileName, Toast.LENGTH_SHORT).show();

        } catch (IOException e) {
            Toast.makeText(this, "Error exporting file: " +
                    e.getLocalizedMessage(), Toast.LENGTH_SHORT).show();

            Log.e(LOG_TAG, "Error exporting file", e);
        } finally {
            closeWriter(exportWriter);
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private void closeWriter(Writer exportWriter) {
        if (exportWriter != null) {
            try {
                exportWriter.close();
            } catch (IOException e) {
                Log.e(LOG_TAG, "error closing file", e);
            }
        }
    }

    private String getUniqueFileName(String ext) {
        File dir = new File(Environment.getExternalStorageDirectory() + "/locationtracker");
        if (!dir.exists()) {
            dir.mkdir();
        }
        return dir + "/tracking-" + DateUtils.getCurrentTimestamp() + "." + ext;
    }

    private void launchSettings() {
        Intent settingsIntent = new Intent();
        settingsIntent.setClass(this, SettingsActivity.class);
        startActivity(settingsIntent);
    }
}
