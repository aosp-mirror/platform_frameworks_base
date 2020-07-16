/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.inputmethod;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.os.IBinder;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.view.inputmethod.InputMethodInfo;

import com.android.internal.inputmethod.SoftInputShowHideReason;
import com.android.internal.view.IInlineSuggestionsRequestCallback;
import com.android.internal.view.InlineSuggestionsRequestInfo;
import com.android.server.LocalServices;

import java.util.Collections;
import java.util.List;

/**
 * Input method manager local system service interface.
 */
public abstract class InputMethodManagerInternal {
    /**
     * Listener for input method list changed events.
     */
    public interface InputMethodListListener {
        /**
         * Called with the list of the installed IMEs when it's updated.
         */
        void onInputMethodListUpdated(List<InputMethodInfo> info, @UserIdInt int userId);
    }

    /**
     * Called by the power manager to tell the input method manager whether it
     * should start watching for wake events.
     */
    public abstract void setInteractive(boolean interactive);

    /**
     * Hides the current input method, if visible.
     */
    public abstract void hideCurrentInputMethod(@SoftInputShowHideReason int reason);

    /**
     * Returns the list of installed input methods for the specified user.
     *
     * @param userId The user ID to be queried.
     * @return A list of {@link InputMethodInfo}.  VR-only IMEs are already excluded.
     */
    public abstract List<InputMethodInfo> getInputMethodListAsUser(@UserIdInt int userId);

    /**
     * Returns the list of installed input methods that are enabled for the specified user.
     *
     * @param userId The user ID to be queried.
     * @return A list of {@link InputMethodInfo} that are enabled for {@code userId}.
     */
    public abstract List<InputMethodInfo> getEnabledInputMethodListAsUser(@UserIdInt int userId);

    /**
     * Called by the Autofill Frameworks to request an {@link InlineSuggestionsRequest} from
     * the input method.
     *
     * @param requestInfo information needed to create an {@link InlineSuggestionsRequest}.
     * @param cb {@link IInlineSuggestionsRequestCallback} used to pass back the request object.
     */
    public abstract void onCreateInlineSuggestionsRequest(@UserIdInt int userId,
            InlineSuggestionsRequestInfo requestInfo, IInlineSuggestionsRequestCallback cb);

    /**
     * Force switch to the enabled input method by {@code imeId} for current user. If the input
     * method with {@code imeId} is not enabled or not installed, do nothing.
     *
     * @param imeId  The input method ID to be switched to.
     * @param userId The user ID to be queried.
     * @return {@code true} if the current input method was successfully switched to the input
     * method by {@code imeId}; {@code false} the input method with {@code imeId} is not available
     * to be switched.
     */
    public abstract boolean switchToInputMethod(String imeId, @UserIdInt int userId);

    /**
     * Registers a new {@link InputMethodListListener}.
     */
    public abstract void registerInputMethodListListener(InputMethodListListener listener);

    /**
     * Transfers input focus from a given input token to that of the IME window.
     *
     * @param sourceInputToken The source token.
     * @param displayId The display hosting the IME window.
     * @return {@code true} if the transfer is successful.
     */
    public abstract boolean transferTouchFocusToImeWindow(@NonNull IBinder sourceInputToken,
            int displayId);

    /**
     * Reports that IME control has transferred to the given window token, or if null that
     * control has been taken away from client windows (and is instead controlled by the policy
     * or SystemUI).
     *
     * @param windowToken the window token that is now in control, or {@code null} if no client
     *                   window is in control of the IME.
     */
    public abstract void reportImeControl(@Nullable IBinder windowToken);

    /**
     * Destroys the IME surface.
     */
    public abstract void removeImeSurface();

    /**
     * Fake implementation of {@link InputMethodManagerInternal}.  All the methods do nothing.
     */
    private static final InputMethodManagerInternal NOP =
            new InputMethodManagerInternal() {
                @Override
                public void setInteractive(boolean interactive) {
                }

                @Override
                public void hideCurrentInputMethod(@SoftInputShowHideReason int reason) {
                }

                @Override
                public List<InputMethodInfo> getInputMethodListAsUser(int userId) {
                    return Collections.emptyList();
                }

                @Override
                public List<InputMethodInfo> getEnabledInputMethodListAsUser(int userId) {
                    return Collections.emptyList();
                }

                @Override
                public void onCreateInlineSuggestionsRequest(int userId,
                        InlineSuggestionsRequestInfo requestInfo,
                        IInlineSuggestionsRequestCallback cb) {
                }

                @Override
                public boolean switchToInputMethod(String imeId, int userId) {
                    return false;
                }

                @Override
                public void registerInputMethodListListener(InputMethodListListener listener) {
                }

                @Override
                public boolean transferTouchFocusToImeWindow(@NonNull IBinder sourceInputToken,
                        int displayId) {
                    return false;
                }

                @Override
                public void reportImeControl(@Nullable IBinder windowToken) {
                }

                @Override
                public void removeImeSurface() {
                }
            };

    /**
     * @return Global instance if exists.  Otherwise, a dummy no-op instance.
     */
    @NonNull
    public static InputMethodManagerInternal get() {
        final InputMethodManagerInternal instance =
                LocalServices.getService(InputMethodManagerInternal.class);
        return instance != null ? instance : NOP;
    }
}
