/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.plugins.qs;

import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;

import com.android.systemui.plugins.FragmentBase;
import com.android.systemui.plugins.annotations.DependsOn;
import com.android.systemui.plugins.annotations.ProvidesInterface;
import com.android.systemui.plugins.qs.QS.HeightListener;

import java.util.function.Consumer;

/**
 * Fragment that contains QS in the notification shade.  Most of the interface is for
 * handling the expand/collapsing of the view interaction.
 */
@ProvidesInterface(action = QS.ACTION, version = QS.VERSION)
@DependsOn(target = HeightListener.class)
public interface QS extends FragmentBase {

    String ACTION = "com.android.systemui.action.PLUGIN_QS";

    int VERSION = 11;

    String TAG = "QS";

    void setPanelView(HeightListener notificationPanelView);

    void hideImmediately();
    int getQsMinExpansionHeight();
    int getDesiredHeight();
    void setHeightOverride(int desiredHeight);
    void setHeaderClickable(boolean qsExpansionEnabled);
    boolean isCustomizing();
    void setOverscrolling(boolean overscrolling);
    void setExpanded(boolean qsExpanded);
    void setListening(boolean listening);
    boolean isShowingDetail();
    void closeDetail();

    /**
     * Set that we're currently pulse expanding
     *
     * @param pulseExpanding if we're currently expanding during pulsing
     */
    default void setPulseExpanding(boolean pulseExpanding) {}
    void animateHeaderSlidingOut();
    void setQsExpansion(float qsExpansionFraction, float headerTranslation);
    void setHeaderListening(boolean listening);
    void notifyCustomizeChanged();
    void setContainer(ViewGroup container);
    void setExpandClickListener(OnClickListener onClickListener);

    View getHeader();

    default void setHasNotifications(boolean hasNotifications) {
    }

    /**
     * Should touches from the notification panel be disallowed?
     * The notification panel might grab any touches rom QS at any time to collapse the shade.
     * We should disallow that in case we are showing the detail panel.
     */
    default boolean disallowPanelTouches() {
        return isShowingDetail();
    }

    /**
     * If QS should translate as we pull it down, or if it should be static.
     */
    void setTranslateWhileExpanding(boolean shouldTranslate);

    /**
     * Set the amount of pixels we have currently dragged down if we're transitioning to the full
     * shade. 0.0f means we're not transitioning yet.
     */
    default void setTransitionToFullShadeAmount(float pxAmount, boolean animated) {}

    /**
     * A rounded corner clipping that makes QS feel as if it were behind everything.
     */
    void setFancyClipping(int top, int bottom, int cornerRadius, boolean visible);

    /**
     * @return if quick settings is fully collapsed currently
     */
    default boolean isFullyCollapsed() {
        return true;
    }

    /**
     * Add a listener for when the collapsed media visibility changes.
     */
    void setCollapsedMediaVisibilityChangedListener(Consumer<Boolean> listener);

    /**
     * Set a scroll listener for the QSPanel container
     */
    default void setScrollListener(ScrollListener scrollListener) {}

    /**
     * Callback for when QSPanel container is scrolled
     */
    @ProvidesInterface(version = ScrollListener.VERSION)
    interface ScrollListener {
        int VERSION = 1;
        void onQsPanelScrollChanged(int scrollY);
    }

    @ProvidesInterface(version = HeightListener.VERSION)
    interface HeightListener {
        int VERSION = 1;
        void onQsHeightChanged();
    }

}
