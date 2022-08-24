/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.android.server.notification;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.Person;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserManager;
import android.provider.ContactsContract;
import android.service.notification.StatusBarNotification;
import android.test.suitebuilder.annotation.SmallTest;
import android.text.SpannableString;
import android.util.ArraySet;
import android.util.LruCache;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.UiServiceTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.Arrays;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ValidateNotificationPeopleTest extends UiServiceTestCase {

    @Test
    public void testNoExtra() throws Exception {
        Bundle bundle = new Bundle();
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertNull("lack of extra should return null", result);
    }

    @Test
    public void testSingleString() throws Exception {
        String[] expected = { "foobar" };
        Bundle bundle = new Bundle();
        bundle.putString(Notification.EXTRA_PEOPLE_LIST, expected[0]);
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertStringArrayEquals("string should be in result[0]", expected, result);
    }

    @Test
    public void testSingleCharArray() throws Exception {
        String[] expected = { "foobar" };
        Bundle bundle = new Bundle();
        bundle.putCharArray(Notification.EXTRA_PEOPLE_LIST, expected[0].toCharArray());
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertStringArrayEquals("char[] should be in result[0]", expected, result);
    }

    @Test
    public void testSingleCharSequence() throws Exception {
        String[] expected = { "foobar" };
        Bundle bundle = new Bundle();
        bundle.putCharSequence(Notification.EXTRA_PEOPLE_LIST, new SpannableString(expected[0]));
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertStringArrayEquals("charSequence should be in result[0]", expected, result);
    }

    @Test
    public void testStringArraySingle() throws Exception {
        Bundle bundle = new Bundle();
        String[] expected = { "foobar" };
        bundle.putStringArray(Notification.EXTRA_PEOPLE_LIST, expected);
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertStringArrayEquals("wrapped string should be in result[0]", expected, result);
    }

    @Test
    public void testStringArrayMultiple() throws Exception {
        Bundle bundle = new Bundle();
        String[] expected = { "foo", "bar", "baz" };
        bundle.putStringArray(Notification.EXTRA_PEOPLE_LIST, expected);
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertStringArrayEquals("testStringArrayMultiple", expected, result);
    }

    @Test
    public void testStringArrayNulls() throws Exception {
        Bundle bundle = new Bundle();
        String[] expected = { "foo", null, "baz" };
        bundle.putStringArray(Notification.EXTRA_PEOPLE_LIST, expected);
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertStringArrayEquals("testStringArrayNulls", expected, result);
    }

    @Test
    public void testCharSequenceArrayMultiple() throws Exception {
        Bundle bundle = new Bundle();
        String[] expected = { "foo", "bar", "baz" };
        CharSequence[] charSeqArray = new CharSequence[expected.length];
        for (int i = 0; i < expected.length; i++) {
            charSeqArray[i] = new SpannableString(expected[i]);
        }
        bundle.putCharSequenceArray(Notification.EXTRA_PEOPLE_LIST, charSeqArray);
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertStringArrayEquals("testCharSequenceArrayMultiple", expected, result);
    }

    @Test
    public void testMixedCharSequenceArrayList() throws Exception {
        Bundle bundle = new Bundle();
        String[] expected = { "foo", "bar", "baz" };
        CharSequence[] charSeqArray = new CharSequence[expected.length];
        for (int i = 0; i < expected.length; i++) {
            if (i % 2 == 0) {
                charSeqArray[i] = expected[i];
            } else {
                charSeqArray[i] = new SpannableString(expected[i]);
            }
        }
        bundle.putCharSequenceArray(Notification.EXTRA_PEOPLE_LIST, charSeqArray);
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertStringArrayEquals("testMixedCharSequenceArrayList", expected, result);
    }

    @Test
    public void testStringArrayList() throws Exception {
        Bundle bundle = new Bundle();
        String[] expected = { "foo", null, "baz" };
        final ArrayList<String> stringArrayList = new ArrayList<String>(expected.length);
        for (int i = 0; i < expected.length; i++) {
            stringArrayList.add(expected[i]);
        }
        bundle.putStringArrayList(Notification.EXTRA_PEOPLE_LIST, stringArrayList);
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertStringArrayEquals("testStringArrayList", expected, result);
    }

    @Test
    public void testCharSequenceArrayList() throws Exception {
        Bundle bundle = new Bundle();
        String[] expected = { "foo", "bar", "baz" };
        final ArrayList<CharSequence> stringArrayList =
                new ArrayList<CharSequence>(expected.length);
        for (int i = 0; i < expected.length; i++) {
            stringArrayList.add(new SpannableString(expected[i]));
        }
        bundle.putCharSequenceArrayList(Notification.EXTRA_PEOPLE_LIST, stringArrayList);
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertStringArrayEquals("testCharSequenceArrayList", expected, result);
    }

    @Test
    public void testPeopleArrayList() throws Exception {
        Bundle bundle = new Bundle();
        String[] expected = { "name:test" , "tel:1234" };
        final ArrayList<Person> arrayList =
                new ArrayList<>(expected.length);
        arrayList.add(new Person.Builder().setName("test").build());
        arrayList.add(new Person.Builder().setUri(expected[1]).build());
        bundle.putParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, arrayList);
        String[] result = ValidateNotificationPeople.getExtraPeople(bundle);
        assertStringArrayEquals("testPeopleArrayList", expected, result);
    }

    @Test
    public void testSearchContacts_workContact_queriesWorkContactProvider()
            throws Exception {
        final int personalUserId = 0;
        final int workUserId = 12;
        final int contactId = 12345;
        final Context mockContext = mock(Context.class);
        when(mockContext.getUserId()).thenReturn(personalUserId);
        final UserManager mockUserManager = mock(UserManager.class);
        when(mockContext.getSystemService(UserManager.class)).thenReturn(mockUserManager);
        when(mockUserManager.getProfileIds(personalUserId, /* enabledOnly= */ true))
                .thenReturn(new int[] {personalUserId, workUserId});
        when(mockUserManager.isManagedProfile(workUserId)).thenReturn(true);
        final ContentResolver mockContentResolver = mock(ContentResolver.class);
        when(mockContext.getContentResolver()).thenReturn(mockContentResolver);
        final Uri lookupUri = Uri.withAppendedPath(
                ContactsContract.Contacts.CONTENT_LOOKUP_URI,
                ContactsContract.Contacts.ENTERPRISE_CONTACT_LOOKUP_PREFIX + contactId);

        new ValidateNotificationPeople().searchContacts(mockContext, lookupUri);

        ArgumentCaptor<Uri> queryUri = ArgumentCaptor.forClass(Uri.class);
        verify(mockContentResolver).query(
                queryUri.capture(),
                any(),
                /* selection= */ isNull(),
                /* selectionArgs= */ isNull(),
                /* sortOrder= */ isNull());
        assertEquals(workUserId, ContentProvider.getUserIdFromUri(queryUri.getValue()));
    }

    @Test
    public void testSearchContacts_personalContact_queriesPersonalContactProvider()
            throws Exception {
        final int personalUserId = 0;
        final int workUserId = 12;
        final int contactId = 12345;
        final Context mockContext = mock(Context.class);
        when(mockContext.getUserId()).thenReturn(personalUserId);
        final UserManager mockUserManager = mock(UserManager.class);
        when(mockContext.getSystemService(UserManager.class)).thenReturn(mockUserManager);
        final ContentResolver mockContentResolver = mock(ContentResolver.class);
        when(mockContext.getContentResolver()).thenReturn(mockContentResolver);
        final Uri lookupUri = Uri.withAppendedPath(
                ContactsContract.Contacts.CONTENT_LOOKUP_URI, String.valueOf(contactId));

        new ValidateNotificationPeople().searchContacts(mockContext, lookupUri);

        ArgumentCaptor<Uri> queryUri = ArgumentCaptor.forClass(Uri.class);
        verify(mockContentResolver).query(
                queryUri.capture(),
                any(),
                /* selection= */ isNull(),
                /* selectionArgs= */ isNull(),
                /* sortOrder= */ isNull());
        assertFalse(ContentProvider.uriHasUserId(queryUri.getValue()));
    }

    @Test
    public void testMergePhoneNumbers_noPhoneNumber() {
        // If merge phone number is called but the contacts lookup turned up no available
        // phone number (HAS_PHONE_NUMBER is false), then no query should happen.

        // setup of various bits required for querying
        final Context mockContext = mock(Context.class);
        final ContentResolver mockContentResolver = mock(ContentResolver.class);
        when(mockContext.getContentResolver()).thenReturn(mockContentResolver);
        final int contactId = 12345;
        final Uri lookupUri = Uri.withAppendedPath(
                ContactsContract.Contacts.CONTENT_LOOKUP_URI, String.valueOf(contactId));

        // when the contact is looked up, we return a cursor that has one entry whose info is:
        //  _ID: 1
        //  LOOKUP_KEY: "testlookupkey"
        //  STARRED: 0
        //  HAS_PHONE_NUMBER: 0
        Cursor cursor = makeMockCursor(1, "testlookupkey", 0, 0);
        when(mockContentResolver.query(any(), any(), any(), any(), any())).thenReturn(cursor);

        // call searchContacts and then mergePhoneNumbers, make sure we never actually
        // query the content resolver for a phone number
        new ValidateNotificationPeople().searchContactsAndLookupNumbers(mockContext, lookupUri);
        verify(mockContentResolver, never()).query(
                eq(ContactsContract.CommonDataKinds.Phone.CONTENT_URI),
                eq(ValidateNotificationPeople.PHONE_LOOKUP_PROJECTION),
                contains(ContactsContract.Contacts.LOOKUP_KEY),
                any(),  // selection args
                isNull());  // sort order
    }

    @Test
    public void testMergePhoneNumbers_hasNumber() {
        // If merge phone number is called and the contact lookup has a phone number,
        // make sure there's then a subsequent query for the phone number.

        // setup of various bits required for querying
        final Context mockContext = mock(Context.class);
        final ContentResolver mockContentResolver = mock(ContentResolver.class);
        when(mockContext.getContentResolver()).thenReturn(mockContentResolver);
        final int contactId = 12345;
        final Uri lookupUri = Uri.withAppendedPath(
                ContactsContract.Contacts.CONTENT_LOOKUP_URI, String.valueOf(contactId));

        // when the contact is looked up, we return a cursor that has one entry whose info is:
        //  _ID: 1
        //  LOOKUP_KEY: "testlookupkey"
        //  STARRED: 0
        //  HAS_PHONE_NUMBER: 1
        Cursor cursor = makeMockCursor(1, "testlookupkey", 0, 1);

        // make sure to add some specifics so this cursor is only returned for the
        // contacts database lookup.
        when(mockContentResolver.query(eq(lookupUri), any(),
                isNull(), isNull(), isNull())).thenReturn(cursor);

        // in the case of a phone lookup, return null cursor; that's not an error case
        // and we're not checking the actual storing of the phone data here.
        when(mockContentResolver.query(eq(ContactsContract.CommonDataKinds.Phone.CONTENT_URI),
                eq(ValidateNotificationPeople.PHONE_LOOKUP_PROJECTION),
                contains(ContactsContract.Contacts.LOOKUP_KEY),
                any(), isNull())).thenReturn(null);

        // call searchContacts and then mergePhoneNumbers, and check that we query
        // once for the
        new ValidateNotificationPeople().searchContactsAndLookupNumbers(mockContext, lookupUri);
        verify(mockContentResolver, times(1)).query(
                eq(ContactsContract.CommonDataKinds.Phone.CONTENT_URI),
                eq(ValidateNotificationPeople.PHONE_LOOKUP_PROJECTION),
                contains(ContactsContract.Contacts.LOOKUP_KEY),
                eq(new String[] { "testlookupkey" }),  // selection args
                isNull());  // sort order
    }

    @Test
    public void testValidatePeople_needsLookupWhenNoCache() {
        final Context mockContext = mock(Context.class);
        final ContentResolver mockContentResolver = mock(ContentResolver.class);
        when(mockContext.getContentResolver()).thenReturn(mockContentResolver);
        final NotificationUsageStats mockNotificationUsageStats =
                mock(NotificationUsageStats.class);

        // Create validator with empty cache
        ValidateNotificationPeople vnp = new ValidateNotificationPeople();
        LruCache cache = new LruCache<String, ValidateNotificationPeople.LookupResult>(5);
        vnp.initForTests(mockContext, mockNotificationUsageStats, cache);

        NotificationRecord record = getNotificationRecord();
        String[] callNumber = new String[]{"tel:12345678910"};
        setNotificationPeople(record, callNumber);

        // Returned ranking reconsideration not null indicates that there is a lookup to be done
        RankingReconsideration rr = vnp.validatePeople(mockContext, record);
        assertNotNull(rr);
    }

    @Test
    public void testValidatePeople_noLookupWhenCached_andPopulatesContactInfo() {
        final Context mockContext = mock(Context.class);
        final ContentResolver mockContentResolver = mock(ContentResolver.class);
        when(mockContext.getContentResolver()).thenReturn(mockContentResolver);
        when(mockContext.getUserId()).thenReturn(1);
        final NotificationUsageStats mockNotificationUsageStats =
                mock(NotificationUsageStats.class);

        // Information to be passed in & returned from the lookup result
        String lookup = "lookup:contactinfohere";
        String lookupTel = "16175551234";
        float affinity = 0.7f;

        // Create a fake LookupResult for the data we'll pass in
        LruCache cache = new LruCache<String, ValidateNotificationPeople.LookupResult>(5);
        ValidateNotificationPeople.LookupResult lr =
                mock(ValidateNotificationPeople.LookupResult.class);
        when(lr.getAffinity()).thenReturn(affinity);
        when(lr.getPhoneNumbers()).thenReturn(new ArraySet<>(new String[]{lookupTel}));
        when(lr.isExpired()).thenReturn(false);
        cache.put(ValidateNotificationPeople.getCacheKey(1, lookup), lr);

        // Create validator with the established cache
        ValidateNotificationPeople vnp = new ValidateNotificationPeople();
        vnp.initForTests(mockContext, mockNotificationUsageStats, cache);

        NotificationRecord record = getNotificationRecord();
        String[] peopleInfo = new String[]{lookup};
        setNotificationPeople(record, peopleInfo);

        // Returned ranking reconsideration null indicates that there is no pending work to be done
        RankingReconsideration rr = vnp.validatePeople(mockContext, record);
        assertNull(rr);

        // Confirm that the affinity & phone number made it into our record
        assertEquals(affinity, record.getContactAffinity(), 1e-8);
        assertNotNull(record.getPhoneNumbers());
        assertTrue(record.getPhoneNumbers().contains(lookupTel));
    }

    // Creates a cursor that points to one item of Contacts data with the specified
    // columns.
    private Cursor makeMockCursor(int id, String lookupKey, int starred, int hasPhone) {
        Cursor mockCursor = mock(Cursor.class);
        when(mockCursor.moveToFirst()).thenReturn(true);
        doAnswer(new Answer<Boolean>() {
            boolean mAccessed = false;
            @Override
            public Boolean answer(InvocationOnMock invocation) throws Throwable {
                if (!mAccessed) {
                    mAccessed = true;
                    return true;
                }
                return false;
            }

        }).when(mockCursor).moveToNext();

        // id
        when(mockCursor.getColumnIndex(ContactsContract.Contacts._ID)).thenReturn(0);
        when(mockCursor.getInt(0)).thenReturn(id);

        // lookup key
        when(mockCursor.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY)).thenReturn(1);
        when(mockCursor.getString(1)).thenReturn(lookupKey);

        // starred
        when(mockCursor.getColumnIndex(ContactsContract.Contacts.STARRED)).thenReturn(2);
        when(mockCursor.getInt(2)).thenReturn(starred);

        // has phone number
        when(mockCursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)).thenReturn(3);
        when(mockCursor.getInt(3)).thenReturn(hasPhone);

        return mockCursor;
    }

    private void assertStringArrayEquals(String message, String[] expected, String[] result) {
        String expectedString = Arrays.toString(expected);
        String resultString = Arrays.toString(result);
        assertEquals(message + ": arrays differ", expectedString, resultString);
    }

    private NotificationRecord getNotificationRecord() {
        StatusBarNotification sbn = mock(StatusBarNotification.class);
        Notification notification = mock(Notification.class);
        when(sbn.getNotification()).thenReturn(notification);
        return new NotificationRecord(mContext, sbn, mock(NotificationChannel.class));
    }

    private void setNotificationPeople(NotificationRecord r, String[] people) {
        Bundle extras = new Bundle();
        extras.putObject(Notification.EXTRA_PEOPLE_LIST, people);
        r.getSbn().getNotification().extras = extras;
    }
}
