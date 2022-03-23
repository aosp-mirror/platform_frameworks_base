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

package android.content;

import static android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND;

import static com.google.common.truth.Truth.assertThat;

import android.app.WindowConfiguration;
import android.content.res.Configuration;
import android.graphics.Rect;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 *  Build/Install/Run:
 *   atest FrameworksCoreTests:ComponentCallbacksControllerTest
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ComponentCallbacksControllerTest {
    private ComponentCallbacksController mController;

    @Before
    public void setUp() {
        mController = new ComponentCallbacksController();
    }

    @Test
    public void testUnregisterCallbackWithoutRegistrationNoCrash() {
        mController.unregisterCallbacks(new FakeComponentCallbacks());
    }

    @Test
    public void testDispatchWithEmptyCallbacksNoCrash() {
        mController.dispatchConfigurationChanged(new Configuration());
        mController.dispatchLowMemory();
        mController.dispatchTrimMemory(TRIM_MEMORY_BACKGROUND);
    }

    @Test
    public void testClearCallbacksNoCrash() {
        mController.clearCallbacks();
    }

    @Test
    public void testDispatchTrimMemoryWithoutComponentCallbacks2NoCrash() {
        // Register a ComponentCallbacks instead of ComponentCallbacks2
        mController.registerCallbacks(new FakeComponentCallbacks());

        mController.dispatchTrimMemory(TRIM_MEMORY_BACKGROUND);
    }

    @Test
    public void testDispatchConfigurationChanged() throws Exception {
        final TestComponentCallbacks2 callback = new TestComponentCallbacks2();
        mController.registerCallbacks(callback);

        final Configuration config = new Configuration();
        config.windowConfiguration.setWindowingMode(WindowConfiguration.WINDOWING_MODE_FREEFORM);
        config.windowConfiguration.setBounds(new Rect(0, 0, 100, 100));

        mController.dispatchConfigurationChanged(config);

        assertThat(callback.mConfiguration).isEqualTo(config);

        mController.dispatchConfigurationChanged(Configuration.EMPTY);

        assertThat(callback.mConfiguration).isEqualTo(Configuration.EMPTY);
    }

    @Test
    public void testDispatchLowMemory() {
        final TestComponentCallbacks2 callback = new TestComponentCallbacks2();
        mController.registerCallbacks(callback);

        mController.dispatchLowMemory();

        assertThat(callback.mLowMemoryCalled).isTrue();
    }

    @Test
    public void testDispatchTrimMemory() {
        final TestComponentCallbacks2 callback = new TestComponentCallbacks2();
        mController.registerCallbacks(callback);

        mController.dispatchTrimMemory(TRIM_MEMORY_BACKGROUND);

        assertThat(callback.mLevel).isEqualTo(TRIM_MEMORY_BACKGROUND);
    }

    private static class FakeComponentCallbacks implements ComponentCallbacks {
        @Override
        public void onConfigurationChanged(@NonNull Configuration newConfig) {}

        @Override
        public void onLowMemory() {}
    }

    private static class TestComponentCallbacks2 implements ComponentCallbacks2 {
        private Configuration mConfiguration;
        private boolean mLowMemoryCalled;
        private int mLevel;

        @Override
        public void onConfigurationChanged(@NonNull Configuration newConfig) {
            mConfiguration = newConfig;
        }

        @Override
        public void onLowMemory() {
            mLowMemoryCalled = true;
        }

        @Override
        public void onTrimMemory(int level) {
            mLevel = level;
        }
    }
}
