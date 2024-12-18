/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.protolog;

import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.GROUPS;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.Group.ID;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.Group.NAME;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.Group.TAG;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MESSAGES;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData.GROUP_ID;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData.LEVEL;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData.LOCATION;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData.MESSAGE;
import static android.internal.perfetto.protos.TracePacketOuterClass.TracePacket.PROTOLOG_VIEWER_CONFIG;
import static android.internal.perfetto.protos.TracePacketOuterClass.TracePacket.TIMESTAMP;

import android.annotation.NonNull;
import android.internal.perfetto.protos.Protolog;
import android.os.SystemClock;
import android.util.Log;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;

import java.io.IOException;

public class Utils {
    private static final String LOG_TAG = "ProtoLogUtils";

    /**
     * Dump the viewer config provided by the input stream to the target datasource.
     * @param dataSource The datasource to dump the ProtoLog viewer config to.
     * @param viewerConfigInputStreamProvider The InputStream that provided the proto viewer config.
     */
    public static void dumpViewerConfig(@NonNull ProtoLogDataSource dataSource,
            @NonNull ViewerConfigInputStreamProvider viewerConfigInputStreamProvider) {
        dataSource.trace(ctx -> {
            try {
                ProtoInputStream pis = viewerConfigInputStreamProvider.getInputStream();

                final ProtoOutputStream os = ctx.newTracePacket();

                os.write(TIMESTAMP, SystemClock.elapsedRealtimeNanos());

                final long outProtologViewerConfigToken = os.start(PROTOLOG_VIEWER_CONFIG);
                while (pis.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                    if (pis.getFieldNumber() == (int) MESSAGES) {
                        writeViewerConfigMessage(pis, os);
                    }

                    if (pis.getFieldNumber() == (int) GROUPS) {
                        writeViewerConfigGroup(pis, os);
                    }
                }

                os.end(outProtologViewerConfigToken);
            } catch (IOException e) {
                Log.e(LOG_TAG, "Failed to read ProtoLog viewer config to dump to datasource", e);
            }
        });
    }

    private static void writeViewerConfigGroup(
            @NonNull ProtoInputStream pis, @NonNull ProtoOutputStream os) throws IOException {
        final long inGroupToken = pis.start(GROUPS);
        final long outGroupToken = os.start(GROUPS);

        while (pis.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (pis.getFieldNumber()) {
                case (int) ID:
                    int id = pis.readInt(ID);
                    os.write(ID, id);
                    break;
                case (int) NAME:
                    String name = pis.readString(NAME);
                    os.write(NAME, name);
                    break;
                case (int) TAG:
                    String tag = pis.readString(TAG);
                    os.write(TAG, tag);
                    break;
                default:
                    throw new RuntimeException(
                            "Unexpected field id " + pis.getFieldNumber());
            }
        }

        pis.end(inGroupToken);
        os.end(outGroupToken);
    }

    private static void writeViewerConfigMessage(
            ProtoInputStream pis, ProtoOutputStream os) throws IOException {
        final long inMessageToken = pis.start(MESSAGES);
        final long outMessagesToken = os.start(MESSAGES);

        while (pis.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (pis.getFieldNumber()) {
                case (int) Protolog.ProtoLogViewerConfig.MessageData.MESSAGE_ID:
                    os.write(Protolog.ProtoLogViewerConfig.MessageData.MESSAGE_ID,
                            pis.readLong(Protolog.ProtoLogViewerConfig.MessageData.MESSAGE_ID));
                    break;
                case (int) MESSAGE:
                    os.write(MESSAGE, pis.readString(MESSAGE));
                    break;
                case (int) LEVEL:
                    os.write(LEVEL, pis.readInt(LEVEL));
                    break;
                case (int) GROUP_ID:
                    os.write(GROUP_ID, pis.readInt(GROUP_ID));
                    break;
                case (int) LOCATION:
                    os.write(LOCATION, pis.readString(LOCATION));
                    break;
                default:
                    throw new RuntimeException(
                            "Unexpected field id " + pis.getFieldNumber());
            }
        }

        pis.end(inMessageToken);
        os.end(outMessagesToken);
    }

}
