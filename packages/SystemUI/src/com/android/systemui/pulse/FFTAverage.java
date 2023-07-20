/**
 *
 * @author: Ritayan Chakraborty <ritayanout@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.pulse;

import java.util.ArrayDeque;

class FFTAverage {
    private static final int WINDOW_LENGTH = 2;
    private static final float WINDOW_LENGTH_F = WINDOW_LENGTH;
    private ArrayDeque<Float> window = new ArrayDeque<>(WINDOW_LENGTH);
    private float average;

    int average(int dB) {
        // Waiting until window is full
        if (window.size() >= WINDOW_LENGTH) {
            Float first = window.pollFirst();
            if (first != null)
                average -= first;
        }
        float newValue = dB / WINDOW_LENGTH_F;
        average += newValue;
        window.offerLast(newValue);

        return Math.round(average);
    }
}
