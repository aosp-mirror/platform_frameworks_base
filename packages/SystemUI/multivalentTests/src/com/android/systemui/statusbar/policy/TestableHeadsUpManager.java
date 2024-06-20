/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_CONTRACTED;
import static com.android.systemui.util.concurrency.MockExecutorHandlerKt.mockExecutorHandler;

import static org.mockito.Mockito.spy;

import android.content.Context;
import android.graphics.Region;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.systemui.util.time.SystemClock;

class TestableHeadsUpManager extends BaseHeadsUpManager {

    private HeadsUpEntry mLastCreatedEntry;

    TestableHeadsUpManager(Context context,
            HeadsUpManagerLogger logger,
            DelayableExecutor executor,
            GlobalSettings globalSettings,
            SystemClock systemClock,
            AccessibilityManagerWrapper accessibilityManagerWrapper,
            UiEventLogger uiEventLogger,
            AvalancheController avalancheController) {
        super(context, logger, mockExecutorHandler(executor), globalSettings, systemClock,
                executor, accessibilityManagerWrapper, uiEventLogger, avalancheController);

        mTouchAcceptanceDelay = BaseHeadsUpManagerTest.TEST_TOUCH_ACCEPTANCE_TIME;
        mMinimumDisplayTime = BaseHeadsUpManagerTest.TEST_MINIMUM_DISPLAY_TIME;
        mAutoDismissTime = BaseHeadsUpManagerTest.TEST_AUTO_DISMISS_TIME;
        mStickyForSomeTimeAutoDismissTime = BaseHeadsUpManagerTest.TEST_STICKY_AUTO_DISMISS_TIME;
    }

    @NonNull
    @Override
    protected HeadsUpEntry createHeadsUpEntry(NotificationEntry entry) {
        mLastCreatedEntry = spy(super.createHeadsUpEntry(entry));
        return mLastCreatedEntry;
    }

    @Override
    public int getContentFlag() {
        return FLAG_CONTENT_VIEW_CONTRACTED;
    }

    // The following are only implemented by HeadsUpManagerPhone. If you need them, use that.
    @Override
    public void addHeadsUpPhoneListener(@NonNull OnHeadsUpPhoneListenerChange listener) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addSwipedOutNotification(@NonNull String key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void extendHeadsUp() {
        throw new UnsupportedOperationException();
    }

    @Nullable
    @Override
    public Region getTouchableRegion() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isHeadsUpAnimatingAwayValue() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void onExpandingFinished() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeNotification(@NonNull String key, boolean releaseImmediately,
            boolean animate) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setAnimationStateHandler(@NonNull AnimationStateHandler handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setGutsShown(@NonNull NotificationEntry entry, boolean gutsShown) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setHeadsUpAnimatingAway(boolean headsUpAnimatingAway) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setRemoteInputActive(@NonNull NotificationEntry entry,
            boolean remoteInputActive) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setTrackingHeadsUp(boolean tracking) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean shouldSwallowClick(@NonNull String key) {
        throw new UnsupportedOperationException();
    }
}
