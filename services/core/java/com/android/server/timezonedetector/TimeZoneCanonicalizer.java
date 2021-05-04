/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.timezonedetector;

import com.android.i18n.timezone.TimeZoneFinder;

import java.util.function.Function;

/**
 * Returns preferred time zone ID if {@code timeZoneId} was deprecated. For example, returns
 * America/Nuuk for America/Godthab.
 */
final class TimeZoneCanonicalizer implements Function<String, String> {
    @Override
    public String apply(String timeZoneId) {
        String canonicialZoneId = TimeZoneFinder.getInstance().getCountryZonesFinder()
                .findCanonicalTimeZoneId(timeZoneId);

        return canonicialZoneId == null ? timeZoneId : canonicialZoneId;
    }
}
