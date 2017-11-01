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

import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.permission.RuntimePermissionPresentationInfo;
import android.content.pm.permission.RuntimePermissionPresenter;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PermissionsSummaryHelper  {

    public static void getPermissionSummary(Context context, String pkg,
            final PermissionsResultCallback callback) {
        final RuntimePermissionPresenter presenter =
                RuntimePermissionPresenter.getInstance(context);
        presenter.getAppPermissions(pkg, new RuntimePermissionPresenter.OnResultCallback() {
            @Override
            public void onGetAppPermissions(
                    @NonNull List<RuntimePermissionPresentationInfo> permissions) {
                final int permissionCount = permissions.size();

                int grantedStandardCount = 0;
                int grantedAdditionalCount = 0;
                int requestedCount = 0;
                List<CharSequence> grantedStandardLabels = new ArrayList<>();

                for (int i = 0; i < permissionCount; i++) {
                    RuntimePermissionPresentationInfo permission = permissions.get(i);
                    requestedCount++;
                    if (permission.isGranted()) {
                        if (permission.isStandard()) {
                            grantedStandardLabels.add(permission.getLabel());
                            grantedStandardCount++;
                        } else {
                            grantedAdditionalCount++;
                        }
                    }
                }

                Collator collator = Collator.getInstance();
                collator.setStrength(Collator.PRIMARY);
                Collections.sort(grantedStandardLabels, collator);

                callback.onPermissionSummaryResult(grantedStandardCount, requestedCount,
                        grantedAdditionalCount, grantedStandardLabels);
            }
        }, null);
    }

    public static abstract class PermissionsResultCallback {
        public void onAppWithPermissionsCountsResult(int standardGrantedPermissionAppCount,
                int standardUsedPermissionAppCount) {
            /* do nothing - stub */
        }

        public void onPermissionSummaryResult(int standardGrantedPermissionCount,
                int requestedPermissionCount, int additionalGrantedPermissionCount,
                List<CharSequence> grantedGroupLabels) {
            /* do nothing - stub */
        }
    }
}
