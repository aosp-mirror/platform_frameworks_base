/*
 * Copyright (C) 2019 The Android Open Source Project
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
import static java.lang.annotation.ElementType.PACKAGE;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Indicates an API that uses {@code context.getUser} or {@code context.getUserId}
 * to operate across users (as the user associated with the context)
 * <p>
 * To create a {@link android.content.Context} associated with a different user,
 *  use {@link android.content.Context#createContextAsUser} or
 *  {@link android.content.Context#createPackageContextAsUser}
 * <p>
 * Example:
 * <pre>{@code
 * {@literal @}UserHandleAware
 * public abstract PackageInfo getPackageInfo({@literal @}NonNull String packageName,
 *      {@literal @}PackageInfoFlags int flags) throws NameNotFoundException;
 * }</pre>
 *
 * @memberDoc This method uses {@linkplain android.content.Context#getUser}
 *            or {@linkplain android.content.Context#getUserId} to execute across users.
 * @hide
 */
@Retention(SOURCE)
@Target({TYPE, METHOD, CONSTRUCTOR, PACKAGE})
public @interface UserHandleAware {
}
