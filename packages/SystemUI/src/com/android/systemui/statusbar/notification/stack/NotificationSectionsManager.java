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

import android.annotation.IntDef;
import android.annotation.NonNull;
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
import com.android.systemui.statusbar.notification.people.DataListener;
import com.android.systemui.statusbar.notification.people.PeopleHubSectionFooterViewAdapter;
import com.android.systemui.statusbar.notification.people.PeopleHubSectionFooterViewBoundary;
import com.android.systemui.statusbar.notification.people.PersonViewModel;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;

import java.lang.annotation.Retention;
import java.util.ArrayList;
import java.util.List;

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

    private final NotificationStackScrollLayout mParent;
    private final ActivityStarter mActivityStarter;
    private final StatusBarStateController mStatusBarStateController;
    private final ConfigurationController mConfigurationController;
    private final int mNumberOfSections;
    private boolean mInitialized = false;

    private SectionHeaderView mGentleHeader;
    private boolean mGentleHeaderVisible = false;

    private boolean mPeopleHubVisible = false;
    private PeopleHubView mPeopleHubView;
    private final PeopleHubSectionFooterViewAdapter mPeopleHubViewAdapter;
    private final PeopleHubSectionFooterViewBoundary mPeopleHubViewBoundary =
            new PeopleHubSectionFooterViewBoundary() {
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

    @Nullable private View.OnClickListener mOnClearGentleNotifsClickListener;

    NotificationSectionsManager(
            NotificationStackScrollLayout parent,
            ActivityStarter activityStarter,
            StatusBarStateController statusBarStateController,
            ConfigurationController configurationController,
            PeopleHubSectionFooterViewAdapter peopleHubViewAdapter,
            int numberOfSections) {
        mParent = parent;
        mActivityStarter = activityStarter;
        mStatusBarStateController = statusBarStateController;
        mConfigurationController = configurationController;
        mPeopleHubViewAdapter = peopleHubViewAdapter;
        mNumberOfSections = numberOfSections;
    }

    NotificationSection[] createSectionsForBuckets(int[] buckets) {
        NotificationSection[] sections = new NotificationSection[buckets.length];
        for (int i = 0; i < buckets.length; i++) {
            sections[i] = new NotificationSection(mParent, buckets[i] /* bucket */);
        }

        return sections;
    }

    /** Must be called before use. */
    void initialize(LayoutInflater layoutInflater) {
        if (mInitialized) {
            throw new IllegalStateException("NotificationSectionsManager already initialized");
        }
        mInitialized = true;
        reinflateViews(layoutInflater);
        mPeopleHubViewAdapter.bindView(mPeopleHubViewBoundary);
        mConfigurationController.addCallback(mConfigurationListener);
    }

    /**
     * Reinflates the entire notification header, including all decoration views.
     */
    void reinflateViews(LayoutInflater layoutInflater) {
        int oldGentleHeaderPos = -1;
        int oldPeopleHubPos = -1;
        if (mGentleHeader != null) {
            if (mGentleHeader.getTransientContainer() != null) {
                mGentleHeader.getTransientContainer().removeView(mGentleHeader);
            } else if (mGentleHeader.getParent() != null) {
                oldGentleHeaderPos = mParent.indexOfChild(mGentleHeader);
                mParent.removeView(mGentleHeader);
            }
        }
        if (mPeopleHubView != null) {
            if (mPeopleHubView.getTransientContainer() != null) {
                mPeopleHubView.getTransientContainer().removeView(mPeopleHubView);
            } else if (mPeopleHubView.getParent() != null) {
                oldPeopleHubPos = mParent.indexOfChild(mPeopleHubView);
                mParent.removeView(mPeopleHubView);
            }
        }

        mGentleHeader = (SectionHeaderView) layoutInflater.inflate(
                R.layout.status_bar_notification_section_header, mParent, false);
        mGentleHeader.setOnHeaderClickListener(this::onGentleHeaderClick);
        mGentleHeader.setOnClearAllClickListener(this::onClearGentleNotifsClick);

        if (oldGentleHeaderPos != -1) {
            mParent.addView(mGentleHeader, oldGentleHeaderPos);
        }

        mPeopleHubView = (PeopleHubView) layoutInflater.inflate(
                R.layout.people_strip, mParent, false);

        if (oldPeopleHubPos != -1) {
            mParent.addView(mPeopleHubView, oldPeopleHubPos);
        }
    }

    /** Listener for when the "clear all" buttton is clciked on the gentle notification header. */
    void setOnClearGentleNotifsClickListener(View.OnClickListener listener) {
        mOnClearGentleNotifsClickListener = listener;
    }

    /** Must be called whenever the UI mode changes (i.e. when we enter night mode). */
    void onUiModeChanged() {
        mGentleHeader.onUiModeChanged();
    }

    @Override
    public boolean beginsSection(@NonNull View view, @Nullable View previous) {
        boolean begin = false;
        if (view instanceof ActivatableNotificationView) {
            if (previous instanceof ActivatableNotificationView) {
                // If we're drawing the first non-person notification, break out a section
                ActivatableNotificationView curr = (ActivatableNotificationView) view;
                ActivatableNotificationView prev = (ActivatableNotificationView) previous;

                begin = getBucket(curr) != getBucket(prev);
            }
        }

        if (!begin) {
            begin = view == mGentleHeader || view == mPeopleHubView;
        }

        return begin;
    }

    private boolean isUsingMultipleSections() {
        return mNumberOfSections > 1;
    }

    private @PriorityBucket int getBucket(ActivatableNotificationView view)
            throws IllegalArgumentException {
        if (view instanceof ExpandableNotificationRow) {
            return ((ExpandableNotificationRow) view).getEntry().getBucket();
        } else if (view == mGentleHeader) {
            return BUCKET_SILENT;
        } else if (view == mPeopleHubView) {
            return BUCKET_PEOPLE;
        }

        throw new IllegalArgumentException("I don't know how to find a bucket for this view :(");
    }

    /**
     * Should be called whenever notifs are added, removed, or updated. Updates section boundary
     * bookkeeping and adds/moves/removes section headers if appropriate.
     */
    void updateSectionBoundaries() {
        if (!isUsingMultipleSections()) {
            return;
        }

        boolean peopleNotificationsPresent = false;
        int firstNonHeadsUpIndex = -1;
        int firstGentleIndex = -1;
        int notifCount = 0;

        final int n = mParent.getChildCount();
        for (int i = 0; i < n; i++) {
            View child = mParent.getChildAt(i);
            if (child instanceof ExpandableNotificationRow && child.getVisibility() != View.GONE) {
                notifCount++;
                ExpandableNotificationRow row = (ExpandableNotificationRow) child;
                if (firstNonHeadsUpIndex == -1 && !row.isHeadsUp()) {
                    firstNonHeadsUpIndex = i;
                }
                if (row.getEntry().getBucket() == BUCKET_PEOPLE) {
                    peopleNotificationsPresent = true;
                }
                if (row.getEntry().getBucket() == BUCKET_SILENT) {
                    firstGentleIndex = i;
                    break;
                }
            }
        }

        if (firstNonHeadsUpIndex == -1) {
            firstNonHeadsUpIndex = firstGentleIndex != -1 ? firstGentleIndex : notifCount;
        }

        // make room for peopleHub
        int offset = adjustPeopleHubVisibilityAndPosition(
                firstNonHeadsUpIndex, peopleNotificationsPresent);
        if (firstGentleIndex != -1) {
            firstGentleIndex += offset;
        }

        adjustGentleHeaderVisibilityAndPosition(firstGentleIndex);

        mGentleHeader.setAreThereDismissableGentleNotifs(
                mParent.hasActiveClearableNotifications(ROWS_GENTLE));
    }

    private void adjustGentleHeaderVisibilityAndPosition(int firstGentleNotifIndex) {
        final boolean showGentleHeader =
                firstGentleNotifIndex != -1
                        && mStatusBarStateController.getState() != StatusBarState.KEYGUARD;
        final int currentHeaderIndex = mParent.indexOfChild(mGentleHeader);

        if (!showGentleHeader) {
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

    private int adjustPeopleHubVisibilityAndPosition(
            int targetIndex, boolean peopleNotificationsPresent) {
        final boolean showPeopleHeader = mNumberOfSections > 2
                && mStatusBarStateController.getState() != StatusBarState.KEYGUARD
                && (peopleNotificationsPresent || mPeopleHubVisible);
        final int currentHubIndex = mParent.indexOfChild(mPeopleHubView);
        final boolean currentlyVisible = currentHubIndex >= 0;

        mPeopleHubView.setCanSwipe(showPeopleHeader && !peopleNotificationsPresent);

        if (!showPeopleHeader) {
            if (currentlyVisible) {
                mParent.removeView(mPeopleHubView);
                return -1;
            }
        } else {
            mPeopleHubView.unDismiss();
            mPeopleHubView.resetTranslation();
            if (!currentlyVisible) {
                if (mPeopleHubView.getTransientContainer() != null) {
                    mPeopleHubView.getTransientContainer().removeTransientView(mPeopleHubView);
                    mPeopleHubView.setTransientContainer(null);
                }
                mParent.addView(mPeopleHubView, targetIndex);
                return 1;
            } else if (currentHubIndex != targetIndex) {
                if (currentHubIndex < targetIndex) {
                    targetIndex--;
                }
                mParent.changeViewPosition(mPeopleHubView, targetIndex);
            }
        }
        return 0;
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
                if (getBucket(v) == filter) {
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
    SectionHeaderView getGentleHeaderView() {
        return mGentleHeader;
    }

    private final ConfigurationListener mConfigurationListener = new ConfigurationListener() {
        @Override
        public void onLocaleListChanged() {
            mGentleHeader.reinflateContents();
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

    /**
     * For now, declare the available notification buckets (sections) here so that other
     * presentation code can decide what to do based on an entry's buckets
     *
     */
    @Retention(SOURCE)
    @IntDef(prefix = { "BUCKET_" }, value = {
            BUCKET_PEOPLE,
            BUCKET_ALERTING,
            BUCKET_SILENT
    })
    public @interface PriorityBucket {}
    public static final int BUCKET_PEOPLE = 0;
    public static final int BUCKET_ALERTING = 1;
    public static final int BUCKET_SILENT = 2;
}
