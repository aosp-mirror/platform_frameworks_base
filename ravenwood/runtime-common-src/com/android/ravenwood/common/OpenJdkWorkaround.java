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
package com.android.ravenwood.common;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.WeakHashMap;

class OpenJdkWorkaround extends JvmWorkaround {

    // @GuardedBy("sAddressMap")
    private static final Map<Object, Long> sAddressMap = new WeakHashMap<>();
    // @GuardedBy("sAddressMap")
    private static long sCurrentAddress = 1;

    @Override
    public void setFdInt(FileDescriptor fd, int fdInt) {
        try {
            final Object obj = Class.forName("jdk.internal.access.SharedSecrets").getMethod(
                    "getJavaIOFileDescriptorAccess").invoke(null);
            Class.forName("jdk.internal.access.JavaIOFileDescriptorAccess").getMethod(
                    "set", FileDescriptor.class, int.class).invoke(obj, fd, fdInt);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to interact with raw FileDescriptor internals;"
                    + " perhaps JRE has changed?", e);
        }
    }

    @Override
    public int getFdInt(FileDescriptor fd) {
        try {
            final Object obj = Class.forName("jdk.internal.access.SharedSecrets").getMethod(
                    "getJavaIOFileDescriptorAccess").invoke(null);
            return (int) Class.forName("jdk.internal.access.JavaIOFileDescriptorAccess").getMethod(
                    "get", FileDescriptor.class).invoke(obj, fd);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to interact with raw FileDescriptor internals;"
                    + " perhaps JRE has changed?", e);
        }
    }

    @Override
    public void closeFd(FileDescriptor fd) throws IOException {
        try {
            final Object obj = Class.forName("jdk.internal.access.SharedSecrets").getMethod(
                    "getJavaIOFileDescriptorAccess").invoke(null);
            Class.forName("jdk.internal.access.JavaIOFileDescriptorAccess").getMethod(
                    "close", FileDescriptor.class).invoke(obj, fd);
        } catch (InvocationTargetException e) {
            SneakyThrow.sneakyThrow(e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to interact with raw FileDescriptor internals;"
                    + " perhaps JRE has changed?", e);
        }
    }

    @Override
    public long addressOf(Object o) {
        synchronized (sAddressMap) {
            Long address = sAddressMap.get(o);
            if (address == null) {
                address = sCurrentAddress++;
                sAddressMap.put(o, address);
            }
            return address;
        }
    }

    @Override
    public <T> T fromAddress(long address) {
        synchronized (sAddressMap) {
            for (var e : sAddressMap.entrySet()) {
                if (e.getValue() == address) {
                    return (T) e.getKey();
                }
            }
        }
        return null;
    }
}
