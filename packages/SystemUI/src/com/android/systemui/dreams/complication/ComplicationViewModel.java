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

import androidx.lifecycle.ViewModel;

import javax.inject.Inject;

/**
 * {@link ComplicationViewModel} is an abstraction over {@link Complication}, providing the model
 * from which any view-related interpretation of the {@link Complication} should be derived from.
 */
public class ComplicationViewModel extends ViewModel {
    private final Complication mComplication;
    private final ComplicationId mId;
    private final Complication.Host mHost;

    /**
     * Default constructor for generating a {@link ComplicationViewModel}.
     * @param complication The {@link Complication} represented by the view model.
     * @param id The {@link ComplicationId} tied to this {@link Complication}.
     * @param host The environment {@link Complication.Host}.
     */
    @Inject
    public ComplicationViewModel(Complication complication, ComplicationId id,
            Complication.Host host) {
        mComplication = complication;
        mId = id;
        mHost = host;
    }

    /**
     * Returns the {@link ComplicationId} for this {@link Complication} for stable id association.
     */
    public ComplicationId getId() {
        return mId;
    }

    /**
     * Returns the underlying {@link Complication}. Should only as a redirection - for example,
     * using the {@link Complication} to generate view. Any property should be surfaced through
     * this ViewModel.
     */
    public Complication getComplication() {
        return mComplication;
    }

    /**
     * Requests the dream exit on behalf of the {@link Complication}.
     */
    public void exitDream() {
        mHost.requestExitDream();
    }
}
