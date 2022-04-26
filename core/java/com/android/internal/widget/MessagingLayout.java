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

import static com.android.internal.widget.MessagingGroup.IMAGE_DISPLAY_LOCATION_EXTERNAL;
import static com.android.internal.widget.MessagingGroup.IMAGE_DISPLAY_LOCATION_INLINE;

import android.annotation.AttrRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StyleRes;
import android.app.Notification;
import android.app.Person;
import android.app.RemoteInputHistoryItem;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.RemotableViewMethod;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;

import com.android.internal.R;
import com.android.internal.util.ContrastColorUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A custom-built layout for the Notification.MessagingStyle allows dynamic addition and removal
 * messages and adapts the layout accordingly.
 */
@RemoteViews.RemoteView
public class MessagingLayout extends FrameLayout
        implements ImageMessageConsumer, IMessagingLayout {

    private static final float COLOR_SHIFT_AMOUNT = 60;
    public static final Interpolator LINEAR_OUT_SLOW_IN = new PathInterpolator(0f, 0f, 0.2f, 1f);
    public static final Interpolator FAST_OUT_LINEAR_IN = new PathInterpolator(0.4f, 0f, 1f, 1f);
    public static final Interpolator FAST_OUT_SLOW_IN = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    public static final OnLayoutChangeListener MESSAGING_PROPERTY_ANIMATOR
            = new MessagingPropertyAnimator();
    private final PeopleHelper mPeopleHelper = new PeopleHelper();
    private List<MessagingMessage> mMessages = new ArrayList<>();
    private List<MessagingMessage> mHistoricMessages = new ArrayList<>();
    private MessagingLinearLayout mMessagingLinearLayout;
    private boolean mShowHistoricMessages;
    private ArrayList<MessagingGroup> mGroups = new ArrayList<>();
    private MessagingLinearLayout mImageMessageContainer;
    private ImageView mRightIconView;
    private Rect mMessagingClipRect;
    private int mLayoutColor;
    private int mSenderTextColor;
    private int mMessageTextColor;
    private Icon mAvatarReplacement;
    private boolean mIsOneToOne;
    private ArrayList<MessagingGroup> mAddedGroups = new ArrayList<>();
    private Person mUser;
    private CharSequence mNameReplacement;
    private boolean mIsCollapsed;
    private ImageResolver mImageResolver;
    private CharSequence mConversationTitle;
    private ArrayList<MessagingLinearLayout.MessagingChild> mToRecycle = new ArrayList<>();

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
        mPeopleHelper.init(getContext());
        mMessagingLinearLayout = findViewById(R.id.notification_messaging);
        mImageMessageContainer = findViewById(R.id.conversation_image_message_container);
        mRightIconView = findViewById(R.id.right_icon);
        // We still want to clip, but only on the top, since views can temporarily out of bounds
        // during transitions.
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        int size = Math.max(displayMetrics.widthPixels, displayMetrics.heightPixels);
        mMessagingClipRect = new Rect(0, 0, size, size);
        setMessagingClippingDisabled(false);
    }

    @RemotableViewMethod
    public void setAvatarReplacement(Icon icon) {
        mAvatarReplacement = icon;
    }

    @RemotableViewMethod
    public void setNameReplacement(CharSequence nameReplacement) {
        mNameReplacement = nameReplacement;
    }

    /**
     * Set this layout to show the collapsed representation.
     *
     * @param isCollapsed is it collapsed
     */
    @RemotableViewMethod
    public void setIsCollapsed(boolean isCollapsed) {
        mIsCollapsed = isCollapsed;
    }

    @RemotableViewMethod
    public void setLargeIcon(Icon largeIcon) {
        // Unused
    }

    /**
     * Sets the conversation title of this conversation.
     *
     * @param conversationTitle the conversation title
     */
    @RemotableViewMethod
    public void setConversationTitle(CharSequence conversationTitle) {
        mConversationTitle = conversationTitle;
    }

    @RemotableViewMethod
    public void setData(Bundle extras) {
        Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        List<Notification.MessagingStyle.Message> newMessages
                = Notification.MessagingStyle.Message.getMessagesFromBundleArray(messages);
        Parcelable[] histMessages = extras.getParcelableArray(Notification.EXTRA_HISTORIC_MESSAGES);
        List<Notification.MessagingStyle.Message> newHistoricMessages
                = Notification.MessagingStyle.Message.getMessagesFromBundleArray(histMessages);
        setUser(extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON));
        RemoteInputHistoryItem[] history = (RemoteInputHistoryItem[])
                extras.getParcelableArray(Notification.EXTRA_REMOTE_INPUT_HISTORY_ITEMS);
        addRemoteInputHistoryToMessages(newMessages, history);
        boolean showSpinner =
                extras.getBoolean(Notification.EXTRA_SHOW_REMOTE_INPUT_SPINNER, false);
        bind(newMessages, newHistoricMessages, showSpinner);
    }

    @Override
    public void setImageResolver(ImageResolver resolver) {
        mImageResolver = resolver;
    }

    private void addRemoteInputHistoryToMessages(
            List<Notification.MessagingStyle.Message> newMessages,
            RemoteInputHistoryItem[] remoteInputHistory) {
        if (remoteInputHistory == null || remoteInputHistory.length == 0) {
            return;
        }
        for (int i = remoteInputHistory.length - 1; i >= 0; i--) {
            RemoteInputHistoryItem historyMessage = remoteInputHistory[i];
            Notification.MessagingStyle.Message message = new Notification.MessagingStyle.Message(
                    historyMessage.getText(), 0, (Person) null, true /* remoteHistory */);
            if (historyMessage.getUri() != null) {
                message.setData(historyMessage.getMimeType(), historyMessage.getUri());
            }
            newMessages.add(message);
        }
    }

    private void bind(List<Notification.MessagingStyle.Message> newMessages,
            List<Notification.MessagingStyle.Message> newHistoricMessages,
            boolean showSpinner) {

        List<MessagingMessage> historicMessages = createMessages(newHistoricMessages,
                true /* isHistoric */);
        List<MessagingMessage> messages = createMessages(newMessages, false /* isHistoric */);

        ArrayList<MessagingGroup> oldGroups = new ArrayList<>(mGroups);
        addMessagesToGroups(historicMessages, messages, showSpinner);

        // Let's first check which groups were removed altogether and remove them in one animation
        removeGroups(oldGroups);

        // Let's remove the remaining messages
        for (MessagingMessage message : mMessages) {
            message.removeMessage(mToRecycle);
        }
        for (MessagingMessage historicMessage : mHistoricMessages) {
            historicMessage.removeMessage(mToRecycle);
        }

        mMessages = messages;
        mHistoricMessages = historicMessages;

        updateHistoricMessageVisibility();
        updateTitleAndNamesDisplay();
        // after groups are finalized, hide the first sender name if it's showing as the title
        mPeopleHelper.maybeHideFirstSenderName(mGroups, mIsOneToOne, mConversationTitle);
        updateImageMessages();

        // Recycle everything at the end of the update, now that we know it's no longer needed.
        for (MessagingLinearLayout.MessagingChild child : mToRecycle) {
            child.recycle();
        }
        mToRecycle.clear();
    }

    private void updateImageMessages() {
        View newMessage = null;
        if (mImageMessageContainer == null) {
            return;
        }
        if (mIsCollapsed && !mGroups.isEmpty()) {
            // When collapsed, we're displaying the image message in a dedicated container
            // on the right of the layout instead of inline. Let's add the isolated image there
            MessagingGroup messagingGroup = mGroups.get(mGroups.size() - 1);
            MessagingImageMessage isolatedMessage = messagingGroup.getIsolatedMessage();
            if (isolatedMessage != null) {
                newMessage = isolatedMessage.getView();
            }
        }
        // Remove all messages that don't belong into the image layout
        View previousMessage = mImageMessageContainer.getChildAt(0);
        if (previousMessage != newMessage) {
            mImageMessageContainer.removeView(previousMessage);
            if (newMessage != null) {
                mImageMessageContainer.addView(newMessage);
            }
        }
        mImageMessageContainer.setVisibility(newMessage != null ? VISIBLE : GONE);

        // When showing an image message, do not show the large icon.  Removing the drawable
        // prevents it from being shown in the left_icon view (by the grouping util).
        if (newMessage != null && mRightIconView != null && mRightIconView.getDrawable() != null) {
            mRightIconView.setImageDrawable(null);
            mRightIconView.setVisibility(GONE);
        }
    }

    private void removeGroups(ArrayList<MessagingGroup> oldGroups) {
        int size = oldGroups.size();
        for (int i = 0; i < size; i++) {
            MessagingGroup group = oldGroups.get(i);
            if (!mGroups.contains(group)) {
                List<MessagingMessage> messages = group.getMessages();

                boolean wasShown = group.isShown();
                mMessagingLinearLayout.removeView(group);
                if (wasShown && !MessagingLinearLayout.isGone(group)) {
                    mMessagingLinearLayout.addTransientView(group, 0);
                    group.removeGroupAnimated(() -> {
                        mMessagingLinearLayout.removeTransientView(group);
                        group.recycle();
                    });
                } else {
                    mToRecycle.add(group);
                }
                mMessages.removeAll(messages);
                mHistoricMessages.removeAll(messages);
            }
        }
    }

    private void updateTitleAndNamesDisplay() {
        Map<CharSequence, String> uniqueNames = mPeopleHelper.mapUniqueNamesToPrefix(mGroups);

        // Now that we have the correct symbols, let's look what we have cached
        ArrayMap<CharSequence, Icon> cachedAvatars = new ArrayMap<>();
        for (int i = 0; i < mGroups.size(); i++) {
            // Let's now set the avatars
            MessagingGroup group = mGroups.get(i);
            boolean isOwnMessage = group.getSender() == mUser;
            CharSequence senderName = group.getSenderName();
            if (!group.needsGeneratedAvatar() || TextUtils.isEmpty(senderName)
                    || (mIsOneToOne && mAvatarReplacement != null && !isOwnMessage)) {
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
            if (!group.needsGeneratedAvatar() || TextUtils.isEmpty(senderName)) {
                continue;
            }
            if (mIsOneToOne && mAvatarReplacement != null && group.getSender() != mUser) {
                group.setAvatar(mAvatarReplacement);
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
        return mPeopleHelper.createAvatarSymbol(senderName, symbol, layoutColor);
    }

    private int findColor(CharSequence senderName, int layoutColor) {
        double luminance = ContrastColorUtil.calculateLuminance(layoutColor);
        float shift = Math.abs(senderName.hashCode()) % 5 / 4.0f - 0.5f;

        // we need to offset the range if the luminance is too close to the borders
        shift += Math.max(COLOR_SHIFT_AMOUNT / 2.0f / 100 - luminance, 0);
        shift -= Math.max(COLOR_SHIFT_AMOUNT / 2.0f / 100 - (1.0f - luminance), 0);
        return ContrastColorUtil.getShiftedColor(layoutColor,
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

    @RemotableViewMethod
    public void setSenderTextColor(int color) {
        mSenderTextColor = color;
    }


    /**
     * @param color the color of the notification background
     */
    @RemotableViewMethod
    public void setNotificationBackgroundColor(int color) {
        // Nothing to do with this
    }

    @RemotableViewMethod
    public void setMessageTextColor(int color) {
        mMessageTextColor = color;
    }

    public void setUser(Person user) {
        mUser = user;
        if (mUser.getIcon() == null) {
            Icon userIcon = Icon.createWithResource(getContext(),
                    com.android.internal.R.drawable.messaging_user);
            userIcon.setTint(mLayoutColor);
            mUser = mUser.toBuilder().setIcon(userIcon).build();
        }
    }

    private void addMessagesToGroups(List<MessagingMessage> historicMessages,
            List<MessagingMessage> messages, boolean showSpinner) {
        // Let's first find our groups!
        List<List<MessagingMessage>> groups = new ArrayList<>();
        List<Person> senders = new ArrayList<>();

        // Lets first find the groups
        findGroups(historicMessages, messages, groups, senders);

        // Let's now create the views and reorder them accordingly
        createGroupViews(groups, senders, showSpinner);
    }

    private void createGroupViews(List<List<MessagingMessage>> groups,
            List<Person> senders, boolean showSpinner) {
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
                mAddedGroups.add(newGroup);
            }
            newGroup.setImageDisplayLocation(mIsCollapsed
                    ? IMAGE_DISPLAY_LOCATION_EXTERNAL
                    : IMAGE_DISPLAY_LOCATION_INLINE);
            newGroup.setIsInConversation(false);
            newGroup.setLayoutColor(mLayoutColor);
            newGroup.setTextColors(mSenderTextColor, mMessageTextColor);
            Person sender = senders.get(groupIndex);
            CharSequence nameOverride = null;
            if (sender != mUser && mNameReplacement != null) {
                nameOverride = mNameReplacement;
            }
            newGroup.setSingleLine(mIsCollapsed);
            newGroup.setShowingAvatar(!mIsCollapsed);
            newGroup.setSender(sender, nameOverride);
            newGroup.setSending(groupIndex == (groups.size() - 1) && showSpinner);
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
            List<Person> senders) {
        CharSequence currentSenderKey = null;
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
            Person sender = message.getMessage().getSenderPerson();
            CharSequence key = sender == null ? null
                    : sender.getKey() == null ? sender.getName() : sender.getKey();
            isNewGroup |= !TextUtils.equals(key, currentSenderKey);
            if (isNewGroup) {
                currentGroup = new ArrayList<>();
                groups.add(currentGroup);
                if (sender == null) {
                    sender = mUser;
                }
                senders.add(sender);
                currentSenderKey = key;
            }
            currentGroup.add(message);
        }
    }

    /**
     * Creates new messages, reusing existing ones if they are available.
     *
     * @param newMessages the messages to parse.
     */
    private List<MessagingMessage> createMessages(
            List<Notification.MessagingStyle.Message> newMessages, boolean historic) {
        List<MessagingMessage> result = new ArrayList<>();
        for (int i = 0; i < newMessages.size(); i++) {
            Notification.MessagingStyle.Message m = newMessages.get(i);
            MessagingMessage message = findAndRemoveMatchingMessage(m);
            if (message == null) {
                message = MessagingMessage.createMessage(this, m, mImageResolver);
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
        int numHistoric = mHistoricMessages.size();
        for (int i = 0; i < numHistoric; i++) {
            MessagingMessage existing = mHistoricMessages.get(i);
            existing.setVisibility(mShowHistoricMessages ? VISIBLE : GONE);
        }
        int numGroups = mGroups.size();
        for (int i = 0; i < numGroups; i++) {
            MessagingGroup group = mGroups.get(i);
            int visibleChildren = 0;
            List<MessagingMessage> messages = group.getMessages();
            int numGroupMessages = messages.size();
            for (int j = 0; j < numGroupMessages; j++) {
                MessagingMessage message = messages.get(j);
                if (message.getVisibility() != GONE) {
                    visibleChildren++;
                }
            }
            if (visibleChildren > 0 && group.getVisibility() == GONE) {
                group.setVisibility(VISIBLE);
            } else if (visibleChildren == 0 && group.getVisibility() != GONE)   {
                group.setVisibility(GONE);
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (!mAddedGroups.isEmpty()) {
            getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    for (MessagingGroup group : mAddedGroups) {
                        if (!group.isShown()) {
                            continue;
                        }
                        MessagingPropertyAnimator.fadeIn(group.getAvatar());
                        MessagingPropertyAnimator.fadeIn(group.getSenderView());
                        MessagingPropertyAnimator.startLocalTranslationFrom(group,
                                group.getHeight(), LINEAR_OUT_SLOW_IN);
                    }
                    mAddedGroups.clear();
                    getViewTreeObserver().removeOnPreDrawListener(this);
                    return true;
                }
            });
        }
    }

    public MessagingLinearLayout getMessagingLinearLayout() {
        return mMessagingLinearLayout;
    }

    @Nullable
    public ViewGroup getImageMessageContainer() {
        return mImageMessageContainer;
    }

    public ArrayList<MessagingGroup> getMessagingGroups() {
        return mGroups;
    }

    @Override
    public void setMessagingClippingDisabled(boolean clippingDisabled) {
        mMessagingLinearLayout.setClipBounds(clippingDisabled ? null : mMessagingClipRect);
    }
}
