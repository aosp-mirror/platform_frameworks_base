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

import static com.android.documentsui.Shared.DEBUG;
import static com.android.documentsui.Shared.TAG;
import static com.android.documentsui.model.DocumentInfo.getCursorString;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;

import com.android.documentsui.BaseActivity.SiblingProvider;
import com.android.documentsui.model.DocumentInfo;

/**
 * Provides support for gather a list of quick-viewable files into a quick view intent.
 */
final class QuickViewIntentBuilder {

    private final DocumentInfo mDocument;
    private final SiblingProvider mSiblings;

    private final PackageManager mPkgManager;
    private final Resources mResources;

    private ClipData mClipData;
    private int mDocumentLocation;

    public QuickViewIntentBuilder(
            PackageManager pkgManager,
            Resources resources,
            DocumentInfo doc,
            SiblingProvider siblings) {

        mPkgManager = pkgManager;
        mResources = resources;
        mDocument = doc;
        mSiblings = siblings;
    }

    /**
     * Builds the intent for quick viewing. Short circuits building if a handler cannot
     * be resolved; in this case {@code null} is returned.
     */
    @Nullable Intent build() {
        if (DEBUG) Log.d(TAG, "Preparing intent for doc:" + mDocument.documentId);

        String trustedPkg = mResources.getString(R.string.trusted_quick_viewer_package);

        Intent intent = new Intent(Intent.ACTION_QUICK_VIEW);
        intent.setDataAndType(mDocument.derivedUri, mDocument.mimeType);
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        if (TextUtils.isEmpty(trustedPkg)) {
            if (hasRegisteredHandler(intent)) {
                return intent;
            }
        } else {
            intent.setPackage(trustedPkg);
            if (hasRegisteredHandler(intent)) {
                // We have a trusted handler. Load all of the docs into the intent.
                Cursor cursor = mSiblings.getCursor();
                for (int i = 0; i < cursor.getCount(); i++) {
                    onNextItem(i, cursor);
                }
                intent.putExtra(Intent.EXTRA_INDEX, mDocumentLocation);
                intent.setClipData(mClipData);

                return intent;
            } else {
                Log.e(TAG, "Can't resolve trusted quick view package: " + trustedPkg);
            }
        }

        return null;
    }

    private boolean hasRegisteredHandler(Intent intent) {
        // Try to resolve the intent. If a matching app isn't installed, it won't resolve.
        return intent.resolveActivity(mPkgManager) != null;
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
