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
package com.android.systemui.classifier;

import static com.android.systemui.classifier.FalsingModule.LONG_TAP_TOUCH_SLOP;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Falsing classifier that accepts or rejects a gesture as a long tap.
 */
public class LongTapClassifier extends TapClassifier{

    @Inject
    LongTapClassifier(FalsingDataProvider dataProvider,
            @Named(LONG_TAP_TOUCH_SLOP) float touchSlop) {
        super(dataProvider, touchSlop);
    }

}
