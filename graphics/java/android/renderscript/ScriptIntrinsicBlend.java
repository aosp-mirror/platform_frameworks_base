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


/**
 * Intrinsic kernels for blending two buffers. Each blend function is a separate
 * kernel to make it easy to change between blend modes.
 **/
public class ScriptIntrinsicBlend extends ScriptIntrinsic {
    ScriptIntrinsicBlend(int id, RenderScript rs) {
        super(id, rs);
    }

    /**
     * Supported elements types are uchar4
     *
     *
     * @param rs
     * @param e
     *
     * @return ScriptIntrinsicBlend
     */
    public static ScriptIntrinsicBlend create(RenderScript rs, Element e) {
        // 7 comes from RS_SCRIPT_INTRINSIC_ID_BLEND in rsDefines.h
        int id = rs.nScriptIntrinsicCreate(7, e.getID(rs));
        return new ScriptIntrinsicBlend(id, rs);

    }

    private void blend(int id, Allocation ain, Allocation aout) {
        if (!ain.getElement().isCompatible(Element.U8_4(mRS))) {
            throw new RSIllegalArgumentException("Input is not of expected format.");
        }
        if (!aout.getElement().isCompatible(Element.U8_4(mRS))) {
            throw new RSIllegalArgumentException("Output is not of expected format.");
        }
        forEach(id, ain, aout, null);
    }

    /**
     * dst = {0, 0, 0, 0}
     *
     * @param ain The source buffer
     * @param aout The destination buffer
     */
    public void forEachClear(Allocation ain, Allocation aout) {
        blend(0, ain, aout);
    }

    /**
     * Get a KernelID for the Clear kernel.
     *
     * @return Script.KernelID The KernelID object.
     */
    public Script.KernelID getKernelIDClear() {
        return createKernelID(0, 3, null, null);
    }


    /**
     * dst = src
     *
     * @param ain The source buffer
     * @param aout The destination buffer
     */
    public void forEachSrc(Allocation ain, Allocation aout) {
        blend(1, ain, aout);
    }

    /**
     * Get a KernelID for the Src kernel.
     *
     * @return Script.KernelID The KernelID object.
     */
    public Script.KernelID getKernelIDSrc() {
        return createKernelID(1, 3, null, null);
    }

    /**
     * dst = dst
     * This is a NOP
     *
     * @param ain The source buffer
     * @param aout The destination buffer
     */
    public void forEachDst(Allocation ain, Allocation aout) {
        // NOP
    }

    /**
     * Get a KernelID for the Dst kernel.
     *
     * @return Script.KernelID The KernelID object.
     */
    public Script.KernelID getKernelIDDst() {
        return createKernelID(2, 3, null, null);
    }

    /**
     * dst = src + dst * (1.0 - src.a)
     *
     * @param ain The source buffer
     * @param aout The destination buffer
     */
    public void forEachSrcOver(Allocation ain, Allocation aout) {
        blend(3, ain, aout);
    }

    /**
     * Get a KernelID for the SrcOver kernel.
     *
     * @return Script.KernelID The KernelID object.
     */
    public Script.KernelID getKernelIDSrcOver() {
        return createKernelID(3, 3, null, null);
    }

    /**
     * dst = dst + src * (1.0 - dst.a)
     *
     * @param ain The source buffer
     * @param aout The destination buffer
     */
    public void forEachDstOver(Allocation ain, Allocation aout) {
        blend(4, ain, aout);
    }

    /**
     * Get a KernelID for the DstOver kernel.
     *
     * @return Script.KernelID The KernelID object.
     */
    public Script.KernelID getKernelIDDstOver() {
        return createKernelID(4, 3, null, null);
    }

    /**
     * dst = src * dst.a
     *
     * @param ain The source buffer
     * @param aout The destination buffer
     */
    public void forEachSrcIn(Allocation ain, Allocation aout) {
        blend(5, ain, aout);
    }

    /**
     * Get a KernelID for the SrcIn kernel.
     *
     * @return Script.KernelID The KernelID object.
     */
    public Script.KernelID getKernelIDSrcIn() {
        return createKernelID(5, 3, null, null);
    }

    /**
     * dst = dst * src.a
     *
     * @param ain The source buffer
     * @param aout The destination buffer
     */
    public void forEachDstIn(Allocation ain, Allocation aout) {
        blend(6, ain, aout);
    }

    /**
     * Get a KernelID for the DstIn kernel.
     *
     * @return Script.KernelID The KernelID object.
     */
    public Script.KernelID getKernelIDDstIn() {
        return createKernelID(6, 3, null, null);
    }

    /**
     * dst = src * (1.0 - dst.a)
     *
     * @param ain The source buffer
     * @param aout The destination buffer
     */
    public void forEachSrcOut(Allocation ain, Allocation aout) {
        blend(7, ain, aout);
    }

    /**
     * Get a KernelID for the SrcOut kernel.
     *
     * @return Script.KernelID The KernelID object.
     */
    public Script.KernelID getKernelIDSrcOut() {
        return createKernelID(7, 3, null, null);
    }

    /**
     * dst = dst * (1.0 - src.a)
     *
     * @param ain The source buffer
     * @param aout The destination buffer
     */
    public void forEachDstOut(Allocation ain, Allocation aout) {
        blend(8, ain, aout);
    }

    /**
     * Get a KernelID for the DstOut kernel.
     *
     * @return Script.KernelID The KernelID object.
     */
    public Script.KernelID getKernelIDDstOut() {
        return createKernelID(8, 3, null, null);
    }

    /**
     * dst.rgb = src.rgb * dst.a + (1.0 - src.a) * dst.rgb
     * dst.a = dst.a
     *
     * @param ain The source buffer
     * @param aout The destination buffer
     */
    public void forEachSrcAtop(Allocation ain, Allocation aout) {
        blend(9, ain, aout);
    }

    /**
     * Get a KernelID for the SrcAtop kernel.
     *
     * @return Script.KernelID The KernelID object.
     */
    public Script.KernelID getKernelIDSrcAtop() {
        return createKernelID(9, 3, null, null);
    }

    /**
     * dst = dst.rgb * src.a + (1.0 - dst.a) * src.rgb
     * dst.a = src.a
     *
     * @param ain The source buffer
     * @param aout The destination buffer
     */
    public void forEachDstAtop(Allocation ain, Allocation aout) {
        blend(10, ain, aout);
    }

    /**
     * Get a KernelID for the DstAtop kernel.
     *
     * @return Script.KernelID The KernelID object.
     */
    public Script.KernelID getKernelIDDstAtop() {
        return createKernelID(10, 3, null, null);
    }

    /**
     * dst = {src.r ^ dst.r, src.g ^ dst.g, src.b ^ dst.b, src.a ^ dst.a}
     *
     * @param ain The source buffer
     * @param aout The destination buffer
     */
    public void forEachXor(Allocation ain, Allocation aout) {
        blend(11, ain, aout);
    }

    /**
     * Get a KernelID for the Xor kernel.
     *
     * @return Script.KernelID The KernelID object.
     */
    public Script.KernelID getKernelIDXor() {
        return createKernelID(11, 3, null, null);
    }

    ////////
/*
    public void forEachNormal(Allocation ain, Allocation aout) {
        blend(12, ain, aout);
    }

    public void forEachAverage(Allocation ain, Allocation aout) {
        blend(13, ain, aout);
    }
*/
    /**
     * dst = src * dst
     *
     * @param ain The source buffer
     * @param aout The destination buffer
     */
    public void forEachMultiply(Allocation ain, Allocation aout) {
        blend(14, ain, aout);
    }

    /**
     * Get a KernelID for the Multiply kernel.
     *
     * @return Script.KernelID The KernelID object.
     */
    public Script.KernelID getKernelIDMultiply() {
        return createKernelID(14, 3, null, null);
    }

/*
    public void forEachScreen(Allocation ain, Allocation aout) {
        blend(15, ain, aout);
    }

    public void forEachDarken(Allocation ain, Allocation aout) {
        blend(16, ain, aout);
    }

    public void forEachLighten(Allocation ain, Allocation aout) {
        blend(17, ain, aout);
    }

    public void forEachOverlay(Allocation ain, Allocation aout) {
        blend(18, ain, aout);
    }

    public void forEachHardlight(Allocation ain, Allocation aout) {
        blend(19, ain, aout);
    }

    public void forEachSoftlight(Allocation ain, Allocation aout) {
        blend(20, ain, aout);
    }

    public void forEachDifference(Allocation ain, Allocation aout) {
        blend(21, ain, aout);
    }

    public void forEachNegation(Allocation ain, Allocation aout) {
        blend(22, ain, aout);
    }

    public void forEachExclusion(Allocation ain, Allocation aout) {
        blend(23, ain, aout);
    }

    public void forEachColorDodge(Allocation ain, Allocation aout) {
        blend(24, ain, aout);
    }

    public void forEachInverseColorDodge(Allocation ain, Allocation aout) {
        blend(25, ain, aout);
    }

    public void forEachSoftDodge(Allocation ain, Allocation aout) {
        blend(26, ain, aout);
    }

    public void forEachColorBurn(Allocation ain, Allocation aout) {
        blend(27, ain, aout);
    }

    public void forEachInverseColorBurn(Allocation ain, Allocation aout) {
        blend(28, ain, aout);
    }

    public void forEachSoftBurn(Allocation ain, Allocation aout) {
        blend(29, ain, aout);
    }

    public void forEachReflect(Allocation ain, Allocation aout) {
        blend(30, ain, aout);
    }

    public void forEachGlow(Allocation ain, Allocation aout) {
        blend(31, ain, aout);
    }

    public void forEachFreeze(Allocation ain, Allocation aout) {
        blend(32, ain, aout);
    }

    public void forEachHeat(Allocation ain, Allocation aout) {
        blend(33, ain, aout);
    }
*/
    /**
     * dst = min(src + dst, 1.0)
     *
     * @param ain The source buffer
     * @param aout The destination buffer
     */
    public void forEachAdd(Allocation ain, Allocation aout) {
        blend(34, ain, aout);
    }

    /**
     * Get a KernelID for the Add kernel.
     *
     * @return Script.KernelID The KernelID object.
     */
    public Script.KernelID getKernelIDAdd() {
        return createKernelID(34, 3, null, null);
    }

    /**
     * dst = max(dst - src, 0.0)
     *
     * @param ain The source buffer
     * @param aout The destination buffer
     */
    public void forEachSubtract(Allocation ain, Allocation aout) {
        blend(35, ain, aout);
    }

    /**
     * Get a KernelID for the Subtract kernel.
     *
     * @return Script.KernelID The KernelID object.
     */
    public Script.KernelID getKernelIDSubtract() {
        return createKernelID(35, 3, null, null);
    }

/*
    public void forEachStamp(Allocation ain, Allocation aout) {
        blend(36, ain, aout);
    }

    public void forEachRed(Allocation ain, Allocation aout) {
        blend(37, ain, aout);
    }

    public void forEachGreen(Allocation ain, Allocation aout) {
        blend(38, ain, aout);
    }

    public void forEachBlue(Allocation ain, Allocation aout) {
        blend(39, ain, aout);
    }

    public void forEachHue(Allocation ain, Allocation aout) {
        blend(40, ain, aout);
    }

    public void forEachSaturation(Allocation ain, Allocation aout) {
        blend(41, ain, aout);
    }

    public void forEachColor(Allocation ain, Allocation aout) {
        blend(42, ain, aout);
    }

    public void forEachLuminosity(Allocation ain, Allocation aout) {
        blend(43, ain, aout);
    }
*/
}

