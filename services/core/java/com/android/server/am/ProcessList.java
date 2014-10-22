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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import android.app.ActivityManager;
import android.os.Build;
import android.os.SystemClock;
import com.android.internal.util.MemInfoReader;
import com.android.server.wm.WindowManagerService;

import android.content.res.Resources;
import android.graphics.Point;
import android.os.SystemProperties;
import android.net.LocalSocketAddress;
import android.net.LocalSocket;
import android.util.Slog;
import android.view.Display;

/**
 * Activity manager code dealing with processes.
 */
final class ProcessList {
    // The minimum time we allow between crashes, for us to consider this
    // application to be bad and stop and its services and reject broadcasts.
    static final int MIN_CRASH_INTERVAL = 60*1000;

    // OOM adjustments for processes in various states:

    // Adjustment used in certain places where we don't know it yet.
    // (Generally this is something that is going to be cached, but we
    // don't know the exact value in the cached range to assign yet.)
    static final int UNKNOWN_ADJ = 16;

    // This is a process only hosting activities that are not visible,
    // so it can be killed without any disruption.
    static final int CACHED_APP_MAX_ADJ = 15;
    static final int CACHED_APP_MIN_ADJ = 9;

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

    // This is a process with a heavy-weight application.  It is in the
    // background, but we want to try to avoid killing it.  Value set in
    // system/rootdir/init.rc on startup.
    static final int HEAVY_WEIGHT_APP_ADJ = 4;

    // This is a process currently hosting a backup operation.  Killing it
    // is not entirely fatal but is generally a bad idea.
    static final int BACKUP_APP_ADJ = 3;

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

    // This is a process that the system or a persistent process has bound to,
    // and indicated it is important.
    static final int PERSISTENT_SERVICE_ADJ = -11;

    // This is a system persistent process, such as telephony.  Definitely
    // don't want to kill it, but doing so is not completely fatal.
    static final int PERSISTENT_PROC_ADJ = -12;

    // The system process runs at the default adjustment.
    static final int SYSTEM_ADJ = -16;

    // Special code for native processes that are not being managed by the system (so
    // don't have an oom adj assigned by the system).
    static final int NATIVE_ADJ = -17;

    // Memory pages are 4K.
    static final int PAGE_SIZE = 4*1024;

    // The minimum number of cached apps we want to be able to keep around,
    // without empty apps being able to push them out of memory.
    static final int MIN_CACHED_APPS = 2;

    // The maximum number of cached processes we will keep around before killing them.
    // NOTE: this constant is *only* a control to not let us go too crazy with
    // keeping around processes on devices with large amounts of RAM.  For devices that
    // are tighter on RAM, the out of memory killer is responsible for killing background
    // processes as RAM is needed, and we should *never* be relying on this limit to
    // kill them.  Also note that this limit only applies to cached background processes;
    // we have no limit on the number of service, visible, foreground, or other such
    // processes and the number of those processes does not count against the cached
    // process limit.
    static final int MAX_CACHED_APPS = 32;

    // We allow empty processes to stick around for at most 30 minutes.
    static final long MAX_EMPTY_TIME = 30*60*1000;

    // The maximum number of empty app processes we will let sit around.
    private static final int MAX_EMPTY_APPS = computeEmptyProcessLimit(MAX_CACHED_APPS);

    // The number of empty apps at which we don't consider it necessary to do
    // memory trimming.
    static final int TRIM_EMPTY_APPS = MAX_EMPTY_APPS/2;

    // The number of cached at which we don't consider it necessary to do
    // memory trimming.
    static final int TRIM_CACHED_APPS = (MAX_CACHED_APPS-MAX_EMPTY_APPS)/3;

    // Threshold of number of cached+empty where we consider memory critical.
    static final int TRIM_CRITICAL_THRESHOLD = 3;

    // Threshold of number of cached+empty where we consider memory critical.
    static final int TRIM_LOW_THRESHOLD = 5;

    // Low Memory Killer Daemon command codes.
    // These must be kept in sync with the definitions in lmkd.c
    //
    // LMK_TARGET <minfree> <minkillprio> ... (up to 6 pairs)
    // LMK_PROCPRIO <pid> <prio>
    // LMK_PROCREMOVE <pid>
    static final byte LMK_TARGET = 0;
    static final byte LMK_PROCPRIO = 1;
    static final byte LMK_PROCREMOVE = 2;

    // These are the various interesting memory levels that we will give to
    // the OOM killer.  Note that the OOM killer only supports 6 slots, so we
    // can't give it a different value for every possible kind of process.
    private final int[] mOomAdj = new int[] {
            FOREGROUND_APP_ADJ, VISIBLE_APP_ADJ, PERCEPTIBLE_APP_ADJ,
            BACKUP_APP_ADJ, CACHED_APP_MIN_ADJ, CACHED_APP_MAX_ADJ
    };
    // These are the low-end OOM level limits.  This is appropriate for an
    // HVGA or smaller phone with less than 512MB.  Values are in KB.
    private final int[] mOomMinFreeLow = new int[] {
            12288, 18432, 24576,
            36864, 43008, 49152
    };
    // These are the high-end OOM level limits.  This is appropriate for a
    // 1280x800 or larger screen with around 1GB RAM.  Values are in KB.
    private final int[] mOomMinFreeHigh = new int[] {
            73728, 92160, 110592,
            129024, 147456, 184320
    };
    // The actual OOM killer memory levels we are using.
    private final int[] mOomMinFree = new int[mOomAdj.length];

    private final long mTotalMemMb;

    private long mCachedRestoreLevel;

    private boolean mHaveDisplaySize;

    private static LocalSocket sLmkdSocket;
    private static OutputStream sLmkdOutputStream;

    ProcessList() {
        MemInfoReader minfo = new MemInfoReader();
        minfo.readMemInfo();
        mTotalMemMb = minfo.getTotalSize()/(1024*1024);
        updateOomLevels(0, 0, false);
    }

    void applyDisplaySize(WindowManagerService wm) {
        if (!mHaveDisplaySize) {
            Point p = new Point();
            wm.getBaseDisplaySize(Display.DEFAULT_DISPLAY, p);
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
        int minSize = 480*800;  //  384000
        int maxSize = 1280*800; // 1024000  230400 870400  .264
        float scaleDisp = ((float)(displayWidth*displayHeight)-minSize)/(maxSize-minSize);
        if (false) {
            Slog.i("XXXXXX", "scaleMem=" + scaleMem);
            Slog.i("XXXXXX", "scaleDisp=" + scaleDisp + " dw=" + displayWidth
                    + " dh=" + displayHeight);
        }

        float scale = scaleMem > scaleDisp ? scaleMem : scaleDisp;
        if (scale < 0) scale = 0;
        else if (scale > 1) scale = 1;
        int minfree_adj = Resources.getSystem().getInteger(
                com.android.internal.R.integer.config_lowMemoryKillerMinFreeKbytesAdjust);
        int minfree_abs = Resources.getSystem().getInteger(
                com.android.internal.R.integer.config_lowMemoryKillerMinFreeKbytesAbsolute);
        if (false) {
            Slog.i("XXXXXX", "minfree_adj=" + minfree_adj + " minfree_abs=" + minfree_abs);
        }

        // We've now baked in the increase to the basic oom values above, since
        // they seem to be useful more generally for devices that are tight on
        // memory than just for 64 bit.  This should probably have some more
        // tuning done, so not deleting it quite yet...
        final boolean is64bit = false; //Build.SUPPORTED_64_BIT_ABIS.length > 0;

        for (int i=0; i<mOomAdj.length; i++) {
            int low = mOomMinFreeLow[i];
            int high = mOomMinFreeHigh[i];
            mOomMinFree[i] = (int)(low + ((high-low)*scale));
            if (is64bit) {
                // On 64 bit devices, we consume more baseline RAM, because 64 bit is cool!
                // To avoid being all pagey and stuff, scale up the memory levels to
                // give us some breathing room.
                mOomMinFree[i] = (3*mOomMinFree[i])/2;
            }
        }

        if (minfree_abs >= 0) {
            for (int i=0; i<mOomAdj.length; i++) {
                mOomMinFree[i] = (int)((float)minfree_abs * mOomMinFree[i]
                        / mOomMinFree[mOomAdj.length - 1]);
            }
        }

        if (minfree_adj != 0) {
            for (int i=0; i<mOomAdj.length; i++) {
                mOomMinFree[i] += (int)((float)minfree_adj * mOomMinFree[i]
                        / mOomMinFree[mOomAdj.length - 1]);
                if (mOomMinFree[i] < 0) {
                    mOomMinFree[i] = 0;
                }
            }
        }

        // The maximum size we will restore a process from cached to background, when under
        // memory duress, is 1/3 the size we have reserved for kernel caches and other overhead
        // before killing background processes.
        mCachedRestoreLevel = (getMemLevel(ProcessList.CACHED_APP_MAX_ADJ)/1024) / 3;

        // Ask the kernel to try to keep enough memory free to allocate 3 full
        // screen 32bpp buffers without entering direct reclaim.
        int reserve = displayWidth * displayHeight * 4 * 3 / 1024;
        int reserve_adj = Resources.getSystem().getInteger(com.android.internal.R.integer.config_extraFreeKbytesAdjust);
        int reserve_abs = Resources.getSystem().getInteger(com.android.internal.R.integer.config_extraFreeKbytesAbsolute);

        if (reserve_abs >= 0) {
            reserve = reserve_abs;
        }

        if (reserve_adj != 0) {
            reserve += reserve_adj;
            if (reserve < 0) {
                reserve = 0;
            }
        }

        if (write) {
            ByteBuffer buf = ByteBuffer.allocate(4 * (2*mOomAdj.length + 1));
            buf.putInt(LMK_TARGET);
            for (int i=0; i<mOomAdj.length; i++) {
                buf.putInt((mOomMinFree[i]*1024)/PAGE_SIZE);
                buf.putInt(mOomAdj[i]);
            }

            writeLmkd(buf);
            SystemProperties.set("sys.sysctl.extra_free_kbytes", Integer.toString(reserve));
        }
        // GB: 2048,3072,4096,6144,7168,8192
        // HC: 8192,10240,12288,14336,16384,20480
    }

    public static int computeEmptyProcessLimit(int totalProcessLimit) {
        return totalProcessLimit/2;
    }

    private static String buildOomTag(String prefix, String space, int val, int base) {
        if (val == base) {
            if (space == null) return prefix;
            return prefix + "  ";
        }
        return prefix + "+" + Integer.toString(val-base);
    }

    public static String makeOomAdjString(int setAdj) {
        if (setAdj >= ProcessList.CACHED_APP_MIN_ADJ) {
            return buildOomTag("cch", "  ", setAdj, ProcessList.CACHED_APP_MIN_ADJ);
        } else if (setAdj >= ProcessList.SERVICE_B_ADJ) {
            return buildOomTag("svcb ", null, setAdj, ProcessList.SERVICE_B_ADJ);
        } else if (setAdj >= ProcessList.PREVIOUS_APP_ADJ) {
            return buildOomTag("prev ", null, setAdj, ProcessList.PREVIOUS_APP_ADJ);
        } else if (setAdj >= ProcessList.HOME_APP_ADJ) {
            return buildOomTag("home ", null, setAdj, ProcessList.HOME_APP_ADJ);
        } else if (setAdj >= ProcessList.SERVICE_ADJ) {
            return buildOomTag("svc  ", null, setAdj, ProcessList.SERVICE_ADJ);
        } else if (setAdj >= ProcessList.HEAVY_WEIGHT_APP_ADJ) {
            return buildOomTag("hvy  ", null, setAdj, ProcessList.HEAVY_WEIGHT_APP_ADJ);
        } else if (setAdj >= ProcessList.BACKUP_APP_ADJ) {
            return buildOomTag("bkup ", null, setAdj, ProcessList.BACKUP_APP_ADJ);
        } else if (setAdj >= ProcessList.PERCEPTIBLE_APP_ADJ) {
            return buildOomTag("prcp ", null, setAdj, ProcessList.PERCEPTIBLE_APP_ADJ);
        } else if (setAdj >= ProcessList.VISIBLE_APP_ADJ) {
            return buildOomTag("vis  ", null, setAdj, ProcessList.VISIBLE_APP_ADJ);
        } else if (setAdj >= ProcessList.FOREGROUND_APP_ADJ) {
            return buildOomTag("fore ", null, setAdj, ProcessList.FOREGROUND_APP_ADJ);
        } else if (setAdj >= ProcessList.PERSISTENT_SERVICE_ADJ) {
            return buildOomTag("psvc ", null, setAdj, ProcessList.PERSISTENT_SERVICE_ADJ);
        } else if (setAdj >= ProcessList.PERSISTENT_PROC_ADJ) {
            return buildOomTag("pers ", null, setAdj, ProcessList.PERSISTENT_PROC_ADJ);
        } else if (setAdj >= ProcessList.SYSTEM_ADJ) {
            return buildOomTag("sys  ", null, setAdj, ProcessList.SYSTEM_ADJ);
        } else if (setAdj >= ProcessList.NATIVE_ADJ) {
            return buildOomTag("ntv  ", null, setAdj, ProcessList.NATIVE_ADJ);
        } else {
            return Integer.toString(setAdj);
        }
    }

    public static String makeProcStateString(int curProcState) {
        String procState;
        switch (curProcState) {
            case -1:
                procState = "N ";
                break;
            case ActivityManager.PROCESS_STATE_PERSISTENT:
                procState = "P ";
                break;
            case ActivityManager.PROCESS_STATE_PERSISTENT_UI:
                procState = "PU";
                break;
            case ActivityManager.PROCESS_STATE_TOP:
                procState = "T ";
                break;
            case ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND:
                procState = "IF";
                break;
            case ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND:
                procState = "IB";
                break;
            case ActivityManager.PROCESS_STATE_BACKUP:
                procState = "BU";
                break;
            case ActivityManager.PROCESS_STATE_HEAVY_WEIGHT:
                procState = "HW";
                break;
            case ActivityManager.PROCESS_STATE_SERVICE:
                procState = "S ";
                break;
            case ActivityManager.PROCESS_STATE_RECEIVER:
                procState = "R ";
                break;
            case ActivityManager.PROCESS_STATE_HOME:
                procState = "HO";
                break;
            case ActivityManager.PROCESS_STATE_LAST_ACTIVITY:
                procState = "LA";
                break;
            case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY:
                procState = "CA";
                break;
            case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT:
                procState = "Ca";
                break;
            case ActivityManager.PROCESS_STATE_CACHED_EMPTY:
                procState = "CE";
                break;
            default:
                procState = "??";
                break;
        }
        return procState;
    }

    public static void appendRamKb(StringBuilder sb, long ramKb) {
        for (int j=0, fact=10; j<6; j++, fact*=10) {
            if (ramKb < fact) {
                sb.append(' ');
            }
        }
        sb.append(ramKb);
    }

    // The minimum amount of time after a state change it is safe ro collect PSS.
    public static final int PSS_MIN_TIME_FROM_STATE_CHANGE = 15*1000;

    // The maximum amount of time we want to go between PSS collections.
    public static final int PSS_MAX_INTERVAL = 30*60*1000;

    // The minimum amount of time between successive PSS requests for *all* processes.
    public static final int PSS_ALL_INTERVAL = 10*60*1000;

    // The minimum amount of time between successive PSS requests for a process.
    private static final int PSS_SHORT_INTERVAL = 2*60*1000;

    // The amount of time until PSS when a process first becomes top.
    private static final int PSS_FIRST_TOP_INTERVAL = 10*1000;

    // The amount of time until PSS when a process first goes into the background.
    private static final int PSS_FIRST_BACKGROUND_INTERVAL = 20*1000;

    // The amount of time until PSS when a process first becomes cached.
    private static final int PSS_FIRST_CACHED_INTERVAL = 30*1000;

    // The amount of time until PSS when an important process stays in the same state.
    private static final int PSS_SAME_IMPORTANT_INTERVAL = 15*60*1000;

    // The amount of time until PSS when a service process stays in the same state.
    private static final int PSS_SAME_SERVICE_INTERVAL = 20*60*1000;

    // The amount of time until PSS when a cached process stays in the same state.
    private static final int PSS_SAME_CACHED_INTERVAL = 30*60*1000;

    public static final int PROC_MEM_PERSISTENT = 0;
    public static final int PROC_MEM_TOP = 1;
    public static final int PROC_MEM_IMPORTANT = 2;
    public static final int PROC_MEM_SERVICE = 3;
    public static final int PROC_MEM_CACHED = 4;

    private static final int[] sProcStateToProcMem = new int[] {
        PROC_MEM_PERSISTENT,            // ActivityManager.PROCESS_STATE_PERSISTENT
        PROC_MEM_PERSISTENT,            // ActivityManager.PROCESS_STATE_PERSISTENT_UI
        PROC_MEM_TOP,                   // ActivityManager.PROCESS_STATE_TOP
        PROC_MEM_IMPORTANT,             // ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND
        PROC_MEM_IMPORTANT,             // ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND
        PROC_MEM_IMPORTANT,             // ActivityManager.PROCESS_STATE_BACKUP
        PROC_MEM_IMPORTANT,             // ActivityManager.PROCESS_STATE_HEAVY_WEIGHT
        PROC_MEM_SERVICE,               // ActivityManager.PROCESS_STATE_SERVICE
        PROC_MEM_CACHED,                // ActivityManager.PROCESS_STATE_RECEIVER
        PROC_MEM_CACHED,                // ActivityManager.PROCESS_STATE_HOME
        PROC_MEM_CACHED,                // ActivityManager.PROCESS_STATE_LAST_ACTIVITY
        PROC_MEM_CACHED,                // ActivityManager.PROCESS_STATE_CACHED_ACTIVITY
        PROC_MEM_CACHED,                // ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT
        PROC_MEM_CACHED,                // ActivityManager.PROCESS_STATE_CACHED_EMPTY
    };

    private static final long[] sFirstAwakePssTimes = new long[] {
        PSS_SHORT_INTERVAL,             // ActivityManager.PROCESS_STATE_PERSISTENT
        PSS_SHORT_INTERVAL,             // ActivityManager.PROCESS_STATE_PERSISTENT_UI
        PSS_FIRST_TOP_INTERVAL,         // ActivityManager.PROCESS_STATE_TOP
        PSS_FIRST_BACKGROUND_INTERVAL,  // ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND
        PSS_FIRST_BACKGROUND_INTERVAL,  // ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND
        PSS_FIRST_BACKGROUND_INTERVAL,  // ActivityManager.PROCESS_STATE_BACKUP
        PSS_FIRST_BACKGROUND_INTERVAL,  // ActivityManager.PROCESS_STATE_HEAVY_WEIGHT
        PSS_FIRST_BACKGROUND_INTERVAL,  // ActivityManager.PROCESS_STATE_SERVICE
        PSS_FIRST_CACHED_INTERVAL,      // ActivityManager.PROCESS_STATE_RECEIVER
        PSS_FIRST_CACHED_INTERVAL,      // ActivityManager.PROCESS_STATE_HOME
        PSS_FIRST_CACHED_INTERVAL,      // ActivityManager.PROCESS_STATE_LAST_ACTIVITY
        PSS_FIRST_CACHED_INTERVAL,      // ActivityManager.PROCESS_STATE_CACHED_ACTIVITY
        PSS_FIRST_CACHED_INTERVAL,      // ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT
        PSS_FIRST_CACHED_INTERVAL,      // ActivityManager.PROCESS_STATE_CACHED_EMPTY
    };

    private static final long[] sSameAwakePssTimes = new long[] {
        PSS_SAME_IMPORTANT_INTERVAL,    // ActivityManager.PROCESS_STATE_PERSISTENT
        PSS_SAME_IMPORTANT_INTERVAL,    // ActivityManager.PROCESS_STATE_PERSISTENT_UI
        PSS_SHORT_INTERVAL,             // ActivityManager.PROCESS_STATE_TOP
        PSS_SAME_IMPORTANT_INTERVAL,    // ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND
        PSS_SAME_IMPORTANT_INTERVAL,    // ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND
        PSS_SAME_IMPORTANT_INTERVAL,    // ActivityManager.PROCESS_STATE_BACKUP
        PSS_SAME_IMPORTANT_INTERVAL,    // ActivityManager.PROCESS_STATE_HEAVY_WEIGHT
        PSS_SAME_SERVICE_INTERVAL,      // ActivityManager.PROCESS_STATE_SERVICE
        PSS_SAME_SERVICE_INTERVAL,      // ActivityManager.PROCESS_STATE_RECEIVER
        PSS_SAME_CACHED_INTERVAL,       // ActivityManager.PROCESS_STATE_HOME
        PSS_SAME_CACHED_INTERVAL,       // ActivityManager.PROCESS_STATE_LAST_ACTIVITY
        PSS_SAME_CACHED_INTERVAL,       // ActivityManager.PROCESS_STATE_CACHED_ACTIVITY
        PSS_SAME_CACHED_INTERVAL,       // ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT
        PSS_SAME_CACHED_INTERVAL,       // ActivityManager.PROCESS_STATE_CACHED_EMPTY
    };

    public static boolean procStatesDifferForMem(int procState1, int procState2) {
        return sProcStateToProcMem[procState1] != sProcStateToProcMem[procState2];
    }

    public static long computeNextPssTime(int procState, boolean first, boolean sleeping,
            long now) {
        final long[] table = sleeping
                ? (first
                        ? sFirstAwakePssTimes
                        : sSameAwakePssTimes)
                : (first
                        ? sFirstAwakePssTimes
                        : sSameAwakePssTimes);
        return now + table[procState];
    }

    long getMemLevel(int adjustment) {
        for (int i=0; i<mOomAdj.length; i++) {
            if (adjustment <= mOomAdj[i]) {
                return mOomMinFree[i] * 1024;
            }
        }
        return mOomMinFree[mOomAdj.length-1] * 1024;
    }

    /**
     * Return the maximum pss size in kb that we consider a process acceptable to
     * restore from its cached state for running in the background when RAM is low.
     */
    long getCachedRestoreThresholdKb() {
        return mCachedRestoreLevel;
    }

    /**
     * Set the out-of-memory badness adjustment for a process.
     *
     * @param pid The process identifier to set.
     * @param uid The uid of the app
     * @param amt Adjustment value -- lmkd allows -16 to +15.
     *
     * {@hide}
     */
    public static final void setOomAdj(int pid, int uid, int amt) {
        if (amt == UNKNOWN_ADJ)
            return;

        long start = SystemClock.elapsedRealtime();
        ByteBuffer buf = ByteBuffer.allocate(4 * 4);
        buf.putInt(LMK_PROCPRIO);
        buf.putInt(pid);
        buf.putInt(uid);
        buf.putInt(amt);
        writeLmkd(buf);
        long now = SystemClock.elapsedRealtime();
        if ((now-start) > 250) {
            Slog.w("ActivityManager", "SLOW OOM ADJ: " + (now-start) + "ms for pid " + pid
                    + " = " + amt);
        }
    }

    /*
     * {@hide}
     */
    public static final void remove(int pid) {
        ByteBuffer buf = ByteBuffer.allocate(4 * 2);
        buf.putInt(LMK_PROCREMOVE);
        buf.putInt(pid);
        writeLmkd(buf);
    }

    private static boolean openLmkdSocket() {
        try {
            sLmkdSocket = new LocalSocket(LocalSocket.SOCKET_SEQPACKET);
            sLmkdSocket.connect(
                new LocalSocketAddress("lmkd",
                        LocalSocketAddress.Namespace.RESERVED));
            sLmkdOutputStream = sLmkdSocket.getOutputStream();
        } catch (IOException ex) {
            Slog.w(ActivityManagerService.TAG,
                   "lowmemorykiller daemon socket open failed");
            sLmkdSocket = null;
            return false;
        }

        return true;
    }

    private static void writeLmkd(ByteBuffer buf) {

        for (int i = 0; i < 3; i++) {
            if (sLmkdSocket == null) {
                    if (openLmkdSocket() == false) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ie) {
                        }
                        continue;
                    }
            }

            try {
                sLmkdOutputStream.write(buf.array(), 0, buf.position());
                return;
            } catch (IOException ex) {
                Slog.w(ActivityManagerService.TAG,
                       "Error writing to lowmemorykiller socket");

                try {
                    sLmkdSocket.close();
                } catch (IOException ex2) {
                }

                sLmkdSocket = null;
            }
        }
    }
}
