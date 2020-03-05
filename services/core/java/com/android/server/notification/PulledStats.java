/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.notification;

import static com.android.server.notification.NotificationManagerService.REPORT_REMOTE_VIEWS;

import android.os.ParcelFileDescriptor;
import android.service.notification.NotificationRemoteViewsProto;
import android.service.notification.PackageRemoteViewInfoProto;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public class PulledStats {
    static final String TAG = "PulledStats";

    private final long mTimePeriodStartMs;
    private long mTimePeriodEndMs;
    private List<String> mUndecoratedPackageNames;

    public PulledStats(long startMs) {
        mTimePeriodEndMs = mTimePeriodStartMs = startMs;
        mUndecoratedPackageNames = new ArrayList<>();
    }

    ParcelFileDescriptor toParcelFileDescriptor(int report)
            throws IOException {
        final ParcelFileDescriptor[] fds = ParcelFileDescriptor.createPipe();
        switch(report) {
            case REPORT_REMOTE_VIEWS:
                Thread thr = new Thread("NotificationManager pulled metric output") {
                    public void run() {
                        try {
                            FileOutputStream fout = new ParcelFileDescriptor.AutoCloseOutputStream(
                                    fds[1]);
                            final ProtoOutputStream proto = new ProtoOutputStream(fout);
                            writeToProto(report, proto);
                            proto.flush();
                            fout.close();
                        } catch (IOException e) {
                            Slog.w(TAG, "Failure writing pipe", e);
                        }
                    }
                };
                thr.start();
                break;

            default:
                Slog.w(TAG, "Unknown pulled stats request: " + report);
                break;
        }
        return fds[0];
    }

    /*
     * @return the most recent timestamp in the report, as nanoseconds.
     */
    public long endTimeMs() {
        return mTimePeriodEndMs;
    }

    public void dump(int report, PrintWriter pw, NotificationManagerService.DumpFilter filter) {
        switch(report) {
            case REPORT_REMOTE_VIEWS:
                pw.print("  Packages with undecordated notifications (");
                pw.print(mTimePeriodStartMs);
                pw.print(" - ");
                pw.print(mTimePeriodEndMs);
                pw.println("):");
                if (mUndecoratedPackageNames.size() == 0) {
                    pw.println("    none");
                } else {
                    for (String pkg : mUndecoratedPackageNames) {
                        if (!filter.filtered || pkg.equals(filter.pkgFilter)) {
                            pw.println("    " + pkg);
                        }
                    }
                }
                break;

            default:
                pw.println("Unknown pulled stats request: " + report);
                break;
        }
    }

    @VisibleForTesting
    void writeToProto(int report, ProtoOutputStream proto) {
        switch(report) {
            case REPORT_REMOTE_VIEWS:
                for (String pkg: mUndecoratedPackageNames) {
                    long token = proto.start(NotificationRemoteViewsProto.PACKAGE_REMOTE_VIEW_INFO);
                    proto.write(PackageRemoteViewInfoProto.PACKAGE_NAME, pkg);
                    proto.end(token);
                }
                break;

            default:
                Slog.w(TAG, "Unknown pulled stats request: " + report);
                break;
        }
    }

    public void addUndecoratedPackage(String packageName, long timestampMs) {
        mUndecoratedPackageNames.add(packageName);
        mTimePeriodEndMs = Math.max(mTimePeriodEndMs, timestampMs);
    }
}
