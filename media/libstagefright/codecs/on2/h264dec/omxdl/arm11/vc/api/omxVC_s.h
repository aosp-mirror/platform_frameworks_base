;/******************************************************************************
;// Copyright (c) 1999-2005 The Khronos Group Inc. All Rights Reserved
;//
;//
;//
;//
;//
;//
;//
;//
;******************************************************************************/

;/** =============== Structure Definition for Sample Generation ============== */
;/** transparent status */

;enum {
OMX_VIDEO_TRANSPARENT	EQU 0;	/** Wholly transparent */
OMX_VIDEO_PARTIAL		EQU 1;	/** Partially transparent */
OMX_VIDEO_OPAQUE		EQU 2;	/** Opaque */
;}

;/** direction */
;enum {
OMX_VIDEO_NONE			EQU 0;
OMX_VIDEO_HORIZONTAL	EQU 1;
OMX_VIDEO_VERTICAL		EQU 2;
;}

;/** bilinear interpolation type */
;enum {
OMX_VIDEO_INTEGER_PIXEL EQU 0;	/** case ¡°a¡± */
OMX_VIDEO_HALF_PIXEL_X  EQU 1;	/** case ¡°b¡± */
OMX_VIDEO_HALF_PIXEL_Y  EQU 2;	/** case ¡°c¡± */
OMX_VIDEO_HALF_PIXEL_XY EQU 3;	/** case ¡°d¡± */
;}

;enum {
OMX_UPPER  				EQU 1;			/** set if the above macroblock is available */
OMX_LEFT   				EQU 2;			/** set if the left macroblock is available */
OMX_CENTER 				EQU 4;
OMX_RIGHT				EQU 8;
OMX_LOWER  				EQU	16;
OMX_UPPER_LEFT  		EQU 32;		/** set if the above-left macroblock is available */
OMX_UPPER_RIGHT 		EQU 64;		/** set if the above-right macroblock is available */
OMX_LOWER_LEFT  		EQU 128;
OMX_LOWER_RIGHT 		EQU 256
;}

;enum {
OMX_VIDEO_LUMINANCE  	EQU 0;	/** Luminance component */
OMX_VIDEO_CHROMINANCE  	EQU 1;	/** chrominance component */
OMX_VIDEO_ALPHA  		EQU 2;			/** Alpha component */
;}

;enum {
OMX_VIDEO_INTER			EQU 0;	/** P picture or P-VOP */
OMX_VIDEO_INTER_Q		EQU 1;	/** P picture or P-VOP */
OMX_VIDEO_INTER4V		EQU 2;	/** P picture or P-VOP */
OMX_VIDEO_INTRA			EQU 3;	/** I and P picture; I- and P-VOP */
OMX_VIDEO_INTRA_Q		EQU 4;	/** I and P picture; I- and P-VOP */
OMX_VIDEO_INTER4V_Q		EQU 5;	/** P picture or P-VOP (H.263)*/
OMX_VIDEO_DIRECT		EQU 6;	/** B picture or B-VOP (MPEG-4 only) */
OMX_VIDEO_INTERPOLATE	EQU 7;	/** B picture or B-VOP */
OMX_VIDEO_BACKWARD		EQU 8;	/** B picture or B-VOP */
OMX_VIDEO_FORWARD		EQU 9;	/** B picture or B-VOP */
OMX_VIDEO_NOTCODED		EQU 10;	/** B picture or B-VOP */
;}

;enum {
OMX_16X16_VERT 			EQU 0;		/** Intra_16x16_Vertical (prediction mode) */
OMX_16X16_HOR 			EQU 1;		/** Intra_16x16_Horizontal (prediction mode) */
OMX_16X16_DC 			EQU 2;		/** Intra_16x16_DC (prediction mode) */
OMX_16X16_PLANE 		EQU 3;	/** Intra_16x16_Plane (prediction mode) */
;}

;enum {
OMX_4x4_VERT 			EQU 0;		/** Intra_4x4_Vertical (prediction mode) */
OMX_4x4_HOR  			EQU 1;		/** Intra_4x4_Horizontal (prediction mode) */
OMX_4x4_DC   			EQU 2;		/** Intra_4x4_DC (prediction mode) */
OMX_4x4_DIAG_DL 		EQU 3;	/** Intra_4x4_Diagonal_Down_Left (prediction mode) */
OMX_4x4_DIAG_DR 		EQU 4;	/** Intra_4x4_Diagonal_Down_Right (prediction mode) */
OMX_4x4_VR 				EQU 5;			/** Intra_4x4_Vertical_Right (prediction mode) */
OMX_4x4_HD 				EQU 6;			/** Intra_4x4_Horizontal_Down (prediction mode) */
OMX_4x4_VL 				EQU 7;			/** Intra_4x4_Vertical_Left (prediction mode) */
OMX_4x4_HU 				EQU 8;			/** Intra_4x4_Horizontal_Up (prediction mode) */
;}

;enum {
OMX_CHROMA_DC 			EQU 0;		/** Intra_Chroma_DC (prediction mode) */
OMX_CHROMA_HOR 			EQU 1;		/** Intra_Chroma_Horizontal (prediction mode) */
OMX_CHROMA_VERT 		EQU 2;	/** Intra_Chroma_Vertical (prediction mode) */
OMX_CHROMA_PLANE 		EQU 3;	/** Intra_Chroma_Plane (prediction mode) */
;}

;typedef	struct {	
x	EQU	0;
y	EQU	4;
;}OMXCoordinate;

;typedef struct {
dx	EQU	0;
dy	EQU	2;
;}OMXMotionVector;

;typedef struct {
xx		EQU	0;
yy		EQU	4;
width	EQU	8;
height	EQU	12;
;}OMXiRect;

;typedef enum {
OMX_VC_INTER         EQU 0;        /** P picture or P-VOP */
OMX_VC_INTER_Q       EQU 1;       /** P picture or P-VOP */
OMX_VC_INTER4V       EQU 2;       /** P picture or P-VOP */
OMX_VC_INTRA         EQU 3;        /** I and P picture, I- and P-VOP */
OMX_VC_INTRA_Q       EQU 4;       /** I and P picture, I- and P-VOP */
OMX_VC_INTER4V_Q     EQU 5;    /** P picture or P-VOP (H.263)*/
;} OMXVCM4P2MacroblockType;

;enum {
OMX_VC_NONE          EQU 0
OMX_VC_HORIZONTAL    EQU 1
OMX_VC_VERTICAL      EQU 2 
;};


	END

