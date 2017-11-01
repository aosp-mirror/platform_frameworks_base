/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.annotation;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.os.SystemClock;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * @memberDoc Value is a non-negative timestamp in the
 *            {@link SystemClock#elapsedRealtime()} time base.
 * @paramDoc Value is a non-negative timestamp in the
 *           {@link SystemClock#elapsedRealtime()} time base.
 * @returnDoc Value is a non-negative timestamp in the
 *            {@link SystemClock#elapsedRealtime()} time base.
 * @hide
 */
@Retention(SOURCE)
@Target({METHOD, PARAMETER, FIELD})
public @interface ElapsedRealtimeLong {
}
