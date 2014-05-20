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

package com.android.framework.multidexlegacytestservices;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.support.multidex.MultiDex;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Empty service for testing legacy multidex. Access more than 64k methods but some are required at
 * init, some only at verification and others during execution.
 */
public abstract class AbstractService extends Service implements Runnable {
    private final String TAG = "MultidexLegacyTestService" + getId();

    private int instanceFieldNotInited;
    private int instanceFieldInited =
            new com.android.framework.multidexlegacytestservices.manymethods.Big043().get43();
    private static int staticField =
            new com.android.framework.multidexlegacytestservices.manymethods.Big044().get44();

    public AbstractService() {
        instanceFieldNotInited = new com.android.framework.multidexlegacytestservices.manymethods.Big042().get42();
    }

    @Override
    public void onCreate() {
        Log.i(TAG, "onCreate");
        new Thread(this).start();

    }

    @Override
    public void run() {
        Context applicationContext = getApplicationContext();
        File resultFile = new File(applicationContext.getFilesDir(), getId());
        try {
            // Append a constant value in result file, if services crashed and is relaunched, size
            // of the result file will be too big.
            RandomAccessFile raf = new RandomAccessFile(resultFile, "rw");
            raf.seek(raf.length());
            Log.i(TAG, "Writing 0x42434445 at " + raf.length() + " in " + resultFile.getPath());
            raf.writeInt(0x42434445);
            raf.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        MultiDex.install(applicationContext);
        Log.i(TAG, "Multi dex installation done.");

        int value = getValue();
        Log.i(TAG, "Saving the result (" + value + ") to " + resultFile.getPath());
        try {
            // Append the check value in result file, keeping the constant values already written.
            RandomAccessFile raf = new RandomAccessFile(resultFile, "rw");
            raf.seek(raf.length());
            Log.i(TAG, "Writing result at " + raf.length() + " in " + resultFile.getPath());
            raf.writeInt(value);
            raf.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            // Writing end of processing flags, the existence of the file is the criteria
            RandomAccessFile raf = new RandomAccessFile(new File(applicationContext.getFilesDir(), getId() + ".complete"), "rw");
            Log.i(TAG, "creating complete file " + resultFile.getPath());
            raf.writeInt(0x32333435);
            raf.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i("Service" + getId(), "Received start id " + startId + ": " + intent);
        // We want this service to continue running until it is explicitly
        // stopped, so return sticky.
        return START_STICKY;
    }

    private String getId() {
        return this.getClass().getSimpleName();
    }

    @Override
    public void onDestroy() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public int getValue() {
        int intermediate = -1;
        try {
            intermediate = ReflectIntermediateClass.get(45, 80, 20 /* 5 seems enough on a nakasi,
                using 20 to get some margin */);
        } catch (Exception e) {
            e.printStackTrace();
        }
        int value = new com.android.framework.multidexlegacytestservices.manymethods.Big001().get1() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big002().get2() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big003().get3() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big004().get4() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big005().get5() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big006().get6() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big007().get7() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big008().get8() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big009().get9() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big010().get10() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big011().get11() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big012().get12() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big013().get13() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big014().get14() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big015().get15() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big016().get16() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big017().get17() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big018().get18() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big019().get19() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big020().get20() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big021().get21() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big022().get22() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big023().get23() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big024().get24() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big025().get25() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big026().get26() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big027().get27() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big028().get28() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big029().get29() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big030().get30() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big031().get31() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big032().get32() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big033().get33() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big034().get34() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big035().get35() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big036().get36() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big037().get37() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big038().get38() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big039().get39() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big040().get40() +
                new com.android.framework.multidexlegacytestservices.manymethods.Big041().get41() +
                instanceFieldNotInited +
                instanceFieldInited +
                staticField +
                intermediate;
        return value;
    }

}
