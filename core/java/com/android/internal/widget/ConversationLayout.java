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

import static com.android.internal.widget.MessagingGroup.IMAGE_DISPLAY_LOCATION_EXTERNAL;
import static com.android.internal.widget.MessagingGroup.IMAGE_DISPLAY_LOCATION_INLINE;
import static com.android.internal.widget.MessagingPropertyAnimator.ALPHA_IN;
import static com.android.internal.widget.MessagingPropertyAnimator.ALPHA_OUT;

import android.annotation.AttrRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StyleRes;
import android.app.Notification;
import android.app.Person;
import android.app.RemoteInputHistoryItem;
import android.content.Context;
import android.content.res.ColorStateList;
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
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.RemotableViewMethod;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.graphics.ColorUtils;
import com.android.internal.util.ContrastColorUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * A custom-built layout for the Notification.MessagingStyle allows dynamic addition and removal
 * messages and adapts the layout accordingly.
 */
@RemoteViews.RemoteView
public class ConversationLayout extends FrameLayout
        implements ImageMessageConsumer, IMessagingLayout {

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
    private CachingIconView mConversationIcon;
    private View mConversationIconContainer;
    private int mConversationIconTopPadding;
    private int mConversationIconTopPaddingExpandedGroup;
    private int mConversationIconTopPaddingNoAppName;
    private int mExpandedGroupMessagePaddingNoAppName;
    private TextView mConversationText;
    private View mConversationIconBadge;
    private CachingIconView mConversationIconBadgeBg;
    private Icon mLargeIcon;
    private View mExpandButtonContainer;
    private View mExpandButtonInnerContainer;
    private ViewGroup mExpandButtonAndContentContainer;
    private NotificationExpandButton mExpandButton;
    private MessagingLinearLayout mImageMessageContainer;
    private int mExpandButtonExpandedTopMargin;
    private int mBadgedSideMargins;
    private int mConversationAvatarSize;
    private int mConversationAvatarSizeExpanded;
    private CachingIconView mIcon;
    private CachingIconView mImportanceRingView;
    private int mExpandedGroupSideMargin;
    private int mExpandedGroupSideMarginFacePile;
    private View mConversationFacePile;
    private int mNotificationBackgroundColor;
    private CharSequence mFallbackChatName;
    private CharSequence mFallbackGroupChatName;
    private CharSequence mConversationTitle;
    private int mNotificationHeaderExpandedPadding;
    private View mConversationHeader;
    private View mContentContainer;
    private boolean mExpandable = true;
    private int mContentMarginEnd;
    private Rect mMessagingClipRect;
    private ObservableTextView mAppName;
    private ViewGroup mActions;
    private int mConversationContentStart;
    private int mInternalButtonPadding;
    private boolean mAppNameGone;
    private int mFacePileAvatarSize;
    private int mFacePileAvatarSizeExpandedGroup;
    private int mFacePileProtectionWidth;
    private int mFacePileProtectionWidthExpanded;
    private boolean mImportantConversation;
    private TextView mUnreadBadge;
    private ViewGroup mAppOps;
    private Rect mAppOpsTouchRect = new Rect();
    private float mMinTouchSize;

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
        mActions = findViewById(R.id.actions);
        mMessagingLinearLayout.setMessagingLayout(this);
        mImageMessageContainer = findViewById(R.id.conversation_image_message_container);
        // We still want to clip, but only on the top, since views can temporarily out of bounds
        // during transitions.
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        int size = Math.max(displayMetrics.widthPixels, displayMetrics.heightPixels);
        mMessagingClipRect = new Rect(0, 0, size, size);
        setMessagingClippingDisabled(false);
        mAvatarSize = getResources().getDimensionPixelSize(R.dimen.messaging_avatar_size);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mTextPaint.setAntiAlias(true);
        mConversationIcon = findViewById(R.id.conversation_icon);
        mConversationIconContainer = findViewById(R.id.conversation_icon_container);
        mIcon = findViewById(R.id.icon);
        mAppOps = findViewById(com.android.internal.R.id.app_ops);
        mMinTouchSize = 48 * getResources().getDisplayMetrics().density;
        mImportanceRingView = findViewById(R.id.conversation_icon_badge_ring);
        mConversationIconBadge = findViewById(R.id.conversation_icon_badge);
        mConversationIconBadgeBg = findViewById(R.id.conversation_icon_badge_bg);
        mIcon.setOnVisibilityChangedListener((visibility) -> {

            // Let's hide the background directly or in an animated way
            boolean isGone = visibility == GONE;
            int oldVisibility = mConversationIconBadgeBg.getVisibility();
            boolean wasGone = oldVisibility == GONE;
            if (wasGone != isGone) {
                // Keep the badge gone state in sync with the icon. This is necessary in cases
                // Where the icon is being hidden externally like in group children.
                mConversationIconBadgeBg.animate().cancel();
                mConversationIconBadgeBg.setVisibility(visibility);
            }

            // Let's handle the importance ring which can also be be gone normally
            oldVisibility = mImportanceRingView.getVisibility();
            wasGone = oldVisibility == GONE;
            visibility = !mImportantConversation ? GONE : visibility;
            isGone = visibility == GONE;
            if (wasGone != isGone) {
                // Keep the badge visibility in sync with the icon. This is necessary in cases
                // Where the icon is being hidden externally like in group children.
                mImportanceRingView.animate().cancel();
                mImportanceRingView.setVisibility(visibility);
            }
        });
        // When the small icon is gone, hide the rest of the badge
        mIcon.setOnForceHiddenChangedListener((forceHidden) -> {
            animateViewForceHidden(mConversationIconBadgeBg, forceHidden);
            animateViewForceHidden(mImportanceRingView, forceHidden);
        });

        // When the conversation icon is gone, hide the whole badge
        mConversationIcon.setOnForceHiddenChangedListener((forceHidden) -> {
            animateViewForceHidden(mConversationIconBadgeBg, forceHidden);
            animateViewForceHidden(mImportanceRingView, forceHidden);
            animateViewForceHidden(mIcon, forceHidden);
        });
        mConversationText = findViewById(R.id.conversation_text);
        mExpandButtonContainer = findViewById(R.id.expand_button_container);
        mConversationHeader = findViewById(R.id.conversation_header);
        mContentContainer = findViewById(R.id.notification_action_list_margin_target);
        mExpandButtonAndContentContainer = findViewById(R.id.expand_button_and_content_container);
        mExpandButtonInnerContainer = findViewById(R.id.expand_button_inner_container);
        mExpandButton = findViewById(R.id.expand_button);
        mExpandButtonExpandedTopMargin = getResources().getDimensionPixelSize(
                R.dimen.conversation_expand_button_top_margin_expanded);
        mNotificationHeaderExpandedPadding = getResources().getDimensionPixelSize(
                R.dimen.conversation_header_expanded_padding_end);
        mContentMarginEnd = getResources().getDimensionPixelSize(
                R.dimen.notification_content_margin_end);
        mBadgedSideMargins = getResources().getDimensionPixelSize(
                R.dimen.conversation_badge_side_margin);
        mConversationAvatarSize = getResources().getDimensionPixelSize(
                R.dimen.conversation_avatar_size);
        mConversationAvatarSizeExpanded = getResources().getDimensionPixelSize(
                R.dimen.conversation_avatar_size_group_expanded);
        mConversationIconTopPadding = getResources().getDimensionPixelSize(
                R.dimen.conversation_icon_container_top_padding);
        mConversationIconTopPaddingExpandedGroup = getResources().getDimensionPixelSize(
                R.dimen.conversation_icon_container_top_padding_small_avatar);
        mConversationIconTopPaddingNoAppName = getResources().getDimensionPixelSize(
                R.dimen.conversation_icon_container_top_padding_no_app_name);
        mExpandedGroupMessagePaddingNoAppName = getResources().getDimensionPixelSize(
                R.dimen.expanded_group_conversation_message_padding_without_app_name);
        mExpandedGroupSideMargin = getResources().getDimensionPixelSize(
                R.dimen.conversation_badge_side_margin_group_expanded);
        mExpandedGroupSideMarginFacePile = getResources().getDimensionPixelSize(
                R.dimen.conversation_badge_side_margin_group_expanded_face_pile);
        mConversationFacePile = findViewById(R.id.conversation_face_pile);
        mFacePileAvatarSize = getResources().getDimensionPixelSize(
                R.dimen.conversation_face_pile_avatar_size);
        mFacePileAvatarSizeExpandedGroup = getResources().getDimensionPixelSize(
                R.dimen.conversation_face_pile_avatar_size_group_expanded);
        mFacePileProtectionWidth = getResources().getDimensionPixelSize(
                R.dimen.conversation_face_pile_protection_width);
        mFacePileProtectionWidthExpanded = getResources().getDimensionPixelSize(
                R.dimen.conversation_face_pile_protection_width_expanded);
        mFallbackChatName = getResources().getString(
                R.string.conversation_title_fallback_one_to_one);
        mFallbackGroupChatName = getResources().getString(
                R.string.conversation_title_fallback_group_chat);
        mAppName = findViewById(R.id.app_name_text);
        mAppNameGone = mAppName.getVisibility() == GONE;
        mAppName.setOnVisibilityChangedListener((visibility) -> {
            onAppNameVisibilityChanged();
        });
        mUnreadBadge = findViewById(R.id.conversation_unread_count);
        mConversationContentStart = getResources().getDimensionPixelSize(
                R.dimen.conversation_content_start);
        mInternalButtonPadding
                = getResources().getDimensionPixelSize(R.dimen.button_padding_horizontal_material)
                + getResources().getDimensionPixelSize(R.dimen.button_inset_horizontal_material);
    }

    private void animateViewForceHidden(CachingIconView view, boolean forceHidden) {
        boolean nowForceHidden = view.willBeForceHidden() || view.isForceHidden();
        if (forceHidden == nowForceHidden) {
            // We are either already forceHidden or will be
            return;
        }
        view.animate().cancel();
        view.setWillBeForceHidden(forceHidden);
        view.animate()
                .scaleX(forceHidden ? 0.5f : 1.0f)
                .scaleY(forceHidden ? 0.5f : 1.0f)
                .alpha(forceHidden ? 0.0f : 1.0f)
                .setInterpolator(forceHidden ? ALPHA_OUT : ALPHA_IN)
                .setDuration(160);
        if (view.getVisibility() != VISIBLE) {
            view.setForceHidden(forceHidden);
        } else {
            view.animate().withEndAction(() -> view.setForceHidden(forceHidden));
        }
        view.animate().start();
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
     * Sets this conversation as "important", adding some additional UI treatment.
     */
    @RemotableViewMethod
    public void setIsImportantConversation(boolean isImportantConversation) {
        mImportantConversation = isImportantConversation;
        mImportanceRingView.setVisibility(isImportantConversation
                && mIcon.getVisibility() != GONE ? VISIBLE : GONE);
    }

    public boolean isImportantConversation() {
        return mImportantConversation;
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
        updateContentEndPaddings();
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

        int unreadCount = extras.getInt(Notification.EXTRA_CONVERSATION_UNREAD_MESSAGE_COUNT);
        setUnreadCount(unreadCount);
    }

    @Override
    public void setImageResolver(ImageResolver resolver) {
        mImageResolver = resolver;
    }

    /** @hide */
    public void setUnreadCount(int unreadCount) {
        mUnreadBadge.setVisibility(mIsCollapsed && unreadCount > 1 ? VISIBLE : GONE);
        CharSequence text = unreadCount >= 100
                ? getResources().getString(R.string.unread_convo_overflow, 99)
                : String.format(Locale.getDefault(), "%d", unreadCount);
        mUnreadBadge.setText(text);
        mUnreadBadge.setBackgroundTintList(ColorStateList.valueOf(mLayoutColor));
        boolean needDarkText = ColorUtils.calculateLuminance(mLayoutColor) > 0.5f;
        mUnreadBadge.setTextColor(needDarkText ? Color.BLACK : Color.WHITE);
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

        updateConversationLayout();
    }

    /**
     * Update the layout according to the data provided (i.e mIsOneToOne, expanded etc);
     */
    private void updateConversationLayout() {
        // Set avatar and name
        CharSequence conversationText = mConversationTitle;
        if (mIsOneToOne) {
            // Let's resolve the icon / text from the last sender
            mConversationIcon.setVisibility(VISIBLE);
            mConversationFacePile.setVisibility(GONE);
            CharSequence userKey = getKey(mUser);
            for (int i = mGroups.size() - 1; i >= 0; i--) {
                MessagingGroup messagingGroup = mGroups.get(i);
                Person messageSender = messagingGroup.getSender();
                if ((messageSender != null && !TextUtils.equals(userKey, getKey(messageSender)))
                        || i == 0) {
                    if (TextUtils.isEmpty(conversationText)) {
                        // We use the sendername as header text if no conversation title is provided
                        // (This usually happens for most 1:1 conversations)
                        conversationText = messagingGroup.getSenderName();
                    }
                    Icon avatarIcon = messagingGroup.getAvatarIcon();
                    if (avatarIcon == null) {
                        avatarIcon = createAvatarSymbol(conversationText, "", mLayoutColor);
                    }
                    mConversationIcon.setImageIcon(avatarIcon);
                    break;
                }
            }
        } else {
            if (mLargeIcon != null) {
                mConversationIcon.setVisibility(VISIBLE);
                mConversationFacePile.setVisibility(GONE);
                mConversationIcon.setImageIcon(mLargeIcon);
            } else {
                mConversationIcon.setVisibility(GONE);
                // This will also inflate it!
                mConversationFacePile.setVisibility(VISIBLE);
                // rebind the value to the inflated view instead of the stub
                mConversationFacePile = findViewById(R.id.conversation_face_pile);
                bindFacePile();
            }
        }
        if (TextUtils.isEmpty(conversationText)) {
            conversationText = mIsOneToOne ? mFallbackChatName : mFallbackGroupChatName;
        }
        mConversationText.setText(conversationText);
        // Update if the groups can hide the sender if they are first (applies to 1:1 conversations)
        // This needs to happen after all of the above o update all of the groups
        for (int i = mGroups.size() - 1; i >= 0; i--) {
            MessagingGroup messagingGroup = mGroups.get(i);
            CharSequence messageSender = messagingGroup.getSenderName();
            boolean canHide = mIsOneToOne
                    && TextUtils.equals(conversationText, messageSender);
            messagingGroup.setCanHideSenderIfFirst(canHide);
        }
        updateAppName();
        updateIconPositionAndSize();
        updateImageMessages();
        updatePaddingsBasedOnContentAvailability();
        updateActionListPadding();
    }

    private void updateActionListPadding() {
        if (mActions == null) {
            return;
        }
        View firstAction = mActions.getChildAt(0);
        if (firstAction != null) {
            // Let's visually position the first action where the content starts
            int paddingStart = mConversationContentStart;

            MarginLayoutParams layoutParams = (MarginLayoutParams) firstAction.getLayoutParams();
            paddingStart -= layoutParams.getMarginStart();
            paddingStart -= mInternalButtonPadding;

            mActions.setPaddingRelative(paddingStart,
                    mActions.getPaddingTop(),
                    mActions.getPaddingEnd(),
                    mActions.getPaddingBottom());
        }
    }

    private void updateImageMessages() {
        boolean displayExternalImage = false;
        ArraySet<View> newMessages = new ArraySet<>();
        if (mIsCollapsed) {

            // When collapsed, we're displaying all image messages in a dedicated container
            // on the right of the layout instead of inline. Let's add all isolated images there
            int imageIndex = 0;
            for (int i = 0; i < mGroups.size(); i++) {
                MessagingGroup messagingGroup = mGroups.get(i);
                MessagingImageMessage isolatedMessage = messagingGroup.getIsolatedMessage();
                if (isolatedMessage != null) {
                    newMessages.add(isolatedMessage.getView());
                    displayExternalImage = true;
                    if (imageIndex
                            != mImageMessageContainer.indexOfChild(isolatedMessage.getView())) {
                        mImageMessageContainer.removeView(isolatedMessage.getView());
                        mImageMessageContainer.addView(isolatedMessage.getView(), imageIndex);
                    }
                    imageIndex++;
                }
            }
        }
        // Remove all messages that don't belong into the image layout
        for (int i = 0; i < mImageMessageContainer.getChildCount(); i++) {
            View child = mImageMessageContainer.getChildAt(i);
            if (!newMessages.contains(child)) {
                mImageMessageContainer.removeView(child);
                i--;
            }
        }
        mImageMessageContainer.setVisibility(displayExternalImage ? VISIBLE : GONE);
    }

    private void bindFacePile() {
        // Let's bind the face pile
        ImageView bottomBackground = mConversationFacePile.findViewById(
                R.id.conversation_face_pile_bottom_background);
        applyNotificationBackgroundColor(bottomBackground);
        ImageView bottomView = mConversationFacePile.findViewById(
                R.id.conversation_face_pile_bottom);
        ImageView topView = mConversationFacePile.findViewById(
                R.id.conversation_face_pile_top);
        // Let's find the two last conversations:
        Icon secondLastIcon = null;
        CharSequence lastKey = null;
        Icon lastIcon = null;
        CharSequence userKey = getKey(mUser);
        for (int i = mGroups.size() - 1; i >= 0; i--) {
            MessagingGroup messagingGroup = mGroups.get(i);
            Person messageSender = messagingGroup.getSender();
            boolean notUser = messageSender != null
                    && !TextUtils.equals(userKey, getKey(messageSender));
            boolean notIncluded = messageSender != null
                    && !TextUtils.equals(lastKey, getKey(messageSender));
            if ((notUser && notIncluded)
                    || (i == 0 && lastKey == null)) {
                if (lastIcon == null) {
                    lastIcon = messagingGroup.getAvatarIcon();
                    lastKey = getKey(messageSender);
                } else {
                    secondLastIcon = messagingGroup.getAvatarIcon();
                    break;
                }
            }
        }
        if (lastIcon == null) {
            lastIcon = createAvatarSymbol(" ", "", mLayoutColor);
        }
        bottomView.setImageIcon(lastIcon);
        if (secondLastIcon == null) {
            secondLastIcon = createAvatarSymbol("", "", mLayoutColor);
        }
        topView.setImageIcon(secondLastIcon);

        int conversationAvatarSize;
        int facepileAvatarSize;
        int facePileBackgroundSize;
        if (mIsCollapsed) {
            conversationAvatarSize = mConversationAvatarSize;
            facepileAvatarSize = mFacePileAvatarSize;
            facePileBackgroundSize = facepileAvatarSize + 2 * mFacePileProtectionWidth;
        } else {
            conversationAvatarSize = mConversationAvatarSizeExpanded;
            facepileAvatarSize = mFacePileAvatarSizeExpandedGroup;
            facePileBackgroundSize = facepileAvatarSize + 2 * mFacePileProtectionWidthExpanded;
        }
        LayoutParams layoutParams = (LayoutParams) mConversationIcon.getLayoutParams();
        layoutParams.width = conversationAvatarSize;
        layoutParams.height = conversationAvatarSize;
        mConversationFacePile.setLayoutParams(layoutParams);

        layoutParams = (LayoutParams) bottomView.getLayoutParams();
        layoutParams.width = facepileAvatarSize;
        layoutParams.height = facepileAvatarSize;
        bottomView.setLayoutParams(layoutParams);

        layoutParams = (LayoutParams) topView.getLayoutParams();
        layoutParams.width = facepileAvatarSize;
        layoutParams.height = facepileAvatarSize;
        topView.setLayoutParams(layoutParams);

        layoutParams = (LayoutParams) bottomBackground.getLayoutParams();
        layoutParams.width = facePileBackgroundSize;
        layoutParams.height = facePileBackgroundSize;
        bottomBackground.setLayoutParams(layoutParams);
    }

    private void updateAppName() {
        mAppName.setVisibility(mIsCollapsed ? GONE : VISIBLE);
    }

    public boolean shouldHideAppName() {
        return mIsCollapsed;
    }

    /**
     * update the icon position and sizing
     */
    private void updateIconPositionAndSize() {
        int sidemargin;
        int conversationAvatarSize;
        if (mIsOneToOne || mIsCollapsed) {
            sidemargin = mBadgedSideMargins;
            conversationAvatarSize = mConversationAvatarSize;
        } else {
            sidemargin = mConversationFacePile.getVisibility() == VISIBLE
                    ? mExpandedGroupSideMarginFacePile
                    : mExpandedGroupSideMargin;
            conversationAvatarSize = mConversationAvatarSizeExpanded;
        }
        LayoutParams layoutParams =
                (LayoutParams) mConversationIconBadge.getLayoutParams();
        layoutParams.topMargin = sidemargin;
        layoutParams.setMarginStart(sidemargin);
        mConversationIconBadge.setLayoutParams(layoutParams);

        if (mConversationIcon.getVisibility() == VISIBLE) {
            layoutParams = (LayoutParams) mConversationIcon.getLayoutParams();
            layoutParams.width = conversationAvatarSize;
            layoutParams.height = conversationAvatarSize;
            mConversationIcon.setLayoutParams(layoutParams);
        }
    }

    private void updatePaddingsBasedOnContentAvailability() {
        int containerTopPadding;
        int messagingPadding = 0;
        if (mIsOneToOne || mIsCollapsed) {
            containerTopPadding = mConversationIconTopPadding;
        } else {
            if (mAppName.getVisibility() != GONE) {
                // The app name is visible, let's center outselves in the two lines
                containerTopPadding = mConversationIconTopPaddingExpandedGroup;
            } else {
                // App name is gone, let's center ourselves int he one remaining line
                containerTopPadding = mConversationIconTopPaddingNoAppName;

                // The app name is gone and we're a group, we'll need to add some extra padding
                // to the messages, since otherwise it will overlap with the group
                messagingPadding = mExpandedGroupMessagePaddingNoAppName;
            }
        }

        mConversationIconContainer.setPaddingRelative(
                mConversationIconContainer.getPaddingStart(),
                containerTopPadding,
                mConversationIconContainer.getPaddingEnd(),
                mConversationIconContainer.getPaddingBottom());

        mMessagingLinearLayout.setPaddingRelative(
                mMessagingLinearLayout.getPaddingStart(),
                messagingPadding,
                mMessagingLinearLayout.getPaddingEnd(),
                mMessagingLinearLayout.getPaddingBottom());
    }

    @RemotableViewMethod
    public void setLargeIcon(Icon largeIcon) {
        mLargeIcon = largeIcon;
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
        mConversationText.setTextColor(color);
    }

    /**
     * @param color the color of the notification background
     */
    @RemotableViewMethod
    public void setNotificationBackgroundColor(int color) {
        mNotificationBackgroundColor = color;
        applyNotificationBackgroundColor(mConversationIconBadgeBg);
    }

    private void applyNotificationBackgroundColor(ImageView view) {
        view.setImageTintList(ColorStateList.valueOf(mNotificationBackgroundColor));
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
            newGroup.setImageDisplayLocation(mIsCollapsed
                    ? IMAGE_DISPLAY_LOCATION_EXTERNAL
                    : IMAGE_DISPLAY_LOCATION_INLINE);
            newGroup.setIsInConversation(true);
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
            CharSequence key = getKey(sender);
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

    private CharSequence getKey(Person person) {
        return person == null ? null : person.getKey() == null ? person.getName() : person.getKey();
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
        if (mAppOps.getWidth() > 0) {

            // Let's increase the touch size of the app ops view if it's here
            mAppOpsTouchRect.set(
                    mAppOps.getLeft(),
                    mAppOps.getTop(),
                    mAppOps.getRight(),
                    mAppOps.getBottom());
            for (int i = 0; i < mAppOps.getChildCount(); i++) {
                View child = mAppOps.getChildAt(i);
                if (child.getVisibility() == GONE) {
                    continue;
                }
                // Make sure each child has at least a minTouchSize touch target around it
                float childTouchLeft = child.getLeft() + child.getWidth() / 2.0f
                        - mMinTouchSize / 2.0f;
                float childTouchRight = childTouchLeft + mMinTouchSize;
                mAppOpsTouchRect.left = (int) Math.min(mAppOpsTouchRect.left,
                        mAppOps.getLeft() + childTouchLeft);
                mAppOpsTouchRect.right = (int) Math.max(mAppOpsTouchRect.right,
                        mAppOps.getLeft() + childTouchRight);
            }

            // Increase the height
            int heightIncrease = 0;
            if (mAppOpsTouchRect.height() < mMinTouchSize) {
                heightIncrease = (int) Math.ceil((mMinTouchSize - mAppOpsTouchRect.height())
                        / 2.0f);
            }
            mAppOpsTouchRect.inset(0, -heightIncrease);

            // Let's adjust the hitrect since app ops isn't a direct child
            ViewGroup viewGroup = (ViewGroup) mAppOps.getParent();
            while (viewGroup != this) {
                mAppOpsTouchRect.offset(viewGroup.getLeft(), viewGroup.getTop());
                viewGroup = (ViewGroup) viewGroup.getParent();
            }
            //
            // Extend the size of the app opps to be at least 48dp
            setTouchDelegate(new TouchDelegate(mAppOpsTouchRect, mAppOps));
        }
    }

    public MessagingLinearLayout getMessagingLinearLayout() {
        return mMessagingLinearLayout;
    }

    public @NonNull ViewGroup getImageMessageContainer() {
        return mImageMessageContainer;
    }

    public ArrayList<MessagingGroup> getMessagingGroups() {
        return mGroups;
    }

    private void updateExpandButton() {
        int drawableId;
        int contentDescriptionId;
        int gravity;
        int topMargin = 0;
        ViewGroup newContainer;
        if (mIsCollapsed) {
            drawableId = R.drawable.ic_expand_notification;
            contentDescriptionId = R.string.expand_button_content_description_collapsed;
            gravity = Gravity.CENTER;
            newContainer = mExpandButtonAndContentContainer;
        } else {
            drawableId = R.drawable.ic_collapse_notification;
            contentDescriptionId = R.string.expand_button_content_description_expanded;
            gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
            topMargin = mExpandButtonExpandedTopMargin;
            newContainer = this;
        }
        mExpandButton.setImageDrawable(getContext().getDrawable(drawableId));
        mExpandButton.setColorFilter(mExpandButton.getOriginalNotificationColor());

        // We need to make sure that the expand button is in the linearlayout pushing over the
        // content when collapsed, but allows the content to flow under it when expanded.
        if (newContainer != mExpandButtonContainer.getParent()) {
            ((ViewGroup) mExpandButtonContainer.getParent()).removeView(mExpandButtonContainer);
            newContainer.addView(mExpandButtonContainer);
        }

        // update if the expand button is centered
        LinearLayout.LayoutParams layoutParams =
                (LinearLayout.LayoutParams) mExpandButton.getLayoutParams();
        layoutParams.gravity = gravity;
        layoutParams.topMargin = topMargin;
        mExpandButton.setLayoutParams(layoutParams);

        mExpandButtonInnerContainer.setContentDescription(mContext.getText(contentDescriptionId));
    }

    private void updateContentEndPaddings() {

        // Let's make sure the conversation header can't run into the expand button when we're
        // collapsed and update the paddings of the content
        int headerPaddingEnd;
        int contentPaddingEnd;
        if (!mExpandable) {
            headerPaddingEnd = 0;
            contentPaddingEnd = mContentMarginEnd;
        } else if (mIsCollapsed) {
            headerPaddingEnd = 0;
            contentPaddingEnd = 0;
        } else {
            headerPaddingEnd = mNotificationHeaderExpandedPadding;
            contentPaddingEnd = mContentMarginEnd;
        }
        mConversationHeader.setPaddingRelative(
                mConversationHeader.getPaddingStart(),
                mConversationHeader.getPaddingTop(),
                headerPaddingEnd,
                mConversationHeader.getPaddingBottom());

        mContentContainer.setPaddingRelative(
                mContentContainer.getPaddingStart(),
                mContentContainer.getPaddingTop(),
                contentPaddingEnd,
                mContentContainer.getPaddingBottom());
    }

    private void onAppNameVisibilityChanged() {
        boolean appNameGone = mAppName.getVisibility() == GONE;
        if (appNameGone != mAppNameGone) {
            mAppNameGone = appNameGone;
            updatePaddingsBasedOnContentAvailability();
        }
    }

    public void updateExpandability(boolean expandable, @Nullable OnClickListener onClickListener) {
        mExpandable = expandable;
        if (expandable) {
            mExpandButtonContainer.setVisibility(VISIBLE);
            mExpandButtonInnerContainer.setOnClickListener(onClickListener);
        } else {
            // TODO: handle content paddings to end of layout
            mExpandButtonContainer.setVisibility(GONE);
        }
        updateContentEndPaddings();
    }

    @Override
    public void setMessagingClippingDisabled(boolean clippingDisabled) {
        mMessagingLinearLayout.setClipBounds(clippingDisabled ? null : mMessagingClipRect);
    }
}
