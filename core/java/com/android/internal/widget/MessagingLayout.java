/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.internal.widget;

import android.annotation.AttrRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StyleRes;
import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.view.RemotableViewMethod;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.graphics.ColorUtils;
import com.android.internal.util.NotificationColorUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A custom-built layout for the Notification.MessagingStyle allows dynamic addition and removal
 * messages and adapts the layout accordingly.
 */
@RemoteViews.RemoteView
public class MessagingLayout extends FrameLayout {

    private static final float COLOR_SHIFT_AMOUNT = 60;
    private static final Consumer<MessagingMessage> REMOVE_MESSAGE
            = MessagingMessage::removeMessage;
    private List<MessagingMessage> mMessages = new ArrayList<>();
    private List<MessagingMessage> mHistoricMessages = new ArrayList<>();
    private MessagingLinearLayout mMessagingLinearLayout;
    private View mContractedMessage;
    private boolean mShowHistoricMessages;
    private ArrayList<MessagingGroup> mGroups = new ArrayList<>();
    private TextView mTitleView;
    private int mLayoutColor;
    private int mAvatarSize;
    private Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint mTextPaint = new Paint();
    private CharSequence mConversationTitle;
    private Icon mLargeIcon;
    private boolean mIsOneToOne;

    public MessagingLayout(@NonNull Context context) {
        super(context);
    }

    public MessagingLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public MessagingLayout(@NonNull Context context, @Nullable AttributeSet attrs,
            @AttrRes int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public MessagingLayout(@NonNull Context context, @Nullable AttributeSet attrs,
            @AttrRes int defStyleAttr, @StyleRes int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mMessagingLinearLayout = findViewById(R.id.notification_messaging);
        mTitleView = findViewById(R.id.title);
        mAvatarSize = getResources().getDimensionPixelSize(R.dimen.messaging_avatar_size);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mTextPaint.setAntiAlias(true);
    }

    @RemotableViewMethod
    public void setLargeIcon(Icon icon) {
        mLargeIcon = icon;
    }

    @RemotableViewMethod
    public void setData(Bundle extras) {
        Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        List<Notification.MessagingStyle.Message> newMessages
                = Notification.MessagingStyle.Message.getMessagesFromBundleArray(messages);
        Parcelable[] histMessages = extras.getParcelableArray(Notification.EXTRA_HISTORIC_MESSAGES);
        List<Notification.MessagingStyle.Message> newHistoricMessages
                = Notification.MessagingStyle.Message.getMessagesFromBundleArray(histMessages);
        mConversationTitle = null;
        TextView headerText = findViewById(R.id.header_text);
        if (headerText != null) {
            mConversationTitle = headerText.getText();
        }
        bind(newMessages, newHistoricMessages);
    }

    private void bind(List<Notification.MessagingStyle.Message> newMessages,
            List<Notification.MessagingStyle.Message> newHistoricMessages) {

        List<MessagingMessage> historicMessages = createMessages(newHistoricMessages,
                true /* isHistoric */);
        List<MessagingMessage> messages = createMessages(newMessages, false /* isHistoric */);
        addMessagesToGroups(historicMessages, messages);

        // Let's remove the remaining messages
        mMessages.forEach(REMOVE_MESSAGE);
        mHistoricMessages.forEach(REMOVE_MESSAGE);

        mMessages = messages;
        mHistoricMessages = historicMessages;

        updateContractedMessage();
        updateHistoricMessageVisibility();
        updateTitleAndNamesDisplay();
    }

    private void updateTitleAndNamesDisplay() {
        ArrayMap<CharSequence, String> uniqueNames = new ArrayMap<>();
        ArrayMap<Character, CharSequence> uniqueCharacters = new ArrayMap<>();
        for (int i = 0; i < mGroups.size(); i++) {
            MessagingGroup group = mGroups.get(i);
            CharSequence senderName = group.getSenderName();
            if (TextUtils.isEmpty(senderName)) {
                continue;
            }
            boolean visible = !mIsOneToOne;
            group.setSenderVisible(visible);
            if ((visible || mLargeIcon == null) && !uniqueNames.containsKey(senderName)) {
                char c = senderName.charAt(0);
                if (uniqueCharacters.containsKey(c)) {
                    // this character was already used, lets make it more unique. We first need to
                    // resolve the existing character if it exists
                    CharSequence existingName = uniqueCharacters.get(c);
                    if (existingName != null) {
                        uniqueNames.put(existingName, findNameSplit((String) existingName));
                        uniqueCharacters.put(c, null);
                    }
                    uniqueNames.put(senderName, findNameSplit((String) senderName));
                } else {
                    uniqueNames.put(senderName, Character.toString(c));
                    uniqueCharacters.put(c, senderName);
                }
            }
        }

        // Now that we have the correct symbols, let's look what we have cached
        ArrayMap<CharSequence, Icon> cachedAvatars = new ArrayMap<>();
        for (int i = 0; i < mGroups.size(); i++) {
            // Let's now set the avatars
            MessagingGroup group = mGroups.get(i);
            CharSequence senderName = group.getSenderName();
            if (TextUtils.isEmpty(senderName) || (mIsOneToOne && mLargeIcon != null)) {
                continue;
            }
            String symbol = uniqueNames.get(senderName);
            Icon cachedIcon = group.getAvatarSymbolIfMatching(senderName,
                    symbol, mLayoutColor);
            if (cachedIcon != null) {
                cachedAvatars.put(senderName, cachedIcon);
            }
        }

        for (int i = 0; i < mGroups.size(); i++) {
            // Let's now set the avatars
            MessagingGroup group = mGroups.get(i);
            CharSequence senderName = group.getSenderName();
            if (TextUtils.isEmpty(senderName)) {
                continue;
            }
            if (mIsOneToOne && mLargeIcon != null) {
                group.setAvatar(mLargeIcon);
            } else {
                Icon cachedIcon = cachedAvatars.get(senderName);
                if (cachedIcon == null) {
                    cachedIcon = createAvatarSymbol(senderName, uniqueNames.get(senderName),
                            mLayoutColor);
                    cachedAvatars.put(senderName, cachedIcon);
                }
                group.setCreatedAvatar(cachedIcon, senderName, uniqueNames.get(senderName),
                        mLayoutColor);
            }
        }
    }

    public Icon createAvatarSymbol(CharSequence senderName, String symbol, int layoutColor) {
        Bitmap bitmap = Bitmap.createBitmap(mAvatarSize, mAvatarSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        float radius = mAvatarSize / 2.0f;
        int color = findColor(senderName, layoutColor);
        mPaint.setColor(color);
        canvas.drawCircle(radius, radius, radius, mPaint);
        boolean needDarkText  = ColorUtils.calculateLuminance(color) > 0.5f;
        mTextPaint.setColor(needDarkText ? Color.BLACK : Color.WHITE);
        mTextPaint.setTextSize(symbol.length() == 1 ? mAvatarSize * 0.75f : mAvatarSize * 0.4f);
        int yPos = (int) (radius - ((mTextPaint.descent() + mTextPaint.ascent()) / 2)) ;
        canvas.drawText(symbol, radius, yPos, mTextPaint);
        return Icon.createWithBitmap(bitmap);
    }

    private int findColor(CharSequence senderName, int layoutColor) {
        double luminance = NotificationColorUtil.calculateLuminance(layoutColor);
        float shift = Math.abs(senderName.hashCode()) % 5 / 4.0f - 0.5f;

        // we need to offset the range if the luminance is too close to the borders
        shift += Math.max(COLOR_SHIFT_AMOUNT / 2.0f / 100 - luminance, 0);
        shift -= Math.max(COLOR_SHIFT_AMOUNT / 2.0f / 100 - (1.0f - luminance), 0);
        return NotificationColorUtil.getShiftedColor(layoutColor,
                (int) (shift * COLOR_SHIFT_AMOUNT));
    }

    private String findNameSplit(String existingName) {
        String[] split = existingName.split(" ");
        if (split.length > 1) {
            return Character.toString(split[0].charAt(0))
                    + Character.toString(split[1].charAt(0));
        }
        return existingName.substring(0, 1);
    }

    @RemotableViewMethod
    public void setLayoutColor(int color) {
        mLayoutColor = color;
    }

    @RemotableViewMethod
    public void setIsOneToOne(boolean oneToOne) {
        mIsOneToOne = oneToOne;
    }

    private void addMessagesToGroups(List<MessagingMessage> historicMessages,
            List<MessagingMessage> messages) {
        // Let's first find our groups!
        List<List<MessagingMessage>> groups = new ArrayList<>();
        List<CharSequence> senders = new ArrayList<>();

        // Lets first find the groups
        findGroups(historicMessages, messages, groups, senders);

        // Let's now create the views and reorder them accordingly
        createGroupViews(groups, senders);
    }

    private void createGroupViews(List<List<MessagingMessage>> groups, List<CharSequence> senders) {
        mGroups.clear();
        for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
            List<MessagingMessage> group = groups.get(groupIndex);
            MessagingGroup newGroup = null;
            // we'll just take the first group that exists or create one there is none
            for (int messageIndex = group.size() - 1; messageIndex >= 0; messageIndex--) {
                MessagingMessage message = group.get(messageIndex);
                newGroup = message.getGroup();
                if (newGroup != null) {
                    break;
                }
            }
            if (newGroup == null) {
                newGroup = MessagingGroup.createGroup(mMessagingLinearLayout);
            }
            newGroup.setLayoutColor(mLayoutColor);
            newGroup.setSender(senders.get(groupIndex));
            mGroups.add(newGroup);

            if (mMessagingLinearLayout.indexOfChild(newGroup) != groupIndex) {
                mMessagingLinearLayout.removeView(newGroup);
                mMessagingLinearLayout.addView(newGroup, groupIndex);
            }
            newGroup.setMessages(group);
        }
    }

    private void findGroups(List<MessagingMessage> historicMessages,
            List<MessagingMessage> messages, List<List<MessagingMessage>> groups,
            List<CharSequence> senders) {
        CharSequence currentSender = null;
        List<MessagingMessage> currentGroup = null;
        int histSize = historicMessages.size();
        for (int i = 0; i < histSize + messages.size(); i++) {
            MessagingMessage message;
            if (i < histSize) {
                message = historicMessages.get(i);
            } else {
                message = messages.get(i - histSize);
            }
            boolean isNewGroup = currentGroup == null;
            CharSequence sender = message.getMessage().getSender();
            isNewGroup |= !TextUtils.equals(sender, currentSender);
            if (isNewGroup) {
                currentGroup = new ArrayList<>();
                groups.add(currentGroup);
                senders.add(sender);
                currentSender = sender;
            }
            currentGroup.add(message);
        }
    }

    private void updateContractedMessage() {
        for (int i = mMessages.size() - 1; i >= 0; i--) {
            MessagingMessage m = mMessages.get(i);
            // Incoming messages have a non-empty sender.
            if (!TextUtils.isEmpty(m.getMessage().getSender())) {
                mContractedMessage = m;
                return;
            }
        }
        if (!mMessages.isEmpty()) {
            // No incoming messages, fall back to outgoing message
            mContractedMessage = mMessages.get(mMessages.size() - 1);
            return;
        }
        mContractedMessage = null;
    }

    /**
     * Creates new messages, reusing existing ones if they are available.
     *
     * @param newMessages the messages to parse.
     */
    private List<MessagingMessage> createMessages(
            List<Notification.MessagingStyle.Message> newMessages, boolean historic) {
        List<MessagingMessage> result = new ArrayList<>();;
        for (int i = 0; i < newMessages.size(); i++) {
            Notification.MessagingStyle.Message m = newMessages.get(i);
            MessagingMessage message = findAndRemoveMatchingMessage(m);
            if (message == null) {
                message = MessagingMessage.createMessage(this, m);
            }
            message.setIsHistoric(historic);
            result.add(message);
        }
        return result;
    }

    private MessagingMessage findAndRemoveMatchingMessage(Notification.MessagingStyle.Message m) {
        for (int i = 0; i < mMessages.size(); i++) {
            MessagingMessage existing = mMessages.get(i);
            if (existing.sameAs(m)) {
                mMessages.remove(i);
                return existing;
            }
        }
        for (int i = 0; i < mHistoricMessages.size(); i++) {
            MessagingMessage existing = mHistoricMessages.get(i);
            if (existing.sameAs(m)) {
                mHistoricMessages.remove(i);
                return existing;
            }
        }
        return null;
    }

    public void showHistoricMessages(boolean show) {
        mShowHistoricMessages = show;
        updateHistoricMessageVisibility();
    }

    private void updateHistoricMessageVisibility() {
        for (int i = 0; i < mHistoricMessages.size(); i++) {
            MessagingMessage existing = mHistoricMessages.get(i);
            existing.setVisibility(mShowHistoricMessages ? VISIBLE : GONE);
        }
    }

    public View getContractedMessage() {
        return mContractedMessage;
    }

    public MessagingLinearLayout getMessagingLinearLayout() {
        return mMessagingLinearLayout;
    }
}
