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

import static com.android.internal.app.procstats.ProcessStats.ADJ_COUNT;
import static com.android.internal.app.procstats.ProcessStats.ADJ_MEM_FACTOR_COUNT;
import static com.android.internal.app.procstats.ProcessStats.ADJ_MEM_FACTOR_CRITICAL;
import static com.android.internal.app.procstats.ProcessStats.ADJ_MEM_FACTOR_LOW;
import static com.android.internal.app.procstats.ProcessStats.ADJ_MEM_FACTOR_MODERATE;
import static com.android.internal.app.procstats.ProcessStats.ADJ_MEM_FACTOR_NORMAL;
import static com.android.internal.app.procstats.ProcessStats.ADJ_NOTHING;
import static com.android.internal.app.procstats.ProcessStats.ADJ_SCREEN_MOD;
import static com.android.internal.app.procstats.ProcessStats.ADJ_SCREEN_OFF;
import static com.android.internal.app.procstats.ProcessStats.ADJ_SCREEN_ON;
import static com.android.internal.app.procstats.ProcessStats.STATE_BACKUP;
import static com.android.internal.app.procstats.ProcessStats.STATE_CACHED_ACTIVITY;
import static com.android.internal.app.procstats.ProcessStats.STATE_CACHED_ACTIVITY_CLIENT;
import static com.android.internal.app.procstats.ProcessStats.STATE_CACHED_EMPTY;
import static com.android.internal.app.procstats.ProcessStats.STATE_COUNT;
import static com.android.internal.app.procstats.ProcessStats.STATE_HEAVY_WEIGHT;
import static com.android.internal.app.procstats.ProcessStats.STATE_HOME;
import static com.android.internal.app.procstats.ProcessStats.STATE_IMPORTANT_BACKGROUND;
import static com.android.internal.app.procstats.ProcessStats.STATE_IMPORTANT_FOREGROUND;
import static com.android.internal.app.procstats.ProcessStats.STATE_LAST_ACTIVITY;
import static com.android.internal.app.procstats.ProcessStats.STATE_NOTHING;
import static com.android.internal.app.procstats.ProcessStats.STATE_PERSISTENT;
import static com.android.internal.app.procstats.ProcessStats.STATE_RECEIVER;
import static com.android.internal.app.procstats.ProcessStats.STATE_SERVICE;
import static com.android.internal.app.procstats.ProcessStats.STATE_SERVICE_RESTARTING;
import static com.android.internal.app.procstats.ProcessStats.STATE_TOP;

import android.os.UserHandle;
import android.service.procstats.ProcessStatsEnums;
import android.service.procstats.ProcessStatsStateProto;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Utilities for dumping.
 */
public final class DumpUtils {
    public static final String[] STATE_NAMES;
    public static final String[] STATE_LABELS;
    public static final String STATE_LABEL_TOTAL;
    public static final String STATE_LABEL_CACHED;
    public static final String[] STATE_NAMES_CSV;
    static final String[] STATE_TAGS;
    static final int[] STATE_PROTO_ENUMS;
    private static final int[] PROCESS_STATS_STATE_TO_AGGREGATED_STATE;

    // Make the mapping easy to update.
    static {
        STATE_NAMES = new String[STATE_COUNT];
        STATE_NAMES[STATE_PERSISTENT]               = "Persist";
        STATE_NAMES[STATE_TOP]                      = "Top";
        STATE_NAMES[STATE_IMPORTANT_FOREGROUND]     = "ImpFg";
        STATE_NAMES[STATE_IMPORTANT_BACKGROUND]     = "ImpBg";
        STATE_NAMES[STATE_BACKUP]                   = "Backup";
        STATE_NAMES[STATE_SERVICE]                  = "Service";
        STATE_NAMES[STATE_SERVICE_RESTARTING]       = "ServRst";
        STATE_NAMES[STATE_RECEIVER]                 = "Receivr";
        STATE_NAMES[STATE_HEAVY_WEIGHT]             = "HeavyWt";
        STATE_NAMES[STATE_HOME]                     = "Home";
        STATE_NAMES[STATE_LAST_ACTIVITY]            = "LastAct";
        STATE_NAMES[STATE_CACHED_ACTIVITY]          = "CchAct";
        STATE_NAMES[STATE_CACHED_ACTIVITY_CLIENT]   = "CchCAct";
        STATE_NAMES[STATE_CACHED_EMPTY]             = "CchEmty";

        STATE_LABELS = new String[STATE_COUNT];
        STATE_LABELS[STATE_PERSISTENT]              = "Persistent";
        STATE_LABELS[STATE_TOP]                     = "       Top";
        STATE_LABELS[STATE_IMPORTANT_FOREGROUND]    = "    Imp Fg";
        STATE_LABELS[STATE_IMPORTANT_BACKGROUND]    = "    Imp Bg";
        STATE_LABELS[STATE_BACKUP]                  = "    Backup";
        STATE_LABELS[STATE_SERVICE]                 = "   Service";
        STATE_LABELS[STATE_SERVICE_RESTARTING]      = "Service Rs";
        STATE_LABELS[STATE_RECEIVER]                = "  Receiver";
        STATE_LABELS[STATE_HEAVY_WEIGHT]            = " Heavy Wgt";
        STATE_LABELS[STATE_HOME]                    = "    (Home)";
        STATE_LABELS[STATE_LAST_ACTIVITY]           = "(Last Act)";
        STATE_LABELS[STATE_CACHED_ACTIVITY]         = " (Cch Act)";
        STATE_LABELS[STATE_CACHED_ACTIVITY_CLIENT]  = "(Cch CAct)";
        STATE_LABELS[STATE_CACHED_EMPTY]            = "(Cch Emty)";
        STATE_LABEL_CACHED                          = "  (Cached)";
        STATE_LABEL_TOTAL                           = "     TOTAL";

        STATE_NAMES_CSV = new String[STATE_COUNT];
        STATE_NAMES_CSV[STATE_PERSISTENT]               = "pers";
        STATE_NAMES_CSV[STATE_TOP]                      = "top";
        STATE_NAMES_CSV[STATE_IMPORTANT_FOREGROUND]     = "impfg";
        STATE_NAMES_CSV[STATE_IMPORTANT_BACKGROUND]     = "impbg";
        STATE_NAMES_CSV[STATE_BACKUP]                   = "backup";
        STATE_NAMES_CSV[STATE_SERVICE]                  = "service";
        STATE_NAMES_CSV[STATE_SERVICE_RESTARTING]       = "service-rs";
        STATE_NAMES_CSV[STATE_RECEIVER]                 = "receiver";
        STATE_NAMES_CSV[STATE_HEAVY_WEIGHT]             = "heavy";
        STATE_NAMES_CSV[STATE_HOME]                     = "home";
        STATE_NAMES_CSV[STATE_LAST_ACTIVITY]            = "lastact";
        STATE_NAMES_CSV[STATE_CACHED_ACTIVITY]          = "cch-activity";
        STATE_NAMES_CSV[STATE_CACHED_ACTIVITY_CLIENT]   = "cch-aclient";
        STATE_NAMES_CSV[STATE_CACHED_EMPTY]             = "cch-empty";

        STATE_TAGS = new String[STATE_COUNT];
        STATE_TAGS[STATE_PERSISTENT]                = "p";
        STATE_TAGS[STATE_TOP]                       = "t";
        STATE_TAGS[STATE_IMPORTANT_FOREGROUND]      = "f";
        STATE_TAGS[STATE_IMPORTANT_BACKGROUND]      = "b";
        STATE_TAGS[STATE_BACKUP]                    = "u";
        STATE_TAGS[STATE_SERVICE]                   = "s";
        STATE_TAGS[STATE_SERVICE_RESTARTING]        = "x";
        STATE_TAGS[STATE_RECEIVER]                  = "r";
        STATE_TAGS[STATE_HEAVY_WEIGHT]              = "w";
        STATE_TAGS[STATE_HOME]                      = "h";
        STATE_TAGS[STATE_LAST_ACTIVITY]             = "l";
        STATE_TAGS[STATE_CACHED_ACTIVITY]           = "a";
        STATE_TAGS[STATE_CACHED_ACTIVITY_CLIENT]    = "c";
        STATE_TAGS[STATE_CACHED_EMPTY]              = "e";

        STATE_PROTO_ENUMS = new int[STATE_COUNT];
        STATE_PROTO_ENUMS[STATE_PERSISTENT] = ProcessStatsEnums.PROCESS_STATE_PERSISTENT;
        STATE_PROTO_ENUMS[STATE_TOP] = ProcessStatsEnums.PROCESS_STATE_TOP;
        STATE_PROTO_ENUMS[STATE_IMPORTANT_FOREGROUND] =
                ProcessStatsEnums.PROCESS_STATE_IMPORTANT_FOREGROUND;
        STATE_PROTO_ENUMS[STATE_IMPORTANT_BACKGROUND] =
                ProcessStatsEnums.PROCESS_STATE_IMPORTANT_BACKGROUND;
        STATE_PROTO_ENUMS[STATE_BACKUP] = ProcessStatsEnums.PROCESS_STATE_BACKUP;
        STATE_PROTO_ENUMS[STATE_SERVICE] = ProcessStatsEnums.PROCESS_STATE_SERVICE;
        STATE_PROTO_ENUMS[STATE_SERVICE_RESTARTING] =
                ProcessStatsEnums.PROCESS_STATE_SERVICE_RESTARTING;
        STATE_PROTO_ENUMS[STATE_RECEIVER] = ProcessStatsEnums.PROCESS_STATE_RECEIVER;
        STATE_PROTO_ENUMS[STATE_HEAVY_WEIGHT] = ProcessStatsEnums.PROCESS_STATE_HEAVY_WEIGHT;
        STATE_PROTO_ENUMS[STATE_HOME] = ProcessStatsEnums.PROCESS_STATE_HOME;
        STATE_PROTO_ENUMS[STATE_LAST_ACTIVITY] = ProcessStatsEnums.PROCESS_STATE_LAST_ACTIVITY;
        STATE_PROTO_ENUMS[STATE_CACHED_ACTIVITY] = ProcessStatsEnums.PROCESS_STATE_CACHED_ACTIVITY;
        STATE_PROTO_ENUMS[STATE_CACHED_ACTIVITY_CLIENT] =
                ProcessStatsEnums.PROCESS_STATE_CACHED_ACTIVITY_CLIENT;
        STATE_PROTO_ENUMS[STATE_CACHED_EMPTY] = ProcessStatsEnums.PROCESS_STATE_CACHED_EMPTY;

        // Remap states, as defined by ProcessStats.java, to a reduced subset of states for data
        // aggregation / size reduction purposes.
        PROCESS_STATS_STATE_TO_AGGREGATED_STATE = new int[STATE_COUNT];
        PROCESS_STATS_STATE_TO_AGGREGATED_STATE[STATE_PERSISTENT] =
                ProcessStatsEnums.AGGREGATED_PROCESS_STATE_PERSISTENT;
        PROCESS_STATS_STATE_TO_AGGREGATED_STATE[STATE_TOP] =
                ProcessStatsEnums.AGGREGATED_PROCESS_STATE_TOP;
        PROCESS_STATS_STATE_TO_AGGREGATED_STATE[STATE_IMPORTANT_FOREGROUND] =
                ProcessStatsEnums.AGGREGATED_PROCESS_STATE_IMPORTANT_FOREGROUND;
        PROCESS_STATS_STATE_TO_AGGREGATED_STATE[STATE_IMPORTANT_BACKGROUND] =
                ProcessStatsEnums.AGGREGATED_PROCESS_STATE_BACKGROUND;
        PROCESS_STATS_STATE_TO_AGGREGATED_STATE[STATE_BACKUP] =
                ProcessStatsEnums.AGGREGATED_PROCESS_STATE_BACKGROUND;
        PROCESS_STATS_STATE_TO_AGGREGATED_STATE[STATE_SERVICE] =
                ProcessStatsEnums.AGGREGATED_PROCESS_STATE_BACKGROUND;
        // "Restarting" is not a real state, so this shouldn't exist.
        PROCESS_STATS_STATE_TO_AGGREGATED_STATE[STATE_SERVICE_RESTARTING] =
                ProcessStatsEnums.AGGREGATED_PROCESS_STATE_UNKNOWN;
        PROCESS_STATS_STATE_TO_AGGREGATED_STATE[STATE_RECEIVER] =
                ProcessStatsEnums.AGGREGATED_PROCESS_STATE_RECEIVER;
        PROCESS_STATS_STATE_TO_AGGREGATED_STATE[STATE_HEAVY_WEIGHT] =
                ProcessStatsEnums.AGGREGATED_PROCESS_STATE_BACKGROUND;
        PROCESS_STATS_STATE_TO_AGGREGATED_STATE[STATE_HOME] =
                ProcessStatsEnums.AGGREGATED_PROCESS_STATE_CACHED;
        PROCESS_STATS_STATE_TO_AGGREGATED_STATE[STATE_LAST_ACTIVITY] =
                ProcessStatsEnums.AGGREGATED_PROCESS_STATE_CACHED;
        PROCESS_STATS_STATE_TO_AGGREGATED_STATE[STATE_CACHED_ACTIVITY] =
                ProcessStatsEnums.AGGREGATED_PROCESS_STATE_CACHED;
        PROCESS_STATS_STATE_TO_AGGREGATED_STATE[STATE_CACHED_ACTIVITY_CLIENT] =
                ProcessStatsEnums.AGGREGATED_PROCESS_STATE_CACHED;
        PROCESS_STATS_STATE_TO_AGGREGATED_STATE[STATE_CACHED_EMPTY] =
                ProcessStatsEnums.AGGREGATED_PROCESS_STATE_CACHED;
    }

    public static final String[] ADJ_SCREEN_NAMES_CSV = new String[] {
            "off", "on"
    };

    public static final String[] ADJ_MEM_NAMES_CSV = new String[] {
            "norm", "mod",  "low", "crit"
    };

    // State enum is defined in frameworks/base/core/proto/android/service/procstats.proto
    // Update states must sync enum definition as well, the ordering must not be changed.
    static final String[] ADJ_SCREEN_TAGS = new String[] {
            "0", "1"
    };

    static final int[] ADJ_SCREEN_PROTO_ENUMS = new int[] {
            ProcessStatsEnums.SCREEN_STATE_OFF,
            ProcessStatsEnums.SCREEN_STATE_ON
    };

    static final String[] ADJ_MEM_TAGS = new String[] {
            "n", "m",  "l", "c"
    };

    static final int[] ADJ_MEM_PROTO_ENUMS = new int[] {
            ProcessStatsEnums.MEMORY_STATE_NORMAL,
            ProcessStatsEnums.MEMORY_STATE_MODERATE,
            ProcessStatsEnums.MEMORY_STATE_LOW,
            ProcessStatsEnums.MEMORY_STATE_CRITICAL
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
                pw.print(" SOn/");
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
                pw.print(" Mod");
                if (sep != 0) pw.print(sep);
                break;
            case ADJ_MEM_FACTOR_LOW:
                pw.print(" Low");
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

    public static void printProcStateTagProto(ProtoOutputStream proto, long screenId, long memId,
            long stateId, int state) {
        state = printProto(proto, screenId, ADJ_SCREEN_PROTO_ENUMS,
                state, ADJ_SCREEN_MOD * STATE_COUNT);
        state = printProto(proto, memId, ADJ_MEM_PROTO_ENUMS, state, STATE_COUNT);
        printProto(proto, stateId, STATE_PROTO_ENUMS, state, 1);
    }

    public static void printAdjTag(PrintWriter pw, int state) {
        state = printArrayEntry(pw, ADJ_SCREEN_TAGS,  state, ADJ_SCREEN_MOD);
        printArrayEntry(pw, ADJ_MEM_TAGS, state, 1);
    }

    public static void printProcStateAdjTagProto(ProtoOutputStream proto, long screenId, long memId,
            int state) {
        state = printProto(proto, screenId, ADJ_SCREEN_PROTO_ENUMS,
                state, ADJ_SCREEN_MOD * STATE_COUNT);
        printProto(proto, memId, ADJ_MEM_PROTO_ENUMS, state, STATE_COUNT);
    }

    public static void printProcStateDurationProto(ProtoOutputStream proto, long fieldId,
            int procState, long duration) {
        final long stateToken = proto.start(fieldId);
        DumpUtils.printProto(proto, ProcessStatsStateProto.PROCESS_STATE,
                DumpUtils.STATE_PROTO_ENUMS, procState, 1);
        proto.write(ProcessStatsStateProto.DURATION_MS, duration);
        proto.end(stateToken);
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

    public static void dumpProcessSummaryLocked(PrintWriter pw, String prefix, String header,
            ArrayList<ProcessState> procs, int[] screenStates, int[] memStates, int[] procStates,
            long now, long totalTime) {
        for (int i=procs.size()-1; i>=0; i--) {
            final ProcessState proc = procs.get(i);
            proc.dumpSummary(pw, prefix, header, screenStates, memStates, procStates, now,
                    totalTime);
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

    public static int printProto(ProtoOutputStream proto, long fieldId,
            int[] enums, int value, int mod) {
        int index = value/mod;
        if (index >= 0 && index < enums.length) {
            proto.write(fieldId, enums[index]);
        } // else enum default is always zero in proto3
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

    /**
     * Aggregate process states to reduce size of statistics logs.
     *
     * <p>Involves unpacking the three parts of state (process state / device memory state /
     * screen state), manipulating the elements, then re-packing the new values into a single
     * int. This integer is guaranteed to be unique for any given combination of state elements.
     *
     * @param curState current state as used in mCurState in {@class ProcessState} ie. a value
     *                 combined from the process's state, the device's memory pressure state, and
     *                 the device's screen on/off state.
     * @return an integer representing the combination of screen state and process state, where
     *         process state has been aggregated.
     */
    public static int aggregateCurrentProcessState(int curState) {
        int screenStateIndex = curState / (ADJ_SCREEN_MOD * STATE_COUNT);
        // extract process state from the compound state variable (discarding memory state)
        int procStateIndex = curState % STATE_COUNT;

        // Remap process state per array above.
        try {
            procStateIndex = PROCESS_STATS_STATE_TO_AGGREGATED_STATE[procStateIndex];
        } catch (IndexOutOfBoundsException e) {
            procStateIndex = ProcessStatsEnums.AGGREGATED_PROCESS_STATE_UNKNOWN;
        }

        // Pack screen & process state using bit shifting
        return (procStateIndex << 0xf) | screenStateIndex;
    }

    /** Print aggregated tags generated via {@code #aggregateCurrentProcessState}. */
    public static void printAggregatedProcStateTagProto(ProtoOutputStream proto, long screenId,
            long stateId, int state) {
        // screen state is in lowest 0xf bits, process state is in next 0xf bits up

        try {
            proto.write(stateId, STATE_PROTO_ENUMS[state >> 0xf]);
        } catch (IndexOutOfBoundsException e) {
            proto.write(stateId, ProcessStatsEnums.PROCESS_STATE_UNKNOWN);
        }

        try {
            proto.write(screenId, ADJ_SCREEN_PROTO_ENUMS[state & 0xf]);
        } catch (IndexOutOfBoundsException e) {
            proto.write(screenId, ProcessStatsEnums.SCREEN_STATE_UNKNOWN);
        }
    }
}
