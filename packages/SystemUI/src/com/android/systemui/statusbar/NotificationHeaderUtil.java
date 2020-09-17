/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import android.app.Notification;
import android.content.res.Configuration;
import android.graphics.PorterDuff;
import android.graphics.drawable.Icon;
import android.text.TextUtils;
import android.view.NotificationHeaderView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.util.ContrastColorUtil;
import com.android.internal.widget.ConversationLayout;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationContentView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * A Util to manage {@link android.view.NotificationHeaderView} objects and their redundancies.
 */
public class NotificationHeaderUtil {

    private static final TextViewComparator sTextViewComparator = new TextViewComparator();
    private static final VisibilityApplicator sVisibilityApplicator = new VisibilityApplicator();
    private static final VisibilityApplicator sAppNameApplicator = new AppNameApplicator();
    private static  final DataExtractor sIconExtractor = new DataExtractor() {
        @Override
        public Object extractData(ExpandableNotificationRow row) {
            return row.getEntry().getSbn().getNotification();
        }
    };
    private static final IconComparator sIconVisibilityComparator = new IconComparator() {
        public boolean compare(View parent, View child, Object parentData,
                Object childData) {
            return hasSameIcon(parentData, childData)
                    && hasSameColor(parentData, childData);
        }
    };
    private static final IconComparator sGreyComparator = new IconComparator() {
        public boolean compare(View parent, View child, Object parentData,
                Object childData) {
            return !hasSameIcon(parentData, childData)
                    || hasSameColor(parentData, childData);
        }
    };
    private final static ResultApplicator mGreyApplicator = new ResultApplicator() {
        @Override
        public void apply(View parent, View view, boolean apply, boolean reset) {
            NotificationHeaderView header = (NotificationHeaderView) view;
            ImageView icon = (ImageView) view.findViewById(
                    com.android.internal.R.id.icon);
            ImageView expand = (ImageView) view.findViewById(
                    com.android.internal.R.id.expand_button);
            applyToChild(icon, apply, header.getOriginalIconColor());
            applyToChild(expand, apply, header.getOriginalNotificationColor());
        }

        private void applyToChild(View view, boolean shouldApply, int originalColor) {
            if (originalColor != NotificationHeaderView.NO_COLOR) {
                ImageView imageView = (ImageView) view;
                imageView.getDrawable().mutate();
                if (shouldApply) {
                    // lets gray it out
                    Configuration config = view.getContext().getResources().getConfiguration();
                    boolean inNightMode = (config.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                            == Configuration.UI_MODE_NIGHT_YES;
                    int grey = ContrastColorUtil.resolveColor(view.getContext(),
                            Notification.COLOR_DEFAULT, inNightMode);
                    imageView.getDrawable().setColorFilter(grey, PorterDuff.Mode.SRC_ATOP);
                } else {
                    // lets reset it
                    imageView.getDrawable().setColorFilter(originalColor,
                            PorterDuff.Mode.SRC_ATOP);
                }
            }
        }
    };

    private final ExpandableNotificationRow mRow;
    private final ArrayList<HeaderProcessor> mComparators = new ArrayList<>();
    private final HashSet<Integer> mDividers = new HashSet<>();

    public NotificationHeaderUtil(ExpandableNotificationRow row) {
        mRow = row;
        // To hide the icons if they are the same and the color is the same
        mComparators.add(new HeaderProcessor(mRow,
                com.android.internal.R.id.icon,
                sIconExtractor,
                sIconVisibilityComparator,
                sVisibilityApplicator));
        // To grey them out the icons and expand button when the icons are not the same
        mComparators.add(new HeaderProcessor(mRow,
                com.android.internal.R.id.notification_header,
                sIconExtractor,
                sGreyComparator,
                mGreyApplicator));
        mComparators.add(new HeaderProcessor(mRow,
                com.android.internal.R.id.profile_badge,
                null /* Extractor */,
                new ViewComparator() {
                    @Override
                    public boolean compare(View parent, View child, Object parentData,
                            Object childData) {
                        return parent.getVisibility() != View.GONE;
                    }

                    @Override
                    public boolean isEmpty(View view) {
                        if (view instanceof ImageView) {
                            return ((ImageView) view).getDrawable() == null;
                        }
                        return false;
                    }
                },
                sVisibilityApplicator));
        mComparators.add(new HeaderProcessor(
                mRow,
                com.android.internal.R.id.app_name_text,
                null,
                sTextViewComparator,
                sAppNameApplicator));
        mComparators.add(HeaderProcessor.forTextView(mRow,
                com.android.internal.R.id.header_text));
        mDividers.add(com.android.internal.R.id.header_text_divider);
        mDividers.add(com.android.internal.R.id.header_text_secondary_divider);
        mDividers.add(com.android.internal.R.id.time_divider);
    }

    public void updateChildrenHeaderAppearance() {
        List<ExpandableNotificationRow> notificationChildren = mRow.getAttachedChildren();
        if (notificationChildren == null) {
            return;
        }
        // Initialize the comparators
        for (int compI = 0; compI < mComparators.size(); compI++) {
            mComparators.get(compI).init();
        }

        // Compare all notification headers
        for (int i = 0; i < notificationChildren.size(); i++) {
            ExpandableNotificationRow row = notificationChildren.get(i);
            for (int compI = 0; compI < mComparators.size(); compI++) {
                mComparators.get(compI).compareToHeader(row);
            }
        }

        // Apply the comparison to the row
        for (int i = 0; i < notificationChildren.size(); i++) {
            ExpandableNotificationRow row = notificationChildren.get(i);
            for (int compI = 0; compI < mComparators.size(); compI++) {
                mComparators.get(compI).apply(row);
            }
            // We need to sanitize the dividers since they might be off-balance now
            sanitizeHeaderViews(row);
        }
    }

    private void sanitizeHeaderViews(ExpandableNotificationRow row) {
        if (row.isSummaryWithChildren()) {
            sanitizeHeader(row.getNotificationHeader());
            return;
        }
        final NotificationContentView layout = row.getPrivateLayout();
        sanitizeChild(layout.getContractedChild());
        sanitizeChild(layout.getHeadsUpChild());
        sanitizeChild(layout.getExpandedChild());
    }

    private void sanitizeChild(View child) {
        if (child != null) {
            ViewGroup header = child.findViewById(
                    com.android.internal.R.id.notification_header);
            sanitizeHeader(header);
        }
    }

    private void sanitizeHeader(ViewGroup rowHeader) {
        if (rowHeader == null) {
            return;
        }
        final int childCount = rowHeader.getChildCount();
        View time = rowHeader.findViewById(com.android.internal.R.id.time);
        boolean hasVisibleText = false;
        for (int i = 0; i < childCount; i++) {
            View child = rowHeader.getChildAt(i);
            if (child instanceof TextView
                    && child.getVisibility() != View.GONE
                    && !mDividers.contains(child.getId())
                    && child != time) {
                hasVisibleText = true;
                break;
            }
        }
        // in case no view is visible we make sure the time is visible
        int timeVisibility = !hasVisibleText
                || mRow.getEntry().getSbn().getNotification().showsTime()
                ? View.VISIBLE : View.GONE;
        time.setVisibility(timeVisibility);
        View left = null;
        View right;
        for (int i = 0; i < childCount; i++) {
            View child = rowHeader.getChildAt(i);
            if (mDividers.contains(child.getId())) {
                boolean visible = false;
                // Lets find the item to the right
                for (i++; i < childCount; i++) {
                    right = rowHeader.getChildAt(i);
                    if (mDividers.contains(right.getId())) {
                        // A divider was found, this needs to be hidden
                        i--;
                        break;
                    } else if (right.getVisibility() != View.GONE && right instanceof TextView) {
                        visible = left != null;
                        left = right;
                        break;
                    }
                }
                child.setVisibility(visible ? View.VISIBLE : View.GONE);
            } else if (child.getVisibility() != View.GONE && child instanceof TextView) {
                left = child;
            }
        }
    }

    public void restoreNotificationHeader(ExpandableNotificationRow row) {
        for (int compI = 0; compI < mComparators.size(); compI++) {
            mComparators.get(compI).apply(row, true /* reset */);
        }
        sanitizeHeaderViews(row);
    }

    private static class HeaderProcessor {
        private final int mId;
        private final DataExtractor mExtractor;
        private final ResultApplicator mApplicator;
        private final ExpandableNotificationRow mParentRow;
        private boolean mApply;
        private View mParentView;
        private ViewComparator mComparator;
        private Object mParentData;

        public static HeaderProcessor forTextView(ExpandableNotificationRow row, int id) {
            return new HeaderProcessor(row, id, null, sTextViewComparator, sVisibilityApplicator);
        }

        HeaderProcessor(ExpandableNotificationRow row, int id, DataExtractor extractor,
                ViewComparator comparator,
                ResultApplicator applicator) {
            mId = id;
            mExtractor = extractor;
            mApplicator = applicator;
            mComparator = comparator;
            mParentRow = row;
        }

        public void init() {
            mParentView = mParentRow.getNotificationHeader().findViewById(mId);
            mParentData = mExtractor == null ? null : mExtractor.extractData(mParentRow);
            mApply = !mComparator.isEmpty(mParentView);
        }
        public void compareToHeader(ExpandableNotificationRow row) {
            if (!mApply) {
                return;
            }
            View contractedChild = row.getPrivateLayout().getContractedChild();
            if (contractedChild == null) {
                return;
            }
            View ownView = contractedChild.findViewById(mId);
            if (ownView == null) {
                // No view found. We still consider this to be the same to avoid weird flickering
                // when for example showing an undo notification
                return;
            }
            Object childData = mExtractor == null ? null : mExtractor.extractData(row);
            mApply = mComparator.compare(mParentView, ownView,
                    mParentData, childData);
        }

        public void apply(ExpandableNotificationRow row) {
            apply(row, false /* reset */);
        }

        public void apply(ExpandableNotificationRow row, boolean reset) {
            boolean apply = mApply && !reset;
            if (row.isSummaryWithChildren()) {
                applyToView(apply, reset, row.getNotificationHeader());
                return;
            }
            applyToView(apply, reset, row.getPrivateLayout().getContractedChild());
            applyToView(apply, reset, row.getPrivateLayout().getHeadsUpChild());
            applyToView(apply, reset, row.getPrivateLayout().getExpandedChild());
        }

        private void applyToView(boolean apply, boolean reset, View parent) {
            if (parent != null) {
                View view = parent.findViewById(mId);
                if (view != null && !mComparator.isEmpty(view)) {
                    mApplicator.apply(parent, view, apply, reset);
                }
            }
        }
    }

    private interface ViewComparator {
        /**
         * @param parent the parent view
         * @param child the child view
         * @param parentData optional data for the parent
         * @param childData optional data for the child
         * @return whether to views are the same
         */
        boolean compare(View parent, View child, Object parentData, Object childData);
        boolean isEmpty(View view);
    }

    private interface DataExtractor {
        Object extractData(ExpandableNotificationRow row);
    }

    private static class TextViewComparator implements ViewComparator {
        @Override
        public boolean compare(View parent, View child, Object parentData, Object childData) {
            TextView parentView = (TextView) parent;
            TextView childView = (TextView) child;
            return parentView.getText().equals(childView.getText());
        }

        @Override
        public boolean isEmpty(View view) {
            return TextUtils.isEmpty(((TextView) view).getText());
        }
    }

    private static abstract class IconComparator implements ViewComparator {
        @Override
        public boolean compare(View parent, View child, Object parentData, Object childData) {
            return false;
        }

        protected boolean hasSameIcon(Object parentData, Object childData) {
            Icon parentIcon = ((Notification) parentData).getSmallIcon();
            Icon childIcon = ((Notification) childData).getSmallIcon();
            return parentIcon.sameAs(childIcon);
        }

        /**
         * @return whether two ImageViews have the same colorFilterSet or none at all
         */
        protected boolean hasSameColor(Object parentData, Object childData) {
            int parentColor = ((Notification) parentData).color;
            int childColor = ((Notification) childData).color;
            return parentColor == childColor;
        }

        @Override
        public boolean isEmpty(View view) {
            return false;
        }
    }

    private interface ResultApplicator {
        void apply(View parent, View view, boolean apply, boolean reset);
    }

    private static class VisibilityApplicator implements ResultApplicator {

        @Override
        public void apply(View parent, View view, boolean apply, boolean reset) {
            view.setVisibility(apply ? View.GONE : View.VISIBLE);
        }
    }

    private static class AppNameApplicator extends VisibilityApplicator {

        @Override
        public void apply(View parent, View view, boolean apply, boolean reset) {
            if (reset && parent instanceof ConversationLayout) {
                ConversationLayout layout = (ConversationLayout) parent;
                apply = layout.shouldHideAppName();
            }
            super.apply(parent, view, apply, reset);
        }
    }
}
