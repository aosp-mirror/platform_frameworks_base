/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.settingslib.widget;

import android.content.Context;
import android.support.v14.preference.PreferenceFragment;
import android.support.v7.preference.PreferenceScreen;

import com.android.settingslib.core.lifecycle.Lifecycle;
import com.android.settingslib.core.lifecycle.LifecycleObserver;
import com.android.settingslib.core.lifecycle.events.SetPreferenceScreen;

public class FooterPreferenceMixin implements LifecycleObserver, SetPreferenceScreen {

    private final PreferenceFragment mFragment;
    private FooterPreference mFooterPreference;

    public FooterPreferenceMixin(PreferenceFragment fragment, Lifecycle lifecycle) {
        mFragment = fragment;
        lifecycle.addObserver(this);
    }

    @Override
    public void setPreferenceScreen(PreferenceScreen preferenceScreen) {
        if (mFooterPreference != null) {
            preferenceScreen.addPreference(mFooterPreference);
        }
    }

    /**
     * Creates a new {@link FooterPreference}.
     */
    public FooterPreference createFooterPreference() {
        final PreferenceScreen screen = mFragment.getPreferenceScreen();
        if (mFooterPreference != null && screen != null) {
            screen.removePreference(mFooterPreference);
        }
        mFooterPreference = new FooterPreference(getPrefContext());

        if (screen != null) {
            screen.addPreference(mFooterPreference);
        }
        return mFooterPreference;
    }

    /**
     * Returns an UI context with theme properly set for new Preference objects.
     */
    private Context getPrefContext() {
        return mFragment.getPreferenceManager().getContext();
    }

    public boolean hasFooter() {
        return mFooterPreference != null;
    }
}

