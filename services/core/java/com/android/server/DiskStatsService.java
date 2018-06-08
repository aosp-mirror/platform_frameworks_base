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

package com.android.server;

import android.content.Context;
import android.os.Binder;
import android.os.Environment;
import android.os.IBinder;
import android.os.IStoraged;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.service.diskstats.DiskStatsAppSizesProto;
import android.service.diskstats.DiskStatsCachedValuesProto;
import android.service.diskstats.DiskStatsFreeSpaceProto;
import android.service.diskstats.DiskStatsServiceDumpProto;
import android.util.Log;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.DumpUtils;
import com.android.server.storage.DiskStatsFileLogger;
import com.android.server.storage.DiskStatsLoggingService;

import libcore.io.IoUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * This service exists only as a "dumpsys" target which reports
 * statistics about the status of the disk.
 */
public class DiskStatsService extends Binder {
    private static final String TAG = "DiskStatsService";
    private static final String DISKSTATS_DUMP_FILE = "/data/system/diskstats_cache.json";

    private final Context mContext;

    public DiskStatsService(Context context) {
        mContext = context;
        DiskStatsLoggingService.schedule(context);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpAndUsageStatsPermission(mContext, TAG, pw)) return;

        // Run a quick-and-dirty performance test: write 512 bytes
        byte[] junk = new byte[512];
        for (int i = 0; i < junk.length; i++) junk[i] = (byte) i;  // Write nonzero bytes

        File tmp = new File(Environment.getDataDirectory(), "system/perftest.tmp");
        FileOutputStream fos = null;
        IOException error = null;

        long before = SystemClock.uptimeMillis();
        try {
            fos = new FileOutputStream(tmp);
            fos.write(junk);
        } catch (IOException e) {
            error = e;
        } finally {
            try { if (fos != null) fos.close(); } catch (IOException e) {}
        }

        long after = SystemClock.uptimeMillis();
        if (tmp.exists()) tmp.delete();

        boolean protoFormat = hasOption(args, "--proto");
        ProtoOutputStream proto = null;

        if (protoFormat) {
            proto = new ProtoOutputStream(fd);
            pw = null;
            proto.write(DiskStatsServiceDumpProto.HAS_TEST_ERROR, error != null);
            if (error != null) {
                proto.write(DiskStatsServiceDumpProto.ERROR_MESSAGE, error.toString());
            } else {
                proto.write(DiskStatsServiceDumpProto.WRITE_512B_LATENCY_MILLIS, after - before);
            }
        } else {
            if (error != null) {
                pw.print("Test-Error: ");
                pw.println(error.toString());
            } else {
                pw.print("Latency: ");
                pw.print(after - before);
                pw.println("ms [512B Data Write]");
            }
        }

        if (protoFormat) {
            reportDiskWriteSpeedProto(proto);
        } else {
            reportDiskWriteSpeed(pw);
        }

        reportFreeSpace(Environment.getDataDirectory(), "Data", pw, proto,
                DiskStatsFreeSpaceProto.FOLDER_DATA);
        reportFreeSpace(Environment.getDownloadCacheDirectory(), "Cache", pw, proto,
                DiskStatsFreeSpaceProto.FOLDER_CACHE);
        reportFreeSpace(new File("/system"), "System", pw, proto,
                DiskStatsFreeSpaceProto.FOLDER_SYSTEM);

        boolean fileBased = StorageManager.isFileEncryptedNativeOnly();
        boolean blockBased = fileBased ? false : StorageManager.isBlockEncrypted();
        if (protoFormat) {
            if (fileBased) {
                proto.write(DiskStatsServiceDumpProto.ENCRYPTION,
                        DiskStatsServiceDumpProto.ENCRYPTION_FILE_BASED);
            } else if (blockBased) {
                proto.write(DiskStatsServiceDumpProto.ENCRYPTION,
                        DiskStatsServiceDumpProto.ENCRYPTION_FULL_DISK);
            } else {
                proto.write(DiskStatsServiceDumpProto.ENCRYPTION,
                        DiskStatsServiceDumpProto.ENCRYPTION_NONE);
            }
        } else if (fileBased) {
            pw.println("File-based Encryption: true");
        }

        if (protoFormat) {
            reportCachedValuesProto(proto);
        } else {
            reportCachedValues(pw);
        }

        if (protoFormat) {
            proto.flush();
        }
        // TODO: Read /proc/yaffs and report interesting values;
        // add configurable (through args) performance test parameters.
    }

    private void reportFreeSpace(File path, String name, PrintWriter pw,
            ProtoOutputStream proto, int folderType) {
        try {
            StatFs statfs = new StatFs(path.getPath());
            long bsize = statfs.getBlockSize();
            long avail = statfs.getAvailableBlocks();
            long total = statfs.getBlockCount();
            if (bsize <= 0 || total <= 0) {
                throw new IllegalArgumentException(
                        "Invalid stat: bsize=" + bsize + " avail=" + avail + " total=" + total);
            }

            if (proto != null) {
                long freeSpaceToken = proto.start(DiskStatsServiceDumpProto.PARTITIONS_FREE_SPACE);
                proto.write(DiskStatsFreeSpaceProto.FOLDER, folderType);
                proto.write(DiskStatsFreeSpaceProto.AVAILABLE_SPACE_KB, avail * bsize / 1024);
                proto.write(DiskStatsFreeSpaceProto.TOTAL_SPACE_KB, total * bsize / 1024);
                proto.end(freeSpaceToken);
            } else {
                pw.print(name);
                pw.print("-Free: ");
                pw.print(avail * bsize / 1024);
                pw.print("K / ");
                pw.print(total * bsize / 1024);
                pw.print("K total = ");
                pw.print(avail * 100 / total);
                pw.println("% free");
            }
        } catch (IllegalArgumentException e) {
            if (proto != null) {
                // Empty proto
            } else {
                pw.print(name);
                pw.print("-Error: ");
                pw.println(e.toString());
            }
            return;
        }
    }

    private boolean hasOption(String[] args, String arg) {
        for (String opt : args) {
            if (arg.equals(opt)) {
                return true;
            }
        }
        return false;
    }

    // If you change this method, make sure to modify the Proto version of this method as well.
    private void reportCachedValues(PrintWriter pw) {
        try {
            String jsonString = IoUtils.readFileAsString(DISKSTATS_DUMP_FILE);
            JSONObject json = new JSONObject(jsonString);
            pw.print("App Size: ");
            pw.println(json.getLong(DiskStatsFileLogger.APP_SIZE_AGG_KEY));
            pw.print("App Data Size: ");
            pw.println(json.getLong(DiskStatsFileLogger.APP_DATA_SIZE_AGG_KEY));
            pw.print("App Cache Size: ");
            pw.println(json.getLong(DiskStatsFileLogger.APP_CACHE_AGG_KEY));
            pw.print("Photos Size: ");
            pw.println(json.getLong(DiskStatsFileLogger.PHOTOS_KEY));
            pw.print("Videos Size: ");
            pw.println(json.getLong(DiskStatsFileLogger.VIDEOS_KEY));
            pw.print("Audio Size: ");
            pw.println(json.getLong(DiskStatsFileLogger.AUDIO_KEY));
            pw.print("Downloads Size: ");
            pw.println(json.getLong(DiskStatsFileLogger.DOWNLOADS_KEY));
            pw.print("System Size: ");
            pw.println(json.getLong(DiskStatsFileLogger.SYSTEM_KEY));
            pw.print("Other Size: ");
            pw.println(json.getLong(DiskStatsFileLogger.MISC_KEY));
            pw.print("Package Names: ");
            pw.println(json.getJSONArray(DiskStatsFileLogger.PACKAGE_NAMES_KEY));
            pw.print("App Sizes: ");
            pw.println(json.getJSONArray(DiskStatsFileLogger.APP_SIZES_KEY));
            pw.print("App Data Sizes: ");
            pw.println(json.getJSONArray(DiskStatsFileLogger.APP_DATA_KEY));
            pw.print("Cache Sizes: ");
            pw.println(json.getJSONArray(DiskStatsFileLogger.APP_CACHES_KEY));
        } catch (IOException | JSONException e) {
            Log.w(TAG, "exception reading diskstats cache file", e);
        }
    }

    private void reportCachedValuesProto(ProtoOutputStream proto) {
        try {
            String jsonString = IoUtils.readFileAsString(DISKSTATS_DUMP_FILE);
            JSONObject json = new JSONObject(jsonString);
            long cachedValuesToken = proto.start(DiskStatsServiceDumpProto.CACHED_FOLDER_SIZES);

            proto.write(DiskStatsCachedValuesProto.AGG_APPS_SIZE_KB,
                    json.getLong(DiskStatsFileLogger.APP_SIZE_AGG_KEY));
            proto.write(DiskStatsCachedValuesProto.AGG_APPS_DATA_SIZE_KB,
                    json.getLong(DiskStatsFileLogger.APP_DATA_SIZE_AGG_KEY));
            proto.write(DiskStatsCachedValuesProto.AGG_APPS_CACHE_SIZE_KB,
                    json.getLong(DiskStatsFileLogger.APP_CACHE_AGG_KEY));
            proto.write(DiskStatsCachedValuesProto.PHOTOS_SIZE_KB,
                    json.getLong(DiskStatsFileLogger.PHOTOS_KEY));
            proto.write(DiskStatsCachedValuesProto.VIDEOS_SIZE_KB,
                    json.getLong(DiskStatsFileLogger.VIDEOS_KEY));
            proto.write(DiskStatsCachedValuesProto.AUDIO_SIZE_KB,
                    json.getLong(DiskStatsFileLogger.AUDIO_KEY));
            proto.write(DiskStatsCachedValuesProto.DOWNLOADS_SIZE_KB,
                    json.getLong(DiskStatsFileLogger.DOWNLOADS_KEY));
            proto.write(DiskStatsCachedValuesProto.SYSTEM_SIZE_KB,
                    json.getLong(DiskStatsFileLogger.SYSTEM_KEY));
            proto.write(DiskStatsCachedValuesProto.OTHER_SIZE_KB,
                    json.getLong(DiskStatsFileLogger.MISC_KEY));

            JSONArray packageNamesArray = json.getJSONArray(DiskStatsFileLogger.PACKAGE_NAMES_KEY);
            JSONArray appSizesArray = json.getJSONArray(DiskStatsFileLogger.APP_SIZES_KEY);
            JSONArray appDataSizesArray = json.getJSONArray(DiskStatsFileLogger.APP_DATA_KEY);
            JSONArray cacheSizesArray = json.getJSONArray(DiskStatsFileLogger.APP_CACHES_KEY);
            final int len = packageNamesArray.length();
            if (len == appSizesArray.length()
                    && len == appDataSizesArray.length()
                    && len == cacheSizesArray.length()) {
                for (int i = 0; i < len; i++) {
                    long packageToken = proto.start(DiskStatsCachedValuesProto.APP_SIZES);

                    proto.write(DiskStatsAppSizesProto.PACKAGE_NAME,
                            packageNamesArray.getString(i));
                    proto.write(DiskStatsAppSizesProto.APP_SIZE_KB, appSizesArray.getLong(i));
                    proto.write(DiskStatsAppSizesProto.APP_DATA_SIZE_KB, appDataSizesArray.getLong(i));
                    proto.write(DiskStatsAppSizesProto.CACHE_SIZE_KB, cacheSizesArray.getLong(i));

                    proto.end(packageToken);
                }
            } else {
                Slog.wtf(TAG, "Sizes of packageNamesArray, appSizesArray, appDataSizesArray "
                        + " and cacheSizesArray are not the same");
            }

            proto.end(cachedValuesToken);
        } catch (IOException | JSONException e) {
            Log.w(TAG, "exception reading diskstats cache file", e);
        }
    }

    private int getRecentPerf() throws RemoteException, IllegalStateException {
        IBinder binder = ServiceManager.getService("storaged");
        if (binder == null) throw new IllegalStateException("storaged not found");
        IStoraged storaged = IStoraged.Stub.asInterface(binder);
        return storaged.getRecentPerf();
    }

    // Keep reportDiskWriteSpeed and reportDiskWriteSpeedProto in sync
    private void reportDiskWriteSpeed(PrintWriter pw) {
        try {
            long perf = getRecentPerf();
            if (perf != 0) {
                pw.print("Recent Disk Write Speed (kB/s) = ");
                pw.println(perf);
            } else {
                pw.println("Recent Disk Write Speed data unavailable");
                Log.w(TAG, "Recent Disk Write Speed data unavailable!");
            }
        } catch (RemoteException | IllegalStateException e) {
            pw.println(e.toString());
            Log.e(TAG, e.toString());
        }
    }

    private void reportDiskWriteSpeedProto(ProtoOutputStream proto) {
        try {
            long perf = getRecentPerf();
            if (perf != 0) {
                proto.write(DiskStatsServiceDumpProto.BENCHMARKED_WRITE_SPEED_KBPS, perf);
            } else {
                Log.w(TAG, "Recent Disk Write Speed data unavailable!");
            }
        } catch (RemoteException | IllegalStateException e) {
            Log.e(TAG, e.toString());
        }
    }
}
