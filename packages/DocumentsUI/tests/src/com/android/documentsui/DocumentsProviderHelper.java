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

import android.content.ContentProviderClient;
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteException;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;

import com.android.documentsui.model.RootInfo;

import libcore.io.IoUtils;

/**
 * Provides support for creation of documents in a test settings.
 */
public class DocumentsProviderHelper {

    private final ContentProviderClient mClient;
    private final String mAuthority;

    public DocumentsProviderHelper(String authority, ContentProviderClient client) {
        mClient = client;
        mAuthority = authority;
    }

    public RootInfo getRoot(String id) throws RemoteException {
        final Uri rootsUri = DocumentsContract.buildRootsUri(mAuthority);

        Cursor cursor = null;
        try {
            cursor = mClient.query(rootsUri, null, null, null, null);
            while (cursor.moveToNext()) {
                if (id.equals(getCursorString(cursor, Root.COLUMN_ROOT_ID))) {
                    return RootInfo.fromRootsCursor(mAuthority, cursor);
                }
            }
            throw new IllegalArgumentException("Can't find matching root for id=" + id);
        } catch (Exception e) {
            throw new RuntimeException("Can't load root for id=" + id , e);
        } finally {
            IoUtils.closeQuietly(cursor);
        }
    }

    public Uri createDocument(Uri parentUri, String mimeType, String name) {
        if (name.contains("/")) {
            throw new IllegalArgumentException("Name and mimetype probably interposed.");
        }
        try {
            return DocumentsContract.createDocument(mClient, parentUri, mimeType, name);
        } catch (RemoteException e) {
            throw new RuntimeException("Couldn't create document: " + name + " with mimetype " + mimeType, e);
        }
    }

    public Uri createFolder(Uri parentUri, String name) {
        return createDocument(parentUri, Document.MIME_TYPE_DIR, name);
    }

    public Uri createDocument(RootInfo root, String mimeType, String name) {
        Uri rootUri = DocumentsContract.buildDocumentUri(mAuthority, root.documentId);
        return createDocument(rootUri, mimeType, name);
    }

    public Uri createFolder(RootInfo root, String name) {
        return createDocument(root, Document.MIME_TYPE_DIR, name);
    }
}
