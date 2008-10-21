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

import android.app.IIntentReceiver;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * A receiver object that has registered for one or more broadcasts.
 * The ArrayList holds BroadcastFilter objects.
 */
class ReceiverList extends ArrayList<BroadcastFilter>
        implements IBinder.DeathRecipient {
    final ActivityManagerService owner;
    public final IIntentReceiver receiver;
    public final ProcessRecord app;
    public final int pid;
    public final int uid;
    BroadcastRecord curBroadcast = null;
    boolean linkedToDeath = false;

    ReceiverList(ActivityManagerService _owner, ProcessRecord _app,
            int _pid, int _uid, IIntentReceiver _receiver) {
        owner = _owner;
        receiver = _receiver;
        app = _app;
        pid = _pid;
        uid = _uid;
    }

    // Want object identity, not the array identity we are inheriting.
    public boolean equals(Object o) {
        return this == o;
    }
    public int hashCode() {
        return System.identityHashCode(this);
    }
    
    public void binderDied() {
        linkedToDeath = false;
        owner.unregisterReceiver(receiver);
    }
    
    void dumpLocal(PrintWriter pw, String prefix) {
        pw.println(prefix + "receiver=IBinder "
                + Integer.toHexString(System.identityHashCode(receiver.asBinder())));
        pw.println(prefix + "app=" + app + " pid=" + pid + " uid=" + uid);
        pw.println(prefix + "curBroadcast=" + curBroadcast
                + " linkedToDeath=" + linkedToDeath);
    }
    
    void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + this);
        dumpLocal(pw, prefix);
        String p2 = prefix + "  ";
        final int N = size();
        for (int i=0; i<N; i++) {
            BroadcastFilter bf = get(i);
            pw.println(prefix + "Filter #" + i + ": " + bf);
            bf.dump(pw, p2);
        }
    }
    
    public String toString() {
        return "ReceiverList{"
            + Integer.toHexString(System.identityHashCode(this))
            + " " + pid + " " + (app != null ? app.processName : "(unknown name)")
            + "/" + uid + " client "
            + Integer.toHexString(System.identityHashCode(receiver.asBinder()))
            + "}";
    }
}
