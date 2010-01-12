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

package android.content;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Parcel;
import android.test.suitebuilder.annotation.SmallTest;
import android.text.TextUtils;
import junit.framework.TestCase;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Set;
import java.util.Map;
import java.util.Map.Entry;

@SmallTest
public class ContentProviderOperationTest extends TestCase {
    private final static Uri sTestUri1 = Uri.parse("content://authority/blah");
    private final static ContentValues sTestValues1;

    private final static Class<ContentProviderOperation.Builder> CLASS_BUILDER =
            ContentProviderOperation.Builder.class;
    private final static Class<ContentProviderOperation> CLASS_OPERATION =
            ContentProviderOperation.class;

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
        ContentProviderResult[] previousResults = new ContentProviderResult[4];
        previousResults[0] = new ContentProviderResult(100);
        previousResults[1] = new ContentProviderResult(101);
        previousResults[2] = new ContentProviderResult(102);
        previousResults[3] = new ContentProviderResult(103);
        ContentProviderOperation op1 = ContentProviderOperation.newInsert(sTestUri1)
                .withValues(sTestValues1)
                .withValueBackReference("a1", 3)
                .withValueBackReference("a2", 1)
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

    public void testAssert() {
        // Build an operation to assert values match provider
        ContentProviderOperation op1 = ContentProviderOperation.newAssertQuery(sTestUri1)
                .withValues(sTestValues1).build();

        try {
            // Assert that values match from cursor
            ContentProviderResult result = op1.apply(new TestContentProvider() {
                public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
                    // Return cursor over specific set of values
                    return getCursor(sTestValues1, 1);
                }
            }, null, 0);
        } catch (OperationApplicationException e) {
            fail("newAssert() failed");
        }
    }

    public void testAssertNoValues() {
        // Build an operation to assert values match provider
        ContentProviderOperation op1 = ContentProviderOperation.newAssertQuery(sTestUri1)
                .withExpectedCount(1).build();

        try {
            // Assert that values match from cursor
            ContentProviderResult result = op1.apply(new TestContentProvider() {
                public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
                    // Return cursor over specific set of values
                    return getCursor(sTestValues1, 1);
                }
            }, null, 0);
        } catch (OperationApplicationException e) {
            fail("newAssert() failed");
        }

        ContentProviderOperation op2 = ContentProviderOperation.newAssertQuery(sTestUri1)
                .withExpectedCount(0).build();

        try {
            // Assert that values match from cursor
            ContentProviderResult result = op2.apply(new TestContentProvider() {
                public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
                    // Return cursor over specific set of values
                    return getCursor(sTestValues1, 0);
                }
            }, null, 0);
        } catch (OperationApplicationException e) {
            fail("newAssert() failed");
        }

        ContentProviderOperation op3 = ContentProviderOperation.newAssertQuery(sTestUri1)
                .withExpectedCount(2).build();

        try {
            // Assert that values match from cursor
            ContentProviderResult result = op3.apply(new TestContentProvider() {
                public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
                    // Return cursor over specific set of values
                    return getCursor(sTestValues1, 5);
                }
            }, null, 0);
            fail("we expect the exception to be thrown");
        } catch (OperationApplicationException e) {
        }
    }

    /**
     * Build a {@link Cursor} with a single row that contains all values
     * provided through the given {@link ContentValues}.
     */
    private Cursor getCursor(ContentValues contentValues, int numRows) {
        final Set<Entry<String, Object>> valueSet = contentValues.valueSet();
        final String[] keys = new String[valueSet.size()];
        final Object[] values = new Object[valueSet.size()];

        int i = 0;
        for (Entry<String, Object> entry : valueSet) {
            keys[i] = entry.getKey();
            values[i] = entry.getValue();
            i++;
        }

        final MatrixCursor cursor = new MatrixCursor(keys);
        for (i = 0; i < numRows; i++) {
            cursor.addRow(values);
        }
        return cursor;
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

        ContentValues expectedValues = new ContentValues(values);
        expectedValues.put("a1", (long) 103);
        expectedValues.put("a2", (long) 101);
        expectedValues.put("a3", (long) 102);

        ContentProviderOperation op1 = ContentProviderOperation.newInsert(sTestUri1)
                .withValues(values)
                .withValueBackReference("a1", 3)
                .withValueBackReference("a2", 1)
                .withValueBackReference("a3", 2)
                .build();
        ContentValues v2 = op1.resolveValueBackReferences(previousResults, previousResults.length);
        assertEquals(expectedValues, v2);
    }

    public void testSelectionBackRefs() {
        ContentProviderResult[] previousResults = new ContentProviderResult[4];
        previousResults[0] = new ContentProviderResult(100);
        previousResults[1] = new ContentProviderResult(101);
        previousResults[2] = new ContentProviderResult(102);
        previousResults[3] = new ContentProviderResult(103);

        String[] selectionArgs = new String[]{"a", null, null, "b", null};

        final ContentValues values = new ContentValues();
        values.put("unused", "unused");

        ContentProviderOperation op1 = ContentProviderOperation.newUpdate(sTestUri1)
                .withSelectionBackReference(1, 3)
                .withSelectionBackReference(2, 1)
                .withSelectionBackReference(4, 2)
                .withSelection("unused", selectionArgs)
                .withValues(values)
                .build();
        String[] s2 = op1.resolveSelectionArgsBackReferences(
                previousResults, previousResults.length);
        assertEquals("a,103,101,b,102", TextUtils.join(",", s2));
    }

    public void testParcelingOperation() throws NoSuchFieldException, IllegalAccessException,
            NoSuchMethodException, InvocationTargetException, InstantiationException {
        Parcel parcel = Parcel.obtain();
        ContentProviderOperation op1;
        ContentProviderOperation op2;

        HashMap<Integer, Integer> selArgsBackRef = new HashMap<Integer, Integer>();
        selArgsBackRef.put(1, 2);
        selArgsBackRef.put(3, 4);

        ContentValues values = new ContentValues();
        values.put("v1", "val1");
        values.put("v2", "43");

        ContentValues valuesBackRef = new ContentValues();
        values.put("v3", "val3");
        values.put("v4", "44");

        try {
            ContentProviderOperation.Builder builder = ContentProviderOperation.newInsert(
                    Uri.parse("content://goo/bar"));

            builderSetExpectedCount(builder, 42);
            builderSetSelection(builder, "selection");
            builderSetSelectionArgs(builder, new String[]{"a", "b"});
            builderSetSelectionArgsBackReferences(builder, selArgsBackRef);
            builderSetValues(builder, values);
            builderSetValuesBackReferences(builder, valuesBackRef);

            op1 = newOperationFromBuilder(builder);
            op1.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            op2 = ContentProviderOperation.CREATOR.createFromParcel(parcel);

            assertEquals(ContentProviderOperation.TYPE_INSERT, operationGetType(op2));
            assertEquals("content://goo/bar", operationGetUri(op2).toString());
            assertEquals(Integer.valueOf(42), operationGetExpectedCount(op2));
            assertEquals("selection", operationGetSelection(op2));
            assertEquals(2, operationGetSelectionArgs(op2).length);
            assertEquals("a", operationGetSelectionArgs(op2)[0]);
            assertEquals("b", operationGetSelectionArgs(op2)[1]);
            assertEquals(values, operationGetValues(op2));
            assertEquals(valuesBackRef, operationGetValuesBackReferences(op2));
            assertEquals(2, operationGetSelectionArgsBackReferences(op2).size());
            assertEquals(Integer.valueOf(2), operationGetSelectionArgsBackReferences(op2).get(1));
            assertEquals(Integer.valueOf(4), operationGetSelectionArgsBackReferences(op2).get(3));
        } finally {
            parcel.recycle();
        }

        try {
            ContentProviderOperation.Builder builder = ContentProviderOperation.newUpdate(
                    Uri.parse("content://goo/bar"));

            builderSetSelectionArgsBackReferences(builder, selArgsBackRef);

            op1 = newOperationFromBuilder(builder);
            op1.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            op2 = ContentProviderOperation.CREATOR.createFromParcel(parcel);
            assertEquals(ContentProviderOperation.TYPE_UPDATE, operationGetType(op2));
            assertEquals("content://goo/bar", operationGetUri(op2).toString());
            assertNull(operationGetExpectedCount(op2));
            assertNull(operationGetSelection(op2));
            assertNull(operationGetSelectionArgs(op2));
            assertNull(operationGetValues(op2));
            assertNull(operationGetValuesBackReferences(op2));
            assertEquals(2, operationGetSelectionArgsBackReferences(op2).size());
            assertEquals(Integer.valueOf(2), operationGetSelectionArgsBackReferences(op2).get(1));
            assertEquals(Integer.valueOf(4), operationGetSelectionArgsBackReferences(op2).get(3));
        } finally {
            parcel.recycle();
        }

        try {
            ContentProviderOperation.Builder builder = ContentProviderOperation.newDelete(
                    Uri.parse("content://goo/bar"));

            op1 = newOperationFromBuilder(builder);
            op1.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            op2 = ContentProviderOperation.CREATOR.createFromParcel(parcel);
            assertEquals(ContentProviderOperation.TYPE_DELETE, operationGetType(op2));
            assertEquals("content://goo/bar", operationGetUri(op2).toString());
            assertNull(operationGetExpectedCount(op2));
            assertNull(operationGetSelection(op2));
            assertNull(operationGetSelectionArgs(op2));
            assertNull(operationGetValues(op2));
            assertNull(operationGetValuesBackReferences(op2));
            assertNull(operationGetSelectionArgsBackReferences(op2));
        } finally {
            parcel.recycle();
        }
    }

    private static ContentProviderOperation newOperationFromBuilder(
            ContentProviderOperation.Builder builder)
            throws NoSuchMethodException, InstantiationException, IllegalAccessException,
            InvocationTargetException {
        final Constructor constructor = CLASS_OPERATION.getDeclaredConstructor(CLASS_BUILDER);
        constructor.setAccessible(true);
        return (ContentProviderOperation) constructor.newInstance(builder);
    }

    private void builderSetSelectionArgsBackReferences(
            ContentProviderOperation.Builder builder, HashMap<Integer, Integer> selArgsBackRef)
            throws NoSuchFieldException, IllegalAccessException {
        Field field;
        field = CLASS_BUILDER.getDeclaredField("mSelectionArgsBackReferences");
        field.setAccessible(true);
        field.set(builder, selArgsBackRef);
    }

    private void builderSetValuesBackReferences(
            ContentProviderOperation.Builder builder, ContentValues valuesBackReferences)
            throws NoSuchFieldException, IllegalAccessException {
        Field field;
        field = CLASS_BUILDER.getDeclaredField("mValuesBackReferences");
        field.setAccessible(true);
        field.set(builder, valuesBackReferences);
    }

    private void builderSetSelection(
            ContentProviderOperation.Builder builder, String selection)
            throws NoSuchFieldException, IllegalAccessException {
        Field field;
        field = CLASS_BUILDER.getDeclaredField("mSelection");
        field.setAccessible(true);
        field.set(builder, selection);
    }

    private void builderSetSelectionArgs(
            ContentProviderOperation.Builder builder, String[] selArgs)
            throws NoSuchFieldException, IllegalAccessException {
        Field field;
        field = CLASS_BUILDER.getDeclaredField("mSelectionArgs");
        field.setAccessible(true);
        field.set(builder, selArgs);
    }

    private void builderSetValues(
            ContentProviderOperation.Builder builder, ContentValues values)
            throws NoSuchFieldException, IllegalAccessException {
        Field field;
        field = CLASS_BUILDER.getDeclaredField("mValues");
        field.setAccessible(true);
        field.set(builder, values);
    }

    private void builderSetExpectedCount(
            ContentProviderOperation.Builder builder, Integer expectedCount)
            throws NoSuchFieldException, IllegalAccessException {
        Field field;
        field = CLASS_BUILDER.getDeclaredField("mExpectedCount");
        field.setAccessible(true);
        field.set(builder, expectedCount);
    }

    private int operationGetType(ContentProviderOperation operation)
            throws NoSuchFieldException, IllegalAccessException {
        final Field field = CLASS_OPERATION.getDeclaredField("mType");
        field.setAccessible(true);
        return field.getInt(operation);
    }

    private Uri operationGetUri(ContentProviderOperation operation)
            throws NoSuchFieldException, IllegalAccessException {
        final Field field = CLASS_OPERATION.getDeclaredField("mUri");
        field.setAccessible(true);
        return (Uri) field.get(operation);
    }

    private String operationGetSelection(ContentProviderOperation operation)
            throws NoSuchFieldException, IllegalAccessException {
        final Field field = CLASS_OPERATION.getDeclaredField("mSelection");
        field.setAccessible(true);
        return (String) field.get(operation);
    }

    private String[] operationGetSelectionArgs(ContentProviderOperation operation)
            throws NoSuchFieldException, IllegalAccessException {
        final Field field = CLASS_OPERATION.getDeclaredField("mSelectionArgs");
        field.setAccessible(true);
        return (String[]) field.get(operation);
    }

    private ContentValues operationGetValues(ContentProviderOperation operation)
            throws NoSuchFieldException, IllegalAccessException {
        final Field field = CLASS_OPERATION.getDeclaredField("mValues");
        field.setAccessible(true);
        return (ContentValues) field.get(operation);
    }

    private Integer operationGetExpectedCount(ContentProviderOperation operation)
            throws NoSuchFieldException, IllegalAccessException {
        final Field field = CLASS_OPERATION.getDeclaredField("mExpectedCount");
        field.setAccessible(true);
        return (Integer) field.get(operation);
    }

    private ContentValues operationGetValuesBackReferences(ContentProviderOperation operation)
            throws NoSuchFieldException, IllegalAccessException {
        final Field field = CLASS_OPERATION.getDeclaredField("mValuesBackReferences");
        field.setAccessible(true);
        return (ContentValues) field.get(operation);
    }

    private Map<Integer, Integer> operationGetSelectionArgsBackReferences(
            ContentProviderOperation operation)
            throws NoSuchFieldException, IllegalAccessException {
        final Field field = CLASS_OPERATION.getDeclaredField("mSelectionArgsBackReferences");
        field.setAccessible(true);
        return (Map<Integer, Integer>) field.get(operation);
    }

    public void testParcelingResult() {
        Parcel parcel = Parcel.obtain();
        ContentProviderResult result1;
        ContentProviderResult result2;
        try {
            result1 = new ContentProviderResult(Uri.parse("content://goo/bar"));
            result1.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            result2 = ContentProviderResult.CREATOR.createFromParcel(parcel);
            assertEquals("content://goo/bar", result2.uri.toString());
            assertNull(result2.count);
        } finally {
            parcel.recycle();
        }

        parcel = Parcel.obtain();
        try {
            result1 = new ContentProviderResult(42);
            result1.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            result2 = ContentProviderResult.CREATOR.createFromParcel(parcel);
            assertEquals(Integer.valueOf(42), result2.count);
            assertNull(result2.uri);
        } finally {
            parcel.recycle();
        }
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