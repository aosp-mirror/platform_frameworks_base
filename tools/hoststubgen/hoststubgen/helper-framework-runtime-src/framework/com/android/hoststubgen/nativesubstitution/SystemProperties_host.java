/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.hoststubgen.nativesubstitution;

public class SystemProperties_host {
    public static String native_get(String key, String def) {
        throw new RuntimeException("Not implemented yet");
    }
    public static int native_get_int(String key, int def) {
        throw new RuntimeException("Not implemented yet");
    }
    public static long native_get_long(String key, long def) {
        throw new RuntimeException("Not implemented yet");
    }
    public static boolean native_get_boolean(String key, boolean def) {
        throw new RuntimeException("Not implemented yet");
    }

    public static long native_find(String name) {
        throw new RuntimeException("Not implemented yet");
    }
    public static String native_get(long handle) {
        throw new RuntimeException("Not implemented yet");
    }
    public static int native_get_int(long handle, int def) {
        throw new RuntimeException("Not implemented yet");
    }
    public static long native_get_long(long handle, long def) {
        throw new RuntimeException("Not implemented yet");
    }
    public static boolean native_get_boolean(long handle, boolean def) {
        throw new RuntimeException("Not implemented yet");
    }
    public static void native_set(String key, String def) {
        throw new RuntimeException("Not implemented yet");
    }
    public static void native_add_change_callback() {
        throw new RuntimeException("Not implemented yet");
    }
    public static void native_report_sysprop_change() {
        throw new RuntimeException("Not implemented yet");
    }
}
