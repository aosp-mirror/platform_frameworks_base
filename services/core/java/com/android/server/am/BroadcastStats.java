/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.TimeUtils;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

public final class BroadcastStats {
    final long mStartRealtime;
    final long mStartUptime;
    long mEndRealtime;
    long mEndUptime;
    final ArrayMap<String, ActionEntry> mActions = new ArrayMap<>();

    static final Comparator<ActionEntry> ACTIONS_COMPARATOR = new Comparator<ActionEntry>() {
        @Override public int compare(ActionEntry o1, ActionEntry o2) {
            if (o1.mTotalDispatchTime < o2.mTotalDispatchTime) {
                return -1;
            }
            if (o1.mTotalDispatchTime > o2.mTotalDispatchTime) {
                return 1;
            }
            return 0;
        }
    };

    static final class ActionEntry {
        final String mAction;
        final ArrayMap<String, PackageEntry> mPackages = new ArrayMap<>();
        final ArrayMap<String, ViolationEntry> mBackgroundCheckViolations = new ArrayMap<>();
        int mReceiveCount;
        int mSkipCount;
        long mTotalDispatchTime;
        long mMaxDispatchTime;

        ActionEntry(String action) {
            mAction = action;
        }
    }

    static final class PackageEntry {
        int mSendCount;
    }

    static final class ViolationEntry {
        int mCount;
    }

    public BroadcastStats() {
        mStartRealtime = SystemClock.elapsedRealtime();
        mStartUptime = SystemClock.uptimeMillis();
    }

    public void addBroadcast(String action, String srcPackage, int receiveCount,
            int skipCount, long dispatchTime) {
        ActionEntry ae = mActions.get(action);
        if (ae == null) {
            ae = new ActionEntry(action);
            mActions.put(action, ae);
        }
        ae.mReceiveCount += receiveCount;
        ae.mSkipCount += skipCount;
        ae.mTotalDispatchTime += dispatchTime;
        if (ae.mMaxDispatchTime < dispatchTime) {
            ae.mMaxDispatchTime = dispatchTime;
        }
        PackageEntry pe = ae.mPackages.get(srcPackage);
        if (pe == null) {
            pe = new PackageEntry();
            ae.mPackages.put(srcPackage, pe);
        }
        pe.mSendCount++;
    }

    public void addBackgroundCheckViolation(String action, String targetPackage) {
        ActionEntry ae = mActions.get(action);
        if (ae == null) {
            ae = new ActionEntry(action);
            mActions.put(action, ae);
        }
        ViolationEntry ve = ae.mBackgroundCheckViolations.get(targetPackage);
        if (ve == null) {
            ve = new ViolationEntry();
            ae.mBackgroundCheckViolations.put(targetPackage, ve);
        }
        ve.mCount++;
    }

    public boolean dumpStats(PrintWriter pw, String prefix, String dumpPackage) {
        boolean printedSomething = false;
        ArrayList<ActionEntry> actions = new ArrayList<>(mActions.size());
        for (int i=mActions.size()-1; i>=0; i--) {
            actions.add(mActions.valueAt(i));
        }
        Collections.sort(actions, ACTIONS_COMPARATOR);
        for (int i=actions.size()-1; i>=0; i--) {
            ActionEntry ae = actions.get(i);
            if (dumpPackage != null && !ae.mPackages.containsKey(dumpPackage)) {
                continue;
            }
            printedSomething = true;
            pw.print(prefix);
            pw.print(ae.mAction);
            pw.println(":");
            pw.print(prefix);
            pw.print("  Number received: ");
            pw.print(ae.mReceiveCount);
            pw.print(", skipped: ");
            pw.println(ae.mSkipCount);
            pw.print(prefix);
            pw.print("  Total dispatch time: ");
            TimeUtils.formatDuration(ae.mTotalDispatchTime, pw);
            pw.print(", max: ");
            TimeUtils.formatDuration(ae.mMaxDispatchTime, pw);
            pw.println();
            for (int j=ae.mPackages.size()-1; j>=0; j--) {
                pw.print(prefix);
                pw.print("  Package ");
                pw.print(ae.mPackages.keyAt(j));
                pw.print(": ");
                PackageEntry pe = ae.mPackages.valueAt(j);
                pw.print(pe.mSendCount);
                pw.println(" times");
            }
            for (int j=ae.mBackgroundCheckViolations.size()-1; j>=0; j--) {
                pw.print(prefix);
                pw.print("  Bg Check Violation ");
                pw.print(ae.mBackgroundCheckViolations.keyAt(j));
                pw.print(": ");
                ViolationEntry ve = ae.mBackgroundCheckViolations.valueAt(j);
                pw.print(ve.mCount);
                pw.println(" times");
            }
        }
        return printedSomething;
    }

    public void dumpCheckinStats(PrintWriter pw, String dumpPackage) {
        pw.print("broadcast-stats,1,");
        pw.print(mStartRealtime);
        pw.print(",");
        pw.print(mEndRealtime == 0 ? SystemClock.elapsedRealtime() : mEndRealtime);
        pw.print(",");
        pw.println((mEndUptime == 0 ? SystemClock.uptimeMillis() : mEndUptime) - mStartUptime);
        for (int i=mActions.size()-1; i>=0; i--) {
            ActionEntry ae = mActions.valueAt(i);
            if (dumpPackage != null && !ae.mPackages.containsKey(dumpPackage)) {
                continue;
            }
            pw.print("a,");
            pw.print(mActions.keyAt(i));
            pw.print(",");
            pw.print(ae.mReceiveCount);
            pw.print(",");
            pw.print(ae.mSkipCount);
            pw.print(",");
            pw.print(ae.mTotalDispatchTime);
            pw.print(",");
            pw.print(ae.mMaxDispatchTime);
            pw.println();
            for (int j=ae.mPackages.size()-1; j>=0; j--) {
                pw.print("p,");
                pw.print(ae.mPackages.keyAt(j));
                PackageEntry pe = ae.mPackages.valueAt(j);
                pw.print(",");
                pw.print(pe.mSendCount);
                pw.println();
            }
            for (int j=ae.mBackgroundCheckViolations.size()-1; j>=0; j--) {
                pw.print("v,");
                pw.print(ae.mBackgroundCheckViolations.keyAt(j));
                ViolationEntry ve = ae.mBackgroundCheckViolations.valueAt(j);
                pw.print(",");
                pw.print(ve.mCount);
                pw.println();
            }
        }
    }
}
