/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.tools.layoutlib.java;

/**
 * Defines the same interface as the java.lang.AutoCloseable which was added in
 * Java 7. This hack makes it possible to run the Android code which uses Java 7
 * features (API 18 and beyond) to run on Java 6.
 * <p/>
 * Extracted from API level 18, file:
 * platform/libcore/luni/src/main/java/java/lang/AutoCloseable.java
 */
public interface AutoCloseable {
    /**
     * Closes the object and release any system resources it holds.
     */
    void close() throws Exception;
}
