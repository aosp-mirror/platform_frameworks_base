/* ------------------------------------------------------------------
 * Copyright (C) 1998-2009 PacketVideo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 * -------------------------------------------------------------------
 */
/**
This file contains application function interfaces to the AVC encoder library
and necessary type defitionitions and enumerations.
@publishedAll
*/

#ifndef AVCENC_INT_H_INCLUDED
#define AVCENC_INT_H_INCLUDED

#ifndef AVCINT_COMMON_H_INCLUDED
#include "avcint_common.h"
#endif
#ifndef AVCENC_API_H_INCLUDED
#include "avcenc_api.h"
#endif

typedef float OsclFloat;

/* Definition for the structures below */
#define DEFAULT_ATTR    0 /* default memory attribute */
#define MAX_INPUT_FRAME 30 /* some arbitrary number, it can be much higher than this. */
#define MAX_REF_FRAME  16 /* max size of the RefPicList0 and RefPicList1 */
#define MAX_REF_PIC_LIST 33

#define MIN_QP          0
#define MAX_QP          51
#define SHIFT_QP        12
#define  LAMBDA_ACCURACY_BITS         16
#define  LAMBDA_FACTOR(lambda)        ((int)((double)(1<<LAMBDA_ACCURACY_BITS)*lambda+0.5))


#define DISABLE_THRESHOLDING  0
// for better R-D performance
#define _LUMA_COEFF_COST_       4 //!< threshold for luma coeffs
#define _CHROMA_COEFF_COST_     4 //!< threshold for chroma coeffs, used to be 7
#define _LUMA_MB_COEFF_COST_    5 //!< threshold for luma coeffs of inter Macroblocks
#define _LUMA_8x8_COEFF_COST_   5 //!< threshold for luma coeffs of 8x8 Inter Partition
#define MAX_VALUE       999999   //!< used for start value for some variables

#define  WEIGHTED_COST(factor,bits)   (((factor)*(bits))>>LAMBDA_ACCURACY_BITS)
#define  MV_COST(f,s,cx,cy,px,py)     (WEIGHTED_COST(f,mvbits[((cx)<<(s))-px]+mvbits[((cy)<<(s))-py]))
#define  MV_COST_S(f,cx,cy,px,py)     (WEIGHTED_COST(f,mvbits[cx-px]+mvbits[cy-py]))

/* for sub-pel search and interpolation */
#define SUBPEL_PRED_BLK_SIZE 576 // 24x24
#define REF_CENTER 75
#define V2Q_H0Q 1
#define V0Q_H2Q 2
#define V2Q_H2Q 3

/*
#define V3Q_H0Q 1
#define V3Q_H1Q 2
#define V0Q_H1Q 3
#define V1Q_H1Q 4
#define V1Q_H0Q 5
#define V1Q_H3Q 6
#define V0Q_H3Q 7
#define V3Q_H3Q 8
#define V2Q_H3Q 9
#define V2Q_H0Q 10
#define V2Q_H1Q 11
#define V2Q_H2Q 12
#define V3Q_H2Q 13
#define V0Q_H2Q 14
#define V1Q_H2Q 15
*/


#define DEFAULT_OVERRUN_BUFFER_SIZE 1000

// associated with the above cost model
const uint8 COEFF_COST[2][16] =
{
    {3, 2, 2, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
    {9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9}
};



//! convert from H.263 QP to H.264 quant given by: quant=pow(2,QP/6)
const int QP2QUANT[40] =
{
    1, 1, 1, 1, 2, 2, 2, 2,
    3, 3, 3, 4, 4, 4, 5, 6,
    6, 7, 8, 9, 10, 11, 13, 14,
    16, 18, 20, 23, 25, 29, 32, 36,
    40, 45, 51, 57, 64, 72, 81, 91
};


/**
This enumeration keeps track of the internal status of the encoder whether it is doing
something. The encoding flow follows the order in which these states are.
@publishedAll
*/
typedef enum
{
    AVCEnc_Initializing = 0,
    AVCEnc_Encoding_SPS,
    AVCEnc_Encoding_PPS,
    AVCEnc_Analyzing_Frame,
    AVCEnc_WaitingForBuffer,  // pending state
    AVCEnc_Encoding_Frame,
} AVCEnc_State ;

/**
Bitstream structure contains bitstream related parameters such as the pointer
to the buffer, the current byte position and bit position. The content of the
bitstreamBuffer will be in EBSP format as the emulation prevention codes are
automatically inserted as the RBSP is recorded.
@publishedAll
*/
typedef struct tagEncBitstream
{
    uint8 *bitstreamBuffer; /* pointer to buffer memory   */
    int buf_size;       /* size of the buffer memory */
    int write_pos;      /* next position to write to bitstreamBuffer  */
    int count_zeros;   /* count number of consecutive zero */
    uint current_word;  /* byte-swapped (MSB left) current word to write to buffer */
    int bit_left;      /* number of bit left in current_word */
    uint8   *overrunBuffer;  /* extra output buffer to prevent current skip due to output buffer overrun*/
    int     oBSize;     /* size of allocated overrun buffer */
    void   *encvid; /* pointer to the main object */

} AVCEncBitstream;

/**
This structure is used for rate control purpose and other performance related control
variables such as, RD cost, statistics, motion search stuffs, etc.
should be in this structure.
@publishedAll
*/


typedef struct tagRDInfo
{
    int QP;
    int actual_bits;
    OsclFloat mad;
    OsclFloat R_D;
} RDInfo;

typedef struct tagMultiPass
{
    /* multipass rate control data */
    int target_bits;    /* target bits for current frame, = rc->T */
    int actual_bits;    /* actual bits for current frame obtained after encoding, = rc->Rc*/
    int QP;             /* quantization level for current frame, = rc->Qc*/
    int prev_QP;        /* quantization level for previous frame */
    int prev_prev_QP;   /* quantization level for previous frame before last*/
    OsclFloat mad;          /* mad for current frame, = video->avgMAD*/
    int bitrate;        /* bitrate for current frame */
    OsclFloat framerate;    /* framerate for current frame*/

    int nRe_Quantized;  /* control variable for multipass encoding, */
    /* 0 : first pass */
    /* 1 : intermediate pass(quantization and VLC loop only) */
    /* 2 : final pass(de-quantization, idct, etc) */
    /* 3 : macroblock level rate control */

    int encoded_frames;     /* counter for all encoded frames */
    int re_encoded_frames;  /* counter for all multipass encoded frames*/
    int re_encoded_times;   /* counter for all times of multipass frame encoding */

    /* Multiple frame prediction*/
    RDInfo **pRDSamples;        /* pRDSamples[30][32], 30->30fps, 32 -> 5 bit quantizer, 32 candidates*/
    int framePos;               /* specific position in previous multiple frames*/
    int frameRange;             /* number of overall previous multiple frames */
    int samplesPerFrame[30];    /* number of samples per frame, 30->30fps */

    /* Bit allocation for scene change frames and high motion frames */
    OsclFloat sum_mad;
    int counter_BTsrc;  /* BT = Bit Transfer, bit transfer from low motion frames or less complicatedly compressed frames */
    int counter_BTdst;  /* BT = Bit Transfer, bit transfer to scene change frames or high motion frames or more complicatedly compressed frames */
    OsclFloat sum_QP;
    int diff_counter;   /* diff_counter = -diff_counter_BTdst, or diff_counter_BTsrc */

    /* For target bitrate or framerate update */
    OsclFloat target_bits_per_frame;        /* = C = bitrate/framerate */
    OsclFloat target_bits_per_frame_prev;   /* previous C */
    OsclFloat aver_mad;                     /* so-far average mad could replace sum_mad */
    OsclFloat aver_mad_prev;                /* previous average mad */
    int   overlapped_win_size;          /* transition period of time */
    int   encoded_frames_prev;          /* previous encoded_frames */
} MultiPass;


typedef struct tagdataPointArray
{
    int Qp;
    int Rp;
    OsclFloat Mp;   /* for MB-based RC */
    struct tagdataPointArray *next;
    struct tagdataPointArray *prev;
} dataPointArray;

typedef struct tagAVCRateControl
{

    /* these parameters are initialized by the users AVCEncParams */
    /* bitrate-robustness tradeoff */
    uint scdEnable; /* enable scene change detection */
    int idrPeriod;  /* IDR period in number of frames */
    int intraMBRate;   /* intra MB refresh rate per frame */
    uint dpEnable;  /* enable data partitioning */

    /* quality-complexity tradeoff */
    uint subPelEnable;  /* enable quarter pel search */
    int mvRange;    /* motion vector search range in +/- pixel */
    uint subMBEnable;  /* enable sub MB prediction mode (4x4, 4x8, 8x4) */
    uint rdOptEnable;  /* enable RD-opt mode selection */
    uint twoPass; /* flag for 2 pass encoding ( for future )*/
    uint bidirPred; /* bi-directional prediction for B-frame. */

    uint rcEnable;  /* enable rate control, '1' on, '0' const QP */
    int initQP; /* initial QP */

    /* note the following 3 params are for HRD, these triplets can be a series
    of triplets as the generalized HRD allows. SEI message must be generated in this case. */
    /* We no longer have to differentiate between CBR and VBR. The users to the
    AVC encoder lib will do the mapping from CBR/VBR to these parameters. */
    int32 bitRate;  /* target bit rate for the overall clip in bits/second*/
    int32 cpbSize;  /* coded picture buffer size in bytes */
    int32 initDelayOffset; /* initial CBP removal delay in bits */

    OsclFloat frame_rate; /* frame rate */
    int srcInterval; /* source frame rate in msec */
    int basicUnit;  /* number of macroblocks per BU */

    /* Then internal parameters for the operation */
    uint first_frame; /* a flag for the first frame */
    int lambda_mf; /* for example */
    int totalSAD;    /* SAD of current frame */

    /*******************************************/
    /* this part comes from MPEG4 rate control */
    int alpha;  /* weight for I frame */
    int Rs;     /*bit rate for the sequence (or segment) e.g., 24000 bits/sec */
    int Rc;     /*bits used for the current frame. It is the bit count obtained after encoding. */
    int Rp;     /*bits to be removed from the buffer per picture. */
    /*? is this the average one, or just the bits coded for the previous frame */
    int Rps;    /*bit to be removed from buffer per src frame */
    OsclFloat Ts;   /*number of seconds for the sequence  (or segment). e.g., 10 sec */
    OsclFloat Ep;
    OsclFloat Ec;   /*mean absolute difference for the current frame after motion compensation.*/
    /*If the macroblock is intra coded, the original spatial pixel values are summed.*/
    int Qc;     /*quantization level used for the current frame. */
    int Nr;     /*number of P frames remaining for encoding.*/
    int Rr; /*number of bits remaining for encoding this sequence (or segment).*/
    int Rr_Old;
    int T;      /*target bit to be used for the current frame.*/
    int S;      /*number of bits used for encoding the previous frame.*/
    int Hc; /*header and motion vector bits used in the current frame. It includes all the  information except to the residual information.*/
    int Hp; /*header and motion vector bits used in the previous frame. It includes all the     information except to the residual information.*/
    int Ql; /*quantization level used in the previous frame */
    int Bs; /*buffer size e.g., R/2 */
    int B;      /*current buffer level e.g., R/4 - start from the middle of the buffer */
    OsclFloat X1;
    OsclFloat X2;
    OsclFloat X11;
    OsclFloat M;            /*safe margin for the buffer */
    OsclFloat smTick;    /*ratio of src versus enc frame rate */
    double remnant;  /*remainder frame of src/enc frame for fine frame skipping */
    int timeIncRes; /* vol->timeIncrementResolution */

    dataPointArray   *end; /*quantization levels for the past (20) frames */

    int     frameNumber; /* ranging from 0 to 20 nodes*/
    int     w;
    int     Nr_Original;
    int     Nr_Old, Nr_Old2;
    int     skip_next_frame;
    int     Qdep;       /* smooth Q adjustment */
    int     VBR_Enabled;

    int totalFrameNumber; /* total coded frames, for debugging!!*/

    char    oFirstTime;

    int numFrameBits; /* keep track of number of bits of the current frame */
    int NumberofHeaderBits;
    int NumberofTextureBits;
    int numMBHeaderBits;
    int numMBTextureBits;
    double *MADofMB;
    int32 bitsPerFrame;

    /* BX rate control, something like TMN8 rate control*/

    MultiPass *pMP;

    int     TMN_W;
    int     TMN_TH;
    int     VBV_fullness;
    int     max_BitVariance_num; /* the number of the maximum bit variance within the given buffer with the unit of 10% of bitrate/framerate*/
    int     encoded_frames; /* counter for all encoded frames */
    int     low_bound;              /* bound for underflow detection, usually low_bound=-Bs/2, but could be changed in H.263 mode */
    int     VBV_fullness_offset;    /* offset of VBV_fullness, usually is zero, but can be changed in H.263 mode*/
    /* End BX */

} AVCRateControl;


/**
This structure is for the motion vector information. */
typedef struct tagMV
{
    int x;
    int y;
    uint sad;
} AVCMV;

/**
This structure contains function pointers for different platform dependent implementation of
functions. */
typedef struct tagAVCEncFuncPtr
{

    int (*SAD_MB_HalfPel[4])(uint8*, uint8*, int, void *);
    int (*SAD_Macroblock)(uint8 *ref, uint8 *blk, int dmin_lx, void *extra_info);

} AVCEncFuncPtr;

/**
This structure contains information necessary for correct padding.
*/
typedef struct tagPadInfo
{
    int i;
    int width;
    int j;
    int height;
} AVCPadInfo;


#ifdef HTFM
typedef struct tagHTFM_Stat
{
    int abs_dif_mad_avg;
    uint countbreak;
    int offsetArray[16];
    int offsetRef[16];
} HTFM_Stat;
#endif


/**
This structure is the main object for AVC encoder library providing access to all
global variables. It is allocated at PVAVCInitEncoder and freed at PVAVCCleanUpEncoder.
@publishedAll
*/
typedef struct tagEncObject
{

    AVCCommonObj *common;

    AVCEncBitstream     *bitstream; /* for current NAL */
    uint8   *overrunBuffer;  /* extra output buffer to prevent current skip due to output buffer overrun*/
    int     oBSize;     /* size of allocated overrun buffer */

    /* rate control */
    AVCRateControl      *rateCtrl; /* pointer to the rate control structure */

    /* encoding operation */
    AVCEnc_State        enc_state; /* encoding state */

    AVCFrameIO          *currInput; /* pointer to the current input frame */

    int                 currSliceGroup; /* currently encoded slice group id */

    int     level[24][16], run[24][16]; /* scratch memory */
    int     leveldc[16], rundc[16]; /* for DC component */
    int     levelcdc[16], runcdc[16]; /* for chroma DC component */
    int     numcoefcdc[2]; /* number of coefficient for chroma DC */
    int     numcoefdc;      /* number of coefficients for DC component */

    int     qp_const;
    int     qp_const_c;
    /********* intra prediction scratch memory **********************/
    uint8   pred_i16[AVCNumI16PredMode][256]; /* save prediction for MB */
    uint8   pred_i4[AVCNumI4PredMode][16];  /* save prediction for blk */
    uint8   pred_ic[AVCNumIChromaMode][128];  /* for 2 chroma */

    int     mostProbableI4Mode[16]; /* in raster scan order */
    /********* motion compensation related variables ****************/
    AVCMV   *mot16x16;          /* Saved motion vectors for 16x16 block*/
    AVCMV(*mot16x8)[2];     /* Saved motion vectors for 16x8 block*/
    AVCMV(*mot8x16)[2];     /* Saved motion vectors for 8x16 block*/
    AVCMV(*mot8x8)[4];      /* Saved motion vectors for 8x8 block*/

    /********* subpel position **************************************/
    uint32  subpel_pred[SUBPEL_PRED_BLK_SIZE/*<<2*/]; /* all 16 sub-pel positions  */
    uint8   *hpel_cand[9];      /* pointer to half-pel position */
    int     best_hpel_pos;          /* best position */
    uint8   qpel_cand[8][24*16];        /* pointer to quarter-pel position */
    int     best_qpel_pos;
    uint8   *bilin_base[9][4];    /* pointer to 4 position at top left of bilinear quarter-pel */

    /* need for intra refresh rate */
    uint8   *intraSearch;       /* Intra Array for MBs to be intra searched */
    uint    firstIntraRefreshMBIndx; /* keep track for intra refresh */

    int     i4_sad;             /* temporary for i4 mode SAD */
    int     *min_cost;          /* Minimum cost for the all MBs */
    int     lambda_mode;        /* Lagrange parameter for mode selection */
    int     lambda_motion;      /* Lagrange parameter for MV selection */

    uint8   *mvbits_array;      /* Table for bits spent in the cost funciton */
    uint8   *mvbits;            /* An offset to the above array. */

    /* to speedup the SAD calculation */
    void *sad_extra_info;
    uint8 currYMB[256];     /* interleaved current macroblock in HTFM order */

#ifdef HTFM
    int nrmlz_th[48];       /* Threshold for fast SAD calculation using HTFM */
    HTFM_Stat htfm_stat;    /* For statistics collection */
#endif

    /* statistics */
    int numIntraMB;         /* keep track of number of intra MB */

    /* encoding complexity control */
    uint fullsearch_enable; /* flag to enable full-pel full-search */

    /* misc.*/
    bool outOfBandParamSet; /* flag to enable out-of-band param set */

    AVCSeqParamSet extSPS; /* for external SPS */
    AVCPicParamSet extPPS; /* for external PPS */

    /* time control */
    uint32  prevFrameNum;   /* previous frame number starting from modTimeRef */
    uint32  modTimeRef;     /* Reference modTime update every I-Vop*/
    uint32  wrapModTime;    /* Offset to modTime Ref, rarely used */

    uint    prevProcFrameNum;  /* previously processed frame number, could be skipped */
    uint    prevCodedFrameNum;  /* previously encoded frame number */
    /* POC related variables */
    uint32  dispOrdPOCRef;      /* reference POC is displayer order unit. */

    /* Function pointers */
    AVCEncFuncPtr *functionPointer; /* store pointers to platform specific functions */

    /* Application control data */
    AVCHandle *avcHandle;


} AVCEncObject;


#endif /*AVCENC_INT_H_INCLUDED*/

