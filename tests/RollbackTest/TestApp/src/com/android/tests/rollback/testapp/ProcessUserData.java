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

package com.android.tests.rollback.testapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Scanner;

/**
 * A broadcast reciever to check for and update user app data version
 * compatibility.
 */
public class ProcessUserData extends BroadcastReceiver {

    /**
     * Exception thrown in case of issue with user data.
     */
    public static class UserDataException extends Exception {
        public UserDataException(String message) {
            super(message);
        }

        public UserDataException(String message, Throwable cause) {
           super(message, cause);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            processUserData(context);
            setResultCode(1);
        } catch (UserDataException e) {
            setResultCode(0);
            setResultData(e.getMessage());
        }
    }

    /**
     * Update the app's user data version to match the app version.
     *
     * @param context The application context.
     * @throws UserDataException in case of problems with app user data.
     */
    public void processUserData(Context context) throws UserDataException {
        int appVersion = 0;
        try {
            ApplicationInfo appInfo = context.getPackageManager().getApplicationInfo(
                    context.getPackageName(), PackageManager.GET_META_DATA);
            Bundle bundle = appInfo.metaData;
            appVersion = bundle.getInt("version");
        } catch (PackageManager.NameNotFoundException e) {
            throw new UserDataException("Unable to get app version info", e);
        }

        // Read the version of the app's user data and ensure it is compatible
        // with our version of the application.
        File versionFile = new File(context.getFilesDir(), "version.txt");
        try {
            Scanner s = new Scanner(versionFile);
            int userDataVersion = s.nextInt();
            s.close();

            if (userDataVersion > appVersion) {
                throw new UserDataException("User data is from version " + userDataVersion
                        + ", which is not compatible with this version " + appVersion
                        + " of the RollbackTestApp");
            }
        } catch (FileNotFoundException e) {
            // No problem. This is a fresh install of the app or the user data
            // has been wiped.
        }

        // Record the current version of the app in the user data.
        try {
            PrintWriter pw = new PrintWriter(versionFile);
            pw.println(appVersion);
            pw.close();
        } catch (IOException e) {
            throw new UserDataException("Unable to write user data.", e);
        }
    }
}
