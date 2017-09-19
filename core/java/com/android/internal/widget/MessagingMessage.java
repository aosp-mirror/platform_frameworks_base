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
import android.text.Layout;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Pools;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RemoteViews;

import com.android.internal.R;

import java.util.Objects;

/**
 * A message of a {@link MessagingLayout}.
 */
@RemoteViews.RemoteView
public class MessagingMessage extends ImageFloatingTextView implements
        MessagingLinearLayout.MessagingChild {

    private static Pools.SimplePool<MessagingMessage> sInstancePool
            = new Pools.SynchronizedPool<>(10);
    private Notification.MessagingStyle.Message mMessage;
    private MessagingGroup mGroup;
    private boolean mIsHistoric;

    public MessagingMessage(@NonNull Context context) {
        super(context);
    }

    public MessagingMessage(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public MessagingMessage(@NonNull Context context, @Nullable AttributeSet attrs,
            @AttrRes int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public MessagingMessage(@NonNull Context context, @Nullable AttributeSet attrs,
            @AttrRes int defStyleAttr, @StyleRes int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    private void setMessage(Notification.MessagingStyle.Message message) {
        mMessage = message;
        setText(message.getText());
    }

    public Notification.MessagingStyle.Message getMessage() {
        return mMessage;
    }

    boolean sameAs(Notification.MessagingStyle.Message message) {
        if (!Objects.equals(message.getText(), mMessage.getText())) {
            return false;
        }
        if (!Objects.equals(message.getSender(), mMessage.getSender())) {
            return false;
        }
        if (!Objects.equals(message.getTimestamp(), mMessage.getTimestamp())) {
            return false;
        }
        return true;
    }

    static MessagingMessage createMessage(MessagingLayout layout,
            Notification.MessagingStyle.Message m) {
        MessagingLinearLayout messagingLinearLayout = layout.getMessagingLinearLayout();
        MessagingMessage createdMessage = sInstancePool.acquire();
        if (createdMessage == null) {
            createdMessage = (MessagingMessage) LayoutInflater.from(layout.getContext()).inflate(
                    R.layout.notification_template_messaging_message, messagingLinearLayout,
                    false);
        }
        createdMessage.setMessage(m);
        return createdMessage;
    }

    public void removeMessage() {
        mGroup.removeMessage(this);
        mGroup = null;
        mMessage = null;
        sInstancePool.release(this);
    }

    public void setMessagingGroup(MessagingGroup group) {
        mGroup = group;
    }

    public static void dropCache() {
        sInstancePool = new Pools.SynchronizedPool<>(10);
    }

    public void setIsHistoric(boolean isHistoric) {
        mIsHistoric = isHistoric;
    }

    public MessagingGroup getGroup() {
        return mGroup;
    }

    @Override
    public int getMeasuredType() {
        boolean measuredTooSmall = getMeasuredHeight()
                < getLayoutHeight() + getPaddingTop() + getPaddingBottom();
        if (measuredTooSmall) {
            return MEASURED_TOO_SMALL;
        } else {
            Layout layout = getLayout();
            if (layout == null) {
                return MEASURED_TOO_SMALL;
            }
            if (layout.getEllipsisCount(layout.getLineCount() - 1) > 0) {
                return MEASURED_SHORTENED;
            } else {
                return MEASURED_NORMAL;
            }
        }
    }

    @Override
    public int getConsumedLines() {
        return getLineCount();
    }

    public int getLayoutHeight() {
        Layout layout = getLayout();
        if (layout == null) {
            return 0;
        }
        return layout.getHeight();
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
