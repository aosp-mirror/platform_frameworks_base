/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.test.hwui;

import android.app.Activity;
import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Mesh;
import android.graphics.MeshSpecification;
import android.graphics.MeshSpecification.Attribute;
import android.graphics.MeshSpecification.Varying;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.View;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

public class MeshLargeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(new MeshView(this));
    }

    static class MeshView extends View {
        MeshView(Context c) {
            super(c);
            this.setOnTouchListener((v, event) -> {
                invalidate();
                return true;
            });
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            MeshSpecification meshSpec = createMeshSpecification();
            int numTriangles = 10000;
            // number of triangles plus first 2 vertices
            FloatBuffer vertexBuffer = FloatBuffer.allocate((numTriangles + 2) * 30);
            ShortBuffer indexBuffer = ShortBuffer.allocate(numTriangles * 3);

            float origin = 500.0f;
            int radius = 200;

            // origin
            vertexBuffer.put(0, origin);
            vertexBuffer.put(1, origin);
            for (int i = 0; i < 7; i++) {
                vertexBuffer.put(2 + (i * 4), 1.0f);
                vertexBuffer.put(2 + (i * 4) + 1, 1.0f);
                vertexBuffer.put(2 + (i * 4) + 2, 1.0f);
                vertexBuffer.put(2 + (i * 4) + 3, 1.0f);
            }

            // first point
            vertexBuffer.put(30, origin + radius);
            vertexBuffer.put(31, origin);
            for (int i = 0; i < 7; i++) {
                vertexBuffer.put(32 + (i * 4), 1.0f);
                vertexBuffer.put(32 + (i * 4) + 1, 1.0f);
                vertexBuffer.put(32 + (i * 4) + 2, 1.0f);
                vertexBuffer.put(32 + (i * 4) + 3, 1.0f);
            }

            int nVert = 2;
            int nInd = 0;
            for (int i = 2; i <= numTriangles + 1; i++) {
                double angle = 2 * Math.PI * i / numTriangles;
                double x = radius * Math.cos(angle);
                double y = radius * Math.sin(angle);
                // position
                vertexBuffer.put(i * 30, origin + (float) x);
                vertexBuffer.put(i * 30 + 1, origin + (float) y);

                // test through test7
                for (int j = 0; j < 7; j++) {
                    vertexBuffer.put((i * 30 + 2) + (j * 4), 1.0f);
                    vertexBuffer.put((i * 30 + 2) + (j * 4) + 1, 1.0f);
                    vertexBuffer.put((i * 30 + 2) + (j * 4) + 2, 1.0f);
                    vertexBuffer.put((i * 30 + 2) + (j * 4) + 3, 1.0f);
                }

                indexBuffer.put(nInd++, (short) 0);
                indexBuffer.put(nInd++, (short) (nVert - 1));
                indexBuffer.put(nInd++, (short) nVert);
                nVert++;
            }
            vertexBuffer.rewind();
            indexBuffer.rewind();
            Mesh mesh = new Mesh(
                    meshSpec, Mesh.TRIANGLES, vertexBuffer, numTriangles + 2, indexBuffer,
                    new RectF(0, 0, 1000, 1000)
            );
            mesh.setFloatUniform("test", 1.0f, 2.0f);
            Paint paint = new Paint();
            paint.setColor(Color.BLUE);

            canvas.drawMesh(mesh, BlendMode.SRC, paint);
        }

        private MeshSpecification createMeshSpecification() {
            String vs = "Varyings main(const Attributes attributes) { "
                    + "     Varyings varyings;"
                    + "     varyings.position = attributes.position;"
                    + "     return varyings;"
                    + "}";
            String fs = "uniform float2 test;"
                    + "float2 main(const Varyings varyings, out float4 color) {\n"
                    + "      color = vec4(1.0, 0.0, 0.0, 1.0);"
                    + "      return varyings.position;\n"
                    + "}";
            Attribute[] attList = new Attribute[]{
                    new Attribute(MeshSpecification.TYPE_FLOAT2, 0, "position"),
                    new Attribute(MeshSpecification.TYPE_FLOAT4, 8, "test"),
                    new Attribute(MeshSpecification.TYPE_FLOAT4, 24, "test2"),
                    new Attribute(
                            MeshSpecification.TYPE_FLOAT4,
                            40,
                            "test3"
                    ),
                    new Attribute(
                            MeshSpecification.TYPE_FLOAT4,
                            56,
                            "test4"
                    ),
                    new Attribute(
                            MeshSpecification.TYPE_FLOAT4,
                            72,
                            "test5"
                    ),
                    new Attribute(
                            MeshSpecification.TYPE_FLOAT4,
                            88,
                            "test6"
                    ),
                    new Attribute(
                            MeshSpecification.TYPE_FLOAT4,
                            104,
                            "test7"
                    )
            };
            Varying[] varyList = new Varying[0];
            return MeshSpecification.make(attList, 120, varyList, vs, fs);
        }
    }
}
