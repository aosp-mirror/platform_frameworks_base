/*
 * Copyright (C) 2010 The Android Open Source Project
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
package android.animation;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.PathParser;
import android.util.StateSet;
import android.util.TypedValue;
import android.util.Xml;
import android.view.animation.AnimationUtils;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;

/**
 * This class is used to instantiate animator XML files into Animator objects.
 * <p>
 * For performance reasons, inflation relies heavily on pre-processing of
 * XML files that is done at build time. Therefore, it is not currently possible
 * to use this inflater with an XmlPullParser over a plain XML file at runtime;
 * it only works with an XmlPullParser returned from a compiled resource (R.
 * <em>something</em> file.)
 */
public class AnimatorInflater {

    /**
     * These flags are used when parsing AnimatorSet objects
     */
    private static final int TOGETHER = 0;
    private static final int SEQUENTIALLY = 1;

    /**
     * Enum values used in XML attributes to indicate the value for mValueType
     */
    private static final int VALUE_TYPE_FLOAT       = 0;
    private static final int VALUE_TYPE_INT         = 1;
    private static final int VALUE_TYPE_COLOR       = 4;
    private static final int VALUE_TYPE_CUSTOM      = 5;

    /**
     * Loads an {@link Animator} object from a resource
     *
     * @param context Application context used to access resources
     * @param id The resource id of the animation to load
     * @return The animator object reference by the specified id
     * @throws android.content.res.Resources.NotFoundException when the animation cannot be loaded
     */
    public static Animator loadAnimator(Context context, int id)
            throws NotFoundException {
        return loadAnimator(context.getResources(), context.getTheme(), id);
    }

    /**
     * Loads an {@link Animator} object from a resource
     *
     * @param resources The resources
     * @param theme The theme
     * @param id The resource id of the animation to load
     * @return The animator object reference by the specified id
     * @throws android.content.res.Resources.NotFoundException when the animation cannot be loaded
     * @hide
     */
    public static Animator loadAnimator(Resources resources, Theme theme, int id)
            throws NotFoundException {

        XmlResourceParser parser = null;
        try {
            parser = resources.getAnimation(id);
            return createAnimatorFromXml(resources, theme, parser);
        } catch (XmlPullParserException ex) {
            Resources.NotFoundException rnf =
                    new Resources.NotFoundException("Can't load animation resource ID #0x" +
                    Integer.toHexString(id));
            rnf.initCause(ex);
            throw rnf;
        } catch (IOException ex) {
            Resources.NotFoundException rnf =
                    new Resources.NotFoundException("Can't load animation resource ID #0x" +
                    Integer.toHexString(id));
            rnf.initCause(ex);
            throw rnf;
        } finally {
            if (parser != null) parser.close();
        }
    }

    public static StateListAnimator loadStateListAnimator(Context context, int id)
            throws NotFoundException {
        XmlResourceParser parser = null;
        try {
            parser = context.getResources().getAnimation(id);
            return createStateListAnimatorFromXml(context, parser, Xml.asAttributeSet(parser));
        } catch (XmlPullParserException ex) {
            Resources.NotFoundException rnf =
                    new Resources.NotFoundException(
                            "Can't load state list animator resource ID #0x" +
                                    Integer.toHexString(id)
                    );
            rnf.initCause(ex);
            throw rnf;
        } catch (IOException ex) {
            Resources.NotFoundException rnf =
                    new Resources.NotFoundException(
                            "Can't load state list animator resource ID #0x" +
                                    Integer.toHexString(id)
                    );
            rnf.initCause(ex);
            throw rnf;
        } finally {
            if (parser != null) {
                parser.close();
            }
        }
    }

    private static StateListAnimator createStateListAnimatorFromXml(Context context,
            XmlPullParser parser, AttributeSet attributeSet)
            throws IOException, XmlPullParserException {
        int type;
        StateListAnimator stateListAnimator = new StateListAnimator();

        while (true) {
            type = parser.next();
            switch (type) {
                case XmlPullParser.END_DOCUMENT:
                case XmlPullParser.END_TAG:
                    return stateListAnimator;

                case XmlPullParser.START_TAG:
                    // parse item
                    Animator animator = null;
                    if ("item".equals(parser.getName())) {
                        int attributeCount = parser.getAttributeCount();
                        int[] states = new int[attributeCount];
                        int stateIndex = 0;
                        for (int i = 0; i < attributeCount; i++) {
                            int attrName = attributeSet.getAttributeNameResource(i);
                            if (attrName == R.attr.animation) {
                                animator = loadAnimator(context,
                                        attributeSet.getAttributeResourceValue(i, 0));
                            } else {
                                states[stateIndex++] =
                                        attributeSet.getAttributeBooleanValue(i, false) ?
                                                attrName : -attrName;
                            }

                        }
                        if (animator == null) {
                            animator = createAnimatorFromXml(context.getResources(),
                                    context.getTheme(), parser);
                        }

                        if (animator == null) {
                            throw new Resources.NotFoundException(
                                    "animation state item must have a valid animation");
                        }
                        stateListAnimator
                                .addState(StateSet.trimStateSet(states, stateIndex), animator);

                    }
                    break;
            }
        }
    }

    /**
     * @param anim Null if this is a ValueAnimator, otherwise this is an
     *            ObjectAnimator
     * @param arrayAnimator Incoming typed array for Animator's attributes.
     * @param arrayObjectAnimator Incoming typed array for Object Animator's
     *            attributes.
     */
    private static void parseAnimatorFromTypeArray(ValueAnimator anim,
            TypedArray arrayAnimator, TypedArray arrayObjectAnimator) {
        long duration = arrayAnimator.getInt(R.styleable.Animator_duration, 300);

        long startDelay = arrayAnimator.getInt(R.styleable.Animator_startOffset, 0);

        int valueType = arrayAnimator.getInt(R.styleable.Animator_valueType,
                VALUE_TYPE_FLOAT);

        if (anim == null) {
            anim = new ValueAnimator();
        }

        TypeEvaluator evaluator = null;
        int valueFromIndex = R.styleable.Animator_valueFrom;
        int valueToIndex = R.styleable.Animator_valueTo;

        boolean getFloats = (valueType == VALUE_TYPE_FLOAT);

        TypedValue tvFrom = arrayAnimator.peekValue(valueFromIndex);
        boolean hasFrom = (tvFrom != null);
        int fromType = hasFrom ? tvFrom.type : 0;
        TypedValue tvTo = arrayAnimator.peekValue(valueToIndex);
        boolean hasTo = (tvTo != null);
        int toType = hasTo ? tvTo.type : 0;

        if ((hasFrom && (fromType >= TypedValue.TYPE_FIRST_COLOR_INT) &&
                (fromType <= TypedValue.TYPE_LAST_COLOR_INT)) ||
                (hasTo && (toType >= TypedValue.TYPE_FIRST_COLOR_INT) &&
                        (toType <= TypedValue.TYPE_LAST_COLOR_INT))) {
            // special case for colors: ignore valueType and get ints
            getFloats = false;
            evaluator = ArgbEvaluator.getInstance();
        }

        if (getFloats) {
            float valueFrom;
            float valueTo;
            if (hasFrom) {
                if (fromType == TypedValue.TYPE_DIMENSION) {
                    valueFrom = arrayAnimator.getDimension(valueFromIndex, 0f);
                } else {
                    valueFrom = arrayAnimator.getFloat(valueFromIndex, 0f);
                }
                if (hasTo) {
                    if (toType == TypedValue.TYPE_DIMENSION) {
                        valueTo = arrayAnimator.getDimension(valueToIndex, 0f);
                    } else {
                        valueTo = arrayAnimator.getFloat(valueToIndex, 0f);
                    }
                    anim.setFloatValues(valueFrom, valueTo);
                } else {
                    anim.setFloatValues(valueFrom);
                }
            } else {
                if (toType == TypedValue.TYPE_DIMENSION) {
                    valueTo = arrayAnimator.getDimension(valueToIndex, 0f);
                } else {
                    valueTo = arrayAnimator.getFloat(valueToIndex, 0f);
                }
                anim.setFloatValues(valueTo);
            }
        } else {
            int valueFrom;
            int valueTo;
            if (hasFrom) {
                if (fromType == TypedValue.TYPE_DIMENSION) {
                    valueFrom = (int) arrayAnimator.getDimension(valueFromIndex, 0f);
                } else if ((fromType >= TypedValue.TYPE_FIRST_COLOR_INT) &&
                        (fromType <= TypedValue.TYPE_LAST_COLOR_INT)) {
                    valueFrom = arrayAnimator.getColor(valueFromIndex, 0);
                } else {
                    valueFrom = arrayAnimator.getInt(valueFromIndex, 0);
                }
                if (hasTo) {
                    if (toType == TypedValue.TYPE_DIMENSION) {
                        valueTo = (int) arrayAnimator.getDimension(valueToIndex, 0f);
                    } else if ((toType >= TypedValue.TYPE_FIRST_COLOR_INT) &&
                            (toType <= TypedValue.TYPE_LAST_COLOR_INT)) {
                        valueTo = arrayAnimator.getColor(valueToIndex, 0);
                    } else {
                        valueTo = arrayAnimator.getInt(valueToIndex, 0);
                    }
                    anim.setIntValues(valueFrom, valueTo);
                } else {
                    anim.setIntValues(valueFrom);
                }
            } else {
                if (hasTo) {
                    if (toType == TypedValue.TYPE_DIMENSION) {
                        valueTo = (int) arrayAnimator.getDimension(valueToIndex, 0f);
                    } else if ((toType >= TypedValue.TYPE_FIRST_COLOR_INT) &&
                            (toType <= TypedValue.TYPE_LAST_COLOR_INT)) {
                        valueTo = arrayAnimator.getColor(valueToIndex, 0);
                    } else {
                        valueTo = arrayAnimator.getInt(valueToIndex, 0);
                    }
                    anim.setIntValues(valueTo);
                }
            }
        }

        anim.setDuration(duration);
        anim.setStartDelay(startDelay);

        if (arrayAnimator.hasValue(R.styleable.Animator_repeatCount)) {
            anim.setRepeatCount(
                    arrayAnimator.getInt(R.styleable.Animator_repeatCount, 0));
        }
        if (arrayAnimator.hasValue(R.styleable.Animator_repeatMode)) {
            anim.setRepeatMode(
                    arrayAnimator.getInt(R.styleable.Animator_repeatMode,
                            ValueAnimator.RESTART));
        }
        if (evaluator != null) {
            anim.setEvaluator(evaluator);
        }

        if (arrayObjectAnimator != null) {
            ObjectAnimator oa = (ObjectAnimator) anim;
            String pathData = arrayObjectAnimator.getString(R.styleable.PropertyAnimator_pathData);

            // Note that if there is a pathData defined in the Object Animator,
            // valueFrom / valueTo will be overwritten by the pathData.
            if (pathData != null) {
                String propertyXName =
                        arrayObjectAnimator.getString(R.styleable.PropertyAnimator_propertyXName);
                String propertyYName =
                        arrayObjectAnimator.getString(R.styleable.PropertyAnimator_propertyYName);

                if (propertyXName == null && propertyYName == null) {
                    throw new IllegalArgumentException("propertyXName or propertyYName"
                            + " is needed for PathData in Object Animator");
                } else {
                    Path path = PathParser.createPathFromPathData(pathData);
                    Keyframe[][] keyframes = PropertyValuesHolder.createKeyframes(path, !getFloats);
                    PropertyValuesHolder x = null;
                    PropertyValuesHolder y = null;
                    if (propertyXName != null) {
                        x = PropertyValuesHolder.ofKeyframe(propertyXName, keyframes[0]);
                    }
                    if (propertyYName != null) {
                        y = PropertyValuesHolder.ofKeyframe(propertyYName, keyframes[1]);
                    }
                    if (x == null) {
                        oa.setValues(y);
                    } else if (y == null) {
                        oa.setValues(x);
                    } else {
                        oa.setValues(x, y);
                    }
                }
            } else {
                String propertyName =
                        arrayObjectAnimator.getString(R.styleable.PropertyAnimator_propertyName);
                oa.setPropertyName(propertyName);
            }
        }
    }

    private static Animator createAnimatorFromXml(Resources res, Theme theme, XmlPullParser parser)
            throws XmlPullParserException, IOException {
        return createAnimatorFromXml(res, theme, parser, Xml.asAttributeSet(parser), null, 0);
    }

    private static Animator createAnimatorFromXml(Resources res, Theme theme, XmlPullParser parser,
            AttributeSet attrs, AnimatorSet parent, int sequenceOrdering)
            throws XmlPullParserException, IOException {

        Animator anim = null;
        ArrayList<Animator> childAnims = null;

        // Make sure we are on a start tag.
        int type;
        int depth = parser.getDepth();

        while (((type = parser.next()) != XmlPullParser.END_TAG || parser.getDepth() > depth)
                && type != XmlPullParser.END_DOCUMENT) {

            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            String name = parser.getName();

            if (name.equals("objectAnimator")) {
                anim = loadObjectAnimator(res, theme, attrs);
            } else if (name.equals("animator")) {
                anim = loadAnimator(res, theme, attrs, null);
            } else if (name.equals("set")) {
                anim = new AnimatorSet();
                TypedArray a;
                if (theme != null) {
                    a = theme.obtainStyledAttributes(attrs, R.styleable.AnimatorSet, 0, 0);
                } else {
                    a = res.obtainAttributes(attrs, R.styleable.AnimatorSet);
                }
                int ordering = a.getInt(R.styleable.AnimatorSet_ordering,
                        TOGETHER);
                createAnimatorFromXml(res, theme, parser, attrs, (AnimatorSet) anim, ordering);
                a.recycle();
            } else {
                throw new RuntimeException("Unknown animator name: " + parser.getName());
            }

            if (parent != null) {
                if (childAnims == null) {
                    childAnims = new ArrayList<Animator>();
                }
                childAnims.add(anim);
            }
        }
        if (parent != null && childAnims != null) {
            Animator[] animsArray = new Animator[childAnims.size()];
            int index = 0;
            for (Animator a : childAnims) {
                animsArray[index++] = a;
            }
            if (sequenceOrdering == TOGETHER) {
                parent.playTogether(animsArray);
            } else {
                parent.playSequentially(animsArray);
            }
        }

        return anim;

    }

    private static ObjectAnimator loadObjectAnimator(Resources res, Theme theme, AttributeSet attrs)
            throws NotFoundException {
        ObjectAnimator anim = new ObjectAnimator();

        loadAnimator(res, theme, attrs, anim);

        return anim;
    }

    /**
     * Creates a new animation whose parameters come from the specified context
     * and attributes set.
     *
     * @param res The resources
     * @param attrs The set of attributes holding the animation parameters
     * @param anim Null if this is a ValueAnimator, otherwise this is an
     *            ObjectAnimator
     */
    private static ValueAnimator loadAnimator(Resources res, Theme theme,
            AttributeSet attrs, ValueAnimator anim)
            throws NotFoundException {

        TypedArray arrayAnimator = null;
        TypedArray arrayObjectAnimator = null;

        if (theme != null) {
            arrayAnimator = theme.obtainStyledAttributes(attrs, R.styleable.Animator, 0, 0);
        } else {
            arrayAnimator = res.obtainAttributes(attrs, R.styleable.Animator);
        }

        // If anim is not null, then it is an object animator.
        if (anim != null) {
            if (theme != null) {
                arrayObjectAnimator = theme.obtainStyledAttributes(attrs,
                        R.styleable.PropertyAnimator, 0, 0);
            } else {
                arrayObjectAnimator = res.obtainAttributes(attrs, R.styleable.PropertyAnimator);
            }
        }
        parseAnimatorFromTypeArray(anim, arrayAnimator, arrayObjectAnimator);

        final int resID =
                arrayAnimator.getResourceId(R.styleable.Animator_interpolator, 0);
        if (resID > 0) {
            anim.setInterpolator(AnimationUtils.loadInterpolator(res, theme, resID));
        }

        arrayAnimator.recycle();
        arrayObjectAnimator.recycle();

        return anim;
    }
}
