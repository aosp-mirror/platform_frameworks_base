/*
 * Copyright 2024 The Android Open Source Project
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

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Message;
import android.os.UserHandle;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ResolverActivity.ResolvedComponentInfo;
import com.android.internal.app.chooser.TargetInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;


/**
 * A basic {@link AbstractResolverComparator} implementation that sorts items into the same order as
 * they appeared in the list provided to {@link #doCompute(List)}. "Unknown" items that didn't
 * appear in the original list are ordered arbitrarily at the end.
 */
public class NoOpResolverComparator extends AbstractResolverComparator {
    @Nullable
    private List<ResolveInfo> mOriginalTargetOrder = null;

    public NoOpResolverComparator(
            Context launchedFromContext,
            Intent intent,
            List<UserHandle> targetUserSpaceList) {
        super(launchedFromContext, intent, targetUserSpaceList);
    }

    @Override
    public void doCompute(List<ResolvedComponentInfo> targets) {
        mOriginalTargetOrder = new ArrayList<>();
        for (ResolvedComponentInfo target : targets) {
            mOriginalTargetOrder.add(target.getResolveInfoAt(0));
        }
        afterCompute();
    }

    @Override
    public int compare(ResolveInfo lhs, ResolveInfo rhs) {
        Comparator<ResolveInfo> c = Comparator.comparingDouble(r -> getScore((ResolveInfo) r));
        c = c.reversed();
        return c.compare(lhs, rhs);
    }

    @Override
    public float getScore(TargetInfo targetInfo) {
        return getScore(targetInfo.getResolveInfo());
    }

    @Override
    public void handleResultMessage(Message message) {}

    @VisibleForTesting
    public float getScore(ResolveInfo resolveInfo) {
        if (!mOriginalTargetOrder.contains(resolveInfo)) {
            return 0;
        }

        // Assign a score from 1 (for the first item in the original list) down
        // to 1/(n+1) for the last item (which is still greater than 0, the
        // score we assign to any unknown items).
        float rank = mOriginalTargetOrder.indexOf(resolveInfo);
        return 1.0f - (rank / (1 + mOriginalTargetOrder.size()));
    }
}
