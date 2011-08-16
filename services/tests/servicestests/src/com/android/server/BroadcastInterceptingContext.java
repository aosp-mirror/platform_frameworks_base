/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.AbstractFuture;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link ContextWrapper} that can attach listeners for upcoming
 * {@link Context#sendBroadcast(Intent)}.
 */
public class BroadcastInterceptingContext extends ContextWrapper {
    private static final String TAG = "WatchingContext";

    private final List<BroadcastInterceptor> mInterceptors = Lists.newArrayList();

    public class BroadcastInterceptor extends AbstractFuture<Intent> {
        private final BroadcastReceiver mReceiver;
        private final IntentFilter mFilter;

        public BroadcastInterceptor(BroadcastReceiver receiver, IntentFilter filter) {
            mReceiver = receiver;
            mFilter = filter;
        }

        public boolean dispatchBroadcast(Intent intent) {
            if (mFilter.match(getContentResolver(), intent, false, TAG) > 0) {
                if (mReceiver != null) {
                    final Context context = BroadcastInterceptingContext.this;
                    mReceiver.onReceive(context, intent);
                    return false;
                } else {
                    set(intent);
                    return true;
                }
            } else {
                return false;
            }
        }

        @Override
        public Intent get() throws InterruptedException, ExecutionException {
            try {
                return get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public BroadcastInterceptingContext(Context base) {
        super(base);
    }

    public Future<Intent> nextBroadcastIntent(String action) {
        return nextBroadcastIntent(new IntentFilter(action));
    }

    public Future<Intent> nextBroadcastIntent(IntentFilter filter) {
        final BroadcastInterceptor interceptor = new BroadcastInterceptor(null, filter);
        synchronized (mInterceptors) {
            mInterceptors.add(interceptor);
        }
        return interceptor;
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        synchronized (mInterceptors) {
            mInterceptors.add(new BroadcastInterceptor(receiver, filter));
        }
        return null;
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
            String broadcastPermission, Handler scheduler) {
        return registerReceiver(receiver, filter);
    }

    @Override
    public void unregisterReceiver(BroadcastReceiver receiver) {
        synchronized (mInterceptors) {
            final Iterator<BroadcastInterceptor> i = mInterceptors.iterator();
            while (i.hasNext()) {
                final BroadcastInterceptor interceptor = i.next();
                if (receiver.equals(interceptor.mReceiver)) {
                    i.remove();
                }
            }
        }
    }

    @Override
    public void sendBroadcast(Intent intent) {
        synchronized (mInterceptors) {
            final Iterator<BroadcastInterceptor> i = mInterceptors.iterator();
            while (i.hasNext()) {
                final BroadcastInterceptor interceptor = i.next();
                if (interceptor.dispatchBroadcast(intent)) {
                    i.remove();
                }
            }
        }
    }

    @Override
    public void sendStickyBroadcast(Intent intent) {
        sendBroadcast(intent);
    }

    @Override
    public void sendBroadcast(Intent intent, String receiverPermission) {
        sendBroadcast(intent);
    }

    @Override
    public void removeStickyBroadcast(Intent intent) {
        // ignored
    }
}
