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
 * limitations under the License.
 */

package com.android.server.textservices;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.view.textservice.SpellCheckerInfo;

import com.android.server.LocalServices;

/**
 * Local interface of {@link TextServicesManagerService} inside system server process.
 */
public abstract class TextServicesManagerInternal {
    /**
     * Returns the list of installed input methods for the specified user.
     *
     * <p>CAVEAT: This method is not fully implemented yet. This may return an empty list if
     * {@code userId} for a background user is specified. Check the implementation before starting
     * this method.</p>
     *
     * @param userId The user ID to be queried.
     * @return {@link SpellCheckerInfo} that is currently selected {@code userId}.
     */
    @Nullable
    public abstract SpellCheckerInfo getCurrentSpellCheckerForUser(@UserIdInt int userId);

    /**
     * Fake implementation of {@link TextServicesManagerInternal}.  All the methods do nothing.
     */
    private static final TextServicesManagerInternal NOP =
            new TextServicesManagerInternal() {
                @Override
                public SpellCheckerInfo getCurrentSpellCheckerForUser(@UserIdInt int userId) {
                    return null;
                }
            };

    /**
     * @return Global instance if exists.  Otherwise, a placeholder no-op instance.
     */
    @NonNull
    public static TextServicesManagerInternal get() {
        final TextServicesManagerInternal instance =
                LocalServices.getService(TextServicesManagerInternal.class);
        return instance != null ? instance : NOP;
    }
}
