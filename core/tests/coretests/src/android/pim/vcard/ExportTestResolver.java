/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package android.pim.vcard;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Entity;
import android.content.EntityIterator;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;
import android.test.mock.MockCursor;

import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/* package */ public class ExportTestResolver extends MockContentResolver {
    ExportTestProvider mProvider;
    public ExportTestResolver(TestCase testCase) {
        mProvider = new ExportTestProvider(testCase);
        addProvider(VCardComposer.VCARD_TEST_AUTHORITY, mProvider);
        addProvider(RawContacts.CONTENT_URI.getAuthority(), mProvider);
    }

    public ContactEntry addInputContactEntry() {
        return mProvider.buildInputEntry();
    }

    public ExportTestProvider getProvider() {
        return mProvider;
    }
}

/* package */ class MockEntityIterator implements EntityIterator {
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

    public void remove() {
        throw new UnsupportedOperationException("remove not supported");
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
/* package */ class ContactEntry {
    private final List<ContentValues> mContentValuesList = new ArrayList<ContentValues>();

    public ContentValuesBuilder addContentValues(String mimeType) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(Data.MIMETYPE, mimeType);
        mContentValuesList.add(contentValues);
        return new ContentValuesBuilder(contentValues);
    }

    public List<ContentValues> getList() {
        return mContentValuesList;
    }
}

/* package */ class ExportTestProvider extends MockContentProvider {
    final private TestCase mTestCase;
    final private ArrayList<ContactEntry> mContactEntryList = new ArrayList<ContactEntry>();

    public ExportTestProvider(TestCase testCase) {
        mTestCase = testCase;
    }

    public ContactEntry buildInputEntry() {
        ContactEntry contactEntry = new ContactEntry();
        mContactEntryList.add(contactEntry);
        return contactEntry;
    }

    /**
     * <p>
     * An old method which had existed but was removed from ContentResolver.
     * </p>
     * <p>
     * We still keep using this method since we don't have a propeer way to know
     * which value in the ContentValue corresponds to the entry in Contacts database.
     * </p>
     * <p>
     * Detail:
     * There's an easy way to know which index "family name" corresponds to, via
     * {@link android.provider.ContactsContract}.
     * FAMILY_NAME equals DATA3, so the corresponding index
     * for "family name" should be 2 (note that index is 0-origin).
     * However, we cannot know what the index 2 corresponds to; it may be "family name",
     * "label" for now, but may be the other some column in the future. We don't have
     * convenient way to know the original data structure.
     * </p>
     */
    public EntityIterator queryEntities(Uri uri,
            String selection, String[] selectionArgs, String sortOrder) {
        mTestCase.assertTrue(uri != null);
        mTestCase.assertTrue(ContentResolver.SCHEME_CONTENT.equals(uri.getScheme()));
        final String authority = uri.getAuthority();
        mTestCase.assertTrue(RawContacts.CONTENT_URI.getAuthority().equals(authority));
        mTestCase.assertTrue((Data.CONTACT_ID + "=?").equals(selection));
        mTestCase.assertEquals(1, selectionArgs.length);
        final int id = Integer.parseInt(selectionArgs[0]);
        mTestCase.assertTrue(id >= 0 && id < mContactEntryList.size());

        return new MockEntityIterator(mContactEntryList.get(id).getList());
    }

    @Override
    public Cursor query(Uri uri,String[] projection,
            String selection, String[] selectionArgs, String sortOrder) {
        mTestCase.assertTrue(VCardComposer.CONTACTS_TEST_CONTENT_URI.equals(uri));
        // In this test, following arguments are not supported.
        mTestCase.assertNull(selection);
        mTestCase.assertNull(selectionArgs);
        mTestCase.assertNull(sortOrder);

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
                mTestCase.assertEquals(Contacts._ID, columnName);
                return 0;
            }

            @Override
            public int getInt(int columnIndex) {
                mTestCase.assertEquals(0, columnIndex);
                mTestCase.assertTrue(mCurrentPosition >= 0
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
