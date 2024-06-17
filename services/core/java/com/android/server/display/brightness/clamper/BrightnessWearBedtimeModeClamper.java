/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.display.brightness.clamper;

import android.annotation.NonNull;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.internal.annotations.VisibleForTesting;

public class BrightnessWearBedtimeModeClamper extends
        BrightnessClamper<BrightnessWearBedtimeModeClamper.WearBedtimeModeData> {

    public static final int BEDTIME_MODE_OFF = 0;
    public static final int BEDTIME_MODE_ON = 1;

    private final Context mContext;

    private final ContentObserver mSettingsObserver;

    BrightnessWearBedtimeModeClamper(Handler handler, Context context,
            BrightnessClamperController.ClamperChangeListener listener, WearBedtimeModeData data) {
        this(new Injector(), handler, context, listener, data);
    }

    @VisibleForTesting
    BrightnessWearBedtimeModeClamper(Injector injector, Handler handler, Context context,
            BrightnessClamperController.ClamperChangeListener listener, WearBedtimeModeData data) {
        super(handler, listener);
        mContext = context;
        mBrightnessCap = data.getBrightnessWearBedtimeModeCap();
        mSettingsObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                final int bedtimeModeSetting = Settings.Global.getInt(
                        mContext.getContentResolver(),
                        Settings.Global.Wearable.BEDTIME_MODE,
                        BEDTIME_MODE_OFF);
                mIsActive = bedtimeModeSetting == BEDTIME_MODE_ON;
                mChangeListener.onChanged();
            }
        };
        injector.registerBedtimeModeObserver(context.getContentResolver(), mSettingsObserver);
    }

    @NonNull
    @Override
    Type getType() {
        return Type.WEAR_BEDTIME_MODE;
    }

    @Override
    void onDeviceConfigChanged() {}

    @Override
    void onDisplayChanged(WearBedtimeModeData displayData) {
        mHandler.post(() -> {
            mBrightnessCap = displayData.getBrightnessWearBedtimeModeCap();
            mChangeListener.onChanged();
        });
    }

    @Override
    void stop() {
        mContext.getContentResolver().unregisterContentObserver(mSettingsObserver);
    }

    interface WearBedtimeModeData {
        float getBrightnessWearBedtimeModeCap();
    }

    @VisibleForTesting
    static class Injector {
        void registerBedtimeModeObserver(@NonNull ContentResolver cr,
                @NonNull ContentObserver observer) {
            cr.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.Wearable.BEDTIME_MODE),
                    /* notifyForDescendants= */ false, observer, UserHandle.USER_ALL);
        }
    }
}
