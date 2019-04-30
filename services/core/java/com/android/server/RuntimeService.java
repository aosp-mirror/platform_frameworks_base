/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server;

import android.content.Context;
import android.os.Binder;
import android.service.runtime.DebugEntryProto;
import android.service.runtime.RuntimeServiceInfoProto;
import android.util.proto.ProtoOutputStream;

import com.android.i18n.timezone.DebugInfo;
import com.android.i18n.timezone.I18nModuleDebug;
import com.android.internal.util.DumpUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * This service exists only as a "dumpsys" target which reports information about the status of the
 * runtime and related libraries.
 */
public class RuntimeService extends Binder {

    private static final String TAG = "RuntimeService";

    private final Context mContext;

    public RuntimeService(Context context) {
        mContext = context;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpAndUsageStatsPermission(mContext, TAG, pw)) {
            return;
        }

        boolean protoFormat = hasOption(args, "--proto");
        ProtoOutputStream proto = null;

        DebugInfo i18nLibraryDebugInfo = I18nModuleDebug.getDebugInfo();

        if (protoFormat) {
            proto = new ProtoOutputStream(fd);
            reportTimeZoneInfoProto(i18nLibraryDebugInfo, proto);
        } else {
            reportTimeZoneInfo(i18nLibraryDebugInfo, pw);
        }

        if (protoFormat) {
            proto.flush();
        }
    }

    /** Returns {@code true} if {@code args} contains {@code arg}. */
    private static boolean hasOption(String[] args, String arg) {
        for (String opt : args) {
            if (arg.equals(opt)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Prints {@code coreLibraryDebugInfo} to {@code pw}.
     *
     * <p>If you change this method, make sure to modify
     * {@link #reportTimeZoneInfoProto(DebugInfo, ProtoOutputStream)} as well.
     */
    private static void reportTimeZoneInfo(DebugInfo coreLibraryDebugInfo,
            PrintWriter pw) {
        pw.println("Core Library Debug Info: ");
        for (DebugInfo.DebugEntry debugEntry : coreLibraryDebugInfo.getDebugEntries()) {
            pw.print(debugEntry.getKey());
            pw.print(": \"");
            pw.print(debugEntry.getStringValue());
            pw.println("\"");
        }
    }

    /**
     * Adds {@code coreLibraryDebugInfo} to {@code protoStream}.
     *
     * <p>If you change this method, make sure to modify
     * {@link #reportTimeZoneInfo(DebugInfo, PrintWriter)}.
     */
    private static void reportTimeZoneInfoProto(
            DebugInfo coreLibraryDebugInfo, ProtoOutputStream protoStream) {
        for (DebugInfo.DebugEntry debugEntry : coreLibraryDebugInfo.getDebugEntries()) {
            long entryToken = protoStream.start(RuntimeServiceInfoProto.DEBUG_ENTRY);
            protoStream.write(DebugEntryProto.KEY, debugEntry.getKey());
            protoStream.write(DebugEntryProto.STRING_VALUE, debugEntry.getStringValue());
            protoStream.end(entryToken);
        }
    }
}
