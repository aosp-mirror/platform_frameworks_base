/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.annotation.IntDef;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 *
 * ScriptIntrinsicBLAS class provides high performance RenderScript APIs to BLAS.
 *
 * The BLAS (Basic Linear Algebra Subprograms) are routines that provide standard
 * building blocks for performing basic vector and matrix operations.
 *
 * For detailed description of BLAS, please refer to http://www.netlib.org/blas/
 *
 * @deprecated Renderscript has been deprecated in API level 31. Please refer to the <a
 * href="https://developer.android.com/guide/topics/renderscript/migration-guide">migration
 * guide</a> for the proposed alternatives.
 **/
@Deprecated
public final class ScriptIntrinsicBLAS extends ScriptIntrinsic {
    private Allocation mLUT;

    private ScriptIntrinsicBLAS(long id, RenderScript rs) {
        super(id, rs);
    }

    private static final int RsBlas_sdsdot = 1;
    private static final int RsBlas_dsdot = 2;
    private static final int RsBlas_sdot = 3;
    private static final int RsBlas_ddot = 4;
    private static final int RsBlas_cdotu_sub = 5;
    private static final int RsBlas_cdotc_sub = 6;
    private static final int RsBlas_zdotu_sub = 7;
    private static final int RsBlas_zdotc_sub = 8;
    private static final int RsBlas_snrm2 = 9;
    private static final int RsBlas_sasum = 10;
    private static final int RsBlas_dnrm2 = 11;
    private static final int RsBlas_dasum = 12;
    private static final int RsBlas_scnrm2 = 13;
    private static final int RsBlas_scasum = 14;
    private static final int RsBlas_dznrm2 = 15;
    private static final int RsBlas_dzasum = 16;
    private static final int RsBlas_isamax = 17;
    private static final int RsBlas_idamax = 18;
    private static final int RsBlas_icamax = 19;
    private static final int RsBlas_izamax = 20;
    private static final int RsBlas_sswap = 21;
    private static final int RsBlas_scopy = 22;
    private static final int RsBlas_saxpy = 23;
    private static final int RsBlas_dswap = 24;
    private static final int RsBlas_dcopy = 25;
    private static final int RsBlas_daxpy = 26;
    private static final int RsBlas_cswap = 27;
    private static final int RsBlas_ccopy = 28;
    private static final int RsBlas_caxpy = 29;
    private static final int RsBlas_zswap = 30;
    private static final int RsBlas_zcopy = 31;
    private static final int RsBlas_zaxpy = 32;
    private static final int RsBlas_srotg = 33;
    private static final int RsBlas_srotmg = 34;
    private static final int RsBlas_srot = 35;
    private static final int RsBlas_srotm = 36;
    private static final int RsBlas_drotg = 37;
    private static final int RsBlas_drotmg = 38;
    private static final int RsBlas_drot = 39;
    private static final int RsBlas_drotm = 40;
    private static final int RsBlas_sscal = 41;
    private static final int RsBlas_dscal = 42;
    private static final int RsBlas_cscal = 43;
    private static final int RsBlas_zscal = 44;
    private static final int RsBlas_csscal = 45;
    private static final int RsBlas_zdscal = 46;
    private static final int RsBlas_sgemv = 47;
    private static final int RsBlas_sgbmv = 48;
    private static final int RsBlas_strmv = 49;
    private static final int RsBlas_stbmv = 50;
    private static final int RsBlas_stpmv = 51;
    private static final int RsBlas_strsv = 52;
    private static final int RsBlas_stbsv = 53;
    private static final int RsBlas_stpsv = 54;
    private static final int RsBlas_dgemv = 55;
    private static final int RsBlas_dgbmv = 56;
    private static final int RsBlas_dtrmv = 57;
    private static final int RsBlas_dtbmv = 58;
    private static final int RsBlas_dtpmv = 59;
    private static final int RsBlas_dtrsv = 60;
    private static final int RsBlas_dtbsv = 61;
    private static final int RsBlas_dtpsv = 62;
    private static final int RsBlas_cgemv = 63;
    private static final int RsBlas_cgbmv = 64;
    private static final int RsBlas_ctrmv = 65;
    private static final int RsBlas_ctbmv = 66;
    private static final int RsBlas_ctpmv = 67;
    private static final int RsBlas_ctrsv = 68;
    private static final int RsBlas_ctbsv = 69;
    private static final int RsBlas_ctpsv = 70;
    private static final int RsBlas_zgemv = 71;
    private static final int RsBlas_zgbmv = 72;
    private static final int RsBlas_ztrmv = 73;
    private static final int RsBlas_ztbmv = 74;
    private static final int RsBlas_ztpmv = 75;
    private static final int RsBlas_ztrsv = 76;
    private static final int RsBlas_ztbsv = 77;
    private static final int RsBlas_ztpsv = 78;
    private static final int RsBlas_ssymv = 79;
    private static final int RsBlas_ssbmv = 80;
    private static final int RsBlas_sspmv = 81;
    private static final int RsBlas_sger = 82;
    private static final int RsBlas_ssyr = 83;
    private static final int RsBlas_sspr = 84;
    private static final int RsBlas_ssyr2 = 85;
    private static final int RsBlas_sspr2 = 86;
    private static final int RsBlas_dsymv = 87;
    private static final int RsBlas_dsbmv = 88;
    private static final int RsBlas_dspmv = 89;
    private static final int RsBlas_dger = 90;
    private static final int RsBlas_dsyr = 91;
    private static final int RsBlas_dspr = 92;
    private static final int RsBlas_dsyr2 = 93;
    private static final int RsBlas_dspr2 = 94;
    private static final int RsBlas_chemv = 95;
    private static final int RsBlas_chbmv = 96;
    private static final int RsBlas_chpmv = 97;
    private static final int RsBlas_cgeru = 98;
    private static final int RsBlas_cgerc = 99;
    private static final int RsBlas_cher = 100;
    private static final int RsBlas_chpr = 101;
    private static final int RsBlas_cher2 = 102;
    private static final int RsBlas_chpr2 = 103;
    private static final int RsBlas_zhemv = 104;
    private static final int RsBlas_zhbmv = 105;
    private static final int RsBlas_zhpmv = 106;
    private static final int RsBlas_zgeru = 107;
    private static final int RsBlas_zgerc = 108;
    private static final int RsBlas_zher = 109;
    private static final int RsBlas_zhpr = 110;
    private static final int RsBlas_zher2 = 111;
    private static final int RsBlas_zhpr2 = 112;
    private static final int RsBlas_sgemm = 113;
    private static final int RsBlas_ssymm = 114;
    private static final int RsBlas_ssyrk = 115;
    private static final int RsBlas_ssyr2k = 116;
    private static final int RsBlas_strmm = 117;
    private static final int RsBlas_strsm = 118;
    private static final int RsBlas_dgemm = 119;
    private static final int RsBlas_dsymm = 120;
    private static final int RsBlas_dsyrk = 121;
    private static final int RsBlas_dsyr2k = 122;
    private static final int RsBlas_dtrmm = 123;
    private static final int RsBlas_dtrsm = 124;
    private static final int RsBlas_cgemm = 125;
    private static final int RsBlas_csymm = 126;
    private static final int RsBlas_csyrk = 127;
    private static final int RsBlas_csyr2k = 128;
    private static final int RsBlas_ctrmm = 129;
    private static final int RsBlas_ctrsm = 130;
    private static final int RsBlas_zgemm = 131;
    private static final int RsBlas_zsymm = 132;
    private static final int RsBlas_zsyrk = 133;
    private static final int RsBlas_zsyr2k = 134;
    private static final int RsBlas_ztrmm = 135;
    private static final int RsBlas_ztrsm = 136;
    private static final int RsBlas_chemm = 137;
    private static final int RsBlas_cherk = 138;
    private static final int RsBlas_cher2k = 139;
    private static final int RsBlas_zhemm = 140;
    private static final int RsBlas_zherk = 141;
    private static final int RsBlas_zher2k = 142;

    // BLAS extensions start here
    private static final int RsBlas_bnnm = 1000;

    /**
     * Create an intrinsic to access BLAS subroutines.
     *
     * @param rs The RenderScript context
     * @return ScriptIntrinsicBLAS
     */
    public static ScriptIntrinsicBLAS create(RenderScript rs) {
        long id = rs.nScriptIntrinsicCreate(13, Element.U32(rs).getID(rs));
        return new ScriptIntrinsicBLAS(id, rs);
    }

    /**
     * @hide
     */
    @IntDef({NO_TRANSPOSE, TRANSPOSE, CONJ_TRANSPOSE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Transpose {}

    /**
     * @hide
     */
    @IntDef({UPPER, LOWER})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Uplo {}

    /**
     * @hide
     */
    @IntDef({NON_UNIT, UNIT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Diag {}

    /**
     * @hide
     */
    @IntDef({LEFT, RIGHT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Side {}

    public static final int NO_TRANSPOSE = 111;
    public static final int TRANSPOSE = 112;
    public static final int CONJ_TRANSPOSE = 113;

    public static final int UPPER = 121;
    public static final int LOWER = 122;

    public static final int NON_UNIT = 131;
    public static final int UNIT = 132;

    public static final int LEFT = 141;
    public static final int RIGHT = 142;

    static void validateSide(@Side int Side) {
        if (Side != LEFT && Side != RIGHT) {
            throw new RSRuntimeException("Invalid side passed to BLAS");
        }
    }

    static void validateTranspose(@Transpose int Trans) {
        if (Trans != NO_TRANSPOSE && Trans != TRANSPOSE &&
            Trans != CONJ_TRANSPOSE) {
            throw new RSRuntimeException("Invalid transpose passed to BLAS");
        }
    }

    static void validateConjTranspose(@Transpose int Trans) {
        if (Trans != NO_TRANSPOSE &&
            Trans != CONJ_TRANSPOSE) {
            throw new RSRuntimeException("Invalid transpose passed to BLAS");
        }
    }

    static void validateDiag(@Diag int Diag) {
        if (Diag != NON_UNIT && Diag != UNIT) {
            throw new RSRuntimeException("Invalid diag passed to BLAS");
        }
    }

    static void validateUplo(@Uplo int Uplo) {
        if (Uplo != UPPER && Uplo != LOWER) {
            throw new RSRuntimeException("Invalid uplo passed to BLAS");
        }
    }


    /**
     * Level 2 BLAS
     */

    static void validateGEMV(Element e, int TransA, Allocation A, Allocation X, int incX, Allocation Y, int incY) {
        validateTranspose(TransA);
        int M = A.getType().getY();
        int N = A.getType().getX();
        if (!A.getType().getElement().isCompatible(e) ||
            !X.getType().getElement().isCompatible(e) ||
            !Y.getType().getElement().isCompatible(e)) {
            throw new RSRuntimeException("Called BLAS with wrong Element type");
        }
        if (X.getType().getY() > 1 || Y.getType().getY() > 1) {
            throw new RSRuntimeException("BLAS vectors must have Y dimension of 0 or 1");
        }

        if (incX <= 0 || incY <= 0) {
            throw new RSRuntimeException("Vector increments must be greater than 0");
        }
        int expectedXDim = -1, expectedYDim = -1;
        if (TransA == NO_TRANSPOSE) {
            expectedXDim = 1 + (N - 1) * incX;
            expectedYDim = 1 + (M - 1) * incY;
        } else {
            expectedXDim = 1 + (M - 1) * incX;
            expectedYDim = 1 + (N - 1) * incY;
        }
        if (X.getType().getX() != expectedXDim ||
            Y.getType().getX() != expectedYDim) {
            throw new RSRuntimeException("Incorrect vector dimensions for GEMV");
        }
    }

    /**
     * SGEMV performs one of the matrix-vector operations
     * y := alpha*A*x + beta*y   or   y := alpha*A**T*x + beta*y
     *
     * Details: http://www.netlib.org/lapack/explore-html/db/d58/sgemv_8f.html
     *
     * @param TransA The type of transpose applied to matrix A.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param beta The scalar beta.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F32}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     */
    public void SGEMV(@Transpose int TransA, float alpha, Allocation A, Allocation X, int incX, float beta, Allocation Y, int incY) {
        validateGEMV(Element.F32(mRS), TransA, A, X, incX, Y, incY);
        int M = A.getType().getY();
        int N = A.getType().getX();
        mRS.nScriptIntrinsicBLAS_Single(getID(mRS), RsBlas_sgemv, TransA, 0, 0, 0, 0, M, N, 0, alpha, A.getID(mRS), X.getID(mRS), beta, Y.getID(mRS), incX, incY, 0, 0);
    }

    /**
     * DGEMV performs one of the matrix-vector operations
     * y := alpha*A*x + beta*y   or   y := alpha*A**T*x + beta*y
     *
     * Details: http://www.netlib.org/lapack/explore-html/dc/da8/dgemv_8f.html
     *
     * @param TransA The type of transpose applied to matrix A.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param beta The scalar beta.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F64}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     */
    public void DGEMV(@Transpose int TransA, double alpha, Allocation A, Allocation X, int incX, double beta, Allocation Y, int incY) {
        validateGEMV(Element.F64(mRS), TransA, A, X, incX, Y, incY);
        int M = A.getType().getY();
        int N = A.getType().getX();
        mRS.nScriptIntrinsicBLAS_Double(getID(mRS), RsBlas_dgemv, TransA, 0, 0, 0, 0, M, N, 0, alpha, A.getID(mRS), X.getID(mRS), beta, Y.getID(mRS), incX, incY, 0, 0);
    }

    /**
     * CGEMV performs one of the matrix-vector operations
     * y := alpha*A*x + beta*y   or   y := alpha*A**T*x + beta*y   or   y := alpha*A**H*x + beta*y
     *
     * Details: http://www.netlib.org/lapack/explore-html/d4/d8a/cgemv_8f.html
     *
     * @param TransA The type of transpose applied to matrix A.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32_2}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param beta The scalar beta.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F32_2}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     */
    public void CGEMV(@Transpose int TransA, Float2 alpha, Allocation A, Allocation X, int incX, Float2 beta, Allocation Y, int incY) {
        validateGEMV(Element.F32_2(mRS), TransA, A, X, incX, Y, incY);
        int M = A.getType().getY();
        int N = A.getType().getX();
        mRS.nScriptIntrinsicBLAS_Complex(getID(mRS), RsBlas_cgemv, TransA, 0, 0, 0, 0, M, N, 0, alpha.x, alpha.y, A.getID(mRS), X.getID(mRS), beta.x, beta.y, Y.getID(mRS), incX, incY, 0, 0);
    }

    /**
     * ZGEMV performs one of the matrix-vector operations
     * y := alpha*A*x + beta*y   or   y := alpha*A**T*x + beta*y   or   y := alpha*A**H*x + beta*y
     *
     * Details: http://www.netlib.org/lapack/explore-html/db/d40/zgemv_8f.html
     *
     * @param TransA The type of transpose applied to matrix A.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64_2}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param beta The scalar beta.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F64_2}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     */
    public void ZGEMV(@Transpose int TransA, Double2 alpha, Allocation A, Allocation X, int incX, Double2 beta, Allocation Y, int incY) {
        validateGEMV(Element.F64_2(mRS), TransA, A, X, incX, Y, incY);
        int M = A.getType().getY();
        int N = A.getType().getX();
        mRS.nScriptIntrinsicBLAS_Z(getID(mRS), RsBlas_zgemv, TransA, 0, 0, 0, 0, M, N, 0, alpha.x, alpha.y, A.getID(mRS), X.getID(mRS), beta.x, beta.y, Y.getID(mRS), incX, incY, 0, 0);
    }

    /**
     * SGBMV performs one of the matrix-vector operations
     * y := alpha*A*x + beta*y   or   y := alpha*A**T*x + beta*y
     *
     * Details: http://www.netlib.org/lapack/explore-html/d6/d46/sgbmv_8f.html
     *
     * Note: For a M*N matrix, the input Allocation should also be of size M*N (dimY = M, dimX = N),
     *       but only the region M*(KL+KU+1) will be referenced. The following subroutine can is an
     *       example showing how to convert the original matrix 'a' to row-based band matrix 'b'.
     *           for i in range(0, m):
     *              for j in range(max(0, i-kl), min(i+ku+1, n)):
     *                  b[i, j-i+kl] = a[i, j]
     *
     * @param TransA The type of transpose applied to matrix A.
     * @param KL The number of sub-diagonals of the matrix A.
     * @param KU The number of super-diagonals of the matrix A.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains the band matrix A, supported elements type {@link Element#F32}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param beta The scalar beta.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F32}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     */
    public void SGBMV(@Transpose int TransA, int KL, int KU, float alpha, Allocation A, Allocation X, int incX, float beta, Allocation Y, int incY) {
        // GBMV has the same validation requirements as GEMV + KL and KU >= 0
        validateGEMV(Element.F32(mRS), TransA, A, X, incX, Y, incY);
        if (KL < 0 || KU < 0) {
            throw new RSRuntimeException("KL and KU must be greater than or equal to 0");
        }
        int M = A.getType().getY();
        int N = A.getType().getX();
        mRS.nScriptIntrinsicBLAS_Single(getID(mRS), RsBlas_sgbmv, TransA, 0, 0, 0, 0, M, N, 0, alpha, A.getID(mRS), X.getID(mRS), beta, Y.getID(mRS), incX, incY, KL, KU);
    }

    /**
     * DGBMV performs one of the matrix-vector operations
     * y := alpha*A*x + beta*y   or   y := alpha*A**T*x + beta*y
     *
     * Details: http://www.netlib.org/lapack/explore-html/d2/d3f/dgbmv_8f.html
     *
     * Note: For a M*N matrix, the input Allocation should also be of size M*N (dimY = M, dimX = N),
     *       but only the region M*(KL+KU+1) will be referenced. The following subroutine can is an
     *       example showing how to convert the original matrix 'a' to row-based band matrix 'b'.
     *           for i in range(0, m):
     *              for j in range(max(0, i-kl), min(i+ku+1, n)):
     *                  b[i, j-i+kl] = a[i, j]
     *
     * @param TransA The type of transpose applied to matrix A.
     * @param KL The number of sub-diagonals of the matrix A.
     * @param KU The number of super-diagonals of the matrix A.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains the band matrix A, supported elements type {@link Element#F64}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param beta The scalar beta.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F64}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     */
    public void DGBMV(@Transpose int TransA, int KL, int KU, double alpha, Allocation A, Allocation X, int incX, double beta, Allocation Y, int incY) {
        // GBMV has the same validation requirements as GEMV + KL and KU >= 0
        validateGEMV(Element.F64(mRS), TransA, A, X, incX, Y, incY);
        if (KL < 0 || KU < 0) {
            throw new RSRuntimeException("KL and KU must be greater than or equal to 0");
        }
        int M = A.getType().getY();
        int N = A.getType().getX();
        mRS.nScriptIntrinsicBLAS_Double(getID(mRS), RsBlas_dgbmv, TransA, 0, 0, 0, 0, M, N, 0, alpha, A.getID(mRS), X.getID(mRS), beta, Y.getID(mRS), incX, incY, KL, KU);
    }

    /**
     * CGBMV performs one of the matrix-vector operations
     * y := alpha*A*x + beta*y   or   y := alpha*A**T*x + beta*y   or   y := alpha*A**H*x + beta*y
     *
     * Details: http://www.netlib.org/lapack/explore-html/d0/d75/cgbmv_8f.html
     *
     * Note: For a M*N matrix, the input Allocation should also be of size M*N (dimY = M, dimX = N),
     *       but only the region M*(KL+KU+1) will be referenced. The following subroutine can is an
     *       example showing how to convert the original matrix 'a' to row-based band matrix 'b'.
     *           for i in range(0, m):
     *              for j in range(max(0, i-kl), min(i+ku+1, n)):
     *                  b[i, j-i+kl] = a[i, j]
     *
     * @param TransA The type of transpose applied to matrix A.
     * @param KL The number of sub-diagonals of the matrix A.
     * @param KU The number of super-diagonals of the matrix A.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains the band matrix A, supported elements type {@link Element#F32_2}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param beta The scalar beta.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F32_2}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     */
    public void CGBMV(@Transpose int TransA, int KL, int KU, Float2 alpha, Allocation A, Allocation X, int incX, Float2 beta, Allocation Y, int incY) {
        // GBMV has the same validation requirements as GEMV + KL and KU >= 0
        validateGEMV(Element.F32_2(mRS), TransA, A, X, incX, Y, incY);
        if (KL < 0 || KU < 0) {
            throw new RSRuntimeException("KL and KU must be greater than or equal to 0");
        }
        int M = A.getType().getY();
        int N = A.getType().getX();
        mRS.nScriptIntrinsicBLAS_Complex(getID(mRS), RsBlas_cgbmv, TransA, 0, 0, 0, 0, M, N, 0, alpha.x, alpha.y, A.getID(mRS), X.getID(mRS), beta.x, beta.y, Y.getID(mRS), incX, incY, KL, KU);
    }

    /**
     * ZGBMV performs one of the matrix-vector operations
     * y := alpha*A*x + beta*y   or   y := alpha*A**T*x + beta*y   or   y := alpha*A**H*x + beta*y
     *
     * Details: http://www.netlib.org/lapack/explore-html/d9/d46/zgbmv_8f.html
     *
     * Note: For a M*N matrix, the input Allocation should also be of size M*N (dimY = M, dimX = N),
     *       but only the region M*(KL+KU+1) will be referenced. The following subroutine can is an
     *       example showing how to convert the original matrix 'a' to row-based band matrix 'b'.
     *           for i in range(0, m):
     *              for j in range(max(0, i-kl), min(i+ku+1, n)):
     *                  b[i, j-i+kl] = a[i, j]
     *
     * @param TransA The type of transpose applied to matrix A.
     * @param KL The number of sub-diagonals of the matrix A.
     * @param KU The number of super-diagonals of the matrix A.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains the band matrix A, supported elements type {@link Element#F64_2}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param beta The scalar beta.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F64_2}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     */
    public void ZGBMV(@Transpose int TransA, int KL, int KU, Double2 alpha, Allocation A, Allocation X, int incX, Double2 beta, Allocation Y, int incY) {
        // GBMV has the same validation requirements as GEMV + KL and KU >= 0
        validateGEMV(Element.F64_2(mRS), TransA, A, X, incX, Y, incY);
        if (KL < 0 || KU < 0) {
            throw new RSRuntimeException("KL and KU must be greater than or equal to 0");
        }
        int M = A.getType().getY();
        int N = A.getType().getX();
        mRS.nScriptIntrinsicBLAS_Z(getID(mRS), RsBlas_zgbmv, TransA, 0, 0, 0, 0, M, N, 0, alpha.x, alpha.y, A.getID(mRS), X.getID(mRS), beta.x, beta.y, Y.getID(mRS), incX, incY, KL, KU);
    }

    static void validateTRMV(Element e, @Uplo int Uplo, @Transpose int TransA, @Diag int Diag, Allocation A, Allocation X, int incX) {
        validateTranspose(TransA);
        validateUplo(Uplo);
        validateDiag(Diag);
        int N = A.getType().getY();
        if (A.getType().getX() != N) {
            throw new RSRuntimeException("A must be a square matrix for TRMV");
        }
        if (!A.getType().getElement().isCompatible(e) ||
            !X.getType().getElement().isCompatible(e)) {
            throw new RSRuntimeException("Called BLAS with wrong Element type");
        }
        if (X.getType().getY() > 1) {
            throw new RSRuntimeException("BLAS vectors must have Y dimension of 0 or 1");
        }

        if (incX <= 0) {
            throw new RSRuntimeException("Vector increments must be greater than 0");
        }
        int expectedXDim = 1 + (N - 1) * incX;
        if (X.getType().getX() != expectedXDim) {
            throw new RSRuntimeException("Incorrect vector dimensions for TRMV");
        }
    }

    static int validateTPMV(Element e, @Uplo int Uplo, @Transpose int TransA, @Diag int Diag, Allocation Ap, Allocation X, int incX) {
        validateTranspose(TransA);
        validateUplo(Uplo);
        validateDiag(Diag);
        if (!Ap.getType().getElement().isCompatible(e) ||
            !X.getType().getElement().isCompatible(e)) {
            throw new RSRuntimeException("Called BLAS with wrong Element type");
        }
        if (X.getType().getY() > 1) {
            throw new RSRuntimeException("BLAS vectors must have Y dimension of 0 or 1");
        }

        if (Ap.getType().getY() > 1) {
            throw new RSRuntimeException("Ap must have a Y dimension of 0 or 1");
        }

        int N = (int)Math.sqrt((double)Ap.getType().getX() * 2);
        //is it really doing anything?
        if (Ap.getType().getX() != ((N * (N+1)) / 2)) {
            throw new RSRuntimeException("Invalid dimension for Ap");
        }
        if (incX <= 0) {
            throw new RSRuntimeException("Vector increments must be greater than 0");
        }
        int expectedXDim = 1 + (N - 1) * incX;
        if (X.getType().getX() != expectedXDim) {
            throw new RSRuntimeException("Incorrect vector dimensions for TPMV");
        }

        return N;
    }

    /**
     * STRMV performs one of the matrix-vector operations
     * x := A*x   or   x := A**T*x
     *
     * Details: http://www.netlib.org/lapack/explore-html/de/d45/strmv_8f.html
     *
     * @param Uplo Specifies whether the matrix is an upper or lower triangular matrix.
     * @param TransA The type of transpose applied to matrix A.
     * @param Diag Specifies whether or not A is unit triangular.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     */
    public void STRMV(@Uplo int Uplo, @Transpose int TransA, @Diag int Diag, Allocation A, Allocation X, int incX) {
        validateTRMV(Element.F32(mRS), Uplo, TransA, Diag, A, X, incX);
        int N = A.getType().getY();
        mRS.nScriptIntrinsicBLAS_Single(getID(mRS), RsBlas_strmv, TransA, 0, 0, Uplo, Diag, 0, N, 0, 0, A.getID(mRS), X.getID(mRS), 0, 0, incX, 0, 0, 0);
    }

    /**
     * DTRMV performs one of the matrix-vector operations
     * x := A*x   or   x := A**T*x
     *
     * Details: http://www.netlib.org/lapack/explore-html/dc/d7e/dtrmv_8f.html
     *
     * @param Uplo Specifies whether the matrix is an upper or lower triangular matrix.
     * @param TransA The type of transpose applied to matrix A.
     * @param Diag Specifies whether or not A is unit triangular.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     */
    public void DTRMV(@Uplo int Uplo, @Transpose int TransA, @Diag int Diag, Allocation A, Allocation X, int incX) {
        validateTRMV(Element.F64(mRS), Uplo, TransA, Diag, A, X, incX);
        int N = A.getType().getY();
        mRS.nScriptIntrinsicBLAS_Double(getID(mRS), RsBlas_dtrmv, TransA, 0, 0, Uplo, Diag, 0, N, 0, 0, A.getID(mRS), X.getID(mRS), 0, 0, incX, 0, 0, 0);
    }

    /**
     * CTRMV performs one of the matrix-vector operations
     * x := A*x   or   x := A**T*x   or   x := A**H*x
     *
     * Details: http://www.netlib.org/lapack/explore-html/df/d78/ctrmv_8f.html
     *
     * @param Uplo Specifies whether the matrix is an upper or lower triangular matrix.
     * @param TransA The type of transpose applied to matrix A.
     * @param Diag Specifies whether or not A is unit triangular.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32_2}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     */
    public void CTRMV(@Uplo int Uplo, @Transpose int TransA, @Diag int Diag, Allocation A, Allocation X, int incX) {
        validateTRMV(Element.F32_2(mRS), Uplo, TransA, Diag, A, X, incX);
        int N = A.getType().getY();
        mRS.nScriptIntrinsicBLAS_Complex(getID(mRS), RsBlas_ctrmv, TransA, 0, 0, Uplo, Diag, 0, N, 0, 0, 0, A.getID(mRS), X.getID(mRS), 0, 0, 0, incX, 0, 0, 0);
    }

    /**
     * ZTRMV performs one of the matrix-vector operations
     * x := A*x   or   x := A**T*x   or   x := A**H*x
     *
     * Details: http://www.netlib.org/lapack/explore-html/d0/dd1/ztrmv_8f.html
     *
     * @param Uplo Specifies whether the matrix is an upper or lower triangular matrix.
     * @param TransA The type of transpose applied to matrix A.
     * @param Diag Specifies whether or not A is unit triangular.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64_2}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     */
    public void ZTRMV(@Uplo int Uplo, @Transpose int TransA, @Diag int Diag, Allocation A, Allocation X, int incX) {
        validateTRMV(Element.F64_2(mRS), Uplo, TransA, Diag, A, X, incX);
        int N = A.getType().getY();
        mRS.nScriptIntrinsicBLAS_Z(getID(mRS), RsBlas_ztrmv, TransA, 0, 0, Uplo, Diag, 0, N, 0, 0, 0, A.getID(mRS), X.getID(mRS), 0, 0, 0, incX, 0, 0, 0);
    }

    /**
     * STBMV performs one of the matrix-vector operations
     * x := A*x   or   x := A**T*x
     *
     * Details: http://www.netlib.org/lapack/explore-html/d6/d7d/stbmv_8f.html
     *
     * Note: For a N*N matrix, the input Allocation should also be of size N*N (dimY = N, dimX = N),
     *       but only the region N*(K+1) will be referenced. The following subroutine can is an
     *       example showing how to convert a UPPER trianglar matrix 'a' to row-based band matrix 'b'.
     *           for i in range(0, n):
     *              for j in range(i, min(i+k+1, n)):
     *                  b[i, j-i] = a[i, j]
     *
     * @param Uplo Specifies whether the matrix is an upper or lower triangular matrix.
     * @param TransA The type of transpose applied to matrix A.
     * @param Diag Specifies whether or not A is unit triangular.
     * @param K The number of off-diagonals of the matrix A
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     */
    public void STBMV(@Uplo int Uplo, @Transpose int TransA, @Diag int Diag,  int K, Allocation A,  Allocation X,  int incX) {
        // TBMV has the same requirements as TRMV + K >= 0
        if (K < 0) {
            throw new RSRuntimeException("K must be greater than or equal to 0");
        }
        validateTRMV(Element.F32(mRS), Uplo, TransA, Diag, A, X, incX);
        int N = A.getType().getY();
        mRS.nScriptIntrinsicBLAS_Single(getID(mRS), RsBlas_stbmv, TransA, 0, 0, Uplo, Diag, 0, N, K, 0, A.getID(mRS), X.getID(mRS), 0, 0, incX, 0, 0, 0);
    }

    /**
     * DTBMV performs one of the matrix-vector operations
     * x := A*x   or   x := A**T*x
     *
     * Details: http://www.netlib.org/lapack/explore-html/df/d29/dtbmv_8f.html
     *
     * Note: For a N*N matrix, the input Allocation should also be of size N*N (dimY = N, dimX = N),
     *       but only the region N*(K+1) will be referenced. The following subroutine can is an
     *       example showing how to convert a UPPER trianglar matrix 'a' to row-based band matrix 'b'.
     *           for i in range(0, n):
     *              for j in range(i, min(i+k+1, n)):
     *                  b[i, j-i] = a[i, j]
     *
     * @param Uplo Specifies whether the matrix is an upper or lower triangular matrix.
     * @param TransA The type of transpose applied to matrix A.
     * @param Diag Specifies whether or not A is unit triangular.
     * @param K The number of off-diagonals of the matrix A
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     */
    public void DTBMV(@Uplo int Uplo, @Transpose int TransA, @Diag int Diag,  int K, Allocation A,  Allocation X,  int incX) {
        // TBMV has the same requirements as TRMV + K >= 0
        if (K < 0) {
            throw new RSRuntimeException("K must be greater than or equal to 0");
        }
        validateTRMV(Element.F64(mRS), Uplo, TransA, Diag, A, X, incX);
        int N = A.getType().getY();
        mRS.nScriptIntrinsicBLAS_Double(getID(mRS), RsBlas_dtbmv, TransA, 0, 0, Uplo, Diag, 0, N, K, 0, A.getID(mRS), X.getID(mRS), 0, 0, incX, 0, 0, 0);
    }

    /**
     * CTBMV performs one of the matrix-vector operations
     * x := A*x   or   x := A**T*x   or   x := A**H*x
     *
     * Details: http://www.netlib.org/lapack/explore-html/d3/dcd/ctbmv_8f.html
     *
     * Note: For a N*N matrix, the input Allocation should also be of size N*N (dimY = N, dimX = N),
     *       but only the region N*(K+1) will be referenced. The following subroutine can is an
     *       example showing how to convert a UPPER trianglar matrix 'a' to row-based band matrix 'b'.
     *           for i in range(0, n):
     *              for j in range(i, min(i+k+1, n)):
     *                  b[i, j-i] = a[i, j]
     *
     * @param Uplo Specifies whether the matrix is an upper or lower triangular matrix.
     * @param TransA The type of transpose applied to matrix A.
     * @param Diag Specifies whether or not A is unit triangular.
     * @param K The number of off-diagonals of the matrix A
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32_2}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     */
    public void CTBMV(@Uplo int Uplo, @Transpose int TransA, @Diag int Diag,  int K, Allocation A,  Allocation X,  int incX) {
        // TBMV has the same requirements as TRMV + K >= 0
        if (K < 0) {
            throw new RSRuntimeException("K must be greater than or equal to 0");
        }
        validateTRMV(Element.F32_2(mRS), Uplo, TransA, Diag, A, X, incX);
        int N = A.getType().getY();
        mRS.nScriptIntrinsicBLAS_Complex(getID(mRS), RsBlas_ctbmv, TransA, 0, 0, Uplo, Diag, 0, N, K, 0, 0, A.getID(mRS), X.getID(mRS), 0, 0, 0, incX, 0, 0, 0);
    }

    /**
     * ZTBMV performs one of the matrix-vector operations
     * x := A*x   or   x := A**T*x   or   x := A**H*x
     *
     * Details: http://www.netlib.org/lapack/explore-html/d3/d39/ztbmv_8f.html
     *
     * Note: For a N*N matrix, the input Allocation should also be of size N*N (dimY = N, dimX = N),
     *       but only the region N*(K+1) will be referenced. The following subroutine can is an
     *       example showing how to convert a UPPER trianglar matrix 'a' to row-based band matrix 'b'.
     *           for i in range(0, n):
     *              for j in range(i, min(i+k+1, n)):
     *                  b[i, j-i] = a[i, j]
     *
     * @param Uplo Specifies whether the matrix is an upper or lower triangular matrix.
     * @param TransA The type of transpose applied to matrix A.
     * @param Diag Specifies whether or not A is unit triangular.
     * @param K The number of off-diagonals of the matrix A
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64_2}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     */
    public void ZTBMV(@Uplo int Uplo, @Transpose int TransA, @Diag int Diag,  int K, Allocation A,  Allocation X,  int incX) {
        // TBMV has the same requirements as TRMV + K >= 0
        if (K < 0) {
            throw new RSRuntimeException("K must be greater than or equal to 0");
        }
        validateTRMV(Element.F64_2(mRS), Uplo, TransA, Diag, A, X, incX);
        int N = A.getType().getY();
        mRS.nScriptIntrinsicBLAS_Z(getID(mRS), RsBlas_ztbmv, TransA, 0, 0, Uplo, Diag, 0, N, K, 0, 0, A.getID(mRS), X.getID(mRS), 0, 0, 0, incX, 0, 0, 0);
    }

    /**
     * STPMV performs one of the matrix-vector operations
     * x := A*x   or   x := A**T*x
     *
     * Details: http://www.netlib.org/lapack/explore-html/db/db1/stpmv_8f.html
     *
     * Note: For a N*N matrix, the input Allocation should be a 1D allocation of size dimX = N*(N+1)/2,
     *       The following subroutine can is an example showing how to convert a UPPER trianglar matrix
     *       'a' to packed matrix 'b'.
     *           k = 0
     *           for i in range(0, n):
     *              for j in range(i, n):
     *                  b[k++] = a[i, j]
     *
     * @param Uplo Specifies whether the matrix is an upper or lower triangular matrix.
     * @param TransA The type of transpose applied to matrix A.
     * @param Diag Specifies whether or not A is unit triangular.
     * @param Ap The input allocation contains packed matrix A, supported elements type {@link Element#F32}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     */
    public void STPMV(@Uplo int Uplo, @Transpose int TransA, @Diag int Diag,  Allocation Ap,  Allocation X,  int incX) {
        int N = validateTPMV(Element.F32(mRS), Uplo, TransA, Diag, Ap, X, incX);
        mRS.nScriptIntrinsicBLAS_Single(getID(mRS), RsBlas_stpmv, TransA, 0, 0, Uplo, Diag, 0, N, 0, 0, Ap.getID(mRS), X.getID(mRS), 0, 0, incX, 0, 0, 0);
    }

    /**
     * DTPMV performs one of the matrix-vector operations
     * x := A*x   or   x := A**T*x
     *
     * Details: http://www.netlib.org/lapack/explore-html/dc/dcd/dtpmv_8f.html
     *
     * Note: For a N*N matrix, the input Allocation should be a 1D allocation of size dimX = N*(N+1)/2,
     *       The following subroutine can is an example showing how to convert a UPPER trianglar matrix
     *       'a' to packed matrix 'b'.
     *           k = 0
     *           for i in range(0, n):
     *              for j in range(i, n):
     *                  b[k++] = a[i, j]
     *
     * @param Uplo Specifies whether the matrix is an upper or lower triangular matrix.
     * @param TransA The type of transpose applied to matrix A.
     * @param Diag Specifies whether or not A is unit triangular.
     * @param Ap The input allocation contains packed matrix A, supported elements type {@link Element#F64}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     */
    public void DTPMV(@Uplo int Uplo, @Transpose int TransA, @Diag int Diag,  Allocation Ap,  Allocation X,  int incX) {
        int N = validateTPMV(Element.F64(mRS), Uplo, TransA, Diag, Ap, X, incX);
        mRS.nScriptIntrinsicBLAS_Double(getID(mRS), RsBlas_dtpmv, TransA, 0, 0, Uplo, Diag, 0, N, 0, 0, Ap.getID(mRS), X.getID(mRS), 0, 0, incX, 0, 0, 0);
    }

    /**
     * CTPMV performs one of the matrix-vector operations
     * x := A*x   or   x := A**T*x   or   x := A**H*x
     *
     * Details: http://www.netlib.org/lapack/explore-html/d4/dbb/ctpmv_8f.html
     *
     * Note: For a N*N matrix, the input Allocation should be a 1D allocation of size dimX = N*(N+1)/2,
     *       The following subroutine can is an example showing how to convert a UPPER trianglar matrix
     *       'a' to packed matrix 'b'.
     *           k = 0
     *           for i in range(0, n):
     *              for j in range(i, n):
     *                  b[k++] = a[i, j]
     *
     * @param Uplo Specifies whether the matrix is an upper or lower triangular matrix.
     * @param TransA The type of transpose applied to matrix A.
     * @param Diag Specifies whether or not A is unit triangular.
     * @param Ap The input allocation contains packed matrix A, supported elements type {@link Element#F32_2}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     */
    public void CTPMV(@Uplo int Uplo, @Transpose int TransA, @Diag int Diag,  Allocation Ap,  Allocation X,  int incX) {
        int N = validateTPMV(Element.F32_2(mRS), Uplo, TransA, Diag, Ap, X, incX);
        mRS.nScriptIntrinsicBLAS_Complex(getID(mRS), RsBlas_ctpmv, TransA, 0, 0, Uplo, Diag, 0, N, 0, 0, 0, Ap.getID(mRS), X.getID(mRS), 0, 0, 0, incX, 0, 0, 0);
    }

    /**
     * ZTPMV performs one of the matrix-vector operations
     * x := A*x   or   x := A**T*x   or   x := A**H*x
     *
     * Details: http://www.netlib.org/lapack/explore-html/d2/d9e/ztpmv_8f.html
     *
     * Note: For a N*N matrix, the input Allocation should be a 1D allocation of size dimX = N*(N+1)/2,
     *       The following subroutine can is an example showing how to convert a UPPER trianglar matrix
     *       'a' to packed matrix 'b'.
     *           k = 0
     *           for i in range(0, n):
     *              for j in range(i, n):
     *                  b[k++] = a[i, j]
     *
     * @param Uplo Specifies whether the matrix is an upper or lower triangular matrix.
     * @param TransA The type of transpose applied to matrix A.
     * @param Diag Specifies whether or not A is unit triangular.
     * @param Ap The input allocation contains packed matrix A, supported elements type {@link Element#F64_2}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     */
    public void ZTPMV(@Uplo int Uplo, @Transpose int TransA, @Diag int Diag,  Allocation Ap,  Allocation X,  int incX) {
        int N = validateTPMV(Element.F64_2(mRS), Uplo, TransA, Diag, Ap, X, incX);
        mRS.nScriptIntrinsicBLAS_Z(getID(mRS), RsBlas_ztpmv, TransA, 0, 0, Uplo, Diag, 0, N, 0, 0, 0, Ap.getID(mRS), X.getID(mRS), 0, 0, 0, incX, 0, 0, 0);
    }

    /**
     * STRSV solves one of the systems of equations
     * A*x = b   or   A**T*x = b
     *
     * Details: http://www.netlib.org/lapack/explore-html/d0/d2a/strsv_8f.html
     *
     * @param Uplo Specifies whether the matrix is an upper or lower triangular matrix.
     * @param TransA The type of transpose applied to matrix A.
     * @param Diag Specifies whether or not A is unit triangular.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     */
    public void STRSV(@Uplo int Uplo, @Transpose int TransA, @Diag int Diag,  Allocation A,  Allocation X,  int incX) {
        // TRSV is the same as TRMV
        validateTRMV(Element.F32(mRS), Uplo, TransA, Diag, A, X, incX);
        int N = A.getType().getY();
        mRS.nScriptIntrinsicBLAS_Single(getID(mRS), RsBlas_strsv, TransA, 0, 0, Uplo, Diag, 0, N, 0, 0, A.getID(mRS), X.getID(mRS), 0, 0, incX, 0, 0, 0);

    }

    /**
     * DTRSV solves one of the systems of equations
     * A*x = b   or   A**T*x = b
     *
     * Details: http://www.netlib.org/lapack/explore-html/d6/d96/dtrsv_8f.html
     *
     * @param Uplo Specifies whether the matrix is an upper or lower triangular matrix.
     * @param TransA The type of transpose applied to matrix A.
     * @param Diag Specifies whether or not A is unit triangular.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     */
    public void DTRSV(@Uplo int Uplo, @Transpose int TransA, @Diag int Diag,  Allocation A,  Allocation X,  int incX) {
        // TRSV is the same as TRMV
        validateTRMV(Element.F64(mRS), Uplo, TransA, Diag, A, X, incX);
        int N = A.getType().getY();
        mRS.nScriptIntrinsicBLAS_Double(getID(mRS), RsBlas_dtrsv, TransA, 0, 0, Uplo, Diag, 0, N, 0, 0, A.getID(mRS), X.getID(mRS), 0, 0, incX, 0, 0, 0);

    }

    /**
     * CTRSV solves one of the systems of equations
     * A*x = b   or   A**T*x = b   or   A**H*x = b
     *
     * Details: http://www.netlib.org/lapack/explore-html/d4/dc8/ctrsv_8f.html
     *
     * @param Uplo Specifies whether the matrix is an upper or lower triangular matrix.
     * @param TransA The type of transpose applied to matrix A.
     * @param Diag Specifies whether or not A is unit triangular.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32_2}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     */
    public void CTRSV(@Uplo int Uplo, @Transpose int TransA, @Diag int Diag,  Allocation A,  Allocation X,  int incX) {
        // TRSV is the same as TRMV
        validateTRMV(Element.F32_2(mRS), Uplo, TransA, Diag, A, X, incX);
        int N = A.getType().getY();
        mRS.nScriptIntrinsicBLAS_Complex(getID(mRS), RsBlas_ctrsv, TransA, 0, 0, Uplo, Diag, 0, N, 0, 0, 0, A.getID(mRS), X.getID(mRS), 0, 0, 0, incX, 0, 0, 0);

    }

    /**
     * ZTRSV solves one of the systems of equations
     * A*x = b   or   A**T*x = b   or   A**H*x = b
     *
     * Details: http://www.netlib.org/lapack/explore-html/d1/d2f/ztrsv_8f.html
     *
     * @param Uplo Specifies whether the matrix is an upper or lower triangular matrix.
     * @param TransA The type of transpose applied to matrix A.
     * @param Diag Specifies whether or not A is unit triangular.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64_2}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     */
    public void ZTRSV(@Uplo int Uplo, @Transpose int TransA, @Diag int Diag,  Allocation A,  Allocation X,  int incX) {
        // TRSV is the same as TRMV
        validateTRMV(Element.F64_2(mRS), Uplo, TransA, Diag, A, X, incX);
        int N = A.getType().getY();
        mRS.nScriptIntrinsicBLAS_Z(getID(mRS), RsBlas_ztrsv, TransA, 0, 0, Uplo, Diag, 0, N, 0, 0, 0, A.getID(mRS), X.getID(mRS), 0, 0, 0, incX, 0, 0, 0);

    }

    /**
     * STBSV solves one of the systems of equations
     * A*x = b   or   A**T*x = b
     *
     * Details: http://www.netlib.org/lapack/explore-html/d0/d1f/stbsv_8f.html
     *
     * Note: For a N*N matrix, the input Allocation should also be of size N*N (dimY = N, dimX = N),
     *       but only the region N*(K+1) will be referenced. The following subroutine can is an
     *       example showing how to convert a UPPER trianglar matrix 'a' to row-based band matrix 'b'.
     *           for i in range(0, n):
     *              for j in range(i, min(i+k+1, n)):
     *                  b[i, j-i] = a[i, j]
     *
     * @param Uplo Specifies whether the matrix is an upper or lower triangular matrix.
     * @param TransA The type of transpose applied to matrix A.
     * @param Diag Specifies whether or not A is unit triangular.
     * @param K The number of off-diagonals of the matrix A
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     */
    public void STBSV(@Uplo int Uplo, @Transpose int TransA, @Diag int Diag,  int K, Allocation A,  Allocation X,  int incX) {
        // TBSV is the same as TRMV + K >= 0
        validateTRMV(Element.F32(mRS), Uplo, TransA, Diag, A, X, incX);
        int N = A.getType().getY();
        if (K < 0) {
            throw new RSRuntimeException("Number of diagonals must be positive");
        }
        mRS.nScriptIntrinsicBLAS_Single(getID(mRS), RsBlas_stbsv, TransA, 0, 0, Uplo, Diag, 0, N, K, 0, A.getID(mRS), X.getID(mRS), 0, 0, incX, 0, 0, 0);
    }

    /**
     * DTBSV solves one of the systems of equations
     * A*x = b   or   A**T*x = b
     *
     * Details: http://www.netlib.org/lapack/explore-html/d4/dcf/dtbsv_8f.html
     *
     * Note: For a N*N matrix, the input Allocation should also be of size N*N (dimY = N, dimX = N),
     *       but only the region N*(K+1) will be referenced. The following subroutine can is an
     *       example showing how to convert a UPPER trianglar matrix 'a' to row-based band matrix 'b'.
     *           for i in range(0, n):
     *              for j in range(i, min(i+k+1, n)):
     *                  b[i, j-i] = a[i, j]
     *
     * @param Uplo Specifies whether the matrix is an upper or lower triangular matrix.
     * @param TransA The type of transpose applied to matrix A.
     * @param Diag Specifies whether or not A is unit triangular.
     * @param K The number of off-diagonals of the matrix A
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     */
    public void DTBSV(@Uplo int Uplo, @Transpose int TransA, @Diag int Diag,  int K, Allocation A,  Allocation X,  int incX) {
        // TBSV is the same as TRMV + K >= 0
        validateTRMV(Element.F64(mRS), Uplo, TransA, Diag, A, X, incX);
        int N = A.getType().getY();
        if (K < 0) {
            throw new RSRuntimeException("Number of diagonals must be positive");
        }
        mRS.nScriptIntrinsicBLAS_Double(getID(mRS), RsBlas_dtbsv, TransA, 0, 0, Uplo, Diag, 0, N, K, 0, A.getID(mRS), X.getID(mRS), 0, 0, incX, 0, 0, 0);
    }

    /**
     * CTBSV solves one of the systems of equations
     * A*x = b   or   A**T*x = b   or   A**H*x = b
     *
     * Details: http://www.netlib.org/lapack/explore-html/d9/d5f/ctbsv_8f.html
     *
     * Note: For a N*N matrix, the input Allocation should also be of size N*N (dimY = N, dimX = N),
     *       but only the region N*(K+1) will be referenced. The following subroutine can is an
     *       example showing how to convert a UPPER trianglar matrix 'a' to row-based band matrix 'b'.
     *           for i in range(0, n):
     *              for j in range(i, min(i+k+1, n)):
     *                  b[i, j-i] = a[i, j]
     *
     * @param Uplo Specifies whether the matrix is an upper or lower triangular matrix.
     * @param TransA The type of transpose applied to matrix A.
     * @param Diag Specifies whether or not A is unit triangular.
     * @param K The number of off-diagonals of the matrix A
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32_2}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     */
    public void CTBSV(@Uplo int Uplo, @Transpose int TransA, @Diag int Diag,  int K, Allocation A,  Allocation X,  int incX) {
        // TBSV is the same as TRMV + K >= 0
        validateTRMV(Element.F32_2(mRS), Uplo, TransA, Diag, A, X, incX);
        int N = A.getType().getY();
        if (K < 0) {
            throw new RSRuntimeException("Number of diagonals must be positive");
        }
        mRS.nScriptIntrinsicBLAS_Complex(getID(mRS), RsBlas_ctbsv, TransA, 0, 0, Uplo, Diag, 0, N, K, 0, 0, A.getID(mRS), X.getID(mRS), 0, 0, 0, incX, 0, 0, 0);
    }

    /**
     * ZTBSV solves one of the systems of equations
     * A*x = b   or   A**T*x = b   or   A**H*x = b
     *
     * Details: http://www.netlib.org/lapack/explore-html/d4/d5a/ztbsv_8f.html
     *
     * Note: For a N*N matrix, the input Allocation should also be of size N*N (dimY = N, dimX = N),
     *       but only the region N*(K+1) will be referenced. The following subroutine can is an
     *       example showing how to convert a UPPER trianglar matrix 'a' to row-based band matrix 'b'.
     *           for i in range(0, n):
     *              for j in range(i, min(i+k+1, n)):
     *                  b[i, j-i] = a[i, j]
     *
     * @param Uplo Specifies whether the matrix is an upper or lower triangular matrix.
     * @param TransA The type of transpose applied to matrix A.
     * @param Diag Specifies whether or not A is unit triangular.
     * @param K The number of off-diagonals of the matrix A
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64_2}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     */
    public void ZTBSV(@Uplo int Uplo, @Transpose int TransA, @Diag int Diag,  int K, Allocation A,  Allocation X,  int incX) {
        // TBSV is the same as TRMV + K >= 0
        validateTRMV(Element.F64_2(mRS), Uplo, TransA, Diag, A, X, incX);
        int N = A.getType().getY();
        if (K < 0) {
            throw new RSRuntimeException("Number of diagonals must be positive");
        }
        mRS.nScriptIntrinsicBLAS_Z(getID(mRS), RsBlas_ztbsv, TransA, 0, 0, Uplo, Diag, 0, N, K, 0, 0, A.getID(mRS), X.getID(mRS), 0, 0, 0, incX, 0, 0, 0);
    }

    /**
     * STPSV solves one of the systems of equations
     * A*x = b   or   A**T*x = b
     *
     * Details: http://www.netlib.org/lapack/explore-html/d0/d7c/stpsv_8f.html
     *
     * Note: For a N*N matrix, the input Allocation should be a 1D allocation of size dimX = N*(N+1)/2,
     *       The following subroutine can is an example showing how to convert a UPPER trianglar matrix
     *       'a' to packed matrix 'b'.
     *           k = 0
     *           for i in range(0, n):
     *              for j in range(i, n):
     *                  b[k++] = a[i, j]
     *
     * @param Uplo Specifies whether the matrix is an upper or lower triangular matrix.
     * @param TransA The type of transpose applied to matrix A.
     * @param Diag Specifies whether or not A is unit triangular.
     * @param Ap The input allocation contains packed matrix A, supported elements type {@link Element#F32}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     */
    public void STPSV(@Uplo int Uplo, @Transpose int TransA, @Diag int Diag,  Allocation Ap,  Allocation X,  int incX) {
        // TPSV is same as TPMV
        int N = validateTPMV(Element.F32(mRS), Uplo, TransA, Diag, Ap, X, incX);
        mRS.nScriptIntrinsicBLAS_Single(getID(mRS), RsBlas_stpsv, TransA, 0, 0, Uplo, Diag, 0, N, 0, 0, Ap.getID(mRS), X.getID(mRS), 0, 0, incX, 0, 0, 0);
    }

    /**
     * DTPSV solves one of the systems of equations
     * A*x = b   or   A**T*x = b
     *
     * Details: http://www.netlib.org/lapack/explore-html/d9/d84/dtpsv_8f.html
     *
     * Note: For a N*N matrix, the input Allocation should be a 1D allocation of size dimX = N*(N+1)/2,
     *       The following subroutine can is an example showing how to convert a UPPER trianglar matrix
     *       'a' to packed matrix 'b'.
     *           k = 0
     *           for i in range(0, n):
     *              for j in range(i, n):
     *                  b[k++] = a[i, j]
     *
     * @param Uplo Specifies whether the matrix is an upper or lower triangular matrix.
     * @param TransA The type of transpose applied to matrix A.
     * @param Diag Specifies whether or not A is unit triangular.
     * @param Ap The input allocation contains packed matrix A, supported elements type {@link Element#F64}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     */
    public void DTPSV(@Uplo int Uplo, @Transpose int TransA, @Diag int Diag,  Allocation Ap,  Allocation X,  int incX) {
        // TPSV is same as TPMV
        int N = validateTPMV(Element.F64(mRS), Uplo, TransA, Diag, Ap, X, incX);
        mRS.nScriptIntrinsicBLAS_Double(getID(mRS), RsBlas_dtpsv, TransA, 0, 0, Uplo, Diag, 0, N, 0, 0, Ap.getID(mRS), X.getID(mRS), 0, 0, incX, 0, 0, 0);
    }

    /**
     * CTPSV solves one of the systems of equations
     * A*x = b   or   A**T*x = b   or   A**H*x = b
     *
     * Details: http://www.netlib.org/lapack/explore-html/d8/d56/ctpsv_8f.html
     *
     * Note: For a N*N matrix, the input Allocation should be a 1D allocation of size dimX = N*(N+1)/2,
     *       The following subroutine can is an example showing how to convert a UPPER trianglar matrix
     *       'a' to packed matrix 'b'.
     *           k = 0
     *           for i in range(0, n):
     *              for j in range(i, n):
     *                  b[k++] = a[i, j]
     *
     * @param Uplo Specifies whether the matrix is an upper or lower triangular matrix.
     * @param TransA The type of transpose applied to matrix A.
     * @param Diag Specifies whether or not A is unit triangular.
     * @param Ap The input allocation contains packed matrix A, supported elements type {@link Element#F32_2}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     */
    public void CTPSV(@Uplo int Uplo, @Transpose int TransA, @Diag int Diag,  Allocation Ap,  Allocation X,  int incX) {
        // TPSV is same as TPMV
        int N = validateTPMV(Element.F32_2(mRS), Uplo, TransA, Diag, Ap, X, incX);
        mRS.nScriptIntrinsicBLAS_Complex(getID(mRS), RsBlas_ctpsv, TransA, 0, 0, Uplo, Diag, 0, N, 0, 0, 0, Ap.getID(mRS), X.getID(mRS), 0, 0, 0, incX, 0, 0, 0);
    }

    /**
     * ZTPSV solves one of the systems of equations
     * A*x = b   or   A**T*x = b   or   A**H*x = b
     *
     * Details: http://www.netlib.org/lapack/explore-html/da/d57/ztpsv_8f.html
     *
     * Note: For a N*N matrix, the input Allocation should be a 1D allocation of size dimX = N*(N+1)/2,
     *       The following subroutine can is an example showing how to convert a UPPER trianglar matrix
     *       'a' to packed matrix 'b'.
     *           k = 0
     *           for i in range(0, n):
     *              for j in range(i, n):
     *                  b[k++] = a[i, j]
     *
     * @param Uplo Specifies whether the matrix is an upper or lower triangular matrix.
     * @param TransA The type of transpose applied to matrix A.
     * @param Diag Specifies whether or not A is unit triangular.
     * @param Ap The input allocation contains packed matrix A, supported elements type {@link Element#F64_2}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     */
    public void ZTPSV(@Uplo int Uplo, @Transpose int TransA, @Diag int Diag,  Allocation Ap,  Allocation X,  int incX) {
        // TPSV is same as TPMV
        int N = validateTPMV(Element.F64_2(mRS), Uplo, TransA, Diag, Ap, X, incX);
        mRS.nScriptIntrinsicBLAS_Z(getID(mRS), RsBlas_ztpsv, TransA, 0, 0, Uplo, Diag, 0, N, 0, 0, 0, Ap.getID(mRS), X.getID(mRS), 0, 0, 0, incX, 0, 0, 0);
    }

    /**
     * Level 2, S and D only
     */
    static int validateSYMV(Element e, @Uplo int Uplo, Allocation A, Allocation X, Allocation Y, int incX, int incY) {
        validateUplo(Uplo);
        int N = A.getType().getY();
        if (A.getType().getX() != N) {
            throw new RSRuntimeException("A must be a square matrix for SYMV");
        }
        if (!A.getType().getElement().isCompatible(e) ||
            !X.getType().getElement().isCompatible(e) ||
            !Y.getType().getElement().isCompatible(e) ) {
            throw new RSRuntimeException("Called BLAS with wrong Element type");
        }
        if (X.getType().getY() > 1 || Y.getType().getY() > 1) {
            throw new RSRuntimeException("BLAS vectors must have Y dimension of 0 or 1");
        }

        if (incX <= 0 || incY <= 0) {
            throw new RSRuntimeException("Vector increments must be greater than 0");
        }
        int expectedXDim = 1 + (N - 1) * incX;
        if (X.getType().getX() != expectedXDim) {
            throw new RSRuntimeException("Incorrect vector dimensions for SYMV");
        }
        int expectedYDim = 1 + (N - 1) * incY;
        if (Y.getType().getX() != expectedYDim) {
            throw new RSRuntimeException("Incorrect vector dimensions for SYMV");
        }
        return N;
    }
    static int validateSPMV(Element e, @Uplo int Uplo, Allocation Ap, Allocation X, int incX, Allocation Y, int incY) {
        validateUplo(Uplo);
        if (!Ap.getType().getElement().isCompatible(e) ||
            !X.getType().getElement().isCompatible(e) ||
            !Y.getType().getElement().isCompatible(e)) {
            throw new RSRuntimeException("Called BLAS with wrong Element type");
        }
        if (X.getType().getY() > 1 || Y.getType().getY() > 1) {
            throw new RSRuntimeException("BLAS vectors must have Y dimension of 0 or 1");
        }

        if (Ap.getType().getY() > 1) {
            throw new RSRuntimeException("Ap must have a Y dimension of 0 or 1");
        }

        int N = (int)Math.sqrt((double)Ap.getType().getX() * 2);
        if (Ap.getType().getX() != ((N * (N+1)) / 2)) {
            throw new RSRuntimeException("Invalid dimension for Ap");
        }
        if (incX <= 0 || incY <= 0) {
            throw new RSRuntimeException("Vector increments must be greater than 0");
        }
        int expectedXDim = 1 + (N - 1) * incX;
        if (X.getType().getX() != expectedXDim) {
            throw new RSRuntimeException("Incorrect vector dimensions for SPMV");
        }
        int expectedYDim = 1 + (N - 1) * incY;
        if (Y.getType().getX() != expectedYDim) {
            throw new RSRuntimeException("Incorrect vector dimensions for SPMV");
        }

        return N;
    }
    static void validateGER(Element e, Allocation X, int incX, Allocation Y, int incY, Allocation A) {
        if (!A.getType().getElement().isCompatible(e) ||
            !X.getType().getElement().isCompatible(e) ||
            !Y.getType().getElement().isCompatible(e) ) {
            throw new RSRuntimeException("Called BLAS with wrong Element type");
        }

        if (X.getType().getY() > 1 || Y.getType().getY() > 1) {
            throw new RSRuntimeException("BLAS vectors must have Y dimension of 0 or 1");
        }

        int M = A.getType().getY();
        int N = A.getType().getX();

        if (N < 1 || M < 1) {
            throw new RSRuntimeException("M and N must be 1 or greater for GER");
        }
        if (incX <= 0 || incY <= 0) {
            throw new RSRuntimeException("Vector increments must be greater than 0");
        }
        int expectedXDim = 1 + (M - 1) * incX;
        if (X.getType().getX() != expectedXDim) {
            throw new RSRuntimeException("Incorrect vector dimensions for GER");
        }
        int expectedYDim = 1 + (N - 1) * incY;
        if (Y.getType().getX() != expectedYDim) {
            throw new RSRuntimeException("Incorrect vector dimensions for GER");
        }


    }
    static int validateSYR(Element e, @Uplo int Uplo, Allocation X, int incX, Allocation A) {
        validateUplo(Uplo);
        if (!A.getType().getElement().isCompatible(e) ||
            !X.getType().getElement().isCompatible(e)) {
            throw new RSRuntimeException("Called BLAS with wrong Element type");
        }

        int N = A.getType().getX();

        if (X.getType().getY() > 1) {
            throw new RSRuntimeException("BLAS vectors must have Y dimension of 0 or 1");
        }
        if (N != A.getType().getY()) {
            throw new RSRuntimeException("A must be a symmetric matrix");
        }
        if (incX <= 0) {
            throw new RSRuntimeException("Vector increments must be greater than 0");
        }
        int expectedXDim = 1 + (N - 1) * incX;
        if (X.getType().getX() != expectedXDim) {
            throw new RSRuntimeException("Incorrect vector dimensions for SYR");
        }
        return N;
    }
    static int validateSPR(Element e, @Uplo int Uplo, Allocation X, int incX, Allocation Ap) {
        validateUplo(Uplo);
        if (!Ap.getType().getElement().isCompatible(e) ||
            !X.getType().getElement().isCompatible(e)) {
            throw new RSRuntimeException("Called BLAS with wrong Element type");
        }
        if (X.getType().getY() > 1) {
            throw new RSRuntimeException("BLAS vectors must have Y dimension of 0 or 1");
        }

        if (Ap.getType().getY() > 1) {
            throw new RSRuntimeException("Ap must have a Y dimension of 0 or 1");
        }

        int N = (int)Math.sqrt((double)Ap.getType().getX() * 2);
        if (Ap.getType().getX() != ((N * (N+1)) / 2)) {
            throw new RSRuntimeException("Invalid dimension for Ap");
        }
        if (incX <= 0) {
            throw new RSRuntimeException("Vector increments must be greater than 0");
        }
        int expectedXDim = 1 + (N - 1) * incX;
        if (X.getType().getX() != expectedXDim) {
            throw new RSRuntimeException("Incorrect vector dimensions for SPR");
        }

        return N;
    }

    static int validateSYR2(Element e, @Uplo int Uplo, Allocation X, int incX, Allocation Y, int incY, Allocation A) {
        validateUplo(Uplo);
        if (!A.getType().getElement().isCompatible(e) ||
            !X.getType().getElement().isCompatible(e) ||
            !Y.getType().getElement().isCompatible(e)) {
            throw new RSRuntimeException("Called BLAS with wrong Element type");
        }

        if (X.getType().getY() > 1 || Y.getType().getY() > 1) {
            throw new RSRuntimeException("BLAS vectors must have Y dimension of 0 or 1");
        }

        int N = A.getType().getX();

        if (N != A.getType().getY()) {
            throw new RSRuntimeException("A must be a symmetric matrix");
        }
        if (incX <= 0 || incY <= 0) {
            throw new RSRuntimeException("Vector increments must be greater than 0");
        }
        int expectedXDim = 1 + (N - 1) * incX;
        int expectedYDim = 1 + (N - 1) * incY;
        if (X.getType().getX() != expectedXDim || Y.getType().getX() != expectedYDim) {
            throw new RSRuntimeException("Incorrect vector dimensions for SYR");
        }
        return N;

    }
    static int validateSPR2(Element e, @Uplo int Uplo, Allocation X, int incX, Allocation Y, int incY, Allocation Ap) {
        validateUplo(Uplo);
        if (!Ap.getType().getElement().isCompatible(e) ||
            !X.getType().getElement().isCompatible(e) ||
            !Y.getType().getElement().isCompatible(e)) {
            throw new RSRuntimeException("Called BLAS with wrong Element type");
        }
        if (X.getType().getY() > 1 || Y.getType().getY() > 1) {
            throw new RSRuntimeException("BLAS vectors must have Y dimension of 0 or 1");
        }

        if (Ap.getType().getY() > 1) {
            throw new RSRuntimeException("Ap must have a Y dimension of 0 or 1");
        }

        int N = (int)Math.sqrt((double)Ap.getType().getX() * 2);
        if (Ap.getType().getX() != ((N * (N+1)) / 2)) {
            throw new RSRuntimeException("Invalid dimension for Ap");
        }
        if (incX <= 0 || incY <= 0) {
            throw new RSRuntimeException("Vector increments must be greater than 0");
        }
        int expectedXDim = 1 + (N - 1) * incX;
        int expectedYDim = 1 + (N - 1) * incY;
        if (X.getType().getX() != expectedXDim || Y.getType().getX() != expectedYDim) {
            throw new RSRuntimeException("Incorrect vector dimensions for SPR2");
        }

        return N;
    }

    /**
     * SSYMV performs the matrix-vector operation
     * y := alpha*A*x + beta*y
     *
     * Details: http://www.netlib.org/lapack/explore-html/d2/d94/ssymv_8f.html
     *
     * @param Uplo Specifies whether the upper or lower triangular part is to be referenced.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param beta The scalar beta.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F32}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     */
    public void SSYMV(@Uplo int Uplo, float alpha, Allocation A, Allocation X, int incX, float beta, Allocation Y, int incY) {
        int N = validateSYMV(Element.F32(mRS), Uplo, A, X, Y, incX, incY);
        mRS.nScriptIntrinsicBLAS_Single(getID(mRS), RsBlas_ssymv, 0, 0, 0, Uplo, 0, 0, N, 0, alpha, A.getID(mRS), X.getID(mRS), beta, Y.getID(mRS), incX, incY, 0, 0);
    }

    /**
     * SSBMV performs the matrix-vector operation
     * y := alpha*A*x + beta*y
     *
     * Details: http://www.netlib.org/lapack/explore-html/d3/da1/ssbmv_8f.html
     *
     * Note: For a N*N matrix, the input Allocation should also be of size N*N (dimY = N, dimX = N),
     *       but only the region N*(K+1) will be referenced. The following subroutine can is an
     *       example showing how to convert a UPPER trianglar matrix 'a' to row-based band matrix 'b'.
     *           for i in range(0, n):
     *              for j in range(i, min(i+k+1, n)):
     *                  b[i, j-i] = a[i, j]
     *
     * @param Uplo Specifies whether the upper or lower triangular part of the band matrix A is being supplied.
     * @param K The number of off-diagonals of the matrix A
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param beta The scalar beta.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F32}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     */
    public void SSBMV(@Uplo int Uplo, int K, float alpha, Allocation A, Allocation X, int incX, float beta, Allocation Y, int incY) {
        // SBMV is the same as SYMV + K >= 0
        if (K < 0) {
            throw new RSRuntimeException("K must be greater than or equal to 0");
        }
        int N = validateSYMV(Element.F32(mRS), Uplo, A, X, Y, incX, incY);
        mRS.nScriptIntrinsicBLAS_Single(getID(mRS), RsBlas_ssbmv, 0, 0, 0, Uplo, 0, 0, N, K, alpha, A.getID(mRS), X.getID(mRS), beta, Y.getID(mRS), incX, incY, 0, 0);
    }

    /**
     * SSPMV performs the matrix-vector operation
     * y := alpha*A*x + beta*y
     *
     * Details: http://www.netlib.org/lapack/explore-html/d8/d68/sspmv_8f.html
     *
     * Note: For a N*N matrix, the input Allocation should be a 1D allocation of size dimX = N*(N+1)/2,
     *       The following subroutine can is an example showing how to convert a UPPER trianglar matrix
     *       'a' to packed matrix 'b'.
     *           k = 0
     *           for i in range(0, n):
     *              for j in range(i, n):
     *                  b[k++] = a[i, j]
     *
     * @param Uplo Specifies whether the upper or lower triangular part of the matrix A is supplied in packed form.
     * @param alpha The scalar alpha.
     * @param Ap The input allocation contains matrix A, supported elements type {@link Element#F32}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param beta The scalar beta.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F32}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     */
    public void SSPMV(@Uplo int Uplo, float alpha, Allocation Ap, Allocation X, int incX, float beta, Allocation Y, int incY) {
        int N = validateSPMV(Element.F32(mRS), Uplo, Ap, X, incX, Y, incY);
        mRS.nScriptIntrinsicBLAS_Single(getID(mRS), RsBlas_sspmv, 0, 0, 0, Uplo, 0, 0, N, 0, alpha, Ap.getID(mRS), X.getID(mRS), beta, Y.getID(mRS), incX, incY, 0, 0);
    }

    /**
     * SGER performs the rank 1 operation
     * A := alpha*x*y**T + A
     *
     * Details: http://www.netlib.org/lapack/explore-html/db/d5c/sger_8f.html
     *
     * @param alpha The scalar alpha.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F32}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32}.
     */
    public void SGER(float alpha, Allocation X, int incX, Allocation Y, int incY, Allocation A) {
        int M = A.getType().getY();
        int N = A.getType().getX();
        validateGER(Element.F32(mRS), X, incX, Y, incY, A);
        mRS.nScriptIntrinsicBLAS_Single(getID(mRS), RsBlas_sger, 0, 0, 0, 0, 0, M, N, 0, alpha, X.getID(mRS), Y.getID(mRS), 0.f, A.getID(mRS), incX, incY, 0, 0);
    }

    /**
     * SSYR performs the rank 1 operation
     * A := alpha*x*x**T + A
     *
     * Details: http://www.netlib.org/lapack/explore-html/d6/dac/ssyr_8f.html
     *
     * @param Uplo Specifies whether the upper or lower triangular part is to be referenced.
     * @param alpha The scalar alpha.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32}.
     */
    public void SSYR(@Uplo int Uplo, float alpha, Allocation X, int incX, Allocation A) {
        int N = validateSYR(Element.F32(mRS), Uplo, X, incX, A);
        mRS.nScriptIntrinsicBLAS_Single(getID(mRS), RsBlas_ssyr, 0, 0, 0, Uplo, 0, 0, N, 0, alpha, X.getID(mRS), A.getID(mRS), 0.f, 0, incX, 0, 0, 0);
    }

    /**
     * SSPR performs the rank 1 operation
     * A := alpha*x*x**T + A
     *
     * Details: http://www.netlib.org/lapack/explore-html/d2/d9b/sspr_8f.html
     *
     * Note: For a N*N matrix, the input Allocation should be a 1D allocation of size dimX = N*(N+1)/2,
     *       The following subroutine can is an example showing how to convert a UPPER trianglar matrix
     *       'a' to packed matrix 'b'.
     *           k = 0
     *           for i in range(0, n):
     *              for j in range(i, n):
     *                  b[k++] = a[i, j]
     *
     * @param Uplo Specifies whether the upper or lower triangular part is to be supplied in the packed form.
     * @param alpha The scalar alpha.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param Ap The input allocation contains matrix A, supported elements type {@link Element#F32}.
     */
    public void SSPR(@Uplo int Uplo, float alpha, Allocation X, int incX, Allocation Ap) {
        int N = validateSPR(Element.F32(mRS), Uplo, X, incX, Ap);
        mRS.nScriptIntrinsicBLAS_Single(getID(mRS), RsBlas_sspr, 0, 0, 0, Uplo, 0, 0, N, 0, alpha, X.getID(mRS), Ap.getID(mRS), 0.f, 0, incX, 0, 0, 0);
    }

    /**
     * SSYR2 performs the symmetric rank 2 operation
     * A := alpha*x*y**T + alpha*y*x**T + A
     *
     * Details: http://www.netlib.org/lapack/explore-html/db/d99/ssyr2_8f.html
     *
     * @param Uplo Specifies whether the upper or lower triangular part is to be referenced.
     * @param alpha The scalar alpha.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F32}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32}.
     */
    public void SSYR2(@Uplo int Uplo, float alpha, Allocation X, int incX, Allocation Y, int incY, Allocation A) {
        int N = validateSYR2(Element.F32(mRS), Uplo, X, incX, Y, incY, A);
        mRS.nScriptIntrinsicBLAS_Single(getID(mRS), RsBlas_ssyr2, 0, 0, 0, Uplo, 0, 0, N, 0, alpha, X.getID(mRS), Y.getID(mRS), 0, A.getID(mRS), incX, incY, 0, 0);
    }

    /**
     * SSPR2 performs the symmetric rank 2 operation
     * A := alpha*x*y**T + alpha*y*x**T + A
     *
     * Details: http://www.netlib.org/lapack/explore-html/db/d3e/sspr2_8f.html
     *
     * Note: For a N*N matrix, the input Allocation should be a 1D allocation of size dimX = N*(N+1)/2,
     *       The following subroutine can is an example showing how to convert a UPPER trianglar matrix
     *       'a' to packed matrix 'b'.
     *           k = 0
     *           for i in range(0, n):
     *              for j in range(i, n):
     *                  b[k++] = a[i, j]
     *
     * @param Uplo Specifies whether the upper or lower triangular part is to be supplied in the packed form.
     * @param alpha The scalar alpha.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F32}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     * @param Ap The input allocation contains matrix A, supported elements type {@link Element#F32}.
     */
    public void SSPR2(@Uplo int Uplo, float alpha, Allocation X, int incX, Allocation Y, int incY, Allocation Ap) {
        int N = validateSPR2(Element.F32(mRS), Uplo, X, incX, Y, incY, Ap);
        mRS.nScriptIntrinsicBLAS_Single(getID(mRS), RsBlas_sspr2, 0, 0, 0, Uplo, 0, 0, N, 0, alpha, X.getID(mRS), Y.getID(mRS), 0, Ap.getID(mRS), incX, incY, 0, 0);
    }

    /**
     * DSYMV performs the matrix-vector operation
     * y := alpha*A*x + beta*y
     *
     * Details: http://www.netlib.org/lapack/explore-html/d8/dbe/dsymv_8f.html
     *
     * @param Uplo Specifies whether the upper or lower triangular part is to be referenced.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param beta The scalar beta.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F64}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     */
    public void DSYMV(@Uplo int Uplo, double alpha, Allocation A, Allocation X, int incX, double beta, Allocation Y, int incY) {
        int N = validateSYMV(Element.F64(mRS), Uplo, A, X, Y, incX, incY);
        mRS.nScriptIntrinsicBLAS_Double(getID(mRS), RsBlas_dsymv, 0, 0, 0, Uplo, 0, 0, N, 0, alpha, A.getID(mRS), X.getID(mRS), beta, Y.getID(mRS), incX, incY, 0, 0);
    }

    /**
     * DSBMV performs the matrix-vector operation
     * y := alpha*A*x + beta*y
     *
     * Details: http://www.netlib.org/lapack/explore-html/d8/d1e/dsbmv_8f.html
     *
     * Note: For a N*N matrix, the input Allocation should also be of size N*N (dimY = N, dimX = N),
     *       but only the region N*(K+1) will be referenced. The following subroutine can is an
     *       example showing how to convert a UPPER trianglar matrix 'a' to row-based band matrix 'b'.
     *           for i in range(0, n):
     *              for j in range(i, min(i+k+1, n)):
     *                  b[i, j-i] = a[i, j]
     *
     * @param Uplo Specifies whether the upper or lower triangular part of the band matrix A is being supplied.
     * @param K The number of off-diagonals of the matrix A
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param beta The scalar beta.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F64}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     */
    public void DSBMV(@Uplo int Uplo, int K, double alpha, Allocation A, Allocation X, int incX, double beta, Allocation Y, int incY) {
        // SBMV is the same as SYMV + K >= 0
        if (K < 0) {
            throw new RSRuntimeException("K must be greater than or equal to 0");
        }
        int N = validateSYMV(Element.F64(mRS), Uplo, A, X, Y, incX, incY);
        mRS.nScriptIntrinsicBLAS_Double(getID(mRS), RsBlas_dsbmv, 0, 0, 0, Uplo, 0, 0, N, K, alpha, A.getID(mRS), X.getID(mRS), beta, Y.getID(mRS), incX, incY, 0, 0);
    }

    /**
     * DSPMV performs the matrix-vector operation
     * y := alpha*A*x + beta*y
     *
     * Details: http://www.netlib.org/lapack/explore-html/d4/d85/dspmv_8f.html
     *
     * Note: For a N*N matrix, the input Allocation should be a 1D allocation of size dimX = N*(N+1)/2,
     *       The following subroutine can is an example showing how to convert a UPPER trianglar matrix
     *       'a' to packed matrix 'b'.
     *           k = 0
     *           for i in range(0, n):
     *              for j in range(i, n):
     *                  b[k++] = a[i, j]
     *
     * @param Uplo Specifies whether the upper or lower triangular part of the matrix A is supplied in packed form.
     * @param alpha The scalar alpha.
     * @param Ap The input allocation contains matrix A, supported elements type {@link Element#F64}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param beta The scalar beta.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F64}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     */
    public void DSPMV(@Uplo int Uplo, double alpha, Allocation Ap, Allocation X, int incX, double beta, Allocation Y, int incY) {
        int N = validateSPMV(Element.F64(mRS), Uplo, Ap, X, incX, Y, incY);
        mRS.nScriptIntrinsicBLAS_Double(getID(mRS), RsBlas_dspmv, 0, 0, 0, Uplo, 0, 0, N, 0, alpha, Ap.getID(mRS), X.getID(mRS), beta, Y.getID(mRS), incX, incY, 0, 0);
    }

    /**
     * DGER performs the rank 1 operation
     * A := alpha*x*y**T + A
     *
     * Details: http://www.netlib.org/lapack/explore-html/dc/da8/dger_8f.html
     *
     * @param alpha The scalar alpha.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F64}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64}.
     */
    public void DGER(double alpha, Allocation X, int incX, Allocation Y, int incY, Allocation A) {
        int M = A.getType().getY();
        int N = A.getType().getX();
        validateGER(Element.F64(mRS), X, incX, Y, incY, A);
        mRS.nScriptIntrinsicBLAS_Double(getID(mRS), RsBlas_dger, 0, 0, 0, 0, 0, M, N, 0, alpha, X.getID(mRS), Y.getID(mRS), 0.f, A.getID(mRS), incX, incY, 0, 0);
    }

    /**
     * DSYR performs the rank 1 operation
     * A := alpha*x*x**T + A
     *
     * Details: http://www.netlib.org/lapack/explore-html/d3/d60/dsyr_8f.html
     *
     * @param Uplo Specifies whether the upper or lower triangular part is to be referenced.
     * @param alpha The scalar alpha.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64}.
     */
    public void DSYR(@Uplo int Uplo, double alpha, Allocation X, int incX, Allocation A) {
        int N = validateSYR(Element.F64(mRS), Uplo, X, incX, A);
        mRS.nScriptIntrinsicBLAS_Double(getID(mRS), RsBlas_dsyr, 0, 0, 0, Uplo, 0, 0, N, 0, alpha, X.getID(mRS), A.getID(mRS), 0.f, 0, incX, 0, 0, 0);
    }

    /**
     * DSPR performs the rank 1 operation
     * A := alpha*x*x**T + A
     *
     * Details: http://www.netlib.org/lapack/explore-html/dd/dba/dspr_8f.html
     *
     * Note: For a N*N matrix, the input Allocation should be a 1D allocation of size dimX = N*(N+1)/2,
     *       The following subroutine can is an example showing how to convert a UPPER trianglar matrix
     *       'a' to packed matrix 'b'.
     *           k = 0
     *           for i in range(0, n):
     *              for j in range(i, n):
     *                  b[k++] = a[i, j]
     *
     * @param Uplo Specifies whether the upper or lower triangular part is to be supplied in the packed form.
     * @param alpha The scalar alpha.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param Ap The input allocation contains matrix A, supported elements type {@link Element#F64}.
     */
    public void DSPR(@Uplo int Uplo, double alpha, Allocation X, int incX, Allocation Ap) {
        int N = validateSPR(Element.F64(mRS), Uplo, X, incX, Ap);
        mRS.nScriptIntrinsicBLAS_Double(getID(mRS), RsBlas_dspr, 0, 0, 0, Uplo, 0, 0, N, 0, alpha, X.getID(mRS), Ap.getID(mRS), 0.f, 0, incX, 0, 0, 0);
    }

    /**
     * DSYR2 performs the symmetric rank 2 operation
     * A := alpha*x*y**T + alpha*y*x**T + A
     *
     * Details: http://www.netlib.org/lapack/explore-html/de/d41/dsyr2_8f.html
     *
     * @param Uplo Specifies whether the upper or lower triangular part is to be referenced.
     * @param alpha The scalar alpha.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F64}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64}.
     */
    public void DSYR2(@Uplo int Uplo, double alpha, Allocation X, int incX, Allocation Y, int incY, Allocation A) {
        int N = validateSYR2(Element.F64(mRS), Uplo, X, incX, Y, incY, A);
        mRS.nScriptIntrinsicBLAS_Double(getID(mRS), RsBlas_dsyr2, 0, 0, 0, Uplo, 0, 0, N, 0, alpha, X.getID(mRS), Y.getID(mRS), 0, A.getID(mRS), incX, incY, 0, 0);
    }

    /**
     * DSPR2 performs the symmetric rank 2 operation
     * A := alpha*x*y**T + alpha*y*x**T + A
     *
     * Details: http://www.netlib.org/lapack/explore-html/dd/d9e/dspr2_8f.html
     *
     * Note: For a N*N matrix, the input Allocation should be a 1D allocation of size dimX = N*(N+1)/2,
     *       The following subroutine can is an example showing how to convert a UPPER trianglar matrix
     *       'a' to packed matrix 'b'.
     *           k = 0
     *           for i in range(0, n):
     *              for j in range(i, n):
     *                  b[k++] = a[i, j]
     *
     * @param Uplo Specifies whether the upper or lower triangular part is to be supplied in the packed form.
     * @param alpha The scalar alpha.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F64}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     * @param Ap The input allocation contains matrix A, supported elements type {@link Element#F64}.
     */
    public void DSPR2(@Uplo int Uplo, double alpha, Allocation X, int incX, Allocation Y, int incY, Allocation Ap) {
        int N = validateSPR2(Element.F64(mRS), Uplo, X, incX, Y, incY, Ap);
        mRS.nScriptIntrinsicBLAS_Double(getID(mRS), RsBlas_dspr2, 0, 0, 0, Uplo, 0, 0, N, 0, alpha, X.getID(mRS), Y.getID(mRS), 0, Ap.getID(mRS), incX, incY, 0, 0);
    }


    /**
     * Level 2, C and Z only
     */

    static void validateGERU(Element e, Allocation X, int incX, Allocation Y, int incY, Allocation A) {
        if (!A.getType().getElement().isCompatible(e) ||
            !X.getType().getElement().isCompatible(e) ||
            !Y.getType().getElement().isCompatible(e)) {
            throw new RSRuntimeException("Called BLAS with wrong Element type");
        }
        if (X.getType().getY() > 1 || Y.getType().getY() > 1) {
            throw new RSRuntimeException("BLAS vectors must have Y dimension of 0 or 1");
        }

        int M = A.getType().getY();
        int N = A.getType().getX();
        if (incX <= 0 || incY <= 0) {
            throw new RSRuntimeException("Vector increments must be greater than 0");
        }
        int expectedXDim = 1 + (M - 1) * incX;
        if (X.getType().getX() != expectedXDim) {
            throw new RSRuntimeException("Incorrect vector dimensions for GERU");
        }
        int expectedYDim = 1 + (N - 1) * incY;
        if (Y.getType().getX() != expectedYDim) {
            throw new RSRuntimeException("Incorrect vector dimensions for GERU");
        }

    }

    /**
     * CHEMV performs the matrix-vector operation
     * y := alpha*A*x + beta*y
     *
     * Details: http://www.netlib.org/lapack/explore-html/d7/d51/chemv_8f.html
     *
     * @param Uplo Specifies whether the upper or lower triangular part is to be referenced.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32_2}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param beta The scalar beta.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F32_2}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     */
    public void CHEMV(@Uplo int Uplo, Float2 alpha, Allocation A, Allocation X, int incX, Float2 beta, Allocation Y, int incY) {
        // HEMV is the same as SYR2 validation-wise
        int N = validateSYR2(Element.F32_2(mRS), Uplo, X, incX, Y, incY, A);
        mRS.nScriptIntrinsicBLAS_Complex(getID(mRS), RsBlas_chemv, 0, 0, 0, Uplo, 0, 0, N, 0, alpha.x, alpha.y, A.getID(mRS), X.getID(mRS), beta.x, beta.y, Y.getID(mRS), incX, incY, 0, 0);
    }

    /**
     * CHBMV performs the matrix-vector operation
     * y := alpha*A*x + beta*y
     *
     * Details: http://www.netlib.org/lapack/explore-html/db/dc2/chbmv_8f.html
     *
     * Note: For a N*N matrix, the input Allocation should also be of size N*N (dimY = N, dimX = N),
     *       but only the region N*(K+1) will be referenced. The following subroutine can is an
     *       example showing how to convert a UPPER trianglar matrix 'a' to row-based band matrix 'b'.
     *           for i in range(0, n):
     *              for j in range(i, min(i+k+1, n)):
     *                  b[i, j-i] = a[i, j]
     *
     * @param Uplo Specifies whether the upper or lower triangular part of the band matrix A is being supplied.
     * @param K The number of off-diagonals of the matrix A
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32_2}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param beta The scalar beta.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F32_2}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     */
    public void CHBMV(@Uplo int Uplo, int K, Float2 alpha, Allocation A, Allocation X, int incX, Float2 beta, Allocation Y, int incY) {
        // HBMV is the same as SYR2 validation-wise
        int N = validateSYR2(Element.F32_2(mRS), Uplo, X, incX, Y, incY, A);
        if (K < 0) {
            throw new RSRuntimeException("K must be 0 or greater for HBMV");
        }
        mRS.nScriptIntrinsicBLAS_Complex(getID(mRS), RsBlas_chbmv, 0, 0, 0, Uplo, 0, 0, N, K, alpha.x, alpha.y, A.getID(mRS), X.getID(mRS), beta.x, beta.y, Y.getID(mRS), incX, incY, 0, 0);
    }

    /**
     * CHPMV performs the matrix-vector operation
     * y := alpha*A*x + beta*y
     *
     * Details: http://www.netlib.org/lapack/explore-html/d2/d06/chpmv_8f.html
     *
     * Note: For a N*N matrix, the input Allocation should be a 1D allocation of size dimX = N*(N+1)/2,
     *       The following subroutine can is an example showing how to convert a UPPER trianglar matrix
     *       'a' to packed matrix 'b'.
     *           k = 0
     *           for i in range(0, n):
     *              for j in range(i, n):
     *                  b[k++] = a[i, j]
     *
     * @param Uplo Specifies whether the upper or lower triangular part of the matrix A is supplied in packed form.
     * @param alpha The scalar alpha.
     * @param Ap The input allocation contains matrix A, supported elements type {@link Element#F32_2}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param beta The scalar beta.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F32_2}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     */
    public void CHPMV(@Uplo int Uplo, Float2 alpha, Allocation Ap, Allocation X, int incX, Float2 beta, Allocation Y, int incY) {
        // HPMV is the same as SPR2
        int N = validateSPR2(Element.F32_2(mRS), Uplo, X, incX, Y, incY, Ap);
        mRS.nScriptIntrinsicBLAS_Complex(getID(mRS), RsBlas_chpmv, 0, 0, 0, Uplo, 0, 0, N, 0, alpha.x, alpha.y, Ap.getID(mRS), X.getID(mRS), beta.x, beta.y, Y.getID(mRS), incX, incY, 0, 0);
    }

    /**
     * CGERU performs the rank 1 operation
     * A := alpha*x*y**T + A
     *
     * Details: http://www.netlib.org/lapack/explore-html/db/d5f/cgeru_8f.html
     *
     * @param alpha The scalar alpha.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F32_2}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32_2}.
     */
    public void CGERU(Float2 alpha, Allocation X, int incX, Allocation Y, int incY, Allocation A) {
        validateGERU(Element.F32_2(mRS), X, incX, Y, incY, A);
        int M = A.getType().getY();
        int N = A.getType().getX();
        mRS.nScriptIntrinsicBLAS_Complex(getID(mRS), RsBlas_cgeru, 0, 0, 0, 0, 0, M, N, 0, alpha.x, alpha.y, X.getID(mRS), Y.getID(mRS), 0, 0, A.getID(mRS), incX, incY, 0, 0);
    }

    /**
     * CGERC performs the rank 1 operation
     * A := alpha*x*y**H + A
     *
     * Details: http://www.netlib.org/lapack/explore-html/dd/d84/cgerc_8f.html
     *
     * @param alpha The scalar alpha.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F32_2}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32_2}.
     */
    public void CGERC(Float2 alpha, Allocation X, int incX, Allocation Y, int incY, Allocation A) {
        // same as GERU
        validateGERU(Element.F32_2(mRS), X, incX, Y, incY, A);
        int M = A.getType().getY();
        int N = A.getType().getX();
        mRS.nScriptIntrinsicBLAS_Complex(getID(mRS), RsBlas_cgerc, 0, 0, 0, 0, 0, M, N, 0, alpha.x, alpha.y, X.getID(mRS), Y.getID(mRS), 0, 0, A.getID(mRS), incX, incY, 0, 0);
    }

    /**
     * CHER performs the rank 1 operation
     * A := alpha*x*x**H + A
     *
     * Details: http://www.netlib.org/lapack/explore-html/d3/d6d/cher_8f.html
     *
     * @param Uplo Specifies whether the upper or lower triangular part is to be referenced.
     * @param alpha The scalar alpha.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32_2}.
     */
    public void CHER(@Uplo int Uplo, float alpha, Allocation X, int incX, Allocation A) {
        // same as SYR
        int N = validateSYR(Element.F32_2(mRS), Uplo, X, incX, A);
        mRS.nScriptIntrinsicBLAS_Complex(getID(mRS), RsBlas_cher, 0, 0, 0, Uplo, 0, 0, N, 0, alpha, 0, X.getID(mRS), 0, 0, 0, A.getID(mRS), incX, 0, 0, 0);
    }

    /**
     * CHPR performs the rank 1 operation
     * A := alpha*x*x**H + A
     *
     * Details: http://www.netlib.org/lapack/explore-html/db/dcd/chpr_8f.html
     *
     * Note: For a N*N matrix, the input Allocation should be a 1D allocation of size dimX = N*(N+1)/2,
     *       The following subroutine can is an example showing how to convert a UPPER trianglar matrix
     *       'a' to packed matrix 'b'.
     *           k = 0
     *           for i in range(0, n):
     *              for j in range(i, n):
     *                  b[k++] = a[i, j]
     *
     * @param Uplo Specifies whether the upper or lower triangular part is to be supplied in the packed form.
     * @param alpha The scalar alpha.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param Ap The input allocation contains matrix A, supported elements type {@link Element#F32_2}.
     */
    public void CHPR(@Uplo int Uplo, float alpha, Allocation X, int incX, Allocation Ap) {
        // equivalent to SPR for validation
        int N = validateSPR(Element.F32_2(mRS), Uplo, X, incX, Ap);
        mRS.nScriptIntrinsicBLAS_Complex(getID(mRS), RsBlas_chpr, 0, 0, 0, Uplo, 0, 0, N, 0, alpha, 0, X.getID(mRS), 0, 0, 0, Ap.getID(mRS), incX, 0, 0, 0);
    }

    /**
     * CHER2 performs the symmetric rank 2 operation
     * A := alpha*x*y**H + alpha*y*x**H + A
     *
     * Details: http://www.netlib.org/lapack/explore-html/db/d87/cher2_8f.html
     *
     * @param Uplo Specifies whether the upper or lower triangular part is to be referenced.
     * @param alpha The scalar alpha.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F32_2}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32_2}.
     */
    public void CHER2(@Uplo int Uplo, Float2 alpha, Allocation X, int incX, Allocation Y, int incY, Allocation A) {
        // same as SYR2
        int N = validateSYR2(Element.F32_2(mRS), Uplo, X, incX, Y, incY, A);
        mRS.nScriptIntrinsicBLAS_Complex(getID(mRS), RsBlas_cher2, 0, 0, 0, Uplo, 0, 0, N, 0, alpha.x, alpha.y, X.getID(mRS), Y.getID(mRS), 0, 0, A.getID(mRS), incX, incY, 0, 0);
    }

    /**
     * CHPR2 performs the symmetric rank 2 operation
     * A := alpha*x*y**H + alpha*y*x**H + A
     *
     * Details: http://www.netlib.org/lapack/explore-html/d6/d44/chpr2_8f.html
     *
     * Note: For a N*N matrix, the input Allocation should be a 1D allocation of size dimX = N*(N+1)/2,
     *       The following subroutine can is an example showing how to convert a UPPER trianglar matrix
     *       'a' to packed matrix 'b'.
     *           k = 0
     *           for i in range(0, n):
     *              for j in range(i, n):
     *                  b[k++] = a[i, j]
     *
     * @param Uplo Specifies whether the upper or lower triangular part is to be supplied in the packed form.
     * @param alpha The scalar alpha.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F32_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F32_2}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     * @param Ap The input allocation contains matrix A, supported elements type {@link Element#F32_2}.
     */
    public void CHPR2(@Uplo int Uplo, Float2 alpha, Allocation X, int incX, Allocation Y, int incY, Allocation Ap) {
        // same as SPR2
        int N = validateSPR2(Element.F32_2(mRS), Uplo, X, incX, Y, incY, Ap);
        mRS.nScriptIntrinsicBLAS_Complex(getID(mRS), RsBlas_chpr2, 0, 0, 0, Uplo, 0, 0, N, 0, alpha.x, alpha.y, X.getID(mRS), Y.getID(mRS), 0, 0, Ap.getID(mRS), incX, incY, 0, 0);
    }

    /**
     * ZHEMV performs the matrix-vector operation
     * y := alpha*A*x + beta*y
     *
     * Details: http://www.netlib.org/lapack/explore-html/d0/ddd/zhemv_8f.html
     *
     * @param Uplo Specifies whether the upper or lower triangular part is to be referenced.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64_2}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param beta The scalar beta.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F64_2}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     */
    public void ZHEMV(@Uplo int Uplo, Double2 alpha, Allocation A, Allocation X, int incX, Double2 beta, Allocation Y, int incY) {
        // HEMV is the same as SYR2 validation-wise
        int N = validateSYR2(Element.F64_2(mRS), Uplo, X, incX, Y, incY, A);
        mRS.nScriptIntrinsicBLAS_Z(getID(mRS), RsBlas_zhemv, 0, 0, 0, Uplo, 0, 0, N, 0, alpha.x, alpha.y, A.getID(mRS), X.getID(mRS), beta.x, beta.y, Y.getID(mRS), incX, incY, 0, 0);
    }

    /**
     * ZHBMV performs the matrix-vector operation
     * y := alpha*A*x + beta*y
     *
     * Details: http://www.netlib.org/lapack/explore-html/d3/d1a/zhbmv_8f.html
     *
     * Note: For a N*N matrix, the input Allocation should also be of size N*N (dimY = N, dimX = N),
     *       but only the region N*(K+1) will be referenced. The following subroutine can is an
     *       example showing how to convert a UPPER trianglar matrix 'a' to row-based band matrix 'b'.
     *           for i in range(0, n):
     *              for j in range(i, min(i+k+1, n)):
     *                  b[i, j-i] = a[i, j]
     *
     * @param Uplo Specifies whether the upper or lower triangular part of the band matrix A is being supplied.
     * @param K The number of off-diagonals of the matrix A
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64_2}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param beta The scalar beta.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F64_2}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     */
    public void ZHBMV(@Uplo int Uplo, int K, Double2 alpha, Allocation A, Allocation X, int incX, Double2 beta, Allocation Y, int incY) {
        // HBMV is the same as SYR2 validation-wise
        int N = validateSYR2(Element.F64_2(mRS), Uplo, X, incX, Y, incY, A);
        if (K < 0) {
            throw new RSRuntimeException("K must be 0 or greater for HBMV");
        }
        mRS.nScriptIntrinsicBLAS_Z(getID(mRS), RsBlas_zhbmv, 0, 0, 0, Uplo, 0, 0, N, K, alpha.x, alpha.y, A.getID(mRS), X.getID(mRS), beta.x, beta.y, Y.getID(mRS), incX, incY, 0, 0);
    }

    /**
     * ZHPMV performs the matrix-vector operation
     * y := alpha*A*x + beta*y
     *
     * Details: http://www.netlib.org/lapack/explore-html/d0/d60/zhpmv_8f.html
     *
     * Note: For a N*N matrix, the input Allocation should be a 1D allocation of size dimX = N*(N+1)/2,
     *       The following subroutine can is an example showing how to convert a UPPER trianglar matrix
     *       'a' to packed matrix 'b'.
     *           k = 0
     *           for i in range(0, n):
     *              for j in range(i, n):
     *                  b[k++] = a[i, j]
     *
     * @param Uplo Specifies whether the upper or lower triangular part of the matrix A is supplied in packed form.
     * @param alpha The scalar alpha.
     * @param Ap The input allocation contains matrix A, supported elements type {@link Element#F64_2}.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param beta The scalar beta.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F64_2}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     */
    public void ZHPMV(@Uplo int Uplo, Double2 alpha, Allocation Ap, Allocation X, int incX, Double2 beta, Allocation Y, int incY) {
        // HPMV is the same as SPR2
        int N = validateSPR2(Element.F64_2(mRS), Uplo, X, incX, Y, incY, Ap);
        mRS.nScriptIntrinsicBLAS_Z(getID(mRS), RsBlas_zhpmv, 0, 0, 0, Uplo, 0, 0, N, 0, alpha.x, alpha.y, Ap.getID(mRS), X.getID(mRS), beta.x, beta.y, Y.getID(mRS), incX, incY, 0, 0);
    }

    /**
     * ZGERU performs the rank 1 operation
     * A := alpha*x*y**T + A
     *
     * Details: http://www.netlib.org/lapack/explore-html/d7/d12/zgeru_8f.html
     *
     * @param alpha The scalar alpha.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F64_2}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64_2}.
     */
    public void ZGERU(Double2 alpha, Allocation X, int incX, Allocation Y, int incY, Allocation A) {
        validateGERU(Element.F64_2(mRS), X, incX, Y, incY, A);
        int M = A.getType().getY();
        int N = A.getType().getX();
        mRS.nScriptIntrinsicBLAS_Z(getID(mRS), RsBlas_zgeru, 0, 0, 0, 0, 0, M, N, 0, alpha.x, alpha.y, X.getID(mRS), Y.getID(mRS), 0, 0, A.getID(mRS), incX, incY, 0, 0);
    }

    /**
     * ZGERC performs the rank 1 operation
     * A := alpha*x*y**H + A
     *
     * Details: http://www.netlib.org/lapack/explore-html/d3/dad/zgerc_8f.html
     *
     * @param alpha The scalar alpha.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F64_2}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64_2}.
     */
    public void ZGERC(Double2 alpha, Allocation X, int incX, Allocation Y, int incY, Allocation A) {
        // same as GERU
        validateGERU(Element.F64_2(mRS), X, incX, Y, incY, A);
        int M = A.getType().getY();
        int N = A.getType().getX();
        mRS.nScriptIntrinsicBLAS_Z(getID(mRS), RsBlas_zgerc, 0, 0, 0, 0, 0, M, N, 0, alpha.x, alpha.y, X.getID(mRS), Y.getID(mRS), 0, 0, A.getID(mRS), incX, incY, 0, 0);
    }

    /**
     * ZHER performs the rank 1 operation
     * A := alpha*x*x**H + A
     *
     * Details: http://www.netlib.org/lapack/explore-html/de/d0e/zher_8f.html
     *
     * @param Uplo Specifies whether the upper or lower triangular part is to be referenced.
     * @param alpha The scalar alpha.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64_2}.
     */
    public void ZHER(@Uplo int Uplo, double alpha, Allocation X, int incX, Allocation A) {
        // same as SYR
        int N = validateSYR(Element.F64_2(mRS), Uplo, X, incX, A);
        mRS.nScriptIntrinsicBLAS_Z(getID(mRS), RsBlas_zher, 0, 0, 0, Uplo, 0, 0, N, 0, alpha, 0, X.getID(mRS), 0, 0, 0, A.getID(mRS), incX, 0, 0, 0);
    }

    /**
     * ZHPR performs the rank 1 operation
     * A := alpha*x*x**H + A
     *
     * Details: http://www.netlib.org/lapack/explore-html/de/de1/zhpr_8f.html
     *
     * Note: For a N*N matrix, the input Allocation should be a 1D allocation of size dimX = N*(N+1)/2,
     *       The following subroutine can is an example showing how to convert a UPPER trianglar matrix
     *       'a' to packed matrix 'b'.
     *           k = 0
     *           for i in range(0, n):
     *              for j in range(i, n):
     *                  b[k++] = a[i, j]
     *
     * @param Uplo Specifies whether the upper or lower triangular part is to be supplied in the packed form.
     * @param alpha The scalar alpha.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param Ap The input allocation contains matrix A, supported elements type {@link Element#F64_2}.
     */
    public void ZHPR(@Uplo int Uplo, double alpha, Allocation X, int incX, Allocation Ap) {
        // equivalent to SPR for validation
        int N = validateSPR(Element.F64_2(mRS), Uplo, X, incX, Ap);
        mRS.nScriptIntrinsicBLAS_Z(getID(mRS), RsBlas_zhpr, 0, 0, 0, Uplo, 0, 0, N, 0, alpha, 0, X.getID(mRS), 0, 0, 0, Ap.getID(mRS), incX, 0, 0, 0);
    }

    /**
     * ZHER2 performs the symmetric rank 2 operation
     * A := alpha*x*y**H + alpha*y*x**H + A
     *
     * Details: http://www.netlib.org/lapack/explore-html/da/d8a/zher2_8f.html
     *
     * @param Uplo Specifies whether the upper or lower triangular part is to be referenced.
     * @param alpha The scalar alpha.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F64_2}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64_2}.
     */
    public void ZHER2(@Uplo int Uplo, Double2 alpha, Allocation X, int incX, Allocation Y, int incY, Allocation A) {
        // same as SYR2
        int N = validateSYR2(Element.F64_2(mRS), Uplo, X, incX, Y, incY, A);
        mRS.nScriptIntrinsicBLAS_Z(getID(mRS), RsBlas_zher2, 0, 0, 0, Uplo, 0, 0, N, 0, alpha.x, alpha.y, X.getID(mRS), Y.getID(mRS), 0, 0, A.getID(mRS), incX, incY, 0, 0);
    }

    /**
     * ZHPR2 performs the symmetric rank 2 operation
     * A := alpha*x*y**H + alpha*y*x**H + A
     *
     * Details: http://www.netlib.org/lapack/explore-html/d5/d52/zhpr2_8f.html
     *
     * Note: For a N*N matrix, the input Allocation should be a 1D allocation of size dimX = N*(N+1)/2,
     *       The following subroutine can is an example showing how to convert a UPPER trianglar matrix
     *       'a' to packed matrix 'b'.
     *           k = 0
     *           for i in range(0, n):
     *              for j in range(i, n):
     *                  b[k++] = a[i, j]
     *
     * @param Uplo Specifies whether the upper or lower triangular part is to be supplied in the packed form.
     * @param alpha The scalar alpha.
     * @param X The input allocation contains vector x, supported elements type {@link Element#F64_2}.
     * @param incX The increment for the elements of vector x, must be larger than zero.
     * @param Y The input allocation contains vector y, supported elements type {@link Element#F64_2}.
     * @param incY The increment for the elements of vector y, must be larger than zero.
     * @param Ap The input allocation contains matrix A, supported elements type {@link Element#F64_2}.
     */
    public void ZHPR2(@Uplo int Uplo, Double2 alpha, Allocation X, int incX, Allocation Y, int incY, Allocation Ap) {
        // same as SPR2
        int N = validateSPR2(Element.F64_2(mRS), Uplo, X, incX, Y, incY, Ap);
        mRS.nScriptIntrinsicBLAS_Z(getID(mRS), RsBlas_zhpr2, 0, 0, 0, Uplo, 0, 0, N, 0, alpha.x, alpha.y, X.getID(mRS), Y.getID(mRS), 0, 0, Ap.getID(mRS), incX, incY, 0, 0);
    }


    /**
     * Level 3 BLAS
     */

    static void validateL3(Element e, int TransA, int TransB, int Side, Allocation A, Allocation B, Allocation C) {
        int aM = -1, aN = -1, bM = -1, bN = -1, cM = -1, cN = -1;
        if ((A != null && !A.getType().getElement().isCompatible(e)) ||
            (B != null && !B.getType().getElement().isCompatible(e)) ||
            (C != null && !C.getType().getElement().isCompatible(e))) {
            throw new RSRuntimeException("Called BLAS with wrong Element type");
        }
        if (C == null) {
            //since matrix C is used to store the result, it cannot be null.
            throw new RSRuntimeException("Allocation C cannot be null");
        }
        cM = C.getType().getY();
        cN = C.getType().getX();

        if (Side == RIGHT) {
            if ((A == null && B != null) || (A != null && B == null)) {
                throw new RSRuntimeException("Provided Matrix A without Matrix B, or vice versa");
            }
            if (B != null) {
                bM = A.getType().getY();
                bN = A.getType().getX();
            }
            if (A != null) {
                aM = B.getType().getY();
                aN = B.getType().getX();
            }
        } else {
            if (A != null) {
                if (TransA == TRANSPOSE || TransA == CONJ_TRANSPOSE) {
                    aN = A.getType().getY();
                    aM = A.getType().getX();
                } else {
                    aM = A.getType().getY();
                    aN = A.getType().getX();
                }
            }
            if (B != null) {
                if (TransB == TRANSPOSE || TransB == CONJ_TRANSPOSE) {
                    bN = B.getType().getY();
                    bM = B.getType().getX();
                } else {
                    bM = B.getType().getY();
                    bN = B.getType().getX();
                }
            }
        }
        if (A != null && B != null && C != null) {
            if (aN != bM || aM != cM || bN != cN) {
                throw new RSRuntimeException("Called BLAS with invalid dimensions");
            }
        } else if (A != null && C != null) {
            // A and C only, for SYRK
            if (cM != cN) {
                throw new RSRuntimeException("Matrix C is not symmetric");
            }
            if (aM != cM) {
                throw new RSRuntimeException("Called BLAS with invalid dimensions");
            }
        } else if (A != null && B != null) {
            // A and B only
            if (aN != bM) {
                throw new RSRuntimeException("Called BLAS with invalid dimensions");
            }
        }

    }

    /**
     * SGEMM performs one of the matrix-matrix operations
     * C := alpha*op(A)*op(B) + beta*C   where op(X) is one of op(X) = X  or  op(X) = X**T
     *
     * Details: http://www.netlib.org/lapack/explore-html/d4/de2/sgemm_8f.html
     *
     * @param TransA The type of transpose applied to matrix A.
     * @param TransB The type of transpose applied to matrix B.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32}.
     * @param B The input allocation contains matrix B, supported elements type {@link Element#F32}.
     * @param beta The scalar beta.
     * @param C The input allocation contains matrix C, supported elements type {@link Element#F32}.
     */
    public void SGEMM(@Transpose int TransA, @Transpose int TransB, float alpha, Allocation A,
                      Allocation B, float beta, Allocation C) {
        validateTranspose(TransA);
        validateTranspose(TransB);
        validateL3(Element.F32(mRS), TransA, TransB, 0, A, B, C);

        int M = -1, N = -1, K = -1;
        if (TransA != NO_TRANSPOSE) {
            M = A.getType().getX();
            K = A.getType().getY();
        } else {
            M = A.getType().getY();
            K = A.getType().getX();
        }
        if (TransB != NO_TRANSPOSE) {
            N = B.getType().getY();
        } else {
            N = B.getType().getX();
        }
        mRS.nScriptIntrinsicBLAS_Single(getID(mRS), RsBlas_sgemm, TransA, TransB, 0, 0, 0, M, N, K,  alpha, A.getID(mRS), B.getID(mRS),
                                        beta, C.getID(mRS), 0, 0, 0, 0);
    }

    /**
     * DGEMM performs one of the matrix-matrix operations
     * C := alpha*op(A)*op(B) + beta*C   where op(X) is one of op(X) = X  or  op(X) = X**T
     *
     * Details: http://www.netlib.org/lapack/explore-html/d7/d2b/dgemm_8f.html
     *
     * @param TransA The type of transpose applied to matrix A.
     * @param TransB The type of transpose applied to matrix B.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64}.
     * @param B The input allocation contains matrix B, supported elements type {@link Element#F64}.
     * @param beta The scalar beta.
     * @param C The input allocation contains matrix C, supported elements type {@link Element#F64}.
     */
    public void DGEMM(@Transpose int TransA, @Transpose int TransB, double alpha, Allocation A,
                      Allocation B, double beta, Allocation C) {
        validateTranspose(TransA);
        validateTranspose(TransB);
        validateL3(Element.F64(mRS), TransA, TransB, 0, A, B, C);
        int M = -1, N = -1, K = -1;
        if (TransA != NO_TRANSPOSE) {
            M = A.getType().getX();
            K = A.getType().getY();
        } else {
            M = A.getType().getY();
            K = A.getType().getX();
        }
        if (TransB != NO_TRANSPOSE) {
            N = B.getType().getY();
        } else {
            N = B.getType().getX();
        }
        mRS.nScriptIntrinsicBLAS_Double(getID(mRS), RsBlas_dgemm, TransA, TransB, 0, 0, 0, M, N, K,  alpha, A.getID(mRS), B.getID(mRS),
                                        beta, C.getID(mRS), 0, 0, 0, 0);
    }

    /**
     * CGEMM performs one of the matrix-matrix operations
     * C := alpha*op(A)*op(B) + beta*C   where op(X) is one of op(X) = X  or  op(X) = X**T  or  op(X) = X**H
     *
     * Details: http://www.netlib.org/lapack/explore-html/d6/d5b/cgemm_8f.html
     *
     * @param TransA The type of transpose applied to matrix A.
     * @param TransB The type of transpose applied to matrix B.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32_2}.
     * @param B The input allocation contains matrix B, supported elements type {@link Element#F32_2}.
     * @param beta The scalar beta.
     * @param C The input allocation contains matrix C, supported elements type {@link Element#F32_2}.
     */
    public void CGEMM(@Transpose int TransA, @Transpose int TransB, Float2 alpha, Allocation A,
                      Allocation B, Float2 beta, Allocation C) {
        validateTranspose(TransA);
        validateTranspose(TransB);
        validateL3(Element.F32_2(mRS), TransA, TransB, 0, A, B, C);
        int M = -1, N = -1, K = -1;
        if (TransA != NO_TRANSPOSE) {
            M = A.getType().getX();
            K = A.getType().getY();
        } else {
            M = A.getType().getY();
            K = A.getType().getX();
        }
        if (TransB != NO_TRANSPOSE) {
            N = B.getType().getY();
        } else {
            N = B.getType().getX();
        }
        mRS.nScriptIntrinsicBLAS_Complex(getID(mRS), RsBlas_cgemm, TransA, TransB, 0, 0, 0, M, N, K,  alpha.x, alpha.y, A.getID(mRS), B.getID(mRS),
                                         beta.x, beta.y, C.getID(mRS), 0, 0, 0, 0);
    }

    /**
     * ZGEMM performs one of the matrix-matrix operations
     * C := alpha*op(A)*op(B) + beta*C   where op(X) is one of op(X) = X  or  op(X) = X**T  or  op(X) = X**H
     *
     * Details: http://www.netlib.org/lapack/explore-html/d7/d76/zgemm_8f.html
     *
     * @param TransA The type of transpose applied to matrix A.
     * @param TransB The type of transpose applied to matrix B.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64_2}.
     * @param B The input allocation contains matrix B, supported elements type {@link Element#F64_2}.
     * @param beta The scalar beta.
     * @param C The input allocation contains matrix C, supported elements type {@link Element#F64_2}.
     */
    public void ZGEMM(@Transpose int TransA, @Transpose int TransB, Double2 alpha, Allocation A,
                      Allocation B, Double2 beta, Allocation C) {
        validateTranspose(TransA);
        validateTranspose(TransB);
        validateL3(Element.F64_2(mRS), TransA, TransB, 0, A, B, C);
        int M = -1, N = -1, K = -1;
        if (TransA != NO_TRANSPOSE) {
            M = A.getType().getX();
            K = A.getType().getY();
        } else {
            M = A.getType().getY();
            K = A.getType().getX();
        }
        if (TransB != NO_TRANSPOSE) {
            N = B.getType().getY();
        } else {
            N = B.getType().getX();
        }
        mRS.nScriptIntrinsicBLAS_Z(getID(mRS), RsBlas_zgemm, TransA, TransB, 0, 0, 0, M, N, K,  alpha.x, alpha.y, A.getID(mRS), B.getID(mRS),
                                   beta.x, beta.y, C.getID(mRS), 0, 0, 0, 0);
    }

    /**
     * SSYMM performs one of the matrix-matrix operations
     * C := alpha*A*B + beta*C   or   C := alpha*B*A + beta*C
     *
     * Details: http://www.netlib.org/lapack/explore-html/d7/d42/ssymm_8f.html
     *
     * @param Side Specifies whether the symmetric matrix A appears on the left or right.
     * @param Uplo Specifies whether the upper or lower triangular part is to be referenced.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32}.
     * @param B The input allocation contains matrix B, supported elements type {@link Element#F32}.
     * @param beta The scalar beta.
     * @param C The input allocation contains matrix C, supported elements type {@link Element#F32}.
     */
    public void SSYMM(@Side int Side, @Uplo int Uplo, float alpha, Allocation A,
                      Allocation B, float beta, Allocation C) {
        validateSide(Side);
        validateUplo(Uplo);
        //For SYMM, Matrix A should be symmetric
        if (A.getType().getX() != A.getType().getY()) {
            throw new RSRuntimeException("Matrix A is not symmetric");
        }
        validateL3(Element.F32(mRS), 0, 0, Side, A, B, C);
        mRS.nScriptIntrinsicBLAS_Single(getID(mRS), RsBlas_ssymm, 0, 0, Side, Uplo, 0, C.getType().getY(), C.getType().getX(), 0, alpha, A.getID(mRS), B.getID(mRS),
                                        beta, C.getID(mRS), 0, 0, 0, 0);
    }

    /**
     * DSYMM performs one of the matrix-matrix operations
     * C := alpha*A*B + beta*C   or   C := alpha*B*A + beta*C
     *
     * Details: http://www.netlib.org/lapack/explore-html/d8/db0/dsymm_8f.html
     *
     * @param Side Specifies whether the symmetric matrix A appears on the left or right.
     * @param Uplo Specifies whether the upper or lower triangular part is to be referenced.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64}.
     * @param B The input allocation contains matrix B, supported elements type {@link Element#F64}.
     * @param beta The scalar beta.
     * @param C The input allocation contains matrix C, supported elements type {@link Element#F64}.
     */
    public void DSYMM(@Side int Side, @Uplo int Uplo, double alpha, Allocation A,
                      Allocation B, double beta, Allocation C) {
        validateSide(Side);
        validateUplo(Uplo);
        if (A.getType().getX() != A.getType().getY()) {
            throw new RSRuntimeException("Matrix A is not symmetric");
        }
        validateL3(Element.F64(mRS), 0, 0, Side, A, B, C);
        mRS.nScriptIntrinsicBLAS_Double(getID(mRS), RsBlas_dsymm, 0, 0, Side, Uplo, 0, C.getType().getY(), C.getType().getX(), 0, alpha, A.getID(mRS), B.getID(mRS),
                                        beta, C.getID(mRS), 0, 0, 0, 0);
    }

    /**
     * CSYMM performs one of the matrix-matrix operations
     * C := alpha*A*B + beta*C   or   C := alpha*B*A + beta*C
     *
     * Details: http://www.netlib.org/lapack/explore-html/db/d59/csymm_8f.html
     *
     * @param Side Specifies whether the symmetric matrix A appears on the left or right.
     * @param Uplo Specifies whether the upper or lower triangular part is to be referenced.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32_2}.
     * @param B The input allocation contains matrix B, supported elements type {@link Element#F32_2}.
     * @param beta The scalar beta.
     * @param C The input allocation contains matrix C, supported elements type {@link Element#F32_2}.
     */
    public void CSYMM(@Side int Side, @Uplo int Uplo, Float2 alpha, Allocation A,
                      Allocation B, Float2 beta, Allocation C) {
        validateSide(Side);
        validateUplo(Uplo);
        if (A.getType().getX() != A.getType().getY()) {
            throw new RSRuntimeException("Matrix A is not symmetric");
        }
        validateL3(Element.F32_2(mRS), 0, 0, Side, A, B, C);
        mRS.nScriptIntrinsicBLAS_Complex(getID(mRS), RsBlas_csymm, 0, 0, Side, Uplo, 0, C.getType().getY(), C.getType().getX(), 0, alpha.x, alpha.y, A.getID(mRS), B.getID(mRS),
                                         beta.x, beta.y, C.getID(mRS), 0, 0, 0, 0);
    }

    /**
     * ZSYMM performs one of the matrix-matrix operations
     * C := alpha*A*B + beta*C   or   C := alpha*B*A + beta*C
     *
     * Details: http://www.netlib.org/lapack/explore-html/df/d51/zsymm_8f.html
     *
     * @param Side Specifies whether the symmetric matrix A appears on the left or right.
     * @param Uplo Specifies whether the upper or lower triangular part is to be referenced.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64_2}.
     * @param B The input allocation contains matrix B, supported elements type {@link Element#F64_2}.
     * @param beta The scalar beta.
     * @param C The input allocation contains matrix C, supported elements type {@link Element#F64_2}.
     */
    public void ZSYMM(@Side int Side, @Uplo int Uplo, Double2 alpha, Allocation A,
                      Allocation B, Double2 beta, Allocation C) {
        validateSide(Side);
        validateUplo(Uplo);
        if (A.getType().getX() != A.getType().getY()) {
            throw new RSRuntimeException("Matrix A is not symmetric");
        }
        validateL3(Element.F64_2(mRS), 0, 0, Side, A, B, C);
        mRS.nScriptIntrinsicBLAS_Z(getID(mRS), RsBlas_zsymm, 0, 0, Side, Uplo, 0, C.getType().getY(), C.getType().getX(), 0, alpha.x, alpha.y, A.getID(mRS), B.getID(mRS),
                                   beta.x, beta.y, C.getID(mRS), 0, 0, 0, 0);
    }

    /**
     * SSYRK performs one of the symmetric rank k operations
     * C := alpha*A*A**T + beta*C   or   C := alpha*A**T*A + beta*C
     *
     * Details: http://www.netlib.org/lapack/explore-html/d0/d40/ssyrk_8f.html
     *
     * @param Uplo Specifies whether the upper or lower triangular part of C is to be referenced.
     * @param Trans The type of transpose applied to the operation.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32}.
     * @param beta The scalar beta.
     * @param C The input allocation contains matrix C, supported elements type {@link Element#F32}.
     */
    public void SSYRK(@Uplo int Uplo, @Transpose int Trans, float alpha, Allocation A, float beta, Allocation C) {
        validateTranspose(Trans);
        validateUplo(Uplo);
        validateL3(Element.F32(mRS), Trans, 0, 0, A, null, C);
        int K = -1;
        if (Trans != NO_TRANSPOSE) {
            K = A.getType().getY();
        } else {
            K = A.getType().getX();
        }

        mRS.nScriptIntrinsicBLAS_Single(getID(mRS), RsBlas_ssyrk, Trans, 0, 0, Uplo, 0, 0, C.getType().getX(), K, alpha, A.getID(mRS), 0, beta, C.getID(mRS), 0, 0, 0, 0);
    }

    /**
     * DSYRK performs one of the symmetric rank k operations
     * C := alpha*A*A**T + beta*C   or   C := alpha*A**T*A + beta*C
     *
     * Details: http://www.netlib.org/lapack/explore-html/dc/d05/dsyrk_8f.html
     *
     * @param Uplo Specifies whether the upper or lower triangular part of C is to be referenced.
     * @param Trans The type of transpose applied to the operation.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64}.
     * @param beta The scalar beta.
     * @param C The input allocation contains matrix C, supported elements type {@link Element#F64}.
     */
    public void DSYRK(@Uplo int Uplo, @Transpose int Trans, double alpha, Allocation A, double beta, Allocation C) {
        validateTranspose(Trans);
        validateUplo(Uplo);
        validateL3(Element.F64(mRS), Trans, 0, 0, A, null, C);
        int K = -1;
        if (Trans != NO_TRANSPOSE) {
            K = A.getType().getY();
        } else {
            K = A.getType().getX();
        }
        mRS.nScriptIntrinsicBLAS_Double(getID(mRS), RsBlas_dsyrk, Trans, 0, 0, Uplo, 0, 0, C.getType().getX(), K, alpha, A.getID(mRS), 0, beta, C.getID(mRS), 0, 0, 0, 0);
    }

    /**
     * CSYRK performs one of the symmetric rank k operations
     * C := alpha*A*A**T + beta*C   or   C := alpha*A**T*A + beta*C
     *
     * Details: http://www.netlib.org/lapack/explore-html/d3/d6a/csyrk_8f.html
     *
     * @param Uplo Specifies whether the upper or lower triangular part of C is to be referenced.
     * @param Trans The type of transpose applied to the operation.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32_2}.
     * @param beta The scalar beta.
     * @param C The input allocation contains matrix C, supported elements type {@link Element#F32_2}.
     */
    public void CSYRK(@Uplo int Uplo, @Transpose int Trans, Float2 alpha, Allocation A, Float2 beta, Allocation C) {
        validateTranspose(Trans);
        validateUplo(Uplo);
        validateL3(Element.F32_2(mRS), Trans, 0, 0, A, null, C);
        int K = -1;
        if (Trans != NO_TRANSPOSE) {
            K = A.getType().getY();
        } else {
            K = A.getType().getX();
        }
        mRS.nScriptIntrinsicBLAS_Complex(getID(mRS), RsBlas_csyrk, Trans, 0, 0, Uplo, 0, 0, C.getType().getX(), K, alpha.x, alpha.y, A.getID(mRS), 0, beta.x, beta.y,
                                         C.getID(mRS), 0, 0, 0, 0);
    }

    /**
     * ZSYRK performs one of the symmetric rank k operations
     * C := alpha*A*A**T + beta*C   or   C := alpha*A**T*A + beta*C
     *
     * Details: http://www.netlib.org/lapack/explore-html/de/d54/zsyrk_8f.html
     *
     * @param Uplo Specifies whether the upper or lower triangular part of C is to be referenced.
     * @param Trans The type of transpose applied to the operation.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64_2}.
     * @param beta The scalar beta.
     * @param C The input allocation contains matrix C, supported elements type {@link Element#F64_2}.
     */
    public void ZSYRK(@Uplo int Uplo, @Transpose int Trans, Double2 alpha, Allocation A, Double2 beta, Allocation C) {
        validateTranspose(Trans);
        validateUplo(Uplo);
        validateL3(Element.F64_2(mRS), Trans, 0, 0, A, null, C);
        int K = -1;
        if (Trans != NO_TRANSPOSE) {
            K = A.getType().getY();
        } else {
            K = A.getType().getX();
        }
        mRS.nScriptIntrinsicBLAS_Z(getID(mRS), RsBlas_zsyrk, Trans, 0, 0, Uplo, 0, 0, C.getType().getX(), K, alpha.x, alpha.y, A.getID(mRS), 0, beta.x, beta.y,
                                   C.getID(mRS), 0, 0, 0, 0);
    }

    static void validateSYR2K(Element e, @Transpose int Trans, Allocation A, Allocation B, Allocation C) {
        validateTranspose(Trans);
        if (!A.getType().getElement().isCompatible(e) ||
            !B.getType().getElement().isCompatible(e) ||
            !C.getType().getElement().isCompatible(e)) {
            throw new RSRuntimeException("Called BLAS with wrong Element type");
        }
        int Cdim = -1;
        // A is n x k if no transpose, k x n if transpose
        // C is n x n
        if (Trans == TRANSPOSE) {
            // check columns versus C
            Cdim = A.getType().getX();
        } else {
            // check rows versus C
            Cdim = A.getType().getY();
        }
        if (C.getType().getX() != Cdim || C.getType().getY() != Cdim) {
            throw new RSRuntimeException("Invalid symmetric matrix in SYR2K");
        }
        // A dims == B dims
        if (A.getType().getX() != B.getType().getX() || A.getType().getY() != B.getType().getY()) {
            throw new RSRuntimeException("Invalid A and B in SYR2K");
        }
    }

    /**
     * SSYR2K performs one of the symmetric rank 2k operations
     * C := alpha*A*B**T + alpha*B*A**T + beta*C   or   C := alpha*A**T*B + alpha*B**T*A + beta*C
     *
     * Details: http://www.netlib.org/lapack/explore-html/df/d3d/ssyr2k_8f.html
     *
     * @param Uplo Specifies whether the upper or lower triangular part of C is to be referenced.
     * @param Trans The type of transpose applied to the operation.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32}.
     * @param B The input allocation contains matrix B, supported elements type {@link Element#F32}.
     * @param beta The scalar beta.
     * @param C The input allocation contains matrix C, supported elements type {@link Element#F32}.
     */
    public void SSYR2K(@Uplo int Uplo, @Transpose int Trans, float alpha, Allocation A, Allocation B, float beta, Allocation C) {
        validateUplo(Uplo);
        validateSYR2K(Element.F32(mRS), Trans, A, B, C);
        int K = -1;
        if (Trans != NO_TRANSPOSE) {
            K = A.getType().getY();
        } else {
            K = A.getType().getX();
        }
        mRS.nScriptIntrinsicBLAS_Single(getID(mRS), RsBlas_ssyr2k, Trans, 0, 0, Uplo, 0, 0, C.getType().getX(), K, alpha, A.getID(mRS), B.getID(mRS), beta, C.getID(mRS), 0, 0, 0, 0);
    }

    /**
     * DSYR2K performs one of the symmetric rank 2k operations
     * C := alpha*A*B**T + alpha*B*A**T + beta*C   or   C := alpha*A**T*B + alpha*B**T*A + beta*C
     *
     * Details: http://www.netlib.org/lapack/explore-html/d1/dec/dsyr2k_8f.html
     *
     * @param Uplo Specifies whether the upper or lower triangular part of C is to be referenced.
     * @param Trans The type of transpose applied to the operation.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64}.
     * @param B The input allocation contains matrix B, supported elements type {@link Element#F64}.
     * @param beta The scalar beta.
     * @param C The input allocation contains matrix C, supported elements type {@link Element#F64}.
     */
    public void DSYR2K(@Uplo int Uplo, @Transpose int Trans, double alpha, Allocation A, Allocation B, double beta, Allocation C) {
        validateUplo(Uplo);
        validateSYR2K(Element.F64(mRS), Trans, A, B, C);
        int K = -1;
        if (Trans != NO_TRANSPOSE) {
            K = A.getType().getY();
        } else {
            K = A.getType().getX();
        }
        mRS.nScriptIntrinsicBLAS_Double(getID(mRS), RsBlas_dsyr2k, Trans, 0, 0, Uplo, 0, 0, C.getType().getX(), K, alpha, A.getID(mRS), B.getID(mRS), beta, C.getID(mRS), 0, 0, 0, 0);
    }

    /**
     * CSYR2K performs one of the symmetric rank 2k operations
     * C := alpha*A*B**T + alpha*B*A**T + beta*C   or   C := alpha*A**T*B + alpha*B**T*A + beta*C
     *
     * Details: http://www.netlib.org/lapack/explore-html/de/d7e/csyr2k_8f.html
     *
     * @param Uplo Specifies whether the upper or lower triangular part of C is to be referenced.
     * @param Trans The type of transpose applied to the operation.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32_2}.
     * @param B The input allocation contains matrix B, supported elements type {@link Element#F32_2}.
     * @param beta The scalar beta.
     * @param C The input allocation contains matrix C, supported elements type {@link Element#F32_2}.
     */
    public void CSYR2K(@Uplo int Uplo, @Transpose int Trans, Float2 alpha, Allocation A, Allocation B, Float2 beta, Allocation C) {
        validateUplo(Uplo);
        validateSYR2K(Element.F32_2(mRS), Trans, A, B, C);
        int K = -1;
        if (Trans != NO_TRANSPOSE) {
            K = A.getType().getY();
        } else {
            K = A.getType().getX();
        }
        mRS.nScriptIntrinsicBLAS_Complex(getID(mRS), RsBlas_csyr2k, Trans, 0, 0, Uplo, 0, 0, C.getType().getX(), K, alpha.x, alpha.y, A.getID(mRS), B.getID(mRS), beta.x, beta.y, C.getID(mRS), 0, 0, 0, 0);
    }

    /**
     * ZSYR2K performs one of the symmetric rank 2k operations
     * C := alpha*A*B**T + alpha*B*A**T + beta*C   or   C := alpha*A**T*B + alpha*B**T*A + beta*C
     *
     * Details: http://www.netlib.org/lapack/explore-html/df/d20/zsyr2k_8f.html
     *
     * @param Uplo Specifies whether the upper or lower triangular part of C is to be referenced.
     * @param Trans The type of transpose applied to the operation.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64_2}.
     * @param B The input allocation contains matrix B, supported elements type {@link Element#F64_2}.
     * @param beta The scalar beta.
     * @param C The input allocation contains matrix C, supported elements type {@link Element#F64_2}.
     */
    public void ZSYR2K(@Uplo int Uplo, @Transpose int Trans, Double2 alpha, Allocation A, Allocation B, Double2 beta, Allocation C) {
        validateUplo(Uplo);
        validateSYR2K(Element.F64_2(mRS), Trans, A, B, C);
        int K = -1;
        if (Trans != NO_TRANSPOSE) {
            K = A.getType().getY();
        } else {
            K = A.getType().getX();
        }
        mRS.nScriptIntrinsicBLAS_Z(getID(mRS), RsBlas_zsyr2k, Trans, 0, 0, Uplo, 0, 0, C.getType().getX(), K, alpha.x, alpha.y, A.getID(mRS), B.getID(mRS), beta.x, beta.y, C.getID(mRS), 0, 0, 0, 0);
    }

    static void validateTRMM(Element e, @Side int Side, @Transpose int TransA, Allocation A, Allocation B) {
        validateSide(Side);
        validateTranspose(TransA);
        int aM = -1, aN = -1, bM = -1, bN = -1;
        if (!A.getType().getElement().isCompatible(e) ||
            !B.getType().getElement().isCompatible(e)) {
            throw new RSRuntimeException("Called BLAS with wrong Element type");
        }

        aM = A.getType().getY();
        aN = A.getType().getX();
        if (aM != aN) {
            throw new RSRuntimeException("Called TRMM with a non-symmetric matrix A");
        }

        bM = B.getType().getY();
        bN = B.getType().getX();
        if (Side == LEFT) {
            if (aN != bM) {
                throw new RSRuntimeException("Called TRMM with invalid matrices");
            }
        } else {
            if (bN != aM) {
                throw new RSRuntimeException("Called TRMM with invalid matrices");
            }
        }
    }

    /**
     * STRMM performs one of the matrix-matrix operations
     * B := alpha*op(A)*B   or   B := alpha*B*op(A)
     * op(A) is one of  op(A) = A  or  op(A) = A**T
     *
     * Details: http://www.netlib.org/lapack/explore-html/df/d01/strmm_8f.html
     *
     * @param Side Specifies whether the symmetric matrix A appears on the left or right.
     * @param Uplo Specifies whether matrix A is upper or lower triangular.
     * @param TransA The type of transpose applied to matrix A.
     * @param Diag Specifies whether or not A is unit triangular.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32}.
     * @param B The input allocation contains matrix B, supported elements type {@link Element#F32}.
     */
    public void STRMM(@Side int Side, @Uplo int Uplo, @Transpose int TransA, @Diag int Diag, float alpha, Allocation A, Allocation B) {
        validateUplo(Uplo);
        validateDiag(Diag);
        validateTRMM(Element.F32(mRS), Side, TransA, A, B);
        mRS.nScriptIntrinsicBLAS_Single(getID(mRS), RsBlas_strmm, TransA, 0, Side, Uplo, Diag, B.getType().getY(), B.getType().getX(), 0,
                                        alpha, A.getID(mRS), B.getID(mRS), 0.f, 0, 0, 0, 0, 0);
    }

    /**
     * DTRMM performs one of the matrix-matrix operations
     * B := alpha*op(A)*B   or   B := alpha*B*op(A)
     * op(A) is one of  op(A) = A  or  op(A) = A**T
     *
     * Details: http://www.netlib.org/lapack/explore-html/dd/d19/dtrmm_8f.html
     *
     * @param Side Specifies whether the symmetric matrix A appears on the left or right.
     * @param Uplo Specifies whether matrix A is upper or lower triangular.
     * @param TransA The type of transpose applied to matrix A.
     * @param Diag Specifies whether or not A is unit triangular.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64}.
     * @param B The input allocation contains matrix B, supported elements type {@link Element#F64}.
     */
    public void DTRMM(@Side int Side, @Uplo int Uplo, @Transpose int TransA, @Diag int Diag, double alpha, Allocation A, Allocation B) {
        validateUplo(Uplo);
        validateDiag(Diag);
        validateTRMM(Element.F64(mRS), Side, TransA, A, B);
        mRS.nScriptIntrinsicBLAS_Double(getID(mRS), RsBlas_dtrmm, TransA, 0, Side, Uplo, Diag, B.getType().getY(), B.getType().getX(), 0,
                                        alpha, A.getID(mRS), B.getID(mRS), 0, 0, 0, 0, 0, 0);
    }

    /**
     * CTRMM performs one of the matrix-matrix operations
     * B := alpha*op(A)*B   or   B := alpha*B*op(A)
     * op(A) is one of  op(A) = A  or  op(A) = A**T  or  op(A) = A**H
     *
     * Details: http://www.netlib.org/lapack/explore-html/d4/d9b/ctrmm_8f.html
     *
     * @param Side Specifies whether the symmetric matrix A appears on the left or right.
     * @param Uplo Specifies whether matrix A is upper or lower triangular.
     * @param TransA The type of transpose applied to matrix A.
     * @param Diag Specifies whether or not A is unit triangular.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32_2}.
     * @param B The input allocation contains matrix B, supported elements type {@link Element#F32_2}.
     */
    public void CTRMM(@Side int Side, @Uplo int Uplo, @Transpose int TransA, @Diag int Diag, Float2 alpha, Allocation A, Allocation B) {
        validateUplo(Uplo);
        validateDiag(Diag);
        validateTRMM(Element.F32_2(mRS), Side, TransA, A, B);
        mRS.nScriptIntrinsicBLAS_Complex(getID(mRS), RsBlas_ctrmm, TransA, 0, Side, Uplo, Diag, B.getType().getY(), B.getType().getX(), 0,
                                         alpha.x, alpha.y, A.getID(mRS), B.getID(mRS), 0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * ZTRMM performs one of the matrix-matrix operations
     * B := alpha*op(A)*B   or   B := alpha*B*op(A)
     * op(A) is one of  op(A) = A  or  op(A) = A**T  or  op(A) = A**H
     *
     * Details: http://www.netlib.org/lapack/explore-html/d8/de1/ztrmm_8f.html
     *
     * @param Side Specifies whether the symmetric matrix A appears on the left or right.
     * @param Uplo Specifies whether matrix A is upper or lower triangular.
     * @param TransA The type of transpose applied to matrix A.
     * @param Diag Specifies whether or not A is unit triangular.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64_2}.
     * @param B The input allocation contains matrix B, supported elements type {@link Element#F64_2}.
     */
    public void ZTRMM(@Side int Side, @Uplo int Uplo, @Transpose int TransA, @Diag int Diag, Double2 alpha, Allocation A, Allocation B) {
        validateUplo(Uplo);
        validateDiag(Diag);
        validateTRMM(Element.F64_2(mRS), Side, TransA, A, B);
        mRS.nScriptIntrinsicBLAS_Z(getID(mRS), RsBlas_ztrmm, TransA, 0, Side, Uplo, Diag, B.getType().getY(), B.getType().getX(), 0,
                                   alpha.x, alpha.y, A.getID(mRS), B.getID(mRS), 0, 0, 0, 0, 0, 0, 0);
    }

    static void validateTRSM(Element e, @Side int Side, @Transpose int TransA, Allocation A, Allocation B) {
        int adim = -1, bM = -1, bN = -1;
        validateSide(Side);
        validateTranspose(TransA);
        if (!A.getType().getElement().isCompatible(e) ||
            !B.getType().getElement().isCompatible(e)) {
            throw new RSRuntimeException("Called BLAS with wrong Element type");
        }
        adim = A.getType().getX();
        if (adim != A.getType().getY()) {
            // this may be unnecessary, the restriction could potentially be relaxed
            // A needs to contain at least that symmetric matrix but could theoretically be larger
            // for now we assume adapters are sufficient, will reevaluate in the future
            throw new RSRuntimeException("Called TRSM with a non-symmetric matrix A");
        }
        bM = B.getType().getY();
        bN = B.getType().getX();
        if (Side == LEFT) {
            // A is M*M
            if (adim != bM) {
                throw new RSRuntimeException("Called TRSM with invalid matrix dimensions");
            }
        } else {
            // A is N*N
            if (adim != bN) {
                throw new RSRuntimeException("Called TRSM with invalid matrix dimensions");
            }
        }
    }

    /**
     * STRSM solves one of the matrix equations
     * op(A)*X := alpha*B   or   X*op(A) := alpha*B
     * op(A) is one of  op(A) = A  or  op(A) = A**T
     *
     * Details: http://www.netlib.org/lapack/explore-html/d2/d8b/strsm_8f.html
     *
     * @param Side Specifies whether the symmetric matrix A appears on the left or right.
     * @param Uplo Specifies whether matrix A is upper or lower triangular.
     * @param TransA The type of transpose applied to matrix A.
     * @param Diag Specifies whether or not A is unit triangular.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32}.
     * @param B The input allocation contains matrix B, supported elements type {@link Element#F32}.
     */
    public void STRSM(@Side int Side, @Uplo int Uplo, @Transpose int TransA, @Diag int Diag, float alpha, Allocation A, Allocation B) {
        validateUplo(Uplo);
        validateDiag(Diag);
        validateTRSM(Element.F32(mRS), Side, TransA, A, B);
        mRS.nScriptIntrinsicBLAS_Single(getID(mRS), RsBlas_strsm, TransA, 0, Side, Uplo, Diag, B.getType().getY(), B.getType().getX(), 0,
                                        alpha, A.getID(mRS), B.getID(mRS), 0, 0, 0, 0, 0, 0);
    }

    /**
     * DTRSM solves one of the matrix equations
     * op(A)*X := alpha*B   or   X*op(A) := alpha*B
     * op(A) is one of  op(A) = A  or  op(A) = A**T
     *
     * Details: http://www.netlib.org/lapack/explore-html/de/da7/dtrsm_8f.html
     *
     * @param Side Specifies whether the symmetric matrix A appears on the left or right.
     * @param Uplo Specifies whether matrix A is upper or lower triangular.
     * @param TransA The type of transpose applied to matrix A.
     * @param Diag Specifies whether or not A is unit triangular.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64}.
     * @param B The input allocation contains matrix B, supported elements type {@link Element#F64}.
     */
    public void DTRSM(@Side int Side, @Uplo int Uplo, @Transpose int TransA, @Diag int Diag, double alpha, Allocation A, Allocation B) {
        validateUplo(Uplo);
        validateDiag(Diag);
        validateTRSM(Element.F64(mRS), Side, TransA, A, B);
        mRS.nScriptIntrinsicBLAS_Double(getID(mRS), RsBlas_dtrsm, TransA, 0, Side, Uplo, Diag, B.getType().getY(), B.getType().getX(), 0,
                                        alpha, A.getID(mRS), B.getID(mRS), 0, 0, 0, 0, 0, 0);
    }

    /**
     * CTRSM solves one of the matrix equations
     * op(A)*X := alpha*B   or   X*op(A) := alpha*B
     * op(A) is one of  op(A) = A  or  op(A) = A**T  or  op(A) = A**H
     *
     * Details: http://www.netlib.org/lapack/explore-html/de/d30/ctrsm_8f.html
     *
     * @param Side Specifies whether the symmetric matrix A appears on the left or right.
     * @param Uplo Specifies whether matrix A is upper or lower triangular.
     * @param TransA The type of transpose applied to matrix A.
     * @param Diag Specifies whether or not A is unit triangular.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32_2}.
     * @param B The input allocation contains matrix B, supported elements type {@link Element#F32_2}.
     */
    public void CTRSM(@Side int Side, @Uplo int Uplo, @Transpose int TransA, @Diag int Diag, Float2 alpha, Allocation A, Allocation B) {
        validateUplo(Uplo);
        validateDiag(Diag);
        validateTRSM(Element.F32_2(mRS), Side, TransA, A, B);
        mRS.nScriptIntrinsicBLAS_Complex(getID(mRS), RsBlas_ctrsm, TransA, 0, Side, Uplo, Diag, B.getType().getY(), B.getType().getX(), 0,
                                         alpha.x, alpha.y, A.getID(mRS), B.getID(mRS), 0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * ZTRSM solves one of the matrix equations
     * op(A)*X := alpha*B   or   X*op(A) := alpha*B
     * op(A) is one of  op(A) = A  or  op(A) = A**T  or  op(A) = A**H
     *
     * Details: http://www.netlib.org/lapack/explore-html/d1/d39/ztrsm_8f.html
     *
     * @param Side Specifies whether the symmetric matrix A appears on the left or right.
     * @param Uplo Specifies whether matrix A is upper or lower triangular.
     * @param TransA The type of transpose applied to matrix A.
     * @param Diag Specifies whether or not A is unit triangular.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64_2}.
     * @param B The input allocation contains matrix B, supported elements type {@link Element#F64_2}.
     */
    public void ZTRSM(@Side int Side, @Uplo int Uplo, @Transpose int TransA, @Diag int Diag, Double2 alpha, Allocation A, Allocation B) {
        validateUplo(Uplo);
        validateDiag(Diag);
        validateTRSM(Element.F64_2(mRS), Side, TransA, A, B);
        mRS.nScriptIntrinsicBLAS_Z(getID(mRS), RsBlas_ztrsm, TransA, 0, Side, Uplo, Diag, B.getType().getY(), B.getType().getX(), 0,
                                   alpha.x, alpha.y, A.getID(mRS), B.getID(mRS), 0, 0, 0, 0, 0, 0, 0);
    }

    static void validateHEMM(Element e, @Side int Side, Allocation A, Allocation B, Allocation C) {
        validateSide(Side);

        if (!A.getType().getElement().isCompatible(e) ||
            !B.getType().getElement().isCompatible(e) ||
            !C.getType().getElement().isCompatible(e)) {
            throw new RSRuntimeException("Called BLAS with wrong Element type");
        }

        // A must be square; can potentially be relaxed similar to TRSM
        int adim = A.getType().getX();
        if (adim != A.getType().getY()) {
            throw new RSRuntimeException("Called HEMM with non-square A");
        }
        if ((Side == LEFT && adim != B.getType().getY()) ||
            (Side == RIGHT && adim != B.getType().getX())) {
            throw new RSRuntimeException("Called HEMM with invalid B");
        }
        if (B.getType().getX() != C.getType().getX() ||
            B.getType().getY() != C.getType().getY()) {
            throw new RSRuntimeException("Called HEMM with mismatched B and C");
        }
    }

    /**
     * CHEMM performs one of the matrix-matrix operations
     * C := alpha*A*B + beta*C   or   C := alpha*B*A + beta*C
     *
     * Details: http://www.netlib.org/lapack/explore-html/d3/d66/chemm_8f.html
     *
     * @param Side Specifies whether the symmetric matrix A appears on the left or right.
     * @param Uplo Specifies whether the upper or lower triangular part is to be referenced.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32_2}.
     * @param B The input allocation contains matrix B, supported elements type {@link Element#F32_2}.
     * @param beta The scalar beta.
     * @param C The input allocation contains matrix C, supported elements type {@link Element#F32_2}.
     */
    public void CHEMM(@Side int Side, @Uplo int Uplo, Float2 alpha, Allocation A, Allocation B, Float2 beta, Allocation C) {
        validateUplo(Uplo);
        validateHEMM(Element.F32_2(mRS), Side, A, B, C);
        mRS.nScriptIntrinsicBLAS_Complex(getID(mRS), RsBlas_chemm, 0, 0, Side, Uplo, 0, C.getType().getY(), C.getType().getX(), 0,
                                         alpha.x, alpha.y, A.getID(mRS), B.getID(mRS), beta.x, beta.y, C.getID(mRS), 0, 0, 0, 0);
    }

    /**
     * ZHEMM performs one of the matrix-matrix operations
     * C := alpha*A*B + beta*C   or   C := alpha*B*A + beta*C
     *
     * Details: http://www.netlib.org/lapack/explore-html/d6/d3e/zhemm_8f.html
     *
     * @param Side Specifies whether the symmetric matrix A appears on the left or right.
     * @param Uplo Specifies whether the upper or lower triangular part is to be referenced.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64_2}.
     * @param B The input allocation contains matrix B, supported elements type {@link Element#F64_2}.
     * @param beta The scalar beta.
     * @param C The input allocation contains matrix C, supported elements type {@link Element#F64_2}.
     */
    public void ZHEMM(@Side int Side, @Uplo int Uplo, Double2 alpha, Allocation A, Allocation B, Double2 beta, Allocation C) {
        validateUplo(Uplo);
        validateHEMM(Element.F64_2(mRS), Side, A, B, C);
        mRS.nScriptIntrinsicBLAS_Z(getID(mRS), RsBlas_zhemm, 0, 0, Side, Uplo, 0, C.getType().getY(), C.getType().getX(), 0,
                                   alpha.x, alpha.y, A.getID(mRS), B.getID(mRS), beta.x, beta.y, C.getID(mRS), 0, 0, 0, 0);
    }

    static void validateHERK(Element e, @Transpose int Trans, Allocation A, Allocation C) {
        if (!A.getType().getElement().isCompatible(e) ||
            !C.getType().getElement().isCompatible(e)) {
            throw new RSRuntimeException("Called BLAS with wrong Element type");
        }
        validateConjTranspose(Trans);
        int cdim = C.getType().getX();
        if (cdim != C.getType().getY()) {
            throw new RSRuntimeException("Called HERK with non-square C");
        }
        if (Trans == NO_TRANSPOSE) {
            if (cdim != A.getType().getY()) {
                throw new RSRuntimeException("Called HERK with invalid A");
            }
        } else {
            if (cdim != A.getType().getX()) {
                throw new RSRuntimeException("Called HERK with invalid A");
            }
        }
    }

    /**
     * CHERK performs one of the hermitian rank k operations
     * C := alpha*A*A**H + beta*C   or   C := alpha*A**H*A + beta*C
     *
     * Details: http://www.netlib.org/lapack/explore-html/d8/d52/cherk_8f.html
     *
     * @param Uplo Specifies whether the upper or lower triangular part of C is to be referenced.
     * @param Trans The type of transpose applied to the operation.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32_2}.
     * @param beta The scalar beta.
     * @param C The input allocation contains matrix C, supported elements type {@link Element#F32_2}.
     */
    public void CHERK(@Uplo int Uplo, @Transpose int Trans, float alpha, Allocation A, float beta, Allocation C) {
        validateUplo(Uplo);
        validateHERK(Element.F32_2(mRS), Trans, A, C);
        int k = 0;
        if (Trans == CONJ_TRANSPOSE) {
            k = A.getType().getY();
        } else {
            k = A.getType().getX();
        }
        mRS.nScriptIntrinsicBLAS_Complex(getID(mRS), RsBlas_cherk, Trans, 0, 0, Uplo, 0, 0, C.getType().getX(), k,
                                         alpha, 0, A.getID(mRS), 0, beta, 0, C.getID(mRS), 0, 0, 0, 0);
    }

    /**
     * ZHERK performs one of the hermitian rank k operations
     * C := alpha*A*A**H + beta*C   or   C := alpha*A**H*A + beta*C
     *
     * Details: http://www.netlib.org/lapack/explore-html/d1/db1/zherk_8f.html
     *
     * @param Uplo Specifies whether the upper or lower triangular part of C is to be referenced.
     * @param Trans The type of transpose applied to the operation.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64_2}.
     * @param beta The scalar beta.
     * @param C The input allocation contains matrix C, supported elements type {@link Element#F64_2}.
     */
    public void ZHERK(@Uplo int Uplo, @Transpose int Trans, double alpha, Allocation A, double beta, Allocation C) {
        validateUplo(Uplo);
        validateHERK(Element.F64_2(mRS), Trans, A, C);
        int k = 0;
        if (Trans == CONJ_TRANSPOSE) {
            k = A.getType().getY();
        } else {
            k = A.getType().getX();
        }
        mRS.nScriptIntrinsicBLAS_Z(getID(mRS), RsBlas_zherk, Trans, 0, 0, Uplo, 0, 0, C.getType().getX(), k,
                                   alpha, 0, A.getID(mRS), 0, beta, 0, C.getID(mRS), 0, 0, 0, 0);
    }

    static void validateHER2K(Element e, @Transpose int Trans, Allocation A, Allocation B, Allocation C) {
        if (!A.getType().getElement().isCompatible(e) ||
            !B.getType().getElement().isCompatible(e) ||
            !C.getType().getElement().isCompatible(e)) {
            throw new RSRuntimeException("Called BLAS with wrong Element type");
        }
        validateConjTranspose(Trans);
        int cdim = C.getType().getX();
        if (cdim != C.getType().getY()) {
            throw new RSRuntimeException("Called HER2K with non-square C");
        }
        if (Trans == NO_TRANSPOSE) {
            if (A.getType().getY() != cdim) {
                throw new RSRuntimeException("Called HER2K with invalid matrices");
            }
        } else {
            if (A.getType().getX() != cdim) {
                throw new RSRuntimeException("Called HER2K with invalid matrices");
            }
        }
        if (A.getType().getX() != B.getType().getX() || A.getType().getY() != B.getType().getY()) {
            throw new RSRuntimeException("Called HER2K with invalid A and B matrices");
        }
    }

    /**
     * CHER2K performs one of the hermitian rank 2k operations
     * C := alpha*A*B**H + conjg( alpha )*B*A**H + beta*C   or   C := alpha*A**H*B + conjg( alpha )*B**H*A + beta*C
     *
     * Details: http://www.netlib.org/lapack/explore-html/d1/d82/cher2k_8f.html
     *
     * @param Uplo Specifies whether the upper or lower triangular part of C is to be referenced.
     * @param Trans The type of transpose applied to the operation.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F32_2}.
     * @param B The input allocation contains matrix B, supported elements type {@link Element#F32_2}.
     * @param beta The scalar beta.
     * @param C The input allocation contains matrix C, supported elements type {@link Element#F32_2}.
     */
    public void CHER2K(@Uplo int Uplo, @Transpose int Trans, Float2 alpha, Allocation A, Allocation B, float beta, Allocation C) {
        validateUplo(Uplo);
        validateHER2K(Element.F32_2(mRS), Trans, A, B, C);
        int k = 0;
        if (Trans == NO_TRANSPOSE) {
            k = A.getType().getX();
        } else {
            k = A.getType().getY();
        }
        mRS.nScriptIntrinsicBLAS_Complex(getID(mRS), RsBlas_cher2k, Trans, 0, 0, Uplo, 0, 0, C.getType().getX(), k, alpha.x, alpha.y,
                                         A.getID(mRS), B.getID(mRS), beta, 0, C.getID(mRS), 0, 0, 0, 0);
    }

    /**
     * ZHER2K performs one of the hermitian rank 2k operations
     * C := alpha*A*B**H + conjg( alpha )*B*A**H + beta*C   or   C := alpha*A**H*B + conjg( alpha )*B**H*A + beta*C
     *
     * Details: http://www.netlib.org/lapack/explore-html/d7/dfa/zher2k_8f.html
     *
     * @param Uplo Specifies whether the upper or lower triangular part of C is to be referenced.
     * @param Trans The type of transpose applied to the operation.
     * @param alpha The scalar alpha.
     * @param A The input allocation contains matrix A, supported elements type {@link Element#F64_2}.
     * @param B The input allocation contains matrix B, supported elements type {@link Element#F64_2}.
     * @param beta The scalar beta.
     * @param C The input allocation contains matrix C, supported elements type {@link Element#F64_2}.
     */
    public void ZHER2K(@Uplo int Uplo, @Transpose int Trans, Double2 alpha, Allocation A, Allocation B, double beta, Allocation C) {
        validateUplo(Uplo);
        validateHER2K(Element.F64_2(mRS), Trans, A, B, C);
        int k = 0;
        if (Trans == NO_TRANSPOSE) {
            k = A.getType().getX();
        } else {
            k = A.getType().getY();
        }
        mRS.nScriptIntrinsicBLAS_Z(getID(mRS), RsBlas_zher2k, Trans, 0, 0, Uplo, 0, 0, C.getType().getX(), k, alpha.x, alpha.y,
                                   A.getID(mRS), B.getID(mRS), beta, 0, C.getID(mRS), 0, 0, 0, 0);
    }


    /**
     * 8-bit GEMM-like operation for neural networks: C = A * Transpose(B)
     * Calculations are done in 1.10.21 fixed-point format for the final output,
     * just before there's a shift down to drop the fractional parts. The output
     * values are gated to 0 to 255 to fit in a byte, but the 10-bit format
     * gives some headroom to avoid wrapping around on small overflows.
     *
     * @param A The input allocation contains matrix A, supported elements type {@link Element#U8}.
     * @param a_offset The offset for all values in matrix A, e.g A[i,j] = A[i,j] - a_offset. Value should be from 0 to 255.
     * @param B The input allocation contains matrix B, supported elements type {@link Element#U8}.
     * @param b_offset The offset for all values in matrix B, e.g B[i,j] = B[i,j] - b_offset. Value should be from 0 to 255.
     * @param C The input allocation contains matrix C, supported elements type {@link Element#U8}.
     * @param c_offset The offset for all values in matrix C.
     * @param c_mult The multiplier for all values in matrix C, e.g C[i,j] = (C[i,j] + c_offset) * c_mult.
     **/
    public void BNNM(Allocation A, int a_offset, Allocation B, int b_offset, Allocation C, int c_offset, int c_mult) {
        validateL3(Element.U8(mRS), NO_TRANSPOSE, TRANSPOSE, 0, A, B, C);

        if (a_offset < 0 || a_offset > 255) {
            throw new RSRuntimeException("Invalid a_offset passed to BNNM");
        }
        if (b_offset < 0 || b_offset > 255) {
            throw new RSRuntimeException("Invalid b_offset passed to BNNM");
        }
        int M = -1, N = -1, K = -1;
        M = A.getType().getY();
        N = B.getType().getY();
        K = A.getType().getX();


        mRS.nScriptIntrinsicBLAS_BNNM(getID(mRS), M, N, K, A.getID(mRS), a_offset, B.getID(mRS), b_offset, C.getID(mRS), c_offset, c_mult);

    }

}
