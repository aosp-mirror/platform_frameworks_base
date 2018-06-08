/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.MutableLong;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.util.proto.ProtoUtils;

import java.io.PrintWriter;

/**
 * Tracks the time a user spent in an app.
 */
public class AppTimeTracker {
    private final PendingIntent mReceiver;

    private long mTotalTime;
    private final ArrayMap<String, MutableLong> mPackageTimes = new ArrayMap<>();

    private long mStartedTime;
    private String mStartedPackage;
    private MutableLong mStartedPackageTime;

    public AppTimeTracker(PendingIntent receiver) {
        mReceiver = receiver;
    }

    public void start(String packageName) {
        long now = SystemClock.elapsedRealtime();
        if (mStartedTime == 0) {
            mStartedTime = now;
        }
        if (!packageName.equals(mStartedPackage)) {
            if (mStartedPackageTime != null) {
                long elapsedTime = now - mStartedTime;
                mStartedPackageTime.value += elapsedTime;
                mTotalTime += elapsedTime;
            }
            mStartedPackage = packageName;
            mStartedPackageTime = mPackageTimes.get(packageName);
            if (mStartedPackageTime == null) {
                mStartedPackageTime = new MutableLong(0);
                mPackageTimes.put(packageName, mStartedPackageTime);
            }
        }
    }

    public void stop() {
        if (mStartedTime != 0) {
            long elapsedTime = SystemClock.elapsedRealtime() - mStartedTime;
            mTotalTime += elapsedTime;
            if (mStartedPackageTime != null) {
                mStartedPackageTime.value += elapsedTime;
            }
            mStartedPackage = null;
            mStartedPackageTime = null;
        }
    }

    public void deliverResult(Context context) {
        stop();
        Bundle extras = new Bundle();
        extras.putLong(ActivityOptions.EXTRA_USAGE_TIME_REPORT, mTotalTime);
        Bundle pkgs = new Bundle();
        for (int i=mPackageTimes.size()-1; i>=0; i--) {
            pkgs.putLong(mPackageTimes.keyAt(i), mPackageTimes.valueAt(i).value);
        }
        extras.putBundle(ActivityOptions.EXTRA_USAGE_TIME_REPORT_PACKAGES, pkgs);
        Intent fillinIntent = new Intent();
        fillinIntent.putExtras(extras);
        try {
            mReceiver.send(context, 0, fillinIntent);
        } catch (PendingIntent.CanceledException e) {
        }
    }

    public void dumpWithHeader(PrintWriter pw, String prefix, boolean details) {
        pw.print(prefix); pw.print("AppTimeTracker #");
        pw.print(Integer.toHexString(System.identityHashCode(this)));
        pw.println(":");
        dump(pw, prefix + "  ", details);
    }

    public void dump(PrintWriter pw, String prefix, boolean details) {
        pw.print(prefix); pw.print("mReceiver="); pw.println(mReceiver);
        pw.print(prefix); pw.print("mTotalTime=");
        TimeUtils.formatDuration(mTotalTime, pw);
        pw.println();
        for (int i = 0; i < mPackageTimes.size(); i++) {
            pw.print(prefix); pw.print("mPackageTime:"); pw.print(mPackageTimes.keyAt(i));
            pw.print("=");
            TimeUtils.formatDuration(mPackageTimes.valueAt(i).value, pw);
            pw.println();
        }
        if (details && mStartedTime != 0) {
            pw.print(prefix); pw.print("mStartedTime=");
            TimeUtils.formatDuration(SystemClock.elapsedRealtime(), mStartedTime, pw);
            pw.println();
            pw.print(prefix); pw.print("mStartedPackage="); pw.println(mStartedPackage);
        }
    }

    void writeToProto(ProtoOutputStream proto, long fieldId, boolean details) {
        final long token = proto.start(fieldId);
        proto.write(AppTimeTrackerProto.RECEIVER, mReceiver.toString());
        proto.write(AppTimeTrackerProto.TOTAL_DURATION_MS, mTotalTime);
        for (int i=0; i<mPackageTimes.size(); i++) {
            final long ptoken = proto.start(AppTimeTrackerProto.PACKAGE_TIMES);
            proto.write(AppTimeTrackerProto.PackageTime.PACKAGE, mPackageTimes.keyAt(i));
            proto.write(AppTimeTrackerProto.PackageTime.DURATION_MS, mPackageTimes.valueAt(i).value);
            proto.end(ptoken);
        }
        if (details && mStartedTime != 0) {
            ProtoUtils.toDuration(proto, AppTimeTrackerProto.STARTED_TIME,
                    mStartedTime, SystemClock.elapsedRealtime());
            proto.write(AppTimeTrackerProto.STARTED_PACKAGE, mStartedPackage);
        }
        proto.end(token);
    }
}
