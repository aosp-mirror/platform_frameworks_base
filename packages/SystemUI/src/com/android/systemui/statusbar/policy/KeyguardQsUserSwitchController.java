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

import android.content.Context;
import android.content.res.Resources;
import android.database.DataSetObserver;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.keyguard.KeyguardConstants;
import com.android.keyguard.KeyguardVisibilityHelper;
import com.android.keyguard.dagger.KeyguardUserSwitcherScope;
import com.android.settingslib.drawable.CircleFramedDrawable;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.tiles.UserDetailView;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.notification.PropertyAnimator;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.NotificationPanelViewController;
import com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationController;
import com.android.systemui.statusbar.phone.UserAvatarView;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;
import javax.inject.Provider;

/**
 * Manages the user switch on the Keyguard that is used for opening the QS user panel.
 */
@KeyguardUserSwitcherScope
public class KeyguardQsUserSwitchController extends ViewController<UserAvatarView> {

    private static final String TAG = "KeyguardQsUserSwitchController";
    private static final boolean DEBUG = KeyguardConstants.DEBUG;

    private static final AnimationProperties ANIMATION_PROPERTIES =
            new AnimationProperties().setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);

    private final Context mContext;
    private Resources mResources;
    private final UserSwitcherController mUserSwitcherController;
    private final ScreenLifecycle mScreenLifecycle;
    private UserSwitcherController.BaseUserAdapter mAdapter;
    private final KeyguardStateController mKeyguardStateController;
    private final FalsingManager mFalsingManager;
    protected final SysuiStatusBarStateController mStatusBarStateController;
    private final ConfigurationController mConfigurationController;
    private final KeyguardVisibilityHelper mKeyguardVisibilityHelper;
    private final KeyguardUserDetailAdapter mUserDetailAdapter;
    private NotificationPanelViewController mNotificationPanelViewController;
    UserSwitcherController.UserRecord mCurrentUser;

    // State info for the user switch and keyguard
    private int mBarState;

    private final StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onStateChanged(int newState) {
                    if (DEBUG) Log.d(TAG, String.format("onStateChanged: newState=%d", newState));

                    boolean goingToFullShade = mStatusBarStateController.goingToFullShade();
                    boolean keyguardFadingAway = mKeyguardStateController.isKeyguardFadingAway();
                    int oldState = mBarState;
                    mBarState = newState;

                    setKeyguardQsUserSwitchVisibility(
                            newState,
                            keyguardFadingAway,
                            goingToFullShade,
                            oldState);
                }
            };

    private ConfigurationController.ConfigurationListener
            mConfigurationListener = new ConfigurationController.ConfigurationListener() {

                @Override
                public void onUiModeChanged() {
                    updateView(true);
                }
            };

    @Inject
    public KeyguardQsUserSwitchController(
            UserAvatarView view,
            Context context,
            @Main Resources resources,
            ScreenLifecycle screenLifecycle,
            UserSwitcherController userSwitcherController,
            KeyguardStateController keyguardStateController,
            FalsingManager falsingManager,
            ConfigurationController configurationController,
            SysuiStatusBarStateController statusBarStateController,
            DozeParameters dozeParameters,
            Provider<UserDetailView.Adapter> userDetailViewAdapterProvider,
            UnlockedScreenOffAnimationController unlockedScreenOffAnimationController) {
        super(view);
        if (DEBUG) Log.d(TAG, "New KeyguardQsUserSwitchController");
        mContext = context;
        mResources = resources;
        mScreenLifecycle = screenLifecycle;
        mUserSwitcherController = userSwitcherController;
        mKeyguardStateController = keyguardStateController;
        mFalsingManager = falsingManager;
        mConfigurationController = configurationController;
        mStatusBarStateController = statusBarStateController;
        mKeyguardVisibilityHelper = new KeyguardVisibilityHelper(mView,
                keyguardStateController, dozeParameters,
                unlockedScreenOffAnimationController,  /* animateYPos= */ false);
        mUserDetailAdapter = new KeyguardUserDetailAdapter(context, userDetailViewAdapterProvider);
    }

    @Override
    protected void onInit() {
        super.onInit();
        if (DEBUG) Log.d(TAG, "onInit");
        mAdapter = new UserSwitcherController.BaseUserAdapter(mUserSwitcherController) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                return null;
            }
        };

        mView.setOnClickListener(v -> {
            if (mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                return;
            }

            if (isListAnimating()) {
                return;
            }

            // Tapping anywhere in the view will open QS user panel
            openQsUserPanel();
        });

        mView.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_CLICK,
                        mContext.getString(
                                R.string.accessibility_quick_settings_choose_user_action)));
            }
        });
    }

    @Override
    protected void onViewAttached() {
        if (DEBUG) Log.d(TAG, "onViewAttached");
        mAdapter.registerDataSetObserver(mDataSetObserver);
        mDataSetObserver.onChanged();
        mStatusBarStateController.addCallback(mStatusBarStateListener);
        mConfigurationController.addCallback(mConfigurationListener);
        updateView(true /* forceUpdate */);
    }

    @Override
    protected void onViewDetached() {
        if (DEBUG) Log.d(TAG, "onViewDetached");

        mAdapter.unregisterDataSetObserver(mDataSetObserver);
        mStatusBarStateController.removeCallback(mStatusBarStateListener);
        mConfigurationController.removeCallback(mConfigurationListener);
    }

    public final DataSetObserver mDataSetObserver = new DataSetObserver() {
        @Override
        public void onChanged() {
            updateView(false /* forceUpdate */);
        }
    };

    /**
     * @return true if the current user has changed
     */
    private boolean updateCurrentUser() {
        UserSwitcherController.UserRecord previousUser = mCurrentUser;
        mCurrentUser = null;
        for (int i = 0; i < mAdapter.getCount(); i++) {
            UserSwitcherController.UserRecord r = mAdapter.getItem(i);
            if (r.isCurrent) {
                mCurrentUser = r;
                return !mCurrentUser.equals(previousUser);
            }
        }
        return mCurrentUser == null && previousUser != null;
    }

    /**
     * @param forceUpdate whether to update view even if current user did not change
     */
    private void updateView(boolean forceUpdate) {
        if (!updateCurrentUser() && !forceUpdate) {
            return;
        }

        String contentDescription = null;
        if (mCurrentUser != null && mCurrentUser.info != null && !TextUtils.isEmpty(
                mCurrentUser.info.name)) {
            // If we know the current user's name, have TalkBack to announce "Signed in as [user
            // name]" when the icon is selected
            contentDescription = mContext.getString(R.string.accessibility_quick_settings_user,
                    mCurrentUser.info.name);
        } else {
            // As a fallback, have TalkBack announce "Switch user"
            contentDescription = mContext.getString(
                    R.string.accessibility_multi_user_switch_switcher);
        }

        if (!TextUtils.equals(mView.getContentDescription(), contentDescription)) {
            mView.setContentDescription(contentDescription);
        }

        int userId = mCurrentUser != null ? mCurrentUser.resolveId() : UserHandle.USER_NULL;
        mView.setDrawableWithBadge(getCurrentUserIcon().mutate(), userId);
    }

    Drawable getCurrentUserIcon() {
        Drawable drawable;
        if (mCurrentUser == null || mCurrentUser.picture == null) {
            if (mCurrentUser != null && mCurrentUser.isGuest) {
                drawable = mContext.getDrawable(R.drawable.ic_avatar_guest_user);
            } else {
                drawable = mContext.getDrawable(R.drawable.ic_avatar_user);
            }
            int iconColorRes = R.color.kg_user_switcher_avatar_icon_color;
            drawable.setTint(mResources.getColor(iconColorRes, mContext.getTheme()));
        } else {
            int avatarSize = (int) mResources.getDimension(R.dimen.kg_framed_avatar_size);
            drawable = new CircleFramedDrawable(mCurrentUser.picture, avatarSize);
        }

        Drawable bg = mContext.getDrawable(R.drawable.kg_bg_avatar);
        drawable = new LayerDrawable(new Drawable[]{bg, drawable});
        return drawable;
    }

    /**
     * Get the height of the keyguard user switcher view when closed.
     */
    public int getUserIconHeight() {
        return mView.getHeight();
    }

    /**
     * Set the visibility of the user avatar view based on some new state.
     */
    public void setKeyguardQsUserSwitchVisibility(
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
        PropertyAnimator.setProperty(mView, AnimatableProperty.Y, y, ANIMATION_PROPERTIES, animate);
        PropertyAnimator.setProperty(mView, AnimatableProperty.TRANSLATION_X, -Math.abs(x),
                ANIMATION_PROPERTIES, animate);
    }

    /**
     * Set keyguard user avatar view alpha.
     */
    public void setAlpha(float alpha) {
        if (!mKeyguardVisibilityHelper.isVisibilityAnimating()) {
            mView.setAlpha(alpha);
        }
    }

    private boolean isListAnimating() {
        return mKeyguardVisibilityHelper.isVisibilityAnimating();
    }

    private void openQsUserPanel() {
        mNotificationPanelViewController.expandWithQsDetail(mUserDetailAdapter);
    }

    public void setNotificationPanelViewController(
            NotificationPanelViewController notificationPanelViewController) {
        mNotificationPanelViewController = notificationPanelViewController;
    }

    class KeyguardUserDetailAdapter extends UserSwitcherController.UserDetailAdapter {
        KeyguardUserDetailAdapter(Context context,
                Provider<UserDetailView.Adapter> userDetailViewAdapterProvider) {
            super(context, userDetailViewAdapterProvider);
        }

        @Override
        public boolean shouldAnimate() {
            return false;
        }

        @Override
        public int getDoneText() {
            return R.string.quick_settings_close_user_panel;
        }

        @Override
        public boolean onDoneButtonClicked() {
            if (mNotificationPanelViewController != null) {
                mNotificationPanelViewController.animateCloseQs(true /* animateAway */);
                return true;
            } else {
                return false;
            }
        }
    }
}
