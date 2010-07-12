/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.dumprendertree2.ui;

import com.android.dumprendertree2.FileFilter;
import com.android.dumprendertree2.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ListActivity;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An Activity that allows navigating through tests folders and choosing folders or tests to run.
 */
public class DirListActivity extends ListActivity {

    private static final String LOG_TAG = "DirListActivity";
    private static final String ROOT_DIR_PATH =
            Environment.getExternalStorageDirectory() +
            File.separator + "android" +
            File.separator + "LayoutTests";

    /** TODO: This is just a guess - think of a better way to achieve it */
    private static final int MEAN_TITLE_CHAR_SIZE = 13;

    private static final int PROGRESS_DIALOG_DELAY_MS = 200;

    /** Code for the dialog, used in showDialog and onCreateDialog */
    private static final int DIALOG_RUN_ABORT_DIR = 0;

    /** Messages codes */
    private static final int MSG_LOADED_ITEMS = 0;
    private static final int MSG_SHOW_PROGRESS_DIALOG = 1;

    /** Initialized lazily before first sProgressDialog.show() */
    private static ProgressDialog sProgressDialog;

    private ListView mListView;

    /** This is a relative path! */
    private String mCurrentDirPath;

    /**
     * TODO: This should not be a constant, but rather be configurable from somewhere.
     */
    private String mRootDirPath = ROOT_DIR_PATH;

    /**
     * A thread responsible for loading the contents of the directory from sd card
     * and sending them via Message to main thread that then loads them into
     * ListView
     */
    private class LoadListItemsThread extends Thread {
        private Handler mHandler;
        private String mRelativePath;

        public LoadListItemsThread(String relativePath, Handler handler) {
            mRelativePath = relativePath;
            mHandler = handler;
        }

        @Override
        public void run() {
            Message msg = mHandler.obtainMessage(MSG_LOADED_ITEMS);
            msg.obj = getDirList(mRelativePath);
            mHandler.sendMessage(msg);
        }
    }

    /**
     * Very simple object to use inside ListView as an item.
     */
    private static class ListItem implements Comparable<ListItem> {
        private String mRelativePath;
        private String mName;
        private boolean mIsDirectory;

        public ListItem(String relativePath, boolean isDirectory) {
            mRelativePath = relativePath;
            mName = new File(relativePath).getName();
            mIsDirectory = isDirectory;
        }

        public boolean isDirectory() {
            return mIsDirectory;
        }

        public String getRelativePath() {
            return mRelativePath;
        }

        public String getName() {
            return mName;
        }

        @Override
        public int compareTo(ListItem another) {
            return mRelativePath.compareTo(another.getRelativePath());
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ListItem)) {
                return false;
            }

            return mRelativePath.equals(((ListItem) o).getRelativePath());
        }

        @Override
        public int hashCode() {
            return mRelativePath.hashCode();
        }

    }

    /**
     * A custom adapter that sets the proper icon and label in the list view.
     */
    private static class DirListAdapter extends ArrayAdapter<ListItem> {
        private Activity mContext;
        private ListItem[] mItems;

        public DirListAdapter(Activity context, ListItem[] items) {
            super(context, R.layout.dirlist_row, items);

            mContext = context;
            mItems = items;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LayoutInflater inflater = mContext.getLayoutInflater();
            View row = inflater.inflate(R.layout.dirlist_row, null);

            TextView label = (TextView) row.findViewById(R.id.label);
            label.setText(mItems[position].getName());

            ImageView icon = (ImageView) row.findViewById(R.id.icon);
            if (mItems[position].isDirectory()) {
                icon.setImageResource(R.drawable.folder);
            } else {
                icon.setImageResource(R.drawable.runtest);
            }

            return row;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mListView = getListView();

        mListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                ListItem item = (ListItem) parent.getItemAtPosition(position);

                if (item.isDirectory()) {
                    showDir(item.getRelativePath());
                } else {
                    /** TODO: run the test */
                }
            }
        });

        mListView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                ListItem item = (ListItem) parent.getItemAtPosition(position);

                if (item.isDirectory()) {
                    Bundle arguments = new Bundle(1);
                    arguments.putString("name", item.getName());
                    arguments.putString("relativePath", item.getRelativePath());
                    showDialog(DIALOG_RUN_ABORT_DIR, arguments);
                } else {
                    /** TODO: Maybe show some info about a test? */
                }

                return true;
            }
        });

        /** All the paths are relative to test root dir where possible */
        showDir("");
    }

    @Override
    /**
     * Moves to the parent directory if one exists. Does not allow to move above
     * the test 'root' directory.
     */
    public void onBackPressed() {
        File currentDirParent = new File(mCurrentDirPath).getParentFile();
        if (currentDirParent != null) {
            showDir(currentDirParent.getPath());
        } else {
            showDir("");
        }
    }

    /**
     * Prevents the activity from recreating on change of orientation. The title needs to
     * be recalculated.
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        setTitle(shortenTitle(mCurrentDirPath));
    }

    @Override
    protected Dialog onCreateDialog(int id, Bundle args) {
        Dialog dialog = null;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        switch (id) {
            case DIALOG_RUN_ABORT_DIR:
                builder.setTitle(getText(R.string.dialog_run_abort_dir_title_prefix) + " " +
                        args.getString("name"));
                builder.setMessage(R.string.dialog_run_abort_dir_msg);
                builder.setCancelable(true);

                builder.setPositiveButton(R.string.dialog_run_abort_dir_ok_button,
                        new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        /** TODO: Run tests from the dir */
                        removeDialog(DIALOG_RUN_ABORT_DIR);
                    }
                });

                builder.setNegativeButton(R.string.dialog_run_abort_dir_abort_button,
                        new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        removeDialog(DIALOG_RUN_ABORT_DIR);
                    }
                });

                dialog = builder.create();
                dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        removeDialog(DIALOG_RUN_ABORT_DIR);
                    }
                });
                break;
        }

        return dialog;
    }

    /**
     * Loads the contents of dir into the list view.
     *
     * @param dirPath
     *      directory to load into list view
     */
    private void showDir(String dirPath) {
        mCurrentDirPath = dirPath;

        /** Show progress dialog with a delay */
        final Handler delayedDialogHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                if (msg.what == MSG_SHOW_PROGRESS_DIALOG) {
                    if (sProgressDialog == null) {
                        sProgressDialog = new ProgressDialog(DirListActivity.this);
                        sProgressDialog.setCancelable(false);
                        sProgressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
                        sProgressDialog.setTitle(R.string.dialog_progress_title);
                        sProgressDialog.setMessage(getText(R.string.dialog_progress_msg));
                    }
                    sProgressDialog.show();
                }
            }
        };
        Message msgShowDialog = delayedDialogHandler.obtainMessage(MSG_SHOW_PROGRESS_DIALOG);
        delayedDialogHandler.sendMessageDelayed(msgShowDialog, PROGRESS_DIALOG_DELAY_MS);

        /** Delegate loading contents from SD card to a new thread */
        new LoadListItemsThread(mCurrentDirPath, new Handler() {
            @Override
            public void handleMessage(Message msg) {
                if (msg.what == MSG_LOADED_ITEMS) {
                    setListAdapter(new DirListAdapter(DirListActivity.this,
                            (ListItem[])msg.obj));
                    delayedDialogHandler.removeMessages(MSG_SHOW_PROGRESS_DIALOG);
                    setTitle(shortenTitle(mCurrentDirPath));
                    if (sProgressDialog != null) {
                        sProgressDialog.dismiss();
                    }
                }
            }
        }).start();
    }

    /**
     * TODO: find a neat way to determine number of characters that fit in the title
     * bar.
     * */
    private String shortenTitle(String title) {
        if (title.equals("")) {
            return "Tests' root dir:";
        }
        int charCount = mListView.getWidth() / MEAN_TITLE_CHAR_SIZE;

        if (title.length() > charCount) {
            return "..." + title.substring(title.length() - charCount);
        } else {
            return title;
        }
    }

    /**
     * Return the array with contents of the given directory.
     * First it contains the subfolders, then the files. Both sorted
     * alphabetically.
     *
     * The dirPath is relative.
     */
    private ListItem[] getDirList(String dirPath) {
        File dir = new File(mRootDirPath, dirPath);

        List<ListItem> subDirs = new ArrayList<ListItem>();
        List<ListItem> subFiles = new ArrayList<ListItem>();

        for (File item : dir.listFiles()) {
            if (item.isDirectory() && FileFilter.isTestDir(item.getName())) {
                subDirs.add(new ListItem(getRelativePath(item), true));
            } else if (FileFilter.isTestFile(item.getName())) {
                subFiles.add(new ListItem(getRelativePath(item), false));
            }
        }

        Collections.sort(subDirs);
        Collections.sort(subFiles);

        /** Concatenate the two lists */
        subDirs.addAll(subFiles);

        return subDirs.toArray(new ListItem[subDirs.size()]);
    }

    private String getRelativePath(File file) {
        File rootDir = new File(mRootDirPath);
        return file.getAbsolutePath().replaceFirst(rootDir.getPath() + File.separator, "");
    }
}