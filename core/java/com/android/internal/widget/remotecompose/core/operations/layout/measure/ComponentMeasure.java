/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.widget.remotecompose.core.operations.layout.measure;
import com.android.internal.widget.remotecompose.core.operations.layout.Component;

/**
 * Encapsulate the result of a measure pass for a component
 */
public class ComponentMeasure {
    int mId = -1;
    float mX;
    float mY;
    float mW;
    float mH;
    Component.Visibility mVisibility = Component.Visibility.VISIBLE;

    public void setX(float value) {
        mX = value;
    }
    public void setY(float value) {
        mY = value;
    }
    public void setW(float value) {
        mW = value;
    }
    public void setH(float value) {
        mH = value;
    }
    public float getX() {
        return mX;
    }
    public float getY() {
        return mY;
    }
    public float getW() {
        return mW;
    }
    public float getH() {
        return mH;
    }

    public Component.Visibility getVisibility() {
        return mVisibility;
    }

    public void setVisibility(Component.Visibility visibility) {
        mVisibility = visibility;
    }

    public ComponentMeasure(int id, float x, float y, float w, float h,
                            Component.Visibility visibility) {
        this.mId = id;
        this.mX = x;
        this.mY = y;
        this.mW = w;
        this.mH = h;
        this.mVisibility = visibility;
    }

    public ComponentMeasure(int id, float x, float y, float w, float h) {
        this(id, x, y, w, h, Component.Visibility.VISIBLE);
    }

    public ComponentMeasure(Component component) {
        this(component.getComponentId(), component.getX(), component.getY(),
                component.getWidth(), component.getHeight(),
                component.mVisibility);
    }

    public void copyFrom(ComponentMeasure m) {
        mX = m.mX;
        mY = m.mY;
        mW = m.mW;
        mH = m.mH;
        mVisibility = m.mVisibility;
    }
}
