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

import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.concurrent.Executor;

/**
 * @paramDoc Callback and listener events are dispatched through this
 *           {@link Executor}, providing an easy way to control which thread is
 *           used. To dispatch events through the main thread of your
 *           application, you can use
 *           {@link android.content.Context#getMainExecutor() Context.getMainExecutor()}.
 *           To dispatch events through a shared thread pool, you can use
 *           {@link android.os.AsyncTask#THREAD_POOL_EXECUTOR AsyncTask#THREAD_POOL_EXECUTOR}.
 * @hide
 */
@Retention(SOURCE)
@Target(PARAMETER)
public @interface CallbackExecutor {
}
