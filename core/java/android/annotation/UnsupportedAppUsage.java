/*
 * Copyright (C) 2018 The Android Open Source Project
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
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.CLASS;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Indicates that a class member, that is not part of the SDK, is used by apps.
 * Since the member is not part of the SDK, such use is not supported.
 *
 * This annotation acts as a heads up that changing a given method or field
 * may affect apps, potentially breaking them when the next Android version is
 * released. In some cases, for members that are heavily used, this annotation
 * may imply restrictions on changes to the member.
 *
 * This annotation also results in access to the member being permitted by the
 * runtime, with a warning being generated in debug builds.
 *
 * For more details, see go/UnsupportedAppUsage.
 *
 * {@hide}
 */
@Retention(CLASS)
@Target({CONSTRUCTOR, METHOD, FIELD})
public @interface UnsupportedAppUsage {

    /**
     * Associates a bug tracking the work to add a public alternative to this API. Optional.
     *
     * @return ID of the associated tracking bug
     */
    long trackingBug() default 0;

    /**
     * Indicates that usage of this API is limited to apps based on their target SDK version.
     *
     * Access to the API is allowed if the targetSdkVersion in the apps manifest is no greater than
     * this value. Access checks are performed at runtime.
     *
     * This is used to give app developers a grace period to migrate off a non-SDK interface. When
     * making Android version N, existing APIs can have a maxTargetSdk of N-1 added to them.
     * Developers must then migrate off the API when their app is updated in future, but it will
     * continue working in the meantime.
     *
     * Possible values are:
     * <ul>
     *     <li>
     *         {@link android.os.Build.VERSION_CODES#O} or {@link android.os.Build.VERSION_CODES#P},
     *         to limit access to apps targeting these SDKs (or earlier).
     *     </li>
     *     <li>
     *         absent (default value) - All apps can access this API, but doing so may result in
     *         warnings in the log, UI warnings (on developer builds) and/or strictmode violations.
     *         The API is likely to be further restricted in future.
     *     </li>
     *
     * </ul>
     *
     * Note, if this is set to {@link android.os.Build.VERSION_CODES#O}, apps targeting O
     * maintenance releases will also be allowed to use the API, and similarly for any future
     * maintenance releases of P.
     *
     * @return The maximum value for an apps targetSdkVersion in order to access this API.
     */
    int maxTargetSdk() default Integer.MAX_VALUE;

    /**
     * For debug use only. The expected dex signature to be generated for this API, used to verify
     * parts of the build process.
     *
     * @return A dex API signature.
     */
    String expectedSignature() default "";
}
