/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.powerstats;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.hardware.power.stats.Channel;
import android.hardware.power.stats.EnergyConsumer;
import android.hardware.power.stats.EnergyConsumerAttribution;
import android.hardware.power.stats.EnergyConsumerResult;
import android.hardware.power.stats.EnergyMeasurement;
import android.hardware.power.stats.PowerEntity;
import android.hardware.power.stats.State;
import android.hardware.power.stats.StateResidency;
import android.hardware.power.stats.StateResidencyResult;

import androidx.test.InstrumentationRegistry;

import com.android.server.SystemService;
import com.android.server.powerstats.PowerStatsHALWrapper.IPowerStatsHALWrapper;
import com.android.server.powerstats.ProtoStreamUtils.ChannelUtils;
import com.android.server.powerstats.ProtoStreamUtils.EnergyConsumerUtils;
import com.android.server.powerstats.ProtoStreamUtils.PowerEntityUtils;
import com.android.server.powerstats.nano.PowerEntityProto;
import com.android.server.powerstats.nano.PowerStatsServiceMeterProto;
import com.android.server.powerstats.nano.PowerStatsServiceModelProto;
import com.android.server.powerstats.nano.PowerStatsServiceResidencyProto;
import com.android.server.powerstats.nano.StateProto;
import com.android.server.powerstats.nano.StateResidencyProto;
import com.android.server.powerstats.nano.StateResidencyResultProto;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;

/**
 * Tests for {@link com.android.server.powerstats.PowerStatsService}.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:PowerStatsServiceTest
 */
public class PowerStatsServiceTest {
    private static final String TAG = PowerStatsServiceTest.class.getSimpleName();
    private static final String DATA_STORAGE_SUBDIR = "powerstatstest";
    private static final String METER_FILENAME = "log.powerstats.metertest.0";
    private static final String MODEL_FILENAME = "log.powerstats.modeltest.0";
    private static final String RESIDENCY_FILENAME = "log.powerstats.residencytest.0";
    private static final String PROTO_OUTPUT_FILENAME = "powerstats.proto";
    private static final String CHANNEL_NAME = "channelname";
    private static final String CHANNEL_SUBSYSTEM = "channelsubsystem";
    private static final String POWER_ENTITY_NAME = "powerentityinfo";
    private static final String STATE_NAME = "stateinfo";
    private static final String ENERGY_CONSUMER_NAME = "energyconsumer";
    private static final String METER_CACHE_FILENAME = "meterCacheTest";
    private static final String MODEL_CACHE_FILENAME = "modelCacheTest";
    private static final String RESIDENCY_CACHE_FILENAME = "residencyCacheTest";
    private static final int ENERGY_METER_COUNT = 8;
    private static final int ENERGY_CONSUMER_COUNT = 2;
    private static final int ENERGY_CONSUMER_ATTRIBUTION_COUNT = 5;
    private static final int POWER_ENTITY_COUNT = 3;
    private static final int STATE_INFO_COUNT = 5;
    private static final int STATE_RESIDENCY_COUNT = 4;

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();
    private PowerStatsService mService;
    private File mDataStorageDir;
    private TimerTrigger mTimerTrigger;
    private BatteryTrigger mBatteryTrigger;
    private PowerStatsLogger mPowerStatsLogger;

    private final PowerStatsService.Injector mInjector = new PowerStatsService.Injector() {
        private TestPowerStatsHALWrapper mTestPowerStatsHALWrapper = new TestPowerStatsHALWrapper();
        @Override
        File createDataStoragePath() {
            if (mDataStorageDir == null) {
                try {
                    mDataStorageDir = Files.createTempDirectory(DATA_STORAGE_SUBDIR).toFile();
                } catch (IOException e) {
                    fail("Could not create temp directory.");
                }
            }

            return mDataStorageDir;
        }

        @Override
        String createMeterFilename() {
            return METER_FILENAME;
        }

        @Override
        String createModelFilename() {
            return MODEL_FILENAME;
        }

        @Override
        String createResidencyFilename() {
            return RESIDENCY_FILENAME;
        }

        @Override
        String createMeterCacheFilename() {
            return METER_CACHE_FILENAME;
        }

        @Override
        String createModelCacheFilename() {
            return MODEL_CACHE_FILENAME;
        }

        @Override
        String createResidencyCacheFilename() {
            return RESIDENCY_CACHE_FILENAME;
        }

        @Override
        IPowerStatsHALWrapper getPowerStatsHALWrapperImpl() {
            return mTestPowerStatsHALWrapper;
        }

        @Override
        PowerStatsLogger createPowerStatsLogger(Context context, File dataStoragePath,
                String meterFilename, String meterCacheFilename,
                String modelFilename, String modelCacheFilename,
                String residencyFilename, String residencyCacheFilename,
                IPowerStatsHALWrapper powerStatsHALWrapper) {
            mPowerStatsLogger = new PowerStatsLogger(context, dataStoragePath,
                meterFilename, meterCacheFilename,
                modelFilename, modelCacheFilename,
                residencyFilename, residencyCacheFilename,
                powerStatsHALWrapper);
            return mPowerStatsLogger;
        }

        @Override
        BatteryTrigger createBatteryTrigger(Context context, PowerStatsLogger powerStatsLogger) {
            mBatteryTrigger = new BatteryTrigger(context, powerStatsLogger,
                false /* trigger enabled */);
            return mBatteryTrigger;
        }

        @Override
        TimerTrigger createTimerTrigger(Context context, PowerStatsLogger powerStatsLogger) {
            mTimerTrigger = new TimerTrigger(context, powerStatsLogger,
                false /* trigger enabled */);
            return mTimerTrigger;
        }
    };

    public static final class TestPowerStatsHALWrapper implements IPowerStatsHALWrapper {
        @Override
        public PowerEntity[] getPowerEntityInfo() {
            PowerEntity[] powerEntityList = new PowerEntity[POWER_ENTITY_COUNT];
            for (int i = 0; i < powerEntityList.length; i++) {
                powerEntityList[i] = new PowerEntity();
                powerEntityList[i].id = i;
                powerEntityList[i].name = new String(POWER_ENTITY_NAME + i);
                powerEntityList[i].states = new State[STATE_INFO_COUNT];
                for (int j = 0; j < powerEntityList[i].states.length; j++) {
                    powerEntityList[i].states[j] = new State();
                    powerEntityList[i].states[j].id = j;
                    powerEntityList[i].states[j].name = new String(STATE_NAME + j);
                }
            }
            return powerEntityList;
        }

        @Override
        public StateResidencyResult[] getStateResidency(int[] powerEntityIds) {
            StateResidencyResult[] stateResidencyResultList =
                new StateResidencyResult[POWER_ENTITY_COUNT];
            for (int i = 0; i < stateResidencyResultList.length; i++) {
                stateResidencyResultList[i] = new StateResidencyResult();
                stateResidencyResultList[i].id = i;
                stateResidencyResultList[i].stateResidencyData =
                    new StateResidency[STATE_RESIDENCY_COUNT];
                for (int j = 0; j < stateResidencyResultList[i].stateResidencyData.length; j++) {
                    stateResidencyResultList[i].stateResidencyData[j] = new StateResidency();
                    stateResidencyResultList[i].stateResidencyData[j].id = j;
                    stateResidencyResultList[i].stateResidencyData[j].totalTimeInStateMs = j;
                    stateResidencyResultList[i].stateResidencyData[j].totalStateEntryCount = j;
                    stateResidencyResultList[i].stateResidencyData[j].lastEntryTimestampMs = j;
                }
            }

            return stateResidencyResultList;
        }

        @Override
        public EnergyConsumer[] getEnergyConsumerInfo() {
            EnergyConsumer[] energyConsumerList = new EnergyConsumer[ENERGY_CONSUMER_COUNT];
            for (int i = 0; i < energyConsumerList.length; i++) {
                energyConsumerList[i] = new EnergyConsumer();
                energyConsumerList[i].id = i;
                energyConsumerList[i].ordinal = i;
                energyConsumerList[i].type = (byte) i;
                energyConsumerList[i].name = new String(ENERGY_CONSUMER_NAME + i);
            }
            return energyConsumerList;
        }

        @Override
        public EnergyConsumerResult[] getEnergyConsumed(int[] energyConsumerIds) {
            EnergyConsumerResult[] energyConsumedList =
                new EnergyConsumerResult[ENERGY_CONSUMER_COUNT];
            for (int i = 0; i < energyConsumedList.length; i++) {
                energyConsumedList[i] = new EnergyConsumerResult();
                energyConsumedList[i].id = i;
                energyConsumedList[i].timestampMs = i;
                energyConsumedList[i].energyUWs = i;
                energyConsumedList[i].attribution =
                    new EnergyConsumerAttribution[ENERGY_CONSUMER_ATTRIBUTION_COUNT];
                for (int j = 0; j < energyConsumedList[i].attribution.length; j++) {
                    energyConsumedList[i].attribution[j] = new EnergyConsumerAttribution();
                    energyConsumedList[i].attribution[j].uid = j;
                    energyConsumedList[i].attribution[j].energyUWs = j;
                }
            }
            return energyConsumedList;
        }

        @Override
        public Channel[] getEnergyMeterInfo() {
            Channel[] energyMeterList = new Channel[ENERGY_METER_COUNT];
            for (int i = 0; i < energyMeterList.length; i++) {
                energyMeterList[i] = new Channel();
                energyMeterList[i].id = i;
                energyMeterList[i].name = new String(CHANNEL_NAME + i);
                energyMeterList[i].subsystem = new String(CHANNEL_SUBSYSTEM + i);
            }
            return energyMeterList;
        }

        @Override
        public EnergyMeasurement[] readEnergyMeter(int[] channelIds) {
            EnergyMeasurement[] energyMeasurementList = new EnergyMeasurement[ENERGY_METER_COUNT];
            for (int i = 0; i < energyMeasurementList.length; i++) {
                energyMeasurementList[i] = new EnergyMeasurement();
                energyMeasurementList[i].id = i;
                energyMeasurementList[i].timestampMs = i;
                energyMeasurementList[i].durationMs = i;
                energyMeasurementList[i].energyUWs = i;
            }
            return energyMeasurementList;
        }

        @Override
        public boolean isInitialized() {
            return true;
        }
    }

    @Before
    public void setUp() {
        mService = new PowerStatsService(mContext, mInjector);
    }

    @Test
    public void testWrittenMeterDataMatchesReadIncidentReportData()
            throws InterruptedException, IOException {
        mService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);

        // Write data to on-device storage.
        mTimerTrigger.logPowerStatsData(PowerStatsLogger.MSG_LOG_TO_DATA_STORAGE_HIGH_FREQUENCY);

        // The above call puts a message on a handler.  Wait for
        // it to be processed.
        Thread.sleep(100);

        // Write on-device storage to an incident report.
        File incidentReport = new File(mDataStorageDir, PROTO_OUTPUT_FILENAME);
        FileOutputStream fos = new FileOutputStream(incidentReport);
        mPowerStatsLogger.writeMeterDataToFile(fos.getFD());

        // Read the incident report in to a byte array.
        FileInputStream fis = new FileInputStream(incidentReport);
        byte[] fileContent = new byte[(int) incidentReport.length()];
        fis.read(fileContent);

        // Parse the incident data into a PowerStatsServiceMeterProto object.
        PowerStatsServiceMeterProto pssProto = PowerStatsServiceMeterProto.parseFrom(fileContent);

        // Validate the channel array matches what was written to on-device storage.
        assertTrue(pssProto.channel.length == ENERGY_METER_COUNT);
        for (int i = 0; i < pssProto.channel.length; i++) {
            assertTrue(pssProto.channel[i].id == i);
            assertTrue(pssProto.channel[i].name.equals(CHANNEL_NAME + i));
            assertTrue(pssProto.channel[i].subsystem.equals(CHANNEL_SUBSYSTEM + i));
        }

        // Validate the energyMeasurement array matches what was written to on-device storage.
        assertTrue(pssProto.energyMeasurement.length == ENERGY_METER_COUNT);
        for (int i = 0; i < pssProto.energyMeasurement.length; i++) {
            assertTrue(pssProto.energyMeasurement[i].id == i);
            assertTrue(pssProto.energyMeasurement[i].timestampMs ==
                    i + mPowerStatsLogger.getStartWallTime());
            assertTrue(pssProto.energyMeasurement[i].durationMs == i);
            assertTrue(pssProto.energyMeasurement[i].energyUws == i);
        }
    }

    @Test
    public void testWrittenModelDataMatchesReadIncidentReportData()
            throws InterruptedException, IOException {
        mService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);

        // Write data to on-device storage.
        mTimerTrigger.logPowerStatsData(PowerStatsLogger.MSG_LOG_TO_DATA_STORAGE_LOW_FREQUENCY);

        // The above call puts a message on a handler.  Wait for
        // it to be processed.
        Thread.sleep(100);

        // Write on-device storage to an incident report.
        File incidentReport = new File(mDataStorageDir, PROTO_OUTPUT_FILENAME);
        FileOutputStream fos = new FileOutputStream(incidentReport);
        mPowerStatsLogger.writeModelDataToFile(fos.getFD());

        // Read the incident report in to a byte array.
        FileInputStream fis = new FileInputStream(incidentReport);
        byte[] fileContent = new byte[(int) incidentReport.length()];
        fis.read(fileContent);

        // Parse the incident data into a PowerStatsServiceModelProto object.
        PowerStatsServiceModelProto pssProto = PowerStatsServiceModelProto.parseFrom(fileContent);

        // Validate the energyConsumer array matches what was written to on-device storage.
        assertTrue(pssProto.energyConsumer.length == ENERGY_CONSUMER_COUNT);
        for (int i = 0; i < pssProto.energyConsumer.length; i++) {
            assertTrue(pssProto.energyConsumer[i].id == i);
        }

        // Validate the energyConsumerResult array matches what was written to on-device storage.
        assertTrue(pssProto.energyConsumerResult.length == ENERGY_CONSUMER_COUNT);
        for (int i = 0; i < pssProto.energyConsumerResult.length; i++) {
            assertTrue(pssProto.energyConsumerResult[i].id == i);
            assertTrue(pssProto.energyConsumerResult[i].timestampMs ==
                    i + mPowerStatsLogger.getStartWallTime());
            assertTrue(pssProto.energyConsumerResult[i].energyUws == i);
            assertTrue(pssProto.energyConsumerResult[i].attribution.length
                    == ENERGY_CONSUMER_ATTRIBUTION_COUNT);
            for (int j = 0; j < pssProto.energyConsumerResult[i].attribution.length; j++) {
                assertTrue(pssProto.energyConsumerResult[i].attribution[j].uid == j);
                assertTrue(pssProto.energyConsumerResult[i].attribution[j].energyUws  == j);
            }
        }
    }

    @Test
    public void testWrittenResidencyDataMatchesReadIncidentReportData()
            throws InterruptedException, IOException {
        mService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);

        // Write data to on-device storage.
        mBatteryTrigger.logPowerStatsData(PowerStatsLogger.MSG_LOG_TO_DATA_STORAGE_BATTERY_DROP);

        // The above call puts a message on a handler.  Wait for
        // it to be processed.
        Thread.sleep(100);

        // Write on-device storage to an incident report.
        File incidentReport = new File(mDataStorageDir, PROTO_OUTPUT_FILENAME);
        FileOutputStream fos = new FileOutputStream(incidentReport);
        mPowerStatsLogger.writeResidencyDataToFile(fos.getFD());

        // Read the incident report in to a byte array.
        FileInputStream fis = new FileInputStream(incidentReport);
        byte[] fileContent = new byte[(int) incidentReport.length()];
        fis.read(fileContent);

        // Parse the incident data into a PowerStatsServiceResidencyProto object.
        PowerStatsServiceResidencyProto pssProto =
                PowerStatsServiceResidencyProto.parseFrom(fileContent);

        // Validate the powerEntity array matches what was written to on-device storage.
        assertTrue(pssProto.powerEntity.length == POWER_ENTITY_COUNT);
        for (int i = 0; i < pssProto.powerEntity.length; i++) {
            PowerEntityProto powerEntity = pssProto.powerEntity[i];
            assertTrue(powerEntity.id == i);
            assertTrue(powerEntity.name.equals(POWER_ENTITY_NAME + i));
            for (int j = 0; j < powerEntity.states.length; j++) {
                StateProto state = powerEntity.states[j];
                assertTrue(state.id == j);
                assertTrue(state.name.equals(STATE_NAME + j));
            }
        }

        // Validate the stateResidencyResult array matches what was written to on-device storage.
        assertTrue(pssProto.stateResidencyResult.length == POWER_ENTITY_COUNT);
        for (int i = 0; i < pssProto.stateResidencyResult.length; i++) {
            StateResidencyResultProto stateResidencyResult = pssProto.stateResidencyResult[i];
            assertTrue(stateResidencyResult.id == i);
            assertTrue(stateResidencyResult.stateResidencyData.length == STATE_RESIDENCY_COUNT);
            for (int j = 0; j < stateResidencyResult.stateResidencyData.length; j++) {
                StateResidencyProto stateResidency = stateResidencyResult.stateResidencyData[j];
                assertTrue(stateResidency.id == j);
                assertTrue(stateResidency.totalTimeInStateMs == j);
                assertTrue(stateResidency.totalStateEntryCount == j);
                assertTrue(stateResidency.lastEntryTimestampMs ==
                        j + mPowerStatsLogger.getStartWallTime());
            }
        }
    }

    @Test
    public void testCorruptOnDeviceMeterStorage() throws IOException {
        mService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);

        // Generate random array of bytes to emulate corrupt data.
        Random rd = new Random();
        byte[] bytes = new byte[100];
        rd.nextBytes(bytes);

        // Store corrupt data in on-device storage.  Add fake timestamp to filename
        // to match format expected by FileRotator.
        File onDeviceStorageFile = new File(mDataStorageDir, METER_FILENAME + ".1234-2234");
        FileOutputStream onDeviceStorageFos = new FileOutputStream(onDeviceStorageFile);
        onDeviceStorageFos.write(bytes);
        onDeviceStorageFos.close();

        // Write on-device storage to an incident report.
        File incidentReport = new File(mDataStorageDir, PROTO_OUTPUT_FILENAME);
        FileOutputStream incidentReportFos = new FileOutputStream(incidentReport);
        mPowerStatsLogger.writeMeterDataToFile(incidentReportFos.getFD());

        // Read the incident report in to a byte array.
        FileInputStream fis = new FileInputStream(incidentReport);
        byte[] fileContent = new byte[(int) incidentReport.length()];
        fis.read(fileContent);

        // Parse the incident data into a PowerStatsServiceMeterProto object.
        PowerStatsServiceMeterProto pssProto = PowerStatsServiceMeterProto.parseFrom(fileContent);

        // Valid channel data is written to the incident report in the call to
        // mPowerStatsLogger.writeMeterDataToFile().
        assertTrue(pssProto.channel.length == ENERGY_METER_COUNT);
        for (int i = 0; i < pssProto.channel.length; i++) {
            assertTrue(pssProto.channel[i].id == i);
            assertTrue(pssProto.channel[i].name.equals(CHANNEL_NAME + i));
            assertTrue(pssProto.channel[i].subsystem.equals(CHANNEL_SUBSYSTEM + i));
        }

        // No energyMeasurements should be written to the incident report since it
        // is all corrupt (random bytes generated above).
        assertTrue(pssProto.energyMeasurement.length == 0);
    }

    @Test
    public void testCorruptOnDeviceModelStorage() throws IOException {
        mService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);

        // Generate random array of bytes to emulate corrupt data.
        Random rd = new Random();
        byte[] bytes = new byte[100];
        rd.nextBytes(bytes);

        // Store corrupt data in on-device storage.  Add fake timestamp to filename
        // to match format expected by FileRotator.
        File onDeviceStorageFile = new File(mDataStorageDir, MODEL_FILENAME + ".1234-2234");
        FileOutputStream onDeviceStorageFos = new FileOutputStream(onDeviceStorageFile);
        onDeviceStorageFos.write(bytes);
        onDeviceStorageFos.close();

        // Write on-device storage to an incident report.
        File incidentReport = new File(mDataStorageDir, PROTO_OUTPUT_FILENAME);
        FileOutputStream incidentReportFos = new FileOutputStream(incidentReport);
        mPowerStatsLogger.writeModelDataToFile(incidentReportFos.getFD());

        // Read the incident report in to a byte array.
        FileInputStream fis = new FileInputStream(incidentReport);
        byte[] fileContent = new byte[(int) incidentReport.length()];
        fis.read(fileContent);

        // Parse the incident data into a PowerStatsServiceModelProto object.
        PowerStatsServiceModelProto pssProto = PowerStatsServiceModelProto.parseFrom(fileContent);

        // Valid energyConsumer data is written to the incident report in the call to
        // mPowerStatsLogger.writeModelDataToFile().
        assertTrue(pssProto.energyConsumer.length == ENERGY_CONSUMER_COUNT);
        for (int i = 0; i < pssProto.energyConsumer.length; i++) {
            assertTrue(pssProto.energyConsumer[i].id == i);
        }

        // No energyConsumerResults should be written to the incident report since it
        // is all corrupt (random bytes generated above).
        assertTrue(pssProto.energyConsumerResult.length == 0);
    }

    @Test
    public void testCorruptOnDeviceResidencyStorage() throws IOException {
        mService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);

        // Generate random array of bytes to emulate corrupt data.
        Random rd = new Random();
        byte[] bytes = new byte[100];
        rd.nextBytes(bytes);

        // Store corrupt data in on-device storage.  Add fake timestamp to filename
        // to match format expected by FileRotator.
        File onDeviceStorageFile = new File(mDataStorageDir, RESIDENCY_FILENAME + ".1234-2234");
        FileOutputStream onDeviceStorageFos = new FileOutputStream(onDeviceStorageFile);
        onDeviceStorageFos.write(bytes);
        onDeviceStorageFos.close();

        // Write on-device storage to an incident report.
        File incidentReport = new File(mDataStorageDir, PROTO_OUTPUT_FILENAME);
        FileOutputStream incidentReportFos = new FileOutputStream(incidentReport);
        mPowerStatsLogger.writeResidencyDataToFile(incidentReportFos.getFD());

        // Read the incident report in to a byte array.
        FileInputStream fis = new FileInputStream(incidentReport);
        byte[] fileContent = new byte[(int) incidentReport.length()];
        fis.read(fileContent);

        // Parse the incident data into a PowerStatsServiceResidencyProto object.
        PowerStatsServiceResidencyProto pssProto =
                PowerStatsServiceResidencyProto.parseFrom(fileContent);

        // Valid powerEntity data is written to the incident report in the call to
        // mPowerStatsLogger.writeResidencyDataToFile().
        assertTrue(pssProto.powerEntity.length == POWER_ENTITY_COUNT);
        for (int i = 0; i < pssProto.powerEntity.length; i++) {
            PowerEntityProto powerEntity = pssProto.powerEntity[i];
            assertTrue(powerEntity.id == i);
            assertTrue(powerEntity.name.equals(POWER_ENTITY_NAME + i));
            for (int j = 0; j < powerEntity.states.length; j++) {
                StateProto state = powerEntity.states[j];
                assertTrue(state.id == j);
                assertTrue(state.name.equals(STATE_NAME + j));
            }
        }

        // No stateResidencyResults should be written to the incident report since it
        // is all corrupt (random bytes generated above).
        assertTrue(pssProto.stateResidencyResult.length == 0);
    }

    @Test
    public void testNotEnoughBytesAfterMeterLengthField() throws IOException {
        mService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);

        // Create corrupt data.
        // Length field is correct, but there is no data following the length.
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(ByteBuffer.allocate(4).putInt(50).array());
        byte[] test = data.toByteArray();

        // Store corrupt data in on-device storage.  Add fake timestamp to filename
        // to match format expected by FileRotator.
        File onDeviceStorageFile = new File(mDataStorageDir, METER_FILENAME + ".1234-2234");
        FileOutputStream onDeviceStorageFos = new FileOutputStream(onDeviceStorageFile);
        onDeviceStorageFos.write(data.toByteArray());
        onDeviceStorageFos.close();

        // Write on-device storage to an incident report.
        File incidentReport = new File(mDataStorageDir, PROTO_OUTPUT_FILENAME);
        FileOutputStream incidentReportFos = new FileOutputStream(incidentReport);
        mPowerStatsLogger.writeMeterDataToFile(incidentReportFos.getFD());

        // Read the incident report in to a byte array.
        FileInputStream fis = new FileInputStream(incidentReport);
        byte[] fileContent = new byte[(int) incidentReport.length()];
        fis.read(fileContent);

        // Parse the incident data into a PowerStatsServiceMeterProto object.
        PowerStatsServiceMeterProto pssProto = PowerStatsServiceMeterProto.parseFrom(fileContent);

        // Valid channel data is written to the incident report in the call to
        // mPowerStatsLogger.writeMeterDataToFile().
        assertTrue(pssProto.channel.length == ENERGY_METER_COUNT);
        for (int i = 0; i < pssProto.channel.length; i++) {
            assertTrue(pssProto.channel[i].id == i);
            assertTrue(pssProto.channel[i].name.equals(CHANNEL_NAME + i));
            assertTrue(pssProto.channel[i].subsystem.equals(CHANNEL_SUBSYSTEM + i));
        }

        // No energyMeasurements should be written to the incident report since the
        // input buffer had only length and no data.
        assertTrue(pssProto.energyMeasurement.length == 0);
    }

    @Test
    public void testNotEnoughBytesAfterModelLengthField() throws IOException {
        mService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);

        // Create corrupt data.
        // Length field is correct, but there is no data following the length.
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(ByteBuffer.allocate(4).putInt(50).array());
        byte[] test = data.toByteArray();

        // Store corrupt data in on-device storage.  Add fake timestamp to filename
        // to match format expected by FileRotator.
        File onDeviceStorageFile = new File(mDataStorageDir, MODEL_FILENAME + ".1234-2234");
        FileOutputStream onDeviceStorageFos = new FileOutputStream(onDeviceStorageFile);
        onDeviceStorageFos.write(data.toByteArray());
        onDeviceStorageFos.close();

        // Write on-device storage to an incident report.
        File incidentReport = new File(mDataStorageDir, PROTO_OUTPUT_FILENAME);
        FileOutputStream incidentReportFos = new FileOutputStream(incidentReport);
        mPowerStatsLogger.writeModelDataToFile(incidentReportFos.getFD());

        // Read the incident report in to a byte array.
        FileInputStream fis = new FileInputStream(incidentReport);
        byte[] fileContent = new byte[(int) incidentReport.length()];
        fis.read(fileContent);

        // Parse the incident data into a PowerStatsServiceModelProto object.
        PowerStatsServiceModelProto pssProto = PowerStatsServiceModelProto.parseFrom(fileContent);

        // Valid energyConsumer data is written to the incident report in the call to
        // mPowerStatsLogger.writeModelDataToFile().
        assertTrue(pssProto.energyConsumer.length == ENERGY_CONSUMER_COUNT);
        for (int i = 0; i < pssProto.energyConsumer.length; i++) {
            assertTrue(pssProto.energyConsumer[i].id == i);
        }

        // No energyConsumerResults should be written to the incident report since the
        // input buffer had only length and no data.
        assertTrue(pssProto.energyConsumerResult.length == 0);
    }

    @Test
    public void testNotEnoughBytesAfterResidencyLengthField() throws IOException {
        mService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);

        // Create corrupt data.
        // Length field is correct, but there is no data following the length.
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(ByteBuffer.allocate(4).putInt(50).array());
        byte[] test = data.toByteArray();

        // Store corrupt data in on-device storage.  Add fake timestamp to filename
        // to match format expected by FileRotator.
        File onDeviceStorageFile = new File(mDataStorageDir, RESIDENCY_FILENAME + ".1234-2234");
        FileOutputStream onDeviceStorageFos = new FileOutputStream(onDeviceStorageFile);
        onDeviceStorageFos.write(data.toByteArray());
        onDeviceStorageFos.close();

        // Write on-device storage to an incident report.
        File incidentReport = new File(mDataStorageDir, PROTO_OUTPUT_FILENAME);
        FileOutputStream incidentReportFos = new FileOutputStream(incidentReport);
        mPowerStatsLogger.writeResidencyDataToFile(incidentReportFos.getFD());

        // Read the incident report in to a byte array.
        FileInputStream fis = new FileInputStream(incidentReport);
        byte[] fileContent = new byte[(int) incidentReport.length()];
        fis.read(fileContent);

        // Parse the incident data into a PowerStatsServiceResidencyProto object.
        PowerStatsServiceResidencyProto pssProto =
                PowerStatsServiceResidencyProto.parseFrom(fileContent);

        // Valid powerEntity data is written to the incident report in the call to
        // mPowerStatsLogger.writeResidencyDataToFile().
        assertTrue(pssProto.powerEntity.length == POWER_ENTITY_COUNT);
        for (int i = 0; i < pssProto.powerEntity.length; i++) {
            PowerEntityProto powerEntity = pssProto.powerEntity[i];
            assertTrue(powerEntity.id == i);
            assertTrue(powerEntity.name.equals(POWER_ENTITY_NAME + i));
            for (int j = 0; j < powerEntity.states.length; j++) {
                StateProto state = powerEntity.states[j];
                assertTrue(state.id == j);
                assertTrue(state.name.equals(STATE_NAME + j));
            }
        }

        // No stateResidencyResults should be written to the incident report since the
        // input buffer had only length and no data.
        assertTrue(pssProto.stateResidencyResult.length == 0);
    }

    @Test
    public void testDataStorageDeletedMeterMismatch() throws IOException {
        // Create the directory where cached data will be stored.
        mInjector.createDataStoragePath();

        // In order to create cached data that will match the current data read by the
        // PowerStatsService we need to write valid data from the TestPowerStatsHALWrapper that is
        // returned from the Injector.
        IPowerStatsHALWrapper powerStatsHALWrapper = mInjector.getPowerStatsHALWrapperImpl();

        // Generate random array of bytes to emulate cached meter data.  Store to file.
        Random rd = new Random();
        byte[] bytes = new byte[100];
        rd.nextBytes(bytes);
        File onDeviceStorageFile = new File(mDataStorageDir, mInjector.createMeterCacheFilename());
        FileOutputStream onDeviceStorageFos = new FileOutputStream(onDeviceStorageFile);
        onDeviceStorageFos.write(bytes);
        onDeviceStorageFos.close();

        // Create cached energy consumer data and write to file.
        EnergyConsumer[] energyConsumers = powerStatsHALWrapper.getEnergyConsumerInfo();
        bytes = EnergyConsumerUtils.getProtoBytes(energyConsumers);
        onDeviceStorageFile = new File(mDataStorageDir, mInjector.createModelCacheFilename());
        onDeviceStorageFos = new FileOutputStream(onDeviceStorageFile);
        onDeviceStorageFos.write(bytes);
        onDeviceStorageFos.close();

        // Create cached power entity info data and write to file.
        PowerEntity[] powerEntityInfo = powerStatsHALWrapper.getPowerEntityInfo();
        bytes = PowerEntityUtils.getProtoBytes(powerEntityInfo);
        onDeviceStorageFile = new File(mDataStorageDir, mInjector.createResidencyCacheFilename());
        onDeviceStorageFos = new FileOutputStream(onDeviceStorageFile);
        onDeviceStorageFos.write(bytes);
        onDeviceStorageFos.close();

        // Create log files.
        File meterFile = new File(mDataStorageDir, mInjector.createMeterFilename());
        File modelFile = new File(mDataStorageDir, mInjector.createModelFilename());
        File residencyFile = new File(mDataStorageDir, mInjector.createResidencyFilename());
        meterFile.createNewFile();
        modelFile.createNewFile();
        residencyFile.createNewFile();

        // Verify log files exist.
        assertTrue(meterFile.exists());
        assertTrue(modelFile.exists());
        assertTrue(residencyFile.exists());

        // Boot device after creating old cached data.
        mService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);

        // Since cached meter data is just random bytes it won't match the data read from the HAL.
        // This mismatch of cached and current HAL data should force a delete.
        assertTrue(mService.getDeleteMeterDataOnBoot());
        assertFalse(mService.getDeleteModelDataOnBoot());
        assertFalse(mService.getDeleteResidencyDataOnBoot());

        // Verify log files were deleted.
        assertFalse(meterFile.exists());
        assertTrue(modelFile.exists());
        assertTrue(residencyFile.exists());

        // Verify cached meter data was updated to new HAL output.
        Channel[] channels = powerStatsHALWrapper.getEnergyMeterInfo();
        byte[] bytesExpected = ChannelUtils.getProtoBytes(channels);
        onDeviceStorageFile = new File(mDataStorageDir, mInjector.createMeterCacheFilename());
        byte[] bytesActual = new byte[(int) onDeviceStorageFile.length()];
        FileInputStream onDeviceStorageFis = new FileInputStream(onDeviceStorageFile);
        onDeviceStorageFis.read(bytesActual);
        assertTrue(Arrays.equals(bytesExpected, bytesActual));
    }

    @Test
    public void testDataStorageDeletedModelMismatch() throws IOException {
        // Create the directory where cached data will be stored.
        mInjector.createDataStoragePath();

        // In order to create cached data that will match the current data read by the
        // PowerStatsService we need to write valid data from the TestPowerStatsHALWrapper that is
        // returned from the Injector.
        IPowerStatsHALWrapper powerStatsHALWrapper = mInjector.getPowerStatsHALWrapperImpl();

        // Create cached channel data and write to file.
        Channel[] channels = powerStatsHALWrapper.getEnergyMeterInfo();
        byte[] bytes = ChannelUtils.getProtoBytes(channels);
        File onDeviceStorageFile = new File(mDataStorageDir, mInjector.createMeterCacheFilename());
        FileOutputStream onDeviceStorageFos = new FileOutputStream(onDeviceStorageFile);
        onDeviceStorageFos.write(bytes);
        onDeviceStorageFos.close();

        // Generate random array of bytes to emulate cached energy consumer data.  Store to file.
        Random rd = new Random();
        bytes = new byte[100];
        rd.nextBytes(bytes);
        onDeviceStorageFile = new File(mDataStorageDir, mInjector.createModelCacheFilename());
        onDeviceStorageFos = new FileOutputStream(onDeviceStorageFile);
        onDeviceStorageFos.write(bytes);
        onDeviceStorageFos.close();

        // Create cached power entity info data and write to file.
        PowerEntity[] powerEntityInfo = powerStatsHALWrapper.getPowerEntityInfo();
        bytes = PowerEntityUtils.getProtoBytes(powerEntityInfo);
        onDeviceStorageFile = new File(mDataStorageDir, mInjector.createResidencyCacheFilename());
        onDeviceStorageFos = new FileOutputStream(onDeviceStorageFile);
        onDeviceStorageFos.write(bytes);
        onDeviceStorageFos.close();

        // Create log files.
        File meterFile = new File(mDataStorageDir, mInjector.createMeterFilename());
        File modelFile = new File(mDataStorageDir, mInjector.createModelFilename());
        File residencyFile = new File(mDataStorageDir, mInjector.createResidencyFilename());
        meterFile.createNewFile();
        modelFile.createNewFile();
        residencyFile.createNewFile();

        // Verify log files exist.
        assertTrue(meterFile.exists());
        assertTrue(modelFile.exists());
        assertTrue(residencyFile.exists());

        // Boot device after creating old cached data.
        mService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);

        // Since cached energy consumer data is just random bytes it won't match the data read from
        // the HAL.  This mismatch of cached and current HAL data should force a delete.
        assertFalse(mService.getDeleteMeterDataOnBoot());
        assertTrue(mService.getDeleteModelDataOnBoot());
        assertFalse(mService.getDeleteResidencyDataOnBoot());

        // Verify log files were deleted.
        assertTrue(meterFile.exists());
        assertFalse(modelFile.exists());
        assertTrue(residencyFile.exists());

        // Verify cached energy consumer data was updated to new HAL output.
        EnergyConsumer[] energyConsumers = powerStatsHALWrapper.getEnergyConsumerInfo();
        byte[] bytesExpected = EnergyConsumerUtils.getProtoBytes(energyConsumers);
        onDeviceStorageFile = new File(mDataStorageDir, mInjector.createModelCacheFilename());
        byte[] bytesActual = new byte[(int) onDeviceStorageFile.length()];
        FileInputStream onDeviceStorageFis = new FileInputStream(onDeviceStorageFile);
        onDeviceStorageFis.read(bytesActual);
        assertTrue(Arrays.equals(bytesExpected, bytesActual));
    }

    @Test
    public void testDataStorageDeletedResidencyMismatch() throws IOException {
        // Create the directory where cached data will be stored.
        mInjector.createDataStoragePath();

        // In order to create cached data that will match the current data read by the
        // PowerStatsService we need to write valid data from the TestPowerStatsHALWrapper that is
        // returned from the Injector.
        IPowerStatsHALWrapper powerStatsHALWrapper = mInjector.getPowerStatsHALWrapperImpl();

        // Create cached channel data and write to file.
        Channel[] channels = powerStatsHALWrapper.getEnergyMeterInfo();
        byte[] bytes = ChannelUtils.getProtoBytes(channels);
        File onDeviceStorageFile = new File(mDataStorageDir, mInjector.createMeterCacheFilename());
        FileOutputStream onDeviceStorageFos = new FileOutputStream(onDeviceStorageFile);
        onDeviceStorageFos.write(bytes);
        onDeviceStorageFos.close();

        // Create cached energy consumer data and write to file.
        EnergyConsumer[] energyConsumers = powerStatsHALWrapper.getEnergyConsumerInfo();
        bytes = EnergyConsumerUtils.getProtoBytes(energyConsumers);
        onDeviceStorageFile = new File(mDataStorageDir, mInjector.createModelCacheFilename());
        onDeviceStorageFos = new FileOutputStream(onDeviceStorageFile);
        onDeviceStorageFos.write(bytes);
        onDeviceStorageFos.close();

        // Generate random array of bytes to emulate cached power entity info data.  Store to file.
        Random rd = new Random();
        bytes = new byte[100];
        rd.nextBytes(bytes);
        onDeviceStorageFile = new File(mDataStorageDir, mInjector.createResidencyCacheFilename());
        onDeviceStorageFos = new FileOutputStream(onDeviceStorageFile);
        onDeviceStorageFos.write(bytes);
        onDeviceStorageFos.close();

        // Create log files.
        File meterFile = new File(mDataStorageDir, mInjector.createMeterFilename());
        File modelFile = new File(mDataStorageDir, mInjector.createModelFilename());
        File residencyFile = new File(mDataStorageDir, mInjector.createResidencyFilename());
        meterFile.createNewFile();
        modelFile.createNewFile();
        residencyFile.createNewFile();

        // Verify log files exist.
        assertTrue(meterFile.exists());
        assertTrue(modelFile.exists());
        assertTrue(residencyFile.exists());

        // Boot device after creating old cached data.
        mService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);

        // Since cached power entity info data is just random bytes it won't match the data read
        // from the HAL.  This mismatch of cached and current HAL data should force a delete.
        assertFalse(mService.getDeleteMeterDataOnBoot());
        assertFalse(mService.getDeleteModelDataOnBoot());
        assertTrue(mService.getDeleteResidencyDataOnBoot());

        // Verify log files were deleted.
        assertTrue(meterFile.exists());
        assertTrue(modelFile.exists());
        assertFalse(residencyFile.exists());

        // Verify cached power entity data was updated to new HAL output.
        PowerEntity[] powerEntityInfo = powerStatsHALWrapper.getPowerEntityInfo();
        byte[] bytesExpected = PowerEntityUtils.getProtoBytes(powerEntityInfo);
        onDeviceStorageFile = new File(mDataStorageDir, mInjector.createResidencyCacheFilename());
        byte[] bytesActual = new byte[(int) onDeviceStorageFile.length()];
        FileInputStream onDeviceStorageFis = new FileInputStream(onDeviceStorageFile);
        onDeviceStorageFis.read(bytesActual);
        assertTrue(Arrays.equals(bytesExpected, bytesActual));
    }

    @Test
    public void testDataStorageNotDeletedNoCachedData() throws IOException {
        // Create the directory where log files will be stored.
        mInjector.createDataStoragePath();

        // Create log files.
        File meterFile = new File(mDataStorageDir, mInjector.createMeterFilename());
        File modelFile = new File(mDataStorageDir, mInjector.createModelFilename());
        File residencyFile = new File(mDataStorageDir, mInjector.createResidencyFilename());
        meterFile.createNewFile();
        modelFile.createNewFile();
        residencyFile.createNewFile();

        // Verify log files exist.
        assertTrue(meterFile.exists());
        assertTrue(modelFile.exists());
        assertTrue(residencyFile.exists());

        // This test mimics the device's first boot where there is no cached data.
        mService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);

        // Since there is no cached data on the first boot any log files that happen to exist
        // should be deleted.
        assertTrue(mService.getDeleteMeterDataOnBoot());
        assertTrue(mService.getDeleteModelDataOnBoot());
        assertTrue(mService.getDeleteResidencyDataOnBoot());

        // Verify log files were deleted.
        assertFalse(meterFile.exists());
        assertFalse(modelFile.exists());
        assertFalse(residencyFile.exists());
    }

    @Test
    public void testDataStorageNotDeletedAllDataMatches() throws IOException {
        // Create the directory where cached data will be stored.
        mInjector.createDataStoragePath();

        // In order to create cached data that will match the current data read by the
        // PowerStatsService we need to write valid data from the TestPowerStatsHALWrapper that is
        // returned from the Injector.
        IPowerStatsHALWrapper powerStatsHALWrapper = mInjector.getPowerStatsHALWrapperImpl();

        // Create cached channel data and write to file.
        Channel[] channels = powerStatsHALWrapper.getEnergyMeterInfo();
        byte[] bytes = ChannelUtils.getProtoBytes(channels);
        File onDeviceStorageFile = new File(mDataStorageDir, mInjector.createMeterCacheFilename());
        FileOutputStream onDeviceStorageFos = new FileOutputStream(onDeviceStorageFile);
        onDeviceStorageFos.write(bytes);
        onDeviceStorageFos.close();

        // Create cached energy consumer data and write to file.
        EnergyConsumer[] energyConsumers = powerStatsHALWrapper.getEnergyConsumerInfo();
        bytes = EnergyConsumerUtils.getProtoBytes(energyConsumers);
        onDeviceStorageFile = new File(mDataStorageDir, mInjector.createModelCacheFilename());
        onDeviceStorageFos = new FileOutputStream(onDeviceStorageFile);
        onDeviceStorageFos.write(bytes);
        onDeviceStorageFos.close();

        // Create cached power entity info data and write to file.
        PowerEntity[] powerEntityInfo = powerStatsHALWrapper.getPowerEntityInfo();
        bytes = PowerEntityUtils.getProtoBytes(powerEntityInfo);
        onDeviceStorageFile = new File(mDataStorageDir, mInjector.createResidencyCacheFilename());
        onDeviceStorageFos = new FileOutputStream(onDeviceStorageFile);
        onDeviceStorageFos.write(bytes);
        onDeviceStorageFos.close();

        // Create log files.
        File meterFile = new File(mDataStorageDir, mInjector.createMeterFilename());
        File modelFile = new File(mDataStorageDir, mInjector.createModelFilename());
        File residencyFile = new File(mDataStorageDir, mInjector.createResidencyFilename());
        meterFile.createNewFile();
        modelFile.createNewFile();
        residencyFile.createNewFile();

        // Verify log files exist.
        assertTrue(meterFile.exists());
        assertTrue(modelFile.exists());
        assertTrue(residencyFile.exists());

        // Boot device after creating old cached data.
        mService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);

        // All cached data created above should match current data read in PowerStatsService so we
        // expect the data not to be deleted.
        assertFalse(mService.getDeleteMeterDataOnBoot());
        assertFalse(mService.getDeleteModelDataOnBoot());
        assertFalse(mService.getDeleteResidencyDataOnBoot());

        // Verify log files were not deleted.
        assertTrue(meterFile.exists());
        assertTrue(modelFile.exists());
        assertTrue(residencyFile.exists());
    }
}
