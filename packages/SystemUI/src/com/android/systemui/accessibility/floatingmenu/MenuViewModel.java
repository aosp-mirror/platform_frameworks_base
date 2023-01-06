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

package com.android.systemui.accessibility.floatingmenu;

import android.content.Context;
import android.view.accessibility.AccessibilityManager;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.android.internal.accessibility.dialog.AccessibilityTarget;

import java.util.List;

/**
 * The view model provides the menu information from the repository{@link MenuInfoRepository} for
 * the menu view{@link MenuView}.
 */
class MenuViewModel implements MenuInfoRepository.OnSettingsContentsChanged {
    private final MutableLiveData<List<AccessibilityTarget>> mTargetFeaturesData =
            new MutableLiveData<>();
    private final MutableLiveData<Integer> mSizeTypeData = new MutableLiveData<>();
    private final MutableLiveData<MenuFadeEffectInfo> mFadeEffectInfoData =
            new MutableLiveData<>();
    private final MutableLiveData<Boolean> mMoveToTuckedData = new MutableLiveData<>();
    private final MutableLiveData<Boolean> mDockTooltipData = new MutableLiveData<>();
    private final MutableLiveData<Boolean> mMigrationTooltipData = new MutableLiveData<>();
    private final MutableLiveData<Position> mPercentagePositionData = new MutableLiveData<>();
    private final MenuInfoRepository mInfoRepository;

    MenuViewModel(Context context, AccessibilityManager accessibilityManager) {
        mInfoRepository = new MenuInfoRepository(context,
                accessibilityManager, /* settingsContentsChanged= */ this);
    }

    @Override
    public void onTargetFeaturesChanged(List<AccessibilityTarget> newTargetFeatures) {
        mTargetFeaturesData.setValue(newTargetFeatures);
    }

    @Override
    public void onSizeTypeChanged(int newSizeType) {
        mSizeTypeData.setValue(newSizeType);
    }

    @Override
    public void onFadeEffectInfoChanged(MenuFadeEffectInfo fadeEffectInfo) {
        mFadeEffectInfoData.setValue(fadeEffectInfo);
    }

    void updateMenuMoveToTucked(boolean isMoveToTucked) {
        mInfoRepository.updateMoveToTucked(isMoveToTucked);
    }

    void updateMenuSavingPosition(Position percentagePosition) {
        mInfoRepository.updateMenuSavingPosition(percentagePosition);
    }

    void updateDockTooltipVisibility(boolean hasSeen) {
        mInfoRepository.updateDockTooltipVisibility(hasSeen);
    }

    void updateMigrationTooltipVisibility(boolean visible) {
        mInfoRepository.updateMigrationTooltipVisibility(visible);
    }

    LiveData<Boolean> getMoveToTuckedData() {
        mInfoRepository.loadMenuMoveToTucked(mMoveToTuckedData::setValue);
        return mMoveToTuckedData;
    }

    LiveData<Boolean> getDockTooltipVisibilityData() {
        mInfoRepository.loadDockTooltipVisibility(mDockTooltipData::setValue);
        return mDockTooltipData;
    }

    LiveData<Boolean> getMigrationTooltipVisibilityData() {
        mInfoRepository.loadMigrationTooltipVisibility(mMigrationTooltipData::setValue);
        return mMigrationTooltipData;
    }

    LiveData<Position> getPercentagePositionData() {
        mInfoRepository.loadMenuPosition(mPercentagePositionData::setValue);
        return mPercentagePositionData;
    }

    LiveData<Integer> getSizeTypeData() {
        mInfoRepository.loadMenuSizeType(mSizeTypeData::setValue);
        return mSizeTypeData;
    }

    LiveData<MenuFadeEffectInfo> getFadeEffectInfoData() {
        mInfoRepository.loadMenuFadeEffectInfo(mFadeEffectInfoData::setValue);
        return mFadeEffectInfoData;
    }

    LiveData<List<AccessibilityTarget>> getTargetFeaturesData() {
        mInfoRepository.loadMenuTargetFeatures(mTargetFeaturesData::setValue);
        return mTargetFeaturesData;
    }

    void registerObserversAndCallbacks() {
        mInfoRepository.registerObserversAndCallbacks();
    }

    void unregisterObserversAndCallbacks() {
        mInfoRepository.unregisterObserversAndCallbacks();
    }
}