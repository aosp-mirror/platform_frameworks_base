/**
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.internal.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Slog;

import java.util.Stack;

/**
 * <p>An asynchronous channel between two handlers.</p>
 *
 * <p>The handlers maybe in the same process or in another process. There
 * are two protocol styles that can be used with an AysncChannel. The
 * first is a simple request/reply protocol where the server does
 * not need to know which client is issuing the request.</p>
 *
 * <p>In a simple request/reply protocol the client/source sends requests to the
 * server/destination. And the server uses the replyToMessage methods.
 * In this usage model there is no need for the destination to
 * use the connect methods. The typical sequence of operations is:</p>
 *<ol>
 *   <li>Client calls AsyncChannel#connect</li>
 *   <li>Client receives CMD_CHANNEL_HALF_CONNECTED from AsyncChannel</li>
 *   <li><code>comm-loop:</code></li>
 *   <li>Client calls AsyncChannel#sendMessage(msgX)</li>
 *   <li>Server receives and processes msgX</li>
 *   <li>Server optionally calls AsyncChannel#replyToMessage(msgY)
 *       and if sent Client receives and processes msgY</li>
 *   <li>Loop to <code>comm-loop</code> until done</li>
 *   <li>When done Client calls {@link AsyncChannel#disconnect(int)}</li>
 *   <li>Client receives CMD_CHANNEL_DISCONNECTED from AsyncChannel</li>
 *</ol>
 *<br/>
 * <p>A second usage model is where the server/destination needs to know
 * which client it's connected too. For example the server needs to
 * send unsolicited messages back to the client. Or the server keeps
 * different state for each client. In this model the server will also
 * use the connect methods. The typical sequence of operation is:</p>
 *<ol>
 *   <li>Client calls AsyncChannel#connect</li>
 *   <li>Client receives CMD_CHANNEL_HALF_CONNECTED from AsyncChannel</li>
 *   <li>Client calls AsyncChannel#sendMessage(CMD_CHANNEL_FULL_CONNECTION)</li>
 *   <li>Server receives CMD_CHANNEL_FULL_CONNECTION</li>
 *   <li>Server calls AsyncChannel#connect</li>
 *   <li>Server receives CMD_CHANNEL_HALF_CONNECTED from AsyncChannel</li>
 *   <li>Server sends AsyncChannel#sendMessage(CMD_CHANNEL_FULLY_CONNECTED)</li>
 *   <li>Client receives CMD_CHANNEL_FULLY_CONNECTED</li>
 *   <li><code>comm-loop:</code></li>
 *   <li>Client/Server uses AsyncChannel#sendMessage/replyToMessage
 *       to communicate and perform work</li>
 *   <li>Loop to <code>comm-loop</code> until done</li>
 *   <li>When done Client/Server calls {@link AsyncChannel#disconnect(int)}</li>
 *   <li>Client/Server receives CMD_CHANNEL_DISCONNECTED from AsyncChannel</li>
 *</ol>
 */
public class AsyncChannel {
    /** Log tag */
    private static final String TAG = "AsyncChannel";

    /** Enable to turn on debugging */
    private static final boolean DBG = false;

    /**
     * Command sent when the channel is half connected. Half connected
     * means that the channel can be used to send commends to the destination
     * but the destination is unaware that the channel exists. The first
     * command sent to the destination is typically CMD_CHANNEL_FULL_CONNECTION if
     * it is desired to establish a long term connection, but any command maybe
     * sent.
     *
     * msg.arg1 == 0 : STATUS_SUCCESSFUL
     *             1 : STATUS_BINDING_UNSUCCESSFUL
     * msg.obj  == the AsyncChannel
     * msg.replyTo == dstMessenger if successful
     */
    public static final int CMD_CHANNEL_HALF_CONNECTED = -1;

    /**
     * Command typically sent when after receiving the CMD_CHANNEL_HALF_CONNECTED.
     * This is used to initiate a long term connection with the destination and
     * typically the destination will reply with CMD_CHANNEL_FULLY_CONNECTED.
     *
     * msg.replyTo = srcMessenger.
     */
    public static final int CMD_CHANNEL_FULL_CONNECTION = -2;

    /**
     * Command typically sent after the destination receives a CMD_CHANNEL_FULL_CONNECTION.
     * This signifies the acceptance or rejection of the channel by the sender.
     *
     * msg.arg1 == 0 : Accept connection
     *               : All other values signify the destination rejected the connection
     *                 and {@link AsyncChannel#disconnect(int)} would typically be called.
     */
    public static final int CMD_CHANNEL_FULLY_CONNECTED = -3;

    /**
     * Command sent when one side or the other wishes to disconnect. The sender
     * may or may not be able to receive a reply depending upon the protocol and
     * the state of the connection. The receiver should call {@link AsyncChannel#disconnect(int)}
     * to close its side of the channel and it will receive a CMD_CHANNEL_DISCONNECTED
     * when the channel is closed.
     *
     * msg.replyTo = messenger that is disconnecting
     */
    public static final int CMD_CHANNEL_DISCONNECT = -4;

    /**
     * Command sent when the channel becomes disconnected. This is sent when the
     * channel is forcibly disconnected by the system or as a reply to CMD_CHANNEL_DISCONNECT.
     *
     * msg.arg1 == 0 : STATUS_SUCCESSFUL
     *             1 : STATUS_BINDING_UNSUCCESSFUL
     *             2 : STATUS_SEND_UNSUCCESSFUL
     *               : All other values signify failure and the channel state is indeterminate
     * msg.obj  == the AsyncChannel
     * msg.replyTo = messenger disconnecting or null if it was never connected.
     */
    public static final int CMD_CHANNEL_DISCONNECTED = -5;

    /** Successful status always 0, !0 is an unsuccessful status */
    public static final int STATUS_SUCCESSFUL = 0;

    /** Error attempting to bind on a connect */
    public static final int STATUS_BINDING_UNSUCCESSFUL = 1;

    /** Error attempting to send a message */
    public static final int STATUS_SEND_UNSUCCESSFUL = 2;

    /** Service connection */
    private AsyncChannelConnection mConnection;

    /** Context for source */
    private Context mSrcContext;

    /** Handler for source */
    private Handler mSrcHandler;

    /** Messenger for source */
    private Messenger mSrcMessenger;

    /** Messenger for destination */
    private Messenger mDstMessenger;

    /**
     * AsyncChannel constructor
     */
    public AsyncChannel() {
    }

    /**
     * Connect handler to named package/class.
     *
     * Sends a CMD_CHANNEL_HALF_CONNECTED message to srcHandler when complete.
     *
     * @param srcContext is the context of the source
     * @param srcHandler is the hander to receive CONNECTED & DISCONNECTED
     *            messages
     * @param dstPackageName is the destination package name
     * @param dstClassName is the fully qualified class name (i.e. contains
     *            package name)
     */
    private void connectSrcHandlerToPackage(
            Context srcContext, Handler srcHandler, String dstPackageName, String dstClassName) {
        if (DBG) log("connect srcHandler to dst Package & class E");

        mConnection = new AsyncChannelConnection();

        /* Initialize the source information */
        mSrcContext = srcContext;
        mSrcHandler = srcHandler;
        mSrcMessenger = new Messenger(srcHandler);

        /*
         * Initialize destination information to null they will
         * be initialized when the AsyncChannelConnection#onServiceConnected
         * is called
         */
        mDstMessenger = null;

        /* Send intent to create the connection */
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setClassName(dstPackageName, dstClassName);
        boolean result = srcContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        if (!result) {
            replyHalfConnected(STATUS_BINDING_UNSUCCESSFUL);
        }

        if (DBG) log("connect srcHandler to dst Package & class X result=" + result);
    }

    /**
     * Connect handler to named package/class.
     *
     * Sends a CMD_CHANNEL_HALF_CONNECTED message to srcHandler when complete.
     *      msg.arg1 = status
     *      msg.obj = the AsyncChannel
     *
     * @param srcContext is the context of the source
     * @param srcHandler is the hander to receive CONNECTED & DISCONNECTED
     *            messages
     * @param dstPackageName is the destination package name
     * @param dstClassName is the fully qualified class name (i.e. contains
     *            package name)
     */
    public void connect(Context srcContext, Handler srcHandler, String dstPackageName,
            String dstClassName) {
        if (DBG) log("connect srcHandler to dst Package & class E");

        final class ConnectAsync implements Runnable {
            Context mSrcCtx;
            Handler mSrcHdlr;
            String mDstPackageName;
            String mDstClassName;

            ConnectAsync(Context srcContext, Handler srcHandler, String dstPackageName,
                    String dstClassName) {
                mSrcCtx = srcContext;
                mSrcHdlr = srcHandler;
                mDstPackageName = dstPackageName;
                mDstClassName = dstClassName;
            }

            public void run() {
                connectSrcHandlerToPackage(mSrcCtx, mSrcHdlr, mDstPackageName, mDstClassName);
            }
        }

        ConnectAsync ca = new ConnectAsync(srcContext, srcHandler, dstPackageName, dstClassName);
        new Thread(ca).start();

        if (DBG) log("connect srcHandler to dst Package & class X");
    }

    /**
     * Connect handler to a class
     *
     * Sends a CMD_CHANNEL_HALF_CONNECTED message to srcHandler when complete.
     *      msg.arg1 = status
     *      msg.obj = the AsyncChannel
     *
     * @param srcContext
     * @param srcHandler
     * @param klass is the class to send messages to.
     */
    public void connect(Context srcContext, Handler srcHandler, Class<?> klass) {
        connect(srcContext, srcHandler, klass.getPackage().getName(), klass.getName());
    }

    /**
     * Connect handler and messenger.
     *
     * Sends a CMD_CHANNEL_HALF_CONNECTED message to srcHandler when complete.
     *      msg.arg1 = status
     *      msg.obj = the AsyncChannel
     *
     * @param srcContext
     * @param srcHandler
     * @param dstMessenger
     */
    public void connect(Context srcContext, Handler srcHandler, Messenger dstMessenger) {
        if (DBG) log("connect srcHandler to the dstMessenger  E");

        // Initialize source fields
        mSrcContext = srcContext;
        mSrcHandler = srcHandler;
        mSrcMessenger = new Messenger(mSrcHandler);

        // Initialize destination fields
        mDstMessenger = dstMessenger;

        if (DBG) log("tell source we are half connected");

        // Tell source we are half connected
        replyHalfConnected(STATUS_SUCCESSFUL);

        if (DBG) log("connect srcHandler to the dstMessenger X");
    }

    /**
     * Connect two local Handlers.
     *
     * Sends a CMD_CHANNEL_HALF_CONNECTED message to srcHandler when complete.
     *      msg.arg1 = status
     *      msg.obj = the AsyncChannel
     *
     * @param srcContext is the context of the source
     * @param srcHandler is the hander to receive CONNECTED & DISCONNECTED
     *            messages
     * @param dstHandler is the hander to send messages to.
     */
    public void connect(Context srcContext, Handler srcHandler, Handler dstHandler) {
        connect(srcContext, srcHandler, new Messenger(dstHandler));
    }

    /**
     * Connect service and messenger.
     *
     * Sends a CMD_CHANNEL_HALF_CONNECTED message to srcAsyncService when complete.
     *      msg.arg1 = status
     *      msg.obj = the AsyncChannel
     *
     * @param srcAsyncService
     * @param dstMessenger
     */
    public void connect(AsyncService srcAsyncService, Messenger dstMessenger) {
        connect(srcAsyncService, srcAsyncService.getHandler(), dstMessenger);
    }

    /**
     * To close the connection call when handler receives CMD_CHANNEL_DISCONNECTED
     */
    public void disconnected() {
        mSrcHandler = null;
        mSrcMessenger = null;
        mDstMessenger = null;
        mConnection = null;
    }

    /**
     * Disconnect
     */
    public void disconnect() {
        if (mConnection != null) {
            mSrcContext.unbindService(mConnection);
        }
        if (mSrcHandler != null) {
            replyDisconnected(STATUS_SUCCESSFUL);
        }
    }

    /**
     * Send a message to the destination handler.
     *
     * @param msg
     */
    public void sendMessage(Message msg) {
        msg.replyTo = mSrcMessenger;
        try {
            mDstMessenger.send(msg);
        } catch (RemoteException e) {
            replyDisconnected(STATUS_SEND_UNSUCCESSFUL);
        }
    }

    /**
     * Send a message to the destination handler
     *
     * @param what
     */
    public void sendMessage(int what) {
        Message msg = Message.obtain();
        msg.what = what;
        sendMessage(msg);
    }

    /**
     * Send a message to the destination handler
     *
     * @param what
     * @param arg1
     */
    public void sendMessage(int what, int arg1) {
        Message msg = Message.obtain();
        msg.what = what;
        msg.arg1 = arg1;
        sendMessage(msg);
    }

    /**
     * Send a message to the destination handler
     *
     * @param what
     * @param arg1
     * @param arg2
     */
    public void sendMessage(int what, int arg1, int arg2) {
        Message msg = Message.obtain();
        msg.what = what;
        msg.arg1 = arg1;
        msg.arg2 = arg2;
        sendMessage(msg);
    }

    /**
     * Send a message to the destination handler
     *
     * @param what
     * @param arg1
     * @param arg2
     * @param obj
     */
    public void sendMessage(int what, int arg1, int arg2, Object obj) {
        Message msg = Message.obtain();
        msg.what = what;
        msg.arg1 = arg1;
        msg.arg2 = arg2;
        msg.obj = obj;
        sendMessage(msg);
    }

    /**
     * Send a message to the destination handler
     *
     * @param what
     * @param obj
     */
    public void sendMessage(int what, Object obj) {
        Message msg = Message.obtain();
        msg.what = what;
        msg.obj = obj;
        sendMessage(msg);
    }

    /**
     * Reply to srcMsg sending dstMsg
     *
     * @param srcMsg
     * @param dstMsg
     */
    public void replyToMessage(Message srcMsg, Message dstMsg) {
        try {
            srcMsg.replyTo.send(dstMsg);
        } catch (RemoteException e) {
            log("TODO: handle replyToMessage RemoteException" + e);
            e.printStackTrace();
        }
    }

    /**
     * Reply to srcMsg
     *
     * @param srcMsg
     * @param what
     */
    public void replyToMessage(Message srcMsg, int what) {
        Message msg = Message.obtain();
        msg.what = what;
        replyToMessage(srcMsg, msg);
    }

    /**
     * Reply to srcMsg
     *
     * @param srcMsg
     * @param what
     * @param arg1
     */
    public void replyToMessage(Message srcMsg, int what, int arg1) {
        Message msg = Message.obtain();
        msg.what = what;
        msg.arg1 = arg1;
        replyToMessage(srcMsg, msg);
    }

    /**
     * Reply to srcMsg
     *
     * @param srcMsg
     * @param what
     * @param arg1
     * @param arg2
     */
    public void replyToMessage(Message srcMsg, int what, int arg1, int arg2) {
        Message msg = Message.obtain();
        msg.what = what;
        msg.arg1 = arg1;
        msg.arg2 = arg2;
        replyToMessage(srcMsg, msg);
    }

    /**
     * Reply to srcMsg
     *
     * @param srcMsg
     * @param what
     * @param arg1
     * @param arg2
     * @param obj
     */
    public void replyToMessage(Message srcMsg, int what, int arg1, int arg2, Object obj) {
        Message msg = Message.obtain();
        msg.what = what;
        msg.arg1 = arg1;
        msg.arg2 = arg2;
        msg.obj = obj;
        replyToMessage(srcMsg, msg);
    }

    /**
     * Reply to srcMsg
     *
     * @param srcMsg
     * @param what
     * @param obj
     */
    public void replyToMessage(Message srcMsg, int what, Object obj) {
        Message msg = Message.obtain();
        msg.what = what;
        msg.obj = obj;
        replyToMessage(srcMsg, msg);
    }

    /**
     * Send the Message synchronously.
     *
     * @param msg to send
     * @return reply message or null if an error.
     */
    public Message sendMessageSynchronously(Message msg) {
        Message resultMsg = SyncMessenger.sendMessageSynchronously(mDstMessenger, msg);
        return resultMsg;
    }

    /**
     * Send the Message synchronously.
     *
     * @param what
     * @return reply message or null if an error.
     */
    public Message sendMessageSynchronously(int what) {
        Message msg = Message.obtain();
        msg.what = what;
        Message resultMsg = sendMessageSynchronously(msg);
        return resultMsg;
    }

    /**
     * Send the Message synchronously.
     *
     * @param what
     * @param arg1
     * @return reply message or null if an error.
     */
    public Message sendMessageSynchronously(int what, int arg1) {
        Message msg = Message.obtain();
        msg.what = what;
        msg.arg1 = arg1;
        Message resultMsg = sendMessageSynchronously(msg);
        return resultMsg;
    }

    /**
     * Send the Message synchronously.
     *
     * @param what
     * @param arg1
     * @param arg2
     * @return reply message or null if an error.
     */
    public Message sendMessageSynchronously(int what, int arg1, int arg2) {
        Message msg = Message.obtain();
        msg.what = what;
        msg.arg1 = arg1;
        msg.arg2 = arg2;
        Message resultMsg = sendMessageSynchronously(msg);
        return resultMsg;
    }

    /**
     * Send the Message synchronously.
     *
     * @param what
     * @param arg1
     * @param arg2
     * @param obj
     * @return reply message or null if an error.
     */
    public Message sendMessageSynchronously(int what, int arg1, int arg2, Object obj) {
        Message msg = Message.obtain();
        msg.what = what;
        msg.arg1 = arg1;
        msg.arg2 = arg2;
        msg.obj = obj;
        Message resultMsg = sendMessageSynchronously(msg);
        return resultMsg;
    }

    /**
     * Send the Message synchronously.
     *
     * @param what
     * @param obj
     * @return reply message or null if an error.
     */
    public Message sendMessageSynchronously(int what, Object obj) {
        Message msg = Message.obtain();
        msg.what = what;
        msg.obj = obj;
        Message resultMsg = sendMessageSynchronously(msg);
        return resultMsg;
    }

    /**
     * Helper class to send messages synchronously
     */
    private static class SyncMessenger {
        /** A stack of SyncMessengers */
        private static Stack<SyncMessenger> sStack = new Stack<SyncMessenger>();
        /** A number of SyncMessengers created */
        private static int sCount = 0;
        /** The handler thread */
        private HandlerThread mHandlerThread;
        /** The handler that will receive the result */
        private SyncHandler mHandler;
        /** The messenger used to send the message */
        private Messenger mMessenger;

        /** private constructor */
        private SyncMessenger() {
        }

        /** Synchronous Handler class */
        private class SyncHandler extends Handler {
            /** The object used to wait/notify */
            private Object mLockObject = new Object();
            /** The resulting message */
            private Message mResultMsg;

            /** Constructor */
            private SyncHandler(Looper looper) {
                super(looper);
            }

            /** Handle of the reply message */
            @Override
            public void handleMessage(Message msg) {
                mResultMsg = Message.obtain();
                mResultMsg.copyFrom(msg);
                synchronized(mLockObject) {
                    mLockObject.notify();
                }
            }
        }

        /**
         * @return the SyncMessenger
         */
        private static SyncMessenger obtain() {
            SyncMessenger sm;
            synchronized (sStack) {
                if (sStack.isEmpty()) {
                    sm = new SyncMessenger();
                    sm.mHandlerThread = new HandlerThread("SyncHandler-" + sCount++);
                    sm.mHandlerThread.start();
                    sm.mHandler = sm.new SyncHandler(sm.mHandlerThread.getLooper());
                    sm.mMessenger = new Messenger(sm.mHandler);
                } else {
                    sm = sStack.pop();
                }
            }
            return sm;
        }

        /**
         * Recycle this object
         */
        private void recycle() {
            synchronized (sStack) {
                sStack.push(this);
            }
        }

        /**
         * Send a message synchronously.
         *
         * @param msg to send
         * @return result message or null if an error occurs
         */
        private static Message sendMessageSynchronously(Messenger dstMessenger, Message msg) {
            SyncMessenger sm = SyncMessenger.obtain();
            try {
                msg.replyTo = sm.mMessenger;
                dstMessenger.send(msg);
                synchronized (sm.mHandler.mLockObject) {
                    sm.mHandler.mLockObject.wait();
                }
            } catch (InterruptedException e) {
                sm.mHandler.mResultMsg = null;
            } catch (RemoteException e) {
                sm.mHandler.mResultMsg = null;
            }
            Message resultMsg = sm.mHandler.mResultMsg;
            sm.recycle();
            return resultMsg;
        }
    }

    /**
     * Reply to the src handler that we're half connected.
     * see: CMD_CHANNEL_HALF_CONNECTED for message contents
     *
     * @param status to be stored in msg.arg1
     */
    private void replyHalfConnected(int status) {
        Message msg = mSrcHandler.obtainMessage(CMD_CHANNEL_HALF_CONNECTED);
        msg.arg1 = status;
        msg.obj = this;
        msg.replyTo = mDstMessenger;
        mSrcHandler.sendMessage(msg);
    }

    /**
     * Reply to the src handler that we are disconnected
     * see: CMD_CHANNEL_DISCONNECTED for message contents
     *
     * @param status to be stored in msg.arg1
     */
    private void replyDisconnected(int status) {
        Message msg = mSrcHandler.obtainMessage(CMD_CHANNEL_DISCONNECTED);
        msg.arg1 = status;
        msg.obj = this;
        msg.replyTo = mDstMessenger;
        mSrcHandler.sendMessage(msg);
    }


    /**
     * ServiceConnection to receive call backs.
     */
    class AsyncChannelConnection implements ServiceConnection {
        AsyncChannelConnection() {
        }

        public void onServiceConnected(ComponentName className, IBinder service) {
            mDstMessenger = new Messenger(service);
            replyHalfConnected(STATUS_SUCCESSFUL);
        }

        public void onServiceDisconnected(ComponentName className) {
            replyDisconnected(STATUS_SUCCESSFUL);
        }
    }

    /**
     * Log the string.
     *
     * @param s
     */
    private static void log(String s) {
        Slog.d(TAG, s);
    }
}
