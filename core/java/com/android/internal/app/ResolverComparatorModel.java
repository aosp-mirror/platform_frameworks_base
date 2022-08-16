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

import java.util.Comparator;
import java.util.List;

/**
 * A ranking model for resolver targets, providing ordering and (optionally) numerical scoring.
 *
 * As required by the {@link Comparator} contract, objects returned by {@code getComparator()} must
 * apply a total ordering on its inputs consistent across all calls to {@code Comparator#compare()}.
 * Other query methods and ranking feedback should refer to that same ordering, so implementors are
 * generally advised to "lock in" an immutable snapshot of their model data when this object is
 * initialized (preferring to replace the entire {@code ResolverComparatorModel} instance if the
 * backing data needs to be updated in the future).
 */
interface ResolverComparatorModel {
    /**
     * Get a {@code Comparator} that can be used to sort {@code ResolveInfo} targets according to
     * the model ranking.
     */
    Comparator<ResolveInfo> getComparator();

    /**
     * Get the numerical score, if any, that the model assigns to the component with the specified
     * {@code name}. Scores range from zero to one, with one representing the highest possible
     * likelihood that the user will select that component as the target. Implementations that don't
     * assign numerical scores are <em>recommended</em> to return a value of 0 for all components.
     */
    float getScore(ComponentName name);

    /**
     * Notify the model that the user selected a target. (Models may log this information, use it as
     * a feedback signal for their ranking, etc.) Because the data in this
     * {@code ResolverComparatorModel} instance is immutable, clients will need to get an up-to-date
     * instance in order to see any changes in the ranking that might result from this feedback.
     */
    void notifyOnTargetSelected(ComponentName componentName);
}
