/*
 * Copyright (C) 2012 The Android Open Source Project
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
package com.android.internal.policy.impl.keyguard;

import com.android.internal.widget.LockPatternUtils;

public interface KeyguardSecurityView {
    static public final int SCREEN_ON = 1;
    static public final int VIEW_REVEALED = 2;

    /**
     * Interface back to keyguard to tell it when security
     * @param callback
     */
    void setKeyguardCallback(KeyguardSecurityCallback callback);

    /**
     * Set {@link LockPatternUtils} object. Useful for providing a mock interface.
     * @param utils
     */
    void setLockPatternUtils(LockPatternUtils utils);

    /**
     * Reset the view and prepare to take input. This should do things like clearing the
     * password or pattern and clear error messages.
     */
    void reset();

    /**
     * Emulate activity life cycle within the view. When called, the view should clean up
     * and prepare to be removed.
     */
    void onPause();

    /**
     * Emulate activity life cycle within this view.  When called, the view should prepare itself
     * to be shown.
     * @param reason the root cause of the event.
     */
    void onResume(int reason);

    /**
     * Inquire whether this view requires IME (keyboard) interaction.
     *
     * @return true if IME interaction is required.
     */
    boolean needsInput();

    /**
     * Get {@link KeyguardSecurityCallback} for the given object
     * @return KeyguardSecurityCallback
     */
    KeyguardSecurityCallback getCallback();

    /**
     * Instruct the view to show usability hints, if any.
     *
     */
    void showUsabilityHint();

    /**
     * Place the security view into bouncer mode.
     * Animate transisiton if duration is non-zero.
     * @param duration millisends for the transisiton animation.
     */
    void showBouncer(int duration);

    /**
     * Place the security view into non-bouncer mode.
     * Animate transisiton if duration is non-zero.
     * @param duration millisends for the transisiton animation.
     */
    void hideBouncer(int duration);
}
