/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.apphibernation;


import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Slog;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes protos for {@link GlobalLevelState} hiberation states.
 */
final class GlobalLevelHibernationProto implements ProtoReadWriter<List<GlobalLevelState>> {
    private static final String TAG = "GlobalLevelHibernationProtoReadWriter";

    @Override
    public void writeToProto(@NonNull ProtoOutputStream stream,
            @NonNull List<GlobalLevelState> data) {
        for (int i = 0, size = data.size(); i < size; i++) {
            long token = stream.start(GlobalLevelHibernationStatesProto.HIBERNATION_STATE);
            GlobalLevelState state = data.get(i);
            stream.write(GlobalLevelHibernationStateProto.PACKAGE_NAME, state.packageName);
            stream.write(GlobalLevelHibernationStateProto.HIBERNATED, state.hibernated);
            stream.write(GlobalLevelHibernationStateProto.SAVED_BYTE, state.savedByte);
            stream.end(token);
        }
    }

    @Override
    public @Nullable List<GlobalLevelState> readFromProto(@NonNull ProtoInputStream stream)
            throws IOException {
        List<GlobalLevelState> list = new ArrayList<>();
        while (stream.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            if (stream.getFieldNumber()
                    != (int) GlobalLevelHibernationStatesProto.HIBERNATION_STATE) {
                continue;
            }
            GlobalLevelState state = new GlobalLevelState();
            long token = stream.start(GlobalLevelHibernationStatesProto.HIBERNATION_STATE);
            while (stream.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                switch (stream.getFieldNumber()) {
                    case (int) GlobalLevelHibernationStateProto.PACKAGE_NAME:
                        state.packageName =
                                stream.readString(GlobalLevelHibernationStateProto.PACKAGE_NAME);
                        break;
                    case (int) GlobalLevelHibernationStateProto.HIBERNATED:
                        state.hibernated =
                                stream.readBoolean(GlobalLevelHibernationStateProto.HIBERNATED);
                        break;
                    case (int) GlobalLevelHibernationStateProto.SAVED_BYTE:
                        state.savedByte =
                                stream.readLong(GlobalLevelHibernationStateProto.SAVED_BYTE);
                        break;
                    default:
                        Slog.w(TAG, "Undefined field in proto: " + stream.getFieldNumber());
                }
            }
            stream.end(token);
            list.add(state);
        }
        return list;
    }
}
