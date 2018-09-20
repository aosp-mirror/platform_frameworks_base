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

package android.os;

/**
 * @hide Only for use within system server.
 */
public final class ThreadLocalWorkSourceUid {
    public static final int UID_NONE = Message.UID_NONE;
    private static final ThreadLocal<Integer> sWorkSourceUid =
            ThreadLocal.withInitial(() -> UID_NONE);

    /** Returns the original work source uid. */
    public static int get() {
        return sWorkSourceUid.get();
    }

    /** Sets the original work source uid. */
    public static void set(int uid) {
        sWorkSourceUid.set(uid);
    }

    /** Clears the stored work source uid. */
    public static void clear() {
        sWorkSourceUid.set(UID_NONE);
    }

    private ThreadLocalWorkSourceUid() {
    }
}
