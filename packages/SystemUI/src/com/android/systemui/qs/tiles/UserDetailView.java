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

package com.android.systemui.qs.tiles;

import static com.android.systemui.statusbar.policy.UserSwitcherController.USER_SWITCH_DISABLED_ALPHA;
import static com.android.systemui.statusbar.policy.UserSwitcherController.USER_SWITCH_ENABLED_ALPHA;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settingslib.RestrictedLockUtils;
import com.android.settingslib.drawable.CircleFramedDrawable;
import com.android.systemui.R;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.qs.PseudoGridView;
import com.android.systemui.qs.QSUserSwitcherEvent;
import com.android.systemui.statusbar.policy.UserSwitcherController;

import javax.inject.Inject;

/**
 * Quick settings detail view for user switching.
 */
public class UserDetailView extends PseudoGridView {

    protected Adapter mAdapter;

    public UserDetailView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public static UserDetailView inflate(Context context, ViewGroup parent, boolean attach) {
        return (UserDetailView) LayoutInflater.from(context).inflate(
                R.layout.qs_user_detail, parent, attach);
    }

    /** Set a {@link android.widget.BaseAdapter} */
    public void setAdapter(Adapter adapter) {
        mAdapter = adapter;
        ViewGroupAdapterBridge.link(this, mAdapter);
    }

    public void refreshAdapter() {
        mAdapter.refresh();
    }

    public static class Adapter extends UserSwitcherController.BaseUserAdapter
            implements OnClickListener {

        private final Context mContext;
        protected UserSwitcherController mController;
        private View mCurrentUserView;
        private final UiEventLogger mUiEventLogger;
        private final FalsingManager mFalsingManager;

        @Inject
        public Adapter(Context context, UserSwitcherController controller,
                UiEventLogger uiEventLogger, FalsingManager falsingManager) {
            super(controller);
            mContext = context;
            mController = controller;
            mUiEventLogger = uiEventLogger;
            mFalsingManager = falsingManager;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            UserSwitcherController.UserRecord item = getItem(position);
            return createUserDetailItemView(convertView, parent, item);
        }

        public UserDetailItemView createUserDetailItemView(View convertView, ViewGroup parent,
                UserSwitcherController.UserRecord item) {
            UserDetailItemView v = UserDetailItemView.convertOrInflate(
                    parent.getContext(), convertView, parent);
            if (!item.isCurrent || item.isGuest) {
                v.setOnClickListener(this);
            } else {
                v.setOnClickListener(null);
                v.setClickable(false);
            }
            String name = getName(mContext, item);
            if (item.picture == null) {
                v.bind(name, getDrawable(mContext, item).mutate(), item.resolveId());
            } else {
                int avatarSize =
                        (int) mContext.getResources().getDimension(R.dimen.qs_framed_avatar_size);
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

        private static Drawable getDrawable(Context context,
                UserSwitcherController.UserRecord item) {
            Drawable icon = getIconDrawable(context, item);
            int iconColorRes;
            if (item.isCurrent) {
                iconColorRes = R.color.qs_user_switcher_selected_avatar_icon_color;
            } else if (!item.isSwitchToEnabled) {
                iconColorRes = R.color.GM2_grey_600;
            } else {
                iconColorRes = R.color.qs_user_switcher_avatar_icon_color;
            }
            icon.setTint(context.getResources().getColor(iconColorRes, context.getTheme()));

            int bgRes = item.isCurrent ? R.drawable.bg_avatar_selected : R.drawable.qs_bg_avatar;
            Drawable bg = context.getDrawable(bgRes);
            LayerDrawable drawable = new LayerDrawable(new Drawable[]{bg, icon});
            return drawable;
        }

        @Override
        public void onClick(View view) {
            if (mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                return;
            }

            UserSwitcherController.UserRecord tag =
                    (UserSwitcherController.UserRecord) view.getTag();
            if (tag.isDisabledByAdmin) {
                final Intent intent = RestrictedLockUtils.getShowAdminSupportDetailsIntent(
                        mContext, tag.enforcedAdmin);
                mController.startActivity(intent);
            } else if (tag.isSwitchToEnabled) {
                MetricsLogger.action(mContext, MetricsEvent.QS_SWITCH_USER);
                mUiEventLogger.log(QSUserSwitcherEvent.QS_USER_SWITCH);
                if (!tag.isAddUser && !tag.isRestricted && !tag.isDisabledByAdmin) {
                    if (mCurrentUserView != null) {
                        mCurrentUserView.setActivated(false);
                    }
                    view.setActivated(true);
                }
                onUserListItemClicked(tag);
            }
        }
    }
}
