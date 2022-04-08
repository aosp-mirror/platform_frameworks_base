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

package com.android.internal.os;

import android.os.BatteryStats;
import android.os.Parcel;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ParseUtils;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * BatteryStatsHistory encapsulates battery history files.
 * Battery history record is appended into buffer {@link #mHistoryBuffer} and backed up into
 * {@link #mActiveFile}.
 * When {@link #mHistoryBuffer} size reaches {@link BatteryStatsImpl.Constants#MAX_HISTORY_BUFFER},
 * current mActiveFile is closed and a new mActiveFile is open.
 * History files are under directory /data/system/battery-history/.
 * History files have name battery-history-<num>.bin. The file number <num> starts from zero and
 * grows sequentially.
 * The mActiveFile is always the highest numbered history file.
 * The lowest number file is always the oldest file.
 * The highest number file is always the newest file.
 * The file number grows sequentially and we never skip number.
 * When count of history files exceeds {@link BatteryStatsImpl.Constants#MAX_HISTORY_FILES},
 * the lowest numbered file is deleted and a new file is open.
 *
 * All interfaces in BatteryStatsHistory should only be called by BatteryStatsImpl and protected by
 * locks on BatteryStatsImpl object.
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public class BatteryStatsHistory {
    private static final boolean DEBUG = false;
    private static final String TAG = "BatteryStatsHistory";
    public static final String HISTORY_DIR = "battery-history";
    public static final String FILE_SUFFIX = ".bin";
    private static final int MIN_FREE_SPACE = 100 * 1024 * 1024;

    private final BatteryStatsImpl mStats;
    private final Parcel mHistoryBuffer;
    private final File mHistoryDir;
    /**
     * The active history file that the history buffer is backed up into.
     */
    private AtomicFile mActiveFile;
    /**
     * A list of history files with incremental indexes.
     */
    private final List<Integer> mFileNumbers = new ArrayList<>();

    /**
     * A list of small history parcels, used when BatteryStatsImpl object is created from
     * deserialization of a parcel, such as Settings app or checkin file.
     */
    private List<Parcel> mHistoryParcels = null;

    /**
     * When iterating history files, the current file index.
     */
    private int mCurrentFileIndex;
    /**
     * When iterating history files, the current file parcel.
     */
    private Parcel mCurrentParcel;
    /**
     * When iterating history file, the current parcel's Parcel.dataSize().
     */
    private int mCurrentParcelEnd;
    /**
     * When iterating history files, the current record count.
     */
    private int mRecordCount = 0;
    /**
     * Used when BatteryStatsImpl object is created from deserialization of a parcel,
     * such as Settings app or checkin file, to iterate over history parcels.
     */
    private int mParcelIndex = 0;

    /**
     * Constructor
     * @param stats BatteryStatsImpl object.
     * @param systemDir typically /data/system
     * @param historyBuffer The in-memory history buffer.
     */
    public BatteryStatsHistory(BatteryStatsImpl stats, File systemDir, Parcel historyBuffer) {
        mStats = stats;
        mHistoryBuffer = historyBuffer;
        mHistoryDir = new File(systemDir, HISTORY_DIR);
        mHistoryDir.mkdirs();
        if (!mHistoryDir.exists()) {
            Slog.wtf(TAG, "HistoryDir does not exist:" + mHistoryDir.getPath());
        }

        final Set<Integer> dedup = new ArraySet<>();
        // scan directory, fill mFileNumbers and mActiveFile.
        mHistoryDir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                final int b = name.lastIndexOf(FILE_SUFFIX);
                if (b <= 0) {
                    return false;
                }
                final Integer c =
                        ParseUtils.parseInt(name.substring(0, b), -1);
                if (c != -1) {
                    dedup.add(c);
                    return true;
                } else {
                    return false;
                }
            }
        });
        if (!dedup.isEmpty()) {
            mFileNumbers.addAll(dedup);
            Collections.sort(mFileNumbers);
            setActiveFile(mFileNumbers.get(mFileNumbers.size() - 1));
        } else {
            // No file found, default to have file 0.
            mFileNumbers.add(0);
            setActiveFile(0);
        }
    }

    /**
     * Used when BatteryStatsImpl object is created from deserialization of a parcel,
     * such as Settings app or checkin file.
     * @param stats BatteryStatsImpl object.
     * @param historyBuffer the history buffer inside BatteryStatsImpl
     */
    public BatteryStatsHistory(BatteryStatsImpl stats, Parcel historyBuffer) {
        mStats = stats;
        mHistoryDir = null;
        mHistoryBuffer = historyBuffer;
    }
    /**
     * Set the active file that mHistoryBuffer is backed up into.
     *
     * @param fileNumber the history file that mHistoryBuffer is backed up into.
     */
    private void setActiveFile(int fileNumber) {
        mActiveFile = getFile(fileNumber);
        if (DEBUG) {
            Slog.d(TAG, "activeHistoryFile:" + mActiveFile.getBaseFile().getPath());
        }
    }

    /**
     * Create history AtomicFile from file number.
     * @param num file number.
     * @return AtomicFile object.
     */
    private AtomicFile getFile(int num) {
        return new AtomicFile(
                new File(mHistoryDir,  num + FILE_SUFFIX));
    }

    /**
     * When {@link #mHistoryBuffer} reaches {@link BatteryStatsImpl.Constants#MAX_HISTORY_BUFFER},
     * create next history file.
     */
    public void startNextFile() {
        if (mFileNumbers.isEmpty()) {
            Slog.wtf(TAG, "mFileNumbers should never be empty");
            return;
        }
        // The last number in mFileNumbers is the highest number. The next file number is highest
        // number plus one.
        final int next = mFileNumbers.get(mFileNumbers.size() - 1) + 1;
        mFileNumbers.add(next);
        setActiveFile(next);

        // if free disk space is less than 100MB, delete oldest history file.
        if (!hasFreeDiskSpace()) {
            int oldest = mFileNumbers.remove(0);
            getFile(oldest).delete();
        }

        // if there are more history files than allowed, delete oldest history files.
        // MAX_HISTORY_FILES can be updated by GService config at run time.
        while (mFileNumbers.size() > mStats.mConstants.MAX_HISTORY_FILES) {
            int oldest = mFileNumbers.get(0);
            getFile(oldest).delete();
            mFileNumbers.remove(0);
        }
    }

    /**
     * Delete all existing history files. Active history file start from number 0 again.
     */
    public void resetAllFiles() {
        for (Integer i : mFileNumbers) {
            getFile(i).delete();
        }
        mFileNumbers.clear();
        mFileNumbers.add(0);
        setActiveFile(0);
    }

    /**
     * Start iterating history files and history buffer.
     * @return always return true.
     */
    public boolean startIteratingHistory() {
        mRecordCount = 0;
        mCurrentFileIndex = 0;
        mCurrentParcel = null;
        mCurrentParcelEnd = 0;
        mParcelIndex = 0;
        return true;
    }

    /**
     * Finish iterating history files and history buffer.
     */
    public void finishIteratingHistory() {
        // setDataPosition so mHistoryBuffer Parcel can be written.
        mHistoryBuffer.setDataPosition(mHistoryBuffer.dataSize());
        if (DEBUG) {
            Slog.d(TAG, "Battery history records iterated: " + mRecordCount);
        }
    }

    /**
     * When iterating history files and history buffer, always start from the lowest numbered
     * history file, when reached the mActiveFile (highest numbered history file), do not read from
     * mActiveFile, read from history buffer instead because the buffer has more updated data.
     * @param out a history item.
     * @return The parcel that has next record. null if finished all history files and history
     *         buffer
     */
    public Parcel getNextParcel(BatteryStats.HistoryItem out) {
        if (mRecordCount == 0) {
            // reset out if it is the first record.
            out.clear();
        }
        ++mRecordCount;

        // First iterate through all records in current parcel.
        if (mCurrentParcel != null)
        {
            if (mCurrentParcel.dataPosition() < mCurrentParcelEnd) {
                // There are more records in current parcel.
                return mCurrentParcel;
            } else if (mHistoryBuffer == mCurrentParcel) {
                // finished iterate through all history files and history buffer.
                return null;
            } else if (mHistoryParcels == null
                    || !mHistoryParcels.contains(mCurrentParcel)) {
                // current parcel is from history file.
                mCurrentParcel.recycle();
            }
        }

        // Try next available history file.
        // skip the last file because its data is in history buffer.
        while (mCurrentFileIndex < mFileNumbers.size() - 1) {
            mCurrentParcel = null;
            mCurrentParcelEnd = 0;
            final Parcel p = Parcel.obtain();
            AtomicFile file = getFile(mFileNumbers.get(mCurrentFileIndex++));
            if (readFileToParcel(p, file)) {
                int bufSize = p.readInt();
                int curPos = p.dataPosition();
                mCurrentParcelEnd = curPos + bufSize;
                mCurrentParcel = p;
                if (curPos < mCurrentParcelEnd) {
                    return mCurrentParcel;
                }
            } else {
                p.recycle();
            }
        }

        // mHistoryParcels is created when BatteryStatsImpl object is created from deserialization
        // of a parcel, such as Settings app or checkin file.
        if (mHistoryParcels != null) {
            while (mParcelIndex < mHistoryParcels.size()) {
                final Parcel p = mHistoryParcels.get(mParcelIndex++);
                if (!skipHead(p)) {
                    continue;
                }
                final int bufSize = p.readInt();
                final int curPos = p.dataPosition();
                mCurrentParcelEnd = curPos + bufSize;
                mCurrentParcel = p;
                if (curPos < mCurrentParcelEnd) {
                    return mCurrentParcel;
                }
            }
        }

        // finished iterator through history files (except the last one), now history buffer.
        if (mHistoryBuffer.dataSize() <= 0) {
            // buffer is empty.
            return null;
        }
        mHistoryBuffer.setDataPosition(0);
        mCurrentParcel = mHistoryBuffer;
        mCurrentParcelEnd = mCurrentParcel.dataSize();
        return mCurrentParcel;
    }

    /**
     * Read history file into a parcel.
     * @param out the Parcel read into.
     * @param file the File to read from.
     * @return true if success, false otherwise.
     */
    public boolean readFileToParcel(Parcel out, AtomicFile file) {
        byte[] raw = null;
        try {
            final long start = SystemClock.uptimeMillis();
            raw = file.readFully();
            if (DEBUG) {
                Slog.d(TAG, "readFileToParcel:" + file.getBaseFile().getPath()
                        + " duration ms:" + (SystemClock.uptimeMillis() - start));
            }
        } catch(Exception e) {
            Slog.e(TAG, "Error reading file "+ file.getBaseFile().getPath(), e);
            return false;
        }
        out.unmarshall(raw, 0, raw.length);
        out.setDataPosition(0);
        return skipHead(out);
    }

    /**
     * Skip the header part of history parcel.
     * @param p history parcel to skip head.
     * @return true if version match, false if not.
     */
    private boolean skipHead(Parcel p) {
        p.setDataPosition(0);
        final int version = p.readInt();
        if (version != mStats.VERSION) {
            return false;
        }
        // skip historyBaseTime field.
        p.readLong();
        return true;
    }

    /**
     * Read all history files and serialize into a big Parcel. This is to send history files to
     * Settings app since Settings app can not access /data/system directory.
     * Checkin file also call this method.
     * @param out the output parcel
     */
    public void writeToParcel(Parcel out) {
        final long start = SystemClock.uptimeMillis();
        out.writeInt(mFileNumbers.size() - 1);
        for(int i = 0;  i < mFileNumbers.size() - 1; i++) {
            AtomicFile file = getFile(mFileNumbers.get(i));
            byte[] raw = new byte[0];
            try {
                raw = file.readFully();
            } catch(Exception e) {
                Slog.e(TAG, "Error reading file "+ file.getBaseFile().getPath(), e);
            }
            out.writeByteArray(raw);
        }
        if (DEBUG) {
            Slog.d(TAG, "writeToParcel duration ms:" + (SystemClock.uptimeMillis() - start));
        }
    }

    /**
     * This is for Settings app, when Settings app receives big history parcel, it call
     * this method to parse it into list of parcels.
     * Checkin file also call this method.
     * @param in the input parcel.
     */
    public void readFromParcel(Parcel in) {
        final long start = SystemClock.uptimeMillis();
        mHistoryParcels = new ArrayList<>();
        final int count = in.readInt();
        for(int i = 0; i < count; i++) {
            byte[] temp = in.createByteArray();
            if (temp.length == 0) {
                continue;
            }
            Parcel p = Parcel.obtain();
            p.unmarshall(temp, 0, temp.length);
            p.setDataPosition(0);
            mHistoryParcels.add(p);
        }
        if (DEBUG) {
            Slog.d(TAG, "readFromParcel duration ms:" + (SystemClock.uptimeMillis() - start));
        }
    }

    /**
     * @return true if there is more than 100MB free disk space left.
     */
    private boolean hasFreeDiskSpace() {
        final StatFs stats = new StatFs(mHistoryDir.getAbsolutePath());
        return stats.getAvailableBytes() > MIN_FREE_SPACE;
    }

    public List<Integer> getFilesNumbers() {
        return mFileNumbers;
    }

    public AtomicFile getActiveFile() {
        return mActiveFile;
    }

    /**
     * @return the total size of all history files and history buffer.
     */
    public int getHistoryUsedSize() {
        int ret = 0;
        for(int i = 0; i < mFileNumbers.size() - 1; i++) {
            ret += getFile(mFileNumbers.get(i)).getBaseFile().length();
        }
        ret += mHistoryBuffer.dataSize();
        if (mHistoryParcels != null) {
            for(int i = 0; i < mHistoryParcels.size(); i++) {
                ret += mHistoryParcels.get(i).dataSize();
            }
        }
        return ret;
    }
}
