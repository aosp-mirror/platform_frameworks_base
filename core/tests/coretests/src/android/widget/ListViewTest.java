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

import com.google.android.collect.Lists;

import junit.framework.Assert;

import android.content.Context;
import android.content.res.Resources;
import android.test.InstrumentationTestCase;
import android.test.mock.MockContext;
import android.test.suitebuilder.annotation.MediumTest;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import java.util.List;

public class ListViewTest extends InstrumentationTestCase {

    /**
     * If a view in a ListView requests a layout it should be remeasured.
     */
    @MediumTest
    public void testRequestLayout() throws Exception {
        MockContext context = new MockContext2();
        ListView listView = new ListView(context);
        List<String> items = Lists.newArrayList("hello");
        Adapter<String> adapter = new Adapter<String>(context, 0, items);
        listView.setAdapter(adapter);

        int measureSpec = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY);

        adapter.notifyDataSetChanged();
        listView.measure(measureSpec, measureSpec);
        listView.layout(0, 0, 100, 100);

        MockView childView = (MockView) listView.getChildAt(0);

        childView.requestLayout();
        childView.onMeasureCalled = false;
        listView.measure(measureSpec, measureSpec);
        listView.layout(0, 0, 100, 100);
        Assert.assertTrue(childView.onMeasureCalled);
    }

    /**
     * The list view should handle the disappearance of the only selected item, even when that item
     * was selected before its disappearance.
     *
     */
    @MediumTest
    public void testNoSelectableItems() throws Exception {
        MockContext context = new MockContext2();
        ListView listView = new ListView(context);
        // We use a header as the unselectable item to remain after the selectable one is removed.
        listView.addHeaderView(new View(context), null, false);
        List<String> items = Lists.newArrayList("hello");
        Adapter<String> adapter = new Adapter<String>(context, 0, items);
        listView.setAdapter(adapter);

        listView.setSelection(1);

        int measureSpec = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY);

        adapter.notifyDataSetChanged();
        listView.measure(measureSpec, measureSpec);
        listView.layout(0, 0, 100, 100);

        items.remove(0);

        adapter.notifyDataSetChanged();
        listView.measure(measureSpec, measureSpec);
        listView.layout(0, 0, 100, 100);
    }

    private class MockContext2 extends MockContext {

        @Override
        public Resources getResources() {
            return getInstrumentation().getTargetContext().getResources();
        }

        @Override
        public Resources.Theme getTheme() {
            return getInstrumentation().getTargetContext().getTheme();
        }

        @Override
        public Object getSystemService(String name) {
            if (Context.LAYOUT_INFLATER_SERVICE.equals(name)) {
                return getInstrumentation().getTargetContext().getSystemService(name);
            }
            return super.getSystemService(name);
        }
    }
    
    private class MockView extends View {

        public boolean onMeasureCalled = false;

        public MockView(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            onMeasureCalled = true;
        }
    }

    private class Adapter<T> extends ArrayAdapter<T> {

        public Adapter(Context context, int resource, List<T> objects) {
            super(context, resource, objects);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return new MockView(getContext());
        }
    }

}
