/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.am;

import java.io.FileOutputStream;
import java.io.IOException;

import com.android.internal.util.MemInfoReader;
import com.android.server.wm.WindowManagerService;

import android.graphics.Point;
import android.util.Slog;
import android.view.Display;

/**
 * Activity manager code dealing with processes.
 */
class ProcessList {
    // The minimum time we allow between crashes, for us to consider this
    // application to be bad and stop and its services and reject broadcasts.
    static final int MIN_CRASH_INTERVAL = 60*1000;

    // OOM adjustments for processes in various states:

    // This is a process only hosting activities that are not visible,
    // so it can be killed without any disruption.
    static final int HIDDEN_APP_MAX_ADJ = 15;
    static int HIDDEN_APP_MIN_ADJ = 9;

    // The B list of SERVICE_ADJ -- these are the old and decrepit
    // services that aren't as shiny and interesting as the ones in the A list.
    static final int SERVICE_B_ADJ = 8;

    // This is the process of the previous application that the user was in.
    // This process is kept above other things, because it is very common to
    // switch back to the previous app.  This is important both for recent
    // task switch (toggling between the two top recent apps) as well as normal
    // UI flow such as clicking on a URI in the e-mail app to view in the browser,
    // and then pressing back to return to e-mail.
    static final int PREVIOUS_APP_ADJ = 7;

    // This is a process holding the home application -- we want to try
    // avoiding killing it, even if it would normally be in the background,
    // because the user interacts with it so much.
    static final int HOME_APP_ADJ = 6;

    // This is a process holding an application service -- killing it will not
    // have much of an impact as far as the user is concerned.
    static final int SERVICE_ADJ = 5;

    // This is a process currently hosting a backup operation.  Killing it
    // is not entirely fatal but is generally a bad idea.
    static final int BACKUP_APP_ADJ = 4;

    // This is a process with a heavy-weight application.  It is in the
    // background, but we want to try to avoid killing it.  Value set in
    // system/rootdir/init.rc on startup.
    static final int HEAVY_WEIGHT_APP_ADJ = 3;

    // This is a process only hosting components that are perceptible to the
    // user, and we really want to avoid killing them, but they are not
    // immediately visible. An example is background music playback.
    static final int PERCEPTIBLE_APP_ADJ = 2;

    // This is a process only hosting activities that are visible to the
    // user, so we'd prefer they don't disappear.
    static final int VISIBLE_APP_ADJ = 1;

    // This is the process running the current foreground app.  We'd really
    // rather not kill it!
    static final int FOREGROUND_APP_ADJ = 0;

    // This is a system persistent process, such as telephony.  Definitely
    // don't want to kill it, but doing so is not completely fatal.
    static final int PERSISTENT_PROC_ADJ = -12;

    // The system process runs at the default adjustment.
    static final int SYSTEM_ADJ = -16;

    // Memory pages are 4K.
    static final int PAGE_SIZE = 4*1024;

    // The minimum number of hidden apps we want to be able to keep around,
    // without empty apps being able to push them out of memory.
    static final int MIN_HIDDEN_APPS = 2;

    // The maximum number of hidden processes we will keep around before
    // killing them; this is just a control to not let us go too crazy with
    // keeping around processes on devices with large amounts of RAM.
    static final int MAX_HIDDEN_APPS = 24;

    // We allow empty processes to stick around for at most 30 minutes.
    static final long MAX_EMPTY_TIME = 30*60*1000;

    // The number of hidden at which we don't consider it necessary to do
    // memory trimming.
    static final int TRIM_HIDDEN_APPS = 3;

    // The number of empty apps at which we don't consider it necessary to do
    // memory trimming.
    static final int TRIM_EMPTY_APPS = 3;

    // Threshold of number of hidden+empty where we consider memory critical.
    static final int TRIM_CRITICAL_THRESHOLD = 3;

    // Threshold of number of hidden+empty where we consider memory critical.
    static final int TRIM_LOW_THRESHOLD = 5;

    // We put empty content processes after any hidden processes that have
    // been idle for less than 15 seconds.
    static final long CONTENT_APP_IDLE_OFFSET = 15*1000;

    // We put empty content processes after any hidden processes that have
    // been idle for less than 120 seconds.
    static final long EMPTY_APP_IDLE_OFFSET = 120*1000;

    // These are the various interesting memory levels that we will give to
    // the OOM killer.  Note that the OOM killer only supports 6 slots, so we
    // can't give it a different value for every possible kind of process.
    private final int[] mOomAdj = new int[] {
            FOREGROUND_APP_ADJ, VISIBLE_APP_ADJ, PERCEPTIBLE_APP_ADJ,
            BACKUP_APP_ADJ, HIDDEN_APP_MIN_ADJ, HIDDEN_APP_MAX_ADJ
    };
    // These are the low-end OOM level limits.  This is appropriate for an
    // HVGA or smaller phone with less than 512MB.  Values are in KB.
    private final long[] mOomMinFreeLow = new long[] {
            8192, 12288, 16384,
            24576, 28672, 32768
    };
    // These are the high-end OOM level limits.  This is appropriate for a
    // 1280x800 or larger screen with around 1GB RAM.  Values are in KB.
    private final long[] mOomMinFreeHigh = new long[] {
            32768, 40960, 49152,
            57344, 65536, 81920
    };
    // The actual OOM killer memory levels we are using.
    private final long[] mOomMinFree = new long[mOomAdj.length];

    private final long mTotalMemMb;

    private boolean mHaveDisplaySize;

    ProcessList() {
        MemInfoReader minfo = new MemInfoReader();
        minfo.readMemInfo();
        mTotalMemMb = minfo.getTotalSize()/(1024*1024);
        updateOomLevels(0, 0, false);
    }

    void applyDisplaySize(WindowManagerService wm) {
        if (!mHaveDisplaySize) {
            Point p = new Point();
            wm.getInitialDisplaySize(Display.DEFAULT_DISPLAY, p);
            if (p.x != 0 && p.y != 0) {
                updateOomLevels(p.x, p.y, true);
                mHaveDisplaySize = true;
            }
        }
    }

    private void updateOomLevels(int displayWidth, int displayHeight, boolean write) {
        // Scale buckets from avail memory: at 300MB we use the lowest values to
        // 700MB or more for the top values.
        float scaleMem = ((float)(mTotalMemMb-300))/(700-300);

        // Scale buckets from screen size.
        int minSize = 320*480;  //  153600
        int maxSize = 1280*800; // 1024000  230400 870400  .264
        float scaleDisp = ((float)(displayWidth*displayHeight)-minSize)/(maxSize-minSize);
        //Slog.i("XXXXXX", "scaleDisp=" + scaleDisp + " dw=" + displayWidth + " dh=" + displayHeight);

        StringBuilder adjString = new StringBuilder();
        StringBuilder memString = new StringBuilder();

        float scale = scaleMem > scaleDisp ? scaleMem : scaleDisp;
        if (scale < 0) scale = 0;
        else if (scale > 1) scale = 1;
        for (int i=0; i<mOomAdj.length; i++) {
            long low = mOomMinFreeLow[i];
            long high = mOomMinFreeHigh[i];
            mOomMinFree[i] = (long)(low + ((high-low)*scale));

            if (i > 0) {
                adjString.append(',');
                memString.append(',');
            }
            adjString.append(mOomAdj[i]);
            memString.append((mOomMinFree[i]*1024)/PAGE_SIZE);
        }

        //Slog.i("XXXXXXX", "******************************* MINFREE: " + memString);
        if (write) {
            writeFile("/sys/module/lowmemorykiller/parameters/adj", adjString.toString());
            writeFile("/sys/module/lowmemorykiller/parameters/minfree", memString.toString());
        }
        // GB: 2048,3072,4096,6144,7168,8192
        // HC: 8192,10240,12288,14336,16384,20480
    }

    long getMemLevel(int adjustment) {
        for (int i=0; i<mOomAdj.length; i++) {
            if (adjustment <= mOomAdj[i]) {
                return mOomMinFree[i] * 1024;
            }
        }
        return mOomMinFree[mOomAdj.length-1] * 1024;
    }

    private void writeFile(String path, String data) {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(path);
            fos.write(data.getBytes());
        } catch (IOException e) {
            Slog.w(ActivityManagerService.TAG, "Unable to write " + path);
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) {
                }
            }
        }
    }
}
