/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import org.junit.Test;

import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Test class for {@link WindowContainerController}.
 *
 * Build/Install/Run:
 *  bit FrameworksServicesTests:com.android.server.wm.WindowContainerControllerTests
 */
@SmallTest
@Presubmit
@org.junit.runner.RunWith(AndroidJUnit4.class)
public class WindowContainerControllerTests extends WindowTestsBase {

    @Test
    public void testCreation() throws Exception {
        final WindowContainerController controller = new WindowContainerController(null, sWm);
        final WindowContainer container = new WindowContainer();

        container.setController(controller);
        assertEquals(controller, container.getController());
        assertEquals(controller.mContainer, container);
    }

    @Test
    public void testSetContainer() throws Exception {
        final WindowContainerController controller = new WindowContainerController(null, sWm);
        final WindowContainer container = new WindowContainer();

        controller.setContainer(container);
        assertEquals(controller.mContainer, container);

        // Assert we can't change the container to another one once set
        boolean gotException = false;
        try {
            controller.setContainer(new WindowContainer());
        } catch (IllegalArgumentException e) {
            gotException = true;
        }
        assertTrue(gotException);

        // Assert that we can set the container to null.
        controller.setContainer(null);
        assertNull(controller.mContainer);
    }

    @Test
    public void testRemoveContainer() throws Exception {
        final WindowContainerController controller = new WindowContainerController(null, sWm);
        final WindowContainer container = new WindowContainer();

        controller.setContainer(container);
        assertEquals(controller.mContainer, container);

        controller.removeContainer();
        assertNull(controller.mContainer);
    }
}
