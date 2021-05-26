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

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.plugins.FalsingManager;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Simple Fake for testing where {@link FalsingManager} is required.
 */
public class FalsingManagerFake implements FalsingManager {
    private boolean mIsFalseTouch;
    private boolean mIsSimpleTap;
    private boolean mIsFalseDoubleTap;
    private boolean mIsUnlockingDisabled;
    private boolean mIsClassiferEnabled;
    private boolean mShouldEnforceBouncer;
    private boolean mIsReportingEnabled;
    private boolean mIsFalseRobustTap;

    private final List<FalsingBeliefListener> mFalsingBeliefListeners = new ArrayList<>();
    private final List<FalsingTapListener> mTapListeners = new ArrayList<>();

    @Override
    public void onSuccessfulUnlock() {

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

    public void setFalseTap(boolean falseRobustTap) {
        mIsFalseRobustTap = falseRobustTap;
    }

    public void setSimpleTap(boolean isSimpleTape) {
        mIsSimpleTap = isSimpleTape;
    }

    public void setFalseDoubleTap(boolean falseDoubleTap) {
        mIsFalseDoubleTap = falseDoubleTap;
    }

    @Override
    public boolean isSimpleTap() {
        return mIsSimpleTap;
    }

    @Override
    public boolean isFalseTap(@Penalty int penalty) {
        return mIsFalseRobustTap;
    }

    @Override
    public boolean isFalseDoubleTap() {
        return mIsFalseDoubleTap;
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
    public boolean shouldEnforceBouncer() {
        return mShouldEnforceBouncer;
    }

    @Override
    public Uri reportRejectedTouch() {
        return null;
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
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
    }

    @Override
    public void cleanup() {
    }

    @Override
    public void onProximityEvent(ProximityEvent proximityEvent) {

    }

    @Override
    public void addFalsingBeliefListener(FalsingBeliefListener listener) {
        mFalsingBeliefListeners.add(listener);
    }

    @Override
    public void removeFalsingBeliefListener(FalsingBeliefListener listener) {
        mFalsingBeliefListeners.remove(listener);
    }

    @Override
    public void addTapListener(FalsingTapListener falsingTapListener) {
        mTapListeners.add(falsingTapListener);
    }

    @Override
    public void removeTapListener(FalsingTapListener falsingTapListener) {
        mTapListeners.remove(falsingTapListener);
    }

    public List<FalsingTapListener> getTapListeners() {
        return mTapListeners;
    }
}
