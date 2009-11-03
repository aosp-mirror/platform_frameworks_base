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

package com.android.unit_tests.vcard;

import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Entity;
import android.content.EntityIterator;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.CursorWindow;
import android.database.IBulkCursor;
import android.database.IContentObserver;
import android.net.Uri;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.pim.vcard.EntryCommitter;
import android.pim.vcard.VCardComposer;
import android.pim.vcard.VCardConfig;
import android.pim.vcard.VCardDataBuilder;
import android.pim.vcard.VCardParser;
import android.pim.vcard.VCardParser_V21;
import android.pim.vcard.VCardParser_V30;
import android.pim.vcard.exception.VCardException;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Event;
import android.provider.ContactsContract.CommonDataKinds.GroupMembership;
import android.provider.ContactsContract.CommonDataKinds.Im;
import android.provider.ContactsContract.CommonDataKinds.Nickname;
import android.provider.ContactsContract.CommonDataKinds.Note;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.Photo;
import android.provider.ContactsContract.CommonDataKinds.Relation;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.provider.ContactsContract.CommonDataKinds.Website;
import android.test.AndroidTestCase;
import android.test.mock.MockContentResolver;
import android.test.mock.MockContext;
import android.test.mock.MockCursor;
import android.text.TextUtils;

import junit.framework.TestCase;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.Map.Entry;

/**
 * Almost a dead copy of android.test.mock.MockContentProvider, but different in that this
 * class extends ContentProvider, not implementing IContentProvider,
 * so that MockContentResolver is able to accept this class :(
 */
class MockContentProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public int bulkInsert(Uri url, ContentValues[] initialValues) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @SuppressWarnings("unused")
    public IBulkCursor bulkQuery(Uri url, String[] projection, String selection,
            String[] selectionArgs, String sortOrder, IContentObserver observer,
            CursorWindow window) throws RemoteException {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    @SuppressWarnings("unused")
    public int delete(Uri url, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public String getType(Uri url) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public Uri insert(Uri url, ContentValues initialValues) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public ParcelFileDescriptor openFile(Uri url, String mode) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public Cursor query(Uri url, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    /**
     * @hide
     */
    @Override
    public EntityIterator queryEntities(Uri url, String selection, String[] selectionArgs,
            String sortOrder) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    @Override
    public int update(Uri url, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("unimplemented mock method");
    }

    public IBinder asBinder() {
        throw new UnsupportedOperationException("unimplemented mock method");
    }
}

class CustomMockContext extends MockContext {
    final ContentResolver mResolver;
    public CustomMockContext(ContentResolver resolver) {
        mResolver = resolver;
    }

    @Override
    public ContentResolver getContentResolver() {
        return mResolver;
    }
}

class ContentValuesBuilder {
    private final ContentValues mContentValues;

    public ContentValuesBuilder(final ContentValues contentValues) {
        mContentValues = contentValues;
    }

    public ContentValuesBuilder put(String key, String value) {
        mContentValues.put(key, value);
        return this;
    }

    public ContentValuesBuilder put(String key, Byte value) {
        mContentValues.put(key, value);
        return this;
    }

    public ContentValuesBuilder put(String key, Short value) {
        mContentValues.put(key, value);
        return this;
    }

    public ContentValuesBuilder put(String key, Integer value) {
        mContentValues.put(key, value);
        return this;
    }

    public ContentValuesBuilder put(String key, Long value) {
        mContentValues.put(key, value);
        return this;
    }

    public ContentValuesBuilder put(String key, Float value) {
        mContentValues.put(key, value);
        return this;
    }

    public ContentValuesBuilder put(String key, Double value) {
        mContentValues.put(key, value);
        return this;
    }

    public ContentValuesBuilder put(String key, Boolean value) {
        mContentValues.put(key, value);
        return this;
    }

    public ContentValuesBuilder put(String key, byte[] value) {
        mContentValues.put(key, value);
        return this;
    }

    public ContentValuesBuilder putNull(String key) {
        mContentValues.putNull(key);
        return this;
    }
}

/**
 * BaseClass for vCard unit tests with utility classes.
 * Please do not add each unit test here.
 */
/* package */ class VCardTestsBase extends AndroidTestCase {
    public static final int V21 = 0;
    public static final int V30 = 1;

    public class ImportVerificationResolver extends MockContentResolver {
        ImportVerificationProvider mVerificationProvider = new ImportVerificationProvider();
        @Override
        public ContentProviderResult[] applyBatch(String authority,
                ArrayList<ContentProviderOperation> operations) {
            equalsString(authority, RawContacts.CONTENT_URI.toString());
            return mVerificationProvider.applyBatch(operations);
        }

        public void addExpectedContentValues(ContentValues expectedContentValues) {
            mVerificationProvider.addExpectedContentValues(expectedContentValues);
        }

        public void verify() {
            mVerificationProvider.verify();
        }
    }

    private static final Set<String> sKnownMimeTypeSet =
        new HashSet<String>(Arrays.asList(StructuredName.CONTENT_ITEM_TYPE,
                Nickname.CONTENT_ITEM_TYPE, Phone.CONTENT_ITEM_TYPE,
                Email.CONTENT_ITEM_TYPE, StructuredPostal.CONTENT_ITEM_TYPE,
                Im.CONTENT_ITEM_TYPE, Organization.CONTENT_ITEM_TYPE,
                Event.CONTENT_ITEM_TYPE, Photo.CONTENT_ITEM_TYPE,
                Note.CONTENT_ITEM_TYPE, Website.CONTENT_ITEM_TYPE,
                Relation.CONTENT_ITEM_TYPE, Event.CONTENT_ITEM_TYPE,
                GroupMembership.CONTENT_ITEM_TYPE));

    public class ImportVerificationProvider extends MockContentProvider {
        final Map<String, Collection<ContentValues>> mMimeTypeToExpectedContentValues;

        public ImportVerificationProvider() {
            mMimeTypeToExpectedContentValues =
                new HashMap<String, Collection<ContentValues>>();
            for (String acceptanbleMimeType : sKnownMimeTypeSet) {
                // Do not use HashSet since the current implementation changes the content of
                // ContentValues after the insertion, which make the result of hashCode()
                // changes...
                mMimeTypeToExpectedContentValues.put(
                        acceptanbleMimeType, new ArrayList<ContentValues>());
            }
        }

        public void addExpectedContentValues(ContentValues expectedContentValues) {
            final String mimeType = expectedContentValues.getAsString(Data.MIMETYPE);
            if (!sKnownMimeTypeSet.contains(mimeType)) {
                fail(String.format(
                        "Unknow MimeType %s in the test code. Test code should be broken.",
                        mimeType));
            }

            final Collection<ContentValues> contentValuesCollection =
                    mMimeTypeToExpectedContentValues.get(mimeType);
            contentValuesCollection.add(expectedContentValues);
        }

        @Override
        public ContentProviderResult[] applyBatch(
                ArrayList<ContentProviderOperation> operations) {
            if (operations == null) {
                fail("There is no operation.");
            }

            final int size = operations.size();
            ContentProviderResult[] fakeResultArray = new ContentProviderResult[size];
            for (int i = 0; i < size; i++) {
                Uri uri = Uri.withAppendedPath(RawContacts.CONTENT_URI, String.valueOf(i));
                fakeResultArray[i] = new ContentProviderResult(uri);
            }

            for (int i = 0; i < size; i++) {
                ContentProviderOperation operation = operations.get(i);
                ContentValues contentValues = operation.resolveValueBackReferences(
                        fakeResultArray, i);
            }
            for (int i = 0; i < size; i++) {
                ContentProviderOperation operation = operations.get(i);
                ContentValues actualContentValues = operation.resolveValueBackReferences(
                        fakeResultArray, i);
                final Uri uri = operation.getUri();
                if (uri.equals(RawContacts.CONTENT_URI)) {
                    assertNull(actualContentValues.get(RawContacts.ACCOUNT_NAME));
                    assertNull(actualContentValues.get(RawContacts.ACCOUNT_TYPE));
                } else if (uri.equals(Data.CONTENT_URI)) {
                    final String mimeType = actualContentValues.getAsString(Data.MIMETYPE);
                    if (!sKnownMimeTypeSet.contains(mimeType)) {
                        fail(String.format(
                                "Unknown MimeType %s. Probably added after developing this test",
                                mimeType));
                    }
                    // Remove data meaningless in this unit tests.
                    // Specifically, Data.DATA1 - DATA7 are set to null or empty String
                    // regardless of the input, but it may change depending on how
                    // resolver-related code handles it.
                    // Here, we ignore these implementation-dependent specs and
                    // just check whether vCard importer correctly inserts rellevent data.
                    Set<String> keyToBeRemoved = new HashSet<String>();
                    for (Entry<String, Object> entry : actualContentValues.valueSet()) {
                        Object value = entry.getValue();
                        if (value == null || TextUtils.isEmpty(value.toString())) {
                            keyToBeRemoved.add(entry.getKey());
                        }
                    }
                    for (String key: keyToBeRemoved) {
                        actualContentValues.remove(key);
                    }
                    /* for testing
                    Log.d("@@@",
                            String.format("MimeType: %s, data: %s",
                                    mimeType, actualContentValues.toString())); */
                    // Remove RAW_CONTACT_ID entry just for safety, since we do not care
                    // how resolver-related code handles the entry in this unit test,
                    if (actualContentValues.containsKey(Data.RAW_CONTACT_ID)) {
                        actualContentValues.remove(Data.RAW_CONTACT_ID);
                    }
                    final Collection<ContentValues> contentValuesCollection =
                        mMimeTypeToExpectedContentValues.get(mimeType);
                    if (contentValuesCollection.isEmpty()) {
                        fail("ContentValues for MimeType " + mimeType
                                + " is not expected at all (" + actualContentValues + ")");
                    }
                    boolean checked = false;
                    for (ContentValues expectedContentValues : contentValuesCollection) {
                        /* for testing
                        Log.d("@@@", "expected: "
                                + convertToEasilyReadableString(expectedContentValues));
                        Log.d("@@@", "actual  : "
                                + convertToEasilyReadableString(actualContentValues)); */
                        if (equalsForContentValues(expectedContentValues,
                                actualContentValues)) {
                            assertTrue(contentValuesCollection.remove(expectedContentValues));
                            checked = true;
                            break;
                        }
                    }
                    if (!checked) {
                        final String failMsg =
                            "Unexpected ContentValues for MimeType " + mimeType
                            + ": " + actualContentValues;
                        fail(failMsg);
                    }
                } else {
                    fail("Unexpected Uri has come: " + uri);
                }
            }  // for (int i = 0; i < size; i++) {
            return null;
        }

        public void verify() {
            StringBuilder builder = new StringBuilder();
            for (Collection<ContentValues> contentValuesCollection :
                    mMimeTypeToExpectedContentValues.values()) {
                for (ContentValues expectedContentValues: contentValuesCollection) {
                    builder.append(convertToEasilyReadableString(expectedContentValues));
                    builder.append("\n");
                }
            }
            if (builder.length() > 0) {
                final String failMsg =
                    "There is(are) remaining expected ContentValues instance(s): \n"
                        + builder.toString();
                fail(failMsg);
            }
        }
    }

    public class ContentValuesVerifier {
        private final ImportVerificationResolver mResolver;
        // private final String mCharset;

        public ContentValuesVerifier() {
            mResolver = new ImportVerificationResolver();
        }

        public ContentValuesBuilder buildExpected(String mimeType) {
            ContentValues contentValues = new ContentValues();
            contentValues.put(Data.MIMETYPE, mimeType);
            mResolver.addExpectedContentValues(contentValues);
            return new ContentValuesBuilder(contentValues);
        }

        public void verify(int resId, int vCardType)
                throws IOException, VCardException {
            verify(getContext().getResources().openRawResource(resId), vCardType);
        }

        public void verify(InputStream is, int vCardType)
                throws IOException, VCardException {
            final VCardParser vCardParser;
            if (VCardConfig.isV30(vCardType)) {
                vCardParser = new VCardParser_V30(true);  // use StrictParsing
            } else {
                vCardParser = new VCardParser_V21();
            }
            VCardDataBuilder builder =
                new VCardDataBuilder(null, null, false, vCardType, null);
            builder.addEntryHandler(new EntryCommitter(mResolver));
            try {
                vCardParser.parse(is, builder);
            } finally {
                if (is != null) {
                    try {
                        is.close();
                    } catch (IOException e) {
                    }
                }
            }
            mResolver.verify();
        }
    }

    public class ExportTestResolver extends MockContentResolver {
        ExportTestProvider mProvider = new ExportTestProvider();
        public ExportTestResolver() {
            addProvider(VCardComposer.VCARD_TEST_AUTHORITY, mProvider);
            addProvider(RawContacts.CONTENT_URI.getAuthority(), mProvider);
        }

        public ContentValuesBuilder buildInput(String mimeType) {
            return mProvider.buildData(mimeType);
        }
    }

    public static class MockEntityIterator implements EntityIterator {
        Collection<Entity> mEntityCollection;
        Iterator<Entity> mIterator;

        // TODO: Support multiple vCard entries.
        public MockEntityIterator(Collection<ContentValues> contentValuesCollection) {
            mEntityCollection = new ArrayList<Entity>();
            Entity entity = new Entity(new ContentValues());
            for (ContentValues contentValues : contentValuesCollection) {
                entity.addSubValue(Data.CONTENT_URI, contentValues);
            }
            mEntityCollection.add(entity);
            mIterator = mEntityCollection.iterator();
        }

        public boolean hasNext() {
            return mIterator.hasNext();
        }

        public Entity next() {
            return mIterator.next();
        }

        public void reset() {
            mIterator = mEntityCollection.iterator();
        }

        public void close() {
        }
    }

    public class ExportTestProvider extends MockContentProvider {
        List<ContentValues> mContentValuesList = new ArrayList<ContentValues>();
        public ContentValuesBuilder buildData(String mimeType) {
            ContentValues contentValues = new ContentValues();
            contentValues.put(Data.MIMETYPE, mimeType);
            mContentValuesList.add(contentValues);
            return new ContentValuesBuilder(contentValues);
        }

        @Override
        public EntityIterator queryEntities(Uri uri, String selection, String[] selectionArgs,
                String sortOrder) {
            assert(uri != null);
            assert(ContentResolver.SCHEME_CONTENT.equals(uri.getScheme()));
            final String authority = uri.getAuthority();
            assert(RawContacts.CONTENT_URI.getAuthority().equals(authority));

            return new MockEntityIterator(mContentValuesList);
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                String sortOrder) {
            assert(VCardComposer.CONTACTS_TEST_CONTENT_URI.equals(uri));
            // Support multiple rows.
            return new MockCursor() {
                int mCurrentPosition = -1;

                @Override
                public int getCount() {
                    return 1;
                }

                @Override
                public boolean moveToFirst() {
                    mCurrentPosition = 0;
                    return true;
                }

                @Override
                public boolean moveToNext() {
                    if (mCurrentPosition == 0 || mCurrentPosition == -1) {
                        mCurrentPosition++;
                        return true;
                    } else {
                        return false;
                    }
                }

                @Override
                public boolean isBeforeFirst() {
                    return mCurrentPosition < 0;
                }

                @Override
                public boolean isAfterLast() {
                    return mCurrentPosition > 0;
                }

                @Override
                public int getColumnIndex(String columnName) {
                    assertEquals(Contacts._ID, columnName);
                    return 0;
                }

                @Override
                public int getInt(int columnIndex) {
                    assertEquals(0, columnIndex);
                    return 0;
                }

                @Override
                public String getString(int columnIndex) {
                    return String.valueOf(getInt(columnIndex));
                }

                @Override
                public void close() {
                }
            };
        }
    }

    public class VCardVerificationHandler implements VCardComposer.OneEntryHandler {
        final private TestCase mTestCase;
        final private List<PropertyNodesVerifier> mPropertyNodesVerifierList;
        final private boolean mIsV30;
        // To allow duplication, use list instead of set.
        // TODO: support multiple vCard entries.
        final private List<String> mExpectedLineList;
        final private List<ContentValuesVerifier> mContentValuesVerifierList;
        final private int mVCardType;
        int mCount;

        public VCardVerificationHandler(final TestCase testCase, final int version) {
            mTestCase = testCase;
            mPropertyNodesVerifierList = new ArrayList<PropertyNodesVerifier>();
            mIsV30 = (version == V30);
            mExpectedLineList = new ArrayList<String>();
            mContentValuesVerifierList = new ArrayList<ContentValuesVerifier>();
            mVCardType = (version == V30 ? VCardConfig.VCARD_TYPE_V30_GENERIC_UTF8
                    : VCardConfig.VCARD_TYPE_V21_GENERIC_UTF8);
            mCount = 1;
        }

        public PropertyNodesVerifier addPropertyNodesVerifier() {
            PropertyNodesVerifier verifier = new PropertyNodesVerifier(mTestCase);
            mPropertyNodesVerifierList.add(verifier);
            verifier.addNodeWithOrder("VERSION", mIsV30 ? "3.0" : "2.1");
            return verifier;
        }

        public PropertyNodesVerifier addPropertyVerifierWithEmptyName() {
            PropertyNodesVerifier verifier = addPropertyNodesVerifier();
            if (mIsV30) {
                verifier.addNodeWithOrder("N", "").addNodeWithOrder("FN", "");
            }
            return verifier;
        }

        public ContentValuesVerifier addContentValuesVerifier() {
            ContentValuesVerifier verifier = new ContentValuesVerifier();
            mContentValuesVerifierList.add(verifier);
            return verifier;
        }

        public VCardVerificationHandler addExpectedLine(String line) {
            mExpectedLineList.add(line);
            return this;
        }

        public boolean onInit(final Context context) {
            return true;
        }

        public boolean onEntryCreated(final String vcard) {
            if (!mExpectedLineList.isEmpty()) {
                verifyLines(vcard);
            }
            verifyNodes(vcard);
            return true;
        }

        private void verifyLines(final String vcard) {
            final String[] lineArray = vcard.split("\\r?\\n");
            final int length = lineArray.length;
            for (int i = 0; i < length; i++) {
                final String line = lineArray[i];
                // TODO: support multiple vcard entries.
                if ("BEGIN:VCARD".equals(line) || "END:VCARD".equals(line) ||
                        (mIsV30 ? "VERSION:3.0" : "VERSION:2.1").equals(line)) {
                    continue;
                }
                final int index = mExpectedLineList.indexOf(line);
                if (index >= 0) {
                    mExpectedLineList.remove(index);
                } else {
                    mTestCase.fail("Unexpected line: " + line);
                }
            }
            if (!mExpectedLineList.isEmpty()) {
                StringBuffer buffer = new StringBuffer();
                for (String expectedLine : mExpectedLineList) {
                    buffer.append(expectedLine);
                    buffer.append("\n");
                }
                mTestCase.fail("Expected line(s) not found:" + buffer.toString());
            }
        }

        private void verifyNodes(final String vcard) {
            if (mPropertyNodesVerifierList.size() == 0) {
                mTestCase.fail("Too many vCard entries seems to be inserted(No."
                        + mCount + " of the entries (No.1 is the first entry))");
            }
            PropertyNodesVerifier propertyNodesVerifier =
                    mPropertyNodesVerifierList.get(0);
            mPropertyNodesVerifierList.remove(0);
            VCardParser parser = (mIsV30 ? new VCardParser_V30(true) : new VCardParser_V21());
            VNodeBuilder builder = new VNodeBuilder();
            InputStream is;
            try {
                is = new ByteArrayInputStream(vcard.getBytes("UTF-8"));
                mTestCase.assertEquals(true, parser.parse(is, null, builder));
                is.close();
                mTestCase.assertEquals(1, builder.vNodeList.size());
                propertyNodesVerifier.verify(builder.vNodeList.get(0));
                if (!mContentValuesVerifierList.isEmpty()) {
                    ContentValuesVerifier contentValuesVerifier =
                            mContentValuesVerifierList.get(0);
                    is = new ByteArrayInputStream(vcard.getBytes("UTF-8"));
                    contentValuesVerifier.verify(is, mVCardType);
                    is.close();
                }
            } catch (IOException e) {
                mTestCase.fail("Unexpected IOException: " + e.getMessage());
            } catch (VCardException e) {
                mTestCase.fail("Unexpected VCardException: " + e.getMessage());
            } finally {
                mCount++;
            }
        }

        public void onTerminate() {
        }
    }

    /**
     * Utility method to print ContentValues whose content is printed with sorted keys.
     */
    private static String convertToEasilyReadableString(ContentValues contentValues) {
        if (contentValues == null) {
            return "null";
        }
        String mimeTypeValue = "";
        SortedMap<String, String> sortedMap = new TreeMap<String, String>();
        for (Entry<String, Object> entry : contentValues.valueSet()) {
            final String key = entry.getKey();
            final Object value = entry.getValue();
            final String valueString = (value != null ? value.toString() : null);
            if (Data.MIMETYPE.equals(key)) {
                mimeTypeValue = valueString;
            } else {
                assertNotNull(key);
                sortedMap.put(key, valueString);
            }
        }
        StringBuilder builder = new StringBuilder();
        builder.append(Data.MIMETYPE);
        builder.append('=');
        builder.append(mimeTypeValue);
        for (Entry<String, String> entry : sortedMap.entrySet()) {
            final String key = entry.getKey();
            final String value = entry.getValue();
            builder.append(' ');
            builder.append(key);
            builder.append('=');
            builder.append(value);
        }
        return builder.toString();
    }

    private static boolean equalsForContentValues(
            ContentValues expected, ContentValues actual) {
        if (expected == actual) {
            return true;
        } else if (expected == null || actual == null || expected.size() != actual.size()) {
            return false;
        }
        for (Entry<String, Object> entry : expected.valueSet()) {
            final String key = entry.getKey();
            final Object value = entry.getValue();
            if (!actual.containsKey(key)) {
                return false;
            }
            if (value instanceof byte[]) {
                Object actualValue = actual.get(key);
                if (!Arrays.equals((byte[])value, (byte[])actualValue)) {
                    return false;
                }
            } else if (!value.equals(actual.get(key))) {
                    return false;
            }
        }
        return true;
    }

    private static boolean equalsString(String a, String b) {
        if (a == null || a.length() == 0) {
            return b == null || b.length() == 0;
        } else {
            return a.equals(b);
        }
    }
}