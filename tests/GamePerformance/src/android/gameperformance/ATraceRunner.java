/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.gameperformance;

import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;

import android.app.Instrumentation;
import android.os.AsyncTask;
import android.os.ParcelFileDescriptor;
import android.util.Log;

/**
 * Helper that runs atrace command for required duration and category. Results are read from
 * the output of atrace and serialized to the provided file. We cannot use direct atrace to
 * file because atrace is executed in UI automator context and analysis is done in test context.
 * In last case output file is not accessible from the both contexts.
 */
public class ATraceRunner extends AsyncTask<Void, Integer, Boolean>{
    private final static String TAG = "ATraceRunner";

    // Report that atrace is done.
    public interface Delegate {
        public void onProcessed(boolean success);
    }

    private final Instrumentation mInstrumentation;
    private final String mOutput;
    private final int mTimeInSeconds;
    private final String mCategory;
    private final Delegate mDelegate;

    public ATraceRunner(Instrumentation instrumentation,
                        String output,
                        int timeInSeconds,
                        String category,
                        Delegate delegate) {
        mInstrumentation = instrumentation;
        mOutput = output;
        mTimeInSeconds = timeInSeconds;
        mCategory = category;
        mDelegate = delegate;
    }

    @Override
    protected Boolean doInBackground(Void... params) {
        BufferedReader bufferedReader = null;
        FileWriter writer = null;
        try {
            // Run the command.
            final String cmd = "atrace -t " + mTimeInSeconds + " " + mCategory;
            Log.i(TAG, "Running atrace... " + cmd);
            writer = new FileWriter(mOutput);
            final ParcelFileDescriptor fd =
                    mInstrumentation.getUiAutomation().executeShellCommand(cmd);
            bufferedReader = new BufferedReader(
                    new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(fd)));
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                writer.write(line);
                writer.write("\n");
            }
            Log.i(TAG, "Running atrace... DONE");
            return true;
        } catch (IOException e) {
            Log.i(TAG, "atrace failed", e);
            return false;
        } finally {
            Utils.closeQuietly(bufferedReader);
            Utils.closeQuietly(writer);
        }
    }

    @Override
    protected void onPostExecute(Boolean result) {
        mDelegate.onProcessed(result);
    }

}
