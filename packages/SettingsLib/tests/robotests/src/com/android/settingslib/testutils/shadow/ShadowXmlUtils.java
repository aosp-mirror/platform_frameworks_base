/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settingslib.testutils.shadow;

import static org.robolectric.shadow.api.Shadow.directlyOn;

import com.android.internal.util.XmlUtils;

import org.robolectric.Robolectric;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;

@Implements(XmlUtils.class)
public class ShadowXmlUtils {

    @Implementation
    public static final int convertValueToInt(CharSequence charSeq, int defaultValue) {
        final Class<?> xmlUtilsClass = ReflectionHelpers.loadClass(
                Robolectric.class.getClassLoader(), "com.android.internal.util.XmlUtils");
        try {
            return directlyOn(xmlUtilsClass, "convertValueToInt",
                    ReflectionHelpers.ClassParameter.from(CharSequence.class, charSeq),
                    ReflectionHelpers.ClassParameter.from(int.class, new Integer(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}