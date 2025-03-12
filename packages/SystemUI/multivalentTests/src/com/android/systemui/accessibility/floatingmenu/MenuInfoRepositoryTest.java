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

package com.android.systemui.accessibility.floatingmenu;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;

import android.content.res.Configuration;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.settings.SecureSettings;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Locale;

/** Tests for {@link MenuInfoRepository}. */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class MenuInfoRepositoryTest extends SysuiTestCase {
    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private MenuInfoRepository.OnSettingsContentsChanged mMockSettingsContentsChanged;
    @Mock
    private SecureSettings mSecureSettings;

    private MenuInfoRepository mMenuInfoRepository;

    @Before
    public void setUp() {
        mMenuInfoRepository = new MenuInfoRepository(mContext, mMockSettingsContentsChanged,
                mSecureSettings);
    }

    @Test
    public void menuSizeTypeChanged_verifyOnSizeTypeChanged() {
        mMenuInfoRepository.mMenuSizeContentObserver.onChange(true);

        verify(mMockSettingsContentsChanged).onSizeTypeChanged(anyInt());
    }

    @Test
    public void menuOpacityChanged_verifyOnFadeEffectChanged() {
        mMenuInfoRepository.mMenuFadeOutContentObserver.onChange(true);

        verify(mMockSettingsContentsChanged).onFadeEffectInfoChanged(any(MenuFadeEffectInfo.class));
    }

    @Test
    public void localeChange_verifyTargetFeaturesChanged() {
        final Configuration configuration = new Configuration();
        configuration.setLocale(Locale.TAIWAN);

        mMenuInfoRepository.mComponentCallbacks.onConfigurationChanged(configuration);

        verify(mMockSettingsContentsChanged).onTargetFeaturesChanged(any());
    }
}
