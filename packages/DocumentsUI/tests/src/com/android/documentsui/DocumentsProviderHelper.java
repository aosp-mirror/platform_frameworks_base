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

import static android.provider.DocumentsContract.buildChildDocumentsUri;
import static android.provider.DocumentsContract.buildDocumentUri;
import static android.provider.DocumentsContract.buildRootsUri;
import static com.android.documentsui.model.DocumentInfo.getCursorString;
import static com.android.internal.util.Preconditions.checkArgument;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.fail;

import android.content.ContentProviderClient;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFileDescriptor.AutoCloseInputStream;
import android.os.ParcelFileDescriptor.AutoCloseOutputStream;
import android.os.RemoteException;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.support.annotation.Nullable;
import android.test.MoreAsserts;
import android.text.TextUtils;

import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.RootInfo;

import com.google.android.collect.Lists;

import libcore.io.IoUtils;
import libcore.io.Streams;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Provides support for creation of documents in a test settings.
 */
public class DocumentsProviderHelper {

    private final String mAuthority;
    private final ContentProviderClient mClient;

    public DocumentsProviderHelper(String authority, ContentProviderClient client) {
        checkArgument(!TextUtils.isEmpty(authority));
        mAuthority = authority;
        mClient = client;
    }

    public RootInfo getRoot(String documentId) throws RemoteException {
        final Uri rootsUri = buildRootsUri(mAuthority);
        Cursor cursor = null;
        try {
            cursor = mClient.query(rootsUri, null, null, null, null);
            while (cursor.moveToNext()) {
                if (documentId.equals(getCursorString(cursor, Root.COLUMN_ROOT_ID))) {
                    return RootInfo.fromRootsCursor(mAuthority, cursor);
                }
            }
            throw new IllegalArgumentException("Can't find matching root for id=" + documentId);
        } catch (Exception e) {
            throw new RuntimeException("Can't load root for id=" + documentId , e);
        } finally {
            IoUtils.closeQuietly(cursor);
        }
    }

    public Uri createDocument(Uri parentUri, String mimeType, String name) {
        if (name.contains("/")) {
            throw new IllegalArgumentException("Name and mimetype probably interposed.");
        }
        try {
            Uri uri = DocumentsContract.createDocument(mClient, parentUri, mimeType, name);
            return uri;
        } catch (RemoteException e) {
            throw new RuntimeException("Couldn't create document: " + name + " with mimetype "
                    + mimeType, e);
        }
    }

    public Uri createDocument(String parentId, String mimeType, String name) {
        Uri parentUri = buildDocumentUri(mAuthority, parentId);
        return createDocument(parentUri, mimeType, name);
    }

    public Uri createDocument(RootInfo root, String mimeType, String name) {
        return createDocument(root.documentId, mimeType, name);
    }

    public Uri createDocumentWithFlags(String documentId, String mimeType, String name, int flags,
            String... streamTypes)
            throws RemoteException {
        Bundle in = new Bundle();
        in.putInt(StubProvider.EXTRA_FLAGS, flags);
        in.putString(StubProvider.EXTRA_PARENT_ID, documentId);
        in.putString(Document.COLUMN_MIME_TYPE, mimeType);
        in.putString(Document.COLUMN_DISPLAY_NAME, name);
        in.putStringArrayList(StubProvider.EXTRA_STREAM_TYPES, Lists.newArrayList(streamTypes));

        Bundle out = mClient.call("createDocumentWithFlags", null, in);
        Uri uri = out.getParcelable(DocumentsContract.EXTRA_URI);
        return uri;
    }

    public Uri createFolder(Uri parentUri, String name) {
        return createDocument(parentUri, Document.MIME_TYPE_DIR, name);
    }

    public Uri createFolder(String parentId, String name) {
        Uri parentUri = buildDocumentUri(mAuthority, parentId);
        return createDocument(parentUri, Document.MIME_TYPE_DIR, name);
    }

    public Uri createFolder(RootInfo root, String name) {
        return createDocument(root, Document.MIME_TYPE_DIR, name);
    }

    public void writeDocument(Uri documentUri, byte[] contents)
            throws RemoteException, IOException {
        ParcelFileDescriptor file = mClient.openFile(documentUri, "w", null);
        try (AutoCloseOutputStream out = new AutoCloseOutputStream(file)) {
            out.write(contents, 0, contents.length);
        }
    }

    public byte[] readDocument(Uri documentUri) throws RemoteException, IOException {
        ParcelFileDescriptor file = mClient.openFile(documentUri, "r", null);
        byte[] buf = null;
        try (AutoCloseInputStream in = new AutoCloseInputStream(file)) {
            buf = Streams.readFully(in);
        }
        return buf;
    }

    public void assertChildCount(Uri parentUri, int expected) throws Exception {
        List<DocumentInfo> children = listChildren(parentUri);
        assertEquals("Incorrect file count after copy", expected, children.size());
    }

    public void assertChildCount(String parentId, int expected) throws Exception {
        List<DocumentInfo> children = listChildren(parentId);
        assertEquals("Incorrect file count after copy", expected, children.size());
    }

    public void assertChildCount(RootInfo root, int expected) throws Exception {
        assertChildCount(root.documentId, expected);
    }

    public void assertHasFile(Uri parentUri, String name) throws Exception {
        List<DocumentInfo> children = listChildren(parentUri);
        for (DocumentInfo child : children) {
            if (name.equals(child.displayName) && !child.isDirectory()) {
                return;
            }
        }
        fail("Could not find file named=" + name + " in children " + children);
    }

    public void assertHasFile(String parentId, String name) throws Exception {
        Uri parentUri = buildDocumentUri(mAuthority, parentId);
        assertHasFile(parentUri, name);
    }

    public void assertHasFile(RootInfo root, String name) throws Exception {
        assertHasFile(root.documentId, name);
    }

    public void assertHasDirectory(Uri parentUri, String name) throws Exception {
        List<DocumentInfo> children = listChildren(parentUri);
        for (DocumentInfo child : children) {
            if (name.equals(child.displayName) && child.isDirectory()) {
                return;
            }
        }
        fail("Could not find name=" + name + " in children " + children);
    }

    public void assertHasDirectory(String parentId, String name) throws Exception {
        Uri parentUri = buildDocumentUri(mAuthority, parentId);
        assertHasDirectory(parentUri, name);
    }

    public void assertHasDirectory(RootInfo root, String name) throws Exception {
        assertHasDirectory(root.documentId, name);
    }

    public void assertDoesNotExist(Uri parentUri, String name) throws Exception {
        List<DocumentInfo> children = listChildren(parentUri);
        for (DocumentInfo child : children) {
            if (name.equals(child.displayName)) {
                fail("Found name=" + name + " in children " + children);
            }
        }
    }

    public void assertDoesNotExist(String parentId, String name) throws Exception {
        Uri parentUri = buildDocumentUri(mAuthority, parentId);
        assertDoesNotExist(parentUri, name);
    }

    public void assertDoesNotExist(RootInfo root, String name) throws Exception {
        assertDoesNotExist(root.getUri(), name);
    }

    public @Nullable DocumentInfo findFile(String parentId, String name)
            throws Exception {
        List<DocumentInfo> children = listChildren(parentId);
        for (DocumentInfo child : children) {
            if (name.equals(child.displayName)) {
                return child;
            }
        }
        return null;
    }

    public DocumentInfo findDocument(String parentId, String name) throws Exception {
        List<DocumentInfo> children = listChildren(parentId);
        for (DocumentInfo child : children) {
            if (name.equals(child.displayName)) {
                return child;
            }
        }
        return null;
    }

    public DocumentInfo findDocument(Uri parentUri, String name) throws Exception {
        List<DocumentInfo> children = listChildren(parentUri);
        for (DocumentInfo child : children) {
            if (name.equals(child.displayName)) {
                return child;
            }
        }
        return null;
    }

    public List<DocumentInfo> listChildren(Uri parentUri) throws Exception {
        String id = DocumentsContract.getDocumentId(parentUri);
        return listChildren(id);
    }

    public List<DocumentInfo> listChildren(String documentId) throws Exception {
        Uri uri = buildChildDocumentsUri(mAuthority, documentId);
        List<DocumentInfo> children = new ArrayList<>();
        try (Cursor cursor = mClient.query(uri, null, null, null, null, null)) {
            Cursor wrapper = new RootCursorWrapper(mAuthority, "totally-fake", cursor, 100);
            while (wrapper.moveToNext()) {
                children.add(DocumentInfo.fromDirectoryCursor(wrapper));
            }
        }
        return children;
    }

    public void assertFileContents(Uri documentUri, byte[] expected) throws Exception {
        MoreAsserts.assertEquals(
                "Copied file contents differ",
                expected, readDocument(documentUri));
    }

    public void assertFileContents(String parentId, String fileName, byte[] expected)
            throws Exception {
        DocumentInfo file = findFile(parentId, fileName);
        assertNotNull(file);
        assertFileContents(file.derivedUri, expected);
    }

    /**
     * A helper method for StubProvider only. Won't work with other providers.
     * @throws RemoteException
     */
    public Uri createVirtualFile(
            RootInfo root, String path, String mimeType, byte[] content, String... streamTypes)
                    throws RemoteException {

        Bundle args = new Bundle();
        args.putString(StubProvider.EXTRA_ROOT, root.rootId);
        args.putString(StubProvider.EXTRA_PATH, path);
        args.putString(Document.COLUMN_MIME_TYPE, mimeType);
        args.putStringArrayList(StubProvider.EXTRA_STREAM_TYPES, Lists.newArrayList(streamTypes));
        args.putByteArray(StubProvider.EXTRA_CONTENT, content);

        Bundle result = mClient.call("createVirtualFile", null, args);
        String documentId = result.getString(Document.COLUMN_DOCUMENT_ID);

        return DocumentsContract.buildDocumentUri(mAuthority, documentId);
    }

}
