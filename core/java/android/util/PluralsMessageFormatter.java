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

package android.util;

import android.annotation.NonNull;
import android.annotation.StringRes;
import android.content.res.Resources;
import android.icu.text.MessageFormat;

import java.util.Map;

/**
 * Helper class for easier formatting of ICU {@link android.icu.text.MessageFormat} syntax.
 * @hide
 */
public class PluralsMessageFormatter {
    /**
     * Formatting the ICU {@link android.icu.text.MessageFormat} syntax
     *
     * @param resources the {@link android.content.res.Resources}
     * @param arguments the mapping of argument names and values
     * @param messageId the string resource id with {@link android.icu.text.MessageFormat} syntax
     * @return the formatted result
     */
    public static String format(@NonNull Resources resources,
            Map<String, Object> arguments,
            @StringRes int messageId) {
        return new MessageFormat(resources.getString(messageId)).format(arguments);
    }
}
