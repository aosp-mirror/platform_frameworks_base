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

/**
 * Collection of methods to workaround limitation in the hostside JVM.
 */
public abstract class JvmWorkaround {
    JvmWorkaround() {
    }

    // We only support OpenJDK for now.
    private static JvmWorkaround sInstance =
            RavenwoodCommonUtils.isOnRavenwood() ? new OpenJdkWorkaround() : new NullWorkaround();

    public static JvmWorkaround getInstance() {
        return sInstance;
    }

    /**
     * Equivalent to Android's FileDescriptor.setInt$().
     */
    public abstract void setFdInt(FileDescriptor fd, int fdInt);


    /**
     * Equivalent to Android's FileDescriptor.getInt$().
     */
    public abstract int getFdInt(FileDescriptor fd);

    /**
     * Placeholder implementation for the host side.
     *
     * Even on the host side, we don't want to throw just because the class is loaded,
     * which could cause weird random issues, so we throw from individual methods rather
     * than from the constructor.
     */
    private static class NullWorkaround extends JvmWorkaround {
        private RuntimeException calledOnHostside() {
            throw new RuntimeException("This method shouldn't be called on the host side");
        }

        @Override
        public void setFdInt(FileDescriptor fd, int fdInt) {
            throw calledOnHostside();
        }

        @Override
        public int getFdInt(FileDescriptor fd) {
            throw calledOnHostside();
        }
    }
}
