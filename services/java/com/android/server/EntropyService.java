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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;

import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.util.Slog;

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
    private static final String TAG = "EntropyService";
    private static final int ENTROPY_WHAT = 1;
    private static final int ENTROPY_WRITE_PERIOD = 3 * 60 * 60 * 1000;  // 3 hrs
    private static final long START_TIME = System.currentTimeMillis();
    private static final long START_NANOTIME = System.nanoTime();

    private final String randomDevice;
    private final String entropyFile;

    /**
     * Handler that periodically updates the entropy on disk.
     */
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what != ENTROPY_WHAT) {
                Slog.e(TAG, "Will not process invalid message");
                return;
            }
            writeEntropy();
            scheduleEntropyWriter();
        }
    };

    public EntropyService() {
        this(getSystemDir() + "/entropy.dat", "/dev/urandom");
    }

    /** Test only interface, not for public use */
    public EntropyService(String entropyFile, String randomDevice) {
        if (randomDevice == null) { throw new NullPointerException("randomDevice"); }
        if (entropyFile == null) { throw new NullPointerException("entropyFile"); }

        this.randomDevice = randomDevice;
        this.entropyFile = entropyFile;
        loadInitialEntropy();
        addDeviceSpecificEntropy();
        writeEntropy();
        scheduleEntropyWriter();
    }

    private void scheduleEntropyWriter() {
        mHandler.removeMessages(ENTROPY_WHAT);
        mHandler.sendEmptyMessageDelayed(ENTROPY_WHAT, ENTROPY_WRITE_PERIOD);
    }

    private void loadInitialEntropy() {
        try {
            RandomBlock.fromFile(entropyFile).toFile(randomDevice, false);
        } catch (IOException e) {
            Slog.w(TAG, "unable to load initial entropy (first boot?)", e);
        }
    }

    private void writeEntropy() {
        try {
            RandomBlock.fromFile(randomDevice).toFile(entropyFile, true);
        } catch (IOException e) {
            Slog.w(TAG, "unable to write entropy", e);
        }
    }

    /**
     * Add additional information to the kernel entropy pool.  The
     * information isn't necessarily "random", but that's ok.  Even
     * sending non-random information to {@code /dev/urandom} is useful
     * because, while it doesn't increase the "quality" of the entropy pool,
     * it mixes more bits into the pool, which gives us a higher degree
     * of uncertainty in the generated randomness.  Like nature, writes to
     * the random device can only cause the quality of the entropy in the
     * kernel to stay the same or increase.
     *
     * <p>For maximum effect, we try to target information which varies
     * on a per-device basis, and is not easily observable to an
     * attacker.
     */
    private void addDeviceSpecificEntropy() {
        PrintWriter out = null;
        try {
            out = new PrintWriter(new FileOutputStream(randomDevice));
            out.println("Copyright (C) 2009 The Android Open Source Project");
            out.println("All Your Randomness Are Belong To Us");
            out.println(START_TIME);
            out.println(START_NANOTIME);
            out.println(SystemProperties.get("ro.serialno"));
            out.println(SystemProperties.get("ro.bootmode"));
            out.println(SystemProperties.get("ro.baseband"));
            out.println(SystemProperties.get("ro.carrier"));
            out.println(SystemProperties.get("ro.bootloader"));
            out.println(SystemProperties.get("ro.hardware"));
            out.println(SystemProperties.get("ro.revision"));
            out.println(new Object().hashCode());
            out.println(System.currentTimeMillis());
            out.println(System.nanoTime());
        } catch (IOException e) {
            Slog.w(TAG, "Unable to add device specific data to the entropy pool", e);
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }

    private static String getSystemDir() {
        File dataDir = Environment.getDataDirectory();
        File systemDir = new File(dataDir, "system");
        systemDir.mkdirs();
        return systemDir.toString();
    }
}
