/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.settingslib.applications;

import android.content.Context;
import android.permission.PermissionControllerManager;
import android.permission.RuntimePermissionPresentationInfo;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;

/**
 * Helper to get the runtime permissions for an app.
 */
public class PermissionsSummaryHelper {

    public static void getPermissionSummary(Context context, String pkg,
            final PermissionsResultCallback callback) {
        final PermissionControllerManager permController =
                context.getSystemService(PermissionControllerManager.class);
        permController.getAppPermissions(pkg, permissions -> {

            int grantedAdditionalCount = 0;
            int requestedCount = 0;
            List<CharSequence> grantedStandardLabels = new ArrayList<>();

            for (RuntimePermissionPresentationInfo permission : permissions) {
                requestedCount++;
                if (permission.isGranted()) {
                    if (permission.isStandard()) {
                        grantedStandardLabels.add(permission.getLabel());
                    } else {
                        grantedAdditionalCount++;
                    }
                }
            }

            Collator collator = Collator.getInstance();
            collator.setStrength(Collator.PRIMARY);
            grantedStandardLabels.sort(collator);

            callback.onPermissionSummaryResult(
                    requestedCount, grantedAdditionalCount, grantedStandardLabels);
        }, null);
    }

    /**
     * Callback for the runtime permissions result for an app.
     */
    public interface PermissionsResultCallback {

        /** The runtime permission summary result for an app. */
        void onPermissionSummaryResult(
                int requestedPermissionCount, int additionalGrantedPermissionCount,
                List<CharSequence> grantedGroupLabels);
    }
}
