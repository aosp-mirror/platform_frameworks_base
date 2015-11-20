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

package com.android.documentsui.model;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

@SmallTest
public class DocumentInfoTest extends AndroidTestCase {

    public void testEquals() throws Exception {
        DocumentInfo doc = createDocInfo("authority.a", "doc.1", "text/plain");
        assertEquals(doc, doc);
    }

    public void testNotEquals_differentAuthority() throws Exception {
        DocumentInfo docA = createDocInfo("authority.a", "doc.1", "text/plain");
        DocumentInfo docB = createDocInfo("authority.b", "doc.1", "text/plain");
        assertFalse(docA.equals(docB));
    }

    public void testNotEquals_differentDocId() throws Exception {
        DocumentInfo docA = createDocInfo("authority.a", "doc.1", "text/plain");
        DocumentInfo docB = createDocInfo("authority.a", "doc.2", "text/plain");
        assertFalse(docA.equals(docB));
    }

    public void testNotEquals_differentMimetype() throws Exception {
        DocumentInfo docA = createDocInfo("authority.a", "doc.1", "text/plain");
        DocumentInfo docB = createDocInfo("authority.a", "doc.1", "image/png");
        assertFalse(docA.equals(docB));
    }

    private DocumentInfo createDocInfo(String authority, String docId, String mimeType) {
        DocumentInfo doc = new DocumentInfo();
        doc.authority = authority;
        doc.documentId = docId;
        doc.mimeType = mimeType;
        doc.deriveFields();
        return doc;
    }
}
