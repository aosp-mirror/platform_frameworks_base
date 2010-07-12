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

package com.android.dumprendertree2;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.Window;

/**
 * An Activity that is responsible only for updating the UI features, like titles, progress bars,
 * etc.
 *
 * <p>Also, the webview form the test must be running in this activity's thread if we want
 * to be able to display it on the screen.
 */
public class LayoutTestsRunner extends Activity {

    public static final int MSG_UPDATE_PROGRESS = 1;
    public static final int MSG_SHOW_PROGRESS_DIALOG = 2;
    public static final int MSG_DISMISS_PROGRESS_DIALOG = 3;

    /** Constants for adding extras to an intent */
    public static final String EXTRA_TEST_PATH = "TestPath";

    private static ProgressDialog sProgressDialog;

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_PROGRESS:
                    int i = msg.arg1;
                    int size = msg.arg2;
                    getWindow().setFeatureInt(Window.FEATURE_PROGRESS,
                            i * Window.PROGRESS_END / size);
                    setTitle(i * 100 / size + "% (" + i + "/" + size + ")");
                    break;

                case MSG_SHOW_PROGRESS_DIALOG:
                    sProgressDialog.show();
                    break;

                case MSG_DISMISS_PROGRESS_DIALOG:
                    sProgressDialog.dismiss();
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /** Prepare the progress dialog */
        sProgressDialog = new ProgressDialog(LayoutTestsRunner.this);
        sProgressDialog.setCancelable(false);
        sProgressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        sProgressDialog.setTitle(R.string.dialog_progress_title);
        sProgressDialog.setMessage(getText(R.string.dialog_progress_msg));

        requestWindowFeature(Window.FEATURE_PROGRESS);

        /** Execute the intent */
        Intent intent = getIntent();
        if (!intent.getAction().equals(Intent.ACTION_RUN)) {
            return;
        }
        String path = intent.getStringExtra(EXTRA_TEST_PATH);

        new LayoutTestsRunnerThread(path, mHandler).start();
    }
}