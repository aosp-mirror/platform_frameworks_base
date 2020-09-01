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

package com.android.stubs.am;

import static com.android.frameworks.perftests.am.util.Constants.COMMAND_ACQUIRE_CONTENT_PROVIDER;
import static com.android.frameworks.perftests.am.util.Constants.COMMAND_BIND_SERVICE;
import static com.android.frameworks.perftests.am.util.Constants.COMMAND_RELEASE_CONTENT_PROVIDER;
import static com.android.frameworks.perftests.am.util.Constants.COMMAND_SEND_BROADCAST;
import static com.android.frameworks.perftests.am.util.Constants.COMMAND_START_ACTIVITY;
import static com.android.frameworks.perftests.am.util.Constants.COMMAND_STOP_ACTIVITY;
import static com.android.frameworks.perftests.am.util.Constants.COMMAND_UNBIND_SERVICE;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IContentProvider;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;

import com.android.frameworks.perftests.am.util.Constants;
import com.android.frameworks.perftests.am.util.ICommandReceiver;

public class InitService extends Service {
    private static final String TAG = "InitService";
    public static final boolean VERBOSE = false;

    private static class Stub extends ICommandReceiver.Stub {
        private final Context mContext;
        private final Messenger mCallback;
        private final Handler mHandler;
        private final Messenger mMessenger;
        final ArrayMap<String, MyServiceConnection> mServices =
                new ArrayMap<String, MyServiceConnection>();
        final ArrayMap<Uri, IContentProvider> mProviders =
                new ArrayMap<Uri, IContentProvider>();

        Stub(Context context, Messenger callback) {
            mContext = context;
            mCallback = callback;
            HandlerThread thread = new HandlerThread("result handler");
            thread.start();
            mHandler = new H(thread.getLooper());
            mMessenger = new Messenger(mHandler);
        }

        private class H extends Handler {
            H(Looper looper) {
                super(looper);
            }

            @Override
            public void handleMessage(Message msg) {
                if (msg.what == Constants.MSG_DEFAULT) {
                    if (VERBOSE) {
                        Log.i(TAG, "H: received seq=" + msg.arg1 + ", result=" + msg.arg2);
                    }
                    sendResult(mCallback, Constants.REPLY_COMMAND_RESULT, msg.arg1, msg.arg2, null);
                } else if (msg.what == Constants.MSG_UNBIND_DONE) {
                    if (VERBOSE) {
                        Log.i(TAG, "H: received unbind=" + msg.obj);
                    }
                    synchronized (InitService.sStub) {
                        Bundle b = (Bundle) msg.obj;
                        String pkg = b.getString(Constants.EXTRA_SOURCE_PACKAGE, "");
                        MyServiceConnection c = mServices.remove(pkg);
                        if (c != null) {
                            sendResult(mCallback, Constants.REPLY_COMMAND_RESULT, c.mSeq,
                                    Constants.RESULT_NO_ERROR, null);
                        }
                    }
                }
            }
        }

        @Override
        public void sendCommand(int command, int seq, String sourcePackage, String targetPackage,
                int flags, Bundle bundle) {
            if (VERBOSE) {
                Log.i(TAG, "Received command=" + command + ", seq=" + seq + ", from="
                        + sourcePackage + ", to=" + targetPackage + ", flags=" + flags);
            }
            switch (command) {
                case COMMAND_BIND_SERVICE:
                    handleBindService(seq, targetPackage, flags, bundle);
                    break;
                case COMMAND_UNBIND_SERVICE:
                    handleUnbindService(seq, targetPackage);
                    break;
                case COMMAND_ACQUIRE_CONTENT_PROVIDER:
                    acquireProvider(seq, bundle.getParcelable(Constants.EXTRA_URI));
                    break;
                case COMMAND_RELEASE_CONTENT_PROVIDER:
                    releaseProvider(seq, bundle.getParcelable(Constants.EXTRA_URI));
                    break;
                case COMMAND_SEND_BROADCAST:
                    sendBroadcast(seq, targetPackage);
                    break;
                case COMMAND_START_ACTIVITY:
                    startActivity(seq, targetPackage);
                    break;
                case COMMAND_STOP_ACTIVITY:
                    stopActivity(seq, targetPackage);
                    break;
            }
        }

        private void handleBindService(int seq, String targetPackage, int flags, Bundle bundle) {
            Intent intent = new Intent();
            intent.setClassName(targetPackage, "com.android.stubs.am.TestService");
            intent.putExtra(Constants.EXTRA_RECEIVER_CALLBACK, mMessenger);
            if (bundle != null) {
                intent.putExtras(bundle);
            }
            synchronized (this) {
                if (!mServices.containsKey(targetPackage)) {
                    MyServiceConnection c = new MyServiceConnection(mCallback);
                    c.mSeq = seq;
                    if (!mContext.bindService(intent, c, flags)) {
                        Log.e(TAG, "Unable to bind to service in " + targetPackage);
                        sendResult(mCallback, Constants.REPLY_COMMAND_RESULT, seq,
                                Constants.RESULT_ERROR, null);
                    } else {
                        if (VERBOSE) {
                            Log.i(TAG, "Bind to service " + intent);
                        }
                        mServices.put(targetPackage, c);
                    }
                } else {
                    sendResult(mCallback, Constants.REPLY_COMMAND_RESULT, seq,
                            Constants.RESULT_NO_ERROR, null);
                }
            }
        }

        private void handleUnbindService(int seq, String target) {
            MyServiceConnection c = null;
            synchronized (this) {
                c = mServices.get(target);
            }
            if (c != null) {
                c.mSeq = seq;
                mContext.unbindService(c);
            }
        }

        private void acquireProvider(int seq, Uri uri) {
            ContentResolver resolver = mContext.getContentResolver();
            IContentProvider provider = resolver.acquireProvider(uri);
            if (provider != null) {
                synchronized (mProviders) {
                    mProviders.put(uri, provider);
                }
                sendResult(mCallback, Constants.REPLY_COMMAND_RESULT, seq,
                        Constants.RESULT_NO_ERROR, null);
            } else {
                sendResult(mCallback, Constants.REPLY_COMMAND_RESULT, seq,
                        Constants.RESULT_ERROR, null);
            }
        }

        private void releaseProvider(int seq, Uri uri) {
            ContentResolver resolver = mContext.getContentResolver();
            IContentProvider provider;
            synchronized (mProviders) {
                provider = mProviders.get(uri);
            }
            if (provider != null) {
                resolver.releaseProvider(provider);
                synchronized (mProviders) {
                    mProviders.remove(uri);
                }
            }
            sendResult(mCallback, Constants.REPLY_COMMAND_RESULT, seq,
                    Constants.RESULT_NO_ERROR, null);
        }

        private void sendBroadcast(final int seq, String targetPackage) {
            Intent intent = new Intent(Constants.STUB_ACTION_BROADCAST);
            intent.setPackage(targetPackage);
            mContext.sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    sendResult(mCallback, Constants.REPLY_COMMAND_RESULT, seq,
                            Constants.RESULT_NO_ERROR, null);
                }
            }, null, 0, null, null);
        }

        private void startActivity(int seq, String targetPackage) {
            Intent intent = new Intent(Constants.STUB_ACTION_ACTIVITY);
            intent.setClassName(targetPackage, "com.android.stubs.am.TestActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra(Constants.EXTRA_ARG1, seq);
            intent.putExtra(Constants.EXTRA_RECEIVER_CALLBACK, mMessenger);
            mContext.startActivity(intent);
        }

        private void stopActivity(int seq, String targetPackage) {
            Intent intent = new Intent(Constants.STUB_ACTION_ACTIVITY);
            intent.setClassName(targetPackage, "com.android.stubs.am.TestActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra(Constants.EXTRA_REQ_FINISH_ACTIVITY, true);
            intent.putExtra(Constants.EXTRA_ARG1, seq);
            intent.putExtra(Constants.EXTRA_RECEIVER_CALLBACK, mMessenger);
            mContext.startActivity(intent);
        }
    };

    private static void sendResult(Messenger callback, int what, int seq, int result, Object obj) {
        Message msg = Message.obtain();
        msg.what = what;
        msg.arg1 = seq;
        msg.arg2 = result;
        msg.obj = obj;
        try {
            if (VERBOSE) {
                Log.i(TAG, "Sending result seq=" + seq + ", result=" + result);
            }
            callback.send(msg);
        } catch (RemoteException e) {
            Log.e(TAG, "Error in sending result back", e);
        }
        msg.recycle();
    }

    private static class MyServiceConnection implements ServiceConnection {
        private Messenger mCallback;
        int mSeq;

        MyServiceConnection(Messenger callback) {
            mCallback = callback;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            sendResult(mCallback, Constants.REPLY_COMMAND_RESULT, mSeq,
                    Constants.RESULT_NO_ERROR, null);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (sStub) {
                MyServiceConnection c = sStub.mServices.remove(name.getPackageName());
                if (c != null) {
                    sendResult(mCallback, Constants.REPLY_COMMAND_RESULT, c.mSeq,
                            Constants.RESULT_NO_ERROR, null);
                }
            }
        }
    }

    private static Stub sStub = null;

    @Override
    public IBinder onBind(Intent intent) {
        return new Binder();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Messenger callback = intent.getParcelableExtra(Constants.EXTRA_RECEIVER_CALLBACK);
        if (sStub == null) {
            sStub = new Stub(getApplicationContext(), callback);
        }

        Bundle extras = new Bundle();
        extras.putString(Constants.EXTRA_SOURCE_PACKAGE, getPackageName());
        extras.putBinder(Constants.EXTRA_RECEIVER_CALLBACK, sStub);
        sendResult(callback, Constants.REPLY_PACKAGE_START_RESULT,
                intent.getIntExtra(Constants.EXTRA_SEQ, -1), 0, extras);
        return START_NOT_STICKY;
    }
}
