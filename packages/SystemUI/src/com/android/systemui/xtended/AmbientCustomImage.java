/*
* Copyright (C) 2014 The Android Open Source Project
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program.  If not, see <http://www.gnu.org/licenses/>.
*
*/

package com.android.systemui.xtended;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.graphics.Color;
import android.graphics.PorterDuff.Mode;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Calendar;

import com.android.internal.util.xtended.ImageHelper;
import com.android.systemui.R;

public class AmbientCustomImage extends FrameLayout {

   public static final String TAG = "AmbientCustomImage";
   private static final boolean DEBUG = true;
   private static final String AMBIENT_IMAGE_FILE_NAME = "custom_file_ambient_image";

   private Drawable mImage;
   private ImageView mCustomImage;

   public AmbientCustomImage(Context context) {
       this(context, null);
       mCustomImage = (ImageView) findViewById(R.id.custom_image);
   }

   public AmbientCustomImage(Context context, AttributeSet attrs) {
       this(context, attrs, 0);
   }

   public AmbientCustomImage(Context context, AttributeSet attrs, int defStyleAttr) {
       this(context, attrs, defStyleAttr, 0);
   }

   public AmbientCustomImage(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
       super(context, attrs, defStyleAttr, defStyleRes);
       if (DEBUG) Log.d(TAG, "new");
   }

   public void update() {
        String imageUri = Settings.System.getStringForUser(mContext.getContentResolver(),
              Settings.System.AMBIENT_CUSTOM_IMAGE,
              UserHandle.USER_CURRENT);
        if (imageUri != null) {
              saveAmbientImage(Uri.parse(imageUri));
        }
        loadAmbientImage();
        setCustomImage(mImage);
   }

   private void saveAmbientImage(Uri imageUri) {
       if (DEBUG) Log.i(TAG, "Save ambient image " + " " + imageUri);
       try {
           final InputStream imageStream = mContext.getContentResolver().openInputStream(imageUri);
           File file = new File(mContext.getFilesDir(), AMBIENT_IMAGE_FILE_NAME);
           if (file.exists()) {
               file.delete();
           }
           FileOutputStream output = new FileOutputStream(file);
           byte[] buffer = new byte[8 * 1024];
           int read;

           while ((read = imageStream.read(buffer)) != -1) {
               output.write(buffer, 0, read);
           }
           output.flush();
           if (DEBUG) Log.i(TAG, "Saved ambient image " + " " + file.getAbsolutePath());
       } catch (IOException e) {
           Log.e(TAG, "Save ambient image failed " + " " + imageUri);
       }
   }

   private void loadAmbientImage() {
       mImage = null;
       File file = new File(mContext.getFilesDir(), AMBIENT_IMAGE_FILE_NAME);
       if (file.exists()) {
           if (DEBUG) Log.i(TAG, "Load ambient image");
           final Bitmap image = BitmapFactory.decodeFile(file.getAbsolutePath());
           mImage = new BitmapDrawable(mContext.getResources(), ImageHelper.resizeMaxDeviceSize(mContext, image));
       }
   }

   public Drawable getCurrent() {
       return mImage;
   }

    private void setCustomImage(final Drawable dw) {
        ImageView customImage = (ImageView) findViewById(R.id.custom_image);
        if (customImage.getDrawable() != null) {
            Drawable[] arrayDrawable = new Drawable[2];
            arrayDrawable[0] = customImage.getDrawable();
            arrayDrawable[1] = dw;

            TransitionDrawable transitionDrawable = new TransitionDrawable(arrayDrawable);
            transitionDrawable.setCrossFadeEnabled(true);
            customImage.setImageDrawable(transitionDrawable);
            transitionDrawable.startTransition(1000);
        } else {
            customImage.setImageDrawable(dw);
        }
    }
}
