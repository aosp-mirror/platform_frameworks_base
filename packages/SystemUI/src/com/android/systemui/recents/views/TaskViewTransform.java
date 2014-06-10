/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents.views;

import android.graphics.Rect;


/* The transform state for a task view */
public class TaskViewTransform {
    public int translationY = 0;
    public int translationZ = 0;
    public float scale = 1f;
    public float alpha = 1f;
    public float dismissAlpha = 1f;
    public boolean visible = false;
    public Rect rect = new Rect();
    float t;

    public TaskViewTransform() {
        // Do nothing
    }

    public TaskViewTransform(TaskViewTransform o) {
        translationY = o.translationY;
        translationZ = o.translationZ;
        scale = o.scale;
        alpha = o.alpha;
        dismissAlpha = o.dismissAlpha;
        visible = o.visible;
        rect.set(o.rect);
        t = o.t;
    }

    @Override
    public String toString() {
        return "TaskViewTransform y: " + translationY + " scale: " + scale + " alpha: " + alpha +
                " visible: " + visible + " rect: " + rect + " dismissAlpha: " + dismissAlpha;
    }
}
