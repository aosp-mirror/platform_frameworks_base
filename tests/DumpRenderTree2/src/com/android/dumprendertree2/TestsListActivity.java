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

import com.android.dumprendertree2.scriptsupport.OnEverythingFinishedCallback;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.Gravity;
import android.view.Window;
import android.webkit.WebView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;

/**
 * An Activity that generates a list of tests and sends the intent to
 * LayoutTestsExecuter to run them. It also restarts the LayoutTestsExecuter
 * after it crashes.
 */
public class TestsListActivity extends Activity {

    private static final int MSG_TEST_LIST_PRELOADER_DONE = 0;

    /** Constants for adding extras to an intent */
    public static final String EXTRA_TEST_PATH = "TestPath";

    private static ProgressDialog sProgressDialog;

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_TEST_LIST_PRELOADER_DONE:
                    sProgressDialog.dismiss();
                    mTestsList = (ArrayList<String>)msg.obj;
                    mTotalTestCount = mTestsList.size();
                    restartExecutor(0);
                    break;
            }
        }
    };

    private ArrayList<String> mTestsList;
    private int mTotalTestCount;

    private OnEverythingFinishedCallback mOnEverythingFinishedCallback;
    private boolean mEverythingFinished;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /** Prepare the progress dialog */
        sProgressDialog = new ProgressDialog(TestsListActivity.this);
        sProgressDialog.setCancelable(false);
        sProgressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        sProgressDialog.setTitle(R.string.dialog_progress_title);
        sProgressDialog.setMessage(getText(R.string.dialog_progress_msg));

        requestWindowFeature(Window.FEATURE_PROGRESS);

        Intent intent = getIntent();
        if (!intent.getAction().equals(Intent.ACTION_RUN)) {
            return;
        }
        String path = intent.getStringExtra(EXTRA_TEST_PATH);

        sProgressDialog.show();
        Message doneMsg = Message.obtain(mHandler, MSG_TEST_LIST_PRELOADER_DONE);

        Intent serviceIntent = new Intent(this, ManagerService.class);
        serviceIntent.putExtra("path", path);
        startService(serviceIntent);

        new TestsListPreloaderThread(path, doneMsg).start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if (intent.getAction().equals(Intent.ACTION_REBOOT)) {
            onCrashIntent(intent);
        } else if (intent.getAction().equals(Intent.ACTION_SHUTDOWN)) {
            onEverythingFinishedIntent(intent);
        }
    }

    /**
     * This method handles an intent that comes from ManageService when crash is detected.
     * The intent contains an index in mTestsList of the test that crashed. TestsListActivity
     * restarts the LayoutTestsExecutor from the following test in mTestsList, by sending
     * an intent to it. This new intent contains a list of remaining tests to run,
     * total count of all tests, and the index of the first test to run after restarting.
     * LayoutTestExecutor runs then as usual, sending reports to ManagerService. If it
     * detects the crash it sends a new intent and the flow repeats.
     */
    private void onCrashIntent(Intent intent) {
        int nextTestToRun = intent.getIntExtra("crashedTestIndex", -1) + 1;
        if (nextTestToRun > 0 && nextTestToRun <= mTotalTestCount) {
            restartExecutor(nextTestToRun);
        }
    }

    public void registerOnEverythingFinishedCallback(OnEverythingFinishedCallback callback) {
        mOnEverythingFinishedCallback = callback;
        if (mEverythingFinished) {
            mOnEverythingFinishedCallback.onFinished();
        }
    }

    private void onEverythingFinishedIntent(Intent intent) {
        Toast toast = Toast.makeText(this,
                "All tests finished.\nPress back key to return to the tests' list.",
                Toast.LENGTH_LONG);
        toast.setGravity(Gravity.CENTER, -40, 0);
        toast.show();

        /** Show the details to the user */
        WebView webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setBuiltInZoomControls(true);
        webView.getSettings().setEnableSmoothTransition(true);
        /** This enables double-tap to zoom */
        webView.getSettings().setUseWideViewPort(true);

        setContentView(webView);
        webView.loadUrl(Summarizer.getDetailsUri().toString());

        mEverythingFinished = true;
        if (mOnEverythingFinishedCallback != null) {
            mOnEverythingFinishedCallback.onFinished();
        }
    }

    /**
     * This, together with android:configChanges="orientation" in manifest file, prevents
     * the activity from restarting on orientation change.
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putStringArrayList("testsList", mTestsList);
        outState.putInt("totalCount", mTotalTestCount);

        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);

        mTestsList = savedInstanceState.getStringArrayList("testsList");
        mTotalTestCount = savedInstanceState.getInt("totalCount");
    }

    /**
     * (Re)starts the executer activity from the given test number (inclusive, 0-based).
     * This number is an index in mTestsList, not the sublist passed in the intent.
     *
     * @param startFrom
     *      test index in mTestsList to start the tests from (inclusive, 0-based)
     */
    private void restartExecutor(int startFrom) {
        Intent intent = new Intent();
        intent.setClass(this, LayoutTestsExecutor.class);
        intent.setAction(Intent.ACTION_RUN);

        if (startFrom < mTotalTestCount) {
            File testListFile = new File(getExternalFilesDir(null), "test_list.txt");
            FsUtils.saveTestListToStorage(testListFile, startFrom, mTestsList);
            intent.putExtra(LayoutTestsExecutor.EXTRA_TESTS_FILE, testListFile.getAbsolutePath());
            intent.putExtra(LayoutTestsExecutor.EXTRA_TEST_INDEX, startFrom);
        } else {
            intent.putExtra(LayoutTestsExecutor.EXTRA_TESTS_FILE, "");
        }

        startActivity(intent);
    }
}