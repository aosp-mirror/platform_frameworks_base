/*
 * Copyright (C) 2009 The Android Open Source Project
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

import java.util.Set;
import java.util.ArrayList;

public abstract class GestureLibrary {
    protected final GestureStore mStore;

    protected GestureLibrary() {
        mStore = new GestureStore();
    }

    public abstract boolean save();

    public abstract boolean load();

    public boolean isReadOnly() {
        return false;
    }

    /** @hide */
    public Learner getLearner() {
        return mStore.getLearner();
    }

    public void setOrientationStyle(int style) {
        mStore.setOrientationStyle(style);
    }

    public int getOrientationStyle() {
        return mStore.getOrientationStyle();
    }

    public void setSequenceType(int type) {
        mStore.setSequenceType(type);
    }

    public int getSequenceType() {
        return mStore.getSequenceType();
    }

    public Set<String> getGestureEntries() {
        return mStore.getGestureEntries();
    }

    public ArrayList<Prediction> recognize(Gesture gesture) {
        return mStore.recognize(gesture);
    }

    public void addGesture(String entryName, Gesture gesture) {
        mStore.addGesture(entryName, gesture);
    }

    public void removeGesture(String entryName, Gesture gesture) {
        mStore.removeGesture(entryName, gesture);
    }

    public void removeEntry(String entryName) {
        mStore.removeEntry(entryName);
    }

    public ArrayList<Gesture> getGestures(String entryName) {
        return mStore.getGestures(entryName);
    }
}
