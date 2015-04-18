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

package android.view.animation;

import android.content.res.Configuration;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.annotation.AnimRes;
import android.annotation.InterpolatorRes;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.XmlResourceParser;
import android.content.res.Resources.NotFoundException;
import android.util.AttributeSet;
import android.util.Xml;
import android.os.SystemClock;

import java.io.IOException;

/**
 * Defines common utilities for working with animations.
 *
 */
public class AnimationUtils {

    /**
     * These flags are used when parsing AnimatorSet objects
     */
    private static final int TOGETHER = 0;
    private static final int SEQUENTIALLY = 1;

    private static final float RECOMMENDED_FIELD_OF_VIEW_FOR_TV = 40f;
    private static final float ESTIMATED_VIEWING_DISTANCE_FOR_WATCH = 11f;
    private static final float AVERAGE_VIEWING_DISTANCE_FOR_PHONES = 14.2f;
    private static final float N5_DIAGONAL_VIEW_ANGLE = 19.58f;
    private static final float N5_DENSITY = 3.0f;
    private static final float N5_DPI = 443f;

    private static final float COTANGENT_OF_HALF_TV_ANGLE = (float)  (1 / Math.tan(Math.toRadians
                (RECOMMENDED_FIELD_OF_VIEW_FOR_TV / 2)));


    /**
     * Returns the current animation time in milliseconds. This time should be used when invoking
     * {@link Animation#setStartTime(long)}. Refer to {@link android.os.SystemClock} for more
     * information about the different available clocks. The clock used by this method is
     * <em>not</em> the "wall" clock (it is not {@link System#currentTimeMillis}).
     *
     * @return the current animation time in milliseconds
     *
     * @see android.os.SystemClock
     */
    public static long currentAnimationTimeMillis() {
        return SystemClock.uptimeMillis();
    }

    /**
     * Loads an {@link Animation} object from a resource
     *
     * @param context Application context used to access resources
     * @param id The resource id of the animation to load
     * @return The animation object reference by the specified id
     * @throws NotFoundException when the animation cannot be loaded
     */
    public static Animation loadAnimation(Context context, @AnimRes int id)
            throws NotFoundException {

        XmlResourceParser parser = null;
        try {
            parser = context.getResources().getAnimation(id);
            return createAnimationFromXml(context, parser);
        } catch (XmlPullParserException ex) {
            NotFoundException rnf = new NotFoundException("Can't load animation resource ID #0x" +
                    Integer.toHexString(id));
            rnf.initCause(ex);
            throw rnf;
        } catch (IOException ex) {
            NotFoundException rnf = new NotFoundException("Can't load animation resource ID #0x" +
                    Integer.toHexString(id));
            rnf.initCause(ex);
            throw rnf;
        } finally {
            if (parser != null) parser.close();
        }
    }

    private static Animation createAnimationFromXml(Context c, XmlPullParser parser)
            throws XmlPullParserException, IOException {

        return createAnimationFromXml(c, parser, null, Xml.asAttributeSet(parser));
    }

    private static Animation createAnimationFromXml(Context c, XmlPullParser parser,
            AnimationSet parent, AttributeSet attrs) throws XmlPullParserException, IOException {

        Animation anim = null;

        // Make sure we are on a start tag.
        int type;
        int depth = parser.getDepth();

        while (((type=parser.next()) != XmlPullParser.END_TAG || parser.getDepth() > depth)
               && type != XmlPullParser.END_DOCUMENT) {

            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            String  name = parser.getName();

            if (name.equals("set")) {
                anim = new AnimationSet(c, attrs);
                createAnimationFromXml(c, parser, (AnimationSet)anim, attrs);
            } else if (name.equals("alpha")) {
                anim = new AlphaAnimation(c, attrs);
            } else if (name.equals("scale")) {
                anim = new ScaleAnimation(c, attrs);
            }  else if (name.equals("rotate")) {
                anim = new RotateAnimation(c, attrs);
            }  else if (name.equals("translate")) {
                anim = new TranslateAnimation(c, attrs);
            } else {
                throw new RuntimeException("Unknown animation name: " + parser.getName());
            }

            if (parent != null) {
                parent.addAnimation(anim);
            }
        }

        return anim;

    }

    /**
     * Loads a {@link LayoutAnimationController} object from a resource
     *
     * @param context Application context used to access resources
     * @param id The resource id of the animation to load
     * @return The animation object reference by the specified id
     * @throws NotFoundException when the layout animation controller cannot be loaded
     */
    public static LayoutAnimationController loadLayoutAnimation(Context context, @AnimRes int id)
            throws NotFoundException {

        XmlResourceParser parser = null;
        try {
            parser = context.getResources().getAnimation(id);
            return createLayoutAnimationFromXml(context, parser);
        } catch (XmlPullParserException ex) {
            NotFoundException rnf = new NotFoundException("Can't load animation resource ID #0x" +
                    Integer.toHexString(id));
            rnf.initCause(ex);
            throw rnf;
        } catch (IOException ex) {
            NotFoundException rnf = new NotFoundException("Can't load animation resource ID #0x" +
                    Integer.toHexString(id));
            rnf.initCause(ex);
            throw rnf;
        } finally {
            if (parser != null) parser.close();
        }
    }

    private static LayoutAnimationController createLayoutAnimationFromXml(Context c,
            XmlPullParser parser) throws XmlPullParserException, IOException {

        return createLayoutAnimationFromXml(c, parser, Xml.asAttributeSet(parser));
    }

    private static LayoutAnimationController createLayoutAnimationFromXml(Context c,
            XmlPullParser parser, AttributeSet attrs) throws XmlPullParserException, IOException {

        LayoutAnimationController controller = null;

        int type;
        int depth = parser.getDepth();

        while (((type = parser.next()) != XmlPullParser.END_TAG || parser.getDepth() > depth)
                && type != XmlPullParser.END_DOCUMENT) {

            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            String name = parser.getName();

            if ("layoutAnimation".equals(name)) {
                controller = new LayoutAnimationController(c, attrs);
            } else if ("gridLayoutAnimation".equals(name)) {
                controller = new GridLayoutAnimationController(c, attrs);
            } else {
                throw new RuntimeException("Unknown layout animation name: " + name);
            }
        }

        return controller;
    }

    /**
     * Make an animation for objects becoming visible. Uses a slide and fade
     * effect.
     *
     * @param c Context for loading resources
     * @param fromLeft is the object to be animated coming from the left
     * @return The new animation
     */
    public static Animation makeInAnimation(Context c, boolean fromLeft) {
        Animation a;
        if (fromLeft) {
            a = AnimationUtils.loadAnimation(c, com.android.internal.R.anim.slide_in_left);
        } else {
            a = AnimationUtils.loadAnimation(c, com.android.internal.R.anim.slide_in_right);
        }

        a.setInterpolator(new DecelerateInterpolator());
        a.setStartTime(currentAnimationTimeMillis());
        return a;
    }

    /**
     * Make an animation for objects becoming invisible. Uses a slide and fade
     * effect.
     *
     * @param c Context for loading resources
     * @param toRight is the object to be animated exiting to the right
     * @return The new animation
     */
    public static Animation makeOutAnimation(Context c, boolean toRight) {
        Animation a;
        if (toRight) {
            a = AnimationUtils.loadAnimation(c, com.android.internal.R.anim.slide_out_right);
        } else {
            a = AnimationUtils.loadAnimation(c, com.android.internal.R.anim.slide_out_left);
        }

        a.setInterpolator(new AccelerateInterpolator());
        a.setStartTime(currentAnimationTimeMillis());
        return a;
    }


    /**
     * Make an animation for objects becoming visible. Uses a slide up and fade
     * effect.
     *
     * @param c Context for loading resources
     * @return The new animation
     */
    public static Animation makeInChildBottomAnimation(Context c) {
        Animation a;
        a = AnimationUtils.loadAnimation(c, com.android.internal.R.anim.slide_in_child_bottom);
        a.setInterpolator(new AccelerateInterpolator());
        a.setStartTime(currentAnimationTimeMillis());
        return a;
    }

    /**
     * Loads an {@link Interpolator} object from a resource
     *
     * @param context Application context used to access resources
     * @param id The resource id of the animation to load
     * @return The animation object reference by the specified id
     * @throws NotFoundException
     */
    public static Interpolator loadInterpolator(Context context, @InterpolatorRes int id)
            throws NotFoundException {
        XmlResourceParser parser = null;
        try {
            parser = context.getResources().getAnimation(id);
            return createInterpolatorFromXml(context.getResources(), context.getTheme(), parser);
        } catch (XmlPullParserException ex) {
            NotFoundException rnf = new NotFoundException("Can't load animation resource ID #0x" +
                    Integer.toHexString(id));
            rnf.initCause(ex);
            throw rnf;
        } catch (IOException ex) {
            NotFoundException rnf = new NotFoundException("Can't load animation resource ID #0x" +
                    Integer.toHexString(id));
            rnf.initCause(ex);
            throw rnf;
        } finally {
            if (parser != null) parser.close();
        }

    }

    /**
     * Loads an {@link Interpolator} object from a resource
     *
     * @param res The resources
     * @param id The resource id of the animation to load
     * @return The interpolator object reference by the specified id
     * @throws NotFoundException
     * @hide
     */
    public static Interpolator loadInterpolator(Resources res, Theme theme, int id) throws NotFoundException {
        XmlResourceParser parser = null;
        try {
            parser = res.getAnimation(id);
            return createInterpolatorFromXml(res, theme, parser);
        } catch (XmlPullParserException ex) {
            NotFoundException rnf = new NotFoundException("Can't load animation resource ID #0x" +
                    Integer.toHexString(id));
            rnf.initCause(ex);
            throw rnf;
        } catch (IOException ex) {
            NotFoundException rnf = new NotFoundException("Can't load animation resource ID #0x" +
                    Integer.toHexString(id));
            rnf.initCause(ex);
            throw rnf;
        } finally {
            if (parser != null)
                parser.close();
        }

    }

    private static Interpolator createInterpolatorFromXml(Resources res, Theme theme, XmlPullParser parser)
            throws XmlPullParserException, IOException {

        BaseInterpolator interpolator = null;

        // Make sure we are on a start tag.
        int type;
        int depth = parser.getDepth();

        while (((type = parser.next()) != XmlPullParser.END_TAG || parser.getDepth() > depth)
                && type != XmlPullParser.END_DOCUMENT) {

            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            AttributeSet attrs = Xml.asAttributeSet(parser);

            String name = parser.getName();

            if (name.equals("linearInterpolator")) {
                interpolator = new LinearInterpolator();
            } else if (name.equals("accelerateInterpolator")) {
                interpolator = new AccelerateInterpolator(res, theme, attrs);
            } else if (name.equals("decelerateInterpolator")) {
                interpolator = new DecelerateInterpolator(res, theme, attrs);
            } else if (name.equals("accelerateDecelerateInterpolator")) {
                interpolator = new AccelerateDecelerateInterpolator();
            } else if (name.equals("cycleInterpolator")) {
                interpolator = new CycleInterpolator(res, theme, attrs);
            } else if (name.equals("anticipateInterpolator")) {
                interpolator = new AnticipateInterpolator(res, theme, attrs);
            } else if (name.equals("overshootInterpolator")) {
                interpolator = new OvershootInterpolator(res, theme, attrs);
            } else if (name.equals("anticipateOvershootInterpolator")) {
                interpolator = new AnticipateOvershootInterpolator(res, theme, attrs);
            } else if (name.equals("bounceInterpolator")) {
                interpolator = new BounceInterpolator();
            } else if (name.equals("pathInterpolator")) {
                interpolator = new PathInterpolator(res, theme, attrs);
            } else {
                throw new RuntimeException("Unknown interpolator name: " + parser.getName());
            }
        }
        return interpolator;
    }

    /**
     * Derives the viewing distance of a device based on the device size (in inches), and the
     * device type.
     * @hide
     */
    public static float getViewingDistance(float width, float height, int uiMode) {
        if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) {
            // TV
            return (width / 2) * COTANGENT_OF_HALF_TV_ANGLE;
        } else if (uiMode == Configuration.UI_MODE_TYPE_WATCH) {
            // Watch
            return ESTIMATED_VIEWING_DISTANCE_FOR_WATCH;
        } else {
            // Tablet, phone, etc
            return AVERAGE_VIEWING_DISTANCE_FOR_PHONES;
        }
    }

    /**
     * Calculates the duration scaling factor of an animation based on the hint that the animation
     * will move across the entire screen. A scaling factor of 1 means the duration on this given
     * device will be the same as the duration set through
     * {@link android.animation.Animator#setDuration(long)}. The calculation uses Nexus 5 as a
     * baseline device. That is, the duration of the animation on a given device will scale its
     * duration so that it has the same look and feel as the animation on Nexus 5. In order to
     * achieve the same perceived effect of the animation across different devices, we maintain
     * the same angular speed of the same animation in users' field of view. Therefore, the
     * duration scale factor is determined by the ratio of the angular movement on current
     * devices to that on the baseline device.
     *
     * @param width width of the screen (in inches)
     * @param height height of the screen (in inches)
     * @param viewingDistance the viewing distance of the device (i.e. watch, phone, TV, etc) in
     *                        inches
     * @return scaling factor (or multiplier) of the duration set through
     * {@link android.animation.Animator#setDuration(long)} on current device.
     * @hide
     */
    public static float getScreenSizeBasedDurationScale(float width, float height,
            float viewingDistance) {
        // Animation's moving distance is proportional to the screen size.
        float diagonal = (float) Math.sqrt(width * width + height * height);
        float diagonalViewAngle = (float) Math.toDegrees(Math.atan((diagonal / 2f)
                / viewingDistance) * 2);
        return diagonalViewAngle / N5_DIAGONAL_VIEW_ANGLE;
    }

    /**
     * Calculates the duration scaling factor of an animation under the assumption that the
     * animation is defined to move the same amount of distance (in dp) across all devices. A
     * scaling factor of 1 means the duration on this given device will be the same as the
     * duration set through {@link android.animation.Animator#setDuration(long)}. The calculation
     * uses Nexus 5 as a baseline device. That is, the duration of the animation on a given
     * device will scale its duration so that it has the same look and feel as the animation on
     * Nexus 5. In order to achieve the same perceived effect of the animation across different
     * devices, we maintain the same angular velocity of the same animation in users' field of
     * view. Therefore, the duration scale factor is determined by the ratio of the angular
     * movement on current devices to that on the baseline device.
     *
     * @param density logical density of the display. {@link android.util.DisplayMetrics#density}
     * @param dpi pixels per inch
     * @param viewingDistance viewing distance of the device (in inches)
     * @return the scaling factor of duration
     * @hide
     */
    public static float getDpBasedDurationScale(float density, float dpi,
            float viewingDistance) {
        // Angle in users' field of view per dp:
        float anglePerDp = (float) Math.atan2((density / dpi) / 2, viewingDistance) * 2;
        float baselineAnglePerDp = (float) Math.atan2((N5_DENSITY / N5_DPI) / 2,
                AVERAGE_VIEWING_DISTANCE_FOR_PHONES) * 2;
        return anglePerDp / baselineAnglePerDp;
    }
}
