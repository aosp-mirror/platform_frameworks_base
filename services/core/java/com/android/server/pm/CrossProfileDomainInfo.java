/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.UserIdInt;
import android.content.pm.ResolveInfo;
import android.os.UserHandle;

public final class CrossProfileDomainInfo {
    /* ResolveInfo for IntentForwarderActivity to send the intent to the other profile */
    final ResolveInfo mResolveInfo;
    int mHighestApprovalLevel;
    @UserIdInt
    final int mTargetUserId;

    CrossProfileDomainInfo(ResolveInfo resolveInfo, int highestApprovalLevel, @UserIdInt
            int targetUserId) {
        this.mResolveInfo = resolveInfo;
        this.mHighestApprovalLevel = highestApprovalLevel;
        this.mTargetUserId = targetUserId;
    }

    CrossProfileDomainInfo(ResolveInfo resolveInfo, int highestApprovalLevel) {
        this.mResolveInfo = resolveInfo;
        this.mHighestApprovalLevel = highestApprovalLevel;
        this.mTargetUserId = UserHandle.USER_CURRENT; // default as current user
    }

    @Override
    public String toString() {
        return "CrossProfileDomainInfo{"
                + "resolveInfo=" + mResolveInfo
                + ", highestApprovalLevel=" + mHighestApprovalLevel
                + ", targetUserId= " + mTargetUserId
                + '}';
    }
}
