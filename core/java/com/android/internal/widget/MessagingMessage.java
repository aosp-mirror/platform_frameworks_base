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

import android.app.ActivityManager;
import android.app.Notification;
import android.view.View;

import java.util.Objects;

/**
 * A message of a {@link MessagingLayout}.
 */
public interface MessagingMessage extends MessagingLinearLayout.MessagingChild {

    /**
     * Prefix for supported image MIME types
     **/
    String IMAGE_MIME_TYPE_PREFIX = "image/";

    static MessagingMessage createMessage(MessagingLayout layout,
            Notification.MessagingStyle.Message m) {
        if (hasImage(m) && !ActivityManager.isLowRamDeviceStatic()) {
            return MessagingImageMessage.createMessage(layout, m);
        } else {
            return MessagingTextMessage.createMessage(layout, m);
        }
    }

    static void dropCache() {
        MessagingTextMessage.dropCache();
        MessagingImageMessage.dropCache();
    }

    static boolean hasImage(Notification.MessagingStyle.Message m) {
        return m.getDataUri() != null
                && m.getDataMimeType() != null
                && m.getDataMimeType().startsWith(IMAGE_MIME_TYPE_PREFIX);
    }

    /**
     * Set a message for this view.
     * @return true if setting the message worked
     */
    default boolean setMessage(Notification.MessagingStyle.Message message) {
        getState().setMessage(message);
        return true;
    }

    default Notification.MessagingStyle.Message getMessage() {
        return getState().getMessage();
    }

    default boolean sameAs(Notification.MessagingStyle.Message message) {
        Notification.MessagingStyle.Message ownMessage = getMessage();
        if (!Objects.equals(message.getText(), ownMessage.getText())) {
            return false;
        }
        if (!Objects.equals(message.getSender(), ownMessage.getSender())) {
            return false;
        }
        if (!Objects.equals(message.getTimestamp(), ownMessage.getTimestamp())) {
            return false;
        }
        if (!Objects.equals(message.getDataMimeType(), ownMessage.getDataMimeType())) {
            return false;
        }
        if (!Objects.equals(message.getDataUri(), ownMessage.getDataUri())) {
            return false;
        }
        if (message.isRemoteInputHistory() != ownMessage.isRemoteInputHistory()) {
            return false;
        }
        return true;
    }

    default boolean sameAs(MessagingMessage message) {
        return sameAs(message.getMessage());
    }

    default void removeMessage() {
        getGroup().removeMessage(this);
    }

    default void setMessagingGroup(MessagingGroup group) {
        getState().setGroup(group);
    }

    default void setIsHistoric(boolean isHistoric) {
        getState().setIsHistoric(isHistoric);
    }

    default MessagingGroup getGroup() {
        return getState().getGroup();
    }

    default void setIsHidingAnimated(boolean isHiding) {
        getState().setIsHidingAnimated(isHiding);
    }

    @Override
    default boolean isHidingAnimated() {
        return getState().isHidingAnimated();
    }

    @Override
    default void hideAnimated() {
        setIsHidingAnimated(true);
        getGroup().performRemoveAnimation(getState().getHostView(),
                () -> setIsHidingAnimated(false));
    }

    default boolean hasOverlappingRendering() {
        return false;
    }

    default void recycle() {
        getState().reset();
    }

    default View getView() {
        return (View) this;
    }

    default void setColor(int textColor) {}

    MessagingMessageState getState();

    void setVisibility(int visibility);
}
