/*
 * Copyright (C) 2018 The Android Open Source Project
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
import android.util.AttributeSet;
import android.util.Pools;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.RemoteViews;

import com.android.internal.R;

import java.util.Objects;

/**
 * A message of a {@link MessagingLayout}.
 */
@RemoteViews.RemoteView
public class MessagingTextMessage extends ImageFloatingTextView implements MessagingMessage {

    private static Pools.SimplePool<MessagingTextMessage> sInstancePool
            = new Pools.SynchronizedPool<>(20);
    private final MessagingMessageState mState = new MessagingMessageState(this);

    public MessagingTextMessage(@NonNull Context context) {
        super(context);
    }

    public MessagingTextMessage(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public MessagingTextMessage(@NonNull Context context, @Nullable AttributeSet attrs,
            @AttrRes int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public MessagingTextMessage(@NonNull Context context, @Nullable AttributeSet attrs,
            @AttrRes int defStyleAttr, @StyleRes int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public MessagingMessageState getState() {
        return mState;
    }

    @Override
    public boolean setMessage(Notification.MessagingStyle.Message message) {
        MessagingMessage.super.setMessage(message);
        setText(message.getText());
        return true;
    }

    static MessagingMessage createMessage(MessagingLayout layout,
            Notification.MessagingStyle.Message m) {
        MessagingLinearLayout messagingLinearLayout = layout.getMessagingLinearLayout();
        MessagingTextMessage createdMessage = sInstancePool.acquire();
        if (createdMessage == null) {
            createdMessage = (MessagingTextMessage) LayoutInflater.from(
                    layout.getContext()).inflate(
                            R.layout.notification_template_messaging_text_message,
                            messagingLinearLayout,
                            false);
            createdMessage.addOnLayoutChangeListener(MessagingLayout.MESSAGING_PROPERTY_ANIMATOR);
        }
        createdMessage.setMessage(m);
        return createdMessage;
    }

    public void recycle() {
        MessagingMessage.super.recycle();
        sInstancePool.release(this);
    }

    public static void dropCache() {
        sInstancePool = new Pools.SynchronizedPool<>(10);
    }

    @Override
    public int getMeasuredType() {
        boolean measuredTooSmall = getMeasuredHeight()
                < getLayoutHeight() + getPaddingTop() + getPaddingBottom();
        if (measuredTooSmall && getLineCount() <= 1) {
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
    public void setMaxDisplayedLines(int lines) {
        setMaxLines(lines);
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
    public void setColor(int color) {
        setTextColor(color);
    }
}
