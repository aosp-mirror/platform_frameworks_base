/*
 * Copyright (C) 2010 The Android Open Source Project
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
package android.pim.vcard.test_utils;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Entity;
import android.content.EntityIterator;
import android.database.Cursor;
import android.net.Uri;
import android.pim.vcard.VCardComposer;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;
import android.test.AndroidTestCase;
import android.test.mock.MockContentProvider;
import android.test.mock.MockCursor;

import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class ExportTestProvider extends MockContentProvider {
    final private ArrayList<ContactEntry> mContactEntryList = new ArrayList<ContactEntry>();

    private static class MockEntityIterator implements EntityIterator {
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

    public ExportTestProvider(AndroidTestCase androidTestCase) {
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
     */
    public EntityIterator queryEntities(Uri uri,
            String selection, String[] selectionArgs, String sortOrder) {
        TestCase.assertTrue(uri != null);
        TestCase.assertTrue(ContentResolver.SCHEME_CONTENT.equals(uri.getScheme()));
        final String authority = uri.getAuthority();
        TestCase.assertTrue(RawContacts.CONTENT_URI.getAuthority().equals(authority));
        TestCase.assertTrue((Data.CONTACT_ID + "=?").equals(selection));
        TestCase.assertEquals(1, selectionArgs.length);
        final int id = Integer.parseInt(selectionArgs[0]);
        TestCase.assertTrue(id >= 0 && id < mContactEntryList.size());

        return new MockEntityIterator(mContactEntryList.get(id).getList());
    }

    @Override
    public Cursor query(Uri uri,String[] projection,
            String selection, String[] selectionArgs, String sortOrder) {
        TestCase.assertTrue(VCardComposer.CONTACTS_TEST_CONTENT_URI.equals(uri));
        // In this test, following arguments are not supported.
        TestCase.assertNull(selection);
        TestCase.assertNull(selectionArgs);
        TestCase.assertNull(sortOrder);

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
                TestCase.assertEquals(Contacts._ID, columnName);
                return 0;
            }

            @Override
            public int getInt(int columnIndex) {
                TestCase.assertEquals(0, columnIndex);
                TestCase.assertTrue(mCurrentPosition >= 0
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