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

package com.android.systemui.statusbar.car;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.support.v4.view.PagerAdapter;
import android.support.v4.view.ViewPager;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.util.UserIcons;
import com.android.systemui.R;
import com.android.systemui.statusbar.UserUtil;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.UserSwitcherController;

/**
 * Displays a ViewPager with icons for the users in the system to allow switching between users.
 * One of the uses of this is for the lock screen in auto.
 */
public class UserGridView extends ViewPager {
    private StatusBar mStatusBar;
    private UserSwitcherController mUserSwitcherController;
    private Adapter mAdapter;
    private UserSelectionListener mUserSelectionListener;

    public UserGridView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void init(StatusBar statusBar, UserSwitcherController userSwitcherController) {
        mStatusBar = statusBar;
        mUserSwitcherController = userSwitcherController;
        mAdapter = new Adapter(mUserSwitcherController);
        addOnLayoutChangeListener(mAdapter);
        setAdapter(mAdapter);
    }

    public void onUserSwitched(int newUserId) {
        // Bring up security view after user switch is completed.
        post(this::showOfflineAuthUi);
    }

    public void setUserSelectionListener(UserSelectionListener userSelectionListener) {
        mUserSelectionListener = userSelectionListener;
    }

    void showOfflineAuthUi() {
        // TODO: Show keyguard UI in-place.
        mStatusBar.executeRunnableDismissingKeyguard(null, null, true, true, true);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Wrap content doesn't work in ViewPagers, so simulate the behavior in code.
        int height = 0;
        for(int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.measure(widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            height = Math.max(child.getMeasuredHeight(), height);
        }
        heightMeasureSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    /**
     * This is a ViewPager.PagerAdapter which deletegates the work to a
     * UserSwitcherController.BaseUserAdapter. Java doesn't support multiple inheritance so we have
     * to use composition instead to achieve the same goal since both the base classes are abstract
     * classes and not interfaces.
     */
    private final class Adapter extends PagerAdapter implements View.OnLayoutChangeListener {
        private final int mPodWidth;
        private final int mPodMargin;

        private final WrappedBaseUserAdapter mUserAdapter;
        private int mContainerWidth;

        public Adapter(UserSwitcherController controller) {
            super();
            mUserAdapter = new WrappedBaseUserAdapter(controller, this);
            mPodWidth = getResources().getDimensionPixelSize(
                    R.dimen.car_fullscreen_user_pod_image_avatar_width);
            mPodMargin = getResources().getDimensionPixelSize(
                    R.dimen.car_fullscreen_user_pod_margin_side);
        }

        @Override
        public void destroyItem(ViewGroup container, int position, Object object) {
            container.removeView((View) object);
        }

        private int getIconsPerPage() {
            // We need to know how many pods we need in this page. Each pod has its own width and
            // margins on both sides. We can then divide the measured width of the parent by the
            // sum of pod width and margin to get the number of pods that will completely fit.
            return mContainerWidth / (mPodWidth + mPodMargin * 2);
        }

        @Override
        public Object instantiateItem(ViewGroup container, int position) {
            Context context = getContext();
            LayoutInflater inflater = LayoutInflater.from(context);

            ViewGroup pods = (ViewGroup) inflater.inflate(
                    R.layout.car_fullscreen_user_pod_container, null);

            int iconsPerPage = getIconsPerPage();
            int limit = Math.min(mUserAdapter.getCount(), (position + 1) * iconsPerPage);
            for (int i = position * iconsPerPage; i < limit; i++) {
                pods.addView(makeUserPod(inflater, context, i, pods));
            }
            container.addView(pods);
            return pods;
        }

        private Drawable getUserIcon(Context context, UserSwitcherController.UserRecord record) {
            if (record.isAddUser) {
                Drawable icon = context.getDrawable(R.drawable.ic_add_circle_qs);
                icon.setTint(Color.WHITE);
                return icon;
            }
            return UserIcons.getDefaultUserIcon(record.resolveId(), /* light= */ true);
        }

        private View makeUserPod(LayoutInflater inflater, Context context,
                int position, ViewGroup parent) {
            final UserSwitcherController.UserRecord record = mUserAdapter.getItem(position);
            View view = inflater.inflate(R.layout.car_fullscreen_user_pod, parent, false);

            TextView nameView = view.findViewById(R.id.user_name);
            if (record != null) {
                nameView.setText(mUserAdapter.getName(context, record));
                view.setActivated(record.isCurrent);
            } else {
                nameView.setText(context.getString(R.string.unknown_user_label));
            }

            ImageView iconView = (ImageView) view.findViewById(R.id.user_avatar);
            if (record == null || record.picture == null) {
                iconView.setImageDrawable(getUserIcon(context, record));
            } else {
                iconView.setImageBitmap(record.picture);
            }

            iconView.setOnClickListener(v -> {
                if (record == null) {
                    return;
                }

                if (mUserSelectionListener != null) {
                    mUserSelectionListener.onUserSelected(record);
                }

                if (record.isCurrent) {
                    showOfflineAuthUi();
                } else {
                    mUserSwitcherController.switchTo(record);
                }
            });

            return view;
        }

        @Override
        public int getCount() {
            int iconsPerPage = getIconsPerPage();
            if (iconsPerPage == 0) {
                return 0;
            }
            return (int) Math.ceil((double) mUserAdapter.getCount() / getIconsPerPage());
        }

        public void refresh() {
            mUserAdapter.refresh();
        }

        @Override
        public boolean isViewFromObject(View view, Object object) {
            return view == object;
        }

        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom,
                int oldLeft, int oldTop, int oldRight, int oldBottom) {
            mContainerWidth = Math.max(left - right, right - left);
            notifyDataSetChanged();
        }
    }

    private final class WrappedBaseUserAdapter extends UserSwitcherController.BaseUserAdapter {
        private Adapter mContainer;

        public WrappedBaseUserAdapter(UserSwitcherController controller, Adapter container) {
            super(controller);
            mContainer = container;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            throw new UnsupportedOperationException("unused");
        }

        @Override
        public void notifyDataSetChanged() {
            super.notifyDataSetChanged();
            mContainer.notifyDataSetChanged();
        }
    }

    interface UserSelectionListener {
        void onUserSelected(UserSwitcherController.UserRecord record);
    };
}
