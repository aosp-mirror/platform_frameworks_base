/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.rs.test;

import android.content.Context;
import android.content.res.Resources;
import android.renderscript.*;

public class UT_vector extends UnitTest {
    private Resources mRes;

    protected UT_vector(RSTestCore rstc, Resources res, Context ctx) {
        super(rstc, "Vector", ctx);
        mRes = res;
    }

    private boolean initializeGlobals(ScriptC_vector s) {
        Float2 F2 = s.get_f2();
        if (F2.x != 1.0f || F2.y != 2.0f) {
            return false;
        }
        F2.x = 2.99f;
        F2.y = 3.99f;
        s.set_f2(F2);

        Float3 F3 = s.get_f3();
        if (F3.x != 1.0f || F3.y != 2.0f || F3.z != 3.0f) {
            return false;
        }
        F3.x = 2.99f;
        F3.y = 3.99f;
        F3.z = 4.99f;
        s.set_f3(F3);

        Float4 F4 = s.get_f4();
        if (F4.x != 1.0f || F4.y != 2.0f || F4.z != 3.0f || F4.w != 4.0f) {
            return false;
        }
        F4.x = 2.99f;
        F4.y = 3.99f;
        F4.z = 4.99f;
        F4.w = 5.99f;
        s.set_f4(F4);

        Double2 D2 = s.get_d2();
        if (D2.x != 1.0 || D2.y != 2.0) {
            return false;
        }
        D2.x = 2.99;
        D2.y = 3.99;
        s.set_d2(D2);

        Double3 D3 = s.get_d3();
        if (D3.x != 1.0 || D3.y != 2.0 || D3.z != 3.0) {
            return false;
        }
        D3.x = 2.99;
        D3.y = 3.99;
        D3.z = 4.99;
        s.set_d3(D3);

        Double4 D4 = s.get_d4();
        if (D4.x != 1.0 || D4.y != 2.0 || D4.z != 3.0 || D4.w != 4.0) {
            return false;
        }
        D4.x = 2.99;
        D4.y = 3.99;
        D4.z = 4.99;
        D4.w = 5.99;
        s.set_d4(D4);

        Byte2 B2 = s.get_i8_2();
        if (B2.x != 1 || B2.y != 2) {
            return false;
        }
        B2.x = 2;
        B2.y = 3;
        s.set_i8_2(B2);

        Byte3 B3 = s.get_i8_3();
        if (B3.x != 1 || B3.y != 2 || B3.z != 3) {
            return false;
        }
        B3.x = 2;
        B3.y = 3;
        B3.z = 4;
        s.set_i8_3(B3);

        Byte4 B4 = s.get_i8_4();
        if (B4.x != 1 || B4.y != 2 || B4.z != 3 || B4.w != 4) {
            return false;
        }
        B4.x = 2;
        B4.y = 3;
        B4.z = 4;
        B4.w = 5;
        s.set_i8_4(B4);

        Short2 S2 = s.get_u8_2();
        if (S2.x != 1 || S2.y != 2) {
            return false;
        }
        S2.x = 2;
        S2.y = 3;
        s.set_u8_2(S2);

        Short3 S3 = s.get_u8_3();
        if (S3.x != 1 || S3.y != 2 || S3.z != 3) {
            return false;
        }
        S3.x = 2;
        S3.y = 3;
        S3.z = 4;
        s.set_u8_3(S3);

        Short4 S4 = s.get_u8_4();
        if (S4.x != 1 || S4.y != 2 || S4.z != 3 || S4.w != 4) {
            return false;
        }
        S4.x = 2;
        S4.y = 3;
        S4.z = 4;
        S4.w = 5;
        s.set_u8_4(S4);

        S2 = s.get_i16_2();
        if (S2.x != 1 || S2.y != 2) {
            return false;
        }
        S2.x = 2;
        S2.y = 3;
        s.set_i16_2(S2);

        S3 = s.get_i16_3();
        if (S3.x != 1 || S3.y != 2 || S3.z != 3) {
            return false;
        }
        S3.x = 2;
        S3.y = 3;
        S3.z = 4;
        s.set_i16_3(S3);

        S4 = s.get_i16_4();
        if (S4.x != 1 || S4.y != 2 || S4.z != 3 || S4.w != 4) {
            return false;
        }
        S4.x = 2;
        S4.y = 3;
        S4.z = 4;
        S4.w = 5;
        s.set_i16_4(S4);

        Int2 I2 = s.get_u16_2();
        if (I2.x != 1 || I2.y != 2) {
            return false;
        }
        I2.x = 2;
        I2.y = 3;
        s.set_u16_2(I2);

        Int3 I3 = s.get_u16_3();
        if (I3.x != 1 || I3.y != 2 || I3.z != 3) {
            return false;
        }
        I3.x = 2;
        I3.y = 3;
        I3.z = 4;
        s.set_u16_3(I3);

        Int4 I4 = s.get_u16_4();
        if (I4.x != 1 || I4.y != 2 || I4.z != 3 || I4.w != 4) {
            return false;
        }
        I4.x = 2;
        I4.y = 3;
        I4.z = 4;
        I4.w = 5;
        s.set_u16_4(I4);

        I2 = s.get_i32_2();
        if (I2.x != 1 || I2.y != 2) {
            return false;
        }
        I2.x = 2;
        I2.y = 3;
        s.set_i32_2(I2);

        I3 = s.get_i32_3();
        if (I3.x != 1 || I3.y != 2 || I3.z != 3) {
            return false;
        }
        I3.x = 2;
        I3.y = 3;
        I3.z = 4;
        s.set_i32_3(I3);

        I4 = s.get_i32_4();
        if (I4.x != 1 || I4.y != 2 || I4.z != 3 || I4.w != 4) {
            return false;
        }
        I4.x = 2;
        I4.y = 3;
        I4.z = 4;
        I4.w = 5;
        s.set_i32_4(I4);

        Long2 L2 = s.get_u32_2();
        if (L2.x != 1 || L2.y != 2) {
            return false;
        }
        L2.x = 2;
        L2.y = 3;
        s.set_u32_2(L2);

        Long3 L3 = s.get_u32_3();
        if (L3.x != 1 || L3.y != 2 || L3.z != 3) {
            return false;
        }
        L3.x = 2;
        L3.y = 3;
        L3.z = 4;
        s.set_u32_3(L3);

        Long4 L4 = s.get_u32_4();
        if (L4.x != 1 || L4.y != 2 || L4.z != 3 || L4.w != 4) {
            return false;
        }
        L4.x = 2;
        L4.y = 3;
        L4.z = 4;
        L4.w = 5;
        s.set_u32_4(L4);

        L2 = s.get_i64_2();
        if (L2.x != 1 || L2.y != 2) {
            return false;
        }
        L2.x = 2;
        L2.y = 3;
        s.set_i64_2(L2);

        L3 = s.get_i64_3();
        if (L3.x != 1 || L3.y != 2 || L3.z != 3) {
            return false;
        }
        L3.x = 2;
        L3.y = 3;
        L3.z = 4;
        s.set_i64_3(L3);

        L4 = s.get_i64_4();
        if (L4.x != 1 || L4.y != 2 || L4.z != 3 || L4.w != 4) {
            return false;
        }
        L4.x = 2;
        L4.y = 3;
        L4.z = 4;
        L4.w = 5;
        s.set_i64_4(L4);

        L2 = s.get_u64_2();
        if (L2.x != 1 || L2.y != 2) {
            return false;
        }
        L2.x = 2;
        L2.y = 3;
        s.set_u64_2(L2);

        L3 = s.get_u64_3();
        if (L3.x != 1 || L3.y != 2 || L3.z != 3) {
            return false;
        }
        L3.x = 2;
        L3.y = 3;
        L3.z = 4;
        s.set_u64_3(L3);

        L4 = s.get_u64_4();
        if (L4.x != 1 || L4.y != 2 || L4.z != 3 || L4.w != 4) {
            return false;
        }
        L4.x = 2;
        L4.y = 3;
        L4.z = 4;
        L4.w = 5;
        s.set_u64_4(L4);

        return true;
    }

    public void run() {
        RenderScript pRS = RenderScript.create(mCtx);
        ScriptC_vector s = new ScriptC_vector(pRS);
        pRS.setMessageHandler(mRsMessage);
        if (!initializeGlobals(s)) {
            failTest();
        } else {
            s.invoke_vector_test();
            pRS.finish();
            waitForMessage();
        }
        pRS.destroy();
    }
}
