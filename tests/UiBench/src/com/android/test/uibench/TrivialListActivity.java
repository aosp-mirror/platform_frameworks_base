/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.test.uibench;

import android.os.Bundle;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.ListFragment;
import android.support.v7.app.AppCompatActivity;
import android.widget.ArrayAdapter;

import java.util.Random;

public class TrivialListActivity extends AppCompatActivity {
    static final int STRING_LENGTH = 10;

    static String[] buildStringList() {
        String[] strings = new String[200];
        Random random = new Random(0);
        for (int i = 0; i < strings.length; i++) {
            String result = "";
            for (int j = 0; j < STRING_LENGTH; j++) {
                // add random letter
                result += (char)(random.nextInt(26) + 65);
            }
            strings[i] = result;
        }
        return strings;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FragmentManager fm = getSupportFragmentManager();
        if (fm.findFragmentById(android.R.id.content) == null) {
            ListFragment listFragment = new ListFragment();
            listFragment.setListAdapter(new ArrayAdapter<>(TrivialListActivity.this,
                    android.R.layout.simple_list_item_1, buildStringList()));
            fm.beginTransaction().add(android.R.id.content, listFragment).commit();
        }
    }
}
