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

package com.android.server.appsearch.impl;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fake in-memory implementation of the Icing key-value store and reverse index.
 * <p>
 * Currently, only queries by single exact term are supported. There is no support for persistence,
 * namespaces, i18n tokenization, or schema.
 *
 * @hide
 */
public class FakeIcing {
    private final AtomicInteger mNextDocId = new AtomicInteger();
    private final Map<String, Integer> mUriToDocIdMap = new ArrayMap<>();
    /** Array of Documents (pair of uri and content) where index into the array is the docId. */
    private final SparseArray<Pair<String, String>> mDocStore = new SparseArray<>();
    /** Map of term to posting-list (the set of DocIds containing that term). */
    private final Map<String, Set<Integer>> mIndex = new ArrayMap<>();

    /**
     * Inserts a document into the index.
     *
     * @param uri The globally unique identifier of the document.
     * @param doc The contents of the document.
     */
    public void put(@NonNull String uri, @NonNull String doc) {
        // Update mDocIdMap
        Integer docId = mUriToDocIdMap.get(uri);
        if (docId != null) {
            // Delete the old doc
            mDocStore.remove(docId);
        }

        // Allocate a new docId
        docId = mNextDocId.getAndIncrement();
        mUriToDocIdMap.put(uri, docId);

        // Update mDocStore
        mDocStore.put(docId, Pair.create(uri, doc));

        // Update mIndex
        String[] words = normalizeString(doc).split("\\s+");
        for (String word : words) {
            Set<Integer> postingList = mIndex.get(word);
            if (postingList == null) {
                postingList = new ArraySet<>();
                mIndex.put(word, postingList);
            }
            postingList.add(docId);
        }
    }

    /**
     * Retrieves a document from the index.
     *
     * @param uri The URI of the document to retrieve.
     * @return The body of the document, or {@code null} if no such document exists.
     */
    @Nullable
    public String get(@NonNull String uri) {
        Integer docId = mUriToDocIdMap.get(uri);
        if (docId == null) {
            return null;
        }
        Pair<String, String> record = mDocStore.get(docId);
        if (record == null) {
            return null;
        }
        return record.second;
    }

    /**
     * Returns documents containing the given term.
     *
     * @param term A single exact term to look up in the index.
     * @return The URIs of the matching documents, or an empty {@code List} if no documents match.
     */
    @NonNull
    public List<String> query(@NonNull String term) {
        String normTerm = normalizeString(term);
        Set<Integer> docIds = mIndex.get(normTerm);
        if (docIds == null || docIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> uris = new ArrayList<>(docIds.size());
        for (int docId : docIds) {
            Pair<String, String> record = mDocStore.get(docId);
            if (record != null) {
                uris.add(record.first);
            }
        }
        return uris;
    }

    /**
     * Deletes a document by its URI.
     *
     * @param uri The URI of the document to be deleted.
     */
    public void delete(@NonNull String uri) {
        // Update mDocIdMap
        Integer docId = mUriToDocIdMap.get(uri);
        if (docId != null) {
            // Delete the old doc
            mDocStore.remove(docId);
            mUriToDocIdMap.remove(uri);
        }
    }

    /** Strips out punctuation and converts to lowercase. */
    private static String normalizeString(String input) {
        return input.replaceAll("\\p{P}", "").toLowerCase(Locale.getDefault());
    }
}
