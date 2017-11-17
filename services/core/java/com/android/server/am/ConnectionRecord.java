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

import android.app.IServiceConnection;
import android.app.PendingIntent;
import android.content.Context;
import android.util.proto.ProtoOutputStream;

import com.android.server.am.proto.ConnectionRecordProto;

import java.io.PrintWriter;

/**
 * Description of a single binding to a service.
 */
final class ConnectionRecord {
    final AppBindRecord binding;    // The application/service binding.
    final ActivityRecord activity;  // If non-null, the owning activity.
    final IServiceConnection conn;  // The client connection.
    final int flags;                // Binding options.
    final int clientLabel;          // String resource labeling this client.
    final PendingIntent clientIntent; // How to launch the client.
    String stringName;              // Caching of toString.
    boolean serviceDead;            // Well is it?
    
    void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "binding=" + binding);
        if (activity != null) {
            pw.println(prefix + "activity=" + activity);
        }
        pw.println(prefix + "conn=" + conn.asBinder()
                + " flags=0x" + Integer.toHexString(flags));
    }
    
    ConnectionRecord(AppBindRecord _binding, ActivityRecord _activity,
               IServiceConnection _conn, int _flags,
               int _clientLabel, PendingIntent _clientIntent) {
        binding = _binding;
        activity = _activity;
        conn = _conn;
        flags = _flags;
        clientLabel = _clientLabel;
        clientIntent = _clientIntent;
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
        if ((flags&Context.BIND_VISIBLE) != 0) {
            sb.append("VIS ");
        }
        if ((flags&Context.BIND_SHOWING_UI) != 0) {
            sb.append("UI ");
        }
        if ((flags&Context.BIND_NOT_VISIBLE) != 0) {
            sb.append("!VIS ");
        }
        if (serviceDead) {
            sb.append("DEAD ");
        }
        sb.append(binding.service.shortName);
        sb.append(":@");
        sb.append(Integer.toHexString(System.identityHashCode(conn.asBinder())));
        sb.append('}');
        return stringName = sb.toString();
    }

    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        if (binding == null) return; // if binding is null, don't write data, something is wrong.
        long token = proto.start(fieldId);
        proto.write(ConnectionRecordProto.HEX_HASH,
                Integer.toHexString(System.identityHashCode(this)));
        if (binding.client != null) {
            proto.write(ConnectionRecordProto.USER_ID, binding.client.userId);
        }
        if ((flags&Context.BIND_AUTO_CREATE) != 0) {
            proto.write(ConnectionRecordProto.FLAGS, ConnectionRecordProto.AUTO_CREATE);
        }
        if ((flags&Context.BIND_DEBUG_UNBIND) != 0) {
            proto.write(ConnectionRecordProto.FLAGS, ConnectionRecordProto.DEBUG_UNBIND);
        }
        if ((flags&Context.BIND_NOT_FOREGROUND) != 0) {
            proto.write(ConnectionRecordProto.FLAGS, ConnectionRecordProto.NOT_FG);
        }
        if ((flags&Context.BIND_IMPORTANT_BACKGROUND) != 0) {
            proto.write(ConnectionRecordProto.FLAGS, ConnectionRecordProto.IMPORTANT_BG);
        }
        if ((flags&Context.BIND_ABOVE_CLIENT) != 0) {
            proto.write(ConnectionRecordProto.FLAGS, ConnectionRecordProto.ABOVE_CLIENT);
        }
        if ((flags&Context.BIND_ALLOW_OOM_MANAGEMENT) != 0) {
            proto.write(ConnectionRecordProto.FLAGS, ConnectionRecordProto.ALLOW_OOM_MANAGEMENT);
        }
        if ((flags&Context.BIND_WAIVE_PRIORITY) != 0) {
            proto.write(ConnectionRecordProto.FLAGS, ConnectionRecordProto.WAIVE_PRIORITY);
        }
        if ((flags&Context.BIND_IMPORTANT) != 0) {
            proto.write(ConnectionRecordProto.FLAGS, ConnectionRecordProto.IMPORTANT);
        }
        if ((flags&Context.BIND_ADJUST_WITH_ACTIVITY) != 0) {
            proto.write(ConnectionRecordProto.FLAGS, ConnectionRecordProto.ADJUST_WITH_ACTIVITY);
        }
        if ((flags&Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE) != 0) {
            proto.write(ConnectionRecordProto.FLAGS, ConnectionRecordProto.FG_SERVICE_WHILE_WAKE);
        }
        if ((flags&Context.BIND_FOREGROUND_SERVICE) != 0) {
            proto.write(ConnectionRecordProto.FLAGS, ConnectionRecordProto.FG_SERVICE);
        }
        if ((flags&Context.BIND_TREAT_LIKE_ACTIVITY) != 0) {
            proto.write(ConnectionRecordProto.FLAGS, ConnectionRecordProto.TREAT_LIKE_ACTIVITY);
        }
        if ((flags&Context.BIND_VISIBLE) != 0) {
            proto.write(ConnectionRecordProto.FLAGS, ConnectionRecordProto.VISIBLE);
        }
        if ((flags&Context.BIND_SHOWING_UI) != 0) {
            proto.write(ConnectionRecordProto.FLAGS, ConnectionRecordProto.SHOWING_UI);
        }
        if ((flags&Context.BIND_NOT_VISIBLE) != 0) {
            proto.write(ConnectionRecordProto.FLAGS, ConnectionRecordProto.NOT_VISIBLE);
        }
        if (serviceDead) {
            proto.write(ConnectionRecordProto.FLAGS, ConnectionRecordProto.DEAD);
        }
        if (binding.service != null) {
            proto.write(ConnectionRecordProto.SERVICE_NAME, binding.service.shortName);
        }
        if (conn != null) {
            proto.write(ConnectionRecordProto.CONN_HEX_HASH,
                    Integer.toHexString(System.identityHashCode(conn.asBinder())));
        }
        proto.end(token);
    }
}
