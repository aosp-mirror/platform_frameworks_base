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

package android.widget.scroll;

import com.android.frameworks.coretests.R;

import android.app.Activity;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Button;
import android.view.View;
import android.graphics.Rect;

public class RequestRectangleVisibleWithInternalScroll extends Activity {

    private int scrollYofBlob = 52;

    private TextView mTextBlob;
    private Button mScrollToBlob;


    public int getScrollYofBlob() {
        return scrollYofBlob;
    }


    public TextView getTextBlob() {
        return mTextBlob;
    }


    public Button getScrollToBlob() {
        return mScrollToBlob;
    }

    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        setContentView(R.layout.scroll_to_rect_with_internal_scroll);

        mTextBlob = (TextView) findViewById(R.id.blob);
        mTextBlob.scrollBy(0, scrollYofBlob);


        mScrollToBlob = (Button) findViewById(R.id.scrollToBlob);
        mScrollToBlob.setOnClickListener(new View.OnClickListener() {

            public void onClick(View v) {

                // the rect we want to make visible is offset to match
                // the internal scroll
                Rect rect = new Rect();
                rect.set(0, 0, 0, mTextBlob.getHeight());
                rect.offset(0, mTextBlob.getScrollY());
                mTextBlob.requestRectangleOnScreen(rect);
            }
        });
    }


}
