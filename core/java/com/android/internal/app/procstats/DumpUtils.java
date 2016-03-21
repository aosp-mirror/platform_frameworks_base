/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.internal.app.procstats;

import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.text.format.DateFormat;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DebugUtils;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;

import static com.android.internal.app.procstats.ProcessStats.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Objects;

/**
 * Utilities for dumping.
 */
public final class DumpUtils {
    public static final String[] STATE_NAMES = new String[] {
            "Persist", "Top    ", "ImpFg  ", "ImpBg  ",
            "Backup ", "HeavyWt", "Service", "ServRst",
            "Receivr", "Home   ",
            "LastAct", "CchAct ", "CchCAct", "CchEmty"
    };

    public static final String[] ADJ_SCREEN_NAMES_CSV = new String[] {
            "off", "on"
    };

    public static final String[] ADJ_MEM_NAMES_CSV = new String[] {
            "norm", "mod",  "low", "crit"
    };

    public static final String[] STATE_NAMES_CSV = new String[] {
            "pers", "top", "impfg", "impbg", "backup", "heavy",
            "service", "service-rs", "receiver", "home", "lastact",
            "cch-activity", "cch-aclient", "cch-empty"
    };

    static final String[] ADJ_SCREEN_TAGS = new String[] {
            "0", "1"
    };

    static final String[] ADJ_MEM_TAGS = new String[] {
            "n", "m",  "l", "c"
    };

    static final String[] STATE_TAGS = new String[] {
            "p", "t", "f", "b", "u", "w",
            "s", "x", "r", "h", "l", "a", "c", "e"
    };

    static final String CSV_SEP = "\t";

    /**
     * No instantiate
     */
    private DumpUtils() {
    }

    public static void printScreenLabel(PrintWriter pw, int offset) {
        switch (offset) {
            case ADJ_NOTHING:
                pw.print("     ");
                break;
            case ADJ_SCREEN_OFF:
                pw.print("SOff/");
                break;
            case ADJ_SCREEN_ON:
                pw.print("SOn /");
                break;
            default:
                pw.print("????/");
                break;
        }
    }

    public static void printScreenLabelCsv(PrintWriter pw, int offset) {
        switch (offset) {
            case ADJ_NOTHING:
                break;
            case ADJ_SCREEN_OFF:
                pw.print(ADJ_SCREEN_NAMES_CSV[0]);
                break;
            case ADJ_SCREEN_ON:
                pw.print(ADJ_SCREEN_NAMES_CSV[1]);
                break;
            default:
                pw.print("???");
                break;
        }
    }

    public static void printMemLabel(PrintWriter pw, int offset, char sep) {
        switch (offset) {
            case ADJ_NOTHING:
                pw.print("    ");
                if (sep != 0) pw.print(' ');
                break;
            case ADJ_MEM_FACTOR_NORMAL:
                pw.print("Norm");
                if (sep != 0) pw.print(sep);
                break;
            case ADJ_MEM_FACTOR_MODERATE:
                pw.print("Mod ");
                if (sep != 0) pw.print(sep);
                break;
            case ADJ_MEM_FACTOR_LOW:
                pw.print("Low ");
                if (sep != 0) pw.print(sep);
                break;
            case ADJ_MEM_FACTOR_CRITICAL:
                pw.print("Crit");
                if (sep != 0) pw.print(sep);
                break;
            default:
                pw.print("????");
                if (sep != 0) pw.print(sep);
                break;
        }
    }

    public static void printMemLabelCsv(PrintWriter pw, int offset) {
        if (offset >= ADJ_MEM_FACTOR_NORMAL) {
            if (offset <= ADJ_MEM_FACTOR_CRITICAL) {
                pw.print(ADJ_MEM_NAMES_CSV[offset]);
            } else {
                pw.print("???");
            }
        }
    }

    public static void printPercent(PrintWriter pw, double fraction) {
        fraction *= 100;
        if (fraction < 1) {
            pw.print(String.format("%.2f", fraction));
        } else if (fraction < 10) {
            pw.print(String.format("%.1f", fraction));
        } else {
            pw.print(String.format("%.0f", fraction));
        }
        pw.print("%");
    }

    public static void printProcStateTag(PrintWriter pw, int state) {
        state = printArrayEntry(pw, ADJ_SCREEN_TAGS,  state, ADJ_SCREEN_MOD*STATE_COUNT);
        state = printArrayEntry(pw, ADJ_MEM_TAGS,  state, STATE_COUNT);
        printArrayEntry(pw, STATE_TAGS,  state, 1);
    }

    public static void printAdjTag(PrintWriter pw, int state) {
        state = printArrayEntry(pw, ADJ_SCREEN_TAGS,  state, ADJ_SCREEN_MOD);
        printArrayEntry(pw, ADJ_MEM_TAGS, state, 1);
    }

    public static void printProcStateTagAndValue(PrintWriter pw, int state, long value) {
        pw.print(',');
        printProcStateTag(pw, state);
        pw.print(':');
        pw.print(value);
    }

    public static void printAdjTagAndValue(PrintWriter pw, int state, long value) {
        pw.print(',');
        printAdjTag(pw, state);
        pw.print(':');
        pw.print(value);
    }

    public static long dumpSingleTime(PrintWriter pw, String prefix, long[] durations,
            int curState, long curStartTime, long now) {
        long totalTime = 0;
        int printedScreen = -1;
        for (int iscreen=0; iscreen<ADJ_COUNT; iscreen+=ADJ_SCREEN_MOD) {
            int printedMem = -1;
            for (int imem=0; imem<ADJ_MEM_FACTOR_COUNT; imem++) {
                int state = imem+iscreen;
                long time = durations[state];
                String running = "";
                if (curState == state) {
                    time += now - curStartTime;
                    if (pw != null) {
                        running = " (running)";
                    }
                }
                if (time != 0) {
                    if (pw != null) {
                        pw.print(prefix);
                        printScreenLabel(pw, printedScreen != iscreen
                                ? iscreen : STATE_NOTHING);
                        printedScreen = iscreen;
                        printMemLabel(pw, printedMem != imem ? imem : STATE_NOTHING, (char)0);
                        printedMem = imem;
                        pw.print(": ");
                        TimeUtils.formatDuration(time, pw); pw.println(running);
                    }
                    totalTime += time;
                }
            }
        }
        if (totalTime != 0 && pw != null) {
            pw.print(prefix);
            pw.print("    TOTAL: ");
            TimeUtils.formatDuration(totalTime, pw);
            pw.println();
        }
        return totalTime;
    }

    public static void dumpAdjTimesCheckin(PrintWriter pw, String sep, long[] durations,
            int curState, long curStartTime, long now) {
        for (int iscreen=0; iscreen<ADJ_COUNT; iscreen+=ADJ_SCREEN_MOD) {
            for (int imem=0; imem<ADJ_MEM_FACTOR_COUNT; imem++) {
                int state = imem+iscreen;
                long time = durations[state];
                if (curState == state) {
                    time += now - curStartTime;
                }
                if (time != 0) {
                    printAdjTagAndValue(pw, state, time);
                }
            }
        }
    }

    private static void dumpStateHeadersCsv(PrintWriter pw, String sep, int[] screenStates,
            int[] memStates, int[] procStates) {
        final int NS = screenStates != null ? screenStates.length : 1;
        final int NM = memStates != null ? memStates.length : 1;
        final int NP = procStates != null ? procStates.length : 1;
        for (int is=0; is<NS; is++) {
            for (int im=0; im<NM; im++) {
                for (int ip=0; ip<NP; ip++) {
                    pw.print(sep);
                    boolean printed = false;
                    if (screenStates != null && screenStates.length > 1) {
                        printScreenLabelCsv(pw, screenStates[is]);
                        printed = true;
                    }
                    if (memStates != null && memStates.length > 1) {
                        if (printed) {
                            pw.print("-");
                        }
                        printMemLabelCsv(pw, memStates[im]);
                        printed = true;
                    }
                    if (procStates != null && procStates.length > 1) {
                        if (printed) {
                            pw.print("-");
                        }
                        pw.print(STATE_NAMES_CSV[procStates[ip]]);
                    }
                }
            }
        }
    }

    /*
     * Doesn't seem to be used.
     *
    public static void dumpProcessList(PrintWriter pw, String prefix, ArrayList<ProcessState> procs,
            int[] screenStates, int[] memStates, int[] procStates, long now) {
        String innerPrefix = prefix + "  ";
        for (int i=procs.size()-1; i>=0; i--) {
            ProcessState proc = procs.get(i);
            pw.print(prefix);
            pw.print(proc.mName);
            pw.print(" / ");
            UserHandle.formatUid(pw, proc.mUid);
            pw.print(" (");
            pw.print(proc.durations.getKeyCount());
            pw.print(" entries)");
            pw.println(":");
            proc.dumpProcessState(pw, innerPrefix, screenStates, memStates, procStates, now);
            if (proc.pssTable.getKeyCount() > 0) {
                proc.dumpPss(pw, innerPrefix, screenStates, memStates, procStates);
            }
        }
    }
    */

    public static void dumpProcessSummaryLocked(PrintWriter pw, String prefix,
            ArrayList<ProcessState> procs, int[] screenStates, int[] memStates, int[] procStates,
            long now, long totalTime) {
        for (int i=procs.size()-1; i>=0; i--) {
            final ProcessState proc = procs.get(i);
            proc.dumpSummary(pw, prefix, screenStates, memStates, procStates, now, totalTime);
        }
    }

    public static void dumpProcessListCsv(PrintWriter pw, ArrayList<ProcessState> procs,
            boolean sepScreenStates, int[] screenStates, boolean sepMemStates, int[] memStates,
            boolean sepProcStates, int[] procStates, long now) {
        pw.print("process");
        pw.print(CSV_SEP);
        pw.print("uid");
        pw.print(CSV_SEP);
        pw.print("vers");
        dumpStateHeadersCsv(pw, CSV_SEP, sepScreenStates ? screenStates : null,
                sepMemStates ? memStates : null,
                sepProcStates ? procStates : null);
        pw.println();
        for (int i=procs.size()-1; i>=0; i--) {
            ProcessState proc = procs.get(i);
            pw.print(proc.getName());
            pw.print(CSV_SEP);
            UserHandle.formatUid(pw, proc.getUid());
            pw.print(CSV_SEP);
            pw.print(proc.getVersion());
            proc.dumpCsv(pw, sepScreenStates, screenStates, sepMemStates,
                    memStates, sepProcStates, procStates, now);
            pw.println();
        }
    }

    public static int printArrayEntry(PrintWriter pw, String[] array, int value, int mod) {
        int index = value/mod;
        if (index >= 0 && index < array.length) {
            pw.print(array[index]);
        } else {
            pw.print('?');
        }
        return value - index*mod;
    }

    public static String collapseString(String pkgName, String itemName) {
        if (itemName.startsWith(pkgName)) {
            final int ITEMLEN = itemName.length();
            final int PKGLEN = pkgName.length();
            if (ITEMLEN == PKGLEN) {
                return "";
            } else if (ITEMLEN >= PKGLEN) {
                if (itemName.charAt(PKGLEN) == '.') {
                    return itemName.substring(PKGLEN);
                }
            }
        }
        return itemName;
    }
}
