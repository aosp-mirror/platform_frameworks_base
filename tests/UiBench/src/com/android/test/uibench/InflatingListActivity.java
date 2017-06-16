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

import android.content.ComponentName;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListAdapter;

import com.android.test.uibench.listview.CompatListActivity;

public class InflatingListActivity extends CompatListActivity {
    private static final String PACKAGE_NAME = "com.android.test.uibench";
    private static final ComponentName LATIN_WORDS =
            ComponentName.createRelative(PACKAGE_NAME, ".InflatingListActivity");
    private static final ComponentName EMOJI =
            ComponentName.createRelative(PACKAGE_NAME, ".InflatingEmojiListActivity");
    private static final ComponentName HAN =
            ComponentName.createRelative(PACKAGE_NAME, ".InflatingHanListActivity");
    private static final ComponentName LONG_STRING =
            ComponentName.createRelative(PACKAGE_NAME, ".InflatingLongStringListActivity");
    @Override
    protected ListAdapter createListAdapter() {
        final ComponentName targetComponent = getIntent().getComponent();

        final String[] testStrings;
        if (targetComponent.equals(LATIN_WORDS)) {
            testStrings = TextUtils.buildSimpleStringList();
        } else if (targetComponent.equals(EMOJI)) {
            testStrings = TextUtils.buildEmojiStringList();
        } else if (targetComponent.equals(HAN)) {
            testStrings = TextUtils.buildHanStringList();
        } else if (targetComponent.equals(LONG_STRING)) {
            testStrings = TextUtils.buildLongStringList();
        } else {
            throw new RuntimeException("Unknown Component: " + targetComponent);
        }

        return new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, testStrings) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                // pathological getView behavior: drop convertView on the floor to force inflation
                return super.getView(position, null, parent);
            }
        };
    }
}
