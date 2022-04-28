/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.content.Context.OVERRIDABLE_COMPONENT_CALLBACKS;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;

import android.app.WindowConfiguration;
import android.compat.testing.PlatformCompatChangeRule;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.platform.test.annotations.Presubmit;
import android.view.Display;
import android.window.WindowContext;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;


/**
 *  Build/Install/Run:
 *   atest FrameworksCoreTests:ContextWrapperTest
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ContextWrapperTest {
    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

    /**
     * Before {@link android.os.Build.VERSION_CODES#TIRAMISU}, {@link ContextWrapper} must
     * register {@link ComponentCallbacks} to {@link ContextWrapper#getApplicationContext} before
     * {@link ContextWrapper#attachBaseContext(Context)}.
     */
    @DisableCompatChanges(OVERRIDABLE_COMPONENT_CALLBACKS)
    @Test
    public void testRegisterComponentCallbacksWithoutBaseContextBeforeT() {
        final ContextWrapper wrapper = new TestContextWrapper(null /* base */);
        final ComponentCallbacks callbacks = new TestComponentCallbacks2();

        // It should be no-op if unregister a ComponentCallbacks without registration.
        wrapper.unregisterComponentCallbacks(callbacks);

        wrapper.registerComponentCallbacks(callbacks);

        assertThat(wrapper.mCallbacksRegisteredToSuper.size()).isEqualTo(1);
        assertThat(wrapper.mCallbacksRegisteredToSuper.get(0)).isEqualTo(callbacks);

        wrapper.unregisterComponentCallbacks(callbacks);

        assertThat(wrapper.mCallbacksRegisteredToSuper.isEmpty()).isTrue();
    }

    /**
     * After {@link android.os.Build.VERSION_CODES#TIRAMISU}, {@link ContextWrapper} must
     * throw {@link IllegalStateException} before {@link ContextWrapper#attachBaseContext(Context)}.
     */
    @Test
    public void testRegisterComponentCallbacksWithoutBaseContextAfterT() {
        final ContextWrapper wrapper = new TestContextWrapper(null /* base */);
        final ComponentCallbacks callbacks = new TestComponentCallbacks2();

        try {
            wrapper.unregisterComponentCallbacks(callbacks);
            fail("ContextWrapper#unregisterComponentCallbacks must throw Exception before"
                    + " ContextWrapper#attachToBaseContext.");
        } catch (IllegalStateException ignored) {
            // It is expected to throw IllegalStateException.
        }

        try {
            wrapper.registerComponentCallbacks(callbacks);
            fail("ContextWrapper#registerComponentCallbacks must throw Exception before"
                    + " ContextWrapper#attachToBaseContext.");
        } catch (IllegalStateException ignored) {
            // It is expected to throw IllegalStateException.
        }
    }

    /**
     * {@link ContextWrapper#registerComponentCallbacks(ComponentCallbacks)} must delegate to its
     * {@link ContextWrapper#getBaseContext()}, so does
     * {@link ContextWrapper#unregisterComponentCallbacks(ComponentCallbacks)}.
     */
    @Test
    public void testRegisterComponentCallbacks() {
        final Context appContext = ApplicationProvider.getApplicationContext();
        final Display display = appContext.getSystemService(DisplayManager.class)
                .getDisplay(DEFAULT_DISPLAY);
        final WindowContext windowContext = (WindowContext) appContext.createWindowContext(display,
                TYPE_APPLICATION_OVERLAY, null /* options */);
        final ContextWrapper wrapper = new ContextWrapper(windowContext);
        final TestComponentCallbacks2 callbacks = new TestComponentCallbacks2();

        wrapper.registerComponentCallbacks(callbacks);

        assertThat(wrapper.mCallbacksRegisteredToSuper).isNull();

        final Configuration dispatchedConfig = new Configuration();
        dispatchedConfig.fontScale = 1.2f;
        dispatchedConfig.windowConfiguration.setWindowingMode(
                WindowConfiguration.WINDOWING_MODE_FREEFORM);
        dispatchedConfig.windowConfiguration.setBounds(new Rect(0, 0, 100, 100));
        windowContext.dispatchConfigurationChanged(dispatchedConfig);

        assertThat(callbacks.mConfiguration).isEqualTo(dispatchedConfig);
    }

    private static class TestContextWrapper extends ContextWrapper {
        TestContextWrapper(Context base) {
            super(base);
        }

        @Override
        public Context getApplicationContext() {
            // The default implementation of ContextWrapper#getApplicationContext is to delegate
            // to the base Context, and it leads to NPE if #registerComponentCallbacks is called
            // directly before attach to base Context.
            // We call to ApplicationProvider#getApplicationContext to prevent NPE because
            // developers may have its implementation to prevent NPE without attaching base Context.
            final Context baseContext = getBaseContext();
            if (baseContext == null) {
                return ApplicationProvider.getApplicationContext();
            } else {
                return super.getApplicationContext();
            }
        }
    }
}
