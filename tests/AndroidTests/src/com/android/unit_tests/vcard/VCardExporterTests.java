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

import com.android.unit_tests.vcard.PropertyNodesVerifier.TypeSet;

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
import android.pim.vcard.VCardComposer;
import android.pim.vcard.VCardConfig;
import android.pim.vcard.VCardParser;
import android.pim.vcard.VCardParser_V21;
import android.pim.vcard.VCardParser_V30;
import android.pim.vcard.exception.VCardException;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;
import android.provider.ContactsContract.CommonDataKinds.Photo;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.test.AndroidTestCase;
import android.test.mock.MockContentResolver;
import android.test.mock.MockContext;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import junit.framework.TestCase;

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

/**
 * Tests for the code related to vCard exporter, inculding vCard composer.
 * This test class depends on vCard importer code, so if tests for vCard importer fail,
 * the result of this class will not be reliable.
 */
public class VCardExporterTests extends AndroidTestCase {
    /* package */ static final byte[] sPhotoByteArray =
        VCardImporterTests.sPhotoByteArrayForComplicatedCase;

    public class ExportTestResolver extends MockContentResolver {
        ExportTestProvider mProvider = new ExportTestProvider();
        public ExportTestResolver() {
            addProvider(VCardComposer.VCARD_TEST_AUTHORITY, mProvider);
            addProvider(RawContacts.CONTENT_URI.getAuthority(), mProvider);
        }

        public ContentValues buildData(String mimeType) {
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
        public ContentValues buildData(String mimeType) {
            ContentValues contentValues = new ContentValues();
            contentValues.put(Data.MIMETYPE, mimeType);
            mContentValuesList.add(contentValues);
            return contentValues;
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

    public static class VCardVerificationHandler implements VCardComposer.OneEntryHandler {
        final private TestCase mTestCase;
        final private List<PropertyNodesVerifier> mPropertyNodesVerifierList;
        final private boolean mIsV30;
        int mCount;

        public VCardVerificationHandler(TestCase testCase, boolean isV30) {
            mTestCase = testCase;
            mPropertyNodesVerifierList = new ArrayList<PropertyNodesVerifier>();
            mIsV30 = isV30;
            mCount = 1;
        }

        public PropertyNodesVerifier addNewPropertyNodesVerifier() {
            PropertyNodesVerifier propertyNodesVerifier = new PropertyNodesVerifier(mTestCase);
            mPropertyNodesVerifierList.add(propertyNodesVerifier);
            return propertyNodesVerifier;
        }

        public boolean onInit(Context context) {
            return true;
        }

        public boolean onEntryCreated(String vcard) {
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
            } catch (IOException e) {
                mTestCase.fail("Unexpected IOException: " + e.getMessage());
            } catch (VCardException e) {
                mTestCase.fail("Unexpected VCardException: " + e.getMessage());
            } finally {
                mCount++;
            }
            return true;
        }

        public void onTerminate() {
        }
    }

    private class CustomMockContext extends MockContext {
        final ContentResolver mResolver;
        public CustomMockContext(ContentResolver resolver) {
            mResolver = resolver;
        }

        @Override
        public ContentResolver getContentResolver() {
            return mResolver;
        }
    }

    //// Followings are actual tests ////

    public void testSimple() {
        ExportTestResolver resolver = new ExportTestResolver();
        ContentValues contentValues = resolver.buildData(StructuredName.CONTENT_ITEM_TYPE);
        contentValues.put(StructuredName.FAMILY_NAME, "Ando");
        contentValues.put(StructuredName.GIVEN_NAME, "Roid");

        VCardVerificationHandler handler = new VCardVerificationHandler(this, false);
        handler.addNewPropertyNodesVerifier()
            .addNodeWithOrder("VERSION", "2.1")
            .addNodeWithoutOrder("FN", "Roid Ando")
            .addNodeWithoutOrder("N", "Ando;Roid;;;", Arrays.asList("Ando", "Roid", "", "", ""));

        VCardComposer composer = new VCardComposer(new CustomMockContext(resolver),
                VCardConfig.VCARD_TYPE_V21_GENERIC);
        composer.addHandler(handler);
        if (!composer.init(VCardComposer.CONTACTS_TEST_CONTENT_URI, null, null, null)) {
            fail("init failed. Reason: " + composer.getErrorReason());
        }
        assertFalse(composer.isAfterLast());
        assertTrue(composer.createOneEntry());
        assertTrue(composer.isAfterLast());
        composer.terminate();
    }

    private void testPhotoCommon(boolean isV30) {
        ExportTestResolver resolver = new ExportTestResolver();
        ContentValues contentValues = resolver.buildData(StructuredName.CONTENT_ITEM_TYPE);
        contentValues.put(StructuredName.FAMILY_NAME, "PhotoTest");

        contentValues = resolver.buildData(Photo.CONTENT_ITEM_TYPE);
        contentValues.put(Photo.PHOTO, sPhotoByteArray);

        ContentValues contentValuesForPhoto = new ContentValues();
        contentValuesForPhoto.put("ENCODING", (isV30 ? "b" : "BASE64"));
        VCardVerificationHandler handler = new VCardVerificationHandler(this, isV30);
        handler.addNewPropertyNodesVerifier()
            .addNodeWithOrder("VERSION", (isV30 ? "3.0" : "2.1"))
            .addNodeWithoutOrder("FN", "PhotoTest")
            .addNodeWithoutOrder("N", "PhotoTest;;;;", Arrays.asList("PhotoTest", "", "", "", ""))
            .addNodeWithOrder("PHOTO", null, null, sPhotoByteArray,
                    contentValuesForPhoto, new TypeSet("JPEG"), null);

        int vcardType = (isV30 ? VCardConfig.VCARD_TYPE_V30_GENERIC
                : VCardConfig.VCARD_TYPE_V21_GENERIC);
        VCardComposer composer = new VCardComposer(new CustomMockContext(resolver), vcardType);
        composer.addHandler(handler);
        if (!composer.init(VCardComposer.CONTACTS_TEST_CONTENT_URI, null, null, null)) {
            fail("init() failed. Reason: " + composer.getErrorReason());
        }
        assertFalse(composer.isAfterLast());
        assertTrue(composer.createOneEntry());
        assertTrue(composer.isAfterLast());
        composer.terminate();
    }

    public void testPhotoV21() {
        testPhotoCommon(false);
    }

    public void testPhotoV30() {
        testPhotoCommon(true);
    }
}
