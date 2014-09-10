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
import android.util.Log;
import android.util.PathParser;
import android.util.StateSet;
import android.util.TypedValue;
import android.util.Xml;
import android.view.InflateException;
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
    private static final String TAG = "AnimatorInflater";
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
    private static final int VALUE_TYPE_PATH        = 2;
    private static final int VALUE_TYPE_COLOR       = 4;
    private static final int VALUE_TYPE_CUSTOM      = 5;

    private static final boolean DBG_ANIMATOR_INFLATER = false;

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
        return loadAnimator(resources, theme, id, 1);
    }

    /** @hide */
    public static Animator loadAnimator(Resources resources, Theme theme, int id,
            float pathErrorScale) throws NotFoundException {

        XmlResourceParser parser = null;
        try {
            parser = resources.getAnimation(id);
            return createAnimatorFromXml(resources, theme, parser, pathErrorScale);
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
                                    context.getTheme(), parser, 1f);
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
     * PathDataEvaluator is used to interpolate between two paths which are
     * represented in the same format but different control points' values.
     * The path is represented as an array of PathDataNode here, which is
     * fundamentally an array of floating point numbers.
     */
    private static class PathDataEvaluator implements TypeEvaluator<PathParser.PathDataNode[]> {
        private PathParser.PathDataNode[] mNodeArray;

        /**
         * Create a PathParser.PathDataNode[] that does not reuse the animated value.
         * Care must be taken when using this option because on every evaluation
         * a new <code>PathParser.PathDataNode[]</code> will be allocated.
         */
        private PathDataEvaluator() {}

        /**
         * Create a PathDataEvaluator that reuses <code>nodeArray</code> for every evaluate() call.
         * Caution must be taken to ensure that the value returned from
         * {@link android.animation.ValueAnimator#getAnimatedValue()} is not cached, modified, or
         * used across threads. The value will be modified on each <code>evaluate()</code> call.
         *
         * @param nodeArray The array to modify and return from <code>evaluate</code>.
         */
        public PathDataEvaluator(PathParser.PathDataNode[] nodeArray) {
            mNodeArray = nodeArray;
        }

        @Override
        public PathParser.PathDataNode[] evaluate(float fraction,
                PathParser.PathDataNode[] startPathData,
                PathParser.PathDataNode[] endPathData) {
            if (!PathParser.canMorph(startPathData, endPathData)) {
                throw new IllegalArgumentException("Can't interpolate between"
                        + " two incompatible pathData");
            }

            if (mNodeArray == null || !PathParser.canMorph(mNodeArray, startPathData)) {
                mNodeArray = PathParser.deepCopyNodes(startPathData);
            }

            for (int i = 0; i < startPathData.length; i++) {
                mNodeArray[i].interpolatePathDataNode(startPathData[i],
                        endPathData[i], fraction);
            }

            return mNodeArray;
        }
    }

    /**
     * @param anim The animator, must not be null
     * @param arrayAnimator Incoming typed array for Animator's attributes.
     * @param arrayObjectAnimator Incoming typed array for Object Animator's
     *            attributes.
     * @param pixelSize The relative pixel size, used to calculate the
     *                  maximum error for path animations.
     */
    private static void parseAnimatorFromTypeArray(ValueAnimator anim,
            TypedArray arrayAnimator, TypedArray arrayObjectAnimator, float pixelSize) {
        long duration = arrayAnimator.getInt(R.styleable.Animator_duration, 300);

        long startDelay = arrayAnimator.getInt(R.styleable.Animator_startOffset, 0);

        int valueType = arrayAnimator.getInt(R.styleable.Animator_valueType,
                VALUE_TYPE_FLOAT);

        TypeEvaluator evaluator = null;

        boolean getFloats = (valueType == VALUE_TYPE_FLOAT);

        TypedValue tvFrom = arrayAnimator.peekValue(R.styleable.Animator_valueFrom);
        boolean hasFrom = (tvFrom != null);
        int fromType = hasFrom ? tvFrom.type : 0;
        TypedValue tvTo = arrayAnimator.peekValue(R.styleable.Animator_valueTo);
        boolean hasTo = (tvTo != null);
        int toType = hasTo ? tvTo.type : 0;

        // TODO: Further clean up this part of code into 4 types : path, color,
        // integer and float.
        if (valueType == VALUE_TYPE_PATH) {
            evaluator = setupAnimatorForPath(anim, arrayAnimator);
        } else {
            // Integer and float value types are handled here.
            if ((hasFrom && (fromType >= TypedValue.TYPE_FIRST_COLOR_INT) &&
                    (fromType <= TypedValue.TYPE_LAST_COLOR_INT)) ||
                    (hasTo && (toType >= TypedValue.TYPE_FIRST_COLOR_INT) &&
                            (toType <= TypedValue.TYPE_LAST_COLOR_INT))) {
                // special case for colors: ignore valueType and get ints
                getFloats = false;
                evaluator = ArgbEvaluator.getInstance();
            }
            setupValues(anim, arrayAnimator, getFloats, hasFrom, fromType, hasTo, toType);
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
            setupObjectAnimator(anim, arrayObjectAnimator, getFloats, pixelSize);
        }
    }

    /**
     * Setup the Animator to achieve path morphing.
     *
     * @param anim The target Animator which will be updated.
     * @param arrayAnimator TypedArray for the ValueAnimator.
     * @return the PathDataEvaluator.
     */
    private static TypeEvaluator setupAnimatorForPath(ValueAnimator anim,
             TypedArray arrayAnimator) {
        TypeEvaluator evaluator = null;
        String fromString = arrayAnimator.getString(R.styleable.Animator_valueFrom);
        String toString = arrayAnimator.getString(R.styleable.Animator_valueTo);
        PathParser.PathDataNode[] nodesFrom = PathParser.createNodesFromPathData(fromString);
        PathParser.PathDataNode[] nodesTo = PathParser.createNodesFromPathData(toString);

        if (nodesFrom != null) {
            if (nodesTo != null) {
                anim.setObjectValues(nodesFrom, nodesTo);
                if (!PathParser.canMorph(nodesFrom, nodesTo)) {
                    throw new InflateException(arrayAnimator.getPositionDescription()
                            + " Can't morph from " + fromString + " to " + toString);
                }
            } else {
                anim.setObjectValues((Object)nodesFrom);
            }
            evaluator = new PathDataEvaluator(PathParser.deepCopyNodes(nodesFrom));
        } else if (nodesTo != null) {
            anim.setObjectValues((Object)nodesTo);
            evaluator = new PathDataEvaluator(PathParser.deepCopyNodes(nodesTo));
        }

        if (DBG_ANIMATOR_INFLATER && evaluator != null) {
            Log.v(TAG, "create a new PathDataEvaluator here");
        }

        return evaluator;
    }

    /**
     * Setup ObjectAnimator's property or values from pathData.
     *
     * @param anim The target Animator which will be updated.
     * @param arrayObjectAnimator TypedArray for the ObjectAnimator.
     * @param getFloats True if the value type is float.
     * @param pixelSize The relative pixel size, used to calculate the
     *                  maximum error for path animations.
     */
    private static void setupObjectAnimator(ValueAnimator anim, TypedArray arrayObjectAnimator,
            boolean getFloats, float pixelSize) {
        ObjectAnimator oa = (ObjectAnimator) anim;
        String pathData = arrayObjectAnimator.getString(R.styleable.PropertyAnimator_pathData);

        // Note that if there is a pathData defined in the Object Animator,
        // valueFrom / valueTo will be ignored.
        if (pathData != null) {
            String propertyXName =
                    arrayObjectAnimator.getString(R.styleable.PropertyAnimator_propertyXName);
            String propertyYName =
                    arrayObjectAnimator.getString(R.styleable.PropertyAnimator_propertyYName);

            if (propertyXName == null && propertyYName == null) {
                throw new InflateException(arrayObjectAnimator.getPositionDescription()
                        + " propertyXName or propertyYName is needed for PathData");
            } else {
                Path path = PathParser.createPathFromPathData(pathData);
                float error = 0.5f * pixelSize; // max half a pixel error
                PathKeyframes keyframeSet = KeyframeSet.ofPath(path, error);
                Keyframes xKeyframes;
                Keyframes yKeyframes;
                if (getFloats) {
                    xKeyframes = keyframeSet.createXFloatKeyframes();
                    yKeyframes = keyframeSet.createYFloatKeyframes();
                } else {
                    xKeyframes = keyframeSet.createXIntKeyframes();
                    yKeyframes = keyframeSet.createYIntKeyframes();
                }
                PropertyValuesHolder x = null;
                PropertyValuesHolder y = null;
                if (propertyXName != null) {
                    x = PropertyValuesHolder.ofKeyframes(propertyXName, xKeyframes);
                }
                if (propertyYName != null) {
                    y = PropertyValuesHolder.ofKeyframes(propertyYName, yKeyframes);
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

    /**
     * Setup ValueAnimator's values.
     * This will handle all of the integer, float and color types.
     *
     * @param anim The target Animator which will be updated.
     * @param arrayAnimator TypedArray for the ValueAnimator.
     * @param getFloats True if the value type is float.
     * @param hasFrom True if "valueFrom" exists.
     * @param fromType The type of "valueFrom".
     * @param hasTo True if "valueTo" exists.
     * @param toType The type of "valueTo".
     */
    private static void setupValues(ValueAnimator anim, TypedArray arrayAnimator,
            boolean getFloats, boolean hasFrom, int fromType, boolean hasTo, int toType) {
        int valueFromIndex = R.styleable.Animator_valueFrom;
        int valueToIndex = R.styleable.Animator_valueTo;
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
    }

    private static Animator createAnimatorFromXml(Resources res, Theme theme, XmlPullParser parser,
            float pixelSize)
            throws XmlPullParserException, IOException {
        return createAnimatorFromXml(res, theme, parser, Xml.asAttributeSet(parser), null, 0,
                pixelSize);
    }

    private static Animator createAnimatorFromXml(Resources res, Theme theme, XmlPullParser parser,
            AttributeSet attrs, AnimatorSet parent, int sequenceOrdering, float pixelSize)
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
                anim = loadObjectAnimator(res, theme, attrs, pixelSize);
            } else if (name.equals("animator")) {
                anim = loadAnimator(res, theme, attrs, null, pixelSize);
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
                createAnimatorFromXml(res, theme, parser, attrs, (AnimatorSet) anim, ordering,
                        pixelSize);
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

    private static ObjectAnimator loadObjectAnimator(Resources res, Theme theme, AttributeSet attrs,
            float pathErrorScale) throws NotFoundException {
        ObjectAnimator anim = new ObjectAnimator();

        loadAnimator(res, theme, attrs, anim, pathErrorScale);

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
            AttributeSet attrs, ValueAnimator anim, float pathErrorScale)
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

        if (anim == null) {
            anim = new ValueAnimator();
        }

        parseAnimatorFromTypeArray(anim, arrayAnimator, arrayObjectAnimator, pathErrorScale);

        final int resID =
                arrayAnimator.getResourceId(R.styleable.Animator_interpolator, 0);
        if (resID > 0) {
            anim.setInterpolator(AnimationUtils.loadInterpolator(res, theme, resID));
        }

        arrayAnimator.recycle();
        if (arrayObjectAnimator != null) {
            arrayObjectAnimator.recycle();
        }

        return anim;
    }
}
