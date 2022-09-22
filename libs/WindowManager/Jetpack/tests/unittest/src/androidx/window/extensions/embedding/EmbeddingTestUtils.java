/*
 * Copyright (C) 2022 The Android Open Source Project
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

package androidx.window.extensions.embedding;

import static android.view.Display.DEFAULT_DISPLAY;

import static androidx.window.extensions.embedding.SplitRule.FINISH_ALWAYS;
import static androidx.window.extensions.embedding.SplitRule.FINISH_NEVER;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.annotation.NonNull;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.Pair;
import android.window.TaskFragmentInfo;
import android.window.WindowContainerToken;

import androidx.window.extensions.embedding.SplitAttributes.SplitType;
import androidx.window.extensions.layout.DisplayFeature;
import androidx.window.extensions.layout.FoldingFeature;
import androidx.window.extensions.layout.WindowLayoutInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class EmbeddingTestUtils {
    static final Rect TASK_BOUNDS = new Rect(0, 0, 600, 1200);
    static final int TASK_ID = 10;
    static final SplitType SPLIT_TYPE = SplitType.RatioSplitType.splitEqually();
    static final SplitAttributes SPLIT_ATTRIBUTES = new SplitAttributes.Builder().build();
    static final String TEST_TAG = "test";
    /** Default finish behavior in Jetpack. */
    static final int DEFAULT_FINISH_PRIMARY_WITH_SECONDARY = FINISH_NEVER;
    static final int DEFAULT_FINISH_SECONDARY_WITH_PRIMARY = FINISH_ALWAYS;
    private static final float SPLIT_RATIO = 0.5f;

    private EmbeddingTestUtils() {}

    /** Gets the bounds of a TaskFragment that is in split. */
    static Rect getSplitBounds(boolean isPrimary) {
        return getSplitBounds(isPrimary, false /* shouldSplitHorizontally */);
    }

    /** Gets the bounds of a TaskFragment that is in split. */
    static Rect getSplitBounds(boolean isPrimary, boolean shouldSplitHorizontally) {
        final int dimension = (int) (
                (shouldSplitHorizontally ? TASK_BOUNDS.height() : TASK_BOUNDS.width())
                        * SPLIT_RATIO);
        if (shouldSplitHorizontally) {
            return isPrimary
                    ? new Rect(
                            TASK_BOUNDS.left,
                            TASK_BOUNDS.top,
                            TASK_BOUNDS.right,
                            TASK_BOUNDS.top + dimension)
                    : new Rect(
                            TASK_BOUNDS.left,
                            TASK_BOUNDS.top + dimension,
                            TASK_BOUNDS.right,
                            TASK_BOUNDS.bottom);
        }
        return isPrimary
                ? new Rect(
                        TASK_BOUNDS.left,
                        TASK_BOUNDS.top,
                        TASK_BOUNDS.left + dimension,
                        TASK_BOUNDS.bottom)
                : new Rect(
                        TASK_BOUNDS.left + dimension,
                        TASK_BOUNDS.top,
                        TASK_BOUNDS.right,
                        TASK_BOUNDS.bottom);
    }

    /** Creates a rule to always split the given activity and the given intent. */
    static SplitRule createSplitRule(@NonNull Activity primaryActivity,
            @NonNull Intent secondaryIntent) {
        return createSplitRule(primaryActivity, secondaryIntent, true /* clearTop */);
    }

    /** Creates a rule to always split the given activity and the given intent. */
    static SplitRule createSplitRule(@NonNull Activity primaryActivity,
            @NonNull Intent secondaryIntent, boolean clearTop) {
        final Pair<Activity, Intent> targetPair = new Pair<>(primaryActivity, secondaryIntent);
        return new SplitPairRule.Builder(
                activityPair -> false,
                targetPair::equals,
                w -> true)
                .setDefaultSplitAttributes(
                        new SplitAttributes.Builder()
                                .setSplitType(SPLIT_TYPE)
                                .build()
                )
                .setShouldClearTop(clearTop)
                .setFinishPrimaryWithSecondary(DEFAULT_FINISH_PRIMARY_WITH_SECONDARY)
                .setFinishSecondaryWithPrimary(DEFAULT_FINISH_SECONDARY_WITH_PRIMARY)
                .setTag(TEST_TAG)
                .build();
    }

    /** Creates a rule to always split the given activities. */
    static SplitRule createSplitRule(@NonNull Activity primaryActivity,
            @NonNull Activity secondaryActivity) {
        return createSplitRule(primaryActivity, secondaryActivity,
                DEFAULT_FINISH_PRIMARY_WITH_SECONDARY, DEFAULT_FINISH_SECONDARY_WITH_PRIMARY,
                true /* clearTop */);
    }

    /** Creates a rule to always split the given activities. */
    static SplitRule createSplitRule(@NonNull Activity primaryActivity,
            @NonNull Activity secondaryActivity, boolean clearTop) {
        return createSplitRule(primaryActivity, secondaryActivity,
                DEFAULT_FINISH_PRIMARY_WITH_SECONDARY, DEFAULT_FINISH_SECONDARY_WITH_PRIMARY,
                clearTop);
    }

    /** Creates a rule to always split the given activities with the given finish behaviors. */
    static SplitRule createSplitRule(@NonNull Activity primaryActivity,
            @NonNull Activity secondaryActivity, int finishPrimaryWithSecondary,
            int finishSecondaryWithPrimary, boolean clearTop) {
        final Pair<Activity, Activity> targetPair = new Pair<>(primaryActivity, secondaryActivity);
        return new SplitPairRule.Builder(
                targetPair::equals,
                activityIntentPair -> false,
                w -> true)
                .setDefaultSplitAttributes(
                        new SplitAttributes.Builder()
                                .setSplitType(SPLIT_TYPE)
                                .build()
                )
                .setFinishPrimaryWithSecondary(finishPrimaryWithSecondary)
                .setFinishSecondaryWithPrimary(finishSecondaryWithPrimary)
                .setShouldClearTop(clearTop)
                .setTag(TEST_TAG)
                .build();
    }

    /** Creates a mock TaskFragmentInfo for the given TaskFragment. */
    static TaskFragmentInfo createMockTaskFragmentInfo(@NonNull TaskFragmentContainer container,
            @NonNull Activity activity) {
        return new TaskFragmentInfo(container.getTaskFragmentToken(),
                mock(WindowContainerToken.class),
                new Configuration(),
                1,
                true /* isVisible */,
                Collections.singletonList(activity.getActivityToken()),
                new Point(),
                false /* isTaskClearedForReuse */,
                false /* isTaskFragmentClearedForPip */,
                new Point());
    }

    static ActivityInfo createActivityInfoWithMinDimensions() {
        ActivityInfo aInfo = new ActivityInfo();
        final Rect primaryBounds = getSplitBounds(true /* isPrimary */);
        aInfo.windowLayout = new ActivityInfo.WindowLayout(0, 0, 0, 0, 0,
                primaryBounds.width() + 1, primaryBounds.height() + 1);
        return aInfo;
    }

    static TaskContainer createTestTaskContainer() {
        Resources resources = mock(Resources.class);
        doReturn(new Configuration()).when(resources).getConfiguration();
        Activity activity = mock(Activity.class);
        doReturn(resources).when(activity).getResources();
        doReturn(DEFAULT_DISPLAY).when(activity).getDisplayId();

        return new TaskContainer(TASK_ID, activity);
    }

    static WindowLayoutInfo createWindowLayoutInfo() {
        final FoldingFeature foldingFeature = new FoldingFeature(
                new Rect(
                        TASK_BOUNDS.left,
                        TASK_BOUNDS.top + TASK_BOUNDS.height() / 2 - 5,
                        TASK_BOUNDS.right,
                        TASK_BOUNDS.top + TASK_BOUNDS.height() / 2 + 5
                        ),
                FoldingFeature.TYPE_HINGE,
                FoldingFeature.STATE_HALF_OPENED);
        final List<DisplayFeature> displayFeatures = new ArrayList<>();
        displayFeatures.add(foldingFeature);
        return new WindowLayoutInfo(displayFeatures);
    }
}
