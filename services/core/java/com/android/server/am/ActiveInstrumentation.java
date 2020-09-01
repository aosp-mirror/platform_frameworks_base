/*
 * Copyright (C) 2017 The Android Open Source Project
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
import android.app.IUiAutomationConnection;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.util.PrintWriterPrinter;
import android.util.proto.ProtoOutputStream;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;

class ActiveInstrumentation {
    final ActivityManagerService mService;

    // Class installed to instrument app
    ComponentName mClass;

    // All process names that should be instrumented
    String[] mTargetProcesses;

    // The application being instrumented
    ApplicationInfo mTargetInfo;

    // Where to save profiling
    String mProfileFile;

    // Who is waiting
    IInstrumentationWatcher mWatcher;

    // Connection to use the UI introspection APIs.
    IUiAutomationConnection mUiAutomationConnection;

    // Whether the caller holds START_ACTIVITIES_FROM_BACKGROUND permission
    boolean mHasBackgroundActivityStartsPermission;

    // As given to us
    Bundle mArguments;

    // Any intermediate results that have been collected.
    Bundle mCurResults;

    // Copy of instrumentationClass.
    ComponentName mResultClass;

    // Contains all running processes that have active instrumentation.
    final ArrayList<ProcessRecord> mRunningProcesses = new ArrayList<>();

    // Set to true when we have told the watcher the instrumentation is finished.
    boolean mFinished;

    // The uid of the process who started this instrumentation.
    int mSourceUid;

    ActiveInstrumentation(ActivityManagerService service) {
        mService = service;
    }

    void removeProcess(ProcessRecord proc) {
        mFinished = true;
        mRunningProcesses.remove(proc);
        if (mRunningProcesses.size() == 0) {
            mService.mActiveInstrumentation.remove(this);
        }
    }

    public String toString() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("ActiveInstrumentation{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(' ');
        sb.append(mClass.toShortString());
        if (mFinished) {
            sb.append(" FINISHED");
        }
        sb.append(" ");
        sb.append(mRunningProcesses.size());
        sb.append(" procs");
        sb.append('}');
        return sb.toString();
    }

    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("mClass="); pw.print(mClass);
        pw.print(" mFinished="); pw.println(mFinished);
        pw.print(prefix); pw.println("mRunningProcesses:");
        for (int i=0; i<mRunningProcesses.size(); i++) {
            pw.print(prefix); pw.print("  #"); pw.print(i); pw.print(": ");
            pw.println(mRunningProcesses.get(i));
        }
        pw.print(prefix); pw.print("mTargetProcesses=");
        pw.println(Arrays.toString(mTargetProcesses));
        pw.print(prefix); pw.print("mTargetInfo=");
        pw.println(mTargetInfo);
        if (mTargetInfo != null) {
            mTargetInfo.dump(new PrintWriterPrinter(pw), prefix + "  ", 0);
        }
        if (mProfileFile != null) {
            pw.print(prefix); pw.print("mProfileFile="); pw.println(mProfileFile);
        }
        if (mWatcher != null) {
            pw.print(prefix); pw.print("mWatcher="); pw.println(mWatcher);
        }
        if (mUiAutomationConnection != null) {
            pw.print(prefix); pw.print("mUiAutomationConnection=");
            pw.println(mUiAutomationConnection);
        }
        pw.print("mHasBackgroundActivityStartsPermission=");
        pw.println(mHasBackgroundActivityStartsPermission);
        pw.print(prefix); pw.print("mArguments=");
        pw.println(mArguments);
    }

    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        mClass.dumpDebug(proto, ActiveInstrumentationProto.CLASS);
        proto.write(ActiveInstrumentationProto.FINISHED, mFinished);
        for (int i=0; i<mRunningProcesses.size(); i++) {
            mRunningProcesses.get(i).dumpDebug(proto,
                    ActiveInstrumentationProto.RUNNING_PROCESSES);
        }
        for (String p : mTargetProcesses) {
            proto.write(ActiveInstrumentationProto.TARGET_PROCESSES, p);
        }
        if (mTargetInfo != null) {
            mTargetInfo.dumpDebug(proto, ActiveInstrumentationProto.TARGET_INFO, 0);
        }
        proto.write(ActiveInstrumentationProto.PROFILE_FILE, mProfileFile);
        proto.write(ActiveInstrumentationProto.WATCHER, mWatcher.toString());
        proto.write(ActiveInstrumentationProto.UI_AUTOMATION_CONNECTION,
                mUiAutomationConnection.toString());
        if (mArguments != null) {
            mArguments.dumpDebug(proto, ActiveInstrumentationProto.ARGUMENTS);
        }
        proto.end(token);
    }
}
