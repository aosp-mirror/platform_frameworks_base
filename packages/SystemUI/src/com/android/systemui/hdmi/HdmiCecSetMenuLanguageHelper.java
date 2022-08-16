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

import android.os.UserHandle;
import android.provider.Settings;

import com.android.internal.app.LocalePicker;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.util.settings.SecureSettings;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Helper class to separate model and view for system language change initiated by HDMI CEC.
 */
@SysUISingleton
public class HdmiCecSetMenuLanguageHelper {
    private static final String TAG = HdmiCecSetMenuLanguageHelper.class.getSimpleName();
    private static final String SEPARATOR = ",";

    private final Executor mBackgroundExecutor;
    private final SecureSettings mSecureSettings;

    private Locale mLocale;
    private HashSet<String> mDenylist;

    @Inject
    public HdmiCecSetMenuLanguageHelper(@Background Executor executor,
            SecureSettings secureSettings) {
        mBackgroundExecutor = executor;
        mSecureSettings = secureSettings;
        String denylist = mSecureSettings.getStringForUser(
                Settings.Secure.HDMI_CEC_SET_MENU_LANGUAGE_DENYLIST, UserHandle.USER_CURRENT);
        mDenylist = new HashSet<>(denylist == null
                ? Collections.EMPTY_SET
                : Arrays.asList(denylist.split(SEPARATOR)));
    }

    /**
     * Set internal locale based on given language tag.
     */
    public void setLocale(String languageTag) {
        mLocale = Locale.forLanguageTag(languageTag);
    }

    /**
     * Returns the locale from {@code <Set Menu Language>} CEC message.
     */
    public Locale getLocale() {
        return mLocale;
    }

    /**
     * Returns whether the locale from {@code <Set Menu Language>} CEC message was already
     * denylisted.
     */
    public boolean isLocaleDenylisted() {
        return mDenylist.contains(mLocale.toLanguageTag());
    }

    /**
     * Accepts the new locale and updates system language.
     */
    public void acceptLocale() {
        mBackgroundExecutor.execute(() -> LocalePicker.updateLocale(mLocale));
    }

    /**
     * Declines the locale and puts it on the denylist.
     */
    public void declineLocale() {
        mDenylist.add(mLocale.toLanguageTag());
        mSecureSettings.putStringForUser(Settings.Secure.HDMI_CEC_SET_MENU_LANGUAGE_DENYLIST,
                String.join(SEPARATOR, mDenylist), UserHandle.USER_CURRENT);
    }
}
