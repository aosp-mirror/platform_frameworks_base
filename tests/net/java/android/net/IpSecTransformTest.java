/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.net;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.support.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link IpSecTransform}. */
@SmallTest
@RunWith(JUnit4.class)
public class IpSecTransformTest {

    @Test
    public void testCreateTransformCopiesConfig() {
        // Create a config with a few parameters to make sure it's not empty
        IpSecConfig config = new IpSecConfig();
        config.setSourceAddress("0.0.0.0");
        config.setDestinationAddress("1.2.3.4");
        config.setSpiResourceId(1984);

        IpSecTransform preModification = new IpSecTransform(null, config);

        config.setSpiResourceId(1985);
        IpSecTransform postModification = new IpSecTransform(null, config);

        assertFalse(IpSecTransform.equals(preModification, postModification));
    }

    @Test
    public void testCreateTransformsWithSameConfigEqual() {
        // Create a config with a few parameters to make sure it's not empty
        IpSecConfig config = new IpSecConfig();
        config.setSourceAddress("0.0.0.0");
        config.setDestinationAddress("1.2.3.4");
        config.setSpiResourceId(1984);

        IpSecTransform config1 = new IpSecTransform(null, config);
        IpSecTransform config2 = new IpSecTransform(null, config);

        assertTrue(IpSecTransform.equals(config1, config2));
    }
}
