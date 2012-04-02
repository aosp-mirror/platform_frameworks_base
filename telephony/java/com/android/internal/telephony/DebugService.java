/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.internal.telephony;

import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * A debug service that will dump telephony's state
 *
 * Currently this "Service" has a proxy in the phone app
 * com.android.phone.TelephonyDebugService which actually
 * invokes the dump method.
 */
public class DebugService {
    private static String TAG = "DebugService";

    /** Constructor */
    public DebugService() {
        log("DebugService:");
    }

    /**
     * Dump the state of various objects, add calls to other objects as desired.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        log("dump: +");
        PhoneProxy phoneProxy = null;
        PhoneBase phoneBase = null;

        try {
            phoneProxy = (PhoneProxy) PhoneFactory.getDefaultPhone();
        } catch (Exception e) {
            pw.println("Telephony DebugService: Could not getDefaultPhone e=" + e);
            return;
        }
        try {
            phoneBase = (PhoneBase)phoneProxy.getActivePhone();
        } catch (Exception e) {
            pw.println("Telephony DebugService: Could not PhoneBase e=" + e);
            return;
        }

        /**
         * Surround each of the sub dump's with try/catch so even
         * if one fails we'll be able to dump the next ones.
         */
        pw.println();
        pw.println("++++++++++++++++++++++++++++++++");
        pw.flush();
        try {
            phoneBase.dump(fd, pw, args);
        } catch (Exception e) {
            e.printStackTrace();
        }
        pw.flush();
        pw.println("++++++++++++++++++++++++++++++++");
        try {
            phoneBase.mDataConnectionTracker.dump(fd, pw, args);
        } catch (Exception e) {
            e.printStackTrace();
        }
        pw.flush();
        pw.println("++++++++++++++++++++++++++++++++");
        try {
            phoneBase.getServiceStateTracker().dump(fd, pw, args);
        } catch (Exception e) {
            e.printStackTrace();
        }
        pw.flush();
        pw.println("++++++++++++++++++++++++++++++++");
        try {
            phoneBase.getCallTracker().dump(fd, pw, args);
        } catch (Exception e) {
            e.printStackTrace();
        }
        pw.flush();
        pw.println("++++++++++++++++++++++++++++++++");
        try {
            ((RIL)phoneBase.mCM).dump(fd, pw, args);
        } catch (Exception e) {
            e.printStackTrace();
        }
        pw.flush();
        pw.println("++++++++++++++++++++++++++++++++");
        log("dump: -");
    }

    private static void log(String s) {
        Log.d(TAG, "DebugService " + s);
    }
}
