/*
 * Copyright (C) 2011 The Android Open Source Project
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


package android.media.effect;

import java.lang.reflect.Constructor;
import java.util.HashMap;

/**
 * <p>The EffectFactory class defines the list of available Effects, and provides functionality to
 * inspect and instantiate them. Some effects may not be available on all platforms, so before
 * creating a certain effect, the application should confirm that the effect is supported on this
 * platform by calling {@link #isEffectSupported(String)}.</p>
 */
public class EffectFactory {

    private EffectContext mEffectContext;

    private final static String[] EFFECT_PACKAGES = {
        "android.media.effect.effects.",  // Default effect package
        ""                                // Allows specifying full class path
    };

    /** List of Effects */
    /**
     * <p>Copies the input texture to the output.</p>
     * <p>Available parameters: None</p>
     * @hide
     */
    public final static String EFFECT_IDENTITY = "IdentityEffect";

    /**
     * <p>Adjusts the brightness of the image.</p>
     * <p>Available parameters:</p>
     * <table>
     * <tr><td>Parameter name</td><td>Meaning</td><td>Valid values</td></tr>
     * <tr><td><code>brightness</code></td>
     *     <td>The brightness multiplier.</td>
     *     <td>Positive float. 1.0 means no change;
               larger values will increase brightness.</td>
     * </tr>
     * </table>
     */
    public final static String EFFECT_BRIGHTNESS =
            "android.media.effect.effects.BrightnessEffect";

    /**
     * <p>Adjusts the contrast of the image.</p>
     * <p>Available parameters:</p>
     * <table>
     * <tr><td>Parameter name</td><td>Meaning</td><td>Valid values</td></tr>
     * <tr><td><code>contrast</code></td>
     *     <td>The contrast multiplier.</td>
     *     <td>Float. 1.0 means no change;
               larger values will increase contrast.</td>
     * </tr>
     * </table>
     */
    public final static String EFFECT_CONTRAST =
            "android.media.effect.effects.ContrastEffect";

    /**
     * <p>Applies a fisheye lens distortion to the image.</p>
     * <p>Available parameters:</p>
     * <table>
     * <tr><td>Parameter name</td><td>Meaning</td><td>Valid values</td></tr>
     * <tr><td><code>scale</code></td>
     *     <td>The scale of the distortion.</td>
     *     <td>Float, between 0 and 1. Zero means no distortion.</td>
     * </tr>
     * </table>
     */
    public final static String EFFECT_FISHEYE =
            "android.media.effect.effects.FisheyeEffect";

    /**
     * <p>Replaces the background of the input frames with frames from a
     * selected video.  Requires an initial learning period with only the
     * background visible before the effect becomes active. The effect will wait
     * until it does not see any motion in the scene before learning the
     * background and starting the effect.</p>
     *
     * <p>Available parameters:</p>
     * <table>
     * <tr><td>Parameter name</td><td>Meaning</td><td>Valid values</td></tr>
     * <tr><td><code>source</code></td>
     *     <td>A URI for the background video to use. This parameter must be
     *         supplied before calling apply() for the first time.</td>
     *     <td>String, such as from
     *         {@link android.net.Uri#toString Uri.toString()}</td>
     * </tr>
     * </table>
     *
     * <p>If the update listener is set for this effect using
     * {@link Effect#setUpdateListener}, it will be called when the effect has
     * finished learning the background, with a null value for the info
     * parameter.</p>
     */
    public final static String EFFECT_BACKDROPPER =
            "android.media.effect.effects.BackDropperEffect";

    /**
     * <p>Attempts to auto-fix the image based on histogram equalization.</p>
     * <p>Available parameters:</p>
     * <table>
     * <tr><td>Parameter name</td><td>Meaning</td><td>Valid values</td></tr>
     * <tr><td><code>scale</code></td>
     *     <td>The scale of the adjustment.</td>
     *     <td>Float, between 0 and 1. Zero means no adjustment, while 1 indicates the maximum
     *     amount of adjustment.</td>
     * </tr>
     * </table>
     */
    public final static String EFFECT_AUTOFIX =
            "android.media.effect.effects.AutoFixEffect";

    /**
     * <p>Adjusts the range of minimal and maximal color pixel intensities.</p>
     * <p>Available parameters:</p>
     * <table>
     * <tr><td>Parameter name</td><td>Meaning</td><td>Valid values</td></tr>
     * <tr><td><code>black</code></td>
     *     <td>The value of the minimal pixel.</td>
     *     <td>Float, between 0 and 1.</td>
     * </tr>
     * <tr><td><code>white</code></td>
     *     <td>The value of the maximal pixel.</td>
     *     <td>Float, between 0 and 1.</td>
     * </tr>
     * </table>
     */
    public final static String EFFECT_BLACKWHITE =
            "android.media.effect.effects.BlackWhiteEffect";

    /**
     * <p>Crops an upright rectangular area from the image. If the crop region falls outside of
     * the image bounds, the results are undefined.</p>
     * <p>Available parameters:</p>
     * <table>
     * <tr><td>Parameter name</td><td>Meaning</td><td>Valid values</td></tr>
     * <tr><td><code>xorigin</code></td>
     *     <td>The origin's x-value.</td>
     *     <td>Integer, between 0 and width of the image.</td>
     * </tr>
     * <tr><td><code>yorigin</code></td>
     *     <td>The origin's y-value.</td>
     *     <td>Integer, between 0 and height of the image.</td>
     * </tr>
     * <tr><td><code>width</code></td>
     *     <td>The width of the cropped image.</td>
     *     <td>Integer, between 1 and the width of the image minus xorigin.</td>
     * </tr>
     * <tr><td><code>height</code></td>
     *     <td>The height of the cropped image.</td>
     *     <td>Integer, between 1 and the height of the image minus yorigin.</td>
     * </tr>
     * </table>
     */
    public final static String EFFECT_CROP =
            "android.media.effect.effects.CropEffect";

    /**
     * <p>Applies a cross process effect on image, in which the red and green channels are
     * enhanced while the blue channel is restricted.</p>
     * <p>Available parameters: None</p>
     */
    public final static String EFFECT_CROSSPROCESS =
            "android.media.effect.effects.CrossProcessEffect";

    /**
     * <p>Applies black and white documentary style effect on image..</p>
     * <p>Available parameters: None</p>
     */
    public final static String EFFECT_DOCUMENTARY =
            "android.media.effect.effects.DocumentaryEffect";


    /**
     * <p>Overlays a bitmap (with premultiplied alpha channel) onto the input image. The bitmap
     * is stretched to fit the input image.</p>
     * <p>Available parameters:</p>
     * <table>
     * <tr><td>Parameter name</td><td>Meaning</td><td>Valid values</td></tr>
     * <tr><td><code>bitmap</code></td>
     *     <td>The overlay bitmap.</td>
     *     <td>A non-null Bitmap instance.</td>
     * </tr>
     * </table>
     */
    public final static String EFFECT_BITMAPOVERLAY =
            "android.media.effect.effects.BitmapOverlayEffect";

    /**
     * <p>Representation of photo using only two color tones.</p>
     * <p>Available parameters:</p>
     * <table>
     * <tr><td>Parameter name</td><td>Meaning</td><td>Valid values</td></tr>
     * <tr><td><code>first_color</code></td>
     *     <td>The first color tone.</td>
     *     <td>Integer, representing an ARGB color with 8 bits per channel. May be created using
     *     {@link android.graphics.Color Color} class.</td>
     * </tr>
     * <tr><td><code>second_color</code></td>
     *     <td>The second color tone.</td>
     *     <td>Integer, representing an ARGB color with 8 bits per channel. May be created using
     *     {@link android.graphics.Color Color} class.</td>
     * </tr>
     * </table>
     */
    public final static String EFFECT_DUOTONE =
            "android.media.effect.effects.DuotoneEffect";

    /**
     * <p>Applies back-light filling to the image.</p>
     * <p>Available parameters:</p>
     * <table>
     * <tr><td>Parameter name</td><td>Meaning</td><td>Valid values</td></tr>
     * <tr><td><code>strength</code></td>
     *     <td>The strength of the backlight.</td>
     *     <td>Float, between 0 and 1. Zero means no change.</td>
     * </tr>
     * </table>
     */
    public final static String EFFECT_FILLLIGHT =
            "android.media.effect.effects.FillLightEffect";

    /**
     * <p>Flips image vertically and/or horizontally.</p>
     * <p>Available parameters:</p>
     * <table>
     * <tr><td>Parameter name</td><td>Meaning</td><td>Valid values</td></tr>
     * <tr><td><code>vertical</code></td>
     *     <td>Whether to flip image vertically.</td>
     *     <td>Boolean</td>
     * </tr>
     * <tr><td><code>horizontal</code></td>
     *     <td>Whether to flip image horizontally.</td>
     *     <td>Boolean</td>
     * </tr>
     * </table>
     */
    public final static String EFFECT_FLIP =
            "android.media.effect.effects.FlipEffect";

    /**
     * <p>Applies film grain effect to image.</p>
     * <p>Available parameters:</p>
     * <table>
     * <tr><td>Parameter name</td><td>Meaning</td><td>Valid values</td></tr>
     * <tr><td><code>strength</code></td>
     *     <td>The strength of the grain effect.</td>
     *     <td>Float, between 0 and 1. Zero means no change.</td>
     * </tr>
     * </table>
     */
    public final static String EFFECT_GRAIN =
            "android.media.effect.effects.GrainEffect";

    /**
     * <p>Converts image to grayscale.</p>
     * <p>Available parameters: None</p>
     */
    public final static String EFFECT_GRAYSCALE =
            "android.media.effect.effects.GrayscaleEffect";

    /**
     * <p>Applies lomo-camera style effect to image.</p>
     * <p>Available parameters: None</p>
     */
    public final static String EFFECT_LOMOISH =
            "android.media.effect.effects.LomoishEffect";

    /**
     * <p>Inverts the image colors.</p>
     * <p>Available parameters: None</p>
     */
    public final static String EFFECT_NEGATIVE =
            "android.media.effect.effects.NegativeEffect";

    /**
     * <p>Applies posterization effect to image.</p>
     * <p>Available parameters: None</p>
     */
    public final static String EFFECT_POSTERIZE =
            "android.media.effect.effects.PosterizeEffect";

    /**
     * <p>Removes red eyes on specified region.</p>
     * <p>Available parameters:</p>
     * <table>
     * <tr><td>Parameter name</td><td>Meaning</td><td>Valid values</td></tr>
     * <tr><td><code>centers</code></td>
     *     <td>Multiple center points (x, y) of the red eye regions.</td>
     *     <td>An array of floats, where (f[2*i], f[2*i+1]) specifies the center of the i'th eye.
     *     Coordinate values are expected to be normalized between 0 and 1.</td>
     * </tr>
     * </table>
     */
    public final static String EFFECT_REDEYE =
            "android.media.effect.effects.RedEyeEffect";

    /**
     * <p>Rotates the image. The output frame size must be able to fit the rotated version of
     * the input image. Note that the rotation snaps to a the closest multiple of 90 degrees.</p>
     * <p>Available parameters:</p>
     * <table>
     * <tr><td>Parameter name</td><td>Meaning</td><td>Valid values</td></tr>
     * <tr><td><code>angle</code></td>
     *     <td>The angle of rotation in degrees.</td>
     *     <td>Integer value. This will be rounded to the nearest multiple of 90.</td>
     * </tr>
     * </table>
     */
    public final static String EFFECT_ROTATE =
            "android.media.effect.effects.RotateEffect";

    /**
     * <p>Adjusts color saturation of image.</p>
     * <p>Available parameters:</p>
     * <table>
     * <tr><td>Parameter name</td><td>Meaning</td><td>Valid values</td></tr>
     * <tr><td><code>scale</code></td>
     *     <td>The scale of color saturation.</td>
     *     <td>Float, between -1 and 1. 0 means no change, while -1 indicates full desaturation,
     *     i.e. grayscale.</td>
     * </tr>
     * </table>
     */
    public final static String EFFECT_SATURATE =
            "android.media.effect.effects.SaturateEffect";

    /**
     * <p>Converts image to sepia tone.</p>
     * <p>Available parameters: None</p>
     */
    public final static String EFFECT_SEPIA =
            "android.media.effect.effects.SepiaEffect";

    /**
     * <p>Sharpens the image.</p>
     * <p>Available parameters:</p>
     * <table>
     * <tr><td>Parameter name</td><td>Meaning</td><td>Valid values</td></tr>
     * <tr><td><code>scale</code></td>
     *     <td>The degree of sharpening.</td>
     *     <td>Float, between 0 and 1. 0 means no change.</td>
     * </tr>
     * </table>
     */
    public final static String EFFECT_SHARPEN =
            "android.media.effect.effects.SharpenEffect";

    /**
     * <p>Rotates the image according to the specified angle, and crops the image so that no
     * non-image portions are visible.</p>
     * <p>Available parameters:</p>
     * <table>
     * <tr><td>Parameter name</td><td>Meaning</td><td>Valid values</td></tr>
     * <tr><td><code>angle</code></td>
     *     <td>The angle of rotation.</td>
     *     <td>Float, between -45 and +45.</td>
     * </tr>
     * </table>
     */
    public final static String EFFECT_STRAIGHTEN =
            "android.media.effect.effects.StraightenEffect";

    /**
     * <p>Adjusts color temperature of the image.</p>
     * <p>Available parameters:</p>
     * <table>
     * <tr><td>Parameter name</td><td>Meaning</td><td>Valid values</td></tr>
     * <tr><td><code>scale</code></td>
     *     <td>The value of color temperature.</td>
     *     <td>Float, between 0 and 1, with 0 indicating cool, and 1 indicating warm. A value of
     *     of 0.5 indicates no change.</td>
     * </tr>
     * </table>
     */
    public final static String EFFECT_TEMPERATURE =
            "android.media.effect.effects.ColorTemperatureEffect";

    /**
     * <p>Tints the photo with specified color.</p>
     * <p>Available parameters:</p>
     * <table>
     * <tr><td>Parameter name</td><td>Meaning</td><td>Valid values</td></tr>
     * <tr><td><code>tint</code></td>
     *     <td>The color of the tint.</td>
     *     <td>Integer, representing an ARGB color with 8 bits per channel. May be created using
     *     {@link android.graphics.Color Color} class.</td>
     * </tr>
     * </table>
     */
    public final static String EFFECT_TINT =
            "android.media.effect.effects.TintEffect";

    /**
     * <p>Adds a vignette effect to image, i.e. fades away the outer image edges.</p>
     * <p>Available parameters:</p>
     * <table>
     * <tr><td>Parameter name</td><td>Meaning</td><td>Valid values</td></tr>
     * <tr><td><code>scale</code></td>
     *     <td>The scale of vignetting.</td>
     *     <td>Float, between 0 and 1. 0 means no change.</td>
     * </tr>
     * </table>
     */
    public final static String EFFECT_VIGNETTE =
            "android.media.effect.effects.VignetteEffect";

    EffectFactory(EffectContext effectContext) {
        mEffectContext = effectContext;
    }

    /**
     * Instantiate a new effect with the given effect name.
     *
     * <p>The effect's parameters will be set to their default values.</p>
     *
     * <p>Note that the EGL context associated with the current EffectContext need not be made
     * current when creating an effect. This allows the host application to instantiate effects
     * before any EGL context has become current.</p>
     *
     * @param effectName The name of the effect to create.
     * @return A new Effect instance.
     * @throws IllegalArgumentException if the effect with the specified name is not supported or
     *         not known.
     */
    public Effect createEffect(String effectName) {
        Class effectClass = getEffectClassByName(effectName);
        if (effectClass == null) {
            throw new IllegalArgumentException("Cannot instantiate unknown effect '" +
                effectName + "'!");
        }
        return instantiateEffect(effectClass, effectName);
    }

    /**
     * Check if an effect is supported on this platform.
     *
     * <p>Some effects may only be available on certain platforms. Use this method before
     * instantiating an effect to make sure it is supported.</p>
     *
     * @param effectName The name of the effect.
     * @return true, if the effect is supported on this platform.
     * @throws IllegalArgumentException if the effect name is not known.
     */
    public static boolean isEffectSupported(String effectName) {
        return getEffectClassByName(effectName) != null;
    }

    private static Class getEffectClassByName(String className) {
        Class effectClass = null;

        // Get context's classloader; otherwise cannot load non-framework effects
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();

        // Look for the class in the imported packages
        for (String packageName : EFFECT_PACKAGES) {
            try {
                effectClass = contextClassLoader.loadClass(packageName + className);
            } catch (ClassNotFoundException e) {
                continue;
            }
            // Exit loop if class was found.
            if (effectClass != null) {
                break;
            }
        }
        return effectClass;
    }

    private Effect instantiateEffect(Class effectClass, String name) {
        // Make sure this is an Effect subclass
        try {
            effectClass.asSubclass(Effect.class);
        } catch (ClassCastException e) {
            throw new IllegalArgumentException("Attempting to allocate effect '" + effectClass
                + "' which is not a subclass of Effect!", e);
        }

        // Look for the correct constructor
        Constructor effectConstructor = null;
        try {
            effectConstructor = effectClass.getConstructor(EffectContext.class, String.class);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("The effect class '" + effectClass + "' does not have "
                + "the required constructor.", e);
        }

        // Construct the effect
        Effect effect = null;
        try {
            effect = (Effect)effectConstructor.newInstance(mEffectContext, name);
        } catch (Throwable t) {
            throw new RuntimeException("There was an error constructing the effect '" + effectClass
                + "'!", t);
        }

        return effect;
    }
}
