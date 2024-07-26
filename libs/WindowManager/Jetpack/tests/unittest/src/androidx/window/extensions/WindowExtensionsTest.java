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

package androidx.window.extensions;

import static androidx.window.extensions.WindowExtensionsImpl.getExtensionsVersionCurrentPlatform;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.platform.test.annotations.Presubmit;
import android.view.WindowManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.window.extensions.embedding.AnimationBackground;
import androidx.window.extensions.embedding.AnimationParams;
import androidx.window.extensions.embedding.SplitAttributes;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test class for {@link WindowExtensions}.
 *
 * Build/Install/Run:
 *  atest WMJetpackUnitTests:WindowExtensionsTest
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class WindowExtensionsTest {

    private WindowExtensions mExtensions;
    private int mVersion;

    @Before
    public void setUp() {
        mExtensions = WindowExtensionsProvider.getWindowExtensions();
        mVersion = mExtensions.getVendorApiLevel();
    }

    @Test
    public void testGetVendorApiLevel_extensionsEnabled_matchesCurrentVersion() {
        assumeTrue(WindowManager.hasWindowExtensionsEnabled());
        assumeFalse(((WindowExtensionsImpl) mExtensions).hasLevelOverride());
        assertThat(mVersion).isEqualTo(getExtensionsVersionCurrentPlatform());
    }

    @Test
    public void testGetVendorApiLevel_extensionsDisabled_returnsZero() {
        assumeFalse(WindowManager.hasWindowExtensionsEnabled());
        assertThat(mVersion).isEqualTo(0);
    }

    @Test
    public void testGetWindowLayoutComponent_extensionsEnabled_returnsImplementation() {
        assumeTrue(WindowManager.hasWindowExtensionsEnabled());
        assertThat(mExtensions.getWindowLayoutComponent()).isNotNull();
    }

    @Test
    public void testGetWindowLayoutComponent_extensionsDisabled_returnsNull() {
        assumeFalse(WindowManager.hasWindowExtensionsEnabled());
        assertThat(mExtensions.getWindowLayoutComponent()).isNull();
    }
    @Test
    public void testGetActivityEmbeddingComponent_featureDisabled_returnsNull() {
        assumeFalse(WindowExtensionsImpl.isActivityEmbeddingEnabled());
        assertThat(mExtensions.getActivityEmbeddingComponent()).isNull();
    }

    @Test
    public void testGetActivityEmbeddingComponent_featureEnabled_returnsImplementation() {
        assumeTrue(WindowExtensionsImpl.isActivityEmbeddingEnabled());
        assertThat(mExtensions.getActivityEmbeddingComponent()).isNotNull();
    }

    @Test
    public void testGetWindowAreaComponent_extensionsEnabled_returnsImplementation() {
        assumeTrue(WindowManager.hasWindowExtensionsEnabled());
        assertThat(mExtensions.getWindowAreaComponent()).isNotNull();
    }

    @Test
    public void testGetWindowAreaComponent_extensionsDisabled_returnsNull() {
        assumeFalse(WindowManager.hasWindowExtensionsEnabled());
        assertThat(mExtensions.getWindowAreaComponent()).isNull();
    }

    @Test
    public void testSplitAttributes_default() {
        // Make sure the default value in the extensions aar.
        final SplitAttributes splitAttributes = new SplitAttributes.Builder().build();
        assertThat(splitAttributes.getLayoutDirection())
                .isEqualTo(SplitAttributes.LayoutDirection.LOCALE);
        assertThat(splitAttributes.getSplitType())
                .isEqualTo(new SplitAttributes.SplitType.RatioSplitType(0.5f));
        assertThat(splitAttributes.getAnimationBackground())
                .isEqualTo(AnimationBackground.ANIMATION_BACKGROUND_DEFAULT);
        assertThat(splitAttributes.getAnimationParams().getAnimationBackground())
                .isEqualTo(AnimationBackground.ANIMATION_BACKGROUND_DEFAULT);
        assertThat(splitAttributes.getAnimationParams().getOpenAnimationResId())
                .isEqualTo(AnimationParams.DEFAULT_ANIMATION_RESOURCES_ID);
        assertThat(splitAttributes.getAnimationParams().getCloseAnimationResId())
                .isEqualTo(AnimationParams.DEFAULT_ANIMATION_RESOURCES_ID);
        assertThat(splitAttributes.getAnimationParams().getChangeAnimationResId())
                .isEqualTo(AnimationParams.DEFAULT_ANIMATION_RESOURCES_ID);
    }
}
