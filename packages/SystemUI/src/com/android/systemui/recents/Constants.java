/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents;

/**
 * Constants
 * XXX: We are going to move almost all of these into a resource.
 */
public class Constants {
    public static class DebugFlags {
        // Enable this with any other debug flag to see more info
        public static final boolean Verbose = false;

        public static class App {
            public static final boolean EnableTaskFiltering = false;
            public static final boolean EnableTaskStackClipping = false;
            public static final boolean EnableToggleNewRecentsActivity = false;
            // This disables the bitmap and icon caches to
            public static final boolean DisableBackgroundCache = false;

            public static final boolean TaskDataLoader = false;
            public static final boolean SystemUIHandshake = false;
            public static final boolean TimeSystemCalls = false;
            public static final boolean Memory = false;
        }

        public static class UI {
            public static final boolean Draw = false;
            public static final boolean ClickEvents = false;
            public static final boolean TouchEvents = false;
            public static final boolean MeasureAndLayout = false;
            public static final boolean Clipping = false;
            public static final boolean HwLayers = false;
        }

        public static class TaskStack {
            public static final boolean SynchronizeViewsWithModel = false;
        }

        public static class ViewPool {
            public static final boolean PoolCallbacks = false;
        }
    }

    public static class Values {
        public static class Window {
            // The dark background dim is set behind the empty recents view
            public static final float DarkBackgroundDim = 0.5f;
            // The background dim is set behind the card stack
            public static final float BackgroundDim = 0.35f;
        }

        public static class RecentsTaskLoader {
            // XXX: This should be calculated on the first load
            public static final int PreloadFirstTasksCount = 5;
            // For debugging, this allows us to multiply the number of cards for each task
            public static final int TaskEntryMultiplier = 1;
        }

        public static class TaskStackView {
            public static class Animation {
                public static final int TaskRemovedReshuffleDuration = 200;
                public static final int SnapScrollBackDuration = 650;
            }

            public static final int TaskStackOverscrollRange = 150;

            // The padding will be applied to the smallest dimension, and then applied to all sides
            public static final float StackPaddingPct = 0.15f;
            // The overlap height relative to the task height
            public static final float StackOverlapPct = 0.65f;
            // The height of the peek space relative to the stack height
            public static final float StackPeekHeightPct = 0.1f;
            // The min scale of the last card in the peek area
            public static final float StackPeekMinScale = 0.9f;
            // The number of cards we see in the peek space
            public static final int StackPeekNumCards = 3;
        }

        public static class TaskView {
            public static class Animation {
                public static final int TaskDataUpdatedFadeDuration = 250;
                public static final int TaskIconOnEnterDuration = 175;
                public static final int TaskIconOnLeavingDuration = 75;
            }

            public static final boolean AnimateFrontTaskIconOnEnterRecents = true;
            public static final boolean AnimateFrontTaskIconOnLeavingRecents = true;
            public static final boolean AnimateFrontTaskIconOnEnterUseClip = false;
            public static final boolean AnimateFrontTaskIconOnLeavingUseClip = false;
            public static final boolean DrawColoredTaskBars = false;
            public static final boolean UseRoundedCorners = true;
            public static final float RoundedCornerRadiusDps = 3;

            public static final float TaskBarHeightDps = 54;
            public static final float TaskIconSizeDps = 60;
        }
    }
}