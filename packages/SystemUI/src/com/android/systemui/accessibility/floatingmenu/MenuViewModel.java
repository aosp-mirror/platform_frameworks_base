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

import static com.android.internal.accessibility.AccessibilityShortcutController.ACCESSIBILITY_HEARING_AIDS_COMPONENT_NAME;

import static java.util.Collections.emptyList;

import android.content.ComponentName;
import android.content.Context;
import android.view.accessibility.AccessibilityManager;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.android.internal.accessibility.dialog.AccessibilityTarget;
import com.android.settingslib.bluetooth.HearingAidDeviceManager;
import com.android.systemui.util.settings.SecureSettings;

import java.util.List;

/**
 * The view model provides the menu information from the repository{@link MenuInfoRepository} for
 * the menu view{@link MenuView}.
 */
class MenuViewModel implements MenuInfoRepository.OnContentsChanged {
    private final MutableLiveData<List<AccessibilityTarget>> mTargetFeaturesData =
            new MutableLiveData<>(emptyList());
    private final MutableLiveData<Integer> mSizeTypeData = new MutableLiveData<>();
    private final MutableLiveData<MenuFadeEffectInfo> mFadeEffectInfoData =
            new MutableLiveData<>();
    private final MutableLiveData<Boolean> mMoveToTuckedData = new MutableLiveData<>();
    private final MutableLiveData<Boolean> mDockTooltipData = new MutableLiveData<>();
    private final MutableLiveData<Boolean> mMigrationTooltipData = new MutableLiveData<>();
    private final MutableLiveData<Position> mPercentagePositionData = new MutableLiveData<>();
    private final MutableLiveData<Integer> mHearingDeviceStatusData = new MutableLiveData<>(
            HearingAidDeviceManager.ConnectionStatus.NO_DEVICE_BONDED);
    private final LiveData<Integer> mHearingDeviceTargetIndex = Transformations.map(
            mTargetFeaturesData, this::getHearingDeviceTargetIndex);

    private final MenuInfoRepository mInfoRepository;

    MenuViewModel(Context context, AccessibilityManager accessibilityManager,
            SecureSettings secureSettings, HearingAidDeviceManager hearingAidDeviceManager) {
        mInfoRepository = new MenuInfoRepository(context,
                accessibilityManager, /* settingsContentsChanged= */ this, secureSettings,
                hearingAidDeviceManager);
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

    @Override
    public void onDevicesConnectionStatusChanged(
            @HearingAidDeviceManager.ConnectionStatus int status) {
        mHearingDeviceStatusData.postValue(status);
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

    LiveData<Integer> loadHearingDeviceStatus() {
        mInfoRepository.loadHearingDeviceStatus(mHearingDeviceStatusData::setValue);
        return mHearingDeviceStatusData;
    }

    LiveData<Integer> getHearingDeviceStatusData() {
        return mHearingDeviceStatusData;
    }

    LiveData<Integer> getHearingDeviceTargetIndexData() {
        return mHearingDeviceTargetIndex;
    }

    void registerObserversAndCallbacks() {
        mInfoRepository.registerObserversAndCallbacks();
    }

    void unregisterObserversAndCallbacks() {
        mInfoRepository.unregisterObserversAndCallbacks();
    }

    private int getHearingDeviceTargetIndex(List<AccessibilityTarget> targetList) {
        final int listSize = targetList.size();
        for (int index = 0; index < listSize; index++) {
            AccessibilityTarget target = targetList.get(index);
            if (ACCESSIBILITY_HEARING_AIDS_COMPONENT_NAME.equals(
                    ComponentName.unflattenFromString(target.getId()))) {
                return index;
            }
        }
        return -1;
    }
}