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

package androidx.media.filterfw.samples.simplecamera;

import android.util.Log;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;

import androidx.media.filterfw.Filter;
import androidx.media.filterfw.FrameBuffer2D;
import androidx.media.filterfw.FrameImage2D;
import androidx.media.filterfw.FrameType;
import androidx.media.filterfw.FrameValue;
import androidx.media.filterfw.MffContext;
import androidx.media.filterfw.OutputPort;
import androidx.media.filterfw.Signature;


public class CSVWriterFilter extends Filter {

    private static final String TAG = "CSVWriterFilter";
    private static boolean mLogVerbose = Log.isLoggable(TAG, Log.VERBOSE);
    private boolean mFirstTime = true;
    private final static int NUM_FRAMES = 3;
    private final String mFileName = "/CSVFile.csv";

    public CSVWriterFilter(MffContext context, String name) {

        super(context, name);
    }

    @Override
    public Signature getSignature() {
        FrameType floatT = FrameType.single(float.class);
        FrameType stringT = FrameType.single(String.class);
        FrameType floatArrayT = FrameType.array(float.class);

        return new Signature()
                .addInputPort("sharpness", Signature.PORT_REQUIRED, floatT)
                .addInputPort("overExposure", Signature.PORT_REQUIRED, floatT)
                .addInputPort("underExposure", Signature.PORT_REQUIRED, floatT)
                .addInputPort("colorfulness", Signature.PORT_REQUIRED, floatT)
                .addInputPort("contrastRating", Signature.PORT_REQUIRED, floatT)
                .addInputPort("brightness", Signature.PORT_REQUIRED, floatT)
                .addInputPort("motionValues", Signature.PORT_REQUIRED, floatArrayT)
                .addInputPort("imageFileName", Signature.PORT_REQUIRED, stringT)
                .addInputPort("csvFilePath", Signature.PORT_REQUIRED, stringT)
                .disallowOtherPorts();
    }

    @Override
    protected void onProcess() {



        Log.v(TAG,"in csv writer on process");
        FrameValue sharpnessValue =
                getConnectedInputPort("sharpness").pullFrame().asFrameValue();
        float sharpness = ((Float)sharpnessValue.getValue()).floatValue();

        FrameValue overExposureValue =
                getConnectedInputPort("overExposure").pullFrame().asFrameValue();
        float overExposure = ((Float)overExposureValue.getValue()).floatValue();

        FrameValue underExposureValue =
                getConnectedInputPort("underExposure").pullFrame().asFrameValue();
        float underExposure = ((Float)underExposureValue.getValue()).floatValue();

        FrameValue colorfulnessValue =
                getConnectedInputPort("colorfulness").pullFrame().asFrameValue();
        float colorfulness = ((Float)colorfulnessValue.getValue()).floatValue();

        FrameValue contrastValue =
                getConnectedInputPort("contrastRating").pullFrame().asFrameValue();
        float contrast = ((Float)contrastValue.getValue()).floatValue();

        FrameValue brightnessValue =
                getConnectedInputPort("brightness").pullFrame().asFrameValue();
        float brightness = ((Float)brightnessValue.getValue()).floatValue();

        FrameValue motionValuesFrameValue =
                getConnectedInputPort("motionValues").pullFrame().asFrameValue();
        float[] motionValues = (float[]) motionValuesFrameValue.getValue();
        float vectorAccel = (float) Math.sqrt(Math.pow(motionValues[0], 2) +
                Math.pow(motionValues[1], 2) + Math.pow(motionValues[2], 2));

        FrameValue imageFileNameFrameValue =
                getConnectedInputPort("imageFileName").pullFrame().asFrameValue();
        String imageFileName = ((String)imageFileNameFrameValue.getValue());

        FrameValue csvFilePathFrameValue =
                getConnectedInputPort("csvFilePath").pullFrame().asFrameValue();
        String csvFilePath = ((String)csvFilePathFrameValue.getValue());


        if(mFirstTime) {
            try {
                FileWriter fileWriter = new FileWriter(csvFilePath + "/CSVFile.csv");
                BufferedWriter csvWriter = new BufferedWriter(fileWriter);

                csvWriter.write("FileName,Sharpness,OverExposure,UnderExposure,Colorfulness," +
                            "ContrastRating,Brightness,Motion");
                csvWriter.newLine();
                csvWriter.close();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            mFirstTime = false;
        }

        try {
            Log.v(TAG,"about to write to file");
            FileWriter fileWriter = new FileWriter(csvFilePath + mFileName, true);
            BufferedWriter csvWriter = new BufferedWriter(fileWriter);

            csvWriter.write(imageFileName + "," + sharpness + "," + overExposure + "," +
                    underExposure + "," + colorfulness + "," + contrast + "," + brightness +
                    "," + vectorAccel);
            Log.v(TAG, "" + imageFileName + "," + sharpness + "," + overExposure + "," +
                    underExposure + "," + colorfulness + "," + contrast + "," + brightness +
                    "," + vectorAccel);
            csvWriter.newLine();
            csvWriter.close();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }
}
