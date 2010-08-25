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
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.content.res.Resources.NotFoundException;
import android.util.AttributeSet;
import android.util.Xml;
import android.view.animation.AnimationUtils;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;

/**
 * This class is used to instantiate menu XML files into Animatable objects.
 * <p>
 * For performance reasons, menu inflation relies heavily on pre-processing of
 * XML files that is done at build time. Therefore, it is not currently possible
 * to use MenuInflater with an XmlPullParser over a plain XML file at runtime;
 * it only works with an XmlPullParser returned from a compiled resource (R.
 * <em>something</em> file.)
 */
public class AnimatableInflater {

    /**
     * These flags are used when parsing Sequencer objects
     */
    private static final int TOGETHER = 0;
    private static final int SEQUENTIALLY = 1;

    /**
     * Enum values used in XML attributes to indicate the value for mValueType
     */
    private static final int VALUE_TYPE_FLOAT       = 0;
    private static final int VALUE_TYPE_INT         = 1;
    private static final int VALUE_TYPE_DOUBLE      = 2;
    private static final int VALUE_TYPE_COLOR       = 3;
    private static final int VALUE_TYPE_CUSTOM      = 4;

    /**
     * Loads an {@link Animatable} object from a resource
     *
     * @param context Application context used to access resources
     * @param id The resource id of the animation to load
     * @return The animatable object reference by the specified id
     * @throws android.content.res.Resources.NotFoundException when the animation cannot be loaded
     */
    public static Animatable loadAnimatable(Context context, int id)
            throws NotFoundException {

        XmlResourceParser parser = null;
        try {
            parser = context.getResources().getAnimation(id);
            return createAnimatableFromXml(context, parser);
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

    private static Animatable createAnimatableFromXml(Context c, XmlPullParser parser)
            throws XmlPullParserException, IOException {

        return createAnimatableFromXml(c, parser, Xml.asAttributeSet(parser), null, 0);
    }

    private static Animatable createAnimatableFromXml(Context c, XmlPullParser parser,
            AttributeSet attrs, Sequencer parent, int sequenceOrdering)
            throws XmlPullParserException, IOException {

        Animatable anim = null;
        ArrayList<Animatable> childAnims = null;

        // Make sure we are on a start tag.
        int type;
        int depth = parser.getDepth();

        while (((type=parser.next()) != XmlPullParser.END_TAG || parser.getDepth() > depth)
               && type != XmlPullParser.END_DOCUMENT) {

            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            String  name = parser.getName();

            if (name.equals("property")) {
                anim = loadPropertyAnimator(c, attrs);
            } else if (name.equals("animator")) {
                anim = loadAnimator(c, attrs, null);
            } else if (name.equals("sequencer")) {
                anim = new Sequencer();
                TypedArray a = c.obtainStyledAttributes(attrs,
                        com.android.internal.R.styleable.Sequencer);
                int ordering = a.getInt(com.android.internal.R.styleable.Sequencer_ordering,
                        TOGETHER);
                createAnimatableFromXml(c, parser, attrs, (Sequencer) anim,  ordering);
                a.recycle();
            } else {
                throw new RuntimeException("Unknown animator name: " + parser.getName());
            }

            if (parent != null) {
                if (childAnims == null) {
                    childAnims = new ArrayList<Animatable>();
                }
                childAnims.add(anim);
            }
        }
        if (parent != null && childAnims != null) {
            Animatable[] animsArray = new Animatable[childAnims.size()];
            int index = 0;
            for (Animatable a : childAnims) {
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

    private static PropertyAnimator loadPropertyAnimator(Context context, AttributeSet attrs)
            throws NotFoundException {

        PropertyAnimator anim = new PropertyAnimator();

        loadAnimator(context, attrs, anim);

        TypedArray a =
                context.obtainStyledAttributes(attrs, com.android.internal.R.styleable.PropertyAnimator);

        String propertyName = a.getString(com.android.internal.R.styleable.PropertyAnimator_propertyName);

        anim.setPropertyName(propertyName);

        a.recycle();

        return anim;
    }

    /**
     * Creates a new animation whose parameters come from the specified context and
     * attributes set.
     *
     * @param context the application environment
     * @param attrs the set of attributes holding the animation parameters
     */
    private static Animator loadAnimator(Context context, AttributeSet attrs, Animator anim)
            throws NotFoundException {

        TypedArray a =
                context.obtainStyledAttributes(attrs, com.android.internal.R.styleable.Animator);

        long duration = a.getInt(com.android.internal.R.styleable.Animator_duration, 0);

        long startDelay = a.getInt(com.android.internal.R.styleable.Animator_startOffset, 0);

        int valueType = a.getInt(com.android.internal.R.styleable.Animator_valueType,
                VALUE_TYPE_FLOAT);

        Object valueFrom = null;
        Object valueTo = null;
        TypeEvaluator evaluator = null;

        switch (valueType) {
            case VALUE_TYPE_FLOAT:
                if (a.hasValue(com.android.internal.R.styleable.Animator_valueFrom)) {
                    valueFrom = a.getFloat(com.android.internal.R.styleable.Animator_valueFrom, 0f);
                }
                if (a.hasValue(com.android.internal.R.styleable.Animator_valueTo)) {
                    valueTo = a.getFloat(com.android.internal.R.styleable.Animator_valueTo, 0f);
                }
                break;
            case VALUE_TYPE_COLOR:
                evaluator = new RGBEvaluator();
                // fall through to pick up values
            case VALUE_TYPE_INT:
                if (a.hasValue(com.android.internal.R.styleable.Animator_valueFrom)) {
                    valueFrom = a.getInteger(com.android.internal.R.styleable.Animator_valueFrom, 0);
                }
                if (a.hasValue(com.android.internal.R.styleable.Animator_valueTo)) {
                    valueTo = a.getInteger(com.android.internal.R.styleable.Animator_valueTo, 0);
                }
                break;
            case VALUE_TYPE_DOUBLE:
                if (a.hasValue(com.android.internal.R.styleable.Animator_valueFrom)) {
                    valueFrom = (Double)((Float)(a.getFloat(com.android.internal.R.styleable.Animator_valueFrom, 0f))).doubleValue();
                }
                if (a.hasValue(com.android.internal.R.styleable.Animator_valueTo)) {
                    valueTo = (Double)((Float)a.getFloat(com.android.internal.R.styleable.Animator_valueTo, 0f)).doubleValue();
                }
                break;
            case VALUE_TYPE_CUSTOM:
                // TODO: How to get an 'Object' value?
                if (a.hasValue(com.android.internal.R.styleable.Animator_valueFrom)) {
                    valueFrom = a.getFloat(com.android.internal.R.styleable.Animator_valueFrom, 0f);
                }
                if (a.hasValue(com.android.internal.R.styleable.Animator_valueTo)) {
                    valueTo = a.getFloat(com.android.internal.R.styleable.Animator_valueTo, 0f);
                }
                break;
        }

        if (anim == null) {
            anim = new Animator(duration, valueFrom, valueTo);
        } else {
            anim.setDuration(duration);
            anim.setValues(valueFrom, valueTo);
        }

        anim.setStartDelay(startDelay);

        if (a.hasValue(com.android.internal.R.styleable.Animator_repeatCount)) {
            anim.setRepeatCount(
                    a.getInt(com.android.internal.R.styleable.Animator_repeatCount, 0));
        }
        if (a.hasValue(com.android.internal.R.styleable.Animator_repeatMode)) {
            anim.setRepeatMode(
                    a.getInt(com.android.internal.R.styleable.Animator_repeatMode,
                            Animator.RESTART));
        }
        if (evaluator != null) {
            anim.setEvaluator(evaluator);
        }

        final int resID =
                a.getResourceId(com.android.internal.R.styleable.Animator_interpolator, 0);
        if (resID > 0) {
            anim.setInterpolator(AnimationUtils.loadInterpolator(context, resID));
        }
        a.recycle();

        return anim;
    }
}
