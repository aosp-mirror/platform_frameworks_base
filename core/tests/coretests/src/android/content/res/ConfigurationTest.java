/*
1;3409;0c * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package android.content.res;

import org.junit.runner.RunWith;
import org.junit.Test;
import org.junit.runners.JUnit4;

import android.content.res.Configuration;
import android.support.test.filters.SmallTest;
import android.platform.test.annotations.Presubmit;

import junit.framework.TestCase;

import static org.junit.Assert.assertEquals;

/**
 * Build/install/run: bit FrameworksCoreTests:android.content.res.ConfigurationTest
 */
@RunWith(JUnit4.class)
@SmallTest
@Presubmit
public class ConfigurationTest extends TestCase {
    @Test
    public void testUpdateFromPreservesRoundBit() {
        Configuration config = new Configuration();
        config.screenLayout = Configuration.SCREENLAYOUT_ROUND_YES;
        Configuration config2 = new Configuration();

        config.updateFrom(config2);
        assertEquals(config.screenLayout, Configuration.SCREENLAYOUT_ROUND_YES);
    }

    @Test
    public void testUpdateFromPreservesCompatNeededBit() {
        Configuration config = new Configuration();
        config.screenLayout = Configuration.SCREENLAYOUT_COMPAT_NEEDED;
        Configuration config2 = new Configuration();
        config.updateFrom(config2);
        assertEquals(config.screenLayout, Configuration.SCREENLAYOUT_COMPAT_NEEDED);

        config2.updateFrom(config);
        assertEquals(config2.screenLayout, Configuration.SCREENLAYOUT_COMPAT_NEEDED);
    }
}
