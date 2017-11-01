/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.content.Intent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Description of how the annotated broadcast action behaves.
 *
 * @hide
 */
@Target({ ElementType.FIELD })
@Retention(RetentionPolicy.SOURCE)
public @interface BroadcastBehavior {
    /**
     * This broadcast will only be delivered to an explicit target.
     *
     * @see Intent#setPackage(String)
     * @see Intent#setComponent(android.content.ComponentName)
     */
    boolean explicitOnly() default false;

    /**
     * This broadcast will only be delivered to registered receivers.
     *
     * @see Intent#FLAG_RECEIVER_REGISTERED_ONLY
     */
    boolean registeredOnly() default false;

    /**
     * This broadcast will include all {@code AndroidManifest.xml} receivers
     * regardless of process state.
     *
     * @see Intent#FLAG_RECEIVER_INCLUDE_BACKGROUND
     */
    boolean includeBackground() default false;

    /**
     * This broadcast is protected and can only be sent by the OS.
     */
    boolean protectedBroadcast() default false;
}
