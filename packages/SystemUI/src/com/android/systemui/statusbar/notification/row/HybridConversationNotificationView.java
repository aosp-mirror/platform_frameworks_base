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

package com.android.systemui.statusbar.notification.row;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.widget.ConversationLayout;
import com.android.systemui.R;
import com.android.systemui.statusbar.notification.NotificationFadeAware;

/**
 * A hybrid view which may contain information about one ore more conversations.
 */
public class HybridConversationNotificationView extends HybridNotificationView {

    private ImageView mConversationIconView;
    private TextView mConversationSenderName;
    private View mConversationFacePile;
    private int mSingleAvatarSize;
    private int mFacePileSize;
    private int mFacePileAvatarSize;
    private int mFacePileProtectionWidth;

    public HybridConversationNotificationView(Context context) {
        this(context, null);
    }

    public HybridConversationNotificationView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public HybridConversationNotificationView(
            Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public HybridConversationNotificationView(
            Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mConversationIconView = requireViewById(com.android.internal.R.id.conversation_icon);
        mConversationFacePile = requireViewById(com.android.internal.R.id.conversation_face_pile);
        mConversationSenderName = requireViewById(R.id.conversation_notification_sender);
        mFacePileSize = getResources()
                .getDimensionPixelSize(R.dimen.conversation_single_line_face_pile_size);
        mFacePileAvatarSize = getResources()
                .getDimensionPixelSize(R.dimen.conversation_single_line_face_pile_avatar_size);
        mSingleAvatarSize = getResources()
                .getDimensionPixelSize(R.dimen.conversation_single_line_avatar_size);
        mFacePileProtectionWidth = getResources().getDimensionPixelSize(
                R.dimen.conversation_single_line_face_pile_protection_width);
        mTransformationHelper.addViewTransformingToSimilar(mConversationIconView);
        mTransformationHelper.addTransformedView(mConversationSenderName);
    }

    @Override
    public void bind(@Nullable CharSequence title, @Nullable CharSequence text,
            @Nullable View contentView) {
        if (!(contentView instanceof ConversationLayout)) {
            super.bind(title, text, contentView);
            return;
        }

        ConversationLayout conversationLayout = (ConversationLayout) contentView;
        Icon conversationIcon = conversationLayout.getConversationIcon();
        if (conversationIcon != null) {
            mConversationFacePile.setVisibility(GONE);
            mConversationIconView.setVisibility(VISIBLE);
            mConversationIconView.setImageIcon(conversationIcon);
            setSize(mConversationIconView, mSingleAvatarSize);
        } else {
            // If there isn't an icon, generate a "face pile" based on the sender avatars
            mConversationIconView.setVisibility(GONE);
            mConversationFacePile.setVisibility(VISIBLE);

            mConversationFacePile =
                    requireViewById(com.android.internal.R.id.conversation_face_pile);
            ImageView facePileBottomBg = mConversationFacePile.requireViewById(
                    com.android.internal.R.id.conversation_face_pile_bottom_background);
            ImageView facePileBottom = mConversationFacePile.requireViewById(
                    com.android.internal.R.id.conversation_face_pile_bottom);
            ImageView facePileTop = mConversationFacePile.requireViewById(
                    com.android.internal.R.id.conversation_face_pile_top);
            conversationLayout.bindFacePile(facePileBottomBg, facePileBottom, facePileTop);
            setSize(mConversationFacePile, mFacePileSize);
            setSize(facePileBottom, mFacePileAvatarSize);
            setSize(facePileTop, mFacePileAvatarSize);
            setSize(facePileBottomBg, mFacePileAvatarSize + 2 * mFacePileProtectionWidth);
            mTransformationHelper.addViewTransformingToSimilar(facePileTop);
            mTransformationHelper.addViewTransformingToSimilar(facePileBottom);
            mTransformationHelper.addViewTransformingToSimilar(facePileBottomBg);
        }
        CharSequence conversationTitle = conversationLayout.getConversationTitle();
        if (TextUtils.isEmpty(conversationTitle)) {
            conversationTitle = title;
        }
        if (conversationLayout.isOneToOne()) {
            mConversationSenderName.setVisibility(GONE);
        } else {
            mConversationSenderName.setVisibility(VISIBLE);
            mConversationSenderName.setText(conversationLayout.getConversationSenderName());
        }
        CharSequence conversationText = conversationLayout.getConversationText();
        if (TextUtils.isEmpty(conversationText)) {
            conversationText = text;
        }
        super.bind(conversationTitle, conversationText, conversationLayout);
    }

    private static void setSize(View view, int size) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
        lp.width = size;
        lp.height = size;
        view.setLayoutParams(lp);
    }

    /**
     * Apply the faded state as a layer type change to the face pile view which needs to have
     * overlapping contents render precisely.
     */
    @Override
    public void setNotificationFaded(boolean faded) {
        super.setNotificationFaded(faded);
        NotificationFadeAware.setLayerTypeForFaded(mConversationFacePile, faded);
    }
}
