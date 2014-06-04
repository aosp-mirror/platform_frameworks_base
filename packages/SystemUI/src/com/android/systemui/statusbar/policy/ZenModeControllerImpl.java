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

package com.android.systemui.statusbar.policy;

import android.app.INotificationManager;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings.Global;
import android.service.notification.Condition;
import android.service.notification.IConditionListener;
import android.service.notification.ZenModeConfig;
import android.util.Slog;

import com.android.systemui.qs.GlobalSetting;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Platform implementation of the zen mode controller. **/
public class ZenModeControllerImpl implements ZenModeController {
    private static final String TAG = "ZenModeControllerImpl";
    private static final boolean DEBUG = false;

    private final ArrayList<Callback> mCallbacks = new ArrayList<Callback>();
    private final Context mContext;
    private final GlobalSetting mModeSetting;
    private final GlobalSetting mConfigSetting;
    private final INotificationManager mNoMan;
    private final LinkedHashMap<Uri, Condition> mConditions = new LinkedHashMap<Uri, Condition>();

    private boolean mRequesting;

    public ZenModeControllerImpl(Context context, Handler handler) {
        mContext = context;
        mModeSetting = new GlobalSetting(mContext, handler, Global.ZEN_MODE) {
            @Override
            protected void handleValueChanged(int value) {
                fireZenChanged(value != 0);
            }
        };
        mConfigSetting = new GlobalSetting(mContext, handler, Global.ZEN_MODE_CONFIG_ETAG) {
            @Override
            protected void handleValueChanged(int value) {
                fireExitConditionChanged();
            }
        };
        mModeSetting.setListening(true);
        mConfigSetting.setListening(true);
        mNoMan = INotificationManager.Stub.asInterface(
                ServiceManager.getService(Context.NOTIFICATION_SERVICE));
    }

    @Override
    public void addCallback(Callback callback) {
        mCallbacks.add(callback);
    }

    @Override
    public void removeCallback(Callback callback) {
        mCallbacks.remove(callback);
    }

    @Override
    public boolean isZen() {
        return mModeSetting.getValue() != 0;
    }

    @Override
    public void setZen(boolean zen) {
        mModeSetting.setValue(zen ? 1 : 0);
    }

    @Override
    public void requestConditions(boolean request) {
        mRequesting = request;
        try {
            mNoMan.requestZenModeConditions(mListener, request ? Condition.FLAG_RELEVANT_NOW : 0);
        } catch (RemoteException e) {
            // noop
        }
        if (!mRequesting) {
            mConditions.clear();
        }
    }

    @Override
    public void setExitConditionId(Uri exitConditionId) {
        try {
            mNoMan.setZenModeCondition(exitConditionId);
        } catch (RemoteException e) {
            // noop
        }
    }

    @Override
    public Uri getExitConditionId() {
        try {
            final ZenModeConfig config = mNoMan.getZenModeConfig();
            if (config != null) {
                return config.exitConditionId;
            }
        } catch (RemoteException e) {
            // noop
        }
        return null;
    }

    private void fireZenChanged(boolean zen) {
        for (Callback cb : mCallbacks) {
            cb.onZenChanged(zen);
        }
    }

    private void fireConditionsChanged(Condition[] conditions) {
        for (Callback cb : mCallbacks) {
            cb.onConditionsChanged(conditions);
        }
    }

    private void fireExitConditionChanged() {
        final Uri exitConditionId = getExitConditionId();
        if (DEBUG) Slog.d(TAG, "exitConditionId changed: " + exitConditionId);
        for (Callback cb : mCallbacks) {
            cb.onExitConditionChanged(exitConditionId);
        }
    }

    private void updateConditions(Condition[] conditions) {
        if (conditions == null || conditions.length == 0) return;
        for (Condition c : conditions) {
            if ((c.flags & Condition.FLAG_RELEVANT_NOW) == 0) continue;
            mConditions.put(c.id, c);
        }
        fireConditionsChanged(
                mConditions.values().toArray(new Condition[mConditions.values().size()]));
    }

    private final IConditionListener mListener = new IConditionListener.Stub() {
        @Override
        public void onConditionsReceived(Condition[] conditions) {
            if (DEBUG) Slog.d(TAG, "onConditionsReceived "
                    + (conditions == null ? 0 : conditions.length) + " mRequesting=" + mRequesting);
            if (!mRequesting) return;
            updateConditions(conditions);
        }
    };
}
