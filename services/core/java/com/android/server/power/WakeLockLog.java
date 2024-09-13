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

package com.android.server.power;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.os.Process;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Simple Log for wake lock events. Optimized to reduce memory usage.
 *
 * The wake lock events are ultimately saved in-memory in a pre-allocated byte-based ring-buffer.
 *
 * Most of the work of this log happens in the {@link BackgroundThread}.
 *
 * The main log is basically just a sequence of the two wake lock events (ACQUIRE and RELEASE).
 * Each entry in the log stores the following data:
 *  {
 *    event type (RELEASE | ACQUIRE),
 *    time (64-bit from System.currentTimeMillis()),
 *    wake-lock ID {ownerUID (int) + tag (String)},
 *    wake-lock flags
 *  }
 *
 * In order to maximize the number of entries that fit into the log, there are various efforts made
 * to compress what we store; of which two are fairly significant and contribute the most to the
 * complexity of this code:
 * A) Relative Time
 *     - Time in each log entry is stored as an 8-bit value and is relative to the time of the
 *       previous event. When relative time is too large for 8-bits, we add a third type of event
 *       called TIME_RESET, which is used to add a new 64-bit reference-time event to the log.
 *       In practice, TIME_RESETs seem to make up about 10% or less of the total events depending
 *       on the device usage.
 * B) Wake-lock tag/ID as indexes
 *     - Wake locks are often reused many times. To avoid storing large strings in the ring buffer,
 *       we maintain a {@link TagDatabase} that associates each wakelock tag with an 7-bit index.
 *       The main log stores only these 7-bit indexes instead of whole strings.
 *
 * To make the code a bit more organized, there exists a class {@link EntryByteTranslator} which
 * uses the tag database, and reference-times to convert between a {@link LogEntry} and the
 * byte sequence that is ultimately stored in the main log, {@link TheLog}.
 */
final class WakeLockLog {
    private static final String TAG = "PowerManagerService.WLLog";

    private static final boolean DEBUG = false;

    private static final int TYPE_TIME_RESET = 0x0;
    private static final int TYPE_ACQUIRE = 0x1;
    private static final int TYPE_RELEASE = 0x2;
    private static final int MAX_LOG_ENTRY_BYTE_SIZE = 9;
    private static final int LOG_SIZE = 1024 * 10;
    private static final int LOG_SIZE_MIN = MAX_LOG_ENTRY_BYTE_SIZE + 1;

    private static final int TAG_DATABASE_SIZE = 128;
    private static final int TAG_DATABASE_SIZE_MAX = 128;

    private static final int LEVEL_SCREEN_TIMEOUT_OVERRIDE_WAKE_LOCK = 0;
    private static final int LEVEL_PARTIAL_WAKE_LOCK = 1;
    private static final int LEVEL_FULL_WAKE_LOCK = 2;
    private static final int LEVEL_SCREEN_DIM_WAKE_LOCK = 3;
    private static final int LEVEL_SCREEN_BRIGHT_WAKE_LOCK = 4;
    private static final int LEVEL_PROXIMITY_SCREEN_OFF_WAKE_LOCK = 5;
    private static final int LEVEL_DOZE_WAKE_LOCK = 6;
    private static final int LEVEL_DRAW_WAKE_LOCK = 7;

    private static final String[] LEVEL_TO_STRING = {
        "override",
        "partial",
        "full",
        "screen-dim",
        "screen-bright",
        "prox",
        "doze",
        "draw"
    };

    /**
     * Flags use the same bit field as the level, so must start at the next available bit
     * after the largest level.
     */
    private static final int FLAG_ON_AFTER_RELEASE = 0x8;
    private static final int FLAG_ACQUIRE_CAUSES_WAKEUP = 0x10;
    private static final int FLAG_SYSTEM_WAKELOCK = 0x20;

    private static final int MASK_LOWER_6_BITS = 0x3F;
    private static final int MASK_LOWER_7_BITS = 0x7F;

    private static final String[] REDUCED_TAG_PREFIXES =
            {"*job*/", "*gms_scheduler*/", "IntentOp:"};

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("MM-dd HH:mm:ss.SSS");

    @VisibleForTesting
    static final String SYSTEM_PACKAGE_NAME = "System";

    /**
     * Lock protects WakeLockLog.dump (binder thread) from conflicting with changes to the log
     * happening on the background thread.
     */
    private final Object mLock = new Object();

    private final Injector mInjector;
    private final TheLog mLog;
    private final TagDatabase mTagDatabase;
    private final SimpleDateFormat mDumpsysDateFormat;
    private final Context mContext;

    WakeLockLog(Context context) {
        this(new Injector(), context);
    }

    @VisibleForTesting
    WakeLockLog(Injector injector, Context context) {
        mInjector = injector;
        mTagDatabase = new TagDatabase(injector);
        EntryByteTranslator translator = new EntryByteTranslator(mTagDatabase);
        mLog = new TheLog(injector, translator, mTagDatabase);
        mDumpsysDateFormat = injector.getDateFormat();
        mContext = context;
    }

    /**
     * Receives notifications of an ACQUIRE wake lock event from PowerManager.
     *
     * @param tag The wake lock tag
     * @param ownerUid The owner UID of the wake lock.
     * @param flags Flags used for the wake lock.
     * @param eventTime The time at which the event occurred
     */
    public void onWakeLockAcquired(String tag, int ownerUid, int flags, long eventTime) {
        onWakeLockEvent(TYPE_ACQUIRE, tag, ownerUid, flags, eventTime);
    }

    /**
     * Receives notifications of a RELEASE wake lock event from PowerManager.
     *
     * @param tag The wake lock tag
     * @param ownerUid The owner UID of the wake lock.
     * @param eventTime The time at which the event occurred
     */
    public void onWakeLockReleased(String tag, int ownerUid, long eventTime) {
        onWakeLockEvent(TYPE_RELEASE, tag, ownerUid, 0 /* flags */, eventTime);
    }

    /**
     * Dumps all the wake lock data currently saved in the wake lock log to the specified
     * {@code PrintWriter}.
     *
     * @param pw The {@code PrintWriter} to write to.
     */
    public void dump(PrintWriter pw) {
        dump(pw, false);
    }

    @VisibleForTesting
    void dump(PrintWriter pw, boolean includeTagDb) {
        try {
            synchronized (mLock) {
                pw.println("Wake Lock Log");
                int numEvents = 0;
                int numResets = 0;
                SparseArray<String[]> uidToPackagesCache = new SparseArray();

                for (int i = 0; i < mLog.mSavedAcquisitions.size(); i++) {
                    numEvents++;
                    LogEntry entry = mLog.mSavedAcquisitions.get(i);

                    entry.updatePackageName(uidToPackagesCache, mContext.getPackageManager());

                    if (DEBUG) {
                        pw.print("Saved acquisition no. " + i);
                    }
                    entry.dump(pw, mDumpsysDateFormat);
                }

                LogEntry tempEntry = new LogEntry();  // Temporary entry for the iterator to reuse.
                final Iterator<LogEntry> iterator = mLog.getAllItems(tempEntry);
                while (iterator.hasNext()) {
                    String address = null;
                    if (DEBUG) {
                        // Gets the byte index in the log for the current entry.
                        address = iterator.toString();
                    }
                    LogEntry entry = iterator.next();
                    if (entry != null) {
                        if (entry.type == TYPE_TIME_RESET) {
                            numResets++;
                        } else {
                            numEvents++;
                            entry.updatePackageName(uidToPackagesCache,
                                    mContext.getPackageManager());
                            if (DEBUG) {
                                pw.print(address);
                            }
                            entry.dump(pw, mDumpsysDateFormat);
                        }
                    }
                }
                pw.println("  -");
                pw.println("  Events: " + numEvents + ", Time-Resets: " + numResets);
                pw.println("  Buffer, Bytes used: " + mLog.getUsedBufferSize());
                if (DEBUG || includeTagDb) {
                    pw.println("  " + mTagDatabase);
                }
            }
        } catch (Exception e) {
            pw.println("Exception dumping wake-lock log: " + e.toString());
        }
    }

    /**
     * Adds a new entry to the log based on the specified wake lock parameters.
     *
     * @param eventType The type of event (ACQUIRE, RELEASE);
     * @param tag The wake lock's identifying tag.
     * @param ownerUid The owner UID of the wake lock.
     * @param flags The flags used with the wake lock.
     * @param eventTime The time at which the event occurred
     */
    private void onWakeLockEvent(int eventType, String tag, int ownerUid,
            int flags, long eventTime) {
        if (tag == null) {
            Slog.w(TAG, "Insufficient data to log wakelock [tag: " + tag
                    + ", ownerUid: " + ownerUid
                    + ", flags: 0x" + Integer.toHexString(flags));
            return;
        }

        final long time = (eventTime == -1) ? mInjector.currentTimeMillis() : eventTime;

        final int translatedFlags = eventType == TYPE_ACQUIRE
                ? translateFlagsFromPowerManager(flags)
                : 0;
        handleWakeLockEventInternal(eventType, tagNameReducer(tag), ownerUid, translatedFlags,
                time);
    }

    /**
     * Handles a new wakelock event in the background thread.
     *
     * @param eventType The type of event (ACQUIRE, RELEASE)
     * @param tag The wake lock's identifying tag.
     * @param ownerUid The owner UID of the wake lock.
     * @param flags the flags used with the wake lock.
     */
    private void handleWakeLockEventInternal(int eventType, String tag, int ownerUid, int flags,
            long time) {
        synchronized (mLock) {
            final TagData tagData = mTagDatabase.findOrCreateTag(
                    tag, ownerUid, true /* shouldCreate */);
            mLog.addEntry(new LogEntry(time, eventType, tagData, flags));
        }
    }

    /**
     * Translates wake lock flags from PowerManager into a redefined set that fits
     * in the lower 6-bits of the return value. The results are an OR-ed combination of the
     * flags, {@code WakeLockLog.FLAG_*}, and a log-level, {@code WakeLockLog.LEVEL_*}.
     *
     * @param flags Wake lock flags including {@code PowerManager.*_WAKE_LOCK}
     *              {@link PowerManager#ACQUIRE_CAUSES_WAKEUP}, and
     *              {@link PowerManager#ON_AFTER_RELEASE}.
     * @return The compressed flags value.
     */
    int translateFlagsFromPowerManager(int flags) {
        int newFlags = 0;
        switch(PowerManager.WAKE_LOCK_LEVEL_MASK & flags) {
            case PowerManager.PARTIAL_WAKE_LOCK:
                newFlags = LEVEL_PARTIAL_WAKE_LOCK;
                break;
            case PowerManager.SCREEN_DIM_WAKE_LOCK:
                newFlags = LEVEL_SCREEN_DIM_WAKE_LOCK;
                break;
            case PowerManager.SCREEN_BRIGHT_WAKE_LOCK:
                newFlags = LEVEL_SCREEN_BRIGHT_WAKE_LOCK;
                break;
            case PowerManager.FULL_WAKE_LOCK:
                newFlags = LEVEL_FULL_WAKE_LOCK;
                break;
            case PowerManager.DOZE_WAKE_LOCK:
                newFlags = LEVEL_DOZE_WAKE_LOCK;
                break;
            case PowerManager.DRAW_WAKE_LOCK:
                newFlags = LEVEL_DRAW_WAKE_LOCK;
                break;
            case PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK:
                newFlags = LEVEL_PROXIMITY_SCREEN_OFF_WAKE_LOCK;
                break;
            case PowerManager.SCREEN_TIMEOUT_OVERRIDE_WAKE_LOCK:
                newFlags = LEVEL_SCREEN_TIMEOUT_OVERRIDE_WAKE_LOCK;
                break;
            default:
                Slog.w(TAG, "Unsupported lock level for logging, flags: " + flags);
                break;
        }
        if ((flags & PowerManager.ACQUIRE_CAUSES_WAKEUP) != 0) {
            newFlags |= FLAG_ACQUIRE_CAUSES_WAKEUP;
        }
        if ((flags & PowerManager.ON_AFTER_RELEASE) != 0) {
            newFlags |= FLAG_ON_AFTER_RELEASE;
        }
        if ((flags & PowerManager.SYSTEM_WAKELOCK) != 0) {
            newFlags |= FLAG_SYSTEM_WAKELOCK;
        }
        return newFlags;
    }

    /**
     * Reduce certain wakelock tags to something smaller.
     * e.g. "*job* /com.aye.bee.cee/dee.ee.eff.Gee$Eich" -> "*job* /c.a.b.c/d.e.e.Gee$Eich"
     * This is used to save space when storing the tags in the Tag Database.
     *
     * @param tag The tag name to reduce
     * @return A reduced version of the tag name.
     */
    private String tagNameReducer(String tag) {
        if (tag == null) {
            return null;
        }

        String reduciblePrefix = null;
        for (String reducedTagPrefix : REDUCED_TAG_PREFIXES) {
            if (tag.startsWith(reducedTagPrefix)) {
                reduciblePrefix = reducedTagPrefix;
                break;
            }
        }

        if (reduciblePrefix != null) {
            final StringBuilder sb = new StringBuilder();

            // add prefix first
            sb.append(tag, 0, reduciblePrefix.length());

            // Stop looping on final marker
            final int end = Math.max(tag.lastIndexOf("/"), tag.lastIndexOf("."));
            boolean printNext = true;
            int index = sb.length();  // Start looping after the prefix
            for (; index < end; index++) {
                char c = tag.charAt(index);
                boolean isMarker = (c == '.' || c == '/');
                // We print all markers and the character that follows each marker
                if (isMarker || printNext) {
                    sb.append(c);
                }
                printNext = isMarker;
            }
            sb.append(tag.substring(index));  // append everything that is left
            return sb.toString();
        }
        return tag;
    }

    /**
     * Represents a wakelock-event entry in the log.
     * Holds all the data of a wakelock. The lifetime of this class is fairly short as the data
     * within this type is eventually written to the log as bytes and the instances discarded until
     * the log is read again which is fairly infrequent.
     *
     * At the time of this writing, this can be one of three types of entries:
     * 1) Wake lock acquire
     * 2) Wake lock release
     * 3) Time reset
     */
    static class LogEntry {
        /**
         * Type of wake lock from the {@code WakeLockLog.TYPE_*} set.
         */
        public int type;

        /**
         * Time of the wake lock entry as taken from System.currentTimeMillis().
         */
        public long time;

        /**
         * Data about the wake lock tag.
         */
        public TagData tag;

        /**
         * Flags used with the wake lock.
         */
        public int flags;

        /**
         * The name of the package that acquired the wake lock
         */
        public String packageName;

        LogEntry() {}

        LogEntry(long time, int type, TagData tag, int flags) {
            set(time, type, tag, flags);
        }

        /**
         * Sets the values of the log entry.
         * This is exposed to ease the reuse of {@code LogEntry} instances.
         *
         * @param time Time of the entry.
         * @param type Type of entry.
         * @param tag Tag data of the wake lock.
         * @param flags Flags used with the wake lock.
         */
        public void set(long time, int type, TagData tag, int flags) {
            this.time = time;
            this.type = type;
            this.tag = tag;
            this.flags = flags;
        }

        /**
         * Dumps this entry to the specified {@link PrintWriter}.
         *
         * @param pw The print-writer to dump to.
         * @param dateFormat The date format to use for outputing times.
         */
        public void dump(PrintWriter pw, SimpleDateFormat dateFormat) {
            pw.println("  " + toStringInternal(dateFormat));
        }

        /**
         * Converts the entry to a string.
         * date - ownerUid - (ACQ|REL) tag [(flags)]
         * e.g., 1999-01-01 12:01:01.123 - 10012 - ACQ bluetooth_timer (partial)
         */
        @Override
        public String toString() {
            return toStringInternal(DATE_FORMAT);
        }

        /**
         * Converts the entry to a string.
         * date - ownerUid - (ACQ|REL) tag [(flags)]
         * e.g., 1999-01-01 12:01:01.123 - 10012 - ACQ bluetooth_timer (partial)
         *
         * @param dateFormat The date format to use for outputing times.
         * @return The string output of this class instance.
         */
        private String toStringInternal(SimpleDateFormat dateFormat) {
            StringBuilder sb = new StringBuilder();
            if (type == TYPE_TIME_RESET) {
                return dateFormat.format(new Date(time)) + " - RESET";
            }
            sb.append(dateFormat.format(new Date(time)))
                    .append(" - ")
                    .append(tag == null ? "---" : tag.ownerUid);
            if (packageName != null) {
                sb.append(" (");
                sb.append(packageName);
                sb.append(")");
            }
            sb.append(" - ")
                    .append(type == TYPE_ACQUIRE ? "ACQ" : "REL")
                    .append(" ")
                    .append(tag == null ? "UNKNOWN" : tag.tag);
            if (type == TYPE_ACQUIRE) {
                sb.append(" (");
                flagsToString(sb);
                sb.append(")");
            }
            return sb.toString();
        }

        private void flagsToString(StringBuilder sb) {
            sb.append(LEVEL_TO_STRING[flags & 0x7]);
            if ((flags & FLAG_ON_AFTER_RELEASE) == FLAG_ON_AFTER_RELEASE) {
                sb.append(",on-after-release");
            }
            if ((flags & FLAG_ACQUIRE_CAUSES_WAKEUP) == FLAG_ACQUIRE_CAUSES_WAKEUP) {
                sb.append(",acq-causes-wake");
            }
            if ((flags & FLAG_SYSTEM_WAKELOCK) == FLAG_SYSTEM_WAKELOCK) {
                sb.append(",system-wakelock");
            }
        }

        /**
         * Update the package name using the cache if available or the package manager.
         * @param uidToPackagesCache The cache of package names
         * @param packageManager The package manager
         */
        public void updatePackageName(SparseArray<String[]> uidToPackagesCache,
                PackageManager packageManager) {
            if (tag == null) {
                return;
            }

            if (tag.ownerUid == Process.SYSTEM_UID) {
                packageName = SYSTEM_PACKAGE_NAME;
            }
            else {
                String[] packages;
                if (uidToPackagesCache.contains(tag.ownerUid)) {
                    packages = uidToPackagesCache.get(tag.ownerUid);
                } else {
                    packages = packageManager.getPackagesForUid(tag.ownerUid);
                    uidToPackagesCache.put(tag.ownerUid, packages);
                }

                if (packages != null && packages.length > 0) {
                    packageName = packages[0];
                    if (packages.length > 1) {
                        StringBuilder sb = new StringBuilder();
                        sb.append(packageName)
                                .append(",...");
                        packageName = sb.toString();
                    }
                }
            }
        }
    }

    /**
     * Converts between a {@link LogEntry} instance and a byte sequence.
     *
     * This is used to convert {@link LogEntry}s to a series of bytes before being written into
     * the log, and vice-versa when reading from the log.
     *
     * This method employs the compression techniques that are mentioned in the header of
     * {@link WakeLockLog}: Relative-time and Tag-indexing.  Please see the header for the
     * description of both.
     *
     * The specific byte formats used are explained more thoroughly in the method {@link #toBytes}.
     */
    static class EntryByteTranslator {

        // Error codes that can be returned when converting to bytes.
        static final int ERROR_TIME_IS_NEGATIVE = -1;  // Relative time is negative
        static final int ERROR_TIME_TOO_LARGE = -2;  // Relative time is out of valid range (0-255)

        private final TagDatabase mTagDatabase;

        EntryByteTranslator(TagDatabase tagDatabase) {
            mTagDatabase = tagDatabase;
        }

        /**
         * Translates the specified bytes into a LogEntry instance, if possible.
         *
         * See {@link #toBytes} for an explanation of the byte formats.
         *
         * @param bytes The bytes to read.
         * @param timeReference The reference time to use when reading the relative time from the
         *                      bytes buffer.
         * @param entryToReuse The entry instance to write to. If null, this method will create a
         *                     new instance.
         * @return The converted entry, or null if data is corrupt.
         */
        LogEntry fromBytes(byte[] bytes, long timeReference, LogEntry entryToReuse) {
            if (bytes == null || bytes.length == 0) {
                return null;
            }

            // Create an entry if non if passed in to use
            LogEntry entry = entryToReuse != null ? entryToReuse : new LogEntry();

            int type = (bytes[0] >> 6) & 0x3;
            if ((type & 0x2) == 0x2) {
                // As long as the highest order bit of the byte is set, it is a release
                type = TYPE_RELEASE;
            }
            switch (type) {
                case TYPE_ACQUIRE: {
                    if (bytes.length < 3) {
                        break;
                    }

                    int flags = bytes[0] & MASK_LOWER_6_BITS;
                    int tagIndex = bytes[1] & MASK_LOWER_7_BITS;
                    TagData tag = mTagDatabase.getTag(tagIndex);
                    long time = (bytes[2] & 0xFF) + timeReference;
                    entry.set(time, TYPE_ACQUIRE, tag, flags);
                    return entry;
                }
                case TYPE_RELEASE: {
                    if (bytes.length < 2) {
                        break;
                    }

                    int flags = 0;
                    int tagIndex = bytes[0] & MASK_LOWER_7_BITS;
                    TagData tag = mTagDatabase.getTag(tagIndex);
                    long time = (bytes[1] & 0xFF) + timeReference;
                    entry.set(time, TYPE_RELEASE, tag, flags);
                    return entry;
                }
                case TYPE_TIME_RESET: {
                    if (bytes.length < 9) {
                        break;
                    }

                    long time = ((bytes[1] & 0xFFL) << 56)
                                | ((bytes[2] & 0xFFL) << 48)
                                | ((bytes[3] & 0xFFL) << 40)
                                | ((bytes[4] & 0xFFL) << 32)
                                | ((bytes[5] & 0xFFL) << 24)
                                | ((bytes[6] & 0xFFL) << 16)
                                | ((bytes[7] & 0xFFL) << 8)
                                | (bytes[8] & 0xFFL);
                    entry.set(time, TYPE_TIME_RESET, null, 0);
                    return entry;
                }
                default:
                    Slog.w(TAG, "Type not recognized [" + type + "]", new Exception());
                    break;
            }
            return null;
        }

        /**
         * Converts and writes the specified entry into the specified byte array.
         * If the byte array is null or too small, then the method writes nothing, but still returns
         * the number of bytes necessary to write the entry.
         *
         * Byte format used for each type:
         *
         * TYPE_RELEASE:
         *                                        bits
         *                0      1      2       3       4       5       6       7
         *   bytes  0  [  1   |            7-bit wake lock tag index                ]
         *          1  [                 8-bit relative time                        ]
         *
         *
         * TYPE_ACQUIRE:
         *                                        bits
         *                0      1      2       3       4       5       6       7
         *          0  [  0      1   |            wake lock flags                   ]
         *  bytes   1  [unused|         7-bit wake lock tag index                   ]
         *          2  [                 8-bit relative time                        ]
         *
         *
         * TYPE_TIME_RESET:
         *                                        bits
         *                0      1      2       3       4       5       6       7
         *          0  [  0      0   |            unused                            ]
         *  bytes 1-9  [                  64-bit reference-time                     ]
         *
         * @param entry The entry to convert/write
         * @param bytes The buffer to write to, or null to just return the necessary bytes.
         * @param timeReference The reference-time used to calculate relative time of the entry.
         * @return The number of bytes written to buffer, or required to write to the buffer.
         */
        int toBytes(LogEntry entry, byte[] bytes, long timeReference) {
            final int sizeNeeded;
            switch (entry.type) {
                case TYPE_ACQUIRE: {
                    sizeNeeded = 3;
                    if (bytes != null && bytes.length >= sizeNeeded) {
                        int relativeTime = getRelativeTime(timeReference, entry.time);
                        if (relativeTime < 0) {
                            // Negative relative time indicates error code
                            return relativeTime;
                        }
                        bytes[0] = (byte) ((TYPE_ACQUIRE << 6)
                                | (entry.flags & MASK_LOWER_6_BITS));
                        bytes[1] = (byte) mTagDatabase.getTagIndex(entry.tag);
                        bytes[2] = (byte) (relativeTime & 0xFF);  // Lower 8 bits of the time
                        if (DEBUG) {
                            Slog.d(TAG, "ACQ - Setting bytes: " + Arrays.toString(bytes));
                        }
                    }
                    break;
                }
                case TYPE_RELEASE: {
                    sizeNeeded = 2;
                    if (bytes != null && bytes.length >= sizeNeeded) {
                        int relativeTime = getRelativeTime(timeReference, entry.time);
                        if (relativeTime < 0) {
                            // Negative relative time indicates error code
                            return relativeTime;
                        }
                        bytes[0] = (byte) (0x80 | mTagDatabase.getTagIndex(entry.tag));
                        bytes[1] = (byte) (relativeTime & 0xFF);  // Lower 8 bits of the time
                        if (DEBUG) {
                            Slog.d(TAG, "REL - Setting bytes: " + Arrays.toString(bytes));
                        }
                    }
                    break;
                }
                case TYPE_TIME_RESET: {
                    sizeNeeded = 9;
                    long time = entry.time;
                    if (bytes != null && bytes.length >= sizeNeeded) {
                        bytes[0] = (TYPE_TIME_RESET << 6);
                        bytes[1] = (byte) ((time >> 56) & 0xFF);
                        bytes[2] = (byte) ((time >> 48) & 0xFF);
                        bytes[3] = (byte) ((time >> 40) & 0xFF);
                        bytes[4] = (byte) ((time >> 32) & 0xFF);
                        bytes[5] = (byte) ((time >> 24) & 0xFF);
                        bytes[6] = (byte) ((time >> 16) & 0xFF);
                        bytes[7] = (byte) ((time >> 8) & 0xFF);
                        bytes[8] = (byte) (time & 0xFF);
                    }
                    break;
                }
                default:
                    throw new RuntimeException("Unknown type " +  entry);
            }

            return sizeNeeded;
        }

        /**
         * Calculates the relative time between the specified time and timeReference.  The relative
         * time is expected to be non-negative and fit within 8-bits (values between 0-255). If the
         * relative time is outside of that range an error code will be returned instead.
         *
         * @param time
         * @param timeReference
         * @return The relative time between time and timeReference, or an error code.
         */
        private int getRelativeTime(long timeReference, long time) {
            if (time < timeReference) {
                if (DEBUG) {
                    Slog.w(TAG, "ERROR_TIME_IS_NEGATIVE");
                }
                return ERROR_TIME_IS_NEGATIVE;
            }
            long relativeTime = time - timeReference;
            if (relativeTime > 255) {
                if (DEBUG) {
                    Slog.w(TAG, "ERROR_TIME_TOO_LARGE");
                }
                return ERROR_TIME_TOO_LARGE;
            }
            return (int) relativeTime;
        }
    }

    /**
     * Main implementation of the ring buffer used to store the log entries.  This class takes
     * {@link LogEntry} instances and adds them to the ring buffer, utilizing
     * {@link EntryByteTranslator} to convert byte {@link LogEntry} to bytes within the buffer.
     *
     * This class also implements the logic around TIME_RESET events. Since the LogEntries store
     * their time (8-bit) relative to the previous event, this class can add
     * {@link #TYPE_TIME_RESET} LogEntries as necessary to allow a LogEntry's relative time to fit
     * within that range.
     */
    static class TheLog {
        private final EntryByteTranslator mTranslator;

        /**
         * Temporary buffer used when converting a new entry to bytes for writing to the buffer.
         * Allocating once allows us to avoid allocating a buffer with each write.
         */
        private final byte[] mTempBuffer = new byte[MAX_LOG_ENTRY_BYTE_SIZE];

        /**
         * Second temporary buffer used when reading and writing bytes from the buffer.
         * A second temporary buffer is necessary since additional items can be read concurrently
         * from {@link #mTempBuffer}. E.g., Adding an entry to a full buffer requires removing
         * other entries from the buffer.
         */
        private final byte[] mReadWriteTempBuffer = new byte[MAX_LOG_ENTRY_BYTE_SIZE];

        /**
         * Main log buffer.
         */
        private final byte[] mBuffer;

        /**
         * Start index of the ring buffer.
         */
        private int mStart = 0;

        /**
         * Current end index of the ring buffer.
         */
        private int mEnd = 0;

        /**
         * Start time of the entries in the buffer. The first item stores an 8-bit time that is
         * relative to this value.
         */
        private long mStartTime = 0;

        /**
         * The time of the last entry in the buffer. Reading the time from the last entry to
         * calculate the relative time of a new one is sufficiently hard to prefer saving the value
         * here instead.
         */
        private long mLatestTime = 0;

        /**
         * Counter for number of changes (adds or removes) that have been done to the buffer.
         */
        private long mChangeCount = 0;

        private final TagDatabase mTagDatabase;

        /**
         * Wake lock acquisition events should continue to be printed until their corresponding
         * release event is removed from the log.
         */
        private final List<LogEntry> mSavedAcquisitions;

        TheLog(Injector injector, EntryByteTranslator translator, TagDatabase tagDatabase) {
            final int logSize = Math.max(injector.getLogSize(), LOG_SIZE_MIN);
            mBuffer = new byte[logSize];

            mTranslator = translator;
            mTagDatabase = tagDatabase;

            // Register to be notified when an older tag is removed from the TagDatabase to make
            // room for a new entry.
            mTagDatabase.setCallback(new TagDatabase.Callback() {
                @Override public void onIndexRemoved(int index) {
                    removeTagIndex(index);
                }
            });

            mSavedAcquisitions = new ArrayList();
        }

        /**
         * Returns the amount of space being used in the ring buffer (in bytes).
         *
         * @return Used buffer size in bytes.
         */
        int getUsedBufferSize() {
            return mBuffer.length - getAvailableSpace();
        }

        /**
         * Adds the specified {@link LogEntry} to the log by converting it to bytes and writing
         * those bytes to the buffer.
         *
         * This method can have side effects of removing old values from the ring buffer and
         * adding an extra TIME_RESET entry if necessary.
         */
        void addEntry(LogEntry entry) {
            if (isBufferEmpty()) {
                // First item being added, do initialization.
                mStartTime = mLatestTime = entry.time;
            }

            int size = mTranslator.toBytes(entry, mTempBuffer, mLatestTime);
            if (size == EntryByteTranslator.ERROR_TIME_IS_NEGATIVE) {
                return;  // Wholly unexpected circumstance...just break out now.
            } else if (size == EntryByteTranslator.ERROR_TIME_TOO_LARGE) {
                // The relative time between the last entry and this new one is too large
                // to fit in our byte format...we need to create a new Time-Reset event and add
                // that to the log first.
                addEntry(new LogEntry(entry.time, TYPE_TIME_RESET, null, 0));
                size = mTranslator.toBytes(entry, mTempBuffer, mLatestTime);
            }

            if (size > MAX_LOG_ENTRY_BYTE_SIZE || size <= 0) {
                Slog.w(TAG, "Log entry size is out of expected range: " + size);
                return;
            }

            // In case the buffer is full or nearly full, ensure there is a proper amount of space
            // for the new entry.
            if (!makeSpace(size)) {
                return;  // Doesn't fit
            }

            if (DEBUG) {
                Slog.d(TAG, "Wrote New Entry @(" + mEnd + ") [" + entry + "] as "
                        + Arrays.toString(mTempBuffer));
            }
            // Set the bytes and update our end index & timestamp.
            writeBytesAt(mEnd, mTempBuffer, size);
            if (DEBUG) {
                Slog.d(TAG, "Read written Entry @(" + mEnd + ") ["
                        + readEntryAt(mEnd, mLatestTime, null));
            }
            mEnd = (mEnd + size) % mBuffer.length;
            mLatestTime = entry.time;

            TagDatabase.updateTagTime(entry.tag, entry.time);
            mChangeCount++;
        }

        /**
         * Returns an {@link Iterator} of {@link LogEntry}s for all the entries in the log.
         *
         * If the log is modified while the entries are being read, the iterator will throw a
         * {@link ConcurrentModificationException}.
         *
         * @param tempEntry A temporary {@link LogEntry} instance to use so that new instances
         *                  aren't allocated with every call to {@code Iterator.next}.
         */
        Iterator<LogEntry> getAllItems(final LogEntry tempEntry) {
            return new Iterator<LogEntry>() {
                private int mCurrent = mStart;  // Current read position in the log.
                private long mCurrentTimeReference = mStartTime;  // Current time-reference to use.
                private final long mChangeValue = mChangeCount;  // Used to track if buffer changed.

                /**
                 * @return True if there are more elements to iterate through, false otherwise.\
                 * @throws ConcurrentModificationException if the buffer contents change.
                 */
                @Override
                public boolean hasNext() {
                    checkState();
                    return mCurrent != mEnd;
                }

                /**
                 * Returns the next element in the iterator.
                 *
                 * @return The next entry in the iterator
                 * @throws NoSuchElementException if iterator reaches the end.
                 * @throws ConcurrentModificationException if buffer contents change.
                 */
                @Override
                public LogEntry next() {
                    checkState();

                    if (!hasNext()) {
                        throw new NoSuchElementException("No more entries left.");
                    }

                    LogEntry entry = readEntryAt(mCurrent, mCurrentTimeReference, tempEntry);
                    int size = mTranslator.toBytes(entry, null, mStartTime);
                    mCurrent = (mCurrent + size) % mBuffer.length;
                    mCurrentTimeReference = entry.time;

                    return entry;
                }

                @Override public String toString() {
                    return "@" + mCurrent;
                }

                /**
                 * @throws ConcurrentModificationException if the underlying buffer has changed
                 * since this iterator was instantiated.
                 */
                private void checkState() {
                    if (mChangeValue != mChangeCount) {
                        throw new ConcurrentModificationException("Buffer modified, old change: "
                                + mChangeValue + ", new change: " + mChangeCount);
                    }
                }
            };
        }

        /**
         * Cleans up old tag index references from the entire log.
         * Called when an older wakelock tag is removed from the tag database. This happens
         * when the database needed additional room for newer tags.
         *
         * This is a fairly expensive operation.  Reads all the entries from the buffer, which can
         * be around 1500 for a 10Kb buffer. It will write back any entries that use the tag as
         * well, but that's not many of them. Commonly-used tags dont ever make it to this part.
         *
         * If necessary, in the future we can keep track of the number of tag-users the same way we
         * keep track of a tag's last-used-time to stop having to do this for old tags that dont
         * have entries in the logs any more. Light testing has shown that for a 10Kb
         * buffer, there are about 5 or fewer (of 1500) entries with "UNKNOWN" tag...which means
         * this operation does happen, but not very much.
         *
         * @param tagIndex The index of the tag, as stored in the log
         */
        private void removeTagIndex(int tagIndex) {
            if (isBufferEmpty()) {
                return;
            }

            int readIndex = mStart;
            long timeReference = mStartTime;
            final LogEntry reusableEntryInstance = new LogEntry();
            while (readIndex != mEnd) {
                LogEntry entry = readEntryAt(readIndex, timeReference, reusableEntryInstance);
                if (DEBUG) {
                    Slog.d(TAG, "Searching to remove tags @ " + readIndex + ": " + entry);
                }
                if (entry == null) {
                    Slog.w(TAG, "Entry is unreadable - Unexpected @ " + readIndex);
                    break;  // cannot continue if entries are now corrupt
                }
                if (entry.tag != null && entry.tag.index == tagIndex) {
                    // We found an entry that uses the tag being removed. Re-write the
                    // entry back without a tag.
                    entry.tag = null;  // remove the tag, and write it back
                    writeEntryAt(readIndex, entry, timeReference);
                    if (DEBUG) {
                        Slog.d(TAG, "Remove tag index: " + tagIndex + " @ " + readIndex);
                    }
                }
                timeReference = entry.time;
                int entryByteSize = mTranslator.toBytes(entry, null, 0L);
                readIndex = (readIndex + entryByteSize) % mBuffer.length;
            }
        }

        /**
         * Removes entries from the buffer until the specified amount of space is available for use.
         *
         * @param spaceNeeded The number of bytes needed in the buffer.
         * @return True if there is space enough in the buffer, false otherwise.
         */
        private boolean makeSpace(int spaceNeeded) {
            // Test the size of the buffer can fit it first, so that we dont loop forever in the
            // following while loop.
            if (mBuffer.length < spaceNeeded + 1) {
                return false;
            }

            // We check spaceNeeded + 1 so that mStart + mEnd aren't equal.  We use them being equal
            // to mean that the buffer is empty...so avoid that.
            while (getAvailableSpace() < (spaceNeeded + 1)) {
                removeOldestItem();
            }
            return true;
        }

        /**
         * Returns the available space of the ring buffer.
         */
        private int getAvailableSpace() {
            return mEnd > mStart ? mBuffer.length - (mEnd - mStart) :
                    (mEnd < mStart ? mStart - mEnd :
                     mBuffer.length);
        }

        /**
         * Removes the oldest item from the buffer if the buffer is not empty.
         */
        private void removeOldestItem() {
            if (isBufferEmpty()) {
                // No items to remove
                return;
            }

            // Copy the contents of the start of the buffer to our temporary buffer.
            LogEntry entry = readEntryAt(mStart, mStartTime, null);
            if (entry.type == TYPE_ACQUIRE) {
                // We'll continue to print the event until the corresponding release event is also
                // removed from the log.
                mSavedAcquisitions.add(entry);
            } else if (entry.type == TYPE_RELEASE) {
                // We no longer need to print the corresponding acquire event.
                for (int i = 0; i < mSavedAcquisitions.size(); i++) {
                    if (Objects.equals(mSavedAcquisitions.get(i).tag, entry.tag)) {
                        mSavedAcquisitions.remove(i);
                        break;
                    }
                }
            }
            if (DEBUG) {
                Slog.d(TAG, "Removing oldest item at @ " + mStart + ", found: " + entry);
            }
            int size = mTranslator.toBytes(entry, null, mStartTime);
            mStart = (mStart + size) % mBuffer.length;
            mStartTime = entry.time;  // new start time
            mChangeCount++;
        }

        /**
         * Returns true if the buffer is currently unused (contains zero entries).
         *
         * @return True if empty, false otherwise.
         */
        private boolean isBufferEmpty() {
            return mStart == mEnd;
        }

        /**
         * Reads an entry from the specified index in the buffer.
         *
         * @param index Index into the buffer from which to read.
         * @param timeReference Reference time to use when creating the {@link LogEntry}.
         * @param entryToSet Temporary entry to use instead of allocating a new one.
         * @return the log-entry instance that was read.
         */
        private LogEntry readEntryAt(int index, long timeReference, LogEntry entryToSet) {
            for (int i = 0; i < MAX_LOG_ENTRY_BYTE_SIZE; i++) {
                int indexIntoMainBuffer = (index + i) % mBuffer.length;
                if (indexIntoMainBuffer == mEnd) {
                    break;
                }
                mReadWriteTempBuffer[i] = mBuffer[indexIntoMainBuffer];
            }
            return mTranslator.fromBytes(mReadWriteTempBuffer, timeReference, entryToSet);
        }

        /**
         * Write a specified {@link LogEntry} to the buffer at the specified index.
         *
         * @param index Index in which to write in the buffer.
         * @param entry The entry to write into the buffer.
         * @param timeReference The reference time to use when calculating the relative time.
         */
        private void writeEntryAt(int index, LogEntry entry, long timeReference) {
            int size = mTranslator.toBytes(entry, mReadWriteTempBuffer, timeReference);
            if (size > 0) {
                if (DEBUG) {
                    Slog.d(TAG, "Writing Entry (" + index + ") [" + entry + "] as "
                            + Arrays.toString(mReadWriteTempBuffer));
                }
                writeBytesAt(index, mReadWriteTempBuffer, size);
            }
        }

        /**
         * Write the specified bytes into the buffer at the specified index.
         * Handling wrap-around calculation for the ring-buffer.
         *
         * @param index The index from which to start writing.
         * @param buffer The buffer of bytes to be written.
         * @param size The amount of bytes to write from {@code buffer} to the log.
         */
        private void writeBytesAt(int index, byte[] buffer, int size) {
            for (int i = 0; i < size; i++) {
                int indexIntoMainBuffer = (index + i) % mBuffer.length;
                mBuffer[indexIntoMainBuffer] = buffer[i];
            }
            if (DEBUG) {
                Slog.d(TAG, "Write Byte: " + Arrays.toString(buffer));
            }
        }
    }

    /**
     * An in-memory database of wake lock {@link TagData}. All tags stored in the database are given
     * a 7-bit index. This index is then used by {@link TheLog} when translating {@link LogEntry}
     * instanced into bytes.
     *
     * If a new tag is added when the database is full, the oldest tag is removed. The oldest tag
     * is calculated using {@link TagData#lastUsedTime}.
     */
    static class TagDatabase {
        private final int mInvalidIndex;
        private final TagData[] mArray;
        private Callback mCallback;

        TagDatabase(Injector injector) {
            int size = Math.min(injector.getTagDatabaseSize(), TAG_DATABASE_SIZE_MAX);

            // Largest possible index used as "INVALID", hence the (size - 1) sizing.
            mArray = new TagData[size - 1];
            mInvalidIndex = size - 1;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Tag Database: size(").append(mArray.length).append(")");
            int entries = 0;
            int byteEstimate = 0;
            int tagSize = 0;
            int tags = 0;
            for (TagData tagData : mArray) {
                byteEstimate += 8;  // reference pointer
                TagData data = tagData;
                if (data != null) {
                    entries++;
                    byteEstimate += data.getByteSize();
                    if (data.tag != null) {
                        tags++;
                        tagSize += data.tag.length();
                    }
                }
            }
            sb.append(", entries: ").append(entries);
            sb.append(", Bytes used: ").append(byteEstimate);
            if (DEBUG) {
                sb.append(", Avg tag size: ").append(tagSize / tags);
                sb.append("\n    ").append(Arrays.toString(mArray));
            }
            return sb.toString();
        }

        /**
         * Sets the callback.
         *
         * @param callback The callback to set.
         */
        public void setCallback(Callback callback) {
            mCallback = callback;
        }

        /**
         * Returns the tag corresponding to the specified index.
         *
         * @param index The index to search for.
         */
        public TagData getTag(int index) {
            if (index < 0 || index >= mArray.length || index == mInvalidIndex) {
                return null;
            }
            return mArray[index];
        }

        /**
         * Returns an existing tag for the specified wake lock tag + ownerUid.
         *
         * @param tag The wake lock tag.
         * @param ownerUid The wake lock's ownerUid.
         * @return the TagData instance.
         */
        public TagData getTag(String tag, int ownerUid) {
            return findOrCreateTag(tag, ownerUid, false /* shouldCreate */);
        }

        /**
         * Returns the index for the corresponding tag.
         *
         * @param tagData The tag-data to search for.
         * @return the corresponding index, or mInvalidIndex of none is found.
         */
        public int getTagIndex(TagData tagData) {
            return tagData == null ? mInvalidIndex : tagData.index;
        }

        /**
         * Returns a tag instance for the specified wake lock tag and ownerUid. If the data
         * does not exist in the database, it will be created if so specified by
         * {@code shouldCreate}.
         *
         * @param tagStr The wake lock's tag.
         * @param ownerUid The wake lock's owner Uid.
         * @param shouldCreate True when the tag should be created if it doesn't already exist.
         * @return The tag-data instance that was found or created.
         */
        public TagData findOrCreateTag(String tagStr, int ownerUid, boolean shouldCreate) {
            int firstAvailable = -1;
            TagData oldest = null;
            int oldestIndex = -1;

            // Loop through and find the tag to be used.
            TagData tag = new TagData(tagStr, ownerUid);
            for (int i = 0; i < mArray.length; i++) {
                TagData current = mArray[i];
                if (tag.equals(current)) {
                    // found it
                    return current;
                } else if (!shouldCreate) {
                    continue;
                } else if (current != null) {
                    // See if this entry is the oldest entry, in case
                    // we need to replace it.
                    if (oldest == null || current.lastUsedTime < oldest.lastUsedTime) {
                        oldestIndex = i;
                        oldest = current;
                    }
                } else if (firstAvailable == -1) {
                    firstAvailable = i;
                }
            }

            // Item not found, and we shouldn't create one.
            if (!shouldCreate) {
                return null;
            }

            // If we need to remove an index, report to listeners that we are removing an index.
            boolean useOldest = firstAvailable == -1;
            if (useOldest && mCallback != null) {
                if (DEBUG) {
                    Slog.d(TAG, "Removing tag index: " + oldestIndex + " = " + oldest);
                }
                mCallback.onIndexRemoved(oldestIndex);
            }
            setToIndex(tag, firstAvailable != -1 ? firstAvailable : oldestIndex);
            return tag;
        }

        /**
         * Updates the last-used-time of the specified tag.
         *
         * @param tag The tag to update.
         * @param time The new last-used-time for the tag.
         */
        public static void updateTagTime(TagData tag, long time) {
            if (tag != null) {
                tag.lastUsedTime = time;
            }
        }

        /**
         * Sets a specified tag to the specified index.
         */
        private void setToIndex(TagData tag, int index) {
            if (index < 0 || index >= mArray.length) {
                return;
            }
            TagData current = mArray[index];
            if (current != null) {
                // clean up the reference in the TagData instance first.
                current.index = mInvalidIndex;

                if (DEBUG) {
                    Slog.d(TAG, "Replaced tag " + current.tag + " from index " + index + " with tag"
                            + tag);
                }
            }

            mArray[index] = tag;
            tag.index = index;
        }

        /**
         * Callback on which to be notified of changes to {@link TagDatabase}.
         */
        interface Callback {

            /**
             * Handles removals of TagData indexes.
             *
             * @param index the index being removed.
             */
            void onIndexRemoved(int index);
        }
    }

    /**
     * This class represents unique wake lock tags that are stored in {@link TagDatabase}.
     * Contains both the wake lock tag data (tag + ownerUid) as well as index and last-used
     * time data as it relates to the tag-database.
     */
    static class TagData {
        public String tag;  // Wake lock tag
        public int ownerUid;  // Wake lock owner Uid
        public int index;  // Index of the tag in the tag-database
        public long lastUsedTime;  // Last time that this entry was used

        TagData(String tag, int ownerUid) {
            this.tag = tag;
            this.ownerUid = ownerUid;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o instanceof TagData) {
                TagData other = (TagData) o;
                return TextUtils.equals(tag, other.tag) && ownerUid == other.ownerUid;
            }
            return false;
        }

        @Override
        public String toString() {
            return "[" + ownerUid + " ; " + tag + "]";
        }

        /**
         * Returns an estimate of the number of bytes used by each instance of this class.
         * Used for debug purposes.
         *
         * @return the size of this tag-data.
         */
        int getByteSize() {
            int bytes = 0;
            bytes += 8;  // tag reference-pointer;
            bytes += tag == null ? 0 : tag.length() * 2;
            bytes += 4;  // ownerUid
            bytes += 4;  // index
            bytes += 8;  // lastUsedTime
            return bytes;
        }
    }

    /**
     * Injector used by {@link WakeLockLog} for testing purposes.
     */
    public static class Injector {
        public int getTagDatabaseSize() {
            return TAG_DATABASE_SIZE;
        }

        public int getLogSize() {
            return LOG_SIZE;
        }

        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }

        public SimpleDateFormat getDateFormat() {
            return DATE_FORMAT;
        }
    }
}
