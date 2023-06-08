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
import android.widget.FrameLayout;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.UiEventLogger;
import com.android.keyguard.KeyguardConstants;
import com.android.keyguard.KeyguardVisibilityHelper;
import com.android.keyguard.dagger.KeyguardUserSwitcherScope;
import com.android.settingslib.drawable.CircleFramedDrawable;
import com.android.systemui.R;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.user.UserSwitchDialogController;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.notification.PropertyAnimator;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.LockscreenGestureLogger;
import com.android.systemui.statusbar.phone.ScreenOffAnimationController;
import com.android.systemui.statusbar.phone.UserAvatarView;
import com.android.systemui.user.data.source.UserRecord;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;

/**
 * Manages the user switch on the Keyguard that is used for opening the QS user panel.
 */
@KeyguardUserSwitcherScope
public class KeyguardQsUserSwitchController extends ViewController<FrameLayout> {

    private static final String TAG = "KeyguardQsUserSwitchController";
    private static final boolean DEBUG = KeyguardConstants.DEBUG;

    private static final AnimationProperties ANIMATION_PROPERTIES =
            new AnimationProperties().setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);

    private final Context mContext;
    private Resources mResources;
    private final UserSwitcherController mUserSwitcherController;
    private BaseUserSwitcherAdapter mAdapter;
    private final KeyguardStateController mKeyguardStateController;
    private final FalsingManager mFalsingManager;
    protected final SysuiStatusBarStateController mStatusBarStateController;
    private final ConfigurationController mConfigurationController;
    private final KeyguardVisibilityHelper mKeyguardVisibilityHelper;
    private final UserSwitchDialogController mUserSwitchDialogController;
    private final UiEventLogger mUiEventLogger;
    @VisibleForTesting
    UserAvatarView mUserAvatarView;
    private View mUserAvatarViewWithBackground;
    UserRecord mCurrentUser;
    private boolean mIsKeyguardShowing;

    // State info for the user switch and keyguard
    private int mBarState;

    private final StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onStateChanged(int newState) {
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

    private ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {

                @Override
                public void onUiModeChanged() {
                    // Force update when dark theme toggled. Otherwise, icon will not update
                    // until it is clicked
                    if (mIsKeyguardShowing) {
                        updateView();
                    }
                }
            };

    private final KeyguardStateController.Callback mKeyguardStateCallback =
            new KeyguardStateController.Callback() {
                @Override
                public void onUnlockedChanged() {
                    updateKeyguardShowing(false /* forceViewUpdate */);
                }

                @Override
                public void onKeyguardShowingChanged() {
                    updateKeyguardShowing(false /* forceViewUpdate */);
                }

                @Override
                public void onKeyguardFadingAwayChanged() {
                    updateKeyguardShowing(false /* forceViewUpdate */);
                }
            };

    @Inject
    public KeyguardQsUserSwitchController(
            FrameLayout view,
            Context context,
            @Main Resources resources,
            UserSwitcherController userSwitcherController,
            KeyguardStateController keyguardStateController,
            FalsingManager falsingManager,
            ConfigurationController configurationController,
            SysuiStatusBarStateController statusBarStateController,
            DozeParameters dozeParameters,
            ScreenOffAnimationController screenOffAnimationController,
            UserSwitchDialogController userSwitchDialogController,
            UiEventLogger uiEventLogger) {
        super(view);
        if (DEBUG) Log.d(TAG, "New KeyguardQsUserSwitchController");
        mContext = context;
        mResources = resources;
        mUserSwitcherController = userSwitcherController;
        mKeyguardStateController = keyguardStateController;
        mFalsingManager = falsingManager;
        mConfigurationController = configurationController;
        mStatusBarStateController = statusBarStateController;
        mKeyguardVisibilityHelper = new KeyguardVisibilityHelper(mView,
                keyguardStateController, dozeParameters,
                screenOffAnimationController,  /* animateYPos= */ false, /* logBuffer= */ null);
        mUserSwitchDialogController = userSwitchDialogController;
        mUiEventLogger = uiEventLogger;
    }

    @Override
    protected void onInit() {
        super.onInit();
        if (DEBUG) Log.d(TAG, "onInit");
        mUserAvatarView = mView.findViewById(R.id.kg_multi_user_avatar);
        mUserAvatarViewWithBackground = mView.findViewById(
                R.id.kg_multi_user_avatar_with_background);
        mAdapter = new BaseUserSwitcherAdapter(mUserSwitcherController) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                return null;
            }
        };

        mUserAvatarView.setOnClickListener(v -> {
            if (mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                return;
            }
            if (isListAnimating()) {
                return;
            }

            // Tapping anywhere in the view will open the user switcher
            mUiEventLogger.log(
                    LockscreenGestureLogger.LockscreenUiEvent.LOCKSCREEN_SWITCH_USER_TAP);

            mUserSwitchDialogController.showDialog(mUserAvatarViewWithBackground.getContext(),
                    Expandable.fromView(mUserAvatarViewWithBackground));
        });

        mUserAvatarView.setAccessibilityDelegate(new View.AccessibilityDelegate() {
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
        mKeyguardStateController.addCallback(mKeyguardStateCallback);
        // Force update when view attached in case configuration changed while the view was detached
        updateCurrentUser();
        updateKeyguardShowing(true /* forceViewUpdate */);
    }

    @Override
    protected void onViewDetached() {
        if (DEBUG) Log.d(TAG, "onViewDetached");

        mAdapter.unregisterDataSetObserver(mDataSetObserver);
        mStatusBarStateController.removeCallback(mStatusBarStateListener);
        mConfigurationController.removeCallback(mConfigurationListener);
        mKeyguardStateController.removeCallback(mKeyguardStateCallback);
    }

    public final DataSetObserver mDataSetObserver = new DataSetObserver() {
        @Override
        public void onChanged() {
            boolean userChanged = updateCurrentUser();
            if (userChanged || (mIsKeyguardShowing && mUserAvatarView.isEmpty())) {
                updateView();
            }
        }
    };

    private void clearAvatar() {
        if (DEBUG) Log.d(TAG, "clearAvatar");
        mUserAvatarView.setAvatar(null);
    }

    /**
     * @param forceViewUpdate whether view should be updated regardless of whether
     *                        keyguard-showing state changed
     */
    @VisibleForTesting
    void updateKeyguardShowing(boolean forceViewUpdate) {
        boolean wasKeyguardShowing = mIsKeyguardShowing;
        mIsKeyguardShowing = mKeyguardStateController.isShowing()
                || mKeyguardStateController.isKeyguardGoingAway();
        if (wasKeyguardShowing == mIsKeyguardShowing && !forceViewUpdate) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "updateKeyguardShowing:"
                    + " mIsKeyguardShowing=" + mIsKeyguardShowing
                    + " forceViewUpdate=" + forceViewUpdate);
        }
        if (mIsKeyguardShowing) {
            updateView();
        } else {
            clearAvatar();
        }
    }

    /**
     * @return true if the current user has changed
     */
    private boolean updateCurrentUser() {
        UserRecord previousUser = mCurrentUser;
        mCurrentUser = null;
        for (int i = 0; i < mAdapter.getCount(); i++) {
            UserRecord r = mAdapter.getItem(i);
            if (r.isCurrent) {
                mCurrentUser = r;
                return !mCurrentUser.equals(previousUser);
            }
        }
        return mCurrentUser == null && previousUser != null;
    }

    private String getContentDescription() {
        if (mCurrentUser != null && mCurrentUser.info != null
                && !TextUtils.isEmpty(mCurrentUser.info.name)) {
            // If we know the current user's name, have TalkBack to announce "Signed in as [user
            // name]" when the icon is selected
            return mContext.getString(
                    R.string.accessibility_quick_settings_user, mCurrentUser.info.name);
        } else {
            // As a fallback, have TalkBack announce "Switch user"
            return mContext.getString(R.string.accessibility_multi_user_switch_switcher);
        }
    }

    private void updateView() {
        if (DEBUG) Log.d(TAG, "updateView");
        mUserAvatarView.setContentDescription(getContentDescription());
        int userId = mCurrentUser != null ? mCurrentUser.resolveId() : UserHandle.USER_NULL;
        mUserAvatarView.setDrawableWithBadge(getCurrentUserIcon().mutate(), userId);
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

        Drawable bg = mContext.getDrawable(R.drawable.user_avatar_bg);
        drawable = new LayerDrawable(new Drawable[]{bg, drawable});
        return drawable;
    }

    /**
     * Get the height of the keyguard user switcher view when closed.
     */
    public int getUserIconHeight() {
        return mUserAvatarView.getHeight();
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
}
