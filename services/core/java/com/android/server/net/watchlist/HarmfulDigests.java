/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.net.watchlist;

import com.android.internal.util.HexDump;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Helper class to store all harmful digests in memory.
 * TODO: Optimize memory usage using byte array with binary search.
 */
class HarmfulDigests {

    private final Set<String> mDigestSet;

    HarmfulDigests(List<byte[]> digests) {
        final HashSet<String> tmpDigestSet = new HashSet<>();
        final int size = digests.size();
        for (int i = 0; i < size; i++) {
            tmpDigestSet.add(HexDump.toHexString(digests.get(i)));
        }
        mDigestSet = Collections.unmodifiableSet(tmpDigestSet);
    }

    public boolean contains(byte[] digest) {
        return mDigestSet.contains(HexDump.toHexString(digest));
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        for (String digest : mDigestSet) {
            pw.println(digest);
        }
        pw.println("");
    }
}
