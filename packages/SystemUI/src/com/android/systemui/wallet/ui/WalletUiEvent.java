/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.wallet.ui;

import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;

/**
 * Ui events for the Quick Access Wallet.
 */
public enum WalletUiEvent implements UiEventLogger.UiEventEnum {
    @UiEvent(doc = "The default payment app is opened to show all payment cards.")
    QAW_SHOW_ALL(860),

    @UiEvent(doc = "The Quick Access Wallet homescreen is unlocked.")
    QAW_UNLOCK_FROM_CARD_CLICK(861),

    @UiEvent(doc = "The Quick Access Wallet center card is changed")
    QAW_CHANGE_CARD(863),

    @UiEvent(doc = "The Quick Access Wallet is opened.")
    QAW_IMPRESSION(864),

    @UiEvent(doc = "The Quick Access Wallet card is clicked")
    QAW_CLICK_CARD(865),

    @UiEvent(doc = "The Quick Access Wallet homescreen is unlocked via clicking the unlock button")
    QAW_UNLOCK_FROM_UNLOCK_BUTTON(866),

    @UiEvent(
            doc = "The Quick Access Wallet homescreen is unlocked via clicking the show all button")
    QAW_UNLOCK_FROM_SHOW_ALL_BUTTON(867);

    private final int mId;

    WalletUiEvent(int id) {
        mId = id;
    }

    @Override
    public int getId() {
        return mId;
    }
}
