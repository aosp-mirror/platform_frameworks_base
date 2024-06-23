/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.util.SizeF;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;
import android.view.View;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.coretests.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

import java.util.Map;

/**
 * Tests for RemoteViews.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class RemoteViewsProtoTest {

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
    public void copy_canStillBeApplied() {
        RemoteViews original = new RemoteViews(mPackage, R.layout.remote_views_test);

        RemoteViews clone = recreateFromProto(original);

        clone.apply(mContext, mContainer);
    }

    @SuppressWarnings("ReturnValueIgnored")
    @Test
    public void clone_repeatedly() {
        RemoteViews original = new RemoteViews(mPackage, R.layout.remote_views_test);

        recreateFromProto(original);
        recreateFromProto(original);

        original.apply(mContext, mContainer);
    }

    @Test
    public void clone_chained() {
        RemoteViews original = new RemoteViews(mPackage, R.layout.remote_views_test);

        RemoteViews clone = recreateFromProto(recreateFromProto(original));


        clone.apply(mContext, mContainer);
    }

    @Test
    public void landscapePortraitViews_lightBackgroundLayoutFlag() {
        RemoteViews inner = new RemoteViews(mPackage, R.layout.remote_views_text);
        inner.setLightBackgroundLayoutId(R.layout.remote_views_light_background_text);

        RemoteViews parent = new RemoteViews(inner, inner);
        parent.addFlags(RemoteViews.FLAG_USE_LIGHT_BACKGROUND_LAYOUT);

        View view = recreateFromProto(parent).apply(mContext, mContainer);
        assertNull(view.findViewById(R.id.text));
        assertNotNull(view.findViewById(R.id.light_background_text));
    }

    @Test
    public void sizedViews_lightBackgroundLayoutFlag() {
        RemoteViews inner = new RemoteViews(mPackage, R.layout.remote_views_text);
        inner.setLightBackgroundLayoutId(R.layout.remote_views_light_background_text);

        RemoteViews parent = new RemoteViews(
                Map.of(new SizeF(0, 0), inner, new SizeF(100, 100), inner));
        parent.addFlags(RemoteViews.FLAG_USE_LIGHT_BACKGROUND_LAYOUT);

        View view = recreateFromProto(parent).apply(mContext, mContainer);
        assertNull(view.findViewById(R.id.text));
        assertNotNull(view.findViewById(R.id.light_background_text));
    }

    @Test
    public void nestedLandscapeViews() throws Exception {
        RemoteViews views = new RemoteViews(mPackage, R.layout.remote_views_test);
        for (int i = 0; i < 10; i++) {
            views = new RemoteViews(views, new RemoteViews(mPackage, R.layout.remote_views_test));
        }
        // writeTo/createFromProto works
        recreateFromProto(views);

        views = new RemoteViews(mPackage, R.layout.remote_views_test);
        for (int i = 0; i < 11; i++) {
            views = new RemoteViews(views, new RemoteViews(mPackage, R.layout.remote_views_test));
        }
        // writeTo/createFromProto fails
        exception.expect(IllegalArgumentException.class);
        recreateFromProtoNoRethrow(views);
    }

    private RemoteViews recreateFromProto(RemoteViews views) {
        try {
            return recreateFromProtoNoRethrow(views);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private RemoteViews recreateFromProtoNoRethrow(RemoteViews views) throws Exception {
        ProtoOutputStream out = new ProtoOutputStream();
        views.writePreviewToProto(mContext, out);
        ProtoInputStream in = new ProtoInputStream(out.getBytes());
        return RemoteViews.createPreviewFromProto(mContext, in);
    }
}
