/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.devicepolicy;

import android.app.admin.SecurityLog;

/**
 * Helper to call native BoringSSL self test.
 */
public class CryptoTestHelper {
    public static void runAndLogSelfTest() {
        final int result = runSelfTest();
        SecurityLog.writeEvent(SecurityLog.TAG_CRYPTO_SELF_TEST_COMPLETED, result);
    }
    private static native int runSelfTest();
}
