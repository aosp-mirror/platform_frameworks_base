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

import android.content.IntentFilter;
import android.util.PrintWriterPrinter;

import java.io.PrintWriter;

class BroadcastFilter extends IntentFilter {
    // Back-pointer to the list this filter is in.
    final ReceiverList receiverList;
    final String requiredPermission;

    BroadcastFilter(IntentFilter _filter, ReceiverList _receiverList,
            String _requiredPermission) {
        super(_filter);
        receiverList = _receiverList;
        requiredPermission = _requiredPermission;
    }
    
    public void dumpLocal(PrintWriter pw, String prefix) {
        super.dump(new PrintWriterPrinter(pw), prefix);
    }
    
    public void dump(PrintWriter pw, String prefix) {
        dumpLocal(pw, prefix);
        pw.println(prefix + "requiredPermission=" + requiredPermission);
        receiverList.dumpLocal(pw, prefix);
    }
    
    public String toString() {
        return "BroadcastFilter{"
            + Integer.toHexString(System.identityHashCode(this))
            + " " + receiverList + "}";
    }
}
