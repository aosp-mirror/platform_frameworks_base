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
import android.os.Message;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * A Thread that is responsible for generating a lists of tests to run.
 */
public class TestsListPreloaderThread extends Thread {

    private static final String LOG_TAG = "TestsListPreloaderThread";

    /** A list containing relative paths of tests to run */
    private ArrayList<String> mTestsList = new ArrayList<String>();

    private FileFilter mFileFilter;

    /**
     * A relative path to the directory with the tests we want to run or particular test.
     * Used up to and including preloadTests().
     */
    private String mRelativePath;

    private Message mDoneMsg;

    /**
     * The given path must be relative to the root dir.
     *
     * @param path
     * @param doneMsg
     */
    public TestsListPreloaderThread(String path, Message doneMsg) {
        mRelativePath = path;
        mDoneMsg = doneMsg;
    }

    @Override
    public void run() {
        mFileFilter = new FileFilter();
        if (FileFilter.isTestFile(mRelativePath)) {
            mTestsList.add(mRelativePath);
        } else {
            loadTestsFromUrl(mRelativePath);
        }

        mDoneMsg.obj = mTestsList;
        mDoneMsg.sendToTarget();
    }

    /**
     * Loads all the tests from the given directories and all the subdirectories
     * into mTestsList.
     *
     * @param dirRelativePath
     */
    private void loadTestsFromUrl(String rootRelativePath) {
        LinkedList<String> directoriesList = new LinkedList<String>();
        directoriesList.add(rootRelativePath);

        String relativePath;
        String itemName;
        while (!directoriesList.isEmpty()) {
            relativePath = directoriesList.removeFirst();

            List<String> dirRelativePaths = FsUtils.getLayoutTestsDirContents(relativePath, false, true);
            if (dirRelativePaths != null) {
                for (String dirRelativePath : dirRelativePaths) {
                    itemName = new File(dirRelativePath).getName();
                    if (FileFilter.isTestDir(itemName)) {
                        directoriesList.add(dirRelativePath);
                    }
                }
            }

            List<String> testRelativePaths = FsUtils.getLayoutTestsDirContents(relativePath, false, false);
            if (testRelativePaths != null) {
                for (String testRelativePath : testRelativePaths) {
                    itemName = new File(testRelativePath).getName();
                    if (FileFilter.isTestFile(itemName)) {
                        /** We choose to skip all the tests that are expected to crash. */
                        if (!mFileFilter.isCrash(testRelativePath)) {
                            mTestsList.add(testRelativePath);
                        } else {
                            /**
                             * TODO: Summarizer is now in service - figure out how to send the info.
                             * Previously: mSummarizer.addSkippedTest(relativePath);
                             */
                        }
                    }
                }
            }
        }
    }
}
