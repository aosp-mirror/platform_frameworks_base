/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.media.audiofx;

import android.annotation.RequiresPermission;
import android.app.ActivityThread;
import android.util.Log;
import java.util.UUID;

/**
 * SourceDefaultEffect is a default effect that attaches automatically to all AudioRecord and
 * MediaRecorder instances of a given source type.
 * <p>see {@link android.media.audiofx.DefaultEffect} class for more details on default effects.
 * @hide
 */

public class SourceDefaultEffect extends DefaultEffect {
    static {
        System.loadLibrary("audioeffect_jni");
    }

    private final static String TAG = "SourceDefaultEffect-JAVA";

    /**
     * Class constructor.
     *
     * @param type type of effect engine to be default. This parameter is ignored if uuid is set,
     *             and can be set to {@link android.media.audiofx.AudioEffect#EFFECT_TYPE_NULL}
     *             in that case.
     * @param uuid unique identifier of a particular effect implementation to be default. This
     *             parameter can be set to
     *             {@link android.media.audiofx.AudioEffect#EFFECT_TYPE_NULL}, in which case only
     *             the type will be used to select the effect.
     * @param priority the priority level requested by the application for controlling the effect
     *             engine. As the same engine can be shared by several applications, this parameter
     *             indicates how much the requesting application needs control of effect parameters.
     *             The normal priority is 0, above normal is a positive number, below normal a
     *             negative number.
     * @param source a MediaRecorder.AudioSource.* constant from
     *             {@link android.media.MediaRecorder.AudioSource} indicating
     *             what sources the given effect should attach to by default. Note that similar
     *             sources may share defaults.
     *
     * @throws java.lang.IllegalArgumentException
     * @throws java.lang.UnsupportedOperationException
     * @throws java.lang.RuntimeException
     */
    @RequiresPermission(value = android.Manifest.permission.MODIFY_DEFAULT_AUDIO_EFFECTS,
                        conditional = true)  // Android Things uses an alternate permission.
    public SourceDefaultEffect(UUID type, UUID uuid, int priority, int source) {
        int[] id = new int[1];
        int initResult = native_setup(type.toString(),
                                      uuid.toString(),
                                      priority,
                                      source,
                                      ActivityThread.currentOpPackageName(),
                                      id);
        if (initResult != AudioEffect.SUCCESS) {
            Log.e(TAG, "Error code " + initResult + " when initializing SourceDefaultEffect");
            switch (initResult) {
                case AudioEffect.ERROR_BAD_VALUE:
                    throw (new IllegalArgumentException(
                            "Source, type uuid, or implementation uuid not supported."));
                case AudioEffect.ERROR_INVALID_OPERATION:
                    throw (new UnsupportedOperationException(
                            "Effect library not loaded"));
                default:
                    throw (new RuntimeException(
                            "Cannot initialize effect engine for type: " + type
                            + " Error: " + initResult));
            }
        }

        mId = id[0];
    }


    /**
     * Releases the native SourceDefaultEffect resources. It is a good practice to
     * release the default effect when done with use as control can be returned to
     * other applications or the native resources released.
     */
    public void release() {
        native_release(mId);
    }

    @Override
    protected void finalize() {
        release();
    }

    // ---------------------------------------------------------
    // Native methods called from the Java side
    // --------------------

    private native final int native_setup(String type,
                                          String uuid,
                                          int priority,
                                          int source,
                                          String opPackageName,
                                          int[] id);

    private native final void native_release(int id);
}
