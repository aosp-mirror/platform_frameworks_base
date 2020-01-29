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
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.Telephony.Sms;
import android.provider.Telephony.TextBasedSmsColumns;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

@RunWith(JUnit4.class)
public final class SmsQueryHelperTest {

    private static final String SMS_AUTHORITY = "sms";
    private static final String PHONE_NUMBER = "650-555-1111";
    private static final String NORMALIZED_PHONE_NUMBER = "+16505551111";

    private static final String[] SMS_COLUMNS = new String[] {
            Sms._ID, Sms.DATE, Sms.TYPE, Sms.ADDRESS };

    @Mock
    private MockContext mContext;

    private MatrixCursor mSmsCursor;
    private EventConsumer mEventConsumer;
    private SmsQueryHelper mHelper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mSmsCursor = new MatrixCursor(SMS_COLUMNS);

        MockContentResolver contentResolver = new MockContentResolver();
        contentResolver.addProvider(SMS_AUTHORITY, new SmsContentProvider());
        when(mContext.getContentResolver()).thenReturn(contentResolver);

        mEventConsumer = new EventConsumer();
        mHelper = new SmsQueryHelper(mContext, mEventConsumer);
    }

    @Test
    public void testQueryNoMessages() {
        assertFalse(mHelper.querySince(50_000L));
        assertFalse(mEventConsumer.mEventMap.containsKey(NORMALIZED_PHONE_NUMBER));
    }

    @Test
    public void testQueryIncomingMessage() {
        mSmsCursor.addRow(new Object[] {
                /* id= */ 0, /* date= */ 100L, /* type= */ TextBasedSmsColumns.MESSAGE_TYPE_INBOX,
                /* address= */ PHONE_NUMBER });

        assertTrue(mHelper.querySince(50L));
        List<Event> events = mEventConsumer.mEventMap.get(NORMALIZED_PHONE_NUMBER);

        assertEquals(100L, mHelper.getLastMessageTimestamp());
        assertEquals(1, events.size());
        assertEquals(Event.TYPE_SMS_INCOMING, events.get(0).getType());
        assertEquals(100L, events.get(0).getTimestamp());
    }

    @Test
    public void testQueryOutgoingMessage() {
        mSmsCursor.addRow(new Object[] {
                /* id= */ 0, /* date= */ 100L, /* type= */ TextBasedSmsColumns.MESSAGE_TYPE_SENT,
                /* address= */ PHONE_NUMBER });

        assertTrue(mHelper.querySince(50L));
        List<Event> events = mEventConsumer.mEventMap.get(NORMALIZED_PHONE_NUMBER);

        assertEquals(100L, mHelper.getLastMessageTimestamp());
        assertEquals(1, events.size());
        assertEquals(Event.TYPE_SMS_OUTGOING, events.get(0).getType());
        assertEquals(100L, events.get(0).getTimestamp());
    }

    @Test
    public void testQueryMultipleMessages() {
        mSmsCursor.addRow(new Object[] {
                /* id= */ 0, /* date= */ 100L, /* type= */ TextBasedSmsColumns.MESSAGE_TYPE_SENT,
                /* address= */ PHONE_NUMBER });
        mSmsCursor.addRow(new Object[] {
                /* id= */ 0, /* date= */ 110L, /* type= */ TextBasedSmsColumns.MESSAGE_TYPE_INBOX,
                /* address= */ PHONE_NUMBER });

        assertTrue(mHelper.querySince(50L));
        List<Event> events = mEventConsumer.mEventMap.get(NORMALIZED_PHONE_NUMBER);

        assertEquals(110L, mHelper.getLastMessageTimestamp());
        assertEquals(2, events.size());
        assertEquals(Event.TYPE_SMS_OUTGOING, events.get(0).getType());
        assertEquals(100L, events.get(0).getTimestamp());
        assertEquals(Event.TYPE_SMS_INCOMING, events.get(1).getType());
        assertEquals(110L, events.get(1).getTimestamp());
    }

    private class EventConsumer implements BiConsumer<String, Event> {

        private final Map<String, List<Event>> mEventMap = new ArrayMap<>();

        @Override
        public void accept(String phoneNumber, Event event) {
            mEventMap.computeIfAbsent(phoneNumber, key -> new ArrayList<>()).add(event);
        }
    }

    private class SmsContentProvider extends MockContentProvider {

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                String sortOrder) {
            return mSmsCursor;
        }
    }
}
