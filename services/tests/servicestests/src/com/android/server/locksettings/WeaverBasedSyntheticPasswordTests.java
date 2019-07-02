package com.android.server.locksettings;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

@SmallTest
@Presubmit
public class WeaverBasedSyntheticPasswordTests extends SyntheticPasswordTests {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mSpManager.enableWeaver();
    }

}
