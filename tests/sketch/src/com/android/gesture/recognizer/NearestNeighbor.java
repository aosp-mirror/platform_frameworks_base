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

import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.TreeMap;

public class NearestNeighbor extends Classifier {
  
    private static final String LOGTAG = "NearestNeighbor";
    private static final double variance = 0.25; // std = 0.5
    
    public ArrayList<Prediction> classify(Instance instance) {
    
        ArrayList<Prediction> list = new ArrayList<Prediction>();
        Iterator<Instance> it = mInstances.values().iterator();
        Log.v(LOGTAG, mInstances.size() + " instances found");
        TreeMap<String, Double> label2score = new TreeMap<String, Double>();
        while (it.hasNext()) {
            Instance sample = it.next();
            double dis = RecognitionUtil.cosineDistance(sample, instance);
            double weight = Math.exp(-dis*dis/(2 * variance));
            Log.v(LOGTAG, sample.label + " = " + dis + " weight = " + weight);
            Double score = label2score.get(sample.label);
            if (score == null) {
                score = weight;
            }
            else {
                score += weight;
            }
            label2score.put(sample.label, score);
        }
        
        double sum = 0;
        Iterator it2 = label2score.keySet().iterator();
        while (it2.hasNext()) {
            String name = (String)it2.next();
            double score = label2score.get(name);
            sum += score;
            list.add(new Prediction(name, score));
        }
        
        it2 = list.iterator();
        while (it2.hasNext()) {
            Prediction name = (Prediction)it2.next();
            name.score /= sum;
        }
    
        
        Collections.sort(list, new Comparator<Prediction>() {
            @Override
            public int compare(Prediction object1, Prediction object2) {
                // TODO Auto-generated method stub
                double score1 = object1.score;
                double score2 = object2.score;
                if (score1 > score2)
                    return -1;
                else if (score1 < score2)
                    return 1;
                else
                    return 0;
            }
        });
        
        it2 = list.iterator();
        while (it2.hasNext()) {
            Prediction name = (Prediction)it2.next();
            Log.v(LOGTAG, "prediction [" + name.label + " = " + name.score + "]");
        }
        
        return list;
    }
}
