/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.widget;

import android.os.Handler;

/**
 * Provides callbacks indicating the steps in two digit pressing within a
 * timeout.
 *
 * Package private: only relevant in helping {@link TimeSpinnerHelper}.
 */
class DoubleDigitManager {

    private final long timeoutInMillis;
    private final CallBack mCallBack;

    private Integer intermediateDigit;

    /**
     * @param timeoutInMillis How long after the first digit is pressed does
     *   the user have to press the second digit?
     * @param callBack The callback to indicate what's going on with the user.
     */
    public DoubleDigitManager(long timeoutInMillis, CallBack callBack) {
        this.timeoutInMillis = timeoutInMillis;
        mCallBack = callBack;
    }

    /**
     * Report to this manager that a digit was pressed.
     * @param digit
     */
    public void reportDigit(int digit) {
        if (intermediateDigit == null) {
            intermediateDigit = digit;

            new Handler().postDelayed(new Runnable() {
                public void run() {
                    if (intermediateDigit != null) {
                        mCallBack.singleDigitFinal(intermediateDigit);
                        intermediateDigit = null;
                    }
                }
            }, timeoutInMillis);

            if (!mCallBack.singleDigitIntermediate(digit)) {

                // this wasn't a good candidate for the intermediate digit,
                // make it the final digit (since there is no opportunity to
                // reject the final digit).
                intermediateDigit = null;
                mCallBack.singleDigitFinal(digit);
            }
        } else if (mCallBack.twoDigitsFinal(intermediateDigit, digit)) {
             intermediateDigit = null;
        }
    }

    /**
     * The callback to indicate what is going on with the digits pressed.
     */
    static interface CallBack {

        /**
         * A digit was pressed, and there are no intermediate digits.
         * @param digit The digit pressed.
         * @return Whether the digit was accepted; how the user of this manager
         *   tells us that the intermediate digit is acceptable as an
         *   intermediate digit.
         */
        boolean singleDigitIntermediate(int digit);

        /**
         * A single digit was pressed, and it is 'the final answer'.
         * - a single digit pressed, and the timeout expires.
         * - a single digit pressed, and {@link #singleDigitIntermediate}
         *   returned false.
         * @param digit The digit.
         */
        void singleDigitFinal(int digit);

        /**
         * The user pressed digit1, then digit2 within the timeout.
         * @param digit1
         * @param digit2
         */
        boolean twoDigitsFinal(int digit1, int digit2);
    }

}
