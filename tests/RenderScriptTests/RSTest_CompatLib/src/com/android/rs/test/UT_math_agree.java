/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.rs.test_compat;

import android.content.Context;
import android.content.res.Resources;
import android.support.v8.renderscript.*;
import android.util.Log;
import java.util.Arrays;
import java.util.Random;

public class UT_math_agree extends UnitTest {
    private Resources mRes;
    private Random rand;

    protected UT_math_agree(RSTestCore rstc, Resources res, Context ctx) {
        super(rstc, "Math Agreement", ctx);
        mRes = res;
        rand = new Random();
    }

    // packing functions
    private Float2 pack_f2(float[] val) {
        assert val.length == 2;
        return new Float2(val[0], val[1]);
    }
    private Float3 pack_f3(float[] val) {
        assert val.length == 3;
        return new Float3(val[0], val[1], val[2]);
    }
    private Float4 pack_f4(float[] val) {
        assert val.length == 4;
        return new Float4(val[0], val[1], val[2], val[3]);
    }
    private Byte2 pack_b2(byte[] val) {
        assert val.length == 2;
        return new Byte2(val[0], val[1]);
    }
    private Byte3 pack_b3(byte[] val) {
        assert val.length == 3;
        return new Byte3(val[0], val[1], val[2]);
    }
    private Byte4 pack_b4(byte[] val) {
        assert val.length == 4;
        return new Byte4(val[0], val[1], val[2], val[3]);
    }
    private Short2 pack_s2(short[] val) {
        assert val.length == 2;
        return new Short2(val[0], val[1]);
    }
    private Short3 pack_s3(short[] val) {
        assert val.length == 3;
        return new Short3(val[0], val[1], val[2]);
    }
    private Short4 pack_s4(short[] val) {
        assert val.length == 4;
        return new Short4(val[0], val[1], val[2], val[3]);
    }
    private Int2 pack_i2(int[] val) {
        assert val.length == 2;
        return new Int2(val[0], val[1]);
    }
    private Int3 pack_i3(int[] val) {
        assert val.length == 3;
        return new Int3(val[0], val[1], val[2]);
    }
    private Int4 pack_i4(int[] val) {
        assert val.length == 4;
        return new Int4(val[0], val[1], val[2], val[3]);
    }
    private Long2 pack_l2(long[] val) {
        assert val.length == 2;
        return new Long2(val[0], val[1]);
    }
    private Long3 pack_l3(long[] val) {
        assert val.length == 3;
        return new Long3(val[0], val[1], val[2]);
    }
    private Long4 pack_l4(long[] val) {
        assert val.length == 4;
        return new Long4(val[0], val[1], val[2], val[3]);
    }

    // random vector generation functions
    private float[] randvec_float(int dim) {
        float[] fv = new float[dim];
        for (int i = 0; i < dim; ++i)
            fv[i] = rand.nextFloat();
        return fv;
    }
    private byte[] randvec_char(int dim) {
        byte[] cv = new byte[dim];
        rand.nextBytes(cv);
        return cv;
    }
    private short[] randvec_uchar(int dim) {
       short[] ucv = new short[dim];
       for (int i = 0; i < dim; ++i)
           ucv[i] = (short)rand.nextInt(0x1 << 8);
       return ucv;
    }
    private short[] randvec_short(int dim) {
        short[] sv = new short[dim];
        for (int i = 0; i < dim; ++i)
            sv[i] = (short)rand.nextInt(0x1 << 16);
        return sv;
    }
    private int[] randvec_ushort(int dim) {
        int[] usv = new int[dim];
        for (int i = 0; i < dim; ++i)
            usv[i] = rand.nextInt(0x1 << 16);
        return usv;
    }
    private int[] randvec_int(int dim) {
        int[] iv = new int[dim];
        for (int i = 0; i < dim; ++i)
            iv[i] = rand.nextInt();
        return iv;
    }
    private long[] randvec_uint(int dim) {
        long[] uiv = new long[dim];
        for (int i = 0; i < dim; ++i)
            uiv[i] = (long)rand.nextInt() - (long)Integer.MIN_VALUE;
        return uiv;
    }
    private long[] randvec_long(int dim) {
        long[] lv = new long[dim];
        for (int i = 0; i < dim; ++i)
            lv[i] = rand.nextLong();
        return lv;
    }
    // TODO:  unsigned long generator

    // min reference functions
    private float min(float v1, float v2) {
        return v1 < v2 ? v1 : v2;
    }
    private float[] min(float[] v1, float[] v2) {
        assert v1.length == v2.length;
        float[] rv = new float[v1.length];
        for (int i = 0; i < v1.length; ++i)
            rv[i] = min(v1[i], v2[i]);
        return rv;
    }
    private byte min(byte v1, byte v2) {
        return v1 < v2 ? v1 : v2;
    }
    private byte[] min(byte[] v1, byte[] v2) {
        assert v1.length == v2.length;
        byte[] rv = new byte[v1.length];
        for (int i = 0; i < v1.length; ++i)
            rv[i] = min(v1[i], v2[i]);
        return rv;
    }
    private short min(short v1, short v2) {
        return v1 < v2 ? v1 : v2;
    }
    private short[] min(short[] v1, short[] v2) {
        assert v1.length == v2.length;
        short[] rv = new short[v1.length];
        for (int i = 0; i < v1.length; ++i)
            rv[i] = min(v1[i], v2[i]);
        return rv;
    }
    private int min(int v1, int v2) {
        return v1 < v2 ? v1 : v2;
    }
    private int[] min(int[] v1, int[] v2) {
        assert v1.length == v2.length;
        int[] rv = new int[v1.length];
        for (int i = 0; i < v1.length; ++i)
            rv[i] = min(v1[i], v2[i]);
        return rv;
    }
    private long min(long v1, long v2) {
        return v1 < v2 ? v1 : v2;
    }
    private long[] min(long[] v1, long[] v2) {
        assert v1.length == v2.length;
        long[] rv = new long[v1.length];
        for (int i = 0; i < v1.length; ++i)
            rv[i] = min(v1[i], v2[i]);
        return rv;
    }
    // TODO:  unsigned long version of min

    // max reference functions
    private float max(float v1, float v2) {
        return v1 > v2 ? v1 : v2;
    }
    private float[] max(float[] v1, float[] v2) {
        assert v1.length == v2.length;
        float[] rv = new float[v1.length];
        for (int i = 0; i < v1.length; ++i)
            rv[i] = max(v1[i], v2[i]);
        return rv;
    }
    private byte max(byte v1, byte v2) {
        return v1 > v2 ? v1 : v2;
    }
    private byte[] max(byte[] v1, byte[] v2) {
        assert v1.length == v2.length;
        byte[] rv = new byte[v1.length];
        for (int i = 0; i < v1.length; ++i)
            rv[i] = max(v1[i], v2[i]);
        return rv;
    }
    private short max(short v1, short v2) {
        return v1 > v2 ? v1 : v2;
    }
    private short[] max(short[] v1, short[] v2) {
        assert v1.length == v2.length;
        short[] rv = new short[v1.length];
        for (int i = 0; i < v1.length; ++i)
            rv[i] = max(v1[i], v2[i]);
        return rv;
    }
    private int max(int v1, int v2) {
        return v1 > v2 ? v1 : v2;
    }
    private int[] max(int[] v1, int[] v2) {
        assert v1.length == v2.length;
        int[] rv = new int[v1.length];
        for (int i = 0; i < v1.length; ++i)
            rv[i] = max(v1[i], v2[i]);
        return rv;
    }
    private long max(long v1, long v2) {
        return v1 > v2 ? v1 : v2;
    }
    private long[] max(long[] v1, long[] v2) {
        assert v1.length == v2.length;
        long[] rv = new long[v1.length];
        for (int i = 0; i < v1.length; ++i)
            rv[i] = max(v1[i], v2[i]);
        return rv;
    }
    // TODO:  unsigned long version of max

    // fmin reference functions
    private float fmin(float v1, float v2) {
        return min(v1, v2);
    }
    private float[] fmin(float[] v1, float[] v2) {
        return min(v1, v2);
    }
    private float[] fmin(float[] v1, float v2) {
        float[] rv = new float[v1.length];
        for (int i = 0; i < v1.length; ++i)
            rv[i] = min(v1[i], v2);
        return rv;
    }

    // fmax reference functions
    private float fmax(float v1, float v2) {
        return max(v1, v2);
    }
    private float[] fmax(float[] v1, float[] v2) {
        return max(v1, v2);
    }
    private float[] fmax(float[] v1, float v2) {
        float[] rv = new float[v1.length];
        for (int i = 0; i < v1.length; ++i)
            rv[i] = max(v1[i], v2);
        return rv;
    }

    private void initializeValues(ScriptC_math_agree s) {
        float x = rand.nextFloat();
        float y = rand.nextFloat();

        s.set_x(x);
        s.set_y(y);
        s.set_result_add(x + y);
        s.set_result_sub(x - y);
        s.set_result_mul(x * y);
        s.set_result_div(x / y);

        // Generate random vectors of all types
        float rand_f1_0 = rand.nextFloat();
        float[] rand_f2_0 = randvec_float(2);
        float[] rand_f3_0 = randvec_float(3);
        float[] rand_f4_0 = randvec_float(4);
        float rand_f1_1 = rand.nextFloat();
        float[] rand_f2_1 = randvec_float(2);
        float[] rand_f3_1 = randvec_float(3);
        float[] rand_f4_1 = randvec_float(4);
        short rand_uc1_0 = (short)rand.nextInt(0x1 << 8);
        short[] rand_uc2_0 = randvec_uchar(2);
        short[] rand_uc3_0 = randvec_uchar(3);
        short[] rand_uc4_0 = randvec_uchar(4);
        short rand_uc1_1 = (short)rand.nextInt(0x1 << 8);
        short[] rand_uc2_1 = randvec_uchar(2);
        short[] rand_uc3_1 = randvec_uchar(3);
        short[] rand_uc4_1 = randvec_uchar(4);
        short rand_ss1_0 = (short)rand.nextInt(0x1 << 16);
        short[] rand_ss2_0 = randvec_short(2);
        short[] rand_ss3_0 = randvec_short(3);
        short[] rand_ss4_0 = randvec_short(4);
        short rand_ss1_1 = (short)rand.nextInt(0x1 << 16);
        short[] rand_ss2_1 = randvec_short(2);
        short[] rand_ss3_1 = randvec_short(3);
        short[] rand_ss4_1 = randvec_short(4);
        int rand_us1_0 = rand.nextInt(0x1 << 16);
        int[] rand_us2_0 = randvec_ushort(2);
        int[] rand_us3_0 = randvec_ushort(3);
        int[] rand_us4_0 = randvec_ushort(4);
        int rand_us1_1 = rand.nextInt(0x1 << 16);
        int[] rand_us2_1 = randvec_ushort(2);
        int[] rand_us3_1 = randvec_ushort(3);
        int[] rand_us4_1 = randvec_ushort(4);
        int rand_si1_0 = rand.nextInt();
        int[] rand_si2_0 = randvec_int(2);
        int[] rand_si3_0 = randvec_int(3);
        int[] rand_si4_0 = randvec_int(4);
        int rand_si1_1 = rand.nextInt();
        int[] rand_si2_1 = randvec_int(2);
        int[] rand_si3_1 = randvec_int(3);
        int[] rand_si4_1 = randvec_int(4);
        long rand_ui1_0 = (long)rand.nextInt() - (long)Integer.MIN_VALUE;
        long[] rand_ui2_0 = randvec_uint(2);
        long[] rand_ui3_0 = randvec_uint(3);
        long[] rand_ui4_0 = randvec_uint(4);
        long rand_ui1_1 = (long)rand.nextInt() - (long)Integer.MIN_VALUE;
        long[] rand_ui2_1 = randvec_uint(2);
        long[] rand_ui3_1 = randvec_uint(3);
        long[] rand_ui4_1 = randvec_uint(4);
        long rand_sl1_0 = rand.nextLong();
        long[] rand_sl2_0 = randvec_long(2);
        long[] rand_sl3_0 = randvec_long(3);
        long[] rand_sl4_0 = randvec_long(4);
        long rand_sl1_1 = rand.nextLong();
        long[] rand_sl2_1 = randvec_long(2);
        long[] rand_sl3_1 = randvec_long(3);
        long[] rand_sl4_1 = randvec_long(4);
        byte rand_sc1_0 = (byte)rand.nextInt(0x1 << 8);
        byte[] rand_sc2_0 = randvec_char(2);
        byte[] rand_sc3_0 = randvec_char(3);
        byte[] rand_sc4_0 = randvec_char(4);
        byte rand_sc1_1 = (byte)rand.nextInt(0x1 << 8);
        byte[] rand_sc2_1 = randvec_char(2);
        byte[] rand_sc3_1 = randvec_char(3);
        byte[] rand_sc4_1 = randvec_char(4);
        // TODO:  generate unsigned long vectors

        // Set random vectors in renderscript code
        s.set_rand_f1_0(rand_f1_0);
        s.set_rand_f2_0(pack_f2(rand_f2_0));
        s.set_rand_f3_0(pack_f3(rand_f3_0));
        s.set_rand_f4_0(pack_f4(rand_f4_0));
        s.set_rand_f1_1(rand_f1_1);
        s.set_rand_f2_1(pack_f2(rand_f2_1));
        s.set_rand_f3_1(pack_f3(rand_f3_1));
        s.set_rand_f4_1(pack_f4(rand_f4_1));
        s.set_rand_uc1_1(rand_uc1_1);
        s.set_rand_uc2_1(pack_s2(rand_uc2_1));
        s.set_rand_uc3_1(pack_s3(rand_uc3_1));
        s.set_rand_uc4_1(pack_s4(rand_uc4_1));
        s.set_rand_ss1_0(rand_ss1_0);
        s.set_rand_ss2_0(pack_s2(rand_ss2_0));
        s.set_rand_ss3_0(pack_s3(rand_ss3_0));
        s.set_rand_ss4_0(pack_s4(rand_ss4_0));
        s.set_rand_ss1_1(rand_ss1_1);
        s.set_rand_ss2_1(pack_s2(rand_ss2_1));
        s.set_rand_ss3_1(pack_s3(rand_ss3_1));
        s.set_rand_ss4_1(pack_s4(rand_ss4_1));
        s.set_rand_us1_0(rand_us1_0);
        s.set_rand_us2_0(pack_i2(rand_us2_0));
        s.set_rand_us3_0(pack_i3(rand_us3_0));
        s.set_rand_us4_0(pack_i4(rand_us4_0));
        s.set_rand_us1_1(rand_us1_1);
        s.set_rand_us2_1(pack_i2(rand_us2_1));
        s.set_rand_us3_1(pack_i3(rand_us3_1));
        s.set_rand_us4_1(pack_i4(rand_us4_1));
        s.set_rand_si1_0(rand_si1_0);
        s.set_rand_si2_0(pack_i2(rand_si2_0));
        s.set_rand_si3_0(pack_i3(rand_si3_0));
        s.set_rand_si4_0(pack_i4(rand_si4_0));
        s.set_rand_si1_1(rand_si1_1);
        s.set_rand_si2_1(pack_i2(rand_si2_1));
        s.set_rand_si3_1(pack_i3(rand_si3_1));
        s.set_rand_si4_1(pack_i4(rand_si4_1));
        s.set_rand_ui1_0(rand_ui1_0);
        s.set_rand_ui2_0(pack_l2(rand_ui2_0));
        s.set_rand_ui3_0(pack_l3(rand_ui3_0));
        s.set_rand_ui4_0(pack_l4(rand_ui4_0));
        s.set_rand_ui1_1(rand_ui1_1);
        s.set_rand_ui2_1(pack_l2(rand_ui2_1));
        s.set_rand_ui3_1(pack_l3(rand_ui3_1));
        s.set_rand_ui4_1(pack_l4(rand_ui4_1));
        s.set_rand_sl1_0(rand_sl1_0);
        s.set_rand_sl2_0(pack_l2(rand_sl2_0));
        s.set_rand_sl3_0(pack_l3(rand_sl3_0));
        s.set_rand_sl4_0(pack_l4(rand_sl4_0));
        s.set_rand_sl1_1(rand_sl1_1);
        s.set_rand_sl2_1(pack_l2(rand_sl2_1));
        s.set_rand_sl3_1(pack_l3(rand_sl3_1));
        s.set_rand_sl4_1(pack_l4(rand_sl4_1));
        s.set_rand_uc1_0(rand_uc1_0);
        s.set_rand_uc2_0(pack_s2(rand_uc2_0));
        s.set_rand_uc3_0(pack_s3(rand_uc3_0));
        s.set_rand_uc4_0(pack_s4(rand_uc4_0));
        s.set_rand_sc1_0(rand_sc1_0);
        s.set_rand_sc2_0(pack_b2(rand_sc2_0));
        s.set_rand_sc3_0(pack_b3(rand_sc3_0));
        s.set_rand_sc4_0(pack_b4(rand_sc4_0));
        s.set_rand_sc1_1(rand_sc1_1);
        s.set_rand_sc2_1(pack_b2(rand_sc2_1));
        s.set_rand_sc3_1(pack_b3(rand_sc3_1));
        s.set_rand_sc4_1(pack_b4(rand_sc4_1));
        // TODO:  set unsigned long vectors

        // Set results for min
        s.set_min_rand_f1_f1(min(rand_f1_0, rand_f1_1));
        s.set_min_rand_f2_f2(pack_f2(min(rand_f2_0, rand_f2_1)));
        s.set_min_rand_f3_f3(pack_f3(min(rand_f3_0, rand_f3_1)));
        s.set_min_rand_f4_f4(pack_f4(min(rand_f4_0, rand_f4_1)));
        s.set_min_rand_uc1_uc1(min(rand_uc1_0, rand_uc1_1));
        s.set_min_rand_uc2_uc2(pack_s2(min(rand_uc2_0, rand_uc2_1)));
        s.set_min_rand_uc3_uc3(pack_s3(min(rand_uc3_0, rand_uc3_1)));
        s.set_min_rand_uc4_uc4(pack_s4(min(rand_uc4_0, rand_uc4_1)));
        s.set_min_rand_ss1_ss1(min(rand_ss1_0, rand_ss1_1));
        s.set_min_rand_ss2_ss2(pack_s2(min(rand_ss2_0, rand_ss2_1)));
        s.set_min_rand_ss3_ss3(pack_s3(min(rand_ss3_0, rand_ss3_1)));
        s.set_min_rand_ss4_ss4(pack_s4(min(rand_ss4_0, rand_ss4_1)));
        s.set_min_rand_us1_us1(min(rand_us1_0, rand_us1_1));
        s.set_min_rand_us2_us2(pack_i2(min(rand_us2_0, rand_us2_1)));
        s.set_min_rand_us3_us3(pack_i3(min(rand_us3_0, rand_us3_1)));
        s.set_min_rand_us4_us4(pack_i4(min(rand_us4_0, rand_us4_1)));
        s.set_min_rand_si1_si1(min(rand_si1_0, rand_si1_1));
        s.set_min_rand_si2_si2(pack_i2(min(rand_si2_0, rand_si2_1)));
        s.set_min_rand_si3_si3(pack_i3(min(rand_si3_0, rand_si3_1)));
        s.set_min_rand_si4_si4(pack_i4(min(rand_si4_0, rand_si4_1)));
        s.set_min_rand_ui1_ui1(min(rand_ui1_0, rand_ui1_1));
        s.set_min_rand_ui2_ui2(pack_l2(min(rand_ui2_0, rand_ui2_1)));
        s.set_min_rand_ui3_ui3(pack_l3(min(rand_ui3_0, rand_ui3_1)));
        s.set_min_rand_ui4_ui4(pack_l4(min(rand_ui4_0, rand_ui4_1)));
        s.set_min_rand_sl1_sl1(min(rand_sl1_0, rand_sl1_1));
        s.set_min_rand_sl2_sl2(pack_l2(min(rand_sl2_0, rand_sl2_1)));
        s.set_min_rand_sl3_sl3(pack_l3(min(rand_sl3_0, rand_sl3_1)));
        s.set_min_rand_sl4_sl4(pack_l4(min(rand_sl4_0, rand_sl4_1)));
        s.set_min_rand_sc1_sc1(min(rand_sc1_0, rand_sc1_1));
        s.set_min_rand_sc2_sc2(pack_b2(min(rand_sc2_0, rand_sc2_1)));
        s.set_min_rand_sc3_sc3(pack_b3(min(rand_sc3_0, rand_sc3_1)));
        s.set_min_rand_sc4_sc4(pack_b4(min(rand_sc4_0, rand_sc4_1)));
        // TODO:  set results for unsigned long min

        // Set results for max
        s.set_max_rand_f1_f1(max(rand_f1_0, rand_f1_1));
        s.set_max_rand_f2_f2(pack_f2(max(rand_f2_0, rand_f2_1)));
        s.set_max_rand_f3_f3(pack_f3(max(rand_f3_0, rand_f3_1)));
        s.set_max_rand_f4_f4(pack_f4(max(rand_f4_0, rand_f4_1)));
        s.set_max_rand_uc1_uc1(max(rand_uc1_0, rand_uc1_1));
        s.set_max_rand_uc2_uc2(pack_s2(max(rand_uc2_0, rand_uc2_1)));
        s.set_max_rand_uc3_uc3(pack_s3(max(rand_uc3_0, rand_uc3_1)));
        s.set_max_rand_uc4_uc4(pack_s4(max(rand_uc4_0, rand_uc4_1)));
        s.set_max_rand_ss1_ss1(max(rand_ss1_0, rand_ss1_1));
        s.set_max_rand_ss2_ss2(pack_s2(max(rand_ss2_0, rand_ss2_1)));
        s.set_max_rand_ss3_ss3(pack_s3(max(rand_ss3_0, rand_ss3_1)));
        s.set_max_rand_ss4_ss4(pack_s4(max(rand_ss4_0, rand_ss4_1)));
        s.set_max_rand_us1_us1(max(rand_us1_0, rand_us1_1));
        s.set_max_rand_us2_us2(pack_i2(max(rand_us2_0, rand_us2_1)));
        s.set_max_rand_us3_us3(pack_i3(max(rand_us3_0, rand_us3_1)));
        s.set_max_rand_us4_us4(pack_i4(max(rand_us4_0, rand_us4_1)));
        s.set_max_rand_si1_si1(max(rand_si1_0, rand_si1_1));
        s.set_max_rand_si2_si2(pack_i2(max(rand_si2_0, rand_si2_1)));
        s.set_max_rand_si3_si3(pack_i3(max(rand_si3_0, rand_si3_1)));
        s.set_max_rand_si4_si4(pack_i4(max(rand_si4_0, rand_si4_1)));
        s.set_max_rand_ui1_ui1(max(rand_ui1_0, rand_ui1_1));
        s.set_max_rand_ui2_ui2(pack_l2(max(rand_ui2_0, rand_ui2_1)));
        s.set_max_rand_ui3_ui3(pack_l3(max(rand_ui3_0, rand_ui3_1)));
        s.set_max_rand_ui4_ui4(pack_l4(max(rand_ui4_0, rand_ui4_1)));
        s.set_max_rand_sl1_sl1(max(rand_sl1_0, rand_sl1_1));
        s.set_max_rand_sl2_sl2(pack_l2(max(rand_sl2_0, rand_sl2_1)));
        s.set_max_rand_sl3_sl3(pack_l3(max(rand_sl3_0, rand_sl3_1)));
        s.set_max_rand_sl4_sl4(pack_l4(max(rand_sl4_0, rand_sl4_1)));
        s.set_max_rand_sc1_sc1(max(rand_sc1_0, rand_sc1_1));
        s.set_max_rand_sc2_sc2(pack_b2(max(rand_sc2_0, rand_sc2_1)));
        s.set_max_rand_sc3_sc3(pack_b3(max(rand_sc3_0, rand_sc3_1)));
        s.set_max_rand_sc4_sc4(pack_b4(max(rand_sc4_0, rand_sc4_1)));

        // TODO:  set results for unsigned long max

        // Set results for fmin
        s.set_fmin_rand_f1_f1(fmin(rand_f1_0, rand_f1_1));
        s.set_fmin_rand_f2_f2(pack_f2(fmin(rand_f2_0, rand_f2_1)));
        s.set_fmin_rand_f3_f3(pack_f3(fmin(rand_f3_0, rand_f3_1)));
        s.set_fmin_rand_f4_f4(pack_f4(fmin(rand_f4_0, rand_f4_1)));
        s.set_fmin_rand_f2_f1(pack_f2(fmin(rand_f2_0, rand_f1_1)));
        s.set_fmin_rand_f3_f1(pack_f3(fmin(rand_f3_0, rand_f1_1)));
        s.set_fmin_rand_f4_f1(pack_f4(fmin(rand_f4_0, rand_f1_1)));

        // Set results for fmax
        s.set_fmax_rand_f1_f1(fmax(rand_f1_0, rand_f1_1));
        s.set_fmax_rand_f2_f2(pack_f2(fmax(rand_f2_0, rand_f2_1)));
        s.set_fmax_rand_f3_f3(pack_f3(fmax(rand_f3_0, rand_f3_1)));
        s.set_fmax_rand_f4_f4(pack_f4(fmax(rand_f4_0, rand_f4_1)));
        s.set_fmax_rand_f2_f1(pack_f2(fmax(rand_f2_0, rand_f1_1)));
        s.set_fmax_rand_f3_f1(pack_f3(fmax(rand_f3_0, rand_f1_1)));
        s.set_fmax_rand_f4_f1(pack_f4(fmax(rand_f4_0, rand_f1_1)));
    }

    public void run() {
        RenderScript pRS = RenderScript.create(mCtx);
        ScriptC_math_agree s = new ScriptC_math_agree(pRS);
        pRS.setMessageHandler(mRsMessage);
        initializeValues(s);
        s.invoke_math_agree_test();
        pRS.finish();
        waitForMessage();
        pRS.destroy();
    }
}
