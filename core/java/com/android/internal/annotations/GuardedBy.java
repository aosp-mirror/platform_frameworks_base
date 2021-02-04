/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.internal.annotations;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation type used to mark a method or field that can only be accessed when
 * holding the referenced locks.
 */
@Target({FIELD, METHOD})
@Retention(RetentionPolicy.CLASS)
public @interface GuardedBy {
    /**
     * Specifies a list of locks to be held in order to access the field/method
     * annotated with this; when used in conjunction with the {@link CompositeRWLock}, locks
     * should be acquired in the order of the appearance in the {@link #value} here.
     *
     * <p>
     * If specified, {@link #anyOf()} must be null.
     * </p>
     *
     * @see CompositeRWLock
     */
    String[] value() default {};

    /**
     * Specifies a list of locks where at least one of them must be held in order to access
     * the field/method annotated with this; it should be <em>only</em> used in the conjunction
     * with the {@link CompositeRWLock}.
     *
     * <p>
     * If specified, {@link #allOf()} must be null.
     * </p>
     *
     * @see CompositeRWLock
     */
    String[] anyOf() default {};
}
