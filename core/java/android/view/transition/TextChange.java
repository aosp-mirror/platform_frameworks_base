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
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.graphics.Color;
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
    private static final String PROPNAME_TEXT_COLOR = "android:textchange:textColor";

    private int mChangeBehavior = CHANGE_BEHAVIOR_KEEP;

    /**
     * Flag specifying that the text in affected/changing TextView targets will keep
     * their original text during the transition, setting it to the final text when
     * the transition ends. This is the default behavior.
     *
     * @see #setChangeBehavior(int)
     */
    public static final int CHANGE_BEHAVIOR_KEEP = 0;
    /**
     * Flag specifying that the text changing animation should first fade
     * out the original text completely. The new text is set on the target
     * view at the end of the fade-out animation. This transition is typically
     * used with a later {@link #CHANGE_BEHAVIOR_IN} transition, allowing more
     * flexibility than the {@link #CHANGE_BEHAVIOR_OUT_IN} by allowing other
     * transitions to be run sequentially or in parallel with these fades.
     *
     * @see #setChangeBehavior(int)
     */
    public static final int CHANGE_BEHAVIOR_OUT = 1;
    /**
     * Flag specifying that the text changing animation should fade in the
     * end text into the affected target view(s). This transition is typically
     * used in conjunction with an earlier {@link #CHANGE_BEHAVIOR_OUT}
     * transition, possibly with other transitions running as well, such as
     * a sequence to fade out, then resize the view, then fade in.
     *
     * @see #setChangeBehavior(int)
     */
    public static final int CHANGE_BEHAVIOR_IN = 2;
    /**
     * Flag specifying that the text changing animation should first fade
     * out the original text completely and then fade in the
     * new text.
     *
     * @see #setChangeBehavior(int)
     */
    public static final int CHANGE_BEHAVIOR_OUT_IN = 3;

    /**
     * Sets the type of changing animation that will be run, one of
     * {@link #CHANGE_BEHAVIOR_KEEP} and {@link #CHANGE_BEHAVIOR_OUT_IN}.
     *
     * @param changeBehavior The type of fading animation to use when this
     * transition is run.
     */
    public void setChangeBehavior(int changeBehavior) {
        if (changeBehavior >= CHANGE_BEHAVIOR_KEEP && changeBehavior <= CHANGE_BEHAVIOR_OUT_IN) {
            mChangeBehavior = changeBehavior;
        }
    }

    @Override
    protected void captureValues(TransitionValues values, boolean start) {
        if (values.view instanceof TextView) {
            TextView textview = (TextView) values.view;
            values.values.put(PROPNAME_TEXT, textview.getText());
            if (mChangeBehavior > CHANGE_BEHAVIOR_KEEP) {
                values.values.put(PROPNAME_TEXT_COLOR, textview.getCurrentTextColor());
            }
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
            Animator anim;
            if (mChangeBehavior == CHANGE_BEHAVIOR_KEEP) {
                anim = ValueAnimator.ofFloat(0, 1);
                anim.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        view.setText(endText);
                    }
                });
            } else {
                // Fade out start text
                final int startColor = (Integer) startVals.get(PROPNAME_TEXT_COLOR);
                final int endColor = (Integer) endVals.get(PROPNAME_TEXT_COLOR);
                ValueAnimator outAnim = null, inAnim = null;
                if (mChangeBehavior == CHANGE_BEHAVIOR_OUT_IN ||
                        mChangeBehavior == CHANGE_BEHAVIOR_OUT) {
                    outAnim = ValueAnimator.ofInt(255, 0);
                    outAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                        @Override
                        public void onAnimationUpdate(ValueAnimator animation) {
                            int currAlpha = (Integer) animation.getAnimatedValue();
                            view.setTextColor(currAlpha << 24 | Color.red(startColor) << 16 |
                                    Color.green(startColor) << 8 | Color.red(startColor));
                        }
                    });
                    outAnim.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            view.setText(endText);
                        }
                    });
                }
                if (mChangeBehavior == CHANGE_BEHAVIOR_OUT_IN ||
                        mChangeBehavior == CHANGE_BEHAVIOR_IN) {
                    inAnim = ValueAnimator.ofInt(0, 255);
                    inAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                        @Override
                        public void onAnimationUpdate(ValueAnimator animation) {
                            int currAlpha = (Integer) animation.getAnimatedValue();
                            view.setTextColor(currAlpha << 24 | Color.red(endColor) << 16 |
                                    Color.green(endColor) << 8 | Color.red(endColor));
                        }
                    });
                }
                if (outAnim != null && inAnim != null) {
                    anim = new AnimatorSet();
                    ((AnimatorSet) anim).playSequentially(outAnim, inAnim);
                } else if (outAnim != null) {
                    anim = outAnim;
                } else {
                    // Must be an in-only animation
                    anim = inAnim;
                }
            }
            return anim;
        }
        return null;
    }
}
