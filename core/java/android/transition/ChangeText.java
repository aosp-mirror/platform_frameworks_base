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

package android.transition;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Color;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import java.util.Map;

/**
 * This transition tracks changes to the text in TextView targets. If the text
 * changes between the start and end scenes, the transition ensures that the
 * starting text stays until the transition ends, at which point it changes
 * to the end text.  This is useful in situations where you want to resize a
 * text view to its new size before displaying the text that goes there.
 *
 * @hide
 */
public class ChangeText extends Transition {

    private static final String LOG_TAG = "TextChange";

    private static final String PROPNAME_TEXT = "android:textchange:text";
    private static final String PROPNAME_TEXT_SELECTION_START =
            "android:textchange:textSelectionStart";
    private static final String PROPNAME_TEXT_SELECTION_END =
            "android:textchange:textSelectionEnd";
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

    private static final String[] sTransitionProperties = {
            PROPNAME_TEXT,
            PROPNAME_TEXT_SELECTION_START,
            PROPNAME_TEXT_SELECTION_END
    };

    /**
     * Sets the type of changing animation that will be run, one of
     * {@link #CHANGE_BEHAVIOR_KEEP}, {@link #CHANGE_BEHAVIOR_OUT},
     * {@link #CHANGE_BEHAVIOR_IN}, and {@link #CHANGE_BEHAVIOR_OUT_IN}.
     *
     * @param changeBehavior The type of fading animation to use when this
     * transition is run.
     * @return this textChange object.
     */
    public ChangeText setChangeBehavior(int changeBehavior) {
        if (changeBehavior >= CHANGE_BEHAVIOR_KEEP && changeBehavior <= CHANGE_BEHAVIOR_OUT_IN) {
            mChangeBehavior = changeBehavior;
        }
        return this;
    }

    @Override
    public String[] getTransitionProperties() {
        return sTransitionProperties;
    }

    /**
     * Returns the type of changing animation that will be run.
     *
     * @return either {@link #CHANGE_BEHAVIOR_KEEP}, {@link #CHANGE_BEHAVIOR_OUT},
     * {@link #CHANGE_BEHAVIOR_IN}, or {@link #CHANGE_BEHAVIOR_OUT_IN}.
     */
    public int getChangeBehavior() {
        return mChangeBehavior;
    }

    private void captureValues(TransitionValues transitionValues) {
        if (transitionValues.view instanceof TextView) {
            TextView textview = (TextView) transitionValues.view;
            transitionValues.values.put(PROPNAME_TEXT, textview.getText());
            if (textview instanceof EditText) {
                transitionValues.values.put(PROPNAME_TEXT_SELECTION_START,
                        textview.getSelectionStart());
                transitionValues.values.put(PROPNAME_TEXT_SELECTION_END,
                        textview.getSelectionEnd());
            }
            if (mChangeBehavior > CHANGE_BEHAVIOR_KEEP) {
                transitionValues.values.put(PROPNAME_TEXT_COLOR, textview.getCurrentTextColor());
            }
        }
    }

    @Override
    public void captureStartValues(TransitionValues transitionValues) {
        captureValues(transitionValues);
    }

    @Override
    public void captureEndValues(TransitionValues transitionValues) {
        captureValues(transitionValues);
    }

    @Nullable
    @Override
    public Animator createAnimator(@NonNull ViewGroup sceneRoot,
            @Nullable TransitionValues startValues,
            @Nullable TransitionValues endValues) {
        if (startValues == null || endValues == null ||
                !(startValues.view instanceof TextView) || !(endValues.view instanceof TextView)) {
            return null;
        }
        final TextView view = (TextView) endValues.view;
        Map<String, Object> startVals = startValues.values;
        Map<String, Object> endVals = endValues.values;
        final CharSequence startText = startVals.get(PROPNAME_TEXT) != null ?
                (CharSequence) startVals.get(PROPNAME_TEXT) : "";
        final CharSequence endText = endVals.get(PROPNAME_TEXT) != null ?
                (CharSequence) endVals.get(PROPNAME_TEXT) : "";
        final int startSelectionStart, startSelectionEnd, endSelectionStart, endSelectionEnd;
        if (view instanceof EditText) {
            startSelectionStart = startVals.get(PROPNAME_TEXT_SELECTION_START) != null ?
                    (Integer) startVals.get(PROPNAME_TEXT_SELECTION_START) : -1;
            startSelectionEnd = startVals.get(PROPNAME_TEXT_SELECTION_END) != null ?
                    (Integer) startVals.get(PROPNAME_TEXT_SELECTION_END) : startSelectionStart;
            endSelectionStart = endVals.get(PROPNAME_TEXT_SELECTION_START) != null ?
                    (Integer) endVals.get(PROPNAME_TEXT_SELECTION_START) : -1;
            endSelectionEnd = endVals.get(PROPNAME_TEXT_SELECTION_END) != null ?
                    (Integer) endVals.get(PROPNAME_TEXT_SELECTION_END) : endSelectionStart;
        } else {
            startSelectionStart = startSelectionEnd = endSelectionStart = endSelectionEnd = -1;
        }
        if (!startText.equals(endText)) {
            final int startColor;
            final int endColor;
            if (mChangeBehavior != CHANGE_BEHAVIOR_IN) {
                view.setText(startText);
                if (view instanceof EditText) {
                    setSelection(((EditText) view), startSelectionStart, startSelectionEnd);
                }
            }
            Animator anim;
            if (mChangeBehavior == CHANGE_BEHAVIOR_KEEP) {
                startColor = endColor = 0;
                anim = ValueAnimator.ofFloat(0, 1);
                anim.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (startText.equals(view.getText())) {
                            // Only set if it hasn't been changed since anim started
                            view.setText(endText);
                            if (view instanceof EditText) {
                                setSelection(((EditText) view), endSelectionStart, endSelectionEnd);
                            }
                        }
                    }
                });
            } else {
                startColor = (Integer) startVals.get(PROPNAME_TEXT_COLOR);
                endColor = (Integer) endVals.get(PROPNAME_TEXT_COLOR);
                // Fade out start text
                ValueAnimator outAnim = null, inAnim = null;
                if (mChangeBehavior == CHANGE_BEHAVIOR_OUT_IN ||
                        mChangeBehavior == CHANGE_BEHAVIOR_OUT) {
                    outAnim = ValueAnimator.ofInt(Color.alpha(startColor), 0);
                    outAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                        @Override
                        public void onAnimationUpdate(ValueAnimator animation) {
                            int currAlpha = (Integer) animation.getAnimatedValue();
                            view.setTextColor(currAlpha << 24 | startColor & 0xffffff);
                        }
                    });
                    outAnim.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            if (startText.equals(view.getText())) {
                                // Only set if it hasn't been changed since anim started
                                view.setText(endText);
                                if (view instanceof EditText) {
                                    setSelection(((EditText) view), endSelectionStart,
                                            endSelectionEnd);
                                }
                            }
                            // restore opaque alpha and correct end color
                            view.setTextColor(endColor);
                        }
                    });
                }
                if (mChangeBehavior == CHANGE_BEHAVIOR_OUT_IN ||
                        mChangeBehavior == CHANGE_BEHAVIOR_IN) {
                    inAnim = ValueAnimator.ofInt(0, Color.alpha(endColor));
                    inAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                        @Override
                        public void onAnimationUpdate(ValueAnimator animation) {
                            int currAlpha = (Integer) animation.getAnimatedValue();
                            view.setTextColor(currAlpha << 24 | endColor & 0xffffff);
                        }
                    });
                    inAnim.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationCancel(Animator animation) {
                            // restore opaque alpha and correct end color
                            view.setTextColor(endColor);
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
            TransitionListener transitionListener = new TransitionListenerAdapter() {
                int mPausedColor = 0;

                @Override
                public void onTransitionPause(Transition transition) {
                    if (mChangeBehavior != CHANGE_BEHAVIOR_IN) {
                        view.setText(endText);
                        if (view instanceof EditText) {
                            setSelection(((EditText) view), endSelectionStart, endSelectionEnd);
                        }
                    }
                    if (mChangeBehavior > CHANGE_BEHAVIOR_KEEP) {
                        mPausedColor = view.getCurrentTextColor();
                        view.setTextColor(endColor);
                    }
                }

                @Override
                public void onTransitionResume(Transition transition) {
                    if (mChangeBehavior != CHANGE_BEHAVIOR_IN) {
                        view.setText(startText);
                        if (view instanceof EditText) {
                            setSelection(((EditText) view), startSelectionStart, startSelectionEnd);
                        }
                    }
                    if (mChangeBehavior > CHANGE_BEHAVIOR_KEEP) {
                        view.setTextColor(mPausedColor);
                    }
                }

                @Override
                public void onTransitionEnd(Transition transition) {
                    transition.removeListener(this);
                }
            };
            addListener(transitionListener);
            if (DBG) {
                Log.d(LOG_TAG, "createAnimator returning " + anim);
            }
            return anim;
        }
        return null;
    }

    private void setSelection(EditText editText, int start, int end) {
        if (start >= 0 && end >= 0) {
            editText.setSelection(start, end);
        }
    }
}
