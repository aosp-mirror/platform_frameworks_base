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

package com.android.server.backup.encryption.kv;

import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.internal.util.Preconditions.checkNotNull;

import com.android.server.backup.encryption.chunk.ChunkHash;
import com.android.server.backup.encryption.protos.nano.KeyValueListingProto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Builds a {@link KeyValueListingProto.KeyValueListing}, which is a nano proto and so has no
 * builder.
 */
public class KeyValueListingBuilder {
    private final List<KeyValueListingProto.KeyValueEntry> mEntries = new ArrayList<>();

    /** Adds a new pair entry to the listing. */
    public KeyValueListingBuilder addPair(String key, ChunkHash hash) {
        checkArgument(key.length() != 0, "Key must have non-zero length");
        checkNotNull(hash, "Hash must not be null");

        KeyValueListingProto.KeyValueEntry entry = new KeyValueListingProto.KeyValueEntry();
        entry.key = key;
        entry.hash = hash.getHash();
        mEntries.add(entry);

        return this;
    }

    /** Adds all pairs contained in a map, where the map is from key to hash. */
    public KeyValueListingBuilder addAll(Map<String, ChunkHash> map) {
        for (Entry<String, ChunkHash> entry : map.entrySet()) {
            addPair(entry.getKey(), entry.getValue());
        }

        return this;
    }

    /** Returns a new listing containing all the pairs added so far. */
    public KeyValueListingProto.KeyValueListing build() {
        if (mEntries.size() == 0) {
            return emptyListing();
        }

        KeyValueListingProto.KeyValueListing listing = new KeyValueListingProto.KeyValueListing();
        listing.entries = new KeyValueListingProto.KeyValueEntry[mEntries.size()];
        mEntries.toArray(listing.entries);
        return listing;
    }

    /** Returns a new listing which does not contain any pairs. */
    public static KeyValueListingProto.KeyValueListing emptyListing() {
        KeyValueListingProto.KeyValueListing listing = new KeyValueListingProto.KeyValueListing();
        listing.entries = KeyValueListingProto.KeyValueEntry.emptyArray();
        return listing;
    }
}
