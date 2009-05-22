/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.unit_tests.content;

import android.test.suitebuilder.annotation.SmallTest;
import android.net.Uri;
import android.content.ContentValues;
import android.content.ContentProviderOperation;
import android.content.OperationApplicationException;
import android.content.ContentProviderResult;
import android.content.ContentProvider;
import android.text.TextUtils;
import android.database.Cursor;
import junit.framework.TestCase;

import java.util.Map;
import java.util.Hashtable;

@SmallTest
public class ContentProviderOperationTest extends TestCase {
    private final static Uri sTestUri1 = Uri.parse("content://authority/blah");
    private final static ContentValues sTestValues1;

    static {
        sTestValues1 = new ContentValues();
        sTestValues1.put("a", 1);
        sTestValues1.put("b", "two");
    }

    public void testInsert() throws OperationApplicationException {
        ContentProviderOperation op1 = ContentProviderOperation.newInsert(sTestUri1)
                .withValues(sTestValues1)
                .build();
        ContentProviderResult result = op1.apply(new TestContentProvider() {
            public Uri insert(Uri uri, ContentValues values) {
                assertEquals(sTestUri1.toString(), uri.toString());
                assertEquals(sTestValues1.toString(), values.toString());
                return uri.buildUpon().appendPath("19").build();
            }
        }, null, 0);
        assertEquals(sTestUri1.buildUpon().appendPath("19").toString(), result.uri.toString());
    }

    public void testInsertNoValues() throws OperationApplicationException {
        ContentProviderOperation op1 = ContentProviderOperation.newInsert(sTestUri1)
                .build();
        ContentProviderResult result = op1.apply(new TestContentProvider() {
            public Uri insert(Uri uri, ContentValues values) {
                assertEquals(sTestUri1.toString(), uri.toString());
                assertNull(values);
                return uri.buildUpon().appendPath("19").build();
            }
        }, null, 0);
        assertEquals(sTestUri1.buildUpon().appendPath("19").toString(), result.uri.toString());
    }

    public void testInsertFailed() {
        ContentProviderOperation op1 = ContentProviderOperation.newInsert(sTestUri1)
                .withValues(sTestValues1)
                .build();
        try {
            op1.apply(new TestContentProvider() {
                public Uri insert(Uri uri, ContentValues values) {
                    assertEquals(sTestUri1.toString(), uri.toString());
                    assertEquals(sTestValues1.toString(), values.toString());
                    return null;
                }
            }, null, 0);
            fail("the apply should have thrown an OperationApplicationException");
        } catch (OperationApplicationException e) {
            // this is the expected case
        }
    }

    public void testInsertWithBackRefs() throws OperationApplicationException {
        ContentValues valuesBackRefs = new ContentValues();
        valuesBackRefs.put("a1", 3);
        valuesBackRefs.put("a2", 1);

        ContentProviderResult[] previousResults = new ContentProviderResult[4];
        previousResults[0] = new ContentProviderResult(100);
        previousResults[1] = new ContentProviderResult(101);
        previousResults[2] = new ContentProviderResult(102);
        previousResults[3] = new ContentProviderResult(103);
        ContentProviderOperation op1 = ContentProviderOperation.newInsert(sTestUri1)
                .withValues(sTestValues1)
                .withValueBackReferences(valuesBackRefs)
                .build();
        ContentProviderResult result = op1.apply(new TestContentProvider() {
            public Uri insert(Uri uri, ContentValues values) {
                assertEquals(sTestUri1.toString(), uri.toString());
                ContentValues expected = new ContentValues(sTestValues1);
                expected.put("a1", 103);
                expected.put("a2", 101);
                assertEquals(expected.toString(), values.toString());
                return uri.buildUpon().appendPath("19").build();
            }
        }, previousResults, previousResults.length);
        assertEquals(sTestUri1.buildUpon().appendPath("19").toString(), result.uri.toString());
    }

    public void testUpdate() throws OperationApplicationException {
        ContentProviderOperation op1 = ContentProviderOperation.newInsert(sTestUri1)
                .withValues(sTestValues1)
                .build();
        ContentProviderResult[] backRefs = new ContentProviderResult[2];
        ContentProviderResult result = op1.apply(new TestContentProvider() {
            public Uri insert(Uri uri, ContentValues values) {
                assertEquals(sTestUri1.toString(), uri.toString());
                assertEquals(sTestValues1.toString(), values.toString());
                return uri.buildUpon().appendPath("19").build();
            }
        }, backRefs, 1);
        assertEquals(sTestUri1.buildUpon().appendPath("19").toString(), result.uri.toString());
    }

    public void testValueBackRefs() {
        ContentValues values = new ContentValues();
        values.put("a", "in1");
        values.put("a2", "in2");
        values.put("b", "in3");
        values.put("c", "in4");

        ContentProviderResult[] previousResults = new ContentProviderResult[4];
        previousResults[0] = new ContentProviderResult(100);
        previousResults[1] = new ContentProviderResult(101);
        previousResults[2] = new ContentProviderResult(102);
        previousResults[3] = new ContentProviderResult(103);

        ContentValues valuesBackRefs = new ContentValues();
        valuesBackRefs.put("a1", 3); // a1 -> 103
        valuesBackRefs.put("a2", 1); // a2 -> 101
        valuesBackRefs.put("a3", 2); // a3 -> 102

        ContentValues expectedValues = new ContentValues(values);
        expectedValues.put("a1", "103");
        expectedValues.put("a2", "101");
        expectedValues.put("a3", "102");

        ContentProviderOperation op1 = ContentProviderOperation.newInsert(sTestUri1)
                .withValues(values)
                .withValueBackReferences(valuesBackRefs)
                .build();
        ContentValues v2 = op1.resolveValueBackReferences(previousResults, previousResults.length);
        assertEquals(expectedValues, v2);
    }

    public void testSelectionBackRefs() {
        Map<Integer, Integer> selectionBackRefs = new Hashtable<Integer, Integer>();
        selectionBackRefs.put(1, 3);
        selectionBackRefs.put(2, 1);
        selectionBackRefs.put(4, 2);

        ContentProviderResult[] previousResults = new ContentProviderResult[4];
        previousResults[0] = new ContentProviderResult(100);
        previousResults[1] = new ContentProviderResult(101);
        previousResults[2] = new ContentProviderResult(102);
        previousResults[3] = new ContentProviderResult(103);

        String[] selectionArgs = new String[]{"a", null, null, "b", null};

        ContentProviderOperation op1 = ContentProviderOperation.newUpdate(sTestUri1)
                .withSelectionBackReferences(selectionBackRefs)
                .withSelection("unused", selectionArgs)
                .build();
        String[] s2 = op1.resolveSelectionArgsBackReferences(
                previousResults, previousResults.length);
        assertEquals("a,103,101,b,102", TextUtils.join(",", s2));
    }

    static class TestContentProvider extends ContentProvider {
        public boolean onCreate() {
            throw new UnsupportedOperationException();
        }

        public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                String sortOrder) {
            throw new UnsupportedOperationException();
        }

        public String getType(Uri uri) {
            throw new UnsupportedOperationException();
        }

        public Uri insert(Uri uri, ContentValues values) {
            throw new UnsupportedOperationException();
        }

        public int delete(Uri uri, String selection, String[] selectionArgs) {
            throw new UnsupportedOperationException();
        }

        public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
            throw new UnsupportedOperationException();
        }
    }
}