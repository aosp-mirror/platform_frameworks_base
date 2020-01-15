/* //device/java/android/com/google/android/gles_jni/GLImpl.java
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

// This source file is automatically generated

package com.google.android.gles_jni;

import android.app.AppGlobals;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.os.Build;
import android.os.UserHandle;
import android.util.Log;

import java.nio.Buffer;

import javax.microedition.khronos.opengles.GL10;
import javax.microedition.khronos.opengles.GL10Ext;
import javax.microedition.khronos.opengles.GL11;
import javax.microedition.khronos.opengles.GL11Ext;
import javax.microedition.khronos.opengles.GL11ExtensionPack;

public class GLImpl implements GL10, GL10Ext, GL11, GL11Ext, GL11ExtensionPack {

    // Private accessors for native code

    native private static void _nativeClassInit();
    static {
        _nativeClassInit();
    }

    Buffer _colorPointer = null;
    Buffer _normalPointer = null;
    Buffer _texCoordPointer = null;
    Buffer _vertexPointer = null;
    Buffer _pointSizePointerOES = null;
    Buffer _matrixIndexPointerOES = null;
    Buffer _weightPointerOES = null;
    
    private boolean haveCheckedExtensions;
    private boolean have_OES_blend_equation_separate;
    private boolean have_OES_blend_subtract;
    private boolean have_OES_framebuffer_object;
    private boolean have_OES_texture_cube_map;

    @UnsupportedAppUsage
    public GLImpl() {
    }

    public void glGetPointerv(int pname, java.nio.Buffer[] params) {
        throw new UnsupportedOperationException("glGetPointerv");
    }

    private static boolean allowIndirectBuffers(String appName) {
        boolean result = false;
        int version = 0;
        IPackageManager pm = AppGlobals.getPackageManager();
        try {
            ApplicationInfo applicationInfo = pm.getApplicationInfo(appName, 0, UserHandle.myUserId());
            if (applicationInfo != null) {
                version = applicationInfo.targetSdkVersion;
            }
        } catch (android.os.RemoteException e) {
            // ignore
        }
        Log.e("OpenGLES", String.format(
            "Application %s (SDK target %d) called a GL11 Pointer method with an indirect Buffer.",
            appName, version));
        if (version <= Build.VERSION_CODES.CUPCAKE) {
            result = true;
        }
        return result;
    }

    // C function void glActiveTexture ( GLenum texture )

    public native void glActiveTexture(
        int texture
    );

    // C function void glAlphaFunc ( GLenum func, GLclampf ref )

    public native void glAlphaFunc(
        int func,
        float ref
    );

    // C function void glAlphaFuncx ( GLenum func, GLclampx ref )

    public native void glAlphaFuncx(
        int func,
        int ref
    );

    // C function void glBindTexture ( GLenum target, GLuint texture )

    public native void glBindTexture(
        int target,
        int texture
    );

    // C function void glBlendFunc ( GLenum sfactor, GLenum dfactor )

    public native void glBlendFunc(
        int sfactor,
        int dfactor
    );

    // C function void glClear ( GLbitfield mask )

    public native void glClear(
        int mask
    );

    // C function void glClearColor ( GLclampf red, GLclampf green, GLclampf blue, GLclampf alpha )

    public native void glClearColor(
        float red,
        float green,
        float blue,
        float alpha
    );

    // C function void glClearColorx ( GLclampx red, GLclampx green, GLclampx blue, GLclampx alpha )

    public native void glClearColorx(
        int red,
        int green,
        int blue,
        int alpha
    );

    // C function void glClearDepthf ( GLclampf depth )

    public native void glClearDepthf(
        float depth
    );

    // C function void glClearDepthx ( GLclampx depth )

    public native void glClearDepthx(
        int depth
    );

    // C function void glClearStencil ( GLint s )

    public native void glClearStencil(
        int s
    );

    // C function void glClientActiveTexture ( GLenum texture )

    public native void glClientActiveTexture(
        int texture
    );

    // C function void glColor4f ( GLfloat red, GLfloat green, GLfloat blue, GLfloat alpha )

    public native void glColor4f(
        float red,
        float green,
        float blue,
        float alpha
    );

    // C function void glColor4x ( GLfixed red, GLfixed green, GLfixed blue, GLfixed alpha )

    public native void glColor4x(
        int red,
        int green,
        int blue,
        int alpha
    );

    // C function void glColorMask ( GLboolean red, GLboolean green, GLboolean blue, GLboolean alpha )

    public native void glColorMask(
        boolean red,
        boolean green,
        boolean blue,
        boolean alpha
    );

    // C function void glColorPointer ( GLint size, GLenum type, GLsizei stride, const GLvoid *pointer )

    private native void glColorPointerBounds(
        int size,
        int type,
        int stride,
        java.nio.Buffer pointer,
        int remaining
    );

    public void glColorPointer(
        int size,
        int type,
        int stride,
        java.nio.Buffer pointer
    ) {
        glColorPointerBounds(
            size,
            type,
            stride,
            pointer,
            pointer.remaining()
        );
        if ((size == 4) &&
            ((type == GL_FLOAT) ||
             (type == GL_UNSIGNED_BYTE) ||
             (type == GL_FIXED)) &&
            (stride >= 0)) {
            _colorPointer = pointer;
        }
    }

    // C function void glCompressedTexImage2D ( GLenum target, GLint level, GLenum internalformat, GLsizei width, GLsizei height, GLint border, GLsizei imageSize, const GLvoid *data )

    public native void glCompressedTexImage2D(
        int target,
        int level,
        int internalformat,
        int width,
        int height,
        int border,
        int imageSize,
        java.nio.Buffer data
    );

    // C function void glCompressedTexSubImage2D ( GLenum target, GLint level, GLint xoffset, GLint yoffset, GLsizei width, GLsizei height, GLenum format, GLsizei imageSize, const GLvoid *data )

    public native void glCompressedTexSubImage2D(
        int target,
        int level,
        int xoffset,
        int yoffset,
        int width,
        int height,
        int format,
        int imageSize,
        java.nio.Buffer data
    );

    // C function void glCopyTexImage2D ( GLenum target, GLint level, GLenum internalformat, GLint x, GLint y, GLsizei width, GLsizei height, GLint border )

    public native void glCopyTexImage2D(
        int target,
        int level,
        int internalformat,
        int x,
        int y,
        int width,
        int height,
        int border
    );

    // C function void glCopyTexSubImage2D ( GLenum target, GLint level, GLint xoffset, GLint yoffset, GLint x, GLint y, GLsizei width, GLsizei height )

    public native void glCopyTexSubImage2D(
        int target,
        int level,
        int xoffset,
        int yoffset,
        int x,
        int y,
        int width,
        int height
    );

    // C function void glCullFace ( GLenum mode )

    public native void glCullFace(
        int mode
    );

    // C function void glDeleteTextures ( GLsizei n, const GLuint *textures )

    public native void glDeleteTextures(
        int n,
        int[] textures,
        int offset
    );

    // C function void glDeleteTextures ( GLsizei n, const GLuint *textures )

    public native void glDeleteTextures(
        int n,
        java.nio.IntBuffer textures
    );

    // C function void glDepthFunc ( GLenum func )

    public native void glDepthFunc(
        int func
    );

    // C function void glDepthMask ( GLboolean flag )

    public native void glDepthMask(
        boolean flag
    );

    // C function void glDepthRangef ( GLclampf zNear, GLclampf zFar )

    public native void glDepthRangef(
        float zNear,
        float zFar
    );

    // C function void glDepthRangex ( GLclampx zNear, GLclampx zFar )

    public native void glDepthRangex(
        int zNear,
        int zFar
    );

    // C function void glDisable ( GLenum cap )

    public native void glDisable(
        int cap
    );

    // C function void glDisableClientState ( GLenum array )

    public native void glDisableClientState(
        int array
    );

    // C function void glDrawArrays ( GLenum mode, GLint first, GLsizei count )

    public native void glDrawArrays(
        int mode,
        int first,
        int count
    );

    // C function void glDrawElements ( GLenum mode, GLsizei count, GLenum type, const GLvoid *indices )

    public native void glDrawElements(
        int mode,
        int count,
        int type,
        java.nio.Buffer indices
    );

    // C function void glEnable ( GLenum cap )

    public native void glEnable(
        int cap
    );

    // C function void glEnableClientState ( GLenum array )

    public native void glEnableClientState(
        int array
    );

    // C function void glFinish ( void )

    public native void glFinish(
    );

    // C function void glFlush ( void )

    public native void glFlush(
    );

    // C function void glFogf ( GLenum pname, GLfloat param )

    public native void glFogf(
        int pname,
        float param
    );

    // C function void glFogfv ( GLenum pname, const GLfloat *params )

    public native void glFogfv(
        int pname,
        float[] params,
        int offset
    );

    // C function void glFogfv ( GLenum pname, const GLfloat *params )

    public native void glFogfv(
        int pname,
        java.nio.FloatBuffer params
    );

    // C function void glFogx ( GLenum pname, GLfixed param )

    public native void glFogx(
        int pname,
        int param
    );

    // C function void glFogxv ( GLenum pname, const GLfixed *params )

    public native void glFogxv(
        int pname,
        int[] params,
        int offset
    );

    // C function void glFogxv ( GLenum pname, const GLfixed *params )

    public native void glFogxv(
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glFrontFace ( GLenum mode )

    public native void glFrontFace(
        int mode
    );

    // C function void glFrustumf ( GLfloat left, GLfloat right, GLfloat bottom, GLfloat top, GLfloat zNear, GLfloat zFar )

    public native void glFrustumf(
        float left,
        float right,
        float bottom,
        float top,
        float zNear,
        float zFar
    );

    // C function void glFrustumx ( GLfixed left, GLfixed right, GLfixed bottom, GLfixed top, GLfixed zNear, GLfixed zFar )

    public native void glFrustumx(
        int left,
        int right,
        int bottom,
        int top,
        int zNear,
        int zFar
    );

    // C function void glGenTextures ( GLsizei n, GLuint *textures )

    public native void glGenTextures(
        int n,
        int[] textures,
        int offset
    );

    // C function void glGenTextures ( GLsizei n, GLuint *textures )

    public native void glGenTextures(
        int n,
        java.nio.IntBuffer textures
    );

    // C function GLenum glGetError ( void )

    public native int glGetError(
    );

    // C function void glGetIntegerv ( GLenum pname, GLint *params )

    public native void glGetIntegerv(
        int pname,
        int[] params,
        int offset
    );

    // C function void glGetIntegerv ( GLenum pname, GLint *params )

    public native void glGetIntegerv(
        int pname,
        java.nio.IntBuffer params
    );

    // C function const GLubyte * glGetString ( GLenum name )

    public native String _glGetString(
        int name
    );

    public String glGetString(
        int name
    ) {
        String returnValue;
        returnValue = _glGetString(
            name
        );
        return returnValue;
    }

    // C function void glHint ( GLenum target, GLenum mode )

    public native void glHint(
        int target,
        int mode
    );

    // C function void glLightModelf ( GLenum pname, GLfloat param )

    public native void glLightModelf(
        int pname,
        float param
    );

    // C function void glLightModelfv ( GLenum pname, const GLfloat *params )

    public native void glLightModelfv(
        int pname,
        float[] params,
        int offset
    );

    // C function void glLightModelfv ( GLenum pname, const GLfloat *params )

    public native void glLightModelfv(
        int pname,
        java.nio.FloatBuffer params
    );

    // C function void glLightModelx ( GLenum pname, GLfixed param )

    public native void glLightModelx(
        int pname,
        int param
    );

    // C function void glLightModelxv ( GLenum pname, const GLfixed *params )

    public native void glLightModelxv(
        int pname,
        int[] params,
        int offset
    );

    // C function void glLightModelxv ( GLenum pname, const GLfixed *params )

    public native void glLightModelxv(
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glLightf ( GLenum light, GLenum pname, GLfloat param )

    public native void glLightf(
        int light,
        int pname,
        float param
    );

    // C function void glLightfv ( GLenum light, GLenum pname, const GLfloat *params )

    public native void glLightfv(
        int light,
        int pname,
        float[] params,
        int offset
    );

    // C function void glLightfv ( GLenum light, GLenum pname, const GLfloat *params )

    public native void glLightfv(
        int light,
        int pname,
        java.nio.FloatBuffer params
    );

    // C function void glLightx ( GLenum light, GLenum pname, GLfixed param )

    public native void glLightx(
        int light,
        int pname,
        int param
    );

    // C function void glLightxv ( GLenum light, GLenum pname, const GLfixed *params )

    public native void glLightxv(
        int light,
        int pname,
        int[] params,
        int offset
    );

    // C function void glLightxv ( GLenum light, GLenum pname, const GLfixed *params )

    public native void glLightxv(
        int light,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glLineWidth ( GLfloat width )

    public native void glLineWidth(
        float width
    );

    // C function void glLineWidthx ( GLfixed width )

    public native void glLineWidthx(
        int width
    );

    // C function void glLoadIdentity ( void )

    public native void glLoadIdentity(
    );

    // C function void glLoadMatrixf ( const GLfloat *m )

    public native void glLoadMatrixf(
        float[] m,
        int offset
    );

    // C function void glLoadMatrixf ( const GLfloat *m )

    public native void glLoadMatrixf(
        java.nio.FloatBuffer m
    );

    // C function void glLoadMatrixx ( const GLfixed *m )

    public native void glLoadMatrixx(
        int[] m,
        int offset
    );

    // C function void glLoadMatrixx ( const GLfixed *m )

    public native void glLoadMatrixx(
        java.nio.IntBuffer m
    );

    // C function void glLogicOp ( GLenum opcode )

    public native void glLogicOp(
        int opcode
    );

    // C function void glMaterialf ( GLenum face, GLenum pname, GLfloat param )

    public native void glMaterialf(
        int face,
        int pname,
        float param
    );

    // C function void glMaterialfv ( GLenum face, GLenum pname, const GLfloat *params )

    public native void glMaterialfv(
        int face,
        int pname,
        float[] params,
        int offset
    );

    // C function void glMaterialfv ( GLenum face, GLenum pname, const GLfloat *params )

    public native void glMaterialfv(
        int face,
        int pname,
        java.nio.FloatBuffer params
    );

    // C function void glMaterialx ( GLenum face, GLenum pname, GLfixed param )

    public native void glMaterialx(
        int face,
        int pname,
        int param
    );

    // C function void glMaterialxv ( GLenum face, GLenum pname, const GLfixed *params )

    public native void glMaterialxv(
        int face,
        int pname,
        int[] params,
        int offset
    );

    // C function void glMaterialxv ( GLenum face, GLenum pname, const GLfixed *params )

    public native void glMaterialxv(
        int face,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glMatrixMode ( GLenum mode )

    public native void glMatrixMode(
        int mode
    );

    // C function void glMultMatrixf ( const GLfloat *m )

    public native void glMultMatrixf(
        float[] m,
        int offset
    );

    // C function void glMultMatrixf ( const GLfloat *m )

    public native void glMultMatrixf(
        java.nio.FloatBuffer m
    );

    // C function void glMultMatrixx ( const GLfixed *m )

    public native void glMultMatrixx(
        int[] m,
        int offset
    );

    // C function void glMultMatrixx ( const GLfixed *m )

    public native void glMultMatrixx(
        java.nio.IntBuffer m
    );

    // C function void glMultiTexCoord4f ( GLenum target, GLfloat s, GLfloat t, GLfloat r, GLfloat q )

    public native void glMultiTexCoord4f(
        int target,
        float s,
        float t,
        float r,
        float q
    );

    // C function void glMultiTexCoord4x ( GLenum target, GLfixed s, GLfixed t, GLfixed r, GLfixed q )

    public native void glMultiTexCoord4x(
        int target,
        int s,
        int t,
        int r,
        int q
    );

    // C function void glNormal3f ( GLfloat nx, GLfloat ny, GLfloat nz )

    public native void glNormal3f(
        float nx,
        float ny,
        float nz
    );

    // C function void glNormal3x ( GLfixed nx, GLfixed ny, GLfixed nz )

    public native void glNormal3x(
        int nx,
        int ny,
        int nz
    );

    // C function void glNormalPointer ( GLenum type, GLsizei stride, const GLvoid *pointer )

    private native void glNormalPointerBounds(
        int type,
        int stride,
        java.nio.Buffer pointer,
        int remaining
    );

    public void glNormalPointer(
        int type,
        int stride,
        java.nio.Buffer pointer
    ) {
        glNormalPointerBounds(
            type,
            stride,
            pointer,
            pointer.remaining()
        );
        if (((type == GL_FLOAT) ||
             (type == GL_BYTE) ||
             (type == GL_SHORT) ||
             (type == GL_FIXED)) &&
            (stride >= 0)) {
            _normalPointer = pointer;
        }
    }

    // C function void glOrthof ( GLfloat left, GLfloat right, GLfloat bottom, GLfloat top, GLfloat zNear, GLfloat zFar )

    public native void glOrthof(
        float left,
        float right,
        float bottom,
        float top,
        float zNear,
        float zFar
    );

    // C function void glOrthox ( GLfixed left, GLfixed right, GLfixed bottom, GLfixed top, GLfixed zNear, GLfixed zFar )

    public native void glOrthox(
        int left,
        int right,
        int bottom,
        int top,
        int zNear,
        int zFar
    );

    // C function void glPixelStorei ( GLenum pname, GLint param )

    public native void glPixelStorei(
        int pname,
        int param
    );

    // C function void glPointSize ( GLfloat size )

    public native void glPointSize(
        float size
    );

    // C function void glPointSizex ( GLfixed size )

    public native void glPointSizex(
        int size
    );

    // C function void glPolygonOffset ( GLfloat factor, GLfloat units )

    public native void glPolygonOffset(
        float factor,
        float units
    );

    // C function void glPolygonOffsetx ( GLfixed factor, GLfixed units )

    public native void glPolygonOffsetx(
        int factor,
        int units
    );

    // C function void glPopMatrix ( void )

    public native void glPopMatrix(
    );

    // C function void glPushMatrix ( void )

    public native void glPushMatrix(
    );

    // C function void glReadPixels ( GLint x, GLint y, GLsizei width, GLsizei height, GLenum format, GLenum type, GLvoid *pixels )

    public native void glReadPixels(
        int x,
        int y,
        int width,
        int height,
        int format,
        int type,
        java.nio.Buffer pixels
    );

    // C function void glRotatef ( GLfloat angle, GLfloat x, GLfloat y, GLfloat z )

    public native void glRotatef(
        float angle,
        float x,
        float y,
        float z
    );

    // C function void glRotatex ( GLfixed angle, GLfixed x, GLfixed y, GLfixed z )

    public native void glRotatex(
        int angle,
        int x,
        int y,
        int z
    );

    // C function void glSampleCoverage ( GLclampf value, GLboolean invert )

    public native void glSampleCoverage(
        float value,
        boolean invert
    );

    // C function void glSampleCoveragex ( GLclampx value, GLboolean invert )

    public native void glSampleCoveragex(
        int value,
        boolean invert
    );

    // C function void glScalef ( GLfloat x, GLfloat y, GLfloat z )

    public native void glScalef(
        float x,
        float y,
        float z
    );

    // C function void glScalex ( GLfixed x, GLfixed y, GLfixed z )

    public native void glScalex(
        int x,
        int y,
        int z
    );

    // C function void glScissor ( GLint x, GLint y, GLsizei width, GLsizei height )

    public native void glScissor(
        int x,
        int y,
        int width,
        int height
    );

    // C function void glShadeModel ( GLenum mode )

    public native void glShadeModel(
        int mode
    );

    // C function void glStencilFunc ( GLenum func, GLint ref, GLuint mask )

    public native void glStencilFunc(
        int func,
        int ref,
        int mask
    );

    // C function void glStencilMask ( GLuint mask )

    public native void glStencilMask(
        int mask
    );

    // C function void glStencilOp ( GLenum fail, GLenum zfail, GLenum zpass )

    public native void glStencilOp(
        int fail,
        int zfail,
        int zpass
    );

    // C function void glTexCoordPointer ( GLint size, GLenum type, GLsizei stride, const GLvoid *pointer )

    private native void glTexCoordPointerBounds(
        int size,
        int type,
        int stride,
        java.nio.Buffer pointer,
        int remaining
    );

    public void glTexCoordPointer(
        int size,
        int type,
        int stride,
        java.nio.Buffer pointer
    ) {
        glTexCoordPointerBounds(
            size,
            type,
            stride,
            pointer,
            pointer.remaining()
        );
        if (((size == 2) ||
             (size == 3) ||
             (size == 4)) &&
            ((type == GL_FLOAT) ||
             (type == GL_BYTE) ||
             (type == GL_SHORT) ||
             (type == GL_FIXED)) &&
            (stride >= 0)) {
            _texCoordPointer = pointer;
        }
    }

    // C function void glTexEnvf ( GLenum target, GLenum pname, GLfloat param )

    public native void glTexEnvf(
        int target,
        int pname,
        float param
    );

    // C function void glTexEnvfv ( GLenum target, GLenum pname, const GLfloat *params )

    public native void glTexEnvfv(
        int target,
        int pname,
        float[] params,
        int offset
    );

    // C function void glTexEnvfv ( GLenum target, GLenum pname, const GLfloat *params )

    public native void glTexEnvfv(
        int target,
        int pname,
        java.nio.FloatBuffer params
    );

    // C function void glTexEnvx ( GLenum target, GLenum pname, GLfixed param )

    public native void glTexEnvx(
        int target,
        int pname,
        int param
    );

    // C function void glTexEnvxv ( GLenum target, GLenum pname, const GLfixed *params )

    public native void glTexEnvxv(
        int target,
        int pname,
        int[] params,
        int offset
    );

    // C function void glTexEnvxv ( GLenum target, GLenum pname, const GLfixed *params )

    public native void glTexEnvxv(
        int target,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glTexImage2D ( GLenum target, GLint level, GLint internalformat, GLsizei width, GLsizei height, GLint border, GLenum format, GLenum type, const GLvoid *pixels )

    public native void glTexImage2D(
        int target,
        int level,
        int internalformat,
        int width,
        int height,
        int border,
        int format,
        int type,
        java.nio.Buffer pixels
    );

    // C function void glTexParameterf ( GLenum target, GLenum pname, GLfloat param )

    public native void glTexParameterf(
        int target,
        int pname,
        float param
    );

    // C function void glTexParameterx ( GLenum target, GLenum pname, GLfixed param )

    public native void glTexParameterx(
        int target,
        int pname,
        int param
    );

    // C function void glTexSubImage2D ( GLenum target, GLint level, GLint xoffset, GLint yoffset, GLsizei width, GLsizei height, GLenum format, GLenum type, const GLvoid *pixels )

    public native void glTexSubImage2D(
        int target,
        int level,
        int xoffset,
        int yoffset,
        int width,
        int height,
        int format,
        int type,
        java.nio.Buffer pixels
    );

    // C function void glTranslatef ( GLfloat x, GLfloat y, GLfloat z )

    public native void glTranslatef(
        float x,
        float y,
        float z
    );

    // C function void glTranslatex ( GLfixed x, GLfixed y, GLfixed z )

    public native void glTranslatex(
        int x,
        int y,
        int z
    );

    // C function void glVertexPointer ( GLint size, GLenum type, GLsizei stride, const GLvoid *pointer )

    private native void glVertexPointerBounds(
        int size,
        int type,
        int stride,
        java.nio.Buffer pointer,
        int remaining
    );

    public void glVertexPointer(
        int size,
        int type,
        int stride,
        java.nio.Buffer pointer
    ) {
        glVertexPointerBounds(
            size,
            type,
            stride,
            pointer,
            pointer.remaining()
        );
        if (((size == 2) ||
             (size == 3) ||
             (size == 4)) &&
            ((type == GL_FLOAT) ||
             (type == GL_BYTE) ||
             (type == GL_SHORT) ||
             (type == GL_FIXED)) &&
            (stride >= 0)) {
            _vertexPointer = pointer;
        }
    }

    // C function void glViewport ( GLint x, GLint y, GLsizei width, GLsizei height )

    public native void glViewport(
        int x,
        int y,
        int width,
        int height
    );

    // C function GLbitfield glQueryMatrixxOES ( GLfixed *mantissa, GLint *exponent )

    public native int glQueryMatrixxOES(
        int[] mantissa,
        int mantissaOffset,
        int[] exponent,
        int exponentOffset
    );

    // C function GLbitfield glQueryMatrixxOES ( GLfixed *mantissa, GLint *exponent )

    public native int glQueryMatrixxOES(
        java.nio.IntBuffer mantissa,
        java.nio.IntBuffer exponent
    );

    // C function void glBindBuffer ( GLenum target, GLuint buffer )

    public native void glBindBuffer(
        int target,
        int buffer
    );

    // C function void glBufferData ( GLenum target, GLsizeiptr size, const GLvoid *data, GLenum usage )

    public native void glBufferData(
        int target,
        int size,
        java.nio.Buffer data,
        int usage
    );

    // C function void glBufferSubData ( GLenum target, GLintptr offset, GLsizeiptr size, const GLvoid *data )

    public native void glBufferSubData(
        int target,
        int offset,
        int size,
        java.nio.Buffer data
    );

    // C function void glClipPlanef ( GLenum plane, const GLfloat *equation )

    public native void glClipPlanef(
        int plane,
        float[] equation,
        int offset
    );

    // C function void glClipPlanef ( GLenum plane, const GLfloat *equation )

    public native void glClipPlanef(
        int plane,
        java.nio.FloatBuffer equation
    );

    // C function void glClipPlanex ( GLenum plane, const GLfixed *equation )

    public native void glClipPlanex(
        int plane,
        int[] equation,
        int offset
    );

    // C function void glClipPlanex ( GLenum plane, const GLfixed *equation )

    public native void glClipPlanex(
        int plane,
        java.nio.IntBuffer equation
    );

    // C function void glColor4ub ( GLubyte red, GLubyte green, GLubyte blue, GLubyte alpha )

    public native void glColor4ub(
        byte red,
        byte green,
        byte blue,
        byte alpha
    );

    // C function void glColorPointer ( GLint size, GLenum type, GLsizei stride, GLint offset )

    public native void glColorPointer(
        int size,
        int type,
        int stride,
        int offset
    );

    // C function void glDeleteBuffers ( GLsizei n, const GLuint *buffers )

    public native void glDeleteBuffers(
        int n,
        int[] buffers,
        int offset
    );

    // C function void glDeleteBuffers ( GLsizei n, const GLuint *buffers )

    public native void glDeleteBuffers(
        int n,
        java.nio.IntBuffer buffers
    );

    // C function void glDrawElements ( GLenum mode, GLsizei count, GLenum type, GLint offset )

    public native void glDrawElements(
        int mode,
        int count,
        int type,
        int offset
    );

    // C function void glGenBuffers ( GLsizei n, GLuint *buffers )

    public native void glGenBuffers(
        int n,
        int[] buffers,
        int offset
    );

    // C function void glGenBuffers ( GLsizei n, GLuint *buffers )

    public native void glGenBuffers(
        int n,
        java.nio.IntBuffer buffers
    );

    // C function void glGetBooleanv ( GLenum pname, GLboolean *params )

    public native void glGetBooleanv(
        int pname,
        boolean[] params,
        int offset
    );

    // C function void glGetBooleanv ( GLenum pname, GLboolean *params )

    public native void glGetBooleanv(
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glGetBufferParameteriv ( GLenum target, GLenum pname, GLint *params )

    public native void glGetBufferParameteriv(
        int target,
        int pname,
        int[] params,
        int offset
    );

    // C function void glGetBufferParameteriv ( GLenum target, GLenum pname, GLint *params )

    public native void glGetBufferParameteriv(
        int target,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glGetClipPlanef ( GLenum pname, GLfloat *eqn )

    public native void glGetClipPlanef(
        int pname,
        float[] eqn,
        int offset
    );

    // C function void glGetClipPlanef ( GLenum pname, GLfloat *eqn )

    public native void glGetClipPlanef(
        int pname,
        java.nio.FloatBuffer eqn
    );

    // C function void glGetClipPlanex ( GLenum pname, GLfixed *eqn )

    public native void glGetClipPlanex(
        int pname,
        int[] eqn,
        int offset
    );

    // C function void glGetClipPlanex ( GLenum pname, GLfixed *eqn )

    public native void glGetClipPlanex(
        int pname,
        java.nio.IntBuffer eqn
    );

    // C function void glGetFixedv ( GLenum pname, GLfixed *params )

    public native void glGetFixedv(
        int pname,
        int[] params,
        int offset
    );

    // C function void glGetFixedv ( GLenum pname, GLfixed *params )

    public native void glGetFixedv(
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glGetFloatv ( GLenum pname, GLfloat *params )

    public native void glGetFloatv(
        int pname,
        float[] params,
        int offset
    );

    // C function void glGetFloatv ( GLenum pname, GLfloat *params )

    public native void glGetFloatv(
        int pname,
        java.nio.FloatBuffer params
    );

    // C function void glGetLightfv ( GLenum light, GLenum pname, GLfloat *params )

    public native void glGetLightfv(
        int light,
        int pname,
        float[] params,
        int offset
    );

    // C function void glGetLightfv ( GLenum light, GLenum pname, GLfloat *params )

    public native void glGetLightfv(
        int light,
        int pname,
        java.nio.FloatBuffer params
    );

    // C function void glGetLightxv ( GLenum light, GLenum pname, GLfixed *params )

    public native void glGetLightxv(
        int light,
        int pname,
        int[] params,
        int offset
    );

    // C function void glGetLightxv ( GLenum light, GLenum pname, GLfixed *params )

    public native void glGetLightxv(
        int light,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glGetMaterialfv ( GLenum face, GLenum pname, GLfloat *params )

    public native void glGetMaterialfv(
        int face,
        int pname,
        float[] params,
        int offset
    );

    // C function void glGetMaterialfv ( GLenum face, GLenum pname, GLfloat *params )

    public native void glGetMaterialfv(
        int face,
        int pname,
        java.nio.FloatBuffer params
    );

    // C function void glGetMaterialxv ( GLenum face, GLenum pname, GLfixed *params )

    public native void glGetMaterialxv(
        int face,
        int pname,
        int[] params,
        int offset
    );

    // C function void glGetMaterialxv ( GLenum face, GLenum pname, GLfixed *params )

    public native void glGetMaterialxv(
        int face,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glGetTexEnviv ( GLenum env, GLenum pname, GLint *params )

    public native void glGetTexEnviv(
        int env,
        int pname,
        int[] params,
        int offset
    );

    // C function void glGetTexEnviv ( GLenum env, GLenum pname, GLint *params )

    public native void glGetTexEnviv(
        int env,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glGetTexEnvxv ( GLenum env, GLenum pname, GLfixed *params )

    public native void glGetTexEnvxv(
        int env,
        int pname,
        int[] params,
        int offset
    );

    // C function void glGetTexEnvxv ( GLenum env, GLenum pname, GLfixed *params )

    public native void glGetTexEnvxv(
        int env,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glGetTexParameterfv ( GLenum target, GLenum pname, GLfloat *params )

    public native void glGetTexParameterfv(
        int target,
        int pname,
        float[] params,
        int offset
    );

    // C function void glGetTexParameterfv ( GLenum target, GLenum pname, GLfloat *params )

    public native void glGetTexParameterfv(
        int target,
        int pname,
        java.nio.FloatBuffer params
    );

    // C function void glGetTexParameteriv ( GLenum target, GLenum pname, GLint *params )

    public native void glGetTexParameteriv(
        int target,
        int pname,
        int[] params,
        int offset
    );

    // C function void glGetTexParameteriv ( GLenum target, GLenum pname, GLint *params )

    public native void glGetTexParameteriv(
        int target,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glGetTexParameterxv ( GLenum target, GLenum pname, GLfixed *params )

    public native void glGetTexParameterxv(
        int target,
        int pname,
        int[] params,
        int offset
    );

    // C function void glGetTexParameterxv ( GLenum target, GLenum pname, GLfixed *params )

    public native void glGetTexParameterxv(
        int target,
        int pname,
        java.nio.IntBuffer params
    );

    // C function GLboolean glIsBuffer ( GLuint buffer )

    public native boolean glIsBuffer(
        int buffer
    );

    // C function GLboolean glIsEnabled ( GLenum cap )

    public native boolean glIsEnabled(
        int cap
    );

    // C function GLboolean glIsTexture ( GLuint texture )

    public native boolean glIsTexture(
        int texture
    );

    // C function void glNormalPointer ( GLenum type, GLsizei stride, GLint offset )

    public native void glNormalPointer(
        int type,
        int stride,
        int offset
    );

    // C function void glPointParameterf ( GLenum pname, GLfloat param )

    public native void glPointParameterf(
        int pname,
        float param
    );

    // C function void glPointParameterfv ( GLenum pname, const GLfloat *params )

    public native void glPointParameterfv(
        int pname,
        float[] params,
        int offset
    );

    // C function void glPointParameterfv ( GLenum pname, const GLfloat *params )

    public native void glPointParameterfv(
        int pname,
        java.nio.FloatBuffer params
    );

    // C function void glPointParameterx ( GLenum pname, GLfixed param )

    public native void glPointParameterx(
        int pname,
        int param
    );

    // C function void glPointParameterxv ( GLenum pname, const GLfixed *params )

    public native void glPointParameterxv(
        int pname,
        int[] params,
        int offset
    );

    // C function void glPointParameterxv ( GLenum pname, const GLfixed *params )

    public native void glPointParameterxv(
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glPointSizePointerOES ( GLenum type, GLsizei stride, const GLvoid *pointer )

    private native void glPointSizePointerOESBounds(
        int type,
        int stride,
        java.nio.Buffer pointer,
        int remaining
    );

    public void glPointSizePointerOES(
        int type,
        int stride,
        java.nio.Buffer pointer
    ) {
        glPointSizePointerOESBounds(
            type,
            stride,
            pointer,
            pointer.remaining()
        );
        if (((type == GL_FLOAT) ||
             (type == GL_FIXED)) &&
            (stride >= 0)) {
            _pointSizePointerOES = pointer;
        }
    }

    // C function void glTexCoordPointer ( GLint size, GLenum type, GLsizei stride, GLint offset )

    public native void glTexCoordPointer(
        int size,
        int type,
        int stride,
        int offset
    );

    // C function void glTexEnvi ( GLenum target, GLenum pname, GLint param )

    public native void glTexEnvi(
        int target,
        int pname,
        int param
    );

    // C function void glTexEnviv ( GLenum target, GLenum pname, const GLint *params )

    public native void glTexEnviv(
        int target,
        int pname,
        int[] params,
        int offset
    );

    // C function void glTexEnviv ( GLenum target, GLenum pname, const GLint *params )

    public native void glTexEnviv(
        int target,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glTexParameterfv ( GLenum target, GLenum pname, const GLfloat *params )

    public native void glTexParameterfv(
        int target,
        int pname,
        float[] params,
        int offset
    );

    // C function void glTexParameterfv ( GLenum target, GLenum pname, const GLfloat *params )

    public native void glTexParameterfv(
        int target,
        int pname,
        java.nio.FloatBuffer params
    );

    // C function void glTexParameteri ( GLenum target, GLenum pname, GLint param )

    public native void glTexParameteri(
        int target,
        int pname,
        int param
    );

    // C function void glTexParameteriv ( GLenum target, GLenum pname, const GLint *params )

    public native void glTexParameteriv(
        int target,
        int pname,
        int[] params,
        int offset
    );

    // C function void glTexParameteriv ( GLenum target, GLenum pname, const GLint *params )

    public native void glTexParameteriv(
        int target,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glTexParameterxv ( GLenum target, GLenum pname, const GLfixed *params )

    public native void glTexParameterxv(
        int target,
        int pname,
        int[] params,
        int offset
    );

    // C function void glTexParameterxv ( GLenum target, GLenum pname, const GLfixed *params )

    public native void glTexParameterxv(
        int target,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glVertexPointer ( GLint size, GLenum type, GLsizei stride, GLint offset )

    public native void glVertexPointer(
        int size,
        int type,
        int stride,
        int offset
    );

    // C function void glCurrentPaletteMatrixOES ( GLuint matrixpaletteindex )

    public native void glCurrentPaletteMatrixOES(
        int matrixpaletteindex
    );

    // C function void glDrawTexfOES ( GLfloat x, GLfloat y, GLfloat z, GLfloat width, GLfloat height )

    public native void glDrawTexfOES(
        float x,
        float y,
        float z,
        float width,
        float height
    );

    // C function void glDrawTexfvOES ( const GLfloat *coords )

    public native void glDrawTexfvOES(
        float[] coords,
        int offset
    );

    // C function void glDrawTexfvOES ( const GLfloat *coords )

    public native void glDrawTexfvOES(
        java.nio.FloatBuffer coords
    );

    // C function void glDrawTexiOES ( GLint x, GLint y, GLint z, GLint width, GLint height )

    public native void glDrawTexiOES(
        int x,
        int y,
        int z,
        int width,
        int height
    );

    // C function void glDrawTexivOES ( const GLint *coords )

    public native void glDrawTexivOES(
        int[] coords,
        int offset
    );

    // C function void glDrawTexivOES ( const GLint *coords )

    public native void glDrawTexivOES(
        java.nio.IntBuffer coords
    );

    // C function void glDrawTexsOES ( GLshort x, GLshort y, GLshort z, GLshort width, GLshort height )

    public native void glDrawTexsOES(
        short x,
        short y,
        short z,
        short width,
        short height
    );

    // C function void glDrawTexsvOES ( const GLshort *coords )

    public native void glDrawTexsvOES(
        short[] coords,
        int offset
    );

    // C function void glDrawTexsvOES ( const GLshort *coords )

    public native void glDrawTexsvOES(
        java.nio.ShortBuffer coords
    );

    // C function void glDrawTexxOES ( GLfixed x, GLfixed y, GLfixed z, GLfixed width, GLfixed height )

    public native void glDrawTexxOES(
        int x,
        int y,
        int z,
        int width,
        int height
    );

    // C function void glDrawTexxvOES ( const GLfixed *coords )

    public native void glDrawTexxvOES(
        int[] coords,
        int offset
    );

    // C function void glDrawTexxvOES ( const GLfixed *coords )

    public native void glDrawTexxvOES(
        java.nio.IntBuffer coords
    );

    // C function void glLoadPaletteFromModelViewMatrixOES ( void )

    public native void glLoadPaletteFromModelViewMatrixOES(
    );

    // C function void glMatrixIndexPointerOES ( GLint size, GLenum type, GLsizei stride, const GLvoid *pointer )

    private native void glMatrixIndexPointerOESBounds(
        int size,
        int type,
        int stride,
        java.nio.Buffer pointer,
        int remaining
    );

    public void glMatrixIndexPointerOES(
        int size,
        int type,
        int stride,
        java.nio.Buffer pointer
    ) {
        glMatrixIndexPointerOESBounds(
            size,
            type,
            stride,
            pointer,
            pointer.remaining()
        );
        if (((size == 2) ||
             (size == 3) ||
             (size == 4)) &&
            ((type == GL_FLOAT) ||
             (type == GL_BYTE) ||
             (type == GL_SHORT) ||
             (type == GL_FIXED)) &&
            (stride >= 0)) {
            _matrixIndexPointerOES = pointer;
        }
    }

    // C function void glMatrixIndexPointerOES ( GLint size, GLenum type, GLsizei stride, GLint offset )

    public native void glMatrixIndexPointerOES(
        int size,
        int type,
        int stride,
        int offset
    );

    // C function void glWeightPointerOES ( GLint size, GLenum type, GLsizei stride, const GLvoid *pointer )

    private native void glWeightPointerOESBounds(
        int size,
        int type,
        int stride,
        java.nio.Buffer pointer,
        int remaining
    );

    public void glWeightPointerOES(
        int size,
        int type,
        int stride,
        java.nio.Buffer pointer
    ) {
        glWeightPointerOESBounds(
            size,
            type,
            stride,
            pointer,
            pointer.remaining()
        );
    }

    // C function void glWeightPointerOES ( GLint size, GLenum type, GLsizei stride, GLint offset )

    public native void glWeightPointerOES(
        int size,
        int type,
        int stride,
        int offset
    );

    // C function void glBindFramebufferOES ( GLint target, GLint framebuffer )

    public native void glBindFramebufferOES(
        int target,
        int framebuffer
    );

    // C function void glBindRenderbufferOES ( GLint target, GLint renderbuffer )

    public native void glBindRenderbufferOES(
        int target,
        int renderbuffer
    );

    // C function void glBlendEquation ( GLint mode )

    public native void glBlendEquation(
        int mode
    );

    // C function void glBlendEquationSeparate ( GLint modeRGB, GLint modeAlpha )

    public native void glBlendEquationSeparate(
        int modeRGB,
        int modeAlpha
    );

    // C function void glBlendFuncSeparate ( GLint srcRGB, GLint dstRGB, GLint srcAlpha, GLint dstAlpha )

    public native void glBlendFuncSeparate(
        int srcRGB,
        int dstRGB,
        int srcAlpha,
        int dstAlpha
    );

    // C function GLint glCheckFramebufferStatusOES ( GLint target )

    public native int glCheckFramebufferStatusOES(
        int target
    );

    // C function void glDeleteFramebuffersOES ( GLint n, GLuint *framebuffers )

    public native void glDeleteFramebuffersOES(
        int n,
        int[] framebuffers,
        int offset
    );

    // C function void glDeleteFramebuffersOES ( GLint n, GLuint *framebuffers )

    public native void glDeleteFramebuffersOES(
        int n,
        java.nio.IntBuffer framebuffers
    );

    // C function void glDeleteRenderbuffersOES ( GLint n, GLuint *renderbuffers )

    public native void glDeleteRenderbuffersOES(
        int n,
        int[] renderbuffers,
        int offset
    );

    // C function void glDeleteRenderbuffersOES ( GLint n, GLuint *renderbuffers )

    public native void glDeleteRenderbuffersOES(
        int n,
        java.nio.IntBuffer renderbuffers
    );

    // C function void glFramebufferRenderbufferOES ( GLint target, GLint attachment, GLint renderbuffertarget, GLint renderbuffer )

    public native void glFramebufferRenderbufferOES(
        int target,
        int attachment,
        int renderbuffertarget,
        int renderbuffer
    );

    // C function void glFramebufferTexture2DOES ( GLint target, GLint attachment, GLint textarget, GLint texture, GLint level )

    public native void glFramebufferTexture2DOES(
        int target,
        int attachment,
        int textarget,
        int texture,
        int level
    );

    // C function void glGenerateMipmapOES ( GLint target )

    public native void glGenerateMipmapOES(
        int target
    );

    // C function void glGenFramebuffersOES ( GLint n, GLuint *framebuffers )

    public native void glGenFramebuffersOES(
        int n,
        int[] framebuffers,
        int offset
    );

    // C function void glGenFramebuffersOES ( GLint n, GLuint *framebuffers )

    public native void glGenFramebuffersOES(
        int n,
        java.nio.IntBuffer framebuffers
    );

    // C function void glGenRenderbuffersOES ( GLint n, GLuint *renderbuffers )

    public native void glGenRenderbuffersOES(
        int n,
        int[] renderbuffers,
        int offset
    );

    // C function void glGenRenderbuffersOES ( GLint n, GLuint *renderbuffers )

    public native void glGenRenderbuffersOES(
        int n,
        java.nio.IntBuffer renderbuffers
    );

    // C function void glGetFramebufferAttachmentParameterivOES ( GLint target, GLint attachment, GLint pname, GLint *params )

    public native void glGetFramebufferAttachmentParameterivOES(
        int target,
        int attachment,
        int pname,
        int[] params,
        int offset
    );

    // C function void glGetFramebufferAttachmentParameterivOES ( GLint target, GLint attachment, GLint pname, GLint *params )

    public native void glGetFramebufferAttachmentParameterivOES(
        int target,
        int attachment,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glGetRenderbufferParameterivOES ( GLint target, GLint pname, GLint *params )

    public native void glGetRenderbufferParameterivOES(
        int target,
        int pname,
        int[] params,
        int offset
    );

    // C function void glGetRenderbufferParameterivOES ( GLint target, GLint pname, GLint *params )

    public native void glGetRenderbufferParameterivOES(
        int target,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glGetTexGenfv ( GLint coord, GLint pname, GLfloat *params )

    public native void glGetTexGenfv(
        int coord,
        int pname,
        float[] params,
        int offset
    );

    // C function void glGetTexGenfv ( GLint coord, GLint pname, GLfloat *params )

    public native void glGetTexGenfv(
        int coord,
        int pname,
        java.nio.FloatBuffer params
    );

    // C function void glGetTexGeniv ( GLint coord, GLint pname, GLint *params )

    public native void glGetTexGeniv(
        int coord,
        int pname,
        int[] params,
        int offset
    );

    // C function void glGetTexGeniv ( GLint coord, GLint pname, GLint *params )

    public native void glGetTexGeniv(
        int coord,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glGetTexGenxv ( GLint coord, GLint pname, GLint *params )

    public native void glGetTexGenxv(
        int coord,
        int pname,
        int[] params,
        int offset
    );

    // C function void glGetTexGenxv ( GLint coord, GLint pname, GLint *params )

    public native void glGetTexGenxv(
        int coord,
        int pname,
        java.nio.IntBuffer params
    );

    // C function GLboolean glIsFramebufferOES ( GLint framebuffer )

    public native boolean glIsFramebufferOES(
        int framebuffer
    );

    // C function GLboolean glIsRenderbufferOES ( GLint renderbuffer )

    public native boolean glIsRenderbufferOES(
        int renderbuffer
    );

    // C function void glRenderbufferStorageOES ( GLint target, GLint internalformat, GLint width, GLint height )

    public native void glRenderbufferStorageOES(
        int target,
        int internalformat,
        int width,
        int height
    );

    // C function void glTexGenf ( GLint coord, GLint pname, GLfloat param )

    public native void glTexGenf(
        int coord,
        int pname,
        float param
    );

    // C function void glTexGenfv ( GLint coord, GLint pname, GLfloat *params )

    public native void glTexGenfv(
        int coord,
        int pname,
        float[] params,
        int offset
    );

    // C function void glTexGenfv ( GLint coord, GLint pname, GLfloat *params )

    public native void glTexGenfv(
        int coord,
        int pname,
        java.nio.FloatBuffer params
    );

    // C function void glTexGeni ( GLint coord, GLint pname, GLint param )

    public native void glTexGeni(
        int coord,
        int pname,
        int param
    );

    // C function void glTexGeniv ( GLint coord, GLint pname, GLint *params )

    public native void glTexGeniv(
        int coord,
        int pname,
        int[] params,
        int offset
    );

    // C function void glTexGeniv ( GLint coord, GLint pname, GLint *params )

    public native void glTexGeniv(
        int coord,
        int pname,
        java.nio.IntBuffer params
    );

    // C function void glTexGenx ( GLint coord, GLint pname, GLint param )

    public native void glTexGenx(
        int coord,
        int pname,
        int param
    );

    // C function void glTexGenxv ( GLint coord, GLint pname, GLint *params )

    public native void glTexGenxv(
        int coord,
        int pname,
        int[] params,
        int offset
    );

    // C function void glTexGenxv ( GLint coord, GLint pname, GLint *params )

    public native void glTexGenxv(
        int coord,
        int pname,
        java.nio.IntBuffer params
    );

}
