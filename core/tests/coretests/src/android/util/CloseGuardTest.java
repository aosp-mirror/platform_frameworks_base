/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.util;

import libcore.dalvik.system.CloseGuardSupport;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;

/** Unit tests for {@link android.util.CloseGuard} */
public class CloseGuardTest {

    @Rule
    public final TestRule rule = CloseGuardSupport.getRule();

    @Test
    public void testEnabled_NotOpen() throws Throwable {
        ResourceOwner owner = new ResourceOwner();
        assertUnreleasedResources(owner, 0);
    }

    @Test
    public void testEnabled_OpenNotClosed() throws Throwable {
        ResourceOwner owner = new ResourceOwner();
        owner.open();
        assertUnreleasedResources(owner, 1);
    }

    @Test
    public void testEnabled_OpenThenClosed() throws Throwable {
        ResourceOwner owner = new ResourceOwner();
        owner.open();
        owner.close();
        assertUnreleasedResources(owner, 0);
    }

    @Test(expected = NullPointerException.class)
    public void testOpen_withNullMethodName_throwsNPE() throws Throwable {
        CloseGuard closeGuard = new CloseGuard();
        closeGuard.open(null);
    }

    private void assertUnreleasedResources(ResourceOwner owner, int expectedCount)
            throws Throwable {
        try {
            CloseGuardSupport.getFinalizerChecker().accept(owner, expectedCount);
        } finally {
            // Close the resource so that CloseGuard does not generate a warning for real when it
            // is actually finalized.
            owner.close();
        }
    }

    /**
     * A test user of {@link CloseGuard}.
     */
    private static class ResourceOwner {

        private final CloseGuard mCloseGuard;

        ResourceOwner() {
            mCloseGuard = new CloseGuard();
        }

        public void open() {
            mCloseGuard.open("close");
        }

        public void close() {
            mCloseGuard.close();
        }

        /**
         * Make finalize public so that it can be tested directly without relying on garbage
         * collection to trigger it.
         */
        @Override
        public void finalize() throws Throwable {
            mCloseGuard.warnIfOpen();
            super.finalize();
        }
    }
}
