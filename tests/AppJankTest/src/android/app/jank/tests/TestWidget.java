/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.app.jank.tests;

import android.app.jank.AppJankStats;
import android.app.jank.JankTracker;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

public class TestWidget extends View {

    private JankTracker mJankTracker;

    /**
     * Create JankTrackerView
     */
    public TestWidget(Context context) {
        super(context);
    }

    /**
     * Create JankTrackerView, needed by system when inflating views defined in a layout file.
     */
    public TestWidget(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
    }

    /**
     * Mock starting an animation.
     */
    public void simulateAnimationStarting() {
        if (jankTrackerCreated()) {
            mJankTracker.addUiState(AppJankStats.ANIMATION,
                    Integer.toString(this.getId()), AppJankStats.ANIMATING);
        }
    }

    /**
     * Mock ending an animation.
     */
    public void simulateAnimationEnding() {
        if (jankTrackerCreated()) {
            mJankTracker.removeUiState(AppJankStats.ANIMATION,
                    Integer.toString(this.getId()), AppJankStats.ANIMATING);
        }
    }

    private boolean jankTrackerCreated() {
        if (mJankTracker == null) {
            mJankTracker = getJankTracker();
        }
        return mJankTracker != null;
    }
}
