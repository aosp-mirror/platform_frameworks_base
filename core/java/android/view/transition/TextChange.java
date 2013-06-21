/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.view.transition;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.util.ArrayMap;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.Map;

/**
 * This transition tracks changes to the text in TextView targets. If the text
 * changes between the start and end scenes, the transition ensures that the
 * starting text stays until the transition ends, at which point it changes
 * to the end text.  This is useful in situations where you want to resize a
 * text view to its new size before displaying the text that goes there.
 */
public class TextChange extends Transition {
    private static final String PROPNAME_TEXT = "android:textchange:text";

    // TODO: think about other options we could have here, like cross-fading the text, or fading
    // it out/in. These could be parameters to supply to the constructors (and xml attributes).

    @Override
    protected void captureValues(TransitionValues values, boolean start) {
        if (values.view instanceof TextView) {
            TextView textview = (TextView) values.view;
            values.values.put(PROPNAME_TEXT, textview.getText());
        }
    }

    @Override
    protected Animator play(ViewGroup sceneRoot, TransitionValues startValues,
            TransitionValues endValues) {
        if (startValues == null || endValues == null || !(endValues.view instanceof TextView)) {
            return null;
        }
        final TextView view = (TextView) endValues.view;
        Map<String, Object> startVals = startValues.values;
        Map<String, Object> endVals = endValues.values;
        String startText = (String) startVals.get(PROPNAME_TEXT);
        final String endText = (String) endVals.get(PROPNAME_TEXT);
        if (!startText.equals(endText)) {
            view.setText(startText);
            ValueAnimator anim = ValueAnimator.ofFloat(0, 1);
            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    view.setText(endText);
                }
            });
            return anim;
        }
        return null;
    }
}
