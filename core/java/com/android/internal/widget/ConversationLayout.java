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

package com.android.internal.widget;

import android.annotation.AttrRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StyleRes;
import android.app.Notification;
import android.app.Person;
import android.app.RemoteInputHistoryItem;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.RemotableViewMethod;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.graphics.ColorUtils;
import com.android.internal.util.ContrastColorUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * A custom-built layout for the Notification.MessagingStyle allows dynamic addition and removal
 * messages and adapts the layout accordingly.
 */
@RemoteViews.RemoteView
public class ConversationLayout extends FrameLayout
        implements ImageMessageConsumer, IMessagingLayout {

    public static final boolean CONVERSATION_LAYOUT_ENABLED = true;
    private static final float COLOR_SHIFT_AMOUNT = 60;
    /**
     *  Pattren for filter some ingonable characters.
     *  p{Z} for any kind of whitespace or invisible separator.
     *  p{C} for any kind of punctuation character.
     */
    private static final Pattern IGNORABLE_CHAR_PATTERN
            = Pattern.compile("[\\p{C}\\p{Z}]");
    private static final Pattern SPECIAL_CHAR_PATTERN
            = Pattern.compile ("[!@#$%&*()_+=|<>?{}\\[\\]~-]");
    private static final Consumer<MessagingMessage> REMOVE_MESSAGE
            = MessagingMessage::removeMessage;
    public static final Interpolator LINEAR_OUT_SLOW_IN = new PathInterpolator(0f, 0f, 0.2f, 1f);
    public static final Interpolator FAST_OUT_LINEAR_IN = new PathInterpolator(0.4f, 0f, 1f, 1f);
    public static final Interpolator FAST_OUT_SLOW_IN = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    public static final OnLayoutChangeListener MESSAGING_PROPERTY_ANIMATOR
            = new MessagingPropertyAnimator();
    private List<MessagingMessage> mMessages = new ArrayList<>();
    private List<MessagingMessage> mHistoricMessages = new ArrayList<>();
    private MessagingLinearLayout mMessagingLinearLayout;
    private boolean mShowHistoricMessages;
    private ArrayList<MessagingGroup> mGroups = new ArrayList<>();
    private TextView mTitleView;
    private int mLayoutColor;
    private int mSenderTextColor;
    private int mMessageTextColor;
    private int mAvatarSize;
    private Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint mTextPaint = new Paint();
    private Icon mAvatarReplacement;
    private boolean mIsOneToOne;
    private ArrayList<MessagingGroup> mAddedGroups = new ArrayList<>();
    private Person mUser;
    private CharSequence mNameReplacement;
    private boolean mIsCollapsed;
    private ImageResolver mImageResolver;
    private ImageView mConversationIcon;
    private TextView mHeaderText;
    private View mConversationIconBadge;
    private Icon mLargeIcon;
    private View mExpandButtonContainer;
    private NotificationExpandButton mExpandButton;
    private int mExpandButtonExpandedTopMargin;
    private int mBadgedSideMargins;
    private int mIconSizeBadged;
    private int mIconSizeCentered;
    private View mIcon;
    private int mExpandedGroupTopMargin;

    public ConversationLayout(@NonNull Context context) {
        super(context);
    }

    public ConversationLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ConversationLayout(@NonNull Context context, @Nullable AttributeSet attrs,
            @AttrRes int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ConversationLayout(@NonNull Context context, @Nullable AttributeSet attrs,
            @AttrRes int defStyleAttr, @StyleRes int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mMessagingLinearLayout = findViewById(R.id.notification_messaging);
        mMessagingLinearLayout.setMessagingLayout(this);
        // We still want to clip, but only on the top, since views can temporarily out of bounds
        // during transitions.
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        int size = Math.max(displayMetrics.widthPixels, displayMetrics.heightPixels);
        Rect rect = new Rect(0, 0, size, size);
        mMessagingLinearLayout.setClipBounds(rect);
        mTitleView = findViewById(R.id.title);
        mAvatarSize = getResources().getDimensionPixelSize(R.dimen.messaging_avatar_size);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mTextPaint.setAntiAlias(true);
        mConversationIcon = findViewById(R.id.conversation_icon);
        mIcon = findViewById(R.id.icon);
        mConversationIconBadge = findViewById(R.id.conversation_icon_badge);
        mHeaderText = findViewById(R.id.header_text);
        mExpandButtonContainer = findViewById(R.id.expand_button_container);
        mExpandButton = findViewById(R.id.expand_button);
        mExpandButtonExpandedTopMargin = getResources().getDimensionPixelSize(
                R.dimen.conversation_expand_button_top_margin_expanded);
        mBadgedSideMargins = getResources().getDimensionPixelSize(
                R.dimen.conversation_badge_side_margin);
        mIconSizeBadged = getResources().getDimensionPixelSize(
                R.dimen.conversation_icon_size_badged);
        mIconSizeCentered = getResources().getDimensionPixelSize(
                R.dimen.conversation_icon_size_centered);
        mExpandedGroupTopMargin = getResources().getDimensionPixelSize(
                R.dimen.conversation_icon_margin_top_centered);
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
        mMessagingLinearLayout.setMaxDisplayedLines(isCollapsed ? 1 : Integer.MAX_VALUE);
        updateExpandButton();
    }

    @RemotableViewMethod
    public void setData(Bundle extras) {
        Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        List<Notification.MessagingStyle.Message> newMessages
                = Notification.MessagingStyle.Message.getMessagesFromBundleArray(messages);
        Parcelable[] histMessages = extras.getParcelableArray(Notification.EXTRA_HISTORIC_MESSAGES);
        List<Notification.MessagingStyle.Message> newHistoricMessages
                = Notification.MessagingStyle.Message.getMessagesFromBundleArray(histMessages);

        // mUser now set (would be nice to avoid the side effect but WHATEVER)
        setUser(extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON));


        // Append remote input history to newMessages (again, side effect is lame but WHATEVS)
        RemoteInputHistoryItem[] history = (RemoteInputHistoryItem[])
                extras.getParcelableArray(Notification.EXTRA_REMOTE_INPUT_HISTORY_ITEMS);
        addRemoteInputHistoryToMessages(newMessages, history);

        boolean showSpinner =
                extras.getBoolean(Notification.EXTRA_SHOW_REMOTE_INPUT_SPINNER, false);

        // bind it, baby
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
        // convert MessagingStyle.Message to MessagingMessage, re-using ones from a previous binding
        // if they exist
        List<MessagingMessage> historicMessages = createMessages(newHistoricMessages,
                true /* isHistoric */);
        List<MessagingMessage> messages = createMessages(newMessages, false /* isHistoric */);

        // Copy our groups, before they get clobbered
        ArrayList<MessagingGroup> oldGroups = new ArrayList<>(mGroups);

        // Add our new MessagingMessages to groups
        List<List<MessagingMessage>> groups = new ArrayList<>();
        List<Person> senders = new ArrayList<>();

        // Lets first find the groups (populate `groups` and `senders`)
        findGroups(historicMessages, messages, groups, senders);

        // Let's now create the views and reorder them accordingly
        //   side-effect: updates mGroups, mAddedGroups
        createGroupViews(groups, senders, showSpinner);

        // Let's first check which groups were removed altogether and remove them in one animation
        removeGroups(oldGroups);

        // Let's remove the remaining messages
        mMessages.forEach(REMOVE_MESSAGE);
        mHistoricMessages.forEach(REMOVE_MESSAGE);

        mMessages = messages;
        mHistoricMessages = historicMessages;

        updateHistoricMessageVisibility();
        updateTitleAndNamesDisplay();

        updateConversationIconAndHeaderText();

    }

    private void updateConversationIconAndHeaderText() {
        // TODO: resolve this from shortcuts
        // Set avatar and name
        if (mIsOneToOne) {
            // Let's resolve the icon / text from the last sender
            mConversationIcon.setVisibility(VISIBLE);
            mHeaderText.setVisibility(VISIBLE);
            boolean found = false;
            for (int i = mGroups.size() - 1; i >= 0; i--) {
                MessagingGroup messagingGroup = mGroups.get(i);
                Person messageSender = messagingGroup.getSender();
                if (!mUser.equals(messageSender)) {
                    // Make sure the header is actually visible
                    // TODO: figure out what to do if there's a converationtitle + a Sender
                    mHeaderText.setText(messagingGroup.getSenderName());
                    mConversationIcon.setImageIcon(messagingGroup.getAvatarIcon());
                    found = true;
                    break;
                }
            }
            if (!found) {
                mHeaderText.setText(mUser.getName());
                mConversationIcon.setImageIcon(mUser.getIcon());
            }
        } else {
            mHeaderText.setVisibility(GONE);
            if (mIsCollapsed) {
                mConversationIcon.setVisibility(VISIBLE);
                if (mLargeIcon != null) {
                    mConversationIcon.setImageIcon(mLargeIcon);
                } else {
                    // TODO: generate LargeIcon from Conversation
                }
            } else {
                mConversationIcon.setVisibility(GONE);
            }
        }
        // update the icon position and sizing
        int gravity;
        int marginStart;
        int marginTop;
        int iconSize;
        if (mIsOneToOne || mIsCollapsed) {
            // Baded format
            gravity = Gravity.LEFT;
            marginStart = mBadgedSideMargins;
            marginTop = mBadgedSideMargins;
            iconSize = mIconSizeBadged;
        } else {
            gravity = Gravity.CENTER_HORIZONTAL;
            marginStart = 0;
            marginTop = mExpandedGroupTopMargin;
            iconSize = mIconSizeCentered;
        }
        FrameLayout.LayoutParams layoutParams =
                (LayoutParams) mConversationIconBadge.getLayoutParams();
        layoutParams.gravity = gravity;
        layoutParams.topMargin = marginTop;
        layoutParams.setMarginStart(marginStart);
        mConversationIconBadge.setLayoutParams(layoutParams);
        ViewGroup.LayoutParams iconParams = mIcon.getLayoutParams();
        iconParams.width = iconSize;
        iconParams.height = iconSize;
        mIcon.setLayoutParams(iconParams);
    }

    @RemotableViewMethod
    public void setLargeIcon(Icon largeIcon) {
        mLargeIcon = largeIcon;
    }

    private void removeGroups(ArrayList<MessagingGroup> oldGroups) {
        int size = oldGroups.size();
        for (int i = 0; i < size; i++) {
            MessagingGroup group = oldGroups.get(i);
            if (!mGroups.contains(group)) {
                List<MessagingMessage> messages = group.getMessages();
                Runnable endRunnable = () -> {
                    mMessagingLinearLayout.removeTransientView(group);
                    group.recycle();
                };

                boolean wasShown = group.isShown();
                mMessagingLinearLayout.removeView(group);
                if (wasShown && !MessagingLinearLayout.isGone(group)) {
                    mMessagingLinearLayout.addTransientView(group, 0);
                    group.removeGroupAnimated(endRunnable);
                } else {
                    endRunnable.run();
                }
                mMessages.removeAll(messages);
                mHistoricMessages.removeAll(messages);
            }
        }
    }

    private void updateTitleAndNamesDisplay() {
        ArrayMap<CharSequence, String> uniqueNames = new ArrayMap<>();
        ArrayMap<Character, CharSequence> uniqueCharacters = new ArrayMap<>();
        for (int i = 0; i < mGroups.size(); i++) {
            MessagingGroup group = mGroups.get(i);
            CharSequence senderName = group.getSenderName();
            if (!group.needsGeneratedAvatar() || TextUtils.isEmpty(senderName)) {
                continue;
            }
            if (!uniqueNames.containsKey(senderName)) {
                // Only use visible characters to get uniqueNames
                String pureSenderName = IGNORABLE_CHAR_PATTERN
                        .matcher(senderName).replaceAll("" /* replacement */);
                char c = pureSenderName.charAt(0);
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
                    uniqueCharacters.put(c, pureSenderName);
                }
            }
        }

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

    private Icon createAvatarSymbol(CharSequence senderName, String symbol, int layoutColor) {
        if (symbol.isEmpty() || TextUtils.isDigitsOnly(symbol) ||
                SPECIAL_CHAR_PATTERN.matcher(symbol).find()) {
            Icon avatarIcon = Icon.createWithResource(getContext(),
                    R.drawable.messaging_user);
            avatarIcon.setTint(findColor(senderName, layoutColor));
            return avatarIcon;
        } else {
            Bitmap bitmap = Bitmap.createBitmap(mAvatarSize, mAvatarSize, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            float radius = mAvatarSize / 2.0f;
            int color = findColor(senderName, layoutColor);
            mPaint.setColor(color);
            canvas.drawCircle(radius, radius, radius, mPaint);
            boolean needDarkText = ColorUtils.calculateLuminance(color) > 0.5f;
            mTextPaint.setColor(needDarkText ? Color.BLACK : Color.WHITE);
            mTextPaint.setTextSize(symbol.length() == 1 ? mAvatarSize * 0.5f : mAvatarSize * 0.3f);
            int yPos = (int) (radius - ((mTextPaint.descent() + mTextPaint.ascent()) / 2));
            canvas.drawText(symbol, radius, yPos, mTextPaint);
            return Icon.createWithBitmap(bitmap);
        }
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

    @RemotableViewMethod
    public void setMessageTextColor(int color) {
        mMessageTextColor = color;
    }

    private void setUser(Person user) {
        mUser = user;
        if (mUser.getIcon() == null) {
            Icon userIcon = Icon.createWithResource(getContext(),
                    R.drawable.messaging_user);
            userIcon.setTint(mLayoutColor);
            mUser = mUser.toBuilder().setIcon(userIcon).build();
        }
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
            // Create a new group, adding it to the linear layout as well
            if (newGroup == null) {
                newGroup = MessagingGroup.createGroup(mMessagingLinearLayout);
                mAddedGroups.add(newGroup);
            }
            newGroup.setDisplayImagesAtEnd(mIsCollapsed);
            newGroup.setLayoutColor(mLayoutColor);
            newGroup.setTextColors(mSenderTextColor, mMessageTextColor);
            Person sender = senders.get(groupIndex);
            CharSequence nameOverride = null;
            if (sender != mUser && mNameReplacement != null) {
                nameOverride = mNameReplacement;
            }
            newGroup.setShowingAvatar(!mIsOneToOne && !mIsCollapsed);
            newGroup.setSingleLine(mIsCollapsed);
            newGroup.setSender(sender, nameOverride);
            newGroup.setSending(groupIndex == (groups.size() - 1) && showSpinner);
            mGroups.add(newGroup);

            // Reposition to the correct place (if we're re-using a group)
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

    public ArrayList<MessagingGroup> getMessagingGroups() {
        return mGroups;
    }

    private void updateExpandButton() {
        int drawableId;
        int contentDescriptionId;
        int gravity;
        int topMargin = 0;
        if (mIsCollapsed) {
            drawableId = R.drawable.ic_expand_notification;
            contentDescriptionId = R.string.expand_button_content_description_collapsed;
            gravity = Gravity.CENTER;
        } else {
            drawableId = R.drawable.ic_collapse_notification;
            contentDescriptionId = R.string.expand_button_content_description_expanded;
            gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
            topMargin = mExpandButtonExpandedTopMargin;
        }
        mExpandButton.setImageDrawable(getContext().getDrawable(drawableId));
        mExpandButton.setColorFilter(mExpandButton.getOriginalNotificationColor());

        // update if the expand button is centered
        FrameLayout.LayoutParams layoutParams = (LayoutParams) mExpandButton.getLayoutParams();
        layoutParams.gravity = gravity;
        layoutParams.topMargin = topMargin;
        mExpandButton.setLayoutParams(layoutParams);

        mExpandButtonContainer.setContentDescription(mContext.getText(contentDescriptionId));
    }

    public void updateExpandability(boolean expandable, @Nullable OnClickListener onClickListener) {
        if (expandable) {
            mExpandButtonContainer.setVisibility(VISIBLE);
            mExpandButtonContainer.setOnClickListener(onClickListener);
        } else {
            // TODO: handle content paddings to end of layout
            mExpandButtonContainer.setVisibility(GONE);
        }
    }
}
