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

import com.android.systemui.dreams.complication.dagger.ComplicationViewModelComponent;

import java.util.HashMap;

import javax.inject.Inject;

/**
 * The {@link ComplicationViewModelTransformer} responsibility is provide a mapping from
 * {@link Complication} to {@link ComplicationViewModel}.
 */
public class ComplicationViewModelTransformer {
    private final ComplicationId.Factory mComplicationIdFactory = new ComplicationId.Factory();
    private final HashMap<Complication, ComplicationId> mComplicationIdMapping = new HashMap<>();
    private final ComplicationViewModelComponent.Factory mViewModelComponentFactory;

    @Inject
    public ComplicationViewModelTransformer(
            ComplicationViewModelComponent.Factory viewModelComponentFactory) {
        mViewModelComponentFactory = viewModelComponentFactory;
    }

    /**
     * Generates {@link ComplicationViewModel} from a {@link Complication}.
     */
    public ComplicationViewModel getViewModel(Complication complication) {
        final ComplicationId id = getComplicationId(complication);
        return mViewModelComponentFactory.create(complication, id)
                .getViewModelProvider().get(id.toString(), ComplicationViewModel.class);
    }

    private ComplicationId getComplicationId(Complication complication) {
        if (!mComplicationIdMapping.containsKey(complication)) {
            mComplicationIdMapping.put(complication, mComplicationIdFactory.getNextId());
        }

        return mComplicationIdMapping.get(complication);
    }
}
