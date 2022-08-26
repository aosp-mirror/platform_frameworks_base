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

package android.processor.immutability;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Collection;
import java.util.Map;

/**
 * Marks a class as immutable. When used with the Immutability processor, verifies at compile that
 * the class is truly immutable. Immutable is defined as:
 * <ul>
 *     <li>Only exposes methods and/or static final constants</li>
 *     <li>Every exposed type is an @Immutable interface or otherwise immutable class</li>
 *     <ul>
 *         <li>Implicitly immutable types like {@link String} are ignored</li>
 *         <li>{@link Collection} and {@link Map} and their subclasses where immutability is
 *         enforced at runtime are ignored</li>
 *     </ul>
 *     <li>Every method must return a type (no void methods allowed)</li>
 *     <li>All inner classes must be @Immutable interfaces</li>
 * </ul>
 */
public @interface Immutable {

    /**
     * Marks a specific class, field, or method as ignored for immutability validation.
     */
    @Retention(RetentionPolicy.CLASS) // Not SOURCE as that isn't retained for some reason
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
    @interface Ignore {
        String reason() default "";
    }

    /**
     * Marks an element and its reachable children with a specific policy.
     */
    @Retention(RetentionPolicy.CLASS)
    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
    @interface Policy {
        Exception[] exceptions() default {};

        enum Exception {
            /**
             * Allow final classes with only final fields. By default these are not allowed because
             * direct field access disallows hard removal of APIs (by having their getters return
             * mocks/stubs) and also prevents field compaction, which can occur with booleans
             * stuffed into a number as flags.
             *
             * This exception is allowed though because several framework classes are built around
             * the final field access model and it would be unnecessarily difficult to migrate or
             * wrap each type.
             */
            FINAL_CLASSES_WITH_FINAL_FIELDS,
        }
    }
}
