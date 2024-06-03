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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.annotation.AttrRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StyleRes;
import android.app.Notification;
import android.app.Person;
import android.app.RemoteInputHistoryItem;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
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
import android.widget.flags.Flags;

import com.android.internal.R;
import com.android.internal.widget.ConversationAvatarData.GroupConversationAvatarData;
import com.android.internal.widget.ConversationAvatarData.OneToOneConversationAvatarData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A custom-built layout for the Notification.MessagingStyle allows dynamic addition and removal
 * messages and adapts the layout accordingly.
 */
@RemoteViews.RemoteView
public class ConversationLayout extends FrameLayout
        implements ImageMessageConsumer, IMessagingLayout {

    public static final Interpolator LINEAR_OUT_SLOW_IN = new PathInterpolator(0f, 0f, 0.2f, 1f);
    public static final Interpolator FAST_OUT_LINEAR_IN = new PathInterpolator(0.4f, 0f, 1f, 1f);
    public static final Interpolator FAST_OUT_SLOW_IN = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    public static final Interpolator OVERSHOOT = new PathInterpolator(0.4f, 0f, 0.2f, 1.4f);
    public static final OnLayoutChangeListener MESSAGING_PROPERTY_ANIMATOR
            = new MessagingPropertyAnimator();
    public static final int IMPORTANCE_ANIM_GROW_DURATION = 250;
    public static final int IMPORTANCE_ANIM_SHRINK_DURATION = 200;
    public static final int IMPORTANCE_ANIM_SHRINK_DELAY = 25;
    private final PeopleHelper mPeopleHelper = new PeopleHelper();
    private List<MessagingMessage> mMessages = new ArrayList<>();
    private List<MessagingMessage> mHistoricMessages = new ArrayList<>();
    private MessagingLinearLayout mMessagingLinearLayout;
    private boolean mShowHistoricMessages;
    private ArrayList<MessagingGroup> mGroups = new ArrayList<>();
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
    private CachingIconView mConversationIconView;
    private View mConversationIconContainer;
    private int mConversationIconTopPaddingExpandedGroup;
    private int mConversationIconTopPadding;
    private int mExpandedGroupMessagePadding;
    // TODO (b/217799515) Currently, mConversationText shows the conversation title, the actual
    //  conversation text is inside of mMessagingLinearLayout, which is misleading, we should rename
    //  this to mConversationTitleView
    private TextView mConversationText;
    private View mConversationIconBadge;
    private CachingIconView mConversationIconBadgeBg;
    private Icon mLargeIcon;
    private View mExpandButtonContainer;
    private ViewGroup mExpandButtonAndContentContainer;
    private ViewGroup mExpandButtonContainerA11yContainer;
    private NotificationExpandButton mExpandButton;
    private MessagingLinearLayout mImageMessageContainer;
    private int mBadgeProtrusion;
    private int mConversationAvatarSize;
    private int mConversationAvatarSizeExpanded;
    private CachingIconView mIcon;
    private CachingIconView mImportanceRingView;
    private int mExpandedGroupBadgeProtrusion;
    private int mExpandedGroupBadgeProtrusionFacePile;
    private View mConversationFacePile;
    private int mNotificationBackgroundColor;
    private CharSequence mFallbackChatName;
    private CharSequence mFallbackGroupChatName;
    //TODO (b/217799515) Currently, Notification.MessagingStyle, ConversationLayout, and
    // HybridConversationNotificationView, each has their own definition of "ConversationTitle".
    // What make things worse is that the term of "ConversationTitle" often confuses with
    // "ConversationText".
    // We need to unify them or differentiate the namings.
    private CharSequence mConversationTitle;
    private int mMessageSpacingStandard;
    private int mMessageSpacingGroup;
    private int mNotificationHeaderExpandedPadding;
    private View mConversationHeader;
    private View mContentContainer;
    private boolean mExpandable = true;
    private int mContentMarginEnd;
    private Rect mMessagingClipRect;
    private ObservableTextView mAppName;
    private NotificationActionListLayout mActions;
    private boolean mAppNameGone;
    private int mFacePileAvatarSize;
    private int mFacePileAvatarSizeExpandedGroup;
    private int mFacePileProtectionWidth;
    private int mFacePileProtectionWidthExpanded;
    private boolean mImportantConversation;
    private View mFeedbackIcon;
    private float mMinTouchSize;
    private Icon mConversationIcon;
    private Icon mShortcutIcon;
    private View mAppNameDivider;
    private TouchDelegateComposite mTouchDelegate = new TouchDelegateComposite(this);
    private ArrayList<MessagingLinearLayout.MessagingChild> mToRecycle = new ArrayList<>();
    private boolean mPrecomputedTextEnabled = false;
    @Nullable
    private ConversationHeaderData mConversationHeaderData;

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
        mPeopleHelper.init(getContext());
        mMessagingLinearLayout = findViewById(R.id.notification_messaging);
        mActions = findViewById(R.id.actions);
        mImageMessageContainer = findViewById(R.id.conversation_image_message_container);
        // We still want to clip, but only on the top, since views can temporarily out of bounds
        // during transitions.
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        int size = Math.max(displayMetrics.widthPixels, displayMetrics.heightPixels);
        mMessagingClipRect = new Rect(0, 0, size, size);
        setMessagingClippingDisabled(false);
        mConversationIconView = findViewById(R.id.conversation_icon);
        mConversationIconContainer = findViewById(R.id.conversation_icon_container);
        mIcon = findViewById(R.id.icon);
        mFeedbackIcon = findViewById(com.android.internal.R.id.feedback);
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
            boolean isRingGone = visibility == GONE;
            if (wasGone != isRingGone) {
                // Keep the badge visibility in sync with the icon. This is necessary in cases
                // Where the icon is being hidden externally like in group children.
                mImportanceRingView.animate().cancel();
                mImportanceRingView.setVisibility(visibility);
            }

            oldVisibility = mConversationIconBadge.getVisibility();
            wasGone = oldVisibility == GONE;
            if (wasGone != isGone) {
                mConversationIconBadge.animate().cancel();
                mConversationIconBadge.setVisibility(visibility);
            }
        });
        // When the small icon is gone, hide the rest of the badge
        mIcon.setOnForceHiddenChangedListener((forceHidden) -> {
            mPeopleHelper.animateViewForceHidden(mConversationIconBadgeBg, forceHidden);
            mPeopleHelper.animateViewForceHidden(mImportanceRingView, forceHidden);
        });

        // When the conversation icon is gone, hide the whole badge
        mConversationIconView.setOnForceHiddenChangedListener((forceHidden) -> {
            mPeopleHelper.animateViewForceHidden(mConversationIconBadgeBg, forceHidden);
            mPeopleHelper.animateViewForceHidden(mImportanceRingView, forceHidden);
            mPeopleHelper.animateViewForceHidden(mIcon, forceHidden);
        });
        mConversationText = findViewById(R.id.conversation_text);
        mExpandButtonContainer = findViewById(R.id.expand_button_container);
        mExpandButtonContainerA11yContainer =
                findViewById(R.id.expand_button_a11y_container);
        mConversationHeader = findViewById(R.id.conversation_header);
        mContentContainer = findViewById(R.id.notification_action_list_margin_target);
        mExpandButtonAndContentContainer = findViewById(R.id.expand_button_and_content_container);
        mExpandButton = findViewById(R.id.expand_button);
        mMessageSpacingStandard = getResources().getDimensionPixelSize(
                R.dimen.notification_messaging_spacing);
        mMessageSpacingGroup = getResources().getDimensionPixelSize(
                R.dimen.notification_messaging_spacing_conversation_group);
        mNotificationHeaderExpandedPadding = getResources().getDimensionPixelSize(
                R.dimen.conversation_header_expanded_padding_end);
        mContentMarginEnd = getResources().getDimensionPixelSize(
                R.dimen.notification_content_margin_end);
        mBadgeProtrusion = getResources().getDimensionPixelSize(
                R.dimen.conversation_badge_protrusion);
        mConversationAvatarSize = getResources().getDimensionPixelSize(
                R.dimen.conversation_avatar_size);
        mConversationAvatarSizeExpanded = getResources().getDimensionPixelSize(
                R.dimen.conversation_avatar_size_group_expanded);
        mConversationIconTopPaddingExpandedGroup = getResources().getDimensionPixelSize(
                R.dimen.conversation_icon_container_top_padding_small_avatar);
        mConversationIconTopPadding = getResources().getDimensionPixelSize(
                R.dimen.conversation_icon_container_top_padding);
        mExpandedGroupMessagePadding = getResources().getDimensionPixelSize(
                R.dimen.expanded_group_conversation_message_padding);
        mExpandedGroupBadgeProtrusion = getResources().getDimensionPixelSize(
                R.dimen.conversation_badge_protrusion_group_expanded);
        mExpandedGroupBadgeProtrusionFacePile = getResources().getDimensionPixelSize(
                R.dimen.conversation_badge_protrusion_group_expanded_face_pile);
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
        mAppNameDivider = findViewById(R.id.app_name_divider);
        mAppNameGone = mAppName.getVisibility() == GONE;
        mAppName.setOnVisibilityChangedListener((visibility) -> {
            onAppNameVisibilityChanged();
        });
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
        setIsImportantConversation(isImportantConversation, false);
    }

    /**
     * @hide
     **/
    public void setIsImportantConversation(boolean isImportantConversation, boolean animate) {
        mImportantConversation = isImportantConversation;
        mImportanceRingView.setVisibility(isImportantConversation && mIcon.getVisibility() != GONE
                ? VISIBLE : GONE);

        if (animate && isImportantConversation) {
            GradientDrawable ring = (GradientDrawable) mImportanceRingView.getDrawable();
            ring.mutate();
            GradientDrawable bg = (GradientDrawable) mConversationIconBadgeBg.getDrawable();
            bg.mutate();
            int ringColor = getResources()
                    .getColor(R.color.conversation_important_highlight);
            int standardThickness = getResources()
                    .getDimensionPixelSize(R.dimen.importance_ring_stroke_width);
            int largeThickness = getResources()
                    .getDimensionPixelSize(R.dimen.importance_ring_anim_max_stroke_width);
            int standardSize = getResources().getDimensionPixelSize(
                    R.dimen.importance_ring_size);
            int baseSize = standardSize - standardThickness * 2;
            int bgSize = getResources()
                    .getDimensionPixelSize(R.dimen.conversation_icon_size_badged);

            ValueAnimator.AnimatorUpdateListener animatorUpdateListener = animation -> {
                int strokeWidth = Math.round((float) animation.getAnimatedValue());
                ring.setStroke(strokeWidth, ringColor);
                int newSize = baseSize + strokeWidth * 2;
                ring.setSize(newSize, newSize);
                mImportanceRingView.invalidate();
            };

            ValueAnimator growAnimation = ValueAnimator.ofFloat(0, largeThickness);
            growAnimation.setInterpolator(LINEAR_OUT_SLOW_IN);
            growAnimation.setDuration(IMPORTANCE_ANIM_GROW_DURATION);
            growAnimation.addUpdateListener(animatorUpdateListener);

            ValueAnimator shrinkAnimation =
                    ValueAnimator.ofFloat(largeThickness, standardThickness);
            shrinkAnimation.setDuration(IMPORTANCE_ANIM_SHRINK_DURATION);
            shrinkAnimation.setStartDelay(IMPORTANCE_ANIM_SHRINK_DELAY);
            shrinkAnimation.setInterpolator(OVERSHOOT);
            shrinkAnimation.addUpdateListener(animatorUpdateListener);
            shrinkAnimation.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    // Shrink the badge bg so that it doesn't peek behind the animation
                    bg.setSize(baseSize, baseSize);
                    mConversationIconBadgeBg.invalidate();
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    // Reset bg back to normal size
                    bg.setSize(bgSize, bgSize);
                    mConversationIconBadgeBg.invalidate();
                }
            });

            AnimatorSet anims = new AnimatorSet();
            anims.playSequentially(growAnimation, shrinkAnimation);
            anims.start();
        }
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

    /**
     * Set conversation data
     *
     * @param extras Bundle contains conversation data
     */
    @RemotableViewMethod(asyncImpl = "setDataAsync")
    public void setData(Bundle extras) {
        bind(parseMessagingData(extras,
                /* usePrecomputedText= */ false,
                /*includeConversationIcon= */false));
    }

    @NonNull
    private MessagingData parseMessagingData(Bundle extras, boolean usePrecomputedText,
            boolean includeConversationIcon) {
        Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        List<Notification.MessagingStyle.Message> newMessages =
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(messages);
        Parcelable[] histMessages = extras.getParcelableArray(Notification.EXTRA_HISTORIC_MESSAGES);
        List<Notification.MessagingStyle.Message> newHistoricMessages =
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(histMessages);

        // mUser now set (would be nice to avoid the side effect but WHATEVER)
        final Person user = extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON, Person.class);
        // Append remote input history to newMessages (again, side effect is lame but WHATEVS)
        RemoteInputHistoryItem[] history = (RemoteInputHistoryItem[])
                extras.getParcelableArray(Notification.EXTRA_REMOTE_INPUT_HISTORY_ITEMS,
                        RemoteInputHistoryItem.class);
        addRemoteInputHistoryToMessages(newMessages, history);

        boolean showSpinner =
                extras.getBoolean(Notification.EXTRA_SHOW_REMOTE_INPUT_SPINNER, false);
        int unreadCount = extras.getInt(Notification.EXTRA_CONVERSATION_UNREAD_MESSAGE_COUNT);

        final List<MessagingMessage> newMessagingMessages =
                createMessages(newMessages, /* isHistoric= */false, usePrecomputedText);
        final List<MessagingMessage> newHistoricMessagingMessages =
                createMessages(newHistoricMessages, /* isHistoric= */true, usePrecomputedText);

        // Add our new MessagingMessages to groups
        List<List<MessagingMessage>> groups = new ArrayList<>();
        List<Person> senders = new ArrayList<>();
        // Lets first find the groups (populate `groups` and `senders`)
        findGroups(newHistoricMessagingMessages, newMessagingMessages, user, groups, senders);

        // load conversation header data, avatar and title.
        final ConversationHeaderData conversationHeaderData;
        if (includeConversationIcon && Flags.conversationStyleSetAvatarAsync()) {
            conversationHeaderData = loadConversationHeaderData(mIsOneToOne,
                    mConversationTitle,
                    mShortcutIcon,
                    mLargeIcon, newMessagingMessages, user, groups, mLayoutColor);
        } else {
            conversationHeaderData = null;
        }

        return new MessagingData(user, showSpinner, unreadCount,
                newHistoricMessagingMessages, newMessagingMessages, groups, senders,
                conversationHeaderData);
    }

    /**
     * RemotableViewMethod's asyncImpl of {@link #setData(Bundle)}.
     * This should be called on a background thread, and returns a Runnable which is then must be
     * called on the main thread to complete the operation and set text.
     *
     * @param extras Bundle contains conversation data
     * @hide
     */
    @NonNull
    public Runnable setDataAsync(Bundle extras) {
        if (!mPrecomputedTextEnabled) {
            return () -> setData(extras);
        }

        final MessagingData messagingData =
                parseMessagingData(extras,
                        /* usePrecomputedText= */ true,
                        /*includeConversationIcon=*/true);

        return () -> {
            finalizeInflate(messagingData.getHistoricMessagingMessages());
            finalizeInflate(messagingData.getNewMessagingMessages());

            bind(messagingData);
        };
    }

    /**
     * enable/disable precomputed text usage
     *
     * @hide
     */
    public void setPrecomputedTextEnabled(boolean precomputedTextEnabled) {
        mPrecomputedTextEnabled = precomputedTextEnabled;
    }

    private void finalizeInflate(List<MessagingMessage> historicMessagingMessages) {
        for (MessagingMessage messagingMessage : historicMessagingMessages) {
            messagingMessage.finalizeInflate();
        }
    }

    @Override
    public void setImageResolver(ImageResolver resolver) {
        mImageResolver = resolver;
    }

    /**
     * @hide
     */
    public void setUnreadCount(int unreadCount) {
        mExpandButton.setNumber(unreadCount);
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

    private void bind(MessagingData messagingData) {
        setUser(messagingData.getUser());
        setUnreadCount(messagingData.getUnreadCount());

        // Copy our groups, before they get clobbered
        ArrayList<MessagingGroup> oldGroups = new ArrayList<>(mGroups);

        // Let's now create the views and reorder them accordingly
        //   side-effect: updates mGroups, mAddedGroups
        createGroupViews(messagingData.getGroups(), messagingData.getSenders(),
                messagingData.getShowSpinner());

        // Let's first check which groups were removed altogether and remove them in one animation
        removeGroups(oldGroups);

        // Let's remove the remaining messages
        for (MessagingMessage message : mMessages) {
            message.removeMessage(mToRecycle);
        }
        for (MessagingMessage historicMessage : mHistoricMessages) {
            historicMessage.removeMessage(mToRecycle);
        }

        mMessages = messagingData.getNewMessagingMessages();
        mHistoricMessages = messagingData.getHistoricMessagingMessages();
        updateHistoricMessageVisibility();
        updateTitleAndNamesDisplay();

        updateConversationLayout(messagingData);

        // Recycle everything at the end of the update, now that we know it's no longer needed.
        for (MessagingLinearLayout.MessagingChild child : mToRecycle) {
            child.recycle();
        }
        mToRecycle.clear();
    }

    /**
     * Update the layout according to the data provided (i.e mIsOneToOne, expanded etc);
     */
    private void updateConversationLayout(MessagingData messagingData) {
        if (!Flags.conversationStyleSetAvatarAsync()) {
            computeAndSetConversationAvatarAndName();
        } else {
            ConversationHeaderData conversationHeaderData =
                    messagingData.getConversationHeaderData();
            if (conversationHeaderData == null) {
                conversationHeaderData = loadConversationHeaderData(mIsOneToOne,
                        mConversationTitle, mShortcutIcon, mLargeIcon, mMessages, mUser,
                        messagingData.getGroups(),
                        mLayoutColor);
            }
            setConversationAvatarAndNameFromData(conversationHeaderData);
        }

        updateAppName();
        updateIconPositionAndSize();
        updateImageMessages();
        updatePaddingsBasedOnContentAvailability();
        updateActionListPadding();
        updateAppNameDividerVisibility();
    }

    @Deprecated
    private void computeAndSetConversationAvatarAndName() {
        // Set avatar and name
        CharSequence conversationText = mConversationTitle;
        mConversationIcon = mShortcutIcon;
        if (mIsOneToOne) {
            // Let's resolve the icon / text from the last sender
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
                    if (mConversationIcon == null) {
                        Icon avatarIcon = messagingGroup.getAvatarIcon();
                        if (avatarIcon == null) {
                            avatarIcon = mPeopleHelper.createAvatarSymbol(conversationText, "",
                                    mLayoutColor);
                        }
                        mConversationIcon = avatarIcon;
                    }
                    break;
                }
            }
        }
        if (mConversationIcon == null) {
            mConversationIcon = mLargeIcon;
        }
        if (mIsOneToOne || mConversationIcon != null) {
            mConversationIconView.setVisibility(VISIBLE);
            mConversationFacePile.setVisibility(GONE);
            mConversationIconView.setImageIcon(mConversationIcon);
        } else {
            mConversationIconView.setVisibility(GONE);
            // This will also inflate it!
            mConversationFacePile.setVisibility(VISIBLE);
            // rebind the value to the inflated view instead of the stub
            mConversationFacePile = findViewById(R.id.conversation_face_pile);
            bindFacePile();
        }
        if (TextUtils.isEmpty(conversationText)) {
            conversationText = mIsOneToOne ? mFallbackChatName : mFallbackGroupChatName;
        }
        mConversationText.setText(conversationText);
        // Update if the groups can hide the sender if they are first (applies to 1:1 conversations)
        // This needs to happen after all of the above o update all of the groups
        mPeopleHelper.maybeHideFirstSenderName(mGroups, mIsOneToOne, conversationText);
    }

    private void setConversationAvatarAndNameFromData(
            ConversationHeaderData conversationHeaderData) {
        mConversationHeaderData = conversationHeaderData;
        final OneToOneConversationAvatarData oneToOneConversationDrawable;
        final GroupConversationAvatarData groupConversationAvatarData;
        final ConversationAvatarData conversationAvatar =
                conversationHeaderData.getConversationAvatar();
        if (conversationAvatar instanceof OneToOneConversationAvatarData) {
            oneToOneConversationDrawable =
                    ((OneToOneConversationAvatarData) conversationAvatar);
            groupConversationAvatarData = null;
        } else {
            oneToOneConversationDrawable = null;
            groupConversationAvatarData = ((GroupConversationAvatarData) conversationAvatar);
        }

        if (oneToOneConversationDrawable != null) {
            mConversationIconView.setVisibility(VISIBLE);
            mConversationFacePile.setVisibility(GONE);
            mConversationIconView.setImageDrawable(oneToOneConversationDrawable.mDrawable);
        } else {
            mConversationIconView.setVisibility(GONE);
            // This will also inflate it!
            mConversationFacePile.setVisibility(VISIBLE);
            // rebind the value to the inflated view instead of the stub
            mConversationFacePile = findViewById(R.id.conversation_face_pile);
            bindFacePile(groupConversationAvatarData);
        }
        CharSequence conversationText = conversationHeaderData.getConversationText();
        if (TextUtils.isEmpty(conversationText)) {
            conversationText = mIsOneToOne ? mFallbackChatName : mFallbackGroupChatName;
        }
        mConversationText.setText(conversationText);
        // Update if the groups can hide the sender if they are first (applies to 1:1 conversations)
        // This needs to happen after all of the above o update all of the groups
        mPeopleHelper.maybeHideFirstSenderName(mGroups, mIsOneToOne, conversationText);
    }

    private void updateActionListPadding() {
        if (mActions != null) {
            mActions.setCollapsibleIndentDimen(R.dimen.call_notification_collapsible_indent);
        }
    }

    private void updateImageMessages() {
        View newMessage = null;
        if (mIsCollapsed && mGroups.size() > 0) {

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
    }

    public void bindFacePile(ImageView bottomBackground, ImageView bottomView, ImageView topView) {
        applyNotificationBackgroundColor(bottomBackground);
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
            lastIcon = mPeopleHelper.createAvatarSymbol(" ", "", mLayoutColor);
        }
        bottomView.setImageIcon(lastIcon);
        if (secondLastIcon == null) {
            secondLastIcon = mPeopleHelper.createAvatarSymbol("", "", mLayoutColor);
        }
        topView.setImageIcon(secondLastIcon);
    }

    @Deprecated
    private void bindFacePile() {
        bindFacePile(null);
    }

    private void bindFacePile(@Nullable GroupConversationAvatarData groupConversationAvatarData) {
        ImageView bottomBackground = mConversationFacePile.findViewById(
                R.id.conversation_face_pile_bottom_background);
        ImageView bottomView = mConversationFacePile.findViewById(
                R.id.conversation_face_pile_bottom);
        ImageView topView = mConversationFacePile.findViewById(
                R.id.conversation_face_pile_top);

        if (groupConversationAvatarData == null) {
            bindFacePile(bottomBackground, bottomView, topView);
        } else {
            bindFacePileWithDrawable(bottomBackground, bottomView, topView,
                    groupConversationAvatarData);

        }

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
        LayoutParams layoutParams = (LayoutParams) mConversationFacePile.getLayoutParams();
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

    /**
     * Binds group avatar drawables to face pile.
     */
    public void bindFacePileWithDrawable(ImageView bottomBackground, ImageView bottomView,
            ImageView topView, GroupConversationAvatarData groupConversationAvatarData) {
        applyNotificationBackgroundColor(bottomBackground);
        bottomView.setImageDrawable(groupConversationAvatarData.mLastIcon);
        topView.setImageDrawable(groupConversationAvatarData.mSecondLastIcon);
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
        int badgeProtrusion;
        int conversationAvatarSize;
        if (mIsOneToOne || mIsCollapsed) {
            badgeProtrusion = mBadgeProtrusion;
            conversationAvatarSize = mConversationAvatarSize;
        } else {
            badgeProtrusion = mConversationFacePile.getVisibility() == VISIBLE
                    ? mExpandedGroupBadgeProtrusionFacePile
                    : mExpandedGroupBadgeProtrusion;
            conversationAvatarSize = mConversationAvatarSizeExpanded;
        }

        if (mConversationIconView.getVisibility() == VISIBLE) {
            LayoutParams layoutParams = (LayoutParams) mConversationIconView.getLayoutParams();
            layoutParams.width = conversationAvatarSize;
            layoutParams.height = conversationAvatarSize;
            layoutParams.leftMargin = badgeProtrusion;
            layoutParams.rightMargin = badgeProtrusion;
            layoutParams.bottomMargin = badgeProtrusion;
            mConversationIconView.setLayoutParams(layoutParams);
        }

        if (mConversationFacePile.getVisibility() == VISIBLE) {
            LayoutParams layoutParams = (LayoutParams) mConversationFacePile.getLayoutParams();
            layoutParams.leftMargin = badgeProtrusion;
            layoutParams.rightMargin = badgeProtrusion;
            layoutParams.bottomMargin = badgeProtrusion;
            mConversationFacePile.setLayoutParams(layoutParams);
        }
    }

    private void updatePaddingsBasedOnContentAvailability() {
        // groups have avatars that need more spacing
        mMessagingLinearLayout.setSpacing(
                mIsOneToOne ? mMessageSpacingStandard : mMessageSpacingGroup);

        int messagingPadding = mIsOneToOne || mIsCollapsed
                ? 0
                // Add some extra padding to the messages, since otherwise it will overlap with the
                // group
                : mExpandedGroupMessagePadding;

        int iconPadding = mIsOneToOne || mIsCollapsed
                ? mConversationIconTopPadding
                : mConversationIconTopPaddingExpandedGroup;

        mConversationIconContainer.setPaddingRelative(
                mConversationIconContainer.getPaddingStart(),
                iconPadding,
                mConversationIconContainer.getPaddingEnd(),
                mConversationIconContainer.getPaddingBottom());

        mMessagingLinearLayout.setPaddingRelative(
                mMessagingLinearLayout.getPaddingStart(),
                messagingPadding,
                mMessagingLinearLayout.getPaddingEnd(),
                mMessagingLinearLayout.getPaddingBottom());
    }

    /**
     * async version of {@link ConversationLayout#setLargeIcon}
     */
    @RemotableViewMethod
    public Runnable setLargeIconAsync(Icon largeIcon) {
        if (!Flags.conversationStyleSetAvatarAsync()) {
            return () -> setLargeIcon(largeIcon);
        }

        mLargeIcon = largeIcon;
        return NotificationRunnables.NOOP;
    }

    @RemotableViewMethod(asyncImpl = "setLargeIconAsync")
    public void setLargeIcon(Icon largeIcon) {
        mLargeIcon = largeIcon;
    }

    /**
     * async version of {@link ConversationLayout#setShortcutIcon}
     */
    @RemotableViewMethod
    public Runnable setShortcutIconAsync(Icon shortcutIcon) {
        if (!Flags.conversationStyleSetAvatarAsync()) {
            return () -> setShortcutIcon(shortcutIcon);
        }

        mShortcutIcon = shortcutIcon;
        return NotificationRunnables.NOOP;
    }

    @RemotableViewMethod(asyncImpl = "setShortcutIconAsync")
    public void setShortcutIcon(Icon shortcutIcon) {
        mShortcutIcon = shortcutIcon;
    }

    /**
     * async version of {@link ConversationLayout#setConversationTitle}
     */
    @RemotableViewMethod
    public Runnable setConversationTitleAsync(CharSequence conversationTitle) {
        if (!Flags.conversationStyleSetAvatarAsync()) {
            return () -> setConversationTitle(conversationTitle);
        }

        // Remove formatting from the title.
        mConversationTitle = conversationTitle != null ? conversationTitle.toString() : null;
        return NotificationRunnables.NOOP;
    }

    /**
     * Sets the conversation title of this conversation.
     *
     * @param conversationTitle the conversation title
     */
    @RemotableViewMethod(asyncImpl = "setConversationTitleAsync")
    public void setConversationTitle(CharSequence conversationTitle) {
        // Remove formatting from the title.
        mConversationTitle = conversationTitle != null ? conversationTitle.toString() : null;
    }

    // TODO (b/217799515) getConversationTitle is not consistent with setConversationTitle
    //  if you call getConversationTitle() immediately after setConversationTitle(), the result
    //  will not correctly reflect the new change without calling updateConversationLayout, for
    //  example.
    public CharSequence getConversationTitle() {
        return mConversationText.getText();
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
                    // Defer recycling until after the update is done, since we may still need the
                    // old group around to perform other updates.
                    mToRecycle.add(group);
                }
                mMessages.removeAll(messages);
                mHistoricMessages.removeAll(messages);
            }
        }
    }

    private void updateTitleAndNamesDisplay() {
        // Map of unique names to their prefix
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
                    cachedIcon = mPeopleHelper.createAvatarSymbol(senderName,
                            uniqueNames.get(senderName), mLayoutColor);
                    cachedAvatars.put(senderName, cachedIcon);
                }
                group.setCreatedAvatar(cachedIcon, senderName, uniqueNames.get(senderName),
                        mLayoutColor);
            }
        }
    }

    /**
     * async version of {@link ConversationLayout#setLayoutColor}
     */
    @RemotableViewMethod
    public Runnable setLayoutColorAsync(int color) {
        if (!Flags.conversationStyleSetAvatarAsync()) {
            return () -> setLayoutColor(color);
        }

        mLayoutColor = color;
        return NotificationRunnables.NOOP;
    }

    @RemotableViewMethod(asyncImpl = "setLayoutColorAsync")
    public void setLayoutColor(int color) {
        mLayoutColor = color;
    }

    /**
     * async version of {@link ConversationLayout#setIsOneToOne}
     */
    @RemotableViewMethod
    public Runnable setIsOneToOneAsync(boolean oneToOne) {
        if (!Flags.conversationStyleSetAvatarAsync()) {
            return () -> setIsOneToOne(oneToOne);
        }
        mIsOneToOne = oneToOne;
        return NotificationRunnables.NOOP;
    }

    @RemotableViewMethod(asyncImpl = "setIsOneToOneAsync")
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
            } else if (newGroup.getParent() != mMessagingLinearLayout) {
                throw new IllegalStateException(
                        "group parent was " + newGroup.getParent() + " but expected "
                                + mMessagingLinearLayout);
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

    /**
     * Finds groups and senders from the given messaging messages and fills outGroups and outSenders
     */
    private void findGroups(List<MessagingMessage> historicMessages,
            List<MessagingMessage> messages, Person user, List<List<MessagingMessage>> outGroups,
            List<Person> outSenders) {
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
            Person sender =
                    message.getMessage() == null ? null : message.getMessage().getSenderPerson();
            CharSequence key = getKey(sender);
            isNewGroup |= !TextUtils.equals(key, currentSenderKey);
            if (isNewGroup) {
                currentGroup = new ArrayList<>();
                outGroups.add(currentGroup);
                if (sender == null) {
                    sender = user;
                } else {
                    // Remove all formatting from the sender name
                    sender = sender.toBuilder().setName(Objects.toString(sender.getName())).build();
                }
                outSenders.add(sender);
                currentSenderKey = key;
            }
            currentGroup.add(message);
        }
    }

    private CharSequence getKey(Person person) {
        return person == null ? null : person.getKey() == null ? person.getName() : person.getKey();
    }

    private ConversationHeaderData loadConversationHeaderData(boolean isOneToOne,
            CharSequence conversationTitle, Icon shortcutIcon, Icon largeIcon,
            List<MessagingMessage> messages,
            Person user,
            List<List<MessagingMessage>> groups, int layoutColor) {
        Icon conversationIcon = shortcutIcon;
        CharSequence conversationText = conversationTitle;
        final CharSequence userKey = getKey(user);
        if (isOneToOne) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                final Notification.MessagingStyle.Message message = messages.get(i).getMessage();
                final Person sender = message.getSenderPerson();
                final CharSequence senderKey = getKey(sender);
                if ((sender != null && senderKey != userKey) || i == 0) {
                    if (conversationText == null || conversationText.length() == 0) {
                        conversationText = sender != null ? sender.getName() : "";
                    }
                    if (conversationIcon == null) {
                        conversationIcon = sender != null ? sender.getIcon()
                                : mPeopleHelper.createAvatarSymbol(conversationText, "",
                                        layoutColor);
                    }
                    break;
                }
            }
        }
        if (android.app.Flags.cleanUpSpansAndNewLines() && conversationText != null) {
            // remove formatting from title.
            conversationText = conversationText.toString();
        }

        if (conversationIcon == null) {
            conversationIcon = largeIcon;
        }

        if (isOneToOne || conversationIcon != null) {
            return new ConversationHeaderData(
                    conversationText,
                    new OneToOneConversationAvatarData(
                            resolveAvatarImageForOneToOne(conversationIcon)));
        }

        final List<List<Notification.MessagingStyle.Message>> groupMessages = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            final List<Notification.MessagingStyle.Message> groupMessage = new ArrayList<>();
            for (int j = 0; j < groups.get(i).size(); j++) {
                groupMessage.add(groups.get(i).get(j).getMessage());
            }
            groupMessages.add(groupMessage);
        }

        final PeopleHelper.NameToPrefixMap nameToPrefixMap =
                mPeopleHelper.mapUniqueNamesToPrefixWithGroupList(groupMessages);

        Icon lastIcon = null;
        Icon secondLastIcon = null;

        CharSequence lastKey = null;

        for (int i = groups.size() - 1; i >= 0; i--) {
            final Notification.MessagingStyle.Message message = groups.get(i).get(0).getMessage();
            final Person sender =
                    message.getSenderPerson() != null ? message.getSenderPerson() : user;
            final CharSequence senderKey = getKey(sender);
            final boolean notUser = senderKey != userKey;
            final boolean notIncluded = senderKey != lastKey;

            if ((notUser && notIncluded) || (i == 0 && lastKey == null)) {
                if (lastIcon == null) {
                    if (sender.getIcon() != null) {
                        lastIcon = sender.getIcon();
                    } else {
                        final CharSequence senderName =
                                sender.getName() != null ? sender.getName() : "";
                        lastIcon = mPeopleHelper.createAvatarSymbol(
                                senderName, nameToPrefixMap.getPrefix(senderName),
                                layoutColor);
                    }
                    lastKey = senderKey;
                } else {
                    if (sender.getIcon() != null) {
                        secondLastIcon = sender.getIcon();
                    } else {
                        final CharSequence senderName =
                                sender.getName() != null ? sender.getName() : "";
                        secondLastIcon = mPeopleHelper.createAvatarSymbol(
                                senderName, nameToPrefixMap.getPrefix(senderName),
                                layoutColor);
                    }
                    break;
                }
            }
        }

        if (lastIcon == null) {
            lastIcon = mPeopleHelper.createAvatarSymbol(
                    "", "", layoutColor);
        }

        if (secondLastIcon == null) {
            secondLastIcon = mPeopleHelper.createAvatarSymbol(
                    "", "", layoutColor);
        }

        return new ConversationHeaderData(
                conversationText,
                new GroupConversationAvatarData(resolveAvatarImageForFacePile(lastIcon),
                        resolveAvatarImageForFacePile(secondLastIcon)));
    }

    /**
     * One To One Conversation Avatars is loaded by CachingIconView(conversation icon view).
     */
    @Nullable
    private Drawable resolveAvatarImageForOneToOne(Icon conversationIcon) {
        final Drawable conversationIconDrawable =
                tryLoadingSizeRestrictedIconForOneToOne(conversationIcon);
        if (conversationIconDrawable != null) {
            return conversationIconDrawable;
        }
        // when size restricted icon loading fails, we fallback to icons load drawable.
        return loadDrawableFromIcon(conversationIcon);
    }

    @Nullable
    private Drawable tryLoadingSizeRestrictedIconForOneToOne(Icon conversationIcon) {
        try {
            return mConversationIconView.loadSizeRestrictedIcon(conversationIcon);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Group Avatar drawables are loaded by Icon.
     */
    @Nullable
    private Drawable resolveAvatarImageForFacePile(Icon conversationIcon) {
        return loadDrawableFromIcon(conversationIcon);
    }

    @Nullable
    private Drawable loadDrawableFromIcon(Icon conversationIcon) {
        try {
            return conversationIcon.loadDrawable(getContext());
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Creates new messages, reusing existing ones if they are available.
     *
     * @param newMessages the messages to parse.
     */
    private List<MessagingMessage> createMessages(
            List<Notification.MessagingStyle.Message> newMessages, boolean isHistoric,
            boolean usePrecomputedText) {
        List<MessagingMessage> result = new ArrayList<>();
        for (int i = 0; i < newMessages.size(); i++) {
            Notification.MessagingStyle.Message m = newMessages.get(i);
            MessagingMessage message = findAndRemoveMatchingMessage(m);
            if (message == null) {
                message = MessagingMessage.createMessage(this, m,
                        mImageResolver, usePrecomputedText);
            }
            message.setIsHistoric(isHistoric);
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
            } else if (visibleChildren == 0 && group.getVisibility() != GONE) {
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
        mTouchDelegate.clear();
        if (mFeedbackIcon.getVisibility() == VISIBLE) {
            float width = Math.max(mMinTouchSize, mFeedbackIcon.getWidth());
            float height = Math.max(mMinTouchSize, mFeedbackIcon.getHeight());
            final Rect feedbackTouchRect = new Rect();
            feedbackTouchRect.left = (int) ((mFeedbackIcon.getLeft() + mFeedbackIcon.getRight())
                    / 2.0f - width / 2.0f);
            feedbackTouchRect.top = (int) ((mFeedbackIcon.getTop() + mFeedbackIcon.getBottom())
                    / 2.0f - height / 2.0f);
            feedbackTouchRect.bottom = (int) (feedbackTouchRect.top + height);
            feedbackTouchRect.right = (int) (feedbackTouchRect.left + width);

            getRelativeTouchRect(feedbackTouchRect, mFeedbackIcon);
            mTouchDelegate.add(new TouchDelegate(feedbackTouchRect, mFeedbackIcon));
        }

        setTouchDelegate(mTouchDelegate);
    }

    private void getRelativeTouchRect(Rect touchRect, View view) {
        ViewGroup viewGroup = (ViewGroup) view.getParent();
        while (viewGroup != this) {
            touchRect.offset(viewGroup.getLeft(), viewGroup.getTop());
            viewGroup = (ViewGroup) viewGroup.getParent();
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
        int buttonGravity;
        ViewGroup newContainer;
        if (mIsCollapsed) {
            buttonGravity = Gravity.CENTER;
            // NOTE(b/182474419): In order for the touch target of the expand button to be the full
            // height of the notification, we would want the mExpandButtonContainer's height to be
            // set to WRAP_CONTENT (or 88dp) when in the collapsed state.  Unfortunately, that
            // causes an unstable remeasuring infinite loop when the unread count is visible,
            // causing the layout to occasionally hide the messages.  As an aside, that naive
            // solution also causes an undesirably large gap between content and smart replies.
            newContainer = mExpandButtonAndContentContainer;
        } else {
            buttonGravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
            newContainer = mExpandButtonContainerA11yContainer;
        }
        mExpandButton.setExpanded(!mIsCollapsed);

        // We need to make sure that the expand button is in the linearlayout pushing over the
        // content when collapsed, but allows the content to flow under it when expanded.
        if (newContainer != mExpandButtonContainer.getParent()) {
            ((ViewGroup) mExpandButtonContainer.getParent()).removeView(mExpandButtonContainer);
            newContainer.addView(mExpandButtonContainer);
        }

        // update if the expand button is centered
        LinearLayout.LayoutParams layoutParams =
                (LinearLayout.LayoutParams) mExpandButton.getLayoutParams();
        layoutParams.gravity = buttonGravity;
        mExpandButton.setLayoutParams(layoutParams);
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
            updateAppNameDividerVisibility();
        }
    }

    private void updateAppNameDividerVisibility() {
        mAppNameDivider.setVisibility(mAppNameGone ? GONE : VISIBLE);
    }

    public void updateExpandability(boolean expandable, @Nullable OnClickListener onClickListener) {
        mExpandable = expandable;
        if (expandable) {
            mExpandButtonContainer.setVisibility(VISIBLE);
            mExpandButton.setOnClickListener(onClickListener);
            mConversationIconContainer.setOnClickListener(onClickListener);
        } else {
            mExpandButtonContainer.setVisibility(GONE);
            mConversationIconContainer.setOnClickListener(null);
        }
        mExpandButton.setVisibility(VISIBLE);
        updateContentEndPaddings();
    }

    @Override
    public void setMessagingClippingDisabled(boolean clippingDisabled) {
        mMessagingLinearLayout.setClipBounds(clippingDisabled ? null : mMessagingClipRect);
    }

    @Nullable
    public CharSequence getConversationSenderName() {
        if (mGroups.isEmpty()) {
            return null;
        }
        final CharSequence name = mGroups.get(mGroups.size() - 1).getSenderName();
        return getResources().getString(R.string.conversation_single_line_name_display, name);
    }

    public boolean isOneToOne() {
        return mIsOneToOne;
    }

    @Nullable
    public CharSequence getConversationText() {
        if (mMessages.isEmpty()) {
            return null;
        }
        final MessagingMessage messagingMessage = mMessages.get(mMessages.size() - 1);
        final CharSequence text = messagingMessage.getMessage() == null ? null
                : messagingMessage.getMessage().getText();
        if (text == null && messagingMessage instanceof MessagingImageMessage) {
            final String unformatted =
                    getResources().getString(R.string.conversation_single_line_image_placeholder);
            SpannableString spannableString = new SpannableString(unformatted);
            spannableString.setSpan(
                    new StyleSpan(Typeface.ITALIC),
                    0,
                    spannableString.length(),
                    Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
            return spannableString;
        }
        return text;
    }

    @Nullable
    public Icon getConversationIcon() {
        return mConversationIcon;
    }

    @Nullable
    public ConversationHeaderData getConversationHeaderData() {
        return mConversationHeaderData;
    }

    private static class TouchDelegateComposite extends TouchDelegate {
        private final ArrayList<TouchDelegate> mDelegates = new ArrayList<>();

        private TouchDelegateComposite(View view) {
            super(new Rect(), view);
        }

        public void add(TouchDelegate delegate) {
            mDelegates.add(delegate);
        }

        public void clear() {
            mDelegates.clear();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX();
            float y = event.getY();
            for (TouchDelegate delegate : mDelegates) {
                event.setLocation(x, y);
                if (delegate.onTouchEvent(event)) {
                    return true;
                }
            }
            return false;
        }
    }
}
