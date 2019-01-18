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

package com.android.server.rollback;

import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Bundle;
import android.os.IBinder;

import java.util.function.Consumer;

/** {@code IntentSender} implementation for RollbackManager internal use. */
class LocalIntentReceiver {
    final Consumer<Intent> mConsumer;

    LocalIntentReceiver(Consumer<Intent> consumer) {
        mConsumer = consumer;
    }

    private IIntentSender.Stub mLocalSender = new IIntentSender.Stub() {
        @Override
        public void send(int code, Intent intent, String resolvedType, IBinder whitelistToken,
                IIntentReceiver finishedReceiver, String requiredPermission, Bundle options) {
            mConsumer.accept(intent);
        }
    };

    public IntentSender getIntentSender() {
        return new IntentSender((IIntentSender) mLocalSender);
    }
}
