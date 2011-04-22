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
	File:		AAC_E_SAMPLES.h

	Content:	sample code for AAC encoder

*******************************************************************************/

#include		<dlfcn.h>
#include		<stdio.h>
#include		<stdlib.h>
#include		<string.h>
#include		<time.h>
#include		"voAAC.h"
#include		"cmnMemory.h"

#define  VO_AAC_E_OUTPUT	  1
#define READ_SIZE	(1024*8)	
unsigned char outBuf[1024*8];
unsigned char inBuf[READ_SIZE];

const char* HelpString = 
"VisualOn AAC encoder Usage:\n"
"voAACEncTest -if <inputfile.pcm> -of <outputfile.aac> -sr <samplerate> -ch <channel> -br <bitrate> -adts <adts> \n"
"-if input file name \n"
"-of output file name \n"
"-sr input pcm samplerate, default 44100 \n"
"-ch input pcm channel, default 2 channel \n"
"-br encoded aac bitrate, default 64000 * (samplerate/100)*channel/441(480)\n"
"-adts add or no adts header, default add adts header\n"
"For example: \n"
"./voAACEncTest -if raw.pcm -of raw.aac -sr 44100 -ch 2 -br 128000\n";

static int parsecmdline(int argc, char **argv,char  **input_filename, char  **output_filename, AACENC_PARAM *param)
{
	// notice that:
	// bitRate/nChannels > 8000
	// bitRate/nChannels < 160000 
	// bitRate/nChannels < sampleRate*6
	param->adtsUsed = 1;
	param->bitRate = 0;
	param->nChannels = 2;
	param->sampleRate = 44100;

	if(argc < 5 || argc > 13)
	{
		return -1;
	}

	argc--;
	argv++;
	while (argc > 0)
	{
		if (!strcmp(*argv, "-if"))
		{
			argv++;
			argc--;
			*input_filename = *argv; 
		}
		else if (!strcmp(*argv, "-of"))
		{
			argv++;
			argc--;
			*output_filename = *argv;
		}
		else if (!strcmp(*argv, "-sr"))
		{
			argv++;
			argc--;
			param->sampleRate = atoi(*argv);
		}
		else if (!strcmp(*argv, "-ch"))
		{
			argv++;
			argc--;
			param->nChannels = atoi(*argv);
		}
		else if (!strcmp(*argv, "-br"))
		{
			argv++;
			argc--;
			param->bitRate = atoi(*argv);
		}
		else if(!strcmp(*argv, "-adts"))
		{
			argv++;
			argc--;
			param->adtsUsed = atoi(*argv);
		}
		else
		{
			return -1;
		}

		argv++;
		argc--;
	}

	if(param->bitRate == 0)
	{
		int scale = 441;
		if(param->sampleRate%8000 == 0)
			scale = 480;
		param->bitRate = 640*param->nChannels*param->sampleRate/scale;
	}

	return 0;
}

int ReadFile2Buf(FILE* infile,unsigned char* dest,int readSize)
{
	int readBytes = 0;
	readBytes = fread(dest, 1, readSize, infile);
	return readBytes;
}

typedef int (VO_API * VOGETAUDIODECAPI) (VO_AUDIO_CODECAPI * pDecHandle);

int main(int argc, char **argv)
{
	FILE						*infile, *outfile;
	int							t1, t2;
	VO_AUDIO_CODECAPI			AudioAPI;
	VO_MEM_OPERATOR				moper;
	VO_CODEC_INIT_USERDATA		useData;
	VO_HANDLE					hCodec;
	VO_CODECBUFFER				inData;
	VO_CODECBUFFER				outData;
	VO_AUDIO_OUTPUTINFO			outInfo;
    int							firstWrite = 1;
	int							eofFile = 0;
	int							*info=(int*)inBuf;
	int							bytesLeft, nRead;
	int							EncoderdFrame = 0;
	int							total = 0;
	int							isOutput = 1;
	int							returnCode;
	AACENC_PARAM				aacpara;
	void						*handle;
	void						*pfunc;
	VOGETAUDIODECAPI			pGetAPI;
	const char					*infileName = NULL;
    const char					*outfileName = NULL;

	returnCode = parsecmdline(argc,argv, &infileName, &outfileName, &aacpara);
	if(returnCode)
	{
		printf("%s", HelpString);
		return 0;
	}

	/* open input file */
	infile = fopen(infileName, "rb");
	if (!infile) {
		printf("Open input file fail...");
		return -1;
	}

	/* open output file */
	if(isOutput)
	{
		outfile = fopen(outfileName, "wb");
		if (!outfile) {
			printf("Open output file fail...");
			return -1;
		}
	}
	// set memory operators;
	moper.Alloc = cmnMemAlloc;
	moper.Copy = cmnMemCopy;
	moper.Free = cmnMemFree;
	moper.Set = cmnMemSet;
	moper.Check = cmnMemCheck;
	useData.memflag = VO_IMF_USERMEMOPERATOR;
	useData.memData = (VO_PTR)(&moper);
	// open encoder dll;
	handle = dlopen("libstagefright.so", RTLD_NOW);
	if(handle == 0)
	{
		printf("open dll error......");
		return -1;
	}
	// Get API;
	pfunc = dlsym(handle, "voGetAACEncAPI");
	if(pfunc == 0)
	{
		printf("open function error......");
		return -1;
	}
	pGetAPI = (VOGETAUDIODECAPI)pfunc;
	returnCode  = pGetAPI(&AudioAPI);
	if(returnCode)
		return -1;


//#######################################   Init Encoding Section   #########################################
	returnCode = AudioAPI.Init(&hCodec, VO_AUDIO_CodingAAC, &useData);
	if(returnCode < 0)
	{
		printf("#### VOI_Error2:fail to initialize the Encoderr###\n");
		return -1;
	}

	returnCode = AudioAPI.SetParam(hCodec, VO_PID_AAC_ENCPARAM, &aacpara);

	inData.Buffer = inBuf;
	bytesLeft = ReadFile2Buf(infile,inData.Buffer,READ_SIZE);

//#######################################    Encoding Section   #########################################

	do {

		inData.Length    = bytesLeft;
		outData.Buffer   = outBuf;
		outData.Length = 1024*8;

		t1 = clock();

		returnCode = AudioAPI.SetInputData(hCodec,&inData);

		do {
			outData.Buffer   = outBuf;
			outData.Length = 1024*8;

			returnCode = AudioAPI.GetOutputData(hCodec,&outData, &outInfo);

			if(returnCode == 0)
				EncoderdFrame++;
			if(returnCode == VO_ERR_LICENSE_ERROR)
				break;

#if VO_AAC_E_OUTPUT
			if (isOutput && returnCode == 0)
			{
				fwrite(outData.Buffer, 1, outData.Length, outfile);
			}
#endif
		} while(returnCode != (VO_ERR_INPUT_BUFFER_SMALL));

		if(returnCode == VO_ERR_LICENSE_ERROR)
			break;

		t2 = clock();
		total += t2 - t1;

		if (!eofFile) {
			nRead = ReadFile2Buf(infile, inBuf,READ_SIZE);
			bytesLeft = nRead;
			inData.Buffer = inBuf;
			if (feof(infile))
				eofFile = 1;
		}

	} while (!eofFile && returnCode);


//################################################  End Encoding Section  #######################################################
	returnCode = AudioAPI.Uninit(hCodec);

	fclose(infile);
	if (outfile)
    {
        fclose(outfile);
    }
	dlclose(handle);
	return 0;
}


