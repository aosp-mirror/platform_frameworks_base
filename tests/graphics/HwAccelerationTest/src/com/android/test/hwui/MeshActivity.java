/*
 * Copyright (C) 2022 The Android Open Source Project
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

public class MeshActivity extends Activity {
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
            FloatBuffer vertexBuffer = FloatBuffer.allocate(6);
            vertexBuffer.put(0, 100.0f);
            vertexBuffer.put(1, 100.0f);
            vertexBuffer.put(2, 400.0f);
            vertexBuffer.put(3, 0.0f);
            vertexBuffer.put(4, 0.0f);
            vertexBuffer.put(5, 400.0f);
            vertexBuffer.rewind();
            Mesh mesh = new Mesh(
                    meshSpec, Mesh.TRIANGLES, vertexBuffer, 3, new RectF(0, 0, 1000, 1000));

            canvas.drawMesh(mesh, BlendMode.COLOR, new Paint());

            int numTriangles = 100;
            // number of triangles plus first 2 vertices
            FloatBuffer iVertexBuffer = FloatBuffer.allocate(numTriangles * 2 + 4);
            ShortBuffer indexBuffer = ShortBuffer.allocate(300);

            int radius = 200;
            // origin
            iVertexBuffer.put(0, 500.0f);
            iVertexBuffer.put(1, 500.0f);

            // first point
            iVertexBuffer.put(2, 500.0f + radius);
            iVertexBuffer.put(3, 500.0f);
            int nVert = 2;
            int nInd = 0;
            for (int i = 1; i <= numTriangles; i++) {
                double angle = (Math.PI * i) / numTriangles;
                double x = radius * Math.cos(angle);
                double y = radius * Math.sin(angle);
                iVertexBuffer.put((i + 1) * 2, 500 + (float) x);
                iVertexBuffer.put((i + 1) * 2 + 1, 500 + (float) y);

                indexBuffer.put(nInd++, (short) 0);
                indexBuffer.put(nInd++, (short) (nVert - 1));
                indexBuffer.put(nInd++, (short) nVert);
                nVert++;
            }
            iVertexBuffer.rewind();
            indexBuffer.rewind();
            Mesh mesh2 = new Mesh(meshSpec, Mesh.TRIANGLES, iVertexBuffer, 102, indexBuffer,
                    new RectF(0, 0, 1000, 1000));
            Paint paint = new Paint();
            paint.setColor(Color.RED);
            canvas.drawMesh(mesh2, BlendMode.COLOR, paint);
        }

        private MeshSpecification createMeshSpecification() {
            String vs = "Varyings main(const Attributes attributes) { "
                    + "     Varyings varyings;"
                    + "     varyings.position = attributes.position;"
                    + "     return varyings;"
                    + "}";
            String fs = "float2 main(const Varyings varyings, out float4 color) {\n"
                    + "      color = vec4(1.0, 0.0, 0.0, 1.0);"
                    + "      return varyings.position;\n"
                    + "}";
            Attribute[] attList = new Attribute[]{
                    new Attribute(MeshSpecification.TYPE_FLOAT2, 0, "position"),

            };
            Varying[] varyList = new Varying[0];
            return MeshSpecification.make(attList, 8, varyList, vs, fs);
        }
    }
}
