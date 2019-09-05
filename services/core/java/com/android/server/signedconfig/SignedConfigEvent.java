/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.server.signedconfig;

import android.util.StatsLog;

/**
 * Helper class to allow a SignedConfigReported event to be built up in stages.
 */
public class SignedConfigEvent {

    public int type = StatsLog.SIGNED_CONFIG_REPORTED__TYPE__UNKNOWN_TYPE;
    public int status = StatsLog.SIGNED_CONFIG_REPORTED__STATUS__UNKNOWN_STATUS;
    public int version = 0;
    public String fromPackage = null;
    public int verifiedWith = StatsLog.SIGNED_CONFIG_REPORTED__VERIFIED_WITH__NO_KEY;

    /**
     * Write this event to statslog.
     */
    public void send() {
        StatsLog.write(StatsLog.SIGNED_CONFIG_REPORTED,
                type, status, version, fromPackage, verifiedWith);
    }

}
