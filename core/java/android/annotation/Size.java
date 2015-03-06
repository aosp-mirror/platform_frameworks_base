/*
 * Copyright (C) 2015 The Android Open Source Project
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

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.LOCAL_VARIABLE;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.SOURCE;

/**
 * Denotes that the annotated element should have a given size or length.
 * Note that "-1" means "unset". Typically used with a parameter or
 * return value of type array or collection.
 * <p>
 * Example:
 * <pre>{@code
 *  public void getLocationInWindow(&#64;Size(2) int[] location) {
 *      ...
 *  }
 * }</pre>
 *
 * @hide
 */
@Retention(SOURCE)
@Target({PARAMETER,LOCAL_VARIABLE,METHOD,FIELD})
public @interface Size {
    /** An exact size (or -1 if not specified) */
    long value() default -1;
    /** A minimum size, inclusive */
    long min() default Long.MIN_VALUE;
    /** A maximum size, inclusive */
    long max() default Long.MAX_VALUE;
    /** The size must be a multiple of this factor */
    long multiple() default 1;
}