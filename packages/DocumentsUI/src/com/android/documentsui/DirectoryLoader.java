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
import android.util.Log;

import com.android.documentsui.model.DocumentInfo;
import com.android.internal.util.Predicate;
import com.google.android.collect.Lists;

import libcore.io.IoUtils;

import java.io.FileNotFoundException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

class DirectoryResult implements AutoCloseable {
    Cursor cursor;
    List<DocumentInfo> contents = Lists.newArrayList();
    Exception e;

    @Override
    public void close() throws Exception {
        IoUtils.closeQuietly(cursor);
    }
}

public class DirectoryLoader extends UriDerivativeLoader<Uri, DirectoryResult> {

    private final int mType;
    private Predicate<DocumentInfo> mFilter;
    private Comparator<DocumentInfo> mSortOrder;

    public DirectoryLoader(Context context, Uri uri, int type, Predicate<DocumentInfo> filter,
            Comparator<DocumentInfo> sortOrder) {
        super(context, uri);
        mType = type;
        mFilter = filter;
        mSortOrder = sortOrder;
    }

    @Override
    public DirectoryResult loadInBackground(Uri uri, CancellationSignal signal) {
        final DirectoryResult result = new DirectoryResult();
        try {
            loadInBackgroundInternal(result, uri, signal);
        } catch (Exception e) {
            result.e = e;
        }
        return result;
    }

    private void loadInBackgroundInternal(
            DirectoryResult result, Uri uri, CancellationSignal signal) throws RuntimeException {
        // TODO: switch to using unstable CPC
        final ContentResolver resolver = getContext().getContentResolver();
        final Cursor cursor = resolver.query(uri, null, null, null, null, signal);
        result.cursor = cursor;
        result.cursor.registerContentObserver(mObserver);

        while (cursor.moveToNext()) {
            DocumentInfo doc = null;
            switch (mType) {
                case TYPE_NORMAL:
                case TYPE_SEARCH:
                    doc = DocumentInfo.fromDirectoryCursor(uri, cursor);
                    break;
                case TYPE_RECENT_OPEN:
                    try {
                        doc = DocumentInfo.fromRecentOpenCursor(resolver, cursor);
                    } catch (FileNotFoundException e) {
                        Log.w(TAG, "Failed to find recent: " + e);
                    }
                    break;
                default:
                    throw new IllegalArgumentException("Unknown type");
            }

            if (doc != null && (mFilter == null || mFilter.apply(doc))) {
                result.contents.add(doc);
            }
        }

        if (mSortOrder != null) {
            Collections.sort(result.contents, mSortOrder);
        }
    }
}
