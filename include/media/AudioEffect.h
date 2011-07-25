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

#ifndef ANDROID_AUDIOEFFECT_H
#define ANDROID_AUDIOEFFECT_H

#include <stdint.h>
#include <sys/types.h>

#include <media/IAudioFlinger.h>
#include <media/IAudioPolicyService.h>
#include <media/IEffect.h>
#include <media/IEffectClient.h>
#include <hardware/audio_effect.h>
#include <media/AudioSystem.h>

#include <utils/RefBase.h>
#include <utils/Errors.h>
#include <binder/IInterface.h>


namespace android {

// ----------------------------------------------------------------------------

class effect_param_cblk_t;

// ----------------------------------------------------------------------------

class AudioEffect : public RefBase
{
public:

    /*
     *  Static methods for effects enumeration.
     */

    /*
     * Returns the number of effects available. This method together
     * with queryEffect() is used to enumerate all effects:
     * The enumeration sequence is:
     *      queryNumberEffects(&num_effects);
     *      for (i = 0; i < num_effects; i++)
     *          queryEffect(i,...);
     *
     * Parameters:
     *      numEffects:    address where the number of effects should be returned.
     *
     * Returned status (from utils/Errors.h) can be:
     *      NO_ERROR   successful operation.
     *      PERMISSION_DENIED could not get AudioFlinger interface
     *      NO_INIT    effect library failed to initialize
     *      BAD_VALUE  invalid numEffects pointer
     *
     * Returned value
     *   *numEffects:     updated with number of effects available
     */
    static status_t queryNumberEffects(uint32_t *numEffects);

    /*
     * Returns an effect descriptor during effect
     * enumeration.
     *
     * Parameters:
     *      index:      index of the queried effect.
     *      descriptor: address where the effect descriptor should be returned.
     *
     * Returned status (from utils/Errors.h) can be:
     *      NO_ERROR        successful operation.
     *      PERMISSION_DENIED could not get AudioFlinger interface
     *      NO_INIT         effect library failed to initialize
     *      BAD_VALUE       invalid descriptor pointer or index
     *      INVALID_OPERATION  effect list has changed since last execution of queryNumberEffects()
     *
     * Returned value
     *   *descriptor:     updated with effect descriptor
     */
    static status_t queryEffect(uint32_t index, effect_descriptor_t *descriptor);


    /*
     * Returns the descriptor for the specified effect uuid.
     *
     * Parameters:
     *      uuid:       pointer to effect uuid.
     *      descriptor: address where the effect descriptor should be returned.
     *
     * Returned status (from utils/Errors.h) can be:
     *      NO_ERROR        successful operation.
     *      PERMISSION_DENIED could not get AudioFlinger interface
     *      NO_INIT         effect library failed to initialize
     *      BAD_VALUE       invalid uuid or descriptor pointers
     *      NAME_NOT_FOUND  no effect with this uuid found
     *
     * Returned value
     *   *descriptor updated with effect descriptor
     */
    static status_t getEffectDescriptor(effect_uuid_t *uuid, effect_descriptor_t *descriptor);


    /*
     * Returns a list of descriptors corresponding to the pre processings enabled by default
     * on an AudioRecord with the supplied audio session ID.
     *
     * Parameters:
     *      audioSession:  audio session ID.
     *      descriptors: address where the effect descriptors should be returned.
     *      count: as input, the maximum number of descriptor than should be returned
     *             as output, the number of descriptor returned if status is NO_ERROR or the actual
     *             number of enabled pre processings if status is NO_MEMORY
     *
     * Returned status (from utils/Errors.h) can be:
     *      NO_ERROR        successful operation.
     *      NO_MEMORY       the number of descriptor to return is more than the maximum number
     *                      indicated by count.
     *      PERMISSION_DENIED could not get AudioFlinger interface
     *      NO_INIT         effect library failed to initialize
     *      BAD_VALUE       invalid audio session or descriptor pointers
     *
     * Returned value
     *   *descriptor updated with descriptors of pre processings enabled by default
     *   *count      number of descriptors returned if returned status is N_ERROR.
     *               total number of pre processing enabled by default if returned status is
     *               NO_MEMORY. This happens if the count passed as input is less than the number
     *               of descriptors to return
     */
    static status_t queryDefaultPreProcessing(int audioSession,
                                              effect_descriptor_t *descriptors,
                                              uint32_t *count);

    /*
     * Events used by callback function (effect_callback_t).
     */
    enum event_type {
        EVENT_CONTROL_STATUS_CHANGED = 0,
        EVENT_ENABLE_STATUS_CHANGED = 1,
        EVENT_PARAMETER_CHANGED = 2,
        EVENT_ERROR = 3
    };

    /* Callback function notifying client application of a change in effect engine state or
     * configuration.
     * An effect engine can be shared by several applications but only one has the control
     * of the engine activity and configuration at a time.
     * The EVENT_CONTROL_STATUS_CHANGED event is received when an application loses or
     * retrieves the control of the effect engine. Loss of control happens
     * if another application requests the use of the engine by creating an AudioEffect for
     * the same effect type but with a higher priority. Control is returned when the
     * application having the control deletes its AudioEffect object.
     * The EVENT_ENABLE_STATUS_CHANGED event is received by all applications not having the
     * control of the effect engine when the effect is enabled or disabled.
     * The EVENT_PARAMETER_CHANGED event is received by all applications not having the
     * control of the effect engine when an effect parameter is changed.
     * The EVENT_ERROR event is received when the media server process dies.
     *
     * Parameters:
     *
     * event:   type of event notified (see enum AudioEffect::event_type).
     * user:    Pointer to context for use by the callback receiver.
     * info:    Pointer to optional parameter according to event type:
     *  - EVENT_CONTROL_STATUS_CHANGED:  boolean indicating if control is granted (true)
     *  or stolen (false).
     *  - EVENT_ENABLE_STATUS_CHANGED: boolean indicating if effect is now enabled (true)
     *  or disabled (false).
     *  - EVENT_PARAMETER_CHANGED: pointer to a effect_param_t structure.
     *  - EVENT_ERROR:  status_t indicating the error (DEAD_OBJECT when media server dies).
     */

    typedef void (*effect_callback_t)(int32_t event, void* user, void *info);


    /* Constructor.
     * AudioEffect is the base class for creating and controlling an effect engine from
     * the application process. Creating an AudioEffect object will create the effect engine
     * in the AudioFlinger if no engine of the specified type exists. If one exists, this engine
     * will be used. The application creating the AudioEffect object (or a derived class like
     * Reverb for instance) will either receive control of the effect engine or not, depending
     * on the priority parameter. If priority is higher than the priority used by the current
     * effect engine owner, the control will be transfered to the new application. Otherwise
     * control will remain to the previous application. In this case, the new application will be
     * notified of changes in effect engine state or control ownership by the effect callback.
     * After creating the AudioEffect, the application must call the initCheck() method and
     * check the creation status before trying to control the effect engine (see initCheck()).
     * If the effect is to be applied to an AudioTrack or MediaPlayer only the application
     * must specify the audio session ID corresponding to this player.
     */

    /* Simple Constructor.
     */
    AudioEffect();


    /* Constructor.
     *
     * Parameters:
     *
     * type:  type of effect created: can be null if uuid is specified. This corresponds to
     *        the OpenSL ES interface implemented by this effect.
     * uuid:  Uuid of effect created: can be null if type is specified. This uuid corresponds to
     *        a particular implementation of an effect type.
     * priority:    requested priority for effect control: the priority level corresponds to the
     *      value of priority parameter: negative values indicate lower priorities, positive values
     *      higher priorities, 0 being the normal priority.
     * cbf:         optional callback function (see effect_callback_t)
     * user:        pointer to context for use by the callback receiver.
     * sessionID:   audio session this effect is associated to. If 0, the effect will be global to
     *      the output mix. If not 0, the effect will be applied to all players
     *      (AudioTrack or MediaPLayer) within the same audio session.
     * io:  HAL audio output or input stream to which this effect must be attached. Leave at 0 for
     *      automatic output selection by AudioFlinger.
     */

    AudioEffect(const effect_uuid_t *type,
                const effect_uuid_t *uuid = NULL,
                  int32_t priority = 0,
                  effect_callback_t cbf = 0,
                  void* user = 0,
                  int sessionId = 0,
                  audio_io_handle_t io = 0
                  );

    /* Constructor.
     *      Same as above but with type and uuid specified by character strings
     */
    AudioEffect(const char *typeStr,
                    const char *uuidStr = NULL,
                    int32_t priority = 0,
                    effect_callback_t cbf = 0,
                    void* user = 0,
                    int sessionId = 0,
                    audio_io_handle_t io = 0
                    );

    /* Terminates the AudioEffect and unregisters it from AudioFlinger.
     * The effect engine is also destroyed if this AudioEffect was the last controlling
     * the engine.
     */
                        ~AudioEffect();

    /* Initialize an uninitialized AudioEffect.
    * Returned status (from utils/Errors.h) can be:
    *  - NO_ERROR or ALREADY_EXISTS: successful initialization
    *  - INVALID_OPERATION: AudioEffect is already initialized
    *  - BAD_VALUE: invalid parameter
    *  - NO_INIT: audio flinger or audio hardware not initialized
    * */
            status_t    set(const effect_uuid_t *type,
                            const effect_uuid_t *uuid = NULL,
                            int32_t priority = 0,
                            effect_callback_t cbf = 0,
                            void* user = 0,
                            int sessionId = 0,
                            audio_io_handle_t io = 0
                            );

    /* Result of constructing the AudioEffect. This must be checked
     * before using any AudioEffect API.
     * initCheck() can return:
     *  - NO_ERROR:    the effect engine is successfully created and the application has control.
     *  - ALREADY_EXISTS: the effect engine is successfully created but the application does not
     *              have control.
     *  - NO_INIT:     the effect creation failed.
     *
     */
            status_t    initCheck() const;


    /* Returns the unique effect Id for the controlled effect engine. This ID is unique
     * system wide and is used for instance in the case of auxiliary effects to attach
     * the effect to an AudioTrack or MediaPlayer.
     *
     */
            int32_t     id() const { return mId; }

    /* Returns a descriptor for the effect (see effect_descriptor_t in audio_effect.h).
     */
            effect_descriptor_t descriptor() const;

    /* Returns effect control priority of this AudioEffect object.
     */
            int32_t     priority() const { return mPriority; }


    /* Enables or disables the effect engine.
     *
     * Parameters:
     *  enabled: requested enable state.
     *
     * Returned status (from utils/Errors.h) can be:
     *  - NO_ERROR: successful operation
     *  - INVALID_OPERATION: the application does not have control of the effect engine or the
     *  effect is already in the requested state.
     */
    virtual status_t    setEnabled(bool enabled);
            bool        getEnabled() const;

    /* Sets a parameter value.
     *
     * Parameters:
     *      param:  pointer to effect_param_t structure containing the parameter
     *          and its value (See audio_effect.h).
     * Returned status (from utils/Errors.h) can be:
     *  - NO_ERROR: successful operation.
     *  - INVALID_OPERATION: the application does not have control of the effect engine.
     *  - BAD_VALUE: invalid parameter identifier or value.
     *  - DEAD_OBJECT: the effect engine has been deleted.
     */
     virtual status_t   setParameter(effect_param_t *param);

    /* Prepare a new parameter value that will be set by next call to
     * setParameterCommit(). This method can be used to set multiple parameters
     * in a synchronous manner or to avoid multiple binder calls for each
     * parameter.
     *
     * Parameters:
     *      param:  pointer to effect_param_t structure containing the parameter
     *          and its value (See audio_effect.h).
     *
     * Returned status (from utils/Errors.h) can be:
     *  - NO_ERROR: successful operation.
     *  - INVALID_OPERATION: the application does not have control of the effect engine.
     *  - NO_MEMORY: no more space available in shared memory used for deferred parameter
     *  setting.
     */
     virtual status_t   setParameterDeferred(effect_param_t *param);

     /* Commit all parameter values previously prepared by setParameterDeferred().
      *
      * Parameters:
      *     none
      *
      * Returned status (from utils/Errors.h) can be:
      *  - NO_ERROR: successful operation.
      *  - INVALID_OPERATION: No new parameter values ready for commit.
      *  - BAD_VALUE: invalid parameter identifier or value: there is no indication
      *     as to which of the parameters caused this error.
      *  - DEAD_OBJECT: the effect engine has been deleted.
      */
     virtual status_t   setParameterCommit();

    /* Gets a parameter value.
     *
     * Parameters:
     *      param:  pointer to effect_param_t structure containing the parameter
     *          and the returned value (See audio_effect.h).
     *
     * Returned status (from utils/Errors.h) can be:
     *  - NO_ERROR: successful operation.
     *  - INVALID_OPERATION: the AudioEffect was not successfully initialized.
     *  - BAD_VALUE: invalid parameter identifier.
     *  - DEAD_OBJECT: the effect engine has been deleted.
     */
     virtual status_t   getParameter(effect_param_t *param);

     /* Sends a command and receives a response to/from effect engine.
      *     See audio_effect.h for details on effect command() function, valid command codes
      *     and formats.
      */
     virtual status_t command(uint32_t cmdCode,
                              uint32_t cmdSize,
                              void *cmdData,
                              uint32_t *replySize,
                              void *replyData);


     /*
      * Utility functions.
      */

     /* Converts the string passed as first argument to the effect_uuid_t
      * pointed to by second argument
      */
     static status_t stringToGuid(const char *str, effect_uuid_t *guid);
     /* Converts the effect_uuid_t pointed to by first argument to the
      * string passed as second argument
      */
     static status_t guidToString(const effect_uuid_t *guid, char *str, size_t maxLen);

protected:
     bool                    mEnabled;           // enable state
     int32_t                 mSessionId;         // audio session ID
     int32_t                 mPriority;          // priority for effect control
     status_t                mStatus;            // effect status
     effect_callback_t       mCbf;               // callback function for status, control and
                                                 // parameter changes notifications
     void*                   mUserData;          // client context for callback function
     effect_descriptor_t     mDescriptor;        // effect descriptor
     int32_t                 mId;                // system wide unique effect engine instance ID
     Mutex                   mLock;               // Mutex for mEnabled access

private:

     // Implements the IEffectClient interface
    class EffectClient : public android::BnEffectClient,  public android::IBinder::DeathRecipient
    {
    public:

        EffectClient(AudioEffect *effect) : mEffect(effect){}

        // IEffectClient
        virtual void controlStatusChanged(bool controlGranted) {
            mEffect->controlStatusChanged(controlGranted);
        }
        virtual void enableStatusChanged(bool enabled) {
            mEffect->enableStatusChanged(enabled);
        }
        virtual void commandExecuted(uint32_t cmdCode,
                                     uint32_t cmdSize,
                                     void *pCmdData,
                                     uint32_t replySize,
                                     void *pReplyData) {
            mEffect->commandExecuted(cmdCode, cmdSize, pCmdData, replySize, pReplyData);
        }

        // IBinder::DeathRecipient
        virtual void binderDied(const wp<IBinder>& who) {mEffect->binderDied();}

    private:
        AudioEffect *mEffect;
    };


    friend class EffectClient;

    // IEffectClient
    void controlStatusChanged(bool controlGranted);
    void enableStatusChanged(bool enabled);
    void commandExecuted(uint32_t cmdCode,
                         uint32_t cmdSize,
                         void *pCmdData,
                         uint32_t replySize,
                         void *pReplyData);
    void binderDied();


    sp<IEffect>             mIEffect;           // IEffect binder interface
    sp<EffectClient>        mIEffectClient;     // IEffectClient implementation
    sp<IMemory>             mCblkMemory;        // shared memory for deferred parameter setting
    effect_param_cblk_t*    mCblk;              // control block for deferred parameter setting
};


}; // namespace android

#endif // ANDROID_AUDIOEFFECT_H
