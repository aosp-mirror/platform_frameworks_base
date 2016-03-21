/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package android.view;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import com.android.frameworks.coretests.R;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * Tests for RemoteViews.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class RemoteViewsTest {

    @Rule
    public final ExpectedException exception = ExpectedException.none();

    private Context mContext;
    private String mPackage;
    private LinearLayout mContainer;

    @Before
    public void setup() {
        mContext = InstrumentationRegistry.getContext();
        mPackage = mPackage;
        mContainer = new LinearLayout(mContext);
    }

    @Test
    public void clone_doesNotCopyBitmap() {
        RemoteViews original = new RemoteViews(mPackage, R.layout.remote_views_test);
        Bitmap bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);

        original.setImageViewBitmap(R.id.image, bitmap);
        RemoteViews clone = original.clone();
        View inflated = clone.apply(mContext, mContainer);

        Drawable drawable = ((ImageView) inflated.findViewById(R.id.image)).getDrawable();
        assertSame(bitmap, ((BitmapDrawable)drawable).getBitmap());
    }

    @Test
    public void clone_originalCanStillBeApplied() {
        RemoteViews original = new RemoteViews(mPackage, R.layout.remote_views_test);

        RemoteViews clone = original.clone();

        clone.apply(mContext, mContainer);
    }

    @Test
    public void clone_clones() {
        RemoteViews original = new RemoteViews(mPackage, R.layout.remote_views_test);

        RemoteViews clone = original.clone();
        original.setTextViewText(R.id.text, "test");
        View inflated = clone.apply(mContext, mContainer);

        TextView textView = (TextView) inflated.findViewById(R.id.text);
        assertEquals("", textView.getText());
    }

    @Test
    public void clone_child_fails() {
        RemoteViews original = new RemoteViews(mPackage, R.layout.remote_views_test);
        RemoteViews child = new RemoteViews(mPackage, R.layout.remote_views_test);

        original.addView(R.id.layout, child);

        exception.expect(IllegalStateException.class);
        RemoteViews clone = child.clone();
    }

    @Test
    public void clone_repeatedly() {
        RemoteViews original = new RemoteViews(mPackage, R.layout.remote_views_test);

        original.clone();
        original.clone();

        original.apply(mContext, mContainer);
    }

    @Test
    public void clone_chained() {
        RemoteViews original = new RemoteViews(mPackage, R.layout.remote_views_test);

        RemoteViews clone = original.clone().clone();

        clone.apply(mContext, mContainer);
    }

}
