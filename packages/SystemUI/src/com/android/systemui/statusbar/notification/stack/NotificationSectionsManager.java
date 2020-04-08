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

import static com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout.ROWS_GENTLE;

import android.annotation.Nullable;
import android.content.Intent;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;

/**
 * Manages the boundaries of the two notification sections (high priority and low priority). Also
 * shows/hides the headers for those sections where appropriate.
 *
 * TODO: Move remaining sections logic from NSSL into this class.
 */
class NotificationSectionsManager implements StackScrollAlgorithm.SectionProvider {
    private final NotificationStackScrollLayout mParent;
    private final ActivityStarter mActivityStarter;
    private final StatusBarStateController mStatusBarStateController;
    private final ConfigurationController mConfigurationController;
    private final boolean mUseMultipleSections;

    private boolean mInitialized = false;
    private SectionHeaderView mGentleHeader;
    private SectionHeaderView mAlertHeader;
    private boolean mGentleHeaderVisible = false;
    private boolean mAlertHeaderVisible = false;
    @Nullable private ExpandableNotificationRow mFirstGentleNotif;
    @Nullable private ExpandableNotificationRow mFirstAlertNotif;

    NotificationSectionsManager(
            NotificationStackScrollLayout parent,
            ActivityStarter activityStarter,
            StatusBarStateController statusBarStateController,
            ConfigurationController configurationController,
            boolean useMultipleSections) {
        mParent = parent;
        mActivityStarter = activityStarter;
        mStatusBarStateController = statusBarStateController;
        mConfigurationController = configurationController;
        mUseMultipleSections = useMultipleSections;
    }

    /** Must be called before use. */
    void initialize(LayoutInflater layoutInflater) {
        if (mInitialized) {
            throw new IllegalStateException("NotificationSectionsManager already initialized");
        }
        mInitialized = true;
        reinflateViews(layoutInflater);
        mConfigurationController.addCallback(mConfigurationListener);
    }

    void reinflateViews(LayoutInflater layoutInflater) {
        mGentleHeader = reinflateView(layoutInflater, mGentleHeader,
                R.string.notification_section_header_gentle);
        mAlertHeader = reinflateView(layoutInflater, mAlertHeader,
                R.string.notification_section_header_alerting);
    }

    /**
     * Reinflates the entire notification header, including all decoration views.
     */
    SectionHeaderView reinflateView(LayoutInflater layoutInflater,
            SectionHeaderView sectionHeader, int labelId) {
        int oldPos = -1;
        if (sectionHeader != null) {
            if (sectionHeader.getTransientContainer() != null) {
                sectionHeader.getTransientContainer().removeView(sectionHeader);
            } else if (sectionHeader.getParent() != null) {
                oldPos = mParent.indexOfChild(sectionHeader);
                mParent.removeView(sectionHeader);
            }
        }

        sectionHeader = (SectionHeaderView) layoutInflater.inflate(
                R.layout.status_bar_notification_section_header, mParent, false);
        sectionHeader.setLabelText(mParent.getContext().getResources().getString(labelId));

        if (oldPos != -1) {
            mParent.addView(sectionHeader, oldPos);
        }

        return sectionHeader;
    }

    @Override
    public boolean beginsSection(View view) {
        return view == getFirstLowPriorityChild() || view == getFirstHighPriorityChild();
    }

    /**
     * Should be called whenever notifs are added, removed, or updated. Updates section boundary
     * bookkeeping and adds/moves/removes section headers if appropriate.
     */
    void updateSectionBoundaries() {
        if (!mUseMultipleSections) {
            return;
        }

        mGentleHeaderVisible = adjustHeaderVisibilityAndPosition(
                getFirstNotificationCategoryIndex(true), mGentleHeader, mGentleHeaderVisible);
        mAlertHeaderVisible = adjustHeaderVisibilityAndPosition(
                getFirstNotificationCategoryIndex(false), mAlertHeader, mAlertHeaderVisible);
    }

    private int getFirstNotificationCategoryIndex(boolean gentle) {
        final int n = mParent.getChildCount();
        for (int i = 0; i < n; i++) {
            View child = mParent.getChildAt(i);
            if (child instanceof ExpandableNotificationRow
                    && child.getVisibility() != View.GONE) {
                ExpandableNotificationRow row = (ExpandableNotificationRow) child;
                if (!gentle && row.getEntry().isTopBucket()) {
                    mFirstAlertNotif = row;
                    return i;
                } else if (gentle && !row.getEntry().isTopBucket()) {
                    mFirstGentleNotif = row;
                    return i;
                }
            }
        }

        return -1;
    }

    private boolean adjustHeaderVisibilityAndPosition(int firstNotifIndex,
            SectionHeaderView sectionHeader, boolean headerCurrentlyVisible) {
        final boolean showHeader = firstNotifIndex != -1
                && mStatusBarStateController.getState() != StatusBarState.KEYGUARD;
        final int currentHeaderIndex = mParent.indexOfChild(sectionHeader);

        if (!showHeader) {
            if (headerCurrentlyVisible) {
                headerCurrentlyVisible = false;
                mParent.removeView(sectionHeader);
                sectionHeader.setVisible(false, false);
            }
        } else {
            if (!headerCurrentlyVisible) {
                headerCurrentlyVisible = true;
                // If the header is animating away, it will still have a parent, so detach it first
                // TODO: We should really cancel the active animations here. This will happen
                // automatically when the view's intro animation starts, but it's a fragile link.
                if (sectionHeader.getTransientContainer() != null) {
                    sectionHeader.getTransientContainer().removeTransientView(sectionHeader);
                    sectionHeader.setTransientContainer(null);
                }
                mParent.addView(sectionHeader, firstNotifIndex);
                sectionHeader.setVisible(true, false);
            } else if (currentHeaderIndex != firstNotifIndex - 1) {
                // Relocate the header to be immediately before the first child in the section
                int targetIndex = firstNotifIndex;
                if (currentHeaderIndex < firstNotifIndex) {
                    // Adjust the target index to account for the header itself being temporarily
                    // removed during the position change.
                    targetIndex--;
                }

                mParent.changeViewPosition(sectionHeader, targetIndex);
            }
        }

        return headerCurrentlyVisible;
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
        return mFirstGentleNotif;
    }

    private ActivatableNotificationView getFirstHighPriorityChild() {
        return mFirstAlertNotif;
    }

    @Nullable
    private ActivatableNotificationView getLastHighPriorityChild() {
        ActivatableNotificationView lastChildBeforeGap = null;
        int childCount = mParent.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = mParent.getChildAt(i);
            if (child.getVisibility() != View.GONE && child instanceof ExpandableNotificationRow) {
                ExpandableNotificationRow row = (ExpandableNotificationRow) child;
                if (!row.getEntry().isTopBucket()) {
                    break;
                } else {
                    lastChildBeforeGap = row;
                }
            }
        }
        return lastChildBeforeGap;
    }

    private final ConfigurationListener mConfigurationListener = new ConfigurationListener() {
        @Override
        public void onLocaleListChanged() {
            mGentleHeader.reinflateContents();
            mAlertHeader.reinflateContents();
        }
    };
}
