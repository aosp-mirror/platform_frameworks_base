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

package com.android.systemui.statusbar.notification.stack;

import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;

/**
 * Manages the boundaries of the two notification sections (high priority and low priority). Also
 * shows/hides the headers for those sections where appropriate.
 *
 * TODO: Move remaining sections logic from NSSL into this class.
 */
class NotificationSectionsManager implements StackScrollAlgorithm.SectionProvider {
    private final NotificationStackScrollLayout mParent;
    private final ActivityStarter mActivityStarter;
    private final boolean mUseMultipleSections;

    private SectionHeaderView mGentleHeader;
    private boolean mGentleHeaderVisible = false;

    NotificationSectionsManager(
            NotificationStackScrollLayout parent,
            ActivityStarter activityStarter,
            boolean useMultipleSections) {
        mParent = parent;
        mActivityStarter = activityStarter;
        mUseMultipleSections = useMultipleSections;
    }

    /**
     * Must be called before use. Should be called again whenever inflation-related things change,
     * such as density or theme changes.
     */
    void inflateViews(Context context) {
        int oldPos = -1;
        if (mGentleHeader != null) {
            if (mGentleHeader.getTransientContainer() != null) {
                mGentleHeader.getTransientContainer().removeView(mGentleHeader);
            } else if (mGentleHeader.getParent() != null) {
                oldPos = mParent.indexOfChild(mGentleHeader);
                mParent.removeView(mGentleHeader);
            }
        }

        mGentleHeader = (SectionHeaderView) LayoutInflater.from(context).inflate(
                R.layout.status_bar_notification_section_header, mParent, false);
        mGentleHeader.setOnHeaderClickListener(this::onGentleHeaderClick);

        if (oldPos != -1) {
            mParent.addView(mGentleHeader, oldPos);
        }
    }

    /** Must be called whenever the UI mode changes (i.e. when we enter night mode). */
    void onUiModeChanged() {
        mGentleHeader.onUiModeChanged();
    }

    @Override
    public boolean beginsSection(View view) {
        return view == mGentleHeader;
    }

    /**
     * Should be called whenever notifs are added, removed, or updated. Updates section boundary
     * bookkeeping and adds/moves/removes section headers if appropriate.
     */
    void updateSectionBoundaries() {
        if (!mUseMultipleSections) {
            return;
        }

        int firstGentleNotifIndex = -1;

        final int n = mParent.getChildCount();
        for (int i = 0; i < n; i++) {
            View child = mParent.getChildAt(i);
            if (child instanceof ExpandableNotificationRow
                    && child.getVisibility() != View.GONE) {
                ExpandableNotificationRow row = (ExpandableNotificationRow) child;
                if (!row.getEntry().isHighPriority()) {
                    firstGentleNotifIndex = i;
                    break;
                }
            }
        }

        adjustGentleHeaderVisibilityAndPosition(firstGentleNotifIndex);
    }

    private void adjustGentleHeaderVisibilityAndPosition(int firstGentleNotifIndex) {
        final int currentHeaderIndex = mParent.indexOfChild(mGentleHeader);

        if (firstGentleNotifIndex == -1) {
            if (mGentleHeaderVisible) {
                mGentleHeaderVisible = false;
                mParent.removeView(mGentleHeader);
            }
        } else {
            if (!mGentleHeaderVisible) {
                mGentleHeaderVisible = true;
                // If the header is animating away, it will still have a parent, so detach it first
                // TODO: We should really cancel the active animations here. This will happen
                // automatically when the view's intro animation starts, but it's a fragile link.
                if (mGentleHeader.getTransientContainer() != null) {
                    mGentleHeader.getTransientContainer().removeTransientView(mGentleHeader);
                    mGentleHeader.setTransientContainer(null);
                }
                mParent.addView(mGentleHeader, firstGentleNotifIndex);
            } else if (currentHeaderIndex != firstGentleNotifIndex - 1) {
                // Relocate the header to be immediately before the first child in the section
                int targetIndex = firstGentleNotifIndex;
                if (currentHeaderIndex < firstGentleNotifIndex) {
                    // Adjust the target index to account for the header itself being temporarily
                    // removed during the position change.
                    targetIndex--;
                }

                mParent.changeViewPosition(mGentleHeader, targetIndex);
            }
        }
    }

    /**
     * Updates the boundaries (as tracked by their first and last views) of the high and low
     * priority sections.
     *
     * @return {@code true} If the last view in the top section changed (so we need to animate).
     */
    boolean updateFirstAndLastViewsInSections(
            final NotificationSection highPrioritySection,
            final NotificationSection lowPrioritySection,
            ActivatableNotificationView firstChild,
            ActivatableNotificationView lastChild) {
        if (mUseMultipleSections) {
            ActivatableNotificationView previousLastHighPriorityChild =
                    highPrioritySection.getLastVisibleChild();
            ActivatableNotificationView previousFirstLowPriorityChild =
                    lowPrioritySection.getFirstVisibleChild();
            ActivatableNotificationView lastHighPriorityChild = getLastHighPriorityChild();
            ActivatableNotificationView firstLowPriorityChild = getFirstLowPriorityChild();
            if (lastHighPriorityChild != null && firstLowPriorityChild != null) {
                highPrioritySection.setFirstVisibleChild(firstChild);
                highPrioritySection.setLastVisibleChild(lastHighPriorityChild);
                lowPrioritySection.setFirstVisibleChild(firstLowPriorityChild);
                lowPrioritySection.setLastVisibleChild(lastChild);
            } else if (lastHighPriorityChild != null) {
                highPrioritySection.setFirstVisibleChild(firstChild);
                highPrioritySection.setLastVisibleChild(lastChild);
                lowPrioritySection.setFirstVisibleChild(null);
                lowPrioritySection.setLastVisibleChild(null);
            } else {
                highPrioritySection.setFirstVisibleChild(null);
                highPrioritySection.setLastVisibleChild(null);
                lowPrioritySection.setFirstVisibleChild(firstChild);
                lowPrioritySection.setLastVisibleChild(lastChild);
            }
            return lastHighPriorityChild != previousLastHighPriorityChild
                    || firstLowPriorityChild != previousFirstLowPriorityChild;
        } else {
            highPrioritySection.setFirstVisibleChild(firstChild);
            highPrioritySection.setLastVisibleChild(lastChild);
            return false;
        }
    }

    @VisibleForTesting
    SectionHeaderView getGentleHeaderView() {
        return mGentleHeader;
    }

    @Nullable
    private ActivatableNotificationView getFirstLowPriorityChild() {
        return mGentleHeaderVisible ? mGentleHeader : null;
    }

    @Nullable
    private ActivatableNotificationView getLastHighPriorityChild() {
        ActivatableNotificationView lastChildBeforeGap = null;
        int childCount = mParent.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = mParent.getChildAt(i);
            if (child.getVisibility() != View.GONE && child instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow row = (ExpandableNotificationRow) child;
                if (!row.getEntry().isHighPriority()) {
                    break;
                } else {
                    lastChildBeforeGap = row;
                }
            }
        }
        return lastChildBeforeGap;
    }

    private void onGentleHeaderClick(View v) {
        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_SETTINGS);
        mActivityStarter.startActivity(
                intent,
                true,
                true,
                Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
}
