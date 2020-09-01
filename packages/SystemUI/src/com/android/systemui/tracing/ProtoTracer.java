/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.tracing;

import static com.android.systemui.tracing.nano.SystemUiTraceFileProto.MAGIC_NUMBER_H;
import static com.android.systemui.tracing.nano.SystemUiTraceFileProto.MAGIC_NUMBER_L;

import android.content.Context;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.android.systemui.Dumpable;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.shared.tracing.FrameProtoTracer;
import com.android.systemui.shared.tracing.FrameProtoTracer.ProtoTraceParams;
import com.android.systemui.shared.tracing.ProtoTraceable;
import com.android.systemui.tracing.nano.SystemUiTraceEntryProto;
import com.android.systemui.tracing.nano.SystemUiTraceFileProto;
import com.android.systemui.tracing.nano.SystemUiTraceProto;

import com.google.protobuf.nano.MessageNano;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Queue;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Controller for coordinating winscope proto tracing.
 */
@Singleton
public class ProtoTracer implements Dumpable, ProtoTraceParams<MessageNano, SystemUiTraceFileProto,
        SystemUiTraceEntryProto, SystemUiTraceProto> {

    private static final String TAG = "ProtoTracer";
    private static final long MAGIC_NUMBER_VALUE = ((long) MAGIC_NUMBER_H << 32) | MAGIC_NUMBER_L;

    private final Context mContext;
    private final FrameProtoTracer<MessageNano, SystemUiTraceFileProto, SystemUiTraceEntryProto,
            SystemUiTraceProto> mProtoTracer;

    @Inject
    public ProtoTracer(Context context, DumpManager dumpManager) {
        mContext = context;
        mProtoTracer = new FrameProtoTracer<>(this);
        dumpManager.registerDumpable(getClass().getName(), this);
    }

    @Override
    public File getTraceFile() {
        return new File(mContext.getFilesDir(), "sysui_trace.pb");
    }

    @Override
    public SystemUiTraceFileProto getEncapsulatingTraceProto() {
        return new SystemUiTraceFileProto();
    }

    @Override
    public SystemUiTraceEntryProto updateBufferProto(SystemUiTraceEntryProto reuseObj,
            ArrayList<ProtoTraceable<SystemUiTraceProto>> traceables) {
        SystemUiTraceEntryProto proto = reuseObj != null
                ? reuseObj
                : new SystemUiTraceEntryProto();
        proto.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos();
        proto.systemUi = proto.systemUi != null ? proto.systemUi : new SystemUiTraceProto();
        for (ProtoTraceable t : traceables) {
            t.writeToProto(proto.systemUi);
        }
        return proto;
    }

    @Override
    public byte[] serializeEncapsulatingProto(SystemUiTraceFileProto encapsulatingProto,
            Queue<SystemUiTraceEntryProto> buffer) {
        encapsulatingProto.magicNumber = MAGIC_NUMBER_VALUE;
        encapsulatingProto.entry = buffer.toArray(new SystemUiTraceEntryProto[0]);
        return MessageNano.toByteArray(encapsulatingProto);
    }

    @Override
    public byte[] getProtoBytes(MessageNano proto) {
        return MessageNano.toByteArray(proto);
    }

    @Override
    public int getProtoSize(MessageNano proto) {
        return proto.getCachedSize();
    }

    public void start() {
        mProtoTracer.start();
    }

    public void stop() {
        mProtoTracer.stop();
    }

    public boolean isEnabled() {
        return mProtoTracer.isEnabled();
    }

    public void add(ProtoTraceable<SystemUiTraceProto> traceable) {
        mProtoTracer.add(traceable);
    }

    public void remove(ProtoTraceable<SystemUiTraceProto> traceable) {
        mProtoTracer.remove(traceable);
    }

    public void scheduleFrameUpdate() {
        mProtoTracer.scheduleFrameUpdate();
    }

    public void update() {
        mProtoTracer.update();
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("ProtoTracer:");
        pw.print("    "); pw.println("enabled: " + mProtoTracer.isEnabled());
        pw.print("    "); pw.println("usagePct: " + mProtoTracer.getBufferUsagePct());
        pw.print("    "); pw.println("file: " + getTraceFile());
    }
}
