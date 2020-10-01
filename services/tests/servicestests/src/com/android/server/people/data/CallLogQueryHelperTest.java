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
import android.provider.CallLog.Calls;
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
public final class CallLogQueryHelperTest {

    private static final String CALL_LOG_AUTHORITY = "call_log";
    private static final String NORMALIZED_PHONE_NUMBER = "+16505551111";

    private static final String[] CALL_LOG_COLUMNS = new String[] {
            Calls.CACHED_NORMALIZED_NUMBER, Calls.DATE, Calls.DURATION, Calls.TYPE };

    @Mock
    private MockContext mContext;

    private MatrixCursor mCursor;
    private EventConsumer mEventConsumer;
    private CallLogQueryHelper mHelper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mCursor = new MatrixCursor(CALL_LOG_COLUMNS);

        MockContentResolver contentResolver = new MockContentResolver();
        contentResolver.addProvider(CALL_LOG_AUTHORITY, new CallLogContentProvider());
        when(mContext.getContentResolver()).thenReturn(contentResolver);

        mEventConsumer = new EventConsumer();
        mHelper = new CallLogQueryHelper(mContext, mEventConsumer);
    }

    @Test
    public void testQueryNoCalls() {
        assertFalse(mHelper.querySince(50L));
        assertFalse(mEventConsumer.mEventMap.containsKey(NORMALIZED_PHONE_NUMBER));
    }

    @Test
    public void testQueryIncomingCall() {
        mCursor.addRow(new Object[] {
                NORMALIZED_PHONE_NUMBER, /* date= */ 100L, /* duration= */ 30L,
                /* type= */ Calls.INCOMING_TYPE });

        assertTrue(mHelper.querySince(50L));
        List<Event> events = mEventConsumer.mEventMap.get(NORMALIZED_PHONE_NUMBER);

        assertEquals(100L, mHelper.getLastCallTimestamp());
        assertEquals(1, events.size());
        assertEquals(Event.TYPE_CALL_INCOMING, events.get(0).getType());
        assertEquals(100L, events.get(0).getTimestamp());
        assertEquals(30L, events.get(0).getDurationSeconds());
    }

    @Test
    public void testQueryOutgoingCall() {
        mCursor.addRow(new Object[] {
                NORMALIZED_PHONE_NUMBER, /* date= */ 100L, /* duration= */ 40L,
                /* type= */ Calls.OUTGOING_TYPE });

        assertTrue(mHelper.querySince(50L));
        List<Event> events = mEventConsumer.mEventMap.get(NORMALIZED_PHONE_NUMBER);

        assertEquals(100L, mHelper.getLastCallTimestamp());
        assertEquals(1, events.size());
        assertEquals(Event.TYPE_CALL_OUTGOING, events.get(0).getType());
        assertEquals(100L, events.get(0).getTimestamp());
        assertEquals(40L, events.get(0).getDurationSeconds());
    }

    @Test
    public void testQueryMissedCall() {
        mCursor.addRow(new Object[] {
                NORMALIZED_PHONE_NUMBER, /* date= */ 100L, /* duration= */ 0L,
                /* type= */ Calls.MISSED_TYPE });

        assertTrue(mHelper.querySince(50L));
        List<Event> events = mEventConsumer.mEventMap.get(NORMALIZED_PHONE_NUMBER);

        assertEquals(100L, mHelper.getLastCallTimestamp());
        assertEquals(1, events.size());
        assertEquals(Event.TYPE_CALL_MISSED, events.get(0).getType());
        assertEquals(100L, events.get(0).getTimestamp());
        assertEquals(0L, events.get(0).getDurationSeconds());
    }

    @Test
    public void testQueryMultipleCalls() {
        mCursor.addRow(new Object[] {
                NORMALIZED_PHONE_NUMBER, /* date= */ 100L, /* duration= */ 0L,
                /* type= */ Calls.MISSED_TYPE });
        mCursor.addRow(new Object[] {
                NORMALIZED_PHONE_NUMBER, /* date= */ 110L, /* duration= */ 40L,
                /* type= */ Calls.OUTGOING_TYPE });

        assertTrue(mHelper.querySince(50L));
        List<Event> events = mEventConsumer.mEventMap.get(NORMALIZED_PHONE_NUMBER);

        assertEquals(110L, mHelper.getLastCallTimestamp());
        assertEquals(2, events.size());
        assertEquals(Event.TYPE_CALL_MISSED, events.get(0).getType());
        assertEquals(100L, events.get(0).getTimestamp());
        assertEquals(Event.TYPE_CALL_OUTGOING, events.get(1).getType());
        assertEquals(110L, events.get(1).getTimestamp());
        assertEquals(40L, events.get(1).getDurationSeconds());
    }

    private class EventConsumer implements BiConsumer<String, Event> {

        private final Map<String, List<Event>> mEventMap = new ArrayMap<>();

        @Override
        public void accept(String phoneNumber, Event event) {
            mEventMap.computeIfAbsent(phoneNumber, key -> new ArrayList<>()).add(event);
        }
    }

    private class CallLogContentProvider extends MockContentProvider {

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                String sortOrder) {
            return mCursor;
        }
    }
}
