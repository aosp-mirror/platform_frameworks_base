/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.server.pm;

import android.annotation.UserIdInt;

/**
 * Representation of a {@link UserManagerInternal.UserVisibilityListener} event.
 */
public final class UserVisibilityChangedEvent {

    public @UserIdInt int userId;
    public boolean visible;

    UserVisibilityChangedEvent(@UserIdInt int userId, boolean visible) {
        this.userId = userId;
        this.visible = visible;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + userId;
        result = prime * result + (visible ? 1231 : 1237);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        UserVisibilityChangedEvent other = (UserVisibilityChangedEvent) obj;
        if (userId != other.userId) return false;
        if (visible != other.visible) return false;
        return true;
    }

    @Override
    public String toString() {
        return userId + ":" + (visible ? "visible" : "invisible");
    }

    /**
     * Factory method.
     */
    public static UserVisibilityChangedEvent onVisible(@UserIdInt int userId) {
        return new UserVisibilityChangedEvent(userId, /* visible= */ true);
    }

    /**
     * Factory method.
     */
    public static UserVisibilityChangedEvent onInvisible(@UserIdInt int userId) {
        return new UserVisibilityChangedEvent(userId, /* visible= */ false);
    }
}
