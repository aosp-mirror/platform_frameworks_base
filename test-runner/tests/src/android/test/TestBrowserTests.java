// Copyright 2007 The Android Open Source Project


package android.test;

import junit.framework.TestSuite;

public class TestBrowserTests extends TestBrowserActivity {

    @Override
    public TestSuite getTopTestSuite() {
        return suite();
    }

    public static TestSuite suite() {
        TestSuite testSuite = new TestSuite(TestBrowserTests.class.getName());
        testSuite.addTestSuite(TestBrowserControllerImplTest.class);
        testSuite.addTestSuite(TestCaseUtilTest.class);

        return testSuite;
    }
}
