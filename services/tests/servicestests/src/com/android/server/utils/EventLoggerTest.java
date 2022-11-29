/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.utils;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.runners.Parameterized;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

@SmallTest
@RunWith(Enclosed.class)
public class EventLoggerTest {

    private static final int EVENTS_LOGGER_SIZE = 3;
    private static final String EVENTS_LOGGER_TAG = "TestLogger";

    private static final TestEvent TEST_EVENT_1 = new TestEvent();
    private static final TestEvent TEST_EVENT_2 = new TestEvent();
    private static final TestEvent TEST_EVENT_3 = new TestEvent();
    private static final TestEvent TEST_EVENT_4 = new TestEvent();
    private static final TestEvent TEST_EVENT_5 = new TestEvent();

    @RunWith(JUnit4.class)
    public static class BasicOperationsTest {

        private StringWriter mTestStringWriter;
        private PrintWriter mTestPrintWriter;

        private TestDumpSink mTestConsumer;
        private EventLogger mEventLogger;

        @Before
        public void setUp() {
            mTestStringWriter = new StringWriter();
            mTestPrintWriter = new PrintWriter(mTestStringWriter);
            mTestConsumer = new TestDumpSink();
            mEventLogger = new EventLogger(EVENTS_LOGGER_SIZE, EVENTS_LOGGER_TAG);
        }

        @Test
        public void testThatConsumerProducesEmptyListFromEmptyLog() {
            mEventLogger.dump(mTestConsumer);
            assertThat(mTestConsumer.getLastKnownConsumedEvents()).isEmpty();
        }

        @Test
        public void testThatPrintWriterProducesOnlyTitleFromEmptyLog() {
            mEventLogger.dump(mTestPrintWriter);
            assertThat(mTestStringWriter.toString())
                    .isEqualTo(mEventLogger.getDumpTitle() + "\n");
        }
    }

    @RunWith(Parameterized.class)
    public static class LoggingOperationTest {

        @Parameterized.Parameters
        public static Collection<Object[]> data() {
            return Arrays.asList(new Object[][] {
                    {
                        // insertion order, max size is 3
                        new EventLogger.Event[] { TEST_EVENT_1, TEST_EVENT_2 },
                        // expected events
                        new EventLogger.Event[] { TEST_EVENT_1, TEST_EVENT_2 }
                    },
                    {
                        // insertion order, max size is 3
                        new EventLogger.Event[] { TEST_EVENT_1, TEST_EVENT_3, TEST_EVENT_2 },
                        // expected events
                        new EventLogger.Event[] { TEST_EVENT_1, TEST_EVENT_3, TEST_EVENT_2 }
                    },
                    {
                        // insertion order, max size is 3
                        new EventLogger.Event[] { TEST_EVENT_1, TEST_EVENT_2, TEST_EVENT_3,
                            TEST_EVENT_4 },
                        // expected events
                        new EventLogger.Event[] { TEST_EVENT_2, TEST_EVENT_3, TEST_EVENT_4 }
                    },
                    {
                        // insertion order, max size is 3
                        new EventLogger.Event[] { TEST_EVENT_1, TEST_EVENT_2, TEST_EVENT_3,
                            TEST_EVENT_4, TEST_EVENT_5 },
                        // expected events
                        new EventLogger.Event[] { TEST_EVENT_3, TEST_EVENT_4, TEST_EVENT_5 }
                    }
            });
        }

        private TestDumpSink mTestConsumer;
        private EventLogger mEventLogger;

        private final StringWriter mTestStringWriter;
        private final PrintWriter mTestPrintWriter;

        private final EventLogger.Event[] mEventsToInsert;
        private final EventLogger.Event[] mExpectedEvents;

        public LoggingOperationTest(EventLogger.Event[] eventsToInsert,
                EventLogger.Event[] expectedEvents) {
            mTestStringWriter = new StringWriter();
            mTestPrintWriter = new PrintWriter(mTestStringWriter);
            mEventsToInsert = eventsToInsert;
            mExpectedEvents = expectedEvents;
        }

        @Before
        public void setUp() {
            mTestConsumer = new TestDumpSink();
            mEventLogger = new EventLogger(EVENTS_LOGGER_SIZE, EVENTS_LOGGER_TAG);
        }

        @Test
        public void testThatConsumerDumpsEventsAsExpected() {
            for (EventLogger.Event event: mEventsToInsert) {
                mEventLogger.enqueue(event);
            }

            mEventLogger.dump(mTestConsumer);

            assertThat(mTestConsumer.getLastKnownConsumedEvents())
                    .containsExactlyElementsIn(mExpectedEvents);
        }


        @Test
        public void testThatPrintWriterDumpsEventsAsExpected() {
            for (EventLogger.Event event: mEventsToInsert) {
                mEventLogger.enqueue(event);
            }

            mEventLogger.dump(mTestPrintWriter);

            assertThat(mTestStringWriter.toString())
                    .isEqualTo(convertEventsToString(mExpectedEvents));
        }

    }

    private static String convertEventsToString(EventLogger.Event[] events) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);

        printWriter.println("Events log: " + EVENTS_LOGGER_TAG);

        for (EventLogger.Event event: events) {
            printWriter.println(event.toString());
        }

        return stringWriter.toString();
    }


    private static final class TestEvent extends EventLogger.Event {

        @Override
        public String eventToString() {
            return getClass().getName() + "@" + Integer.toHexString(hashCode());
        }
    }

    private static final class TestDumpSink implements EventLogger.DumpSink {

        private final ArrayList<EventLogger.Event> mEvents = new ArrayList<>();

        @Override
        public void sink(String tag, List<EventLogger.Event> events) {
            mEvents.clear();
            mEvents.addAll(events);
        }

        public ArrayList<EventLogger.Event> getLastKnownConsumedEvents() {
            return new ArrayList<>(mEvents);
        }
    }
}
