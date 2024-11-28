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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Flags;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Icon;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewStub;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.widget.ConversationAvatarData;
import com.android.internal.widget.ConversationAvatarData.GroupConversationAvatarData;
import com.android.internal.widget.ConversationAvatarData.OneToOneConversationAvatarData;
import com.android.internal.widget.ConversationHeaderData;
import com.android.internal.widget.ConversationLayout;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.NotificationFadeAware;
import com.android.systemui.statusbar.notification.row.shared.AsyncHybridViewInflation;
import com.android.systemui.statusbar.notification.row.shared.ConversationStyleSetAvatarAsync;
import com.android.systemui.statusbar.notification.row.ui.viewmodel.ConversationAvatar;
import com.android.systemui.statusbar.notification.row.ui.viewmodel.FacePile;
import com.android.systemui.statusbar.notification.row.ui.viewmodel.SingleIcon;

import java.util.Objects;

/**
 * A hybrid view which may contain information about one or more conversations.
 */
public class HybridConversationNotificationView extends HybridNotificationView {

    private ImageView mConversationIconView;
    private TextView mConversationSenderName;
    private ViewStub mConversationFacePileStub;
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
        if (AsyncHybridViewInflation.isEnabled()) {
            mConversationFacePileStub =
                    requireViewById(com.android.internal.R.id.conversation_face_pile);
        } else {
            // TODO(b/217799515): This usage is vague because mConversationFacePile represents both
            //  View and ViewStub at different stages of View inflation, should be removed when
            //  AsyncHybridViewInflation flag is removed
            mConversationFacePile =
                    requireViewById(com.android.internal.R.id.conversation_face_pile);
        }
        mConversationSenderName = requireViewById(R.id.conversation_notification_sender);
        applyTextColor(mConversationSenderName, mSecondaryTextColor);
        if (Flags.notificationsRedesignTemplates()) {
            mFacePileSize = getResources()
                    .getDimensionPixelSize(R.dimen.notification_2025_single_line_face_pile_size);
            mFacePileAvatarSize = getResources()
                    .getDimensionPixelSize(
                            R.dimen.notification_2025_single_line_face_pile_avatar_size);
            mSingleAvatarSize = getResources()
                    .getDimensionPixelSize(R.dimen.notification_2025_single_line_avatar_size);
        } else {
            mFacePileSize = getResources()
                    .getDimensionPixelSize(R.dimen.conversation_single_line_face_pile_size);
            mFacePileAvatarSize = getResources()
                    .getDimensionPixelSize(R.dimen.conversation_single_line_face_pile_avatar_size);
            mSingleAvatarSize = getResources()
                    .getDimensionPixelSize(R.dimen.conversation_single_line_avatar_size);
        }
        mFacePileProtectionWidth = getResources().getDimensionPixelSize(
                R.dimen.conversation_single_line_face_pile_protection_width);
        mTransformationHelper.setCustomTransformation(
                new FadeOutAndDownWithTitleTransformation(mConversationSenderName),
                mConversationSenderName.getId());
        mTransformationHelper.addViewTransformingToSimilar(mConversationIconView);
        mTransformationHelper.addTransformedView(mConversationSenderName);
    }

    @Override
    public void bind(@Nullable CharSequence title, @Nullable CharSequence text,
            @Nullable View contentView) {
        AsyncHybridViewInflation.assertInLegacyMode();
        if (!(contentView instanceof ConversationLayout)) {
            super.bind(title, text, contentView);
            return;
        }

        ConversationLayout conversationLayout = (ConversationLayout) contentView;
        loadConversationAvatar(conversationLayout);
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

    private void loadConversationAvatar(ConversationLayout conversationLayout) {
        AsyncHybridViewInflation.assertInLegacyMode();
        if (ConversationStyleSetAvatarAsync.isEnabled()) {
            loadConversationAvatarWithDrawable(conversationLayout);
        } else {
            loadConversationAvatarWithIcon(conversationLayout);
        }
    }

    @Deprecated
    private void loadConversationAvatarWithIcon(ConversationLayout conversationLayout) {
        ConversationStyleSetAvatarAsync.assertInLegacyMode();
        AsyncHybridViewInflation.assertInLegacyMode();
        final Icon conversationIcon = conversationLayout.getConversationIcon();
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
            final ImageView facePileBottomBg = mConversationFacePile.requireViewById(
                    com.android.internal.R.id.conversation_face_pile_bottom_background);
            final ImageView facePileBottom = mConversationFacePile.requireViewById(
                    com.android.internal.R.id.conversation_face_pile_bottom);
            final ImageView facePileTop = mConversationFacePile.requireViewById(
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
    }

    private void loadConversationAvatarWithDrawable(ConversationLayout conversationLayout) {
        AsyncHybridViewInflation.assertInLegacyMode();
        final ConversationHeaderData conversationHeaderData = Objects.requireNonNull(
                conversationLayout.getConversationHeaderData(),
                /* message = */ "conversationHeaderData should not be null");
        final ConversationAvatarData conversationAvatar =
                Objects.requireNonNull(conversationHeaderData.getConversationAvatar(),
                        /* message = */"conversationAvatar should not be null");

        if (conversationAvatar instanceof OneToOneConversationAvatarData oneToOneAvatar) {
            mConversationFacePile.setVisibility(GONE);
            mConversationIconView.setVisibility(VISIBLE);
            mConversationIconView.setImageDrawable(oneToOneAvatar.mDrawable);
            setSize(mConversationIconView, mSingleAvatarSize);
        } else {
            // If there isn't an icon, generate a "face pile" based on the sender avatars
            mConversationIconView.setVisibility(GONE);
            mConversationFacePile.setVisibility(VISIBLE);

            final GroupConversationAvatarData groupAvatar =
                    (GroupConversationAvatarData) conversationAvatar;
            mConversationFacePile =
                    requireViewById(com.android.internal.R.id.conversation_face_pile);
            final ImageView facePileBottomBg = mConversationFacePile.requireViewById(
                    com.android.internal.R.id.conversation_face_pile_bottom_background);
            final ImageView facePileBottom = mConversationFacePile.requireViewById(
                    com.android.internal.R.id.conversation_face_pile_bottom);
            final ImageView facePileTop = mConversationFacePile.requireViewById(
                    com.android.internal.R.id.conversation_face_pile_top);
            conversationLayout.bindFacePileWithDrawable(facePileBottomBg, facePileBottom,
                    facePileTop, groupAvatar);
            setSize(mConversationFacePile, mFacePileSize);
            setSize(facePileBottom, mFacePileAvatarSize);
            setSize(facePileTop, mFacePileAvatarSize);
            setSize(facePileBottomBg, mFacePileAvatarSize + 2 * mFacePileProtectionWidth);
            mTransformationHelper.addViewTransformingToSimilar(facePileTop);
            mTransformationHelper.addViewTransformingToSimilar(facePileBottom);
            mTransformationHelper.addViewTransformingToSimilar(facePileBottomBg);
        }
    }

    /**
     * Set the avatar using ConversationAvatar from SingleLineViewModel
     *
     * @param conversationAvatar the icon needed for a single-line conversation view, it should be
     *                           either an instance of SingleIcon or FacePile
     */
    public void setAvatar(@NonNull ConversationAvatar conversationAvatar) {
        if (AsyncHybridViewInflation.isUnexpectedlyInLegacyMode()) return;
        if (conversationAvatar instanceof SingleIcon) {
            SingleIcon avatar = (SingleIcon) conversationAvatar;
            if (mConversationFacePile != null) mConversationFacePile.setVisibility(GONE);
            mConversationIconView.setVisibility(VISIBLE);
            mConversationIconView.setImageDrawable(avatar.getIconDrawable());
            setSize(mConversationIconView, mSingleAvatarSize);
            return;
        }

        // If conversationAvatar is not a SingleIcon, it should be a FacePile.
        // Bind the face pile with it.
        FacePile facePileModel = (FacePile) conversationAvatar;
        mConversationIconView.setVisibility(GONE);
        // Inflate mConversationFacePile from ViewStub
        if (mConversationFacePile == null) {
            mConversationFacePile = mConversationFacePileStub.inflate();
        }
        mConversationFacePile.setVisibility(VISIBLE);

        ImageView facePileBottomBg = mConversationFacePile.requireViewById(
                com.android.internal.R.id.conversation_face_pile_bottom_background);
        ImageView facePileBottom = mConversationFacePile.requireViewById(
                com.android.internal.R.id.conversation_face_pile_bottom);
        ImageView facePileTop = mConversationFacePile.requireViewById(
                com.android.internal.R.id.conversation_face_pile_top);

        int bottomBackgroundColor = facePileModel.getBottomBackgroundColor();
        facePileBottomBg.setImageTintList(ColorStateList.valueOf(bottomBackgroundColor));

        facePileBottom.setImageDrawable(facePileModel.getBottomIconDrawable());
        facePileTop.setImageDrawable(facePileModel.getTopIconDrawable());

        setSize(mConversationFacePile, mFacePileSize);
        setSize(facePileBottom, mFacePileAvatarSize);
        setSize(facePileTop, mFacePileAvatarSize);
        setSize(facePileBottomBg, mFacePileAvatarSize + 2 * mFacePileProtectionWidth);

        mTransformationHelper.addViewTransformingToSimilar(facePileTop);
        mTransformationHelper.addViewTransformingToSimilar(facePileBottom);
        mTransformationHelper.addViewTransformingToSimilar(facePileBottomBg);

    }

    /**
     * bind the text views
     */
    public void setText(
            CharSequence titleText,
            CharSequence contentText,
            CharSequence conversationSenderName
    ) {
        if (AsyncHybridViewInflation.isUnexpectedlyInLegacyMode()) return;
        if (conversationSenderName == null) {
            mConversationSenderName.setVisibility(GONE);
        } else {
            mConversationSenderName.setVisibility(VISIBLE);
            mConversationSenderName.setText(conversationSenderName);
        }
        // TODO (b/217799515): super.bind() doesn't use contentView, remove the contentView
        //  argument when the flag is removed
        super.bind(/* title = */ titleText, /* text = */ contentText, /* contentView = */ null);
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

    @VisibleForTesting
    TextView getConversationSenderNameView() {
        return mConversationSenderName;
    }
}
