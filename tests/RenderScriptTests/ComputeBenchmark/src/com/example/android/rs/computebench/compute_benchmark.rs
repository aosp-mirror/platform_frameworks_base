// Copyright (C) 2012 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#pragma version(1)
#pragma rs java_package_name(com.example.android.rs.computebench)

// Test configuration (accessible from Java)
uint priming_runs   = 1000000;
uint timing_runs    = 5000000;

// Reused variables

static volatile int64_t bench_time;
static float inv_timing_runs;

#define DECL_VAR_SET(prefix)                \
static volatile float prefix##_f_1 = 1;     \
static volatile float2 prefix##_f_2 = 1;    \
static volatile float3 prefix##_f_3 = 1;    \
static volatile float4 prefix##_f_4 = 1;    \
static volatile char prefix##_c_1 = 1;      \
static volatile char2 prefix##_c_2 = 1;     \
static volatile char3 prefix##_c_3 = 1;     \
static volatile char4 prefix##_c_4 = 1;     \
static volatile uchar prefix##_uc_1 = 1;    \
static volatile uchar2 prefix##_uc_2 = 1;   \
static volatile uchar3 prefix##_uc_3 = 1;   \
static volatile uchar4 prefix##_uc_4 = 1;   \
static volatile short prefix##_s_1 = 1;     \
static volatile short2 prefix##_s_2 = 1;    \
static volatile short3 prefix##_s_3 = 1;    \
static volatile short4 prefix##_s_4 = 1;    \
static volatile ushort prefix##_us_1 = 1;   \
static volatile ushort2 prefix##_us_2 = 1;  \
static volatile ushort3 prefix##_us_3 = 1;  \
static volatile ushort4 prefix##_us_4 = 1;  \
static volatile int prefix##_i_1 = 1;       \
static volatile int2 prefix##_i_2 = 1;      \
static volatile int3 prefix##_i_3 = 1;      \
static volatile int4 prefix##_i_4 = 1;      \
static volatile uint prefix##_ui_1 = 1;     \
static volatile uint2 prefix##_ui_2 = 1;    \
static volatile uint3 prefix##_ui_3 = 1;    \
static volatile uint4 prefix##_ui_4 = 1;    \
static volatile long prefix##_l_1 = 1;      \
static volatile long2 prefix##_l_2 = 1;     \
static volatile long3 prefix##_l_3 = 1;     \
static volatile long4 prefix##_l_4 = 1;     \
static volatile ulong prefix##_ul_1 = 1;    \
static volatile ulong2 prefix##_ul_2 = 1;   \
static volatile ulong3 prefix##_ul_3 = 1;   \
static volatile ulong4 prefix##_ul_4 = 1;   \

DECL_VAR_SET(res)
DECL_VAR_SET(src1)
DECL_VAR_SET(src2)
DECL_VAR_SET(src3)


// Testing macros

#define RUN_BENCH(line, op)                         \
    for (int i = priming_runs - 1; i >= 0; --i) {   \
        line;                                       \
    }                                               \
    bench_time = rsUptimeMillis();                  \
    for (int i = timing_runs - 1; i >= 0; --i) {    \
        line;                                       \
    }                                               \
    bench_time = rsUptimeMillis() - bench_time;     \
    rsDebug("    " op " took ns", (float)bench_time * inv_timing_runs);

#define BENCH_BASIC_OP_TYPE(op, type)                                                               \
    RUN_BENCH(res_##type##_1 = src1_##type##_1 op src2_##type##_1, #type "1 " #op " " #type "1")    \
    RUN_BENCH(res_##type##_2 = src1_##type##_2 op src2_##type##_2, #type "2 " #op " " #type "2")    \
    RUN_BENCH(res_##type##_3 = src1_##type##_3 op src2_##type##_3, #type "3 " #op " " #type "3")    \
    RUN_BENCH(res_##type##_4 = src1_##type##_4 op src2_##type##_4, #type "4 " #op " " #type "4")    \

#define BENCH_BASIC_INT_OP(op)                                  \
    rsDebug("Testing basic operation " #op, 0);                 \
    BENCH_BASIC_OP_TYPE(op, c)                                  \
    BENCH_BASIC_OP_TYPE(op, uc)                                 \
    BENCH_BASIC_OP_TYPE(op, s)                                  \
    BENCH_BASIC_OP_TYPE(op, us)                                 \
    BENCH_BASIC_OP_TYPE(op, i)                                  \
    BENCH_BASIC_OP_TYPE(op, ui)                                 \
    RUN_BENCH(res_l_1 = src1_l_1 op src2_l_1, "l1 " #op " l1")  \
    RUN_BENCH(res_ul_1 = src1_ul_1 op src2_ul_1, "ul1 " #op " ul1")

#define BENCH_BASIC_OP(op)      \
    BENCH_BASIC_INT_OP(op)      \
    BENCH_BASIC_OP_TYPE(op, f)

#define BENCH_CVT(to, from, type)                                                                           \
    rsDebug("Testing convert from " #from " to " #to, 0);                                                   \
    RUN_BENCH(res_##to##_1 = (type)src1_##from##_1, "(" #to ")" #from)                                      \
    RUN_BENCH(res_##to##_2 = convert_##type##2(src1_##from##_2), #to "2 convert_" #type "2(" #from "2)")    \
    RUN_BENCH(res_##to##_3 = convert_##type##3(src1_##from##_3), #to "3 convert_" #type "3(" #from "3)")    \
    RUN_BENCH(res_##to##_4 = convert_##type##4(src1_##from##_4), #to "4 convert_" #type "4(" #from "4)")

#define BENCH_CVT_MATRIX(to, type)  \
    BENCH_CVT(to, c, type);         \
    BENCH_CVT(to, uc, type);        \
    BENCH_CVT(to, s, type);         \
    BENCH_CVT(to, us, type);        \
    BENCH_CVT(to, i, type);         \
    BENCH_CVT(to, ui, type);        \
    BENCH_CVT(to, f, type);         \

#define BENCH_XN_FUNC_YN(typeout, fnc, typein)                                                  \
    RUN_BENCH(res_##typeout##_1 = fnc(src1_##typein##_1);, #typeout "1 " #fnc "(" #typein "1)") \
    RUN_BENCH(res_##typeout##_2 = fnc(src1_##typein##_2);, #typeout "2 " #fnc "(" #typein "2)") \
    RUN_BENCH(res_##typeout##_3 = fnc(src1_##typein##_3);, #typeout "3 " #fnc "(" #typein "3)") \
    RUN_BENCH(res_##typeout##_4 = fnc(src1_##typein##_4);, #typeout "4 " #fnc "(" #typein "4)")

#define BENCH_XN_FUNC_XN_XN(type, fnc)                                                                              \
    RUN_BENCH(res_##type##_1 = fnc(src1_##type##_1, src2_##type##_1), #type "1 " #fnc "(" #type "1, " #type "1)")   \
    RUN_BENCH(res_##type##_2 = fnc(src1_##type##_2, src2_##type##_2), #type "2 " #fnc "(" #type "2, " #type "2)")   \
    RUN_BENCH(res_##type##_3 = fnc(src1_##type##_3, src2_##type##_3), #type "3 " #fnc "(" #type "3, " #type "3)")   \
    RUN_BENCH(res_##type##_4 = fnc(src1_##type##_4, src2_##type##_4), #type "4 " #fnc "(" #type "4, " #type "4)")   \

#define BENCH_X_FUNC_X_X_X(type, fnc)   \
    RUN_BENCH(res_##type##_1 = fnc(src1_##type##_1, src2_##type##_1, src3_##type##_1), #type "1 " #fnc "(" #type "1, " #type "1, " #type "1)")

#define BENCH_IN_FUNC_IN(fnc)       \
    rsDebug("Testing " #fnc, 0);    \
    BENCH_XN_FUNC_YN(uc, fnc, uc)   \
    BENCH_XN_FUNC_YN(c, fnc, c)     \
    BENCH_XN_FUNC_YN(us, fnc, us)   \
    BENCH_XN_FUNC_YN(s, fnc, s)     \
    BENCH_XN_FUNC_YN(ui, fnc, ui)   \
    BENCH_XN_FUNC_YN(i, fnc, i)

#define BENCH_UIN_FUNC_IN(fnc)      \
    rsDebug("Testing " #fnc, 0);    \
    BENCH_XN_FUNC_YN(uc, fnc, c)    \
    BENCH_XN_FUNC_YN(us, fnc, s)    \
    BENCH_XN_FUNC_YN(ui, fnc, i)    \

#define BENCH_IN_FUNC_IN_IN(fnc)    \
    rsDebug("Testing " #fnc, 0);    \
    BENCH_XN_FUNC_XN_XN(uc, fnc)    \
    BENCH_XN_FUNC_XN_XN(c, fnc)     \
    BENCH_XN_FUNC_XN_XN(us, fnc)    \
    BENCH_XN_FUNC_XN_XN(s, fnc)     \
    BENCH_XN_FUNC_XN_XN(ui, fnc)    \
    BENCH_XN_FUNC_XN_XN(i, fnc)

#define BENCH_I_FUNC_I_I_I(fnc)     \
    rsDebug("Testing " #fnc, 0);    \
    BENCH_X_FUNC_X_X_X(uc, fnc)     \
    BENCH_X_FUNC_X_X_X(c, fnc)      \
    BENCH_X_FUNC_X_X_X(us, fnc)     \
    BENCH_X_FUNC_X_X_X(s, fnc)      \
    BENCH_X_FUNC_X_X_X(ui, fnc)     \
    BENCH_X_FUNC_X_X_X(i, fnc)

#define BENCH_FN_FUNC_FN(fnc)                               \
    rsDebug("Testing " #fnc, 0);                            \
    RUN_BENCH(res_f_1 = fnc(src1_f_1), "f1 " #fnc "(f1)")   \
    RUN_BENCH(res_f_2 = fnc(src1_f_2), "f2 " #fnc "(f2)")   \
    RUN_BENCH(res_f_3 = fnc(src1_f_3), "f3 " #fnc "(f3)")   \
    RUN_BENCH(res_f_4 = fnc(src1_f_4), "f4 " #fnc "(f4)")

#define BENCH_FN_FUNC_FN_PFN(fnc)                                                   \
    rsDebug("Testing " #fnc, 0);                                                    \
    RUN_BENCH(res_f_1 = fnc(src1_f_1, (float*) &src2_f_1), "f1 " #fnc "(f1, f1*)")  \
    RUN_BENCH(res_f_2 = fnc(src1_f_2, (float2*) &src2_f_2), "f2 " #fnc "(f2, f2*)") \
    RUN_BENCH(res_f_3 = fnc(src1_f_3, (float3*) &src2_f_3), "f3 " #fnc "(f3, f3*)") \
    RUN_BENCH(res_f_4 = fnc(src1_f_4, (float4*) &src2_f_4), "f4 " #fnc "(f4, f4*)")

#define BENCH_FN_FUNC_FN_FN(fnc)                                        \
    rsDebug("Testing " #fnc, 0);                                        \
    RUN_BENCH(res_f_1 = fnc(src1_f_1, src2_f_1), "f1 " #fnc "(f1, f1)") \
    RUN_BENCH(res_f_2 = fnc(src1_f_2, src2_f_2), "f2 " #fnc "(f2, f2)") \
    RUN_BENCH(res_f_3 = fnc(src1_f_3, src2_f_3), "f3 " #fnc "(f3, f3)") \
    RUN_BENCH(res_f_4 = fnc(src1_f_4, src2_f_4), "f4 " #fnc "(f4, f4)")

#define BENCH_F34_FUNC_F34_F34(fnc)                                     \
    rsDebug("Testing " #fnc, 0);                                        \
    RUN_BENCH(res_f_3 = fnc(src1_f_3, src2_f_3), "f3 " #fnc "(f3, f3)") \
    RUN_BENCH(res_f_4 = fnc(src1_f_4, src2_f_4), "f4 " #fnc "(f4, f4)")

#define BENCH_FN_FUNC_FN_F(fnc)                                         \
    rsDebug("Testing " #fnc, 0);                                        \
    RUN_BENCH(res_f_1 = fnc(src1_f_1, src2_f_1), "f1 " #fnc "(f1, f1)") \
    RUN_BENCH(res_f_2 = fnc(src1_f_2, src2_f_1), "f2 " #fnc "(f2, f1)") \
    RUN_BENCH(res_f_3 = fnc(src1_f_3, src2_f_1), "f3 " #fnc "(f3, f1)") \
    RUN_BENCH(res_f_4 = fnc(src1_f_4, src2_f_1), "f4 " #fnc "(f4, f1)")

#define BENCH_F_FUNC_FN(fnc)                                \
    rsDebug("Testing " #fnc, 0);                            \
    RUN_BENCH(res_f_1 = fnc(src1_f_1), "f1 " #fnc "(f1)")   \
    RUN_BENCH(res_f_1 = fnc(src1_f_2), "f1 " #fnc "(f2)")   \
    RUN_BENCH(res_f_1 = fnc(src1_f_3), "f1 " #fnc "(f3)")   \
    RUN_BENCH(res_f_1 = fnc(src1_f_4), "f1 " #fnc "(f4)")

#define BENCH_F_FUNC_FN_FN(fnc)                                         \
    rsDebug("Testing " #fnc, 0);                                        \
    RUN_BENCH(res_f_1 = fnc(src1_f_1, src2_f_1), "f1 " #fnc "(f1, f1)") \
    RUN_BENCH(res_f_1 = fnc(src1_f_2, src2_f_2), "f1 " #fnc "(f2, f2)") \
    RUN_BENCH(res_f_1 = fnc(src1_f_3, src2_f_3), "f1 " #fnc "(f3, f3)") \
    RUN_BENCH(res_f_1 = fnc(src1_f_4, src2_f_4), "f1 " #fnc "(f4, f4)")

#define BENCH_FN_FUNC_FN_IN(fnc)                                        \
    rsDebug("Testing " #fnc, 0);                                        \
    RUN_BENCH(res_f_1 = fnc(src1_f_1, src1_i_1), "f1 " #fnc "(f1, i1)") \
    RUN_BENCH(res_f_2 = fnc(src1_f_2, src1_i_2), "f2 " #fnc "(f2, i2)") \
    RUN_BENCH(res_f_3 = fnc(src1_f_3, src1_i_3), "f3 " #fnc "(f3, i3)") \
    RUN_BENCH(res_f_4 = fnc(src1_f_4, src1_i_4), "f4 " #fnc "(f4, i4)")

#define BENCH_FN_FUNC_FN_I(fnc)                                         \
    rsDebug("Testing " #fnc, 0);                                        \
    RUN_BENCH(res_f_1 = fnc(src1_f_1, src1_i_1), "f1 " #fnc "(f1, i1)") \
    RUN_BENCH(res_f_2 = fnc(src1_f_2, src1_i_1), "f2 " #fnc "(f2, i1)") \
    RUN_BENCH(res_f_3 = fnc(src1_f_3, src1_i_1), "f3 " #fnc "(f3, i1)") \
    RUN_BENCH(res_f_4 = fnc(src1_f_4, src1_i_1), "f4 " #fnc "(f4, i1)")

#define BENCH_FN_FUNC_FN_FN_FN(fnc)                                                     \
    rsDebug("Testing " #fnc, 0);                                                        \
    RUN_BENCH(res_f_1 = fnc(src1_f_1, src2_f_1, src3_f_1), "f1 " #fnc "(f1, f1, f1)")   \
    RUN_BENCH(res_f_2 = fnc(src1_f_2, src2_f_2, src3_f_2), "f2 " #fnc "(f2, f2, f2)")   \
    RUN_BENCH(res_f_3 = fnc(src1_f_3, src2_f_3, src3_f_3), "f3 " #fnc "(f3, f3, f3)")   \
    RUN_BENCH(res_f_4 = fnc(src1_f_4, src2_f_4, src3_f_4), "f4 " #fnc "(f4, f4, f4)")

#define BENCH_FN_FUNC_FN_FN_F(fnc)                                                      \
    rsDebug("Testing " #fnc, 0);                                                        \
    RUN_BENCH(res_f_1 = fnc(src1_f_1, src2_f_1, src3_f_1), "f1 " #fnc "(f1, f1, f1)")   \
    RUN_BENCH(res_f_2 = fnc(src1_f_2, src2_f_2, src3_f_1), "f2 " #fnc "(f2, f2, f1)")   \
    RUN_BENCH(res_f_3 = fnc(src1_f_3, src2_f_3, src3_f_1), "f3 " #fnc "(f3, f3, f1)")   \
    RUN_BENCH(res_f_4 = fnc(src1_f_4, src2_f_4, src3_f_1), "f4 " #fnc "(f4, f4, f1)")

#define BENCH_FN_FUNC_FN_PIN(fnc)                                                   \
    rsDebug("Testing " #fnc, 0);                                                    \
    RUN_BENCH(res_f_1 = fnc(src1_f_1, (int*) &src1_i_1), "f1 " #fnc "(f1, i1*)")    \
    RUN_BENCH(res_f_2 = fnc(src1_f_2, (int2*) &src1_i_2), "f2 " #fnc "(f2, i2*)")   \
    RUN_BENCH(res_f_3 = fnc(src1_f_3, (int3*) &src1_i_3), "f3 " #fnc "(f3, i3*)")   \
    RUN_BENCH(res_f_4 = fnc(src1_f_4, (int4*) &src1_i_4), "f4 " #fnc "(f4, i4*)")

#define BENCH_FN_FUNC_FN_FN_PIN(fnc)                                                            \
    rsDebug("Testing " #fnc, 0);                                                                \
    RUN_BENCH(res_f_1 = fnc(src1_f_1, src2_f_1, (int*) &src1_i_1), "f1 " #fnc "(f1, f1, i1*)")  \
    RUN_BENCH(res_f_2 = fnc(src1_f_2, src2_f_2, (int2*) &src1_i_2), "f2 " #fnc "(f2, f2, i2*)") \
    RUN_BENCH(res_f_3 = fnc(src1_f_3, src2_f_3, (int3*) &src1_i_3), "f3 " #fnc "(f3, f3, i3*)") \
    RUN_BENCH(res_f_4 = fnc(src1_f_4, src2_f_4, (int4*) &src1_i_4), "f4 " #fnc "(f4, f4, i4*)")

#define BENCH_IN_FUNC_FN(fnc)                               \
    rsDebug("Testing " #fnc, 0);                            \
    RUN_BENCH(res_i_1 = fnc(src1_f_1), "i1 " #fnc "(f1)")   \
    RUN_BENCH(res_i_2 = fnc(src1_f_2), "i2 " #fnc "(f2)")   \
    RUN_BENCH(res_i_3 = fnc(src1_f_3), "i3 " #fnc "(f3)")   \
    RUN_BENCH(res_i_4 = fnc(src1_f_4), "i4 " #fnc "(f4)")


// Testing functions

static void bench_basic_operators() {
    int i = 0;
    BENCH_BASIC_OP(+);
    BENCH_BASIC_OP(-);
    BENCH_BASIC_OP(*);
    BENCH_BASIC_OP(/);
    BENCH_BASIC_INT_OP(%);
    BENCH_BASIC_INT_OP(<<);
    BENCH_BASIC_INT_OP(>>);
}

static void bench_convert() {
    BENCH_CVT_MATRIX(c, char);
    BENCH_CVT_MATRIX(uc, uchar);
    BENCH_CVT_MATRIX(s, short);
    BENCH_CVT_MATRIX(us, ushort);
    BENCH_CVT_MATRIX(i, int);
    BENCH_CVT_MATRIX(ui, uint);
    BENCH_CVT_MATRIX(f, float);
}

static void bench_int_math() {
    BENCH_UIN_FUNC_IN(abs);
    BENCH_IN_FUNC_IN(clz);
    BENCH_IN_FUNC_IN_IN(min);
    BENCH_IN_FUNC_IN_IN(max);
    BENCH_I_FUNC_I_I_I(rsClamp);
}

static void bench_fp_math() {
    BENCH_FN_FUNC_FN(acos);
    BENCH_FN_FUNC_FN(acosh);
    BENCH_FN_FUNC_FN(acospi);
    BENCH_FN_FUNC_FN(asin);
    BENCH_FN_FUNC_FN(asinh);
    BENCH_FN_FUNC_FN(asinpi);
    BENCH_FN_FUNC_FN(atan);
    BENCH_FN_FUNC_FN_FN(atan2);
    BENCH_FN_FUNC_FN(atanh);
    BENCH_FN_FUNC_FN(atanpi);
    BENCH_FN_FUNC_FN_FN(atan2pi);
    BENCH_FN_FUNC_FN(cbrt);
    BENCH_FN_FUNC_FN(ceil);
    BENCH_FN_FUNC_FN_FN_FN(clamp);
    BENCH_FN_FUNC_FN_FN_F(clamp);
    BENCH_FN_FUNC_FN_FN(copysign);
    BENCH_FN_FUNC_FN(cos);
    BENCH_FN_FUNC_FN(cosh);
    BENCH_FN_FUNC_FN(cospi);
    BENCH_F34_FUNC_F34_F34(cross);
    BENCH_FN_FUNC_FN(degrees);
    BENCH_F_FUNC_FN_FN(distance);
    BENCH_F_FUNC_FN_FN(dot);
    BENCH_FN_FUNC_FN(erfc);
    BENCH_FN_FUNC_FN(erf);
    BENCH_FN_FUNC_FN(exp);
    BENCH_FN_FUNC_FN(exp2);
    BENCH_FN_FUNC_FN(exp10);
    BENCH_FN_FUNC_FN(expm1);
    BENCH_FN_FUNC_FN(fabs);
    BENCH_FN_FUNC_FN_FN(fdim);
    BENCH_FN_FUNC_FN(floor);
    BENCH_FN_FUNC_FN_FN_FN(fma);
    BENCH_FN_FUNC_FN_FN(fmax);
    BENCH_FN_FUNC_FN_F(fmax);
    BENCH_FN_FUNC_FN_FN(fmin);
    BENCH_FN_FUNC_FN_F(fmin);
    BENCH_FN_FUNC_FN_FN(fmod);
    BENCH_FN_FUNC_FN_PFN(fract);
    BENCH_FN_FUNC_FN_PIN(frexp);
    BENCH_FN_FUNC_FN_FN(hypot);
    BENCH_IN_FUNC_FN(ilogb);
    BENCH_FN_FUNC_FN_IN(ldexp);
    BENCH_FN_FUNC_FN_I(ldexp);
    BENCH_F_FUNC_FN(length);
    BENCH_FN_FUNC_FN(lgamma);
    BENCH_FN_FUNC_FN_PIN(lgamma);
    BENCH_FN_FUNC_FN(log);
    BENCH_FN_FUNC_FN(log2);
    BENCH_FN_FUNC_FN(log10);
    BENCH_FN_FUNC_FN(log1p);
    BENCH_FN_FUNC_FN(logb);
    BENCH_FN_FUNC_FN_FN_FN(mad);
    BENCH_FN_FUNC_FN_FN(max);
    BENCH_FN_FUNC_FN_F(max);
    BENCH_FN_FUNC_FN_FN(min);
    BENCH_FN_FUNC_FN_F(min);
    BENCH_FN_FUNC_FN_FN_FN(mix);
    BENCH_FN_FUNC_FN_FN_F(mix);
    BENCH_FN_FUNC_FN_PFN(modf);
    BENCH_FN_FUNC_FN_FN(nextafter);
    BENCH_FN_FUNC_FN(normalize);
    BENCH_FN_FUNC_FN_FN(pow);
    BENCH_FN_FUNC_FN_IN(pown);
    BENCH_FN_FUNC_FN_FN(powr);
    BENCH_FN_FUNC_FN(radians);
    BENCH_FN_FUNC_FN_FN(remainder);
    BENCH_FN_FUNC_FN_FN_PIN(remquo);
    BENCH_FN_FUNC_FN(rint);
    BENCH_FN_FUNC_FN_IN(rootn);
    BENCH_FN_FUNC_FN(round);
    BENCH_FN_FUNC_FN(rsqrt);
    BENCH_FN_FUNC_FN(sign);
    BENCH_FN_FUNC_FN(sin);
    BENCH_FN_FUNC_FN_PFN(sincos);
    BENCH_FN_FUNC_FN(sinh);
    BENCH_FN_FUNC_FN(sinpi);
    BENCH_FN_FUNC_FN(sqrt);
    BENCH_FN_FUNC_FN_FN(step);
    BENCH_FN_FUNC_FN_F(step);
    BENCH_FN_FUNC_FN(tan);
    BENCH_FN_FUNC_FN(tanh);
    BENCH_FN_FUNC_FN(tanpi);
    BENCH_FN_FUNC_FN(tgamma);
    BENCH_FN_FUNC_FN(trunc);
}

static void bench_approx_math() {
    BENCH_FN_FUNC_FN(half_recip);
    BENCH_FN_FUNC_FN(half_sqrt);
    BENCH_FN_FUNC_FN(half_rsqrt);
    BENCH_FN_FUNC_FN(fast_length);
    BENCH_FN_FUNC_FN_FN(fast_distance);
    BENCH_FN_FUNC_FN(fast_normalize);
}

void bench() {
    rsDebug("RS Compute Benchmark", 0);
    rsDebug("Current configuration:", 0);
    rsDebug("Priming runs", priming_runs);
    rsDebug("Timing runs", timing_runs);
    rsDebug("Beginning test", 0);
    inv_timing_runs = 1000000.f / (float)timing_runs;
    bench_basic_operators();
    bench_convert();
    bench_int_math();
    bench_fp_math();
    bench_approx_math();
}

