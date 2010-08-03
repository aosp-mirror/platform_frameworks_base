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

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;

import java.io.File;

/**
 * A service that handles managing the results of tests, informing of crashes, generating
 * summaries, etc.
 */
public class ManagerService extends Service {

    private static final String LOG_TAG = "ManagerService";

    /** TODO: make it a setting */
    static final String TESTS_ROOT_DIR_PATH =
            Environment.getExternalStorageDirectory() +
            File.separator + "android" +
            File.separator + "LayoutTests";

    /** TODO: make it a setting */
    static final String RESULTS_ROOT_DIR_PATH =
            Environment.getExternalStorageDirectory() +
            File.separator + "android" +
            File.separator + "LayoutTests-results";

    /** TODO: Make it a setting */
    private static final String EXPECTED_RESULT_SECONDARY_LOCATION_RELATIVE_DIR_PREFIX =
            "platform" + File.separator +
            "android-v8" + File.separator;

    /** TODO: Make these settings */
    private static final String TEXT_RESULT_EXTENSION = "txt";
    private static final String IMAGE_RESULT_EXTENSION = "png";

    static final int MSG_PROCESS_ACTUAL_RESULTS = 0;
    static final int MSG_ALL_TESTS_FINISHED = 1;

    private Handler mIncomingHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_PROCESS_ACTUAL_RESULTS:
                    Log.d(LOG_TAG + ".mIncomingHandler", msg.getData().getString("relativePath"));
                    onActualResultsObtained(msg.getData());
                    break;

                case MSG_ALL_TESTS_FINISHED:
                    mSummarizer.summarize();
                    break;
            }
        }
    };

    private Messenger mMessenger = new Messenger(mIncomingHandler);

    private FileFilter mFileFilter;
    private Summarizer mSummarizer;

    @Override
    public void onCreate() {
        super.onCreate();

        mFileFilter = new FileFilter(TESTS_ROOT_DIR_PATH);
        mSummarizer = new Summarizer(mFileFilter, RESULTS_ROOT_DIR_PATH);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mMessenger.getBinder();
    }

    private void onActualResultsObtained(Bundle bundle) {
        AbstractResult results =
                AbstractResult.TestType.valueOf(bundle.getString("type")).createResult(bundle);
        String relativePath = results.getRelativePath();
        results.setExpectedTextResult(getExpectedTextResult(relativePath));
        results.setExpectedImageResult(getExpectedImageResult(relativePath));

        dumpActualTextResult(results);
        dumpActualImageResult(results);

        mSummarizer.appendTest(results);
    }

    private void dumpActualTextResult(AbstractResult result) {
        String testPath = result.getRelativePath();
        String actualTextResult = result.getActualTextResult();
        if (actualTextResult == null) {
            return;
        }

        String resultPath = FileFilter.setPathEnding(testPath, "-actual." + TEXT_RESULT_EXTENSION);
        FsUtils.writeDataToStorage(new File(RESULTS_ROOT_DIR_PATH, resultPath),
                actualTextResult.getBytes(), false);
    }

    private void dumpActualImageResult(AbstractResult result) {
        String testPath = result.getRelativePath();
        byte[] actualImageResult = result.getActualImageResult();
        if (actualImageResult == null) {
            return;
        }

        String resultPath = FileFilter.setPathEnding(testPath, "-actual." + IMAGE_RESULT_EXTENSION);
        FsUtils.writeDataToStorage(new File(RESULTS_ROOT_DIR_PATH, resultPath),
                actualImageResult, false);
    }

    public static String getExpectedTextResult(String relativePath) {
        byte[] result = getExpectedResult(relativePath, TEXT_RESULT_EXTENSION);
        if (result != null) {
            return new String(result);
        }
        return null;
    }

    public static byte[] getExpectedImageResult(String relativePath) {
        return getExpectedResult(relativePath, IMAGE_RESULT_EXTENSION);
    }

    private static byte[] getExpectedResult(String relativePath, String extension) {
        relativePath = FileFilter.setPathEnding(relativePath, "-expected." + extension);

        byte[] bytes = FsUtils.readDataFromStorage(new File(TESTS_ROOT_DIR_PATH, relativePath));
        if (bytes == null) {
            relativePath = EXPECTED_RESULT_SECONDARY_LOCATION_RELATIVE_DIR_PREFIX + relativePath;
            bytes = FsUtils.readDataFromStorage(new File(TESTS_ROOT_DIR_PATH, relativePath));
        }

        return bytes;
    }
}