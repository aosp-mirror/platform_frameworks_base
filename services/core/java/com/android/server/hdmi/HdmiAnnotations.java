/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.hdmi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation container for Hdmi control service package.
 */
public class HdmiAnnotations {
    /**
     * Annotation type to used to mark a method which should be run on service thread.
     * This annotation should go with {@code assertRunOnServiceThread} used to verify
     * whether it's called from service thread.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.FIELD})
    public @interface ServiceThreadOnly {
    }

    /**
     * Annotation type to used to mark a method which should be run on io thread.
     * This annotation should go with {@code assertRunOnIoThread} used to verify
     * whether it's called from io thread.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.FIELD})
    public @interface IoThreadOnly {
    }
}
