/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.os;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

public class HandlerCaller {

    public final Context mContext;
    
    final Looper mMainLooper;
    final Handler mH;

    final Callback mCallback;

    class MyHandler extends Handler {
        MyHandler(Looper looper) {
            super(looper);
        }
        
        @Override
        public void handleMessage(Message msg) {
            mCallback.executeMessage(msg);
        }
    }
    
    public interface Callback {
        public void executeMessage(Message msg);
    }
    
    public HandlerCaller(Context context, Callback callback) {
        mContext = context;
        mMainLooper = context.getMainLooper();
        mH = new MyHandler(mMainLooper);
        mCallback = callback;
    }

    public HandlerCaller(Context context, Looper looper, Callback callback) {
        mContext = context;
        mMainLooper = looper;
        mH = new MyHandler(mMainLooper);
        mCallback = callback;
    }

    public void executeOrSendMessage(Message msg) {
        // If we are calling this from the main thread, then we can call
        // right through.  Otherwise, we need to send the message to the
        // main thread.
        if (Looper.myLooper() == mMainLooper) {
            mCallback.executeMessage(msg);
            msg.recycle();
            return;
        }
        
        mH.sendMessage(msg);
    }
    
    public boolean hasMessages(int what) {
        return mH.hasMessages(what);
    }
    
    public void removeMessages(int what) {
        mH.removeMessages(what);
    }
    
    public void removeMessages(int what, Object obj) {
        mH.removeMessages(what, obj);
    }
    
    public void sendMessage(Message msg) {
        mH.sendMessage(msg);
    }
    
    public Message obtainMessage(int what) {
        return mH.obtainMessage(what);
    }
    
    public Message obtainMessageBO(int what, boolean arg1, Object arg2) {
        return mH.obtainMessage(what, arg1 ? 1 : 0, 0, arg2);
    }
    
    public Message obtainMessageBOO(int what, boolean arg1, Object arg2, Object arg3) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = arg2;
        args.arg2 = arg3;
        return mH.obtainMessage(what, arg1 ? 1 : 0, 0, args);
    }
    
    public Message obtainMessageO(int what, Object arg1) {
        return mH.obtainMessage(what, 0, 0, arg1);
    }
    
    public Message obtainMessageI(int what, int arg1) {
        return mH.obtainMessage(what, arg1, 0);
    }
    
    public Message obtainMessageII(int what, int arg1, int arg2) {
        return mH.obtainMessage(what, arg1, arg2);
    }
    
    public Message obtainMessageIO(int what, int arg1, Object arg2) {
        return mH.obtainMessage(what, arg1, 0, arg2);
    }
    
    public Message obtainMessageIIO(int what, int arg1, int arg2, Object arg3) {
        return mH.obtainMessage(what, arg1, arg2, arg3);
    }
    
    public Message obtainMessageIIOO(int what, int arg1, int arg2,
            Object arg3, Object arg4) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = arg3;
        args.arg2 = arg4;
        return mH.obtainMessage(what, arg1, arg2, args);
    }
    
    public Message obtainMessageIOO(int what, int arg1, Object arg2, Object arg3) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = arg2;
        args.arg2 = arg3;
        return mH.obtainMessage(what, arg1, 0, args);
    }
    
    public Message obtainMessageOO(int what, Object arg1, Object arg2) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = arg1;
        args.arg2 = arg2;
        return mH.obtainMessage(what, 0, 0, args);
    }
    
    public Message obtainMessageOOO(int what, Object arg1, Object arg2, Object arg3) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = arg1;
        args.arg2 = arg2;
        args.arg3 = arg3;
        return mH.obtainMessage(what, 0, 0, args);
    }
    
    public Message obtainMessageOOOO(int what, Object arg1, Object arg2,
            Object arg3, Object arg4) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = arg1;
        args.arg2 = arg2;
        args.arg3 = arg3;
        args.arg4 = arg4;
        return mH.obtainMessage(what, 0, 0, args);
    }
    
    public Message obtainMessageIIII(int what, int arg1, int arg2,
            int arg3, int arg4) {
        SomeArgs args = SomeArgs.obtain();
        args.argi1 = arg1;
        args.argi2 = arg2;
        args.argi3 = arg3;
        args.argi4 = arg4;
        return mH.obtainMessage(what, 0, 0, args);
    }
    
    public Message obtainMessageIIIIII(int what, int arg1, int arg2,
            int arg3, int arg4, int arg5, int arg6) {
        SomeArgs args = SomeArgs.obtain();
        args.argi1 = arg1;
        args.argi2 = arg2;
        args.argi3 = arg3;
        args.argi4 = arg4;
        args.argi5 = arg5;
        args.argi6 = arg6;
        return mH.obtainMessage(what, 0, 0, args);
    }
    
    public Message obtainMessageIIIIO(int what, int arg1, int arg2,
            int arg3, int arg4, Object arg5) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = arg5;
        args.argi1 = arg1;
        args.argi2 = arg2;
        args.argi3 = arg3;
        args.argi4 = arg4;
        return mH.obtainMessage(what, 0, 0, args);
    }
}
