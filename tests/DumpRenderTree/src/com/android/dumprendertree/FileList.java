/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.dumprendertree;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.File;

import android.app.AlertDialog;
import android.app.ListActivity;
import android.content.DialogInterface;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.os.Bundle;
import android.os.Environment;


public abstract class FileList extends ListActivity
{
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		switch (keyCode)
		{
			case KeyEvent.KEYCODE_DPAD_LEFT:
				if (mPath.length() > mBaseLength) {
					File f = new File(mPath);
					mFocusFile = f.getName();
					mFocusIndex = 0;
					f = f.getParentFile();
					mPath = f.getPath();
					updateList();
					return true;
				}
				break;

			case KeyEvent.KEYCODE_DPAD_RIGHT:
				{
					Map map = (Map) getListView().getItemAtPosition(getListView().getSelectedItemPosition());
					String path = (String)map.get("path");
					if ((new File(path)).isDirectory()) {
						mPath = path;
				        mFocusFile = null;
						updateList();
					} else {
						processFile(path, false);
					}
                    return true;
				}

			default:
				break;
		}
		return super.onKeyDown(keyCode, event);
	}

	public void onCreate(Bundle icicle)
    {
        super.onCreate(icicle);
        setupPath();
        updateList();
    }

    protected List getData()
    {
        List myData = new ArrayList<HashMap>();

        File f = new File(mPath);
        if (!f.exists()) {
        	addItem(myData, "!LayoutTests path missing!", "");
        	return myData;
        }
        String[] files = f.list();
        Arrays.sort(files);

        for (int i = 0; i < files.length; i++) {
        	StringBuilder sb = new StringBuilder(mPath);
        	sb.append(File.separatorChar);
        	sb.append(files[i]);
        	String path = sb.toString();
        	File c = new File(path);
        	if (fileFilter(c)) {
	        	if (c.isDirectory()) {
	        		addItem(myData, "<"+files[i]+">", path);
	        		if (mFocusFile != null && mFocusFile.equals(files[i]))
	        			mFocusIndex = myData.size()-1;
	        	}
	        	else
	        	    addItem(myData, files[i], path);
        	}
        }

        return myData;
    }

    protected void addItem(List<Map> data, String name, String path)
    {
        HashMap temp = new HashMap();
        temp.put("title", name);
        temp.put("path", path);
        data.add(temp);
    }

    protected void onListItemClick(ListView l, View v, int position, long id)
    {
        Map map = (Map) l.getItemAtPosition(position);
        final String path = (String)map.get("path");

        if ((new File(path)).isDirectory()) {
            final CharSequence[] items = {"Open", "Run"};
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Select an Action");
            builder.setSingleChoiceItems(items, -1,
                    new DialogInterface.OnClickListener(){
                public void onClick(DialogInterface dialog, int which) {
                    switch (which) {
                        case OPEN_DIRECTORY:
                            dialog.dismiss();
                            mPath = path;
                            mFocusFile = null;
                            updateList();
                            break;
                        case RUN_TESTS:
                            dialog.dismiss();
                            processDirectory(path, false);
                            break;
                    }
                }
            });
            builder.create().show();
        } else {
            processFile(path, false);
        }
    }

    /*
     * This function is called when the user has selected a directory in the
     * list and wants to perform an action on it instead of navigating into
     * the directory.
     */
    abstract void processDirectory(String path, boolean selection);
    /*
     * This function is called when the user has selected a file in the
     * file list. The selected file could be a file or a directory.
     * The flag indicates if this was from a selection or not.
     */
    abstract void processFile(String filename, boolean selection);

    /*
     * This function is called when the file list is being built. Return
     * true if the file is to be added to the file list.
     */
    abstract boolean fileFilter(File f);

    protected void updateList() {
        setListAdapter(new SimpleAdapter(this,
                getData(),
                android.R.layout.simple_list_item_1,
                new String[] {"title"},
                new int[] {android.R.id.text1}));
        String title = mPath; //.substring(mBaseLength-11); // show the word LayoutTests
        setTitle(title);
        getListView().setSelection(mFocusIndex);
    }

    protected void setupPath() {
        mPath = Environment.getExternalStorageDirectory() + "/webkit/layout_tests";
        mBaseLength = mPath.length();
    }

    protected String mPath;
    protected int mBaseLength;
    protected String mFocusFile;
    protected int mFocusIndex;

    private final static int OPEN_DIRECTORY = 0;
    private final static int RUN_TESTS = 1;

}
