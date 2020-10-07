/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.pm.intent.verify.legacy;

import static android.content.pm.PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED;

import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.parsing.component.ParsedIntentInfo;

import com.android.server.pm.PackageSetting;

public class IntentVerifyUtils {

    public static boolean hasValidDomains(ParsedIntentInfo filter) {
        return filter.hasCategory(Intent.CATEGORY_BROWSABLE)
                && (filter.hasDataScheme(IntentFilter.SCHEME_HTTP) ||
                filter.hasDataScheme(IntentFilter.SCHEME_HTTPS));
    }

    // Returns a packed value as a long:
    //
    // high 'int'-sized word: link status: undefined/ask/never/always.
    // low 'int'-sized word: relative priority among 'always' results.
    public static long getDomainVerificationStatus(PackageSetting ps, int userId) {
        long result = ps.getDomainVerificationStatusForUser(userId);
        // if none available, get the status
        if (result >> 32 == INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED) {
            if (ps.getIntentFilterVerificationInfo() != null) {
                result = ((long) ps.getIntentFilterVerificationInfo().getStatus()) << 32;
            }
        }
        return result;
    }
}
