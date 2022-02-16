/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.pm;


import java.util.List;

/* package private */ class IntentFilterVerificationResponse {
    public final int callerUid;
    public final int code;
    public final List<String> failedDomains;

    public IntentFilterVerificationResponse(int callerUid, int code, List<String> failedDomains) {
        this.callerUid = callerUid;
        this.code = code;
        this.failedDomains = failedDomains;
    }

    public String getFailedDomainsString() {
        StringBuilder sb = new StringBuilder();
        for (String domain : failedDomains) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(domain);
        }
        return sb.toString();
    }
}
