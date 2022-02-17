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
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;

import java.util.Collection;
import java.util.stream.Collectors;

import javax.inject.Inject;

/**
 * {@link ComplicationCollectionViewModel} is an abstraction for observing and accessing
 * {@link ComplicationViewModel} for registered {@link Complication}.
 */
public class ComplicationCollectionViewModel extends ViewModel {
    private final LiveData<Collection<ComplicationViewModel>> mComplications;
    private final ComplicationViewModelTransformer mTransformer;

    /**
     * Injectable constructor for {@link ComplicationCollectionViewModel}. Note that this cannot
     * be implicitly injected. Clients must bind scoped instance values through the corresponding
     * dagger subcomponent.
     */
    @Inject
    public ComplicationCollectionViewModel(
            ComplicationCollectionLiveData complications,
            ComplicationViewModelTransformer transformer) {
        mComplications = Transformations.map(complications, collection -> convert(collection));
        mTransformer = transformer;
    }

    private Collection<ComplicationViewModel> convert(Collection<Complication> complications) {
        return complications
                .stream()
                .map(complication -> mTransformer.getViewModel(complication))
                .collect(Collectors.toSet());
    }

    /**
     * Returns {@link LiveData} for the collection of {@link Complication} represented as
     * {@link ComplicationViewModel}.
     */
    public LiveData<Collection<ComplicationViewModel>> getComplications() {
        return mComplications;
    }
}
