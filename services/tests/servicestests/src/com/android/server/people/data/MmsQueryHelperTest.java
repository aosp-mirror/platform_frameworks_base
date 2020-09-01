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
import android.provider.Telephony.BaseMmsColumns;
import android.provider.Telephony.Mms;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;
import android.test.mock.MockContext;
import android.util.ArrayMap;

import com.google.android.mms.pdu.PduHeaders;

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
public final class MmsQueryHelperTest {

    private static final String MMS_AUTHORITY = "mms";
    private static final String PHONE_NUMBER = "650-555-1111";
    private static final String NORMALIZED_PHONE_NUMBER = "+16505551111";
    private static final String OWN_PHONE_NUMBER = "650-555-9999";

    private static final String[] MMS_COLUMNS = new String[] { Mms._ID, Mms.DATE, Mms.MESSAGE_BOX };
    private static final String[] ADDR_COLUMNS = new String[] { Mms.Addr.ADDRESS, Mms.Addr.TYPE };

    @Mock
    private MockContext mContext;

    private MatrixCursor mMmsCursor;
    private final List<MatrixCursor> mAddrCursors = new ArrayList<>();
    private EventConsumer mEventConsumer;
    private MmsQueryHelper mHelper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mMmsCursor = new MatrixCursor(MMS_COLUMNS);
        mAddrCursors.add(new MatrixCursor(ADDR_COLUMNS));
        mAddrCursors.add(new MatrixCursor(ADDR_COLUMNS));

        MockContentResolver contentResolver = new MockContentResolver();
        contentResolver.addProvider(MMS_AUTHORITY, new MmsContentProvider());
        when(mContext.getContentResolver()).thenReturn(contentResolver);

        mEventConsumer = new EventConsumer();
        mHelper = new MmsQueryHelper(mContext, mEventConsumer);
    }

    @Test
    public void testQueryNoMessages() {
        assertFalse(mHelper.querySince(50_000L));
        assertFalse(mEventConsumer.mEventMap.containsKey(NORMALIZED_PHONE_NUMBER));
    }

    @Test
    public void testQueryIncomingMessage() {
        mMmsCursor.addRow(new Object[] {
                /* id= */ 0, /* date= */ 100L, /* msgBox= */ BaseMmsColumns.MESSAGE_BOX_INBOX });
        mAddrCursors.get(0).addRow(new Object[] {
                /* address= */ PHONE_NUMBER, /* type= */ PduHeaders.FROM });
        mAddrCursors.get(0).addRow(new Object[] {
                /* address= */ OWN_PHONE_NUMBER, /* type= */ PduHeaders.TO });

        assertTrue(mHelper.querySince(50_000L));
        List<Event> events = mEventConsumer.mEventMap.get(NORMALIZED_PHONE_NUMBER);

        assertEquals(100_000L, mHelper.getLastMessageTimestamp());
        assertEquals(1, events.size());
        assertEquals(Event.TYPE_SMS_INCOMING, events.get(0).getType());
        assertEquals(100_000L, events.get(0).getTimestamp());
    }

    @Test
    public void testQueryOutgoingMessage() {
        mMmsCursor.addRow(new Object[] {
                /* id= */ 0, /* date= */ 100L, /* msgBox= */ BaseMmsColumns.MESSAGE_BOX_SENT });
        mAddrCursors.get(0).addRow(new Object[] {
                /* address= */ OWN_PHONE_NUMBER, /* type= */ PduHeaders.FROM });
        mAddrCursors.get(0).addRow(new Object[] {
                /* address= */ PHONE_NUMBER, /* type= */ PduHeaders.TO });

        assertTrue(mHelper.querySince(50_000L));
        List<Event> events = mEventConsumer.mEventMap.get(NORMALIZED_PHONE_NUMBER);

        assertEquals(100_000L, mHelper.getLastMessageTimestamp());
        assertEquals(1, events.size());
        assertEquals(Event.TYPE_SMS_OUTGOING, events.get(0).getType());
        assertEquals(100_000L, events.get(0).getTimestamp());
    }

    @Test
    public void testQueryMultipleMessages() {
        mMmsCursor.addRow(new Object[] {
                /* id= */ 0, /* date= */ 100L, /* msgBox= */ BaseMmsColumns.MESSAGE_BOX_SENT });
        mMmsCursor.addRow(new Object[] {
                /* id= */ 1, /* date= */ 110L, /* msgBox= */ BaseMmsColumns.MESSAGE_BOX_INBOX });
        mAddrCursors.get(0).addRow(new Object[] {
                /* address= */ OWN_PHONE_NUMBER, /* type= */ PduHeaders.FROM });
        mAddrCursors.get(0).addRow(new Object[] {
                /* address= */ PHONE_NUMBER, /* type= */ PduHeaders.TO });
        mAddrCursors.get(1).addRow(new Object[] {
                /* address= */ PHONE_NUMBER, /* type= */ PduHeaders.FROM });
        mAddrCursors.get(1).addRow(new Object[] {
                /* address= */ OWN_PHONE_NUMBER, /* type= */ PduHeaders.TO });

        assertTrue(mHelper.querySince(50_000L));
        List<Event> events = mEventConsumer.mEventMap.get(NORMALIZED_PHONE_NUMBER);

        assertEquals(110_000L, mHelper.getLastMessageTimestamp());
        assertEquals(2, events.size());
        assertEquals(Event.TYPE_SMS_OUTGOING, events.get(0).getType());
        assertEquals(100_000L, events.get(0).getTimestamp());
        assertEquals(Event.TYPE_SMS_INCOMING, events.get(1).getType());
        assertEquals(110_000L, events.get(1).getTimestamp());
    }

    private class EventConsumer implements BiConsumer<String, Event> {

        private final Map<String, List<Event>> mEventMap = new ArrayMap<>();

        @Override
        public void accept(String phoneNumber, Event event) {
            mEventMap.computeIfAbsent(phoneNumber, key -> new ArrayList<>()).add(event);
        }
    }

    private class MmsContentProvider extends MockContentProvider {

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                String sortOrder) {
            List<String> segments = uri.getPathSegments();
            if (segments.size() == 2 && "addr".equals(segments.get(1))) {
                int messageId = Integer.valueOf(segments.get(0));
                return mAddrCursors.get(messageId);
            }
            return mMmsCursor;
        }
    }
}
