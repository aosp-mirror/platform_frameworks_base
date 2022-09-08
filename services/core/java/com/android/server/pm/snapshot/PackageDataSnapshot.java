/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.pm.snapshot;

import android.content.pm.PackageManagerInternal;

import com.android.server.pm.Computer;
import com.android.server.pm.PackageManagerService;

/**
 * An empty interface provided as the type for a snapshot of {@link PackageManagerService} data.
 * There should be no members of this interface, to discourage its usage beyond as an input to
 * other package related APIs.
 *
 * Usage inside {@link PackageManagerInternal} and related should cast the object instance to
 * a {@link Computer} to access data.
 *
 * @hide
 */
public interface PackageDataSnapshot {
}
