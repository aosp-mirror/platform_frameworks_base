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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;

import android.app.WindowConfiguration;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.Display;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.window.common.DeviceStateManagerFoldingFeatureProducer;
import androidx.window.common.layout.CommonFoldingFeature;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.List;

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

    private final Context mAppContext = ApplicationProvider.getApplicationContext();

    @NonNull
    private WindowLayoutComponentImpl mWindowLayoutComponent;

    @Before
    public void setUp() {
        mWindowLayoutComponent = new WindowLayoutComponentImpl(mAppContext,
                mock(DeviceStateManagerFoldingFeatureProducer.class));
    }

    @Test
    public void testAddWindowLayoutListener_onFakeUiContext_noCrash() {
        final Context fakeUiContext = new FakeUiContext(mAppContext);

        mWindowLayoutComponent.addWindowLayoutInfoListener(fakeUiContext, info -> {});

        mWindowLayoutComponent.onDisplayFeaturesChanged(Collections.emptyList());
    }

    @Test
    public void testGetCurrentWindowLayoutInfo_noFoldingFeature_returnsEmptyList() {
        final Context testUiContext = new TestUiContext(mAppContext);

        final WindowLayoutInfo layoutInfo =
                mWindowLayoutComponent.getCurrentWindowLayoutInfo(testUiContext);

        assertThat(layoutInfo.getDisplayFeatures()).isEmpty();
    }

    @Test
    public void testGetCurrentWindowLayoutInfo_hasFoldingFeature_returnsWindowLayoutInfo() {
        final Context testUiContext = new TestUiContext(mAppContext);
        final WindowConfiguration windowConfiguration =
                testUiContext.getResources().getConfiguration().windowConfiguration;
        final Rect featureRect = windowConfiguration.getBounds();
        final CommonFoldingFeature foldingFeature = new CommonFoldingFeature(
                CommonFoldingFeature.COMMON_TYPE_HINGE,
                CommonFoldingFeature.COMMON_STATE_FLAT,
                featureRect
        );
        mWindowLayoutComponent.onDisplayFeaturesChanged(List.of(foldingFeature));

        final WindowLayoutInfo layoutInfo =
                mWindowLayoutComponent.getCurrentWindowLayoutInfo(testUiContext);

        assertThat(layoutInfo.getDisplayFeatures()).containsExactly(new FoldingFeature(
                featureRect, FoldingFeature.TYPE_HINGE, FoldingFeature.STATE_FLAT));
    }

    @Test
    public void testGetCurrentWindowLayoutInfo_nonUiContext_returnsEmptyList() {
        final WindowLayoutInfo layoutInfo =
                mWindowLayoutComponent.getCurrentWindowLayoutInfo(mAppContext);

        assertThat(layoutInfo.getDisplayFeatures()).isEmpty();
    }

    /**
     * A {@link Context} that simulates a UI context specifically for testing purposes.
     * This class overrides {@link Context#getAssociatedDisplayId()} to return
     * {@link Display#DEFAULT_DISPLAY}, ensuring the context is tied to the default display,
     * and {@link Context#isUiContext()} to always return {@code true}, simulating a UI context.
     */
    private static class TestUiContext extends ContextWrapper {

        TestUiContext(Context base) {
            super(base);
        }

        @Override
        public int getAssociatedDisplayId() {
            return Display.DEFAULT_DISPLAY;
        }

        @Override
        public boolean isUiContext() {
            return true;
        }
    }

    /**
     * A {@link Context} that cheats by overriding {@link Context#isUiContext} to always
     * return {@code true}. This is useful for scenarios where a UI context is needed,
     * but the underlying context isn't actually a UI one.
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
