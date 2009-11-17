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
import android.pim.vcard.VCardEntry;
import android.pim.vcard.VCardEntryCommitter;
import android.pim.vcard.VCardEntryHandler;
import android.pim.vcard.VCardInterpreter;
import android.pim.vcard.VCardInterpreterCollection;
import android.pim.vcard.VCardComposer;
import android.pim.vcard.VCardConfig;
import android.pim.vcard.VCardEntryConstructor;
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

/**
 * BaseClass for vCard unit tests with utility classes.
 * Please do not add each unit test here.
 */
/* package */ class VCardTestsBase extends AndroidTestCase {
    public static final int V21 = VCardConfig.VCARD_TYPE_V21_GENERIC_UTF8;
    public static final int V30 = VCardConfig.VCARD_TYPE_V30_GENERIC_UTF8;

    // Do not modify these during tests.
    protected final ContentValues mContentValuesForQP;
    protected final ContentValues mContentValuesForSJis;
    protected final ContentValues mContentValuesForUtf8;
    protected final ContentValues mContentValuesForQPAndSJis;
    protected final ContentValues mContentValuesForQPAndUtf8;
    protected final ContentValues mContentValuesForBase64V21;
    protected final ContentValues mContentValuesForBase64V30;

    public VCardTestsBase() {
        super();
        mContentValuesForQP = new ContentValues();
        mContentValuesForQP.put("ENCODING", "QUOTED-PRINTABLE");
        mContentValuesForSJis = new ContentValues();
        mContentValuesForSJis.put("CHARSET", "SHIFT_JIS");
        mContentValuesForUtf8 = new ContentValues();
        mContentValuesForUtf8.put("CHARSET", "UTF-8");
        mContentValuesForQPAndSJis = new ContentValues();
        mContentValuesForQPAndSJis.put("ENCODING", "QUOTED-PRINTABLE");
        mContentValuesForQPAndSJis.put("CHARSET", "SHIFT_JIS");
        mContentValuesForQPAndUtf8 = new ContentValues();
        mContentValuesForQPAndUtf8.put("ENCODING", "QUOTED-PRINTABLE");
        mContentValuesForQPAndUtf8.put("CHARSET", "UTF-8");
        mContentValuesForBase64V21 = new ContentValues();
        mContentValuesForBase64V21.put("ENCODING", "BASE64");
        mContentValuesForBase64V30 = new ContentValues();
        mContentValuesForBase64V30.put("ENCODING", "b");
    }

    public class ImportTestResolver extends MockContentResolver {
        ImportTestProvider mProvider = new ImportTestProvider();
        @Override
        public ContentProviderResult[] applyBatch(String authority,
                ArrayList<ContentProviderOperation> operations) {
            equalsString(authority, RawContacts.CONTENT_URI.toString());
            return mProvider.applyBatch(operations);
        }

        public void addExpectedContentValues(ContentValues expectedContentValues) {
            mProvider.addExpectedContentValues(expectedContentValues);
        }

        public void verify() {
            mProvider.verify();
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

    public class ImportTestProvider extends MockContentProvider {
        final Map<String, Collection<ContentValues>> mMimeTypeToExpectedContentValues;

        public ImportTestProvider() {
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
                        /*for testing
                        Log.d("@@@", "expected: "
                                + convertToEasilyReadableString(expectedContentValues));
                        Log.d("@@@", "actual  : "
                                + convertToEasilyReadableString(actualContentValues));*/
                        if (equalsForContentValues(expectedContentValues,
                                actualContentValues)) {
                            assertTrue(contentValuesCollection.remove(expectedContentValues));
                            checked = true;
                            break;
                        }
                    }
                    if (!checked) {
                        final StringBuilder builder = new StringBuilder();
                        builder.append("Unexpected: ");
                        builder.append(convertToEasilyReadableString(actualContentValues));
                        builder.append("\nExpected: ");
                        for (ContentValues expectedContentValues : contentValuesCollection) {
                            builder.append(convertToEasilyReadableString(expectedContentValues));
                        }
                        fail(builder.toString());
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

    class ImportVerifierElem {
        private final ImportTestResolver mResolver;
        private final VCardEntryHandler mHandler;

        public ImportVerifierElem() {
            mResolver = new ImportTestResolver();
            mHandler = new VCardEntryCommitter(mResolver);
        }

        public ContentValuesBuilder addExpected(String mimeType) {
            ContentValues contentValues = new ContentValues();
            contentValues.put(Data.MIMETYPE, mimeType);
            mResolver.addExpectedContentValues(contentValues);
            return new ContentValuesBuilder(contentValues);
        }

        public void verify(int resId, int vCardType)
                throws IOException, VCardException {
            verify(getContext().getResources().openRawResource(resId), vCardType);
        }

        public void verify(InputStream is, int vCardType) throws IOException, VCardException {
            final VCardParser vCardParser;
            if (VCardConfig.isV30(vCardType)) {
                vCardParser = new VCardParser_V30(true);  // use StrictParsing
            } else {
                vCardParser = new VCardParser_V21();
            }
            VCardEntryConstructor builder =
                    new VCardEntryConstructor(null, null, false, vCardType, null);
            builder.addEntryHandler(mHandler);
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
            verifyResolver();
        }

        public void verifyResolver() {
            mResolver.verify();
        }

        public void onParsingStart() {
            mHandler.onStart();
        }

        public void onEntryCreated(VCardEntry entry) {
            mHandler.onEntryCreated(entry);
        }

        public void onParsingEnd() {
            mHandler.onEnd();
        }
    }

    class ImportVerifier implements VCardEntryHandler {
        private List<ImportVerifierElem> mImportVerifierElemList =
            new ArrayList<ImportVerifierElem>();
        private int mIndex;

        public ImportVerifierElem addImportVerifierElem() {
            ImportVerifierElem importVerifier = new ImportVerifierElem();
            mImportVerifierElemList.add(importVerifier);
            return importVerifier;
        }

        public void verify(int resId, int vCardType) throws IOException, VCardException {
            verify(getContext().getResources().openRawResource(resId), vCardType);
        }

        public void verify(int resId, int vCardType, final VCardParser vCardParser)
                throws IOException, VCardException {
            verify(getContext().getResources().openRawResource(resId), vCardType, vCardParser);
        }

        public void verify(InputStream is, int vCardType) throws IOException, VCardException {
            final VCardParser vCardParser;
            if (VCardConfig.isV30(vCardType)) {
                vCardParser = new VCardParser_V30(true);  // use StrictParsing
            } else {
                vCardParser = new VCardParser_V21();
            }
            verify(is, vCardType, vCardParser);
        }

        public void verify(InputStream is, int vCardType, final VCardParser vCardParser)
                throws IOException, VCardException {
            VCardEntryConstructor builder =
                new VCardEntryConstructor(null, null, false, vCardType, null);
            builder.addEntryHandler(this);
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
        }

        public void onStart() {
            for (ImportVerifierElem elem : mImportVerifierElemList) {
                elem.onParsingStart();
            }
        }

        public void onEntryCreated(VCardEntry entry) {
            assertTrue(mIndex < mImportVerifierElemList.size());
            mImportVerifierElemList.get(mIndex).onEntryCreated(entry);
            mIndex++;
        }

        public void onEnd() {
            for (ImportVerifierElem elem : mImportVerifierElemList) {
                elem.onParsingEnd();
                elem.verifyResolver();
            }
        }
    }

    public class ExportTestResolver extends MockContentResolver {
        ExportTestProvider mProvider = new ExportTestProvider();
        public ExportTestResolver() {
            addProvider(VCardComposer.VCARD_TEST_AUTHORITY, mProvider);
            addProvider(RawContacts.CONTENT_URI.getAuthority(), mProvider);
        }

        public ContactEntry buildContactEntry() {
            return mProvider.buildInputEntry();
        }
    }

    public static class MockEntityIterator implements EntityIterator {
        List<Entity> mEntityList;
        Iterator<Entity> mIterator;

        public MockEntityIterator(List<ContentValues> contentValuesList) {
            mEntityList = new ArrayList<Entity>();
            Entity entity = new Entity(new ContentValues());
            for (ContentValues contentValues : contentValuesList) {
                    entity.addSubValue(Data.CONTENT_URI, contentValues);
            }
            mEntityList.add(entity);
            mIterator = mEntityList.iterator();
        }

        public boolean hasNext() {
            return mIterator.hasNext();
        }

        public Entity next() {
            return mIterator.next();
        }

        public void reset() {
            mIterator = mEntityList.iterator();
        }

        public void close() {
        }
    }

    /**
     * Represents one contact, which should contain multiple ContentValues like
     * StructuredName, Email, etc.
     */
    static class ContactEntry {
        private final List<ContentValues> mContentValuesList = new ArrayList<ContentValues>();

        public ContentValuesBuilder buildData(String mimeType) {
            ContentValues contentValues = new ContentValues();
            contentValues.put(Data.MIMETYPE, mimeType);
            mContentValuesList.add(contentValues);
            return new ContentValuesBuilder(contentValues);
        }

        public List<ContentValues> getList() {
            return mContentValuesList;
        }
    }

    class ExportTestProvider extends MockContentProvider {
        ArrayList<ContactEntry> mContactEntryList = new ArrayList<ContactEntry>();

        public ContactEntry buildInputEntry() {
            ContactEntry contactEntry = new ContactEntry();
            mContactEntryList.add(contactEntry);
            return contactEntry;
        }

        @Override
        public EntityIterator queryEntities(Uri uri, String selection, String[] selectionArgs,
                String sortOrder) {
            assertTrue(uri != null);
            assertTrue(ContentResolver.SCHEME_CONTENT.equals(uri.getScheme()));
            final String authority = uri.getAuthority();
            assertTrue(RawContacts.CONTENT_URI.getAuthority().equals(authority));
            assertTrue((Data.CONTACT_ID + "=?").equals(selection));
            assertEquals(1, selectionArgs.length);
            int id = Integer.parseInt(selectionArgs[0]);
            assertTrue(id >= 0 && id < mContactEntryList.size());

            return new MockEntityIterator(mContactEntryList.get(id).getList());
        }

        @Override
        public Cursor query(Uri uri, String[] projection,
                String selection, String[] selectionArgs, String sortOrder) {
            assertTrue(VCardComposer.CONTACTS_TEST_CONTENT_URI.equals(uri));
            // In this test, following arguments are not supported.
            assertNull(selection);
            assertNull(selectionArgs);
            assertNull(sortOrder);

            return new MockCursor() {
                int mCurrentPosition = -1;

                @Override
                public int getCount() {
                    return mContactEntryList.size();
                }

                @Override
                public boolean moveToFirst() {
                    mCurrentPosition = 0;
                    return true;
                }

                @Override
                public boolean moveToNext() {
                    if (mCurrentPosition < mContactEntryList.size()) {
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
                    return mCurrentPosition >= mContactEntryList.size();
                }

                @Override
                public int getColumnIndex(String columnName) {
                    assertEquals(Contacts._ID, columnName);
                    return 0;
                }

                @Override
                public int getInt(int columnIndex) {
                    assertEquals(0, columnIndex);
                    assertTrue(mCurrentPosition >= 0
                            && mCurrentPosition < mContactEntryList.size());
                    return mCurrentPosition;
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

    class LineVerifierElem {
        private final List<String> mExpectedLineList = new ArrayList<String>();
        private final boolean mIsV30;

        public LineVerifierElem(boolean isV30) {
            mIsV30 = isV30;
        }

        public LineVerifierElem addExpected(final String line) {
            if (!TextUtils.isEmpty(line)) {
                mExpectedLineList.add(line);
            }
            return this;
        }

        public void verify(final String vcard) {
            final String[] lineArray = vcard.split("\\r?\\n");
            final int length = lineArray.length;
            final TestCase testCase = VCardTestsBase.this;
            boolean beginExists = false;
            boolean endExists = false;
            boolean versionExists = false;

            for (int i = 0; i < length; i++) {
                final String line = lineArray[i];
                if (TextUtils.isEmpty(line)) {
                    continue;
                }

                if ("BEGIN:VCARD".equalsIgnoreCase(line)) {
                    if (beginExists) {
                        testCase.fail("Multiple \"BEGIN:VCARD\" line found");
                    } else {
                        beginExists = true;
                        continue;
                    }
                } else if ("END:VCARD".equalsIgnoreCase(line)) {
                    if (endExists) {
                        testCase.fail("Multiple \"END:VCARD\" line found");
                    } else {
                        endExists = true;
                        continue;
                    }
                } else if (
                        (mIsV30 ? "VERSION:3.0" : "VERSION:2.1").equalsIgnoreCase(line)) {
                    if (versionExists) {
                        testCase.fail("Multiple VERSION line + found");
                    } else {
                        versionExists = true;
                        continue;
                    }
                }

                if (!beginExists) {
                    testCase.fail(
                            "Property other than BEGIN came before BEGIN property: " + line);
                } else if (endExists) {
                    testCase.fail("Property other than END came after END property: " + line);
                }

                final int index = mExpectedLineList.indexOf(line);
                if (index >= 0) {
                    mExpectedLineList.remove(index);
                } else {
                    testCase.fail("Unexpected line: " + line);
                }
            }

            if (!mExpectedLineList.isEmpty()) {
                StringBuffer buffer = new StringBuffer();
                for (String expectedLine : mExpectedLineList) {
                    buffer.append(expectedLine);
                    buffer.append("\n");
                }

                testCase.fail("Expected line(s) not found:" + buffer.toString());
            }
        }
    }

    class LineVerifier implements VCardComposer.OneEntryHandler {
        private final ArrayList<LineVerifierElem> mLineVerifierElemList;
        private final boolean mIsV30;
        private int index;

        public LineVerifier(final boolean isV30) {
            mLineVerifierElemList = new ArrayList<LineVerifierElem>();
            mIsV30 = isV30;
        }

        public LineVerifierElem addLineVerifierElem() {
            LineVerifierElem lineVerifier = new LineVerifierElem(mIsV30);
            mLineVerifierElemList.add(lineVerifier);
            return lineVerifier;
        }

        public void verify(String vcard) {
            if (index >= mLineVerifierElemList.size()) {
                VCardTestsBase.this.fail("Insufficient number of LineVerifier (" + index + ")");
            }

            LineVerifierElem lineVerifier = mLineVerifierElemList.get(index);
            lineVerifier.verify(vcard);

            index++;
        }

        public boolean onEntryCreated(String vcard) {
            verify(vcard);
            return true;
        }

        public boolean onInit(Context context) {
            return true;
        }

        public void onTerminate() {
        }
    }

    class VCardVerifier {
        private class VCardVerifierInternal implements VCardComposer.OneEntryHandler {
            public boolean onInit(Context context) {
                return true;
            }
            public boolean onEntryCreated(String vcard) {
                verifyOneVCard(vcard);
                return true;
            }
            public void onTerminate() {
            }
        }

        private final VCardVerifierInternal mVCardVerifierInternal;
        private final ExportTestResolver mResolver;
        private final int mVCardType;
        private final boolean mIsV30;
        private final boolean mIsDoCoMo;

        // To allow duplication, use list instead of set.
        // When null, we don't need to do the verification.
        private PropertyNodesVerifier mPropertyNodesVerifier;
        private LineVerifier mLineVerificationHandler;
        private ImportVerifier mImportVerifier;

        public VCardVerifier(ExportTestResolver resolver, int vcardType) {
            mVCardVerifierInternal = new VCardVerifierInternal();
            mResolver = resolver;
            mIsV30 = VCardConfig.isV30(vcardType);
            mIsDoCoMo = VCardConfig.isDoCoMo(vcardType);
            mVCardType = vcardType;
        }

        public PropertyNodesVerifierElem addPropertyNodesVerifierElem() {
            if (mPropertyNodesVerifier == null) {
                mPropertyNodesVerifier = new PropertyNodesVerifier(VCardTestsBase.this);
            }
            PropertyNodesVerifierElem elem =
                    mPropertyNodesVerifier.addPropertyNodesVerifierElem();
            elem.addNodeWithOrder("VERSION", (mIsV30 ? "3.0" : "2.1"));

            return elem;
        }

        public PropertyNodesVerifierElem addPropertyNodesVerifierElemWithEmptyName() {
            PropertyNodesVerifierElem elem = addPropertyNodesVerifierElem();
            if (mIsV30) {
                elem.addNodeWithOrder("N", "").addNodeWithOrder("FN", "");
            } else if (mIsDoCoMo) {
                elem.addNodeWithOrder("N", "");
            }
            return elem;
        }

        public LineVerifierElem addLineVerifier() {
            if (mLineVerificationHandler == null) {
                mLineVerificationHandler = new LineVerifier(mIsV30);
            }
            return mLineVerificationHandler.addLineVerifierElem();
        }

        public ImportVerifierElem addImportVerifier() {
            if (mImportVerifier == null) {
                mImportVerifier = new ImportVerifier();
            }

            return mImportVerifier.addImportVerifierElem();
        }

        private void verifyOneVCard(final String vcard) {
            // Log.d("@@@", vcard);
            final VCardInterpreter builder;
            if (mImportVerifier != null) {
                final VNodeBuilder vnodeBuilder = mPropertyNodesVerifier;
                final VCardEntryConstructor vcardDataBuilder =
                        new VCardEntryConstructor(mVCardType);
                vcardDataBuilder.addEntryHandler(mImportVerifier);
                if (mPropertyNodesVerifier != null) {
                    builder = new VCardInterpreterCollection(Arrays.asList(
                            mPropertyNodesVerifier, vcardDataBuilder));
                } else {
                    builder = vnodeBuilder;
                }
            } else {
                if (mPropertyNodesVerifier != null) {
                    builder = mPropertyNodesVerifier;
                } else {
                    return;
                }
            }

            final VCardParser parser =
                    (mIsV30 ? new VCardParser_V30(true) : new VCardParser_V21());
            final TestCase testCase = VCardTestsBase.this;

            InputStream is = null;
            try {
                String charset =
                    (VCardConfig.usesShiftJis(mVCardType) ? "SHIFT_JIS" : "UTF-8");
                is = new ByteArrayInputStream(vcard.getBytes(charset));
                testCase.assertEquals(true, parser.parse(is, null, builder));
            } catch (IOException e) {
                testCase.fail("Unexpected IOException: " + e.getMessage());
            } catch (VCardException e) {
                testCase.fail("Unexpected VCardException: " + e.getMessage());
            } finally {
                if (is != null) {
                    try {
                        is.close();
                    } catch (IOException e) {
                    }
                }
            }
        }

        public void verify() {
            VCardComposer composer =
                    new VCardComposer(new CustomMockContext(mResolver), mVCardType);
            composer.addHandler(mLineVerificationHandler);
            composer.addHandler(mVCardVerifierInternal);
            if (!composer.init(VCardComposer.CONTACTS_TEST_CONTENT_URI, null, null, null)) {
                fail("init() failed. Reason: " + composer.getErrorReason());
            }
            assertFalse(composer.isAfterLast());
            try {
                while (!composer.isAfterLast()) {
                    assertTrue(composer.createOneEntry());
                }
            } finally {
                composer.terminate();
            }
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