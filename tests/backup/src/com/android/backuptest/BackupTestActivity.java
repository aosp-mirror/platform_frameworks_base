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
import android.backup.BackupManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

public class BackupTestActivity extends ListActivity
{
    static final String TAG = "BackupTestActivity";

    static final String PREF_GROUP_SETTINGS = "settings";
    static final String PREF_KEY = "pref";

    Test[] mTests = new Test[] {
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
                BackupManager bm = new BackupManager(BackupTestActivity.this);
                bm.dataChanged();
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
    
}

