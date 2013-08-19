/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.documentsui;

import static com.android.documentsui.DirectoryFragment.TYPE_NORMAL;
import static com.android.documentsui.DirectoryFragment.TYPE_RECENT_OPEN;
import static com.android.documentsui.DirectoryFragment.TYPE_SEARCH;
import static com.android.documentsui.DocumentsActivity.TAG;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.DocumentsContract.DocumentColumns;
import android.util.Log;

import com.android.documentsui.model.Document;
import com.android.internal.util.Predicate;
import com.google.android.collect.Lists;

import libcore.io.IoUtils;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

public class DirectoryLoader extends UriDerivativeLoader<List<Document>> {

    private final int mType;
    private Predicate<Document> mFilter;
    private Comparator<Document> mSortOrder;

    /**
     * Stub result that represents an internal error.
     */
    public static class ExceptionResult extends LinkedList<Document> {
        public final Exception e;

        public ExceptionResult(Exception e) {
            this.e = e;
        }
    }

    public DirectoryLoader(Context context, Uri uri, int type, Predicate<Document> filter,
            Comparator<Document> sortOrder) {
        super(context, uri);
        mType = type;
        mFilter = filter;
        mSortOrder = sortOrder;
    }

    @Override
    public List<Document> loadInBackground(Uri uri, CancellationSignal signal) {
        try {
            return loadInBackgroundInternal(uri, signal);
        } catch (Exception e) {
            return new ExceptionResult(e);
        }
    }

    private List<Document> loadInBackgroundInternal(Uri uri, CancellationSignal signal) {
        final ArrayList<Document> result = Lists.newArrayList();

        // TODO: subscribe to the notify uri from query

        final ContentResolver resolver = getContext().getContentResolver();
        final Cursor cursor = resolver.query(uri, null, null, null, getQuerySortOrder(), signal);
        try {
            while (cursor != null && cursor.moveToNext()) {
                Document doc = null;
                switch (mType) {
                    case TYPE_NORMAL:
                    case TYPE_SEARCH:
                        doc = Document.fromDirectoryCursor(uri, cursor);
                        break;
                    case TYPE_RECENT_OPEN:
                        try {
                            doc = Document.fromRecentOpenCursor(resolver, cursor);
                        } catch (FileNotFoundException e) {
                            Log.w(TAG, "Failed to find recent: " + e);
                        }
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown type");
                }

                if (doc != null && (mFilter == null || mFilter.apply(doc))) {
                    result.add(doc);
                }
            }
        } finally {
            IoUtils.closeQuietly(cursor);
        }

        if (mSortOrder != null) {
            Collections.sort(result, mSortOrder);
        }

        return result;
    }

    private String getQuerySortOrder() {
        if (mSortOrder instanceof Document.DateComparator) {
            return DocumentColumns.LAST_MODIFIED + " DESC";
        } else if (mSortOrder instanceof Document.NameComparator) {
            return DocumentColumns.DISPLAY_NAME + " ASC";
        } else if (mSortOrder instanceof Document.SizeComparator) {
            return DocumentColumns.SIZE + " DESC";
        } else {
            return null;
        }
    }
}
