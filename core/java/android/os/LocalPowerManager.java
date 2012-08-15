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

package android.os;

/** @hide */
public interface LocalPowerManager {
    // FIXME: Replace poke locks with something else.

    public static final int POKE_LOCK_IGNORE_TOUCH_EVENTS = 0x1;

    public static final int POKE_LOCK_SHORT_TIMEOUT = 0x2;
    public static final int POKE_LOCK_MEDIUM_TIMEOUT = 0x4;
    public static final int POKE_LOCK_TIMEOUT_MASK = 0x6;
}
