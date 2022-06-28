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

package com.android.systemui.dreams.complication;

import androidx.lifecycle.LiveData;

import com.android.systemui.dreams.DreamOverlayStateController;

import java.util.Collection;

import javax.inject.Inject;

/**
 * {@link ComplicationCollectionLiveData} wraps
 * {@link DreamOverlayStateController#getComplications()} to provide an observable
 * {@link Complication} data set tied to a lifecycle. This should not be directly accessed. Instead,
 * clients should access the data from {@link ComplicationCollectionViewModel}.
 */
public class ComplicationCollectionLiveData extends LiveData<Collection<Complication>> {
    final DreamOverlayStateController mDreamOverlayStateController;

    final DreamOverlayStateController.Callback mStateControllerCallback;

    {
        mStateControllerCallback = new DreamOverlayStateController.Callback() {
            @Override
            public void onComplicationsChanged() {
                setValue(mDreamOverlayStateController.getComplications());
            }

            @Override
            public void onAvailableComplicationTypesChanged() {
                setValue(mDreamOverlayStateController.getComplications());
            }
        };
    }

    @Inject
    public ComplicationCollectionLiveData(DreamOverlayStateController stateController) {
        super();
        mDreamOverlayStateController = stateController;
    }

    @Override
    protected void onActive() {
        super.onActive();
        mDreamOverlayStateController.addCallback(mStateControllerCallback);
        setValue(mDreamOverlayStateController.getComplications());
    }

    @Override
    protected void onInactive() {
        mDreamOverlayStateController.removeCallback(mStateControllerCallback);
        super.onInactive();
    }
}
