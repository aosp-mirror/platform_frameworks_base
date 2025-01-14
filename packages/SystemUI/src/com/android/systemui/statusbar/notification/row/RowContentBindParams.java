/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row;

import static com.android.systemui.statusbar.NotificationLockscreenUserManager.REDACTION_TYPE_NONE;
import static com.android.systemui.statusbar.NotificationLockscreenUserManager.RedactionType;
import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_CONTRACTED;
import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_EXPANDED;

import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.InflationFlag;

/**
 * Parameters for {@link RowContentBindStage}.
 */
public final class RowContentBindParams {
    private boolean mUseMinimized;
    private boolean mViewsNeedReinflation;
    private @InflationFlag int mContentViews = DEFAULT_INFLATION_FLAGS;
    private @RedactionType int mRedactionType = REDACTION_TYPE_NONE;

    /**
     * Content views that are out of date and need to be rebound.
     *
     * TODO: This should go away once {@link NotificationRowContentBinder} is broken down into
     * smaller stages as then the stage itself would be invalidated.
     */
    private @InflationFlag int mDirtyContentViews = mContentViews;

    /**
     * Set whether content should use a minimized version of its content views.
     */
    public void setUseMinimized(boolean useMinimized) {
        if (mUseMinimized != useMinimized) {
            mDirtyContentViews |= (FLAG_CONTENT_VIEW_CONTRACTED | FLAG_CONTENT_VIEW_EXPANDED);
        }
        mUseMinimized = useMinimized;
    }

    /**
     * @return Whether the row uses the minimized style.
     */
    public boolean useMinimized() {
        return mUseMinimized;
    }

    /**
     * @return What type of redaction should be used by the public view (if requested)
     */
    public @RedactionType int getRedactionType() {
        return mRedactionType;
    }

    /**
     * Set the redaction type, which controls what sort of public view is shown.
     */
    public void setRedactionType(@RedactionType int redactionType) {
        mRedactionType = redactionType;
    }

    /**
     * Require the specified content views to be bound after the rebind request.
     *
     * @see InflationFlag
     */
    public void requireContentViews(@InflationFlag int contentViews) {
        @InflationFlag int newContentViews = contentViews &= ~mContentViews;
        mContentViews |= contentViews;
        mDirtyContentViews |= newContentViews;
    }

    /**
     * Mark the content view to be freed. The view may not be immediately freeable since it may
     * be visible and animating out but this lets the binder know to free the view when safe.
     * Note that the callback passed into {@link RowContentBindStage#requestRebind}
     * may return before the view is actually freed since the view is considered up-to-date.
     *
     * @see InflationFlag
     */
    public void markContentViewsFreeable(@InflationFlag int contentViews) {
        @InflationFlag int existingFreeableContentViews = contentViews &= mContentViews;
        mContentViews &= ~contentViews;
        mDirtyContentViews |= existingFreeableContentViews;
    }

    public @InflationFlag int getContentViews() {
        return mContentViews;
    }

    /**
     * Request that all content views be rebound. This may happen if, for example, the underlying
     * layout has changed.
     */
    public void rebindAllContentViews() {
        mDirtyContentViews = mContentViews;
    }

    /**
     * Clears all dirty content views so that they no longer need to be rebound.
     */
    void clearDirtyContentViews() {
        mDirtyContentViews = 0;
    }

    public @InflationFlag int getDirtyContentViews() {
        return mDirtyContentViews;
    }

    /**
     * Set whether all content views need to be reinflated even if cached.
     *
     * TODO: This should probably be a more global config on {@link NotifBindPipeline} since this
     * generally corresponds to a Context/Configuration change that all stages should know about.
     */
    public void setNeedsReinflation(boolean needsReinflation) {
        mViewsNeedReinflation = needsReinflation;
        @InflationFlag int currentContentViews = mContentViews;
        mDirtyContentViews |= currentContentViews;
    }

    public boolean needsReinflation() {
        return mViewsNeedReinflation;
    }

    @Override
    public String toString() {
        return String.format("RowContentBindParams[mContentViews=%x mDirtyContentViews=%x "
                + "mUseMinimized=%b mViewsNeedReinflation=%b]",
                mContentViews, mDirtyContentViews, mUseMinimized, mViewsNeedReinflation);
    }

    /**
     * Content views that should be inflated by default for all notifications.
     */
    @InflationFlag private static final int DEFAULT_INFLATION_FLAGS =
            FLAG_CONTENT_VIEW_CONTRACTED | FLAG_CONTENT_VIEW_EXPANDED;
}
