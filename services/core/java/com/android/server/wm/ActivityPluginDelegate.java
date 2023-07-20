/*
 *Copyright (c) 2018, The Linux Foundation. All rights reserved.
 *
 *Redistribution and use in source and binary forms, with or without
 *modification, are permitted provided that the following conditions are
 *met:
 *   * Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *   * Redistributions in binary form must reproduce the above
 *     copyright notice, this list of conditions and the following
 *     disclaimer in the documentation and/or other materials provided
 *     with the distribution.
 *   * Neither the name of The Linux Foundation nor the names of its
 *     contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 *THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 *WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 *MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 *ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 *BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 *CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 *SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 *BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 *WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 *OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 *IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.server.wm;

import dalvik.system.PathClassLoader;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import android.os.Environment;
import android.util.Log;
import android.content.Context;
import android.provider.Settings;
import android.app.ActivityThread;

public class ActivityPluginDelegate {

    private static final String TAG = "ActivityPluginDelegate";
    private static final boolean LOGV = false;

    private static Class activityServiceClass = null;
    private static Object activityServiceObj = null;
    private static boolean extJarAvail = true;

    private static final String FOREGROUND_ACTIVITY_TRIGGER =
        "foreground_activity_trigger";

    //Try to get global settings for 15 times, if
    //foreground_activity_trigger does not set to 1 after 15 times
    //stop retry and foreground_activity_trigger is 0
    private static final int MAX_CONNECT_RETRIES = 15;

    static int mGetFeatureEnableRetryCount = MAX_CONNECT_RETRIES;
    static boolean isEnabled = false;

    public static void activityInvokeNotification(String appName,
                                                  boolean isFullScreen) {
        if (LOGV) Log.v(TAG, "activityInvokeNotification("
                         + appName + ", " + isFullScreen + ")");
        if (!getFeatureFlag() || !extJarAvail || !loadActivityExtJar())
            return;

        try {
            activityServiceClass.getMethod("sendActivityInvokeNotification",
                                           String.class, boolean.class).invoke(
                                           activityServiceObj, appName, isFullScreen);
        } catch (InvocationTargetException |
                 SecurityException | NoSuchMethodException e) {
            if (LOGV) {
                Log.w(TAG, "Failed to invoke activityInvokeNotification: " + e);
                e.printStackTrace();
            }
        } catch (Exception e) {
            if (LOGV) {
                Log.w(TAG, "Error calling activityInvokeNotification"+
                       "Method on ActivityExt jar: " + e);
                e.printStackTrace();
            }
        }
    }

    public static void activitySuspendNotification(String appName,
                                                   boolean isFullScreen,
                                                   boolean isBg) {
        if (LOGV) Log.v(TAG, "activitySuspendNotification("
                        + appName + ", " + isFullScreen + ", " + isBg + ")");
        if (!getFeatureFlag() || !extJarAvail || !loadActivityExtJar())
            return;

        try {
            activityServiceClass.getMethod("sendActivitySuspendNotification",
                                 String.class, boolean.class, boolean.class).invoke(
                                 activityServiceObj, appName, isFullScreen, isBg);
        } catch (InvocationTargetException |
                 SecurityException | NoSuchMethodException e) {
            if (LOGV) {
                Log.w(TAG, "Failed to call sendActivitySuspendNotification: " + e);
                e.printStackTrace();
            }
        } catch (Exception e) {
            if (LOGV) {
                Log.w(TAG, "Error calling sendActivitySuspendNotification"+
                       "Method on ActivityExt jar: " + e);
                e.printStackTrace();
            }
        }
    }

    private static synchronized boolean loadActivityExtJar() {
        final String realProvider = "com.qualcomm.qti."+
                                    "activityextension.ActivityNotifier";
        final String realProviderPath = Environment.getSystemExtDirectory().
            getAbsolutePath() + "/framework/ActivityExt.jar";

        if (activityServiceClass != null && activityServiceObj != null) {
            return true;
        }

        if ((extJarAvail = new File(realProviderPath).exists()) == false) {
            if (LOGV) Log.w(TAG, "ActivityExt jar file not present");
            return extJarAvail;
        }

        if (activityServiceClass == null && activityServiceObj == null) {
            if (LOGV) Log.v(TAG, "loading ActivityExt jar");
            try {
                PathClassLoader classLoader = new PathClassLoader
                    (realProviderPath, ClassLoader.getSystemClassLoader());

                activityServiceClass = classLoader.loadClass(realProvider);
                activityServiceObj = activityServiceClass.newInstance();
                if (LOGV) Log.v(TAG, "ActivityExt jar loaded");
            } catch (ClassNotFoundException |
                     InstantiationException | IllegalAccessException e) {
                if (LOGV) {
                    Log.w(TAG, "Failed to find, instantiate or access ActivityExt jar:" + e);
                    e.printStackTrace();
                }
                extJarAvail = false;
                return false;
            } catch (Exception e) {
                if (LOGV) {
                    Log.w(TAG, "unable to load ActivityExt jar:" + e);
                    e.printStackTrace();
                }
                extJarAvail = false;
                return false;
            }
        }
        return true;
    }

    public static synchronized boolean getFeatureFlag() {
        //Global setting has been enabled for foreground_activity_trigger
        //Or no one sets foreground_activity_trigger after all retry
        //No need to invoke Settings API
        if(isEnabled == true || (mGetFeatureEnableRetryCount == 0)) {
            return isEnabled;
        }
        isEnabled = ((Settings.Global.getInt(ActivityThread.currentApplication().
                                             getApplicationContext().getContentResolver(),
                                             FOREGROUND_ACTIVITY_TRIGGER, 1)) == 1);
        --mGetFeatureEnableRetryCount;
        return isEnabled;
    }
}
