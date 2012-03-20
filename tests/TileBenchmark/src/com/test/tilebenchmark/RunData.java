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

package com.test.tilebenchmark;

import java.io.Serializable;
import java.util.HashMap;

public class RunData implements Serializable {
    public TileData[][] frames;
    public HashMap<String, Double> singleStats = new HashMap<String, Double>();

    public RunData(int frames) {
        this.frames = new TileData[frames][];
    }

    public class TileData implements Serializable {
        public int left, top, right, bottom;
        public boolean isReady;
        public int level;
        public float scale;

        public TileData(int left, int top, int right, int bottom,
                boolean isReady, int level, float scale) {
            this.left = left;
            this.right = right;
            this.top = top;
            this.bottom = bottom;
            this.isReady = isReady;
            this.level = level;
            this.scale = scale;
        }

        public String toString() {
            return "Tile (" + left + "," + top + ")->("
                    + right + "," + bottom + ")"
                    + (isReady ? "ready" : "NOTready") + " at scale " + scale;
        }
    }

}
