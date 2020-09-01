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

package com.android.server.utils.quota;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.proto.ProtoOutputStream;
import android.util.quota.UptcProto;

import java.util.Objects;

/**
 * A data object that represents a userId-packageName-tag combination (UPTC). The tag can be any
 * desired String.
 */
final class Uptc {
    public final int userId;
    @NonNull
    public final String packageName;
    @Nullable
    public final String tag;

    private final int mHash;

    /** Construct a new Uptc with the specified values. */
    Uptc(int userId, @NonNull String packageName, @Nullable String tag) {
        this.userId = userId;
        this.packageName = packageName;
        this.tag = tag;

        mHash = 31 * userId
                + 31 * packageName.hashCode()
                + tag == null ? 0 : (31 * tag.hashCode());
    }

    @Override
    public String toString() {
        return string(userId, packageName, tag);
    }

    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);

        proto.write(UptcProto.USER_ID, userId);
        proto.write(UptcProto.NAME, packageName);
        proto.write(UptcProto.TAG, tag);

        proto.end(token);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof Uptc) {
            final Uptc other = (Uptc) obj;
            return userId == other.userId
                    && Objects.equals(packageName, other.packageName)
                    && Objects.equals(tag, other.tag);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return mHash;
    }

    /** Standardize the output of a UPTC. */
    static String string(int userId, @NonNull String packageName, @Nullable String tag) {
        return "<" + userId + ">" + packageName + (tag == null ? "" : ("::" + tag));
    }
}
