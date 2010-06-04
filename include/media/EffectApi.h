/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef ANDROID_EFFECTAPI_H_
#define ANDROID_EFFECTAPI_H_

#include <errno.h>
#include <stdint.h>
#include <sys/types.h>
#include <media/AudioCommon.h>

#if __cplusplus
extern "C" {
#endif

/////////////////////////////////////////////////
//      Effect control interface
/////////////////////////////////////////////////

// The effect control interface is exposed by each effect engine implementation. It consists of
// a set of functions controlling the configuration, activation and process of the engine.
// The functions are grouped in a structure of type effect_interface_s:
//    struct effect_interface_s {
//        effect_process_t process;
//        effect_command_t command;
//    };


// effect_interface_t: Effect control interface handle.
// The effect_interface_t serves two purposes regarding the implementation of the effect engine:
// - 1 it is the address of a pointer to an effect_interface_s structure where the functions
// of the effect control API for a particular effect are located.
// - 2 it is the address of the context of a particular effect instance.
// A typical implementation in the effect library would define a structure as follows:
// struct effect_module_s {
//        const struct effect_interface_s *itfe;
//        effect_config_t config;
//        effect_context_t context;
// }
// The implementation of EffectCreate() function would then allocate a structure of this
// type and return its address as effect_interface_t
typedef struct effect_interface_s **effect_interface_t;


// Effect API version 1.0
#define EFFECT_API_VERSION 0x0100 // Format 0xMMmm MM: Major version, mm: minor version

// Maximum length of character strings in structures defines by this API.
#define EFFECT_STRING_LEN_MAX 64

//
//--- Effect descriptor structure effect_descriptor_t
//

// Unique effect ID (can be generated from the following site: http://www.itu.int/ITU-T/asn1/uuid.html)
// This format is used for both "type" and "uuid" fields of the effect descriptor structure.
// - When used for effect type and the engine is implementing and effect corresponding to a standard OpenSL ES
// interface, this ID must be the one defined in OpenSLES_IID.h for that interface.
// - When used as uuid, it should be a unique UUID for this particular implementation.
typedef struct effect_uuid_s {
    uint32_t timeLow;
    uint16_t timeMid;
    uint16_t timeHiAndVersion;
    uint16_t clockSeq;
    uint8_t node[6];
} effect_uuid_t;

// NULL UUID definition (matches SL_IID_NULL_)
#define EFFECT_UUID_INITIALIZER { 0xec7178ec, 0xe5e1, 0x4432, 0xa3f4, { 0x46, 0x57, 0xe6, 0x79, 0x52, 0x10 } }
static const effect_uuid_t EFFECT_UUID_NULL_ = EFFECT_UUID_INITIALIZER;
const effect_uuid_t * const EFFECT_UUID_NULL = &EFFECT_UUID_NULL_;
const char * const EFFECT_UUID_NULL_STR = "ec7178ec-e5e1-4432-a3f4-4657e6795210";

// the effect descriptor contains necessary information to facilitate the enumeration of the effect
// engines present in a library.
typedef struct effect_descriptor_s {
    effect_uuid_t type;     // UUID corresponding to the OpenSL ES interface implemented by this effect
    effect_uuid_t uuid;     // UUID for this particular implementation
    uint16_t apiVersion;    // Version of the effect API implemented: must match current EFFECT_API_VERSION
    uint32_t flags;         // effect engine capabilities/requirements flags (see below)
    char    name[EFFECT_STRING_LEN_MAX] ;   // human readable effect name
    char    implementor[EFFECT_STRING_LEN_MAX] ;    // human readable effect implementor name
} effect_descriptor_t;

// definitions for flags field of effect descriptor.
//  +---------------------------+-----------+-----------------------------------
//  | description               | bits      | values
//  +---------------------------+-----------+-----------------------------------
//  | connection mode           | 0..1      | 0 insert: after track process
//  |                           |           | 1 auxiliary: connect to track auxiliary
//  |                           |           |  output and use send level
//  |                           |           | 2 replace: replaces track process function;
//  |                           |           |   must implement SRC, volume and mono to stereo.
//  |                           |           | 3 reserved
//  +---------------------------+-----------+-----------------------------------
//  | insertion preference      | 2..4      | 0 none
//  |                           |           | 1 first of the chain
//  |                           |           | 2 last of the chain
//  |                           |           | 3 exclusive (only effect in the insert chain)
//  |                           |           | 4..7 reserved
//  +---------------------------+-----------+-----------------------------------
//  | Volume management         | 5..6      | 0 none
//  |                           |           | 1 implements volume control
//  |                           |           | 2 requires volume indication
//  |                           |           | 3 reserved
//  +---------------------------+-----------+-----------------------------------
//  | Device management         | 7..8      | 0 none
//  |                           |           | 1 requires device updates
//  |                           |           | 2..3 reserved
//  +---------------------------+-----------+-----------------------------------
//  | Sample input mode         | 9..10     | 0 direct: process() function or EFFECT_CMD_CONFIGURE
//  |                           |           |   command must specify a buffer descriptor
//  |                           |           | 1 provider: process() function uses the
//  |                           |           |   bufferProvider indicated by the
//  |                           |           |   EFFECT_CMD_CONFIGURE command to request input buffers.
//  |                           |           | 2 both: both input modes are supported
//  |                           |           | 3 reserved
//  +---------------------------+-----------+-----------------------------------
//  | Sample output mode        | 11..12    | 0 direct: process() function or EFFECT_CMD_CONFIGURE
//  |                           |           |   command must specify a buffer descriptor
//  |                           |           | 1 provider: process() function uses the
//  |                           |           |   bufferProvider indicated by the
//  |                           |           |   EFFECT_CMD_CONFIGURE command to request output buffers.
//  |                           |           | 2 both: both output modes are supported
//  |                           |           | 3 reserved
//  +---------------------------+-----------+-----------------------------------

// insert mode
#define EFFECT_FLAG_TYPE_MASK           0x00000003
#define EFFECT_FLAG_TYPE_INSERT         0x00000000
#define EFFECT_FLAG_TYPE_AUXILIARY      0x00000001
#define EFFECT_FLAG_TYPE_REPLACE        0x00000002

// insert preference
#define EFFECT_FLAG_INSERT_MASK         0x0000001C
#define EFFECT_FLAG_INSERT_ANY          0x00000000
#define EFFECT_FLAG_INSERT_FIRST        0x00000004
#define EFFECT_FLAG_INSERT_LAST         0x00000008
#define EFFECT_FLAG_INSERT_EXCLUSIVE    0x0000000C


// volume control
#define EFFECT_FLAG_VOLUME_MASK         0x00000060
#define EFFECT_FLAG_VOLUME_CTRL         0x00000020
#define EFFECT_FLAG_VOLUME_IND          0x00000040
#define EFFECT_FLAG_VOLUME_NONE         0x00000000

// device control
#define EFFECT_FLAG_DEVICE_MASK         0x00000180
#define EFFECT_FLAG_DEVICE_IND          0x00000080
#define EFFECT_FLAG_DEVICE_NONE         0x00000000

// sample input modes
#define EFFECT_FLAG_INPUT_MASK          0x00000600
#define EFFECT_FLAG_INPUT_DIRECT        0x00000000
#define EFFECT_FLAG_INPUT_PROVIDER      0x00000200
#define EFFECT_FLAG_INPUT_BOTH          0x00000400

// sample output modes
#define EFFECT_FLAG_OUTPUT_MASK          0x00001800
#define EFFECT_FLAG_OUTPUT_DIRECT        0x00000000
#define EFFECT_FLAG_OUTPUT_PROVIDER      0x00000800
#define EFFECT_FLAG_OUTPUT_BOTH          0x00001000

// forward definition of type audio_buffer_t
typedef struct audio_buffer_s audio_buffer_t;

////////////////////////////////////////////////////////////////////////////////
//
//    Function:       process
//
//    Description:    Effect process function. Takes input samples as specified
//          (count and location) in input buffer descriptor and output processed
//          samples as specified in output buffer descriptor. If the buffer descriptor
//          is not specified the function must use either the buffer or the
//          buffer provider function installed by the EFFECT_CMD_CONFIGURE command.
//
//    NOTE: the process() function implementation should be "real-time safe" that is
//      it should not perform blocking calls: malloc/free, sleep, read/write/open/close,
//      pthread_cond_wait/pthread_mutex_lock...
//
//    Input:
//          effect_interface_t: handle to the effect interface this function
//              is called on.
//          inBuffer:   buffer descriptor indicating where to read samples to process.
//              If NULL, use the configuration passed by EFFECT_CMD_CONFIGURE command.
//
//          inBuffer:   buffer descriptor indicating where to write processed samples.
//              If NULL, use the configuration passed by EFFECT_CMD_CONFIGURE command.
//
//    Output:
//        returned value:    0 successful operation
//                          -EINVAL invalid interface handle or
//                                  invalid input/output buffer description
////////////////////////////////////////////////////////////////////////////////
typedef int32_t (*effect_process_t)(effect_interface_t self, audio_buffer_t *inBuffer, audio_buffer_t *outBuffer);

////////////////////////////////////////////////////////////////////////////////
//
//    Function:       command
//
//    Description:    Send a command and receive a response to/from effect engine.
//
//    Input:
//          effect_interface_t: handle to the effect interface this function
//              is called on.
//          cmdCode:    command code: the command can be a standardized command defined in
//              effect_command_e (see below) or a proprietary command.
//          cmdSize:    size of command in bytes
//          pCmdData:   pointer to command data
//          pReplyData: pointer to reply data
//
//    Input/Output:
//          replySize: maximum size of reply data as input
//                      actual size of reply data as output
//
//    Output:
//          returned value: 0       successful operation
//                          -EINVAL invalid interface handle or
//                                  invalid command/reply size or format according to command code
//              The return code should be restricted to indicate problems related to the this
//              API specification. Status related to the execution of a particular command should be
//              indicated as part of the reply field.
//
//          *pReplyData updated with command response
//
////////////////////////////////////////////////////////////////////////////////
typedef int32_t (*effect_command_t)(effect_interface_t self, int32_t cmdCode, int32_t cmdSize, void *pCmdData, int32_t *replySize, void *pReplyData);


// Effect control interface definition
struct effect_interface_s {
    effect_process_t process;
    effect_command_t command;
};


//--- Standardized command codes for command function
//  +--------------------------------+-------------------------------+-------------------------------+--------------------------
//  | description                    | command code                  | command format                | reply format
//  +--------------------------------+-------------------------------+-------------------------------+--------------------------
//  | Initialize effect engine:      | EFFECT_CMD_INIT               | size: 0                       | size: sizeof(int)
//  |  All configurations return to  |                               | data: N/A                     | data: status
//  |  default                       |                               |                               |
//  +--------------------------------+-------------------------------+-------------------------------+--------------------------
//  | Apply new audio parameters     | EFFECT_CMD_CONFIGURE          | size: sizeof(effect_config_t) | size: sizeof(int)
//  | configurations for input and   |                               | data: effect_config_t         | data: status
//  | output buffers                 |                               |                               |
//  +--------------------------------+-------------------------------+-------------------------------+--------------------------
//  | Reset effect engine: keep      | EFFECT_CMD_RESET              | size: 0                       | size: 0
//  | configuration but reset state  |                               | data: N/A                     | data: N/A
//  | and buffer content.            |                               |                               |
//  | Called by the framework before |                               |                               |
//  | enabling the effect            |                               |                               |
//  +--------------------------------+-------------------------------+-------------------------------+--------------------------
//  | Enable the process             | EFFECT_CMD_ENABLE             | size: 0                       | size: sizeof(int)
//  | Called by the framework before |                               | data: N/A                     | data: status
//  | the first call to process()    |                               |                               |
//  +--------------------------------+-------------------------------+-------------------------------+--------------------------
//  | Disable the process            | EFFECT_CMD_DISABLE            | size: 0                       | size: sizeof(int)
//  | Called by the framework after  |                               | data: N/A                     | data: status
//  | the last call to process()     |                               |                               |
//  +--------------------------------+-------------------------------+-------------------------------+--------------------------
//  | Set a parameter and apply it   | EFFECT_CMD_SET_PARAM          | size: sizeof(effect_param_t)  | size: sizeof(int)
//  | immediately                    |                               |       + size of param + value | data: status
//  |                                |                               | data: effect_param_t          |
//  +--------------------------------+-------------------------------+-------------------------------+--------------------------
//  | Set a parameter but apply it   | EFFECT_CMD_SET_PARAM_DEFERRED | size: sizeof(effect_param_t)  | size: 0
//  | only when receiving command    |                               |       + size of param + value | data: N/A
//  | EFFECT_CMD_SET_PARAM_COMMIT    |                               | data: effect_param_t          |
//  +--------------------------------+-------------------------------+-------------------------------+--------------------------
//  | Apply all previously received  | EFFECT_CMD_SET_PARAM_COMMIT   | size: 0                       | size: sizeof(int)
//  | EFFECT_CMD_SET_PARAM_DEFERRED  |                               | data: N/A                     | data: status
//  | commands                       |                               |                               |
//  +--------------------------------+-------------------------------+-------------------------------+--------------------------
//  | Get a parameter value          | EFFECT_CMD_GET_PARAM          | size: sizeof(effect_param_t)  | size: sizeof(effect_param_t)
//  |                                |                               |       + size of param         |       + size of param + value
//  |                                |                               | data: effect_param_t          | data: effect_param_t
//  +--------------------------------+-------------------------------+-------------------------------+--------------------------
//  | Set the rendering device the   | EFFECT_CMD_SET_DEVICE         | size: sizeof(uint32_t)        | size: 0
//  | audio output path is connected |                               | data: audio_device_e          | data: N/A
//  | to. See audio_device_e in      |                               |                               |
//  | AudioCommon.h for device values|                               |                               |
//  +--------------------------------+-------------------------------+-------------------------------+--------------------------
//  | Set and get volume. Used by    | EFFECT_CMD_SET_VOLUME         | size: n * sizeof(uint32_t)    | size: n * sizeof(uint32_t)
//  | audio framework to delegate    |                               | data: volume for each channel | data: volume for each channel
//  | volume control to effect engine|                               | defined in effect_config_t in | defined in effect_config_t in
//  | If volume control flag is set  |                               | 8.24 fixed point format       | 8.24 fixed point format
//  | in the effect descriptor, the  |                               |                               | It is legal to receive a null
//  | effect engine must return the  |                               |                               | pointer as pReplyData in which
//  | volume that should be applied  |                               |                               | case the effect framework has
//  | before the effect is processed |                               |                               | delegated volume control to
//  | The overall volume (the volume |                               |                               | another effect.
//  | actually applied by the effect |                               |                               |
//  | multiplied by the returned     |                               |                               |
//  | value) should match the        |                               |                               |
//  | requested value                |                               |                               |
//  +--------------------------------+-------------------------------+-------------------------------+--------------------------
//  | All proprietary effect commands| EFFECT_CMD_FIRST_PROPRIETARY  |                               |
//  | must use command codes above   |                               |                               |
//  | this value. The size and format|                               |                               |
//  | of command and response fields |                               |                               |
//  | is free in this case.          |                               |                               |
//  +--------------------------------+-------------------------------+-------------------------------+--------------------------


enum effect_command_e {
   EFFECT_CMD_INIT,                 // initialize effect engine
   EFFECT_CMD_CONFIGURE,            // configure effect engine (see effect_config_t)
   EFFECT_CMD_RESET,                // reset effect engine
   EFFECT_CMD_ENABLE,               // enable effect process
   EFFECT_CMD_DISABLE,              // disable effect process
   EFFECT_CMD_SET_PARAM,            // set parameter immediately (see effect_param_t)
   EFFECT_CMD_SET_PARAM_DEFERRED,   // set parameter deferred
   EFFECT_CMD_SET_PARAM_COMMIT,     // commit previous set parameter deferred
   EFFECT_CMD_GET_PARAM,            // get parameter
   EFFECT_CMD_SET_DEVICE,           // set audio device (see audio_device_e in AudioCommon.h)
   EFFECT_CMD_SET_VOLUME,           // set volume
   EFFECT_CMD_FIRST_PROPRIETARY = 0x10000 // first proprietary command code
};

// Audio buffer descriptor used by process(), bufferProvider() functions and buffer_config_t structure
// Multi-channel audio is always interleaved. The channel order is from LSB to MSB with regard to the
// channel mask definition in audio_channels_e (AudioCommon.h) e.g :
// Stereo: left, right
// 5 point 1: front left, front right, front center, low frequency, back left, back right
// The buffer size is expressed in frame count, a frame being composed of samples for all
// channels at a given time
struct audio_buffer_s {
    size_t   frameCount;        // number of frames in buffer
    union {
        void*       raw;        // raw pointer to start of buffer
        int32_t*    s32;        // pointer to signed 32 bit data at start of buffer
        int16_t*    s16;        // pointer to signed 16 bit data at start of buffer
        uint8_t*    u8;         // pointer to unsigned 8 bit data at start of buffer
    };
};

// the buffer_provider_s structure contains functions that can be used
// by the effect engine process() function to query and release input
// or output audio buffer.
// The getBuffer() function is called to retrieve a buffer where data
// should read from or written to by process() function.
// The releaseBuffer() function MUST be called when the buffer retrieved
// with getBuffer() is not needed anymore.
// The process function should use the buffer provider mechanism to retrieve
// input or output buffer if the inBuffer or outBuffer passed as argument is NULL
// and the buffer configuration (buffer_config_t) given by the EFFECT_CMD_CONFIGURE
// command did not specify an audio buffer.

typedef int32_t (* buffer_function_t)(void *cookie, audio_buffer_t *buffer);

typedef struct buffer_provider_s {
    buffer_function_t getBuffer;       // retrieve next buffer
    buffer_function_t releaseBuffer;   // release used buffer
    void       *cookie;                // for use by client of buffer provider functions
} buffer_provider_t;

// The buffer_config_s structure specifies the input or output audio format
// to be used by the effect engine. It is part of the effect_config_t
// structure that defines both input and output buffer configurations and is
// passed by the EFFECT_CMD_CONFIGURE command.
typedef struct buffer_config_s {
    audio_buffer_t  buffer;     // buffer for use by process() function if not passed explicitly
    uint32_t   samplingRate;    // sampling rate
    uint32_t   channels;        // channel mask (see audio_channels_e in AudioCommon.h)
    buffer_provider_t bufferProvider;   // buffer provider
    uint8_t    format;          // PCM format  (see audio_format_e in AudioCommon.h)
    uint8_t    accessMode;      // read/write or accumulate in buffer (effect_buffer_access_e)
    uint16_t   mask;            // indicates which of the above fields is valid
} buffer_config_t;

// values for "accessMode" field of buffer_config_t:
//   overwrite, read only, accumulate (read/modify/write)
enum effect_buffer_access_e {
    EFFECT_BUFFER_ACCESS_WRITE,
    EFFECT_BUFFER_ACCESS_READ,
    EFFECT_BUFFER_ACCESS_ACCUMULATE

};

// values for bit field "mask" in buffer_config_t. If a bit is set, the corresponding field
// in buffer_config_t must be taken into account when executing the EFFECT_CMD_CONFIGURE command
#define EFFECT_CONFIG_BUFFER    0x0001  // buffer field must be taken into account
#define EFFECT_CONFIG_SMP_RATE  0x0002  // samplingRate field must be taken into account
#define EFFECT_CONFIG_CHANNELS  0x0004  // channels field must be taken into account
#define EFFECT_CONFIG_FORMAT    0x0008  // format field must be taken into account
#define EFFECT_CONFIG_ACC_MODE  0x0010  // accessMode field must be taken into account
#define EFFECT_CONFIG_PROVIDER  0x0020  // bufferProvider field must be taken into account
#define EFFECT_CONFIG_ALL (EFFECT_CONFIG_BUFFER | EFFECT_CONFIG_SMP_RATE | \
                           EFFECT_CONFIG_CHANNELS | EFFECT_CONFIG_FORMAT | \
                           EFFECT_CONFIG_ACC_MODE | EFFECT_CONFIG_PROVIDER)

// effect_config_s structure describes the format of the pCmdData argument of EFFECT_CMD_CONFIGURE command
// to configure audio parameters and buffers for effect engine input and output.
typedef struct effect_config_s {
    buffer_config_t   inputCfg;
    buffer_config_t   outputCfg;;
} effect_config_t;

// effect_param_s structure describes the format of the pCmdData argument of EFFECT_CMD_SET_PARAM
// command and pCmdData and pReplyData of EFFECT_CMD_GET_PARAM command.
// psize and vsize represent the actual size of parameter and value.
//
// NOTE: the start of value field inside the data field is always on a 32 bit boundary:
//
//  +-----------+
//  | status    | sizeof(int)
//  +-----------+
//  | psize     | sizeof(int)
//  +-----------+
//  | vsize     | sizeof(int)
//  +-----------+
//  |           |   |           |
//  ~ parameter ~   > psize     |
//  |           |   |           >  ((psize - 1)/sizeof(int) + 1) * sizeof(int)
//  +-----------+               |
//  | padding   |               |
//  +-----------+
//  |           |   |
//  ~ value     ~   > vsize
//  |           |   |
//  +-----------+

typedef struct effect_param_s {
    int32_t     status;     // Transaction status (unused for command, used for reply)
    uint32_t    psize;      // Parameter size
    uint32_t    vsize;      // Value size
    char        data[];     // Start of Parameter + Value data
} effect_param_t;


/////////////////////////////////////////////////
//      Effect library interface
/////////////////////////////////////////////////

// An effect library is required to implement and expose the following functions
// to enable effect enumeration and instantiation. The name of these functions must be as
// specified here as the effect framework will get the function address with dlsym():
//
// - effect_QueryNumberEffects_t EffectQueryNumberEffects;
// - effect_QueryNextEffect_t EffectQueryNext;
// - effect_CreateEffect_t EffectCreate;
// - effect_ReleaseEffect_t EffectRelease;


////////////////////////////////////////////////////////////////////////////////
//
//    Function:       EffectQueryNumberEffects
//
//    Description:    Returns the number of different effects exposed by the
//          library. Each effect must have a unique effect uuid (see
//          effect_descriptor_t). This function together with EffectQueryNext()
//          is used to enumerate all effects present in the library.
//          Each time EffectQueryNumberEffects() is called, the library must
//          reset the index of the effect descriptor returned by next call to
//          EffectQueryNext() to restart enumeration from the beginning.
//
//    Input/Output:
//          pNumEffects:    address where the number of effects should be returned.
//
//    Output:
//        returned value:    0          successful operation.
//                          -ENODEV     library failed to initialize
//                          -EINVAL     invalid pNumEffects
//        *pNumEffects:     updated with number of effects in library
//
////////////////////////////////////////////////////////////////////////////////
typedef int32_t (*effect_QueryNumberEffects_t)(uint32_t *pNumEffects);

////////////////////////////////////////////////////////////////////////////////
//
//    Function:       EffectQueryNext
//
//    Description:    Returns a descriptor of the next available effect.
//          See effect_descriptor_t for details on effect descriptors.
//          This function together with EffectQueryNext() is used to enumerate all
//          effects present in the library. The enumeration sequence is:
//              EffectQueryNumberEffects(&num_effects);
//              while (num_effects--)
//                  EffectQueryNext();
//
//    Input/Output:
//          pDescriptor:    address where to return the effect descriptor.
//
//    Output:
//        returned value:    0          successful operation.
//                          -ENODEV     library failed to initialize
//                          -EINVAL     invalid pDescriptor
//                          -ENOENT     no more effect available
//        *pDescriptor:     updated with the effect descriptor.
//
////////////////////////////////////////////////////////////////////////////////
typedef int32_t (*effect_QueryNextEffect_t)(effect_descriptor_t *pDescriptor);

////////////////////////////////////////////////////////////////////////////////
//
//    Function:       EffectCreate
//
//    Description:    Creates an effect engine of the specified type and returns an
//          effect control interface on this engine. The function will allocate the
//          resources for an instance of the requested effect engine and return
//          a handle on the effect control interface.
//
//    Input:
//          pEffectUuid:    pointer to the effect uuid.
//
//    Input/Output:
//          pInterface:    address where to return the effect interface.
//
//    Output:
//        returned value:    0          successful operation.
//                          -ENODEV     library failed to initialize
//                          -EINVAL     invalid pEffectUuid or pInterface
//                          -ENOENT     no effect with this uuid found
//        *pInterface:     updated with the effect interface handle.
//
////////////////////////////////////////////////////////////////////////////////
typedef int32_t (*effect_CreateEffect_t)(effect_uuid_t *uuid, effect_interface_t *pInterface);

////////////////////////////////////////////////////////////////////////////////
//
//    Function:       EffectRelease
//
//    Description:    Releases the effect engine whose handle is given as argument.
//          All resources allocated to this particular instance of the effect are
//          released.
//
//    Input:
//          interface:    handle on the effect interface to be released.
//
//    Output:
//        returned value:    0          successful operation.
//                          -ENODEV     library failed to initialize
//                          -EINVAL     invalid interface handle
//
////////////////////////////////////////////////////////////////////////////////
typedef int32_t (*effect_ReleaseEffect_t)(effect_interface_t interface);


#if __cplusplus
}  // extern "C"
#endif


#endif /*ANDROID_EFFECTAPI_H_*/
