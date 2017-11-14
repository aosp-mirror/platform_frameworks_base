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
 *   <li>Client calls AsyncChannel#connectSync or Asynchronously:</li>
 *      <ol>For an asynchronous half connection client calls AsyncChannel#connect.</ol>
 *          <li>Client receives CMD_CHANNEL_HALF_CONNECTED from AsyncChannel</li>
 *      </ol>
 *   <li><code>comm-loop:</code></li>
 *   <li>Client calls AsyncChannel#sendMessage</li>
 *   <li>Server processes messages and optionally replies using AsyncChannel#replyToMessage
 *   <li>Loop to <code>comm-loop</code> until done</li>
 *   <li>When done Client calls {@link AsyncChannel#disconnect}</li>
 *   <li>Client/Server receives CMD_CHANNEL_DISCONNECTED from AsyncChannel</li>
 *</ol>
 *<br/>
 * <p>A second usage model is where the server/destination needs to know
 * which client it's connected too. For example the server needs to
 * send unsolicited messages back to the client. Or the server keeps
 * different state for each client. In this model the server will also
 * use the connect methods. The typical sequence of operation is:</p>
 *<ol>
 *   <li>Client calls AsyncChannel#fullyConnectSync or Asynchronously:<li>
 *      <ol>For an asynchronous full connection it calls AsyncChannel#connect</li>
 *          <li>Client receives CMD_CHANNEL_HALF_CONNECTED from AsyncChannel</li>
 *          <li>Client calls AsyncChannel#sendMessage(CMD_CHANNEL_FULL_CONNECTION)</li>
 *      </ol>
 *   <li>Server receives CMD_CHANNEL_FULL_CONNECTION</li>
 *   <li>Server calls AsyncChannel#connected</li>
 *   <li>Server sends AsyncChannel#sendMessage(CMD_CHANNEL_FULLY_CONNECTED)</li>
 *   <li>Client receives CMD_CHANNEL_FULLY_CONNECTED</li>
 *   <li><code>comm-loop:</code></li>
 *   <li>Client/Server uses AsyncChannel#sendMessage/replyToMessage
 *       to communicate and perform work</li>
 *   <li>Loop to <code>comm-loop</code> until done</li>
 *   <li>When done Client/Server calls {@link AsyncChannel#disconnect}</li>
 *   <li>Client/Server receives CMD_CHANNEL_DISCONNECTED from AsyncChannel</li>
 *</ol>
 *
 * TODO: Consider simplifying where we have connect and fullyConnect with only one response
 * message RSP_CHANNEL_CONNECT instead of two, CMD_CHANNEL_HALF_CONNECTED and
 * CMD_CHANNEL_FULLY_CONNECTED. We'd also change CMD_CHANNEL_FULL_CONNECTION to REQ_CHANNEL_CONNECT.
 */
public class AsyncChannel {
    /** Log tag */
    private static final String TAG = "AsyncChannel";

    /** Enable to turn on debugging */
    private static final boolean DBG = false;

    private static final int BASE = Protocol.BASE_SYSTEM_ASYNC_CHANNEL;

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
    public static final int CMD_CHANNEL_HALF_CONNECTED = BASE + 0;

    /**
     * Command typically sent when after receiving the CMD_CHANNEL_HALF_CONNECTED.
     * This is used to initiate a long term connection with the destination and
     * typically the destination will reply with CMD_CHANNEL_FULLY_CONNECTED.
     *
     * msg.replyTo = srcMessenger.
     */
    public static final int CMD_CHANNEL_FULL_CONNECTION = BASE + 1;

    /**
     * Command typically sent after the destination receives a CMD_CHANNEL_FULL_CONNECTION.
     * This signifies the acceptance or rejection of the channel by the sender.
     *
     * msg.arg1 == 0 : Accept connection
     *               : All other values signify the destination rejected the connection
     *                 and {@link AsyncChannel#disconnect} would typically be called.
     */
    public static final int CMD_CHANNEL_FULLY_CONNECTED = BASE + 2;

    /**
     * Command sent when one side or the other wishes to disconnect. The sender
     * may or may not be able to receive a reply depending upon the protocol and
     * the state of the connection. The receiver should call {@link AsyncChannel#disconnect}
     * to close its side of the channel and it will receive a CMD_CHANNEL_DISCONNECTED
     * when the channel is closed.
     *
     * msg.replyTo = messenger that is disconnecting
     */
    public static final int CMD_CHANNEL_DISCONNECT = BASE + 3;

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
    public static final int CMD_CHANNEL_DISCONNECTED = BASE + 4;

    private static final int CMD_TO_STRING_COUNT = CMD_CHANNEL_DISCONNECTED - BASE + 1;
    private static String[] sCmdToString = new String[CMD_TO_STRING_COUNT];
    static {
        sCmdToString[CMD_CHANNEL_HALF_CONNECTED - BASE] = "CMD_CHANNEL_HALF_CONNECTED";
        sCmdToString[CMD_CHANNEL_FULL_CONNECTION - BASE] = "CMD_CHANNEL_FULL_CONNECTION";
        sCmdToString[CMD_CHANNEL_FULLY_CONNECTED - BASE] = "CMD_CHANNEL_FULLY_CONNECTED";
        sCmdToString[CMD_CHANNEL_DISCONNECT - BASE] = "CMD_CHANNEL_DISCONNECT";
        sCmdToString[CMD_CHANNEL_DISCONNECTED - BASE] = "CMD_CHANNEL_DISCONNECTED";
    }
    protected static String cmdToString(int cmd) {
        cmd -= BASE;
        if ((cmd >= 0) && (cmd < sCmdToString.length)) {
            return sCmdToString[cmd];
        } else {
            return null;
        }
    }

    /** Successful status always 0, !0 is an unsuccessful status */
    public static final int STATUS_SUCCESSFUL = 0;

    /** Error attempting to bind on a connect */
    public static final int STATUS_BINDING_UNSUCCESSFUL = 1;

    /** Error attempting to send a message */
    public static final int STATUS_SEND_UNSUCCESSFUL = 2;

    /** CMD_FULLY_CONNECTED refused because a connection already exists*/
    public static final int STATUS_FULL_CONNECTION_REFUSED_ALREADY_CONNECTED = 3;

    /** Error indicating abnormal termination of destination messenger */
    public static final int STATUS_REMOTE_DISCONNECTION = 4;

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

    /** Death Monitor for destination messenger */
    private DeathMonitor mDeathMonitor;

    /**
     * AsyncChannel constructor
     */
    public AsyncChannel() {
    }

    /**
     * Connect handler to named package/class synchronously.
     *
     * @param srcContext is the context of the source
     * @param srcHandler is the hander to receive CONNECTED & DISCONNECTED
     *            messages
     * @param dstPackageName is the destination package name
     * @param dstClassName is the fully qualified class name (i.e. contains
     *            package name)
     *
     * @return STATUS_SUCCESSFUL on success any other value is an error.
     */
    public int connectSrcHandlerToPackageSync(
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
        if (DBG) log("connect srcHandler to dst Package & class X result=" + result);
        return result ? STATUS_SUCCESSFUL : STATUS_BINDING_UNSUCCESSFUL;
    }

    /**
     * Connect a handler to Messenger synchronously.
     *
     * @param srcContext is the context of the source
     * @param srcHandler is the hander to receive CONNECTED & DISCONNECTED
     *            messages
     * @param dstMessenger is the hander to send messages to.
     *
     * @return STATUS_SUCCESSFUL on success any other value is an error.
     */
    public int connectSync(Context srcContext, Handler srcHandler, Messenger dstMessenger) {
        if (DBG) log("halfConnectSync srcHandler to the dstMessenger  E");

        // We are connected
        connected(srcContext, srcHandler, dstMessenger);

        if (DBG) log("halfConnectSync srcHandler to the dstMessenger X");
        return STATUS_SUCCESSFUL;
    }

    /**
     * connect two local Handlers synchronously.
     *
     * @param srcContext is the context of the source
     * @param srcHandler is the hander to receive CONNECTED & DISCONNECTED
     *            messages
     * @param dstHandler is the hander to send messages to.
     *
     * @return STATUS_SUCCESSFUL on success any other value is an error.
     */
    public int connectSync(Context srcContext, Handler srcHandler, Handler dstHandler) {
        return connectSync(srcContext, srcHandler, new Messenger(dstHandler));
    }

    /**
     * Fully connect two local Handlers synchronously.
     *
     * @param srcContext is the context of the source
     * @param srcHandler is the hander to receive CONNECTED & DISCONNECTED
     *            messages
     * @param dstHandler is the hander to send messages to.
     *
     * @return STATUS_SUCCESSFUL on success any other value is an error.
     */
    public int fullyConnectSync(Context srcContext, Handler srcHandler, Handler dstHandler) {
        int status = connectSync(srcContext, srcHandler, dstHandler);
        if (status == STATUS_SUCCESSFUL) {
            Message response = sendMessageSynchronously(CMD_CHANNEL_FULL_CONNECTION);
            status = response.arg1;
        }
        return status;
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

            @Override
            public void run() {
                int result = connectSrcHandlerToPackageSync(mSrcCtx, mSrcHdlr, mDstPackageName,
                        mDstClassName);
                replyHalfConnected(result);
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

        // We are connected
        connected(srcContext, srcHandler, dstMessenger);

        // Tell source we are half connected
        replyHalfConnected(STATUS_SUCCESSFUL);

        if (DBG) log("connect srcHandler to the dstMessenger X");
    }

    /**
     * Connect handler to messenger. This method is typically called
     * when a server receives a CMD_CHANNEL_FULL_CONNECTION request
     * and initializes the internal instance variables to allow communication
     * with the dstMessenger.
     *
     * @param srcContext
     * @param srcHandler
     * @param dstMessenger
     */
    public void connected(Context srcContext, Handler srcHandler, Messenger dstMessenger) {
        if (DBG) log("connected srcHandler to the dstMessenger  E");

        // Initialize source fields
        mSrcContext = srcContext;
        mSrcHandler = srcHandler;
        mSrcMessenger = new Messenger(mSrcHandler);

        // Initialize destination fields
        mDstMessenger = dstMessenger;
        if (DBG) log("connected srcHandler to the dstMessenger X");
    }

    /**
     * Connect two local Handlers.
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
        mSrcContext = null;
        mSrcHandler = null;
        mSrcMessenger = null;
        mDstMessenger = null;
        mDeathMonitor = null;
        mConnection = null;
    }

    /**
     * Disconnect
     */
    public void disconnect() {
        if ((mConnection != null) && (mSrcContext != null)) {
            mSrcContext.unbindService(mConnection);
            mConnection = null;
        }
        try {
            // Send the DISCONNECTED, although it may not be received
            // but its the best we can do.
            Message msg = Message.obtain();
            msg.what = CMD_CHANNEL_DISCONNECTED;
            msg.replyTo = mSrcMessenger;
            mDstMessenger.send(msg);
        } catch(Exception e) {
        }
        // Tell source we're disconnected.
        replyDisconnected(STATUS_SUCCESSFUL);
        mSrcHandler = null;
        // Unlink only when bindService isn't used
        if (mConnection == null && mDstMessenger != null && mDeathMonitor!= null) {
            mDstMessenger.getBinder().unlinkToDeath(mDeathMonitor, 0);
            mDeathMonitor = null;
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
            dstMsg.replyTo = mSrcMessenger;
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
                Message msgCopy = Message.obtain();
                msgCopy.copyFrom(msg);
                synchronized(mLockObject) {
                    mResultMsg = msgCopy;
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
            Message resultMsg = null;
            try {
                if (dstMessenger != null && msg != null) {
                    msg.replyTo = sm.mMessenger;
                    synchronized (sm.mHandler.mLockObject) {
                        if (sm.mHandler.mResultMsg != null) {
                            Slog.wtf(TAG, "mResultMsg should be null here");
                            sm.mHandler.mResultMsg = null;
                        }
                        dstMessenger.send(msg);
                        sm.mHandler.mLockObject.wait();
                        resultMsg = sm.mHandler.mResultMsg;
                        sm.mHandler.mResultMsg = null;
                    }
                }
            } catch (InterruptedException e) {
                Slog.e(TAG, "error in sendMessageSynchronously", e);
            } catch (RemoteException e) {
                Slog.e(TAG, "error in sendMessageSynchronously", e);
            }
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
        if (!linkToDeathMonitor()) {
            // Override status to indicate failure
            msg.arg1 = STATUS_BINDING_UNSUCCESSFUL;
        }

        mSrcHandler.sendMessage(msg);
    }

    /**
     * Link to death monitor for destination messenger. Returns true if successfully binded to
     * destination messenger; false otherwise.
     */
    private boolean linkToDeathMonitor() {
        // Link to death only when bindService isn't used and not already linked.
        if (mConnection == null && mDeathMonitor == null) {
            mDeathMonitor = new DeathMonitor();
            try {
                mDstMessenger.getBinder().linkToDeath(mDeathMonitor, 0);
            } catch (RemoteException e) {
                mDeathMonitor = null;
                return false;
            }
        }
        return true;
    }

    /**
     * Reply to the src handler that we are disconnected
     * see: CMD_CHANNEL_DISCONNECTED for message contents
     *
     * @param status to be stored in msg.arg1
     */
    private void replyDisconnected(int status) {
        // Can't reply if already disconnected. Avoid NullPointerException.
        if (mSrcHandler == null) return;
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

        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            mDstMessenger = new Messenger(service);
            replyHalfConnected(STATUS_SUCCESSFUL);
        }

        @Override
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

    private final class DeathMonitor implements IBinder.DeathRecipient {

        DeathMonitor() {
        }

        public void binderDied() {
            replyDisconnected(STATUS_REMOTE_DISCONNECTION);
        }

    }
}
