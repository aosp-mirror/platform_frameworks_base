/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.util.leak;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import androidx.core.content.FileProvider;
import android.util.Log;

import com.android.systemui.Dependency;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Utility class for dumping, compressing, sending, and serving heap dump files.
 *
 * <p>Unlike the Internet, this IS a big truck you can dump something on.
 */
public class DumpTruck {
    private static final String FILEPROVIDER_AUTHORITY = "com.android.systemui.fileprovider";
    private static final String FILEPROVIDER_PATH = "leak";

    private static final String TAG = "DumpTruck";
    private static final int BUFSIZ = 512 * 1024; // 512K

    private final Context context;
    private Uri hprofUri;
    final StringBuilder body = new StringBuilder();

    public DumpTruck(Context context) {
        this.context = context;
    }

    /**
     * Capture memory for the given processes and zip them up for sharing.
     *
     * @param pids
     * @return this, for chaining
     */
    public DumpTruck captureHeaps(int[] pids) {
        final GarbageMonitor gm = Dependency.get(GarbageMonitor.class);

        final File dumpDir = new File(context.getCacheDir(), FILEPROVIDER_PATH);
        dumpDir.mkdirs();
        hprofUri = null;

        body.setLength(0);
        body.append("Build: ").append(Build.DISPLAY).append("\n\nProcesses:\n");

        final ArrayList<String> paths = new ArrayList<String>();
        final int myPid = android.os.Process.myPid();

        final int[] pids_copy = Arrays.copyOf(pids, pids.length);
        for (int pid : pids_copy) {
            body.append("  pid ").append(pid);
            if (gm != null) {
                GarbageMonitor.ProcessMemInfo info = gm.getMemInfo(pid);
                if (info != null) {
                    body.append(":")
                            .append(" up=")
                            .append(info.getUptime())
                            .append(" pss=")
                            .append(info.currentPss)
                            .append(" uss=")
                            .append(info.currentUss);
                }
            }
            if (pid == myPid) {
                final String path =
                        new File(dumpDir, String.format("heap-%d.ahprof", pid)).getPath();
                Log.v(TAG, "Dumping memory info for process " + pid + " to " + path);
                try {
                    android.os.Debug.dumpHprofData(path); // will block
                    paths.add(path);
                    body.append(" (hprof attached)");
                } catch (IOException e) {
                    Log.e(TAG, "error dumping memory:", e);
                    body.append("\n** Could not dump heap: \n").append(e.toString()).append("\n");
                }
            }
            body.append("\n");
        }

        try {
            final String zipfile =
                    new File(dumpDir, String.format("hprof-%d.zip", System.currentTimeMillis()))
                            .getCanonicalPath();
            if (DumpTruck.zipUp(zipfile, paths)) {
                final File pathFile = new File(zipfile);
                hprofUri = FileProvider.getUriForFile(context, FILEPROVIDER_AUTHORITY, pathFile);
            }
        } catch (IOException e) {
            Log.e(TAG, "unable to zip up heapdumps", e);
            body.append("\n** Could not zip up files: \n").append(e.toString()).append("\n");
        }

        return this;
    }

    /**
     * Get the Uri of the current heap dump. Be sure to call captureHeaps first.
     *
     * @return Uri to the dump served by the SystemUI file provider
     */
    public Uri getDumpUri() {
        return hprofUri;
    }

    /**
     * Get an ACTION_SEND intent suitable for startActivity() or attaching to a Notification.
     *
     * @return share intent
     */
    public Intent createShareIntent() {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "SystemUI memory dump");

        shareIntent.putExtra(Intent.EXTRA_TEXT, body.toString());

        if (hprofUri != null) {
            shareIntent.setType("application/zip");
            shareIntent.putExtra(Intent.EXTRA_STREAM, hprofUri);
        }
        return shareIntent;
    }

    private static boolean zipUp(String zipfilePath, ArrayList<String> paths) {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipfilePath))) {
            final byte[] buf = new byte[BUFSIZ];

            for (String filename : paths) {
                try (InputStream is = new BufferedInputStream(new FileInputStream(filename))) {
                    ZipEntry entry = new ZipEntry(filename);
                    zos.putNextEntry(entry);
                    int len;
                    while (0 < (len = is.read(buf, 0, BUFSIZ))) {
                        zos.write(buf, 0, len);
                    }
                    zos.closeEntry();
                }
            }
            return true;
        } catch (IOException e) {
            Log.e(TAG, "error zipping up profile data", e);
        }
        return false;
    }
}
