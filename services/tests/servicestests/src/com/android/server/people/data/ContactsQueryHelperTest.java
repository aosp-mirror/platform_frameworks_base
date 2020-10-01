/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.people.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Contacts;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;
import android.test.mock.MockContext;
import android.util.ArrayMap;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Map;

@RunWith(JUnit4.class)
public final class ContactsQueryHelperTest {

    private static final String CONTACT_LOOKUP_KEY = "123";
    private static final String PHONE_NUMBER = "+1234567890";

    private static final String[] CONTACTS_COLUMNS = new String[] {
            Contacts._ID, Contacts.LOOKUP_KEY, Contacts.STARRED, Contacts.HAS_PHONE_NUMBER,
            Contacts.CONTACT_LAST_UPDATED_TIMESTAMP };
    private static final String[] CONTACTS_LOOKUP_COLUMNS = new String[] {
            Contacts._ID, Contacts.LOOKUP_KEY, Contacts.STARRED, Contacts.HAS_PHONE_NUMBER };
    private static final String[] PHONE_COLUMNS = new String[] {
            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER };

    @Mock
    private MockContext mContext;

    private MatrixCursor mContactsCursor;
    private MatrixCursor mContactsLookupCursor;
    private MatrixCursor mPhoneCursor;
    private ContactsQueryHelper mHelper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mContactsCursor = new MatrixCursor(CONTACTS_COLUMNS);
        mContactsLookupCursor = new MatrixCursor(CONTACTS_LOOKUP_COLUMNS);
        mPhoneCursor = new MatrixCursor(PHONE_COLUMNS);

        MockContentResolver contentResolver = new MockContentResolver();
        ContactsContentProvider contentProvider = new ContactsContentProvider();
        contentProvider.registerCursor(Contacts.CONTENT_URI, mContactsCursor);
        contentProvider.registerCursor(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, mContactsLookupCursor);
        contentProvider.registerCursor(
                ContactsContract.CommonDataKinds.Email.CONTENT_LOOKUP_URI, mContactsLookupCursor);
        contentProvider.registerCursor(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, mPhoneCursor);

        contentResolver.addProvider(ContactsContract.AUTHORITY, contentProvider);
        when(mContext.getContentResolver()).thenReturn(contentResolver);

        mHelper = new ContactsQueryHelper(mContext);
    }

    @Test
    public void testQueryWithUri() {
        mContactsCursor.addRow(new Object[] {
                /* id= */ 11, CONTACT_LOOKUP_KEY, /* starred= */ 1, /* hasPhoneNumber= */ 1,
                /* lastUpdatedTimestamp= */ 100L });
        mPhoneCursor.addRow(new String[] { PHONE_NUMBER });
        Uri contactUri = Uri.withAppendedPath(Contacts.CONTENT_LOOKUP_URI, CONTACT_LOOKUP_KEY);
        assertTrue(mHelper.query(contactUri.toString()));
        assertNotNull(mHelper.getContactUri());
        assertEquals(PHONE_NUMBER, mHelper.getPhoneNumber());
        assertEquals(100L, mHelper.getLastUpdatedTimestamp());
        assertTrue(mHelper.isStarred());
    }

    @Test
    public void testQueryWithUriNotStarredNoPhoneNumber() {
        mContactsCursor.addRow(new Object[] {
                /* id= */ 11, CONTACT_LOOKUP_KEY, /* starred= */ 0, /* hasPhoneNumber= */ 0,
                /* lastUpdatedTimestamp= */ 100L });
        Uri contactUri = Uri.withAppendedPath(Contacts.CONTENT_LOOKUP_URI, CONTACT_LOOKUP_KEY);
        assertTrue(mHelper.query(contactUri.toString()));
        assertNotNull(mHelper.getContactUri());
        assertNull(mHelper.getPhoneNumber());
        assertFalse(mHelper.isStarred());
        assertEquals(100L, mHelper.getLastUpdatedTimestamp());
    }

    @Test
    public void testQueryWithUriNotFound() {
        Uri contactUri = Uri.withAppendedPath(Contacts.CONTENT_LOOKUP_URI, CONTACT_LOOKUP_KEY);
        assertFalse(mHelper.query(contactUri.toString()));
    }

    @Test
    public void testQueryWithPhoneNumber() {
        mContactsLookupCursor.addRow(new Object[] {
                /* id= */ 11, CONTACT_LOOKUP_KEY, /* starred= */ 1, /* hasPhoneNumber= */ 1 });
        mPhoneCursor.addRow(new String[] { PHONE_NUMBER });
        String contactUri = "tel:" + PHONE_NUMBER;
        assertTrue(mHelper.query(contactUri));
        assertNotNull(mHelper.getContactUri());
        assertEquals(PHONE_NUMBER, mHelper.getPhoneNumber());
        assertTrue(mHelper.isStarred());
    }

    @Test
    public void testQueryWithEmail() {
        mContactsLookupCursor.addRow(new Object[] {
                /* id= */ 11, CONTACT_LOOKUP_KEY, /* starred= */ 1, /* hasPhoneNumber= */ 0 });
        String contactUri = "mailto:test@gmail.com";
        assertTrue(mHelper.query(contactUri));
        assertNotNull(mHelper.getContactUri());
        assertNull(mHelper.getPhoneNumber());
        assertTrue(mHelper.isStarred());
    }

    @Test
    public void testQueryUpdatedContactSinceTime() {
        mContactsCursor.addRow(new Object[] {
                /* id= */ 11, CONTACT_LOOKUP_KEY, /* starred= */ 1, /* hasPhoneNumber= */ 0,
                /* lastUpdatedTimestamp= */ 100L });
        assertTrue(mHelper.querySince(50L));
        assertNotNull(mHelper.getContactUri());
        assertNull(mHelper.getPhoneNumber());
        assertTrue(mHelper.isStarred());
        assertEquals(100L, mHelper.getLastUpdatedTimestamp());
    }

    @Test
    public void testQueryWithUnsupportedScheme() {
        mContactsLookupCursor.addRow(new Object[] {
                /* id= */ 11, CONTACT_LOOKUP_KEY, /* starred= */ 1, /* hasPhoneNumber= */ 1 });
        mPhoneCursor.addRow(new String[] { PHONE_NUMBER });
        String contactUri = "unknown:test";
        assertFalse(mHelper.query(contactUri));
    }

    private class ContactsContentProvider extends MockContentProvider {

        private Map<Uri, Cursor> mUriPrefixToCursorMap = new ArrayMap<>();

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                String sortOrder) {
            for (Uri prefixUri : mUriPrefixToCursorMap.keySet()) {
                if (uri.isPathPrefixMatch(prefixUri)) {
                    return mUriPrefixToCursorMap.get(prefixUri);
                }
            }
            return mUriPrefixToCursorMap.get(uri);
        }

        private void registerCursor(Uri uriPrefix, Cursor cursor) {
            mUriPrefixToCursorMap.put(uriPrefix, cursor);
        }
    }
}
