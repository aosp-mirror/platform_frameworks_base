/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.flags;

/**
 * List of {@link Flag} objects for use in SystemUI.
 *
 * Flag Ids are integers. They must be unique.
 *
 * On public release builds, flags will always return their default value. There is no way to
 * change their value on release builds.
 */
public class Flags {
    public static final BooleanFlag THE_FIRST_FLAG = new BooleanFlag(1, false);
}
