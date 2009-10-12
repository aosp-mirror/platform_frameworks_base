package com.android.unit_tests.vcard;

import com.android.unit_tests.AndroidTests;

import android.test.suitebuilder.TestSuiteBuilder;

import junit.framework.TestSuite;

public class VCardTests extends TestSuite {
    public static TestSuite suite() {
        TestSuiteBuilder suiteBuilder = new TestSuiteBuilder(AndroidTests.class);
        suiteBuilder.includeAllPackagesUnderHere();
        return suiteBuilder.build();
    }
}