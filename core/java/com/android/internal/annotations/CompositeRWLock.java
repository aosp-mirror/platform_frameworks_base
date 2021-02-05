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

package com.android.internal.annotations;

import static java.lang.annotation.ElementType.FIELD;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specifies a list of locks which are required for read/write operations on a data field.
 *
 * <p>
 * To annotate methods accessing the data field with the annotation {@link CompositeRWLock},
 * use {@link GuardedBy#value} to annotate method w/ write and/or read access to the data field,
 * use {@link GuardedBy#anyOf} to annotate method w/ read only access to the data field.
 * </p>
 *
 * <p>
 * When its {@link #value()} consists of multiple locks:
 * <ul>
 *   <li>To write to the protected data, acquire <b>all</b> of the locks
 *       in the order of the appearance in the {@link #value}.</li>
 *   <li>To read from the protected data, acquire any of the locks in the {@link #value}.</li>
 * </ul>
 * </p>
 */
@Target({FIELD})
@Retention(RetentionPolicy.CLASS)
public @interface CompositeRWLock {
    String[] value() default {};
}
