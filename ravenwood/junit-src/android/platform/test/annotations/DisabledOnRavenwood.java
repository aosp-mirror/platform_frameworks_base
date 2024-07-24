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
 * Tests marked with this annotation are disabled when running under a Ravenwood test environment.
 *
 * A more specific method-level annotation always takes precedence over any class-level
 * annotation, and an {@link EnabledOnRavenwood} annotation always takes precedence over
 * an {@link DisabledOnRavenwood} annotation.
 *
 * This annotation only takes effect when the containing class has a {@code
 * RavenwoodRule} or {@code RavenwoodClassRule} configured. Ignoring is accomplished by
 * throwing an {@code org.junit.AssumptionViolatedException} which test infrastructure treats as
 * being ignored.
 *
 * This annotation has no effect on any other non-Ravenwood test environments.
 *
 * @hide
 */
@Inherited
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DisabledOnRavenwood {
    /**
     * One or more classes that aren't yet supported by Ravenwood, which this test depends on.
     */
    Class<?>[] blockedBy() default {};

    /**
     * General free-form description of why this test is being ignored.
     */
    String reason() default "";
}
