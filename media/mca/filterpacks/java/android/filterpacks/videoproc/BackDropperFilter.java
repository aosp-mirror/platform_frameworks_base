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

package android.filterpacks.videoproc;

import android.filterfw.core.Filter;
import android.filterfw.core.FilterContext;
import android.filterfw.core.GenerateFieldPort;
import android.filterfw.core.GenerateFinalPort;
import android.filterfw.core.Frame;
import android.filterfw.core.GLFrame;
import android.filterfw.core.FrameFormat;
import android.filterfw.core.MutableFrameFormat;
import android.filterfw.core.Program;
import android.filterfw.core.ShaderProgram;
import android.filterfw.format.ImageFormat;
import android.opengl.GLES20;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

import java.lang.ArrayIndexOutOfBoundsException;
import java.lang.Math;
import java.util.Arrays;
import java.nio.ByteBuffer;

/**
 * @hide
 */
public class BackDropperFilter extends Filter {
    /** User-visible parameters */

    private final int BACKGROUND_STRETCH   = 0;
    private final int BACKGROUND_FIT       = 1;
    private final int BACKGROUND_FILL_CROP = 2;

    @GenerateFieldPort(name = "backgroundFitMode", hasDefault = true)
    private int mBackgroundFitMode = BACKGROUND_FILL_CROP;
    @GenerateFieldPort(name = "learningDuration", hasDefault = true)
    private int mLearningDuration = DEFAULT_LEARNING_DURATION;
    @GenerateFieldPort(name = "learningVerifyDuration", hasDefault = true)
    private int mLearningVerifyDuration = DEFAULT_LEARNING_VERIFY_DURATION;
    @GenerateFieldPort(name = "acceptStddev", hasDefault = true)
    private float mAcceptStddev = DEFAULT_ACCEPT_STDDEV;
    @GenerateFieldPort(name = "hierLrgScale", hasDefault = true)
    private float mHierarchyLrgScale = DEFAULT_HIER_LRG_SCALE;
    @GenerateFieldPort(name = "hierMidScale", hasDefault = true)
    private float mHierarchyMidScale = DEFAULT_HIER_MID_SCALE;
    @GenerateFieldPort(name = "hierSmlScale", hasDefault = true)
    private float mHierarchySmlScale = DEFAULT_HIER_SML_SCALE;

    // Dimensions of foreground / background mask. Optimum value should take into account only
    // image contents, NOT dimensions of input video stream.
    @GenerateFieldPort(name = "maskWidthExp", hasDefault = true)
    private int mMaskWidthExp = DEFAULT_MASK_WIDTH_EXPONENT;
    @GenerateFieldPort(name = "maskHeightExp", hasDefault = true)
    private int mMaskHeightExp = DEFAULT_MASK_HEIGHT_EXPONENT;

    // Levels at which to compute foreground / background decision. Think of them as are deltas
    // SUBTRACTED from maskWidthExp and maskHeightExp.
    @GenerateFieldPort(name = "hierLrgExp", hasDefault = true)
    private int mHierarchyLrgExp = DEFAULT_HIER_LRG_EXPONENT;
    @GenerateFieldPort(name = "hierMidExp", hasDefault = true)
    private int mHierarchyMidExp = DEFAULT_HIER_MID_EXPONENT;
    @GenerateFieldPort(name = "hierSmlExp", hasDefault = true)
    private int mHierarchySmlExp = DEFAULT_HIER_SML_EXPONENT;

    @GenerateFieldPort(name = "lumScale", hasDefault = true)
    private float mLumScale = DEFAULT_Y_SCALE_FACTOR;
    @GenerateFieldPort(name = "chromaScale", hasDefault = true)
    private float mChromaScale = DEFAULT_UV_SCALE_FACTOR;
    @GenerateFieldPort(name = "maskBg", hasDefault = true)
    private float mMaskBg = DEFAULT_MASK_BLEND_BG;
    @GenerateFieldPort(name = "maskFg", hasDefault = true)
    private float mMaskFg = DEFAULT_MASK_BLEND_FG;
    @GenerateFieldPort(name = "exposureChange", hasDefault = true)
    private float mExposureChange = DEFAULT_EXPOSURE_CHANGE;
    @GenerateFieldPort(name = "whitebalanceredChange", hasDefault = true)
    private float mWhiteBalanceRedChange = DEFAULT_WHITE_BALANCE_RED_CHANGE;
    @GenerateFieldPort(name = "whitebalanceblueChange", hasDefault = true)
    private float mWhiteBalanceBlueChange = DEFAULT_WHITE_BALANCE_BLUE_CHANGE;
    @GenerateFieldPort(name = "autowbToggle", hasDefault = true)
    private int mAutoWBToggle = DEFAULT_WHITE_BALANCE_TOGGLE;

    // TODO: These are not updatable:
    @GenerateFieldPort(name = "learningAdaptRate", hasDefault = true)
    private float mAdaptRateLearning = DEFAULT_LEARNING_ADAPT_RATE;
    @GenerateFieldPort(name = "adaptRateBg", hasDefault = true)
    private float mAdaptRateBg = DEFAULT_ADAPT_RATE_BG;
    @GenerateFieldPort(name = "adaptRateFg", hasDefault = true)
    private float mAdaptRateFg = DEFAULT_ADAPT_RATE_FG;
    @GenerateFieldPort(name = "maskVerifyRate", hasDefault = true)
    private float mVerifyRate = DEFAULT_MASK_VERIFY_RATE;
    @GenerateFieldPort(name = "learningDoneListener", hasDefault = true)
    private LearningDoneListener mLearningDoneListener = null;

    @GenerateFieldPort(name = "useTheForce", hasDefault = true)
    private boolean mUseTheForce = false;

    @GenerateFinalPort(name = "provideDebugOutputs", hasDefault = true)
    private boolean mProvideDebugOutputs = false;

    // Whether to mirror the background or not. For ex, the Camera app
    // would mirror the preview for the front camera
    @GenerateFieldPort(name = "mirrorBg", hasDefault = true)
    private boolean mMirrorBg = false;

    // The orientation of the display. This will change the flipping
    // coordinates, if we were to mirror the background
    @GenerateFieldPort(name = "orientation", hasDefault = true)
    private int mOrientation = 0;

    /** Default algorithm parameter values, for non-shader use */

    // Frame count for learning bg model
    private static final int DEFAULT_LEARNING_DURATION = 40;
    // Frame count for learning verification
    private static final int DEFAULT_LEARNING_VERIFY_DURATION = 10;
    // Maximum distance (in standard deviations) for considering a pixel as background
    private static final float DEFAULT_ACCEPT_STDDEV = 0.85f;
    // Variance threshold scale factor for large scale of hierarchy
    private static final float DEFAULT_HIER_LRG_SCALE = 0.7f;
    // Variance threshold scale factor for medium scale of hierarchy
    private static final float DEFAULT_HIER_MID_SCALE = 0.6f;
    // Variance threshold scale factor for small scale of hierarchy
    private static final float DEFAULT_HIER_SML_SCALE = 0.5f;
    // Width of foreground / background mask.
    private static final int DEFAULT_MASK_WIDTH_EXPONENT = 8;
    // Height of foreground / background mask.
    private static final int DEFAULT_MASK_HEIGHT_EXPONENT = 8;
    // Area over which to average for large scale (length in pixels = 2^HIERARCHY_*_EXPONENT)
    private static final int DEFAULT_HIER_LRG_EXPONENT = 3;
    // Area over which to average for medium scale
    private static final int DEFAULT_HIER_MID_EXPONENT = 2;
    // Area over which to average for small scale
    private static final int DEFAULT_HIER_SML_EXPONENT = 0;
    // Scale factor for luminance channel in distance calculations (larger = more significant)
    private static final float DEFAULT_Y_SCALE_FACTOR = 0.40f;
    // Scale factor for chroma channels in distance calculations
    private static final float DEFAULT_UV_SCALE_FACTOR = 1.35f;
    // Mask value to start blending away from background
    private static final float DEFAULT_MASK_BLEND_BG = 0.65f;
    // Mask value to start blending away from foreground
    private static final float DEFAULT_MASK_BLEND_FG = 0.95f;
    // Exposure stop number to change the brightness of foreground
    private static final float DEFAULT_EXPOSURE_CHANGE = 1.0f;
    // White balance change in Red channel for foreground
    private static final float DEFAULT_WHITE_BALANCE_RED_CHANGE = 0.0f;
    // White balance change in Blue channel for foreground
    private static final float DEFAULT_WHITE_BALANCE_BLUE_CHANGE = 0.0f;
    // Variable to control automatic white balance effect
    // 0.f -> Auto WB is off; 1.f-> Auto WB is on
    private static final int DEFAULT_WHITE_BALANCE_TOGGLE = 0;

    // Default rate at which to learn bg model during learning period
    private static final float DEFAULT_LEARNING_ADAPT_RATE = 0.2f;
    // Default rate at which to learn bg model from new background pixels
    private static final float DEFAULT_ADAPT_RATE_BG = 0.0f;
    // Default rate at which to learn bg model from new foreground pixels
    private static final float DEFAULT_ADAPT_RATE_FG = 0.0f;
    // Default rate at which to verify whether background is stable
    private static final float DEFAULT_MASK_VERIFY_RATE = 0.25f;
    // Default rate at which to verify whether background is stable
    private static final int   DEFAULT_LEARNING_DONE_THRESHOLD = 20;

    // Default 3x3 matrix, column major, for fitting background 1:1
    private static final float[] DEFAULT_BG_FIT_TRANSFORM = new float[] {
        1.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 1.0f
    };

    /** Default algorithm parameter values, for shader use */

    // Area over which to blur binary mask values (length in pixels = 2^MASK_SMOOTH_EXPONENT)
    private static final String MASK_SMOOTH_EXPONENT = "2.0";
    // Scale value for mapping variance distance to fit nicely to 0-1, 8-bit
    private static final String DISTANCE_STORAGE_SCALE = "0.6";
    // Scale value for mapping variance to fit nicely to 0-1, 8-bit
    private static final String VARIANCE_STORAGE_SCALE = "5.0";
    // Default scale of auto white balance parameters
    private static final String DEFAULT_AUTO_WB_SCALE = "0.25";
    // Minimum variance (0-255 scale)
    private static final String MIN_VARIANCE = "3.0";
    // Column-major array for 4x4 matrix converting RGB to YCbCr, JPEG definition (no pedestal)
    private static final String RGB_TO_YUV_MATRIX = "0.299, -0.168736,  0.5,      0.000, " +
                                                    "0.587, -0.331264, -0.418688, 0.000, " +
                                                    "0.114,  0.5,      -0.081312, 0.000, " +
                                                    "0.000,  0.5,       0.5,      1.000 ";
    /** Stream names */

    private static final String[] mInputNames = {"video",
                                                 "background"};

    private static final String[] mOutputNames = {"video"};

    private static final String[] mDebugOutputNames = {"debug1",
                                                       "debug2"};

    /** Other private variables */

    private FrameFormat mOutputFormat;
    private MutableFrameFormat mMemoryFormat;
    private MutableFrameFormat mMaskFormat;
    private MutableFrameFormat mAverageFormat;

    private final boolean mLogVerbose;
    private static final String TAG = "BackDropperFilter";

    /** Shader source code */

    // Shared uniforms and utility functions
    private static String mSharedUtilShader =
            "precision mediump float;\n" +
            "uniform float fg_adapt_rate;\n" +
            "uniform float bg_adapt_rate;\n" +
            "const mat4 coeff_yuv = mat4(" + RGB_TO_YUV_MATRIX + ");\n" +
            "const float dist_scale = " + DISTANCE_STORAGE_SCALE + ";\n" +
            "const float inv_dist_scale = 1. / dist_scale;\n" +
            "const float var_scale=" + VARIANCE_STORAGE_SCALE + ";\n" +
            "const float inv_var_scale = 1. / var_scale;\n" +
            "const float min_variance = inv_var_scale *" + MIN_VARIANCE + "/ 256.;\n" +
            "const float auto_wb_scale = " + DEFAULT_AUTO_WB_SCALE + ";\n" +
            "\n" +
            // Variance distance in luminance between current pixel and background model
            "float gauss_dist_y(float y, float mean, float variance) {\n" +
            "  float dist = (y - mean) * (y - mean) / variance;\n" +
            "  return dist;\n" +
            "}\n" +
            // Sum of variance distances in chroma between current pixel and background
            // model
            "float gauss_dist_uv(vec2 uv, vec2 mean, vec2 variance) {\n" +
            "  vec2 dist = (uv - mean) * (uv - mean) / variance;\n" +
            "  return dist.r + dist.g;\n" +
            "}\n" +
            // Select learning rate for pixel based on smoothed decision mask alpha
            "float local_adapt_rate(float alpha) {\n" +
            "  return mix(bg_adapt_rate, fg_adapt_rate, alpha);\n" +
            "}\n" +
            "\n";

    // Distance calculation shader. Calculates a distance metric between the foreground and the
    //   current background model, in both luminance and in chroma (yuv space).  Distance is
    //   measured in variances from the mean background value. For chroma, the distance is the sum
    //   of the two individual color channel distances. The distances are output on the b and alpha
    //   channels, r and g are for debug information.
    // Inputs:
    //   tex_sampler_0: Mip-map for foreground (live) video frame.
    //   tex_sampler_1: Background mean mask.
    //   tex_sampler_2: Background variance mask.
    //   subsample_level: Level on foreground frame's mip-map.
    private static final String mBgDistanceShader =
            "uniform sampler2D tex_sampler_0;\n" +
            "uniform sampler2D tex_sampler_1;\n" +
            "uniform sampler2D tex_sampler_2;\n" +
            "uniform float subsample_level;\n" +
            "varying vec2 v_texcoord;\n" +
            "void main() {\n" +
            "  vec4 fg_rgb = texture2D(tex_sampler_0, v_texcoord, subsample_level);\n" +
            "  vec4 fg = coeff_yuv * vec4(fg_rgb.rgb, 1.);\n" +
            "  vec4 mean = texture2D(tex_sampler_1, v_texcoord);\n" +
            "  vec4 variance = inv_var_scale * texture2D(tex_sampler_2, v_texcoord);\n" +
            "\n" +
            "  float dist_y = gauss_dist_y(fg.r, mean.r, variance.r);\n" +
            "  float dist_uv = gauss_dist_uv(fg.gb, mean.gb, variance.gb);\n" +
            "  gl_FragColor = vec4(0.5*fg.rg, dist_scale*dist_y, dist_scale*dist_uv);\n" +
            "}\n";

    // Foreground/background mask decision shader. Decides whether a frame is in the foreground or
    //   the background using a hierarchical threshold on the distance. Binary foreground/background
    //   mask is placed in the alpha channel. The RGB channels contain debug information.
    private static final String mBgMaskShader =
            "uniform sampler2D tex_sampler_0;\n" +
            "uniform float accept_variance;\n" +
            "uniform vec2 yuv_weights;\n" +
            "uniform float scale_lrg;\n" +
            "uniform float scale_mid;\n" +
            "uniform float scale_sml;\n" +
            "uniform float exp_lrg;\n" +
            "uniform float exp_mid;\n" +
            "uniform float exp_sml;\n" +
            "varying vec2 v_texcoord;\n" +
            // Decide whether pixel is foreground or background based on Y and UV
            //   distance and maximum acceptable variance.
            // yuv_weights.x is smaller than yuv_weights.y to discount the influence of shadow
            "bool is_fg(vec2 dist_yc, float accept_variance) {\n" +
            "  return ( dot(yuv_weights, dist_yc) >= accept_variance );\n" +
            "}\n" +
            "void main() {\n" +
            "  vec4 dist_lrg_sc = texture2D(tex_sampler_0, v_texcoord, exp_lrg);\n" +
            "  vec4 dist_mid_sc = texture2D(tex_sampler_0, v_texcoord, exp_mid);\n" +
            "  vec4 dist_sml_sc = texture2D(tex_sampler_0, v_texcoord, exp_sml);\n" +
            "  vec2 dist_lrg = inv_dist_scale * dist_lrg_sc.ba;\n" +
            "  vec2 dist_mid = inv_dist_scale * dist_mid_sc.ba;\n" +
            "  vec2 dist_sml = inv_dist_scale * dist_sml_sc.ba;\n" +
            "  vec2 norm_dist = 0.75 * dist_sml / accept_variance;\n" + // For debug viz
            "  bool is_fg_lrg = is_fg(dist_lrg, accept_variance * scale_lrg);\n" +
            "  bool is_fg_mid = is_fg_lrg || is_fg(dist_mid, accept_variance * scale_mid);\n" +
            "  float is_fg_sml =\n" +
            "      float(is_fg_mid || is_fg(dist_sml, accept_variance * scale_sml));\n" +
            "  float alpha = 0.5 * is_fg_sml + 0.3 * float(is_fg_mid) + 0.2 * float(is_fg_lrg);\n" +
            "  gl_FragColor = vec4(alpha, norm_dist, is_fg_sml);\n" +
            "}\n";

    // Automatic White Balance parameter decision shader
    // Use the Gray World assumption that in a white balance corrected image, the average of R, G, B
    //   channel will be a common gray value.
    // To match the white balance of foreground and background, the average of R, G, B channel of
    //   two videos should match.
    // Inputs:
    //   tex_sampler_0: Mip-map for foreground (live) video frame.
    //   tex_sampler_1: Mip-map for background (playback) video frame.
    //   pyramid_depth: Depth of input frames' mip-maps.
    private static final String mAutomaticWhiteBalance =
            "uniform sampler2D tex_sampler_0;\n" +
            "uniform sampler2D tex_sampler_1;\n" +
            "uniform float pyramid_depth;\n" +
            "uniform bool autowb_toggle;\n" +
            "varying vec2 v_texcoord;\n" +
            "void main() {\n" +
            "   vec4 mean_video = texture2D(tex_sampler_0, v_texcoord, pyramid_depth);\n"+
            "   vec4 mean_bg = texture2D(tex_sampler_1, v_texcoord, pyramid_depth);\n" +
            // If Auto WB is toggled off, the return texture will be a unicolor texture of value 1
            // If Auto WB is toggled on, the return texture will be a unicolor texture with
            //   adjustment parameters for R and B channels stored in the corresponding channel
            "   float green_normalizer = mean_video.g / mean_bg.g;\n"+
            "   vec4 adjusted_value = vec4(mean_bg.r / mean_video.r * green_normalizer, 1., \n" +
            "                         mean_bg.b / mean_video.b * green_normalizer, 1.) * auto_wb_scale; \n" +
            "   gl_FragColor = autowb_toggle ? adjusted_value : vec4(auto_wb_scale);\n" +
            "}\n";


    // Background subtraction shader. Uses a mipmap of the binary mask map to blend smoothly between
    //   foreground and background
    // Inputs:
    //   tex_sampler_0: Foreground (live) video frame.
    //   tex_sampler_1: Background (playback) video frame.
    //   tex_sampler_2: Foreground/background mask.
    //   tex_sampler_3: Auto white-balance factors.
    private static final String mBgSubtractShader =
            "uniform mat3 bg_fit_transform;\n" +
            "uniform float mask_blend_bg;\n" +
            "uniform float mask_blend_fg;\n" +
            "uniform float exposure_change;\n" +
            "uniform float whitebalancered_change;\n" +
            "uniform float whitebalanceblue_change;\n" +
            "uniform sampler2D tex_sampler_0;\n" +
            "uniform sampler2D tex_sampler_1;\n" +
            "uniform sampler2D tex_sampler_2;\n" +
            "uniform sampler2D tex_sampler_3;\n" +
            "varying vec2 v_texcoord;\n" +
            "void main() {\n" +
            "  vec2 bg_texcoord = (bg_fit_transform * vec3(v_texcoord, 1.)).xy;\n" +
            "  vec4 bg_rgb = texture2D(tex_sampler_1, bg_texcoord);\n" +
            // The foreground texture is modified by multiplying both manual and auto white balance changes in R and B
            //   channel and multiplying exposure change in all R, G, B channels.
            "  vec4 wb_auto_scale = texture2D(tex_sampler_3, v_texcoord) * exposure_change / auto_wb_scale;\n" +
            "  vec4 wb_manual_scale = vec4(1. + whitebalancered_change, 1., 1. + whitebalanceblue_change, 1.);\n" +
            "  vec4 fg_rgb = texture2D(tex_sampler_0, v_texcoord);\n" +
            "  vec4 fg_adjusted = fg_rgb * wb_manual_scale * wb_auto_scale;\n"+
            "  vec4 mask = texture2D(tex_sampler_2, v_texcoord, \n" +
            "                      " + MASK_SMOOTH_EXPONENT + ");\n" +
            "  float alpha = smoothstep(mask_blend_bg, mask_blend_fg, mask.a);\n" +
            "  gl_FragColor = mix(bg_rgb, fg_adjusted, alpha);\n";

    // May the Force... Makes the foreground object translucent blue, with a bright
    // blue-white outline
    private static final String mBgSubtractForceShader =
            "  vec4 ghost_rgb = (fg_adjusted * 0.7 + vec4(0.3,0.3,0.4,0.))*0.65 + \n" +
            "                   0.35*bg_rgb;\n" +
            "  float glow_start = 0.75 * mask_blend_bg; \n"+
            "  float glow_max   = mask_blend_bg; \n"+
            "  gl_FragColor = mask.a < glow_start ? bg_rgb : \n" +
            "                 mask.a < glow_max ? mix(bg_rgb, vec4(0.9,0.9,1.0,1.0), \n" +
            "                                     (mask.a - glow_start) / (glow_max - glow_start) ) : \n" +
            "                 mask.a < mask_blend_fg ? mix(vec4(0.9,0.9,1.0,1.0), ghost_rgb, \n" +
            "                                    (mask.a - glow_max) / (mask_blend_fg - glow_max) ) : \n" +
            "                 ghost_rgb;\n" +
            "}\n";

    // Background model mean update shader. Skews the current model mean toward the most recent pixel
    //   value for a pixel, weighted by the learning rate and by whether the pixel is classified as
    //   foreground or background.
    // Inputs:
    //   tex_sampler_0: Mip-map for foreground (live) video frame.
    //   tex_sampler_1: Background mean mask.
    //   tex_sampler_2: Foreground/background mask.
    //   subsample_level: Level on foreground frame's mip-map.
    private static final String mUpdateBgModelMeanShader =
            "uniform sampler2D tex_sampler_0;\n" +
            "uniform sampler2D tex_sampler_1;\n" +
            "uniform sampler2D tex_sampler_2;\n" +
            "uniform float subsample_level;\n" +
            "varying vec2 v_texcoord;\n" +
            "void main() {\n" +
            "  vec4 fg_rgb = texture2D(tex_sampler_0, v_texcoord, subsample_level);\n" +
            "  vec4 fg = coeff_yuv * vec4(fg_rgb.rgb, 1.);\n" +
            "  vec4 mean = texture2D(tex_sampler_1, v_texcoord);\n" +
            "  vec4 mask = texture2D(tex_sampler_2, v_texcoord, \n" +
            "                      " + MASK_SMOOTH_EXPONENT + ");\n" +
            "\n" +
            "  float alpha = local_adapt_rate(mask.a);\n" +
            "  vec4 new_mean = mix(mean, fg, alpha);\n" +
            "  gl_FragColor = new_mean;\n" +
            "}\n";

    // Background model variance update shader. Skews the current model variance toward the most
    //   recent variance for the pixel, weighted by the learning rate and by whether the pixel is
    //   classified as foreground or background.
    // Inputs:
    //   tex_sampler_0: Mip-map for foreground (live) video frame.
    //   tex_sampler_1: Background mean mask.
    //   tex_sampler_2: Background variance mask.
    //   tex_sampler_3: Foreground/background mask.
    //   subsample_level: Level on foreground frame's mip-map.
    // TODO: to improve efficiency, use single mark for mean + variance, then merge this into
    // mUpdateBgModelMeanShader.
    private static final String mUpdateBgModelVarianceShader =
            "uniform sampler2D tex_sampler_0;\n" +
            "uniform sampler2D tex_sampler_1;\n" +
            "uniform sampler2D tex_sampler_2;\n" +
            "uniform sampler2D tex_sampler_3;\n" +
            "uniform float subsample_level;\n" +
            "varying vec2 v_texcoord;\n" +
            "void main() {\n" +
            "  vec4 fg_rgb = texture2D(tex_sampler_0, v_texcoord, subsample_level);\n" +
            "  vec4 fg = coeff_yuv * vec4(fg_rgb.rgb, 1.);\n" +
            "  vec4 mean = texture2D(tex_sampler_1, v_texcoord);\n" +
            "  vec4 variance = inv_var_scale * texture2D(tex_sampler_2, v_texcoord);\n" +
            "  vec4 mask = texture2D(tex_sampler_3, v_texcoord, \n" +
            "                      " + MASK_SMOOTH_EXPONENT + ");\n" +
            "\n" +
            "  float alpha = local_adapt_rate(mask.a);\n" +
            "  vec4 cur_variance = (fg-mean)*(fg-mean);\n" +
            "  vec4 new_variance = mix(variance, cur_variance, alpha);\n" +
            "  new_variance = max(new_variance, vec4(min_variance));\n" +
            "  gl_FragColor = var_scale * new_variance;\n" +
            "}\n";

    // Background verification shader. Skews the current background verification mask towards the
    //   most recent frame, weighted by the learning rate.
    private static final String mMaskVerifyShader =
            "uniform sampler2D tex_sampler_0;\n" +
            "uniform sampler2D tex_sampler_1;\n" +
            "uniform float verify_rate;\n" +
            "varying vec2 v_texcoord;\n" +
            "void main() {\n" +
            "  vec4 lastmask = texture2D(tex_sampler_0, v_texcoord);\n" +
            "  vec4 mask = texture2D(tex_sampler_1, v_texcoord);\n" +
            "  float newmask = mix(lastmask.a, mask.a, verify_rate);\n" +
            "  gl_FragColor = vec4(0., 0., 0., newmask);\n" +
            "}\n";

    /** Shader program objects */

    private ShaderProgram mBgDistProgram;
    private ShaderProgram mBgMaskProgram;
    private ShaderProgram mBgSubtractProgram;
    private ShaderProgram mBgUpdateMeanProgram;
    private ShaderProgram mBgUpdateVarianceProgram;
    private ShaderProgram mCopyOutProgram;
    private ShaderProgram mAutomaticWhiteBalanceProgram;
    private ShaderProgram mMaskVerifyProgram;
    private ShaderProgram copyShaderProgram;

    /** Background model storage */

    private boolean mPingPong;
    private GLFrame mBgMean[];
    private GLFrame mBgVariance[];
    private GLFrame mMaskVerify[];
    private GLFrame mDistance;
    private GLFrame mAutoWB;
    private GLFrame mMask;
    private GLFrame mVideoInput;
    private GLFrame mBgInput;
    private GLFrame mMaskAverage;

    /** Overall filter state */

    private boolean isOpen;
    private int mFrameCount;
    private boolean mStartLearning;
    private boolean mBackgroundFitModeChanged;
    private float mRelativeAspect;
    private int mPyramidDepth;
    private int mSubsampleLevel;

    /** Learning listener object */

    public interface LearningDoneListener {
        public void onLearningDone(BackDropperFilter filter);
    }

    /** Public Filter methods */

    public BackDropperFilter(String name) {
        super(name);

        mLogVerbose = Log.isLoggable(TAG, Log.VERBOSE);

        String adjStr = SystemProperties.get("ro.media.effect.bgdropper.adj");
        if (adjStr.length() > 0) {
            try {
                mAcceptStddev += Float.parseFloat(adjStr);
                if (mLogVerbose) {
                    Log.v(TAG, "Adjusting accept threshold by " + adjStr +
                            ", now " + mAcceptStddev);
                }
            } catch (NumberFormatException e) {
                Log.e(TAG,
                        "Badly formatted property ro.media.effect.bgdropper.adj: " + adjStr);
            }
        }
    }

    @Override
    public void setupPorts() {
        // Inputs.
        // TODO: Target should be GPU, but relaxed for now.
        FrameFormat imageFormat = ImageFormat.create(ImageFormat.COLORSPACE_RGBA,
                                                     FrameFormat.TARGET_UNSPECIFIED);
        for (String inputName : mInputNames) {
            addMaskedInputPort(inputName, imageFormat);
        }
        // Normal outputs
        for (String outputName : mOutputNames) {
            addOutputBasedOnInput(outputName, "video");
        }

        // Debug outputs
        if (mProvideDebugOutputs) {
            for (String outputName : mDebugOutputNames) {
                addOutputBasedOnInput(outputName, "video");
            }
        }
    }

    @Override
    public FrameFormat getOutputFormat(String portName, FrameFormat inputFormat) {
        // Create memory format based on video input.
        MutableFrameFormat format = inputFormat.mutableCopy();
        // Is this a debug output port? If so, leave dimensions unspecified.
        if (!Arrays.asList(mOutputNames).contains(portName)) {
            format.setDimensions(FrameFormat.SIZE_UNSPECIFIED, FrameFormat.SIZE_UNSPECIFIED);
        }
        return format;
    }

    private boolean createMemoryFormat(FrameFormat inputFormat) {
        // We can't resize because that would require re-learning.
        if (mMemoryFormat != null) {
            return false;
        }

        if (inputFormat.getWidth() == FrameFormat.SIZE_UNSPECIFIED ||
            inputFormat.getHeight() == FrameFormat.SIZE_UNSPECIFIED) {
            throw new RuntimeException("Attempting to process input frame with unknown size");
        }

        mMaskFormat = inputFormat.mutableCopy();
        int maskWidth = (int)Math.pow(2, mMaskWidthExp);
        int maskHeight = (int)Math.pow(2, mMaskHeightExp);
        mMaskFormat.setDimensions(maskWidth, maskHeight);

        mPyramidDepth = Math.max(mMaskWidthExp, mMaskHeightExp);
        mMemoryFormat = mMaskFormat.mutableCopy();
        int widthExp = Math.max(mMaskWidthExp, pyramidLevel(inputFormat.getWidth()));
        int heightExp = Math.max(mMaskHeightExp, pyramidLevel(inputFormat.getHeight()));
        mPyramidDepth = Math.max(widthExp, heightExp);
        int memWidth = Math.max(maskWidth, (int)Math.pow(2, widthExp));
        int memHeight = Math.max(maskHeight, (int)Math.pow(2, heightExp));
        mMemoryFormat.setDimensions(memWidth, memHeight);
        mSubsampleLevel = mPyramidDepth - Math.max(mMaskWidthExp, mMaskHeightExp);

        if (mLogVerbose) {
            Log.v(TAG, "Mask frames size " + maskWidth + " x " + maskHeight);
            Log.v(TAG, "Pyramid levels " + widthExp + " x " + heightExp);
            Log.v(TAG, "Memory frames size " + memWidth + " x " + memHeight);
        }

        mAverageFormat = inputFormat.mutableCopy();
        mAverageFormat.setDimensions(1,1);
        return true;
    }

    public void prepare(FilterContext context){
        if (mLogVerbose) Log.v(TAG, "Preparing BackDropperFilter!");

        mBgMean = new GLFrame[2];
        mBgVariance = new GLFrame[2];
        mMaskVerify = new GLFrame[2];
        copyShaderProgram = ShaderProgram.createIdentity(context);
    }

    private void allocateFrames(FrameFormat inputFormat, FilterContext context) {
        if (!createMemoryFormat(inputFormat)) {
            return;  // All set.
        }
        if (mLogVerbose) Log.v(TAG, "Allocating BackDropperFilter frames");

        // Create initial background model values
        int numBytes = mMaskFormat.getSize();
        byte[] initialBgMean = new byte[numBytes];
        byte[] initialBgVariance = new byte[numBytes];
        byte[] initialMaskVerify = new byte[numBytes];
        for (int i = 0; i < numBytes; i++) {
            initialBgMean[i] = (byte)128;
            initialBgVariance[i] = (byte)10;
            initialMaskVerify[i] = (byte)0;
        }

        // Get frames to store background model in
        for (int i = 0; i < 2; i++) {
            mBgMean[i] = (GLFrame)context.getFrameManager().newFrame(mMaskFormat);
            mBgMean[i].setData(initialBgMean, 0, numBytes);

            mBgVariance[i] = (GLFrame)context.getFrameManager().newFrame(mMaskFormat);
            mBgVariance[i].setData(initialBgVariance, 0, numBytes);

            mMaskVerify[i] = (GLFrame)context.getFrameManager().newFrame(mMaskFormat);
            mMaskVerify[i].setData(initialMaskVerify, 0, numBytes);
        }

        // Get frames to store other textures in
        if (mLogVerbose) Log.v(TAG, "Done allocating texture for Mean and Variance objects!");

        mDistance = (GLFrame)context.getFrameManager().newFrame(mMaskFormat);
        mMask = (GLFrame)context.getFrameManager().newFrame(mMaskFormat);
        mAutoWB = (GLFrame)context.getFrameManager().newFrame(mAverageFormat);
        mVideoInput = (GLFrame)context.getFrameManager().newFrame(mMemoryFormat);
        mBgInput = (GLFrame)context.getFrameManager().newFrame(mMemoryFormat);
        mMaskAverage = (GLFrame)context.getFrameManager().newFrame(mAverageFormat);

        // Create shader programs
        mBgDistProgram = new ShaderProgram(context, mSharedUtilShader + mBgDistanceShader);
        mBgDistProgram.setHostValue("subsample_level", (float)mSubsampleLevel);

        mBgMaskProgram = new ShaderProgram(context, mSharedUtilShader + mBgMaskShader);
        mBgMaskProgram.setHostValue("accept_variance", mAcceptStddev * mAcceptStddev);
        float[] yuvWeights = { mLumScale, mChromaScale };
        mBgMaskProgram.setHostValue("yuv_weights", yuvWeights );
        mBgMaskProgram.setHostValue("scale_lrg", mHierarchyLrgScale);
        mBgMaskProgram.setHostValue("scale_mid", mHierarchyMidScale);
        mBgMaskProgram.setHostValue("scale_sml", mHierarchySmlScale);
        mBgMaskProgram.setHostValue("exp_lrg", (float)(mSubsampleLevel + mHierarchyLrgExp));
        mBgMaskProgram.setHostValue("exp_mid", (float)(mSubsampleLevel + mHierarchyMidExp));
        mBgMaskProgram.setHostValue("exp_sml", (float)(mSubsampleLevel + mHierarchySmlExp));

        if (mUseTheForce) {
            mBgSubtractProgram = new ShaderProgram(context, mSharedUtilShader + mBgSubtractShader + mBgSubtractForceShader);
        } else {
            mBgSubtractProgram = new ShaderProgram(context, mSharedUtilShader + mBgSubtractShader + "}\n");
        }
        mBgSubtractProgram.setHostValue("bg_fit_transform", DEFAULT_BG_FIT_TRANSFORM);
        mBgSubtractProgram.setHostValue("mask_blend_bg", mMaskBg);
        mBgSubtractProgram.setHostValue("mask_blend_fg", mMaskFg);
        mBgSubtractProgram.setHostValue("exposure_change", mExposureChange);
        mBgSubtractProgram.setHostValue("whitebalanceblue_change", mWhiteBalanceBlueChange);
        mBgSubtractProgram.setHostValue("whitebalancered_change", mWhiteBalanceRedChange);


        mBgUpdateMeanProgram = new ShaderProgram(context, mSharedUtilShader + mUpdateBgModelMeanShader);
        mBgUpdateMeanProgram.setHostValue("subsample_level", (float)mSubsampleLevel);

        mBgUpdateVarianceProgram = new ShaderProgram(context, mSharedUtilShader + mUpdateBgModelVarianceShader);
        mBgUpdateVarianceProgram.setHostValue("subsample_level", (float)mSubsampleLevel);

        mCopyOutProgram = ShaderProgram.createIdentity(context);

        mAutomaticWhiteBalanceProgram = new ShaderProgram(context, mSharedUtilShader + mAutomaticWhiteBalance);
        mAutomaticWhiteBalanceProgram.setHostValue("pyramid_depth", (float)mPyramidDepth);
        mAutomaticWhiteBalanceProgram.setHostValue("autowb_toggle", mAutoWBToggle);

        mMaskVerifyProgram = new ShaderProgram(context, mSharedUtilShader + mMaskVerifyShader);
        mMaskVerifyProgram.setHostValue("verify_rate", mVerifyRate);

        if (mLogVerbose) Log.v(TAG, "Shader width set to " + mMemoryFormat.getWidth());

        mRelativeAspect = 1.f;

        mFrameCount = 0;
        mStartLearning = true;
    }

    public void process(FilterContext context) {
        // Grab inputs and ready intermediate frames and outputs.
        Frame video = pullInput("video");
        Frame background = pullInput("background");
        allocateFrames(video.getFormat(), context);

        // Update learning rate after initial learning period
        if (mStartLearning) {
            if (mLogVerbose) Log.v(TAG, "Starting learning");
            mBgUpdateMeanProgram.setHostValue("bg_adapt_rate", mAdaptRateLearning);
            mBgUpdateMeanProgram.setHostValue("fg_adapt_rate", mAdaptRateLearning);
            mBgUpdateVarianceProgram.setHostValue("bg_adapt_rate", mAdaptRateLearning);
            mBgUpdateVarianceProgram.setHostValue("fg_adapt_rate", mAdaptRateLearning);
            mFrameCount = 0;
        }

        // Select correct pingpong buffers
        int inputIndex = mPingPong ? 0 : 1;
        int outputIndex = mPingPong ? 1 : 0;
        mPingPong = !mPingPong;

        // Check relative aspect ratios
        updateBgScaling(video, background, mBackgroundFitModeChanged);
        mBackgroundFitModeChanged = false;

        // Make copies for input frames to GLFrames

        copyShaderProgram.process(video, mVideoInput);
        copyShaderProgram.process(background, mBgInput);

        mVideoInput.generateMipMap();
        mVideoInput.setTextureParameter(GLES20.GL_TEXTURE_MIN_FILTER,
                                        GLES20.GL_LINEAR_MIPMAP_NEAREST);

        mBgInput.generateMipMap();
        mBgInput.setTextureParameter(GLES20.GL_TEXTURE_MIN_FILTER,
                                     GLES20.GL_LINEAR_MIPMAP_NEAREST);

        if (mStartLearning) {
            copyShaderProgram.process(mVideoInput, mBgMean[inputIndex]);
            mStartLearning = false;
        }

        // Process shaders
        Frame[] distInputs = { mVideoInput, mBgMean[inputIndex], mBgVariance[inputIndex] };
        mBgDistProgram.process(distInputs, mDistance);
        mDistance.generateMipMap();
        mDistance.setTextureParameter(GLES20.GL_TEXTURE_MIN_FILTER,
                                      GLES20.GL_LINEAR_MIPMAP_NEAREST);

        mBgMaskProgram.process(mDistance, mMask);
        mMask.generateMipMap();
        mMask.setTextureParameter(GLES20.GL_TEXTURE_MIN_FILTER,
                                  GLES20.GL_LINEAR_MIPMAP_NEAREST);

        Frame[] autoWBInputs = { mVideoInput, mBgInput };
        mAutomaticWhiteBalanceProgram.process(autoWBInputs, mAutoWB);

        if (mFrameCount <= mLearningDuration) {
            // During learning
            pushOutput("video", video);

            if (mFrameCount == mLearningDuration - mLearningVerifyDuration) {
                copyShaderProgram.process(mMask, mMaskVerify[outputIndex]);

                mBgUpdateMeanProgram.setHostValue("bg_adapt_rate", mAdaptRateBg);
                mBgUpdateMeanProgram.setHostValue("fg_adapt_rate", mAdaptRateFg);
                mBgUpdateVarianceProgram.setHostValue("bg_adapt_rate", mAdaptRateBg);
                mBgUpdateVarianceProgram.setHostValue("fg_adapt_rate", mAdaptRateFg);


            } else if (mFrameCount > mLearningDuration - mLearningVerifyDuration) {
                // In the learning verification stage, compute background masks and a weighted average
                //   with weights grow exponentially with time
                Frame[] maskVerifyInputs = {mMaskVerify[inputIndex], mMask};
                mMaskVerifyProgram.process(maskVerifyInputs, mMaskVerify[outputIndex]);
                mMaskVerify[outputIndex].generateMipMap();
                mMaskVerify[outputIndex].setTextureParameter(GLES20.GL_TEXTURE_MIN_FILTER,
                                                             GLES20.GL_LINEAR_MIPMAP_NEAREST);
            }

            if (mFrameCount == mLearningDuration) {
                // In the last verification frame, verify if the verification mask is almost blank
                // If not, restart learning
                copyShaderProgram.process(mMaskVerify[outputIndex], mMaskAverage);
                ByteBuffer mMaskAverageByteBuffer = mMaskAverage.getData();
                byte[] mask_average = mMaskAverageByteBuffer.array();
                int bi = (int)(mask_average[3] & 0xFF);

                if (mLogVerbose) {
                    Log.v(TAG,
                            String.format("Mask_average is %d, threshold is %d",
                                    bi, DEFAULT_LEARNING_DONE_THRESHOLD));
                }

                if (bi >= DEFAULT_LEARNING_DONE_THRESHOLD) {
                    mStartLearning = true;                                      // Restart learning
                } else {
                  if (mLogVerbose) Log.v(TAG, "Learning done");
                  if (mLearningDoneListener != null) {
                      mLearningDoneListener.onLearningDone(this);
                   }
                }
            }
        } else {
            Frame output = context.getFrameManager().newFrame(video.getFormat());
            Frame[] subtractInputs = { video, background, mMask, mAutoWB };
            mBgSubtractProgram.process(subtractInputs, output);
            pushOutput("video", output);
            output.release();
        }

        // Compute mean and variance of the background
        if (mFrameCount < mLearningDuration - mLearningVerifyDuration ||
            mAdaptRateBg > 0.0 || mAdaptRateFg > 0.0) {
            Frame[] meanUpdateInputs = { mVideoInput, mBgMean[inputIndex], mMask };
            mBgUpdateMeanProgram.process(meanUpdateInputs, mBgMean[outputIndex]);
            mBgMean[outputIndex].generateMipMap();
            mBgMean[outputIndex].setTextureParameter(GLES20.GL_TEXTURE_MIN_FILTER,
                                                     GLES20.GL_LINEAR_MIPMAP_NEAREST);

            Frame[] varianceUpdateInputs = {
              mVideoInput, mBgMean[inputIndex], mBgVariance[inputIndex], mMask
            };
            mBgUpdateVarianceProgram.process(varianceUpdateInputs, mBgVariance[outputIndex]);
            mBgVariance[outputIndex].generateMipMap();
            mBgVariance[outputIndex].setTextureParameter(GLES20.GL_TEXTURE_MIN_FILTER,
                                                         GLES20.GL_LINEAR_MIPMAP_NEAREST);
        }

        // Provide debug output to two smaller viewers
        if (mProvideDebugOutputs) {
            Frame dbg1 = context.getFrameManager().newFrame(video.getFormat());
            mCopyOutProgram.process(video, dbg1);
            pushOutput("debug1", dbg1);
            dbg1.release();

            Frame dbg2 = context.getFrameManager().newFrame(mMemoryFormat);
            mCopyOutProgram.process(mMask, dbg2);
            pushOutput("debug2", dbg2);
            dbg2.release();
        }

        mFrameCount++;

        if (mLogVerbose) {
            if (mFrameCount % 30 == 0) {
                if (startTime == -1) {
                    context.getGLEnvironment().activate();
                    GLES20.glFinish();
                    startTime = SystemClock.elapsedRealtime();
                } else {
                    context.getGLEnvironment().activate();
                    GLES20.glFinish();
                    long endTime = SystemClock.elapsedRealtime();
                    Log.v(TAG, "Avg. frame duration: " + String.format("%.2f",(endTime-startTime)/30.) +
                          " ms. Avg. fps: " + String.format("%.2f", 1000./((endTime-startTime)/30.)) );
                    startTime = endTime;
                }
            }
        }
    }

    private long startTime = -1;

    public void close(FilterContext context) {
        if (mMemoryFormat == null) {
            return;
        }

        if (mLogVerbose) Log.v(TAG, "Filter Closing!");
        for (int i = 0; i < 2; i++) {
            mBgMean[i].release();
            mBgVariance[i].release();
            mMaskVerify[i].release();
        }
        mDistance.release();
        mMask.release();
        mAutoWB.release();
        mVideoInput.release();
        mBgInput.release();
        mMaskAverage.release();

        mMemoryFormat = null;
    }

    // Relearn background model
    synchronized public void relearn() {
        // Let the processing thread know about learning restart
        mStartLearning = true;
    }

    @Override
    public void fieldPortValueUpdated(String name, FilterContext context) {
        // TODO: Many of these can be made ProgramPorts!
        if (name.equals("backgroundFitMode")) {
            mBackgroundFitModeChanged = true;
        } else if (name.equals("acceptStddev")) {
            mBgMaskProgram.setHostValue("accept_variance", mAcceptStddev * mAcceptStddev);
        } else if (name.equals("hierLrgScale")) {
            mBgMaskProgram.setHostValue("scale_lrg", mHierarchyLrgScale);
        } else if (name.equals("hierMidScale")) {
            mBgMaskProgram.setHostValue("scale_mid", mHierarchyMidScale);
        } else if (name.equals("hierSmlScale")) {
            mBgMaskProgram.setHostValue("scale_sml", mHierarchySmlScale);
        } else if (name.equals("hierLrgExp")) {
            mBgMaskProgram.setHostValue("exp_lrg", (float)(mSubsampleLevel + mHierarchyLrgExp));
        } else if (name.equals("hierMidExp")) {
            mBgMaskProgram.setHostValue("exp_mid", (float)(mSubsampleLevel + mHierarchyMidExp));
        } else if (name.equals("hierSmlExp")) {
            mBgMaskProgram.setHostValue("exp_sml", (float)(mSubsampleLevel + mHierarchySmlExp));
        } else if (name.equals("lumScale") || name.equals("chromaScale")) {
            float[] yuvWeights = { mLumScale, mChromaScale };
            mBgMaskProgram.setHostValue("yuv_weights", yuvWeights );
        } else if (name.equals("maskBg")) {
            mBgSubtractProgram.setHostValue("mask_blend_bg", mMaskBg);
        } else if (name.equals("maskFg")) {
            mBgSubtractProgram.setHostValue("mask_blend_fg", mMaskFg);
        } else if (name.equals("exposureChange")) {
            mBgSubtractProgram.setHostValue("exposure_change", mExposureChange);
        } else if (name.equals("whitebalanceredChange")) {
            mBgSubtractProgram.setHostValue("whitebalancered_change", mWhiteBalanceRedChange);
        } else if (name.equals("whitebalanceblueChange")) {
            mBgSubtractProgram.setHostValue("whitebalanceblue_change", mWhiteBalanceBlueChange);
        } else if (name.equals("autowbToggle")){
            mAutomaticWhiteBalanceProgram.setHostValue("autowb_toggle", mAutoWBToggle);
        }
    }

    private void updateBgScaling(Frame video, Frame background, boolean fitModeChanged) {
        float foregroundAspect = (float)video.getFormat().getWidth() / video.getFormat().getHeight();
        float backgroundAspect = (float)background.getFormat().getWidth() / background.getFormat().getHeight();
        float currentRelativeAspect = foregroundAspect/backgroundAspect;
        if (currentRelativeAspect != mRelativeAspect || fitModeChanged) {
            mRelativeAspect = currentRelativeAspect;
            float xMin = 0.f, xWidth = 1.f, yMin = 0.f, yWidth = 1.f;
            switch (mBackgroundFitMode) {
                case BACKGROUND_STRETCH:
                    // Just map 1:1
                    break;
                case BACKGROUND_FIT:
                    if (mRelativeAspect > 1.0f) {
                        // Foreground is wider than background, scale down
                        // background in X
                        xMin = 0.5f - 0.5f * mRelativeAspect;
                        xWidth = 1.f * mRelativeAspect;
                    } else {
                        // Foreground is taller than background, scale down
                        // background in Y
                        yMin = 0.5f - 0.5f / mRelativeAspect;
                        yWidth = 1 / mRelativeAspect;
                    }
                    break;
                case BACKGROUND_FILL_CROP:
                    if (mRelativeAspect > 1.0f) {
                        // Foreground is wider than background, crop
                        // background in Y
                        yMin = 0.5f - 0.5f / mRelativeAspect;
                        yWidth = 1.f / mRelativeAspect;
                    } else {
                        // Foreground is taller than background, crop
                        // background in X
                        xMin = 0.5f - 0.5f * mRelativeAspect;
                        xWidth = mRelativeAspect;
                    }
                    break;
            }
            // If mirroring is required (for ex. the camera mirrors the preview
            // in the front camera)
            // TODO: Backdropper does not attempt to apply any other transformation
            // than just flipping. However, in the current state, it's "x-axis" is always aligned
            // with the Camera's width. Hence, we need to define the mirroring based on the camera
            // orientation. In the future, a cleaner design would be to cast away all the rotation
            // in a separate place.
            if (mMirrorBg) {
                if (mLogVerbose) Log.v(TAG, "Mirroring the background!");
                // Mirroring in portrait
                if (mOrientation == 0 || mOrientation == 180) {
                    xWidth = -xWidth;
                    xMin = 1.0f - xMin;
                } else {
                    // Mirroring in landscape
                    yWidth = -yWidth;
                    yMin = 1.0f - yMin;
                }
            }
            if (mLogVerbose) Log.v(TAG, "bgTransform: xMin, yMin, xWidth, yWidth : " +
                    xMin + ", " + yMin + ", " + xWidth + ", " + yWidth +
                    ", mRelAspRatio = " + mRelativeAspect);
            // The following matrix is the transpose of the actual matrix
            float[] bgTransform = {xWidth, 0.f, 0.f,
                                   0.f, yWidth, 0.f,
                                   xMin, yMin,  1.f};
            mBgSubtractProgram.setHostValue("bg_fit_transform", bgTransform);
        }
    }

    private int pyramidLevel(int size) {
        return (int)Math.floor(Math.log10(size) / Math.log10(2)) - 1;
    }

}
