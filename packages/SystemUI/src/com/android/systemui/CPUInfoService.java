/*
 * Copyright (C) 2017 The OmniROM Project
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

package com.android.systemui;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

import java.lang.StringBuffer;

public class CPUInfoService extends Service {
    private View mView;
    private Thread mCurCPUThread;
    private final String TAG = "CPUInfoService";
    private int mNumCpus = 1;
    private String[] mCurrFreq=null;
    private String[] mCurrGov=null;

    private static final String NUM_OF_CPUS_PATH = "/sys/devices/system/cpu/present";

    private class CPUView extends View {
        private Paint mOnlinePaint;
        private Paint mOfflinePaint;
        private Paint mLpPaint;
        private float mAscent;
        private int mFH;
        private int mMaxWidth;

        private int mNeededWidth;
        private int mNeededHeight;

        private boolean mLpMode;
        private String mCPUTemp;
        private boolean mDataAvail;

        private Handler mCurCPUHandler = new Handler() {
            public void handleMessage(Message msg) {
                if(msg.obj==null){
                    return;
                }
                if(msg.what==1){
                    String msgData = (String) msg.obj;
                    try {
                        String[] parts=msgData.split(";");
                        mCPUTemp=parts[0];
                        mLpMode = parts[1].equals("1");

                        String[] cpuParts=parts[2].split("\\|");
                        for(int i=0; i<cpuParts.length; i++){
                            String cpuInfo=cpuParts[i];
                            String cpuInfoParts[]=cpuInfo.split(":");
                            if(cpuInfoParts.length==2){
                                mCurrFreq[i]=cpuInfoParts[0];
                                mCurrGov[i]=cpuInfoParts[1];
                            } else {
                                mCurrFreq[i]="0";
                                mCurrGov[i]="";
                            }
                        }
                        mDataAvail = true;
                        updateDisplay();
                    } catch(ArrayIndexOutOfBoundsException e) {
                        Log.e(TAG, "illegal data " + msgData);
                    }
                }
            }
        };

        CPUView(Context c) {
            super(c);

            setPadding(4, 4, 4, 4);
            //setBackgroundResource(com.android.internal.R.drawable.load_average_background);

            // Need to scale text size by density...  but we won't do it
            // linearly, because with higher dps it is nice to squeeze the
            // text a bit to fit more of it.  And with lower dps, trying to
            // go much smaller will result in unreadable text.
            int textSize = 10;
            float density = c.getResources().getDisplayMetrics().density;
            if (density < 1) {
                textSize = 9;
            } else {
                textSize = (int)(12*density);
                if (textSize < 10) {
                    textSize = 10;
                }
            }
            mOnlinePaint = new Paint();
            mOnlinePaint.setAntiAlias(true);
            mOnlinePaint.setTextSize(textSize);
            mOnlinePaint.setARGB(255, 0, 255, 0);

            mOfflinePaint = new Paint();
            mOfflinePaint.setAntiAlias(true);
            mOfflinePaint.setTextSize(textSize);
            mOfflinePaint.setARGB(255, 255, 0, 0);

            mLpPaint = new Paint();
            mLpPaint.setAntiAlias(true);
            mLpPaint.setTextSize(textSize);
            mLpPaint.setARGB(255, 0, 255, 0);

            mAscent = mOnlinePaint.ascent();
            float descent = mOnlinePaint.descent();
            mFH = (int)(descent - mAscent + .5f);

            final String maxWidthStr="cpuX xxxxxxxxxxxxxx 1700000";
            mMaxWidth = (int)mOnlinePaint.measureText(maxWidthStr);

            updateDisplay();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            mCurCPUHandler.removeMessages(1);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(resolveSize(mNeededWidth, widthMeasureSpec),
                    resolveSize(mNeededHeight, heightMeasureSpec));
        }

        private String getCPUInfoString(int i) {
            String freq=mCurrFreq[i];
            String gov=mCurrGov[i];
            return "cpu:"+i+" "+gov+":"+freq;
        }

        @Override
        public void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!mDataAvail) {
                return;
            }

            final int W = mNeededWidth;
            final int RIGHT = getWidth()-1;

            int x = RIGHT - mPaddingRight;
            int top = mPaddingTop + 2;
            int bottom = mPaddingTop + mFH - 2;

            int y = mPaddingTop - (int)mAscent;

            if (false) {
                canvas.drawText("Temp:"+mCPUTemp, RIGHT-mPaddingRight-mMaxWidth,
                    y-1, mOnlinePaint);
                y += mFH;
            }

            for(int i=0; i<mCurrFreq.length; i++){
                String s=getCPUInfoString(i);
                String freq=mCurrFreq[i];
                if(!freq.equals("0")){
                    if(i==0 && mLpMode){
                        canvas.drawText(s, RIGHT-mPaddingRight-mMaxWidth,
                            y-1, mLpPaint);
                    } else {
                        canvas.drawText(s, RIGHT-mPaddingRight-mMaxWidth,
                            y-1, mOnlinePaint);
                    }
                } else {
                    canvas.drawText(s, RIGHT-mPaddingRight-mMaxWidth,
                        y-1, mOfflinePaint);
                }
                y += mFH;
            }
        }

        void updateDisplay() {
            if (!mDataAvail) {
                return;
            }
            final int NW = mNumCpus;

            int neededWidth = mPaddingLeft + mPaddingRight + mMaxWidth;
            int neededHeight = mPaddingTop + mPaddingBottom + (mFH*(1+NW));
            if (neededWidth != mNeededWidth || neededHeight != mNeededHeight) {
                mNeededWidth = neededWidth;
                mNeededHeight = neededHeight;
                requestLayout();
            } else {
                invalidate();
            }
        }

        private String toMHz(String mhzString) {
            return new StringBuilder().append(Integer.valueOf(mhzString) / 1000).append(" MHz").toString();
        }

        public Handler getHandler(){
            return mCurCPUHandler;
        }
    }

    protected class CurCPUThread extends Thread {
        private boolean mInterrupt = false;
        private Handler mHandler;

        private static final String CURRENT_CPU = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq";
        private static final String CPU_ROOT = "/sys/devices/system/cpu/cpu";
        private static final String CPU_CUR_TAIL = "/cpufreq/scaling_cur_freq";
        private static final String CPU_LP_MODE = "/sys/kernel/debug/clock/cpu_lp/state";
        private static final String CPU_GOV_TAIL = "/cpufreq/scaling_governor";
        private static final String CPU_TEMP_HTC = "/sys/htc/cpu_temp";
        private static final String CPU_TEMP_OPPO = "/sys/class/thermal/thermal_zone0/temp";

        public CurCPUThread(Handler handler, int numCpus){
            mHandler=handler;
            mNumCpus = numCpus;
        }

        public void interrupt() {
            mInterrupt = true;
        }

        @Override
        public void run() {
            try {
                while (!mInterrupt) {
                    sleep(500);
                    StringBuffer sb=new StringBuffer();
                    String cpuTemp = CPUInfoService.readOneLine(CPU_TEMP_HTC);
                    if (cpuTemp == null){
                        cpuTemp = CPUInfoService.readOneLine(CPU_TEMP_OPPO);
                    }
                    sb.append(cpuTemp == null?"0":cpuTemp);
                    sb.append(";");
                    String lpMode = CPUInfoService.readOneLine(CPU_LP_MODE);
                    sb.append(lpMode == null?"0":lpMode);
                    sb.append(";");

                    for(int i=0; i<mNumCpus; i++){
                        final String freqFile=CPU_ROOT+i+CPU_CUR_TAIL;
                        String currFreq = CPUInfoService.readOneLine(freqFile);
                        final String govFile=CPU_ROOT+i+CPU_GOV_TAIL;
                        String currGov = CPUInfoService.readOneLine(govFile);

                        if(currFreq==null){
                            currFreq="0";
                            currGov="";
                        }

                        sb.append(currFreq+":"+currGov+"|");
                    }
                    sb.deleteCharAt(sb.length()-1);
                    mHandler.sendMessage(mHandler.obtainMessage(1, sb.toString()));
                }
            } catch (InterruptedException e) {
                return;
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mNumCpus = getNumOfCpus();
        mCurrFreq = new String[mNumCpus];
        mCurrGov = new String[mNumCpus];

        mView = new CPUView(this);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.RIGHT | Gravity.TOP;
        params.setTitle("CPU Info");

        mCurCPUThread = new CurCPUThread(mView.getHandler(), mNumCpus);
        mCurCPUThread.start();

        Log.d(TAG, "started CurCPUThread");

        WindowManager wm = (WindowManager)getSystemService(WINDOW_SERVICE);
        wm.addView(mView, params);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mCurCPUThread.isAlive()) {
            mCurCPUThread.interrupt();
            try {
                mCurCPUThread.join();
            } catch (InterruptedException e) {
            }
        }
        Log.d(TAG, "stopped CurCPUThread");
        ((WindowManager)getSystemService(WINDOW_SERVICE)).removeView(mView);
        mView = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static String readOneLine(String fname) {
        BufferedReader br;
        String line = null;
        try {
            br = new BufferedReader(new FileReader(fname), 512);
            try {
                line = br.readLine();
            } finally {
                br.close();
            }
        } catch (Exception e) {
            return null;
        }
        return line;
    }

    private static int getNumOfCpus() {
        int numOfCpu = 1;
        String numOfCpus = readOneLine(NUM_OF_CPUS_PATH);
        String[] cpuCount = numOfCpus.split("-");
        if (cpuCount.length > 1) {
            try {
                int cpuStart = Integer.parseInt(cpuCount[0]);
                int cpuEnd = Integer.parseInt(cpuCount[1]);

                numOfCpu = cpuEnd - cpuStart + 1;

                if (numOfCpu < 0)
                    numOfCpu = 1;
            } catch (NumberFormatException ex) {
                numOfCpu = 1;
            }
        }
        return numOfCpu;
    }
}
