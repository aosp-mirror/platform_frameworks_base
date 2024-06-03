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

import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ProcessList.UNKNOWN_ADJ;

import android.annotation.Nullable;
import android.app.IServiceConnection;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.os.SystemClock;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.util.proto.ProtoUtils;

import com.android.internal.app.procstats.AssociationState;
import com.android.internal.app.procstats.ProcessStats;
import com.android.server.wm.ActivityServiceConnectionsHolder;

import java.io.PrintWriter;

/**
 * Description of a single binding to a service.
 */
final class ConnectionRecord implements OomAdjusterModernImpl.Connection{
    final AppBindRecord binding;    // The application/service binding.
    final ActivityServiceConnectionsHolder<ConnectionRecord> activity;  // If non-null, the owning activity.
    final IServiceConnection conn;  // The client connection.
    private final long flags;                // Binding options.
    final int clientLabel;          // String resource labeling this client.
    final PendingIntent clientIntent; // How to launch the client.
    final int clientUid;            // The identity of this connection's client
    final String clientProcessName; // The source process of this connection's client
    final String clientPackageName; // The source package of this connection's client
    public AssociationState.SourceState association; // Association tracking
    String stringName;              // Caching of toString.
    boolean serviceDead;            // Well is it?
    private Object mProcStatsLock;  // Internal lock for accessing AssociationState
    /**
     * If the connection was made against an alias, then the alias conponent name. Otherwise, null.
     * We return this component name to the client.
     */
    @Nullable
    final ComponentName aliasComponent;

    // Please keep the following two enum list synced.
    private static final int[] BIND_ORIG_ENUMS = new int[] {
            Context.BIND_AUTO_CREATE,
            Context.BIND_DEBUG_UNBIND,
            Context.BIND_NOT_FOREGROUND,
            Context.BIND_IMPORTANT_BACKGROUND,
            Context.BIND_ABOVE_CLIENT,
            Context.BIND_ALLOW_OOM_MANAGEMENT,
            Context.BIND_WAIVE_PRIORITY,
            Context.BIND_IMPORTANT,
            Context.BIND_ADJUST_WITH_ACTIVITY,
            Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE,
            Context.BIND_FOREGROUND_SERVICE,
            Context.BIND_TREAT_LIKE_ACTIVITY,
            Context.BIND_TREAT_LIKE_VISIBLE_FOREGROUND_SERVICE,
            Context.BIND_SHOWING_UI,
            Context.BIND_NOT_VISIBLE,
            Context.BIND_NOT_PERCEPTIBLE,
            Context.BIND_INCLUDE_CAPABILITIES,
            Context.BIND_ALLOW_ACTIVITY_STARTS,
    };
    private static final int[] BIND_PROTO_ENUMS = new int[] {
            ConnectionRecordProto.AUTO_CREATE,
            ConnectionRecordProto.DEBUG_UNBIND,
            ConnectionRecordProto.NOT_FG,
            ConnectionRecordProto.IMPORTANT_BG,
            ConnectionRecordProto.ABOVE_CLIENT,
            ConnectionRecordProto.ALLOW_OOM_MANAGEMENT,
            ConnectionRecordProto.WAIVE_PRIORITY,
            ConnectionRecordProto.IMPORTANT,
            ConnectionRecordProto.ADJUST_WITH_ACTIVITY,
            ConnectionRecordProto.FG_SERVICE_WHILE_AWAKE,
            ConnectionRecordProto.FG_SERVICE,
            ConnectionRecordProto.TREAT_LIKE_ACTIVITY,
            ConnectionRecordProto.VISIBLE,
            ConnectionRecordProto.SHOWING_UI,
            ConnectionRecordProto.NOT_VISIBLE,
            ConnectionRecordProto.NOT_PERCEPTIBLE,
            ConnectionRecordProto.INCLUDE_CAPABILITIES,
            ConnectionRecordProto.ALLOW_ACTIVITY_STARTS,
    };

    void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "binding=" + binding);
        if (activity != null) {
            activity.dump(pw, prefix);
        }
        pw.println(prefix + "conn=" + conn.asBinder()
                + " flags=0x" + Long.toHexString(flags));
    }

    ConnectionRecord(AppBindRecord _binding,
            ActivityServiceConnectionsHolder<ConnectionRecord> _activity,
            IServiceConnection _conn, long _flags,
            int _clientLabel, PendingIntent _clientIntent,
            int _clientUid, String _clientProcessName, String _clientPackageName,
            ComponentName _aliasComponent) {
        binding = _binding;
        activity = _activity;
        conn = _conn;
        flags = _flags;
        clientLabel = _clientLabel;
        clientIntent = _clientIntent;
        clientUid = _clientUid;
        clientProcessName = _clientProcessName;
        clientPackageName = _clientPackageName;
        aliasComponent = _aliasComponent;
    }

    @Override
    public void computeHostOomAdjLSP(OomAdjuster oomAdjuster, ProcessRecord host,
            ProcessRecord client, long now, ProcessRecord topApp, boolean doingAll,
            int oomAdjReason, int cachedAdj) {
        oomAdjuster.computeServiceHostOomAdjLSP(this, host, client, now, topApp, doingAll, false,
                false, oomAdjReason, UNKNOWN_ADJ, false, false);
    }

    @Override
    public boolean canAffectCapabilities() {
        return hasFlag(Context.BIND_INCLUDE_CAPABILITIES
                | Context.BIND_BYPASS_USER_NETWORK_RESTRICTIONS);
    }


    public long getFlags() {
        return flags;
    }

    public boolean hasFlag(final int flag) {
        return (flags & Integer.toUnsignedLong(flag)) != 0;
    }

    public boolean hasFlag(final long flag) {
        return (flags & flag) != 0;
    }

    public boolean notHasFlag(final int flag) {
        return !hasFlag(flag);
    }

    public boolean notHasFlag(final long flag) {
        return !hasFlag(flag);
    }

    public void startAssociationIfNeeded() {
        // If we don't already have an active association, create one...  but only if this
        // is an association between two different processes.
        if (ActivityManagerService.TRACK_PROCSTATS_ASSOCIATIONS
                && association == null && binding.service.app != null
                && (binding.service.appInfo.uid != clientUid
                        || !binding.service.processName.equals(clientProcessName))) {
            ProcessStats.ProcessStateHolder holder = binding.service.app.getPkgList().get(
                    binding.service.instanceName.getPackageName());
            if (holder == null) {
                Slog.wtf(TAG_AM, "No package in referenced service "
                        + binding.service.shortInstanceName + ": proc=" + binding.service.app);
            } else if (holder.pkg == null) {
                Slog.wtf(TAG_AM, "Inactive holder in referenced service "
                        + binding.service.shortInstanceName + ": proc=" + binding.service.app);
            } else {
                mProcStatsLock = binding.service.app.mService.mProcessStats.mLock;
                synchronized (mProcStatsLock) {
                    association = holder.pkg.getAssociationStateLocked(holder.state,
                            binding.service.instanceName.getClassName()).startSource(clientUid,
                            clientProcessName, clientPackageName);
                }
            }
        }
    }

    public void trackProcState(int procState, int seq) {
        if (association != null) {
            synchronized (mProcStatsLock) {
                association.trackProcState(procState, seq, SystemClock.uptimeMillis());
            }
        }
    }

    public void stopAssociation() {
        if (association != null) {
            synchronized (mProcStatsLock) {
                association.stop();
            }
            association = null;
        }
    }

    public String toString() {
        if (stringName != null) {
            return stringName;
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("ConnectionRecord{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(" u");
        sb.append(binding.client.userId);
        sb.append(' ');
        if (hasFlag(Context.BIND_AUTO_CREATE)) {
            sb.append("CR ");
        }
        if (hasFlag(Context.BIND_DEBUG_UNBIND)) {
            sb.append("DBG ");
        }
        if (hasFlag(Context.BIND_NOT_FOREGROUND)) {
            sb.append("!FG ");
        }
        if (hasFlag(Context.BIND_IMPORTANT_BACKGROUND)) {
            sb.append("IMPB ");
        }
        if (hasFlag(Context.BIND_ABOVE_CLIENT)) {
            sb.append("ABCLT ");
        }
        if (hasFlag(Context.BIND_ALLOW_OOM_MANAGEMENT)) {
            sb.append("OOM ");
        }
        if (hasFlag(Context.BIND_WAIVE_PRIORITY)) {
            sb.append("WPRI ");
        }
        if (hasFlag(Context.BIND_IMPORTANT)) {
            sb.append("IMP ");
        }
        if (hasFlag(Context.BIND_ADJUST_WITH_ACTIVITY)) {
            sb.append("WACT ");
        }
        if (hasFlag(Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE)) {
            sb.append("FGSA ");
        }
        if (hasFlag(Context.BIND_FOREGROUND_SERVICE)) {
            sb.append("FGS ");
        }
        if (hasFlag(Context.BIND_TREAT_LIKE_ACTIVITY)) {
            sb.append("LACT ");
        }
        if (hasFlag(Context.BIND_SCHEDULE_LIKE_TOP_APP)) {
            sb.append("SLTA ");
        }
        if (hasFlag(Context.BIND_TREAT_LIKE_VISIBLE_FOREGROUND_SERVICE)) {
            sb.append("VFGS ");
        }
        if (hasFlag(Context.BIND_SHOWING_UI)) {
            sb.append("UI ");
        }
        if (hasFlag(Context.BIND_NOT_VISIBLE)) {
            sb.append("!VIS ");
        }
        if (hasFlag(Context.BIND_NOT_PERCEPTIBLE)) {
            sb.append("!PRCP ");
        }
        if (hasFlag(Context.BIND_ALLOW_ACTIVITY_STARTS)) {
            sb.append("BALF ");
        }
        if (hasFlag(Context.BIND_INCLUDE_CAPABILITIES)) {
            sb.append("CAPS ");
        }
        if (serviceDead) {
            sb.append("DEAD ");
        }
        sb.append(binding.service.shortInstanceName);
        sb.append(":@");
        sb.append(Integer.toHexString(System.identityHashCode(conn.asBinder())));
        sb.append(" flags=0x" + Long.toHexString(flags));
        sb.append('}');
        return stringName = sb.toString();
    }

    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        if (binding == null) return; // if binding is null, don't write data, something is wrong.
        long token = proto.start(fieldId);
        proto.write(ConnectionRecordProto.HEX_HASH,
                Integer.toHexString(System.identityHashCode(this)));
        if (binding.client != null) {
            proto.write(ConnectionRecordProto.USER_ID, binding.client.userId);
        }
        ProtoUtils.writeBitWiseFlagsToProtoEnum(proto, ConnectionRecordProto.FLAGS,
                flags, BIND_ORIG_ENUMS, BIND_PROTO_ENUMS);
        if (serviceDead) {
            proto.write(ConnectionRecordProto.FLAGS, ConnectionRecordProto.DEAD);
        }
        if (binding.service != null) {
            proto.write(ConnectionRecordProto.SERVICE_NAME, binding.service.shortInstanceName);
        }
        proto.end(token);
    }
}
