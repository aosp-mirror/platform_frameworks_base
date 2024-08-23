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
package com.android.ravenwood;

import com.android.ravenwood.common.JvmWorkaround;

import java.io.FileDescriptor;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Class to host APIs that exist in libcore, but not in standard JRE.
 */
public class RavenwoodJdkPatch {
    /**
     * Implements FileDescriptor.getInt$()
     */
    public static int getInt$(FileDescriptor fd) {
        return JvmWorkaround.getInstance().getFdInt(fd);
    }

    /**
     * Implements FileDescriptor.setInt$(int)
     */
    public static void setInt$(FileDescriptor fd, int rawFd) {
        JvmWorkaround.getInstance().setFdInt(fd, rawFd);
    }

    /**
     * Implements LinkedHashMap.eldest()
     */
    public static <K, V> Map.Entry<K, V> eldest(LinkedHashMap<K, V> map) {
        final var it = map.entrySet().iterator();
        return it.hasNext() ? it.next() : null;
    }
}
