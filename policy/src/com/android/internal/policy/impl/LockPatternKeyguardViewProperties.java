/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.policy.impl;

import com.android.internal.widget.LockPatternUtils;

import android.content.Context;
import com.android.internal.telephony.IccCard;

/**
 * Knows how to create a lock pattern keyguard view, and answer questions about
 * it (even if it hasn't been created, per the interface specs).
 */
public class LockPatternKeyguardViewProperties implements KeyguardViewProperties {

    private final LockPatternUtils mLockPatternUtils;
    private final KeyguardUpdateMonitor mUpdateMonitor;

    /**
     * @param lockPatternUtils Used to know whether the pattern enabled, and passed
     *   onto the keygaurd view when it is created.
     * @param updateMonitor Used to know whether the sim pin is enabled, and passed
     *   onto the keyguard view when it is created.
     */
    public LockPatternKeyguardViewProperties(LockPatternUtils lockPatternUtils,
            KeyguardUpdateMonitor updateMonitor) {
        mLockPatternUtils = lockPatternUtils;
        mUpdateMonitor = updateMonitor;
    }

    public KeyguardViewBase createKeyguardView(Context context,
            KeyguardUpdateMonitor updateMonitor,
            KeyguardWindowController controller) {
        return new LockPatternKeyguardView(context, updateMonitor,
                mLockPatternUtils, controller);
    }

    public boolean isSecure() {
        return mLockPatternUtils.isSecure() || isSimPinSecure();
    }

    private boolean isSimPinSecure() {
        final IccCard.State simState = mUpdateMonitor.getSimState();
        return (simState == IccCard.State.PIN_REQUIRED
                || simState == IccCard.State.PUK_REQUIRED
                || simState == IccCard.State.ABSENT
                || simState == IccCard.State.PERM_DISABLED);
    }

}
