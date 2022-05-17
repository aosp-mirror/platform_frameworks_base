/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.wm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import android.platform.test.annotations.Presubmit;

import com.android.internal.util.GcUtils;

import org.junit.Test;
import org.junit.runners.model.Statement;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.function.Predicate;

@Presubmit
public class SystemServicesTestRuleTest {

    @Test
    public void testRule_rethrows_throwable() {
        assertThrows(Throwable.class, () -> applyRule(rule -> false));
    }

    @Test
    public void testRule_ranSuccessfully() throws Throwable {
        final int iterations = 5;
        final ArrayList<WeakReference<WindowManagerService>> wmsRefs = new ArrayList<>();
        for (int i = 0; i < iterations; i++) {
            applyRule(rule -> {
                final WindowManagerService wms = rule.getWindowManagerService();
                assertNotNull(wms);
                wmsRefs.add(new WeakReference<>(wms));
                return true;
            });
        }
        assertEquals(iterations, wmsRefs.size());

        GcUtils.runGcAndFinalizersSync();
        // Only ensure that at least one instance is released because some references may be kept
        // temporally by the message of other thread or single static reference.
        for (int i = wmsRefs.size() - 1; i >= 0; i--) {
            if (wmsRefs.get(i).get() == null) {
                return;
            }
        }
        fail("WMS instance is leaked");
    }

    private static void applyRule(Predicate<SystemServicesTestRule> action) throws Throwable {
        final SystemServicesTestRule wmsRule = new SystemServicesTestRule();
        wmsRule.apply(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                if (!action.test(wmsRule)) {
                    throw new Throwable("A failing test!");
                }
            }
        }, null /* description */).evaluate();
    }
}
