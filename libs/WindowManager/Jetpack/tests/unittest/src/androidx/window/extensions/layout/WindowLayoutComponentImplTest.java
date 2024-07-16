/*
 * Copyright (C) 2024 The Android Open Source Project
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

package androidx.window.extensions.layout;

import static org.mockito.Mockito.mock;

import android.content.Context;
import android.content.ContextWrapper;
import android.platform.test.annotations.Presubmit;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.window.common.DeviceStateManagerFoldingFeatureProducer;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;

/**
 * Test class for {@link WindowLayoutComponentImpl}.
 *
 * Build/Install/Run:
 *  atest WMJetpackUnitTests:WindowLayoutComponentImplTest
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class WindowLayoutComponentImplTest {

    private WindowLayoutComponentImpl mWindowLayoutComponent;

    @Before
    public void setUp() {
        mWindowLayoutComponent = new WindowLayoutComponentImpl(
                ApplicationProvider.getApplicationContext(),
                mock(DeviceStateManagerFoldingFeatureProducer.class));
    }

    @Test
    public void testAddWindowLayoutListenerOnFakeUiContext_noCrash() {
        final Context fakeUiContext = createTestContext();

        mWindowLayoutComponent.addWindowLayoutInfoListener(fakeUiContext, info -> {});

        mWindowLayoutComponent.onDisplayFeaturesChanged(Collections.emptyList());
    }

    private static Context createTestContext() {
        return new FakeUiContext(ApplicationProvider.getApplicationContext());
    }

    /**
     * A {@link android.content.Context} overrides {@link android.content.Context#isUiContext} to
     * {@code true}.
     */
    private static class FakeUiContext extends ContextWrapper {

        FakeUiContext(Context base) {
            super(base);
        }

        @Override
        public boolean isUiContext() {
            return true;
        }
    }
}
