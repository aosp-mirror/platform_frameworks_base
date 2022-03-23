/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.server.net;

import com.android.net.module.util.Struct;
import com.android.net.module.util.Struct.Field;
import com.android.net.module.util.Struct.Type;

/**
 * The value of bpf interface index map which is used for NetworkStatsService.
 */
public class InterfaceMapValue extends Struct {
    @Field(order = 0, type = Type.ByteArray, arraysize = 16)
    public final byte[] interfaceName;

    public InterfaceMapValue(String iface) {
        final byte[] ifaceArray = iface.getBytes();
        interfaceName = new byte[16];
        // All array bytes after the interface name, if any, must be 0.
        System.arraycopy(ifaceArray, 0, interfaceName, 0, ifaceArray.length);
    }
}
