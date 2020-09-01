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
import android.util.proto.ProtoOutputStream;
import android.util.quota.CategoryProto;

/**
 * A category as defined by the (system) client. Categories are used to put UPTCs in different
 * groups. A sample group of Categories could be the various App Standby buckets or foreground vs
 * background.
 *
 * @see Uptc
 */
public final class Category {
    /**
     * A {@link Category} that can be used if every app should be treated the same (given the same
     * category).
     */
    public static final Category SINGLE_CATEGORY = new Category("SINGLE");

    @NonNull
    private final String mName;

    private final int mHash;

    /** Construct a new Category with the specified name. */
    public Category(@NonNull String name) {
        mName = name;
        mHash = name.hashCode();
    }

    /** {@inheritDoc} */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other instanceof Category) {
            return this.mName.equals(((Category) other).mName);
        }
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
        return mHash;
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return "Category{" + mName + "}";
    }

    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(CategoryProto.NAME, mName);
        proto.end(token);
    }
}
