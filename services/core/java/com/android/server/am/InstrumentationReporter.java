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

import android.app.IInstrumentationWatcher;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Process;
import android.os.RemoteException;
import android.util.Slog;

import java.util.ArrayList;

public class InstrumentationReporter {
    static final boolean DEBUG = false;
    static final String TAG = ActivityManagerDebugConfig.TAG_AM;

    static final int REPORT_TYPE_STATUS = 0;
    static final int REPORT_TYPE_FINISHED = 1;

    final Object mLock = new Object();
    ArrayList<Report> mPendingReports;
    Thread mThread;

    final class MyThread extends Thread {
        public MyThread() {
            super("InstrumentationReporter");
            if (DEBUG) Slog.d(TAG, "Starting InstrumentationReporter: " + this);
        }

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
            boolean waited = false;
            while (true) {
                ArrayList<Report> reports;
                synchronized (mLock) {
                    reports = mPendingReports;
                    mPendingReports = null;
                    if (reports == null || reports.isEmpty()) {
                        if (!waited) {
                            // Sleep for a little bit, to avoid thrashing through threads.
                            try {
                                mLock.wait(10000); // 10 seconds
                            } catch (InterruptedException e) {
                            }
                            waited = true;
                            continue;
                        } else {
                            mThread = null;
                            if (DEBUG) Slog.d(TAG, "Exiting InstrumentationReporter: " + this);
                            return;
                        }
                    }
                }

                waited = false;

                for (int i=0; i<reports.size(); i++) {
                    final Report rep = reports.get(i);
                    try {
                        if (rep.mType == REPORT_TYPE_STATUS) {
                            if (DEBUG) Slog.d(TAG, "Dispatch status to " + rep.mWatcher
                                    + ": " + rep.mName.flattenToShortString()
                                    + " code=" + rep.mResultCode + " result=" + rep.mResults);
                            rep.mWatcher.instrumentationStatus(rep.mName, rep.mResultCode,
                                    rep.mResults);
                        } else {
                            if (DEBUG) Slog.d(TAG, "Dispatch finished to " + rep.mWatcher
                                    + ": " + rep.mName.flattenToShortString()
                                    + " code=" + rep.mResultCode + " result=" + rep.mResults);
                            rep.mWatcher.instrumentationFinished(rep.mName, rep.mResultCode,
                                    rep.mResults);
                        }
                    } catch (RemoteException e) {
                        Slog.i(TAG, "Failure reporting to instrumentation watcher: comp="
                                + rep.mName + " results=" + rep.mResults);
                    }
                }
            }
        }
    }

    final class Report {
        final int mType;
        final IInstrumentationWatcher mWatcher;
        final ComponentName mName;
        final int mResultCode;
        final Bundle mResults;

        Report(int type, IInstrumentationWatcher watcher, ComponentName name, int resultCode,
                Bundle results) {
            mType = type;
            mWatcher = watcher;
            mName = name;
            mResultCode = resultCode;
            mResults = results;
        }
    }

    public void reportStatus(IInstrumentationWatcher watcher, ComponentName name, int resultCode,
            Bundle results) {
        if (DEBUG) Slog.d(TAG, "Report status to " + watcher
                + ": " + name.flattenToShortString()
                + " code=" + resultCode + " result=" + results);
        report(new Report(REPORT_TYPE_STATUS, watcher, name, resultCode, results));
    }

    public void reportFinished(IInstrumentationWatcher watcher, ComponentName name, int resultCode,
            Bundle results) {
        if (DEBUG) Slog.d(TAG, "Report finished to " + watcher
                + ": " + name.flattenToShortString()
                + " code=" + resultCode + " result=" + results);
        report(new Report(REPORT_TYPE_FINISHED, watcher, name, resultCode, results));
    }

    private void report(Report report) {
        synchronized (mLock) {
            if (mThread == null) {
                mThread = new MyThread();
                mThread.start();
            }
            if (mPendingReports == null) {
                mPendingReports = new ArrayList<>();
            }
            mPendingReports.add(report);
            mLock.notifyAll();
        }
    }
}
