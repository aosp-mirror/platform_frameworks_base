/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.database.DataSetObserver;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.UserHandle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.keyguard.KeyguardConstants;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.keyguard.KeyguardVisibilityHelper;
import com.android.keyguard.dagger.KeyguardUserSwitcherScope;
import com.android.settingslib.drawable.CircleFramedDrawable;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.notification.PropertyAnimator;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.ScreenOffAnimationController;
import com.android.systemui.user.data.source.UserRecord;
import com.android.systemui.util.ViewController;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * Manages the user switcher on the Keyguard.
 */
@KeyguardUserSwitcherScope
public class KeyguardUserSwitcherController extends ViewController<KeyguardUserSwitcherView> {

    private static final String TAG = "KeyguardUserSwitcherController";
    private static final boolean DEBUG = KeyguardConstants.DEBUG;

    private static final AnimationProperties ANIMATION_PROPERTIES =
            new AnimationProperties().setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);

    private final Context mContext;
    private final UserSwitcherController mUserSwitcherController;
    private final ScreenLifecycle mScreenLifecycle;
    private final KeyguardUserAdapter mAdapter;
    private final KeyguardStateController mKeyguardStateController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    protected final SysuiStatusBarStateController mStatusBarStateController;
    private final KeyguardVisibilityHelper mKeyguardVisibilityHelper;
    private ObjectAnimator mBgAnimator;
    private final KeyguardUserSwitcherScrim mBackground;

    // Child views of KeyguardUserSwitcherView
    private KeyguardUserSwitcherListView mListView;

    // State info for the user switcher
    private boolean mUserSwitcherOpen;
    private int mCurrentUserId = UserHandle.USER_NULL;
    private int mBarState;
    private float mDarkAmount;

    private final KeyguardUpdateMonitorCallback mInfoCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onKeyguardVisibilityChanged(boolean visible) {
                    if (DEBUG) Log.d(TAG, String.format("onKeyguardVisibilityChanged %b", visible));
                    // Any time the keyguard is hidden, try to close the user switcher menu to
                    // restore keyguard to the default state
                    if (!visible) {
                        closeSwitcherIfOpenAndNotSimple(false);
                    }
                }

                @Override
                public void onUserSwitching(int userId) {
                    closeSwitcherIfOpenAndNotSimple(false);
                }
            };

    private final ScreenLifecycle.Observer mScreenObserver = new ScreenLifecycle.Observer() {
        @Override
        public void onScreenTurnedOff() {
            if (DEBUG) Log.d(TAG, "onScreenTurnedOff");
            closeSwitcherIfOpenAndNotSimple(false);
        }
    };

    private final StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onStateChanged(int newState) {
                    if (DEBUG) Log.d(TAG, String.format("onStateChanged: newState=%d", newState));

                    boolean goingToFullShade = mStatusBarStateController.goingToFullShade();
                    boolean keyguardFadingAway = mKeyguardStateController.isKeyguardFadingAway();
                    int oldState = mBarState;
                    mBarState = newState;

                    if (mStatusBarStateController.goingToFullShade()
                            || mKeyguardStateController.isKeyguardFadingAway()) {
                        closeSwitcherIfOpenAndNotSimple(true);
                    }

                    setKeyguardUserSwitcherVisibility(
                            newState,
                            keyguardFadingAway,
                            goingToFullShade,
                            oldState);
                }

                @Override
                public void onDozeAmountChanged(float linearAmount, float amount) {
                    if (DEBUG) {
                        Log.d(TAG, String.format("onDozeAmountChanged: linearAmount=%f amount=%f",
                                linearAmount, amount));
                    }
                    setDarkAmount(amount);
                }
            };

    @Inject
    public KeyguardUserSwitcherController(
            KeyguardUserSwitcherView keyguardUserSwitcherView,
            Context context,
            @Main Resources resources,
            LayoutInflater layoutInflater,
            ScreenLifecycle screenLifecycle,
            UserSwitcherController userSwitcherController,
            KeyguardStateController keyguardStateController,
            SysuiStatusBarStateController statusBarStateController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            DozeParameters dozeParameters,
            ScreenOffAnimationController screenOffAnimationController) {
        super(keyguardUserSwitcherView);
        if (DEBUG) Log.d(TAG, "New KeyguardUserSwitcherController");
        mContext = context;
        mScreenLifecycle = screenLifecycle;
        mUserSwitcherController = userSwitcherController;
        mKeyguardStateController = keyguardStateController;
        mStatusBarStateController = statusBarStateController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mAdapter = new KeyguardUserAdapter(mContext, resources, layoutInflater,
                mUserSwitcherController, this);
        mKeyguardVisibilityHelper = new KeyguardVisibilityHelper(mView,
                keyguardStateController, dozeParameters,
                screenOffAnimationController, /* animateYPos= */ false, /* logBuffer= */ null);
        mBackground = new KeyguardUserSwitcherScrim(context);
    }

    @Override
    protected void onInit() {
        super.onInit();

        if (DEBUG) Log.d(TAG, "onInit");

        mListView = mView.findViewById(R.id.keyguard_user_switcher_list);

        mView.setOnTouchListener((v, event) -> {
            if (!isListAnimating()) {
                // Hide switcher if it didn't handle the touch event (and block the event from
                // going through).
                return closeSwitcherIfOpenAndNotSimple(true);
            }
            return false;
        });
    }

    @Override
    protected void onViewAttached() {
        if (DEBUG) Log.d(TAG, "onViewAttached");
        mAdapter.registerDataSetObserver(mDataSetObserver);
        mAdapter.notifyDataSetChanged();
        mKeyguardUpdateMonitor.registerCallback(mInfoCallback);
        mStatusBarStateController.addCallback(mStatusBarStateListener);
        mScreenLifecycle.addObserver(mScreenObserver);
        if (isSimpleUserSwitcher()) {
            // Don't use the background for the simple user switcher
            setUserSwitcherOpened(true /* open */, true /* animate */);
        } else {
            mView.addOnLayoutChangeListener(mBackground);
            mView.setBackground(mBackground);
            mBackground.setAlpha(0);
        }
    }

    @Override
    protected void onViewDetached() {
        if (DEBUG) Log.d(TAG, "onViewDetached");

        // Detaching the view will always close the switcher
        closeSwitcherIfOpenAndNotSimple(false);

        mAdapter.unregisterDataSetObserver(mDataSetObserver);
        mKeyguardUpdateMonitor.removeCallback(mInfoCallback);
        mStatusBarStateController.removeCallback(mStatusBarStateListener);
        mScreenLifecycle.removeObserver(mScreenObserver);
        mView.removeOnLayoutChangeListener(mBackground);
        mView.setBackground(null);
        mBackground.setAlpha(0);
    }

    /**
     * Returns {@code true} if the user switcher should be open by default on the lock screen.
     *
     * @see android.os.UserManager#isUserSwitcherEnabled()
     */
    public boolean isSimpleUserSwitcher() {
        return mUserSwitcherController.isSimpleUserSwitcher();
    }

    public int getHeight() {
        return mListView.getHeight();
    }

    /**
     * @param animate if the transition should be animated
     * @return true if the switcher state changed
     */
    public boolean closeSwitcherIfOpenAndNotSimple(boolean animate) {
        if (isUserSwitcherOpen() && !isSimpleUserSwitcher()) {
            setUserSwitcherOpened(false /* open */, animate);
            return true;
        }
        return false;
    }

    public final DataSetObserver mDataSetObserver = new DataSetObserver() {
        @Override
        public void onChanged() {
            refreshUserList();
        }
    };

    void refreshUserList() {
        final int childCount = mListView.getChildCount();
        final int adapterCount = mAdapter.getCount();
        final int count = Math.max(childCount, adapterCount);

        if (DEBUG) {
            Log.d(TAG, String.format("refreshUserList childCount=%d adapterCount=%d", childCount,
                    adapterCount));
        }

        boolean foundCurrentUser = false;
        for (int i = 0; i < count; i++) {
            if (i < adapterCount) {
                View oldView = null;
                if (i < childCount) {
                    oldView = mListView.getChildAt(i);
                }
                KeyguardUserDetailItemView newView = (KeyguardUserDetailItemView)
                        mAdapter.getView(i, oldView, mListView);
                UserRecord userTag =
                        (UserRecord) newView.getTag();
                if (userTag.isCurrent) {
                    if (i != 0) {
                        Log.w(TAG, "Current user is not the first view in the list");
                    }
                    foundCurrentUser = true;
                    mCurrentUserId = userTag.info.id;
                    // Current user is always visible
                    newView.updateVisibilities(true /* showItem */,
                            mUserSwitcherOpen /* showTextName */, false /* animate */);
                } else {
                    // Views for non-current users are always expanded (e.g. they should the name
                    // next to the user icon). However, they could be hidden entirely if the list
                    // is closed.
                    newView.updateVisibilities(mUserSwitcherOpen /* showItem */,
                            true /* showTextName */, false /* animate */);
                }
                newView.setDarkAmount(mDarkAmount);
                if (oldView == null) {
                    // We ran out of existing views. Add it at the end.
                    mListView.addView(newView);
                } else if (oldView != newView) {
                    // We couldn't rebind the view. Replace it.
                    mListView.replaceView(newView, i);
                }
            } else {
                mListView.removeLastView();
            }
        }
        if (!foundCurrentUser) {
            Log.w(TAG, "Current user is not listed");
            mCurrentUserId = UserHandle.USER_NULL;
        }
    }

    /**
     * Set the visibility of the keyguard user switcher view based on some new state.
     */
    public void setKeyguardUserSwitcherVisibility(
            int statusBarState,
            boolean keyguardFadingAway,
            boolean goingToFullShade,
            int oldStatusBarState) {
        mKeyguardVisibilityHelper.setViewVisibility(
                statusBarState, keyguardFadingAway, goingToFullShade, oldStatusBarState);
    }

    /**
     * Update position of the view with an optional animation
     */
    public void updatePosition(int x, int y, boolean animate) {
        PropertyAnimator.setProperty(mListView, AnimatableProperty.Y, y, ANIMATION_PROPERTIES,
                animate);
        PropertyAnimator.setProperty(mListView, AnimatableProperty.TRANSLATION_X, -Math.abs(x),
                ANIMATION_PROPERTIES, animate);

        Rect r = new Rect();
        mListView.getDrawingRect(r);
        mView.offsetDescendantRectToMyCoords(mListView, r);
        mBackground.setGradientCenter(
                (int) (mListView.getTranslationX() + r.left + r.width() / 2),
                (int) (mListView.getTranslationY() + r.top + r.height() / 2));
    }

    /**
     * Set keyguard user switcher view alpha.
     */
    public void setAlpha(float alpha) {
        if (!mKeyguardVisibilityHelper.isVisibilityAnimating()) {
            mView.setAlpha(alpha);
        }
    }

    /**
     * Set the amount (ratio) that the device has transitioned to doze.
     *
     * @param darkAmount Amount of transition to doze: 1f for doze and 0f for awake.
     */
    private void setDarkAmount(float darkAmount) {
        boolean isFullyDozed = darkAmount == 1;
        if (darkAmount == mDarkAmount) {
            return;
        }
        mDarkAmount = darkAmount;
        mListView.setDarkAmount(darkAmount);
        if (isFullyDozed) {
            closeSwitcherIfOpenAndNotSimple(false);
        }
    }

    private boolean isListAnimating() {
        return mKeyguardVisibilityHelper.isVisibilityAnimating() || mListView.isAnimating();
    }

    /**
     * NOTE: switcher state is updated before animations finish.
     *
     * @param animate true to animate transition. The user switcher state (i.e.
     *                {@link #isUserSwitcherOpen()}) is updated before animation is finished.
     */
    private void setUserSwitcherOpened(boolean open, boolean animate) {
        if (DEBUG) {
            Log.d(TAG,
                    String.format("setUserSwitcherOpened: %b -> %b (animate=%b)",
                            mUserSwitcherOpen, open, animate));
        }
        mUserSwitcherOpen = open;
        updateVisibilities(animate);
    }

    private void updateVisibilities(boolean animate) {
        if (DEBUG) Log.d(TAG, String.format("updateVisibilities: animate=%b", animate));
        if (mBgAnimator != null) {
            mBgAnimator.cancel();
        }

        if (mUserSwitcherOpen) {
            mBgAnimator = ObjectAnimator.ofInt(mBackground, "alpha", 0, 255);
            mBgAnimator.setDuration(400);
            mBgAnimator.setInterpolator(Interpolators.ALPHA_IN);
            mBgAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mBgAnimator = null;
                }
            });
            mBgAnimator.start();
        } else {
            mBgAnimator = ObjectAnimator.ofInt(mBackground, "alpha", 255, 0);
            mBgAnimator.setDuration(400);
            mBgAnimator.setInterpolator(Interpolators.ALPHA_OUT);
            mBgAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mBgAnimator = null;
                }
            });
            mBgAnimator.start();
        }
        mListView.updateVisibilities(mUserSwitcherOpen, animate);
    }

    private boolean isUserSwitcherOpen() {
        return mUserSwitcherOpen;
    }

    static class KeyguardUserAdapter extends
            BaseUserSwitcherAdapter implements View.OnClickListener {

        private final Context mContext;
        private final Resources mResources;
        private final LayoutInflater mLayoutInflater;
        private KeyguardUserSwitcherController mKeyguardUserSwitcherController;
        private View mCurrentUserView;
        // List of users where the first entry is always the current user
        private ArrayList<UserRecord> mUsersOrdered = new ArrayList<>();

        KeyguardUserAdapter(Context context, Resources resources, LayoutInflater layoutInflater,
                UserSwitcherController controller,
                KeyguardUserSwitcherController keyguardUserSwitcherController) {
            super(controller);
            mContext = context;
            mResources = resources;
            mLayoutInflater = layoutInflater;
            mKeyguardUserSwitcherController = keyguardUserSwitcherController;
        }

        @Override
        public void notifyDataSetChanged() {
            // At this point, value of isSimpleUserSwitcher() may have changed in addition to the
            // data set
            refreshUserOrder();
            super.notifyDataSetChanged();
        }

        void refreshUserOrder() {
            List<UserRecord> users = super.getUsers();
            mUsersOrdered = new ArrayList<>(users.size());
            for (int i = 0; i < users.size(); i++) {
                UserRecord record = users.get(i);
                if (record.isCurrent) {
                    mUsersOrdered.add(0, record);
                } else {
                    mUsersOrdered.add(record);
                }
            }
        }

        @Override
        protected ArrayList<UserRecord> getUsers() {
            return mUsersOrdered;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            UserRecord item = getItem(position);
            return createUserDetailItemView(convertView, parent, item);
        }

        KeyguardUserDetailItemView convertOrInflate(View convertView, ViewGroup parent) {
            if (!(convertView instanceof KeyguardUserDetailItemView)
                    || !(convertView.getTag() instanceof UserRecord)) {
                convertView = mLayoutInflater.inflate(
                        R.layout.keyguard_user_switcher_item, parent, false);
            }
            return (KeyguardUserDetailItemView) convertView;
        }

        KeyguardUserDetailItemView createUserDetailItemView(View convertView, ViewGroup parent,
                UserRecord item) {
            KeyguardUserDetailItemView v = convertOrInflate(convertView, parent);
            v.setOnClickListener(this);

            String name = getName(mContext, item);
            if (item.picture == null) {
                v.bind(name, getDrawable(item).mutate(), item.resolveId());
            } else {
                int avatarSize =
                        (int) mResources.getDimension(R.dimen.kg_framed_avatar_size);
                Drawable drawable = new CircleFramedDrawable(item.picture, avatarSize);
                drawable.setColorFilter(
                        item.isSwitchToEnabled ? null : getDisabledUserAvatarColorFilter());
                v.bind(name, drawable, item.info.id);
            }
            v.setActivated(item.isCurrent);
            v.setDisabledByAdmin(item.isDisabledByAdmin());
            v.setEnabled(item.isSwitchToEnabled);
            UserSwitcherController.setSelectableAlpha(v);

            if (item.isCurrent) {
                mCurrentUserView = v;
            }
            v.setTag(item);
            return v;
        }

        private Drawable getDrawable(UserRecord item) {
            Drawable drawable;
            if (item.isCurrent && item.isGuest) {
                drawable = mContext.getDrawable(R.drawable.ic_avatar_guest_user);
            } else {
                drawable = getIconDrawable(mContext, item);
            }

            int iconColorRes;
            if (item.isSwitchToEnabled) {
                iconColorRes = R.color.kg_user_switcher_avatar_icon_color;
            } else {
                iconColorRes = R.color.kg_user_switcher_restricted_avatar_icon_color;
            }
            drawable.setTint(mResources.getColor(iconColorRes, mContext.getTheme()));

            Drawable bg = mContext.getDrawable(R.drawable.user_avatar_bg);
            drawable = new LayerDrawable(new Drawable[]{bg, drawable});
            return drawable;
        }

        @Override
        public void onClick(View v) {
            UserRecord user = (UserRecord) v.getTag();

            if (mKeyguardUserSwitcherController.isListAnimating()) {
                return;
            }

            if (mKeyguardUserSwitcherController.isUserSwitcherOpen()) {
                if (!user.isCurrent || user.isGuest) {
                    onUserListItemClicked(user);
                } else {
                    mKeyguardUserSwitcherController.closeSwitcherIfOpenAndNotSimple(
                            true /* animate */);
                }
            } else {
                // If switcher is closed, tapping anywhere in the view will open it
                mKeyguardUserSwitcherController.setUserSwitcherOpened(
                        true /* open */, true /* animate */);
            }
        }
    }
}
