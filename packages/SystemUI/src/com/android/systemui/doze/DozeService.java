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
 * limitations under the License
 */

package com.android.systemui.doze;

import android.content.Context;
import android.content.res.Configuration;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.dreams.DreamService;
import android.util.Log;

import com.android.systemui.doze.dagger.DozeComponent;
import com.android.systemui.plugins.DozeServicePlugin;
import com.android.systemui.plugins.DozeServicePlugin.RequestDoze;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.PluginManager;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import javax.inject.Inject;

public class DozeService extends DreamService
        implements DozeMachine.Service, RequestDoze, PluginListener<DozeServicePlugin> {
    private static final String TAG = "DozeService";
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private final DozeComponent.Builder mDozeComponentBuilder;

    private DozeMachine mDozeMachine;
    private DozeServicePlugin mDozePlugin;
    private PluginManager mPluginManager;

    @Inject
    public DozeService(DozeComponent.Builder dozeComponentBuilder, PluginManager pluginManager) {
        mDozeComponentBuilder = dozeComponentBuilder;
        setDebug(DEBUG);
        mPluginManager = pluginManager;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        setWindowless(true);

        mPluginManager.addPluginListener(this, DozeServicePlugin.class, false /* allowMultiple */);
        DozeComponent dozeComponent = mDozeComponentBuilder.build(this);
        mDozeMachine = dozeComponent.getDozeMachine();
        mDozeMachine.onConfigurationChanged(getResources().getConfiguration());
    }

    @Override
    public void onDestroy() {
        if (mPluginManager != null) {
            mPluginManager.removePluginListener(this);
        }
        super.onDestroy();
        if (mDozeMachine != null) {
            mDozeMachine.destroy();
        }
        mDozeMachine = null;
    }

    @Override
    public void onPluginConnected(DozeServicePlugin plugin, Context pluginContext) {
        mDozePlugin = plugin;
        mDozePlugin.setDozeRequester(this);
    }

    @Override
    public void onPluginDisconnected(DozeServicePlugin plugin) {
        if (mDozePlugin != null) {
            mDozePlugin.onDreamingStopped();
            mDozePlugin = null;
        }
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (mDozeMachine != null) {
            mDozeMachine.requestState(DozeMachine.State.INITIALIZED);
        }
        startDozing();
        if (mDozePlugin != null) {
            mDozePlugin.onDreamingStarted();
        }
    }

    @Override
    public void onDreamingStopped() {
        super.onDreamingStopped();
        if (mDozeMachine != null) {
            mDozeMachine.requestState(DozeMachine.State.FINISH);
        }
        if (mDozePlugin != null) {
            mDozePlugin.onDreamingStopped();
        }
    }

    @Override
    protected void dumpOnHandler(FileDescriptor fd, PrintWriter pw, String[] args) {
        super.dumpOnHandler(fd, pw, args);
        if (mDozeMachine != null) {
            mDozeMachine.dump(pw);
        }
    }

    @Override
    public void requestWakeUp(@DozeLog.Reason int reason) {
        final PowerManager pm = getSystemService(PowerManager.class);
        pm.wakeUp(SystemClock.uptimeMillis(), DozeLog.getPowerManagerWakeReason(reason),
                "com.android.systemui:NODOZE " + DozeLog.reasonToString(reason));
    }

    @Override
    public void onRequestShowDoze() {
        if (mDozeMachine != null) {
            mDozeMachine.requestState(DozeMachine.State.DOZE_AOD);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mDozeMachine.onConfigurationChanged(newConfig);
    }

    @Override
    public void onRequestHideDoze() {
        if (mDozeMachine != null) {
            mDozeMachine.requestState(DozeMachine.State.DOZE);
        }
    }

    @Override
    public void setDozeScreenState(int state) {
        super.setDozeScreenState(state);
        if (mDozeMachine != null) {
            mDozeMachine.onScreenState(state);
        }
    }
}
