/*
 * Copyright (C) 2008 The Android Open Source Project
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


package com.android.gesture.recognizer;

import android.graphics.PointF;

import com.android.gesture.Gesture;

/**
 * An instance represents a sample if the label is available or a query if
 * the label is null.
 */
public class Instance {

    private final static float[] targetOrientations = {
        0, 45, 90, 135, 180, -0, -45, -90, -135, -180
    };
    
    // the feature vector
    public final float[] vector;
    // the label can be null
	public final String	label;
	// the length of the vector
	public final float length;
	// the id of the instance
	public final long id;

	Instance(long d, float[] v, String l) {
		id = d;
		vector = v;
		label = l;
		float sum = 0;
		for (int i = 0; i < vector.length; i++) {
			sum += vector[i] * vector[i];
		}
		length = (float)Math.sqrt(sum);
	}
	
    public static Instance createInstance(Gesture gesture, String label) {
        float[] pts = RecognitionUtil.resample(gesture, 64);
        PointF center = RecognitionUtil.computeCentroid(pts);
        float inductiveOrientation = (float)Math.atan2(pts[1] - center.y, 
                pts[0] - center.x);
        inductiveOrientation *= 180 / Math.PI;
        
        float minDeviation = Float.MAX_VALUE;
        for (int i=0; i<targetOrientations.length; i++) {
            float delta = targetOrientations[i] - inductiveOrientation;
            if (Math.abs(delta) < Math.abs(minDeviation)) {
                minDeviation = delta;
            }
        }
        
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setTranslate(-center.x, -center.y);
        android.graphics.Matrix rotation = new android.graphics.Matrix();
        rotation.setRotate(minDeviation);
        m.postConcat(rotation);
        m.mapPoints(pts);

        return new Instance(gesture.getID(), pts, label);
    }
}
