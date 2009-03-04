/*
 * Copyright (C) 2006 The Android Open Source Project
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

import java.io.InputStream;
import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.microedition.khronos.opengles.GL10;

import android.content.res.AssetManager;

/**
 * {@hide}
 */
public class Texture {

    private int width, height, bpp;
    private ByteBuffer data;
    private int name = -1;

    // Texture maps have the following format.  All integers
    // are 16 bits, high byte first.  Pixels are in 5/6/5
    // RGB format, low byte first.
    //
    // width
    // height
    // pixel (0, 0)
    // pixel (1, 0)
    // ...
    // pixel (width - 1, height - 1)

    private int readInt16(InputStream is) throws IOException {
        return is.read() | (is.read() << 8);
    }

    public Texture(InputStream is) throws IOException {
        this.width  = readInt16(is);
        this.height  = readInt16(is);
        this.bpp = 2;

        int npixels = width*height;
        int nbytes = npixels*bpp;
        byte[] arr = new byte[nbytes];

        int idx = 0;
        while (idx < nbytes) {
            int nread = is.read(arr, idx, nbytes - idx);
            idx += nread;
        }

        if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) {
            // Swap pairs of bytes on big-endian platforms
            for (int i = 0; i < npixels; i++) {
                int j = i*2;
                int k = j + 1;

                byte tmp = arr[j];
                arr[j] = arr[k];
                arr[k] = tmp;
            }
        }

        this.data = ByteBuffer.allocateDirect(arr.length);
        this.data.order(ByteOrder.nativeOrder());
        data.put(arr);
        data.position(0);
    }

    private int loadTexture(GL10 gl,
            int textureUnit,
            int minFilter, int magFilter,
            int wrapS, int wrapT,
            int mode,
            int width, int height,
            int dataType,
            Buffer data) {
        int[] texture = new int[1];
        gl.glGenTextures(1, texture, 0);

        gl.glEnable(gl.GL_TEXTURE_2D);
        gl.glClientActiveTexture(textureUnit);
        gl.glBindTexture(gl.GL_TEXTURE_2D, texture[0]);
        gl.glTexParameterf(gl.GL_TEXTURE_2D,
                gl.GL_TEXTURE_MIN_FILTER,
                minFilter);
        gl.glTexParameterf(gl.GL_TEXTURE_2D,
                gl.GL_TEXTURE_MAG_FILTER,
                magFilter);
        gl.glTexParameterf(gl.GL_TEXTURE_2D,
                gl.GL_TEXTURE_WRAP_S,
                wrapS);
        gl.glTexParameterf(gl.GL_TEXTURE_2D,
                gl.GL_TEXTURE_WRAP_T,
                wrapT);
        gl.glTexEnvf(gl.GL_TEXTURE_ENV, gl.GL_TEXTURE_ENV_MODE, mode);

        gl.glTexImage2D(gl.GL_TEXTURE_2D, 0, gl.GL_RGB,
                width, height,
                0, gl.GL_RGB, dataType,
                data);

        return texture[0];
    }

    public void setTextureParameters(GL10 gl) {
        if (name < 0) {
            name = loadTexture(gl,
                    gl.GL_TEXTURE0,
                    gl.GL_NEAREST, gl.GL_NEAREST,
                    gl.GL_REPEAT, gl.GL_REPEAT,
                    gl.GL_MODULATE,
                    width, height,
                    gl.GL_UNSIGNED_SHORT_5_6_5,
                    data);
        }

        gl.glBindTexture(gl.GL_TEXTURE_2D, name);
    }
}
