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

import android.app.IActivityManager.ContentProviderHolder;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import android.os.Process;

import java.io.PrintWriter;
import java.util.HashSet;

class ContentProviderRecord extends ContentProviderHolder {
    // All attached clients
    final HashSet<ProcessRecord> clients = new HashSet<ProcessRecord>();
    final int uid;
    final ApplicationInfo appInfo;
    final ComponentName name;
    int externals;     // number of non-framework processes supported by this provider
    ProcessRecord proc; // if non-null, hosting process.
    ProcessRecord launchingApp; // if non-null, waiting for this app to be launched.
    String stringName;
    
    public ContentProviderRecord(ProviderInfo _info, ApplicationInfo ai, ComponentName _name) {
        super(_info);
        uid = ai.uid;
        appInfo = ai;
        name = _name;
        noReleaseNeeded = uid == 0 || uid == Process.SYSTEM_UID;
    }

    public ContentProviderRecord(ContentProviderRecord cpr) {
        super(cpr.info);
        uid = cpr.uid;
        appInfo = cpr.appInfo;
        name = cpr.name;
        noReleaseNeeded = cpr.noReleaseNeeded;
    }

    public boolean canRunHere(ProcessRecord app) {
        return (info.multiprocess || info.processName.equals(app.processName))
                && (uid == Process.SYSTEM_UID || uid == app.info.uid);
    }

    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("package=");
                pw.print(info.applicationInfo.packageName);
                pw.print(" process="); pw.println(info.processName);
        pw.print(prefix); pw.print("proc="); pw.println(proc);
        if (launchingApp != null) {
            pw.print(prefix); pw.print("launchingApp="); pw.println(launchingApp);
        }
        pw.print(prefix); pw.print("uid="); pw.print(uid);
                pw.print(" provider="); pw.println(provider);
        pw.print(prefix); pw.print("name="); pw.println(info.authority);
        if (info.isSyncable || info.multiprocess || info.initOrder != 0) {
            pw.print(prefix); pw.print("isSyncable="); pw.print(info.isSyncable);
                    pw.print("multiprocess="); pw.print(info.multiprocess);
                    pw.print(" initOrder="); pw.println(info.initOrder);
        }
        if (clients.size() > 0) {
            pw.print(prefix); pw.println("Clients:");
            for (ProcessRecord cproc : clients) {
                pw.print(prefix); pw.println("  - "); pw.println(cproc);
            }
            pw.print(prefix); pw.print("clients="); pw.println(clients);
        }
        if (externals != 0) {
            pw.print(prefix); pw.print("externals="); pw.println(externals);
        }
    }

    public String toString() {
        if (stringName != null) {
            return stringName;
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("ContentProviderRecord{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(' ');
        sb.append(info.name);
        sb.append('}');
        return stringName = sb.toString();
    }
}
