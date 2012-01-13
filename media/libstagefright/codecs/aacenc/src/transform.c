/*
 ** Copyright 2003-2010, VisualOn, Inc.
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
/*******************************************************************************
	File:		transform.c

	Content:	MDCT Transform functionss

*******************************************************************************/

#include "basic_op.h"
#include "psy_const.h"
#include "transform.h"
#include "aac_rom.h"


#define LS_TRANS ((FRAME_LEN_LONG-FRAME_LEN_SHORT)/2) /* 448 */
#define SQRT1_2 0x5a82799a	/* sqrt(1/2) in Q31 */
#define swap2(p0,p1) \
	t = p0; t1 = *(&(p0)+1);	\
	p0 = p1; *(&(p0)+1) = *(&(p1)+1);	\
	p1 = t; *(&(p1)+1) = t1

/*********************************************************************************
*
* function name: Shuffle
* description:  Shuffle points prepared function for fft
*
**********************************************************************************/
static void Shuffle(int *buf, int num, const unsigned char* bitTab)
{
    int *part0, *part1;
	int i, j;
	int t, t1;

	part0 = buf;
    part1 = buf + num;

	while ((i = *bitTab++) != 0) {
        j = *bitTab++;

        swap2(part0[4*i+0], part0[4*j+0]);
        swap2(part0[4*i+2], part1[4*j+0]);
        swap2(part1[4*i+0], part0[4*j+2]);
        swap2(part1[4*i+2], part1[4*j+2]);
    }

    do {
        swap2(part0[4*i+2], part1[4*i+0]);
    } while ((i = *bitTab++) != 0);
}

#if !defined(ARMV5E) && !defined(ARMV7Neon)

/*****************************************************************************
*
* function name: Radix4First
* description:  Radix 4 point prepared function for fft
*
**********************************************************************************/
static void Radix4First(int *buf, int num)
{
    int r0, r1, r2, r3;
	int r4, r5, r6, r7;

	for (; num != 0; num--)
	{
		r0 = buf[0] + buf[2];
		r1 = buf[1] + buf[3];
		r2 = buf[0] - buf[2];
		r3 = buf[1] - buf[3];
		r4 = buf[4] + buf[6];
		r5 = buf[5] + buf[7];
		r6 = buf[4] - buf[6];
		r7 = buf[5] - buf[7];

		buf[0] = r0 + r4;
		buf[1] = r1 + r5;
		buf[4] = r0 - r4;
		buf[5] = r1 - r5;
		buf[2] = r2 + r7;
		buf[3] = r3 - r6;
		buf[6] = r2 - r7;
		buf[7] = r3 + r6;

		buf += 8;
	}
}

/*****************************************************************************
*
* function name: Radix8First
* description:  Radix 8 point prepared function for fft
*
**********************************************************************************/
static void Radix8First(int *buf, int num)
{
   int r0, r1, r2, r3;
   int i0, i1, i2, i3;
   int r4, r5, r6, r7;
   int i4, i5, i6, i7;
   int t0, t1, t2, t3;

	for ( ; num != 0; num--)
	{
		r0 = buf[0] + buf[2];
		i0 = buf[1] + buf[3];
		r1 = buf[0] - buf[2];
		i1 = buf[1] - buf[3];
		r2 = buf[4] + buf[6];
		i2 = buf[5] + buf[7];
		r3 = buf[4] - buf[6];
		i3 = buf[5] - buf[7];

		r4 = (r0 + r2) >> 1;
		i4 = (i0 + i2) >> 1;
		r5 = (r0 - r2) >> 1;
		i5 = (i0 - i2) >> 1;
		r6 = (r1 - i3) >> 1;
		i6 = (i1 + r3) >> 1;
		r7 = (r1 + i3) >> 1;
		i7 = (i1 - r3) >> 1;

		r0 = buf[ 8] + buf[10];
		i0 = buf[ 9] + buf[11];
		r1 = buf[ 8] - buf[10];
		i1 = buf[ 9] - buf[11];
		r2 = buf[12] + buf[14];
		i2 = buf[13] + buf[15];
		r3 = buf[12] - buf[14];
		i3 = buf[13] - buf[15];

		t0 = (r0 + r2) >> 1;
		t1 = (i0 + i2) >> 1;
		t2 = (r0 - r2) >> 1;
		t3 = (i0 - i2) >> 1;

		buf[ 0] = r4 + t0;
		buf[ 1] = i4 + t1;
		buf[ 8] = r4 - t0;
		buf[ 9] = i4 - t1;
		buf[ 4] = r5 + t3;
		buf[ 5] = i5 - t2;
		buf[12] = r5 - t3;
		buf[13] = i5 + t2;

		r0 = r1 - i3;
		i0 = i1 + r3;
		r2 = r1 + i3;
		i2 = i1 - r3;

		t0 = MULHIGH(SQRT1_2, r0 - i0);
		t1 = MULHIGH(SQRT1_2, r0 + i0);
		t2 = MULHIGH(SQRT1_2, r2 - i2);
		t3 = MULHIGH(SQRT1_2, r2 + i2);

		buf[ 6] = r6 - t0;
		buf[ 7] = i6 - t1;
		buf[14] = r6 + t0;
		buf[15] = i6 + t1;
		buf[ 2] = r7 + t3;
		buf[ 3] = i7 - t2;
		buf[10] = r7 - t3;
		buf[11] = i7 + t2;

		buf += 16;
	}
}

/*****************************************************************************
*
* function name: Radix4FFT
* description:  Radix 4 point fft core function
*
**********************************************************************************/
static void Radix4FFT(int *buf, int num, int bgn, int *twidTab)
{
	int r0, r1, r2, r3;
	int r4, r5, r6, r7;
	int t0, t1;
	int sinx, cosx;
	int i, j, step;
	int *xptr, *csptr;

	for (num >>= 2; num != 0; num >>= 2)
	{
		step = 2*bgn;
		xptr = buf;

    	for (i = num; i != 0; i--)
		{
			csptr = twidTab;

			for (j = bgn; j != 0; j--)
			{
				r0 = xptr[0];
				r1 = xptr[1];
				xptr += step;

				t0 = xptr[0];
				t1 = xptr[1];
				cosx = csptr[0];
				sinx = csptr[1];
				r2 = MULHIGH(cosx, t0) + MULHIGH(sinx, t1);		/* cos*br + sin*bi */
				r3 = MULHIGH(cosx, t1) - MULHIGH(sinx, t0);		/* cos*bi - sin*br */
				xptr += step;

				t0 = r0 >> 2;
				t1 = r1 >> 2;
				r0 = t0 - r2;
				r1 = t1 - r3;
				r2 = t0 + r2;
				r3 = t1 + r3;

				t0 = xptr[0];
				t1 = xptr[1];
				cosx = csptr[2];
				sinx = csptr[3];
				r4 = MULHIGH(cosx, t0) + MULHIGH(sinx, t1);		/* cos*cr + sin*ci */
				r5 = MULHIGH(cosx, t1) - MULHIGH(sinx, t0);		/* cos*ci - sin*cr */
				xptr += step;

				t0 = xptr[0];
				t1 = xptr[1];
				cosx = csptr[4];
				sinx = csptr[5];
				r6 = MULHIGH(cosx, t0) + MULHIGH(sinx, t1);		/* cos*cr + sin*ci */
				r7 = MULHIGH(cosx, t1) - MULHIGH(sinx, t0);		/* cos*ci - sin*cr */
				csptr += 6;

				t0 = r4;
				t1 = r5;
				r4 = t0 + r6;
				r5 = r7 - t1;
				r6 = t0 - r6;
				r7 = r7 + t1;

				xptr[0] = r0 + r5;
				xptr[1] = r1 + r6;
				xptr -= step;

				xptr[0] = r2 - r4;
				xptr[1] = r3 - r7;
				xptr -= step;

				xptr[0] = r0 - r5;
				xptr[1] = r1 - r6;
				xptr -= step;

				xptr[0] = r2 + r4;
				xptr[1] = r3 + r7;
				xptr += 2;
			}
			xptr += 3*step;
		}
		twidTab += 3*step;
		bgn <<= 2;
	}
}

/*********************************************************************************
*
* function name: PreMDCT
* description:  prepare MDCT process for next FFT compute
*
**********************************************************************************/
static void PreMDCT(int *buf0, int num, const int *csptr)
{
	int i;
	int tr1, ti1, tr2, ti2;
	int cosa, sina, cosb, sinb;
	int *buf1;

	buf1 = buf0 + num - 1;

	for(i = num >> 2; i != 0; i--)
	{
		cosa = *csptr++;
		sina = *csptr++;
		cosb = *csptr++;
		sinb = *csptr++;

		tr1 = *(buf0 + 0);
		ti2 = *(buf0 + 1);
		tr2 = *(buf1 - 1);
		ti1 = *(buf1 + 0);

		*buf0++ = MULHIGH(cosa, tr1) + MULHIGH(sina, ti1);
		*buf0++ = MULHIGH(cosa, ti1) - MULHIGH(sina, tr1);

		*buf1-- = MULHIGH(cosb, ti2) - MULHIGH(sinb, tr2);
		*buf1-- = MULHIGH(cosb, tr2) + MULHIGH(sinb, ti2);
	}
}

/*********************************************************************************
*
* function name: PostMDCT
* description:   post MDCT process after next FFT for MDCT
*
**********************************************************************************/
static void PostMDCT(int *buf0, int num, const int *csptr)
{
	int i;
	int tr1, ti1, tr2, ti2;
	int cosa, sina, cosb, sinb;
	int *buf1;

	buf1 = buf0 + num - 1;

	for(i = num >> 2; i != 0; i--)
	{
		cosa = *csptr++;
		sina = *csptr++;
		cosb = *csptr++;
		sinb = *csptr++;

		tr1 = *(buf0 + 0);
		ti1 = *(buf0 + 1);
		ti2 = *(buf1 + 0);
		tr2 = *(buf1 - 1);

		*buf0++ = MULHIGH(cosa, tr1) + MULHIGH(sina, ti1);
		*buf1-- = MULHIGH(sina, tr1) - MULHIGH(cosa, ti1);

		*buf0++ = MULHIGH(sinb, tr2) - MULHIGH(cosb, ti2);
		*buf1-- = MULHIGH(cosb, tr2) + MULHIGH(sinb, ti2);
	}
}
#endif


/**********************************************************************************
*
* function name: Mdct_Long
* description:  the long block mdct, include long_start block, end_long block
*
**********************************************************************************/
void Mdct_Long(int *buf)
{
	PreMDCT(buf, 1024, cossintab + 128);

	Shuffle(buf, 512, bitrevTab + 17);
	Radix8First(buf, 512 >> 3);
	Radix4FFT(buf, 512 >> 3, 8, (int *)twidTab512);

	PostMDCT(buf, 1024, cossintab + 128);
}


/**********************************************************************************
*
* function name: Mdct_Short
* description:  the short block mdct
*
**********************************************************************************/
void Mdct_Short(int *buf)
{
	PreMDCT(buf, 128, cossintab);

	Shuffle(buf, 64, bitrevTab);
	Radix4First(buf, 64 >> 2);
	Radix4FFT(buf, 64 >> 2, 4, (int *)twidTab64);

	PostMDCT(buf, 128, cossintab);
}


/*****************************************************************************
*
* function name: shiftMdctDelayBuffer
* description:    the mdct delay buffer has a size of 1600,
*  so the calculation of LONG,STOP must be  spilt in two
*  passes with 1024 samples and a mid shift,
*  the SHORT transforms can be completed in the delay buffer,
*  and afterwards a shift
*
**********************************************************************************/
static void shiftMdctDelayBuffer(Word16 *mdctDelayBuffer, /*! start of mdct delay buffer */
								 Word16 *timeSignal,      /*! pointer to new time signal samples, interleaved */
								 Word16 chIncrement       /*! number of channels */
								 )
{
	Word32 i;
	Word16 *srBuf = mdctDelayBuffer;
	Word16 *dsBuf = mdctDelayBuffer+FRAME_LEN_LONG;

	for(i = 0; i < BLOCK_SWITCHING_OFFSET-FRAME_LEN_LONG; i+= 8)
	{
		*srBuf++ = *dsBuf++;	 *srBuf++ = *dsBuf++;
		*srBuf++ = *dsBuf++;	 *srBuf++ = *dsBuf++;
		*srBuf++ = *dsBuf++;	 *srBuf++ = *dsBuf++;
		*srBuf++ = *dsBuf++;	 *srBuf++ = *dsBuf++;
	}

	srBuf = mdctDelayBuffer + BLOCK_SWITCHING_OFFSET-FRAME_LEN_LONG;
	dsBuf = timeSignal;

	for(i=0; i<FRAME_LEN_LONG; i+=8)
	{
		*srBuf++ = *dsBuf; dsBuf += chIncrement;
		*srBuf++ = *dsBuf; dsBuf += chIncrement;
		*srBuf++ = *dsBuf; dsBuf += chIncrement;
		*srBuf++ = *dsBuf; dsBuf += chIncrement;
		*srBuf++ = *dsBuf; dsBuf += chIncrement;
		*srBuf++ = *dsBuf; dsBuf += chIncrement;
		*srBuf++ = *dsBuf; dsBuf += chIncrement;
		*srBuf++ = *dsBuf; dsBuf += chIncrement;
	}
}


/*****************************************************************************
*
* function name: getScalefactorOfShortVectorStride
* description:  Calculate max possible scale factor for input vector of shorts
* returns:      Maximum scale factor
*
**********************************************************************************/
static Word16 getScalefactorOfShortVectorStride(const Word16 *vector, /*!< Pointer to input vector */
												Word16 len,           /*!< Length of input vector */
												Word16 stride)        /*!< Stride of input vector */
{
	Word16 maxVal = 0;
	Word16 absVal;
	Word16 i;

	for(i=0; i<len; i++){
		absVal = abs_s(vector[i*stride]);
		maxVal |= absVal;
	}

	return( maxVal ? norm_s(maxVal) : 15);
}


/*****************************************************************************
*
* function name: Transform_Real
* description:  Calculate transform filter for input vector of shorts
* returns:      TRUE if success
*
**********************************************************************************/
void Transform_Real(Word16 *mdctDelayBuffer,
                    Word16 *timeSignal,
                    Word16 chIncrement,
                    Word32 *realOut,
                    Word16 *mdctScale,
                    Word16 blockType
                    )
{
	Word32 i,w;
	Word32 timeSignalSample;
	Word32 ws1,ws2;
	Word16 *dctIn0, *dctIn1;
	Word32 *outData0, *outData1;
	Word32 *winPtr;

	Word32 delayBufferSf,timeSignalSf,minSf;
	Word32 headRoom=0;

	switch(blockType){


	case LONG_WINDOW:
		/*
		we access BLOCK_SWITCHING_OFFSET (1600 ) delay buffer samples + 448 new timeSignal samples
		and get the biggest scale factor for next calculate more precise
		*/
		delayBufferSf = getScalefactorOfShortVectorStride(mdctDelayBuffer,BLOCK_SWITCHING_OFFSET,1);
		timeSignalSf  = getScalefactorOfShortVectorStride(timeSignal,2*FRAME_LEN_LONG-BLOCK_SWITCHING_OFFSET,chIncrement);
		minSf = min(delayBufferSf,timeSignalSf);
		minSf = min(minSf,14);

		dctIn0 = mdctDelayBuffer;
		dctIn1 = mdctDelayBuffer + FRAME_LEN_LONG - 1;
		outData0 = realOut + FRAME_LEN_LONG/2;

		/* add windows and pre add for mdct to last buffer*/
		winPtr = (int *)LongWindowKBD;
		for(i=0;i<FRAME_LEN_LONG/2;i++){
			timeSignalSample = (*dctIn0++) << minSf;
			ws1 = timeSignalSample * (*winPtr >> 16);
			timeSignalSample = (*dctIn1--) << minSf;
			ws2 = timeSignalSample * (*winPtr & 0xffff);
			winPtr ++;
			/* shift 2 to avoid overflow next */
			*outData0++ = (ws1 >> 2) - (ws2 >> 2);
		}

		shiftMdctDelayBuffer(mdctDelayBuffer,timeSignal,chIncrement);

		/* add windows and pre add for mdct to new buffer*/
		dctIn0 = mdctDelayBuffer;
		dctIn1 = mdctDelayBuffer + FRAME_LEN_LONG - 1;
		outData0 = realOut + FRAME_LEN_LONG/2 - 1;
		winPtr = (int *)LongWindowKBD;
		for(i=0;i<FRAME_LEN_LONG/2;i++){
			timeSignalSample = (*dctIn0++) << minSf;
			ws1 = timeSignalSample * (*winPtr & 0xffff);
			timeSignalSample = (*dctIn1--) << minSf;
			ws2 = timeSignalSample * (*winPtr >> 16);
			winPtr++;
			/* shift 2 to avoid overflow next */
			*outData0-- = -((ws1 >> 2) + (ws2 >> 2));
		}

		Mdct_Long(realOut);
		/* update scale factor */
		minSf = 14 - minSf;
		*mdctScale=minSf;
		break;

	case START_WINDOW:
		/*
		we access BLOCK_SWITCHING_OFFSET (1600 ) delay buffer samples + no timeSignal samples
		and get the biggest scale factor for next calculate more precise
		*/
		minSf = getScalefactorOfShortVectorStride(mdctDelayBuffer,BLOCK_SWITCHING_OFFSET,1);
		minSf = min(minSf,14);

		dctIn0 = mdctDelayBuffer;
		dctIn1 = mdctDelayBuffer + FRAME_LEN_LONG - 1;
		outData0 = realOut + FRAME_LEN_LONG/2;
		winPtr = (int *)LongWindowKBD;

		/* add windows and pre add for mdct to last buffer*/
		for(i=0;i<FRAME_LEN_LONG/2;i++){
			timeSignalSample = (*dctIn0++) << minSf;
			ws1 = timeSignalSample * (*winPtr >> 16);
			timeSignalSample = (*dctIn1--) << minSf;
			ws2 = timeSignalSample * (*winPtr & 0xffff);
			winPtr ++;
			*outData0++ = (ws1 >> 2) - (ws2 >> 2);  /* shift 2 to avoid overflow next */
		}

		shiftMdctDelayBuffer(mdctDelayBuffer,timeSignal,chIncrement);

		outData0 = realOut + FRAME_LEN_LONG/2 - 1;
		for(i=0;i<LS_TRANS;i++){
			*outData0-- = -mdctDelayBuffer[i] << (15 - 2 + minSf);
		}

		/* add windows and pre add for mdct to new buffer*/
		dctIn0 = mdctDelayBuffer + LS_TRANS;
		dctIn1 = mdctDelayBuffer + FRAME_LEN_LONG - 1 - LS_TRANS;
		outData0 = realOut + FRAME_LEN_LONG/2 - 1 -LS_TRANS;
		winPtr = (int *)ShortWindowSine;
		for(i=0;i<FRAME_LEN_SHORT/2;i++){
			timeSignalSample= (*dctIn0++) << minSf;
			ws1 = timeSignalSample * (*winPtr & 0xffff);
			timeSignalSample= (*dctIn1--) << minSf;
			ws2 = timeSignalSample * (*winPtr >> 16);
			winPtr++;
			*outData0-- =  -((ws1 >> 2) + (ws2 >> 2));  /* shift 2 to avoid overflow next */
		}

		Mdct_Long(realOut);
		/* update scale factor */
		minSf = 14 - minSf;
		*mdctScale= minSf;
		break;

	case STOP_WINDOW:
		/*
		we access BLOCK_SWITCHING_OFFSET-LS_TRANS (1600-448 ) delay buffer samples + 448 new timeSignal samples
		and get the biggest scale factor for next calculate more precise
		*/
		delayBufferSf = getScalefactorOfShortVectorStride(mdctDelayBuffer+LS_TRANS,BLOCK_SWITCHING_OFFSET-LS_TRANS,1);
		timeSignalSf  = getScalefactorOfShortVectorStride(timeSignal,2*FRAME_LEN_LONG-BLOCK_SWITCHING_OFFSET,chIncrement);
		minSf = min(delayBufferSf,timeSignalSf);
		minSf = min(minSf,13);

		outData0 = realOut + FRAME_LEN_LONG/2;
		dctIn1 = mdctDelayBuffer + FRAME_LEN_LONG - 1;
		for(i=0;i<LS_TRANS;i++){
			*outData0++ = -(*dctIn1--) << (15 - 2 + minSf);
		}

		/* add windows and pre add for mdct to last buffer*/
		dctIn0 = mdctDelayBuffer + LS_TRANS;
		dctIn1 = mdctDelayBuffer + FRAME_LEN_LONG - 1 - LS_TRANS;
		outData0 = realOut + FRAME_LEN_LONG/2 + LS_TRANS;
		winPtr = (int *)ShortWindowSine;
		for(i=0;i<FRAME_LEN_SHORT/2;i++){
			timeSignalSample = (*dctIn0++) << minSf;
			ws1 = timeSignalSample * (*winPtr >> 16);
			timeSignalSample= (*dctIn1--) << minSf;
			ws2 = timeSignalSample * (*winPtr & 0xffff);
			winPtr++;
			*outData0++ = (ws1 >> 2) - (ws2 >> 2);  /* shift 2 to avoid overflow next */
		}

		shiftMdctDelayBuffer(mdctDelayBuffer,timeSignal,chIncrement);

		/* add windows and pre add for mdct to new buffer*/
		dctIn0 = mdctDelayBuffer;
		dctIn1 = mdctDelayBuffer + FRAME_LEN_LONG - 1;
		outData0 = realOut + FRAME_LEN_LONG/2 - 1;
		winPtr = (int *)LongWindowKBD;
		for(i=0;i<FRAME_LEN_LONG/2;i++){
			timeSignalSample= (*dctIn0++) << minSf;
			ws1 = timeSignalSample *(*winPtr & 0xffff);
			timeSignalSample= (*dctIn1--) << minSf;
			ws2 = timeSignalSample * (*winPtr >> 16);
			*outData0-- =  -((ws1 >> 2) + (ws2 >> 2));  /* shift 2 to avoid overflow next */
			winPtr++;
		}

		Mdct_Long(realOut);
		minSf = 14 - minSf;
		*mdctScale= minSf; /* update scale factor */
		break;

	case SHORT_WINDOW:
		/*
		we access BLOCK_SWITCHING_OFFSET (1600 ) delay buffer samples + no new timeSignal samples
		and get the biggest scale factor for next calculate more precise
		*/
		minSf = getScalefactorOfShortVectorStride(mdctDelayBuffer+TRANSFORM_OFFSET_SHORT,9*FRAME_LEN_SHORT,1);
		minSf = min(minSf,10);


		for(w=0;w<TRANS_FAC;w++){
			dctIn0 = mdctDelayBuffer+w*FRAME_LEN_SHORT+TRANSFORM_OFFSET_SHORT;
			dctIn1 = mdctDelayBuffer+w*FRAME_LEN_SHORT+TRANSFORM_OFFSET_SHORT + FRAME_LEN_SHORT-1;
			outData0 = realOut + FRAME_LEN_SHORT/2;
			outData1 = realOut + FRAME_LEN_SHORT/2 - 1;

			winPtr = (int *)ShortWindowSine;
			for(i=0;i<FRAME_LEN_SHORT/2;i++){
				timeSignalSample= *dctIn0 << minSf;
				ws1 = timeSignalSample * (*winPtr >> 16);
				timeSignalSample= *dctIn1 << minSf;
				ws2 = timeSignalSample * (*winPtr & 0xffff);
				*outData0++ = (ws1 >> 2) - (ws2 >> 2);  /* shift 2 to avoid overflow next */

				timeSignalSample= *(dctIn0 + FRAME_LEN_SHORT) << minSf;
				ws1 = timeSignalSample * (*winPtr & 0xffff);
				timeSignalSample= *(dctIn1 + FRAME_LEN_SHORT) << minSf;
				ws2 = timeSignalSample * (*winPtr >> 16);
				*outData1-- =  -((ws1 >> 2) + (ws2 >> 2));  /* shift 2 to avoid overflow next */

				winPtr++;
				dctIn0++;
				dctIn1--;
			}

			Mdct_Short(realOut);
			realOut += FRAME_LEN_SHORT;
		}

		minSf = 11 - minSf;
		*mdctScale = minSf; /* update scale factor */

		shiftMdctDelayBuffer(mdctDelayBuffer,timeSignal,chIncrement);
		break;
  }
}

