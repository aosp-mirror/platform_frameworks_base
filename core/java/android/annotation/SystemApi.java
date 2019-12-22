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

package android.annotation;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PACKAGE;
import static java.lang.annotation.ElementType.TYPE;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates an API is exposed for use by bundled system applications.
 * <p>
 * These APIs are not guaranteed to remain consistent release-to-release,
 * and are not for use by apps linking against the Android SDK.
 * </p><p>
 * This annotation should only appear on API that is already marked <pre>@hide</pre>.
 * </p>
 *
 * @hide
 */
@Target({TYPE, FIELD, METHOD, CONSTRUCTOR, ANNOTATION_TYPE, PACKAGE})
@Retention(RetentionPolicy.RUNTIME)
public @interface SystemApi {
    enum Client {
        /**
         * Specifies that the intended clients of a SystemApi are privileged apps.
         * This is the default value for {@link #client}. This implies
         * MODULE_APPS and MODULE_LIBRARIES as well, which means that APIs will also
         * be available to module apps and jars.
         */
        PRIVILEGED_APPS,

        /**
         * Specifies that the intended clients of a SystemApi are modules implemented
         * as apps, like the NetworkStack app. This implies MODULE_LIBRARIES as well,
         * which means that APIs will also be available to module jars.
         */
        MODULE_APPS,

        /**
         * Specifies that the intended clients of a SystemApi are modules implemented
         * as libraries, like the conscrypt.jar in the conscrypt APEX.
         */
        MODULE_LIBRARIES
    }

    enum Process {
        /**
         * Specifies that the SystemAPI is available in every Java processes.
         * This is the default value for {@link #process}.
         */
        ALL,

        /**
         * Specifies that the SystemAPI is available only in the system server process.
         */
        SYSTEM_SERVER
    }

    /**
     * The intended client of this SystemAPI.
     */
    Client client() default android.annotation.SystemApi.Client.PRIVILEGED_APPS;

    /**
     * The process(es) that this SystemAPI is available
     */
    Process process() default android.annotation.SystemApi.Process.ALL;
}
