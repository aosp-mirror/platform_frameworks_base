/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.perftests.utils;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.content.res.Resources;
import android.util.Log;

import org.junit.Assert;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Installs packages included within the assets directory.
 */
public class TestPackageInstaller {
    private static final String LOG_TAG = "TestPackageInstaller";
    private static final String BROADCAST_ACTION =
            "com.android.perftests.core.ACTION_INSTALL_COMMIT";

    private final Context mContext;
    public TestPackageInstaller(Context context) {
        mContext = context;
    }



    /**
     * Installs an APK located at the specified path in the assets directory.
     **/
    public InstalledPackage installPackage(String resourceName) throws IOException,
            InterruptedException {
        Log.d(LOG_TAG, "Installing resource APK '" + resourceName + "'");
        LocalBroadcastReceiver intentSender = new LocalBroadcastReceiver(mContext);

        // Initialize the package install session.
        PackageInstaller packageInstaller = mContext.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setInstallAsInstantApp(false);
        int sessionId = packageInstaller.createSession(params);
        PackageInstaller.Session session = packageInstaller.openSession(sessionId);

        // Copy the apk to the install session.
        try (OutputStream os = session.openWrite("TestPackageInstaller", 0, -1);
             InputStream is = mContext.getResources().getAssets().openNonAsset(resourceName)) {
            if (is == null) {
                throw new IOException("Resource " + resourceName + " not found");
            }
            byte[] buffer = new byte[4096];
            int n;
            while ((n = is.read(buffer)) >= 0) {
                os.write(buffer, 0, n);
            }
        }

        session.commit(intentSender.getIntentSender(sessionId));
        session.close();

        // Retrieve the results of the installation.
        Intent intent = intentSender.getIntentSenderResult();
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        Assert.assertEquals(PackageInstaller.STATUS_SUCCESS, status);
        String packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
        return new InstalledPackage(sessionId, packageName);
    }

    public class InstalledPackage {
        private int mSessionId;
        private String mPackageName;

        private InstalledPackage(int sessionId, String packageName) {
            mSessionId = sessionId;
            mPackageName = packageName;
        }

        public String getPackageName() {
            return mPackageName;
        }

        public void uninstall() throws Exception {
            Log.d(LOG_TAG, "Uninstalling package '" + mPackageName + "'");
            LocalBroadcastReceiver intentSender = new LocalBroadcastReceiver(mContext);
            PackageInstaller packageInstaller = mContext.getPackageManager().getPackageInstaller();
            packageInstaller.uninstall(mPackageName, intentSender.getIntentSender(mSessionId));
            int status = intentSender.getIntentSenderResult()
                    .getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            Assert.assertEquals(PackageInstaller.STATUS_SUCCESS, status);
        }
    }

    private class LocalBroadcastReceiver extends BroadcastReceiver {
        private final BlockingQueue<Intent> mIntentSenderResults = new LinkedBlockingQueue<>();
        private final Context mContext;

        private LocalBroadcastReceiver(Context context) {
            mContext = context;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            mIntentSenderResults.add(intent);
        }

        IntentSender getIntentSender(int sessionId) {
            String action = BROADCAST_ACTION + "." + sessionId;
            IntentFilter filter = new IntentFilter(action);
            mContext.registerReceiver(this, filter);

            Intent intent = new Intent(action);
            PendingIntent pending = PendingIntent.getBroadcast(mContext, sessionId, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            return pending.getIntentSender();
        }

        Intent getIntentSenderResult() throws InterruptedException {
            return mIntentSenderResults.take();
        }
    }
}
