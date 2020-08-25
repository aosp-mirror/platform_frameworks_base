/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.classifier;

import android.net.Uri;
import android.view.MotionEvent;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.plugins.FalsingManager;

import java.io.PrintWriter;

/**
 * Simple Fake for testing where {@link FalsingManager} is required.
 */
public class FalsingManagerFake implements FalsingManager {
    private boolean mIsFalseTouch;
    private boolean mIsUnlockingDisabled;
    private boolean mIsClassiferEnabled;
    private boolean mShouldEnforceBouncer;
    private boolean mIsReportingEnabled;

    @Override
    public void onSuccessfulUnlock() {

    }

    @Override
    public void onNotificationActive() {

    }

    @Override
    public void setShowingAod(boolean showingAod) {

    }

    @Override
    public void onNotificatonStartDraggingDown() {

    }

    @VisibleForTesting
    public void setIsUnlockingDisabled(boolean isUnlockingDisabled) {
        mIsUnlockingDisabled = isUnlockingDisabled;
    }

    @Override
    public boolean isUnlockingDisabled() {
        return mIsUnlockingDisabled;
    }

    @VisibleForTesting
    public void setIsFalseTouch(boolean isFalseTouch) {
        mIsFalseTouch = isFalseTouch;
    }

    @Override
    public boolean isFalseTouch(@Classifier.InteractionType int interactionType) {
        return mIsFalseTouch;
    }

    @Override
    public void onNotificatonStopDraggingDown() {

    }

    @Override
    public void setNotificationExpanded() {

    }

    @VisibleForTesting
    public void setIsClassiferEnabled(boolean isClassiferEnabled) {
        mIsClassiferEnabled = isClassiferEnabled;
    }

    @Override
    public boolean isClassifierEnabled() {
        return mIsClassiferEnabled;
    }

    @Override
    public void onQsDown() {

    }

    @Override
    public void setQsExpanded(boolean expanded) {

    }

    @VisibleForTesting
    public void setShouldEnforceBouncer(boolean shouldEnforceBouncer) {
        mShouldEnforceBouncer = shouldEnforceBouncer;
    }

    @Override
    public boolean shouldEnforceBouncer() {
        return mShouldEnforceBouncer;
    }

    @Override
    public void onTrackingStarted(boolean secure) {

    }

    @Override
    public void onTrackingStopped() {

    }

    @Override
    public void onLeftAffordanceOn() {

    }

    @Override
    public void onCameraOn() {

    }

    @Override
    public void onAffordanceSwipingStarted(boolean rightCorner) {

    }

    @Override
    public void onAffordanceSwipingAborted() {

    }

    @Override
    public void onStartExpandingFromPulse() {

    }

    @Override
    public void onExpansionFromPulseStopped() {

    }

    @Override
    public Uri reportRejectedTouch() {
        return null;
    }

    @Override
    public void onScreenOnFromTouch() {

    }


    @VisibleForTesting
    public void setIsReportingEnabled(boolean isReportingEnabled) {
        mIsReportingEnabled = isReportingEnabled;
    }

    @Override
    public boolean isReportingEnabled() {
        return mIsReportingEnabled;
    }

    @Override
    public void onUnlockHintStarted() {

    }

    @Override
    public void onCameraHintStarted() {

    }

    @Override
    public void onLeftAffordanceHintStarted() {

    }

    @Override
    public void onScreenTurningOn() {

    }

    @Override
    public void onScreenOff() {

    }

    @Override
    public void onNotificationStopDismissing() {

    }

    @Override
    public void onNotificationDismissed() {

    }

    @Override
    public void onNotificationStartDismissing() {

    }

    @Override
    public void onNotificationDoubleTap(boolean accepted, float dx, float dy) {

    }

    @Override
    public void onBouncerShown() {

    }

    @Override
    public void onBouncerHidden() {

    }

    @Override
    public void onTouchEvent(MotionEvent ev, int width, int height) {

    }

    @Override
    public void dump(PrintWriter pw) {

    }

    @Override
    public void cleanup() {
    }
}
