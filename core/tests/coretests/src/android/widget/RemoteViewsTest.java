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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.appwidget.AppWidgetHostView;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Parcel;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.coretests.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

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

    @Test
    public void asyncApply_fail() throws Exception {
        RemoteViews views = new RemoteViews(mPackage, R.layout.remote_view_test_bad_1);
        ViewAppliedListener listener = new ViewAppliedListener();
        views.applyAsync(mContext, mContainer, AsyncTask.THREAD_POOL_EXECUTOR, listener);

        exception.expect(Exception.class);
        listener.waitAndGetView();
    }

    @Test
    public void asyncApply() throws Exception {
        RemoteViews views = new RemoteViews(mPackage, R.layout.remote_views_test);
        views.setTextViewText(R.id.text, "Dummy");

        View syncView = views.apply(mContext, mContainer);

        ViewAppliedListener listener = new ViewAppliedListener();
        views.applyAsync(mContext, mContainer, AsyncTask.THREAD_POOL_EXECUTOR, listener);
        View asyncView = listener.waitAndGetView();

        verifyViewTree(syncView, asyncView, "Dummy");
    }

    @Test
    public void asyncApply_viewStub() throws Exception {
        RemoteViews views = new RemoteViews(mPackage, R.layout.remote_views_viewstub);
        views.setInt(R.id.viewStub, "setLayoutResource", R.layout.remote_views_text);
        // This will cause the view to be inflated
        views.setViewVisibility(R.id.viewStub, View.INVISIBLE);
        views.setTextViewText(R.id.stub_inflated, "Dummy");

        View syncView = views.apply(mContext, mContainer);

        ViewAppliedListener listener = new ViewAppliedListener();
        views.applyAsync(mContext, mContainer, AsyncTask.THREAD_POOL_EXECUTOR, listener);
        View asyncView = listener.waitAndGetView();

        verifyViewTree(syncView, asyncView, "Dummy");
    }

    @Test
    public void asyncApply_nestedViews() throws Exception {
        RemoteViews views = new RemoteViews(mPackage, R.layout.remote_view_host);
        views.removeAllViews(R.id.container);
        views.addView(R.id.container, createViewChained(1, "row1-c1", "row1-c2", "row1-c3"));
        views.addView(R.id.container, createViewChained(5, "row2-c1", "row2-c2"));
        views.addView(R.id.container, createViewChained(2, "row3-c1", "row3-c2"));

        View syncView = views.apply(mContext, mContainer);

        ViewAppliedListener listener = new ViewAppliedListener();
        views.applyAsync(mContext, mContainer, AsyncTask.THREAD_POOL_EXECUTOR, listener);
        View asyncView = listener.waitAndGetView();

        verifyViewTree(syncView, asyncView,
                "row1-c1", "row1-c2", "row1-c3", "row2-c1", "row2-c2", "row3-c1", "row3-c2");
    }

    @Test
    public void asyncApply_viewstub_nestedViews() throws Exception {
        RemoteViews viewstub = new RemoteViews(mPackage, R.layout.remote_views_viewstub);
        viewstub.setInt(R.id.viewStub, "setLayoutResource", R.layout.remote_view_host);
        // This will cause the view to be inflated
        viewstub.setViewVisibility(R.id.viewStub, View.INVISIBLE);
        viewstub.addView(R.id.stub_inflated, createViewChained(1, "row1-c1", "row1-c2", "row1-c3"));

        RemoteViews views = new RemoteViews(mPackage, R.layout.remote_view_host);
        views.removeAllViews(R.id.container);
        views.addView(R.id.container, viewstub);
        views.addView(R.id.container, createViewChained(5, "row2-c1", "row2-c2"));

        View syncView = views.apply(mContext, mContainer);

        ViewAppliedListener listener = new ViewAppliedListener();
        views.applyAsync(mContext, mContainer, AsyncTask.THREAD_POOL_EXECUTOR, listener);
        View asyncView = listener.waitAndGetView();

        verifyViewTree(syncView, asyncView, "row1-c1", "row1-c2", "row1-c3", "row2-c1", "row2-c2");
    }

    private RemoteViews createViewChained(int depth, String... texts) {
        RemoteViews result = new RemoteViews(mPackage, R.layout.remote_view_host);

        // Create depth
        RemoteViews parent = result;
        while(depth > 0) {
            depth--;
            RemoteViews child = new RemoteViews(mPackage, R.layout.remote_view_host);
            parent.addView(R.id.container, child);
            parent = child;
        }

        // Add texts
        for (String text : texts) {
            RemoteViews child = new RemoteViews(mPackage, R.layout.remote_views_text);
            child.setTextViewText(R.id.text, text);
            parent.addView(R.id.container, child);
        }
        return result;
    }

    private void verifyViewTree(View v1, View v2, String... texts) {
        ArrayList<String> expectedTexts = new ArrayList<>(Arrays.asList(texts));
        verifyViewTreeRecur(v1, v2, expectedTexts);
        // Verify that all expected texts were found
        assertEquals(0, expectedTexts.size());
    }

    private void verifyViewTreeRecur(View v1, View v2, ArrayList<String> expectedTexts) {
        assertEquals(v1.getClass(), v2.getClass());

        if (v1 instanceof TextView) {
            String text = ((TextView) v1).getText().toString();
            assertEquals(text, ((TextView) v2).getText().toString());
            // Verify that the text was one of the expected texts and remove it from the list
            assertTrue(expectedTexts.remove(text));
        } else if (v1 instanceof ViewGroup) {
            ViewGroup vg1 = (ViewGroup) v1;
            ViewGroup vg2 = (ViewGroup) v2;
            assertEquals(vg1.getChildCount(), vg2.getChildCount());
            for (int i = vg1.getChildCount() - 1; i >= 0; i--) {
                verifyViewTreeRecur(vg1.getChildAt(i), vg2.getChildAt(i), expectedTexts);
            }
        }
    }

    private class ViewAppliedListener implements RemoteViews.OnViewAppliedListener {

        private final CountDownLatch mLatch = new CountDownLatch(1);
        private View mView;
        private Exception mError;

        @Override
        public void onViewApplied(View v) {
            mView = v;
            mLatch.countDown();
        }

        @Override
        public void onError(Exception e) {
            mError = e;
            mLatch.countDown();
        }

        public View waitAndGetView() throws Exception {
            mLatch.await();

            if (mError != null) {
                throw new Exception(mError);
            }
            return mView;
        }
    }

    @Test
    public void nestedAddViews() {
        RemoteViews views = new RemoteViews(mPackage, R.layout.remote_views_test);
        for (int i = 0; i < 10; i++) {
            RemoteViews parent = new RemoteViews(mPackage, R.layout.remote_views_test);
            parent.addView(R.id.layout, views);
            views = parent;
        }
        // Both clone and parcel/unparcel work,
        views.clone();
        parcelAndRecreate(views);

        views = new RemoteViews(mPackage, R.layout.remote_views_test);
        for (int i = 0; i < 11; i++) {
            RemoteViews parent = new RemoteViews(mPackage, R.layout.remote_views_test);
            parent.addView(R.id.layout, views);
            views = parent;
        }
        // Clone works but parcel/unparcel fails
        views.clone();
        exception.expect(IllegalArgumentException.class);
        parcelAndRecreate(views);
    }

    @Test
    public void nestedLandscapeViews() {
        RemoteViews views = new RemoteViews(mPackage, R.layout.remote_views_test);
        for (int i = 0; i < 10; i++) {
            views = new RemoteViews(views,
                    new RemoteViews(mPackage, R.layout.remote_views_test));
        }
        // Both clone and parcel/unparcel work,
        views.clone();
        parcelAndRecreate(views);

        views = new RemoteViews(mPackage, R.layout.remote_views_test);
        for (int i = 0; i < 11; i++) {
            views = new RemoteViews(views,
                    new RemoteViews(mPackage, R.layout.remote_views_test));
        }
        // Clone works but parcel/unparcel fails
        views.clone();
        exception.expect(IllegalArgumentException.class);
        parcelAndRecreate(views);
    }

    private RemoteViews parcelAndRecreate(RemoteViews views) {
        return parcelAndRecreateWithPendingIntentCookie(views, null);
    }

    private RemoteViews parcelAndRecreateWithPendingIntentCookie(RemoteViews views, Object cookie) {
        Parcel p = Parcel.obtain();
        try {
            views.writeToParcel(p, 0);
            p.setDataPosition(0);

            if (cookie != null) {
                p.setClassCookie(PendingIntent.class, cookie);
            }

            return RemoteViews.CREATOR.createFromParcel(p);
        } finally {
            p.recycle();
        }
    }

    @Test
    public void copyWithBinders() throws Exception {
        RemoteViews views = new RemoteViews(mPackage, R.layout.remote_views_test);
        for (int i = 1; i < 10; i++) {
            PendingIntent pi = PendingIntent.getBroadcast(mContext, 0,
                    new Intent("android.widget.RemoteViewsTest_" + i), PendingIntent.FLAG_ONE_SHOT);
            views.setOnClickPendingIntent(i, pi);
        }
        try {
            new RemoteViews(views);
        } catch (Throwable t) {
            throw new Exception(t);
        }
    }

    @Test
    public void copy_keepsPendingIntentWhitelistToken() throws Exception {
        Binder whitelistToken = new Binder();

        RemoteViews views = new RemoteViews(mPackage, R.layout.remote_views_test);
        PendingIntent pi = PendingIntent.getBroadcast(mContext, 0,
                new Intent("test"), PendingIntent.FLAG_ONE_SHOT);
        views.setOnClickPendingIntent(1, pi);
        RemoteViews withCookie = parcelAndRecreateWithPendingIntentCookie(views, whitelistToken);

        RemoteViews cloned = new RemoteViews(withCookie);

        PendingIntent found = extractAnyPendingIntent(cloned);
        assertEquals(whitelistToken, found.getWhitelistToken());
    }

    private PendingIntent extractAnyPendingIntent(RemoteViews cloned) {
        PendingIntent[] found = new PendingIntent[1];
        Parcel p = Parcel.obtain();
        try {
            PendingIntent.setOnMarshaledListener((intent, parcel, flags) -> {
                if (parcel == p) {
                    found[0] = intent;
                }
            });
            cloned.writeToParcel(p, 0);
        } finally {
            p.recycle();
            PendingIntent.setOnMarshaledListener(null);
        }
        return found[0];
    }

    @Test
    public void sharedElement_pendingIntent_notifyParent() throws Exception {
        RemoteViews views = new RemoteViews(mPackage, R.layout.remote_views_test);
        PendingIntent pi = PendingIntent.getBroadcast(mContext, 0,
                new Intent("android.widget.RemoteViewsTest_shared_element"),
                PendingIntent.FLAG_ONE_SHOT);
        views.setOnClickResponse(R.id.image, RemoteViews.RemoteResponse.fromPendingIntent(pi)
                .addSharedElement(0, "e0")
                .addSharedElement(1, "e1")
                .addSharedElement(2, "e2"));

        WidgetContainer container = new WidgetContainer(mContext);
        container.addView(new RemoteViews(views).apply(mContext, container));
        container.findViewById(R.id.image).performClick();

        assertArrayEquals(container.mSharedViewIds, new int[] {0, 1, 2});
        assertArrayEquals(container.mSharedViewNames, new String[] {"e0", "e1", "e2"});
    }

    @Test
    public void setIntTag() {
        RemoteViews views = new RemoteViews(mPackage, R.layout.remote_views_test);
        int index = 10;
        views.setIntTag(
                R.id.layout, com.android.internal.R.id.notification_action_index_tag, index);

        RemoteViews recovered = parcelAndRecreate(views);
        RemoteViews cloned = new RemoteViews(recovered);
        View inflated = cloned.apply(mContext, mContainer);

        assertEquals(
                index, inflated.getTag(com.android.internal.R.id.notification_action_index_tag));
    }

    private class WidgetContainer extends AppWidgetHostView {
        int[] mSharedViewIds;
        String[] mSharedViewNames;

        WidgetContainer(Context context) {
            super(context);
        }

        @Override
        public ActivityOptions createSharedElementActivityOptions(
                int[] sharedViewIds, String[] sharedViewNames, Intent fillInIntent) {
            mSharedViewIds = sharedViewIds;
            mSharedViewNames = sharedViewNames;
            return null;
        }
    }

    @Test
    public void visitUris() {
        RemoteViews views = new RemoteViews(mPackage, R.layout.remote_views_test);

        final Uri imageUri = Uri.parse("content://media/image");
        final Icon icon1 = Icon.createWithContentUri("content://media/icon1");
        final Icon icon2 = Icon.createWithContentUri("content://media/icon2");
        final Icon icon3 = Icon.createWithContentUri("content://media/icon3");
        final Icon icon4 = Icon.createWithContentUri("content://media/icon4");
        views.setImageViewUri(R.id.image, imageUri);
        views.setTextViewCompoundDrawables(R.id.text, icon1, icon2, icon3, icon4);

        Consumer<Uri> visitor = (Consumer<Uri>) spy(Consumer.class);
        views.visitUris(visitor);
        verify(visitor, times(1)).accept(eq(imageUri));
        verify(visitor, times(1)).accept(eq(icon1.getUri()));
        verify(visitor, times(1)).accept(eq(icon2.getUri()));
        verify(visitor, times(1)).accept(eq(icon3.getUri()));
        verify(visitor, times(1)).accept(eq(icon4.getUri()));
    }

    @Test
    public void visitUris_separateOrientation() {
        final RemoteViews landscape = new RemoteViews(mPackage, R.layout.remote_views_test);
        final Uri imageUriL = Uri.parse("content://landscape/image");
        final Icon icon1L = Icon.createWithContentUri("content://landscape/icon1");
        final Icon icon2L = Icon.createWithContentUri("content://landscape/icon2");
        final Icon icon3L = Icon.createWithContentUri("content://landscape/icon3");
        final Icon icon4L = Icon.createWithContentUri("content://landscape/icon4");
        landscape.setImageViewUri(R.id.image, imageUriL);
        landscape.setTextViewCompoundDrawables(R.id.text, icon1L, icon2L, icon3L, icon4L);

        final RemoteViews portrait = new RemoteViews(mPackage, 33);
        final Uri imageUriP = Uri.parse("content://portrait/image");
        final Icon icon1P = Icon.createWithContentUri("content://portrait/icon1");
        final Icon icon2P = Icon.createWithContentUri("content://portrait/icon2");
        final Icon icon3P = Icon.createWithContentUri("content://portrait/icon3");
        final Icon icon4P = Icon.createWithContentUri("content://portrait/icon4");
        portrait.setImageViewUri(R.id.image, imageUriP);
        portrait.setTextViewCompoundDrawables(R.id.text, icon1P, icon2P, icon3P, icon4P);

        RemoteViews views = new RemoteViews(landscape, portrait);

        Consumer<Uri> visitor = (Consumer<Uri>) spy(Consumer.class);
        views.visitUris(visitor);
        verify(visitor, times(1)).accept(eq(imageUriL));
        verify(visitor, times(1)).accept(eq(icon1L.getUri()));
        verify(visitor, times(1)).accept(eq(icon2L.getUri()));
        verify(visitor, times(1)).accept(eq(icon3L.getUri()));
        verify(visitor, times(1)).accept(eq(icon4L.getUri()));
        verify(visitor, times(1)).accept(eq(imageUriP));
        verify(visitor, times(1)).accept(eq(icon1P.getUri()));
        verify(visitor, times(1)).accept(eq(icon2P.getUri()));
        verify(visitor, times(1)).accept(eq(icon3P.getUri()));
        verify(visitor, times(1)).accept(eq(icon4P.getUri()));
    }
}
