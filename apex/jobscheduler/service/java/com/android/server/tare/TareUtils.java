/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.tare;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.util.IndentingPrintWriter;

import com.android.internal.annotations.VisibleForTesting;

import java.text.SimpleDateFormat;
import java.time.Clock;

class TareUtils {
    private static final long NARC_IN_ARC = 1_000_000_000L;

    @SuppressLint("SimpleDateFormat")
    private static final SimpleDateFormat sDumpDateFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    @VisibleForTesting
    static Clock sSystemClock = Clock.systemUTC();

    static long arcToNarc(int arcs) {
        return arcs * NARC_IN_ARC;
    }

    static void dumpTime(IndentingPrintWriter pw, long time) {
        pw.print(sDumpDateFormat.format(time));
    }

    static long getCurrentTimeMillis() {
        return sSystemClock.millis();
    }

    static int narcToArc(long narcs) {
        return (int) (narcs / NARC_IN_ARC);
    }

    @NonNull
    static String narcToString(long narcs) {
        if (narcs == 0) {
            return "0 ARCs";
        }
        final long sub = Math.abs(narcs) % NARC_IN_ARC;
        final long arcs = narcToArc(narcs);
        if (arcs == 0) {
            return sub == 1
                    ? sub + " narc"
                    : sub + " narcs";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(arcs);
        if (sub > 0) {
            sb.append(".").append(sub / (NARC_IN_ARC / 1000));
        }
        sb.append(" ARC");
        if (arcs != 1 || sub > 0) {
            sb.append("s");
        }
        return sb.toString();
    }

    /** Returns a standardized format for printing userId+pkgName combinations. */
    @NonNull
    static String appToString(int userId, String pkgName) {
        return "<" + userId + ">" + pkgName;
    }
}
