package com.android.internal.os;

import android.os.StrictMode;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.LongSparseLongArray;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

/**
 * Reads DDR time spent at various frequencies and stores the data.  Supports diff comparison with
 * other KernelMemoryBandwidthStats objects. The sysfs file has the format:
 *
 * freq time_in_bucket ... time_in_bucket
 *      ...
 * freq time_in_bucket ... time_in_bucket
 *
 * where time is measured in nanoseconds.
 */
public class KernelMemoryBandwidthStats {
    private static final String TAG = "KernelMemoryBandwidthStats";

    private static final String mSysfsFile = "/sys/kernel/memory_state_time/show_stat";
    private static final boolean DEBUG = false;

    protected final LongSparseLongArray mBandwidthEntries = new LongSparseLongArray();
    private boolean mStatsDoNotExist = false;

    public void updateStats() {
        if (mStatsDoNotExist) {
            // Skip reading.
            return;
        }

        final long startTime = SystemClock.uptimeMillis();

        StrictMode.ThreadPolicy policy = StrictMode.allowThreadDiskReads();
        try (BufferedReader reader = new BufferedReader(new FileReader(mSysfsFile))) {
            parseStats(reader);
        } catch (FileNotFoundException e) {
            Slog.w(TAG, "No kernel memory bandwidth stats available");
            mBandwidthEntries.clear();
            mStatsDoNotExist = true;
        } catch (IOException e) {
            Slog.e(TAG, "Failed to read memory bandwidth: " + e.getMessage());
            mBandwidthEntries.clear();
        } finally {
            StrictMode.setThreadPolicy(policy);
        }

        final long readTime = SystemClock.uptimeMillis() - startTime;
        if (DEBUG || readTime > 100) {
            Slog.w(TAG, "Reading memory bandwidth file took " + readTime + "ms");
        }
    }

    @VisibleForTesting
    public void parseStats(BufferedReader reader) throws IOException {
        String line;
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(' ');
        mBandwidthEntries.clear();
        while ((line = reader.readLine()) != null) {
            splitter.setString(line);
            splitter.next();
            int bandwidth = 0;
            int index;
            do {
                if ((index = mBandwidthEntries.indexOfKey(bandwidth)) >= 0) {
                    mBandwidthEntries.put(bandwidth, mBandwidthEntries.valueAt(index)
                            + Long.parseLong(splitter.next()) / 1000000);
                } else {
                    mBandwidthEntries.put(bandwidth, Long.parseLong(splitter.next()) / 1000000);
                }
                if (DEBUG) {
                    Slog.d(TAG, String.format("bandwidth: %s time: %s", bandwidth,
                            mBandwidthEntries.get(bandwidth)));
                }
                bandwidth++;
            } while(splitter.hasNext());
        }
    }

    public LongSparseLongArray getBandwidthEntries() {
        return mBandwidthEntries;
    }
}
