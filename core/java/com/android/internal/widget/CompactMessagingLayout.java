/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.widget;

import android.app.Notification;
import android.app.Notification.MessagingStyle;
import android.app.Person;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.RemotableViewMethod;
import android.view.View;
import android.view.ViewStub;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.R;

import java.util.ArrayList;
import java.util.List;

/**
 * A custom-built layout for the compact Heads Up of Notification.MessagingStyle .
 */
@RemoteViews.RemoteView
public class CompactMessagingLayout extends FrameLayout {

    private final PeopleHelper mPeopleHelper = new PeopleHelper();

    private ViewStub mConversationFacePileViewStub;

    private int mNotificationBackgroundColor;
    private int mFacePileSize;
    private int mFacePileAvatarSize;
    private int mFacePileProtectionWidth;
    private int mLayoutColor;

    public CompactMessagingLayout(@NonNull Context context) {
        super(context);
    }

    public CompactMessagingLayout(@NonNull Context context,
            @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public CompactMessagingLayout(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public CompactMessagingLayout(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mPeopleHelper.init(getContext());
        mConversationFacePileViewStub = requireViewById(R.id.conversation_face_pile);
        mFacePileSize = getResources()
                .getDimensionPixelSize(R.dimen.conversation_compact_face_pile_size);
        mFacePileAvatarSize = getResources()
                .getDimensionPixelSize(R.dimen.conversation_compact_face_pile_avatar_size);
        mFacePileProtectionWidth = getResources().getDimensionPixelSize(
                R.dimen.conversation_compact_face_pile_protection_width);
    }

    /**
     * Set conversation data
     *
     * @param extras Bundle contains conversation data
     */
    @RemotableViewMethod(asyncImpl = "setGroupFacePileAsync")
    public void setGroupFacePile(Bundle extras) {
        // NO-OP
    }

    /**
     * async version of {@link ConversationLayout#setLayoutColor}
     */
    @RemotableViewMethod
    public Runnable setLayoutColorAsync(int color) {
        mLayoutColor = color;
        return NotificationRunnables.NOOP;
    }

    @RemotableViewMethod(asyncImpl = "setLayoutColorAsync")
    public void setLayoutColor(int color) {
        mLayoutColor = color;
    }

    /**
     * @param color the color of the notification background
     */
    @RemotableViewMethod
    public void setNotificationBackgroundColor(int color) {
        mNotificationBackgroundColor = color;
    }

    /**
     * async version of {@link CompactMessagingLayout#setGroupFacePile}
     * setGroupFacePile!
     */
    public Runnable setGroupFacePileAsync(Bundle extras) {
        final Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        final List<Notification.MessagingStyle.Message> newMessages =
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(messages);
        final Parcelable[] histMessages =
                extras.getParcelableArray(Notification.EXTRA_HISTORIC_MESSAGES);
        final List<Notification.MessagingStyle.Message> newHistoricMessages =
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(histMessages);
        final Person user = extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON, Person.class);

        final List<List<MessagingStyle.Message>> groups = groupMessages(newMessages,
                newHistoricMessages);
        final PeopleHelper.NameToPrefixMap nameToPrefixMap =
                mPeopleHelper.mapUniqueNamesToPrefixWithGroupList(groups);
        final int layoutColor = mLayoutColor;
        // Find last two person's icon to show them in the face pile.
        Icon secondLastIcon = null;
        Icon lastIcon = null;
        CharSequence lastKey = null;
        final CharSequence userKey = getPersonKey(user);
        for (int i = groups.size() - 1; i >= 0; i--) {
            final MessagingStyle.Message message = groups.get(i).get(0);
            final Person sender =
                    message.getSenderPerson() != null ? message.getSenderPerson() : user;
            final CharSequence senderKey = getPersonKey(sender);
            final boolean notUser = senderKey != userKey;
            final boolean notIncluded = senderKey != lastKey;

            if ((notUser && notIncluded) || (i == 0 && lastKey == null)) {
                final Icon icon = getSenderIcon(sender, nameToPrefixMap, layoutColor);
                if (lastIcon == null) {
                    lastIcon = icon;
                    lastKey = senderKey;
                } else {
                    secondLastIcon = icon;
                    break;
                }
            }
        }

        if (lastIcon == null) {
            lastIcon = getSenderIcon(null, null, layoutColor);
        }

        if (secondLastIcon == null) {
            secondLastIcon = getSenderIcon(null, null, layoutColor);
        }
        final Drawable secondLastIconDrawable = secondLastIcon.loadDrawable(getContext());
        final Drawable lastIconDrawable = lastIcon.loadDrawable(getContext());
        return () -> {
            final View conversationFacePile = mConversationFacePileViewStub.inflate();
            conversationFacePile.setVisibility(VISIBLE);

            final ImageView facePileBottomBg = conversationFacePile.requireViewById(
                    com.android.internal.R.id.conversation_face_pile_bottom_background);
            final ImageView facePileTop = conversationFacePile.requireViewById(
                    com.android.internal.R.id.conversation_face_pile_top);
            final ImageView facePileBottom = conversationFacePile.requireViewById(
                    com.android.internal.R.id.conversation_face_pile_bottom);

            facePileTop.setImageDrawable(secondLastIconDrawable);
            facePileBottom.setImageDrawable(lastIconDrawable);
            facePileBottomBg.setImageTintList(ColorStateList.valueOf(mNotificationBackgroundColor));
            setSize(conversationFacePile, mFacePileSize);
            setSize(facePileBottom, mFacePileAvatarSize);
            setSize(facePileTop, mFacePileAvatarSize);
            setSize(facePileBottomBg, mFacePileAvatarSize + 2 * mFacePileProtectionWidth);
        };
    }

    @NonNull
    private Icon getSenderIcon(@Nullable Person sender,
            @Nullable PeopleHelper.NameToPrefixMap uniqueNames,
            int layoutColor) {
        if (sender == null) {
            return mPeopleHelper.createAvatarSymbol(/* name = */ "", /* symbol = */ "",
                    layoutColor);
        }

        if (sender.getIcon() != null) {
            return sender.getIcon();
        }

        final CharSequence senderName = sender.getName();
        if (!TextUtils.isEmpty(senderName)) {
            final String symbol = uniqueNames != null ? uniqueNames.getPrefix(senderName) : "";
            return mPeopleHelper.createAvatarSymbol(senderName, symbol, layoutColor);
        }

        return mPeopleHelper.createAvatarSymbol(/* name = */ "", /* symbol = */ "", layoutColor);
    }


    /**
     * Groups the given messages by their sender.
     */
    private static List<List<MessagingStyle.Message>> groupMessages(
            List<MessagingStyle.Message> messages,
            List<MessagingStyle.Message> historicMessages
    ) {
        if (messages.isEmpty() && historicMessages.isEmpty()) return List.of();

        ArrayList<MessagingStyle.Message> currentGroup = null;
        CharSequence currentSenderKey = null;
        final ArrayList<List<MessagingStyle.Message>> groups = new ArrayList<>();
        final int histSize = historicMessages.size();

        for (int i = 0; i < histSize + messages.size(); i++) {
            final MessagingStyle.Message message = i < histSize ? historicMessages.get(i)
                    : messages.get(i - histSize);
            if (message == null) continue;

            final CharSequence senderKey = getPersonKey(message.getSenderPerson());
            final boolean isNewGroup = currentGroup == null || senderKey != currentSenderKey;
            if (isNewGroup) {
                currentGroup = new ArrayList<>();
                groups.add(currentGroup);
                currentSenderKey = senderKey;
            }
            currentGroup.add(message);
        }
        return groups;
    }

    private static CharSequence getPersonKey(@Nullable Person person) {
        return person == null ? null : person.getKey() == null ? person.getName() : person.getKey();
    }

    private static void setSize(View view, int size) {
        final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
        lp.width = size;
        lp.height = size;
        view.setLayoutParams(lp);
    }
}
