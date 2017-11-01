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

package android.view;

import com.android.frameworks.coretests.R;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AbsoluteLayout;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

/**
 * Views should obey their background {@link Drawable}'s minimum size
 * requirements ({@link Drawable#getMinimumHeight()} and
 * {@link Drawable#getMinimumWidth()}) when possible.
 * <p>
 * This Activity exercises a few Views with background {@link Drawable}s. 
 */
public class DrawableBgMinSize extends Activity implements OnClickListener {
    private boolean mUsingBigBg = false;
    private Drawable mBackgroundDrawable;
    private Drawable mBigBackgroundDrawable;
    private Button mChangeBackgroundsButton;
    
    private TextView mTextView;
    private LinearLayout mLinearLayout;
    private RelativeLayout mRelativeLayout;
    private FrameLayout mFrameLayout;
    private AbsoluteLayout mAbsoluteLayout;
    
    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        
        setContentView(R.layout.drawable_background_minimum_size);
        
        mBackgroundDrawable = getResources().getDrawable(R.drawable.drawable_background);
        mBigBackgroundDrawable = getResources().getDrawable(R.drawable.big_drawable_background);
 
        mChangeBackgroundsButton = findViewById(R.id.change_backgrounds);
        mChangeBackgroundsButton.setOnClickListener(this);
        
        mTextView = findViewById(R.id.text_view);
        mLinearLayout = findViewById(R.id.linear_layout);
        mRelativeLayout = findViewById(R.id.relative_layout);
        mFrameLayout = findViewById(R.id.frame_layout);
        mAbsoluteLayout = findViewById(R.id.absolute_layout);

        changeBackgrounds(mBackgroundDrawable);
    }

    private void changeBackgrounds(Drawable newBg) {
        mTextView.setBackgroundDrawable(newBg);
        mLinearLayout.setBackgroundDrawable(newBg);
        mRelativeLayout.setBackgroundDrawable(newBg);
        mFrameLayout.setBackgroundDrawable(newBg);
        mAbsoluteLayout.setBackgroundDrawable(newBg);
    }
    
    public void onClick(View v) {
        if (mUsingBigBg) {
            changeBackgrounds(mBackgroundDrawable);
        } else {
            changeBackgrounds(mBigBackgroundDrawable);
        }
        
        mUsingBigBg = !mUsingBigBg;
    }
    
}
