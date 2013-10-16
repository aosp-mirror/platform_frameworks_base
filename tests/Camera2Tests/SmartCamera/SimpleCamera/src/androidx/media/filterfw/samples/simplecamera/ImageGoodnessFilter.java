/*
 * Copyright 2013 The Android Open Source Project
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
// Takes sharpness score, rates the image good if above 10, bad otherwise

package androidx.media.filterfw.samples.simplecamera;

import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.util.Log;
import android.widget.ImageView;
import androidx.media.filterfw.Filter;
import androidx.media.filterfw.FrameImage2D;
import androidx.media.filterfw.FrameType;
import androidx.media.filterfw.FrameValue;
import androidx.media.filterfw.MffContext;
import androidx.media.filterfw.OutputPort;
import androidx.media.filterfw.Signature;

public class ImageGoodnessFilter extends Filter {

    private static final String TAG = "ImageGoodnessFilter";
    private static boolean mLogVerbose = Log.isLoggable(TAG, Log.VERBOSE);

    private final static String GREAT = "Great Picture!";
    private final static String GOOD = "Good Picture!";
    private final static String OK = "Ok Picture";
    private final static String BAD = "Bad Picture";
    private final static String AWFUL = "Awful Picture";
    private final static float SMALL_SCORE_INC = 0.25f;
    private final static float BIG_SCORE_INC = 0.5f;
    private final static float LOW_VARIANCE = 0.1f;
    private final static float MEDIUM_VARIANCE = 10;
    private final static float HIGH_VARIANCE = 100;
    private float sharpnessMean = 0;
    private float sharpnessVar = 0;
    private float underExposureMean = 0;
    private float underExposureVar = 0;
    private float overExposureMean = 0;
    private float overExposureVar = 0;
    private float contrastMean = 0;
    private float contrastVar = 0;
    private float colorfulnessMean = 0;
    private float colorfulnessVar = 0;
    private float brightnessMean = 0;
    private float brightnessVar = 0;

    private float motionMean = 0;
    private float scoreMean = 0;
    private static final float DECAY = 0.03f;
    /**
     * @param context
     * @param name
     */
    public ImageGoodnessFilter(MffContext context, String name) {
        super(context, name);
    }

    @Override
    public Signature getSignature() {
        FrameType floatT = FrameType.single(float.class);
        FrameType imageIn = FrameType.image2D(FrameType.ELEMENT_RGBA8888, FrameType.READ_GPU);

        return new Signature()
                .addInputPort("sharpness", Signature.PORT_REQUIRED, floatT)
                .addInputPort("overExposure", Signature.PORT_REQUIRED, floatT)
                .addInputPort("underExposure", Signature.PORT_REQUIRED, floatT)
                .addInputPort("colorfulness", Signature.PORT_REQUIRED, floatT)
                .addInputPort("contrastRating", Signature.PORT_REQUIRED, floatT)
                .addInputPort("motionValues", Signature.PORT_REQUIRED, FrameType.array(float.class))
                .addInputPort("brightness", Signature.PORT_REQUIRED, floatT)
                .addInputPort("capturing", Signature.PORT_REQUIRED, FrameType.single(boolean.class))
                .addInputPort("image", Signature.PORT_REQUIRED, imageIn)
                .addOutputPort("goodOrBadPic", Signature.PORT_REQUIRED,
                        FrameType.single(String.class))
                .addOutputPort("score", Signature.PORT_OPTIONAL, floatT)
                .disallowOtherPorts();
    }

    /**
     * @see androidx.media.filterfw.Filter#onProcess()
     */
    @Override
    protected void onProcess() {
        FrameValue sharpnessFrameValue =
                getConnectedInputPort("sharpness").pullFrame().asFrameValue();
        float sharpness = ((Float)sharpnessFrameValue.getValue()).floatValue();

        FrameValue overExposureFrameValue =
                getConnectedInputPort("overExposure").pullFrame().asFrameValue();
        float overExposure = ((Float)overExposureFrameValue.getValue()).floatValue();

        FrameValue underExposureFrameValue =
                getConnectedInputPort("underExposure").pullFrame().asFrameValue();
        float underExposure = ((Float)underExposureFrameValue.getValue()).floatValue();

        FrameValue colorfulnessFrameValue =
                getConnectedInputPort("colorfulness").pullFrame().asFrameValue();
        float colorfulness = ((Float)colorfulnessFrameValue.getValue()).floatValue();

        FrameValue contrastRatingFrameValue =
                getConnectedInputPort("contrastRating").pullFrame().asFrameValue();
        float contrastRating = ((Float)contrastRatingFrameValue.getValue()).floatValue();

        FrameValue brightnessFrameValue =
                getConnectedInputPort("brightness").pullFrame().asFrameValue();
        float brightness = ((Float)brightnessFrameValue.getValue()).floatValue();

        FrameValue motionValuesFrameValue =
                getConnectedInputPort("motionValues").pullFrame().asFrameValue();
        float[] motionValues = (float[]) motionValuesFrameValue.getValue();


        float vectorAccel = (float) Math.sqrt(Math.pow(motionValues[0], 2) +
                Math.pow(motionValues[1], 2) + Math.pow(motionValues[2], 2));
        String outStr;

        FrameValue capturingFrameValue =
                getConnectedInputPort("capturing").pullFrame().asFrameValue();
        boolean capturing = (Boolean) capturingFrameValue.getValue();

        FrameImage2D inputImage = getConnectedInputPort("image").pullFrame().asFrameImage2D();


        // TODO: get rid of magic numbers
        float score = 0.0f;
        score = computePictureScore(vectorAccel, sharpness, underExposure, overExposure,
                    contrastRating, colorfulness, brightness);
        if (scoreMean == 0) scoreMean = score;
        else scoreMean = scoreMean * (1 - DECAY) + score * DECAY;

        if (motionMean == 0) motionMean = vectorAccel;
        else motionMean = motionMean * (1 - DECAY) + vectorAccel * DECAY;

        float classifierScore = classifierComputeScore(vectorAccel, sharpness, underExposure,
                colorfulness, contrastRating, score);

//        Log.v(TAG, "ClassifierScore:: " + classifierScore);
        final float GREAT_SCORE = 3.5f;
        final float GOOD_SCORE = 2.5f;
        final float OK_SCORE = 1.5f;
        final float BAD_SCORE = 0.5f;

        if (score >= GREAT_SCORE) {
            outStr = GREAT;
        } else if (score >= GOOD_SCORE) {
            outStr = GOOD;
        } else if (score >= OK_SCORE) {
            outStr = OK;
        } else if (score >= BAD_SCORE) {
            outStr = BAD;
        } else {
            outStr = AWFUL;
        }

        if(capturing) {
            if (outStr.equals(GREAT)) {
                // take a picture
                Bitmap bitmap = inputImage.toBitmap();

                new AsyncOperation().execute(bitmap);
                final float RESET_FEATURES = 0.01f;
                sharpnessMean = RESET_FEATURES;
                underExposureMean = RESET_FEATURES;
                overExposureMean = RESET_FEATURES;
                contrastMean = RESET_FEATURES;
                colorfulnessMean = RESET_FEATURES;
                brightnessMean = RESET_FEATURES;
            }
        }

        OutputPort outPort = getConnectedOutputPort("goodOrBadPic");
        FrameValue stringFrame = outPort.fetchAvailableFrame(null).asFrameValue();
        stringFrame.setValue(outStr);
        outPort.pushFrame(stringFrame);

        OutputPort scoreOutPort = getConnectedOutputPort("score");
        FrameValue scoreFrame = scoreOutPort.fetchAvailableFrame(null).asFrameValue();
        scoreFrame.setValue(score);
        scoreOutPort.pushFrame(scoreFrame);

    }

    private class AsyncOperation extends AsyncTask<Bitmap, Void, String> {
        private Bitmap b;
        protected void onPostExecute(String result) {
            ImageView view = SmartCamera.getImageView();
            view.setImageBitmap(b);
        }

        @Override
        protected String doInBackground(Bitmap... params) {
            // TODO Auto-generated method stub
            b = params[0];
            return null;
        }

    }
    // Returns a number between -1 and 1
    private float classifierComputeScore(float vectorAccel, float sharpness, float underExposure,
           float colorfulness, float contrast, float score) {
        float result = (-0.0223f * sharpness + -0.0563f * underExposure + 0.0137f * colorfulness
                + 0.3102f * contrast + 0.0314f * vectorAccel + -0.0094f * score + 0.0227f *
                sharpnessMean + 0.0459f * underExposureMean + -0.3934f * contrastMean +
                -0.0697f * motionMean + 0.0091f * scoreMean + -0.0152f);
        return result;
    }

    // Returns a number between -1 and 4 representing the score for this picture
    private float computePictureScore(float vector_accel, float sharpness,
            float underExposure, float overExposure, float contrastRating, float colorfulness,
            float brightness) {
        final float ACCELERATION_THRESHOLD_VERY_STEADY = 0.1f;
        final float ACCELERATION_THRESHOLD_STEADY = 0.3f;
        final float ACCELERATION_THRESHOLD_MOTION = 2f;

        float score = 0.0f;
        if (vector_accel > ACCELERATION_THRESHOLD_MOTION) {
            score -= (BIG_SCORE_INC + BIG_SCORE_INC); // set score to -1, bad pic
        } else if (vector_accel > ACCELERATION_THRESHOLD_STEADY) {
            score -= BIG_SCORE_INC;
            score = subComputeScore(sharpness, underExposure, overExposure, contrastRating,
                    colorfulness, brightness, score);
        } else if (vector_accel < ACCELERATION_THRESHOLD_VERY_STEADY) {
            score += BIG_SCORE_INC;
            score = subComputeScore(sharpness, underExposure, overExposure, contrastRating,
                    colorfulness, brightness, score);
        } else {
            score = subComputeScore(sharpness, underExposure, overExposure, contrastRating,
                    colorfulness, brightness, score);
        }
        return score;
    }

    // Changes the score by at most +/- 3.5
    private float subComputeScore(float sharpness, float underExposure, float overExposure,
                float contrastRating, float colorfulness, float brightness, float score) {
        // The score methods return values -0.5 to 0.5
        final float SHARPNESS_WEIGHT = 2;
        score += SHARPNESS_WEIGHT * sharpnessScore(sharpness);
        score += underExposureScore(underExposure);
        score += overExposureScore(overExposure);
        score += contrastScore(contrastRating);
        score += colorfulnessScore(colorfulness);
        score += brightnessScore(brightness);
        return score;
    }

    private float sharpnessScore(float sharpness) {
        if (sharpnessMean == 0) {
            sharpnessMean = sharpness;
            sharpnessVar = 0;
            return 0;
        } else {
            sharpnessMean = sharpnessMean * (1 - DECAY) + sharpness * DECAY;
            sharpnessVar = sharpnessVar * (1 - DECAY) + (sharpness - sharpnessMean) *
                    (sharpness - sharpnessMean) * DECAY;
            if (sharpnessVar < LOW_VARIANCE) {
                return BIG_SCORE_INC;
            } else if (sharpness < sharpnessMean && sharpnessVar > MEDIUM_VARIANCE) {
                return -BIG_SCORE_INC;
            } else if (sharpness < sharpnessMean) {
                return -SMALL_SCORE_INC;
            } else if (sharpness > sharpnessMean && sharpnessVar > HIGH_VARIANCE) {
                return 0;
            } else if (sharpness > sharpnessMean && sharpnessVar > MEDIUM_VARIANCE) {
                return SMALL_SCORE_INC;
            } else  {
                return BIG_SCORE_INC; // low variance, sharpness above the mean
            }
        }
    }

    private float underExposureScore(float underExposure) {
        if (underExposureMean == 0) {
            underExposureMean = underExposure;
            underExposureVar = 0;
            return 0;
        } else {
            underExposureMean = underExposureMean * (1 - DECAY) + underExposure * DECAY;
            underExposureVar = underExposureVar * (1 - DECAY) + (underExposure - underExposureMean)
                    * (underExposure - underExposureMean) * DECAY;
            if (underExposureVar < LOW_VARIANCE) {
                return BIG_SCORE_INC;
            } else if (underExposure > underExposureMean && underExposureVar > MEDIUM_VARIANCE) {
                return -BIG_SCORE_INC;
            } else if (underExposure > underExposureMean) {
                return -SMALL_SCORE_INC;
            } else if (underExposure < underExposureMean && underExposureVar > HIGH_VARIANCE) {
                return 0;
            } else if (underExposure < underExposureMean && underExposureVar > MEDIUM_VARIANCE) {
                return SMALL_SCORE_INC;
            } else {
                return BIG_SCORE_INC; // low variance, underExposure below the mean
            }
        }
    }

    private float overExposureScore(float overExposure) {
        if (overExposureMean == 0) {
            overExposureMean = overExposure;
            overExposureVar = 0;
            return 0;
        } else {
            overExposureMean = overExposureMean * (1 - DECAY) + overExposure * DECAY;
            overExposureVar = overExposureVar * (1 - DECAY) + (overExposure - overExposureMean) *
                    (overExposure - overExposureMean) * DECAY;
            if (overExposureVar < LOW_VARIANCE) {
                return BIG_SCORE_INC;
            } else if (overExposure > overExposureMean && overExposureVar > MEDIUM_VARIANCE) {
                return -BIG_SCORE_INC;
            } else if (overExposure > overExposureMean) {
                return -SMALL_SCORE_INC;
            } else if (overExposure < overExposureMean && overExposureVar > HIGH_VARIANCE) {
                return 0;
            } else if (overExposure < overExposureMean && overExposureVar > MEDIUM_VARIANCE) {
                return SMALL_SCORE_INC;
            } else {
                return BIG_SCORE_INC; // low variance, overExposure below the mean
            }
        }
    }

    private float contrastScore(float contrast) {
        if (contrastMean == 0) {
            contrastMean = contrast;
            contrastVar = 0;
            return 0;
        } else {
            contrastMean = contrastMean * (1 - DECAY) + contrast * DECAY;
            contrastVar = contrastVar * (1 - DECAY) + (contrast - contrastMean) *
                    (contrast - contrastMean) * DECAY;
            if (contrastVar < LOW_VARIANCE) {
                return BIG_SCORE_INC;
            } else if (contrast < contrastMean && contrastVar > MEDIUM_VARIANCE) {
                return -BIG_SCORE_INC;
            } else if (contrast < contrastMean) {
                return -SMALL_SCORE_INC;
            } else if (contrast > contrastMean && contrastVar > 100) {
                return 0;
            } else if (contrast > contrastMean && contrastVar > MEDIUM_VARIANCE) {
                return SMALL_SCORE_INC;
            } else {
                return BIG_SCORE_INC; // low variance, contrast above the mean
            }
        }
    }

    private float colorfulnessScore(float colorfulness) {
        if (colorfulnessMean == 0) {
            colorfulnessMean = colorfulness;
            colorfulnessVar = 0;
            return 0;
        } else {
            colorfulnessMean = colorfulnessMean * (1 - DECAY) + colorfulness * DECAY;
            colorfulnessVar = colorfulnessVar * (1 - DECAY) + (colorfulness - colorfulnessMean) *
                    (colorfulness - colorfulnessMean) * DECAY;
            if (colorfulnessVar < LOW_VARIANCE) {
                return BIG_SCORE_INC;
            } else if (colorfulness < colorfulnessMean && colorfulnessVar > MEDIUM_VARIANCE) {
                return -BIG_SCORE_INC;
            } else if (colorfulness < colorfulnessMean) {
                return -SMALL_SCORE_INC;
            } else if (colorfulness > colorfulnessMean && colorfulnessVar > 100) {
                return 0;
            } else if (colorfulness > colorfulnessMean && colorfulnessVar > MEDIUM_VARIANCE) {
                return SMALL_SCORE_INC;
            } else {
                return BIG_SCORE_INC; // low variance, colorfulness above the mean
            }
        }
    }

    private float brightnessScore(float brightness) {
        if (brightnessMean == 0) {
            brightnessMean = brightness;
            brightnessVar = 0;
            return 0;
        } else {
            brightnessMean = brightnessMean * (1 - DECAY) + brightness * DECAY;
            brightnessVar = brightnessVar * (1 - DECAY) + (brightness - brightnessMean) *
                    (brightness - brightnessMean) * DECAY;
            if (brightnessVar < LOW_VARIANCE) {
                return BIG_SCORE_INC;
            } else if (brightness < brightnessMean && brightnessVar > MEDIUM_VARIANCE) {
                return -BIG_SCORE_INC;
            } else if (brightness < brightnessMean) {
                return -SMALL_SCORE_INC;
            } else if (brightness > brightnessMean && brightnessVar > 100) {
                return 0;
            } else if (brightness > brightnessMean && brightnessVar > MEDIUM_VARIANCE) {
                return SMALL_SCORE_INC;
            } else {
                return BIG_SCORE_INC; // low variance, brightness above the mean
            }
        }
    }
}
