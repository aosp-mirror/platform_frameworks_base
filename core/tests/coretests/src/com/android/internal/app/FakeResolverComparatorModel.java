/*
 * Copyright 2022 The Android Open Source Project
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

import android.content.ComponentName;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Basic {@link ResolverComparatorModel} implementation that sorts according to a pre-defined (or
 * default) {@link java.util.Comparator}.
 */
public class FakeResolverComparatorModel implements ResolverComparatorModel {
    private final Comparator<ResolveInfo> mComparator;

    public static FakeResolverComparatorModel makeModelFromComparator(
            Comparator<ResolveInfo> comparator) {
        return new FakeResolverComparatorModel(comparator);
    }

    public static FakeResolverComparatorModel makeDefaultModel() {
       return makeModelFromComparator(Comparator.comparing(ri -> ri.activityInfo.name));
    }

    @Override
    public Comparator<ResolveInfo> getComparator() {
        return mComparator;
    }

    @Override
    public float getScore(ComponentName name) {
        return 0.0f;  // Models are not required to provide numerical scores.
    }

    @Override
    public void notifyOnTargetSelected(ComponentName componentName) {
        System.out.println(
                "User selected " + componentName + " under model " + System.identityHashCode(this));
    }

    private FakeResolverComparatorModel(Comparator<ResolveInfo> comparator) {
        mComparator = comparator;
    }
}