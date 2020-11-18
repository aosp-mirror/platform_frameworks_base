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

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.hardware.power.stats.ChannelInfo;
import android.hardware.power.stats.EnergyConsumerResult;
import android.hardware.power.stats.EnergyMeasurement;
import android.hardware.power.stats.PowerEntityInfo;
import android.hardware.power.stats.StateInfo;
import android.hardware.power.stats.StateResidency;
import android.hardware.power.stats.StateResidencyResult;

import androidx.test.InstrumentationRegistry;

import com.android.server.SystemService;
import com.android.server.powerstats.PowerStatsHALWrapper.IPowerStatsHALWrapper;
import com.android.server.powerstats.nano.PowerStatsServiceMeterProto;
import com.android.server.powerstats.nano.PowerStatsServiceModelProto;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
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
    private static final String METER_FILENAME = "metertest";
    private static final String MODEL_FILENAME = "modeltest";
    private static final String PROTO_OUTPUT_FILENAME = "powerstats.proto";
    private static final String CHANNEL_NAME = "channelname";
    private static final String POWER_ENTITY_NAME = "powerentityinfo";
    private static final String STATE_NAME = "stateinfo";
    private static final int ENERGY_METER_COUNT = 8;
    private static final int ENERGY_CONSUMER_COUNT = 2;
    private static final int POWER_ENTITY_COUNT = 3;
    private static final int STATE_INFO_COUNT = 5;
    private static final int STATE_RESIDENCY_COUNT = 4;

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();
    private PowerStatsService mService;
    private File mDataStorageDir;
    private TimerTrigger mTimerTrigger;
    private PowerStatsLogger mPowerStatsLogger;

    private final PowerStatsService.Injector mInjector = new PowerStatsService.Injector() {
        @Override
        File createDataStoragePath() {
            mDataStorageDir = null;

            try {
                mDataStorageDir = Files.createTempDirectory(DATA_STORAGE_SUBDIR).toFile();
            } catch (IOException e) {
                fail("Could not create temp directory.");
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
        IPowerStatsHALWrapper createPowerStatsHALWrapperImpl() {
            return new TestPowerStatsHALWrapper();
        }

        @Override
        PowerStatsLogger createPowerStatsLogger(Context context, File dataStoragePath,
                String meterFilename, String modelFilename,
                IPowerStatsHALWrapper powerStatsHALWrapper) {
            mPowerStatsLogger = new PowerStatsLogger(context, dataStoragePath, meterFilename,
                modelFilename, powerStatsHALWrapper);
            return mPowerStatsLogger;
        }

        @Override
        BatteryTrigger createBatteryTrigger(Context context, PowerStatsLogger powerStatsLogger) {
            return new BatteryTrigger(context, powerStatsLogger, false /* trigger enabled */);
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
        public PowerEntityInfo[] getPowerEntityInfo() {
            PowerEntityInfo[] powerEntityInfoList = new PowerEntityInfo[POWER_ENTITY_COUNT];
            for (int i = 0; i < powerEntityInfoList.length; i++) {
                powerEntityInfoList[i] = new PowerEntityInfo();
                powerEntityInfoList[i].powerEntityId = i;
                powerEntityInfoList[i].powerEntityName = new String(POWER_ENTITY_NAME + i);
                powerEntityInfoList[i].states = new StateInfo[STATE_INFO_COUNT];
                for (int j = 0; j < powerEntityInfoList[i].states.length; j++) {
                    powerEntityInfoList[i].states[j] = new StateInfo();
                    powerEntityInfoList[i].states[j].stateId = j;
                    powerEntityInfoList[i].states[j].stateName = new String(STATE_NAME + i);
                }
            }
            return powerEntityInfoList;
        }

        @Override
        public StateResidencyResult[] getStateResidency(int[] powerEntityIds) {
            StateResidencyResult[] stateResidencyResultList =
                new StateResidencyResult[POWER_ENTITY_COUNT];
            for (int i = 0; i < stateResidencyResultList.length; i++) {
                stateResidencyResultList[i] = new StateResidencyResult();
                stateResidencyResultList[i].powerEntityId = i;
                stateResidencyResultList[i].stateResidencyData =
                    new StateResidency[STATE_RESIDENCY_COUNT];
                for (int j = 0; j < stateResidencyResultList[i].stateResidencyData.length; j++) {
                    stateResidencyResultList[i].stateResidencyData[j] = new StateResidency();
                    stateResidencyResultList[i].stateResidencyData[j].totalTimeInStateMs = j;
                    stateResidencyResultList[i].stateResidencyData[j].totalStateEntryCount = j;
                    stateResidencyResultList[i].stateResidencyData[j].lastEntryTimestampMs = j;
                }
            }

            return stateResidencyResultList;
        }

        @Override
        public int[] getEnergyConsumerInfo() {
            int[] energyConsumerInfoList = new int[ENERGY_CONSUMER_COUNT];
            for (int i = 0; i < energyConsumerInfoList.length; i++) {
                energyConsumerInfoList[i] = i;
            }
            return energyConsumerInfoList;
        }

        @Override
        public EnergyConsumerResult[] getEnergyConsumed(int[] energyConsumerIds) {
            EnergyConsumerResult[] energyConsumedList =
                new EnergyConsumerResult[ENERGY_CONSUMER_COUNT];
            for (int i = 0; i < energyConsumedList.length; i++) {
                energyConsumedList[i] = new EnergyConsumerResult();
                energyConsumedList[i].energyConsumerId = i;
                energyConsumedList[i].timestampMs = i;
                energyConsumedList[i].energyUWs = i;
            }
            return energyConsumedList;
        }

        @Override
        public ChannelInfo[] getEnergyMeterInfo() {
            ChannelInfo[] energyMeterInfoList = new ChannelInfo[ENERGY_METER_COUNT];
            for (int i = 0; i < energyMeterInfoList.length; i++) {
                energyMeterInfoList[i] = new ChannelInfo();
                energyMeterInfoList[i].channelId = i;
                energyMeterInfoList[i].channelName = new String(CHANNEL_NAME + i);
            }
            return energyMeterInfoList;
        }

        @Override
        public EnergyMeasurement[] readEnergyMeters(int[] channelIds) {
            EnergyMeasurement[] energyMeasurementList = new EnergyMeasurement[ENERGY_METER_COUNT];
            for (int i = 0; i < energyMeasurementList.length; i++) {
                energyMeasurementList[i] = new EnergyMeasurement();
                energyMeasurementList[i].channelId = i;
                energyMeasurementList[i].timestampMs = i;
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
        mTimerTrigger.logPowerStatsData();

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

        // Validate the channelInfo array matches what was written to on-device storage.
        assertTrue(pssProto.channelInfo.length == ENERGY_METER_COUNT);
        for (int i = 0; i < pssProto.channelInfo.length; i++) {
            assertTrue(pssProto.channelInfo[i].channelId == i);
            assertTrue(pssProto.channelInfo[i].channelName.equals(CHANNEL_NAME + i));
        }

        // Validate the energyMeasurement array matches what was written to on-device storage.
        assertTrue(pssProto.energyMeasurement.length == ENERGY_METER_COUNT);
        for (int i = 0; i < pssProto.energyMeasurement.length; i++) {
            assertTrue(pssProto.energyMeasurement[i].channelId == i);
            assertTrue(pssProto.energyMeasurement[i].timestampMs == i);
            assertTrue(pssProto.energyMeasurement[i].energyUws == i);
        }
    }

    @Test
    public void testWrittenModelDataMatchesReadIncidentReportData()
            throws InterruptedException, IOException {
        mService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);

        // Write data to on-device storage.
        mTimerTrigger.logPowerStatsData();

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

        // Validate the energyConsumerId array matches what was written to on-device storage.
        assertTrue(pssProto.energyConsumerId.length == ENERGY_CONSUMER_COUNT);
        for (int i = 0; i < pssProto.energyConsumerId.length; i++) {
            assertTrue(pssProto.energyConsumerId[i].energyConsumerId == i);
        }

        // Validate the energyConsumerResult array matches what was written to on-device storage.
        assertTrue(pssProto.energyConsumerResult.length == ENERGY_CONSUMER_COUNT);
        for (int i = 0; i < pssProto.energyConsumerResult.length; i++) {
            assertTrue(pssProto.energyConsumerResult[i].energyConsumerId == i);
            assertTrue(pssProto.energyConsumerResult[i].timestampMs == i);
            assertTrue(pssProto.energyConsumerResult[i].energyUws == i);
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

        // Valid channelInfo data is written to the incident report in the call to
        // mPowerStatsLogger.writeMeterDataToFile().
        assertTrue(pssProto.channelInfo.length == ENERGY_METER_COUNT);
        for (int i = 0; i < pssProto.channelInfo.length; i++) {
            assertTrue(pssProto.channelInfo[i].channelId == i);
            assertTrue(pssProto.channelInfo[i].channelName.equals(CHANNEL_NAME + i));
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

        // Valid energyConsumerId data is written to the incident report in the call to
        // mPowerStatsLogger.writeModelDataToFile().
        assertTrue(pssProto.energyConsumerId.length == ENERGY_CONSUMER_COUNT);
        for (int i = 0; i < pssProto.energyConsumerId.length; i++) {
            assertTrue(pssProto.energyConsumerId[i].energyConsumerId == i);
        }

        // No energyConsumerResults should be written to the incident report since it
        // is all corrupt (random bytes generated above).
        assertTrue(pssProto.energyConsumerResult.length == 0);
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

        // Valid channelInfo data is written to the incident report in the call to
        // mPowerStatsLogger.writeMeterDataToFile().
        assertTrue(pssProto.channelInfo.length == ENERGY_METER_COUNT);
        for (int i = 0; i < pssProto.channelInfo.length; i++) {
            assertTrue(pssProto.channelInfo[i].channelId == i);
            assertTrue(pssProto.channelInfo[i].channelName.equals(CHANNEL_NAME + i));
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

        // Valid energyConsumerId data is written to the incident report in the call to
        // mPowerStatsLogger.writeModelDataToFile().
        assertTrue(pssProto.energyConsumerId.length == ENERGY_CONSUMER_COUNT);
        for (int i = 0; i < pssProto.energyConsumerId.length; i++) {
            assertTrue(pssProto.energyConsumerId[i].energyConsumerId == i);
        }

        // No energyConsumerResults should be written to the incident report since the
        // input buffer had only length and no data.
        assertTrue(pssProto.energyConsumerResult.length == 0);
    }
}
