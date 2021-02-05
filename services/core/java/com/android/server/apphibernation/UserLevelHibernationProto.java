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
 * Reads and writes protos for {@link UserLevelState} hiberation states.
 */
final class UserLevelHibernationProto implements ProtoReadWriter<List<UserLevelState>> {
    private static final String TAG = "UserLevelHibernationProtoReadWriter";

    @Override
    public void writeToProto(@NonNull ProtoOutputStream stream,
            @NonNull List<UserLevelState> data) {
        for (int i = 0, size = data.size(); i < size; i++) {
            long token = stream.start(UserLevelHibernationStatesProto.HIBERNATION_STATE);
            UserLevelState state = data.get(i);
            stream.write(UserLevelHibernationStateProto.PACKAGE_NAME, state.packageName);
            stream.write(UserLevelHibernationStateProto.HIBERNATED, state.hibernated);
            stream.end(token);
        }
    }

    @Override
    public @Nullable List<UserLevelState> readFromProto(@NonNull ProtoInputStream stream)
            throws IOException {
        List<UserLevelState> list = new ArrayList<>();
        while (stream.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            if (stream.getFieldNumber()
                    != (int) UserLevelHibernationStatesProto.HIBERNATION_STATE) {
                continue;
            }
            UserLevelState state = new UserLevelState();
            long token = stream.start(UserLevelHibernationStatesProto.HIBERNATION_STATE);
            while (stream.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                switch (stream.getFieldNumber()) {
                    case (int) UserLevelHibernationStateProto.PACKAGE_NAME:
                        state.packageName =
                                stream.readString(UserLevelHibernationStateProto.PACKAGE_NAME);
                        break;
                    case (int) UserLevelHibernationStateProto.HIBERNATED:
                        state.hibernated =
                                stream.readBoolean(UserLevelHibernationStateProto.HIBERNATED);
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
