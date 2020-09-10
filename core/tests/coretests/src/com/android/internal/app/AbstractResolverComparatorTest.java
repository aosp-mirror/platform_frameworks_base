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

package com.android.internal.app;

import static junit.framework.Assert.assertEquals;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.os.Message;

import androidx.test.InstrumentationRegistry;

import org.junit.Test;

import java.util.List;

public class AbstractResolverComparatorTest {

    @Test
    public void testPinned() {
        ResolverActivity.ResolvedComponentInfo r1 = new ResolverActivity.ResolvedComponentInfo(
                new ComponentName("package", "class"), new Intent(), new ResolveInfo()
        );
        r1.setPinned(true);

        ResolverActivity.ResolvedComponentInfo r2 = new ResolverActivity.ResolvedComponentInfo(
                new ComponentName("zackage", "zlass"), new Intent(), new ResolveInfo()
        );

        Context context = InstrumentationRegistry.getTargetContext();
        AbstractResolverComparator comparator = getTestComparator(context);

        assertEquals("Pinned ranks over unpinned", -1, comparator.compare(r1, r2));
        assertEquals("Unpinned ranks under pinned", 1, comparator.compare(r2, r1));
    }


    @Test
    public void testBothPinned() {
        ResolveInfo pmInfo1 = new ResolveInfo();
        pmInfo1.activityInfo = new ActivityInfo();
        pmInfo1.activityInfo.packageName = "aaa";

        ResolverActivity.ResolvedComponentInfo r1 = new ResolverActivity.ResolvedComponentInfo(
                new ComponentName("package", "class"), new Intent(), pmInfo1);
        r1.setPinned(true);

        ResolveInfo pmInfo2 = new ResolveInfo();
        pmInfo2.activityInfo = new ActivityInfo();
        pmInfo2.activityInfo.packageName = "zzz";
        ResolverActivity.ResolvedComponentInfo r2 = new ResolverActivity.ResolvedComponentInfo(
                new ComponentName("zackage", "zlass"), new Intent(), pmInfo2);
        r2.setPinned(true);

        Context context = InstrumentationRegistry.getTargetContext();
        AbstractResolverComparator comparator = getTestComparator(context);

        assertEquals("Both pinned should rank alphabetically", -1, comparator.compare(r1, r2));
    }

    private AbstractResolverComparator getTestComparator(Context context) {
        Intent intent = new Intent();

        AbstractResolverComparator testComparator =
                new AbstractResolverComparator(context, intent) {

            @Override
            int compare(ResolveInfo lhs, ResolveInfo rhs) {
                // Used for testing pinning, so we should never get here --- the overrides should
                // determine the result instead.
                return 1;
            }

            @Override
            void doCompute(List<ResolverActivity.ResolvedComponentInfo> targets) {}

            @Override
            float getScore(ComponentName name) {
                return 0;
            }

            @Override
            void handleResultMessage(Message message) {}

            @Override
            List<ComponentName> getTopComponentNames(int topK) {
                return null;
            }
        };
        return testComparator;
    }

}
