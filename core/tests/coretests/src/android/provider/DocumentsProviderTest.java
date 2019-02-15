/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.provider;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.net.Uri;
import android.provider.DocumentsContract.Path;
import android.test.ProviderTestCase2;

import androidx.test.filters.SmallTest;

import java.util.Arrays;

/**
 * Unit tests for {@link DocumentsProvider}.
 */
@SmallTest
public class DocumentsProviderTest extends ProviderTestCase2<TestDocumentsProvider> {

    private static final String ROOT_ID = "rootId";
    private static final String DOCUMENT_ID = "docId";
    private static final String PARENT_DOCUMENT_ID = "parentDocId";
    private static final String ANCESTOR_DOCUMENT_ID = "ancestorDocId";

    private TestDocumentsProvider mProvider;

    private ContentResolver mResolver;

    public DocumentsProviderTest() {
        super(TestDocumentsProvider.class, TestDocumentsProvider.AUTHORITY);
    }

    public void setUp() throws Exception {
        super.setUp();

        mProvider = getProvider();
        mResolver = getMockContentResolver();
    }

    public void testFindDocumentPath_docUri() throws Exception {
        final Path expected = new Path(ROOT_ID, Arrays.asList(PARENT_DOCUMENT_ID, DOCUMENT_ID));
        mProvider.nextPath = expected;

        final Uri docUri =
                DocumentsContract.buildDocumentUri(TestDocumentsProvider.AUTHORITY, DOCUMENT_ID);
        try (ContentProviderClient client =
                     mResolver.acquireUnstableContentProviderClient(docUri)) {
            final Path actual = DocumentsContract.findDocumentPath(
                    ContentResolver.wrap(client), docUri);
            assertEquals(expected, actual);
        }
    }

    public void testFindDocumentPath_treeUri() throws Exception {
        mProvider.nextIsChildDocument = true;

        final Path expected = new Path(null, Arrays.asList(PARENT_DOCUMENT_ID, DOCUMENT_ID));
        mProvider.nextPath = expected;

        final Uri docUri = buildTreeDocumentUri(
                TestDocumentsProvider.AUTHORITY, PARENT_DOCUMENT_ID, DOCUMENT_ID);
        final Path actual = DocumentsContract.findDocumentPath(mResolver, docUri);

        assertNull(actual.getRootId());
        assertEquals(expected.getPath(), actual.getPath());
    }

    public void testFindDocumentPath_treeUri_throwsOnNonChildDocument() throws Exception {
        mProvider.nextPath = new Path(null, Arrays.asList(PARENT_DOCUMENT_ID, DOCUMENT_ID));

        final Uri docUri = buildTreeDocumentUri(
                TestDocumentsProvider.AUTHORITY, PARENT_DOCUMENT_ID, DOCUMENT_ID);
        try {
            DocumentsContract.findDocumentPath(mResolver, docUri);
            fail("Expected a SecurityException to be throw");
        } catch (SecurityException expected) { }
    }

    public void testFindDocumentPath_treeUri_erasesNonNullRootId() throws Exception {
        mProvider.nextIsChildDocument = true;

        mProvider.nextPath = new Path(ROOT_ID, Arrays.asList(PARENT_DOCUMENT_ID, DOCUMENT_ID));

        final Uri docUri = buildTreeDocumentUri(
                TestDocumentsProvider.AUTHORITY, PARENT_DOCUMENT_ID, DOCUMENT_ID);
        Path path = DocumentsContract.findDocumentPath(mResolver, docUri);
        assertNull(path.getRootId());
        assertEquals(Arrays.asList(PARENT_DOCUMENT_ID, DOCUMENT_ID), path.getPath());
    }

    public void testFindDocumentPath_treeUri_erasesDocsOutsideTree() throws Exception {
        mProvider.nextIsChildDocument = true;

        mProvider.nextPath = new Path(
                null, Arrays.asList(ANCESTOR_DOCUMENT_ID, PARENT_DOCUMENT_ID, DOCUMENT_ID));

        final Uri docUri = buildTreeDocumentUri(
                TestDocumentsProvider.AUTHORITY, PARENT_DOCUMENT_ID, DOCUMENT_ID);
        Path path = DocumentsContract.findDocumentPath(mResolver, docUri);
        assertEquals(Arrays.asList(PARENT_DOCUMENT_ID, DOCUMENT_ID), path.getPath());
    }

    private static Uri buildTreeDocumentUri(String authority, String parentDocId, String docId) {
        final Uri treeUri = DocumentsContract.buildTreeDocumentUri(authority, parentDocId);
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
    }
}
