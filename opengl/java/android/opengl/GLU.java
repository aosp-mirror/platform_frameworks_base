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

        float[] scratch = sScratch;
        synchronized(scratch) {
            Matrix.setLookAtM(scratch, 0, eyeX, eyeY, eyeZ, centerX, centerY, centerZ,
                    upX, upY, upZ);
            gl.glMultMatrixf(scratch, 0);
        }
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
        float[] scratch = sScratch;
        synchronized(scratch) {
            final int M_OFFSET = 0; // 0..15
            final int V_OFFSET = 16; // 16..19
            final int V2_OFFSET = 20; // 20..23
            Matrix.multiplyMM(scratch, M_OFFSET, project, projectOffset,
                    model, modelOffset);

            scratch[V_OFFSET + 0] = objX;
            scratch[V_OFFSET + 1] = objY;
            scratch[V_OFFSET + 2] = objZ;
            scratch[V_OFFSET + 3] = 1.0f;

            Matrix.multiplyMV(scratch, V2_OFFSET,
                    scratch, M_OFFSET, scratch, V_OFFSET);

            float w = scratch[V2_OFFSET + 3];
            if (w == 0.0f) {
                return GL10.GL_FALSE;
            }

            float rw = 1.0f / w;

            win[winOffset] =
                    view[viewOffset] + view[viewOffset + 2]
                            * (scratch[V2_OFFSET + 0] * rw + 1.0f)
                            * 0.5f;
            win[winOffset + 1] =
                    view[viewOffset + 1] + view[viewOffset + 3]
                            * (scratch[V2_OFFSET + 1] * rw + 1.0f) * 0.5f;
            win[winOffset + 2] = (scratch[V2_OFFSET + 2] * rw + 1.0f) * 0.5f;
        }

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
     * @param obj the output vector {objX, objY, objZ, objW}, that returns the
     *        computed homogeneous object coordinates.
     * @param objOffset the offset into the obj array where the obj vector data
     *        starts.
     * @return A return value of GL10.GL_TRUE indicates success, a return value
     *         of GL10.GL_FALSE indicates failure.
     */
    public static int gluUnProject(float winX, float winY, float winZ,
            float[] model, int modelOffset, float[] project, int projectOffset,
            int[] view, int viewOffset, float[] obj, int objOffset) {
        float[] scratch = sScratch;
        synchronized(scratch) {
            final int PM_OFFSET = 0; // 0..15
            final int INVPM_OFFSET = 16; // 16..31
               final int V_OFFSET = 0; // 0..3 Reuses PM_OFFSET space
            Matrix.multiplyMM(scratch, PM_OFFSET, project, projectOffset,
                    model, modelOffset);

            if (!Matrix.invertM(scratch, INVPM_OFFSET, scratch, PM_OFFSET)) {
                return GL10.GL_FALSE;
            }

            scratch[V_OFFSET + 0] =
                    2.0f * (winX - view[viewOffset + 0]) / view[viewOffset + 2]
                            - 1.0f;
            scratch[V_OFFSET + 1] =
                    2.0f * (winY - view[viewOffset + 1]) / view[viewOffset + 3]
                            - 1.0f;
            scratch[V_OFFSET + 2] = 2.0f * winZ - 1.0f;
            scratch[V_OFFSET + 3] = 1.0f;

            Matrix.multiplyMV(obj, objOffset, scratch, INVPM_OFFSET,
                    scratch, V_OFFSET);
        }

        return GL10.GL_TRUE;
    }

    private static final float[] sScratch = new float[32];
 }
