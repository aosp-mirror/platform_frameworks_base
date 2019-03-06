/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.dynandroid;

import android.gsi.GsiProgress;
import android.os.AsyncTask;
import android.os.DynamicAndroidManager;
import android.util.Log;
import android.webkit.URLUtil;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.GZIPInputStream;


class InstallationAsyncTask extends AsyncTask<String, Long, Integer> {

    private static final String TAG = "InstallationAsyncTask";

    private static final int READ_BUFFER_SIZE = 1 << 19;

    private class InvalidImageUrlException extends RuntimeException {
        private InvalidImageUrlException(String message) {
            super(message);
        }
    }


    /** Not completed, including being cancelled */
    static final int NO_RESULT = 0;
    static final int RESULT_OK = 1;
    static final int RESULT_ERROR_IO = 2;
    static final int RESULT_ERROR_INVALID_URL = 3;
    static final int RESULT_ERROR_EXCEPTION = 6;

    interface InstallStatusListener {
        void onProgressUpdate(long installedSize);
        void onResult(int resultCode);
        void onCancelled();
    }

    private final String mUrl;
    private final long mSystemSize;
    private final long mUserdataSize;
    private final DynamicAndroidManager mDynamicAndroid;
    private final InstallStatusListener mListener;
    private DynamicAndroidManager.Session mInstallationSession;

    private int mResult = NO_RESULT;

    private InputStream mStream;


    InstallationAsyncTask(String url, long systemSize, long userdataSize,
            DynamicAndroidManager dynAndroid, InstallStatusListener listener) {
        mUrl = url;
        mSystemSize = systemSize;
        mUserdataSize = userdataSize;
        mDynamicAndroid = dynAndroid;
        mListener = listener;
    }

    @Override
    protected void onPreExecute() {
        mListener.onProgressUpdate(0);
    }

    @Override
    protected Integer doInBackground(String... voids) {
        Log.d(TAG, "Start doInBackground(), URL: " + mUrl);

        try {
            long installedSize = 0;
            long reportedInstalledSize = 0;

            long minStepToReport = (mSystemSize + mUserdataSize) / 100;

            // init input stream before calling startInstallation(), which takes 90 seconds.
            initInputStream();

            Thread thread = new Thread(() -> {
                mInstallationSession =
                        mDynamicAndroid.startInstallation(mSystemSize, mUserdataSize);
            });


            thread.start();

            while (thread.isAlive()) {
                if (isCancelled()) {
                    boolean aborted = mDynamicAndroid.abort();
                    Log.d(TAG, "Called DynamicAndroidManager.abort(), result = " + aborted);
                    return RESULT_OK;
                }

                GsiProgress progress = mDynamicAndroid.getInstallationProgress();
                installedSize = progress.bytes_processed;

                if (installedSize > reportedInstalledSize + minStepToReport) {
                    publishProgress(installedSize);
                    reportedInstalledSize = installedSize;
                }

                Thread.sleep(10);
            }


            if (mInstallationSession == null) {
                Log.e(TAG, "Failed to start installation with requested size: "
                        + (mSystemSize + mUserdataSize));

                return RESULT_ERROR_IO;
            }

            installedSize = mUserdataSize;

            byte[] bytes = new byte[READ_BUFFER_SIZE];

            int numBytesRead;

            Log.d(TAG, "Start installation loop");
            while ((numBytesRead = mStream.read(bytes, 0, READ_BUFFER_SIZE)) != -1) {
                if (isCancelled()) {
                    break;
                }

                byte[] writeBuffer = numBytesRead == READ_BUFFER_SIZE
                        ? bytes : Arrays.copyOf(bytes, numBytesRead);

                if (!mInstallationSession.write(writeBuffer)) {
                    throw new IOException("Failed write() to DynamicAndroid");
                }

                installedSize += numBytesRead;

                if (installedSize > reportedInstalledSize + minStepToReport) {
                    publishProgress(installedSize);
                    reportedInstalledSize = installedSize;
                }
            }

            return RESULT_OK;

        } catch (IOException e) {
            e.printStackTrace();
            return RESULT_ERROR_IO;

        } catch (InvalidImageUrlException e) {
            e.printStackTrace();
            return RESULT_ERROR_INVALID_URL;

        } catch (Exception e) {
            e.printStackTrace();
            return RESULT_ERROR_EXCEPTION;

        } finally {
            close();
        }
    }

    @Override
    protected void onCancelled() {
        Log.d(TAG, "onCancelled(), URL: " + mUrl);

        close();

        mListener.onCancelled();
    }

    @Override
    protected void onPostExecute(Integer result) {
        Log.d(TAG, "onPostExecute(), URL: " + mUrl + ", result: " + result);

        close();

        mResult = result;
        mListener.onResult(mResult);
    }

    @Override
    protected void onProgressUpdate(Long... values) {
        long progress = values[0];
        mListener.onProgressUpdate(progress);
    }

    private void initInputStream() throws IOException, InvalidImageUrlException {
        if (URLUtil.isNetworkUrl(mUrl) || URLUtil.isFileUrl(mUrl)) {
            mStream = new BufferedInputStream(new GZIPInputStream(new URL(mUrl).openStream()));
        } else {
            throw new InvalidImageUrlException(
                    String.format(Locale.US, "Unsupported file source: %s", mUrl));
        }
    }

    private void close() {
        try {
            if (mStream != null) {
                mStream.close();
                mStream = null;
            }
        } catch (IOException e) {
            // ignore
        }
    }

    int getResult() {
        return mResult;
    }

    boolean commit() {
        if (mInstallationSession == null) {
            return false;
        }

        return mInstallationSession.commit();
    }
}
