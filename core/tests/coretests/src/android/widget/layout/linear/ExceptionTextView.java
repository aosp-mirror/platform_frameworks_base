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

package android.widget.layout.linear;

import android.content.Context;
import android.text.BoringLayout;
import android.util.AttributeSet;
import android.widget.EditText;


/**
 * A special EditText that sets {@link #isFailed()} to true as its internal makeNewLayout() method is called
 * with a width lower than 0. This is used to fail the unit test in
 * BaselineAlignmentZeroWidthAndWeightTest.
 */
public class ExceptionTextView extends EditText {

    private boolean mFailed = false;

    public ExceptionTextView(Context context) {
        super(context);
    }

    public ExceptionTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ExceptionTextView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public boolean isFailed() {
        return mFailed;
    }

    @Override
    public void makeNewLayout(int w, int hintWidth, BoringLayout.Metrics boring,
            BoringLayout.Metrics hintMetrics, int ellipsizedWidth, boolean bringIntoView) {
        if (w < 0) {
            mFailed = true;
            w = 100;
        }

        super.makeNewLayout(w, hintWidth, boring, hintMetrics, ellipsizedWidth,
                            bringIntoView);
    }
}
