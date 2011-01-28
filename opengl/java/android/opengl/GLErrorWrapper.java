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

import java.nio.Buffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import javax.microedition.khronos.opengles.GL;

/**
 * Implement an error checking wrapper. The wrapper will automatically call
     * glError after each GL operation, and throw a GLException if an error
     * occurs. (By design, calling glError itself will not cause an exception
     * to be thrown.) Enabling error checking is an alternative to manually
     *  calling glError after every GL operation.
 */
class GLErrorWrapper extends GLWrapperBase {
    boolean mCheckError;
    boolean mCheckThread;
    Thread mOurThread;

    public GLErrorWrapper(GL gl, int configFlags) {
        super(gl);
        mCheckError = (configFlags & GLDebugHelper.CONFIG_CHECK_GL_ERROR) != 0;
        mCheckThread = (configFlags & GLDebugHelper.CONFIG_CHECK_THREAD) != 0;
    }

    private void checkThread() {
        if (mCheckThread) {
            Thread currentThread = Thread.currentThread();
            if (mOurThread == null) {
                mOurThread = currentThread;
            } else {
                if (!mOurThread.equals(currentThread)) {
                    throw new GLException(GLDebugHelper.ERROR_WRONG_THREAD,
                            "OpenGL method called from wrong thread.");
                }
            }
        }
    }

    private void checkError() {
        if (mCheckError) {
            int glError;
            if ((glError = mgl.glGetError()) != 0) {
                throw new GLException(glError);
            }
        }
    }

    // ---------------------------------------------------------------------
    // GL10 methods:

    public void glActiveTexture(int texture) {
        checkThread();
        mgl.glActiveTexture(texture);
        checkError();
    }

    public void glAlphaFunc(int func, float ref) {
        checkThread();
        mgl.glAlphaFunc(func, ref);
        checkError();
    }

    public void glAlphaFuncx(int func, int ref) {
        checkThread();
        mgl.glAlphaFuncx(func, ref);
        checkError();
    }

    public void glBindTexture(int target, int texture) {
        checkThread();
        mgl.glBindTexture(target, texture);
        checkError();
    }

    public void glBlendFunc(int sfactor, int dfactor) {
        checkThread();
        mgl.glBlendFunc(sfactor, dfactor);
        checkError();
    }

    public void glClear(int mask) {
        checkThread();
        mgl.glClear(mask);
        checkError();
    }

    public void glClearColor(float red, float green, float blue, float alpha) {
        checkThread();
        mgl.glClearColor(red, green, blue, alpha);
        checkError();
    }

    public void glClearColorx(int red, int green, int blue, int alpha) {
        checkThread();
        mgl.glClearColorx(red, green, blue, alpha);
        checkError();
    }

    public void glClearDepthf(float depth) {
        checkThread();
        mgl.glClearDepthf(depth);
        checkError();
    }

    public void glClearDepthx(int depth) {
        checkThread();
        mgl.glClearDepthx(depth);
        checkError();
    }

    public void glClearStencil(int s) {
        checkThread();
        mgl.glClearStencil(s);
        checkError();
    }

    public void glClientActiveTexture(int texture) {
        checkThread();
        mgl.glClientActiveTexture(texture);
        checkError();
    }

    public void glColor4f(float red, float green, float blue, float alpha) {
        checkThread();
        mgl.glColor4f(red, green, blue, alpha);
        checkError();
    }

    public void glColor4x(int red, int green, int blue, int alpha) {
        checkThread();
        mgl.glColor4x(red, green, blue, alpha);
        checkError();
    }

    public void glColorMask(boolean red, boolean green, boolean blue,
            boolean alpha) {
        checkThread();
        mgl.glColorMask(red, green, blue, alpha);
        checkError();
    }

    public void glColorPointer(int size, int type, int stride, Buffer pointer) {
        checkThread();
        mgl.glColorPointer(size, type, stride, pointer);
        checkError();
    }

    public void glCompressedTexImage2D(int target, int level,
            int internalformat, int width, int height, int border,
            int imageSize, Buffer data) {
        checkThread();
        mgl.glCompressedTexImage2D(target, level, internalformat, width,
                height, border, imageSize, data);
        checkError();
    }

    public void glCompressedTexSubImage2D(int target, int level, int xoffset,
            int yoffset, int width, int height, int format, int imageSize,
            Buffer data) {
        checkThread();
        mgl.glCompressedTexSubImage2D(target, level, xoffset, yoffset, width,
                height, format, imageSize, data);
        checkError();
    }

    public void glCopyTexImage2D(int target, int level, int internalformat,
            int x, int y, int width, int height, int border) {
        checkThread();
        mgl.glCopyTexImage2D(target, level, internalformat, x, y, width,
                height, border);
        checkError();
    }

    public void glCopyTexSubImage2D(int target, int level, int xoffset,
            int yoffset, int x, int y, int width, int height) {
        checkThread();
        mgl.glCopyTexSubImage2D(target, level, xoffset, yoffset, x, y, width,
                height);
        checkError();
    }

    public void glCullFace(int mode) {
        checkThread();
        mgl.glCullFace(mode);
        checkError();
    }

    public void glDeleteTextures(int n, int[] textures, int offset) {
        checkThread();
        mgl.glDeleteTextures(n, textures, offset);
        checkError();
    }

    public void glDeleteTextures(int n, IntBuffer textures) {
        checkThread();
        mgl.glDeleteTextures(n, textures);
        checkError();
    }

    public void glDepthFunc(int func) {
        checkThread();
        mgl.glDepthFunc(func);
        checkError();
    }

    public void glDepthMask(boolean flag) {
        checkThread();
        mgl.glDepthMask(flag);
        checkError();
    }

    public void glDepthRangef(float near, float far) {
        checkThread();
        mgl.glDepthRangef(near, far);
        checkError();
    }

    public void glDepthRangex(int near, int far) {
        checkThread();
        mgl.glDepthRangex(near, far);
        checkError();
    }

    public void glDisable(int cap) {
        checkThread();
        mgl.glDisable(cap);
        checkError();
    }

    public void glDisableClientState(int array) {
        checkThread();
        mgl.glDisableClientState(array);
        checkError();
    }

    public void glDrawArrays(int mode, int first, int count) {
        checkThread();
        mgl.glDrawArrays(mode, first, count);
        checkError();
    }

    public void glDrawElements(int mode, int count, int type, Buffer indices) {
        checkThread();
        mgl.glDrawElements(mode, count, type, indices);
        checkError();
    }

    public void glEnable(int cap) {
        checkThread();
        mgl.glEnable(cap);
        checkError();
    }

    public void glEnableClientState(int array) {
        checkThread();
        mgl.glEnableClientState(array);
        checkError();
    }

    public void glFinish() {
        checkThread();
        mgl.glFinish();
        checkError();
    }

    public void glFlush() {
        checkThread();
        mgl.glFlush();
        checkError();
    }

    public void glFogf(int pname, float param) {
        checkThread();
        mgl.glFogf(pname, param);
        checkError();
    }

    public void glFogfv(int pname, float[] params, int offset) {
        checkThread();
        mgl.glFogfv(pname, params, offset);
        checkError();
    }

    public void glFogfv(int pname, FloatBuffer params) {
        checkThread();
        mgl.glFogfv(pname, params);
        checkError();
    }

    public void glFogx(int pname, int param) {
        checkThread();
        mgl.glFogx(pname, param);
        checkError();
    }

    public void glFogxv(int pname, int[] params, int offset) {
        checkThread();
        mgl.glFogxv(pname, params, offset);
        checkError();
    }

    public void glFogxv(int pname, IntBuffer params) {
        checkThread();
        mgl.glFogxv(pname, params);
        checkError();
    }

    public void glFrontFace(int mode) {
        checkThread();
        mgl.glFrontFace(mode);
        checkError();
    }

    public void glFrustumf(float left, float right, float bottom, float top,
            float near, float far) {
        checkThread();
        mgl.glFrustumf(left, right, bottom, top, near, far);
        checkError();
    }

    public void glFrustumx(int left, int right, int bottom, int top, int near,
            int far) {
        checkThread();
        mgl.glFrustumx(left, right, bottom, top, near, far);
        checkError();
    }

    public void glGenTextures(int n, int[] textures, int offset) {
        checkThread();
        mgl.glGenTextures(n, textures, offset);
        checkError();
    }

    public void glGenTextures(int n, IntBuffer textures) {
        checkThread();
        mgl.glGenTextures(n, textures);
        checkError();
    }

    public int glGetError() {
        checkThread();
        int result = mgl.glGetError();
        return result;
    }

    public void glGetIntegerv(int pname, int[] params, int offset) {
        checkThread();
        mgl.glGetIntegerv(pname, params, offset);
        checkError();
    }

    public void glGetIntegerv(int pname, IntBuffer params) {
        checkThread();
        mgl.glGetIntegerv(pname, params);
        checkError();
    }

    public String glGetString(int name) {
        checkThread();
        String result = mgl.glGetString(name);
        checkError();
        return result;
    }

    public void glHint(int target, int mode) {
        checkThread();
        mgl.glHint(target, mode);
        checkError();
    }

    public void glLightModelf(int pname, float param) {
        checkThread();
        mgl.glLightModelf(pname, param);
        checkError();
    }

    public void glLightModelfv(int pname, float[] params, int offset) {
        checkThread();
        mgl.glLightModelfv(pname, params, offset);
        checkError();
    }

    public void glLightModelfv(int pname, FloatBuffer params) {
        checkThread();
        mgl.glLightModelfv(pname, params);
        checkError();
    }

    public void glLightModelx(int pname, int param) {
        checkThread();
        mgl.glLightModelx(pname, param);
        checkError();
    }

    public void glLightModelxv(int pname, int[] params, int offset) {
        checkThread();
        mgl.glLightModelxv(pname, params, offset);
        checkError();
    }

    public void glLightModelxv(int pname, IntBuffer params) {
        checkThread();
        mgl.glLightModelxv(pname, params);
        checkError();
    }

    public void glLightf(int light, int pname, float param) {
        checkThread();
        mgl.glLightf(light, pname, param);
        checkError();
    }

    public void glLightfv(int light, int pname, float[] params, int offset) {
        checkThread();
        mgl.glLightfv(light, pname, params, offset);
        checkError();
    }

    public void glLightfv(int light, int pname, FloatBuffer params) {
        checkThread();
        mgl.glLightfv(light, pname, params);
        checkError();
    }

    public void glLightx(int light, int pname, int param) {
        checkThread();
        mgl.glLightx(light, pname, param);
        checkError();
    }

    public void glLightxv(int light, int pname, int[] params, int offset) {
        checkThread();
        mgl.glLightxv(light, pname, params, offset);
        checkError();
    }

    public void glLightxv(int light, int pname, IntBuffer params) {
        checkThread();
        mgl.glLightxv(light, pname, params);
        checkError();
    }

    public void glLineWidth(float width) {
        checkThread();
        mgl.glLineWidth(width);
        checkError();
    }

    public void glLineWidthx(int width) {
        checkThread();
        mgl.glLineWidthx(width);
        checkError();
    }

    public void glLoadIdentity() {
        checkThread();
        mgl.glLoadIdentity();
        checkError();
    }

    public void glLoadMatrixf(float[] m, int offset) {
        checkThread();
        mgl.glLoadMatrixf(m, offset);
        checkError();
    }

    public void glLoadMatrixf(FloatBuffer m) {
        checkThread();
        mgl.glLoadMatrixf(m);
        checkError();
    }

    public void glLoadMatrixx(int[] m, int offset) {
        checkThread();
        mgl.glLoadMatrixx(m, offset);
        checkError();
    }

    public void glLoadMatrixx(IntBuffer m) {
        checkThread();
        mgl.glLoadMatrixx(m);
        checkError();
    }

    public void glLogicOp(int opcode) {
        checkThread();
        mgl.glLogicOp(opcode);
        checkError();
    }

    public void glMaterialf(int face, int pname, float param) {
        checkThread();
        mgl.glMaterialf(face, pname, param);
        checkError();
    }

    public void glMaterialfv(int face, int pname, float[] params, int offset) {
        checkThread();
        mgl.glMaterialfv(face, pname, params, offset);
        checkError();
    }

    public void glMaterialfv(int face, int pname, FloatBuffer params) {
        checkThread();
        mgl.glMaterialfv(face, pname, params);
        checkError();
    }

    public void glMaterialx(int face, int pname, int param) {
        checkThread();
        mgl.glMaterialx(face, pname, param);
        checkError();
    }

    public void glMaterialxv(int face, int pname, int[] params, int offset) {
        checkThread();
        mgl.glMaterialxv(face, pname, params, offset);
        checkError();
    }

    public void glMaterialxv(int face, int pname, IntBuffer params) {
        checkThread();
        mgl.glMaterialxv(face, pname, params);
        checkError();
    }

    public void glMatrixMode(int mode) {
        checkThread();
        mgl.glMatrixMode(mode);
        checkError();
    }

    public void glMultMatrixf(float[] m, int offset) {
        checkThread();
        mgl.glMultMatrixf(m, offset);
        checkError();
    }

    public void glMultMatrixf(FloatBuffer m) {
        checkThread();
        mgl.glMultMatrixf(m);
        checkError();
    }

    public void glMultMatrixx(int[] m, int offset) {
        checkThread();
        mgl.glMultMatrixx(m, offset);
        checkError();
    }

    public void glMultMatrixx(IntBuffer m) {
        checkThread();
        mgl.glMultMatrixx(m);
        checkError();
    }

    public void glMultiTexCoord4f(int target,
            float s, float t, float r, float q) {
        checkThread();
        mgl.glMultiTexCoord4f(target, s, t, r, q);
        checkError();
    }

    public void glMultiTexCoord4x(int target, int s, int t, int r, int q) {
        checkThread();
        mgl.glMultiTexCoord4x(target, s, t, r, q);
        checkError();
    }

    public void glNormal3f(float nx, float ny, float nz) {
        checkThread();
        mgl.glNormal3f(nx, ny, nz);
        checkError();
    }

    public void glNormal3x(int nx, int ny, int nz) {
        checkThread();
        mgl.glNormal3x(nx, ny, nz);
        checkError();
    }

    public void glNormalPointer(int type, int stride, Buffer pointer) {
        checkThread();
        mgl.glNormalPointer(type, stride, pointer);
        checkError();
    }

    public void glOrthof(float left, float right, float bottom, float top,
            float near, float far) {
        checkThread();
        mgl.glOrthof(left, right, bottom, top, near, far);
        checkError();
    }

    public void glOrthox(int left, int right, int bottom, int top, int near,
            int far) {
        checkThread();
        mgl.glOrthox(left, right, bottom, top, near, far);
        checkError();
    }

    public void glPixelStorei(int pname, int param) {
        checkThread();
        mgl.glPixelStorei(pname, param);
        checkError();
    }

    public void glPointSize(float size) {
        checkThread();
        mgl.glPointSize(size);
        checkError();
    }

    public void glPointSizex(int size) {
        checkThread();
        mgl.glPointSizex(size);
        checkError();
    }

    public void glPolygonOffset(float factor, float units) {
        checkThread();
        mgl.glPolygonOffset(factor, units);
        checkError();
    }

    public void glPolygonOffsetx(int factor, int units) {
        checkThread();
        mgl.glPolygonOffsetx(factor, units);
        checkError();
    }

    public void glPopMatrix() {
        checkThread();
        mgl.glPopMatrix();
        checkError();
    }

    public void glPushMatrix() {
        checkThread();
        mgl.glPushMatrix();
        checkError();
    }

    public void glReadPixels(int x, int y, int width, int height, int format,
            int type, Buffer pixels) {
        checkThread();
        mgl.glReadPixels(x, y, width, height, format, type, pixels);
        checkError();
    }

    public void glRotatef(float angle, float x, float y, float z) {
        checkThread();
        mgl.glRotatef(angle, x, y, z);
        checkError();
    }

    public void glRotatex(int angle, int x, int y, int z) {
        checkThread();
        mgl.glRotatex(angle, x, y, z);
        checkError();
    }

    public void glSampleCoverage(float value, boolean invert) {
        checkThread();
        mgl.glSampleCoverage(value, invert);
        checkError();
    }

    public void glSampleCoveragex(int value, boolean invert) {
        checkThread();
        mgl.glSampleCoveragex(value, invert);
        checkError();
    }

    public void glScalef(float x, float y, float z) {
        checkThread();
        mgl.glScalef(x, y, z);
        checkError();
    }

    public void glScalex(int x, int y, int z) {
        checkThread();
        mgl.glScalex(x, y, z);
        checkError();
    }

    public void glScissor(int x, int y, int width, int height) {
        checkThread();
        mgl.glScissor(x, y, width, height);
        checkError();
    }

    public void glShadeModel(int mode) {
        checkThread();
        mgl.glShadeModel(mode);
        checkError();
    }

    public void glStencilFunc(int func, int ref, int mask) {
        checkThread();
        mgl.glStencilFunc(func, ref, mask);
        checkError();
    }

    public void glStencilMask(int mask) {
        checkThread();
        mgl.glStencilMask(mask);
        checkError();
    }

    public void glStencilOp(int fail, int zfail, int zpass) {
        checkThread();
        mgl.glStencilOp(fail, zfail, zpass);
        checkError();
    }

    public void glTexCoordPointer(int size, int type,
            int stride, Buffer pointer) {
        checkThread();
        mgl.glTexCoordPointer(size, type, stride, pointer);
        checkError();
    }

    public void glTexEnvf(int target, int pname, float param) {
        checkThread();
        mgl.glTexEnvf(target, pname, param);
        checkError();
    }

    public void glTexEnvfv(int target, int pname, float[] params, int offset) {
        checkThread();
        mgl.glTexEnvfv(target, pname, params, offset);
        checkError();
    }

    public void glTexEnvfv(int target, int pname, FloatBuffer params) {
        checkThread();
        mgl.glTexEnvfv(target, pname, params);
        checkError();
    }

    public void glTexEnvx(int target, int pname, int param) {
        checkThread();
        mgl.glTexEnvx(target, pname, param);
        checkError();
    }

    public void glTexEnvxv(int target, int pname, int[] params, int offset) {
        checkThread();
        mgl.glTexEnvxv(target, pname, params, offset);
        checkError();
    }

    public void glTexEnvxv(int target, int pname, IntBuffer params) {
        checkThread();
        mgl.glTexEnvxv(target, pname, params);
        checkError();
    }

    public void glTexImage2D(int target, int level, int internalformat,
            int width, int height, int border, int format, int type,
            Buffer pixels) {
        checkThread();
        mgl.glTexImage2D(target, level, internalformat, width, height, border,
                format, type, pixels);
        checkError();
    }

    public void glTexParameterf(int target, int pname, float param) {
        checkThread();
        mgl.glTexParameterf(target, pname, param);
        checkError();
    }

    public void glTexParameterx(int target, int pname, int param) {
        checkThread();
        mgl.glTexParameterx(target, pname, param);
        checkError();
    }

    public void glTexParameteriv(int target, int pname, int[] params, int offset) {
        checkThread();
        mgl11.glTexParameteriv(target, pname, params, offset);
        checkError();
    }

    public void glTexParameteriv(int target, int pname, IntBuffer params) {
        checkThread();
        mgl11.glTexParameteriv(target, pname, params);
        checkError();
    }

    public void glTexSubImage2D(int target, int level, int xoffset,
            int yoffset, int width, int height, int format, int type,
            Buffer pixels) {
        checkThread();
        mgl.glTexSubImage2D(target, level, xoffset, yoffset, width, height,
                format, type, pixels);
        checkError();
    }

    public void glTranslatef(float x, float y, float z) {
        checkThread();
        mgl.glTranslatef(x, y, z);
        checkError();
    }

    public void glTranslatex(int x, int y, int z) {
        checkThread();
        mgl.glTranslatex(x, y, z);
        checkError();
    }

    public void glVertexPointer(int size, int type,
            int stride, Buffer pointer) {
        checkThread();
        mgl.glVertexPointer(size, type, stride, pointer);
        checkError();
    }

    public void glViewport(int x, int y, int width, int height) {
        checkThread();
        mgl.glViewport(x, y, width, height);
        checkError();
    }

    public void glClipPlanef(int plane, float[] equation, int offset) {
        checkThread();
        mgl11.glClipPlanef(plane, equation, offset);
        checkError();
    }

    public void glClipPlanef(int plane, FloatBuffer equation) {
        checkThread();
        mgl11.glClipPlanef(plane, equation);
        checkError();
    }

    public void glClipPlanex(int plane, int[] equation, int offset) {
        checkThread();
        mgl11.glClipPlanex(plane, equation, offset);
        checkError();
    }

    public void glClipPlanex(int plane, IntBuffer equation) {
        checkThread();
        mgl11.glClipPlanex(plane, equation);
        checkError();
    }

    // Draw Texture Extension

    public void glDrawTexfOES(float x, float y, float z,
        float width, float height) {
        checkThread();
        mgl11Ext.glDrawTexfOES(x, y, z, width, height);
        checkError();
    }

    public void glDrawTexfvOES(float[] coords, int offset) {
        checkThread();
        mgl11Ext.glDrawTexfvOES(coords, offset);
        checkError();
    }

    public void glDrawTexfvOES(FloatBuffer coords) {
        checkThread();
        mgl11Ext.glDrawTexfvOES(coords);
        checkError();
    }

    public void glDrawTexiOES(int x, int y, int z, int width, int height) {
        checkThread();
        mgl11Ext.glDrawTexiOES(x, y, z, width, height);
        checkError();
    }

    public void glDrawTexivOES(int[] coords, int offset) {
        checkThread();
        mgl11Ext.glDrawTexivOES(coords, offset);
        checkError();
    }

    public void glDrawTexivOES(IntBuffer coords) {
        checkThread();
        mgl11Ext.glDrawTexivOES(coords);
        checkError();
    }

    public void glDrawTexsOES(short x, short y, short z,
        short width, short height) {
        checkThread();
        mgl11Ext.glDrawTexsOES(x, y, z, width, height);
        checkError();
    }

    public void glDrawTexsvOES(short[] coords, int offset) {
        checkThread();
        mgl11Ext.glDrawTexsvOES(coords, offset);
        checkError();
    }

    public void glDrawTexsvOES(ShortBuffer coords) {
        checkThread();
        mgl11Ext.glDrawTexsvOES(coords);
        checkError();
    }

    public void glDrawTexxOES(int x, int y, int z, int width, int height) {
        checkThread();
        mgl11Ext.glDrawTexxOES(x, y, z, width, height);
        checkError();
    }

    public void glDrawTexxvOES(int[] coords, int offset) {
        checkThread();
        mgl11Ext.glDrawTexxvOES(coords, offset);
        checkError();
    }

    public void glDrawTexxvOES(IntBuffer coords) {
        checkThread();
        mgl11Ext.glDrawTexxvOES(coords);
        checkError();
    }

    public int glQueryMatrixxOES(int[] mantissa, int mantissaOffset,
        int[] exponent, int exponentOffset) {
        checkThread();
        int valid = mgl10Ext.glQueryMatrixxOES(mantissa, mantissaOffset,
            exponent, exponentOffset);
        checkError();
        return valid;
    }

    public int glQueryMatrixxOES(IntBuffer mantissa, IntBuffer exponent) {
        checkThread();
        int valid = mgl10Ext.glQueryMatrixxOES(mantissa, exponent);
        checkError();
        return valid;
    }

    public void glBindBuffer(int target, int buffer) {
        checkThread();
        mgl11.glBindBuffer(target, buffer);
        checkError();
    }

    public void glBufferData(int target, int size, Buffer data, int usage) {
        checkThread();
        mgl11.glBufferData(target, size, data, usage);
        checkError();
    }

    public void glBufferSubData(int target, int offset, int size, Buffer data) {
        checkThread();
        mgl11.glBufferSubData(target, offset, size, data);
        checkError();
    }

    public void glColor4ub(byte red, byte green, byte blue, byte alpha) {
        checkThread();
        mgl11.glColor4ub(red, green, blue, alpha);
        checkError();    }

    public void glColorPointer(int size, int type, int stride, int offset) {
        checkThread();
        mgl11.glColorPointer(size, type, stride, offset);
        checkError();
    }

    public void glDeleteBuffers(int n, int[] buffers, int offset) {
        checkThread();
        mgl11.glDeleteBuffers(n, buffers, offset);
        checkError();
    }

    public void glDeleteBuffers(int n, IntBuffer buffers) {
        checkThread();
        mgl11.glDeleteBuffers(n, buffers);
        checkError();
    }

    public void glDrawElements(int mode, int count, int type, int offset) {
        checkThread();
        mgl11.glDrawElements(mode, count, type, offset);
        checkError();
    }

    public void glGenBuffers(int n, int[] buffers, int offset) {
        checkThread();
        mgl11.glGenBuffers(n, buffers, offset);
        checkError();
    }

    public void glGenBuffers(int n, IntBuffer buffers) {
        checkThread();
        mgl11.glGenBuffers(n, buffers);
        checkError();
    }

    public void glGetBooleanv(int pname, boolean[] params, int offset) {
        checkThread();
        mgl11.glGetBooleanv(pname, params, offset);
        checkError();
    }

    public void glGetBooleanv(int pname, IntBuffer params) {
        checkThread();
        mgl11.glGetBooleanv(pname, params);
        checkError();
    }

    public void glGetBufferParameteriv(int target, int pname, int[] params,
            int offset) {
        checkThread();
        mgl11.glGetBufferParameteriv(target, pname, params, offset);
        checkError();
    }

    public void glGetBufferParameteriv(int target, int pname, IntBuffer params) {
        checkThread();
        mgl11.glGetBufferParameteriv(target, pname, params);
        checkError();
    }

    public void glGetClipPlanef(int pname, float[] eqn, int offset) {
        checkThread();
        mgl11.glGetClipPlanef(pname, eqn, offset);
        checkError();
    }

    public void glGetClipPlanef(int pname, FloatBuffer eqn) {
        checkThread();
        mgl11.glGetClipPlanef(pname, eqn);
        checkError();
    }

    public void glGetClipPlanex(int pname, int[] eqn, int offset) {
        checkThread();
        mgl11.glGetClipPlanex(pname, eqn, offset);
        checkError();
    }

    public void glGetClipPlanex(int pname, IntBuffer eqn) {
        checkThread();
        mgl11.glGetClipPlanex(pname, eqn);
        checkError();
    }

    public void glGetFixedv(int pname, int[] params, int offset) {
        checkThread();
        mgl11.glGetFixedv(pname, params, offset);
        checkError();
    }

    public void glGetFixedv(int pname, IntBuffer params) {
        checkThread();
        mgl11.glGetFixedv(pname, params);
        checkError();
    }

    public void glGetFloatv(int pname, float[] params, int offset) {
        checkThread();
        mgl11.glGetFloatv(pname, params, offset);
        checkError();
    }

    public void glGetFloatv(int pname, FloatBuffer params) {
        checkThread();
        mgl11.glGetFloatv(pname, params);
        checkError();
    }

    public void glGetLightfv(int light, int pname, float[] params, int offset) {
        checkThread();
        mgl11.glGetLightfv(light, pname, params, offset);
        checkError();
    }

    public void glGetLightfv(int light, int pname, FloatBuffer params) {
        checkThread();
        mgl11.glGetLightfv(light, pname, params);
        checkError();
    }

    public void glGetLightxv(int light, int pname, int[] params, int offset) {
        checkThread();
        mgl11.glGetLightxv(light, pname, params, offset);
        checkError();
    }

    public void glGetLightxv(int light, int pname, IntBuffer params) {
        checkThread();
        mgl11.glGetLightxv(light, pname, params);
        checkError();
    }

    public void glGetMaterialfv(int face, int pname, float[] params, int offset) {
        checkThread();
        mgl11.glGetMaterialfv(face, pname, params, offset);
        checkError();
    }

    public void glGetMaterialfv(int face, int pname, FloatBuffer params) {
        checkThread();
        mgl11.glGetMaterialfv(face, pname, params);
        checkError();
    }

    public void glGetMaterialxv(int face, int pname, int[] params, int offset) {
        checkThread();
        mgl11.glGetMaterialxv(face, pname, params, offset);
        checkError();
    }

    public void glGetMaterialxv(int face, int pname, IntBuffer params) {
        checkThread();
        mgl11.glGetMaterialxv(face, pname, params);
        checkError();
    }

    public void glGetPointerv(int pname, Buffer[] params) {
        checkThread();
        mgl11.glGetPointerv(pname, params);
        checkError();
    }

    public void glGetTexEnviv(int env, int pname, int[] params, int offset) {
        checkThread();
        mgl11.glGetTexEnviv(env, pname, params, offset);
        checkError();
    }

    public void glGetTexEnviv(int env, int pname, IntBuffer params) {
        checkThread();
        mgl11.glGetTexEnviv(env, pname, params);
        checkError();
    }

    public void glGetTexEnvxv(int env, int pname, int[] params, int offset) {
        checkThread();
        mgl11.glGetTexEnvxv(env, pname, params, offset);
        checkError();
    }

    public void glGetTexEnvxv(int env, int pname, IntBuffer params) {
        checkThread();
        mgl11.glGetTexEnvxv(env, pname, params);
        checkError();
    }

    public void glGetTexParameterfv(int target, int pname, float[] params,
            int offset) {
        checkThread();
        mgl11.glGetTexParameterfv(target, pname, params, offset);
        checkError();
    }

    public void glGetTexParameterfv(int target, int pname, FloatBuffer params) {
        checkThread();
        mgl11.glGetTexParameterfv(target, pname, params);
        checkError();
    }

    public void glGetTexParameteriv(int target, int pname, int[] params,
            int offset) {
        checkThread();
        mgl11.glGetTexParameteriv(target, pname, params, offset);
        checkError();
    }

    public void glGetTexParameteriv(int target, int pname, IntBuffer params) {
        checkThread();
        mgl11.glGetTexParameteriv(target, pname, params);
        checkError();
    }

    public void glGetTexParameterxv(int target, int pname, int[] params,
            int offset) {
        checkThread();
        mgl11.glGetTexParameterxv(target, pname, params, offset);
        checkError();
    }

    public void glGetTexParameterxv(int target, int pname, IntBuffer params) {
        checkThread();
        mgl11.glGetTexParameterxv(target, pname, params);
        checkError();
    }

    public boolean glIsBuffer(int buffer) {
        checkThread();
        boolean valid = mgl11.glIsBuffer(buffer);
        checkError();
        return valid;
    }

    public boolean glIsEnabled(int cap) {
        checkThread();
        boolean valid = mgl11.glIsEnabled(cap);
        checkError();
        return valid;
    }

    public boolean glIsTexture(int texture) {
        checkThread();
        boolean valid = mgl11.glIsTexture(texture);
        checkError();
        return valid;
    }

    public void glNormalPointer(int type, int stride, int offset) {
        checkThread();
        mgl11.glNormalPointer(type, stride, offset);
        checkError();
    }

    public void glPointParameterf(int pname, float param) {
        checkThread();
        mgl11.glPointParameterf(pname, param);
        checkError();
    }

    public void glPointParameterfv(int pname, float[] params, int offset) {
        checkThread();
        mgl11.glPointParameterfv(pname, params, offset);
        checkError();
    }

    public void glPointParameterfv(int pname, FloatBuffer params) {
        checkThread();
        mgl11.glPointParameterfv(pname, params);
        checkError();
    }

    public void glPointParameterx(int pname, int param) {
        checkThread();
        mgl11.glPointParameterx(pname, param);
        checkError();
    }

    public void glPointParameterxv(int pname, int[] params, int offset) {
        checkThread();
        mgl11.glPointParameterxv(pname, params, offset);
        checkError();
    }

    public void glPointParameterxv(int pname, IntBuffer params) {
        checkThread();
        mgl11.glPointParameterxv(pname, params);
        checkError();
    }

    public void glPointSizePointerOES(int type, int stride, Buffer pointer) {
        checkThread();
        mgl11.glPointSizePointerOES(type, stride, pointer);
        checkError();
    }

    public void glTexCoordPointer(int size, int type, int stride, int offset) {
        checkThread();
        mgl11.glTexCoordPointer(size, type, stride, offset);
        checkError();
    }

    public void glTexEnvi(int target, int pname, int param) {
        checkThread();
        mgl11.glTexEnvi(target, pname, param);
        checkError();
    }

    public void glTexEnviv(int target, int pname, int[] params, int offset) {
        checkThread();
        mgl11.glTexEnviv(target, pname, params, offset);
        checkError();
    }

    public void glTexEnviv(int target, int pname, IntBuffer params) {
        checkThread();
        mgl11.glTexEnviv(target, pname, params);
        checkError();
    }

    public void glTexParameterfv(int target, int pname, float[] params,
            int offset) {
        checkThread();
        mgl11.glTexParameterfv(target, pname, params, offset);
        checkError();
    }

    public void glTexParameterfv(int target, int pname, FloatBuffer params) {
        checkThread();
        mgl11.glTexParameterfv(target, pname, params);
        checkError();
    }

    public void glTexParameteri(int target, int pname, int param) {
        checkThread();
        mgl11.glTexParameteri(target, pname, param);
        checkError();
    }

    public void glTexParameterxv(int target, int pname, int[] params, int offset) {
        checkThread();
        mgl11.glTexParameterxv(target, pname, params, offset);
        checkError();
    }

    public void glTexParameterxv(int target, int pname, IntBuffer params) {
        checkThread();
        mgl11.glTexParameterxv(target, pname, params);
        checkError();
    }

    public void glVertexPointer(int size, int type, int stride, int offset) {
        checkThread();
        mgl11.glVertexPointer(size, type, stride, offset);
        checkError();
    }

    public void glCurrentPaletteMatrixOES(int matrixpaletteindex) {
        checkThread();
        mgl11Ext.glCurrentPaletteMatrixOES(matrixpaletteindex);
        checkError();
    }

    public void glLoadPaletteFromModelViewMatrixOES() {
        checkThread();
        mgl11Ext.glLoadPaletteFromModelViewMatrixOES();
        checkError();
    }

    public void glMatrixIndexPointerOES(int size, int type, int stride,
            Buffer pointer) {
        checkThread();
        mgl11Ext.glMatrixIndexPointerOES(size, type, stride, pointer);
        checkError();
    }

    public void glMatrixIndexPointerOES(int size, int type, int stride,
            int offset) {
        checkThread();
        mgl11Ext.glMatrixIndexPointerOES(size, type, stride, offset);
        checkError();
    }

    public void glWeightPointerOES(int size, int type, int stride,
            Buffer pointer) {
        checkThread();
        mgl11Ext.glWeightPointerOES(size, type, stride, pointer);
        checkError();
    }

    public void glWeightPointerOES(int size, int type, int stride, int offset) {
        checkThread();
        mgl11Ext.glWeightPointerOES(size, type, stride, offset);
        checkError();
    }

    @Override
    public void glBindFramebufferOES(int target, int framebuffer) {
        checkThread();
        mgl11ExtensionPack.glBindFramebufferOES(target, framebuffer);
        checkError();
    }

    @Override
    public void glBindRenderbufferOES(int target, int renderbuffer) {
        checkThread();
        mgl11ExtensionPack.glBindRenderbufferOES(target, renderbuffer);
        checkError();
    }

    @Override
    public void glBlendEquation(int mode) {
        checkThread();
        mgl11ExtensionPack.glBlendEquation(mode);
        checkError();
    }

    @Override
    public void glBlendEquationSeparate(int modeRGB, int modeAlpha) {
        checkThread();
        mgl11ExtensionPack.glBlendEquationSeparate(modeRGB, modeAlpha);
        checkError();
    }

    @Override
    public void glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha,
            int dstAlpha) {
        checkThread();
        mgl11ExtensionPack.glBlendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
        checkError();
    }

    @Override
    public int glCheckFramebufferStatusOES(int target) {
        checkThread();
        int result = mgl11ExtensionPack.glCheckFramebufferStatusOES(target);
        checkError();
        return result;
    }

    @Override
    public void glDeleteFramebuffersOES(int n, int[] framebuffers, int offset) {
        checkThread();
        mgl11ExtensionPack.glDeleteFramebuffersOES(n, framebuffers, offset);
        checkError();
    }

    @Override
    public void glDeleteFramebuffersOES(int n, IntBuffer framebuffers) {
        checkThread();
        mgl11ExtensionPack.glDeleteFramebuffersOES(n, framebuffers);
        checkError();
    }

    @Override
    public void glDeleteRenderbuffersOES(int n, int[] renderbuffers, int offset) {
        checkThread();
        mgl11ExtensionPack.glDeleteRenderbuffersOES(n, renderbuffers, offset);
        checkError();
    }

    @Override
    public void glDeleteRenderbuffersOES(int n, IntBuffer renderbuffers) {
        checkThread();
        mgl11ExtensionPack.glDeleteRenderbuffersOES(n, renderbuffers);
        checkError();
    }

    @Override
    public void glFramebufferRenderbufferOES(int target, int attachment,
            int renderbuffertarget, int renderbuffer) {
        checkThread();
        mgl11ExtensionPack.glFramebufferRenderbufferOES(target, attachment, renderbuffertarget, renderbuffer);
        checkError();
    }

    @Override
    public void glFramebufferTexture2DOES(int target, int attachment,
            int textarget, int texture, int level) {
        checkThread();
        mgl11ExtensionPack.glFramebufferTexture2DOES(target, attachment, textarget, texture, level);
        checkError();
    }

    @Override
    public void glGenerateMipmapOES(int target) {
        checkThread();
        mgl11ExtensionPack.glGenerateMipmapOES(target);
        checkError();
    }

    @Override
    public void glGenFramebuffersOES(int n, int[] framebuffers, int offset) {
        checkThread();
        mgl11ExtensionPack.glGenFramebuffersOES(n, framebuffers, offset);
        checkError();
    }

    @Override
    public void glGenFramebuffersOES(int n, IntBuffer framebuffers) {
        checkThread();
        mgl11ExtensionPack.glGenFramebuffersOES(n, framebuffers);
        checkError();
    }

    @Override
    public void glGenRenderbuffersOES(int n, int[] renderbuffers, int offset) {
        checkThread();
        mgl11ExtensionPack.glGenRenderbuffersOES(n, renderbuffers, offset);
        checkError();
    }

    @Override
    public void glGenRenderbuffersOES(int n, IntBuffer renderbuffers) {
        checkThread();
        mgl11ExtensionPack.glGenRenderbuffersOES(n, renderbuffers);
        checkError();
    }

    @Override
    public void glGetFramebufferAttachmentParameterivOES(int target,
            int attachment, int pname, int[] params, int offset) {
        checkThread();
        mgl11ExtensionPack.glGetFramebufferAttachmentParameterivOES(target, attachment, pname, params, offset);
        checkError();
    }

    @Override
    public void glGetFramebufferAttachmentParameterivOES(int target,
            int attachment, int pname, IntBuffer params) {
        checkThread();
        mgl11ExtensionPack.glGetFramebufferAttachmentParameterivOES(target, attachment, pname, params);
        checkError();
    }

    @Override
    public void glGetRenderbufferParameterivOES(int target, int pname,
            int[] params, int offset) {
        checkThread();
        mgl11ExtensionPack.glGetRenderbufferParameterivOES(target, pname, params, offset);
        checkError();
    }

    @Override
    public void glGetRenderbufferParameterivOES(int target, int pname,
            IntBuffer params) {
        checkThread();
        mgl11ExtensionPack.glGetRenderbufferParameterivOES(target, pname, params);
        checkError();
    }

    @Override
    public void glGetTexGenfv(int coord, int pname, float[] params, int offset) {
        checkThread();
        mgl11ExtensionPack.glGetTexGenfv(coord, pname, params, offset);
        checkError();
    }

    @Override
    public void glGetTexGenfv(int coord, int pname, FloatBuffer params) {
        checkThread();
        mgl11ExtensionPack.glGetTexGenfv(coord, pname, params);
        checkError();
    }

    @Override
    public void glGetTexGeniv(int coord, int pname, int[] params, int offset) {
        checkThread();
        mgl11ExtensionPack.glGetTexGeniv(coord, pname, params, offset);
        checkError();
    }

    @Override
    public void glGetTexGeniv(int coord, int pname, IntBuffer params) {
        checkThread();
        mgl11ExtensionPack.glGetTexGeniv(coord, pname, params);
        checkError();
    }

    @Override
    public void glGetTexGenxv(int coord, int pname, int[] params, int offset) {
        checkThread();
        mgl11ExtensionPack.glGetTexGenxv(coord, pname, params, offset);
        checkError();
    }

    @Override
    public void glGetTexGenxv(int coord, int pname, IntBuffer params) {
        checkThread();
        mgl11ExtensionPack.glGetTexGenxv(coord, pname, params);
        checkError();
    }

    @Override
    public boolean glIsFramebufferOES(int framebuffer) {
        checkThread();
        boolean result = mgl11ExtensionPack.glIsFramebufferOES(framebuffer);
        checkError();
        return result;
    }

    @Override
    public boolean glIsRenderbufferOES(int renderbuffer) {
        checkThread();
        mgl11ExtensionPack.glIsRenderbufferOES(renderbuffer);
        checkError();
        return false;
    }

    @Override
    public void glRenderbufferStorageOES(int target, int internalformat,
            int width, int height) {
        checkThread();
        mgl11ExtensionPack.glRenderbufferStorageOES(target, internalformat, width, height);
        checkError();
    }

    @Override
    public void glTexGenf(int coord, int pname, float param) {
        checkThread();
        mgl11ExtensionPack.glTexGenf(coord, pname, param);
        checkError();
    }

    @Override
    public void glTexGenfv(int coord, int pname, float[] params, int offset) {
        checkThread();
        mgl11ExtensionPack.glTexGenfv(coord, pname, params, offset);
        checkError();
    }

    @Override
    public void glTexGenfv(int coord, int pname, FloatBuffer params) {
        checkThread();
        mgl11ExtensionPack.glTexGenfv(coord, pname, params);
        checkError();
    }

    @Override
    public void glTexGeni(int coord, int pname, int param) {
        checkThread();
        mgl11ExtensionPack.glTexGeni(coord, pname, param);
        checkError();
    }

    @Override
    public void glTexGeniv(int coord, int pname, int[] params, int offset) {
        checkThread();
        mgl11ExtensionPack.glTexGeniv(coord, pname, params, offset);
        checkError();
    }

    @Override
    public void glTexGeniv(int coord, int pname, IntBuffer params) {
        checkThread();
        mgl11ExtensionPack.glTexGeniv(coord, pname, params);
        checkError();
    }

    @Override
    public void glTexGenx(int coord, int pname, int param) {
        checkThread();
        mgl11ExtensionPack.glTexGenx(coord, pname, param);
        checkError();
    }

    @Override
    public void glTexGenxv(int coord, int pname, int[] params, int offset) {
        checkThread();
        mgl11ExtensionPack.glTexGenxv(coord, pname, params, offset);
        checkError();
    }

    @Override
    public void glTexGenxv(int coord, int pname, IntBuffer params) {
        checkThread();
        mgl11ExtensionPack.glTexGenxv(coord, pname, params);
        checkError();
    }
}
