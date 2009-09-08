/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.lowstoragetest;

import android.app.Activity;
import android.content.Context;

import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import java.io.File;
import java.io.FileOutputStream;
import android.widget.TextView;
import android.widget.Button;

public class LowStorageTest extends Activity {
    static final String TAG = "DiskFullTest";
    static final long WAIT_FOR_FINISH = 5 * 60 * 60;
    static final int NO_OF_BLOCKS_TO_FILL = 1000;
    static final int BYTE_SIZE = 1024;
    static final int WAIT_FOR_SYSTEM_UPDATE = 10000;

    private int mBlockSize = 0;
    private final Object fillUpDone = new Object();

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.main);

        // Update the current data info
        File path = Environment.getDataDirectory();
        StatFs stat = new StatFs(path.getPath());
        int totalBlocks = stat.getBlockCount();
        mBlockSize = (int) (stat.getBlockSize());
        TextView startSizeTextView = (TextView) findViewById(R.id.totalsize);
        startSizeTextView.setText(Long.toString((totalBlocks * mBlockSize) / BYTE_SIZE));
        Button button = (Button) findViewById(R.id.button_run);
        button.setOnClickListener(mStartListener);
    }

    View.OnClickListener mStartListener = new OnClickListener() {
        public void onClick(View v) {
            fillDataAndUpdateInfo();
        }
    };

    public void fillDataAndUpdateInfo() {
        updateInfo(this);
    }

    // Fill up 100% of the data partition
    public void fillupdisk(Context context) {
        final Context contextfill = context;
        new Thread() {
            @Override
            public void run() {
                try {
                    // Fill up all the memory
                    File path = Environment.getDataDirectory();
                    StatFs stat = new StatFs(path.getPath());
                    int totalBlocks = stat.getBlockCount();
                    int noOfBlockToFill = stat.getAvailableBlocks();
                    FileOutputStream fs =
                            contextfill.openFileOutput("testdata", Context.MODE_APPEND);
                    for (int i = 0; i < (noOfBlockToFill / NO_OF_BLOCKS_TO_FILL); i++) {
                        byte buf[] = new byte[mBlockSize * NO_OF_BLOCKS_TO_FILL];
                        fs.write(buf);
                        fs.flush();
                    }

                    // Fill up the last few block
                    byte buf[] = new byte[(noOfBlockToFill % NO_OF_BLOCKS_TO_FILL) * mBlockSize];
                    fs.write(buf);
                    fs.flush();
                    fs.close();

                    // Finished, update the info
                    synchronized (fillUpDone) {
                        fillUpDone.notify();
                    }
                } catch (Exception e) {
                    Log.v(TAG, e.toString());
                }
            }
        }.start();
    }

    public void updateInfo(Context context) {
        fillupdisk(this);
        synchronized (fillUpDone) {
            try {
                fillUpDone.wait(WAIT_FOR_FINISH);
            } catch (Exception e) {
                Log.v(TAG, "wait was interrupted.");
            }
        }
        try {
            // The stat didn't relect the correct data right away
            // put some extra time to make sure if get the right size.
            Thread.sleep(WAIT_FOR_SYSTEM_UPDATE);
            File path = Environment.getDataDirectory();
            StatFs stat = new StatFs(path.getPath());
            long availableBlocks = stat.getAvailableBlocks();
            TextView freeSizeTextView = (TextView) findViewById(R.id.freesize);
            freeSizeTextView.setText(Long.toString((availableBlocks * mBlockSize) / BYTE_SIZE));
            TextView statusTextView = (TextView) findViewById(R.id.status);
            statusTextView.setText("Finished. You can start the test now.");
        } catch (Exception e) {
            Log.v(TAG, e.toString());
        }
    }
}
