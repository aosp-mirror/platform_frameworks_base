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

package com.android.systemui.statusbar;

import android.app.Notification;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.NotificationHeaderView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.widget.CachingIconView;
import com.android.internal.widget.ConversationLayout;
import com.android.internal.widget.ImageFloatingTextView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationContentView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * A utility to manage notification views when they are placed in a group by adjusting elements
 * to reduce redundancies and occasionally tweak layouts to highlight the unique content.
 */
public class NotificationGroupingUtil {

    private static final TextViewComparator TEXT_VIEW_COMPARATOR = new TextViewComparator();
    private static final TextViewComparator APP_NAME_COMPARATOR = new AppNameComparator();
    private static final ViewComparator BADGE_COMPARATOR = new BadgeComparator();
    private static final VisibilityApplicator VISIBILITY_APPLICATOR = new VisibilityApplicator();
    private static final VisibilityApplicator APP_NAME_APPLICATOR = new AppNameApplicator();
    private static final ResultApplicator LEFT_ICON_APPLICATOR = new LeftIconApplicator();
    private static final DataExtractor ICON_EXTRACTOR = new DataExtractor() {
        @Override
        public Object extractData(ExpandableNotificationRow row) {
            return row.getEntry().getSbn().getNotification();
        }
    };
    private static final IconComparator ICON_VISIBILITY_COMPARATOR = new IconComparator() {
        public boolean compare(View parent, View child, Object parentData,
                Object childData) {
            return hasSameIcon(parentData, childData)
                    && hasSameColor(parentData, childData);
        }
    };
    private static final IconComparator GREY_COMPARATOR = new IconComparator() {
        public boolean compare(View parent, View child, Object parentData,
                Object childData) {
            return !hasSameIcon(parentData, childData)
                    || hasSameColor(parentData, childData);
        }
    };
    private static final ResultApplicator GREY_APPLICATOR = new ResultApplicator() {
        @Override
        public void apply(View parent, View view, boolean apply, boolean reset) {
            CachingIconView icon = view.findViewById(com.android.internal.R.id.icon);
            if (icon != null) {
                icon.setGrayedOut(apply);
            }
        }
    };

    private final ExpandableNotificationRow mRow;
    private final ArrayList<Processor> mProcessors = new ArrayList<>();
    private final HashSet<Integer> mDividers = new HashSet<>();

    public NotificationGroupingUtil(ExpandableNotificationRow row) {
        mRow = row;
        // To hide the icons if they are the same and the color is the same
        mProcessors.add(new Processor(mRow,
                com.android.internal.R.id.icon,
                ICON_EXTRACTOR,
                ICON_VISIBILITY_COMPARATOR,
                VISIBILITY_APPLICATOR));
        // To grey them out the icons and expand button when the icons are not the same
        mProcessors.add(new Processor(mRow,
                com.android.internal.R.id.status_bar_latest_event_content,
                ICON_EXTRACTOR,
                GREY_COMPARATOR,
                GREY_APPLICATOR));
        mProcessors.add(new Processor(mRow,
                com.android.internal.R.id.status_bar_latest_event_content,
                ICON_EXTRACTOR,
                ICON_VISIBILITY_COMPARATOR,
                LEFT_ICON_APPLICATOR));
        mProcessors.add(new Processor(mRow,
                com.android.internal.R.id.profile_badge,
                null /* Extractor */,
                BADGE_COMPARATOR,
                VISIBILITY_APPLICATOR));
        mProcessors.add(new Processor(mRow,
                com.android.internal.R.id.app_name_text,
                null,
                APP_NAME_COMPARATOR,
                APP_NAME_APPLICATOR));
        mProcessors.add(Processor.forTextView(mRow, com.android.internal.R.id.header_text));
        mDividers.add(com.android.internal.R.id.header_text_divider);
        mDividers.add(com.android.internal.R.id.header_text_secondary_divider);
        mDividers.add(com.android.internal.R.id.time_divider);
    }

    /**
     * Update the appearance of the children in this group to reduce redundancies.
     */
    public void updateChildrenAppearance() {
        List<ExpandableNotificationRow> notificationChildren = mRow.getAttachedChildren();
        if (notificationChildren == null || !mRow.isSummaryWithChildren()) {
            return;
        }
        // Initialize the processors
        for (int compI = 0; compI < mProcessors.size(); compI++) {
            mProcessors.get(compI).init();
        }

        // Compare all notification headers
        for (int i = 0; i < notificationChildren.size(); i++) {
            ExpandableNotificationRow row = notificationChildren.get(i);
            for (int compI = 0; compI < mProcessors.size(); compI++) {
                mProcessors.get(compI).compareToGroupParent(row);
            }
        }

        // Apply the comparison to the row
        for (int i = 0; i < notificationChildren.size(); i++) {
            ExpandableNotificationRow row = notificationChildren.get(i);
            for (int compI = 0; compI < mProcessors.size(); compI++) {
                mProcessors.get(compI).apply(row);
            }
            // We need to sanitize the dividers since they might be off-balance now
            sanitizeTopLineViews(row);
        }
    }

    private void sanitizeTopLineViews(ExpandableNotificationRow row) {
        if (row.isSummaryWithChildren()) {
            sanitizeTopLine(row.getNotificationViewWrapper().getNotificationHeader(), row);
            return;
        }
        final NotificationContentView layout = row.getPrivateLayout();
        sanitizeChild(layout.getContractedChild(), row);
        sanitizeChild(layout.getHeadsUpChild(), row);
        sanitizeChild(layout.getExpandedChild(), row);
    }

    private void sanitizeChild(View child, ExpandableNotificationRow row) {
        if (child != null) {
            sanitizeTopLine(child.findViewById(R.id.notification_top_line), row);
        }
    }

    private void sanitizeTopLine(ViewGroup rowHeader, ExpandableNotificationRow row) {
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
                || row.getEntry().getSbn().getNotification().showsTime()
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

    /**
     * Reset the modifications to this row for removing it from the group.
     */
    public void restoreChildNotification(ExpandableNotificationRow row) {
        for (int compI = 0; compI < mProcessors.size(); compI++) {
            mProcessors.get(compI).apply(row, true /* reset */);
        }
        sanitizeTopLineViews(row);
    }

    private static class Processor {
        private final int mId;
        private final DataExtractor mExtractor;
        private final ViewComparator mComparator;
        private final ResultApplicator mApplicator;
        private final ExpandableNotificationRow mParentRow;
        private boolean mApply;
        private View mParentView;
        private Object mParentData;

        public static Processor forTextView(ExpandableNotificationRow row, int id) {
            return new Processor(row, id, null, TEXT_VIEW_COMPARATOR, VISIBILITY_APPLICATOR);
        }

        Processor(ExpandableNotificationRow row, int id, DataExtractor extractor,
                ViewComparator comparator,
                ResultApplicator applicator) {
            mId = id;
            mExtractor = extractor;
            mApplicator = applicator;
            mComparator = comparator;
            mParentRow = row;
        }

        public void init() {
            View header = mParentRow.getNotificationViewWrapper().getNotificationHeader();
            mParentView = header == null ? null : header.findViewById(mId);
            mParentData = mExtractor == null ? null : mExtractor.extractData(mParentRow);
            mApply = !mComparator.isEmpty(mParentView);
        }
        public void compareToGroupParent(ExpandableNotificationRow row) {
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
                applyToView(apply, reset, row.getNotificationViewWrapper().getNotificationHeader());
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
         * @param parent the view with the given id in the group header
         * @param child the view with the given id in the child notification
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

    private static class BadgeComparator implements ViewComparator {
        @Override
        public boolean compare(View parent, View child, Object parentData, Object childData) {
            return parent.getVisibility() != View.GONE;
        }

        @Override
        public boolean isEmpty(View view) {
            if (view instanceof ImageView) {
                return ((ImageView) view).getDrawable() == null;
            }
            return false;
        }
    }

    private static class TextViewComparator implements ViewComparator {
        @Override
        public boolean compare(View parent, View child, Object parentData, Object childData) {
            TextView parentView = (TextView) parent;
            CharSequence parentText = parentView == null ? "" : parentView.getText();
            TextView childView = (TextView) child;
            CharSequence childText = childView == null ? "" : childView.getText();
            return Objects.equals(parentText, childText);
        }

        @Override
        public boolean isEmpty(View view) {
            return view == null || TextUtils.isEmpty(((TextView) view).getText());
        }
    }

    private abstract static class IconComparator implements ViewComparator {
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
        /**
         * @param parent the root view of the child notification
         * @param view the view with the given id in the child notification
         * @param apply whether the state should be applied or removed
         * @param reset if [de]application is the result of a reset
         */
        void apply(View parent, View view, boolean apply, boolean reset);
    }

    private static class VisibilityApplicator implements ResultApplicator {

        @Override
        public void apply(View parent, View view, boolean apply, boolean reset) {
            if (view != null) {
                view.setVisibility(apply ? View.GONE : View.VISIBLE);
            }
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

    private static class AppNameComparator extends TextViewComparator {
        @Override
        public boolean compare(View parent, View child, Object parentData, Object childData) {
            if (isEmpty(child)) {
                // In headerless notifications the AppName view exists but is usually GONE (and not
                // populated).  We need to treat this case as equal to the header in order to
                // deduplicate the view.
                return true;
            }
            return super.compare(parent, child, parentData, childData);
        }
    }

    private static class LeftIconApplicator implements ResultApplicator {

        public static final int[] MARGIN_ADJUSTED_VIEWS = {
                R.id.text,
                R.id.big_text,
                R.id.title,
                R.id.notification_main_column,
                R.id.notification_header};

        @Override
        public void apply(View parent, View child, boolean showLeftIcon, boolean reset) {
            ImageView leftIcon = child.findViewById(com.android.internal.R.id.left_icon);
            if (leftIcon == null) {
                return;
            }
            ImageView rightIcon = child.findViewById(com.android.internal.R.id.right_icon);
            boolean keepRightIcon = rightIcon != null && Integer.valueOf(1).equals(
                    rightIcon.getTag(R.id.tag_keep_when_showing_left_icon));
            boolean leftIconUsesRightIconDrawable = Integer.valueOf(1).equals(
                    leftIcon.getTag(R.id.tag_uses_right_icon_drawable));
            if (leftIconUsesRightIconDrawable) {
                // Use the right drawable when showing the left, unless the right is being kept
                Drawable rightDrawable = rightIcon == null ? null : rightIcon.getDrawable();
                leftIcon.setImageDrawable(showLeftIcon && !keepRightIcon ? rightDrawable : null);
            }
            leftIcon.setVisibility(showLeftIcon ? View.VISIBLE : View.GONE);

            // update the right icon as well
            if (rightIcon != null) {
                boolean showRightIcon = (keepRightIcon || !showLeftIcon)
                        && rightIcon.getDrawable() != null;
                rightIcon.setVisibility(showRightIcon ? View.VISIBLE : View.GONE);
                for (int viewId : MARGIN_ADJUSTED_VIEWS) {
                    adjustMargins(showRightIcon, child.findViewById(viewId));
                }
            }
        }

        void adjustMargins(boolean iconVisible, View target) {
            if (target == null) {
                return;
            }
            if (target instanceof ImageFloatingTextView) {
                ((ImageFloatingTextView) target).setHasImage(iconVisible);
                return;
            }
            final Integer data = (Integer) target.getTag(iconVisible
                    ? com.android.internal.R.id.tag_margin_end_when_icon_visible
                    : com.android.internal.R.id.tag_margin_end_when_icon_gone);
            if (data == null) {
                return;
            }
            final DisplayMetrics metrics = target.getResources().getDisplayMetrics();
            final int value = TypedValue.complexToDimensionPixelOffset(data, metrics);
            if (target instanceof NotificationHeaderView) {
                ((NotificationHeaderView) target).setTopLineExtraMarginEnd(value);
            } else {
                ViewGroup.LayoutParams layoutParams = target.getLayoutParams();
                if (layoutParams instanceof ViewGroup.MarginLayoutParams) {
                    ((ViewGroup.MarginLayoutParams) layoutParams).setMarginEnd(value);
                    target.setLayoutParams(layoutParams);
                }
            }
        }
    }
}
