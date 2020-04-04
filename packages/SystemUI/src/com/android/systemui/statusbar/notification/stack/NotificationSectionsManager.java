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

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.ColorInt;
import android.annotation.IntDef;
import android.annotation.LayoutRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;

import com.android.internal.annotations.VisibleForTesting;
import com.android.keyguard.KeyguardMediaPlayer;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.NotificationSectionsFeatureManager;
import com.android.systemui.statusbar.notification.people.DataListener;
import com.android.systemui.statusbar.notification.people.PeopleHubViewAdapter;
import com.android.systemui.statusbar.notification.people.PeopleHubViewBoundary;
import com.android.systemui.statusbar.notification.people.PersonViewModel;
import com.android.systemui.statusbar.notification.people.Subscription;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.notification.row.StackScrollerDecorView;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;

import java.lang.annotation.Retention;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.inject.Inject;

import kotlin.sequences.Sequence;

/**
 * Manages the boundaries of the two notification sections (high priority and low priority). Also
 * shows/hides the headers for those sections where appropriate.
 *
 * TODO: Move remaining sections logic from NSSL into this class.
 */
public class NotificationSectionsManager implements StackScrollAlgorithm.SectionProvider {

    private static final String TAG = "NotifSectionsManager";
    private static final boolean DEBUG = false;
    private static final boolean ENABLE_SNOOZED_CONVERSATION_HUB = false;

    private final ActivityStarter mActivityStarter;
    private final StatusBarStateController mStatusBarStateController;
    private final ConfigurationController mConfigurationController;
    private final PeopleHubViewAdapter mPeopleHubViewAdapter;
    private final KeyguardMediaPlayer mKeyguardMediaPlayer;
    private final NotificationSectionsFeatureManager mSectionsFeatureManager;
    private final int mNumberOfSections;

    private final PeopleHubViewBoundary mPeopleHubViewBoundary = new PeopleHubViewBoundary() {
        @Override
        public void setVisible(boolean isVisible) {
            if (mPeopleHubVisible != isVisible) {
                mPeopleHubVisible = isVisible;
                if (mInitialized) {
                    updateSectionBoundaries();
                }
            }
        }

        @NonNull
        @Override
        public View getAssociatedViewForClickAnimation() {
            return mPeopleHubView;
        }

        @NonNull
        @Override
        public Sequence<DataListener<PersonViewModel>> getPersonViewAdapters() {
            return mPeopleHubView.getPersonViewAdapters();
        }
    };

    private NotificationStackScrollLayout mParent;
    private boolean mInitialized = false;

    private SectionHeaderView mGentleHeader;
    @Nullable private View.OnClickListener mOnClearGentleNotifsClickListener;

    private SectionHeaderView mAlertingHeader;

    private PeopleHubView mPeopleHubView;
    private boolean mPeopleHubVisible = false;
    @Nullable private Subscription mPeopleHubSubscription;

    private MediaHeaderView mMediaControlsView;

    @Inject
    NotificationSectionsManager(
            ActivityStarter activityStarter,
            StatusBarStateController statusBarStateController,
            ConfigurationController configurationController,
            PeopleHubViewAdapter peopleHubViewAdapter,
            KeyguardMediaPlayer keyguardMediaPlayer,
            NotificationSectionsFeatureManager sectionsFeatureManager) {
        mActivityStarter = activityStarter;
        mStatusBarStateController = statusBarStateController;
        mConfigurationController = configurationController;
        mPeopleHubViewAdapter = peopleHubViewAdapter;
        mKeyguardMediaPlayer = keyguardMediaPlayer;
        mSectionsFeatureManager = sectionsFeatureManager;
        mNumberOfSections = mSectionsFeatureManager.getNumberOfBuckets();
    }

    NotificationSection[] createSectionsForBuckets() {
        int[] buckets = mSectionsFeatureManager.getNotificationBuckets();
        NotificationSection[] sections = new NotificationSection[buckets.length];
        for (int i = 0; i < buckets.length; i++) {
            sections[i] = new NotificationSection(mParent, buckets[i] /* bucket */);
        }

        return sections;
    }

    /** Must be called before use. */
    void initialize(
            NotificationStackScrollLayout parent, LayoutInflater layoutInflater) {
        if (mInitialized) {
            throw new IllegalStateException("NotificationSectionsManager already initialized");
        }
        mInitialized = true;
        mParent = parent;
        reinflateViews(layoutInflater);
        mConfigurationController.addCallback(mConfigurationListener);
    }

    private <T extends ExpandableView> T reinflateView(
            T view, LayoutInflater layoutInflater, @LayoutRes int layoutResId) {
        int oldPos = -1;
        if (view != null) {
            if (view.getTransientContainer() != null) {
                view.getTransientContainer().removeView(mGentleHeader);
            } else if (view.getParent() != null) {
                oldPos = mParent.indexOfChild(view);
                mParent.removeView(view);
            }
        }

        view = (T) layoutInflater.inflate(layoutResId, mParent, false);

        if (oldPos != -1) {
            mParent.addView(view, oldPos);
        }

        return view;
    }

    /**
     * Reinflates the entire notification header, including all decoration views.
     */
    void reinflateViews(LayoutInflater layoutInflater) {
        mGentleHeader = reinflateView(
                mGentleHeader, layoutInflater, R.layout.status_bar_notification_section_header);
        mGentleHeader.setHeaderText(R.string.notification_section_header_gentle);
        mGentleHeader.setOnHeaderClickListener(this::onGentleHeaderClick);
        mGentleHeader.setOnClearAllClickListener(this::onClearGentleNotifsClick);

        mAlertingHeader = reinflateView(
                mAlertingHeader, layoutInflater, R.layout.status_bar_notification_section_header);
        mAlertingHeader.setHeaderText(R.string.notification_section_header_alerting);
        mAlertingHeader.setOnHeaderClickListener(this::onGentleHeaderClick);

        if (mPeopleHubSubscription != null) {
            mPeopleHubSubscription.unsubscribe();
        }
        mPeopleHubView = reinflateView(mPeopleHubView, layoutInflater, R.layout.people_strip);
        if (ENABLE_SNOOZED_CONVERSATION_HUB) {
            mPeopleHubSubscription = mPeopleHubViewAdapter.bindView(mPeopleHubViewBoundary);
        }

        if (mMediaControlsView != null) {
            mKeyguardMediaPlayer.unbindView();
        }
        mMediaControlsView = reinflateView(mMediaControlsView, layoutInflater,
                R.layout.keyguard_media_header);
        mKeyguardMediaPlayer.bindView(mMediaControlsView);
    }

    /** Listener for when the "clear all" button is clicked on the gentle notification header. */
    void setOnClearGentleNotifsClickListener(View.OnClickListener listener) {
        mOnClearGentleNotifsClickListener = listener;
    }

    @Override
    public boolean beginsSection(@NonNull View view, @Nullable View previous) {
        return view == mGentleHeader
                || view == mMediaControlsView
                || view == mPeopleHubView
                || view == mAlertingHeader
                || !Objects.equals(getBucket(view), getBucket(previous));
    }

    private boolean isUsingMultipleSections() {
        return mNumberOfSections > 1;
    }

    @Nullable
    private Integer getBucket(View view) {
        if (view == mGentleHeader) {
            return BUCKET_SILENT;
        } else if (view == mMediaControlsView) {
            return BUCKET_MEDIA_CONTROLS;
        } else if (view == mPeopleHubView) {
            return BUCKET_PEOPLE;
        } else if (view == mAlertingHeader) {
            return BUCKET_ALERTING;
        } else if (view instanceof ExpandableNotificationRow) {
            return ((ExpandableNotificationRow) view).getEntry().getBucket();
        }
        return null;
    }

    /**
     * Should be called whenever notifs are added, removed, or updated. Updates section boundary
     * bookkeeping and adds/moves/removes section headers if appropriate.
     */
    void updateSectionBoundaries() {
        if (!isUsingMultipleSections()) {
            return;
        }

        // The overall strategy here is to iterate over the current children of mParent, looking
        // for where the sections headers are currently positioned, and where each section begins.
        // Then, once we find the start of a new section, we track that position as the "target" for
        // the section header, adjusted for the case where existing headers are in front of that
        // target, but won't be once they are moved / removed after the pass has completed.

        final boolean showHeaders = mStatusBarStateController.getState() != StatusBarState.KEYGUARD;
        final boolean usingPeopleFiltering = mSectionsFeatureManager.isFilteringEnabled();
        final boolean isKeyguard = mStatusBarStateController.getState() == StatusBarState.KEYGUARD;
        final boolean usingMediaControls = mSectionsFeatureManager.isMediaControlsEnabled();

        boolean peopleNotifsPresent = false;

        int currentMediaControlsIdx = -1;
        // Currently, just putting media controls in the front and incrementing the position based
        // on the number of heads-up notifs.
        int mediaControlsTarget = isKeyguard && usingMediaControls ? 0 : -1;
        int currentPeopleHeaderIdx = -1;
        int peopleHeaderTarget = -1;
        int currentAlertingHeaderIdx = -1;
        int alertingHeaderTarget = -1;
        int currentGentleHeaderIdx = -1;
        int gentleHeaderTarget = -1;

        int lastNotifIndex = 0;

        final int childCount = mParent.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = mParent.getChildAt(i);

            // Track the existing positions of the headers
            if (child == mMediaControlsView) {
                currentMediaControlsIdx = i;
                continue;
            }
            if (child == mPeopleHubView) {
                currentPeopleHeaderIdx = i;
                continue;
            }
            if (child == mAlertingHeader) {
                currentAlertingHeaderIdx = i;
                continue;
            }
            if (child == mGentleHeader) {
                currentGentleHeaderIdx = i;
                continue;
            }

            if (!(child instanceof ExpandableNotificationRow)) {
                continue;
            }
            lastNotifIndex = i;
            ExpandableNotificationRow row = (ExpandableNotificationRow) child;
            // Once we enter a new section, calculate the target position for the header.
            switch (row.getEntry().getBucket()) {
                case BUCKET_HEADS_UP:
                    if (mediaControlsTarget != -1) {
                        mediaControlsTarget++;
                    }
                    break;
                case BUCKET_PEOPLE:
                    peopleNotifsPresent = true;
                    if (showHeaders && peopleHeaderTarget == -1) {
                        peopleHeaderTarget = i;
                        // Offset the target if there are other headers before this that will be
                        // moved.
                        if (currentPeopleHeaderIdx != -1) {
                            peopleHeaderTarget--;
                        }
                        if (currentAlertingHeaderIdx != -1) {
                            peopleHeaderTarget--;
                        }
                        if (currentGentleHeaderIdx != -1) {
                            peopleHeaderTarget--;
                        }
                    }
                    break;
                case BUCKET_ALERTING:
                    if (showHeaders && usingPeopleFiltering && alertingHeaderTarget == -1) {
                        alertingHeaderTarget = i;
                        // Offset the target if there are other headers before this that will be
                        // moved.
                        if (currentAlertingHeaderIdx != -1) {
                            alertingHeaderTarget--;
                        }
                        if (currentGentleHeaderIdx != -1) {
                            alertingHeaderTarget--;
                        }
                    }
                    break;
                case BUCKET_SILENT:
                    if (showHeaders && gentleHeaderTarget == -1) {
                        gentleHeaderTarget = i;
                        // Offset the target if there are other headers before this that will be
                        // moved.
                        if (currentGentleHeaderIdx != -1) {
                            gentleHeaderTarget--;
                        }
                    }
                    break;
                default:
                    throw new IllegalStateException("Cannot find section bucket for view");
            }
        }
        if (showHeaders && usingPeopleFiltering && mPeopleHubVisible && peopleHeaderTarget == -1) {
            // Insert the people header even if there are no people visible, in order to show
            // the hub. Put it directly above the next header.
            if (alertingHeaderTarget != -1) {
                peopleHeaderTarget = alertingHeaderTarget;
            } else if (gentleHeaderTarget != -1) {
                peopleHeaderTarget = gentleHeaderTarget;
            } else {
                // Put it at the end of the list.
                peopleHeaderTarget = lastNotifIndex;
            }
            // Offset the target to account for the current position of the people header.
            if (currentPeopleHeaderIdx != -1 && currentPeopleHeaderIdx < peopleHeaderTarget) {
                peopleHeaderTarget--;
            }
        }

        // Add headers in reverse order to preserve indices
        adjustHeaderVisibilityAndPosition(
                gentleHeaderTarget, mGentleHeader, currentGentleHeaderIdx);
        adjustHeaderVisibilityAndPosition(
                alertingHeaderTarget, mAlertingHeader, currentAlertingHeaderIdx);
        adjustHeaderVisibilityAndPosition(
                peopleHeaderTarget, mPeopleHubView, currentPeopleHeaderIdx);
        adjustViewPosition(
                mediaControlsTarget, mMediaControlsView, currentMediaControlsIdx);

        // Update headers to reflect state of section contents
        mGentleHeader.setAreThereDismissableGentleNotifs(
                mParent.hasActiveClearableNotifications(ROWS_GENTLE));
        mPeopleHubView.setCanSwipe(showHeaders && mPeopleHubVisible && !peopleNotifsPresent);
        if (peopleHeaderTarget != currentPeopleHeaderIdx) {
            mPeopleHubView.resetTranslation();
        }
    }

    private void adjustHeaderVisibilityAndPosition(
            int targetPosition, StackScrollerDecorView header, int currentPosition) {
        if (targetPosition == -1) {
            if (currentPosition != -1) {
                mParent.removeView(header);
            }
        } else {
            if (currentPosition == -1) {
                // If the header is animating away, it will still have a parent, so detach it first
                // TODO: We should really cancel the active animations here. This will happen
                // automatically when the view's intro animation starts, but it's a fragile link.
                if (header.getTransientContainer() != null) {
                    header.getTransientContainer().removeTransientView(header);
                    header.setTransientContainer(null);
                }
                header.setContentVisible(true);
                mParent.addView(header, targetPosition);
            } else {
                mParent.changeViewPosition(header, targetPosition);
            }
        }
    }

    private void adjustViewPosition(int targetPosition, ExpandableView header,
            int currentPosition) {
        if (targetPosition == -1) {
            if (currentPosition != -1) {
                mParent.removeView(header);
            }
        } else {
            if (currentPosition == -1) {
                // If the header is animating away, it will still have a parent, so detach it first
                // TODO: We should really cancel the active animations here. This will happen
                // automatically when the view's intro animation starts, but it's a fragile link.
                if (header.getTransientContainer() != null) {
                    header.getTransientContainer().removeTransientView(header);
                    header.setTransientContainer(null);
                }
                mParent.addView(header, targetPosition);
            } else {
                mParent.changeViewPosition(header, targetPosition);
            }
        }
    }

    /**
     * Updates the boundaries (as tracked by their first and last views) of the priority sections.
     *
     * @return {@code true} If the last view in the top section changed (so we need to animate).
     */
    boolean updateFirstAndLastViewsForAllSections(
            NotificationSection[] sections,
            List<ActivatableNotificationView> children) {

        if (sections.length <= 0 || children.size() <= 0) {
            for (NotificationSection s : sections) {
                s.setFirstVisibleChild(null);
                s.setLastVisibleChild(null);
            }
            return false;
        }

        boolean changed = false;
        ArrayList<ActivatableNotificationView> viewsInBucket = new ArrayList<>();
        for (NotificationSection s : sections) {
            int filter = s.getBucket();
            viewsInBucket.clear();

            //TODO: do this in a single pass, and more better
            for (ActivatableNotificationView v : children)  {
                Integer bucket = getBucket(v);
                if (bucket == null) {
                    throw new IllegalArgumentException("Cannot find section bucket for view");
                }

                if (bucket == filter) {
                    viewsInBucket.add(v);
                }

                if (viewsInBucket.size() >= 1) {
                    changed |= s.setFirstVisibleChild(viewsInBucket.get(0));
                    changed |= s.setLastVisibleChild(viewsInBucket.get(viewsInBucket.size() - 1));
                } else {
                    changed |= s.setFirstVisibleChild(null);
                    changed |= s.setLastVisibleChild(null);
                }
            }
        }

        if (DEBUG) {
            logSections(sections);
        }

        return changed;
    }

    private void logSections(NotificationSection[] sections) {
        for (int i = 0; i < sections.length; i++) {
            NotificationSection s = sections[i];
            ActivatableNotificationView first = s.getFirstVisibleChild();
            String fs = first == null ? "(null)"
                    :  (first instanceof ExpandableNotificationRow)
                            ? ((ExpandableNotificationRow) first).getEntry().getKey()
                            : Integer.toHexString(System.identityHashCode(first));
            ActivatableNotificationView last = s.getLastVisibleChild();
            String ls = last == null ? "(null)"
                    :  (last instanceof ExpandableNotificationRow)
                            ? ((ExpandableNotificationRow) last).getEntry().getKey()
                            : Integer.toHexString(System.identityHashCode(last));
            android.util.Log.d(TAG, "updateSections: f=" + fs + " s=" + i);
            android.util.Log.d(TAG, "updateSections: l=" + ls + " s=" + i);
        }
    }

    @VisibleForTesting
    ExpandableView getGentleHeaderView() {
        return mGentleHeader;
    }

    @VisibleForTesting
    ExpandableView getAlertingHeaderView() {
        return mAlertingHeader;
    }

    @VisibleForTesting
    ExpandableView getPeopleHeaderView() {
        return mPeopleHubView;
    }

    @VisibleForTesting
    ExpandableView getMediaControlsView() {
        return mMediaControlsView;
    }

    @VisibleForTesting
    void setPeopleHubVisible(boolean visible) {
        mPeopleHubVisible = visible;
    }

    private final ConfigurationListener mConfigurationListener = new ConfigurationListener() {
        @Override
        public void onLocaleListChanged() {
            reinflateViews(LayoutInflater.from(mParent.getContext()));
        }
    };

    private void onGentleHeaderClick(View v) {
        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_SETTINGS);
        mActivityStarter.startActivity(
                intent,
                true,
                true,
                Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    private void onClearGentleNotifsClick(View v) {
        if (mOnClearGentleNotifsClickListener != null) {
            mOnClearGentleNotifsClickListener.onClick(v);
        }
    }

    void hidePeopleRow() {
        mPeopleHubVisible = false;
        updateSectionBoundaries();
    }

    void setHeaderForegroundColor(@ColorInt int color) {
        mPeopleHubView.setTextColor(color);
        mGentleHeader.setForegroundColor(color);
        mAlertingHeader.setForegroundColor(color);
    }

    /**
     * For now, declare the available notification buckets (sections) here so that other
     * presentation code can decide what to do based on an entry's buckets
     */
    @Retention(SOURCE)
    @IntDef(prefix = { "BUCKET_" }, value = {
            BUCKET_HEADS_UP,
            BUCKET_MEDIA_CONTROLS,
            BUCKET_PEOPLE,
            BUCKET_ALERTING,
            BUCKET_SILENT
    })
    public @interface PriorityBucket {}
    public static final int BUCKET_HEADS_UP = 0;
    public static final int BUCKET_MEDIA_CONTROLS = 1;
    public static final int BUCKET_PEOPLE = 2;
    public static final int BUCKET_ALERTING = 3;
    public static final int BUCKET_SILENT = 4;
}
