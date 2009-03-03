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

package com.android.mediaframeworktest.performance;

import com.android.mediaframeworktest.MediaFrameworkTest;
import com.android.mediaframeworktest.MediaNames;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.media.MediaPlayer;
import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.Suppress;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import android.media.MediaMetadataRetriever;

/**
 * Junit / Instrumentation test case for the media player api
 
 */  
public class MediaPlayerPerformance extends ActivityInstrumentationTestCase<MediaFrameworkTest> {    
  
   
   private boolean mIsPlaying = true;
   private String TAG = "MediaPlayerApiTest";
   Context mContext;
   private SQLiteDatabase mDB;
   
   
   public MediaPlayerPerformance() {
     super("com.android.mediaframeworktest", MediaFrameworkTest.class);
   }

    protected void setUp() throws Exception {
      
      super.setUp();
    }
       
    public void createDB(){
      mDB = SQLiteDatabase.openOrCreateDatabase("/sdcard/perf.db",null);
      mDB.execSQL("CREATE TABLE perfdata (_id INTEGER PRIMARY KEY,"
          + "file TEXT," + "setdatatime LONG," +"preparetime LONG," +"playtime LONG" + ");");
    }
    
    public void audioPlaybackStartupTime(String[] testFile){
      long t1 = 0;
      long t2 = 0;
      long t3 = 0;
      long t4 =0;
      
      long setDataSourceDuration = 0;
      long prepareDuration = 0;
      long startDuration=0;
      
      long totalSetDataTime=0;
      long totalPrepareTime=0;
      long totalStartDuration=0;
      
      int numberOfFiles = testFile.length;
      Log.v(TAG, "File lenght " + numberOfFiles);
      for (int k=0; k<numberOfFiles; k++){
        MediaPlayer mp = new MediaPlayer();
        try{
          t1 = SystemClock.uptimeMillis();
          FileInputStream  fis = new FileInputStream(testFile[k]);
          FileDescriptor fd = fis.getFD();
          mp.setDataSource(fd);
          fis.close();
          t2 = SystemClock.uptimeMillis();
          mp.prepare();
          t3 = SystemClock.uptimeMillis();
          mp.start();
          t4 = SystemClock.uptimeMillis();
          Thread.sleep(10000);
          mp.pause();
        }catch (Exception e){}
        setDataSourceDuration = t2 -t1;
        prepareDuration = t3 - t2;
        startDuration = t4 - t3;
        totalSetDataTime = totalSetDataTime + setDataSourceDuration;
        totalPrepareTime = totalPrepareTime + prepareDuration;
        totalStartDuration = totalStartDuration + startDuration;
        mDB.execSQL("INSERT INTO perfdata (file, setdatatime, preparetime, playtime) VALUES (" + '"' + testFile[k] + '"' +','
          +setDataSourceDuration+ ',' + prepareDuration + ',' + startDuration +");");
        Log.v(TAG,"File name " + testFile[k]);
        mp.stop();
        mp.release();   
      }
      Log.v (TAG, "setDataSource average " + totalSetDataTime/numberOfFiles);
      Log.v (TAG, "prepare average " + totalPrepareTime/numberOfFiles);
      Log.v (TAG, "start average " + totalStartDuration/numberOfFiles);
      
    }
    
    //Test cases for GetCurrentPosition
    @LargeTest
    public void testStartUpTime() throws Exception {
      createDB();
      audioPlaybackStartupTime(MediaNames.MP3FILES);    
      audioPlaybackStartupTime(MediaNames.AACFILES);   
      
    }
   
    public void wmametadatautility(String[] testFile){
      long t1 = 0;
      long t2 = 0;
      long sum = 0;
      long duration = 0;
      MediaMetadataRetriever retriever = new MediaMetadataRetriever();
      String value;
      for(int i = 0, n = testFile.length; i < n; ++i) {
          try {
              t1 = SystemClock.uptimeMillis();
              retriever.setDataSource(testFile[i]);
              value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
              value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
              value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER);
              value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE);
              value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
              value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR);
              value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER);
              t2 = SystemClock.uptimeMillis();
              duration = t2 - t1;
              Log.v(TAG, "Time taken = " + duration);
              sum=sum+duration;           
          }
          catch (Exception e){Log.v(TAG, e.getMessage());}
          
      }      
      Log.v(TAG, "Average duration = " + sum/testFile.length); 
    }
    
    @Suppress
    public void testWmaParseTime() throws Exception {
     // createDB();
      wmametadatautility(MediaNames.WMASUPPORTED);
    }
   
    
}

