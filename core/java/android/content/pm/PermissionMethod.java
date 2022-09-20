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

package android.content.pm;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.CLASS;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Documents that the subject method's job is to look
 * up whether the provided or calling uid/pid has the requested permission.
 *
 * Methods should either return `void`, but potentially throw {@link SecurityException},
 * or return {@link android.content.pm.PackageManager.PermissionResult} `int`.
 *
 * @hide
 */
@Retention(CLASS)
@Target({METHOD})
public @interface PermissionMethod {}
