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
package com.android.internal.widget.remotecompose.player.platform;

import static com.android.internal.widget.remotecompose.core.operations.Utils.idFromNan;

import android.graphics.Path;
import android.graphics.PathMeasure;
import android.os.Build;

import com.android.internal.widget.remotecompose.core.operations.PathData;

public class FloatsToPath {
    public static void genPath(Path retPath,
                               float[] floatPath,
                               float start,
                               float stop) {
        int i = 0;
        Path path = new Path(); // todo this should be cached for performance
        while (i < floatPath.length) {
            switch (idFromNan(floatPath[i])) {
                case PathData.MOVE: {
                    i++;
                    path.moveTo(floatPath[i + 0], floatPath[i + 1]);
                    i += 2;
                }
                break;
                case PathData.LINE: {
                    i += 3;
                    path.lineTo(floatPath[i + 0], floatPath[i + 1]);
                    i += 2;
                }
                break;
                case PathData.QUADRATIC: {
                    i += 3;
                    path.quadTo(
                            floatPath[i + 0],
                            floatPath[i + 1],
                            floatPath[i + 2],
                            floatPath[i + 3]
                    );
                    i += 4;

                }
                break;
                case PathData.CONIC: {
                    i += 3;
                    if (Build.VERSION.SDK_INT >= 34) { // REMOVE IN PLATFORM
                        path.conicTo(
                                floatPath[i + 0], floatPath[i + 1],
                                floatPath[i + 2], floatPath[i + 3],
                                floatPath[i + 4]
                        );
                    }
                    i += 5;
                }
                break;
                case PathData.CUBIC: {
                    i += 3;
                    path.cubicTo(
                            floatPath[i + 0], floatPath[i + 1],
                            floatPath[i + 2], floatPath[i + 3],
                            floatPath[i + 4], floatPath[i + 5]
                    );
                    i += 6;
                }
                break;
                case PathData.CLOSE: {

                    path.close();
                    i++;
                }
                break;
                case PathData.DONE: {
                    i++;
                }
                break;
                default: {
                    System.err.println(" Odd command "
                            + idFromNan(floatPath[i]));
                }
            }
        }

        retPath.reset();
        if (start > 0f || stop < 1f) {
            if (start < stop) {

                PathMeasure measure = new PathMeasure(); // todo cached
                measure.setPath(path, false);
                float len = measure.getLength();
                float scaleStart = Math.max(start, 0f) * len;
                float scaleStop = Math.min(stop, 1f) * len;
                measure.getSegment(scaleStart, scaleStop, retPath,
                        true);
            }
        } else {

            retPath.addPath(path);
        }
    }
}
