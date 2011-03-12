/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.hugebackup;

import android.app.Activity;
import android.app.backup.BackupManager;
import android.app.backup.RestoreObserver;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.RadioGroup;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Deliberately back up waaaaaaay too much data.  Cloned with some alterations
 * from the Backup/Restore sample application.
 */
public class HugeBackupActivity extends Activity {
    static final String TAG = "HugeBackupActivity";

    /**
     * We serialize access to our persistent data through a global static
     * object.  This ensures that in the unlikely event of the our backup/restore
     * agent running to perform a backup while our UI is updating the file, the
     * agent will not accidentally read partially-written data.
     *
     * <p>Curious but true: a zero-length array is slightly lighter-weight than
     * merely allocating an Object, and can still be synchronized on.
     */
    static final Object[] sDataLock = new Object[0];

    /** Also supply a global standard file name for everyone to use */
    static final String DATA_FILE_NAME = "saved_data";

    /** The various bits of UI that the user can manipulate */
    RadioGroup mFillingGroup;
    CheckBox mAddMayoCheckbox;
    CheckBox mAddTomatoCheckbox;

    /** Cache a reference to our persistent data file */
    File mDataFile;

    /** Also cache a reference to the Backup Manager */
    BackupManager mBackupManager;

    /** Set up the activity and populate its UI from the persistent data. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /** Establish the activity's UI */
        setContentView(R.layout.backup_restore);

        /** Once the UI has been inflated, cache the controls for later */
        mFillingGroup = (RadioGroup) findViewById(R.id.filling_group);
        mAddMayoCheckbox = (CheckBox) findViewById(R.id.mayo);
        mAddTomatoCheckbox = (CheckBox) findViewById(R.id.tomato);

        /** Set up our file bookkeeping */
        mDataFile = new File(getFilesDir(), HugeBackupActivity.DATA_FILE_NAME);

        /** It is handy to keep a BackupManager cached */
        mBackupManager = new BackupManager(this);

        /**
         * Finally, build the UI from the persistent store
         */
        populateUI();
    }

    /**
     * Configure the UI based on our persistent data, creating the
     * data file and establishing defaults if necessary.
     */
    void populateUI() {
        RandomAccessFile file;

        // Default values in case there's no data file yet
        int whichFilling = R.id.pastrami;
        boolean addMayo = false;
        boolean addTomato = false;

        /** Hold the data-access lock around access to the file */
        synchronized (HugeBackupActivity.sDataLock) {
            boolean exists = mDataFile.exists();
            try {
                file = new RandomAccessFile(mDataFile, "rw");
                if (exists) {
                    Log.v(TAG, "datafile exists");
                    whichFilling = file.readInt();
                    addMayo = file.readBoolean();
                    addTomato = file.readBoolean();
                    Log.v(TAG, "  mayo=" + addMayo
                            + " tomato=" + addTomato
                            + " filling=" + whichFilling);
                } else {
                    // The default values were configured above: write them
                    // to the newly-created file.
                    Log.v(TAG, "creating default datafile");
                    writeDataToFileLocked(file,
                            addMayo, addTomato, whichFilling);

                    // We also need to perform an initial backup; ask for one
                    mBackupManager.dataChanged();
                }
            } catch (IOException ioe) {
            }
        }

        /** Now that we've processed the file, build the UI outside the lock */
        mFillingGroup.check(whichFilling);
        mAddMayoCheckbox.setChecked(addMayo);
        mAddTomatoCheckbox.setChecked(addTomato);

        /**
         * We also want to record the new state when the user makes changes,
         * so install simple observers that do this
         */
        mFillingGroup.setOnCheckedChangeListener(
                new RadioGroup.OnCheckedChangeListener() {
                    public void onCheckedChanged(RadioGroup group,
                            int checkedId) {
                        // As with the checkbox listeners, rewrite the
                        // entire state file
                        Log.v(TAG, "New radio item selected: " + checkedId);
                        recordNewUIState();
                    }
                });

        CompoundButton.OnCheckedChangeListener checkListener
                = new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView,
                    boolean isChecked) {
                // Whichever one is altered, we rewrite the entire UI state
                Log.v(TAG, "Checkbox toggled: " + buttonView);
                recordNewUIState();
            }
        };
        mAddMayoCheckbox.setOnCheckedChangeListener(checkListener);
        mAddTomatoCheckbox.setOnCheckedChangeListener(checkListener);
    }

    /**
     * Handy helper routine to write the UI data to a file.
     */
    void writeDataToFileLocked(RandomAccessFile file,
            boolean addMayo, boolean addTomato, int whichFilling)
        throws IOException {
            file.setLength(0L);
            file.writeInt(whichFilling);
            file.writeBoolean(addMayo);
            file.writeBoolean(addTomato);
            Log.v(TAG, "NEW STATE: mayo=" + addMayo
                    + " tomato=" + addTomato
                    + " filling=" + whichFilling);
    }

    /**
     * Another helper; this one reads the current UI state and writes that
     * to the persistent store, then tells the backup manager that we need
     * a backup.
     */
    void recordNewUIState() {
        boolean addMayo = mAddMayoCheckbox.isChecked();
        boolean addTomato = mAddTomatoCheckbox.isChecked();
        int whichFilling = mFillingGroup.getCheckedRadioButtonId();
        try {
            synchronized (HugeBackupActivity.sDataLock) {
                RandomAccessFile file = new RandomAccessFile(mDataFile, "rw");
                writeDataToFileLocked(file, addMayo, addTomato, whichFilling);
            }
        } catch (IOException e) {
            Log.e(TAG, "Unable to record new UI state");
        }

        mBackupManager.dataChanged();
    }

    /**
     * Click handler, designated in the layout, that runs a restore of the app's
     * most recent data when the button is pressed.
     */
    public void onRestoreButtonClick(View v) {
        Log.v(TAG, "Requesting restore of our most recent data");
        mBackupManager.requestRestore(
                new RestoreObserver() {
                    public void restoreFinished(int error) {
                        /** Done with the restore!  Now draw the new state of our data */
                        Log.v(TAG, "Restore finished, error = " + error);
                        populateUI();
                    }
                }
        );
    }
}
