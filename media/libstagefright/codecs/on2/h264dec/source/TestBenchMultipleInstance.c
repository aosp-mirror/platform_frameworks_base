/*
 * Copyright (C) 2009 The Android Open Source Project
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

/* CVS tag name for identification */
const char tagName[256] = "$Name: FIRST_ANDROID_COPYRIGHT $";

#include "H264SwDecApi.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define DEBUG(argv) printf argv

/* _NO_OUT disables output file writing */
#ifdef __arm
#define _NO_OUT
#endif

/*------------------------------------------------------------------------------

------------------------------------------------------------------------------*/
void WriteOutput(FILE *fid, u8 *data, u32 picSize);

u32 CropPicture(u8 *pOutImage, u8 *pInImage,
    u32 picWidth, u32 picHeight, CropParams *pCropParams);

void CropWriteOutput(FILE *fid, u8 *imageData, u32 cropDisplay,
        H264SwDecInfo *decInfo);

typedef struct
{
    H264SwDecInst decInst;
    H264SwDecInput decInput;
    H264SwDecOutput decOutput;
    H264SwDecPicture decPicture;
    H264SwDecInfo decInfo;
    FILE *foutput;
    char outFileName[256];
    u8 *byteStrmStart;
    u32 picNumber;
} Decoder;


/*------------------------------------------------------------------------------

------------------------------------------------------------------------------*/
int main(int argc, char **argv)
{

    i32 instCount, instRunning;
    i32 i;
    u32 maxNumPics;
    u32 strmLen;
    H264SwDecRet ret;
    u32 numErrors = 0;
    u32 cropDisplay = 0;
    u32 disableOutputReordering = 0;
    FILE *finput;
    Decoder **decoder;
    char outFileName[256] = "out.yuv";


    if ( argc > 1 && strcmp(argv[1], "-T") == 0 )
    {
        fprintf(stderr, "%s\n", tagName);
        return 0;
    }

    if (argc < 2)
    {
        DEBUG((
            "Usage: %s [-Nn] [-Ooutfile] [-P] [-U] [-C] [-R] [-T] file1.264 [file2.264] .. [fileN.264]\n",
            argv[0]));
        DEBUG(("\t-Nn forces decoding to stop after n pictures\n"));
#if defined(_NO_OUT)
        DEBUG(("\t-Ooutfile output writing disabled at compile time\n"));
#else
        DEBUG(("\t-Ooutfile write output to \"outfile\" (default out.yuv)\n"));
        DEBUG(("\t-Onone does not write output\n"));
#endif
        DEBUG(("\t-C display cropped image (default decoded image)\n"));
        DEBUG(("\t-R disable DPB output reordering\n"));
        DEBUG(("\t-T to print tag name and exit\n"));
        exit(100);
    }

    instCount = argc - 1;

    /* read command line arguments */
    maxNumPics = 0;
    for (i = 1; i < (argc-1); i++)
    {
        if ( strncmp(argv[i], "-N", 2) == 0 )
        {
            maxNumPics = (u32)atoi(argv[i]+2);
            instCount--;
        }
        else if ( strncmp(argv[i], "-O", 2) == 0 )
        {
            strcpy(outFileName, argv[i]+2);
            instCount--;
        }
        else if ( strcmp(argv[i], "-C") == 0 )
        {
            cropDisplay = 1;
            instCount--;
        }
        else if ( strcmp(argv[i], "-R") == 0 )
        {
            disableOutputReordering = 1;
            instCount--;
        }
    }

    if (instCount < 1)
    {
        DEBUG(("No input files\n"));
        exit(100);
    }

    /* allocate memory for multiple decoder instances
     * one instance for every stream file */
    decoder = (Decoder **)malloc(sizeof(Decoder*)*(u32)instCount);
    if (decoder == NULL)
    {
        DEBUG(("Unable to allocate memory\n"));
        exit(100);
    }

    /* prepare each decoder instance */
    for (i = 0; i < instCount; i++)
    {
        decoder[i] = (Decoder *)calloc(1, sizeof(Decoder));

        /* open input file */
        finput = fopen(argv[argc-instCount+i],"rb");
        if (finput == NULL)
        {
            DEBUG(("Unable to open input file <%s>\n", argv[argc-instCount+i]));
            exit(100);
        }

        DEBUG(("Reading input file[%d] %s\n", i, argv[argc-instCount+i]));

        /* read input stream to buffer */
        fseek(finput,0L,SEEK_END);
        strmLen = (u32)ftell(finput);
        rewind(finput);
        decoder[i]->byteStrmStart = (u8 *)malloc(sizeof(u8)*strmLen);
        if (decoder[i]->byteStrmStart == NULL)
        {
            DEBUG(("Unable to allocate memory\n"));
            exit(100);
        }
        fread(decoder[i]->byteStrmStart, sizeof(u8), strmLen, finput);
        fclose(finput);

        /* open output file */
        if (strcmp(outFileName, "none") != 0)
        {
#if defined(_NO_OUT)
            decoder[i]->foutput = NULL;
#else
            sprintf(decoder[i]->outFileName, "%s%i", outFileName, i);
            decoder[i]->foutput = fopen(decoder[i]->outFileName, "wb");
            if (decoder[i]->foutput == NULL)
            {
                DEBUG(("Unable to open output file\n"));
                exit(100);
            }
#endif
        }

        ret = H264SwDecInit(&(decoder[i]->decInst), disableOutputReordering);

        if (ret != H264SWDEC_OK)
        {
            DEBUG(("Init failed %d\n", ret));
            exit(100);
        }

        decoder[i]->decInput.pStream = decoder[i]->byteStrmStart;
        decoder[i]->decInput.dataLen = strmLen;
        decoder[i]->decInput.intraConcealmentMethod = 0;

    }

    /* main decoding loop */
    do
    {
        /* decode once using each instance */
        for (i = 0; i < instCount; i++)
        {
            ret = H264SwDecDecode(decoder[i]->decInst,
                                &(decoder[i]->decInput),
                                &(decoder[i]->decOutput));

            switch(ret)
            {

                case H264SWDEC_HDRS_RDY_BUFF_NOT_EMPTY:

                    ret = H264SwDecGetInfo(decoder[i]->decInst,
                            &(decoder[i]->decInfo));
                    if (ret != H264SWDEC_OK)
                        exit(1);

                    if (cropDisplay && decoder[i]->decInfo.croppingFlag)
                    {
                        DEBUG(("Decoder[%d] Cropping params: (%d, %d) %dx%d\n",
                            i,
                            decoder[i]->decInfo.cropParams.cropLeftOffset,
                            decoder[i]->decInfo.cropParams.cropTopOffset,
                            decoder[i]->decInfo.cropParams.cropOutWidth,
                            decoder[i]->decInfo.cropParams.cropOutHeight));
                    }

                    DEBUG(("Decoder[%d] Width %d Height %d\n", i,
                        decoder[i]->decInfo.picWidth,
                        decoder[i]->decInfo.picHeight));

                    DEBUG(("Decoder[%d] videoRange %d, matricCoefficients %d\n",
                        i, decoder[i]->decInfo.videoRange,
                        decoder[i]->decInfo.matrixCoefficients));
                    decoder[i]->decInput.dataLen -=
                        (u32)(decoder[i]->decOutput.pStrmCurrPos -
                              decoder[i]->decInput.pStream);
                    decoder[i]->decInput.pStream =
                        decoder[i]->decOutput.pStrmCurrPos;
                    break;

                case H264SWDEC_PIC_RDY_BUFF_NOT_EMPTY:
                    decoder[i]->decInput.dataLen -=
                        (u32)(decoder[i]->decOutput.pStrmCurrPos -
                              decoder[i]->decInput.pStream);
                    decoder[i]->decInput.pStream =
                        decoder[i]->decOutput.pStrmCurrPos;
                    /* fall through */
                case H264SWDEC_PIC_RDY:
                    if (ret == H264SWDEC_PIC_RDY)
                        decoder[i]->decInput.dataLen = 0;

                    ret = H264SwDecGetInfo(decoder[i]->decInst,
                            &(decoder[i]->decInfo));
                    if (ret != H264SWDEC_OK)
                        exit(1);

                    while (H264SwDecNextPicture(decoder[i]->decInst,
                            &(decoder[i]->decPicture), 0) == H264SWDEC_PIC_RDY)
                    {
                        decoder[i]->picNumber++;

                        numErrors += decoder[i]->decPicture.nbrOfErrMBs;

                        DEBUG(("Decoder[%d] PIC %d, type %s, concealed %d\n",
                            i, decoder[i]->picNumber,
                            decoder[i]->decPicture.isIdrPicture
                                ? "IDR" : "NON-IDR",
                            decoder[i]->decPicture.nbrOfErrMBs));
                        fflush(stdout);

                        CropWriteOutput(decoder[i]->foutput,
                                (u8*)decoder[i]->decPicture.pOutputPicture,
                                cropDisplay, &(decoder[i]->decInfo));
                    }

                    if (maxNumPics && decoder[i]->picNumber == maxNumPics)
                        decoder[i]->decInput.dataLen = 0;
                    break;

                case H264SWDEC_STRM_PROCESSED:
                case H264SWDEC_STRM_ERR:
                case H264SWDEC_PARAM_ERR:
                    decoder[i]->decInput.dataLen = 0;
                    break;

                default:
                    DEBUG(("Decoder[%d] FATAL ERROR\n", i));
                    exit(10);
                    break;

            }
        }

        /* check if any of the instances is still running (=has more data) */
        instRunning = instCount;
        for (i = 0; i < instCount; i++)
        {
            if (decoder[i]->decInput.dataLen == 0)
                instRunning--;
        }

    } while (instRunning);


    /* get last frames and close each instance */
    for (i = 0; i < instCount; i++)
    {
        while (H264SwDecNextPicture(decoder[i]->decInst,
                &(decoder[i]->decPicture), 1) == H264SWDEC_PIC_RDY)
        {
            decoder[i]->picNumber++;

            DEBUG(("Decoder[%d] PIC %d, type %s, concealed %d\n",
                i, decoder[i]->picNumber,
                decoder[i]->decPicture.isIdrPicture
                    ? "IDR" : "NON-IDR",
                decoder[i]->decPicture.nbrOfErrMBs));
            fflush(stdout);

            CropWriteOutput(decoder[i]->foutput,
                    (u8*)decoder[i]->decPicture.pOutputPicture,
                    cropDisplay, &(decoder[i]->decInfo));
        }

        H264SwDecRelease(decoder[i]->decInst);

        if (decoder[i]->foutput)
            fclose(decoder[i]->foutput);

        free(decoder[i]->byteStrmStart);

        free(decoder[i]);
    }

    free(decoder);

    if (numErrors)
        return 1;
    else
        return 0;

}

/*------------------------------------------------------------------------------

------------------------------------------------------------------------------*/
void CropWriteOutput(FILE *foutput, u8 *imageData, u32 cropDisplay,
        H264SwDecInfo *decInfo)
{
    u8 *tmpImage = NULL;
    u32 tmp, picSize;

    if (cropDisplay && decInfo->croppingFlag)
    {
        picSize = decInfo->cropParams.cropOutWidth *
                  decInfo->cropParams.cropOutHeight;
        picSize = (3 * picSize)/2;
        tmpImage = malloc(picSize);
        if (tmpImage == NULL)
            exit(1);
        tmp = CropPicture(tmpImage, imageData,
            decInfo->picWidth, decInfo->picHeight,
            &(decInfo->cropParams));
        if (tmp)
            exit(1);
        WriteOutput(foutput, tmpImage, picSize);
        free(tmpImage);
    }
    else
    {
        picSize = decInfo->picWidth * decInfo->picHeight;
        picSize = (3 * picSize)/2;
        WriteOutput(foutput, imageData, picSize);
    }

}

/*------------------------------------------------------------------------------

------------------------------------------------------------------------------*/
void WriteOutput(FILE *fid, u8 *data, u32 picSize)
{
    if (fid)
        fwrite(data, 1, picSize, fid);
}

/*------------------------------------------------------------------------------

    Function name:  H264SwDecTrace

------------------------------------------------------------------------------*/
void H264SwDecTrace(char *string)
{
    FILE *fp;

    fp = fopen("dec_api.trc", "at");

    if (!fp)
        return;

    fwrite(string, 1, strlen(string), fp);
    fwrite("\n", 1,1, fp);

    fclose(fp);
}

/*------------------------------------------------------------------------------

    Function name:  H264SwDecmalloc

------------------------------------------------------------------------------*/
void* H264SwDecMalloc(u32 size)
{
    return malloc(size);
}

/*------------------------------------------------------------------------------

    Function name:  H264SwDecFree

------------------------------------------------------------------------------*/
void H264SwDecFree(void *ptr)
{
    free(ptr);
}

/*------------------------------------------------------------------------------

    Function name:  H264SwDecMemcpy

------------------------------------------------------------------------------*/
void H264SwDecMemcpy(void *dest, void *src, u32 count)
{
    memcpy(dest, src, count);
}

/*------------------------------------------------------------------------------

    Function name:  H264SwDecMemset

------------------------------------------------------------------------------*/
void H264SwDecMemset(void *ptr, i32 value, u32 count)
{
    memset(ptr, value, count);
}

/*------------------------------------------------------------------------------

    Function name: CropPicture

------------------------------------------------------------------------------*/
u32 CropPicture(u8 *pOutImage, u8 *pInImage,
    u32 picWidth, u32 picHeight, CropParams *pCropParams)
{

    u32 i, j;
    u32 outWidth, outHeight;
    u8 *pOut, *pIn;

    if (pOutImage == NULL || pInImage == NULL || pCropParams == NULL ||
        !picWidth || !picHeight)
    {
        /* due to lint warning */
        free(pOutImage);
        return(1);
    }

    if ( ((pCropParams->cropLeftOffset + pCropParams->cropOutWidth) >
           picWidth ) ||
         ((pCropParams->cropTopOffset + pCropParams->cropOutHeight) >
           picHeight ) )
    {
        /* due to lint warning */
        free(pOutImage);
        return(1);
    }

    outWidth = pCropParams->cropOutWidth;
    outHeight = pCropParams->cropOutHeight;

    pIn = pInImage + pCropParams->cropTopOffset*picWidth +
        pCropParams->cropLeftOffset;
    pOut = pOutImage;

    /* luma */
    for (i = outHeight; i; i--)
    {
        for (j = outWidth; j; j--)
        {
            *pOut++ = *pIn++;
        }
        pIn += picWidth - outWidth;
    }

    outWidth >>= 1;
    outHeight >>= 1;

    pIn = pInImage + picWidth*picHeight +
        pCropParams->cropTopOffset*picWidth/4 + pCropParams->cropLeftOffset/2;

    /* cb */
    for (i = outHeight; i; i--)
    {
        for (j = outWidth; j; j--)
        {
            *pOut++ = *pIn++;
        }
        pIn += picWidth/2 - outWidth;
    }

    pIn = pInImage + 5*picWidth*picHeight/4 +
        pCropParams->cropTopOffset*picWidth/4 + pCropParams->cropLeftOffset/2;

    /* cr */
    for (i = outHeight; i; i--)
    {
        for (j = outWidth; j; j--)
        {
            *pOut++ = *pIn++;
        }
        pIn += picWidth/2 - outWidth;
    }

    return (0);

}

