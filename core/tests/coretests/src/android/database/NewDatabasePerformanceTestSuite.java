/*
 * Copyright (C) 2005 The Android Open Source Project
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

package android.database;

import junit.framework.TestSuite;

public class NewDatabasePerformanceTestSuite extends TestSuite {
    public static TestSuite suite() {
        TestSuite suite =
          new TestSuite(NewDatabasePerformanceTestSuite.class.getName());

        suite.addTestSuite(NewDatabasePerformanceTests.CreateTable100.class);
        suite.addTestSuite(NewDatabasePerformanceTests.Insert100.class);
        suite.addTestSuite(NewDatabasePerformanceTests.InsertIndexed100.class);
        suite.addTestSuite(NewDatabasePerformanceTests.Select100.class);
        suite.addTestSuite(NewDatabasePerformanceTests.SelectStringComparison100.class);
        suite.addTestSuite(NewDatabasePerformanceTests.SelectIndex100.class);
        suite.addTestSuite(NewDatabasePerformanceTests.InnerJoin100.class);
        suite.addTestSuite(NewDatabasePerformanceTests.InnerJoinOneSide100.class);
        suite.addTestSuite(NewDatabasePerformanceTests.InnerJoinNoIndex100.class);
        suite.addTestSuite(NewDatabasePerformanceTests.SelectSubQIndex100.class);
        suite.addTestSuite(NewDatabasePerformanceTests.SelectIndexStringComparison100.class);
        suite.addTestSuite(NewDatabasePerformanceTests.SelectInteger100.class);
        suite.addTestSuite(NewDatabasePerformanceTests.SelectString100.class);
        suite.addTestSuite(NewDatabasePerformanceTests.SelectIntegerIndex100.class);
        suite.addTestSuite(NewDatabasePerformanceTests.SelectIndexString100.class);
        suite.addTestSuite(NewDatabasePerformanceTests.SelectStringStartsWith100.class);
        suite.addTestSuite(NewDatabasePerformanceTests.DeleteIndexed100.class);
        suite.addTestSuite(NewDatabasePerformanceTests.Delete100.class);
        suite.addTestSuite(NewDatabasePerformanceTests.DeleteWhere100.class);
        suite.addTestSuite(NewDatabasePerformanceTests.DeleteIndexWhere100.class);
        suite.addTestSuite(NewDatabasePerformanceTests.UpdateIndexWhere100.class);
        suite.addTestSuite(NewDatabasePerformanceTests.UpdateWhere100.class);
        suite.addTestSuite(NewDatabasePerformanceTests.SelectStringContains100.class);
        suite.addTestSuite(NewDatabasePerformanceTests.SelectStringIndexedContains100.class);

        return suite;
    }
}
