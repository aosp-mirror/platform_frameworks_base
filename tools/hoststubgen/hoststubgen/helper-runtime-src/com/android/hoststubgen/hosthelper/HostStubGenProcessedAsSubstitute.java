/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.hoststubgen.hosthelper;

import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation injected to all methods that are processed as "substitute".
 *
 * (This annotation is only added in the impl jar, but not the stub jar)
 */
@Target({TYPE, METHOD, CONSTRUCTOR, FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface HostStubGenProcessedAsSubstitute {
    String CLASS_INTERNAL_NAME = HostTestUtils.getInternalName(
            HostStubGenProcessedAsSubstitute.class);
    String CLASS_DESCRIPTOR = "L" + CLASS_INTERNAL_NAME + ";";
}
