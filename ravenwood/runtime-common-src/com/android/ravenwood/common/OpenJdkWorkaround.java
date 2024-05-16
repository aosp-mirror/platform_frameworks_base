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

class OpenJdkWorkaround extends JvmWorkaround {
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
}
