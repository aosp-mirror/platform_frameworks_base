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

package com.android.server.utils;

import com.android.tools.r8.keepanno.annotations.KeepItemKind;
import com.android.tools.r8.keepanno.annotations.MethodAccessFlags;
import com.android.tools.r8.keepanno.annotations.UsedByNative;

/**
 * Utility class for lazily registering native methods for a given class.
 *
 * <p><strong>Note: </strong>Most native methods are registered eagerly via the
 * native {@code JNI_OnLoad} hook when system server loads its primary native
 * lib. However, some classes within system server may be stripped if unused.
 * This class offers a way to selectively register their native methods. Such
 * register calls should typically be done from that class's {@code static {}}
 * init block.
 */
@UsedByNative(
        description = "Referenced from JNI in jni/com_android_server_utils_LazyJniRegistrar.cpp",
        kind = KeepItemKind.CLASS_AND_MEMBERS,
        methodAccess = {MethodAccessFlags.NATIVE})
public final class LazyJniRegistrar {

    // Note: {@link SystemServer#run} loads the native "android_servers" lib, so no need to do so
    // explicitly here. Classes that use this registration must not be initialized before this.

    /** Registers native methods for ConsumerIrService. */
    public static native void registerConsumerIrService();

    /** Registers native methods for GameManagerService. */
    public static native void registerGameManagerService();

    /** Registers native methods for VrManagerService. */
    public static native void registerVrManagerService();

    /** Registers native methods for Vpn (the JNI counterpart for VpnManagerService). */
    public static native void registerVpn();
}
