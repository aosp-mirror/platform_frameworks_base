/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.server;

import java.io.File;
import java.io.IOException;

import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

/**
 * A service designed to load and periodically save &quot;randomness&quot;
 * for the Linux kernel.
 *
 * <p>When a Linux system starts up, the entropy pool associated with
 * {@code /dev/random} may be in a fairly predictable state.  Applications which
 * depend strongly on randomness may find {@code /dev/random} or
 * {@code /dev/urandom} returning predictable data.  In order to counteract
 * this effect, it's helpful to carry the entropy pool information across
 * shutdowns and startups.
 *
 * <p>This class was modeled after the script in
 * <a href="http://www.kernel.org/doc/man-pages/online/pages/man4/random.4.html">man
 * 4 random</a>.
 *
 * <p>TODO: Investigate attempting to write entropy data at shutdown time
 * instead of periodically.
 */
public class EntropyService extends Binder {
    private static final String ENTROPY_FILENAME = getSystemDir() + "/entropy.dat";
    private static final String TAG = "EntropyService";
    private static final int ENTROPY_WHAT = 1;
    private static final int ENTROPY_WRITE_PERIOD = 3 * 60 * 60 * 1000;  // 3 hrs
    private static final String RANDOM_DEV = "/dev/urandom";

    /**
     * Handler that periodically updates the entropy on disk.
     */
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what != ENTROPY_WHAT) {
                Log.e(TAG, "Will not process invalid message");
                return;
            }
            writeEntropy();
            scheduleEntropyWriter();
        }
    };

    public EntropyService() {
        loadInitialEntropy();
        writeEntropy();
        scheduleEntropyWriter();
    }

    private void scheduleEntropyWriter() {
        mHandler.removeMessages(ENTROPY_WHAT);
        mHandler.sendEmptyMessageDelayed(ENTROPY_WHAT, ENTROPY_WRITE_PERIOD);
    }

    private void loadInitialEntropy() {
        try {
            RandomBlock.fromFile(ENTROPY_FILENAME).toFile(RANDOM_DEV);
        } catch (IOException e) {
            Log.w(TAG, "unable to load initial entropy (first boot?)", e);
        }
    }

    private void writeEntropy() {
        try {
            RandomBlock.fromFile(RANDOM_DEV).toFile(ENTROPY_FILENAME);
        } catch (IOException e) {
            Log.e(TAG, "unable to write entropy", e);
        }
    }

    private static String getSystemDir() {
        File dataDir = Environment.getDataDirectory();
        File systemDir = new File(dataDir, "system");
        systemDir.mkdirs();
        return systemDir.toString();
    }
}
