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
import android.os.PersistableBundle;
import android.provider.DocumentsContract;
import android.support.annotation.Nullable;
import android.util.Log;

import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.services.FileOperationService;
import com.android.documentsui.services.FileOperationService.OpType;

import libcore.io.IoUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ClipboardManager wrapper class providing higher level logical
 * support for dealing with Documents.
 */
public final class DocumentClipper {

    private static final String TAG = "DocumentClipper";
    private static final String SRC_PARENT_KEY = "srcParent";
    private static final String OP_TYPE_KEY = "opType";

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
     * Returns details regarding the documents on the primary clipboard
     */
    public ClipDetails getClipDetails() {
        return getClipDetails(mClipboard.getPrimaryClip());
    }

    public ClipDetails getClipDetails(@Nullable ClipData clipData) {
        if (clipData == null) {
            return null;
        }

        String srcParent = clipData.getDescription().getExtras().getString(SRC_PARENT_KEY);

        ClipDetails clipDetails = new ClipDetails(
                clipData.getDescription().getExtras().getInt(OP_TYPE_KEY),
                getDocumentsFromClipData(clipData),
                createDocument((srcParent != null) ? Uri.parse(srcParent) : null));

        return clipDetails;
    }

    private List<DocumentInfo> getDocumentsFromClipData(ClipData clipData) {
        assert(clipData != null);

        int count = clipData.getItemCount();
        if (count == 0) {
            return Collections.EMPTY_LIST;
        }

        final List<DocumentInfo> srcDocs = new ArrayList<>();

        for (int i = 0; i < count; ++i) {
            ClipData.Item item = clipData.getItemAt(i);
            Uri itemUri = item.getUri();
            srcDocs.add(createDocument(itemUri));
        }

        return srcDocs;
    }

    /**
     * Returns ClipData representing the list of docs, or null if docs is empty,
     * or docs cannot be converted.
     */
    public @Nullable ClipData getClipDataForDocuments(List<DocumentInfo> docs, @OpType int opType) {
        final ContentResolver resolver = mContext.getContentResolver();
        ClipData clipData = null;
        for (DocumentInfo doc : docs) {
            assert(doc != null);
            assert(doc.derivedUri != null);
            if (clipData == null) {
                // TODO: figure out what this string should be.
                // Currently it is not displayed anywhere in the UI, but this might change.
                final String clipLabel = "";
                clipData = ClipData.newUri(resolver, clipLabel, doc.derivedUri);
                PersistableBundle bundle = new PersistableBundle();
                bundle.putInt(OP_TYPE_KEY, opType);
                clipData.getDescription().setExtras(bundle);
            } else {
                // TODO: update list of mime types in ClipData.
                clipData.addItem(new ClipData.Item(doc.derivedUri));
            }
        }
        return clipData;
    }

    /**
     * Puts {@code ClipData} in a primary clipboard, describing a copy operation
     */
    public void clipDocumentsForCopy(List<DocumentInfo> docs) {
        ClipData data = getClipDataForDocuments(docs, FileOperationService.OPERATION_COPY);
        assert(data != null);

        mClipboard.setPrimaryClip(data);
    }

    /**
     *  Puts {@Code ClipData} in a primary clipboard, describing a cut operation
     */
    public void clipDocumentsForCut(List<DocumentInfo> docs, DocumentInfo srcParent) {
        assert(docs != null);
        assert(!docs.isEmpty());
        assert(srcParent != null);
        assert(srcParent.derivedUri != null);

        ClipData data = getClipDataForDocuments(docs, FileOperationService.OPERATION_MOVE);
        assert(data != null);

        PersistableBundle bundle = data.getDescription().getExtras();
        bundle.putString(SRC_PARENT_KEY, srcParent.derivedUri.toString());

        mClipboard.setPrimaryClip(data);
    }

    private DocumentInfo createDocument(Uri uri) {
        DocumentInfo doc = null;
        if (uri != null && DocumentsContract.isDocumentUri(mContext, uri)) {
            ContentResolver resolver = mContext.getContentResolver();
            ContentProviderClient client = null;
            Cursor cursor = null;
            try {
                client = DocumentsApplication.acquireUnstableProviderOrThrow(resolver, uri.getAuthority());
                cursor = client.query(uri, null, null, null, null);
                cursor.moveToPosition(0);
                doc = DocumentInfo.fromCursor(cursor, uri.getAuthority());
            } catch (Exception e) {
                Log.e(TAG, e.getMessage());
            } finally {
                IoUtils.closeQuietly(cursor);
                ContentProviderClient.releaseQuietly(client);
            }
        }
        return doc;
    }

    public static class ClipDetails {
        public final @OpType int opType;
        public final List<DocumentInfo> docs;
        public final @Nullable DocumentInfo parent;

        ClipDetails(@OpType int opType, List<DocumentInfo> docs, @Nullable DocumentInfo parent) {
            this.opType = opType;
            this.docs = docs;
            this.parent = parent;
        }
    }
}
