/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.connectivity;

import android.os.SystemProperties;
import android.sysprop.NetworkProperties;

public class MockableSystemProperties {

    public String get(String key) {
        return SystemProperties.get(key);
    }

    public int getInt(String key, int def) {
        return SystemProperties.getInt(key, def);
    }

    public boolean getBoolean(String key, boolean def) {
        return SystemProperties.getBoolean(key, def);
    }
    /**
     * Set net.tcp_def_init_rwnd to the tcp initial receive window size.
     */
    public void setTcpInitRwnd(int value) {
        NetworkProperties.tcp_init_rwnd(value);
    }
}
