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

import android.content.Intent;
import android.os.IBinder;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Iterator;

/**
 * A particular Intent that has been bound to a Service.
 */
class IntentBindRecord {
    /** The running service. */
    final ServiceRecord service;
    /** The intent that is bound.*/
    final Intent.FilterComparison intent; // 
    /** All apps that have bound to this Intent. */
    final HashMap<ProcessRecord, AppBindRecord> apps
            = new HashMap<ProcessRecord, AppBindRecord>();
    /** Binder published from service. */
    IBinder binder;
    /** Set when we have initiated a request for this binder. */
    boolean requested;
    /** Set when we have received the requested binder. */
    boolean received;
    /** Set when we still need to tell the service all clients are unbound. */
    boolean hasBound;
    /** Set when the service's onUnbind() has asked to be told about new clients. */
    boolean doRebind;
    
    void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + this);
        pw.println(prefix + "service=" + service);
        pw.println(prefix + "intent=" + intent.getIntent());
        pw.println(prefix + "binder=" + binder
                + " requested=" + requested
                + " received=" + received
                + " hasBound=" + hasBound
                + " doRebind=" + doRebind);
        if (apps.size() > 0) {
            pw.println(prefix + "Application Bindings:");
            Iterator<AppBindRecord> it = apps.values().iterator();
            while (it.hasNext()) {
                AppBindRecord a = it.next();
                pw.println(prefix + "Client " + a.client);
                a.dump(pw, prefix + "  ");
            }
        }
    }

    IntentBindRecord(ServiceRecord _service, Intent.FilterComparison _intent) {
        service = _service;
        intent = _intent;
    }

    public String toString() {
        return "IntentBindRecord{"
            + Integer.toHexString(System.identityHashCode(this))
            + " " + service.name.toShortString()
            + ":" + intent + "}";
    }
}
