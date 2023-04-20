/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.log.dagger;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import com.android.systemui.plugins.log.LogBuffer;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;

import javax.inject.Qualifier;

/** A {@link LogBuffer} for BroadcastDispatcher-related messages. */
@Qualifier
@Documented
@Retention(RUNTIME)
public @interface BroadcastDispatcherLog {
}
