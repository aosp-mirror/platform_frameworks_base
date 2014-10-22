/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.database.DataSetObserver;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.animation.AnimationUtils;
import android.widget.TextView;

import com.android.keyguard.AppearAnimationUtils;
import com.android.systemui.R;
import com.android.systemui.qs.tiles.UserDetailItemView;
import com.android.systemui.statusbar.phone.KeyguardStatusBarView;
import com.android.systemui.statusbar.phone.NotificationPanelView;
import com.android.systemui.statusbar.phone.PhoneStatusBar;
import com.android.systemui.statusbar.phone.StatusBarHeaderView;
import com.android.systemui.statusbar.phone.UserAvatarView;

/**
 * Manages the user switcher on the Keyguard.
 */
public class KeyguardUserSwitcher {

    private static final String TAG = "KeyguardUserSwitcher";
    private static final boolean ALWAYS_ON = false;

    private final ViewGroup mUserSwitcher;
    private final KeyguardStatusBarView mStatusBarView;
    private final Adapter mAdapter;
    private final AppearAnimationUtils mAppearAnimationUtils;
    private final KeyguardUserSwitcherScrim mBackground;
    private ObjectAnimator mBgAnimator;
    private UserSwitcherController mUserSwitcherController;

    public KeyguardUserSwitcher(Context context, ViewStub userSwitcher,
            KeyguardStatusBarView statusBarView, NotificationPanelView panelView,
            UserSwitcherController userSwitcherController) {
        if (context.getResources().getBoolean(R.bool.config_keyguardUserSwitcher) || ALWAYS_ON) {
            mUserSwitcher = (ViewGroup) userSwitcher.inflate();
            mBackground = new KeyguardUserSwitcherScrim(mUserSwitcher);
            mUserSwitcher.setBackground(mBackground);
            mStatusBarView = statusBarView;
            mStatusBarView.setKeyguardUserSwitcher(this);
            panelView.setKeyguardUserSwitcher(this);
            mAdapter = new Adapter(context, userSwitcherController);
            mAdapter.registerDataSetObserver(mDataSetObserver);
            mUserSwitcherController = userSwitcherController;
            mAppearAnimationUtils = new AppearAnimationUtils(context, 400, -0.5f, 0.5f,
                    AnimationUtils.loadInterpolator(
                            context, android.R.interpolator.fast_out_slow_in));
        } else {
            mUserSwitcher = null;
            mStatusBarView = null;
            mAdapter = null;
            mAppearAnimationUtils = null;
            mBackground = null;
        }
    }

    public void setKeyguard(boolean keyguard, boolean animate) {
        if (mUserSwitcher != null) {
            if (keyguard && shouldExpandByDefault()) {
                show(animate);
            } else {
                hide(animate);
            }
        }
    }

    /**
     * @return true if the user switcher should be expanded by default on the lock screen.
     * @see android.os.UserManager#isUserSwitcherEnabled()
     */
    private boolean shouldExpandByDefault() {
        return (mUserSwitcherController != null) && mUserSwitcherController.isSimpleUserSwitcher();
    }

    public void show(boolean animate) {
        if (mUserSwitcher != null && mUserSwitcher.getVisibility() != View.VISIBLE) {
            cancelAnimations();
            mUserSwitcher.setVisibility(View.VISIBLE);
            mStatusBarView.setKeyguardUserSwitcherShowing(true, animate);
            if (animate) {
                startAppearAnimation();
            }
        }
    }

    public void hide(boolean animate) {
        if (mUserSwitcher != null && mUserSwitcher.getVisibility() == View.VISIBLE) {
            cancelAnimations();
            if (animate) {
                startDisappearAnimation();
            } else {
                mUserSwitcher.setVisibility(View.GONE);
            }
            mStatusBarView.setKeyguardUserSwitcherShowing(false, animate);
        }
    }

    private void cancelAnimations() {
        int count = mUserSwitcher.getChildCount();
        for (int i = 0; i < count; i++) {
            mUserSwitcher.getChildAt(i).animate().cancel();
        }
        if (mBgAnimator != null) {
            mBgAnimator.cancel();
        }
        mUserSwitcher.animate().cancel();
    }

    private void startAppearAnimation() {
        int count = mUserSwitcher.getChildCount();
        View[] objects = new View[count];
        for (int i = 0; i < count; i++) {
            objects[i] = mUserSwitcher.getChildAt(i);
        }
        mUserSwitcher.setClipChildren(false);
        mUserSwitcher.setClipToPadding(false);
        mAppearAnimationUtils.startAppearAnimation(objects, new Runnable() {
            @Override
            public void run() {
                mUserSwitcher.setClipChildren(true);
                mUserSwitcher.setClipToPadding(true);
            }
        });
        mBgAnimator = ObjectAnimator.ofInt(mBackground, "alpha", 0, 255);
        mBgAnimator.setDuration(400);
        mBgAnimator.setInterpolator(PhoneStatusBar.ALPHA_IN);
        mBgAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mBgAnimator = null;
            }
        });
        mBgAnimator.start();
    }

    private void startDisappearAnimation() {
        mUserSwitcher.animate()
                .alpha(0f)
                .setDuration(300)
                .setInterpolator(PhoneStatusBar.ALPHA_OUT)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        mUserSwitcher.setVisibility(View.GONE);
                        mUserSwitcher.setAlpha(1f);
                    }
                });
    }

    private void refresh() {
        final int childCount = mUserSwitcher.getChildCount();
        final int adapterCount = mAdapter.getCount();
        final int N = Math.max(childCount, adapterCount);
        for (int i = 0; i < N; i++) {
            if (i < adapterCount) {
                View oldView = null;
                if (i < childCount) {
                    oldView = mUserSwitcher.getChildAt(i);
                }
                View newView = mAdapter.getView(i, oldView, mUserSwitcher);
                if (oldView == null) {
                    // We ran out of existing views. Add it at the end.
                    mUserSwitcher.addView(newView);
                } else if (oldView != newView) {
                    // We couldn't rebind the view. Replace it.
                    mUserSwitcher.removeViewAt(i);
                    mUserSwitcher.addView(newView, i);
                }
            } else {
                int lastIndex = mUserSwitcher.getChildCount() - 1;
                mUserSwitcher.removeViewAt(lastIndex);
            }
        }
    }

    public final DataSetObserver mDataSetObserver = new DataSetObserver() {
        @Override
        public void onChanged() {
            refresh();
        }
    };

    public static class Adapter extends UserSwitcherController.BaseUserAdapter implements
            View.OnClickListener {

        private Context mContext;

        public Adapter(Context context, UserSwitcherController controller) {
            super(controller);
            mContext = context;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            UserSwitcherController.UserRecord item = getItem(position);

            if (!(convertView instanceof UserDetailItemView)
                    || !(convertView.getTag() instanceof UserSwitcherController.UserRecord)) {
                convertView = LayoutInflater.from(mContext).inflate(
                        R.layout.keyguard_user_switcher_item, parent, false);
                convertView.setOnClickListener(this);
            }
            UserDetailItemView v = (UserDetailItemView) convertView;

            String name = getName(mContext, item);
            if (item.picture == null) {
                v.bind(name, getDrawable(mContext, item));
            } else {
                v.bind(name, item.picture);
            }
            convertView.setActivated(item.isCurrent);
            convertView.setTag(item);
            return convertView;
        }

        @Override
        public void onClick(View v) {
            switchTo(((UserSwitcherController.UserRecord)v.getTag()));
        }
    }
}
