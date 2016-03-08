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
 * A classifier which looks at the ratio between the total length covered by the stroke and the
 * distance between the first and last point from this stroke.
 */
public class EndPointRatioClassifier extends StrokeClassifier {
    public EndPointRatioClassifier(ClassifierData classifierData) {
        mClassifierData = classifierData;
    }

    @Override
    public String getTag() {
        return "END_RTIO";
    }

    @Override
    public float getFalseTouchEvaluation(int type, Stroke stroke) {
        float ratio;
        if (stroke.getTotalLength() == 0.0f) {
            ratio = 1.0f;
        } else {
            ratio = stroke.getEndPointLength() / stroke.getTotalLength();
        }
        return EndPointRatioEvaluator.evaluate(ratio);
    }
}