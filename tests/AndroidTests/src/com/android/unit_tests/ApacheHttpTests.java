package com.android.unit_tests;

import junit.framework.TestSuite;

public class ApacheHttpTests {
    public static TestSuite suite() {
        TestSuite suite = new TestSuite(ApacheHttpTests.class.getName());

        suite.addTestSuite(TestHttpService.class);

        return suite;
    }
}
