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

import static com.android.systemui.statusbar.policy.UserSwitcherController.USER_SWITCH_DISABLED_ALPHA;
import static com.android.systemui.statusbar.policy.UserSwitcherController.USER_SWITCH_ENABLED_ALPHA;

import android.content.Context;
import android.content.res.Resources;
import android.database.DataSetObserver;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.UserHandle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.android.keyguard.KeyguardConstants;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.keyguard.KeyguardVisibilityHelper;
import com.android.keyguard.dagger.KeyguardUserSwitcherScope;
import com.android.settingslib.drawable.CircleFramedDrawable;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.notification.PropertyAnimator;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.util.ViewController;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

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
    private WeakReference<KeyguardUserSwitcherListener> mKeyguardUserSwitcherCallback;
    protected final SysuiStatusBarStateController mStatusBarStateController;
    private final KeyguardVisibilityHelper mKeyguardVisibilityHelper;

    // Child views of KeyguardUserSwitcherView
    private KeyguardUserSwitcherListView mListView;
    private LinearLayout mEndGuestButton;

    // State info for the user switcher
    private boolean mUserSwitcherOpen;
    private int mCurrentUserId = UserHandle.USER_NULL;
    private boolean mCurrentUserIsGuest;
    private int mBarState;
    private float mDarkAmount;

    private final KeyguardUpdateMonitorCallback mInfoCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onKeyguardVisibilityChanged(boolean showing) {
                    if (DEBUG) Log.d(TAG, String.format("onKeyguardVisibilityChanged %b", showing));
                    // Any time the keyguard is hidden, try to close the user switcher menu to
                    // restore keyguard to the default state
                    if (!showing) {
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
            DozeParameters dozeParameters) {
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
                keyguardStateController, dozeParameters);
    }

    @Override
    protected void onInit() {
        super.onInit();

        if (DEBUG) Log.d(TAG, "onInit");

        mListView = mView.findViewById(R.id.keyguard_user_switcher_list);
        mEndGuestButton = mView.findViewById(R.id.end_guest_button);

        mEndGuestButton.setOnClickListener(v -> {
            mUserSwitcherController.showExitGuestDialog(mCurrentUserId);
        });

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
        mDataSetObserver.onChanged();
        mKeyguardUpdateMonitor.registerCallback(mInfoCallback);
        mStatusBarStateController.addCallback(mStatusBarStateListener);
        mScreenLifecycle.addObserver(mScreenObserver);
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
    }

    /**
     * See:
     *
     * <ul>
     *   <li>{@link com.android.internal.R.bool.config_expandLockScreenUserSwitcher}</li>
     *    <li>{@link UserSwitcherController.SIMPLE_USER_SWITCHER_GLOBAL_SETTING}</li>
     * </ul>
     *
     * @return true if the user switcher should be open by default on the lock screen.
     * @see android.os.UserManager#isUserSwitcherEnabled()
     */
    public boolean isSimpleUserSwitcher() {
        return mUserSwitcherController.isSimpleUserSwitcher();
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
                UserSwitcherController.UserRecord userTag =
                        (UserSwitcherController.UserRecord) newView.getTag();
                if (userTag.isCurrent) {
                    if (i != 0) {
                        Log.w(TAG, "Current user is not the first view in the list");
                    }
                    foundCurrentUser = true;
                    mCurrentUserId = userTag.info.id;
                    mCurrentUserIsGuest = userTag.isGuest;
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
            mCurrentUserIsGuest = false;
        }
    }

    /**
     * Get the height of the keyguard user switcher view when closed.
     */
    public int getUserIconHeight() {
        View firstChild = mListView.getChildAt(0);
        return firstChild == null ? 0 : firstChild.getHeight();
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
        boolean isAwake = darkAmount != 0;
        if (darkAmount == mDarkAmount) {
            return;
        }
        mDarkAmount = darkAmount;
        mListView.setDarkAmount(darkAmount);
        mView.setVisibility(isAwake ? View.VISIBLE : View.GONE);
        if (!isAwake) {
            closeSwitcherIfOpenAndNotSimple(false);
        }
    }

    private boolean isListAnimating() {
        return mKeyguardVisibilityHelper.isVisibilityAnimating() || mListView.isAnimating();
    }

    /**
     * Remove the callback if it exists.
     */
    public void removeCallback() {
        if (DEBUG) Log.d(TAG, "removeCallback");
        mKeyguardUserSwitcherCallback = null;
    }

    /**
     * Register to receive notifications about keyguard user switcher state
     * (see {@link KeyguardUserSwitcherListener}.
     *
     * Only one callback can be used at a time.
     *
     * @param callback The callback to register
     */
    public void setCallback(KeyguardUserSwitcherListener callback) {
        if (DEBUG) Log.d(TAG, "setCallback");
        mKeyguardUserSwitcherCallback = new WeakReference<>(callback);
    }

    /**
     * If user switcher state changes, notifies all {@link KeyguardUserSwitcherListener}.
     * Switcher state is updatd before animations finish.
     *
     * @param animate true to animate transition. The user switcher state (i.e.
     *                {@link #isUserSwitcherOpen()}) is updated before animation is finished.
     */
    private void setUserSwitcherOpened(boolean open, boolean animate) {
        boolean wasOpen = mUserSwitcherOpen;
        if (DEBUG) {
            Log.d(TAG, String.format("setUserSwitcherOpened: %b -> %b (animate=%b)", wasOpen,
                    open, animate));
        }
        mUserSwitcherOpen = open;
        if (mUserSwitcherOpen != wasOpen) {
            notifyUserSwitcherStateChanged();
        }
        updateVisibilities(animate);
    }

    private void updateVisibilities(boolean animate) {
        if (DEBUG) Log.d(TAG, String.format("updateVisibilities: animate=%b", animate));
        mEndGuestButton.animate().cancel();
        if (mUserSwitcherOpen && mCurrentUserIsGuest) {
            // Show the "End guest session" button
            mEndGuestButton.setVisibility(View.VISIBLE);
            if (animate) {
                mEndGuestButton.setAlpha(0f);
                mEndGuestButton.animate()
                        .alpha(1f)
                        .setDuration(360)
                        .setInterpolator(Interpolators.ALPHA_IN)
                        .withEndAction(() -> {
                            mEndGuestButton.setClickable(true);
                        });
            } else {
                mEndGuestButton.setClickable(true);
                mEndGuestButton.setAlpha(1f);
            }
        } else {
            // Hide the "End guest session" button. If it's already GONE, don't try to
            // animate it or it will appear again for an instant.
            mEndGuestButton.setClickable(false);
            if (animate && mEndGuestButton.getVisibility() != View.GONE) {
                mEndGuestButton.setVisibility(View.VISIBLE);
                mEndGuestButton.setAlpha(1f);
                mEndGuestButton.animate()
                        .alpha(0f)
                        .setDuration(360)
                        .setInterpolator(Interpolators.ALPHA_OUT)
                        .withEndAction(() -> {
                            mEndGuestButton.setVisibility(View.GONE);
                            mEndGuestButton.setAlpha(1f);
                        });
            } else {
                mEndGuestButton.setVisibility(View.GONE);
                mEndGuestButton.setAlpha(1f);
            }
        }

        mListView.updateVisibilities(mUserSwitcherOpen, animate);
    }

    private boolean isUserSwitcherOpen() {
        return mUserSwitcherOpen;
    }

    private void notifyUserSwitcherStateChanged() {
        if (DEBUG) {
            Log.d(TAG, String.format("notifyUserSwitcherStateChanged: mUserSwitcherOpen=%b",
                    mUserSwitcherOpen));
        }
        if (mKeyguardUserSwitcherCallback != null) {
            KeyguardUserSwitcherListener cb = mKeyguardUserSwitcherCallback.get();
            if (cb != null) {
                cb.onKeyguardUserSwitcherChanged(mUserSwitcherOpen);
            }
        }
    }

    /**
     * Callback for keyguard user switcher state information
     */
    public interface KeyguardUserSwitcherListener {

        /**
         * Called when the keyguard enters or leaves user switcher mode. This will be called
         * before the animations are finished.
         *
         * @param open if true, keyguard is showing the user switcher or transitioning from/to user
         *             switcher mode.
         */
        void onKeyguardUserSwitcherChanged(boolean open);
    }

    static class KeyguardUserAdapter extends
            UserSwitcherController.BaseUserAdapter implements View.OnClickListener {

        private final Context mContext;
        private final Resources mResources;
        private final LayoutInflater mLayoutInflater;
        private KeyguardUserSwitcherController mKeyguardUserSwitcherController;
        private View mCurrentUserView;
        // List of users where the first entry is always the current user
        private ArrayList<UserSwitcherController.UserRecord> mUsersOrdered = new ArrayList<>();

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
            ArrayList<UserSwitcherController.UserRecord> users = super.getUsers();
            mUsersOrdered = new ArrayList<>(users.size());
            for (int i = 0; i < users.size(); i++) {
                UserSwitcherController.UserRecord record = users.get(i);
                if (record.isCurrent) {
                    mUsersOrdered.add(0, record);
                } else {
                    mUsersOrdered.add(record);
                }
            }
        }

        @Override
        protected ArrayList<UserSwitcherController.UserRecord> getUsers() {
            return mUsersOrdered;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            UserSwitcherController.UserRecord item = getItem(position);
            return createUserDetailItemView(convertView, parent, item);
        }

        @Override
        public String getName(Context context, UserSwitcherController.UserRecord item) {
            if (item.isGuest) {
                return context.getString(com.android.settingslib.R.string.guest_nickname);
            } else {
                return super.getName(context, item);
            }
        }

        KeyguardUserDetailItemView convertOrInflate(View convertView, ViewGroup parent) {
            if (!(convertView instanceof KeyguardUserDetailItemView)
                    || !(convertView.getTag() instanceof UserSwitcherController.UserRecord)) {
                convertView = mLayoutInflater.inflate(
                        R.layout.keyguard_user_switcher_item, parent, false);
            }
            return (KeyguardUserDetailItemView) convertView;
        }

        KeyguardUserDetailItemView createUserDetailItemView(View convertView, ViewGroup parent,
                UserSwitcherController.UserRecord item) {
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
            v.setDisabledByAdmin(item.isDisabledByAdmin);
            v.setEnabled(item.isSwitchToEnabled);
            v.setAlpha(v.isEnabled() ? USER_SWITCH_ENABLED_ALPHA : USER_SWITCH_DISABLED_ALPHA);

            if (item.isCurrent) {
                mCurrentUserView = v;
            }
            v.setTag(item);
            return v;
        }

        private Drawable getDrawable(UserSwitcherController.UserRecord item) {
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

            Drawable bg = mContext.getDrawable(R.drawable.kg_bg_avatar);
            drawable = new LayerDrawable(new Drawable[]{bg, drawable});
            return drawable;
        }

        @Override
        public void onClick(View v) {
            UserSwitcherController.UserRecord user = (UserSwitcherController.UserRecord) v.getTag();

            if (mKeyguardUserSwitcherController.isListAnimating()) {
                return;
            }

            if (mKeyguardUserSwitcherController.isUserSwitcherOpen()) {
                if (user.isCurrent) {
                    // Close the switcher if tapping the current user
                    mKeyguardUserSwitcherController.setUserSwitcherOpened(
                            false /* open */, true /* animate */);
                } else if (user.isSwitchToEnabled) {
                    if (!user.isAddUser && !user.isRestricted && !user.isDisabledByAdmin) {
                        if (mCurrentUserView != null) {
                            mCurrentUserView.setActivated(false);
                        }
                        v.setActivated(true);
                    }
                    onUserListItemClicked(user);
                }
            } else {
                // If switcher is closed, tapping anywhere in the view will open it
                mKeyguardUserSwitcherController.setUserSwitcherOpened(
                        true /* open */, true /* animate */);
            }
        }
    }
}
