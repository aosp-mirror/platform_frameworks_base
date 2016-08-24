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
 * A classifier which looks at the distance between the first and the last point from the stroke.
 */
public class EndPointLengthClassifier extends StrokeClassifier {
    public EndPointLengthClassifier(ClassifierData classifierData) {
    }

    @Override
    public String getTag() {
        return "END_LNGTH";
    }

    @Override
    public float getFalseTouchEvaluation(int type, Stroke stroke) {
        return EndPointLengthEvaluator.evaluate(stroke.getEndPointLength());
    }
}