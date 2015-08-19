/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.util;

import android.annotation.Nullable;

import java.util.HashSet;
import java.util.Locale;

// TODO: We don't except too many LocaleLists to exist at the same time, and
// we need access to the data at native level, so we should pass the data
// down to the native level, create a mapt of every list seen there, take a
// pointer back, and just keep that pointed in the Java-level object, so
// things could be copied very quickly.

/**
 * LocaleList is an immutable list of Locales, typically used to keep an
 * ordered user preferences for locales.
 */
public final class LocaleList {
    private final Locale[] mList;
    private static final Locale[] sEmptyList = new Locale[0];

    public Locale get(int location) {
        return location < mList.length ? mList[location] : null;
    }

    public Locale getPrimary() {
        return mList.length == 0 ? null : get(0);
    }

    public boolean isEmpty() {
        return mList.length == 0;
    }

    public int size() {
        return mList.length;
    }

    public LocaleList() {
        mList = sEmptyList;
    }

    /**
     * @throws NullPointerException if any of the input locales is <code>null</code>.
     * @throws IllegalArgumentException if any of the input locales repeat.
     */
    public LocaleList(@Nullable Locale[] list) {
        if (list == null || list.length == 0) {
            mList = sEmptyList;
        } else {
            final Locale[] localeList = new Locale[list.length];
            final HashSet<Locale> seenLocales = new HashSet<Locale>();
            for (int i = 0; i < list.length; ++i) {
                final Locale l = list[i];
                if (l == null) {
                    throw new NullPointerException();
                } else if (seenLocales.contains(l)) {
                    throw new IllegalArgumentException();
                } else {
                    seenLocales.add(l);
                    localeList[i] = (Locale) l.clone();
                }
            }
            mList = localeList;
        }
    }
}
