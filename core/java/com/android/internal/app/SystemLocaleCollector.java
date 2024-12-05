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

package com.android.internal.app;

import android.content.Context;
import android.os.LocaleList;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** The Locale data collector for System language. */
public class SystemLocaleCollector implements LocaleCollectorBase {
    private final Context mContext;
    private LocaleList mExplicitLocales;

    SystemLocaleCollector(Context context) {
        this(context, null);
    }

    public SystemLocaleCollector(Context context, LocaleList explicitLocales) {
        mContext = context;
        mExplicitLocales = explicitLocales;
    }

    @Override
    public Set<String> getIgnoredLocaleList(boolean translatedOnly) {
        Set<String> ignoreList = new HashSet<>();
        if (!translatedOnly) {
            final LocaleList userLocales = LocalePicker.getLocales();
            final String[] langTags = userLocales.toLanguageTags().split(",");
            Collections.addAll(ignoreList, langTags);
        }
        return ignoreList;
    }

    @Override
    public Set<LocaleStore.LocaleInfo> getSupportedLocaleList(LocaleStore.LocaleInfo parent,
            boolean translatedOnly, boolean isForCountryMode) {
        Set<String> langTagsToIgnore = getIgnoredLocaleList(translatedOnly);
        Set<LocaleStore.LocaleInfo> localeList;
        if (isForCountryMode) {
            localeList = LocaleStore.getLevelLocales(mContext,
                    langTagsToIgnore, parent, translatedOnly, mExplicitLocales);
        } else {
            localeList = LocaleStore.getLevelLocales(mContext, langTagsToIgnore,
                    null /* no parent */, translatedOnly, mExplicitLocales);
        }
        return localeList;
    }

    @Override
    public boolean hasSpecificPackageName() {
        return false;
    }
}