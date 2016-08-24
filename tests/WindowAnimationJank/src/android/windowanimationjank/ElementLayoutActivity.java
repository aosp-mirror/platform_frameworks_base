/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package android.windowanimationjank;

import java.util.Random;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewTreeObserver.OnPreDrawListener;
import android.widget.Chronometer;
import android.widget.RadioButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.ToggleButton;

/*
 * Activity with arbitrary number of random UI elements, refresh itself constantly.
 */
public class ElementLayoutActivity extends Activity implements OnPreDrawListener {
    public final static String NUM_ELEMENTS_KEY = "num_elements";

    private final static int DEFAULT_NUM_ELEMENTS = 100;
    private final static int BACKGROUND_COLOR = 0xfffff000;
    private final static int INDICATOR_COLOR = 0xffff0000;

    private FlowLayout mLayout;
    // Use the constant seed in order to get predefined order of elements.
    private Random mRandom = new Random(0);
    // Blinker indicator for visual feedback that Activity is currently updating.
    private TextView mIndicator;
    private static float mIndicatorState;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.flowlayout);

        mLayout = (FlowLayout)findViewById(R.id.root_flow_layout);
        mLayout.setBackgroundColor(BACKGROUND_COLOR);

        mIndicator = new TextView(this);
        mLayout.addView(mIndicator);
        mIndicator.setText("***\n***");
        mIndicator.setBackgroundColor(BACKGROUND_COLOR);
        mIndicatorState = 0.0f;

        // Need constantly invalidate view in order to get max redraw rate.
        mLayout.getViewTreeObserver().addOnPreDrawListener(this);

        // Read requested number of elements in layout.
        int numElements = getIntent().getIntExtra(NUM_ELEMENTS_KEY, DEFAULT_NUM_ELEMENTS);

        for (int i = 0; i < numElements; ++i) {
            switch (mRandom.nextInt(5)) {
            case 0:
                createRadioButton();
                break;
            case 1:
                createToggleButton();
                break;
            case 2:
                createSwitch();
                break;
            case 3:
                createTextView();
                break;
            case 4:
                createChronometer();
                break;
            }
        }

        setContentView(mLayout);
    }

    private void createTextView() {
        TextView textView = new TextView(this);
        int lineCnt = mRandom.nextInt(4);
        StringBuffer buffer = new StringBuffer();
        for (int i = 0; i < lineCnt; ++i) {
            if (i != 0) {
                buffer.append("\n");
            }
            buffer.append("Line:" + mRandom.nextInt());
        }
        textView.setText(buffer);
        mLayout.addView(textView);
    }

    private void createRadioButton() {
        RadioButton button = new RadioButton(this);
        button.setText("RadioButton:" + mRandom.nextInt());
        mLayout.addView(button);
    }

    private void createToggleButton() {
        ToggleButton button = new ToggleButton(this);
        button.setChecked(mRandom.nextBoolean());
        mLayout.addView(button);
    }

    private void createSwitch() {
        Switch button = new Switch(this);
        button.setChecked(mRandom.nextBoolean());
        mLayout.addView(button);
    }

    private void createChronometer() {
        Chronometer chronometer = new Chronometer(this);
        chronometer.setBase(mRandom.nextLong());
        mLayout.addView(chronometer);
        chronometer.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    public boolean onPreDraw() {
        // Interpolate indicator color
        int background = 0xff000000;
        for (int i = 0; i < 3; ++i) {
            int shift = 8 * i;
            int colorB = (BACKGROUND_COLOR >> shift) & 0xff;
            int colorI = (INDICATOR_COLOR >> shift) & 0xff;
            int color = (int)((float)colorB * (1.0f - mIndicatorState) +
                    (float)colorI * mIndicatorState);
            if (color > 255) {
                color = 255;
            }
            background |= (color << shift);
        }

        mIndicator.setBackgroundColor(background);
        mIndicatorState += (3 / 60.0f);  // around 3 times per second
        mIndicatorState = mIndicatorState - (int)mIndicatorState;

        mLayout.postInvalidate();
        return true;
    }
}