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
 * An abstract class for classifiers which classify the whole gesture (all the strokes which
 * occurred from DOWN event to UP/CANCEL event)
 */
public abstract class GestureClassifier extends Classifier {

    /**
     * @param type the type of action for which this method is called
     * @return a non-negative value which is used to determine whether the most recent gesture is a
     *         false interaction; the bigger the value the greater the chance that this a false
     *         interaction.
     */
    public abstract float getFalseTouchEvaluation(int type);
}
