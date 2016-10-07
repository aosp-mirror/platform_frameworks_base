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
import android.support.test.filters.SmallTest;
import android.test.ProviderTestCase2;

import java.util.Arrays;
import java.util.List;

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

    public void testFindPath_docUri() throws Exception {
        final Path expected = new Path(ROOT_ID, Arrays.asList(PARENT_DOCUMENT_ID, DOCUMENT_ID));
        mProvider.nextPath = expected;

        final Uri docUri =
                DocumentsContract.buildDocumentUri(TestDocumentsProvider.AUTHORITY, DOCUMENT_ID);
        try (ContentProviderClient client =
                     mResolver.acquireUnstableContentProviderClient(docUri)) {
            final Path actual = DocumentsContract.findPath(client, docUri);
            assertEquals(expected, actual);
        }
    }

    public void testFindPath_treeUri() throws Exception {
        mProvider.nextIsChildDocument = true;

        final Path expected = new Path(null, Arrays.asList(PARENT_DOCUMENT_ID, DOCUMENT_ID));
        mProvider.nextPath = expected;

        final Uri docUri = buildTreeDocumentUri(
                TestDocumentsProvider.AUTHORITY, PARENT_DOCUMENT_ID, DOCUMENT_ID);
        final List<String> actual = DocumentsContract.findPath(mResolver, docUri);

        assertEquals(expected.getPath(), actual);
    }

    public void testFindPath_treeUri_throwsOnNonChildDocument() throws Exception {
        mProvider.nextPath = new Path(null, Arrays.asList(PARENT_DOCUMENT_ID, DOCUMENT_ID));

        final Uri docUri = buildTreeDocumentUri(
                TestDocumentsProvider.AUTHORITY, PARENT_DOCUMENT_ID, DOCUMENT_ID);
        assertNull(DocumentsContract.findPath(mResolver, docUri));
    }

    public void testFindPath_treeUri_throwsOnNonNullRootId() throws Exception {
        mProvider.nextIsChildDocument = true;

        mProvider.nextPath = new Path(ROOT_ID, Arrays.asList(PARENT_DOCUMENT_ID, DOCUMENT_ID));

        final Uri docUri = buildTreeDocumentUri(
                TestDocumentsProvider.AUTHORITY, PARENT_DOCUMENT_ID, DOCUMENT_ID);
        assertNull(DocumentsContract.findPath(mResolver, docUri));
    }

    public void testFindPath_treeUri_throwsOnDifferentParentDocId() throws Exception {
        mProvider.nextIsChildDocument = true;

        mProvider.nextPath = new Path(
                null, Arrays.asList(ANCESTOR_DOCUMENT_ID, PARENT_DOCUMENT_ID, DOCUMENT_ID));

        final Uri docUri = buildTreeDocumentUri(
                TestDocumentsProvider.AUTHORITY, PARENT_DOCUMENT_ID, DOCUMENT_ID);
        assertNull(DocumentsContract.findPath(mResolver, docUri));
    }

    private static Uri buildTreeDocumentUri(String authority, String parentDocId, String docId) {
        final Uri treeUri = DocumentsContract.buildTreeDocumentUri(authority, parentDocId);
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
    }
}
