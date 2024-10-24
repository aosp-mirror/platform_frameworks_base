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

package com.android.server.pinner;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;

@VisibleForTesting
public final class PinnedFile implements AutoCloseable {
    private long mAddress;
    final long mapSize;
    final String fileName;
    public final long bytesPinned;

    // Whether this file was pinned using a pinlist
    boolean used_pinlist;

    // User defined group name for pinner accounting
    String groupName = "";
    ArrayList<PinnedFile> pinnedDeps = new ArrayList<>();

    public PinnedFile(long address, long mapSize, String fileName, long bytesPinned) {
        mAddress = address;
        this.mapSize = mapSize;
        this.fileName = fileName;
        this.bytesPinned = bytesPinned;
    }

    @Override
    public void close() {
        if (mAddress >= 0) {
            PinnerUtils.safeMunmap(mAddress, mapSize);
            mAddress = -1;
        }
        for (PinnedFile dep : pinnedDeps) {
            if (dep != null) {
                dep.close();
            }
        }
    }

    @Override
    public void finalize() {
        close();
    }
}