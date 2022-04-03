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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.util.AtomicFile;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * A service that loads and periodically saves &quot;randomness&quot; for the
 * Linux kernel RNG.
 *
 * <p>When a Linux system starts up, the entropy pool associated with {@code
 * /dev/urandom}, {@code /dev/random}, and {@code getrandom()} may be in a
 * fairly predictable state, depending on the entropy sources available to the
 * kernel.  Applications that depend on randomness may find these APIs returning
 * predictable data.  To counteract this effect, this service maintains a seed
 * file across shutdowns and startups, and also mixes some device and
 * boot-specific information into the pool.
 */
public class EntropyMixer extends Binder {
    private static final String TAG = "EntropyMixer";
    private static final int UPDATE_SEED_MSG = 1;
    private static final int SEED_UPDATE_PERIOD = 3 * 60 * 60 * 1000;  // 3 hrs
    private static final long START_TIME = System.currentTimeMillis();
    private static final long START_NANOTIME = System.nanoTime();

    /*
     * The size of the seed file in bytes.  This must be at least the size of a
     * SHA-256 digest (32 bytes).  It *should* also be at least the size of the
     * kernel's entropy pool (/proc/sys/kernel/random/poolsize divided by 8),
     * which historically was 512 bytes, but changed to 32 bytes in Linux v5.18.
     * There's actually no real need for more than a 32-byte seed, even with
     * older kernels; however, we take the conservative approach of staying with
     * the 512-byte size for now, as the cost is very small.
     */
    @VisibleForTesting
    static final int SEED_FILE_SIZE = 512;

    @VisibleForTesting
    static final String DEVICE_SPECIFIC_INFO_HEADER =
        "Copyright (C) 2009 The Android Open Source Project\n" +
        "All Your Randomness Are Belong To Us\n";

    private final AtomicFile seedFile;
    private final File randomReadDevice;
    private final File randomWriteDevice; // separate from randomReadDevice only for testing

    /**
     * Handler that periodically updates the seed file.
     */
    private final Handler mHandler = new Handler(IoThread.getHandler().getLooper()) {
        // IMPLEMENTATION NOTE: This handler runs on the I/O thread to avoid I/O on the main thread.
        // The reason we're using our own Handler instead of IoThread.getHandler() is to create our
        // own ID space for the "what" parameter of messages seen by the handler.
        @Override
        public void handleMessage(Message msg) {
            if (msg.what != UPDATE_SEED_MSG) {
                Slog.e(TAG, "Will not process invalid message");
                return;
            }
            updateSeedFile();
            scheduleSeedUpdater();
        }
    };

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateSeedFile();
        }
    };

    public EntropyMixer(Context context) {
        this(context, new File(getSystemDir(), "entropy.dat"),
                new File("/dev/urandom"), new File("/dev/urandom"));
    }

    @VisibleForTesting
    EntropyMixer(Context context, File seedFile, File randomReadDevice, File randomWriteDevice) {
        this.seedFile = new AtomicFile(Preconditions.checkNotNull(seedFile));
        this.randomReadDevice = Preconditions.checkNotNull(randomReadDevice);
        this.randomWriteDevice = Preconditions.checkNotNull(randomWriteDevice);

        loadInitialEntropy();
        updateSeedFile();
        scheduleSeedUpdater();
        IntentFilter broadcastFilter = new IntentFilter(Intent.ACTION_SHUTDOWN);
        broadcastFilter.addAction(Intent.ACTION_POWER_CONNECTED);
        broadcastFilter.addAction(Intent.ACTION_REBOOT);
        context.registerReceiver(
                mBroadcastReceiver,
                broadcastFilter,
                null, // do not require broadcaster to hold any permissions
                mHandler // process received broadcasts on the I/O thread instead of the main thread
                );
    }

    private void scheduleSeedUpdater() {
        mHandler.removeMessages(UPDATE_SEED_MSG);
        mHandler.sendEmptyMessageDelayed(UPDATE_SEED_MSG, SEED_UPDATE_PERIOD);
    }

    private void loadInitialEntropy() {
        byte[] seed = readSeedFile();
        try (FileOutputStream out = new FileOutputStream(randomWriteDevice)) {
            if (seed.length != 0) {
                out.write(seed);
                Slog.i(TAG, "Loaded existing seed file");
            }
            out.write(getDeviceSpecificInformation());
        } catch (IOException e) {
            Slog.e(TAG, "Error writing to " + randomWriteDevice, e);
        }
    }

    private byte[] readSeedFile() {
        try {
            return seedFile.readFully();
        } catch (FileNotFoundException e) {
            return new byte[0];
        } catch (IOException e) {
            Slog.e(TAG, "Error reading " + seedFile.getBaseFile(), e);
            return new byte[0];
        }
    }

    /**
     * Update (or create) the seed file.
     *
     * <p>Traditionally, the recommended way to update a seed file on Linux was
     * to simply copy some bytes from /dev/urandom.  However, that isn't
     * actually a good way to do it, because writes to /dev/urandom aren't
     * guaranteed to immediately affect reads from /dev/urandom.  This can cause
     * the new seed file to contain less entropy than the old one!
     *
     * <p>Instead, we generate the new seed by hashing the old seed together
     * with some bytes from /dev/urandom, following the example of <a
     * href="https://git.zx2c4.com/seedrng/tree/README.md">SeedRNG</a>.  This
     * ensures that the new seed is at least as entropic as the old seed.
     */
    private void updateSeedFile() {
        byte[] oldSeed = readSeedFile();
        byte[] newSeed = new byte[SEED_FILE_SIZE];

        try (FileInputStream in = new FileInputStream(randomReadDevice)) {
            if (in.read(newSeed) != newSeed.length) {
                throw new IOException("unexpected EOF");
            }
        } catch (IOException e) {
            Slog.e(TAG, "Error reading " + randomReadDevice +
                    "; seed file won't be properly updated", e);
            // Continue on; at least we'll have new timestamps...
        }

        // newSeed = newSeed[:-32] ||
        //           SHA-256(fixed_prefix || real_time || boot_time ||
        //                   old_seed_len || old_seed || new_seed_len || new_seed)
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            Slog.wtf(TAG, "SHA-256 algorithm not found; seed file won't be updated", e);
            return;
        }
        // This fixed prefix should be changed if the fields that are hashed change.
        sha256.update("Android EntropyMixer v1".getBytes());
        sha256.update(longToBytes(System.currentTimeMillis()));
        sha256.update(longToBytes(System.nanoTime()));
        sha256.update(longToBytes(oldSeed.length));
        sha256.update(oldSeed);
        sha256.update(longToBytes(newSeed.length));
        sha256.update(newSeed);
        byte[] digest = sha256.digest();
        System.arraycopy(digest, 0, newSeed, newSeed.length - digest.length, digest.length);

        writeNewSeed(newSeed);
        if (oldSeed.length == 0) {
            Slog.i(TAG, "Created seed file");
        } else {
            Slog.i(TAG, "Updated seed file");
        }
    }

    private void writeNewSeed(byte[] newSeed) {
        FileOutputStream out = null;
        try {
            out = seedFile.startWrite();
            out.write(newSeed);
            seedFile.finishWrite(out);
        } catch (IOException e) {
            Slog.e(TAG, "Error writing " + seedFile.getBaseFile(), e);
            seedFile.failWrite(out);
        }
    }

    private static byte[] longToBytes(long x) {
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
        buffer.putLong(x);
        return buffer.array();
    }

    /**
     * Get some device and boot-specific information to mix into the kernel's
     * entropy pool.  This information probably won't contain much actual
     * entropy, but that's fine because we don't ask the kernel to credit it.
     * Writes to {@code /dev/urandom} can only increase or have no effect on the
     * quality of random numbers, never decrease it.
     *
     * <p>The main goal here is just to initialize the entropy pool differently
     * on devices that might otherwise be identical and have very little other
     * entropy available.  Therefore, we include various system properties that
     * can vary on a per-device and/or per-build basis.  We also include some
     * timestamps, as these might vary on a per-boot basis and be not easily
     * observable or guessable by an attacker.
     */
    private byte[] getDeviceSpecificInformation() {
        StringBuilder b = new StringBuilder();
        b.append(DEVICE_SPECIFIC_INFO_HEADER);
        b.append(START_TIME).append('\n');
        b.append(START_NANOTIME).append('\n');
        b.append(SystemProperties.get("ro.serialno")).append('\n');
        b.append(SystemProperties.get("ro.bootmode")).append('\n');
        b.append(SystemProperties.get("ro.baseband")).append('\n');
        b.append(SystemProperties.get("ro.carrier")).append('\n');
        b.append(SystemProperties.get("ro.bootloader")).append('\n');
        b.append(SystemProperties.get("ro.hardware")).append('\n');
        b.append(SystemProperties.get("ro.revision")).append('\n');
        b.append(SystemProperties.get("ro.build.fingerprint")).append('\n');
        b.append(new Object().hashCode()).append('\n');
        b.append(System.currentTimeMillis()).append('\n');
        b.append(System.nanoTime()).append('\n');
        return b.toString().getBytes();
    }

    private static File getSystemDir() {
        File dataDir = Environment.getDataDirectory();
        File systemDir = new File(dataDir, "system");
        systemDir.mkdirs();
        return systemDir;
    }
}
