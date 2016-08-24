/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.classifier;

/**
 * A classifier which looks at the ratio between the length of the stroke and its number of
 * points. The number of points is subtracted by 2 because the UP event comes in with some delay
 * and it should not influence the ratio and also strokes which are long and have a small number
 * of points are punished more (these kind of strokes are usually bad ones and they tend to score
 * well in other classifiers).
 */
public class LengthCountClassifier extends StrokeClassifier {
    public LengthCountClassifier(ClassifierData classifierData) {
    }

    @Override
    public String getTag() {
        return "LEN_CNT";
    }

    @Override
    public float getFalseTouchEvaluation(int type, Stroke stroke) {
        return LengthCountEvaluator.evaluate(stroke.getTotalLength()
                / Math.max(1.0f, stroke.getCount() - 2));
    }
}