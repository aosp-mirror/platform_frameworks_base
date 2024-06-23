/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.platform.test.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Tests marked with this annotation are only executed when running on Ravenwood, but not
 * on a device.
 *
 * This is basically equivalent to the opposite of {@link DisabledOnRavenwood}, but in order to
 * avoid complex structure, and there's no equivalent to the opposite {@link EnabledOnRavenwood},
 * which means if a test class has this annotation, you can't negate it in subclasses or
 * on a per-method basis.
 *
 * The {@code RAVENWOOD_RUN_DISABLED_TESTS} environmental variable won't work because it won't be
 * propagated to the device. (We may support it in the future, possibly using a debug. sysprop.)
 *
 * @hide
 */
@Inherited
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DisabledOnNonRavenwood {
    /**
     * General free-form description of why this test is being ignored.
     */
    String reason() default "";
}
