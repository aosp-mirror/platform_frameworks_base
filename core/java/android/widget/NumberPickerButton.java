/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.ImageButton;
import android.widget.NumberPicker;

import com.android.internal.R;

/**
 * This class exists purely to cancel long click events, that got
 * started in NumberPicker
 */
class NumberPickerButton extends ImageButton {

    private NumberPicker mNumberPicker;

    public NumberPickerButton(Context context, AttributeSet attrs,
            int defStyle) {
        super(context, attrs, defStyle);
    }

    public NumberPickerButton(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public NumberPickerButton(Context context) {
        super(context);
    }

    public void setNumberPicker(NumberPicker picker) {
        mNumberPicker = picker;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        cancelLongpressIfRequired(event);
        return super.onTouchEvent(event);
    }

    @Override
    public boolean onTrackballEvent(MotionEvent event) {
        cancelLongpressIfRequired(event);
        return super.onTrackballEvent(event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER)
                || (keyCode == KeyEvent.KEYCODE_ENTER)) {
            cancelLongpress();
        }
        return super.onKeyUp(keyCode, event);
    }

    private void cancelLongpressIfRequired(MotionEvent event) {
        if ((event.getAction() == MotionEvent.ACTION_CANCEL)
                || (event.getAction() == MotionEvent.ACTION_UP)) {
            cancelLongpress();
        }
    }

    private void cancelLongpress() {
        if (R.id.increment == getId()) {
            mNumberPicker.cancelIncrement();
        } else if (R.id.decrement == getId()) {
            mNumberPicker.cancelDecrement();
        }
    }

    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) {
            cancelLongpress();
        }
    }

}
