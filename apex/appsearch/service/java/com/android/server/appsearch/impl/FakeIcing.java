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
import android.util.SparseArray;

import com.google.android.icing.proto.DocumentProto;
import com.google.android.icing.proto.PropertyProto;
import com.google.android.icing.proto.SearchResultProto;
import com.google.android.icing.proto.StatusProto;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fake in-memory implementation of the Icing key-value store and reverse index.
 * <p>
 * Currently, only queries by single exact term are supported. There is no support for persistence,
 * namespaces, i18n tokenization, or schema.
 */
public class FakeIcing {
    private final AtomicInteger mNextDocId = new AtomicInteger();
    private final Map<String, Integer> mUriToDocIdMap = new ArrayMap<>();
    /** Array of Documents where index into the array is the docId. */
    private final SparseArray<DocumentProto> mDocStore = new SparseArray<>();
    /** Map of term to posting-list (the set of DocIds containing that term). */
    private final Map<String, Set<Integer>> mIndex = new ArrayMap<>();

    /**
     * Inserts a document into the index.
     *
     * @param document The document to insert.
     */
    public void put(@NonNull DocumentProto document) {
        String uri = document.getUri();

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
        mDocStore.put(docId, document);

        // Update mIndex
        indexDocument(docId, document);
    }

    /**
     * Retrieves a document from the index.
     *
     * @param uri The URI of the document to retrieve.
     * @return The body of the document, or {@code null} if no such document exists.
     */
    @Nullable
    public DocumentProto get(@NonNull String uri) {
        Integer docId = mUriToDocIdMap.get(uri);
        if (docId == null) {
            return null;
        }
        return mDocStore.get(docId);
    }

    /**
     * Returns documents containing all words in the given query string.
     *
     * @param queryExpression A set of words to search for. They will be implicitly AND-ed together.
     *     No operators are supported.
     * @return A {@link SearchResultProto} containing the matching documents, which may have no
     *   results if no documents match.
     */
    @NonNull
    public SearchResultProto query(@NonNull String queryExpression) {
        String[] terms = normalizeString(queryExpression).split("\\s+");
        SearchResultProto.Builder results = SearchResultProto.newBuilder()
                .setStatus(StatusProto.newBuilder().setCode(StatusProto.Code.OK));
        if (terms.length == 0) {
            return results.build();
        }
        Set<Integer> docIds = mIndex.get(terms[0]);
        if (docIds == null || docIds.isEmpty()) {
            return results.build();
        }
        for (int i = 1; i < terms.length; i++) {
            Set<Integer> termDocIds = mIndex.get(terms[i]);
            if (termDocIds == null) {
                return results.build();
            }
            docIds.retainAll(termDocIds);
            if (docIds.isEmpty()) {
                return results.build();
            }
        }
        for (int docId : docIds) {
            DocumentProto document = mDocStore.get(docId);
            if (document != null) {
                results.addResults(
                        SearchResultProto.ResultProto.newBuilder().setDocument(document));
            }
        }
        return results.build();
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

    /** Deletes all documents belonging to the given namespace. */
    public void deleteByNamespace(@NonNull String namespace) {
        for (int i = 0; i < mDocStore.size(); i++) {
            DocumentProto document = mDocStore.valueAt(i);
            if (namespace.equals(document.getNamespace())) {
                mDocStore.removeAt(i);
                mUriToDocIdMap.remove(document.getUri());
                i--;
            }
        }
    }

    private void indexDocument(int docId, DocumentProto document) {
        for (PropertyProto property : document.getPropertiesList()) {
            for (String stringValue : property.getStringValuesList()) {
                String[] words = normalizeString(stringValue).split("\\s+");
                for (String word : words) {
                    indexTerm(docId, word);
                }
            }
            for (Long longValue : property.getInt64ValuesList()) {
                indexTerm(docId, longValue.toString());
            }
            for (Double doubleValue : property.getDoubleValuesList()) {
                indexTerm(docId, doubleValue.toString());
            }
            for (Boolean booleanValue : property.getBooleanValuesList()) {
                indexTerm(docId, booleanValue.toString());
            }
            // Intentionally skipping bytes values
            for (DocumentProto documentValue : property.getDocumentValuesList()) {
                indexDocument(docId, documentValue);
            }
        }
    }

    private void indexTerm(int docId, String term) {
        Set<Integer> postingList = mIndex.get(term);
        if (postingList == null) {
            postingList = new ArraySet<>();
            mIndex.put(term, postingList);
        }
        postingList.add(docId);
    }

    /** Strips out punctuation and converts to lowercase. */
    private static String normalizeString(String input) {
        return input.replaceAll("\\p{P}", "").toLowerCase(Locale.getDefault());
    }
}
