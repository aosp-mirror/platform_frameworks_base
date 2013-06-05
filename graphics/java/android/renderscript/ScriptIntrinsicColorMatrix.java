/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.renderscript;

import android.util.Log;

/**
 * Intrinsic for applying a color matrix to allocations.
 *
 * This has the same effect as loading each element and
 * converting it to a {@link Element#F32_4}, multiplying the
 * result by the 4x4 color matrix as performed by
 * rsMatrixMultiply() and writing it to the output after
 * conversion back to {@link Element#U8_4}.
 **/
public final class ScriptIntrinsicColorMatrix extends ScriptIntrinsic {
    private final Matrix4f mMatrix = new Matrix4f();
    private Allocation mInput;

    private ScriptIntrinsicColorMatrix(int id, RenderScript rs) {
        super(id, rs);
    }

    /**
     * Create an intrinsic for applying a color matrix to an
     * allocation.
     *
     * Supported elements types are {@link Element#U8_4}
     *
     * @param rs The RenderScript context
     * @param e Element type for intputs and outputs
     *
     * @return ScriptIntrinsicColorMatrix
     */
    public static ScriptIntrinsicColorMatrix create(RenderScript rs, Element e) {
        if (!e.isCompatible(Element.U8_4(rs))) {
            throw new RSIllegalArgumentException("Unsuported element type.");
        }
        int id = rs.nScriptIntrinsicCreate(2, e.getID(rs));
        return new ScriptIntrinsicColorMatrix(id, rs);

    }

    private void setMatrix() {
        FieldPacker fp = new FieldPacker(16*4);
        fp.addMatrix(mMatrix);
        setVar(0, fp);
    }

    /**
     * Set the color matrix which will be applied to each cell of
     * the image.
     *
     * @param m The 4x4 matrix to set.
     */
    public void setColorMatrix(Matrix4f m) {
        mMatrix.load(m);
        setMatrix();
    }

    /**
     * Set the color matrix which will be applied to each cell of the image.
     * This will set the alpha channel to be a copy.
     *
     * @param m The 3x3 matrix to set.
     */
    public void setColorMatrix(Matrix3f m) {
        mMatrix.load(m);
        setMatrix();
    }

    /**
     * Set a color matrix to convert from RGB to luminance. The alpha channel
     * will be a copy.
     *
     */
    public void setGreyscale() {
        mMatrix.loadIdentity();
        mMatrix.set(0, 0, 0.299f);
        mMatrix.set(1, 0, 0.587f);
        mMatrix.set(2, 0, 0.114f);
        mMatrix.set(0, 1, 0.299f);
        mMatrix.set(1, 1, 0.587f);
        mMatrix.set(2, 1, 0.114f);
        mMatrix.set(0, 2, 0.299f);
        mMatrix.set(1, 2, 0.587f);
        mMatrix.set(2, 2, 0.114f);
        setMatrix();
    }

    /**
     * Set the matrix to convert from YUV to RGB with a direct copy of the 4th
     * channel.
     *
     */
    public void setYUVtoRGB() {
        mMatrix.loadIdentity();
        mMatrix.set(0, 0, 1.f);
        mMatrix.set(1, 0, 0.f);
        mMatrix.set(2, 0, 1.13983f);
        mMatrix.set(0, 1, 1.f);
        mMatrix.set(1, 1, -0.39465f);
        mMatrix.set(2, 1, -0.5806f);
        mMatrix.set(0, 2, 1.f);
        mMatrix.set(1, 2, 2.03211f);
        mMatrix.set(2, 2, 0.f);
        setMatrix();
    }

    /**
     * Set the matrix to convert from RGB to YUV with a direct copy of the 4th
     * channel.
     *
     */
    public void setRGBtoYUV() {
        mMatrix.loadIdentity();
        mMatrix.set(0, 0, 0.299f);
        mMatrix.set(1, 0, 0.587f);
        mMatrix.set(2, 0, 0.114f);
        mMatrix.set(0, 1, -0.14713f);
        mMatrix.set(1, 1, -0.28886f);
        mMatrix.set(2, 1, 0.436f);
        mMatrix.set(0, 2, 0.615f);
        mMatrix.set(1, 2, -0.51499f);
        mMatrix.set(2, 2, -0.10001f);
        setMatrix();
    }


    /**
     * Invoke the kernel and apply the matrix to each cell of ain and copy to
     * aout.
     *
     * @param ain Input allocation
     * @param aout Output allocation
     */
    public void forEach(Allocation ain, Allocation aout) {
        forEach(0, ain, aout, null);
    }

    /**
     * Get a KernelID for this intrinsic kernel.
     *
     * @return Script.KernelID The KernelID object.
     */
    public Script.KernelID getKernelID() {
        return createKernelID(0, 3, null, null);
    }

}

