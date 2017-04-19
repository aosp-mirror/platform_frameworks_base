package com.android.server;

public class WeaverBasedSyntheticPasswordTests extends SyntheticPasswordTests {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mSpManager.enableWeaver();
    }

}
