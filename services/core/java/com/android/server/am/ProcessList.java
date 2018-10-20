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

import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;

import android.app.ActivityManager;
import android.app.AppProtoEnums;
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
public final class ProcessList {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ProcessList" : TAG_AM;

    // The minimum time we allow between crashes, for us to consider this
    // application to be bad and stop and its services and reject broadcasts.
    static final int MIN_CRASH_INTERVAL = 60*1000;

    // OOM adjustments for processes in various states:

    // Uninitialized value for any major or minor adj fields
    static final int INVALID_ADJ = -10000;

    // Adjustment used in certain places where we don't know it yet.
    // (Generally this is something that is going to be cached, but we
    // don't know the exact value in the cached range to assign yet.)
    static final int UNKNOWN_ADJ = 1001;

    // This is a process only hosting activities that are not visible,
    // so it can be killed without any disruption.
    static final int CACHED_APP_MAX_ADJ = 906;
    static final int CACHED_APP_MIN_ADJ = 900;

    // The B list of SERVICE_ADJ -- these are the old and decrepit
    // services that aren't as shiny and interesting as the ones in the A list.
    static final int SERVICE_B_ADJ = 800;

    // This is the process of the previous application that the user was in.
    // This process is kept above other things, because it is very common to
    // switch back to the previous app.  This is important both for recent
    // task switch (toggling between the two top recent apps) as well as normal
    // UI flow such as clicking on a URI in the e-mail app to view in the browser,
    // and then pressing back to return to e-mail.
    static final int PREVIOUS_APP_ADJ = 700;

    // This is a process holding the home application -- we want to try
    // avoiding killing it, even if it would normally be in the background,
    // because the user interacts with it so much.
    static final int HOME_APP_ADJ = 600;

    // This is a process holding an application service -- killing it will not
    // have much of an impact as far as the user is concerned.
    static final int SERVICE_ADJ = 500;

    // This is a process with a heavy-weight application.  It is in the
    // background, but we want to try to avoid killing it.  Value set in
    // system/rootdir/init.rc on startup.
    static final int HEAVY_WEIGHT_APP_ADJ = 400;

    // This is a process currently hosting a backup operation.  Killing it
    // is not entirely fatal but is generally a bad idea.
    static final int BACKUP_APP_ADJ = 300;

    // This is a process only hosting components that are perceptible to the
    // user, and we really want to avoid killing them, but they are not
    // immediately visible. An example is background music playback.
    static final int PERCEPTIBLE_APP_ADJ = 200;

    // This is a process only hosting activities that are visible to the
    // user, so we'd prefer they don't disappear.
    static final int VISIBLE_APP_ADJ = 100;
    static final int VISIBLE_APP_LAYER_MAX = PERCEPTIBLE_APP_ADJ - VISIBLE_APP_ADJ - 1;

    // This is a process that was recently TOP and moved to FGS. Continue to treat it almost
    // like a foreground app for a while.
    // @see TOP_TO_FGS_GRACE_PERIOD
    static final int PERCEPTIBLE_RECENT_FOREGROUND_APP_ADJ = 50;

    // This is the process running the current foreground app.  We'd really
    // rather not kill it!
    static final int FOREGROUND_APP_ADJ = 0;

    // This is a process that the system or a persistent process has bound to,
    // and indicated it is important.
    static final int PERSISTENT_SERVICE_ADJ = -700;

    // This is a system persistent process, such as telephony.  Definitely
    // don't want to kill it, but doing so is not completely fatal.
    static final int PERSISTENT_PROC_ADJ = -800;

    // The system process runs at the default adjustment.
    static final int SYSTEM_ADJ = -900;

    // Special code for native processes that are not being managed by the system (so
    // don't have an oom adj assigned by the system).
    static final int NATIVE_ADJ = -1000;

    // Memory pages are 4K.
    static final int PAGE_SIZE = 4*1024;

    // Activity manager's version of Process.THREAD_GROUP_BG_NONINTERACTIVE
    static final int SCHED_GROUP_BACKGROUND = 0;
      // Activity manager's version of Process.THREAD_GROUP_RESTRICTED
    static final int SCHED_GROUP_RESTRICTED = 1;
    // Activity manager's version of Process.THREAD_GROUP_DEFAULT
    static final int SCHED_GROUP_DEFAULT = 2;
    // Activity manager's version of Process.THREAD_GROUP_TOP_APP
    static final int SCHED_GROUP_TOP_APP = 3;
    // Activity manager's version of Process.THREAD_GROUP_TOP_APP
    // Disambiguate between actual top app and processes bound to the top app
    static final int SCHED_GROUP_TOP_APP_BOUND = 4;

    // The minimum number of cached apps we want to be able to keep around,
    // without empty apps being able to push them out of memory.
    static final int MIN_CACHED_APPS = 2;

    // We allow empty processes to stick around for at most 30 minutes.
    static final long MAX_EMPTY_TIME = 30*60*1000;

    // Threshold of number of cached+empty where we consider memory critical.
    static final int TRIM_CRITICAL_THRESHOLD = 3;

    // Threshold of number of cached+empty where we consider memory critical.
    static final int TRIM_LOW_THRESHOLD = 5;

    // Low Memory Killer Daemon command codes.
    // These must be kept in sync with the definitions in lmkd.c
    //
    // LMK_TARGET <minfree> <minkillprio> ... (up to 6 pairs)
    // LMK_PROCPRIO <pid> <uid> <prio>
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
            // TODO(multi-display): Compute based on sum of all connected displays' resolutions.
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
        float scaleMem = ((float)(mTotalMemMb-350))/(700-350);

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

        final boolean is64bit = Build.SUPPORTED_64_BIT_ABIS.length > 0;

        for (int i=0; i<mOomAdj.length; i++) {
            int low = mOomMinFreeLow[i];
            int high = mOomMinFreeHigh[i];
            if (is64bit) {
                // Increase the high min-free levels for cached processes for 64-bit
                if (i == 4) high = (high*3)/2;
                else if (i == 5) high = (high*7)/4;
            }
            mOomMinFree[i] = (int)(low + ((high-low)*scale));
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
            case ActivityManager.PROCESS_STATE_PERSISTENT:
                procState = "PER ";
                break;
            case ActivityManager.PROCESS_STATE_PERSISTENT_UI:
                procState = "PERU";
                break;
            case ActivityManager.PROCESS_STATE_TOP:
                procState = "TOP ";
                break;
            case ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE:
                procState = "FGS ";
                break;
            case ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE:
                procState = "BFGS";
                break;
            case ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND:
                procState = "IMPF";
                break;
            case ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND:
                procState = "IMPB";
                break;
            case ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND:
                procState = "TRNB";
                break;
            case ActivityManager.PROCESS_STATE_BACKUP:
                procState = "BKUP";
                break;
            case ActivityManager.PROCESS_STATE_SERVICE:
                procState = "SVC ";
                break;
            case ActivityManager.PROCESS_STATE_RECEIVER:
                procState = "RCVR";
                break;
            case ActivityManager.PROCESS_STATE_TOP_SLEEPING:
                procState = "TPSL";
                break;
            case ActivityManager.PROCESS_STATE_HEAVY_WEIGHT:
                procState = "HVY ";
                break;
            case ActivityManager.PROCESS_STATE_HOME:
                procState = "HOME";
                break;
            case ActivityManager.PROCESS_STATE_LAST_ACTIVITY:
                procState = "LAST";
                break;
            case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY:
                procState = "CAC ";
                break;
            case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT:
                procState = "CACC";
                break;
            case ActivityManager.PROCESS_STATE_CACHED_RECENT:
                procState = "CRE ";
                break;
            case ActivityManager.PROCESS_STATE_CACHED_EMPTY:
                procState = "CEM ";
                break;
            case ActivityManager.PROCESS_STATE_NONEXISTENT:
                procState = "NONE";
                break;
            default:
                procState = "??";
                break;
        }
        return procState;
    }

    public static int makeProcStateProtoEnum(int curProcState) {
        switch (curProcState) {
            case ActivityManager.PROCESS_STATE_PERSISTENT:
                return AppProtoEnums.PROCESS_STATE_PERSISTENT;
            case ActivityManager.PROCESS_STATE_PERSISTENT_UI:
                return AppProtoEnums.PROCESS_STATE_PERSISTENT_UI;
            case ActivityManager.PROCESS_STATE_TOP:
                return AppProtoEnums.PROCESS_STATE_TOP;
            case ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE:
                return AppProtoEnums.PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
            case ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE:
                return AppProtoEnums.PROCESS_STATE_FOREGROUND_SERVICE;
            case ActivityManager.PROCESS_STATE_TOP_SLEEPING:
                return AppProtoEnums.PROCESS_STATE_TOP_SLEEPING;
            case ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND:
                return AppProtoEnums.PROCESS_STATE_IMPORTANT_FOREGROUND;
            case ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND:
                return AppProtoEnums.PROCESS_STATE_IMPORTANT_BACKGROUND;
            case ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND:
                return AppProtoEnums.PROCESS_STATE_TRANSIENT_BACKGROUND;
            case ActivityManager.PROCESS_STATE_BACKUP:
                return AppProtoEnums.PROCESS_STATE_BACKUP;
            case ActivityManager.PROCESS_STATE_HEAVY_WEIGHT:
                return AppProtoEnums.PROCESS_STATE_HEAVY_WEIGHT;
            case ActivityManager.PROCESS_STATE_SERVICE:
                return AppProtoEnums.PROCESS_STATE_SERVICE;
            case ActivityManager.PROCESS_STATE_RECEIVER:
                return AppProtoEnums.PROCESS_STATE_RECEIVER;
            case ActivityManager.PROCESS_STATE_HOME:
                return AppProtoEnums.PROCESS_STATE_HOME;
            case ActivityManager.PROCESS_STATE_LAST_ACTIVITY:
                return AppProtoEnums.PROCESS_STATE_LAST_ACTIVITY;
            case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY:
                return AppProtoEnums.PROCESS_STATE_CACHED_ACTIVITY;
            case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT:
                return AppProtoEnums.PROCESS_STATE_CACHED_ACTIVITY_CLIENT;
            case ActivityManager.PROCESS_STATE_CACHED_RECENT:
                return AppProtoEnums.PROCESS_STATE_CACHED_RECENT;
            case ActivityManager.PROCESS_STATE_CACHED_EMPTY:
                return AppProtoEnums.PROCESS_STATE_CACHED_EMPTY;
            case ActivityManager.PROCESS_STATE_NONEXISTENT:
                return AppProtoEnums.PROCESS_STATE_NONEXISTENT;
            case ActivityManager.PROCESS_STATE_UNKNOWN:
                return AppProtoEnums.PROCESS_STATE_UNKNOWN;
            default:
                return AppProtoEnums.PROCESS_STATE_UNKNOWN_TO_PROTO;
        }
    }

    public static void appendRamKb(StringBuilder sb, long ramKb) {
        for (int j=0, fact=10; j<6; j++, fact*=10) {
            if (ramKb < fact) {
                sb.append(' ');
            }
        }
        sb.append(ramKb);
    }

    // How long after a state change that it is safe to collect PSS without it being dirty.
    public static final int PSS_SAFE_TIME_FROM_STATE_CHANGE = 1000;

    // The minimum time interval after a state change it is safe to collect PSS.
    public static final int PSS_MIN_TIME_FROM_STATE_CHANGE = 15*1000;

    // The maximum amount of time we want to go between PSS collections.
    public static final int PSS_MAX_INTERVAL = 60*60*1000;

    // The minimum amount of time between successive PSS requests for *all* processes.
    public static final int PSS_ALL_INTERVAL = 20*60*1000;

    // The amount of time until PSS when a persistent process first appears.
    private static final int PSS_FIRST_PERSISTENT_INTERVAL = 30*1000;

    // The amount of time until PSS when a process first becomes top.
    private static final int PSS_FIRST_TOP_INTERVAL = 10*1000;

    // The amount of time until PSS when a process first goes into the background.
    private static final int PSS_FIRST_BACKGROUND_INTERVAL = 20*1000;

    // The amount of time until PSS when a process first becomes cached.
    private static final int PSS_FIRST_CACHED_INTERVAL = 20*1000;

    // The amount of time until PSS when an important process stays in the same state.
    private static final int PSS_SAME_PERSISTENT_INTERVAL = 10*60*1000;

    // The amount of time until PSS when the top process stays in the same state.
    private static final int PSS_SAME_TOP_INTERVAL = 1*60*1000;

    // The amount of time until PSS when an important process stays in the same state.
    private static final int PSS_SAME_IMPORTANT_INTERVAL = 10*60*1000;

    // The amount of time until PSS when a service process stays in the same state.
    private static final int PSS_SAME_SERVICE_INTERVAL = 5*60*1000;

    // The amount of time until PSS when a cached process stays in the same state.
    private static final int PSS_SAME_CACHED_INTERVAL = 10*60*1000;

    // The amount of time until PSS when a persistent process first appears.
    private static final int PSS_FIRST_ASLEEP_PERSISTENT_INTERVAL = 1*60*1000;

    // The amount of time until PSS when a process first becomes top.
    private static final int PSS_FIRST_ASLEEP_TOP_INTERVAL = 20*1000;

    // The amount of time until PSS when a process first goes into the background.
    private static final int PSS_FIRST_ASLEEP_BACKGROUND_INTERVAL = 30*1000;

    // The amount of time until PSS when a process first becomes cached.
    private static final int PSS_FIRST_ASLEEP_CACHED_INTERVAL = 1*60*1000;

    // The minimum time interval after a state change it is safe to collect PSS.
    public static final int PSS_TEST_MIN_TIME_FROM_STATE_CHANGE = 10*1000;

    // The amount of time during testing until PSS when a process first becomes top.
    private static final int PSS_TEST_FIRST_TOP_INTERVAL = 3*1000;

    // The amount of time during testing until PSS when a process first goes into the background.
    private static final int PSS_TEST_FIRST_BACKGROUND_INTERVAL = 5*1000;

    // The amount of time during testing until PSS when an important process stays in same state.
    private static final int PSS_TEST_SAME_IMPORTANT_INTERVAL = 10*1000;

    // The amount of time during testing until PSS when a background process stays in same state.
    private static final int PSS_TEST_SAME_BACKGROUND_INTERVAL = 15*1000;

    public static final int PROC_MEM_PERSISTENT = 0;
    public static final int PROC_MEM_TOP = 1;
    public static final int PROC_MEM_IMPORTANT = 2;
    public static final int PROC_MEM_SERVICE = 3;
    public static final int PROC_MEM_CACHED = 4;
    public static final int PROC_MEM_NUM = 5;

    // Map large set of system process states to
    private static final int[] sProcStateToProcMem = new int[] {
        PROC_MEM_PERSISTENT,            // ActivityManager.PROCESS_STATE_PERSISTENT
        PROC_MEM_PERSISTENT,            // ActivityManager.PROCESS_STATE_PERSISTENT_UI
        PROC_MEM_TOP,                   // ActivityManager.PROCESS_STATE_TOP
        PROC_MEM_IMPORTANT,             // ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE
        PROC_MEM_IMPORTANT,             // ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE
        PROC_MEM_IMPORTANT,             // ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND
        PROC_MEM_IMPORTANT,             // ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND
        PROC_MEM_IMPORTANT,             // ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND
        PROC_MEM_IMPORTANT,             // ActivityManager.PROCESS_STATE_BACKUP
        PROC_MEM_SERVICE,               // ActivityManager.PROCESS_STATE_SERVICE
        PROC_MEM_CACHED,                // ActivityManager.PROCESS_STATE_RECEIVER
        PROC_MEM_TOP,                   // ActivityManager.PROCESS_STATE_TOP_SLEEPING
        PROC_MEM_IMPORTANT,             // ActivityManager.PROCESS_STATE_HEAVY_WEIGHT
        PROC_MEM_CACHED,                // ActivityManager.PROCESS_STATE_HOME
        PROC_MEM_CACHED,                // ActivityManager.PROCESS_STATE_LAST_ACTIVITY
        PROC_MEM_CACHED,                // ActivityManager.PROCESS_STATE_CACHED_ACTIVITY
        PROC_MEM_CACHED,                // ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT
        PROC_MEM_CACHED,                // ActivityManager.PROCESS_STATE_CACHED_RECENT
        PROC_MEM_CACHED,                // ActivityManager.PROCESS_STATE_CACHED_EMPTY
    };

    private static final long[] sFirstAwakePssTimes = new long[] {
        PSS_FIRST_PERSISTENT_INTERVAL,  // PROC_MEM_PERSISTENT
        PSS_FIRST_TOP_INTERVAL,         // PROC_MEM_TOP
        PSS_FIRST_BACKGROUND_INTERVAL,  // PROC_MEM_IMPORTANT
        PSS_FIRST_BACKGROUND_INTERVAL,  // PROC_MEM_SERVICE
        PSS_FIRST_CACHED_INTERVAL,      // PROC_MEM_CACHED
    };

    private static final long[] sSameAwakePssTimes = new long[] {
        PSS_SAME_PERSISTENT_INTERVAL,   // PROC_MEM_PERSISTENT
        PSS_SAME_TOP_INTERVAL,          // PROC_MEM_TOP
        PSS_SAME_IMPORTANT_INTERVAL,    // PROC_MEM_IMPORTANT
        PSS_SAME_SERVICE_INTERVAL,      // PROC_MEM_SERVICE
        PSS_SAME_CACHED_INTERVAL,       // PROC_MEM_CACHED
    };

    private static final long[] sFirstAsleepPssTimes = new long[] {
        PSS_FIRST_ASLEEP_PERSISTENT_INTERVAL,   // PROC_MEM_PERSISTENT
        PSS_FIRST_ASLEEP_TOP_INTERVAL,          // PROC_MEM_TOP
        PSS_FIRST_ASLEEP_BACKGROUND_INTERVAL,   // PROC_MEM_IMPORTANT
        PSS_FIRST_ASLEEP_BACKGROUND_INTERVAL,   // PROC_MEM_SERVICE
        PSS_FIRST_ASLEEP_CACHED_INTERVAL,       // PROC_MEM_CACHED
    };

    private static final long[] sSameAsleepPssTimes = new long[] {
        PSS_SAME_PERSISTENT_INTERVAL,   // PROC_MEM_PERSISTENT
        PSS_SAME_TOP_INTERVAL,          // PROC_MEM_TOP
        PSS_SAME_IMPORTANT_INTERVAL,    // PROC_MEM_IMPORTANT
        PSS_SAME_SERVICE_INTERVAL,      // PROC_MEM_SERVICE
        PSS_SAME_CACHED_INTERVAL,       // PROC_MEM_CACHED
    };

    private static final long[] sTestFirstPssTimes = new long[] {
        PSS_TEST_FIRST_TOP_INTERVAL,        // PROC_MEM_PERSISTENT
        PSS_TEST_FIRST_TOP_INTERVAL,        // PROC_MEM_TOP
        PSS_TEST_FIRST_BACKGROUND_INTERVAL, // PROC_MEM_IMPORTANT
        PSS_TEST_FIRST_BACKGROUND_INTERVAL, // PROC_MEM_SERVICE
        PSS_TEST_FIRST_BACKGROUND_INTERVAL, // PROC_MEM_CACHED
    };

    private static final long[] sTestSamePssTimes = new long[] {
        PSS_TEST_SAME_BACKGROUND_INTERVAL,  // PROC_MEM_PERSISTENT
        PSS_TEST_SAME_IMPORTANT_INTERVAL,   // PROC_MEM_TOP
        PSS_TEST_SAME_IMPORTANT_INTERVAL,   // PROC_MEM_IMPORTANT
        PSS_TEST_SAME_BACKGROUND_INTERVAL,  // PROC_MEM_SERVICE
        PSS_TEST_SAME_BACKGROUND_INTERVAL,  // PROC_MEM_CACHED
    };

    public static final class ProcStateMemTracker {
        final int[] mHighestMem = new int[PROC_MEM_NUM];
        final float[] mScalingFactor = new float[PROC_MEM_NUM];
        int mTotalHighestMem = PROC_MEM_CACHED;

        int mPendingMemState;
        int mPendingHighestMemState;
        float mPendingScalingFactor;

        public ProcStateMemTracker() {
            for (int i = PROC_MEM_PERSISTENT; i < PROC_MEM_NUM; i++) {
                mHighestMem[i] = PROC_MEM_NUM;
                mScalingFactor[i] = 1.0f;
            }
            mPendingMemState = -1;
        }

        public void dumpLine(PrintWriter pw) {
            pw.print("best=");
            pw.print(mTotalHighestMem);
            pw.print(" (");
            boolean needSep = false;
            for (int i = 0; i < PROC_MEM_NUM; i++) {
                if (mHighestMem[i] < PROC_MEM_NUM) {
                    if (needSep) {
                        pw.print(", ");
                        needSep = false;
                    }
                    pw.print(i);
                    pw.print("=");
                    pw.print(mHighestMem[i]);
                    pw.print(" ");
                    pw.print(mScalingFactor[i]);
                    pw.print("x");
                    needSep = true;
                }
            }
            pw.print(")");
            if (mPendingMemState >= 0) {
                pw.print(" / pending state=");
                pw.print(mPendingMemState);
                pw.print(" highest=");
                pw.print(mPendingHighestMemState);
                pw.print(" ");
                pw.print(mPendingScalingFactor);
                pw.print("x");
            }
            pw.println();
        }
    }

    public static boolean procStatesDifferForMem(int procState1, int procState2) {
        return sProcStateToProcMem[procState1] != sProcStateToProcMem[procState2];
    }

    public static long minTimeFromStateChange(boolean test) {
        return test ? PSS_TEST_MIN_TIME_FROM_STATE_CHANGE : PSS_MIN_TIME_FROM_STATE_CHANGE;
    }

    public static void commitNextPssTime(ProcStateMemTracker tracker) {
        if (tracker.mPendingMemState >= 0) {
            tracker.mHighestMem[tracker.mPendingMemState] = tracker.mPendingHighestMemState;
            tracker.mScalingFactor[tracker.mPendingMemState] = tracker.mPendingScalingFactor;
            tracker.mTotalHighestMem = tracker.mPendingHighestMemState;
            tracker.mPendingMemState = -1;
        }
    }

    public static void abortNextPssTime(ProcStateMemTracker tracker) {
        tracker.mPendingMemState = -1;
    }

    public static long computeNextPssTime(int procState, ProcStateMemTracker tracker, boolean test,
            boolean sleeping, long now) {
        boolean first;
        float scalingFactor;
        final int memState = sProcStateToProcMem[procState];
        if (tracker != null) {
            final int highestMemState = memState < tracker.mTotalHighestMem
                    ? memState : tracker.mTotalHighestMem;
            first = highestMemState < tracker.mHighestMem[memState];
            tracker.mPendingMemState = memState;
            tracker.mPendingHighestMemState = highestMemState;
            if (first) {
                tracker.mPendingScalingFactor = scalingFactor = 1.0f;
            } else {
                scalingFactor = tracker.mScalingFactor[memState];
                tracker.mPendingScalingFactor = scalingFactor * 1.5f;
            }
        } else {
            first = true;
            scalingFactor = 1.0f;
        }
        final long[] table = test
                ? (first
                ? sTestFirstPssTimes
                : sTestSamePssTimes)
                : (first
                ? (sleeping ? sFirstAsleepPssTimes : sFirstAwakePssTimes)
                : (sleeping ? sSameAsleepPssTimes : sSameAwakePssTimes));
        long delay = (long)(table[memState] * scalingFactor);
        if (delay > PSS_MAX_INTERVAL) {
            delay = PSS_MAX_INTERVAL;
        }
        return now + delay;
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
     * If {@code pid <= 0}, this method will be a no-op.
     *
     * @param pid The process identifier to set.
     * @param uid The uid of the app
     * @param amt Adjustment value -- lmkd allows -16 to +15.
     *
     * {@hide}
     */
    public static final void setOomAdj(int pid, int uid, int amt) {
        // This indicates that the process is not started yet and so no need to proceed further.
        if (pid <= 0) {
            return;
        }
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
        // This indicates that the process is not started yet and so no need to proceed further.
        if (pid <= 0) {
            return;
        }
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
            Slog.w(TAG, "lowmemorykiller daemon socket open failed");
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
                Slog.w(TAG, "Error writing to lowmemorykiller socket");

                try {
                    sLmkdSocket.close();
                } catch (IOException ex2) {
                }

                sLmkdSocket = null;
            }
        }
    }
}
