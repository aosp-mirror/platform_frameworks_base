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
import android.app.Person;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Pools;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RemoteViews;

import com.android.internal.R;

import java.util.ArrayList;
import java.util.List;

/**
 * A message of a {@link MessagingLayout}.
 */
@RemoteViews.RemoteView
public class MessagingGroup extends LinearLayout implements MessagingLinearLayout.MessagingChild {
    private static Pools.SimplePool<MessagingGroup> sInstancePool
            = new Pools.SynchronizedPool<>(10);
    private MessagingLinearLayout mMessageContainer;
    private ImageFloatingTextView mSenderName;
    private ImageView mAvatarView;
    private String mAvatarSymbol = "";
    private int mLayoutColor;
    private CharSequence mAvatarName = "";
    private Icon mAvatarIcon;
    private int mTextColor;
    private int mSendingTextColor;
    private List<MessagingMessage> mMessages;
    private ArrayList<MessagingMessage> mAddedMessages = new ArrayList<>();
    private boolean mFirstLayout;
    private boolean mIsHidingAnimated;
    private boolean mNeedsGeneratedAvatar;
    private Person mSender;
    private boolean mImagesAtEnd;
    private ViewGroup mImageContainer;
    private MessagingImageMessage mIsolatedMessage;
    private boolean mTransformingImages;
    private Point mDisplaySize = new Point();
    private ProgressBar mSendingSpinner;
    private View mSendingSpinnerContainer;

    public MessagingGroup(@NonNull Context context) {
        super(context);
    }

    public MessagingGroup(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public MessagingGroup(@NonNull Context context, @Nullable AttributeSet attrs,
            @AttrRes int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public MessagingGroup(@NonNull Context context, @Nullable AttributeSet attrs,
            @AttrRes int defStyleAttr, @StyleRes int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mMessageContainer = findViewById(R.id.group_message_container);
        mSenderName = findViewById(R.id.message_name);
        mSenderName.addOnLayoutChangeListener(MessagingLayout.MESSAGING_PROPERTY_ANIMATOR);
        mAvatarView = findViewById(R.id.message_icon);
        mImageContainer = findViewById(R.id.messaging_group_icon_container);
        mSendingSpinner = findViewById(R.id.messaging_group_sending_progress);
        mSendingSpinnerContainer = findViewById(R.id.messaging_group_sending_progress_container);
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        mDisplaySize.x = displayMetrics.widthPixels;
        mDisplaySize.y = displayMetrics.heightPixels;
    }

    public void updateClipRect() {
        // We want to clip to the senderName if it's available, otherwise our images will come
        // from a weird position
        Rect clipRect;
        if (mSenderName.getVisibility() != View.GONE && !mTransformingImages) {
            ViewGroup parent = (ViewGroup) mSenderName.getParent();
            int top = getDistanceFromParent(mSenderName, parent) - getDistanceFromParent(
                    mMessageContainer, parent) + mSenderName.getHeight();
            clipRect = new Rect(0, top, mDisplaySize.x, mDisplaySize.y);
        } else {
            clipRect = null;
        }
        mMessageContainer.setClipBounds(clipRect);
    }

    private int getDistanceFromParent(View searchedView, ViewGroup parent) {
        int position = 0;
        View view = searchedView;
        while(view != parent) {
            position += view.getTop() + view.getTranslationY();
            view = (View) view.getParent();
        }
        return position;
    }

    public void setSender(Person sender, CharSequence nameOverride) {
        mSender = sender;
        if (nameOverride == null) {
            nameOverride = sender.getName();
        }
        mSenderName.setText(nameOverride);
        mNeedsGeneratedAvatar = sender.getIcon() == null;
        if (!mNeedsGeneratedAvatar) {
            setAvatar(sender.getIcon());
        }
        mAvatarView.setVisibility(VISIBLE);
        mSenderName.setVisibility(VISIBLE);
        mTextColor = getNormalTextColor();
        mSendingTextColor = calculateSendingTextColor();
    }

    public void setSending(boolean sending) {
        int visibility = sending ? View.VISIBLE : View.GONE;
        if (mSendingSpinnerContainer.getVisibility() != visibility) {
            mSendingSpinnerContainer.setVisibility(visibility);
            updateMessageColor();
        }
    }

    private int getNormalTextColor() {
        return mContext.getColor(R.color.notification_secondary_text_color_light);
    }

    private int calculateSendingTextColor() {
        TypedValue alphaValue = new TypedValue();
        mContext.getResources().getValue(
                R.dimen.notification_secondary_text_disabled_alpha, alphaValue, true);
        float alpha = alphaValue.getFloat();
        return Color.valueOf(
                Color.red(mTextColor),
                Color.green(mTextColor),
                Color.blue(mTextColor),
                alpha).toArgb();
    }

    public void setAvatar(Icon icon) {
        mAvatarIcon = icon;
        mAvatarView.setImageIcon(icon);
        mAvatarSymbol = "";
        mAvatarName = "";
    }

    static MessagingGroup createGroup(MessagingLinearLayout layout) {;
        MessagingGroup createdGroup = sInstancePool.acquire();
        if (createdGroup == null) {
            createdGroup = (MessagingGroup) LayoutInflater.from(layout.getContext()).inflate(
                    R.layout.notification_template_messaging_group, layout,
                    false);
            createdGroup.addOnLayoutChangeListener(MessagingLayout.MESSAGING_PROPERTY_ANIMATOR);
        }
        layout.addView(createdGroup);
        return createdGroup;
    }

    public void removeMessage(MessagingMessage messagingMessage) {
        ViewGroup messageParent = (ViewGroup) messagingMessage.getView().getParent();
        messageParent.removeView(messagingMessage.getView());
        Runnable recycleRunnable = () -> {
            messageParent.removeTransientView(messagingMessage.getView());
            messagingMessage.recycle();
            if (mMessageContainer.getChildCount() == 0
                    && mMessageContainer.getTransientViewCount() == 0
                    && mImageContainer.getChildCount() == 0) {
                ViewParent parent = getParent();
                if (parent instanceof ViewGroup) {
                    ((ViewGroup) parent).removeView(MessagingGroup.this);
                }
                setAvatar(null);
                mAvatarView.setAlpha(1.0f);
                mAvatarView.setTranslationY(0.0f);
                mSenderName.setAlpha(1.0f);
                mSenderName.setTranslationY(0.0f);
                sInstancePool.release(MessagingGroup.this);
            }
        };
        if (isShown()) {
            messageParent.addTransientView(messagingMessage.getView(), 0);
            performRemoveAnimation(messagingMessage.getView(), recycleRunnable);
            if (mMessageContainer.getChildCount() == 0
                    && mImageContainer.getChildCount() == 0) {
                removeGroupAnimated(null);
            }
        } else {
            recycleRunnable.run();
        }

    }

    private void removeGroupAnimated(Runnable endAction) {
        performRemoveAnimation(mAvatarView, null);
        performRemoveAnimation(mSenderName, null);
        boolean endActionTriggered = false;
        for (int i = mMessageContainer.getChildCount() - 1; i >= 0; i--) {
            View child = mMessageContainer.getChildAt(i);
            if (child.getVisibility() == View.GONE) {
                continue;
            }
            final ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (lp instanceof MessagingLinearLayout.LayoutParams
                    && ((MessagingLinearLayout.LayoutParams) lp).hide
                    && !((MessagingLinearLayout.LayoutParams) lp).visibleBefore) {
                continue;
            }
            Runnable childEndAction = endActionTriggered ? null : endAction;
            performRemoveAnimation(child, childEndAction);
            endActionTriggered = true;
        }
        if (mIsolatedMessage != null) {
            performRemoveAnimation(mIsolatedMessage, !endActionTriggered ? endAction : null);
            endActionTriggered = true;
        }
        if (!endActionTriggered && endAction != null) {
            endAction.run();
        }
    }

    public void performRemoveAnimation(View message, Runnable endAction) {
        MessagingPropertyAnimator.fadeOut(message, endAction);
        MessagingPropertyAnimator.startLocalTranslationTo(message,
                (int) (-getHeight() * 0.5f), MessagingLayout.FAST_OUT_LINEAR_IN);
    }

    public CharSequence getSenderName() {
        return mSenderName.getText();
    }

    public static void dropCache() {
        sInstancePool = new Pools.SynchronizedPool<>(10);
    }

    @Override
    public int getMeasuredType() {
        boolean hasNormal = false;
        for (int i = mMessageContainer.getChildCount() - 1; i >= 0; i--) {
            View child = mMessageContainer.getChildAt(i);
            if (child instanceof MessagingLinearLayout.MessagingChild) {
                int type = ((MessagingLinearLayout.MessagingChild) child).getMeasuredType();
                if (type == MEASURED_TOO_SMALL) {
                    if (hasNormal) {
                        return MEASURED_SHORTENED;
                    } else {
                        return MEASURED_TOO_SMALL;
                    }
                } else if (type == MEASURED_SHORTENED) {
                    return MEASURED_SHORTENED;
                } else {
                    hasNormal = true;
                }
            }
        }
        if (mMessageContainer.getChildCount() == 0 && mIsolatedMessage != null) {
            return mIsolatedMessage.getMeasuredType();
        }
        return MEASURED_NORMAL;
    }

    @Override
    public int getConsumedLines() {
        int result = 0;
        for (int i = 0; i < mMessageContainer.getChildCount(); i++) {
            View child = mMessageContainer.getChildAt(i);
            if (child instanceof MessagingLinearLayout.MessagingChild) {
                result += ((MessagingLinearLayout.MessagingChild) child).getConsumedLines();
            }
        }
        result = mIsolatedMessage != null ? Math.max(result, 1) : result;
        // A group is usually taking up quite some space with the padding and the name, let's add 1
        return result + 1;
    }

    @Override
    public void setMaxDisplayedLines(int lines) {
        mMessageContainer.setMaxDisplayedLines(lines);
    }

    @Override
    public void hideAnimated() {
        setIsHidingAnimated(true);
        removeGroupAnimated(() -> setIsHidingAnimated(false));
    }

    @Override
    public boolean isHidingAnimated() {
        return mIsHidingAnimated;
    }

    private void setIsHidingAnimated(boolean isHiding) {
        ViewParent parent = getParent();
        mIsHidingAnimated = isHiding;
        invalidate();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).invalidate();
        }
    }

    public Icon getAvatarSymbolIfMatching(CharSequence avatarName, String avatarSymbol,
            int layoutColor) {
        if (mAvatarName.equals(avatarName) && mAvatarSymbol.equals(avatarSymbol)
                && layoutColor == mLayoutColor) {
            return mAvatarIcon;
        }
        return null;
    }

    public void setCreatedAvatar(Icon cachedIcon, CharSequence avatarName, String avatarSymbol,
            int layoutColor) {
        if (!mAvatarName.equals(avatarName) || !mAvatarSymbol.equals(avatarSymbol)
                || layoutColor != mLayoutColor) {
            setAvatar(cachedIcon);
            mAvatarSymbol = avatarSymbol;
            setLayoutColor(layoutColor);
            mAvatarName = avatarName;
        }
    }

    public void setLayoutColor(int layoutColor) {
        if (layoutColor != mLayoutColor){
            mLayoutColor = layoutColor;
            mSendingSpinner.setIndeterminateTintList(ColorStateList.valueOf(mLayoutColor));
        }
    }

    private void updateMessageColor() {
        if (mMessages != null) {
            int color = mSendingSpinnerContainer.getVisibility() == View.VISIBLE
                    ? mSendingTextColor : mTextColor;
            for (MessagingMessage message : mMessages) {
                message.setColor(message.getMessage().isRemoteInputHistory() ? color : mTextColor);
            }
        }
    }

    public void setMessages(List<MessagingMessage> group) {
        // Let's now make sure all children are added and in the correct order
        int textMessageIndex = 0;
        MessagingImageMessage isolatedMessage = null;
        for (int messageIndex = 0; messageIndex < group.size(); messageIndex++) {
            MessagingMessage message = group.get(messageIndex);
            if (message.getGroup() != this) {
                message.setMessagingGroup(this);
                mAddedMessages.add(message);
            }
            boolean isImage = message instanceof MessagingImageMessage;
            if (mImagesAtEnd && isImage) {
                isolatedMessage = (MessagingImageMessage) message;
            } else {
                if (removeFromParentIfDifferent(message, mMessageContainer)) {
                    ViewGroup.LayoutParams layoutParams = message.getView().getLayoutParams();
                    if (layoutParams != null
                            && !(layoutParams instanceof MessagingLinearLayout.LayoutParams)) {
                        message.getView().setLayoutParams(
                                mMessageContainer.generateDefaultLayoutParams());
                    }
                    mMessageContainer.addView(message.getView(), textMessageIndex);
                }
                if (isImage) {
                    ((MessagingImageMessage) message).setIsolated(false);
                }
                // Let's sort them properly
                if (textMessageIndex != mMessageContainer.indexOfChild(message.getView())) {
                    mMessageContainer.removeView(message.getView());
                    mMessageContainer.addView(message.getView(), textMessageIndex);
                }
                textMessageIndex++;
            }
        }
        if (isolatedMessage != null) {
            if (removeFromParentIfDifferent(isolatedMessage, mImageContainer)) {
                mImageContainer.removeAllViews();
                mImageContainer.addView(isolatedMessage.getView());
            }
            isolatedMessage.setIsolated(true);
        } else if (mIsolatedMessage != null) {
            mImageContainer.removeAllViews();
        }
        mIsolatedMessage = isolatedMessage;
        updateImageContainerVisibility();
        mMessages = group;
        updateMessageColor();
    }

    private void updateImageContainerVisibility() {
        mImageContainer.setVisibility(mIsolatedMessage != null && mImagesAtEnd
                ? View.VISIBLE : View.GONE);
    }

    /**
     * Remove the message from the parent if the parent isn't the one provided
     * @return whether the message was removed
     */
    private boolean removeFromParentIfDifferent(MessagingMessage message, ViewGroup newParent) {
        ViewParent parent = message.getView().getParent();
        if (parent != newParent) {
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(message.getView());
            }
            return true;
        }
        return false;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (!mAddedMessages.isEmpty()) {
            getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    for (MessagingMessage message : mAddedMessages) {
                        if (!message.getView().isShown()) {
                            continue;
                        }
                        MessagingPropertyAnimator.fadeIn(message.getView());
                        if (!mFirstLayout) {
                            MessagingPropertyAnimator.startLocalTranslationFrom(message.getView(),
                                    message.getView().getHeight(),
                                    MessagingLayout.LINEAR_OUT_SLOW_IN);
                        }
                    }
                    mAddedMessages.clear();
                    getViewTreeObserver().removeOnPreDrawListener(this);
                    return true;
                }
            });
        }
        mFirstLayout = false;
        updateClipRect();
    }

    /**
     * Calculates the group compatibility between this and another group.
     *
     * @param otherGroup the other group to compare it with
     *
     * @return 0 if the groups are totally incompatible or 1 + the number of matching messages if
     *         they match.
     */
    public int calculateGroupCompatibility(MessagingGroup otherGroup) {
        if (TextUtils.equals(getSenderName(),otherGroup.getSenderName())) {
            int result = 1;
            for (int i = 0; i < mMessages.size() && i < otherGroup.mMessages.size(); i++) {
                MessagingMessage ownMessage = mMessages.get(mMessages.size() - 1 - i);
                MessagingMessage otherMessage = otherGroup.mMessages.get(
                        otherGroup.mMessages.size() - 1 - i);
                if (!ownMessage.sameAs(otherMessage)) {
                    return result;
                }
                result++;
            }
            return result;
        }
        return 0;
    }

    public View getSenderView() {
        return mSenderName;
    }

    public View getAvatar() {
        return mAvatarView;
    }

    public MessagingLinearLayout getMessageContainer() {
        return mMessageContainer;
    }

    public MessagingImageMessage getIsolatedMessage() {
        return mIsolatedMessage;
    }

    public boolean needsGeneratedAvatar() {
        return mNeedsGeneratedAvatar;
    }

    public Person getSender() {
        return mSender;
    }

    public void setTransformingImages(boolean transformingImages) {
        mTransformingImages = transformingImages;
    }

    public void setDisplayImagesAtEnd(boolean atEnd) {
        if (mImagesAtEnd != atEnd) {
            mImagesAtEnd = atEnd;
            updateImageContainerVisibility();
        }
    }

    public List<MessagingMessage> getMessages() {
        return mMessages;
    }
}
