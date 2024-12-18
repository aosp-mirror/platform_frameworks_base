
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

package libcore.api;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates an API is part of a contract provided by the "core" set of
 * libraries to select parts of the Android software stack.
 *
 * <p>This annotation should only appear on either (a) classes that are hidden by <pre>@hide</pre>
 * javadoc tags or equivalent annotations, or (b) members of such classes. It is for use with
 * metalava's {@code --show-single-annotation} option and so must be applied at the class level and
 * applied again each member that is to be made part of the API. Members that are not part of the
 * API do not have to be explicitly hidden.
 *
 * @hide
 */
@IntraCoreApi
@Target({TYPE, FIELD, METHOD, CONSTRUCTOR, ANNOTATION_TYPE})
@Retention(RetentionPolicy.SOURCE)
public @interface CorePlatformApi {

    /** Enumeration of the possible statuses of the API in the core/platform API surface. */
    @IntraCoreApi
    enum Status {

        /**
         * This API is considered stable, and so present in both the stable and legacy version of
         * the API surface.
        */
        @IntraCoreApi
        STABLE,

        /**
         * This API is not (yet) considered stable, and so only present in the legacy version of
         * the API surface.
         */
        @IntraCoreApi
        LEGACY_ONLY
    }

    /** The status of the API in the core/platform API surface. */
    @IntraCoreApi
    Status status() default Status.LEGACY_ONLY;
}
