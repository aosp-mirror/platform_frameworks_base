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

import android.app.Notification;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

/**
 * Shared state and implementation for MessagingMessages. Used to share common implementations.
 */
public class MessagingMessageState {
    private final View mHostView;
    private Notification.MessagingStyle.Message mMessage;
    private MessagingGroup mGroup;
    private boolean mIsHistoric;
    private boolean mIsHidingAnimated;

    MessagingMessageState(View hostView) {
        mHostView = hostView;
    }

    public void setMessage(Notification.MessagingStyle.Message message) {
        mMessage = message;
    }

    public Notification.MessagingStyle.Message getMessage() {
        return mMessage;
    }

    public void setGroup(MessagingGroup group) {
        mGroup = group;
    }

    public MessagingGroup getGroup() {
        return mGroup;
    }

    public void setIsHistoric(boolean isHistoric) {
        mIsHistoric = isHistoric;
    }

    public void setIsHidingAnimated(boolean isHiding) {
        ViewParent parent = mHostView.getParent();
        mIsHidingAnimated = isHiding;
        mHostView.invalidate();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).invalidate();
        }
    }

    public boolean isHidingAnimated() {
        return mIsHidingAnimated;
    }

    public View getHostView() {
        return mHostView;
    }

    public void recycle() {
        mHostView.setAlpha(1.0f);
        mHostView.setTranslationY(0);
        MessagingPropertyAnimator.recycle(mHostView);
        mIsHidingAnimated = false;
        mIsHistoric = false;
        mGroup = null;
        mMessage = null;
    }
}
