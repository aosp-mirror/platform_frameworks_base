/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.os;

import junit.framework.TestSuite;

public class OsTests {
    public static TestSuite suite() {
        TestSuite suite = new TestSuite(OsTests.class.getName());

        suite.addTestSuite(AidlTest.class);
        suite.addTestSuite(BroadcasterTest.class);
        suite.addTestSuite(FileObserverTest.class);
        suite.addTestSuite(IdleHandlerTest.class);
        suite.addTestSuite(MessageQueueTest.class);
        suite.addTestSuite(MessengerTest.class);
        suite.addTestSuite(PatternMatcherTest.class);

        return suite;
    }
}
