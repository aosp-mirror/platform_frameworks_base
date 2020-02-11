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

import java.lang.annotation.Repeatable;
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
@Repeatable(SystemApi.Container.class) // TODO(b/146727827): make this non-repeatable
public @interface SystemApi {
    enum Client {
        /**
         * Specifies that the intended clients of a SystemApi are privileged apps.
         * This is the default value for {@link #client}.
         */
        PRIVILEGED_APPS,

        /**
         * Specifies that the intended clients of a SystemApi are used by classes in
         * <pre>BOOTCLASSPATH</pre> in mainline modules. Mainline modules can also expose
         * this type of system APIs too when they're used only by the non-updatable
         * platform code.
         */
        MODULE_LIBRARIES,

        /**
         * Specifies that the system API is available only in the system server process.
         * Use this to expose APIs from code loaded by the system server process <em>but</em>
         * not in <pre>BOOTCLASSPATH</pre>.
         */
        SYSTEM_SERVER
    }

    /**
     * The intended client of this SystemAPI.
     */
    Client client() default android.annotation.SystemApi.Client.PRIVILEGED_APPS;

    /**
     * Container for {@link SystemApi} that allows it to be applied repeatedly to types.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(TYPE)
    @interface Container {
        SystemApi[] value();
    }
}
