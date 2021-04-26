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
package android.annotation;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.CLASS;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Denotes that the annotated element requires no permissions.
 * <p>
 * This explicit annotation helps distinguish which of three states that an
 * element may exist in:
 * <ul>
 * <li>Annotated with {@link RequiresPermission}, indicating that an element
 * requires (or may require) one or more permissions.
 * <li>Annotated with {@link RequiresNoPermission}, indicating that an element
 * requires no permissions.
 * <li>Neither annotation, indicating that no explicit declaration about
 * permissions has been made for that element.
 * </ul>
 *
 * @see RequiresPermission
 * @hide
 */
@Retention(CLASS)
@Target({ANNOTATION_TYPE,METHOD,CONSTRUCTOR,FIELD,PARAMETER})
public @interface RequiresNoPermission {
}
