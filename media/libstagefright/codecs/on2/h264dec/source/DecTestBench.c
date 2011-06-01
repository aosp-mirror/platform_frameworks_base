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

#include "H264SwDecApi.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/*------------------------------------------------------------------------------
    Module defines
------------------------------------------------------------------------------*/

/* CHECK_MEMORY_USAGE prints and sums the memory allocated in calls to
 * H264SwDecMalloc() */
/* #define CHECK_MEMORY_USAGE */

/* _NO_OUT disables output file writing */
/* #define _NO_OUT */

/* Debug prints */
#define DEBUG(argv) printf argv

/* CVS tag name for identification */
const char tagName[256] = "$Name: FIRST_ANDROID_COPYRIGHT $";

void WriteOutput(char *filename, u8 *data, u32 picSize);
u32 NextPacket(u8 **pStrm);
u32 CropPicture(u8 *pOutImage, u8 *pInImage,
    u32 picWidth, u32 picHeight, CropParams *pCropParams);

/* Global variables for stream handling */
u8 *streamStop = NULL;
u32 packetize = 0;
u32 nalUnitStream = 0;
FILE *foutput = NULL;

#ifdef SOC_DESIGNER

// Initialisation function defined in InitCache.s
extern void cache_init(void);

/*------------------------------------------------------------------------------

    Function name:  $Sub$$main

    Purpose:
        This function is called at the end of the C library initialisation and
        before main. Its purpose is to do any further initialisation before the
        application start.

------------------------------------------------------------------------------*/
int $Sub$$main(char argc, char * argv[])
{
  cache_init();                    // does some extra setup work setting up caches
  return $Super$$main(argc, argv); // calls the original function
}
#endif

/*------------------------------------------------------------------------------

    Function name:  main

    Purpose:
        main function of decoder testbench. Provides command line interface
        with file I/O for H.264 decoder. Prints out the usage information
        when executed without arguments.

------------------------------------------------------------------------------*/

int main(int argc, char **argv)
{

    u32 i, tmp;
    u32 maxNumPics = 0;
    u8 *byteStrmStart;
    u8 *imageData;
    u8 *tmpImage = NULL;
    u32 strmLen;
    u32 picSize;
    H264SwDecInst decInst;
    H264SwDecRet ret;
    H264SwDecInput decInput;
    H264SwDecOutput decOutput;
    H264SwDecPicture decPicture;
    H264SwDecInfo decInfo;
    H264SwDecApiVersion decVer;
    u32 picDecodeNumber;
    u32 picDisplayNumber;
    u32 numErrors = 0;
    u32 cropDisplay = 0;
    u32 disableOutputReordering = 0;

    FILE *finput;

    char outFileName[256] = "";

    /* Print API version number */
    decVer = H264SwDecGetAPIVersion();
    DEBUG(("H.264 Decoder API v%d.%d\n", decVer.major, decVer.minor));

    /* Print tag name if '-T' argument present */
    if ( argc > 1 && strcmp(argv[1], "-T") == 0 )
    {
        DEBUG(("%s\n", tagName));
        return 0;
    }

    /* Check that enough command line arguments given, if not -> print usage
     * information out */
    if (argc < 2)
    {
        DEBUG((
            "Usage: %s [-Nn] [-Ooutfile] [-P] [-U] [-C] [-R] [-T] file.h264\n",
            argv[0]));
        DEBUG(("\t-Nn forces decoding to stop after n pictures\n"));
#if defined(_NO_OUT)
        DEBUG(("\t-Ooutfile output writing disabled at compile time\n"));
#else
        DEBUG(("\t-Ooutfile write output to \"outfile\" (default out_wxxxhyyy.yuv)\n"));
        DEBUG(("\t-Onone does not write output\n"));
#endif
        DEBUG(("\t-P packet-by-packet mode\n"));
        DEBUG(("\t-U NAL unit stream mode\n"));
        DEBUG(("\t-C display cropped image (default decoded image)\n"));
        DEBUG(("\t-R disable DPB output reordering\n"));
        DEBUG(("\t-T to print tag name and exit\n"));
        return 0;
    }

    /* read command line arguments */
    for (i = 1; i < (u32)(argc-1); i++)
    {
        if ( strncmp(argv[i], "-N", 2) == 0 )
        {
            maxNumPics = (u32)atoi(argv[i]+2);
        }
        else if ( strncmp(argv[i], "-O", 2) == 0 )
        {
            strcpy(outFileName, argv[i]+2);
        }
        else if ( strcmp(argv[i], "-P") == 0 )
        {
            packetize = 1;
        }
        else if ( strcmp(argv[i], "-U") == 0 )
        {
            nalUnitStream = 1;
        }
        else if ( strcmp(argv[i], "-C") == 0 )
        {
            cropDisplay = 1;
        }
        else if ( strcmp(argv[i], "-R") == 0 )
        {
            disableOutputReordering = 1;
        }
    }

    /* open input file for reading, file name given by user. If file open
     * fails -> exit */
    finput = fopen(argv[argc-1],"rb");
    if (finput == NULL)
    {
        DEBUG(("UNABLE TO OPEN INPUT FILE\n"));
        return -1;
    }

    /* check size of the input file -> length of the stream in bytes */
    fseek(finput,0L,SEEK_END);
    strmLen = (u32)ftell(finput);
    rewind(finput);

    /* allocate memory for stream buffer. if unsuccessful -> exit */
    byteStrmStart = (u8 *)malloc(sizeof(u8)*strmLen);
    if (byteStrmStart == NULL)
    {
        DEBUG(("UNABLE TO ALLOCATE MEMORY\n"));
        return -1;
    }

    /* read input stream from file to buffer and close input file */
    fread(byteStrmStart, sizeof(u8), strmLen, finput);
    fclose(finput);

    /* initialize decoder. If unsuccessful -> exit */
    ret = H264SwDecInit(&decInst, disableOutputReordering);
    if (ret != H264SWDEC_OK)
    {
        DEBUG(("DECODER INITIALIZATION FAILED\n"));
        free(byteStrmStart);
        return -1;
    }

    /* initialize H264SwDecDecode() input structure */
    streamStop = byteStrmStart + strmLen;
    decInput.pStream = byteStrmStart;
    decInput.dataLen = strmLen;
    decInput.intraConcealmentMethod = 0;

    /* get pointer to next packet and the size of packet
     * (for packetize or nalUnitStream modes) */
    if ( (tmp = NextPacket(&decInput.pStream)) != 0 )
        decInput.dataLen = tmp;

    picDecodeNumber = picDisplayNumber = 1;
    /* main decoding loop */
    do
    {
        /* Picture ID is the picture number in decoding order */
        decInput.picId = picDecodeNumber;

        /* call API function to perform decoding */
        ret = H264SwDecDecode(decInst, &decInput, &decOutput);

        switch(ret)
        {

            case H264SWDEC_HDRS_RDY_BUFF_NOT_EMPTY:
                /* Stream headers were successfully decoded
                 * -> stream information is available for query now */

                ret = H264SwDecGetInfo(decInst, &decInfo);
                if (ret != H264SWDEC_OK)
                    return -1;

                DEBUG(("Profile %d\n", decInfo.profile));

                DEBUG(("Width %d Height %d\n",
                    decInfo.picWidth, decInfo.picHeight));

                if (cropDisplay && decInfo.croppingFlag)
                {
                    DEBUG(("Cropping params: (%d, %d) %dx%d\n",
                        decInfo.cropParams.cropLeftOffset,
                        decInfo.cropParams.cropTopOffset,
                        decInfo.cropParams.cropOutWidth,
                        decInfo.cropParams.cropOutHeight));

                    /* Cropped frame size in planar YUV 4:2:0 */
                    picSize = decInfo.cropParams.cropOutWidth *
                              decInfo.cropParams.cropOutHeight;
                    picSize = (3 * picSize)/2;
                    tmpImage = malloc(picSize);
                    if (tmpImage == NULL)
                        return -1;
                }
                else
                {
                    /* Decoder output frame size in planar YUV 4:2:0 */
                    picSize = decInfo.picWidth * decInfo.picHeight;
                    picSize = (3 * picSize)/2;
                }

                DEBUG(("videoRange %d, matrixCoefficients %d\n",
                    decInfo.videoRange, decInfo.matrixCoefficients));

                /* update H264SwDecDecode() input structure, number of bytes
                 * "consumed" is computed as difference between the new stream
                 * pointer and old stream pointer */
                decInput.dataLen -=
                    (u32)(decOutput.pStrmCurrPos - decInput.pStream);
                decInput.pStream = decOutput.pStrmCurrPos;

                /* If -O option not used, generate default file name */
                if (outFileName[0] == 0)
                    sprintf(outFileName, "out_w%dh%d.yuv",
                            decInfo.picWidth, decInfo.picHeight);
                break;

            case H264SWDEC_PIC_RDY_BUFF_NOT_EMPTY:
                /* Picture is ready and more data remains in input buffer
                 * -> update H264SwDecDecode() input structure, number of bytes
                 * "consumed" is computed as difference between the new stream
                 * pointer and old stream pointer */
                decInput.dataLen -=
                    (u32)(decOutput.pStrmCurrPos - decInput.pStream);
                decInput.pStream = decOutput.pStrmCurrPos;
                /* fall through */

            case H264SWDEC_PIC_RDY:

                /*lint -esym(644,tmpImage,picSize) variable initialized at
                 * H264SWDEC_HDRS_RDY_BUFF_NOT_EMPTY case */

                if (ret == H264SWDEC_PIC_RDY)
                    decInput.dataLen = NextPacket(&decInput.pStream);

                /* If enough pictures decoded -> force decoding to end
                 * by setting that no more stream is available */
                if (maxNumPics && picDecodeNumber == maxNumPics)
                    decInput.dataLen = 0;

                /* Increment decoding number for every decoded picture */
                picDecodeNumber++;

                /* use function H264SwDecNextPicture() to obtain next picture
                 * in display order. Function is called until no more images
                 * are ready for display */
                while ( H264SwDecNextPicture(decInst, &decPicture, 0) ==
                        H264SWDEC_PIC_RDY )
                {
                    DEBUG(("PIC %d, type %s", picDisplayNumber,
                        decPicture.isIdrPicture ? "IDR" : "NON-IDR"));
                    if (picDisplayNumber != decPicture.picId)
                        DEBUG((", decoded pic %d", decPicture.picId));
                    if (decPicture.nbrOfErrMBs)
                    {
                        DEBUG((", concealed %d\n", decPicture.nbrOfErrMBs));
                    }
                    else
                        DEBUG(("\n"));
                    fflush(stdout);

                    numErrors += decPicture.nbrOfErrMBs;

                    /* Increment display number for every displayed picture */
                    picDisplayNumber++;

                    /*lint -esym(644,decInfo) always initialized if pictures
                     * available for display */

                    /* Write output picture to file */
                    imageData = (u8*)decPicture.pOutputPicture;
                    if (cropDisplay && decInfo.croppingFlag)
                    {
                        tmp = CropPicture(tmpImage, imageData,
                            decInfo.picWidth, decInfo.picHeight,
                            &decInfo.cropParams);
                        if (tmp)
                            return -1;
                        WriteOutput(outFileName, tmpImage, picSize);
                    }
                    else
                    {
                        WriteOutput(outFileName, imageData, picSize);
                    }
                }

                break;

            case H264SWDEC_STRM_PROCESSED:
            case H264SWDEC_STRM_ERR:
                /* Input stream was decoded but no picture is ready
                 * -> Get more data */
                decInput.dataLen = NextPacket(&decInput.pStream);
                break;

            default:
                DEBUG(("FATAL ERROR\n"));
                return -1;

        }
    /* keep decoding until all data from input stream buffer consumed */
    } while (decInput.dataLen > 0);

    /* if output in display order is preferred, the decoder shall be forced
     * to output pictures remaining in decoded picture buffer. Use function
     * H264SwDecNextPicture() to obtain next picture in display order. Function
     * is called until no more images are ready for display. Second parameter
     * for the function is set to '1' to indicate that this is end of the
     * stream and all pictures shall be output */
    while (H264SwDecNextPicture(decInst, &decPicture, 1) == H264SWDEC_PIC_RDY)
    {
        DEBUG(("PIC %d, type %s", picDisplayNumber,
            decPicture.isIdrPicture ? "IDR" : "NON-IDR"));
        if (picDisplayNumber != decPicture.picId)
            DEBUG((", decoded pic %d", decPicture.picId));
        if (decPicture.nbrOfErrMBs)
        {
            DEBUG((", concealed %d\n", decPicture.nbrOfErrMBs));
        }
        else
            DEBUG(("\n"));
        fflush(stdout);

        numErrors += decPicture.nbrOfErrMBs;

        /* Increment display number for every displayed picture */
        picDisplayNumber++;

        /* Write output picture to file */
        imageData = (u8*)decPicture.pOutputPicture;
        if (cropDisplay && decInfo.croppingFlag)
        {
            tmp = CropPicture(tmpImage, imageData,
                decInfo.picWidth, decInfo.picHeight,
                &decInfo.cropParams);
            if (tmp)
                return -1;
            WriteOutput(outFileName, tmpImage, picSize);
        }
        else
        {
            WriteOutput(outFileName, imageData, picSize);
        }
    }

    /* release decoder instance */
    H264SwDecRelease(decInst);

    if (foutput)
        fclose(foutput);

    /* free allocated buffers */
    free(byteStrmStart);
    free(tmpImage);

    DEBUG(("Output file: %s\n", outFileName));

    DEBUG(("DECODING DONE\n"));
    if (numErrors || picDecodeNumber == 1)
    {
        DEBUG(("ERRORS FOUND\n"));
        return 1;
    }

    return 0;
}

/*------------------------------------------------------------------------------

    Function name:  WriteOutput

    Purpose:
        Write picture pointed by data to file. Size of the
        picture in pixels is indicated by picSize.

------------------------------------------------------------------------------*/
void WriteOutput(char *filename, u8 *data, u32 picSize)
{

    /* foutput is global file pointer */
    if (foutput == NULL)
    {
        /* open output file for writing, can be disabled with define.
         * If file open fails -> exit */
        if (strcmp(filename, "none") != 0)
        {
#if !defined(_NO_OUT)
            foutput = fopen(filename, "wb");
            if (foutput == NULL)
            {
                DEBUG(("UNABLE TO OPEN OUTPUT FILE\n"));
                exit(100);
            }
#endif
        }
    }

    if (foutput && data)
        fwrite(data, 1, picSize, foutput);
}

/*------------------------------------------------------------------------------

    Function name: NextPacket

    Purpose:
        Get the pointer to start of next packet in input stream. Uses
        global variables 'packetize' and 'nalUnitStream' to determine the
        decoder input stream mode and 'streamStop' to determine the end
        of stream. There are three possible stream modes:
            default - the whole stream at once
            packetize - a single NAL-unit with start code prefix
            nalUnitStream - a single NAL-unit without start code prefix

        pStrm stores pointer to the start of previous decoder input and is
        replaced with pointer to the start of the next decoder input.

        Returns the packet size in bytes

------------------------------------------------------------------------------*/
u32 NextPacket(u8 **pStrm)
{

    u32 index;
    u32 maxIndex;
    u32 zeroCount;
    u8 *stream;
    u8 byte;
    static u32 prevIndex=0;

    /* For default stream mode all the stream is in first packet */
    if (!packetize && !nalUnitStream)
        return 0;

    index = 0;
    stream = *pStrm + prevIndex;
    maxIndex = (u32)(streamStop - stream);

    if (maxIndex == 0)
        return(0);

    /* leading zeros of first NAL unit */
    do
    {
        byte = stream[index++];
    } while (byte != 1 && index < maxIndex);

    /* invalid start code prefix */
    if (index == maxIndex || index < 3)
    {
        DEBUG(("INVALID BYTE STREAM\n"));
        exit(100);
    }

    /* nalUnitStream is without start code prefix */
    if (nalUnitStream)
    {
        stream += index;
        maxIndex -= index;
        index = 0;
    }

    zeroCount = 0;

    /* Search stream for next start code prefix */
    /*lint -e(716) while(1) used consciously */
    while (1)
    {
        byte = stream[index++];
        if (!byte)
            zeroCount++;

        if ( (byte == 0x01) && (zeroCount >= 2) )
        {
            /* Start code prefix has two zeros
             * Third zero is assumed to be leading zero of next packet
             * Fourth and more zeros are assumed to be trailing zeros of this
             * packet */
            if (zeroCount > 3)
            {
                index -= 4;
                zeroCount -= 3;
            }
            else
            {
                index -= zeroCount+1;
                zeroCount = 0;
            }
            break;
        }
        else if (byte)
            zeroCount = 0;

        if (index == maxIndex)
        {
            break;
        }

    }

    /* Store pointer to the beginning of the packet */
    *pStrm = stream;
    prevIndex = index;

    /* nalUnitStream is without trailing zeros */
    if (nalUnitStream)
        index -= zeroCount;

    return(index);

}

/*------------------------------------------------------------------------------

    Function name: CropPicture

    Purpose:
        Perform cropping for picture. Input picture pInImage with dimensions
        picWidth x picHeight is cropped with pCropParams and the resulting
        picture is stored in pOutImage.

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
        /* just to prevent lint warning, returning non-zero will result in
         * return without freeing the memory */
        free(pOutImage);
        return(1);
    }

    if ( ((pCropParams->cropLeftOffset + pCropParams->cropOutWidth) >
           picWidth ) ||
         ((pCropParams->cropTopOffset + pCropParams->cropOutHeight) >
           picHeight ) )
    {
        /* just to prevent lint warning, returning non-zero will result in
         * return without freeing the memory */
        free(pOutImage);
        return(1);
    }

    outWidth = pCropParams->cropOutWidth;
    outHeight = pCropParams->cropOutHeight;

    /* Calculate starting pointer for luma */
    pIn = pInImage + pCropParams->cropTopOffset*picWidth +
        pCropParams->cropLeftOffset;
    pOut = pOutImage;

    /* Copy luma pixel values */
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

    /* Calculate starting pointer for cb */
    pIn = pInImage + picWidth*picHeight +
        pCropParams->cropTopOffset*picWidth/4 + pCropParams->cropLeftOffset/2;

    /* Copy cb pixel values */
    for (i = outHeight; i; i--)
    {
        for (j = outWidth; j; j--)
        {
            *pOut++ = *pIn++;
        }
        pIn += picWidth/2 - outWidth;
    }

    /* Calculate starting pointer for cr */
    pIn = pInImage + 5*picWidth*picHeight/4 +
        pCropParams->cropTopOffset*picWidth/4 + pCropParams->cropLeftOffset/2;

    /* Copy cr pixel values */
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

/*------------------------------------------------------------------------------

    Function name:  H264SwDecTrace

    Purpose:
        Example implementation of H264SwDecTrace function. Prototype of this
        function is given in H264SwDecApi.h. This implementation appends
        trace messages to file named 'dec_api.trc'.

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

    Function name:  H264SwDecMalloc

    Purpose:
        Example implementation of H264SwDecMalloc function. Prototype of this
        function is given in H264SwDecApi.h. This implementation uses
        library function malloc for allocation of memory.

------------------------------------------------------------------------------*/
void* H264SwDecMalloc(u32 size)
{

#if defined(CHECK_MEMORY_USAGE)
    /* Note that if the decoder has to free and reallocate some of the buffers
     * the total value will be invalid */
    static u32 numBytes = 0;
    numBytes += size;
    DEBUG(("Allocated %d bytes, total %d\n", size, numBytes));
#endif

    return malloc(size);
}

/*------------------------------------------------------------------------------

    Function name:  H264SwDecFree

    Purpose:
        Example implementation of H264SwDecFree function. Prototype of this
        function is given in H264SwDecApi.h. This implementation uses
        library function free for freeing of memory.

------------------------------------------------------------------------------*/
void H264SwDecFree(void *ptr)
{
    free(ptr);
}

/*------------------------------------------------------------------------------

    Function name:  H264SwDecMemcpy

    Purpose:
        Example implementation of H264SwDecMemcpy function. Prototype of this
        function is given in H264SwDecApi.h. This implementation uses
        library function memcpy to copy src to dest.

------------------------------------------------------------------------------*/
void H264SwDecMemcpy(void *dest, void *src, u32 count)
{
    memcpy(dest, src, count);
}

/*------------------------------------------------------------------------------

    Function name:  H264SwDecMemset

    Purpose:
        Example implementation of H264SwDecMemset function. Prototype of this
        function is given in H264SwDecApi.h. This implementation uses
        library function memset to set content of memory area pointed by ptr.

------------------------------------------------------------------------------*/
void H264SwDecMemset(void *ptr, i32 value, u32 count)
{
    memset(ptr, value, count);
}

