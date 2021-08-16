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

import android.annotation.Nullable;
import android.app.IServiceConnection;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
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
final class ConnectionRecord {
    final AppBindRecord binding;    // The application/service binding.
    final ActivityServiceConnectionsHolder<ConnectionRecord> activity;  // If non-null, the owning activity.
    final IServiceConnection conn;  // The client connection.
    final int flags;                // Binding options.
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
            Context.BIND_VISIBLE,
            Context.BIND_SHOWING_UI,
            Context.BIND_NOT_VISIBLE,
            Context.BIND_NOT_PERCEPTIBLE,
            Context.BIND_INCLUDE_CAPABILITIES,
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
    };

    void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "binding=" + binding);
        if (activity != null) {
            activity.dump(pw, prefix);
        }
        pw.println(prefix + "conn=" + conn.asBinder()
                + " flags=0x" + Integer.toHexString(flags));
    }

    ConnectionRecord(AppBindRecord _binding,
            ActivityServiceConnectionsHolder<ConnectionRecord> _activity,
            IServiceConnection _conn, int _flags,
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

    public boolean hasFlag(final int flag) {
        return (flags & flag) != 0;
    }

    public boolean notHasFlag(final int flag) {
        return (flags & flag) == 0;
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

    public void trackProcState(int procState, int seq, long now) {
        if (association != null) {
            synchronized (mProcStatsLock) {
                association.trackProcState(procState, seq, now);
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
        if ((flags&Context.BIND_AUTO_CREATE) != 0) {
            sb.append("CR ");
        }
        if ((flags&Context.BIND_DEBUG_UNBIND) != 0) {
            sb.append("DBG ");
        }
        if ((flags&Context.BIND_NOT_FOREGROUND) != 0) {
            sb.append("!FG ");
        }
        if ((flags&Context.BIND_IMPORTANT_BACKGROUND) != 0) {
            sb.append("IMPB ");
        }
        if ((flags&Context.BIND_ABOVE_CLIENT) != 0) {
            sb.append("ABCLT ");
        }
        if ((flags&Context.BIND_ALLOW_OOM_MANAGEMENT) != 0) {
            sb.append("OOM ");
        }
        if ((flags&Context.BIND_WAIVE_PRIORITY) != 0) {
            sb.append("WPRI ");
        }
        if ((flags&Context.BIND_IMPORTANT) != 0) {
            sb.append("IMP ");
        }
        if ((flags&Context.BIND_ADJUST_WITH_ACTIVITY) != 0) {
            sb.append("WACT ");
        }
        if ((flags&Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE) != 0) {
            sb.append("FGSA ");
        }
        if ((flags&Context.BIND_FOREGROUND_SERVICE) != 0) {
            sb.append("FGS ");
        }
        if ((flags&Context.BIND_TREAT_LIKE_ACTIVITY) != 0) {
            sb.append("LACT ");
        }
        if ((flags & Context.BIND_SCHEDULE_LIKE_TOP_APP) != 0) {
            sb.append("SLTA ");
        }
        if ((flags&Context.BIND_VISIBLE) != 0) {
            sb.append("VIS ");
        }
        if ((flags&Context.BIND_SHOWING_UI) != 0) {
            sb.append("UI ");
        }
        if ((flags&Context.BIND_NOT_VISIBLE) != 0) {
            sb.append("!VIS ");
        }
        if ((flags & Context.BIND_NOT_PERCEPTIBLE) != 0) {
            sb.append("!PRCP ");
        }
        if ((flags & Context.BIND_INCLUDE_CAPABILITIES) != 0) {
            sb.append("CAPS ");
        }
        if (serviceDead) {
            sb.append("DEAD ");
        }
        sb.append(binding.service.shortInstanceName);
        sb.append(":@");
        sb.append(Integer.toHexString(System.identityHashCode(conn.asBinder())));
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
