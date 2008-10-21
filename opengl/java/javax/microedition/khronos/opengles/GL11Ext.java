/* //device/java/android/javax/microedition/khronos/opengles/GL11Ext.java
**
** Copyright 2007, The Android Open Source Project
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

package javax.microedition.khronos.opengles;

public interface GL11Ext extends GL {
    int GL_MATRIX_INDEX_ARRAY_BUFFER_BINDING_OES = 0x8B9E;
    int GL_MATRIX_INDEX_ARRAY_OES                = 0x8844;
    int GL_MATRIX_INDEX_ARRAY_POINTER_OES        = 0x8849;
    int GL_MATRIX_INDEX_ARRAY_SIZE_OES           = 0x8846;
    int GL_MATRIX_INDEX_ARRAY_STRIDE_OES         = 0x8848;
    int GL_MATRIX_INDEX_ARRAY_TYPE_OES           = 0x8847;
    int GL_MATRIX_PALETTE_OES                    = 0x8840;
    int GL_MAX_PALETTE_MATRICES_OES              = 0x8842;
    int GL_MAX_VERTEX_UNITS_OES                  = 0x86A4;
    int GL_TEXTURE_CROP_RECT_OES                 = 0x8B9D;
    int GL_WEIGHT_ARRAY_BUFFER_BINDING_OES       = 0x889E;
    int GL_WEIGHT_ARRAY_OES                      = 0x86AD;
    int GL_WEIGHT_ARRAY_POINTER_OES              = 0x86AC;
    int GL_WEIGHT_ARRAY_SIZE_OES                 = 0x86AB;
    int GL_WEIGHT_ARRAY_STRIDE_OES               = 0x86AA;
    int GL_WEIGHT_ARRAY_TYPE_OES                 = 0x86A9;

    void glTexParameterfv(int target, int pname, float[] param, int offset);

    void glCurrentPaletteMatrixOES(
        int matrixpaletteindex
    );

    void glDrawTexfOES(
        float x,
        float y,
        float z,
        float width,
        float height
    );

    void glDrawTexfvOES(
        float[] coords,
        int offset
    );

    void glDrawTexfvOES(
        java.nio.FloatBuffer coords
    );

    void glDrawTexiOES(
        int x,
        int y,
        int z,
        int width,
        int height
    );

    void glDrawTexivOES(
        int[] coords,
        int offset
    );

    void glDrawTexivOES(
        java.nio.IntBuffer coords
    );

    void glDrawTexsOES(
        short x,
        short y,
        short z,
        short width,
        short height
    );

    void glDrawTexsvOES(
        short[] coords,
        int offset
    );

    void glDrawTexsvOES(
        java.nio.ShortBuffer coords
    );

    void glDrawTexxOES(
        int x,
        int y,
        int z,
        int width,
        int height
    );

    void glDrawTexxvOES(
        int[] coords,
        int offset
    );

    void glDrawTexxvOES(
        java.nio.IntBuffer coords
    );

    void glEnable(
        int cap
    );

    void glEnableClientState(
        int array
    );

    void glLoadPaletteFromModelViewMatrixOES(
    );

    void glMatrixIndexPointerOES(
        int size,
        int type,
        int stride,
        java.nio.Buffer pointer
    );

    void glMatrixIndexPointerOES(
        int size,
        int type,
        int stride,
        int offset
    );

    void glWeightPointerOES(
        int size,
        int type,
        int stride,
        java.nio.Buffer pointer
    );

    void glWeightPointerOES(
        int size,
        int type,
        int stride,
        int offset
    );

}
