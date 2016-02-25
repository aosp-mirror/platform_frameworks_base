/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.support.annotation.Nullable;
import android.util.Log;

import com.android.documentsui.model.DocumentInfo;

import libcore.io.IoUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * ClipboardManager wrapper class providing higher level logical
 * support for dealing with Documents.
 */
public final class DocumentClipper {

    private static final String TAG = "DocumentClipper";

    private Context mContext;
    private ClipboardManager mClipboard;

    public DocumentClipper(Context context) {
        mContext = context;
        mClipboard = context.getSystemService(ClipboardManager.class);
    }

    public boolean hasItemsToPaste() {
        if (mClipboard.hasPrimaryClip()) {
            ClipData clipData = mClipboard.getPrimaryClip();
            int count = clipData.getItemCount();
            if (count > 0) {
                for (int i = 0; i < count; ++i) {
                    ClipData.Item item = clipData.getItemAt(i);
                    Uri uri = item.getUri();
                    if (isDocumentUri(uri)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isDocumentUri(@Nullable Uri uri) {
        return uri != null && DocumentsContract.isDocumentUri(mContext, uri);
    }

    /**
     * Returns a list of Documents as decoded from Clipboard primary clipdata.
     * This should be run from inside an AsyncTask.
     */
    public List<DocumentInfo> getClippedDocuments() {
        return getDocumentsFromClipData(mClipboard.getPrimaryClip());
    }

    /**
     * Returns a list of Documents as decoded in clipData.
     * This should be run from inside an AsyncTask.
     */
    public List<DocumentInfo> getDocumentsFromClipData(ClipData clipData) {
        assert(clipData != null);
        final List<DocumentInfo> srcDocs = new ArrayList<>();

        int count = clipData.getItemCount();
        if (count == 0) {
            return srcDocs;
        }

        ContentResolver resolver = mContext.getContentResolver();
        for (int i = 0; i < count; ++i) {
            ClipData.Item item = clipData.getItemAt(i);
            Uri itemUri = item.getUri();
            if (itemUri != null && DocumentsContract.isDocumentUri(mContext, itemUri)) {
                ContentProviderClient client = null;
                Cursor cursor = null;
                try {
                    client = DocumentsApplication.acquireUnstableProviderOrThrow(
                            resolver, itemUri.getAuthority());
                    cursor = client.query(itemUri, null, null, null, null);
                    cursor.moveToPosition(0);
                    srcDocs.add(DocumentInfo.fromCursor(cursor, itemUri.getAuthority()));
                } catch (Exception e) {
                    Log.e(TAG, e.getMessage());
                } finally {
                    IoUtils.closeQuietly(cursor);
                    ContentProviderClient.releaseQuietly(client);
                }
            }
        }

        return srcDocs;
    }

    /**
     * Returns ClipData representing the list of docs, or null if docs is empty,
     * or docs cannot be converted.
     */
    public @Nullable ClipData getClipDataForDocuments(List<DocumentInfo> docs) {
        final ContentResolver resolver = mContext.getContentResolver();
        ClipData clipData = null;
        for (DocumentInfo doc : docs) {
            final Uri uri = DocumentsContract.buildDocumentUri(doc.authority, doc.documentId);
            if (clipData == null) {
                // TODO: figure out what this string should be.
                // Currently it is not displayed anywhere in the UI, but this might change.
                final String label = "";
                clipData = ClipData.newUri(resolver, label, uri);
            } else {
                // TODO: update list of mime types in ClipData.
                clipData.addItem(new ClipData.Item(uri));
            }
        }
        return clipData;
    }

    public void clipDocuments(List<DocumentInfo> docs) {
        ClipData data = getClipDataForDocuments(docs);
        mClipboard.setPrimaryClip(data);
    }
}
