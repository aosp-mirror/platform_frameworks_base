/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.mediaframeworktest.helpers;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;

public class CameraTestResultPrinter {

    private static final String TAG = CameraTestResultPrinter.class.getSimpleName();
    private static final String RESULT_DIR = Environment.getExternalStorageDirectory() +
            "/camera-out/";
    private static final String RESULT_FILE_FORMAT = "fwk-stress_camera_%s.txt";
    private static final String RESULT_SWAP_FILE = "fwk-stress.swp";
    private static final String KEY_NUM_ATTEMPTS = "numAttempts";   // Total number of iterations
    private static final String KEY_ITERATION = "iteration";
    private static final String KEY_CAMERA_ID = "cameraId";
    private static final int INST_STATUS_IN_PROGRESS = 2;

    private Instrumentation mInst = null;
    private boolean mWriteToFile = true;


    public CameraTestResultPrinter(Instrumentation instrumentation, boolean writeToFile) {
        mInst = instrumentation;
        mWriteToFile = writeToFile;

        // Create a log directory if not exists.
        File baseDir = new File(RESULT_DIR);
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            throw new IllegalStateException("Couldn't create directory for logs: " + baseDir);
        }
        Log.v(TAG, String.format("Saving test results under: %s", baseDir.getAbsolutePath()));
        // Remove all logs but not the base directory before a test run.
        purgeFiles(baseDir);
    }

    public void printStatus(int numAttempts, int iteration, String cameraId) throws Exception {
        Log.v(TAG, String.format("Print status: numAttempts=%d iteration=%d cameraId=%s",
                numAttempts, iteration, cameraId));
        // Write stats to instrumentation results.
        sendInstrumentationStatus(numAttempts, iteration, cameraId);

        if (mWriteToFile) {
            writeToFile(numAttempts, iteration, cameraId);
        }
    }

    /**
     *  Report the test results to instrumentation status or a file.
     */
    public void printStatus(int numAttempts, int iteration) throws Exception {
        printStatus(numAttempts, iteration, "");
    }

    /**
     * Write stats to instrumentation results.
     */
    private void sendInstrumentationStatus(int numAttempts, int iteration, String cameraId)
            throws Exception {
        Bundle output = new Bundle();
        output.putString(KEY_NUM_ATTEMPTS, String.valueOf(numAttempts));
        output.putString(KEY_ITERATION, String.valueOf(iteration));
        if (!"".equals(cameraId)) {
            output.putString(KEY_CAMERA_ID, cameraId);
        }
        mInst.sendStatus(INST_STATUS_IN_PROGRESS, output);
    }

    private void writeToFile(final int numAttempts, final int iteration, String cameraId) {
        // Format output in a form of pairs of key and value
        // eg, "numAttempts=500|iteration=400[|cameraId=0]"
        String results = String.format("%s=%d|%s=%d", KEY_NUM_ATTEMPTS, numAttempts,
                KEY_ITERATION, iteration);
        if (!"".equals(cameraId)) {
            results += String.format("|%s=%s", KEY_CAMERA_ID, cameraId);
        }
        Log.v(TAG, String.format("Writing result to a file: %s", results));

        // Write results to a swap file temporarily, then rename it to a text file when writing
        // has successfully completed, so that process crash during file writing would
        // not corrupt the file.
        File swapFile = new File(RESULT_DIR, RESULT_SWAP_FILE);
        BufferedWriter out = null;
        try {
            out = new BufferedWriter(new FileWriter(swapFile));
            out.write(results);
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, String.format("Failed to write results to a file: %s", e));
        } finally {
            if (out != null) {
                try {
                    out.close();
                    // Delete an old file just before renaming, instead of overwriting.
                    String resultFileName = String.format(RESULT_FILE_FORMAT, cameraId);
                    File txtFile = new File(RESULT_DIR, resultFileName);
                    txtFile.delete();
                    swapFile.renameTo(txtFile);
                } catch (Exception e) {
                    Log.w(TAG, String.format("Failed to write results to a file: %s", e));
                }
            }
        }
    }

    // Remove sub directories and their contents, but not given directory.
    private void purgeFiles(File path) {
        File[] files = path.listFiles();
        if (files != null) {
            for (File child : files) {
                if (path.isDirectory()) {
                    purgeFiles(child);
                }
                child.delete();
            }
        }
    }
}
