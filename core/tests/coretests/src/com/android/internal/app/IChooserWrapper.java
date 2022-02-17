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

package com.android.internal.app;

import android.annotation.Nullable;
import android.app.usage.UsageStatsManager;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.UserHandle;

import com.android.internal.app.ResolverListAdapter.ResolveInfoPresentationGetter;
import com.android.internal.app.chooser.DisplayResolveInfo;

/**
 * Test-only extended API capabilities that an instrumented ChooserActivity subclass provides in
 * order to expose the internals for override/inspection. Implementations should apply the overrides
 * specified by the {@code ChooserActivityOverrideData} singleton.
 */
public interface IChooserWrapper {
    ChooserListAdapter getAdapter();
    ChooserListAdapter getPersonalListAdapter();
    ChooserListAdapter getWorkListAdapter();
    boolean getIsSelected();
    UsageStatsManager getUsageStatsManager();
    DisplayResolveInfo createTestDisplayResolveInfo(Intent originalIntent, ResolveInfo pri,
            CharSequence pLabel, CharSequence pInfo, Intent replacementIntent,
            @Nullable ResolveInfoPresentationGetter resolveInfoPresentationGetter);
    UserHandle getCurrentUserHandle();
    ChooserActivityLogger getChooserActivityLogger();
}
