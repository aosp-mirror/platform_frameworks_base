package com.android.server.locksettings;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.runner.RunWith;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class WeaverBasedSyntheticPasswordTests extends SyntheticPasswordTests {

    @Before
    public void enableWeaver() throws Exception {
        mSpManager.enableWeaver();
    }
}
