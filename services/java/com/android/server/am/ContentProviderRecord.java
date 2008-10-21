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
    int externals;     // number of non-framework processes supported by this provider
    ProcessRecord app; // if non-null, hosting application
    ProcessRecord launchingApp; // if non-null, waiting for this app to be launched.

    public ContentProviderRecord(ProviderInfo _info, ApplicationInfo ai) {
        super(_info);
        uid = ai.uid;
        appInfo = ai;
        noReleaseNeeded = uid == 0 || uid == Process.SYSTEM_UID;
    }

    public ContentProviderRecord(ContentProviderRecord cpr) {
        super(cpr.info);
        uid = cpr.uid;
        appInfo = cpr.appInfo;
        noReleaseNeeded = cpr.noReleaseNeeded;
    }

    public boolean canRunHere(ProcessRecord app) {
        return (info.multiprocess || info.processName.equals(app.processName))
                && (uid == Process.SYSTEM_UID || uid == app.info.uid);
    }

    void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + this);
        pw.println(prefix + "package=" + info.applicationInfo.packageName
              + " process=" + info.processName);
        pw.println(prefix + "app=" + app);
        pw.println(prefix + "launchingApp=" + launchingApp);
        pw.println(prefix + "provider=" + provider);
        pw.println(prefix + "name=" + info.authority);
        pw.println(prefix + "isSyncable=" + info.isSyncable);
        pw.println(prefix + "multiprocess=" + info.multiprocess
              + " initOrder=" + info.initOrder
              + " uid=" + uid);
        pw.println(prefix + "clients=" + clients);
        pw.println(prefix + "externals=" + externals);
    }

    public String toString() {
        return "ContentProviderRecord{"
            + Integer.toHexString(System.identityHashCode(this))
            + " " + info.name + "}";
    }
}
