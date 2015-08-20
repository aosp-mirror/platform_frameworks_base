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

import static com.android.documentsui.model.DocumentInfo.getCursorString;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.support.annotation.Nullable;
import android.util.Log;

import com.android.documentsui.BaseActivity.DocumentContext;
import com.android.documentsui.model.DocumentInfo;

/**
 * Provides support for gather a list of quick-viewable files into a quick view intent.
 */
final class QuickViewIntentBuilder {

    private static final String TAG = "QvIntentBuilder";
    private static final boolean DEBUG = false;

    private final DocumentInfo mDocument;
    private final DocumentContext mContext;

    public ClipData mClipData;
    public int mDocumentLocation;
    private PackageManager mPkgManager;

    public QuickViewIntentBuilder(
            PackageManager pkgManager, DocumentInfo doc, DocumentContext context) {
        mPkgManager = pkgManager;
        mDocument = doc;
        mContext = context;
    }

    /**
     * Builds the intent for quick viewing. Short circuits building if a handler cannot
     * be resolved; in this case {@code null} is returned.
     */
    @Nullable Intent build() {
        if (DEBUG) Log.d(TAG, "Preparing intent for doc:" + mDocument.documentId);

        Intent intent = new Intent(Intent.ACTION_QUICK_VIEW);
        intent.setDataAndType(mDocument.derivedUri, mDocument.mimeType);

        // Try to resolve the intent. If a matching app isn't installed, it won't resolve.
        ComponentName handler = intent.resolveActivity(mPkgManager);
        if (handler == null) {
            return null;
        }

        Cursor cursor = mContext.getCursor();
        for (int i = 0; i < cursor.getCount(); i++) {
            onNextItem(i, cursor);
        }

        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra(Intent.EXTRA_INDEX, mDocumentLocation);
        intent.setClipData(mClipData);

        return intent;
    }

    private void onNextItem(int index, Cursor cursor) {
        cursor.moveToPosition(index);

        String mimeType = getCursorString(cursor, Document.COLUMN_MIME_TYPE);
        if (Document.MIME_TYPE_DIR.equals(mimeType)) {
            return;
        }

        String id = getCursorString(cursor, Document.COLUMN_DOCUMENT_ID);
        String authority = getCursorString(cursor, RootCursorWrapper.COLUMN_AUTHORITY);
        Uri uri = DocumentsContract.buildDocumentUri(authority, id);
        if (DEBUG) Log.d(TAG, "Including file[" + id + "] @ " + uri);

        if (id.equals(mDocument.documentId)) {
            if (DEBUG) Log.d(TAG, "Found starting point for QV. " + index);
            mDocumentLocation = index;
        }

        ClipData.Item item = new ClipData.Item(uri);
        if (mClipData == null) {
            mClipData = new ClipData(
                    "URIs", new String[]{ClipDescription.MIMETYPE_TEXT_URILIST}, item);
        } else {
            mClipData.addItem(item);
        }
    }
}
