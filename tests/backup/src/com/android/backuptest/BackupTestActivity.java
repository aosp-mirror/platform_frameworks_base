/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.backuptest;

import android.app.ListActivity;
import android.app.backup.BackupHelperDispatcher;
import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.app.backup.BackupManager;
import android.app.backup.FileBackupHelper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.PrintStream;
import java.text.DateFormat;
import java.util.Date;

public class BackupTestActivity extends ListActivity
{
    static final String TAG = "BackupTestActivity";

    static final String PREF_GROUP_SETTINGS = "settings";
    static final String PREF_KEY = "pref";
    static final String FILE_NAME = "file.txt";

    BackupManager sBm = new BackupManager(this);

    Test[] mTests = new Test[] {
        new Test("Show File") {
            void run() {
                StringBuffer str = new StringBuffer();
                str.append("Text is:");
                BufferedReader reader = null;
                try {
                    reader = new BufferedReader(new InputStreamReader(openFileInput(FILE_NAME)));
                    while (reader.ready()) {
                        str.append("\n");
                        str.append(reader.readLine());
                    }
                } catch (IOException ex) {
                    str.append("ERROR: ");
                    str.append(ex.toString());
                }
                Log.d(TAG, str.toString());
                Toast.makeText(BackupTestActivity.this, str, Toast.LENGTH_SHORT).show();
            }
        },
        new Test("Append to File") {
            void run() {
                PrintStream output = null;
                try {
                    output = new PrintStream(openFileOutput(FILE_NAME, MODE_APPEND));
                    DateFormat formatter = DateFormat.getDateTimeInstance();
                    output.println(formatter.format(new Date()));
                    output.close();
                } catch (IOException ex) {
                    if (output != null) {
                        output.close();
                    }
                }
                sBm.dataChanged();
            }
        },
        new Test("Clear File") {
            void run() {
                PrintStream output = null;
                try {
                    output = new PrintStream(openFileOutput(FILE_NAME, MODE_PRIVATE));
                    output.close();
                } catch (IOException ex) {
                    if (output != null) {
                        output.close();
                    }
                }
                sBm.dataChanged();
            }
        },
        new Test("Poke") {
            void run() {
                sBm.dataChanged();
            }
        },
        new Test("Show Shared Pref") {
            void run() {
                SharedPreferences prefs = getSharedPreferences(PREF_GROUP_SETTINGS, MODE_PRIVATE);
                int val = prefs.getInt(PREF_KEY, 0);
                String str = "'" + PREF_KEY + "' is " + val;
                Log.d(TAG, str);
                Toast.makeText(BackupTestActivity.this, str, Toast.LENGTH_SHORT).show();
            }
        },
        new Test("Increment Shared Pref") {
            void run() {
                SharedPreferences prefs = getSharedPreferences(PREF_GROUP_SETTINGS, MODE_PRIVATE);
                int val = prefs.getInt(PREF_KEY, 0);
                SharedPreferences.Editor editor = prefs.edit();
                editor.putInt(PREF_KEY, val+1);
                editor.commit();
                sBm.dataChanged();
            }
        },
        new Test("Backup Helpers") {
            void run() {
                try {
                    writeFile("a", "a\naa", MODE_PRIVATE);
                    writeFile("empty", "", MODE_PRIVATE);

                    ParcelFileDescriptor state = ParcelFileDescriptor.open(
                            new File(getFilesDir(), "state"),
                            ParcelFileDescriptor.MODE_READ_WRITE|ParcelFileDescriptor.MODE_CREATE|
                            ParcelFileDescriptor.MODE_TRUNCATE);
                    FileBackupHelper h = new FileBackupHelper(BackupTestActivity.this,
                            new String[] { "a", "empty" });
                    FileOutputStream dataFile = openFileOutput("backup_test", MODE_WORLD_READABLE);
                    BackupDataOutput data = new BackupDataOutput(dataFile.getFD());
                    h.performBackup(null, data, state);
                    dataFile.close();
                    state.close();
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            }
        },
        new Test("Restore Helpers") {
            void run() {
                try {
                    BackupHelperDispatcher dispatch = new BackupHelperDispatcher();
                    dispatch.addHelper("", new FileBackupHelper(BackupTestActivity.this,
                            new String[] { "a", "empty" }));
                    FileInputStream dataFile = openFileInput("backup_test");
                    BackupDataInput data = new BackupDataInput(dataFile.getFD());
                    ParcelFileDescriptor state = ParcelFileDescriptor.open(
                            new File(getFilesDir(), "restore_state"),
                            ParcelFileDescriptor.MODE_READ_WRITE|ParcelFileDescriptor.MODE_CREATE|
                            ParcelFileDescriptor.MODE_TRUNCATE);
                    // TODO: a more plausable synthetic stored-data version number
                    dispatch.performRestore(data, 0, state);
                    dataFile.close();
                    state.close();
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
    };

    abstract class Test {
        String name;
        Test(String n) {
            name = n;
        }
        abstract void run();
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        String[] labels = new String[mTests.length];
        for (int i=0; i<mTests.length; i++) {
            labels[i] = mTests[i].name;
        }

        setListAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels));
    }

    @Override
    public void onListItemClick(ListView l, View v, int position, long id)
    {
        Test t = mTests[position];
        Log.d(TAG, "Test: " + t.name);
        t.run();
    }
    
    void writeFile(String name, String contents, int mode) {
        try {
            PrintStream out = new PrintStream(openFileOutput(name, mode));
            out.print(contents);
            out.close();
        } catch (FileNotFoundException ex) {
            throw new RuntimeException(ex);
        }
    }
}

