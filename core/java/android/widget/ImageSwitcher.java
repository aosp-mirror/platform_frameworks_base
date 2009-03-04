/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.widget;

import java.util.Map;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.AttributeSet;


public class ImageSwitcher extends ViewSwitcher
{
    public ImageSwitcher(Context context)
    {
        super(context);
    }
    
    public ImageSwitcher(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setImageResource(int resid)
    {
        ImageView image = (ImageView)this.getNextView();
        image.setImageResource(resid);
        showNext();
    }

    public void setImageURI(Uri uri)
    {
        ImageView image = (ImageView)this.getNextView();
        image.setImageURI(uri);
        showNext();
    }

    public void setImageDrawable(Drawable drawable)
    {
        ImageView image = (ImageView)this.getNextView();
        image.setImageDrawable(drawable);
        showNext();
    }
}

