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

import androidx.test.InstrumentationRegistry;

import com.android.server.SystemService;
import com.android.server.powerstats.PowerStatsHALWrapper.IPowerStatsHALWrapper;
import com.android.server.powerstats.nano.PowerStatsServiceProto;

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
    private static final String DATA_STORAGE_FILENAME = "test";
    private static final String PROTO_OUTPUT_FILENAME = "powerstats.proto";
    private static final String RAIL_NAME = "railname";
    private static final String SUBSYS_NAME = "subsysname";
    private static final int POWER_RAIL_COUNT = 8;

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
        String createDataStorageFilename() {
            return DATA_STORAGE_FILENAME;
        }

        @Override
        IPowerStatsHALWrapper createPowerStatsHALWrapperImpl() {
            return new TestPowerStatsHALWrapper();
        }

        @Override
        PowerStatsLogger createPowerStatsLogger(Context context, File dataStoragePath,
                String dataStorageFilename, IPowerStatsHALWrapper powerStatsHALWrapper) {
            mPowerStatsLogger = new PowerStatsLogger(context, dataStoragePath, dataStorageFilename,
                powerStatsHALWrapper);
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
        public PowerStatsData.RailInfo[] readRailInfo() {
            PowerStatsData.RailInfo[] railInfoArray = new PowerStatsData.RailInfo[POWER_RAIL_COUNT];
            for (int i = 0; i < POWER_RAIL_COUNT; i++) {
                railInfoArray[i] = new PowerStatsData.RailInfo(i, RAIL_NAME + i, SUBSYS_NAME + i,
                    i);
            }
            return railInfoArray;
        }

        @Override
        public PowerStatsData.EnergyData[] readEnergyData() {
            PowerStatsData.EnergyData[] energyDataArray =
              new PowerStatsData.EnergyData[POWER_RAIL_COUNT];
            for (int i = 0; i < POWER_RAIL_COUNT; i++) {
                energyDataArray[i] = new PowerStatsData.EnergyData(i, i, i);
            }
            return energyDataArray;
        }

        @Override
        public boolean initialize() {
            return true;
        }
    }

    @Before
    public void setUp() {
        mService = new PowerStatsService(mContext, mInjector);
    }

    @Test
    public void testWrittenPowerStatsHALDataMatchesReadIncidentReportData()
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
        mPowerStatsLogger.writeToFile(fos.getFD());

        // Read the incident report in to a byte array.
        FileInputStream fis = new FileInputStream(incidentReport);
        byte[] fileContent = new byte[(int) incidentReport.length()];
        fis.read(fileContent);

        // Parse the incident data into a PowerStatsServiceProto object.
        PowerStatsServiceProto pssProto = PowerStatsServiceProto.parseFrom(fileContent);

        // Validate the railInfo array matches what was written to on-device storage.
        assertTrue(pssProto.railInfo.length == POWER_RAIL_COUNT);
        for (int i = 0; i < pssProto.railInfo.length; i++) {
            assertTrue(pssProto.railInfo[i].index == i);
            assertTrue(pssProto.railInfo[i].railName.equals(RAIL_NAME + i));
            assertTrue(pssProto.railInfo[i].subsysName.equals(SUBSYS_NAME + i));
            assertTrue(pssProto.railInfo[i].samplingRate == i);
        }

        // Validate the energyData array matches what was written to on-device storage.
        assertTrue(pssProto.energyData.length == POWER_RAIL_COUNT);
        for (int i = 0; i < pssProto.energyData.length; i++) {
            assertTrue(pssProto.energyData[i].index == i);
            assertTrue(pssProto.energyData[i].timestampMs == i);
            assertTrue(pssProto.energyData[i].energyUws == i);
        }
    }

    @Test
    public void testCorruptOnDeviceStorage() throws IOException {
        mService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);

        // Generate random array of bytes to emulate corrupt data.
        Random rd = new Random();
        byte[] bytes = new byte[100];
        rd.nextBytes(bytes);

        // Store corrupt data in on-device storage.  Add fake timestamp to filename
        // to match format expected by FileRotator.
        File onDeviceStorageFile = new File(mDataStorageDir, DATA_STORAGE_FILENAME + ".1234-2234");
        FileOutputStream onDeviceStorageFos = new FileOutputStream(onDeviceStorageFile);
        onDeviceStorageFos.write(bytes);
        onDeviceStorageFos.close();

        // Write on-device storage to an incident report.
        File incidentReport = new File(mDataStorageDir, PROTO_OUTPUT_FILENAME);
        FileOutputStream incidentReportFos = new FileOutputStream(incidentReport);
        mPowerStatsLogger.writeToFile(incidentReportFos.getFD());

        // Read the incident report in to a byte array.
        FileInputStream fis = new FileInputStream(incidentReport);
        byte[] fileContent = new byte[(int) incidentReport.length()];
        fis.read(fileContent);

        // Parse the incident data into a PowerStatsServiceProto object.
        PowerStatsServiceProto pssProto = PowerStatsServiceProto.parseFrom(fileContent);

        // Valid railInfo data is written to the incident report in the call to
        // mPowerStatsLogger.writeToFile().
        assertTrue(pssProto.railInfo.length == POWER_RAIL_COUNT);
        for (int i = 0; i < pssProto.railInfo.length; i++) {
            assertTrue(pssProto.railInfo[i].index == i);
            assertTrue(pssProto.railInfo[i].railName.equals(RAIL_NAME + i));
            assertTrue(pssProto.railInfo[i].subsysName.equals(SUBSYS_NAME + i));
            assertTrue(pssProto.railInfo[i].samplingRate == i);
        }

        // No energyData should be written to the incident report since it
        // is all corrupt (random bytes generated above).
        assertTrue(pssProto.energyData.length == 0);
    }

    @Test
    public void testNotEnoughBytesAfterLengthField() throws IOException {
        mService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);

        // Create corrupt data.
        // Length field is correct, but there is no data following the length.
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(ByteBuffer.allocate(4).putInt(50).array());
        byte[] test = data.toByteArray();

        // Store corrupt data in on-device storage.  Add fake timestamp to filename
        // to match format expected by FileRotator.
        File onDeviceStorageFile = new File(mDataStorageDir, DATA_STORAGE_FILENAME + ".1234-2234");
        FileOutputStream onDeviceStorageFos = new FileOutputStream(onDeviceStorageFile);
        onDeviceStorageFos.write(data.toByteArray());
        onDeviceStorageFos.close();

        // Write on-device storage to an incident report.
        File incidentReport = new File(mDataStorageDir, PROTO_OUTPUT_FILENAME);
        FileOutputStream incidentReportFos = new FileOutputStream(incidentReport);
        mPowerStatsLogger.writeToFile(incidentReportFos.getFD());

        // Read the incident report in to a byte array.
        FileInputStream fis = new FileInputStream(incidentReport);
        byte[] fileContent = new byte[(int) incidentReport.length()];
        fis.read(fileContent);

        // Parse the incident data into a PowerStatsServiceProto object.
        PowerStatsServiceProto pssProto = PowerStatsServiceProto.parseFrom(fileContent);

        // Valid railInfo data is written to the incident report in the call to
        // mPowerStatsLogger.writeToFile().
        assertTrue(pssProto.railInfo.length == POWER_RAIL_COUNT);
        for (int i = 0; i < pssProto.railInfo.length; i++) {
            assertTrue(pssProto.railInfo[i].index == i);
            assertTrue(pssProto.railInfo[i].railName.equals(RAIL_NAME + i));
            assertTrue(pssProto.railInfo[i].subsysName.equals(SUBSYS_NAME + i));
            assertTrue(pssProto.railInfo[i].samplingRate == i);
        }

        // No energyData should be written to the incident report since the
        // input buffer had only length and no data.
        assertTrue(pssProto.energyData.length == 0);
    }
}
