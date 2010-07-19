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

import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.io.File;
import java.util.LinkedList;

/**
 * A Thread that is responsible for finding and loading the tests, starting them and
 * generating summaries. The actual running of the test is delegated to LayoutTestsRunner
 * activity (a UI thread) because of a WebView object that need to be created in UI thread
 * so it can be displayed on the screen. However, the logic for doing this remains in
 * this class (in handler created in constructor).
 */
public class LayoutTestsRunnerThread extends Thread {

    private static final String LOG_TAG = "LayoutTestsRunnerThread";

    /** Messages for handler on this thread */
    public static final int MSG_TEST_FINISHED = 0;

    /** Messages for our handler running on UI thread */
    public static final int MSG_RUN_TEST = 0;

    /** TODO: make it a setting */
    private static final String TESTS_ROOT_DIR_PATH =
            Environment.getExternalStorageDirectory() +
            File.separator + "android" +
            File.separator + "LayoutTests";

    /** TODO: make it a setting */
    private static final String RESULTS_ROOT_DIR_PATH =
            Environment.getExternalStorageDirectory() +
            File.separator + "android" +
            File.separator + "LayoutTests-results";

    /** A list containing relative paths of tests to run */
    private LinkedList<String> mTestsList = new LinkedList<String>();

    private FileFilter mFileFilter;
    private Summarizer mSummarizer;

    /** Our handler running on this thread. Created in run() method. */
    private Handler mHandler;

    /** Our handler running on UI thread. Created in constructor of this thread. */
    private Handler mHandlerOnUiThread;

    /**
     * A relative path to the folder with the tests we want to run or particular test.
     * Used up to and including preloadTests().
     */
    private String mRelativePath;

    /** A handler obtained from UI thread to handle messages concerning updating the display */
    private Handler mUiDisplayHandler;

    private LayoutTest mCurrentTest;
    private String mCurrentTestPath;
    private int mCurrentTestCount = 0;
    private int mTotalTestCount;

    /**
     * The given path must be relative to the root dir. The given handler must be
     * able to handle messages that update the display (UI thread).
     *
     * @param path
     * @param uiDisplayHandler
     */
    public LayoutTestsRunnerThread(String path, Handler uiDisplayHandler) {
        mFileFilter = new FileFilter(TESTS_ROOT_DIR_PATH);
        mRelativePath = path;
        mUiDisplayHandler = uiDisplayHandler;

        /** This creates a handler that runs on the thread that _created_ this thread */
        mHandlerOnUiThread = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_RUN_TEST:
                        ((LayoutTest) msg.obj).run();
                        break;
                }
            }
        };
    }

    @Override
    public void run() {
        Looper.prepare();

        mSummarizer = new Summarizer(mFileFilter, RESULTS_ROOT_DIR_PATH);

        /** Creates a new handler in _this_ thread */
        mHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_TEST_FINISHED:
                        onTestFinished(mCurrentTest);
                        mUiDisplayHandler.obtainMessage(LayoutTestsRunner.MSG_UPDATE_PROGRESS,
                                mCurrentTestCount, mTotalTestCount).sendToTarget();
                        runNextTest();
                        break;
                }
            }
        };

        /** Check if the path is correct */
        File file = new File(TESTS_ROOT_DIR_PATH, mRelativePath);
        if (!file.exists()) {
            Log.e(LOG_TAG + "::run", "Path does not exist: " + mRelativePath);
            return;
        }

        /** Populate the tests' list accordingly */
        if (file.isDirectory()) {
            mUiDisplayHandler.sendEmptyMessage(LayoutTestsRunner.MSG_SHOW_PROGRESS_DIALOG);
            preloadTests(mRelativePath);
            mUiDisplayHandler.sendEmptyMessage(LayoutTestsRunner.MSG_DISMISS_PROGRESS_DIALOG);
        } else {
            mTestsList.addLast(mRelativePath);
            mTotalTestCount = 1;
        }

        runNextTest();
        Looper.loop();
    }

    /**
     * Loads all the tests from the given folders and all the subfolders
     * into mTestsList.
     *
     * @param dirRelativePath
     */
    private void preloadTests(String dirRelativePath) {
        LinkedList<String> foldersList = new LinkedList<String>();
        foldersList.add(dirRelativePath);

        String relativePath;
        String currentDirRelativePath;
        String itemName;
        File[] items;
        while (!foldersList.isEmpty()) {
            currentDirRelativePath = foldersList.removeFirst();
            items = new File(TESTS_ROOT_DIR_PATH, currentDirRelativePath).listFiles();
            for (File item : items) {
                itemName = item.getName();
                relativePath = currentDirRelativePath + File.separator + itemName;

                if (item.isDirectory() && FileFilter.isTestDir(itemName)) {
                    foldersList.add(relativePath);
                    continue;
                }

                if (FileFilter.isTestFile(itemName)) {
                    if (!mFileFilter.isSkip(relativePath)) {
                        mTestsList.addLast(relativePath);
                    } else {
                        mSummarizer.addSkippedTest(relativePath);
                    }
                }
            }
        }

        mTotalTestCount = mTestsList.size();
    }

    private void runNextTest() {
        if (mTestsList.isEmpty()) {
            onFinishedTests();
            return;
        }

        mCurrentTestCount++;
        mCurrentTestPath = mTestsList.removeFirst();
        mCurrentTest = new LayoutTest(mCurrentTestPath, mHandler);

        /**
         * This will run the test on UI thread. The reason why we need to run the test
         * on UI thread is because of the WebView. If we want to display the webview on
         * the screen it needs to be in the UI thread. WebView should be created as
         * part of the LayoutTest.run() method.
         */
        mHandlerOnUiThread.obtainMessage(MSG_RUN_TEST, mCurrentTest).sendToTarget();
    }

    private void onTestFinished(LayoutTest test) {
        String testPath = test.getRelativePath();

        /** Obtain the result */
        AbstractResult result = test.getResult();
        if (result == null) {
            Log.e(LOG_TAG + "::runTests", testPath + ": result NULL!!");
            return;
        }

        dumpResultData(result, testPath);

        mSummarizer.appendTest(test);
    }

    private void dumpResultData(AbstractResult result, String testPath) {
        String resultPath = null;

        switch (result.getType()) {
            case TEXT:
                resultPath = FileFilter.setPathEnding(testPath, "-actual.txt");
                break;

            case PIXEL:
                /** TODO: Check if it is for sure *.bmp */
                resultPath = FileFilter.setPathEnding(testPath, "-actual.bmp");
                break;
        }

        /** Dump the result */
        FsUtils.writeDataToStorage(new File(RESULTS_ROOT_DIR_PATH, resultPath),
                result.getData(), false);
    }

    private void onFinishedTests() {
        Log.d(LOG_TAG + "::onFinishedTests", "Begin.");
        Looper.myLooper().quit();
        mSummarizer.summarize();
        /** TODO: Present some kind of notification to the user that
         * allows to chose next action, e.g:
         * - go to html view of results
         * - zip results
         * - run more tests before zipping */
    }
}
