/*
 * Copyright (C) 2010-2010 NXP Software
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

#define LOG_TAG "Bundle"
#define ARRAY_SIZE(array) (sizeof array / sizeof array[0])
#define LOG_NDEBUG 0

#include <cutils/log.h>
#include <assert.h>
#include <stdlib.h>
#include <string.h>
#include <new>
#include <EffectBundle.h>

#define LVM_MAX_SESSIONS        32
#define MAX_NUM_BANDS           5
#define MAX_CALL_SIZE           256

// effect_interface_t interface implementation for bass boost
extern "C" const struct effect_interface_s gLvmEffectInterface;

#define LVM_ERROR_CHECK(LvmStatus, callingFunc, calledFunc){\
        if (LvmStatus == LVM_NULLADDRESS){\
            LOGV("\tLVM_ERROR : Parameter error - "\
                    "null pointer returned by %s in %s\n\n\n\n", callingFunc, calledFunc);\
        }\
        if (LvmStatus == LVM_ALIGNMENTERROR){\
            LOGV("\tLVM_ERROR : Parameter error - "\
                    "bad alignment returned by %s in %s\n\n\n\n", callingFunc, calledFunc);\
        }\
        if (LvmStatus == LVM_INVALIDNUMSAMPLES){\
            LOGV("\tLVM_ERROR : Parameter error - "\
                    "bad number of samples returned by %s in %s\n\n\n\n", callingFunc, calledFunc);\
        }\
        if (LvmStatus == LVM_OUTOFRANGE){\
            LOGV("\tLVM_ERROR : Parameter error - "\
                    "out of range returned by %s in %s\n", callingFunc, calledFunc);\
        }\
    }

// Namespaces
namespace android {
namespace {

/* local functions */
#define CHECK_ARG(cond) {                     \
    if (!(cond)) {                            \
        LOGV("\tLVM_ERROR : Invalid argument: "#cond);      \
        return -EINVAL;                       \
    }                                         \
}

// Flag to allow a one time init of global memory, only happens on first call ever
int LvmInitFlag = LVM_FALSE;
SessionContext GlobalSessionMemory[32];

// NXP SW BassBoost UUID
const effect_descriptor_t gBassBoostDescriptor = {
        {0x0634f220, 0xddd4, 0x11db, 0xa0fc, { 0x00, 0x02, 0xa5, 0xd5, 0xc5, 0x1b }},
        {0x8631f300, 0x72e2, 0x11df, 0xb57e, {0x00, 0x02, 0xa5, 0xd5, 0xc5, 0x1b}}, // uuid
        EFFECT_API_VERSION,
        (EFFECT_FLAG_TYPE_INSERT | EFFECT_FLAG_INSERT_LAST | EFFECT_FLAG_DEVICE_IND
        | EFFECT_FLAG_VOLUME_CTRL),
        0, // TODO
        1,
        "Dynamic Bass Boost",
        "NXP Software Ltd.",
};

// NXP SW Virtualizer UUID
const effect_descriptor_t gVirtualizerDescriptor = {
        {0x37cc2c00, 0xdddd, 0x11db, 0x8577, {0x00, 0x02, 0xa5, 0xd5, 0xc5, 0x1b}},
        {0x1d4033c0, 0x8557, 0x11df, 0x9f2d, {0x00, 0x02, 0xa5, 0xd5, 0xc5, 0x1b}},
        EFFECT_API_VERSION,
        (EFFECT_FLAG_TYPE_INSERT | EFFECT_FLAG_INSERT_LAST | EFFECT_FLAG_DEVICE_IND
        | EFFECT_FLAG_VOLUME_CTRL),
        0, // TODO
        1,
        "Virtualizer",
        "NXP Software Ltd.",
};

// NXP SW Equalizer UUID
const effect_descriptor_t gEqualizerDescriptor = {
        {0x0bed4300, 0xddd6, 0x11db, 0x8f34, {0x00, 0x02, 0xa5, 0xd5, 0xc5, 0x1b}}, // type
        {0xce772f20, 0x847d, 0x11df, 0xbb17, {0x00, 0x02, 0xa5, 0xd5, 0xc5, 0x1b}}, // uuid Eq NXP
        EFFECT_API_VERSION,
        (EFFECT_FLAG_TYPE_INSERT | EFFECT_FLAG_INSERT_LAST | EFFECT_FLAG_VOLUME_CTRL),
        0, // TODO
        1,
        "Equalizer",
        "NXP Software Ltd.",
};

// NXP SW Volume UUID
const effect_descriptor_t gVolumeDescriptor = {
        {0x09e8ede0, 0xddde, 0x11db, 0xb4f6, { 0x00, 0x02, 0xa5, 0xd5, 0xc5, 0x1b }},
        {0x119341a0, 0x8469, 0x11df, 0x81f9, { 0x00, 0x02, 0xa5, 0xd5, 0xc5, 0x1b }}, //uuid VOL NXP
        EFFECT_API_VERSION,
        (EFFECT_FLAG_TYPE_INSERT | EFFECT_FLAG_INSERT_LAST | EFFECT_FLAG_VOLUME_CTRL),
        0, // TODO
        1,
        "Volume",
        "NXP Software Ltd.",
};

//--- local function prototypes
void LvmGlobalBundle_init      (void);
int  LvmBundle_init            (EffectContext *pContext);
int  LvmEffect_enable          (EffectContext *pContext);
int  LvmEffect_disable         (EffectContext *pContext);
void LvmEffect_free            (EffectContext *pContext);
int  Effect_configure          (EffectContext *pContext, effect_config_t *pConfig);
int  BassBoost_setParameter    (EffectContext *pContext, int32_t *pParam, void *pValue);
int  BassBoost_getParameter    (EffectContext *pContext,
                               int32_t        *pParam,
                               size_t         *pValueSize,
                               void           *pValue);
int  Virtualizer_setParameter  (EffectContext *pContext, int32_t *pParam, void *pValue);
int  Virtualizer_getParameter  (EffectContext *pContext,
                               int32_t        *pParam,
                               size_t         *pValueSize,
                               void           *pValue);
int  Equalizer_setParameter    (EffectContext *pContext, int32_t *pParam, void *pValue);
int  Equalizer_getParameter    (EffectContext *pContext,
                                int32_t       *pParam,
                                size_t        *pValueSize,
                                void          *pValue);
int  Volume_setParameter       (EffectContext *pContext, int32_t *pParam, void *pValue);
int  Volume_getParameter       (EffectContext *pContext,
                                int32_t       *pParam,
                                size_t        *pValueSize,
                                void          *pValue);

/* Effect Library Interface Implementation */
extern "C" int EffectQueryNumberEffects(uint32_t *pNumEffects){
    LOGV("\n\tEffectQueryNumberEffects start");
    *pNumEffects = 4;
    LOGV("\tEffectQueryNumberEffects creating %d effects", *pNumEffects);
    LOGV("\tEffectQueryNumberEffects end\n");
    return 0;
}     /* end EffectQueryNumberEffects */

extern "C" int EffectQueryEffect(uint32_t index, effect_descriptor_t *pDescriptor){
    LOGV("\n\tEffectQueryEffect start");
    LOGV("\tEffectQueryEffect processing index %d", index);

    if (pDescriptor == NULL){
        LOGV("\tLVM_ERROR : EffectQueryEffect was passed NULL pointer");
        return -EINVAL;
    }
    if (index > 3){
        LOGV("\tLVM_ERROR : EffectQueryEffect index out of range %d", index);
        return -ENOENT;
    }
    if(index == LVM_BASS_BOOST){
        LOGV("\tEffectQueryEffect processing LVM_BASS_BOOST");
        memcpy(pDescriptor, &gBassBoostDescriptor,   sizeof(effect_descriptor_t));
    }else if(index == LVM_VIRTUALIZER){
        LOGV("\tEffectQueryEffect processing LVM_VIRTUALIZER");
        memcpy(pDescriptor, &gVirtualizerDescriptor, sizeof(effect_descriptor_t));
    } else if(index == LVM_EQUALIZER){
        LOGV("\tEffectQueryEffect processing LVM_EQUALIZER");
        memcpy(pDescriptor, &gEqualizerDescriptor,   sizeof(effect_descriptor_t));
    } else if(index == LVM_VOLUME){
        LOGV("\tEffectQueryEffect processing LVM_VOLUME");
        memcpy(pDescriptor, &gVolumeDescriptor, sizeof(effect_descriptor_t));
    }
    LOGV("\tEffectQueryEffect end\n");
    return 0;
}     /* end EffectQueryEffect */

extern "C" int EffectCreate(effect_uuid_t       *uuid,
                            int32_t             sessionId,
                            int32_t             ioId,
                            effect_interface_t  *pInterface){
    int ret;
    int i;
    EffectContext *pContext = new EffectContext;

    LOGV("\n\tEffectCreate start session %d", sessionId);

    if (pInterface == NULL || uuid == NULL){
        LOGV("\tLVM_ERROR : EffectCreate() called with NULL pointer");
        return -EINVAL;
    }

    if((sessionId < 0)||(sessionId >= LVM_MAX_SESSIONS)){
        LOGV("\tLVM_ERROR : EffectCreate sessionId is less than 0");
        return -EINVAL;
    }

    if(LvmInitFlag == LVM_FALSE){
        LvmInitFlag = LVM_TRUE;
        LOGV("\tEffectCreate - Initializing all global memory");
        LvmGlobalBundle_init();
    }

    // If this is the first create in this session
    if(GlobalSessionMemory[sessionId].bBundledEffectsEnabled == LVM_FALSE){
        LOGV("\tEffectCreate - This is the first effect in current session %d", sessionId);
        LOGV("\tEffectCreate - Setting up Bundled Effects Instance for session %d", sessionId);

        GlobalSessionMemory[sessionId].bBundledEffectsEnabled = LVM_TRUE;
        GlobalSessionMemory[sessionId].pBundledContext        = new BundledEffectContext;

        pContext->pBundledContext = GlobalSessionMemory[sessionId].pBundledContext;
        pContext->pBundledContext->SessionNo                = sessionId;
        pContext->pBundledContext->hInstance                = NULL;
        pContext->pBundledContext->bVolumeEnabled           = LVM_FALSE;
        pContext->pBundledContext->bEqualizerEnabled        = LVM_FALSE;
        pContext->pBundledContext->bBassEnabled             = LVM_FALSE;
        pContext->pBundledContext->bBassTempDisabled        = LVM_FALSE;
        pContext->pBundledContext->bVirtualizerEnabled      = LVM_FALSE;
        pContext->pBundledContext->bVirtualizerTempDisabled = LVM_FALSE;
        pContext->pBundledContext->NumberEffectsEnabled     = 0;
        pContext->pBundledContext->NumberEffectsCalled      = 0;
        pContext->pBundledContext->frameCount               = 0;

        #ifdef LVM_PCM
        pContext->pBundledContext->PcmInPtr  = NULL;
        pContext->pBundledContext->PcmOutPtr = NULL;

        pContext->pBundledContext->PcmInPtr  = fopen("/data/tmp/bundle_pcm_in.pcm", "w");
        pContext->pBundledContext->PcmOutPtr = fopen("/data/tmp/bundle_pcm_out.pcm", "w");

        if((pContext->pBundledContext->PcmInPtr  == NULL)||
           (pContext->pBundledContext->PcmOutPtr == NULL)){
           return -EINVAL;
        }
        #endif

        /* Saved strength is used to return the exact strength that was used in the set to the get
         * because we map the original strength range of 0:1000 to 1:15, and this will avoid
         * quantisation like effect when returning
         */
        pContext->pBundledContext->BassStrengthSaved        = 0;
        pContext->pBundledContext->VirtStrengthSaved        = 0;
        pContext->pBundledContext->CurPreset                = PRESET_CUSTOM;
        pContext->pBundledContext->levelSaved               = 0;
        pContext->pBundledContext->bMuteEnabled             = LVM_FALSE;
        pContext->pBundledContext->bStereoPositionEnabled   = LVM_FALSE;
        pContext->pBundledContext->positionSaved            = 0;

        LOGV("\tEffectCreate - Calling LvmBundle_init");
        ret = LvmBundle_init(pContext);

        if (ret < 0){
            LOGV("\tLVM_ERROR : EffectCreate() Bundle init failed");
            delete pContext->pBundledContext;
            delete pContext;
            return ret;
        }
    }
    else{
        pContext->pBundledContext = GlobalSessionMemory[sessionId].pBundledContext;
    }

    LOGV("\tEffectCreate - pBundledContext is %p", pContext->pBundledContext);

    // Create each Effect
    if (memcmp(uuid, &gBassBoostDescriptor.uuid, sizeof(effect_uuid_t)) == 0){
        // Create Bass Boost
        LOGV("\tEffectCreate - Effect to be created is LVM_BASS_BOOST");
        GlobalSessionMemory[sessionId].bBassInstantiated = LVM_TRUE;

        pContext->itfe       = &gLvmEffectInterface;
        pContext->EffectType = LVM_BASS_BOOST;
    } else if (memcmp(uuid, &gVirtualizerDescriptor.uuid, sizeof(effect_uuid_t)) == 0){
        // Create Virtualizer
        LOGV("\tEffectCreate - Effect to be created is LVM_VIRTUALIZER");
        GlobalSessionMemory[sessionId].bVirtualizerInstantiated = LVM_TRUE;

        pContext->itfe       = &gLvmEffectInterface;
        pContext->EffectType = LVM_VIRTUALIZER;
    } else if (memcmp(uuid, &gEqualizerDescriptor.uuid, sizeof(effect_uuid_t)) == 0){
        // Create Equalizer
        LOGV("\tEffectCreate - Effect to be created is LVM_EQUALIZER");
        GlobalSessionMemory[sessionId].bEqualizerInstantiated = LVM_TRUE;

        pContext->itfe       = &gLvmEffectInterface;
        pContext->EffectType = LVM_EQUALIZER;
    } else if (memcmp(uuid, &gVolumeDescriptor.uuid, sizeof(effect_uuid_t)) == 0){
        // Create Volume
        LOGV("\tEffectCreate - Effect to be created is LVM_VOLUME");
        GlobalSessionMemory[sessionId].bVolumeInstantiated = LVM_TRUE;

        pContext->itfe       = &gLvmEffectInterface;
        pContext->EffectType = LVM_VOLUME;
    }
    else{
        LOGV("\tLVM_ERROR : EffectCreate() invalid UUID");
        return -EINVAL;
    }

    *pInterface = (effect_interface_t)pContext;
    LOGV("\tEffectCreate end..\n\n");
    return 0;
} /* end EffectCreate */

extern "C" int EffectRelease(effect_interface_t interface){
    LOGV("\n\tEffectRelease start %p", interface);
    EffectContext * pContext = (EffectContext *)interface;

    if (pContext == NULL){
        LOGV("\tLVM_ERROR : EffectRelease called with NULL pointer");
        return -EINVAL;
    }

    // Clear the instantiated flag for the effect
    if(pContext->EffectType == LVM_BASS_BOOST) {
        LOGV("\tEffectRelease LVM_BASS_BOOST Clearing global intstantiated flag");
        GlobalSessionMemory[pContext->pBundledContext->SessionNo].bBassInstantiated = LVM_FALSE;
    } else if(pContext->EffectType == LVM_VIRTUALIZER) {
        LOGV("\tEffectRelease LVM_VIRTUALIZER Clearing global intstantiated flag");
        GlobalSessionMemory[pContext->pBundledContext->SessionNo].bVirtualizerInstantiated
            = LVM_FALSE;
    } else if(pContext->EffectType == LVM_EQUALIZER) {
        LOGV("\tEffectRelease LVM_EQUALIZER Clearing global intstantiated flag");
        GlobalSessionMemory[pContext->pBundledContext->SessionNo].bEqualizerInstantiated =LVM_FALSE;
    } else if(pContext->EffectType == LVM_VOLUME) {
        LOGV("\tEffectRelease LVM_VOLUME Clearing global intstantiated flag");
        GlobalSessionMemory[pContext->pBundledContext->SessionNo].bVolumeInstantiated = LVM_FALSE;
    } else {
        LOGV("\tLVM_ERROR : EffectRelease : Unsupported effect\n\n\n\n\n\n\n");
    }

    // if all effects are no longer instantiaed free the lvm memory and delete BundledEffectContext
    if((GlobalSessionMemory[pContext->pBundledContext->SessionNo].bBassInstantiated == LVM_FALSE)&&
    (GlobalSessionMemory[pContext->pBundledContext->SessionNo].bVolumeInstantiated == LVM_FALSE)&&
    (GlobalSessionMemory[pContext->pBundledContext->SessionNo].bEqualizerInstantiated ==LVM_FALSE)&&
    (GlobalSessionMemory[pContext->pBundledContext->SessionNo].bVirtualizerInstantiated==LVM_FALSE))
    {
        #ifdef LVM_PCM
        fclose(pContext->pBundledContext->PcmInPtr);
        fclose(pContext->pBundledContext->PcmOutPtr);
        #endif
        LOGV("\tEffectRelease: All effects are no longer instantiated\n");
        GlobalSessionMemory[pContext->pBundledContext->SessionNo].bBundledEffectsEnabled =LVM_FALSE;
        GlobalSessionMemory[pContext->pBundledContext->SessionNo].pBundledContext = LVM_NULL;
        LOGV("\tEffectRelease: Freeing LVM Bundle memory\n");
        LvmEffect_free(pContext);
        LOGV("\tEffectRelease: Deleting LVM Bundle context\n");
        delete pContext->pBundledContext;
    }
    // free the effect context for current effect
    delete pContext;

    LOGV("\tEffectRelease end\n");
    return 0;

} /* end EffectRelease */

void LvmGlobalBundle_init(){
    LOGV("\tLvmGlobalBundle_init start");
    for(int i=0; i<LVM_MAX_SESSIONS; i++){
        GlobalSessionMemory[i].bBundledEffectsEnabled   = LVM_FALSE;
        GlobalSessionMemory[i].bVolumeInstantiated      = LVM_FALSE;
        GlobalSessionMemory[i].bEqualizerInstantiated   = LVM_FALSE;
        GlobalSessionMemory[i].bBassInstantiated        = LVM_FALSE;
        GlobalSessionMemory[i].bVirtualizerInstantiated = LVM_FALSE;
        GlobalSessionMemory[i].pBundledContext          = LVM_NULL;
    }
    return;
}
//----------------------------------------------------------------------------
// LvmBundle_init()
//----------------------------------------------------------------------------
// Purpose: Initialize engine with default configuration, creates instance
// with all effects disabled.
//
// Inputs:
//  pContext:   effect engine context
//
// Outputs:
//
//----------------------------------------------------------------------------

int LvmBundle_init(EffectContext *pContext){
    int status;

    LOGV("\tLvmBundle_init start");

    pContext->config.inputCfg.accessMode                    = EFFECT_BUFFER_ACCESS_READ;
    pContext->config.inputCfg.channels                      = CHANNEL_STEREO;
    pContext->config.inputCfg.format                        = SAMPLE_FORMAT_PCM_S15;
    pContext->config.inputCfg.samplingRate                  = 44100;
    pContext->config.inputCfg.bufferProvider.getBuffer      = NULL;
    pContext->config.inputCfg.bufferProvider.releaseBuffer  = NULL;
    pContext->config.inputCfg.bufferProvider.cookie         = NULL;
    pContext->config.inputCfg.mask                          = EFFECT_CONFIG_ALL;
    pContext->config.outputCfg.accessMode                   = EFFECT_BUFFER_ACCESS_ACCUMULATE;
    pContext->config.outputCfg.channels                     = CHANNEL_STEREO;
    pContext->config.outputCfg.format                       = SAMPLE_FORMAT_PCM_S15;
    pContext->config.outputCfg.samplingRate                 = 44100;
    pContext->config.outputCfg.bufferProvider.getBuffer     = NULL;
    pContext->config.outputCfg.bufferProvider.releaseBuffer = NULL;
    pContext->config.outputCfg.bufferProvider.cookie        = NULL;
    pContext->config.outputCfg.mask                         = EFFECT_CONFIG_ALL;

    CHECK_ARG(pContext != NULL);

    if (pContext->pBundledContext->hInstance != NULL){
        LOGV("\tLvmBundle_init pContext->pBassBoost != NULL "
                "-> Calling pContext->pBassBoost->free()");

        LvmEffect_free(pContext);

        LOGV("\tLvmBundle_init pContext->pBassBoost != NULL "
                "-> Called pContext->pBassBoost->free()");
    }

    LVM_ReturnStatus_en     LvmStatus=LVM_SUCCESS;          /* Function call status */
    LVM_ControlParams_t     params;                         /* Control Parameters */
    LVM_InstParams_t        InstParams;                     /* Instance parameters */
    LVM_EQNB_BandDef_t      BandDefs[MAX_NUM_BANDS];        /* Equaliser band definitions */
    LVM_HeadroomParams_t    HeadroomParams;                 /* Headroom parameters */
    LVM_HeadroomBandDef_t   HeadroomBandDef[LVM_HEADROOM_MAX_NBANDS];
    LVM_MemTab_t            MemTab;                         /* Memory allocation table */
    bool                    bMallocFailure = LVM_FALSE;

    /* Set the capabilities */
    InstParams.BufferMode       = LVM_UNMANAGED_BUFFERS;
    InstParams.MaxBlockSize     = MAX_CALL_SIZE;
    InstParams.EQNB_NumBands    = MAX_NUM_BANDS;
    InstParams.PSA_Included     = LVM_PSA_ON;

    /* Allocate memory, forcing alignment */
    LvmStatus = LVM_GetMemoryTable(LVM_NULL,
                                  &MemTab,
                                  &InstParams);

    LVM_ERROR_CHECK(LvmStatus, "LVM_GetMemoryTable", "LvmBundle_init")
    if(LvmStatus != LVM_SUCCESS) return -EINVAL;

    LOGV("\tCreateInstance Succesfully called LVM_GetMemoryTable\n");

    /* Allocate memory */
    for (int i=0; i<LVM_NR_MEMORY_REGIONS; i++){
        if (MemTab.Region[i].Size != 0){
            MemTab.Region[i].pBaseAddress = malloc(MemTab.Region[i].Size);

            if (MemTab.Region[i].pBaseAddress == LVM_NULL){
                LOGV("\tLVM_ERROR :LvmBundle_init CreateInstance Failed to allocate %ld bytes for region %u\n",
                        MemTab.Region[i].Size, i );
                bMallocFailure = LVM_TRUE;
            }else{
                LOGV("\tLvmBundle_init CreateInstance allocated %ld bytes for region %u at %p\n",
                        MemTab.Region[i].Size, i, MemTab.Region[i].pBaseAddress);
            }
        }
    }

    /* If one or more of the memory regions failed to allocate, free the regions that were
     * succesfully allocated and return with an error
     */
    if(bMallocFailure == LVM_TRUE){
        for (int i=0; i<LVM_NR_MEMORY_REGIONS; i++){
            if (MemTab.Region[i].pBaseAddress == LVM_NULL){
                LOGV("\tLVM_ERROR :LvmBundle_init CreateInstance Failed to allocate %ld bytes for region %u - +"
                     "Not freeing\n", MemTab.Region[i].Size, i );
            }else{
                LOGV("\tLVM_ERROR :LvmBundle_init CreateInstance Failed: but allocated %ld bytes "
                     "for region %u at %p- free\n",
                     MemTab.Region[i].Size, i, MemTab.Region[i].pBaseAddress);
                free(MemTab.Region[i].pBaseAddress);
            }
        }
        return -EINVAL;
    }
    LOGV("\tLvmBundle_init CreateInstance Succesfully malloc'd memory\n");

    /* Initialise */
    pContext->pBundledContext->hInstance = LVM_NULL;

    /* Init sets the instance handle */
    LvmStatus = LVM_GetInstanceHandle(&pContext->pBundledContext->hInstance,
                                      &MemTab,
                                      &InstParams);

    LVM_ERROR_CHECK(LvmStatus, "LVM_GetInstanceHandle", "LvmBundle_init")
    if(LvmStatus != LVM_SUCCESS) return -EINVAL;

    LOGV("\tLvmBundle_init CreateInstance Succesfully called LVM_GetInstanceHandle\n");

    /* Set the initial process parameters */
    /* General parameters */
    params.OperatingMode          = LVM_MODE_ON;
    params.SampleRate             = LVM_FS_44100;
    params.SourceFormat           = LVM_STEREO;
    params.SpeakerType            = LVM_HEADPHONES;

    pContext->pBundledContext->SampleRate = LVM_FS_44100;

    /* Concert Sound parameters */
    params.VirtualizerOperatingMode   = LVM_MODE_OFF;
    params.VirtualizerType            = LVM_CONCERTSOUND;
    params.VirtualizerReverbLevel     = 100;
    params.CS_EffectLevel             = LVM_CS_EFFECT_HIGH;

    /* N-Band Equaliser parameters */
    params.EQNB_OperatingMode     = LVM_EQNB_OFF;
    params.EQNB_NBands            = FIVEBAND_NUMBANDS;
    params.pEQNB_BandDefinition   = &BandDefs[0];

    for (int i=0; i<FIVEBAND_NUMBANDS; i++)
    {
        BandDefs[i].Frequency = EQNB_5BandPresetsFrequencies[i];
        BandDefs[i].QFactor   = EQNB_5BandPresetsQFactors[i];
        BandDefs[i].Gain      = EQNB_5BandSoftPresets[i];
    }

    /* Volume Control parameters */
    params.VC_EffectLevel         = 0;
    params.VC_Balance             = 0;

    /* Treble Enhancement parameters */
    params.TE_OperatingMode       = LVM_TE_OFF;
    params.TE_EffectLevel         = 0;

    /* PSA Control parameters */
    params.PSA_Enable             = LVM_PSA_OFF;
    params.PSA_PeakDecayRate      = (LVM_PSA_DecaySpeed_en)0;

    /* Bass Enhancement parameters */
    params.BE_OperatingMode       = LVM_BE_OFF;
    params.BE_EffectLevel         = 0;
    params.BE_CentreFreq          = LVM_BE_CENTRE_90Hz;
    params.BE_HPF                 = LVM_BE_HPF_ON;

    /* PSA Control parameters */
    params.PSA_Enable             = LVM_PSA_OFF;
    params.PSA_PeakDecayRate      = LVM_PSA_SPEED_MEDIUM;

    /* Activate the initial settings */
    LvmStatus = LVM_SetControlParameters(pContext->pBundledContext->hInstance,
                                         &params);

    LVM_ERROR_CHECK(LvmStatus, "LVM_SetControlParameters", "LvmBundle_init")
    if(LvmStatus != LVM_SUCCESS) return -EINVAL;

    LOGV("\tLvmBundle_init CreateInstance Succesfully called LVM_SetControlParameters\n");

    /* Set the headroom parameters */
    HeadroomBandDef[0].Limit_Low          = 20;
    HeadroomBandDef[0].Limit_High         = 4999;
    HeadroomBandDef[0].Headroom_Offset    = 3;
    HeadroomBandDef[1].Limit_Low          = 5000;
    HeadroomBandDef[1].Limit_High         = 24000;
    HeadroomBandDef[1].Headroom_Offset    = 4;
    HeadroomParams.pHeadroomDefinition    = &HeadroomBandDef[0];
    HeadroomParams.Headroom_OperatingMode = LVM_HEADROOM_ON;
    HeadroomParams.NHeadroomBands         = 2;

    LvmStatus = LVM_SetHeadroomParams(pContext->pBundledContext->hInstance,
                                      &HeadroomParams);

    LVM_ERROR_CHECK(LvmStatus, "LVM_SetHeadroomParams", "LvmBundle_init")
    if(LvmStatus != LVM_SUCCESS) return -EINVAL;

    LOGV("\tLvmBundle_init CreateInstance Succesfully called LVM_SetHeadroomParams\n");
    LOGV("\tLvmBundle_init End");
    return 0;
}   /* end LvmBundle_init */

//----------------------------------------------------------------------------
// LvmBundle_process()
//----------------------------------------------------------------------------
// Purpose:
// Apply LVM Bundle effects
//
// Inputs:
//  pIn:        pointer to stereo 16 bit input data
//  pOut:       pointer to stereo 16 bit output data
//  frameCount: Frames to process
//  pContext:   effect engine context
//  strength    strength to be applied
//
//  Outputs:
//  pOut:       pointer to updated stereo 16 bit output data
//
//----------------------------------------------------------------------------

int LvmBundle_process(LVM_INT16        *pIn,
                      LVM_INT16        *pOut,
                      int              frameCount,
                      EffectContext    *pContext){

    LVM_ControlParams_t     ActiveParams;                           /* Current control Parameters */
    LVM_ReturnStatus_en     LvmStatus = LVM_SUCCESS;                /* Function call status */

    LVM_INT16               *pOutTmp;
    if (pContext->config.outputCfg.accessMode == EFFECT_BUFFER_ACCESS_WRITE){
        pOutTmp = pOut;
    }else if (pContext->config.outputCfg.accessMode == EFFECT_BUFFER_ACCESS_ACCUMULATE){
        pOutTmp = (LVM_INT16 *)malloc(frameCount * sizeof(LVM_INT16) * 2);
        if(pOutTmp == NULL){
            LOGV("\tLVM_ERROR : LvmBundle_process failed to allocate memory for "
            "EFFECT_BUFFER_ACCESS_ACCUMULATE mode");
            return -EINVAL;
        }
    }else{
        LOGV("LVM_ERROR : LvmBundle_process invalid access mode");
        return -EINVAL;
    }

    /* Get the current settings */
    LvmStatus = LVM_GetControlParameters(pContext->pBundledContext->hInstance,
                                         &ActiveParams);

    LVM_ERROR_CHECK(LvmStatus, "LVM_GetControlParameters", "LvmBundle_process")
    if(LvmStatus != LVM_SUCCESS) return -EINVAL;

    pContext->pBundledContext->frameCount++;
    if(pContext->pBundledContext->frameCount == 100)
    {
        //LOGV("\tBB: %d VIRT: %d EQ: %d, session (%d), context is %p\n",
        //ActiveParams.BE_OperatingMode,
        //ActiveParams.VirtualizerOperatingMode, ActiveParams.EQNB_OperatingMode,
        //pContext->pBundledContext->SessionNo, pContext->pBundledContext);
        pContext->pBundledContext->frameCount = 0;
    }

    #ifdef LVM_PCM
    fwrite(pIn, frameCount*sizeof(LVM_INT16)*2, 1, pContext->pBundledContext->PcmInPtr);
    fflush(pContext->pBundledContext->PcmInPtr);
    #endif

    /* Process the samples */
    LvmStatus = LVM_Process(pContext->pBundledContext->hInstance, /* Instance handle */
                            pIn,                                  /* Input buffer */
                            pOutTmp,                              /* Output buffer */
                            (LVM_UINT16)frameCount,               /* Number of samples to read */
                            0);                                   /* Audo Time */

    LVM_ERROR_CHECK(LvmStatus, "LVM_Process", "LvmBundle_process")
    if(LvmStatus != LVM_SUCCESS) return -EINVAL;

    #ifdef LVM_PCM
    fwrite(pOutTmp, frameCount*sizeof(LVM_INT16)*2, 1, pContext->pBundledContext->PcmOutPtr);
    fflush(pContext->pBundledContext->PcmOutPtr);
    #endif

    if (pContext->config.outputCfg.accessMode == EFFECT_BUFFER_ACCESS_ACCUMULATE){
        for (int i=0; i<frameCount*2; i++){
            pOut[i] +=  pOutTmp[i];
        }
        free(pOutTmp);
    }
    return 0;
}    /* end LvmBundle_process */

//----------------------------------------------------------------------------
// LvmEffect_enable()
//----------------------------------------------------------------------------
// Purpose: Enable the effect in the bundle
//
// Inputs:
//  pContext:   effect engine context
//
// Outputs:
//
//----------------------------------------------------------------------------

int LvmEffect_enable(EffectContext *pContext){
    //LOGV("\tLvmEffect_enable start");

    LVM_ControlParams_t     ActiveParams;                           /* Current control Parameters */
    LVM_ReturnStatus_en     LvmStatus = LVM_SUCCESS;                /* Function call status */

    /* Get the current settings */
    LvmStatus = LVM_GetControlParameters(pContext->pBundledContext->hInstance,
                                         &ActiveParams);

    LVM_ERROR_CHECK(LvmStatus, "LVM_GetControlParameters", "LvmEffect_enable")
    if(LvmStatus != LVM_SUCCESS) return -EINVAL;
    //LOGV("\tLvmEffect_enable Succesfully called LVM_GetControlParameters\n");

    if(pContext->EffectType == LVM_BASS_BOOST) {
        LOGV("\tLvmEffect_enable : Enabling LVM_BASS_BOOST");
        ActiveParams.BE_OperatingMode       = LVM_BE_ON;
    }
    if(pContext->EffectType == LVM_VIRTUALIZER) {
        LOGV("\tLvmEffect_enable : Enabling LVM_VIRTUALIZER");
        ActiveParams.VirtualizerOperatingMode   = LVM_MODE_ON;
    }
    if(pContext->EffectType == LVM_EQUALIZER) {
        LOGV("\tLvmEffect_enable : Enabling LVM_EQUALIZER");
        ActiveParams.EQNB_OperatingMode     = LVM_EQNB_ON;
    }
    if(pContext->EffectType == LVM_VOLUME) {
        LOGV("\tLvmEffect_enable : Enabling LVM_VOLUME");
    }

    LvmStatus = LVM_SetControlParameters(pContext->pBundledContext->hInstance, &ActiveParams);
    LVM_ERROR_CHECK(LvmStatus, "LVM_SetControlParameters", "LvmEffect_enable")
    if(LvmStatus != LVM_SUCCESS) return -EINVAL;

    //LOGV("\tLvmEffect_enable Succesfully called LVM_SetControlParameters\n");
    //LOGV("\tLvmEffect_enable end");
    return 0;
}

//----------------------------------------------------------------------------
// LvmEffect_disable()
//----------------------------------------------------------------------------
// Purpose: Disable the effect in the bundle
//
// Inputs:
//  pContext:   effect engine context
//
// Outputs:
//
//----------------------------------------------------------------------------

int LvmEffect_disable(EffectContext *pContext){
    //LOGV("\tLvmEffect_disable start");

    LVM_ControlParams_t     ActiveParams;                           /* Current control Parameters */
    LVM_ReturnStatus_en     LvmStatus = LVM_SUCCESS;                /* Function call status */
    /* Get the current settings */
    LvmStatus = LVM_GetControlParameters(pContext->pBundledContext->hInstance,
                                         &ActiveParams);

    LVM_ERROR_CHECK(LvmStatus, "LVM_GetControlParameters", "LvmEffect_disable")
    if(LvmStatus != LVM_SUCCESS) return -EINVAL;
    //LOGV("\tLvmEffect_disable Succesfully called LVM_GetControlParameters\n");

    if(pContext->EffectType == LVM_BASS_BOOST) {
        LOGV("\tLvmEffect_disable : Disabling LVM_BASS_BOOST");
        ActiveParams.BE_OperatingMode       = LVM_BE_OFF;
    }
    if(pContext->EffectType == LVM_VIRTUALIZER) {
        LOGV("\tLvmEffect_disable : Enabling LVM_VIRTUALIZER");
        ActiveParams.VirtualizerOperatingMode   = LVM_MODE_OFF;
    }
    if(pContext->EffectType == LVM_EQUALIZER) {
        LOGV("\tLvmEffect_disable : Enabling LVM_EQUALIZER");
        ActiveParams.EQNB_OperatingMode     = LVM_EQNB_OFF;
    }
    if(pContext->EffectType == LVM_VOLUME) {
        LOGV("\tLvmEffect_disable : Enabling LVM_VOLUME");
    }

    LvmStatus = LVM_SetControlParameters(pContext->pBundledContext->hInstance, &ActiveParams);
    LVM_ERROR_CHECK(LvmStatus, "LVM_SetControlParameters", "LvmEffect_disable")
    if(LvmStatus != LVM_SUCCESS) return -EINVAL;

    //LOGV("\tLvmEffect_disable Succesfully called LVM_SetControlParameters\n");
    //LOGV("\tLvmEffect_disable end");
    return 0;
}

//----------------------------------------------------------------------------
// LvmEffect_free()
//----------------------------------------------------------------------------
// Purpose: Free all memory associated with the Bundle.
//
// Inputs:
//  pContext:   effect engine context
//
// Outputs:
//
//----------------------------------------------------------------------------

void LvmEffect_free(EffectContext *pContext){
    LVM_ReturnStatus_en     LvmStatus=LVM_SUCCESS;         /* Function call status */
    LVM_ControlParams_t     params;                        /* Control Parameters */
    LVM_MemTab_t            MemTab;

    /* Free the algorithm memory */
    LvmStatus = LVM_GetMemoryTable(pContext->pBundledContext->hInstance,
                                   &MemTab,
                                   LVM_NULL);

    LVM_ERROR_CHECK(LvmStatus, "LVM_GetMemoryTable", "LvmEffect_free")

    for (int i=0; i<LVM_NR_MEMORY_REGIONS; i++){
        if (MemTab.Region[i].Size != 0){
            if (MemTab.Region[i].pBaseAddress != NULL){
                LOGV("\tLvmEffect_free - START freeing %ld bytes for region %u at %p\n",
                        MemTab.Region[i].Size, i, MemTab.Region[i].pBaseAddress);

                free(MemTab.Region[i].pBaseAddress);

                LOGV("\tLvmEffect_free - END   freeing %ld bytes for region %u at %p\n",
                        MemTab.Region[i].Size, i, MemTab.Region[i].pBaseAddress);
            }else{
                LOGV("\tLVM_ERROR : LvmEffect_free - trying to free with NULL pointer %ld bytes "
                        "for region %u at %p ERROR\n",
                        MemTab.Region[i].Size, i, MemTab.Region[i].pBaseAddress);
            }
        }
    }
}    /* end LvmEffect_free */

//----------------------------------------------------------------------------
// Effect_configure()
//----------------------------------------------------------------------------
// Purpose: Set input and output audio configuration.
//
// Inputs:
//  pContext:   effect engine context
//  pConfig:    pointer to effect_config_t structure holding input and output
//      configuration parameters
//
// Outputs:
//
//----------------------------------------------------------------------------

int Effect_configure(EffectContext *pContext, effect_config_t *pConfig){
    LVM_Fs_en   SampleRate;
    //LOGV("\tEffect_configure start");

    CHECK_ARG(pContext != NULL);
    CHECK_ARG(pConfig != NULL);

    CHECK_ARG(pConfig->inputCfg.samplingRate == pConfig->outputCfg.samplingRate);
    CHECK_ARG(pConfig->inputCfg.channels == pConfig->outputCfg.channels);
    CHECK_ARG(pConfig->inputCfg.format == pConfig->outputCfg.format);
    CHECK_ARG(pConfig->inputCfg.channels == CHANNEL_STEREO);
    CHECK_ARG(pConfig->outputCfg.accessMode == EFFECT_BUFFER_ACCESS_WRITE
              || pConfig->outputCfg.accessMode == EFFECT_BUFFER_ACCESS_ACCUMULATE);
    CHECK_ARG(pConfig->inputCfg.format == SAMPLE_FORMAT_PCM_S15);

    memcpy(&pContext->config, pConfig, sizeof(effect_config_t));

    switch (pConfig->inputCfg.samplingRate) {
    case 8000:
        SampleRate = LVM_FS_8000;
        break;
    case 16000:
        SampleRate = LVM_FS_16000;
        break;
    case 22050:
        SampleRate = LVM_FS_22050;
        break;
    case 32000:
        SampleRate = LVM_FS_32000;
        break;
    case 44100:
        SampleRate = LVM_FS_44100;
        break;
    case 48000:
        SampleRate = LVM_FS_48000;
        break;
    default:
        LOGV("\tEffect_Configure invalid sampling rate %d", pConfig->inputCfg.samplingRate);
        return -EINVAL;
    }

    if(pContext->pBundledContext->SampleRate != SampleRate){

        LVM_ControlParams_t     ActiveParams;
        LVM_ReturnStatus_en     LvmStatus = LVM_SUCCESS;

        LOGV("\tEffect_configure change sampling rate to %d", SampleRate);

        /* Get the current settings */
        LvmStatus = LVM_GetControlParameters(pContext->pBundledContext->hInstance,
                                         &ActiveParams);

        LVM_ERROR_CHECK(LvmStatus, "LVM_GetControlParameters", "Effect_configure")
        if(LvmStatus != LVM_SUCCESS) return -EINVAL;

        LvmStatus = LVM_SetControlParameters(pContext->pBundledContext->hInstance, &ActiveParams);

        LVM_ERROR_CHECK(LvmStatus, "LVM_SetControlParameters", "Effect_configure")
        LOGV("\tEffect_configure Succesfully called LVM_SetControlParameters\n");

    }else{
        //LOGV("\tEffect_configure keep sampling rate at %d", SampleRate);
    }

    //LOGV("\tEffect_configure End....");
    return 0;
}   /* end Effect_configure */

//----------------------------------------------------------------------------
// BassGetStrength()
//----------------------------------------------------------------------------
// Purpose:
// get the effect strength currently being used, what is actually returned is the strengh that was
// previously used in the set, this is because the app uses a strength in the range 0-1000 while
// the bassboost uses 1-15, so to avoid a quantisation the original set value is used. However the
// actual used value is checked to make sure it corresponds to the one being returned
//
// Inputs:
//  pContext:   effect engine context
//
//----------------------------------------------------------------------------

uint32_t BassGetStrength(EffectContext *pContext){
    //LOGV("\tBassGetStrength() (0-1000) -> %d\n", pContext->pBundledContext->BassStrengthSaved);

    LVM_ControlParams_t     ActiveParams;                           /* Current control Parameters */
    LVM_ReturnStatus_en     LvmStatus = LVM_SUCCESS;                /* Function call status */
    /* Get the current settings */
    LvmStatus = LVM_GetControlParameters(pContext->pBundledContext->hInstance,
                                         &ActiveParams);

    LVM_ERROR_CHECK(LvmStatus, "LVM_GetControlParameters", "BassGetStrength")
    if(LvmStatus != LVM_SUCCESS) return -EINVAL;

    //LOGV("\tBassGetStrength Succesfully returned from LVM_GetControlParameters\n");

    /* Check that the strength returned matches the strength that was set earlier */
    if(ActiveParams.BE_EffectLevel !=
       (LVM_INT16)((15*pContext->pBundledContext->BassStrengthSaved)/1000)){
        LOGV("\tLVM_ERROR : BassGetStrength module strength does not match savedStrength %d %d\n",
                ActiveParams.BE_EffectLevel, pContext->pBundledContext->BassStrengthSaved);
        return -EINVAL;
    }

    //LOGV("\tBassGetStrength() (0-15)   -> %d\n", ActiveParams.BE_EffectLevel );
    //LOGV("\tBassGetStrength() (saved)  -> %d\n", pContext->pBundledContext->BassStrengthSaved );
    return pContext->pBundledContext->BassStrengthSaved;
}    /* end BassGetStrength */

//----------------------------------------------------------------------------
// BassSetStrength()
//----------------------------------------------------------------------------
// Purpose:
// Apply the strength to the BassBosst. Must first be converted from the range 0-1000 to 1-15
//
// Inputs:
//  pContext:   effect engine context
//  strength    strength to be applied
//
//----------------------------------------------------------------------------

void BassSetStrength(EffectContext *pContext, uint32_t strength){
    //LOGV("\tBassSetStrength(%d)", strength);

    pContext->pBundledContext->BassStrengthSaved = (int)strength;

    LVM_ControlParams_t     ActiveParams;              /* Current control Parameters */
    LVM_ReturnStatus_en     LvmStatus=LVM_SUCCESS;     /* Function call status */

    /* Get the current settings */
    LvmStatus = LVM_GetControlParameters(pContext->pBundledContext->hInstance,
                                         &ActiveParams);

    LVM_ERROR_CHECK(LvmStatus, "LVM_GetControlParameters", "BassSetStrength")
    //LOGV("\tBassSetStrength Succesfully returned from LVM_GetControlParameters\n");

    /* Bass Enhancement parameters */
    ActiveParams.BE_EffectLevel    = (LVM_INT16)((15*strength)/1000);
    ActiveParams.BE_CentreFreq     = LVM_BE_CENTRE_90Hz;

    //LOGV("\tBassSetStrength() (0-15)   -> %d\n", ActiveParams.BE_EffectLevel );

    /* Activate the initial settings */
    LvmStatus = LVM_SetControlParameters(pContext->pBundledContext->hInstance, &ActiveParams);

    LVM_ERROR_CHECK(LvmStatus, "LVM_SetControlParameters", "BassSetStrength")
    //LOGV("\tBassSetStrength Succesfully called LVM_SetControlParameters\n");
}    /* end BassSetStrength */

//----------------------------------------------------------------------------
// VirtualizerGetStrength()
//----------------------------------------------------------------------------
// Purpose:
// get the effect strength currently being used, what is actually returned is the strengh that was
// previously used in the set, this is because the app uses a strength in the range 0-1000 while
// the Virtualizer uses 1-100, so to avoid a quantisation the original set value is used.However the
// actual used value is checked to make sure it corresponds to the one being returned
//
// Inputs:
//  pContext:   effect engine context
//
//----------------------------------------------------------------------------

uint32_t VirtualizerGetStrength(EffectContext *pContext){
    //LOGV("\tVirtualizerGetStrength (0-1000) -> %d\n",pContext->pBundledContext->VirtStrengthSaved);

    LVM_ControlParams_t     ActiveParams;                           /* Current control Parameters */
    LVM_ReturnStatus_en     LvmStatus = LVM_SUCCESS;                /* Function call status */

    LvmStatus = LVM_GetControlParameters(pContext->pBundledContext->hInstance, &ActiveParams);

    LVM_ERROR_CHECK(LvmStatus, "LVM_GetControlParameters", "VirtualizerGetStrength")
    if(LvmStatus != LVM_SUCCESS) return -EINVAL;

    //LOGV("\tVirtualizerGetStrength Succesfully returned from LVM_GetControlParameters\n");
    //LOGV("\tVirtualizerGetStrength() (0-100)   -> %d\n", ActiveParams.VirtualizerReverbLevel*10);
    return ActiveParams.VirtualizerReverbLevel*10;
}    /* end getStrength */

//----------------------------------------------------------------------------
// VirtualizerSetStrength()
//----------------------------------------------------------------------------
// Purpose:
// Apply the strength to the Virtualizer. Must first be converted from the range 0-1000 to 1-15
//
// Inputs:
//  pContext:   effect engine context
//  strength    strength to be applied
//
//----------------------------------------------------------------------------

void VirtualizerSetStrength(EffectContext *pContext, uint32_t strength){
    //LOGV("\tVirtualizerSetStrength(%d)", strength);
    LVM_ControlParams_t     ActiveParams;              /* Current control Parameters */
    LVM_ReturnStatus_en     LvmStatus=LVM_SUCCESS;     /* Function call status */

    pContext->pBundledContext->VirtStrengthSaved = (int)strength;

    /* Get the current settings */
    LvmStatus = LVM_GetControlParameters(pContext->pBundledContext->hInstance,&ActiveParams);

    LVM_ERROR_CHECK(LvmStatus, "LVM_GetControlParameters", "VirtualizerSetStrength")
    //LOGV("\tVirtualizerSetStrength Succesfully returned from LVM_GetControlParameters\n");

    /* Virtualizer parameters */
    ActiveParams.VirtualizerReverbLevel    = (LVM_INT16)(strength/10);

    //LOGV("\tVirtualizerSetStrength() (0-1000)   -> %d\n", strength );
    //LOGV("\tVirtualizerSetStrength() (0- 100)   -> %d\n", ActiveParams.VirtualizerReverbLevel );

    /* Activate the initial settings */
    LvmStatus = LVM_SetControlParameters(pContext->pBundledContext->hInstance, &ActiveParams);
    LVM_ERROR_CHECK(LvmStatus, "LVM_SetControlParameters", "VirtualizerSetStrength")
    //LOGV("\tVirtualizerSetStrength Succesfully called LVM_SetControlParameters\n");
}    /* end setStrength */

//----------------------------------------------------------------------------
// EqualizerGetBandLevel()
//----------------------------------------------------------------------------
// Purpose: Retrieve the gain currently being used for the band passed in
//
// Inputs:
//  band:       band number
//  pContext:   effect engine context
//
// Outputs:
//
//----------------------------------------------------------------------------
int32_t EqualizerGetBandLevel(EffectContext *pContext, int32_t band){

    int32_t Gain =0;
    LVM_ControlParams_t     ActiveParams;                           /* Current control Parameters */
    LVM_ReturnStatus_en     LvmStatus = LVM_SUCCESS;                /* Function call status */
    LVM_EQNB_BandDef_t      *BandDef;
    /* Get the current settings */
    LvmStatus = LVM_GetControlParameters(pContext->pBundledContext->hInstance,
                                         &ActiveParams);

    LVM_ERROR_CHECK(LvmStatus, "LVM_GetControlParameters", "EqualizerGetBandLevel")

    BandDef = ActiveParams.pEQNB_BandDefinition;
    Gain    = (int32_t)BandDef[band].Gain*100;    // Convert to millibels

    //LOGV("\tEqualizerGetBandLevel -> %d\n", Gain );
    //LOGV("\tEqualizerGetBandLevel Succesfully returned from LVM_GetControlParameters\n");
    return Gain;
}

//----------------------------------------------------------------------------
// EqualizerSetBandLevel()
//----------------------------------------------------------------------------
// Purpose:
//  Sets gain value for the given band.
//
// Inputs:
//  band:       band number
//  Gain:       Gain to be applied in millibels
//  pContext:   effect engine context
//
// Outputs:
//
//---------------------------------------------------------------------------
void EqualizerSetBandLevel(EffectContext *pContext, int band, int Gain){
    int gainRounded;
    if(Gain > 0){
        gainRounded = (int)((Gain+50)/100);
    }else{
        gainRounded = (int)((Gain-50)/100);
    }
    //LOGV("\tEqualizerSetBandLevel(%d)->(%d)", Gain, gainRounded);


    LVM_ControlParams_t     ActiveParams;              /* Current control Parameters */
    LVM_ReturnStatus_en     LvmStatus=LVM_SUCCESS;     /* Function call status */
    LVM_EQNB_BandDef_t      *BandDef;

    /* Get the current settings */
    LvmStatus = LVM_GetControlParameters(pContext->pBundledContext->hInstance, &ActiveParams);
    LVM_ERROR_CHECK(LvmStatus, "LVM_GetControlParameters", "EqualizerSetBandLevel")
    //LOGV("\tEqualizerSetBandLevel Succesfully returned from LVM_GetControlParameters\n");
    //LOGV("\tEqualizerSetBandLevel just Got -> %d\n", ActiveParams.pEQNB_BandDefinition[band].Gain);

    /* Set local EQ parameters */
    BandDef = ActiveParams.pEQNB_BandDefinition;
    ActiveParams.pEQNB_BandDefinition[band].Gain = gainRounded;

    /* Activate the initial settings */
    LvmStatus = LVM_SetControlParameters(pContext->pBundledContext->hInstance, &ActiveParams);
    LVM_ERROR_CHECK(LvmStatus, "LVM_SetControlParameters", "EqualizerSetBandLevel")
    //LOGV("\tEqualizerSetBandLevel just Set -> %d\n", ActiveParams.pEQNB_BandDefinition[band].Gain);

    pContext->pBundledContext->CurPreset = PRESET_CUSTOM;
    return;
}
//----------------------------------------------------------------------------
// EqualizerGetCentreFrequency()
//----------------------------------------------------------------------------
// Purpose: Retrieve the frequency being used for the band passed in
//
// Inputs:
//  band:       band number
//  pContext:   effect engine context
//
// Outputs:
//
//----------------------------------------------------------------------------
int32_t EqualizerGetCentreFrequency(EffectContext *pContext, int32_t band){
    int32_t Frequency =0;

    LVM_ControlParams_t     ActiveParams;                           /* Current control Parameters */
    LVM_ReturnStatus_en     LvmStatus = LVM_SUCCESS;                /* Function call status */
    LVM_EQNB_BandDef_t      *BandDef;
    /* Get the current settings */
    LvmStatus = LVM_GetControlParameters(pContext->pBundledContext->hInstance,
                                         &ActiveParams);

    LVM_ERROR_CHECK(LvmStatus, "LVM_GetControlParameters", "EqualizerGetCentreFrequency")

    BandDef   = ActiveParams.pEQNB_BandDefinition;
    Frequency = (int32_t)BandDef[band].Frequency*1000;     // Convert to millibels

    //LOGV("\tEqualizerGetCentreFrequency -> %d\n", Frequency );
    //LOGV("\tEqualizerGetCentreFrequency Succesfully returned from LVM_GetControlParameters\n");
    return Frequency;
}

//----------------------------------------------------------------------------
// EqualizerGetBandFreqRange(
//----------------------------------------------------------------------------
// Purpose:
//
// Gets lower and upper boundaries of a band.
// For the high shelf, the low bound is the band frequency and the high
// bound is Nyquist.
// For the peaking filters, they are the gain[dB]/2 points.
//
// Inputs:
//  band:       band number
//  pContext:   effect engine context
//
// Outputs:
//  pLow:       lower band range
//  pLow:       upper band range
//----------------------------------------------------------------------------
int32_t EqualizerGetBandFreqRange(EffectContext *pContext, int32_t band, uint32_t *pLow,
                                  uint32_t *pHi){
    *pLow = bandFreqRange[band][0];
    *pHi  = bandFreqRange[band][1];
    return 0;
}

//----------------------------------------------------------------------------
// EqualizerGetBand(
//----------------------------------------------------------------------------
// Purpose:
//
// Returns the band with the maximum influence on a given frequency.
// Result is unaffected by whether EQ is enabled or not, or by whether
// changes have been committed or not.
//
// Inputs:
//  targetFreq   The target frequency, in millihertz.
//  pContext:    effect engine context
//
// Outputs:
//  pLow:       lower band range
//  pLow:       upper band range
//----------------------------------------------------------------------------
int32_t EqualizerGetBand(EffectContext *pContext, uint32_t targetFreq){
    int band = 0;

    if(targetFreq < bandFreqRange[0][0]){
        return -EINVAL;
    }else if(targetFreq == bandFreqRange[0][0]){
        return 0;
    }
    for(int i=0; i<FIVEBAND_NUMBANDS;i++){
        if((targetFreq > bandFreqRange[i][0])&&(targetFreq <= bandFreqRange[i][1])){
            band = i;
        }
    }
    return band;
}

//----------------------------------------------------------------------------
// EqualizerGetPreset(
//----------------------------------------------------------------------------
// Purpose:
//
// Gets the currently set preset ID.
// Will return PRESET_CUSTOM in case the EQ parameters have been modified
// manually since a preset was set.
//
// Inputs:
//  pContext:    effect engine context
//
//----------------------------------------------------------------------------
int32_t EqualizerGetPreset(EffectContext *pContext){
    return pContext->pBundledContext->CurPreset;
}

//----------------------------------------------------------------------------
// EqualizerSetPreset(
//----------------------------------------------------------------------------
// Purpose:
//
// Sets the current preset by ID.
// All the band parameters will be overridden.
//
// Inputs:
//  pContext:    effect engine context
//  preset       The preset ID.
//
//----------------------------------------------------------------------------
void EqualizerSetPreset(EffectContext *pContext, int preset){

    //LOGV("\tEqualizerSetPreset(%d)", preset);
    pContext->pBundledContext->CurPreset = preset;

    LVM_ControlParams_t     ActiveParams;              /* Current control Parameters */
    LVM_ReturnStatus_en     LvmStatus=LVM_SUCCESS;     /* Function call status */

    /* Get the current settings */
    LvmStatus = LVM_GetControlParameters(pContext->pBundledContext->hInstance, &ActiveParams);
    LVM_ERROR_CHECK(LvmStatus, "LVM_GetControlParameters", "EqualizerSetPreset")
    //LOGV("\tEqualizerSetPreset Succesfully returned from LVM_GetControlParameters\n");

    //ActiveParams.pEQNB_BandDefinition = &BandDefs[0];
    for (int i=0; i<FIVEBAND_NUMBANDS; i++)
    {
        ActiveParams.pEQNB_BandDefinition[i].Frequency = EQNB_5BandPresetsFrequencies[i];
        ActiveParams.pEQNB_BandDefinition[i].QFactor   = EQNB_5BandPresetsQFactors[i];
        ActiveParams.pEQNB_BandDefinition[i].Gain
        = EQNB_5BandSoftPresets[i + preset * FIVEBAND_NUMBANDS];
    }
    /* Activate the new settings */
    LvmStatus = LVM_SetControlParameters(pContext->pBundledContext->hInstance, &ActiveParams);
    LVM_ERROR_CHECK(LvmStatus, "LVM_SetControlParameters", "EqualizerSetPreset")

    //LOGV("\tEqualizerSetPreset Succesfully called LVM_SetControlParameters\n");
    return;
}

int32_t EqualizerGetNumPresets(){
    return sizeof(gEqualizerPresets) / sizeof(PresetConfig);
}

//----------------------------------------------------------------------------
// EqualizerGetPresetName(
//----------------------------------------------------------------------------
// Purpose:
// Gets a human-readable name for a preset ID. Will return "Custom" if
// PRESET_CUSTOM is passed.
//
// Inputs:
// preset       The preset ID. Must be less than number of presets.
//
//-------------------------------------------------------------------------
const char * EqualizerGetPresetName(int32_t preset){
    //LOGV("\tEqualizerGetPresetName start(%d)", preset);
    if (preset == PRESET_CUSTOM) {
        return "Custom";
    } else {
        return gEqualizerPresets[preset].name;
    }
    //LOGV("\tEqualizerGetPresetName end(%d)", preset);
    return 0;
}

//----------------------------------------------------------------------------
// VolumeSetVolumeLevel()
//----------------------------------------------------------------------------
// Purpose:
//
// Inputs:
//  pContext:   effect engine context
//  level       level to be applied
//
//----------------------------------------------------------------------------

int VolumeSetVolumeLevel(EffectContext *pContext, int16_t level){

    LVM_ControlParams_t     ActiveParams;              /* Current control Parameters */
    LVM_ReturnStatus_en     LvmStatus=LVM_SUCCESS;     /* Function call status */

    //LOGV("\tVolumeSetVolumeLevel Level to be set is %d %d\n", level, (LVM_INT16)(level/100));
    /* Get the current settings */
    LvmStatus = LVM_GetControlParameters(pContext->pBundledContext->hInstance, &ActiveParams);
    LVM_ERROR_CHECK(LvmStatus, "LVM_GetControlParameters", "VolumeSetVolumeLevel")
    if(LvmStatus != LVM_SUCCESS) return -EINVAL;
    //LOGV("\tVolumeSetVolumeLevel Succesfully returned from LVM_GetControlParameters got: %d\n",
    //ActiveParams.VC_EffectLevel);

    /* Volume parameters */
    ActiveParams.VC_EffectLevel  = (LVM_INT16)(level/100);
    //LOGV("\tVolumeSetVolumeLevel() (-96dB -> 0dB)   -> %d\n", ActiveParams.VC_EffectLevel );

    /* Activate the initial settings */
    LvmStatus = LVM_SetControlParameters(pContext->pBundledContext->hInstance, &ActiveParams);
    LVM_ERROR_CHECK(LvmStatus, "LVM_SetControlParameters", "VolumeSetVolumeLevel")
    if(LvmStatus != LVM_SUCCESS) return -EINVAL;

    //LOGV("\tVolumeSetVolumeLevel Succesfully called LVM_SetControlParameters\n");

    /* Get the current settings */
    LvmStatus = LVM_GetControlParameters(pContext->pBundledContext->hInstance, &ActiveParams);
    LVM_ERROR_CHECK(LvmStatus, "LVM_GetControlParameters", "VolumeSetVolumeLevel")
    if(LvmStatus != LVM_SUCCESS) return -EINVAL;

    //LOGV("\tVolumeSetVolumeLevel just set (-96dB -> 0dB)   -> %d\n", ActiveParams.VC_EffectLevel );
    return 0;
}    /* end setVolumeLevel */

//----------------------------------------------------------------------------
// VolumeGetVolumeLevel()
//----------------------------------------------------------------------------
// Purpose:
//
// Inputs:
//  pContext:   effect engine context
//
//----------------------------------------------------------------------------

int VolumeGetVolumeLevel(EffectContext *pContext, int16_t *level){

    //LOGV("\tVolumeGetVolumeLevel start");

    LVM_ControlParams_t     ActiveParams;                           /* Current control Parameters */
    LVM_ReturnStatus_en     LvmStatus = LVM_SUCCESS;                /* Function call status */

    LvmStatus = LVM_GetControlParameters(pContext->pBundledContext->hInstance, &ActiveParams);
    LVM_ERROR_CHECK(LvmStatus, "LVM_GetControlParameters", "VolumeGetVolumeLevel")
    if(LvmStatus != LVM_SUCCESS) return -EINVAL;

    //LOGV("\tVolumeGetVolumeLevel() (-96dB -> 0dB) -> %d\n", ActiveParams.VC_EffectLevel );
    //LOGV("\tVolumeGetVolumeLevel Succesfully returned from LVM_GetControlParameters\n");

    *level = ActiveParams.VC_EffectLevel*100;     // Convert dB to millibels
    //LOGV("\tVolumeGetVolumeLevel end");
    return 0;
}    /* end VolumeGetVolumeLevel */

//----------------------------------------------------------------------------
// VolumeSetMute()
//----------------------------------------------------------------------------
// Purpose:
//
// Inputs:
//  pContext:   effect engine context
//  mute:       enable/disable flag
//
//----------------------------------------------------------------------------

int32_t VolumeSetMute(EffectContext *pContext, uint32_t mute){
    //LOGV("\tVolumeSetMute start(%d)", mute);

    pContext->pBundledContext->bMuteEnabled = mute;

    LVM_ControlParams_t     ActiveParams;              /* Current control Parameters */
    LVM_ReturnStatus_en     LvmStatus=LVM_SUCCESS;     /* Function call status */

    /* Get the current settings */
    LvmStatus = LVM_GetControlParameters(pContext->pBundledContext->hInstance, &ActiveParams);
    LVM_ERROR_CHECK(LvmStatus, "LVM_GetControlParameters", "VolumeSetMute")
    if(LvmStatus != LVM_SUCCESS) return -EINVAL;

    //LOGV("\tVolumeSetMute Succesfully returned from LVM_GetControlParameters\n");
    //LOGV("\tVolumeSetMute to %d, level was %d\n", mute, ActiveParams.VC_EffectLevel );

    /* Set appropriate volume level */
    if(pContext->pBundledContext->bMuteEnabled == LVM_TRUE){
        pContext->pBundledContext->levelSaved = ActiveParams.VC_EffectLevel;
        ActiveParams.VC_EffectLevel           = -96;
    }else{
        ActiveParams.VC_EffectLevel  = pContext->pBundledContext->levelSaved;
    }

    /* Activate the initial settings */
    LvmStatus = LVM_SetControlParameters(pContext->pBundledContext->hInstance, &ActiveParams);
    LVM_ERROR_CHECK(LvmStatus, "LVM_SetControlParameters", "VolumeSetMute")
    if(LvmStatus != LVM_SUCCESS) return -EINVAL;

    //LOGV("\tVolumeSetMute Succesfully called LVM_SetControlParameters\n");
    //LOGV("\tVolumeSetMute end");
    return 0;
}    /* end setMute */

//----------------------------------------------------------------------------
// VolumeGetMute()
//----------------------------------------------------------------------------
// Purpose:
//
// Inputs:
//  pContext:   effect engine context
//
// Ourputs:
//  mute:       enable/disable flag
//----------------------------------------------------------------------------

int32_t VolumeGetMute(EffectContext *pContext, uint32_t *mute){
    //LOGV("\tVolumeGetMute start");
    if((pContext->pBundledContext->bMuteEnabled == LVM_FALSE)||
       (pContext->pBundledContext->bMuteEnabled == LVM_TRUE)){
        *mute = pContext->pBundledContext->bMuteEnabled;
        return 0;
    }else{
        LOGV("\tLVM_ERROR : VolumeGetMute read an invalid value from context %d",
              pContext->pBundledContext->bMuteEnabled);
        return -EINVAL;
    }
    //LOGV("\tVolumeGetMute end");
}    /* end getMute */

int16_t VolumeConvertStereoPosition(int16_t position){
    int16_t convertedPosition = 0;

    convertedPosition = (int16_t)(((float)position/1000)*96);
    return convertedPosition;

}

//----------------------------------------------------------------------------
// VolumeSetStereoPosition()
//----------------------------------------------------------------------------
// Purpose:
//
// Inputs:
//  pContext:       effect engine context
//  position:       stereo position
//
// Outputs:
//----------------------------------------------------------------------------

int VolumeSetStereoPosition(EffectContext *pContext, int16_t position){

    LVM_ControlParams_t     ActiveParams;              /* Current control Parameters */
    LVM_ReturnStatus_en     LvmStatus=LVM_SUCCESS;     /* Function call status */
    LVM_INT16               Balance = 0;

    

    pContext->pBundledContext->positionSaved = position;
    Balance = VolumeConvertStereoPosition(pContext->pBundledContext->positionSaved);

    //LOGV("\tVolumeSetStereoPosition start pContext->pBundledContext->positionSaved = %d", pContext->pBundledContext->positionSaved);

    if(pContext->pBundledContext->bStereoPositionEnabled == LVM_TRUE){

        //LOGV("\tVolumeSetStereoPosition Position to be set is %d %d\n", position, Balance);
        pContext->pBundledContext->positionSaved = position;
        /* Get the current settings */
        LvmStatus = LVM_GetControlParameters(pContext->pBundledContext->hInstance, &ActiveParams);
        LVM_ERROR_CHECK(LvmStatus, "LVM_GetControlParameters", "VolumeSetStereoPosition")
        if(LvmStatus != LVM_SUCCESS) return -EINVAL;
        //LOGV("\tVolumeSetStereoPosition Succesfully returned from LVM_GetControlParameters got:"
        //     " %d\n", ActiveParams.VC_Balance);

        /* Volume parameters */
        ActiveParams.VC_Balance  = Balance;
        //LOGV("\tVolumeSetStereoPosition() (-96dB -> +96dB)   -> %d\n", ActiveParams.VC_Balance );

        /* Activate the initial settings */
        LvmStatus = LVM_SetControlParameters(pContext->pBundledContext->hInstance, &ActiveParams);
        LVM_ERROR_CHECK(LvmStatus, "LVM_SetControlParameters", "VolumeSetStereoPosition")
        if(LvmStatus != LVM_SUCCESS) return -EINVAL;

        //LOGV("\tVolumeSetStereoPosition Succesfully called LVM_SetControlParameters\n");

        /* Get the current settings */
        LvmStatus = LVM_GetControlParameters(pContext->pBundledContext->hInstance, &ActiveParams);
        LVM_ERROR_CHECK(LvmStatus, "LVM_GetControlParameters", "VolumeSetStereoPosition")
        if(LvmStatus != LVM_SUCCESS) return -EINVAL;
        //LOGV("\tVolumeSetStereoPosition Succesfully returned from LVM_GetControlParameters got: "
        //     "%d\n", ActiveParams.VC_Balance);
    }
    else{
        //LOGV("\tVolumeSetStereoPosition Position attempting to set, but not enabled %d %d\n",
        //position, Balance);
    }
    //LOGV("\tVolumeSetStereoPosition end pContext->pBundledContext->positionSaved = %d\n", pContext->pBundledContext->positionSaved);
    return 0;
}    /* end VolumeSetStereoPosition */


//----------------------------------------------------------------------------
// VolumeGetStereoPosition()
//----------------------------------------------------------------------------
// Purpose:
//
// Inputs:
//  pContext:       effect engine context
//
// Outputs:
//  position:       stereo position
//----------------------------------------------------------------------------

int32_t VolumeGetStereoPosition(EffectContext *pContext, int16_t *position){
    //LOGV("\tVolumeGetStereoPosition start");

    LVM_ControlParams_t     ActiveParams;                           /* Current control Parameters */
    LVM_ReturnStatus_en     LvmStatus = LVM_SUCCESS;                /* Function call status */
    LVM_INT16               balance;

    //LOGV("\tVolumeGetStereoPosition start pContext->pBundledContext->positionSaved = %d", pContext->pBundledContext->positionSaved);

    LvmStatus = LVM_GetControlParameters(pContext->pBundledContext->hInstance, &ActiveParams);
    LVM_ERROR_CHECK(LvmStatus, "LVM_GetControlParameters", "VolumeGetStereoPosition")
    if(LvmStatus != LVM_SUCCESS) return -EINVAL;

    //LOGV("\tVolumeGetStereoPosition -> %d\n", ActiveParams.VC_Balance);
    //LOGV("\tVolumeGetStereoPosition Succesfully returned from LVM_GetControlParameters\n");

    balance = VolumeConvertStereoPosition(pContext->pBundledContext->positionSaved);

    if(pContext->pBundledContext->bStereoPositionEnabled == LVM_TRUE){
        if(balance != ActiveParams.VC_Balance){
            return -EINVAL;
        }
    }
    *position = (LVM_INT16)pContext->pBundledContext->positionSaved;     // Convert dB to millibels
    //LOGV("\tVolumeGetStereoPosition end returning pContext->pBundledContext->positionSaved = %d\n", pContext->pBundledContext->positionSaved);
    return 0;
}    /* end VolumeGetStereoPosition */

//----------------------------------------------------------------------------
// VolumeEnableStereoPosition()
//----------------------------------------------------------------------------
// Purpose:
//
// Inputs:
//  pContext:   effect engine context
//  mute:       enable/disable flag
//
//----------------------------------------------------------------------------

int32_t VolumeEnableStereoPosition(EffectContext *pContext, uint32_t enabled){
    //LOGV("\tVolumeEnableStereoPosition start()");

    pContext->pBundledContext->bStereoPositionEnabled = enabled;

    LVM_ControlParams_t     ActiveParams;              /* Current control Parameters */
    LVM_ReturnStatus_en     LvmStatus=LVM_SUCCESS;     /* Function call status */

    /* Get the current settings */
    LvmStatus = LVM_GetControlParameters(pContext->pBundledContext->hInstance, &ActiveParams);
    LVM_ERROR_CHECK(LvmStatus, "LVM_GetControlParameters", "VolumeEnableStereoPosition")
    if(LvmStatus != LVM_SUCCESS) return -EINVAL;

    //LOGV("\tVolumeEnableStereoPosition Succesfully returned from LVM_GetControlParameters\n");
    //LOGV("\tVolumeEnableStereoPosition to %d, position was %d\n",
    //     enabled, ActiveParams.VC_Balance );

    /* Set appropriate stereo position */
    if(pContext->pBundledContext->bStereoPositionEnabled == LVM_FALSE){
        ActiveParams.VC_Balance = 0;
    }else{
        ActiveParams.VC_Balance  =
                            VolumeConvertStereoPosition(pContext->pBundledContext->positionSaved);
    }

    /* Activate the initial settings */
    LvmStatus = LVM_SetControlParameters(pContext->pBundledContext->hInstance, &ActiveParams);
    LVM_ERROR_CHECK(LvmStatus, "LVM_SetControlParameters", "VolumeEnableStereoPosition")
    if(LvmStatus != LVM_SUCCESS) return -EINVAL;

    //LOGV("\tVolumeEnableStereoPosition Succesfully called LVM_SetControlParameters\n");
    //LOGV("\tVolumeEnableStereoPosition end()\n");
    return 0;
}    /* end VolumeEnableStereoPosition */

//----------------------------------------------------------------------------
// BassBoost_getParameter()
//----------------------------------------------------------------------------
// Purpose:
// Get a BassBoost parameter
//
// Inputs:
//  pBassBoost       - handle to instance data
//  pParam           - pointer to parameter
//  pValue           - pointer to variable to hold retrieved value
//  pValueSize       - pointer to value size: maximum size as input
//
// Outputs:
//  *pValue updated with parameter value
//  *pValueSize updated with actual value size
//
//
// Side Effects:
//
//----------------------------------------------------------------------------

int BassBoost_getParameter(EffectContext     *pContext,
                           int32_t           *pParam,
                           size_t            *pValueSize,
                           void              *pValue){
    int status = 0;
    int32_t param = *pParam++;
    int32_t param2;
    char *name;

    //LOGV("\tBassBoost_getParameter start");

    switch (param){
        case BASSBOOST_PARAM_STRENGTH_SUP:
        case BASSBOOST_PARAM_STRENGTH:
            if (*pValueSize != sizeof(int16_t)){
                LOGV("\tLVM_ERROR : BassBoost_getParameter() invalid pValueSize2 %d", *pValueSize);
                return -EINVAL;
            }
            *pValueSize = sizeof(int16_t);
            break;

        default:
            LOGV("\tLVM_ERROR : BassBoost_getParameter() invalid param %d", param);
            return -EINVAL;
    }

    switch (param){
        case BASSBOOST_PARAM_STRENGTH_SUP:
            *(uint32_t *)pValue = 1;

            //LOGV("\tBassBoost_getParameter() BASSBOOST_PARAM_STRENGTH_SUP Value is %d",
            //        *(uint32_t *)pValue);
            break;

        case BASSBOOST_PARAM_STRENGTH:
            *(int16_t *)pValue = BassGetStrength(pContext);

            //LOGV("\tBassBoost_getParameter() BASSBOOST_PARAM_STRENGTH Value is %d",
            //        *(int16_t *)pValue);
            break;

        default:
            LOGV("\tLVM_ERROR : BassBoost_getParameter() invalid param %d", param);
            status = -EINVAL;
            break;
    }

    //LOGV("\tBassBoost_getParameter end");
    return status;
} /* end BassBoost_getParameter */

//----------------------------------------------------------------------------
// BassBoost_setParameter()
//----------------------------------------------------------------------------
// Purpose:
// Set a BassBoost parameter
//
// Inputs:
//  pBassBoost       - handle to instance data
//  pParam           - pointer to parameter
//  pValue           - pointer to value
//
// Outputs:
//
//----------------------------------------------------------------------------

int BassBoost_setParameter (EffectContext *pContext, int32_t *pParam, void *pValue){
    int status = 0;
    int16_t strength;

    //LOGV("\tBassBoost_setParameter start");

    switch (*pParam){
        case BASSBOOST_PARAM_STRENGTH:
            strength = *(int16_t *)pValue;
            //LOGV("\tBassBoost_setParameter() BASSBOOST_PARAM_STRENGTH value is %d", strength);
            //LOGV("\tBassBoost_setParameter() Calling pBassBoost->BassSetStrength");
            BassSetStrength(pContext, (int32_t)strength);
            //LOGV("\tBassBoost_setParameter() Called pBassBoost->BassSetStrength");
           break;
        default:
            LOGV("\tLVM_ERROR : BassBoost_setParameter() invalid param %d", *pParam);
            break;
    }

    //LOGV("\tBassBoost_setParameter end");
    return status;
} /* end BassBoost_setParameter */

//----------------------------------------------------------------------------
// Virtualizer_getParameter()
//----------------------------------------------------------------------------
// Purpose:
// Get a Virtualizer parameter
//
// Inputs:
//  pVirtualizer     - handle to instance data
//  pParam           - pointer to parameter
//  pValue           - pointer to variable to hold retrieved value
//  pValueSize       - pointer to value size: maximum size as input
//
// Outputs:
//  *pValue updated with parameter value
//  *pValueSize updated with actual value size
//
//
// Side Effects:
//
//----------------------------------------------------------------------------

int Virtualizer_getParameter(EffectContext        *pContext,
                             int32_t              *pParam,
                             size_t               *pValueSize,
                             void                 *pValue){
    int status = 0;
    int32_t param = *pParam++;
    int32_t param2;
    char *name;

    //LOGV("\tVirtualizer_getParameter start");

    switch (param){
        case VIRTUALIZER_PARAM_STRENGTH_SUP:
        case VIRTUALIZER_PARAM_STRENGTH:
            if (*pValueSize != sizeof(int16_t)){
                LOGV("\tLVM_ERROR : Virtualizer_getParameter() invalid pValueSize2 %d",*pValueSize);
                return -EINVAL;
            }
            *pValueSize = sizeof(int16_t);
            break;

        default:
            LOGV("\tLVM_ERROR : Virtualizer_getParameter() invalid param %d", param);
            return -EINVAL;
    }

    switch (param){
        case VIRTUALIZER_PARAM_STRENGTH_SUP:
            *(uint32_t *)pValue = 1;

            //LOGV("\tVirtualizer_getParameter() VIRTUALIZER_PARAM_STRENGTH_SUP Value is %d",
            //        *(uint32_t *)pValue);
            break;

        case VIRTUALIZER_PARAM_STRENGTH:
            *(int16_t *)pValue = VirtualizerGetStrength(pContext);

            //LOGV("\tVirtualizer_getParameter() VIRTUALIZER_PARAM_STRENGTH Value is %d",
            //        *(int16_t *)pValue);
            break;

        default:
            LOGV("\tLVM_ERROR : Virtualizer_getParameter() invalid param %d", param);
            status = -EINVAL;
            break;
    }

    //LOGV("\tVirtualizer_getParameter end");
    return status;
} /* end Virtualizer_getParameter */

//----------------------------------------------------------------------------
// Virtualizer_setParameter()
//----------------------------------------------------------------------------
// Purpose:
// Set a Virtualizer parameter
//
// Inputs:
//  pVirtualizer     - handle to instance data
//  pParam           - pointer to parameter
//  pValue           - pointer to value
//
// Outputs:
//
//----------------------------------------------------------------------------

int Virtualizer_setParameter (EffectContext *pContext, int32_t *pParam, void *pValue){
    int status = 0;
    int16_t strength;

    //LOGV("\tVirtualizer_setParameter start");

    switch (*pParam){
        case VIRTUALIZER_PARAM_STRENGTH:
            strength = *(int16_t *)pValue;
            //LOGV("\tVirtualizer_setParameter() VIRTUALIZER_PARAM_STRENGTH value is %d", strength);
            //LOGV("\tVirtualizer_setParameter() Calling pVirtualizer->setStrength");
            VirtualizerSetStrength(pContext, (int32_t)strength);
            //LOGV("\tVirtualizer_setParameter() Called pVirtualizer->setStrength");
           break;
        default:
            LOGV("\tLVM_ERROR : Virtualizer_setParameter() invalid param %d", *pParam);
            break;
    }

    //LOGV("\tVirtualizer_setParameter end");
    return status;
} /* end Virtualizer_setParameter */

//----------------------------------------------------------------------------
// Equalizer_getParameter()
//----------------------------------------------------------------------------
// Purpose:
// Get a Equalizer parameter
//
// Inputs:
//  pEqualizer       - handle to instance data
//  pParam           - pointer to parameter
//  pValue           - pointer to variable to hold retrieved value
//  pValueSize       - pointer to value size: maximum size as input
//
// Outputs:
//  *pValue updated with parameter value
//  *pValueSize updated with actual value size
//
//
// Side Effects:
//
//----------------------------------------------------------------------------
int Equalizer_getParameter(EffectContext     *pContext,
                           int32_t           *pParam,
                           size_t            *pValueSize,
                           void              *pValue){
    int status = 0;
    int bMute = 0;
    int32_t param = *pParam++;
    int32_t param2;
    char *name;

    //LOGV("\tEqualizer_getParameter start");

    switch (param) {
    case EQ_PARAM_NUM_BANDS:
    case EQ_PARAM_CUR_PRESET:
    case EQ_PARAM_GET_NUM_OF_PRESETS:
        if (*pValueSize < sizeof(int16_t)) {
            LOGV("\tLVM_ERROR : Equalizer_getParameter() invalid pValueSize 1  %d", *pValueSize);
            return -EINVAL;
        }
        *pValueSize = sizeof(int16_t);
        break;

    case EQ_PARAM_LEVEL_RANGE:
    case EQ_PARAM_BAND_FREQ_RANGE:
        if (*pValueSize < 2 * sizeof(int32_t)) {
            LOGV("\tLVM_ERROR : Equalizer_getParameter() invalid pValueSize 2  %d", *pValueSize);
            return -EINVAL;
        }
        *pValueSize = 2 * sizeof(int32_t);
        break;
    case EQ_PARAM_BAND_LEVEL:
    case EQ_PARAM_GET_BAND:
    case EQ_PARAM_CENTER_FREQ:
        if (*pValueSize < sizeof(int32_t)) {
            LOGV("\tLVM_ERROR : Equalizer_getParameter() invalid pValueSize 1  %d", *pValueSize);
            return -EINVAL;
        }
        *pValueSize = sizeof(int32_t);
        break;

    case EQ_PARAM_GET_PRESET_NAME:
        break;

    default:
        LOGV("\tLVM_ERROR : Equalizer_getParameter unknown param %d", param);
        return -EINVAL;
    }

    switch (param) {
    case EQ_PARAM_NUM_BANDS:
        *(int16_t *)pValue = FIVEBAND_NUMBANDS;
        //LOGV("\tEqualizer_getParameter() EQ_PARAM_NUM_BANDS %d", *(int16_t *)pValue);
        break;

    case EQ_PARAM_LEVEL_RANGE:
        *(int32_t *)pValue = -1500;
        *((int32_t *)pValue + 1) = 1500;
        //LOGV("\tEqualizer_getParameter() EQ_PARAM_LEVEL_RANGE min %d, max %d",
        //      *(int32_t *)pValue, *((int32_t *)pValue + 1));
        break;

    case EQ_PARAM_BAND_LEVEL:
        param2 = *pParam;
        if (param2 >= FIVEBAND_NUMBANDS) {
            status = -EINVAL;
            break;
        }
        *(int32_t *)pValue = EqualizerGetBandLevel(pContext, param2);
        //LOGV("\tEqualizer_getParameter() EQ_PARAM_BAND_LEVEL band %d, level %d",
        //      param2, *(int32_t *)pValue);
        break;

    case EQ_PARAM_CENTER_FREQ:
        param2 = *pParam;
        if (param2 >= FIVEBAND_NUMBANDS) {
            status = -EINVAL;
            break;
        }
        *(int32_t *)pValue = EqualizerGetCentreFrequency(pContext, param2);
        //LOGV("\tEqualizer_getParameter() EQ_PARAM_CENTER_FREQ band %d, frequency %d",
        //      param2, *(int32_t *)pValue);
        break;

    case EQ_PARAM_BAND_FREQ_RANGE:
        param2 = *pParam;
        if (param2 >= FIVEBAND_NUMBANDS) {
            status = -EINVAL;
            break;
        }
        EqualizerGetBandFreqRange(pContext, param2, (uint32_t *)pValue, ((uint32_t *)pValue + 1));
        //LOGV("\tEqualizer_getParameter() EQ_PARAM_BAND_FREQ_RANGE band %d, min %d, max %d",
        //      param2, *(int32_t *)pValue, *((int32_t *)pValue + 1));
        break;

    case EQ_PARAM_GET_BAND:
        param2 = *pParam;
        *(int32_t *)pValue = EqualizerGetBand(pContext, param2);
        //LOGV("\tEqualizer_getParameter() EQ_PARAM_GET_BAND frequency %d, band %d",
        //      param2, *(int32_t *)pValue);
        break;

    case EQ_PARAM_CUR_PRESET:
        *(int16_t *)pValue = EqualizerGetPreset(pContext);
        //LOGV("\tEqualizer_getParameter() EQ_PARAM_CUR_PRESET %d", *(int32_t *)pValue);
        break;

    case EQ_PARAM_GET_NUM_OF_PRESETS:
        *(int16_t *)pValue = EqualizerGetNumPresets();
        //LOGV("\tEqualizer_getParameter() EQ_PARAM_GET_NUM_OF_PRESETS %d", *(int16_t *)pValue);
        break;

    case EQ_PARAM_GET_PRESET_NAME:
        param2 = *pParam;
        if (param2 >= EqualizerGetNumPresets()) {
        //if (param2 >= 20) {     // AGO FIX
            status = -EINVAL;
            break;
        }
        name = (char *)pValue;
        strncpy(name, EqualizerGetPresetName(param2), *pValueSize - 1);
        name[*pValueSize - 1] = 0;
        *pValueSize = strlen(name) + 1;
        //LOGV("\tEqualizer_getParameter() EQ_PARAM_GET_PRESET_NAME preset %d, name %s len %d",
        //      param2, gEqualizerPresets[param2].name, *pValueSize);
        break;

    default:
        LOGV("\tLVM_ERROR : Equalizer_getParameter() invalid param %d", param);
        status = -EINVAL;
        break;
    }

    //LOGV("\tEqualizer_getParameter end");
    return status;
} /* end Equalizer_getParameter */

//----------------------------------------------------------------------------
// Equalizer_setParameter()
//----------------------------------------------------------------------------
// Purpose:
// Set a Equalizer parameter
//
// Inputs:
//  pEqualizer    - handle to instance data
//  pParam        - pointer to parameter
//  pValue        - pointer to value
//
// Outputs:
//
//----------------------------------------------------------------------------
int Equalizer_setParameter (EffectContext *pContext, int32_t *pParam, void *pValue){
    int status = 0;
    int32_t preset;
    int32_t band;
    int32_t level;
    int32_t param = *pParam++;

    //LOGV("\tEqualizer_setParameter start");
    switch (param) {
    case EQ_PARAM_CUR_PRESET:
        preset = *(int16_t *)pValue;

        //LOGV("\tEqualizer_setParameter() EQ_PARAM_CUR_PRESET %d", preset);
        if ((preset >= EqualizerGetNumPresets())||(preset < 0)) {
            status = -EINVAL;
            break;
        }
        EqualizerSetPreset(pContext, preset);
        break;
    case EQ_PARAM_BAND_LEVEL:
        band =  *pParam;
        level = *(int32_t *)pValue;
        //LOGV("\tEqualizer_setParameter() EQ_PARAM_BAND_LEVEL band %d, level %d", band, level);
        if (band >= FIVEBAND_NUMBANDS) {
            status = -EINVAL;
            break;
        }
        EqualizerSetBandLevel(pContext, band, level);
        break;
    default:
        LOGV("\tLVM_ERROR : setParameter() invalid param %d", param);
        break;
    }

    //LOGV("\tEqualizer_setParameter end");
    return status;
} /* end Equalizer_setParameter */

//----------------------------------------------------------------------------
// Volume_getParameter()
//----------------------------------------------------------------------------
// Purpose:
// Get a Volume parameter
//
// Inputs:
//  pVolume          - handle to instance data
//  pParam           - pointer to parameter
//  pValue           - pointer to variable to hold retrieved value
//  pValueSize       - pointer to value size: maximum size as input
//
// Outputs:
//  *pValue updated with parameter value
//  *pValueSize updated with actual value size
//
//
// Side Effects:
//
//----------------------------------------------------------------------------

int Volume_getParameter(EffectContext     *pContext,
                        int32_t           *pParam,
                        size_t            *pValueSize,
                        void              *pValue){
    int status = 0;
    int bMute = 0;
    int32_t param = *pParam++;
    int32_t param2;
    char *name;

    LOGV("\tVolume_getParameter start");

    switch (param){
        case VOLUME_PARAM_LEVEL:
        case VOLUME_PARAM_MAXLEVEL:
        case VOLUME_PARAM_STEREOPOSITION:
            if (*pValueSize != sizeof(int16_t)){
                LOGV("\tLVM_ERROR : Volume_getParameter() invalid pValueSize 1  %d", *pValueSize);
                return -EINVAL;
            }
            *pValueSize = sizeof(int16_t);
            break;

        case VOLUME_PARAM_MUTE:
        case VOLUME_PARAM_ENABLESTEREOPOSITION:
            if (*pValueSize < sizeof(int32_t)){
                LOGV("\tLVM_ERROR : Volume_getParameter() invalid pValueSize 2  %d", *pValueSize);
                return -EINVAL;
            }
            *pValueSize = sizeof(int32_t);
            break;

        default:
            LOGV("\tLVM_ERROR : Volume_getParameter unknown param %d", param);
            return -EINVAL;
    }

    switch (param){
        case VOLUME_PARAM_LEVEL:
            status = VolumeGetVolumeLevel(pContext, (int16_t *)(pValue));
            LOGV("\tVolume_getParameter() VOLUME_PARAM_LEVEL Value is %d",
                    *(int16_t *)pValue);
            break;

        case VOLUME_PARAM_MAXLEVEL:
            *(int16_t *)pValue = 0;
            LOGV("\tVolume_getParameter() VOLUME_PARAM_MAXLEVEL Value is %d",
                    *(int16_t *)pValue);
            break;

        case VOLUME_PARAM_STEREOPOSITION:
            VolumeGetStereoPosition(pContext, (int16_t *)pValue);
            LOGV("\tVolume_getParameter() VOLUME_PARAM_STEREOPOSITION Value is %d",
                    *(int16_t *)pValue);
            break;

        case VOLUME_PARAM_MUTE:
            status = VolumeGetMute(pContext, (uint32_t *)pValue);
            LOGV("\tVolume_getParameter() VOLUME_PARAM_MUTE Value is %d",
                    *(uint32_t *)pValue);
            break;

        case VOLUME_PARAM_ENABLESTEREOPOSITION:
            *(int32_t *)pValue = pContext->pBundledContext->bStereoPositionEnabled;
            LOGV("\tVolume_getParameter() VOLUME_PARAM_ENABLESTEREOPOSITION Value is %d",
                    *(uint32_t *)pValue);
            break;

        default:
            LOGV("\tLVM_ERROR : Volume_getParameter() invalid param %d", param);
            status = -EINVAL;
            break;
    }

    //LOGV("\tVolume_getParameter end");
    return status;
} /* end Volume_getParameter */


//----------------------------------------------------------------------------
// Volume_setParameter()
//----------------------------------------------------------------------------
// Purpose:
// Set a Volume parameter
//
// Inputs:
//  pVolume       - handle to instance data
//  pParam        - pointer to parameter
//  pValue        - pointer to value
//
// Outputs:
//
//----------------------------------------------------------------------------

int Volume_setParameter (EffectContext *pContext, int32_t *pParam, void *pValue){
    int      status = 0;
    int16_t  level;
    int16_t  position;
    uint32_t mute;
    uint32_t positionEnabled;

    LOGV("\tVolume_setParameter start");

    switch (*pParam){
        case VOLUME_PARAM_LEVEL:
            level = *(int16_t *)pValue;
            LOGV("\tVolume_setParameter() VOLUME_PARAM_LEVEL value is %d", level);
            LOGV("\tVolume_setParameter() Calling pVolume->setVolumeLevel");
            status = VolumeSetVolumeLevel(pContext, (int16_t)level);
            LOGV("\tVolume_setParameter() Called pVolume->setVolumeLevel");
            break;

        case VOLUME_PARAM_MUTE:
            mute = *(uint32_t *)pValue;
            LOGV("\tVolume_setParameter() Calling pVolume->setMute, mute is %d", mute);
            LOGV("\tVolume_setParameter() Calling pVolume->setMute");
            status = VolumeSetMute(pContext, mute);
            LOGV("\tVolume_setParameter() Called pVolume->setMute");
            break;

        case VOLUME_PARAM_ENABLESTEREOPOSITION:
            positionEnabled = *(uint32_t *)pValue;
            status = VolumeEnableStereoPosition(pContext, positionEnabled);
            status = VolumeSetStereoPosition(pContext, pContext->pBundledContext->positionSaved);
            LOGV("\tVolume_setParameter() VOLUME_PARAM_ENABLESTEREOPOSITION called");
            break;

        case VOLUME_PARAM_STEREOPOSITION:
            position = *(int16_t *)pValue;
            LOGV("\tVolume_setParameter() VOLUME_PARAM_STEREOPOSITION value is %d", position);
            LOGV("\tVolume_setParameter() Calling pVolume->VolumeSetStereoPosition");
            status = VolumeSetStereoPosition(pContext, (int16_t)position);
            LOGV("\tVolume_setParameter() Called pVolume->VolumeSetStereoPosition");
            break;

        default:
            LOGV("\tLVM_ERROR : Volume_setParameter() invalid param %d", *pParam);
            break;
    }

    //LOGV("\tVolume_setParameter end");
    return status;
} /* end Volume_setParameter */

/****************************************************************************************
 * Name : LVC_ToDB_s32Tos16()
 *  Input       : Signed 32-bit integer
 *  Output      : Signed 16-bit integer
 *                  MSB (16) = sign bit
 *                  (15->05) = integer part
 *                  (04->01) = decimal part
 *  Returns     : Db value with respect to full scale
 *  Description :
 *  Remarks     :
 ****************************************************************************************/

LVM_INT16 LVC_ToDB_s32Tos16(LVM_INT32 Lin_fix)
{
    LVM_INT16   db_fix;
    LVM_INT16   Shift;
    LVM_INT16   SmallRemainder;
    LVM_UINT32  Remainder = (LVM_UINT32)Lin_fix;

    /* Count leading bits, 1 cycle in assembly*/
    for (Shift = 0; Shift<32; Shift++)
    {
        if ((Remainder & 0x80000000U)!=0)
        {
            break;
        }
        Remainder = Remainder << 1;
    }

    /*
     * Based on the approximation equation (for Q11.4 format):
     *
     * dB = -96 * Shift + 16 * (8 * Remainder - 2 * Remainder^2)
     */
    db_fix    = (LVM_INT16)(-96 * Shift);               /* Six dB steps in Q11.4 format*/
    SmallRemainder = (LVM_INT16)((Remainder & 0x7fffffff) >> 24);
    db_fix = (LVM_INT16)(db_fix + SmallRemainder );
    SmallRemainder = (LVM_INT16)(SmallRemainder * SmallRemainder);
    db_fix = (LVM_INT16)(db_fix - (LVM_INT16)((LVM_UINT16)SmallRemainder >> 9));

    /* Correct for small offset */
    db_fix = (LVM_INT16)(db_fix - 5);

    return db_fix;
}

} // namespace
} // namespace

/* Effect Control Interface Implementation: Process */
extern "C" int Effect_process(effect_interface_t     self,
                              audio_buffer_t         *inBuffer,
                              audio_buffer_t         *outBuffer){
    EffectContext * pContext = (EffectContext *) self;
    int    status = 0;
    int    lvmStatus = 0;
    LVM_INT16   *in  = (LVM_INT16 *)inBuffer->raw;
    LVM_INT16   *out = (LVM_INT16 *)outBuffer->raw;

    //LOGV("\tEffect_process Start : Enabled = %d     Called = %d",
    //pContext->pBundledContext->NumberEffectsEnabled,pContext->pBundledContext->NumberEffectsCalled);

    if (pContext == NULL){
        LOGV("\tLVM_ERROR : Effect_process() ERROR pContext == NULL");
        return -EINVAL;
    }
    if (inBuffer == NULL  || inBuffer->raw == NULL  ||
            outBuffer == NULL || outBuffer->raw == NULL ||
            inBuffer->frameCount != outBuffer->frameCount){
        LOGV("\tLVM_ERROR : Effect_process() ERROR NULL INPUT POINTER OR FRAME COUNT IS WRONG");
        return -EINVAL;
    }
    if ((pContext->pBundledContext->bBassEnabled == LVM_FALSE)&&
        (pContext->EffectType == LVM_BASS_BOOST)){
        LOGV("\tEffect_process() ERROR LVM_BASS_BOOST Effect is not enabled");
        status = -ENODATA;
    }
    if ((pContext->pBundledContext->bVolumeEnabled == LVM_FALSE)&&
        (pContext->EffectType == LVM_VOLUME)){
        LOGV("\tEffect_process() ERROR LVM_VOLUME Effect is not enabled");
        status = -ENODATA;
    }
    if ((pContext->pBundledContext->bEqualizerEnabled == LVM_FALSE)&&
        (pContext->EffectType == LVM_EQUALIZER)){
        LOGV("\tEffect_process() ERROR LVM_EQUALIZER Effect is not enabled");
        status = -ENODATA;
    }
    if ((pContext->pBundledContext->bVirtualizerEnabled == LVM_FALSE)&&
        (pContext->EffectType == LVM_VIRTUALIZER)){
        LOGV("\tEffect_process() ERROR LVM_VIRTUALIZER Effect is not enabled");
        status = -ENODATA;
    }

    // If this is the last frame of an effect process its output with no effect
    if(status == -ENODATA){
        if (pContext->config.outputCfg.accessMode == EFFECT_BUFFER_ACCESS_ACCUMULATE){
            //LOGV("\tLVM_ERROR : Effect_process() accumulating last frame into output buffer");
            //LOGV("\tLVM_ERROR : Effect_process() trying copying last frame into output buffer");
            //LOGV("\tLVM_ERROR : Enabled = %d     Called = %d",
            //pContext->pBundledContext->NumberEffectsEnabled,
            //pContext->pBundledContext->NumberEffectsCalled);

        }else{
            //LOGV("\tLVM_ERROR : Effect_process() copying last frame into output buffer");
        }
    }

    if(status != -ENODATA){
        pContext->pBundledContext->NumberEffectsCalled++;
    }

    if(pContext->pBundledContext->NumberEffectsCalled ==
       pContext->pBundledContext->NumberEffectsEnabled){
        //LOGV("\tEffect_process Calling process with %d effects enabled, %d called: Effect %d",
        //pContext->pBundledContext->NumberEffectsEnabled,
        //pContext->pBundledContext->NumberEffectsCalled, pContext->EffectType);

        if(status == -ENODATA){
            //LOGV("\tLVM_ERROR : Effect_process() actually processing last frame");
        }
        pContext->pBundledContext->NumberEffectsCalled = 0;
        /* Process all the available frames, block processing is
           handled internalLY by the LVM bundle */
        lvmStatus = android::LvmBundle_process(    (LVM_INT16 *)inBuffer->raw,
                                                (LVM_INT16 *)outBuffer->raw,
                                                outBuffer->frameCount,
                                                pContext);
        if(lvmStatus != LVM_SUCCESS){
            LOGV("\tLVM_ERROR : LvmBundle_process returned error %d", lvmStatus);
            return lvmStatus;
        }
    }else{
        //LOGV("\tEffect_process Not Calling process with %d effects enabled, %d called: Effect %d",
        //pContext->pBundledContext->NumberEffectsEnabled,
        //pContext->pBundledContext->NumberEffectsCalled, pContext->EffectType);
        // 2 is for stereo input
        memcpy(outBuffer->raw, inBuffer->raw, outBuffer->frameCount*sizeof(LVM_INT16)*2);
    }

    return status;
}   /* end Effect_process */

/* Effect Control Interface Implementation: Command */
extern "C" int Effect_command(effect_interface_t  self,
                              int                 cmdCode,
                              int                 cmdSize,
                              void                *pCmdData,
                              int                 *replySize,
                              void                *pReplyData){
    EffectContext * pContext = (EffectContext *) self;
    int retsize;

    //LOGV("\t\nEffect_command start");

    if(pContext->EffectType == LVM_BASS_BOOST){
        //LOGV("\tEffect_command setting command for LVM_BASS_BOOST");
    }
    if(pContext->EffectType == LVM_VIRTUALIZER){
        //LOGV("\tEffect_command setting command for LVM_VIRTUALIZER");
    }
    if(pContext->EffectType == LVM_EQUALIZER){
        //LOGV("\tEffect_command setting command for LVM_EQUALIZER");
    }
    if(pContext->EffectType == LVM_VOLUME){
        //LOGV("\tEffect_command setting command for LVM_VOLUME");
    }

    if (pContext == NULL){
        LOGV("\tLVM_ERROR : Effect_command ERROR pContext == NULL");
        return -EINVAL;
    }

    //LOGV("\tEffect_command INPUTS are: command %d cmdSize %d",cmdCode, cmdSize);

    // Incase we disable an effect, next time process is
    // called the number of effect called could be greater
    // pContext->pBundledContext->NumberEffectsCalled = 0;

    //LOGV("\tEffect_command NumberEffectsCalled = %d, NumberEffectsEnabled = %d",
    //        pContext->pBundledContext->NumberEffectsCalled,
    //        pContext->pBundledContext->NumberEffectsEnabled);

    switch (cmdCode){
        case EFFECT_CMD_INIT:
            if (pReplyData == NULL || *replySize != sizeof(int)){
                LOGV("\tLVM_ERROR, EFFECT_CMD_INIT: ERROR for effect type %d",
                        pContext->EffectType);
                return -EINVAL;
            }
            *(int *) pReplyData = 0;
            //LOGV("\tEffect_command cmdCode Case: EFFECT_CMD_INIT start");
            if(pContext->EffectType == LVM_BASS_BOOST){
                //LOGV("\tEffect_command cmdCode Case: EFFECT_CMD_INIT for LVM_BASS_BOOST");
                android::BassSetStrength(pContext, 0);
            }
            if(pContext->EffectType == LVM_VIRTUALIZER){
                //LOGV("\tEffect_command cmdCode Case: EFFECT_CMD_INIT for LVM_VIRTUALIZER");
                android::VirtualizerSetStrength(pContext, 0);
            }
            if(pContext->EffectType == LVM_EQUALIZER){
                //LOGV("\tEffect_command cmdCode Case: EFFECT_CMD_INIT for LVM_EQUALIZER");
                android::EqualizerSetPreset(pContext, 0);
            }
            if(pContext->EffectType == LVM_VOLUME){
                //LOGV("\tEffect_command cmdCode Case: "
                //        "EFFECT_CMD_INIT start");
                *(int *) pReplyData = android::VolumeSetVolumeLevel(pContext, 0);
            }
            break;

        case EFFECT_CMD_CONFIGURE:
            //LOGV("\tEffect_command cmdCode Case: EFFECT_CMD_CONFIGURE start");
            if (pCmdData    == NULL||
                cmdSize     != sizeof(effect_config_t)||
                pReplyData  == NULL||
                *replySize  != sizeof(int)){
                LOGV("\tLVM_ERROR : Effect_command cmdCode Case: "
                        "EFFECT_CMD_CONFIGURE: ERROR");
                return -EINVAL;
            }
            *(int *) pReplyData = android::Effect_configure(pContext, (effect_config_t *) pCmdData);
            //LOGV("\tEffect_command cmdCode Case: EFFECT_CMD_CONFIGURE end");
            break;

        case EFFECT_CMD_RESET:
            //LOGV("\tEffect_command cmdCode Case: EFFECT_CMD_RESET start");
            android::Effect_configure(pContext, &pContext->config);
            //LOGV("\tEffect_command cmdCode Case: EFFECT_CMD_RESET end");
            break;

        case EFFECT_CMD_GET_PARAM:{
            //LOGV("\tEffect_command cmdCode Case: EFFECT_CMD_GET_PARAM start");

            if(pContext->EffectType == LVM_BASS_BOOST){
                if (pCmdData == NULL ||
                        cmdSize < (int)(sizeof(effect_param_t) + sizeof(int32_t)) ||
                        pReplyData == NULL ||
                        *replySize < (int) (sizeof(effect_param_t) + sizeof(int32_t))){
                    LOGV("\tLVM_ERROR : BassBoost_command cmdCode Case: "
                            "EFFECT_CMD_GET_PARAM: ERROR");
                    return -EINVAL;
                }
                effect_param_t *p = (effect_param_t *)pCmdData;

                memcpy(pReplyData, pCmdData, sizeof(effect_param_t) + p->psize);

                p = (effect_param_t *)pReplyData;

                int voffset = ((p->psize - 1) / sizeof(int32_t) + 1) * sizeof(int32_t);

                p->status = android::BassBoost_getParameter(pContext,
                                                            (int32_t *)p->data,
                                                            (size_t  *)&p->vsize,
                                                            p->data + voffset);

                *replySize = sizeof(effect_param_t) + voffset + p->vsize;

                //LOGV("\tBassBoost_command EFFECT_CMD_GET_PARAM "
                //        "*pCmdData %d, *replySize %d, *pReplyData %d ",
                //        *(int32_t *)((char *)pCmdData + sizeof(effect_param_t)),
                //        *replySize,
                //        *(int16_t *)((char *)pReplyData + sizeof(effect_param_t) + voffset));
            }

            if(pContext->EffectType == LVM_VIRTUALIZER){
                if (pCmdData == NULL ||
                        cmdSize < (int)(sizeof(effect_param_t) + sizeof(int32_t)) ||
                        pReplyData == NULL ||
                        *replySize < (int) (sizeof(effect_param_t) + sizeof(int32_t))){
                    LOGV("\tLVM_ERROR : Virtualizer_command cmdCode Case: "
                            "EFFECT_CMD_GET_PARAM: ERROR");
                    return -EINVAL;
                }
                effect_param_t *p = (effect_param_t *)pCmdData;

                memcpy(pReplyData, pCmdData, sizeof(effect_param_t) + p->psize);

                p = (effect_param_t *)pReplyData;

                int voffset = ((p->psize - 1) / sizeof(int32_t) + 1) * sizeof(int32_t);

                p->status = android::Virtualizer_getParameter(pContext,
                                                             (int32_t *)p->data,
                                                             (size_t  *)&p->vsize,
                                                              p->data + voffset);

                *replySize = sizeof(effect_param_t) + voffset + p->vsize;

                //LOGV("\tVirtualizer_command EFFECT_CMD_GET_PARAM "
                //        "*pCmdData %d, *replySize %d, *pReplyData %d ",
                //        *(int32_t *)((char *)pCmdData + sizeof(effect_param_t)),
                //        *replySize,
                //        *(int16_t *)((char *)pReplyData + sizeof(effect_param_t) + voffset));
            }
            if(pContext->EffectType == LVM_EQUALIZER){
                //LOGV("\tEqualizer_command cmdCode Case: "
                //        "EFFECT_CMD_GET_PARAM start");
                if (pCmdData == NULL ||
                    cmdSize < (int)(sizeof(effect_param_t) + sizeof(int32_t)) ||
                    pReplyData == NULL ||
                    *replySize < (int) (sizeof(effect_param_t) + sizeof(int32_t))) {
                    LOGV("\tLVM_ERROR : Equalizer_command cmdCode Case: "
                            "EFFECT_CMD_GET_PARAM");
                    return -EINVAL;
                }
                effect_param_t *p = (effect_param_t *)pCmdData;

                memcpy(pReplyData, pCmdData, sizeof(effect_param_t) + p->psize);

                p = (effect_param_t *)pReplyData;

                int voffset = ((p->psize - 1) / sizeof(int32_t) + 1) * sizeof(int32_t);

                p->status = android::Equalizer_getParameter(pContext, (int32_t *)p->data, &p->vsize,
                        p->data + voffset);

                *replySize = sizeof(effect_param_t) + voffset + p->vsize;

                //LOGV("\tEqualizer_command EFFECT_CMD_GET_PARAM *pCmdData %d, *replySize %d, "
                //       "*pReplyData %08x %08x",
                //        *(int32_t *)((char *)pCmdData + sizeof(effect_param_t)), *replySize,
                //        *(int32_t *)((char *)pReplyData + sizeof(effect_param_t) + voffset),
                //        *(int32_t *)((char *)pReplyData + sizeof(effect_param_t) + voffset +
                //        sizeof(int32_t)));
            }
            if(pContext->EffectType == LVM_VOLUME){
                //LOGV("\tVolume_command cmdCode Case: "
                //        "EFFECT_CMD_GET_PARAM start");
                if (pCmdData == NULL ||
                        cmdSize < (int)(sizeof(effect_param_t) + sizeof(int32_t)) ||
                        pReplyData == NULL ||
                        *replySize < (int) (sizeof(effect_param_t) + sizeof(int32_t))){
                    LOGV("\tLVM_ERROR : Volume_command cmdCode Case: "
                            "EFFECT_CMD_GET_PARAM: ERROR");
                    return -EINVAL;
                }
                effect_param_t *p = (effect_param_t *)pCmdData;

                memcpy(pReplyData, pCmdData, sizeof(effect_param_t) + p->psize);

                p = (effect_param_t *)pReplyData;

                int voffset = ((p->psize - 1) / sizeof(int32_t) + 1) * sizeof(int32_t);

                p->status = android::Volume_getParameter(pContext,
                                                         (int32_t *)p->data,
                                                         (size_t  *)&p->vsize,
                                                         p->data + voffset);

                *replySize = sizeof(effect_param_t) + voffset + p->vsize;

                //LOGV("\tVolume_command EFFECT_CMD_GET_PARAM "
                //        "*pCmdData %d, *replySize %d, *pReplyData %d ",
                //        *(int32_t *)((char *)pCmdData + sizeof(effect_param_t)),
                //        *replySize,
                //        *(int16_t *)((char *)pReplyData + sizeof(effect_param_t) + voffset));
            }
            //LOGV("\tEffect_command cmdCode Case: EFFECT_CMD_GET_PARAM end");
        } break;
        case EFFECT_CMD_SET_PARAM:{
            //LOGV("\tEffect_command cmdCode Case: EFFECT_CMD_SET_PARAM start");
            if(pContext->EffectType == LVM_BASS_BOOST){
                //LOGV("\tBassBoost_command EFFECT_CMD_SET_PARAM param %d, *replySize %d, value %d ",
                //        *(int32_t *)((char *)pCmdData + sizeof(effect_param_t)),
                //        *replySize,
                //        *(int16_t *)((char *)pCmdData + sizeof(effect_param_t) + sizeof(int32_t)));

                if (pCmdData   == NULL||
                    cmdSize    != (int)(sizeof(effect_param_t) + sizeof(int32_t) +sizeof(int16_t))||
                    pReplyData == NULL||
                    *replySize != sizeof(int32_t)){
                    LOGV("\tLVM_ERROR : BassBoost_command cmdCode Case: "
                            "EFFECT_CMD_SET_PARAM: ERROR");
                    return -EINVAL;
                }
                effect_param_t *p = (effect_param_t *) pCmdData;

                if (p->psize != sizeof(int32_t)){
                    LOGV("\tLVM_ERROR : BassBoost_command cmdCode Case: "
                            "EFFECT_CMD_SET_PARAM: ERROR, psize is not sizeof(int32_t)");
                    return -EINVAL;
                }

                //LOGV("\tnBassBoost_command cmdSize is %d\n"
                //        "\tsizeof(effect_param_t) is  %d\n"
                //        "\tp->psize is %d\n"
                //        "\tp->vsize is %d"
                //        "\n",
                //        cmdSize, sizeof(effect_param_t), p->psize, p->vsize );

                *(int *)pReplyData = android::BassBoost_setParameter(pContext,
                                                                    (int32_t *)p->data,
                                                                    p->data + p->psize);
            }
            if(pContext->EffectType == LVM_VIRTUALIZER){
                //LOGV("\tVirtualizer_command EFFECT_CMD_SET_PARAM param %d, *replySize %d, value %d",
                //        *(int32_t *)((char *)pCmdData + sizeof(effect_param_t)),
                //        *replySize,
                //        *(int16_t *)((char *)pCmdData + sizeof(effect_param_t) + sizeof(int32_t)));

                if (pCmdData   == NULL||
                    cmdSize    != (int)(sizeof(effect_param_t) + sizeof(int32_t) +sizeof(int16_t))||
                    pReplyData == NULL||
                    *replySize != sizeof(int32_t)){
                    LOGV("\tLVM_ERROR : Virtualizer_command cmdCode Case: "
                            "EFFECT_CMD_SET_PARAM: ERROR");
                    return -EINVAL;
                }
                effect_param_t *p = (effect_param_t *) pCmdData;

                if (p->psize != sizeof(int32_t)){
                    LOGV("\tLVM_ERROR : Virtualizer_command cmdCode Case: "
                            "EFFECT_CMD_SET_PARAM: ERROR, psize is not sizeof(int32_t)");
                    return -EINVAL;
                }

                //LOGV("\tnVirtualizer_command cmdSize is %d\n"
                //        "\tsizeof(effect_param_t) is  %d\n"
                //        "\tp->psize is %d\n"
                //        "\tp->vsize is %d"
                //        "\n",
                //        cmdSize, sizeof(effect_param_t), p->psize, p->vsize );

                *(int *)pReplyData = android::Virtualizer_setParameter(pContext,
                                                                      (int32_t *)p->data,
                                                                       p->data + p->psize);
            }
            if(pContext->EffectType == LVM_EQUALIZER){
                //LOGV("\tEqualizer_command cmdCode Case: "
                //        "EFFECT_CMD_SET_PARAM start");
                //LOGV("\tEqualizer_command EFFECT_CMD_SET_PARAM param %d, *replySize %d, value %d ",
                //        *(int32_t *)((char *)pCmdData + sizeof(effect_param_t)),
                //        *replySize,
                //        *(int16_t *)((char *)pCmdData + sizeof(effect_param_t) + sizeof(int32_t)));

                if (pCmdData == NULL || cmdSize < (int)(sizeof(effect_param_t) + sizeof(int32_t)) ||
                    pReplyData == NULL || *replySize != sizeof(int32_t)) {
                    LOGV("\tLVM_ERROR : Equalizer_command cmdCode Case: "
                            "EFFECT_CMD_SET_PARAM: ERROR");
                    return -EINVAL;
                }
                effect_param_t *p = (effect_param_t *) pCmdData;

                *(int *)pReplyData = android::Equalizer_setParameter(pContext,
                                                                    (int32_t *)p->data,
                                                                     p->data + p->psize);
            }
            if(pContext->EffectType == LVM_VOLUME){
                //LOGV("\tVolume_command cmdCode Case: "
                //        "EFFECT_CMD_SET_PARAM start");
                //LOGV("\tVolume_command EFFECT_CMD_SET_PARAM param %d, *replySize %d, value %d ",
                //        *(int32_t *)((char *)pCmdData + sizeof(effect_param_t)),
                //        *replySize,
                //        *(int16_t *)((char *)pCmdData + sizeof(effect_param_t) + sizeof(int32_t)));

                if (    pCmdData   == NULL||
                        cmdSize    < (int)(sizeof(effect_param_t) + sizeof(int32_t))||
                        pReplyData == NULL||
                        *replySize != sizeof(int32_t)){
                    LOGV("\tLVM_ERROR : Volume_command cmdCode Case: "
                            "EFFECT_CMD_SET_PARAM: ERROR");
                    return -EINVAL;
                }
                effect_param_t *p = (effect_param_t *) pCmdData;

                *(int *)pReplyData = android::Volume_setParameter(pContext,
                                                                 (int32_t *)p->data,
                                                                 p->data + p->psize);
            }
            //LOGV("\tEffect_command cmdCode Case: EFFECT_CMD_SET_PARAM end");
        } break;

        case EFFECT_CMD_ENABLE:
            //LOGV("\tEffect_command cmdCode Case: EFFECT_CMD_ENABLE start");
            if (pReplyData == NULL || *replySize != sizeof(int)){
                LOGV("\tLVM_ERROR : Effect_command cmdCode Case: EFFECT_CMD_ENABLE: ERROR");
                return -EINVAL;
            }
            switch (pContext->EffectType){
                case LVM_BASS_BOOST:
                    if(pContext->pBundledContext->bBassEnabled == LVM_TRUE){
                         LOGV("\tLVM_ERROR : BassBoost_command cmdCode Case: "
                                 "EFFECT_CMD_ENABLE: ERROR-Effect is already enabled");
                         return -EINVAL;
                    }
                    pContext->pBundledContext->bBassEnabled = LVM_TRUE;
                    //LOGV("\tEffect_command cmdCode Case: EFFECT_CMD_ENABLE LVM_BASS_BOOST enabled");
                    break;
                case LVM_EQUALIZER:
                    if(pContext->pBundledContext->bEqualizerEnabled == LVM_TRUE){
                         LOGV("\tLVM_ERROR : Equalizer_command cmdCode Case: "
                                 "EFFECT_CMD_ENABLE: ERROR-Effect is already enabled");
                         return -EINVAL;
                    }
                    pContext->pBundledContext->bEqualizerEnabled = LVM_TRUE;
                    //LOGV("\tEffect_command cmdCode Case: EFFECT_CMD_ENABLE LVM_EQUALIZER enabled");
                    break;
                case LVM_VIRTUALIZER:
                    if(pContext->pBundledContext->bVirtualizerEnabled == LVM_TRUE){
                         LOGV("\tLVM_ERROR : Virtualizer_command cmdCode Case: "
                                 "EFFECT_CMD_ENABLE: ERROR-Effect is already enabled");
                         return -EINVAL;
                    }
                    pContext->pBundledContext->bVirtualizerEnabled = LVM_TRUE;
                    //LOGV("\tEffect_command cmdCode Case:EFFECT_CMD_ENABLE LVM_VIRTUALIZER enabled");
                    break;
                case LVM_VOLUME:
                    if(pContext->pBundledContext->bVolumeEnabled == LVM_TRUE){
                         LOGV("\tLVM_ERROR : Volume_command cmdCode Case: "
                                 "EFFECT_CMD_ENABLE: ERROR-Effect is already enabled");
                         return -EINVAL;
                    }
                    pContext->pBundledContext->bVolumeEnabled = LVM_TRUE;
                    //LOGV("\tEffect_command cmdCode Case: EFFECT_CMD_ENABLE LVM_VOLUME enabled");
                    break;
                default:
                    LOGV("\tLVM_ERROR : Effect_command cmdCode Case: "
                        "EFFECT_CMD_ENABLE: ERROR, invalid Effect Type");
                    return -EINVAL;
            }
            *(int *)pReplyData = 0;
            pContext->pBundledContext->NumberEffectsEnabled++;
            android::LvmEffect_enable(pContext);
            //LOGV("\tEffect_command cmdCode Case: EFFECT_CMD_ENABLE NumberEffectsEnabled = %d",
            //        pContext->pBundledContext->NumberEffectsEnabled);
            //LOGV("\tEffect_command cmdCode Case: EFFECT_CMD_ENABLE end");
            break;

        case EFFECT_CMD_DISABLE:
            //LOGV("\tEffect_command cmdCode Case: EFFECT_CMD_DISABLE start");
            if (pReplyData == NULL || *replySize != sizeof(int)){
                LOGV("\tLVM_ERROR : Effect_command cmdCode Case: EFFECT_CMD_DISABLE: ERROR");
                return -EINVAL;
            }
            switch (pContext->EffectType){
                case LVM_BASS_BOOST:
                    if(pContext->pBundledContext->bBassEnabled == LVM_FALSE){
                         LOGV("\tLVM_ERROR : BassBoost_command cmdCode Case: "
                                 "EFFECT_CMD_DISABLE: ERROR-Effect is not yet enabled");
                         return -EINVAL;
                    }
                    pContext->pBundledContext->bBassEnabled      = LVM_FALSE;
                    //LOGV("\tEffect_command cmdCode Case: "
                    //       "EFFECT_CMD_DISABLE LVM_BASS_BOOST disabled");
                    break;
                case LVM_EQUALIZER:
                    if(pContext->pBundledContext->bEqualizerEnabled == LVM_FALSE){
                         LOGV("\tLVM_ERROR : Equalizer_command cmdCode Case: "
                                 "EFFECT_CMD_DISABLE: ERROR-Effect is not yet enabled");
                         return -EINVAL;
                    }
                    pContext->pBundledContext->bEqualizerEnabled = LVM_FALSE;
                    //LOGV("\tEffect_command cmdCode Case: "
                    //       "EFFECT_CMD_DISABLE LVM_EQUALIZER disabled");
                    break;
                case LVM_VIRTUALIZER:
                    if(pContext->pBundledContext->bVirtualizerEnabled == LVM_FALSE){
                         LOGV("\tLVM_ERROR : Virtualizer_command cmdCode Case: "
                                 "EFFECT_CMD_DISABLE: ERROR-Effect is not yet enabled");
                         return -EINVAL;
                    }
                    pContext->pBundledContext->bVirtualizerEnabled = LVM_FALSE;
                    //LOGV("\tEffect_command cmdCode Case: "
                    //     "EFFECT_CMD_DISABLE LVM_VIRTUALIZER disabled");
                    break;
                case LVM_VOLUME:
                    if(pContext->pBundledContext->bVolumeEnabled == LVM_FALSE){
                         LOGV("\tLVM_ERROR : Volume_command cmdCode Case: "
                                 "EFFECT_CMD_DISABLE: ERROR-Effect is not yet enabled");
                         return -EINVAL;
                    }
                    pContext->pBundledContext->bVolumeEnabled = LVM_FALSE;
                    //LOGV("\tEffect_command cmdCode Case: EFFECT_CMD_DISABLE LVM_VOLUME disabled");
                    break;
                default:
                    LOGV("\tLVM_ERROR : Effect_command cmdCode Case: "
                        "EFFECT_CMD_DISABLE: ERROR, invalid Effect Type");
                    return -EINVAL;
            }
            *(int *)pReplyData = 0;
            pContext->pBundledContext->NumberEffectsEnabled--;
            android::LvmEffect_disable(pContext);
            //LOGV("\tEffect_command cmdCode Case: EFFECT_CMD_DISABLE NumberEffectsEnabled = %d",
            //        pContext->pBundledContext->NumberEffectsEnabled);
            //LOGV("\tEffect_command cmdCode Case: EFFECT_CMD_DISABLE end");
            break;

        case EFFECT_CMD_SET_DEVICE:
        {
            LOGV("\tEffect_command cmdCode Case: EFFECT_CMD_SET_DEVICE start");
            audio_device_e device = *(audio_device_e *)pCmdData;

            if(pContext->EffectType == LVM_BASS_BOOST){
                if((device == DEVICE_SPEAKER)||(device == DEVICE_BLUETOOTH_SCO_CARKIT)||
                   (device == DEVICE_BLUETOOTH_A2DP_SPEAKER)){
                    LOGV("\tEFFECT_CMD_SET_DEVICE device is invalid for LVM_BASS_BOOST %d",
                          *(int32_t *)pCmdData);
                    LOGV("\tEFFECT_CMD_SET_DEVICE temporary disable LVM_BAS_BOOST");

                    // If a device doesnt support bassboost the effect must be temporarily disabled
                    // the effect must still report its original state as this can only be changed
                    // by the ENABLE/DISABLE command

                    if(pContext->pBundledContext->bBassEnabled == LVM_TRUE){
                        LOGV("\tEFFECT_CMD_SET_DEVICE disable LVM_BASS_BOOST %d",
                             *(int32_t *)pCmdData);
                        android::LvmEffect_disable(pContext);
                        pContext->pBundledContext->bBassTempDisabled = LVM_TRUE;
                    }
                }else{
                    LOGV("\tEFFECT_CMD_SET_DEVICE device is valid for LVM_BASS_BOOST %d",
                         *(int32_t *)pCmdData);

                    // If a device supports bassboost and the effect has been temporarily disabled
                    // previously then re-enable it

                    if(pContext->pBundledContext->bBassTempDisabled == LVM_TRUE){
                        LOGV("\tEFFECT_CMD_SET_DEVICE re-enable LVM_BASS_BOOST %d",
                             *(int32_t *)pCmdData);
                        android::LvmEffect_enable(pContext);
                        pContext->pBundledContext->bBassTempDisabled = LVM_FALSE;
                    }
                }
            }
            if(pContext->EffectType == LVM_VIRTUALIZER){
                if((device == DEVICE_SPEAKER)||(device == DEVICE_BLUETOOTH_SCO_CARKIT)||
                   (device == DEVICE_BLUETOOTH_A2DP_SPEAKER)){
                    LOGV("\tEFFECT_CMD_SET_DEVICE device is invalid for LVM_VIRTUALIZER %d",
                          *(int32_t *)pCmdData);
                    LOGV("\tEFFECT_CMD_SET_DEVICE temporary disable LVM_VIRTUALIZER");

                    //If a device doesnt support virtualizer the effect must be temporarily disabled
                    // the effect must still report its original state as this can only be changed
                    // by the ENABLE/DISABLE command

                    if(pContext->pBundledContext->bVirtualizerEnabled == LVM_TRUE){
                        LOGV("\tEFFECT_CMD_SET_DEVICE disable LVM_VIRTUALIZER %d",
                              *(int32_t *)pCmdData);
                        android::LvmEffect_disable(pContext);
                        pContext->pBundledContext->bVirtualizerTempDisabled = LVM_TRUE;
                    }
                }else{
                    LOGV("\tEFFECT_CMD_SET_DEVICE device is valid for LVM_VIRTUALIZER %d",
                          *(int32_t *)pCmdData);

                    // If a device supports virtualizer and the effect has been temporarily disabled
                    // previously then re-enable it

                    if(pContext->pBundledContext->bVirtualizerTempDisabled == LVM_TRUE){
                        LOGV("\tEFFECT_CMD_SET_DEVICE re-enable LVM_VIRTUALIZER %d",
                              *(int32_t *)pCmdData);
                        android::LvmEffect_enable(pContext);
                        pContext->pBundledContext->bVirtualizerTempDisabled = LVM_FALSE;
                    }
                }
            }
            LOGV("\tEffect_command cmdCode Case: EFFECT_CMD_SET_DEVICE end");
            break;
        }
        case EFFECT_CMD_SET_VOLUME:
        {
            int32_t channels = cmdSize/sizeof(int32_t);
            int32_t vol     = *(int32_t *)pCmdData;
            int16_t vol_db;
            int16_t dB;
            int16_t vol_db_rnd;
            int32_t vol_ret[2] = {1<<24,1<<24}; // Apply no volume

            // if pReplyData is NULL, VOL_CTRL is delegated to another effect
            if(pReplyData == LVM_NULL){
                break;
            }

            if(vol==0x1000000){
                vol -= 1;
            }
            // Convert volume linear (Q8.24) to volume dB (0->-96)
            dB = android::LVC_ToDB_s32Tos16(vol <<7);
            dB = (dB +8)>>4;
            dB = (dB <-96) ? -96 : dB ;

            //LOGV("\tSession: %d, VOLUME is %d dB (%d), effect is %d",
            //pContext->pBundledContext->SessionNo, (int32_t)dB, vol<<7, pContext->EffectType);
            memcpy(pReplyData, vol_ret, sizeof(int32_t)*2);
            android::VolumeSetVolumeLevel(pContext, (int16_t)(dB*100));
            break;
         }
        case EFFECT_CMD_SET_AUDIO_MODE:
            break;
        default:
            return -EINVAL;
    }

    //LOGV("\tEffect_command end...\n\n");
    return 0;
}    /* end Effect_command */

// effect_interface_t interface implementation for effect
const struct effect_interface_s gLvmEffectInterface = {
    Effect_process,
    Effect_command
};    /* end gLvmEffectInterface */

