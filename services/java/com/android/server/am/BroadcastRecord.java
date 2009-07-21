/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.content.IIntentReceiver;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.PrintWriterPrinter;

import java.io.PrintWriter;
import java.util.List;

/**
 * An active intent broadcast.
 */
class BroadcastRecord extends Binder {
    final Intent intent;    // the original intent that generated us
    final ProcessRecord callerApp; // process that sent this
    final String callerPackage; // who sent this
    final int callingPid;   // the pid of who sent this
    final int callingUid;   // the uid of who sent this
    String requiredPermission; // a permission the caller has required
    final List receivers;   // contains BroadcastFilter and ResolveInfo
    final IIntentReceiver resultTo; // who receives final result if non-null
    long dispatchTime;      // when dispatch started on this set of receivers
    long startTime;         // when current receiver started for timeouts.
    int resultCode;         // current result code value.
    String resultData;      // current result data value.
    Bundle resultExtras;    // current result extra data values.
    boolean resultAbort;    // current result abortBroadcast value.
    boolean ordered;        // serialize the send to receivers?
    int nextReceiver;       // next receiver to be executed.
    IBinder receiver;       // who is currently running, null if none.
    int state;
    int anrCount;           // has this broadcast record hit any ANRs?

    static final int IDLE = 0;
    static final int APP_RECEIVE = 1;
    static final int CALL_IN_RECEIVE = 2;
    static final int CALL_DONE_RECEIVE = 3;

    // The following are set when we are calling a receiver (one that
    // was found in our list of registered receivers).
    BroadcastFilter curFilter;

    // The following are set only when we are launching a receiver (one
    // that was found by querying the package manager).
    ProcessRecord curApp;       // hosting application of current receiver.
    ComponentName curComponent; // the receiver class that is currently running.
    ActivityInfo curReceiver;   // info about the receiver that is currently running.

    void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + this);
        pw.println(prefix + intent);
        pw.println(prefix + "proc=" + callerApp);
        pw.println(prefix + "caller=" + callerPackage
                + " callingPid=" + callingPid
                + " callingUid=" + callingUid);
        pw.println(prefix + "requiredPermission=" + requiredPermission);
        pw.println(prefix + "dispatchTime=" + dispatchTime + " ("
                + (SystemClock.uptimeMillis()-dispatchTime) + " since now)");
        pw.println(prefix + "startTime=" + startTime + " ("
                + (SystemClock.uptimeMillis()-startTime) + " since now)");
        pw.println(prefix + "anrCount=" + anrCount);
        pw.println(prefix + "resultTo=" + resultTo
              + " resultCode=" + resultCode + " resultData=" + resultData);
        pw.println(prefix + "resultExtras=" + resultExtras);
        pw.println(prefix + "resultAbort=" + resultAbort
                + " ordered=" + ordered);
        pw.println(prefix + "nextReceiver=" + nextReceiver
              + " receiver=" + receiver);
        pw.println(prefix + "curFilter=" + curFilter);
        pw.println(prefix + "curReceiver="
                + ((curReceiver != null) ? curReceiver : "(null)"));
        pw.println(prefix + "curApp=" + curApp);
        if (curApp != null) {
            pw.println(prefix + "curComponent="
                    + (curComponent != null ? curComponent.toShortString() : "--"));
            pw.println(prefix + "curSourceDir=" + curReceiver.applicationInfo.sourceDir);
        }
        String stateStr = " (?)";
        switch (state) {
            case IDLE:              stateStr=" (IDLE)"; break;
            case APP_RECEIVE:       stateStr=" (APP_RECEIVE)"; break;
            case CALL_IN_RECEIVE:   stateStr=" (CALL_IN_RECEIVE)"; break;
            case CALL_DONE_RECEIVE: stateStr=" (CALL_DONE_RECEIVE)"; break;
        }
        pw.println(prefix + "state=" + state + stateStr);
        final int N = receivers != null ? receivers.size() : 0;
        String p2 = prefix + "  ";
        PrintWriterPrinter printer = new PrintWriterPrinter(pw);
        for (int i=0; i<N; i++) {
            Object o = receivers.get(i);
            pw.println(prefix + "Receiver #" + i + ": " + o);
            if (o instanceof BroadcastFilter)
                ((BroadcastFilter)o).dump(pw, p2);
            else if (o instanceof ResolveInfo)
                ((ResolveInfo)o).dump(printer, p2);
        }
    }

    BroadcastRecord(Intent _intent, ProcessRecord _callerApp, String _callerPackage,
            int _callingPid, int _callingUid, String _requiredPermission,
            List _receivers, IIntentReceiver _resultTo, int _resultCode,
            String _resultData, Bundle _resultExtras, boolean _serialized) {
        intent = _intent;
        callerApp = _callerApp;
        callerPackage = _callerPackage;
        callingPid = _callingPid;
        callingUid = _callingUid;
        requiredPermission = _requiredPermission;
        receivers = _receivers;
        resultTo = _resultTo;
        resultCode = _resultCode;
        resultData = _resultData;
        resultExtras = _resultExtras;
        ordered = _serialized;
        nextReceiver = 0;
        state = IDLE;
    }

    public String toString() {
        return "BroadcastRecord{"
            + Integer.toHexString(System.identityHashCode(this))
            + " " + intent.getAction() + "}";
    }
}
