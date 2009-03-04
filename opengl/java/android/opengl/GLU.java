/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.opengl;

import javax.microedition.khronos.opengles.GL10;

/**
 * A set of GL utilities inspired by the OpenGL Utility Toolkit.
 * 
 */

public class GLU {

    /**
     * Return an error string from a GL or GLU error code.
     * 
     * @param error - a GL or GLU error code.
     * @return the error string for the input error code, or NULL if the input
     *         was not a valid GL or GLU error code.
     */
    public static String gluErrorString(int error) {
        switch (error) {
        case GL10.GL_NO_ERROR:
            return "no error";
        case GL10.GL_INVALID_ENUM:
            return "invalid enum";
        case GL10.GL_INVALID_VALUE:
            return "invalid value";
        case GL10.GL_INVALID_OPERATION:
            return "invalid operation";
        case GL10.GL_STACK_OVERFLOW:
            return "stack overflow";
        case GL10.GL_STACK_UNDERFLOW:
            return "stack underflow";
        case GL10.GL_OUT_OF_MEMORY:
            return "out of memory";
        default:
            return null;
        }
    }

    /**
     * Define a viewing transformation in terms of an eye point, a center of
     * view, and an up vector.
     * 
     * @param gl a GL10 interface
     * @param eyeX eye point X
     * @param eyeY eye point Y
     * @param eyeZ eye point Z
     * @param centerX center of view X
     * @param centerY center of view Y
     * @param centerZ center of view Z
     * @param upX up vector X
     * @param upY up vector Y
     * @param upZ up vector Z
     */
    public static void gluLookAt(GL10 gl, float eyeX, float eyeY, float eyeZ,
            float centerX, float centerY, float centerZ, float upX, float upY,
            float upZ) {

        // See the OpenGL GLUT documentation for gluLookAt for a description
        // of the algorithm. We implement it in a straightforward way:

        float fx = centerX - eyeX;
        float fy = centerY - eyeY;
        float fz = centerZ - eyeZ;

        // Normalize f
        float rlf = 1.0f / Matrix.length(fx, fy, fz);
        fx *= rlf;
        fy *= rlf;
        fz *= rlf;

        // compute s = f x up (x means "cross product")
        float sx = fy * upZ - fz * upY;
        float sy = fz * upX - fx * upZ;
        float sz = fx * upY - fy * upX;
        
        // and normalize s
        float rls = 1.0f / Matrix.length(sx, sy, sz);
        sx *= rls;
        sy *= rls;
        sz *= rls;
        
        // compute u = s x f
        float ux = sy * fz - sz * fy;
        float uy = sz * fx - sx * fz;
        float uz = sx * fy - sy * fx;

        float[] m = new float[16];
        m[0] = sx;
        m[1] = ux;
        m[2] = -fx;
        m[3] = 0.0f;

        m[4] = sy;
        m[5] = uy;
        m[6] = -fy;
        m[7] = 0.0f;

        m[8] = sz;
        m[9] = uz;
        m[10] = -fz;
        m[11] = 0.0f;

        m[12] = 0.0f;
        m[13] = 0.0f;
        m[14] = 0.0f;
        m[15] = 1.0f;

        gl.glMultMatrixf(m, 0);
        gl.glTranslatef(-eyeX, -eyeY, -eyeZ);
    }

    /**
     * Set up a 2D orthographic projection matrix
     * 
     * @param gl
     * @param left
     * @param right
     * @param bottom
     * @param top
     */
    public static void gluOrtho2D(GL10 gl, float left, float right,
            float bottom, float top) {
        gl.glOrthof(left, right, bottom, top, -1.0f, 1.0f);
    }

    /**
     * Set up a perspective projection matrix
     * 
     * @param gl a GL10 interface
     * @param fovy specifies the field of view angle, in degrees, in the Y
     *        direction.
     * @param aspect specifies the aspect ration that determins the field of
     *        view in the x direction. The aspect ratio is the ratio of x
     *        (width) to y (height).
     * @param zNear specifies the distance from the viewer to the near clipping
     *        plane (always positive).
     * @param zFar specifies the distance from the viewer to the far clipping
     *        plane (always positive).
     */
    public static void gluPerspective(GL10 gl, float fovy, float aspect,
            float zNear, float zFar) {
        float top = zNear * (float) Math.tan(fovy * (Math.PI / 360.0));
        float bottom = -top;
        float left = bottom * aspect;
        float right = top * aspect;
        gl.glFrustumf(left, right, bottom, top, zNear, zFar);
    }

    /**
     * Map object coordinates into window coordinates. gluProject transforms the
     * specified object coordinates into window coordinates using model, proj,
     * and view. The result is stored in win.
     * <p>
     * Note that you can use the OES_matrix_get extension, if present, to get
     * the current modelView and projection matrices.
     * 
     * @param objX object coordinates X
     * @param objY object coordinates Y
     * @param objZ object coordinates Z
     * @param model the current modelview matrix
     * @param modelOffset the offset into the model array where the modelview
     *        maxtrix data starts.
     * @param project the current projection matrix
     * @param projectOffset the offset into the project array where the project
     *        matrix data starts.
     * @param view the current view, {x, y, width, height}
     * @param viewOffset the offset into the view array where the view vector
     *        data starts.
     * @param win the output vector {winX, winY, winZ}, that returns the
     *        computed window coordinates.
     * @param winOffset the offset into the win array where the win vector data
     *        starts.
     * @return A return value of GL_TRUE indicates success, a return value of
     *         GL_FALSE indicates failure.
     */
    public static int gluProject(float objX, float objY, float objZ,
            float[] model, int modelOffset, float[] project, int projectOffset,
            int[] view, int viewOffset, float[] win, int winOffset) {
        float[] m = new float[16];
        Matrix.multiplyMM(m, 0, project, projectOffset, model, modelOffset);

        float[] v = new float[4];

        v[0] = objX;
        v[1] = objY;
        v[2] = objZ;
        v[3] = 1.0f;

        float[] v2 = new float[4];

        Matrix.multiplyMV(v2, 0, m, 0, v, 0);

        float w = v2[3];
        if (w == 0.0f) {
            return GL10.GL_FALSE;
        }

        float rw = 1.0f / w;

        win[winOffset] =
                view[viewOffset] + view[viewOffset + 2] * (v2[0] * rw + 1.0f)
                        * 0.5f;
        win[winOffset + 1] =
                view[viewOffset + 1] + view[viewOffset + 3]
                        * (v2[1] * rw + 1.0f) * 0.5f;
        win[winOffset + 2] = (v2[2] * rw + 1.0f) * 0.5f;

        return GL10.GL_TRUE;
    }

    /**
     * Map window coordinates to object coordinates. gluUnProject maps the
     * specified window coordinates into object coordinates using model, proj,
     * and view. The result is stored in obj.
     * <p>
     * Note that you can use the OES_matrix_get extension, if present, to get
     * the current modelView and projection matrices.
     * 
     * @param winX window coordinates X
     * @param winY window coordinates Y
     * @param winZ window coordinates Z
     * @param model the current modelview matrix
     * @param modelOffset the offset into the model array where the modelview
     *        maxtrix data starts.
     * @param project the current projection matrix
     * @param projectOffset the offset into the project array where the project
     *        matrix data starts.
     * @param view the current view, {x, y, width, height}
     * @param viewOffset the offset into the view array where the view vector
     *        data starts.
     * @param obj the output vector {objX, objY, objZ}, that returns the
     *        computed object coordinates.
     * @param objOffset the offset into the obj array where the obj vector data
     *        starts.
     * @return A return value of GL10.GL_TRUE indicates success, a return value
     *         of GL10.GL_FALSE indicates failure.
     */
    public static int gluUnProject(float winX, float winY, float winZ,
            float[] model, int modelOffset, float[] project, int projectOffset,
            int[] view, int viewOffset, float[] obj, int objOffset) {
        float[] pm = new float[16];
        Matrix.multiplyMM(pm, 0, project, projectOffset, model, modelOffset);

        float[] invPM = new float[16];
        if (!Matrix.invertM(invPM, 0, pm, 0)) {
            return GL10.GL_FALSE;
        }

        float[] v = new float[4];

        v[0] =
                2.0f * (winX - view[viewOffset + 0]) / view[viewOffset + 2]
                        - 1.0f;
        v[1] =
                2.0f * (winY - view[viewOffset + 1]) / view[viewOffset + 3]
                        - 1.0f;
        v[2] = 2.0f * winZ - 1.0f;
        v[3] = 1.0f;

        float[] v2 = new float[4];

        Matrix.multiplyMV(v2, 0, invPM, 0, v, 0);

        obj[objOffset] = v2[0];
        obj[objOffset + 1] = v2[1];
        obj[objOffset + 2] = v2[2];

        return GL10.GL_TRUE;
    }

 }
