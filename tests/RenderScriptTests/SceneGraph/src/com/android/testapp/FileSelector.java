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

package com.android.testapp;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.List;

import android.app.ListActivity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;

/**
 * A list view where the last item the user clicked is placed in
 * the "activated" state, causing its background to highlight.
 */
public class FileSelector extends ListActivity {

    File[] mCurrentSubList;
    File mCurrentFile;

    class DAEFilter implements FileFilter {
        public boolean accept(File file) {
            if (file.isDirectory()) {
                return true;
            }
            return file.getName().endsWith(".dae");
        }
    }

    private void populateList(File file) {

        mCurrentFile = file;
        setTitle(mCurrentFile.getAbsolutePath() + "/*.dae");
        List<String> names = new ArrayList<String>();
        names.add("..");

        mCurrentSubList = mCurrentFile.listFiles(new DAEFilter());

        if (mCurrentSubList != null) {
            for (int i = 0; i < mCurrentSubList.length; i ++) {
                String fileName = mCurrentSubList[i].getName();
                if (mCurrentSubList[i].isDirectory()) {
                    fileName = "/" + fileName;
                }
                names.add(fileName);
            }
        }

        // Use the built-in layout for showing a list item with a single
        // line of text whose background is changes when activated.
        setListAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_activated_1, names));
        getListView().setTextFilterEnabled(true);

        // Tell the list view to show one checked/activated item at a time.
        getListView().setChoiceMode(ListView.CHOICE_MODE_SINGLE);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        populateList(new File("/sdcard/"));
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        if (position == 0) {
            File parent = mCurrentFile.getParentFile();
            if (parent == null) {
                return;
            }
            populateList(parent);
            return;
        }

        // the first thing in list is parent directory
        File selectedFile = mCurrentSubList[position - 1];
        if (selectedFile.isDirectory()) {
            populateList(selectedFile);
            return;
        }

        Intent resultIntent = new Intent();
        resultIntent.setData(Uri.fromFile(selectedFile));
        setResult(RESULT_OK, resultIntent);
        finish();
    }

}
