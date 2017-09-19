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
import android.content.Context;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.util.AttributeSet;
import android.util.Pools;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RemoteViews;

import com.android.internal.R;
import com.android.internal.util.NotificationColorUtil;

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
    private ColorFilter mMessageBackgroundFilter;
    private int mTextColor;

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
        mAvatarView = findViewById(R.id.message_icon);
    }

    public void setSender(CharSequence sender) {
        if (sender == null) {
            mAvatarView.setVisibility(GONE);
            mSenderName.setVisibility(GONE);
            setGravity(Gravity.END);
            mMessageBackgroundFilter = new PorterDuffColorFilter(mLayoutColor,
                    PorterDuff.Mode.SRC_ATOP);
            mTextColor = NotificationColorUtil.isColorLight(mLayoutColor) ? getNormalTextColor()
                    : Color.WHITE;
        } else {
            mSenderName.setText(sender);
            mAvatarView.setVisibility(VISIBLE);
            mSenderName.setVisibility(VISIBLE);
            setGravity(Gravity.START);
            mMessageBackgroundFilter = null;
            mTextColor = getNormalTextColor();
        }
    }

    private int getNormalTextColor() {
        return mContext.getColor(R.color.notification_primary_text_color_light);
    }

    public void setAvatar(Icon icon) {
        mAvatarIcon = icon;
        mAvatarView.setImageIcon(icon);
        mAvatarSymbol = "";
        mLayoutColor = 0;
        mAvatarName = "";
    }

    static MessagingGroup createGroup(MessagingLinearLayout layout) {;
        MessagingGroup createdGroup = sInstancePool.acquire();
        if (createdGroup == null) {
            createdGroup = (MessagingGroup) LayoutInflater.from(layout.getContext()).inflate(
                    R.layout.notification_template_messaging_group, layout,
                    false);
        }
        layout.addView(createdGroup);
        return createdGroup;
    }

    public void removeMessage(MessagingMessage messagingMessage) {
        // TODO: add removal animation
        mMessageContainer.removeView(messagingMessage);
        if (mMessageContainer.getChildCount() == 0) {
            ViewParent parent = getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(this);
            }
            setAvatar(null);
            sInstancePool.release(this);
        }
    }

    public CharSequence getSenderName() {
        return mSenderName.getText();
    }

    public void setSenderVisible(boolean visible) {
        mSenderName.setVisibility(visible ? VISIBLE : GONE);
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
        return result;
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
            mLayoutColor = layoutColor;
            mAvatarName = avatarName;
        }
    }

    public void setLayoutColor(int layoutColor) {
        mLayoutColor = layoutColor;
    }

    public void setMessages(List<MessagingMessage> group) {
        // Let's now make sure all children are added and in the correct order
        for (int messageIndex = 0; messageIndex < group.size(); messageIndex++) {
            MessagingMessage message = group.get(messageIndex);
            if (message.getGroup() != this) {
                message.setMessagingGroup(this);
                ViewParent parent = mMessageContainer.getParent();
                if (parent instanceof ViewGroup) {
                    ((ViewGroup) parent).removeView(message);
                }
                mMessageContainer.addView(message, messageIndex);
            }
            if (messageIndex != mMessageContainer.indexOfChild(message)) {
                mMessageContainer.removeView(message);
                mMessageContainer.addView(message, messageIndex);
            }
            // Let's make sure the message color is correct
            Drawable targetDrawable = message.getBackground();

            if (targetDrawable != null) {
                targetDrawable.mutate().setColorFilter(mMessageBackgroundFilter);
            }
            message.setTextColor(mTextColor);
        }
    }
}
