/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Denotes that the annotated method can be called from any thread (e.g. it is
 * "thread safe".) If the annotated element is a class, then all methods in the
 * class can be called from any thread.
 * <p>
 * The main purpose of this method is to indicate that you believe a method can
 * be called from any thread; static tools can then check that nothing you call
 * from within this method or class have more strict threading requirements.
 * <p>
 * Example:
 *
 * <pre>
 * <code>
 *  &#64;AnyThread
 *  public void deliverResult(D data) { ... }
 * </code>
 * </pre>
 *
 * @memberDoc This method is safe to call from any thread.
 * @hide
 */
@Retention(SOURCE)
@Target({METHOD,CONSTRUCTOR,TYPE,PARAMETER})
public @interface AnyThread {
}
