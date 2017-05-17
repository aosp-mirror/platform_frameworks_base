package com.android.server.locksettings;

public class WeaverBasedSyntheticPasswordTests extends SyntheticPasswordTests {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mSpManager.enableWeaver();
    }

}
