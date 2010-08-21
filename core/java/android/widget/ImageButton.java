/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.RemoteViews.RemoteView;

import java.util.Map;

/**
 * <p>
 * Displays a button with an image (instead of text) that can be pressed 
 * or clicked by the user. By default, an ImageButton looks like a regular 
 * {@link android.widget.Button}, with the standard button background
 * that changes color during different button states. The image on the surface
 * of the button is defined either by the {@code android:src} attribute in the
 * {@code &lt;ImageButton&gt;} XML element or by the 
 * {@link #setImageResource(int)} method.</p>
 * 
 * <p>To remove the standard button background image, define your own 
 * background image or set the background color to be transparent.</p>
 * <p>To indicate the different button states (focused, selected, etc.), you can
 * define a different image for each state. E.g., a blue image by default, an
 * orange one for when focused, and a yellow one for when pressed. An easy way to
 * do this is with an XML drawable "selector." For example:</p>
 * <pre>
 * &lt;?xml version="1.0" encoding="utf-8"?&gt;
 * &lt;selector xmlns:android="http://schemas.android.com/apk/res/android"&gt;
 *     &lt;item android:state_pressed="true"
 *           android:drawable="@drawable/button_pressed" /&gt; &lt;!-- pressed --&gt;
 *     &lt;item android:state_focused="true"
 *           android:drawable="@drawable/button_focused" /&gt; &lt;!-- focused --&gt;
 *     &lt;item android:drawable="@drawable/button_normal" /&gt; &lt;!-- default --&gt;
 * &lt;/selector&gt;</pre>
 *
 * <p>Save the XML file in your project {@code res/drawable/} folder and then 
 * reference it as a drawable for the source of your ImageButton (in the 
 * {@code android:src} attribute). Android will automatically change the image 
 * based on the state of the button and the corresponding images
 * defined in the XML.</p>
 *
 * <p>The order of the {@code &lt;item>} elements is important because they are
 * evaluated in order. This is why the "normal" button image comes last, because
 * it will only be applied after {@code android:state_pressed} and {@code
 * android:state_focused} have both evaluated false.</p>
 *
 * <p>See the <a href="{@docRoot}resources/tutorials/views/hello-formstuff.html">Form Stuff
 * tutorial</a>.</p>
 *
 * <p><strong>XML attributes</strong></p>
 * <p>
 * See {@link android.R.styleable#ImageView Button Attributes},
 * {@link android.R.styleable#View View Attributes}
 * </p>
 */
@RemoteView
public class ImageButton extends ImageView {
    public ImageButton(Context context) {
        this(context, null);
    }

    public ImageButton(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.imageButtonStyle);
    }

    public ImageButton(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        setFocusable(true);
    }

    @Override
    protected boolean onSetAlpha(int alpha) {
        return false;
    }
}
