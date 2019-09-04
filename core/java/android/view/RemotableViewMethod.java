/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.view;

import android.annotation.TestApi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @hide
 * This annotation indicates that a method on a subclass of View
 * is alllowed to be used with the {@link android.widget.RemoteViews} mechanism.
 */
@TestApi
@Target({ ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
public @interface RemotableViewMethod {
    /**
     * @return Method name which can be called on a background thread. It should have the
     * same arguments as the original method and should return a {@link Runnable} (or null)
     * which will be called on the UI thread.
     */
    String asyncImpl() default "";
}
