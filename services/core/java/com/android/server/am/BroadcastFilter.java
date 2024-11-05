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

import android.annotation.Nullable;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.content.IntentFilter;
import android.os.UserHandle;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.compat.PlatformCompat;

import dalvik.annotation.optimization.NeverCompile;

import java.io.PrintWriter;

public final class BroadcastFilter extends IntentFilter {
    /**
     * Limit priority values defined by non-system apps to
     * ({@link IntentFilter#SYSTEM_LOW_PRIORITY}, {@link IntentFilter#SYSTEM_HIGH_PRIORITY}).
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = android.os.Build.VERSION_CODES.BASE)
    @VisibleForTesting
    static final long CHANGE_RESTRICT_PRIORITY_VALUES = 371309185L;

    // Back-pointer to the list this filter is in.
    final ReceiverList receiverList;
    final String packageName;
    final String featureId;
    final String receiverId;
    final String requiredPermission;
    final int owningUid;
    final int owningUserId;
    final boolean instantApp;
    final boolean visibleToInstantApp;
    public final boolean exported;
    final int initialPriority;

    BroadcastFilter(IntentFilter _filter, ReceiverList _receiverList,
            String _packageName, String _featureId, String _receiverId, String _requiredPermission,
            int _owningUid, int _userId, boolean _instantApp, boolean _visibleToInstantApp,
            boolean _exported, PlatformCompat platformCompat) {
        super(_filter);
        receiverList = _receiverList;
        packageName = _packageName;
        featureId = _featureId;
        receiverId = _receiverId;
        requiredPermission = _requiredPermission;
        owningUid = _owningUid;
        owningUserId = _userId;
        instantApp = _instantApp;
        visibleToInstantApp = _visibleToInstantApp;
        exported = _exported;
        initialPriority = getPriority();
        setPriority(calculateAdjustedPriority(owningUid, initialPriority, platformCompat));
    }

    public @Nullable String getReceiverClassName() {
        if (receiverId != null) {
            final int index = receiverId.lastIndexOf('@');
            if (index > 0) {
                return receiverId.substring(0, index);
            }
        }
        return null;
    }

    @NeverCompile
    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        super.dumpDebug(proto, BroadcastFilterProto.INTENT_FILTER);
        if (requiredPermission != null) {
            proto.write(BroadcastFilterProto.REQUIRED_PERMISSION, requiredPermission);
        }
        proto.write(BroadcastFilterProto.HEX_HASH, Integer.toHexString(System.identityHashCode(this)));
        proto.write(BroadcastFilterProto.OWNING_USER_ID, owningUserId);
        proto.end(token);
    }

    @NeverCompile
    public void dump(PrintWriter pw, String prefix) {
        dumpInReceiverList(pw, new PrintWriterPrinter(pw), prefix);
        receiverList.dumpLocal(pw, prefix);
    }

    @NeverCompile
    public void dumpBrief(PrintWriter pw, String prefix) {
        dumpBroadcastFilterState(pw, prefix);
    }

    @NeverCompile
    public void dumpInReceiverList(PrintWriter pw, Printer pr, String prefix) {
        super.dump(pr, prefix);
        dumpBroadcastFilterState(pw, prefix);
    }

    @NeverCompile
    void dumpBroadcastFilterState(PrintWriter pw, String prefix) {
        if (requiredPermission != null) {
            pw.print(prefix); pw.print("requiredPermission="); pw.println(requiredPermission);
        }
        if (initialPriority != getPriority()) {
            pw.print(prefix); pw.print("initialPriority="); pw.println(initialPriority);
        }
    }

    @VisibleForTesting
    static int calculateAdjustedPriority(int owningUid, int priority,
            PlatformCompat platformCompat) {
        if (!Flags.restrictPriorityValues()) {
            return priority;
        }
        if (!platformCompat.isChangeEnabledByUidInternalNoLogging(
                CHANGE_RESTRICT_PRIORITY_VALUES, owningUid)) {
            return priority;
        }
        if (!UserHandle.isCore(owningUid)) {
            if (priority >= SYSTEM_HIGH_PRIORITY) {
                return SYSTEM_HIGH_PRIORITY - 1;
            } else if (priority <= SYSTEM_LOW_PRIORITY) {
                return SYSTEM_LOW_PRIORITY + 1;
            }
        }
        return priority;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("BroadcastFilter{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(' ');
        sb.append(owningUid);
        sb.append("/u");
        sb.append(owningUserId);
        sb.append(' ');
        sb.append(receiverList);
        sb.append('}');
        return sb.toString();
    }
}
