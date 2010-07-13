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

#ifndef _MP4LIB_INT_H_
#define _MP4LIB_INT_H_

#include "mp4def.h"
#include "mp4enc_api.h"
#include "rate_control.h"

/* BitstreamEncVideo will be modified */
typedef struct tagBitstream
{
    Int(*writeVideoPacket)(UChar *buf, Int nbytes_required);   /*write video packet out */
    UChar *bitstreamBuffer; /*buffer to hold one video packet*/
    Int bufferSize; /*total bitstream buffer size in bytes */
    Int byteCount;  /*how many bytes already encoded*/
    UInt word;      /*hold one word temporarily */
    Int bitLeft;    /*number of bits left in "word" */
    UChar* overrunBuffer;  /* pointer to overrun buffer */
    Int oBSize;     /* length of overrun buffer */
    struct tagVideoEncData *video;
} BitstreamEncVideo;

typedef struct tagVOP
{
    PIXEL   *yChan;             /* The Y component */
    PIXEL   *uChan;             /* The U component */
    PIXEL   *vChan;             /* The V component */
    Int     frame;              /* frame number */
    Int     volID;              /* Layer number */
    //Int       timeStamp;          /* Vop TimeStamp in msec */

    /* Syntax elements copied from VOL (standard) */
    Int     width;              /* Width (multiple of 16) */
    Int     height;             /* Height (multiple of 16) */
    Int     pitch;              /* Pitch (differs from width for UMV case) */
    Int     padded;     /* flag whether this frame has been padded */

    /* Actual syntax elements for VOP (standard) */
    Int     predictionType;     /* VOP prediction type */
    Int     timeInc;            /* VOP time increment (relative to last mtb) */
    Int     vopCoded;
    Int     roundingType;
    Int     intraDCVlcThr;
    Int     quantizer;          /* VOP quantizer */
    Int     fcodeForward;       /* VOP dynamic range of motion vectors */
    Int     fcodeBackward;      /* VOP dynamic range of motion vectors */
    Int     refSelectCode;      /* enhancement layer reference select code */

    /* H.263 parameters */
    Int     gobNumber;
    Int     gobFrameID;
    Int     temporalRef;        /* temporal reference, roll over at 256 */
    Int     temporalInterval;   /* increase every 256 temporalRef */

} Vop;

typedef struct tagVol
{
    Int     volID;              /* VOL identifier (for tracking) */
    Int     shortVideoHeader;   /* shortVideoHeader mode */
    Int     GOVStart;           /* Insert GOV Header */
    Int     timeIncrementResolution;    /* VOL time increment */
    Int     nbitsTimeIncRes;    /* number of bits for time increment */
    Int     timeIncrement;      /* time increment */
    Int     moduloTimeBase;     /* internal decoder clock */
    Int     prevModuloTimeBase; /* in case of pre-frameskip */

    Int     fixedVopRate;
    BitstreamEncVideo  *stream; /* library bitstream buffer (input buffer) */

    /* VOL Dimensions */
    Int     width;              /* Width */
    Int     height;             /* Height */

    /* Error Resilience Flags */
    Int     ResyncMarkerDisable; /* VOL Disable Resynch Markers */
    Int     useReverseVLC;      /* VOL reversible VLCs */
    Int     dataPartitioning;   /* VOL data partitioning */

    /* Quantization related parameters */
    Int     quantPrecision;     /* Quantizer precision */
    Int     quantType;          /* MPEG-4 or H.263 Quantization Type */

    /* Added loaded quant mat, 05/22/2000 */
    Int     loadIntraQuantMat;      /* Load intra quantization matrix */
    Int     loadNonIntraQuantMat;   /* Load nonintra quantization matrix */
    Int     iqmat[64];          /* Intra quant.matrix */
    Int     niqmat[64];         /* Non-intra quant.matrix */


    /* Parameters used for scalability */
    Int     scalability;        /* VOL scalability (flag) */
    Int     scalType;           /* temporal = 0, spatial = 1, both = 2 */

    Int     refVolID;           /* VOL id of reference VOL */
    Int     refSampDir;         /* VOL resol. of ref. VOL */
    Int     horSamp_n;          /* VOL hor. resampling of ref. VOL given by */
    Int     horSamp_m;          /* sampfac = hor_samp_n/hor_samp_m      */
    Int     verSamp_n;          /* VOL ver. resampling of ref. VOL given by */
    Int     verSamp_m;          /* sampfac = ver_samp_n/ver_samp_m      */
    Int     enhancementType;    /* VOL type of enhancement layer */

    /* These variables were added since they are used a lot. */
    Int     nMBPerRow, nMBPerCol;   /* number of MBs in each row & column    */
    Int     nTotalMB;
    Int     nBitsForMBID;           /* how many bits required for MB number? */

    /* for short video header */
    Int     nMBinGOB;           /* number of MBs in GOB, 05/22/00 */
    Int     nGOBinVop;          /* number of GOB in Vop  05/22/00 */
} Vol;

typedef struct tagMacroBlock
{
    Int     mb_x;               /* X coordinate */
    Int     mb_y;               /* Y coordinate */
    Short   block[9][64];       /* 4-Y, U and V blocks , and AAN Scale*/
} MacroBlock;

typedef struct tagRunLevelBlock
{
    Int run[64];        /* Runlength */
    Int level[64];      /* Abs(level) */
    Int s[64];          /* sign level */
} RunLevelBlock;

typedef struct tagHeaderInfoDecVideo
{
    UChar       *Mode;              /* Modes INTRA/INTER/etc. */
    UChar       *CBP;               /* MCBPC/CBPY stuff */
} HeaderInfoEncVideo;

typedef Short typeDCStore[6];   /* ACDC */
typedef Short typeDCACStore[4][8];

typedef struct tagMOT
{
    Int x;  /* half-pel resolution x component */
    Int y;      /* half-pel resolution y component */
    Int sad;  /* SAD */
} MOT;

typedef struct tagHintTrackInfo
{
    UChar MTB;
    UChar LayerID;
    UChar CodeType;
    UChar RefSelCode;

} HintTrackInfo;


typedef struct tagVideoEncParams
{
    //Int       Width;                  /* Input Width */
    //Int       Height;                 /* Input Height */
    //float FrameRate;              /* Input Frame Rate */
    UInt    TimeIncrementRes;       /* timeIncrementRes */

    /*VOL Parameters */
    Int     nLayers;
    Int     LayerWidth[4];          /* Encoded Width */
    Int     LayerHeight[4];         /* Encoded Height */
    float   LayerFrameRate[4];      /* Encoded Frame Rate */
    Int     LayerBitRate[4];        /* Encoded BitRate */
    Int     LayerMaxBitRate[4];     /* Maximum Encoded BitRate */
    float   LayerMaxFrameRate[4];   /* Maximum Encoded Frame Rate */
    Int     LayerMaxMbsPerSec[4];   /* Maximum mbs per second, according to the specified profile and level */
    Int     LayerMaxBufferSize[4];  /* Maximum buffer size, according to the specified profile and level */

    Bool    ResyncMarkerDisable;    /* Disable Resync Marker */
    Bool    DataPartitioning;       /* Base Layer Data Partitioning */
    Bool    ReversibleVLC;          /* RVLC when Data Partitioning */
    Bool    ACDCPrediction;         /* AC/DC Prediction    */
    Int     QuantType[4];           /* H263, MPEG2 */
    Int     InitQuantBvop[4];
    Int     InitQuantPvop[4];
    Int     InitQuantIvop[4];
    Int     ResyncPacketsize;

    Int     RoundingType;
    Int     IntraDCVlcThr;

    /* Rate Control Parameters */
    MP4RateControlType  RC_Type;        /*Constant Q, M4 constantRate, VM5+, M4RC,MPEG2TM5 */

    /* Intra Refresh Parameters */
    Int     IntraPeriod;            /* Intra update period */
    Int     Refresh;                /* Number of MBs refresh in each frame */
    /* Other Parameters */
    Bool    SceneChange_Det;        /* scene change detection */
    Bool    FineFrameSkip_Enabled;  /* src rate resolution frame skipping */
    Bool    VBR_Enabled;            /* VBR rate control */
    Bool    NoFrameSkip_Enabled;    /* do not allow frame skip */
    Bool    NoPreSkip_Enabled;      /* do not allow pre-skip */

    Bool    H263_Enabled;           /* H263 Short Header */
    Bool    GOV_Enabled;            /* GOV Header Enabled */
    Bool    SequenceStartCode;      /* This probably should be removed */
    Bool    FullSearch_Enabled;     /* full-pel exhaustive search motion estimation */
    Bool    HalfPel_Enabled;        /* Turn Halfpel ME on or off */
    Bool    MV8x8_Enabled;          /* Enable 8x8 motion vectors */
    Bool    RD_opt_Enabled;         /* Enable operational R-D optimization */
    Int     GOB_Header_Interval;        /* Enable encoding GOB header in H263_WITH_ERR_RES and SHORT_HERDER_WITH_ERR_RES */
    Int     SearchRange;            /* Search range for 16x16 motion vector */
    Int     MemoryUsage;            /* Amount of memory allocated */
    Int     GetVolHeader[2];        /* Flag to check if Vol Header has been retrieved */
    Int     BufferSize[2];          /* Buffer Size for Base and Enhance Layers */
    Int     ProfileLevel[2];        /* Profile and Level for encoding purposes */
    float   VBV_delay;              /* VBV buffer size in the form of delay */
    Int     maxFrameSize;           /* maximum frame size(bits) for H263/Short header mode, k*16384 */
    Int     profile_table_index;    /* index for profile and level tables given the specified profile and level */

} VideoEncParams;

/* platform dependent functions */
typedef struct tagFuncPtr
{
//  Int (*SAD_MB_HalfPel)(UChar *ref,UChar *blk,Int dmin_lx,Int xh,Int yh,void *extra_info);
    Int(*SAD_MB_HalfPel[4])(UChar*, UChar*, Int, void *);
    Int(*SAD_Blk_HalfPel)(UChar *ref, UChar *blk, Int dmin, Int lx, Int rx, Int xh, Int yh, void *extra_info);
    Int(*SAD_Macroblock)(UChar *ref, UChar *blk, Int dmin_lx, void *extra_info);
    Int(*SAD_Block)(UChar *ref, UChar *blk, Int dmin, Int lx, void *extra_info);
    Int(*SAD_MB_PADDING)(UChar *ref, UChar *blk, Int dmin, Int lx, void *extra_info); /*, 4/21/01 */
    void (*ComputeMBSum)(UChar *cur, Int lx, MOT *mot_mb);
    void (*ChooseMode)(UChar *Mode, UChar *cur, Int lx, Int min_SAD);
    void (*GetHalfPelMBRegion)(UChar *cand, UChar *hmem, Int lx);
    void (*blockIdct)(Int *block);


} FuncPtr;

/* 04/09/01, for multipass rate control */

typedef struct tagRDInfo
{
    Int QP;
    Int actual_bits;
    float mad;
    float R_D;
} RDInfo;

typedef struct tagMultiPass
{
    /* multipass rate control data */
    Int target_bits;    /* target bits for current frame, = rc->T */
    Int actual_bits;    /* actual bits for current frame obtained after encoding, = rc->Rc*/
    Int QP;             /* quantization level for current frame, = rc->Qc*/
    Int prev_QP;        /* quantization level for previous frame */
    Int prev_prev_QP;   /* quantization level for previous frame before last*/
    float mad;          /* mad for current frame, = video->avgMAD*/
    Int bitrate;        /* bitrate for current frame */
    float framerate;    /* framerate for current frame*/

    Int nRe_Quantized;  /* control variable for multipass encoding, */
    /* 0 : first pass */
    /* 1 : intermediate pass(quantization and VLC loop only) */
    /* 2 : final pass(de-quantization, idct, etc) */
    /* 3 : macroblock level rate control */

    Int encoded_frames;     /* counter for all encoded frames */
    Int re_encoded_frames;  /* counter for all multipass encoded frames*/
    Int re_encoded_times;   /* counter for all times of multipass frame encoding */

    /* Multiple frame prediction*/
    RDInfo **pRDSamples;        /* pRDSamples[30][32], 30->30fps, 32 -> 5 bit quantizer, 32 candidates*/
    Int framePos;               /* specific position in previous multiple frames*/
    Int frameRange;             /* number of overall previous multiple frames */
    Int samplesPerFrame[30];    /* number of samples per frame, 30->30fps */

    /* Bit allocation for scene change frames and high motion frames */
    float sum_mad;
    Int counter_BTsrc;  /* BT = Bit Transfer, bit transfer from low motion frames or less complicatedly compressed frames */
    Int counter_BTdst;  /* BT = Bit Transfer, bit transfer to scene change frames or high motion frames or more complicatedly compressed frames */
    float sum_QP;
    Int diff_counter;   /* diff_counter = -diff_counter_BTdst, or diff_counter_BTsrc */

    /* For target bitrate or framerate update */
    float target_bits_per_frame;        /* = C = bitrate/framerate */
    float target_bits_per_frame_prev;   /* previous C */
    float aver_mad;                     /* so-far average mad could replace sum_mad */
    float aver_mad_prev;                /* previous average mad */
    Int   overlapped_win_size;          /* transition period of time */
    Int   encoded_frames_prev;          /* previous encoded_frames */
} MultiPass;

/* End */

#ifdef HTFM
typedef struct tagHTFM_Stat
{
    Int abs_dif_mad_avg;
    UInt countbreak;
    Int offsetArray[16];
    Int offsetRef[16];
} HTFM_Stat;
#endif

/* Global structure that can be passed around */
typedef struct tagVideoEncData
{
    /* VOL Header Initialization */
    UChar   volInitialize[4];       /* Used to Write VOL Headers */
    /* Data For Layers (Scalability) */
    Int     numberOfLayers;     /* Number of Layers */
    Vol     **vol;              /* Data stored for each VOL */

    /* Data used for encoding frames */
    VideoEncFrameIO *input;     /* original input frame */
    Vop     *currVop;           /* Current reconstructed VOP */
    Vop     *prevBaseVop;       /* Previous reference Base Vop */
    Vop     *nextBaseVop;       /* Next reference Base Vop */
    Vop     *prevEnhanceVop;/* Previous Enhancement Layer Vop */
    Vop     *forwardRefVop;     /* Forward Reference VOP */
    Vop     *backwardRefVop;    /* Backward Reference VOP */

    /* scratch memory */
    BitstreamEncVideo  *bitstream1; /* Used for data partitioning */
    BitstreamEncVideo  *bitstream2; /* and combined modes as      */
    BitstreamEncVideo  *bitstream3; /* intermediate storages      */

    UChar   *overrunBuffer;  /* extra output buffer to prevent current skip due to output buffer overrun*/
    Int     oBSize;     /* size of allocated overrun buffer */

    Int dc_scalar_1;            /*dc scalar for Y block */
    Int dc_scalar_2;            /*dc scalar for U, V block*/

    /* Annex L Rate Control */
    rateControl     *rc[4];         /* Pointer to Rate Control structure*/
    /* 12/25/00, each R.C. for each layer */

    /********* motion compensation related variables ****************/
    MOT     **mot;              /* Motion vectors */
    /*  where [mbnum][0] = 1MV.
        [mbnum][1...4] = 4MVs
        [mbnum][5] = backward MV.
        [mbnum][6] = delta MV for direct mode.
        [mbnum][7] = nothing yet. */
    UChar   *intraArray;            /* Intra Update Arrary */
    float   sumMAD;             /* SAD/MAD for frame */

    /* to speedup the SAD calculation */
    void *sad_extra_info;
#ifdef HTFM
    Int nrmlz_th[48];       /* Threshold for fast SAD calculation using HTFM */
    HTFM_Stat htfm_stat;    /* For statistics collection */
#endif

    /*Tao 04/09/00  For DCT routine */
    UChar currYMB[256];     /* interleaved current macroblock in HTFM order */
    MacroBlock  *outputMB;          /* Output MB to VLC encode */
    UChar   predictedMB[384];   /* scrath memory for predicted value */
    RunLevelBlock RLB[6];       /* Run and Level of coefficients! */
    Short   dataBlock[128];     /* DCT block data before and after quant/dequant*/

    UChar   bitmaprow[8];       /* Need to keep it for ACDCPrediction, 8 bytes for alignment, need only 6 */
    UChar   bitmapcol[6][8];
    UInt    bitmapzz[6][2]; /* for zigzag bitmap */
    Int     zeroMV;         /* flag for zero MV */

    Int     usePrevQP;      /* flag for intraDCVlcThreshold switch decision */
    Int     QP_prev;            /* use for DQUANT calculation */
    Int     *acPredFlag;        /* */
    typeDCStore     *predDC;        /* The DC coeffs for each MB */
    typeDCACStore   *predDCAC_row;
    typeDCACStore   *predDCAC_col;


    UChar   *sliceNo;           /* Slice Number for each MB */

    Int     header_bits;        /* header bits in frmae */
    HeaderInfoEncVideo  headerInfo; /* MB Header information */
    UChar   zz_direction;       /* direction of zigzag scan */
    UChar   *QPMB;              /* Quantizer value for each MB */

    /* Miscellaneous data points to be passed */
    float   FrameRate;          /* Src frame Rate */

    ULong   nextModTime;        /* expected next frame time */
    UInt    prevFrameNum[4];    /* previous frame number starting from modTimeRef */
    UInt    modTimeRef;     /* Reference modTime update every I-Vop*/
    UInt    refTick[4];         /* second aligned referenc tick */
    Int     relLayerCodeTime[4];/* Next coding time for each Layer relative to highest layer */

    ULong   modTime;            /* Input frame modTime */
    Int     currLayer;          /* Current frame layer  */
    Int     mbnum;              /*  Macroblock number */

    /* slice coding, state variables */
    Vop     *tempForwRefVop;
    Int     tempRefSelCode;
    Int     end_of_buf;         /* end of bitstream buffer flag */
    Int     slice_coding;       /* flag for slice based coding */
    Int     totalSAD;           /* So far total SAD for a frame */
    Int     numIntra;           /* So far number of Intra MB */
    Int     offset;             /* So far MB offset */
    Int     ind_x, ind_y;       /* So far MB coordinate */
    Int     collect;
    Int     hp_guess;
    /*********************************/

    HintTrackInfo hintTrackInfo;    /* hintTrackInfo */
    /* IntraPeriod, Timestamp, etc. */
    float       nextEncIVop;    /* counter til the next I-Vop */
    float       numVopsInGOP;   /* value at the beginning of nextEncIVop */

    /* platform dependent functions */
    FuncPtr     *functionPointer;   /* structure containing platform dependent functions */

    /* Application controls */
    VideoEncControls    *videoEncControls;
    VideoEncParams      *encParams;

    MultiPass *pMP[4]; /* for multipass encoding, 4 represents 4 layer encoding */

} VideoEncData;

/*************************************************************/
/*                  VLC structures                           */
/*************************************************************/

typedef struct tagVLCtable
{
    unsigned int code; /* right justified */
    int len;
} VLCtable, *LPVLCtable;


/*************************************************************/
/*                  Approx DCT                               */
/*************************************************************/
typedef struct struct_approxDCT  approxDCT;
struct struct_approxDCT
{
    Void(*BlockDCT8x8)(Int *, Int *, UChar *, UChar *, Int, Int);
    Void(*BlockDCT8x8Intra)(Int *, Int *, UChar *, UChar *, Int, Int);
    Void(*BlockDCT8x8wSub)(Int *, Int *, UChar *, UChar *, Int, Int);
};

/*************************************************************/
/*                  QP structure                             */
/*************************************************************/

struct QPstruct
{
    Int QPx2 ;
    Int QP;
    Int QPdiv2;
    Int QPx2plus;
    Int Addition;
};


#endif /* _MP4LIB_INT_H_ */

