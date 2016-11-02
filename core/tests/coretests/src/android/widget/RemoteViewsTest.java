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

package android.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.View;

import com.android.frameworks.coretests.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Tests for RemoteViews.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class RemoteViewsTest {

    // This can point to any other package which exists on the device.
    private static final String OTHER_PACKAGE = "com.android.systemui";

    @Rule
    public final ExpectedException exception = ExpectedException.none();

    private Context mContext;
    private String mPackage;
    private LinearLayout mContainer;

    @Before
    public void setup() {
        mContext = InstrumentationRegistry.getContext();
        mPackage = mContext.getPackageName();
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

    @Test
    public void parcelSize_nestedViews() {
        RemoteViews original = new RemoteViews(mPackage, R.layout.remote_views_test);
        // We don't care about the actual layout id.
        RemoteViews child = new RemoteViews(mPackage, 33);
        int expectedSize = getParcelSize(original) + getParcelSize(child);
        original.addView(R.id.layout, child);

        // The application info will get written only once.
        assertTrue(getParcelSize(original) < expectedSize);
        assertEquals(getParcelSize(original), getParcelSize(original.clone()));

        original = new RemoteViews(mPackage, R.layout.remote_views_test);
        child = new RemoteViews(OTHER_PACKAGE, 33);
        expectedSize = getParcelSize(original) + getParcelSize(child);
        original.addView(R.id.layout, child);

        // Both the views will get written completely along with an additional view operation
        assertTrue(getParcelSize(original) > expectedSize);
        assertEquals(getParcelSize(original), getParcelSize(original.clone()));
    }

    @Test
    public void parcelSize_differentOrientation() {
        RemoteViews landscape = new RemoteViews(mPackage, R.layout.remote_views_test);
        RemoteViews portrait = new RemoteViews(mPackage, 33);

        // The application info will get written only once.
        RemoteViews views = new RemoteViews(landscape, portrait);
        assertTrue(getParcelSize(views) < (getParcelSize(landscape) + getParcelSize(portrait)));
        assertEquals(getParcelSize(views), getParcelSize(views.clone()));
    }

    private int getParcelSize(RemoteViews view) {
        Parcel parcel = Parcel.obtain();
        view.writeToParcel(parcel, 0);
        int size = parcel.dataSize();
        parcel.recycle();
        return size;
    }
}
