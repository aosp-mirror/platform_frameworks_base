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


#ifdef LINUX
#include <dlfcn.h>
#endif

#include      <stdio.h>
#include      <stdlib.h>
#include      <time.h>
#include      "voAMRWB.h"
#include      "cmnMemory.h"

#define VOAMRWB_RFC3267_HEADER_INFO "#!AMR-WB\n"

#define  INPUT_SIZE   640
#define  OUTPUT_SIZE  1024
unsigned char  InputBuf[INPUT_SIZE];
unsigned char  OutputBuf[OUTPUT_SIZE];

void usage (void) {
	printf ("AMR_WB Encoder HELP   Displays this text\n");
	printf ("\n");
	printf ("Usage:\n");
	printf ("AMRWBEnc [options] Input_file output_file \n");
	printf ("\n");
	printf ("Options +M* +F* +DTX \n");
	printf ("Support \n");
	printf ("Options +M* for seting compression bitrate mode, default is 23.85kbps\n");
	printf (" +M0 = 6.6kbps \n");
	printf (" +M1 = 8.85kbps \n");
	printf (" +M2 = 12.65kbps \n");
	printf (" +M3 = 14.25kbps \n");
	printf (" +M4 = 15.58kbps \n");
	printf (" +M5 = 18.25kbps \n");
	printf (" +M6 = 19.85kbps \n");
	printf (" +M7 = 23.05kbps \n");
	printf (" +M8 = 23.85kbps \n");
	printf ("\n");
	printf ("Options +F* for setting output frame Type, default is RFC3267 \n");
	printf ("+F0 for AMR_WB Defualt bit extern short data frame type \n");
	printf ("+F1 for AMR_WB_ITU bit extern short data frame type \n");
	printf ("+F2 for RFC3267\n ");
	printf ("\n");
	printf ("Options +DTX enable DTX mode, default is disable.\n");
	printf ("File names, input raw PCM data, and output is AMR_WB bit-stream file.\n");
	printf ("\n");
}

int  GetNextBuf(FILE* inFile,unsigned char* dst,int size)
{
	int size2 = (int)fread(dst, sizeof(signed char), size,inFile);
	return size2;
}

typedef int (VO_API * VOGETAUDIOENCAPI) (VO_AUDIO_CODECAPI * pEncHandle);

int encode(
		   int mode,
		   short   allow_dtx,
		   VOAMRWBFRAMETYPE frameType,
		   const char* srcfile,
		   const char* dstfile
		   )
{
	int			ret = 0;
	int         returnCode;
	FILE		*fsrc = NULL;
	FILE		*fdst = NULL;
	int         framenum = 0;
	int         eofFile = 0;
	int         size1 = 0;
	int         Relens;

	VO_AUDIO_CODECAPI       AudioAPI;
	VO_MEM_OPERATOR         moper;
	VO_CODEC_INIT_USERDATA  useData;
	VO_HANDLE               hCodec;
	VO_CODECBUFFER          inData;
	VO_CODECBUFFER          outData;
	VO_AUDIO_OUTPUTINFO     outFormat;

	unsigned char *inBuf = InputBuf;
	unsigned char *outBuf = OutputBuf;


#ifdef LINUX
	void  *handle = NULL;
	void  *pfunc;
	VOGETAUDIOENCAPI pGetAPI;
#endif

	clock_t   start, finish;
	double    duration = 0.0;

	if ((fsrc = fopen (srcfile, "rb")) == NULL)
	{
		ret = -1;
		goto safe_exit;
	}

	if ((fdst = fopen (dstfile, "wb")) == NULL)
	{
		ret = -1;
		goto safe_exit;
	}

	moper.Alloc = cmnMemAlloc;
	moper.Copy = cmnMemCopy;
	moper.Free = cmnMemFree;
	moper.Set = cmnMemSet;
	moper.Check = cmnMemCheck;

	useData.memflag = VO_IMF_USERMEMOPERATOR;
	useData.memData = (VO_PTR)(&moper);

#ifdef LINUX
	handle = dlopen("libstagefright.so", RTLD_NOW);
	if(handle == 0)
	{
		printf("open dll error......");
		return -1;
	}

	pfunc = dlsym(handle, "voGetAMRWBEncAPI");
	if(pfunc == 0)
	{
		printf("open function error......");
		return -1;
	}

	pGetAPI = (VOGETAUDIOENCAPI)pfunc;

	returnCode  = pGetAPI(&AudioAPI);
	if(returnCode)
	{
		printf("get APIs error......");
		return -1;
	}
#else
	ret = voGetAMRWBEncAPI(&AudioAPI);
	if(ret)
	{
		ret = -1;
		printf("get APIs error......");
		goto safe_exit;
	}
#endif

	//#######################################   Init Encoding Section   #########################################
	ret = AudioAPI.Init(&hCodec, VO_AUDIO_CodingAMRWB, &useData);

	if(ret)
	{
		ret = -1;
		printf("APIs init error......");
		goto safe_exit;
	}

	Relens = GetNextBuf(fsrc,InputBuf,INPUT_SIZE);
	if(Relens!=INPUT_SIZE && !feof(fsrc))
	{
		ret = -1; //Invalid magic number
		printf("get next buffer error......");
		goto safe_exit;
	}

	//###################################### set encode Mode ##################################################
	ret = AudioAPI.SetParam(hCodec, VO_PID_AMRWB_FRAMETYPE, &frameType);
	ret = AudioAPI.SetParam(hCodec, VO_PID_AMRWB_MODE, &mode);
	ret = AudioAPI.SetParam(hCodec, VO_PID_AMRWB_DTX, &allow_dtx);

	if(frameType == VOAMRWB_RFC3267)
	{
		/* write RFC3267 Header info to indicate single channel AMR file storage format */
		size1 = (int)strlen(VOAMRWB_RFC3267_HEADER_INFO);
		memcpy(outBuf, VOAMRWB_RFC3267_HEADER_INFO, size1);
		outBuf += size1;
	}

	//#######################################   Encoding Section   #########################################
	printf(" \n ---------------- Running -------------------------\n ");

	do{
		inData.Buffer = (unsigned char *)inBuf;
		inData.Length = Relens;
		outData.Buffer = outBuf;

		start = clock();

		/* decode one amr block */
		returnCode = AudioAPI.SetInputData(hCodec,&inData);

		do {
			returnCode = AudioAPI.GetOutputData(hCodec,&outData, &outFormat);
			if(returnCode == 0)
			{
				framenum++;
				printf(" Frames processed: %hd\r", framenum);
				if(framenum == 1)
				{
					fwrite(OutputBuf, 1, outData.Length + size1, fdst);
					fflush(fdst);
				}
				else
				{
					fwrite(outData.Buffer, 1, outData.Length, fdst);
					fflush(fdst);
				}
			}
			else if(returnCode == VO_ERR_LICENSE_ERROR)
			{
		        printf("Encoder time reach upper limit......");
		        goto safe_exit;
			}
		} while(returnCode != VO_ERR_INPUT_BUFFER_SMALL);

		finish = clock();
		duration += finish - start;

		if (!eofFile) {
			Relens = GetNextBuf(fsrc, InputBuf, INPUT_SIZE);
			inBuf = InputBuf;
			if (feof(fsrc) && Relens == 0)
				eofFile = 1;
		}
	} while (!eofFile && returnCode);
	//#######################################   End Encoding Section   #########################################

safe_exit:
	returnCode = AudioAPI.Uninit(hCodec);

	printf( "\n%2.5f seconds\n", (double)duration/CLOCKS_PER_SEC);

	if (fsrc)
		fclose(fsrc);
	if (fdst)
		fclose(fdst);

#ifdef LINUX
	dlclose(handle);
#endif

	return ret;
}

int main(int argc, char **argv)  // for gcc compiler;
{
	int     mode, r;
	int     arg, filename=0;
	char    *inFileName = NULL;
	char    *outFileName = NULL;
	short   allow_dtx;
	VOAMRWBFRAMETYPE frameType;

	printf("\n");
	printf("************************Adaptive Multi-Rate Wide Band Encoder (AMR-WB)*******************************\n");
	printf("***********************************DEFINITIONS:*******************************************************\n");
	printf("AMR-WB encoder scheme is based on the principle of Algebraic Code Excited Linear Prediction algorithm\n");
	printf("The AMR-WB encoder compression MONO liner PCM speech input data at 16kHz sampling rate\n");
	printf("to one of nine data rate modes-6.60, 8.85, 12.65, 14.25, 15.85, 18.25, 19.25, 23.05 and 23.85kbps.\n");
	printf("The encoder supports output format AMRWB ITU, AMRWB RFC3267.\n");
	printf("\n");

	/*Encoder Default setting */
	mode = VOAMRWB_MD2385;
	allow_dtx = 0;
	frameType = VOAMRWB_RFC3267;

	if(argc < 3){
		usage();
		return 0;
	}else{
		for (arg = 1; arg < argc; arg++) {
			if (argv [arg] [0] == '+') {
				if(argv[arg][1] == 'M')
				{
					switch(argv[arg][2])
					{
					case '0': mode = VOAMRWB_MD66;
						break;
					case '1': mode = VOAMRWB_MD885;
						break;
					case '2': mode = VOAMRWB_MD1265;
						break;
					case '3': mode = VOAMRWB_MD1425;
						break;
					case '4': mode = VOAMRWB_MD1585;
						break;
					case '5': mode = VOAMRWB_MD1825;
						break;
					case '6': mode = VOAMRWB_MD1985;
						break;
					case '7': mode = VOAMRWB_MD2305;
						break;
					case '8': mode = VOAMRWB_MD2385;
						break;
					default:
						usage();
						printf ("Invalid parameter '%s'.\n", argv [arg]);
						break;
					}
				}else if(argv[arg][1] == 'F')
				{
					switch(argv[arg][2])
					{
					case '0': frameType = VOAMRWB_DEFAULT;
						break;
					case '1': frameType = VOAMRWB_ITU;
						break;
					case '2': frameType = VOAMRWB_RFC3267 ;
						break;
					default:
						usage();
						printf ("Invalid parameter '%s'.\n", argv [arg]);
						break;


					}
				}else if(strcmp (argv[arg], "+DTX") == 0)
				{
					allow_dtx = 1;
				}

			} else {
				switch (filename) {
						case 0:
							inFileName  = argv[arg];
							break;
						case 1:
							outFileName = argv[arg];
							break;
						default:
							usage ();
							fprintf (stderr, "Invalid parameter '%s'.\n", argv [arg]);
							return 0;
				}
				filename++;
			}
		}
	}

	r = encode(mode, allow_dtx, frameType, inFileName, outFileName);
	if(r)
	{
		fprintf(stderr, "error: %d\n", r);
	}
	return r;
}

