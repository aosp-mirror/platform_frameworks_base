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
import android.util.Printer;

import java.io.PrintWriter;

class BroadcastFilter extends IntentFilter {
    // Back-pointer to the list this filter is in.
    final ReceiverList receiverList;
    final String packageName;
    final String requiredPermission;
    final int owningUid;
    final int owningUserId;

    BroadcastFilter(IntentFilter _filter, ReceiverList _receiverList,
            String _packageName, String _requiredPermission, int _owningUid, int _userId) {
        super(_filter);
        receiverList = _receiverList;
        packageName = _packageName;
        requiredPermission = _requiredPermission;
        owningUid = _owningUid;
        owningUserId = _userId;
    }
    
    public void dump(PrintWriter pw, String prefix) {
        dumpInReceiverList(pw, new PrintWriterPrinter(pw), prefix);
        receiverList.dumpLocal(pw, prefix);
    }
    
    public void dumpBrief(PrintWriter pw, String prefix) {
        dumpBroadcastFilterState(pw, prefix);
    }
    
    public void dumpInReceiverList(PrintWriter pw, Printer pr, String prefix) {
        super.dump(pr, prefix);
        dumpBroadcastFilterState(pw, prefix);
    }
    
    void dumpBroadcastFilterState(PrintWriter pw, String prefix) {
        if (requiredPermission != null) {
            pw.print(prefix); pw.print("requiredPermission="); pw.println(requiredPermission);
        }
    }
    
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("BroadcastFilter{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(" u");
        sb.append(owningUserId);
        sb.append(' ');
        sb.append(receiverList);
        sb.append('}');
        return sb.toString();
    }
}
