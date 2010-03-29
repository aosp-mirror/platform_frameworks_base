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

package com.android.test;
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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.FloatBuffer;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL;
import javax.microedition.khronos.opengles.GL10;
import javax.microedition.khronos.opengles.GL11;
/**
 * An implementation of SurfaceView that uses the dedicated surface for
 * displaying an OpenGL animation.  This allows the animation to run in a
 * separate thread, without requiring that it be driven by the update mechanism
 * of the view hierarchy.
 *
 * The application-specific rendering code is delegated to a GLView.Renderer
 * instance.
 */
class TestView extends GLSurfaceView {
    TestView(Context context) {
        super(context);
        init();
    }

    public TestView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setRenderer(new Renderer());
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }
    
        /** A grid is a topologically rectangular array of vertices.
    *
    * The vertex and index data are held in VBO objects because on most
    * GPUs VBO objects are the fastest way of rendering static vertex
    * and index data.
    *
    */

   private static class Grid {
       // Size of vertex data elements in bytes:
       final static int FLOAT_SIZE = 4;
       final static int CHAR_SIZE = 2;

       // Vertex structure:
       // float x, y, z;

       final static int VERTEX_SIZE = 3 * FLOAT_SIZE;

       private int mVertexBufferObjectId;
       private int mElementBufferObjectId;

       // These buffers are used to hold the vertex and index data while
       // constructing the grid. Once createBufferObjects() is called
       // the buffers are nulled out to save memory.

       private ByteBuffer mVertexByteBuffer;
       private FloatBuffer mVertexBuffer;
       private CharBuffer mIndexBuffer;

       private int mW;
       private int mH;
       private int mIndexCount;

       public Grid(int w, int h) {
           if (w < 0 || w >= 65536) {
               throw new IllegalArgumentException("w");
           }
           if (h < 0 || h >= 65536) {
               throw new IllegalArgumentException("h");
           }
           if (w * h >= 65536) {
               throw new IllegalArgumentException("w * h >= 65536");
           }

           mW = w;
           mH = h;
           int size = w * h;

           mVertexByteBuffer = ByteBuffer.allocateDirect(VERTEX_SIZE * size)
               .order(ByteOrder.nativeOrder());
           mVertexBuffer = mVertexByteBuffer.asFloatBuffer();

           int quadW = mW - 1;
           int quadH = mH - 1;
           int quadCount = quadW * quadH;
           int indexCount = quadCount * 6;
           mIndexCount = indexCount;
           mIndexBuffer = ByteBuffer.allocateDirect(CHAR_SIZE * indexCount)
               .order(ByteOrder.nativeOrder()).asCharBuffer();

           /*
            * Initialize triangle list mesh.
            *
            *     [0]-----[  1] ...
            *      |    /   |
            *      |   /    |
            *      |  /     |
            *     [w]-----[w+1] ...
            *      |       |
            *
            */

           {
               int i = 0;
               for (int y = 0; y < quadH; y++) {
                   for (int x = 0; x < quadW; x++) {
                       char a = (char) (y * mW + x);
                       char b = (char) (y * mW + x + 1);
                       char c = (char) ((y + 1) * mW + x);
                       char d = (char) ((y + 1) * mW + x + 1);

                       mIndexBuffer.put(i++, a);
                       mIndexBuffer.put(i++, c);
                       mIndexBuffer.put(i++, b);

                       mIndexBuffer.put(i++, b);
                       mIndexBuffer.put(i++, c);
                       mIndexBuffer.put(i++, d);
                   }
               }
           }

       }

       public void set(int i, int j, float x, float y, float z) {
           if (i < 0 || i >= mW) {
               throw new IllegalArgumentException("i");
           }
           if (j < 0 || j >= mH) {
               throw new IllegalArgumentException("j");
           }

           int index = mW * j + i;

           mVertexBuffer.position(index * VERTEX_SIZE / FLOAT_SIZE);
           mVertexBuffer.put(x);
           mVertexBuffer.put(y);
           mVertexBuffer.put(z);
       }

       public void createBufferObjects(GL gl) {
           // Generate a the vertex and element buffer IDs
           int[] vboIds = new int[2];
           GL11 gl11 = (GL11) gl;
           gl11.glGenBuffers(2, vboIds, 0);
           mVertexBufferObjectId = vboIds[0];
           mElementBufferObjectId = vboIds[1];

           // Upload the vertex data
           gl11.glBindBuffer(GL11.GL_ARRAY_BUFFER, mVertexBufferObjectId);
           mVertexByteBuffer.position(0);
           gl11.glBufferData(GL11.GL_ARRAY_BUFFER, mVertexByteBuffer.capacity(), mVertexByteBuffer, GL11.GL_STATIC_DRAW);

           gl11.glBindBuffer(GL11.GL_ELEMENT_ARRAY_BUFFER, mElementBufferObjectId);
           mIndexBuffer.position(0);
           gl11.glBufferData(GL11.GL_ELEMENT_ARRAY_BUFFER, mIndexBuffer.capacity() * CHAR_SIZE, mIndexBuffer, GL11.GL_STATIC_DRAW);

           // We don't need the in-memory data any more
           mVertexBuffer = null;
           mVertexByteBuffer = null;
           mIndexBuffer = null;
       }

       public void draw(GL10 gl) {
           GL11 gl11 = (GL11) gl;

           gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);

           gl11.glBindBuffer(GL11.GL_ARRAY_BUFFER, mVertexBufferObjectId);
           gl11.glVertexPointer(3, GL10.GL_FLOAT, VERTEX_SIZE, 0);
           
           gl11.glBindBuffer(GL11.GL_ELEMENT_ARRAY_BUFFER, mElementBufferObjectId);
           gl11.glDrawElements(GL10.GL_TRIANGLES, mIndexCount, GL10.GL_UNSIGNED_SHORT, 0);
           gl.glDisableClientState(GL10.GL_VERTEX_ARRAY);
           gl11.glBindBuffer(GL11.GL_ARRAY_BUFFER, 0);
           gl11.glBindBuffer(GL11.GL_ELEMENT_ARRAY_BUFFER, 0);
       }
   }


    private class Renderer implements GLSurfaceView.Renderer {
        private static final String TAG = "Renderer";
        private Grid mGrid;
        
        public void onDrawFrame(GL10 gl) {
			gl.glClearColor(0,0,1,1);
			gl.glClear(GL10.GL_COLOR_BUFFER_BIT);
            mGrid.draw(gl);
        }

        public void onSurfaceChanged(GL10 gl, int width, int height) {
            gl.glViewport(0, 0, width, height);
			gl.glMatrixMode(GL11.GL_PROJECTION);
			gl.glLoadIdentity();
			gl.glOrthof(0, width, height, 0, -1, 1);
			gl.glMatrixMode(GL11.GL_MODELVIEW);
            createGrid(gl, width, height);
        }

        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        }
        
        private void createGrid(GL10 gl, float w, float h) {
        mGrid = new Grid(2, 2);
			for (int j = 0; j < 2; j++) {
				for (int i = 0; i < 2; i++) {
					float x = w * i;
					float y = h * j;
					float z = 0.0f;
					mGrid.set(i,j, x, y, z);
				}
			}
			mGrid.createBufferObjects(gl);
		}
    }
}

