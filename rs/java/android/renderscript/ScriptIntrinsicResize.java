/*
 * Copyright (C) 2014 The Android Open Source Project
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

/**
 * Intrinsic for performing a resize of a 2D allocation.
 *
 * @deprecated Renderscript has been deprecated in API level 31. Please refer to the <a
 * href="https://developer.android.com/guide/topics/renderscript/migration-guide">migration
 * guide</a> for the proposed alternatives.
 */
@Deprecated
public final class ScriptIntrinsicResize extends ScriptIntrinsic {
    private Allocation mInput;

    private ScriptIntrinsicResize(long id, RenderScript rs) {
        super(id, rs);
    }

    /**
     * Supported elements types are {@link Element#U8}, {@link
     * Element#U8_2}, {@link Element#U8_3}, {@link Element#U8_4}
     * {@link Element#F32}, {@link Element#F32_2}, {@link
     * Element#F32_3}, {@link Element#F32_4}
     *
     * @param rs The RenderScript context
     *
     * @return ScriptIntrinsicResize
     */
    public static ScriptIntrinsicResize create(RenderScript rs) {
        long id = rs.nScriptIntrinsicCreate(12, 0);
        ScriptIntrinsicResize si = new ScriptIntrinsicResize(id, rs);
        return si;

    }

    /**
     * Set the input of the resize.
     * Must match the element type supplied during create.
     *
     * @param ain The input allocation.
     */
    public void setInput(Allocation ain) {
        Element e = ain.getElement();
        if (!e.isCompatible(Element.U8(mRS)) &&
            !e.isCompatible(Element.U8_2(mRS)) &&
            !e.isCompatible(Element.U8_3(mRS)) &&
            !e.isCompatible(Element.U8_4(mRS)) &&
            !e.isCompatible(Element.F32(mRS)) &&
            !e.isCompatible(Element.F32_2(mRS)) &&
            !e.isCompatible(Element.F32_3(mRS)) &&
            !e.isCompatible(Element.F32_4(mRS))) {
            throw new RSIllegalArgumentException("Unsupported element type.");
        }

        mInput = ain;
        setVar(0, ain);
    }

    /**
     * Get a FieldID for the input field of this intrinsic.
     *
     * @return Script.FieldID The FieldID object.
     */
    public Script.FieldID getFieldID_Input() {
        return createFieldID(0, null);
    }


    /**
     * Resize copy the input allocation to the output specified. The
     * Allocation is rescaled if necessary using bi-cubic
     * interpolation.
     *
     * @param aout Output allocation. Element type must match
     *             current input.  Must not be same as input.
     */
    public void forEach_bicubic(Allocation aout) {
        if (aout == mInput) {
            throw new RSIllegalArgumentException("Output cannot be same as Input.");
        }
        forEach_bicubic(aout, null);
    }

    /**
     * Resize copy the input allocation to the output specified. The
     * Allocation is rescaled if necessary using bi-cubic
     * interpolation.
     *
     * @param aout Output allocation. Element type must match
     *             current input.
     * @param opt LaunchOptions for clipping
     */
    public void forEach_bicubic(Allocation aout, Script.LaunchOptions opt) {
        forEach(0, (Allocation) null, aout, null, opt);
    }

    /**
     * Get a KernelID for this intrinsic kernel.
     *
     * @return Script.KernelID The KernelID object.
     */
    public Script.KernelID getKernelID_bicubic() {
        return createKernelID(0, 2, null, null);
    }


}
