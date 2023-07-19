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

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Trace;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settingslib.RestrictedLockUtils;
import com.android.settingslib.drawable.CircleFramedDrawable;
import com.android.systemui.R;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.qs.PseudoGridView;
import com.android.systemui.qs.QSUserSwitcherEvent;
import com.android.systemui.qs.user.UserSwitchDialogController;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.BaseUserSwitcherAdapter;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.user.data.source.UserRecord;

import java.util.List;
import java.util.stream.Collectors;

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

    /** Provides views for user detail items. */
    public static class Adapter extends BaseUserSwitcherAdapter
            implements OnClickListener {

        private final Context mContext;
        protected UserSwitcherController mController;
        @Nullable
        private View mCurrentUserView;
        private final UiEventLogger mUiEventLogger;
        private final FalsingManager mFalsingManager;
        private @Nullable UserSwitchDialogController.DialogShower mDialogShower;

        @NonNull
        @Override
        protected List<UserRecord> getUsers() {
            return super.getUsers().stream().filter(
                    userRecord -> !userRecord.isManageUsers).collect(Collectors.toList());
        }

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
            UserRecord item = getItem(position);
            return createUserDetailItemView(convertView, parent, item);
        }

        /**
         * If this adapter is inside a dialog, passing a
         * {@link UserSwitchDialogController.DialogShower} will help animate to and from the parent
         * dialog. This will also allow for dismissing the whole stack of dialogs in a single
         * animation.
         *
         * @param shower
         * @see SystemUIDialog#dismissStack()
         */
        public void injectDialogShower(UserSwitchDialogController.DialogShower shower) {
            mDialogShower = shower;
        }

        public UserDetailItemView createUserDetailItemView(View convertView, ViewGroup parent,
                UserRecord item) {
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
            v.setDisabledByAdmin(item.isDisabledByAdmin());
            v.setEnabled(item.isSwitchToEnabled);
            UserSwitcherController.setSelectableAlpha(v);

            if (item.isCurrent) {
                mCurrentUserView = v;
            }
            v.setTag(item);
            return v;
        }

        private static Drawable getDrawable(Context context,
                UserRecord item) {
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

            Trace.beginSection("UserDetailView.Adapter#onClick");
            UserRecord userRecord =
                    (UserRecord) view.getTag();
            if (userRecord.isDisabledByAdmin()) {
                final Intent intent = RestrictedLockUtils.getShowAdminSupportDetailsIntent(
                        mContext, userRecord.enforcedAdmin);
                mController.startActivity(intent);
            } else if (userRecord.isSwitchToEnabled) {
                MetricsLogger.action(mContext, MetricsEvent.QS_SWITCH_USER);
                mUiEventLogger.log(QSUserSwitcherEvent.QS_USER_SWITCH);
                if (!userRecord.isAddUser
                        && !userRecord.isRestricted
                        && !userRecord.isDisabledByAdmin()) {
                    if (mCurrentUserView != null) {
                        mCurrentUserView.setActivated(false);
                    }
                    view.setActivated(true);
                }
                onUserListItemClicked(userRecord, mDialogShower);
            }
            Trace.endSection();
        }

        @Override
        public void onUserListItemClicked(@NonNull UserRecord record,
                @Nullable UserSwitchDialogController.DialogShower dialogShower) {
            if (dialogShower != null) {
                mDialogShower.dismiss();
            }
            super.onUserListItemClicked(record, dialogShower);
        }

        public void linkToViewGroup(ViewGroup viewGroup) {
            PseudoGridView.ViewGroupAdapterBridge.link(viewGroup, this);
        }
    }
}
