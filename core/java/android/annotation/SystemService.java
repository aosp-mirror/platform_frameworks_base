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

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Description of a system service available through
 * {@link android.content.Context#getSystemService(Class)}. This is used to auto-generate
 * documentation explaining how to obtain a reference to the service.
 *
 * @hide
 */
@Retention(SOURCE)
@Target(TYPE)
public @interface SystemService {
    /**
     * The string name of the system service that can be passed to
     * {@link android.content.Context#getSystemService(String)}.
     *
     * @see android.content.Context#getSystemServiceName(Class)
     */
    String value();
}
