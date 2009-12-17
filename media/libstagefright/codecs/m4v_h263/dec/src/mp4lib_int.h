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
#include "mp4dec_api.h" // extra structure

#undef ENABLE_LOG
#define BITRATE_AVERAGE_WINDOW 4
#define FRAMERATE_SCALE ((BITRATE_AVERAGE_WINDOW-1)*10000L)
#define FAST_IDCT            /* , for fast Variable complexity IDCT */
//#define PV_DEC_EXTERNAL_IDCT  /*  for separate IDCT (i.e. no direct access to output frame) */
#define PV_ANNEX_IJKT_SUPPORT
#define mid_gray 1024

typedef struct tagBitstream
{
    /* function that reteive data from outside the library.   04/11/2000 */
    /*    In frame-based decoding mode, this shall be NULL.   08/29/2000 */
    uint32 curr_word;
    uint32 next_word;
    uint8 *bitstreamBuffer; /* pointer to buffer memory */
    int32  read_point;          /* starting point in the buffer to be read to cache */
    int  incnt;             /* bit left in cached */
    int  incnt_next;
    uint32 bitcnt;          /* total bit read so-far (from inbfr)*/
    int32  data_end_pos;        /*should be added ,  06/07/2000 */
    int searched_frame_boundary;
} BitstreamDecVideo, *LPBitstreamDecVideo;

/* complexity estimation parameters */
typedef struct tagComplexity_Est
{
    uint8   text_1;             /* texture_complexity_estimation_set_1  */
    uint8   text_2;             /* texture_complexity_estimation_set_2  */
    uint8   mc;                 /* motion_compensation_complexity       */
} Complexity_Est;


typedef struct tagVop
{
    PIXEL   *yChan;             /* The Y component */
    PIXEL   *uChan;             /* The U component */
    PIXEL   *vChan;             /* The V component */

    uint32  timeStamp;          /* Vop TimeStamp in msec */

    /* Actual syntax elements for VOP (standard) */
    int     predictionType;     /* VOP prediction type */
    uint    timeInc;            /* VOP time increment (relative to last mtb) */
    int     vopCoded;
    int     roundingType;
    int     intraDCVlcThr;
    int16       quantizer;          /* VOP quantizer */
    int     fcodeForward;       /* VOP dynamic range of motion vectors */
    int     fcodeBackward;      /* VOP dynamic range of motion vectors */
    int     refSelectCode;      /* enhancement layer reference select code */

    /* H.263 parameters */
    int     gobNumber;
    int     gobFrameID;
    int     temporalRef;        /* temporal reference, roll over at 256 */
    int     ETR;
} Vop;

typedef struct tagVol
{
    int     volID;                  /* VOL identifier (for tracking) */
    uint    timeIncrementResolution;/* VOL time increment */
    int     nbitsTimeIncRes;        /* number of bits for time increment  */
    uint        timeInc_offset;         /* timeInc offset for multiple VOP in a packet  */
    uint32  moduloTimeBase;         /* internal decoder clock */
    int     fixedVopRate;
    BitstreamDecVideo   *bitstream; /* library bitstream buffer (input buffer) */

    int     complexity_estDisable;  /* VOL disable complexity estimation */
    int     complexity_estMethod;   /* VOL complexity estimation method */
    Complexity_Est complexity;      /* complexity estimation flags      */

    /* Error Resilience Flags */
    int     errorResDisable;        /* VOL disable error resilence mode */
    /*            (Use Resynch markers) */
    int     useReverseVLC;          /* VOL reversible VLCs */
    int     dataPartitioning;       /* VOL data partitioning */

    /* Bit depth  */
    uint    bitsPerPixel;
//  int     mid_gray;               /* 2^(bits_per_pixel+2) */

    /* Quantization related parameters */
    int     quantPrecision;         /* Quantizer precision */
    uint    quantType;              /* MPEG-4 or H.263 Quantization Type */
    /* Added loaded quant mat,  05/22/2000 */
    int     loadIntraQuantMat;      /* Load intra quantization matrix */
    int     loadNonIntraQuantMat;   /* Load nonintra quantization matrix */
    int     iqmat[64];              /* Intra quant.matrix */
    int     niqmat[64];             /* Non-intra quant.matrix */

    /* Parameters used for scalability */
    int     scalability;            /* VOL scalability (flag) */
    int     scalType;               /* temporal = 0, spatial = 1, both = 2 */

    int     refVolID;               /* VOL id of reference VOL */
    int     refSampDir;             /* VOL resol. of ref. VOL */
    int     horSamp_n;              /* VOL hor. resampling of ref. VOL given by */
    int     horSamp_m;              /*     sampfac = hor_samp_n/hor_samp_m      */
    int     verSamp_n;              /* VOL ver. resampling of ref. VOL given by */
    int     verSamp_m;              /*     sampfac = ver_samp_n/ver_samp_m      */
    int     enhancementType;        /* VOL type of enhancement layer */
    /* profile and level */
    int32   profile_level_id;       /* 8-bit profile and level */ //  6/17/04

} Vol;


typedef int16 typeMBStore[6][NCOEFF_BLOCK];

typedef struct tagMacroBlock
{
    typeMBStore         block;              /* blocks */         /*  ACDC */
    uint8   pred_block[384];        /* prediction block,  Aug 3,2005 */
    uint8   bitmapcol[6][8];
    uint8   bitmaprow[6];
    int     no_coeff[6];
    int     DCScalarLum;                        /* Luminance DC Scalar */
    int     DCScalarChr;                        /* Chrominance DC Scalar */
#ifdef PV_ANNEX_IJKT_SUPPORT
    int direction;
#endif
} MacroBlock;

typedef struct tagHeaderInfoDecVideo
{
    uint8       *Mode;              /* Modes INTRA/INTER/etc. */
    uint8       *CBP;               /* MCBPC/CBPY stuff */
} HeaderInfoDecVideo;


/************************************************************/
/*                  VLC structures                          */
/************************************************************/
typedef struct tagTcoef
{
    uint last;
    uint run;
    int level;
    uint sign;
} Tcoef, *LPTcoef;



typedef struct tagVLCtab
{
    int32 val;
    int32 len;
} VLCtab, *LPVLCtab;

typedef struct tagVLCshorttab
{
    int16 val;
    int16 len;
} VLCshorttab, *LPVLCshorttab ; /* for space saving, Antoine Nguyen*/

typedef struct tagVLCtab2
{
    uint8 run;
    uint8 level;
    uint8 last;
    uint8 len;
} VLCtab2, *LPVLCtab2;  /* 10/24/2000 */

/* This type is designed for fast access of DC/AC */
/*    prediction data.  If the compiler is smart  */
/*    enough, it will use shifting for indexing.  */
/*     04/14/2000.                              */

typedef int16 typeDCStore[6];   /*  ACDC */
typedef int16 typeDCACStore[4][8];



/* Global structure that can be passed around */
typedef struct tagVideoDecData
{
    BitstreamDecVideo   *bitstream; /* library bitstream buffer (input buffer) */
    /* Data For Layers (Scalability) */
    Vol             **vol;                  /* Data stored for each VOL */

    /* Data used for reconstructing frames */
    Vop             *currVop;               /* Current VOP (frame)  */
    Vop             *prevVop;               /* Previous VOP (frame) */
    /* Data used to facilitate multiple layer decoding.   05/04/2000 */
    Vop             *prevEnhcVop;           /* New change to rid of memcpy().  04/24/2001 */
    Vop             **vopHeader;            /* one for each layer.   08/29/2000 */

    /* I/O structures */
    MacroBlock      *mblock;                    /* Macroblock data structure */
    uint8           *acPredFlag;                /*  */

    /* scratch memory used in data partitioned mode */
    typeDCStore     *predDC;        /*  The DC coeffs for each MB */
    typeDCACStore   *predDCAC_row;
    typeDCACStore   *predDCAC_col;

    int             usePrevQP;              /* running QP decision switch */
    uint8           *sliceNo;               /* Slice indicator for each MB  */
    /*     changed this to a 1D   */
    /*    array for optimization    */
    MOT             *motX;                  /* Motion vector in X direction */
    MOT             *motY;                  /* Motion vector in Y direction */
    HeaderInfoDecVideo  headerInfo;         /* MB Header information */
    int16           *QPMB;                  /* Quantizer value for each MB */

    uint8           *pstprcTypCur;          /* Postprocessing type for current frame */
    uint8           *pstprcTypPrv;          /* Postprocessing type for previous frame */
    /* scratch memory used in all modes */
    int             mbnum;                      /*  Macroblock number */
    uint            mbnum_row;
    int             mbnum_col;
    /* I added these variables since they are used a lot.   04/13/2000 */
    int     nMBPerRow, nMBPerCol;   /* number of MBs in each row & column    */
    int     nTotalMB;
    /* for short video header */
    int     nMBinGOB;               /* number of MBs in GOB,  05/22/00 */
    int     nGOBinVop;              /* number of GOB in Vop   05/22/00 */
    /* VOL Dimensions */
    int     width;                  /* Width */
    int     height;                 /* Height */
    int     displayWidth;               /* Handle image whose size is not a multiple of 16. */
    int     displayHeight;              /*   This is the actual size.   08/09/2000        */
    int32   size;
    /* Miscellaneous data points to be passed */
    int             frame_idx;              /* Current frame ID */
    int             frameRate;              /* Output frame Rate (over 10 seconds) */
    int32           duration;
    uint32          currTimestamp;
    int             currLayer;              /* Current frame layer  */
    int     shortVideoHeader;       /* shortVideoHeader mode */
    int     intra_acdcPredDisable;  /* VOL disable INTRA DC prediction */
    int             numberOfLayers;         /* Number of Layers */
    /* Frame to be used for concealment     07/07/2001 */
    uint8           *concealFrame;
    int             vop_coding_type;
    /* framerate and bitrate statistics counters.   08/23/2000 */
    int32           nBitsPerVop[BITRATE_AVERAGE_WINDOW];
    uint32          prevTimestamp[BITRATE_AVERAGE_WINDOW];
    int     nBitsForMBID;           /* how many bits required for MB number? */
    /* total data memory used by the docder library.   08/23/2000 */
    int32           memoryUsage;

    /* flag to turn on/off error concealment or soft decoding */
    int errorConcealment;

    /* Application controls */
    VideoDecControls    *videoDecControls;
    int                 postFilterType;     /* Postfilter mode  04/25/00 */



    PV_STATUS(*vlcDecCoeffIntra)(BitstreamDecVideo *stream, Tcoef *pTcoef/*, int intra_luma*/);
    PV_STATUS(*vlcDecCoeffInter)(BitstreamDecVideo *stream, Tcoef *pTcoef);
    int                 initialized;

    /* Annex IJKT */
    int     deblocking;
    int     slice_structure;
    int     modified_quant;
    int     advanced_INTRA;
    int16 QP_CHR;  /* ANNEX_T */
} VideoDecData;

/* for fast VLC+Dequant  10/12/2000*/
typedef int (*VlcDequantBlockFuncP)(void *video, int comp, int switched,
                                    uint8 *bitmaprow, uint8 *bitmapcol);

//////////////////////////////////////////////////////////////
//                  Decoder structures                      //
//////////////////////////////////////////////////////////////
#endif /* _MP4LIB_INT_H_ */

