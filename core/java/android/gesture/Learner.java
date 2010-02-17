/*
 * Copyright (C) 2008-2009 The Android Open Source Project
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

package android.gesture;

import java.util.ArrayList;

/**
 * The abstract class of a gesture learner
 */
abstract class Learner {
    private final ArrayList<Instance> mInstances = new ArrayList<Instance>();

    /**
     * Add an instance to the learner
     * 
     * @param instance
     */
    void addInstance(Instance instance) {
        mInstances.add(instance);
    }

    /**
     * Retrieve all the instances
     * 
     * @return instances
     */
    ArrayList<Instance> getInstances() {
        return mInstances;
    }

    /**
     * Remove an instance based on its id
     * 
     * @param id
     */
    void removeInstance(long id) {
        ArrayList<Instance> instances = mInstances;
        int count = instances.size();
        for (int i = 0; i < count; i++) {
            Instance instance = instances.get(i);
            if (id == instance.id) {
                instances.remove(instance);
                return;
            }
        }
    }

    /**
     * Remove all the instances of a category
     * 
     * @param name the category name
     */
    void removeInstances(String name) {
        final ArrayList<Instance> toDelete = new ArrayList<Instance>();
        final ArrayList<Instance> instances = mInstances;
        final int count = instances.size();

        for (int i = 0; i < count; i++) {
            final Instance instance = instances.get(i);
            // the label can be null, as specified in Instance
            if ((instance.label == null && name == null)
                    || (instance.label != null && instance.label.equals(name))) {
                toDelete.add(instance);
            }
        }
        instances.removeAll(toDelete);
    }

    abstract ArrayList<Prediction> classify(int sequenceType, int orientationType, float[] vector);
}
