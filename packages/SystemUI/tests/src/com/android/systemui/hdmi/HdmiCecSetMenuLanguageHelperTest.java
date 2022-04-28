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

package com.android.systemui.hdmi;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.UserHandle;
import android.provider.Settings;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.settings.SecureSettings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Locale;
import java.util.concurrent.Executor;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class HdmiCecSetMenuLanguageHelperTest extends SysuiTestCase {

    private HdmiCecSetMenuLanguageHelper mHdmiCecSetMenuLanguageHelper;

    @Mock
    private Executor mExecutor;

    @Mock
    private SecureSettings mSecureSettings;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mSecureSettings.getStringForUser(
                Settings.Secure.HDMI_CEC_SET_MENU_LANGUAGE_DENYLIST,
                UserHandle.USER_CURRENT)).thenReturn(null);
        mHdmiCecSetMenuLanguageHelper =
                new HdmiCecSetMenuLanguageHelper(mExecutor, mSecureSettings);
    }

    @Test
    public void testSetGetLocale() {
        mHdmiCecSetMenuLanguageHelper.setLocale("en");
        assertThat(mHdmiCecSetMenuLanguageHelper.getLocale()).isEqualTo(Locale.ENGLISH);
    }

    @Test
    public void testIsLocaleDenylisted_EmptyByDefault() {
        mHdmiCecSetMenuLanguageHelper.setLocale("en");
        assertThat(mHdmiCecSetMenuLanguageHelper.isLocaleDenylisted()).isEqualTo(false);
    }

    @Test
    public void testIsLocaleDenylisted_AcceptLanguage() {
        mHdmiCecSetMenuLanguageHelper.setLocale("de");
        mHdmiCecSetMenuLanguageHelper.acceptLocale();
        assertThat(mHdmiCecSetMenuLanguageHelper.isLocaleDenylisted()).isEqualTo(false);
        verify(mExecutor).execute(any());
    }

    @Test
    public void testIsLocaleDenylisted_DeclineLanguage() {
        mHdmiCecSetMenuLanguageHelper.setLocale("de");
        mHdmiCecSetMenuLanguageHelper.declineLocale();
        assertThat(mHdmiCecSetMenuLanguageHelper.isLocaleDenylisted()).isEqualTo(true);
        verify(mSecureSettings).putStringForUser(
                Settings.Secure.HDMI_CEC_SET_MENU_LANGUAGE_DENYLIST, "de",
                UserHandle.USER_CURRENT);
    }

    @Test
    public void testIsLocaleDenylisted_DeclineTwoLanguages() {
        mHdmiCecSetMenuLanguageHelper.setLocale("de");
        mHdmiCecSetMenuLanguageHelper.declineLocale();
        assertThat(mHdmiCecSetMenuLanguageHelper.isLocaleDenylisted()).isEqualTo(true);
        verify(mSecureSettings).putStringForUser(
                Settings.Secure.HDMI_CEC_SET_MENU_LANGUAGE_DENYLIST, "de",
                UserHandle.USER_CURRENT);
        mHdmiCecSetMenuLanguageHelper.setLocale("pl");
        mHdmiCecSetMenuLanguageHelper.declineLocale();
        assertThat(mHdmiCecSetMenuLanguageHelper.isLocaleDenylisted()).isEqualTo(true);
        verify(mSecureSettings).putStringForUser(
                Settings.Secure.HDMI_CEC_SET_MENU_LANGUAGE_DENYLIST, "de,pl",
                UserHandle.USER_CURRENT);
    }
}
