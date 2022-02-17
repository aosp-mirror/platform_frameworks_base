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
 * Value used for both stats maps and uid stats map.
 */
public class StatsMapValue extends Struct {
    @Field(order = 0, type = Type.U63)
    public final long rxPackets;

    @Field(order = 1, type = Type.U63)
    public final long rxBytes;

    @Field(order = 2, type = Type.U63)
    public final long txPackets;

    @Field(order = 3, type = Type.U63)
    public final long txBytes;

    public StatsMapValue(final long rxPackets, final long rxBytes, final long txPackets,
            final long txBytes) {
        this.rxPackets = rxPackets;
        this.rxBytes = rxBytes;
        this.txPackets = txPackets;
        this.txBytes = txBytes;
    }
}
