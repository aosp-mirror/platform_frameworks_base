/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.platform.test.annotations.Presubmit;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class ProgressBarTest {
    private final Instrumentation mInstrumentation = InstrumentationRegistry.getInstrumentation();
    private ProgressBar mBar;
    private AccessibilityNodeInfo mInfo;

    @Before
    public void setUp() throws Exception {
        // enable accessibility
        mInstrumentation.getUiAutomation();
        // create ProgressBar on main thread and call setProgress on main thread
        mInstrumentation.runOnMainSync(() ->
                mBar = new ProgressBar(
                        InstrumentationRegistry.getInstrumentation().getContext(),
                        null,
                        com.android.internal.R.attr.progressBarStyleHorizontal
                )
        );
        mInfo = AccessibilityNodeInfo.obtain();
    }

    @After
    public void tearDown() {
        mInfo.recycle();
    }

    @Test
    public void testStateDescription_determinateProgressBar_default() {
        mBar.setIndeterminate(false);
        assertFalse(mBar.isIndeterminate());

        mInstrumentation.runOnMainSync(() -> mBar.setProgress(50));

        mBar.onInitializeAccessibilityNodeInfo(mInfo);
        assertEquals("50%", mInfo.getStateDescription().toString());
    }

    @Test
    public void testStateDescription_determinateProgressBar_custom_viewApi() {
        mBar.setIndeterminate(false);
        assertFalse(mBar.isIndeterminate());
        // A workaround for the not-attached ProgressBar.
        mBar.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                info.setStateDescription(host.getStateDescription());
                super.onInitializeAccessibilityNodeInfo(host, info);
            }
        });

        mBar.setStateDescription("custom state");
        mInstrumentation.runOnMainSync(() -> mBar.setProgress(50));

        assertEquals("custom state", mBar.getStateDescription().toString());
        mBar.onInitializeAccessibilityNodeInfo(mInfo);
        assertEquals("custom state", mInfo.getStateDescription().toString());

        mBar.setStateDescription(null);

        assertNull(mBar.getStateDescription());
        mInfo.recycle();
        mInfo = AccessibilityNodeInfo.obtain();
        mBar.onInitializeAccessibilityNodeInfo(mInfo);
        assertEquals("50%", mInfo.getStateDescription().toString());
    }

    @Test
    public void testStateDescription_determinateProgressBar_custom_accessibilityNodeInfoApi() {
        mBar.setIndeterminate(false);
        assertFalse(mBar.isIndeterminate());
        mBar.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setStateDescription("custom state");
            }
        });

        mInstrumentation.runOnMainSync(() -> mBar.setProgress(50));

        mBar.onInitializeAccessibilityNodeInfo(mInfo);
        assertEquals("custom state", mInfo.getStateDescription().toString());
    }

    @Test
    public void testStateDescription_indeterminateProgressBar_default() {
        mBar.setIndeterminate(true);
        assertTrue(mBar.isIndeterminate());

        // call setMax to invoke call to ProgressBar#onProgressRefresh()
        mInstrumentation.runOnMainSync(() -> mBar.setMax(200));

        mBar.onInitializeAccessibilityNodeInfo(mInfo);
        assertEquals("in progress", mInfo.getStateDescription().toString());
    }

    @Test
    public void testStateDescription_indeterminateProgressBar_custom_viewApi() {
        mBar.setIndeterminate(true);
        assertTrue(mBar.isIndeterminate());
        // A workaround for the not-attached ProgressBar.
        mBar.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                info.setStateDescription(host.getStateDescription());
                super.onInitializeAccessibilityNodeInfo(host, info);
            }
        });

        mBar.setStateDescription("custom state");
        // call setMax to invoke call to ProgressBar#onProgressRefresh()
        mInstrumentation.runOnMainSync(() -> mBar.setMax(200));

        assertEquals("custom state", mBar.getStateDescription().toString());
        mBar.onInitializeAccessibilityNodeInfo(mInfo);
        assertEquals("custom state", mInfo.getStateDescription().toString());

        mBar.setStateDescription(null);

        assertNull(mBar.getStateDescription());
        mInfo.recycle();
        mInfo = AccessibilityNodeInfo.obtain();
        mBar.onInitializeAccessibilityNodeInfo(mInfo);
        assertEquals("in progress", mInfo.getStateDescription().toString());
    }

    @Test
    public void testStateDescription_indeterminateProgressBar_custom_accessibilityNodeInfoApi() {
        mBar.setIndeterminate(true);
        assertTrue(mBar.isIndeterminate());
        mBar.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setStateDescription("custom state");
            }
        });

        // call setMax to invoke call to ProgressBar#onProgressRefresh()
        mInstrumentation.runOnMainSync(() -> mBar.setMax(200));

        mBar.onInitializeAccessibilityNodeInfo(mInfo);
        assertEquals("custom state", mInfo.getStateDescription().toString());
    }
}
