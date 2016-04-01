/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.hardware.soundtrigger;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.hardware.soundtrigger.SoundTrigger.GenericRecognitionEvent;
import android.hardware.soundtrigger.SoundTrigger.GenericSoundModel;
import android.hardware.soundtrigger.SoundTrigger.KeyphraseRecognitionEvent;
import android.hardware.soundtrigger.SoundTrigger.RecognitionConfig;
import android.media.soundtrigger.SoundTriggerManager;
import android.os.ParcelUuid;
import android.os.ServiceManager;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.app.ISoundTriggerService;

import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;
import java.util.UUID;

import org.mockito.MockitoAnnotations;

public class GenericSoundModelTest extends AndroidTestCase {
    static final int MSG_DETECTION_ERROR = -1;
    static final int MSG_DETECTION_RESUME = 0;
    static final int MSG_DETECTION_PAUSE = 1;
    static final int MSG_KEYPHRASE_TRIGGER = 2;
    static final int MSG_GENERIC_TRIGGER = 4;

    private Random random = new Random();
    private HashSet<UUID> loadedModelUuids;
    private ISoundTriggerService soundTriggerService;
    private SoundTriggerManager soundTriggerManager;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);

        Context context = getContext();
        soundTriggerService = ISoundTriggerService.Stub.asInterface(
                ServiceManager.getService(Context.SOUND_TRIGGER_SERVICE));
        soundTriggerManager = (SoundTriggerManager) context.getSystemService(
                Context.SOUND_TRIGGER_SERVICE);

        loadedModelUuids = new HashSet<UUID>();
    }

    @Override
    public void tearDown() throws Exception {
        for (UUID modelUuid : loadedModelUuids) {
            soundTriggerService.deleteSoundModel(new ParcelUuid(modelUuid));
        }
        super.tearDown();
    }

    GenericSoundModel new_sound_model() {
        // Create sound model
        byte[] data = new byte[1024];
        random.nextBytes(data);
        UUID modelUuid = UUID.randomUUID();
        UUID mVendorUuid = UUID.randomUUID();
        return new GenericSoundModel(modelUuid, mVendorUuid, data);
    }

    @SmallTest
    public void testUpdateGenericSoundModel() throws Exception {
        GenericSoundModel model = new_sound_model();

        // Update sound model
        soundTriggerService.updateSoundModel(model);
        loadedModelUuids.add(model.uuid);

        // Confirm it was updated
        GenericSoundModel returnedModel =
                soundTriggerService.getSoundModel(new ParcelUuid(model.uuid));
        assertEquals(model, returnedModel);
    }

    @SmallTest
    public void testDeleteGenericSoundModel() throws Exception {
        GenericSoundModel model = new_sound_model();

        // Update sound model
        soundTriggerService.updateSoundModel(model);
        loadedModelUuids.add(model.uuid);

        // Delete sound model
        soundTriggerService.deleteSoundModel(new ParcelUuid(model.uuid));
        loadedModelUuids.remove(model.uuid);

        // Confirm it was deleted
        GenericSoundModel returnedModel =
                soundTriggerService.getSoundModel(new ParcelUuid(model.uuid));
        assertEquals(null, returnedModel);
    }

    @LargeTest
    public void testStartStopGenericSoundModel() throws Exception {
        GenericSoundModel model = new_sound_model();

        boolean captureTriggerAudio = true;
        boolean allowMultipleTriggers = true;
        RecognitionConfig config = new RecognitionConfig(captureTriggerAudio, allowMultipleTriggers,
                null, null);
        TestRecognitionStatusCallback spyCallback = spy(new TestRecognitionStatusCallback());

        // Update and start sound model recognition
        soundTriggerService.updateSoundModel(model);
        loadedModelUuids.add(model.uuid);
        int r = soundTriggerService.startRecognition(new ParcelUuid(model.uuid), spyCallback,
                config);
        assertEquals("Could Not Start Recognition with code: " + r,
                android.hardware.soundtrigger.SoundTrigger.STATUS_OK, r);

        // Stop recognition
        r = soundTriggerService.stopRecognition(new ParcelUuid(model.uuid), spyCallback);
        assertEquals("Could Not Stop Recognition with code: " + r,
                android.hardware.soundtrigger.SoundTrigger.STATUS_OK, r);
    }

    @LargeTest
    public void testTriggerGenericSoundModel() throws Exception {
        GenericSoundModel model = new_sound_model();

        boolean captureTriggerAudio = true;
        boolean allowMultipleTriggers = true;
        RecognitionConfig config = new RecognitionConfig(captureTriggerAudio, allowMultipleTriggers,
                null, null);
        TestRecognitionStatusCallback spyCallback = spy(new TestRecognitionStatusCallback());

        // Update and start sound model
        soundTriggerService.updateSoundModel(model);
        loadedModelUuids.add(model.uuid);
        soundTriggerService.startRecognition(new ParcelUuid(model.uuid), spyCallback, config);

        // Send trigger to stub HAL
        Socket socket = new Socket(InetAddress.getLocalHost(), 14035);
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        out.writeBytes("trig " + model.uuid.toString() + "\r\n");
        out.flush();
        socket.close();

        // Verify trigger was received
        verify(spyCallback, timeout(100)).onGenericSoundTriggerDetected(any());
    }

    /**
     * Tests a more complicated pattern of loading, unloading, triggering, starting and stopping
     * recognition. Intended to find unexpected errors that occur in unexpected states.
     */
    @LargeTest
    public void testFuzzGenericSoundModel() throws Exception {
        int numModels = 2;

        final int STATUS_UNLOADED = 0;
        final int STATUS_LOADED = 1;
        final int STATUS_STARTED = 2;

        class ModelInfo {
            int status;
            GenericSoundModel model;

            public ModelInfo(GenericSoundModel model, int status) {
                this.status = status;
                this.model = model;
            }
        }

        Random predictableRandom = new Random(100);

        ArrayList modelInfos = new ArrayList<ModelInfo>();
        for(int i=0; i<numModels; i++) {
            // Create sound model
            byte[] data = new byte[1024];
            predictableRandom.nextBytes(data);
            UUID modelUuid = UUID.randomUUID();
            UUID mVendorUuid = UUID.randomUUID();
            GenericSoundModel model = new GenericSoundModel(modelUuid, mVendorUuid, data);
            ModelInfo modelInfo = new ModelInfo(model, STATUS_UNLOADED);
            modelInfos.add(modelInfo);
        }

        boolean captureTriggerAudio = true;
        boolean allowMultipleTriggers = true;
        RecognitionConfig config = new RecognitionConfig(captureTriggerAudio, allowMultipleTriggers,
                null, null);
        TestRecognitionStatusCallback spyCallback = spy(new TestRecognitionStatusCallback());


        int numOperationsToRun = 100;
        for(int i=0; i<numOperationsToRun; i++) {
            // Select a random model
            int modelInfoIndex = predictableRandom.nextInt(modelInfos.size());
            ModelInfo modelInfo = (ModelInfo) modelInfos.get(modelInfoIndex);

            // Perform a random operation
            int operation = predictableRandom.nextInt(5);

            if (operation == 0 && modelInfo.status == STATUS_UNLOADED) {
                // Update and start sound model
                soundTriggerService.updateSoundModel(modelInfo.model);
                loadedModelUuids.add(modelInfo.model.uuid);
                modelInfo.status = STATUS_LOADED;
            } else if (operation == 1 && modelInfo.status == STATUS_LOADED) {
                // Start the sound model
                int r = soundTriggerService.startRecognition(new ParcelUuid(modelInfo.model.uuid),
                        spyCallback, config);
                assertEquals("Could Not Start Recognition with code: " + r,
                        android.hardware.soundtrigger.SoundTrigger.STATUS_OK, r);
                modelInfo.status = STATUS_STARTED;
            } else if (operation == 2 && modelInfo.status == STATUS_STARTED) {
                // Send trigger to stub HAL
                Socket socket = new Socket(InetAddress.getLocalHost(), 14035);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                out.writeBytes("trig " + modelInfo.model.uuid + "\r\n");
                out.flush();
                socket.close();

                // Verify trigger was received
                verify(spyCallback, timeout(100)).onGenericSoundTriggerDetected(any());
                reset(spyCallback);
            } else if (operation == 3 && modelInfo.status == STATUS_STARTED) {
                // Stop recognition
                int r = soundTriggerService.stopRecognition(new ParcelUuid(modelInfo.model.uuid),
                        spyCallback);
                assertEquals("Could Not Stop Recognition with code: " + r,
                        android.hardware.soundtrigger.SoundTrigger.STATUS_OK, r);
                modelInfo.status = STATUS_LOADED;
            } else if (operation == 4 && modelInfo.status != STATUS_UNLOADED) {
                // Delete sound model
                soundTriggerService.deleteSoundModel(new ParcelUuid(modelInfo.model.uuid));
                loadedModelUuids.remove(modelInfo.model.uuid);

                // Confirm it was deleted
                GenericSoundModel returnedModel =
                        soundTriggerService.getSoundModel(new ParcelUuid(modelInfo.model.uuid));
                assertEquals(null, returnedModel);
                modelInfo.status = STATUS_UNLOADED;
            }
        }
    }

    public class TestRecognitionStatusCallback extends IRecognitionStatusCallback.Stub {
        @Override
        public void onGenericSoundTriggerDetected(GenericRecognitionEvent recognitionEvent) {
        }

        @Override
        public void onKeyphraseDetected(KeyphraseRecognitionEvent recognitionEvent) {
        }

        @Override
        public void onError(int status) {
        }

        @Override
        public void onRecognitionPaused() {
        }

        @Override
        public void onRecognitionResumed() {
        }
    }
}
