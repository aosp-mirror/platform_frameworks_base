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

import java.util.HashMap;

/**
 * Represents the result of a measure pass on the entire hierarchy
 * TODO: optimize to use a flat array vs the current hashmap
 */
public class MeasurePass {
    HashMap<Integer, ComponentMeasure> mList = new HashMap<>();

    public void clear() {
        mList.clear();
    }

    public void add(ComponentMeasure measure) throws Exception {
        if (measure.mId == -1) {
            throw new Exception("Component has no id!");
        }
        mList.put(measure.mId, measure);
    }

    public boolean contains(int id) {
        return mList.containsKey(id);
    }

    public ComponentMeasure get(Component c) {
        if (!mList.containsKey(c.getComponentId())) {
            ComponentMeasure measure = new ComponentMeasure(c.getComponentId(),
                    c.getX(), c.getY(), c.getWidth(), c.getHeight());
            mList.put(c.getComponentId(), measure);
            return measure;
        }
        return mList.get(c.getComponentId());
    }

    public ComponentMeasure get(int id) {
        if (!mList.containsKey(id)) {
            ComponentMeasure measure = new ComponentMeasure(id,
                    0f, 0f, 0f, 0f, Component.Visibility.GONE);
            mList.put(id, measure);
            return measure;
        }
        return mList.get(id);
    }
}
