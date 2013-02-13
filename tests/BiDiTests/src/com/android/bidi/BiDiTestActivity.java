/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.bidi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.SimpleAdapter;

public class BiDiTestActivity extends Activity {

    private static final String KEY_CLASS = "class";
    private static final String KEY_TITLE = "title";
    private static final String KEY_FRAGMENT_ID = "id";
    
    private ListView mList;
    
    private AdapterView.OnItemClickListener mOnClickListener = 
            new AdapterView.OnItemClickListener() {
                public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                    onListItemClick((ListView)parent, v, position, id);
                }
    };

    private void onListItemClick(ListView lv, View v, int position, long id) {
        // Show the test
        Map<String, Object> map = (Map<String, Object>)lv.getItemAtPosition(position);
        int fragmentId = (Integer) map.get(KEY_FRAGMENT_ID);
        Fragment fragment = getFragmentManager().findFragmentById(fragmentId);
        if (fragment == null) {
            try {
                // Create an instance of the test
                Class<? extends Fragment> clazz = (Class<? extends Fragment>) map.get(KEY_CLASS);  
                fragment = clazz.newInstance();
                
                // Replace the old test fragment with the new one
                FragmentTransaction ft = getFragmentManager().beginTransaction();
                ft.replace(R.id.testframe, fragment);
                ft.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);
                ft.commit();
            } catch (InstantiationException e) {
            } catch (IllegalAccessException e) {
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.main);

        mList = (ListView) findViewById(R.id.testlist);
        mList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        mList.setFocusableInTouchMode(true);
        
        final SimpleAdapter adapter = new SimpleAdapter(this, getTests(),
                R.layout.custom_list_item, new String[]{"title"},
                new int[]{android.R.id.text1});
        mList.setAdapter(adapter);
        
        mList.setOnItemClickListener(mOnClickListener);
    }

    private void addItem(List<Map<String, Object>> data, String name,
            Class<? extends Fragment> clazz, int fragmentId) {
        Map<String, Object> temp = new HashMap<String, Object>();
        temp.put(KEY_TITLE, name);
        temp.put(KEY_CLASS, clazz);
        temp.put(KEY_FRAGMENT_ID, fragmentId);
        data.add(temp);
    }

    private List<Map<String, Object>> getTests() {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();

        addItem(result, "Basic", BiDiTestBasic.class, R.id.basic);

        addItem(result, "Canvas2", BiDiTestCanvas2.class, R.id.canvas2);

        addItem(result, "TextView LTR", BiDiTestTextViewLtr.class, R.id.textview_ltr);
        addItem(result, "TextView RTL", BiDiTestTextViewRtl.class, R.id.textview_rtl);
        addItem(result, "TextView LOC", BiDiTestTextViewLocale.class, R.id.textview_locale);

        addItem(result, "TextDirection LTR", BiDiTestTextViewDirectionLtr.class, R.id.textview_direction_ltr);
        addItem(result, "TextDirection RTL", BiDiTestTextViewDirectionRtl.class, R.id.textview_direction_rtl);

        addItem(result, "TextAlignment LTR", BiDiTestTextViewAlignmentLtr.class, R.id.textview_alignment_ltr);
        addItem(result, "TextAlignment RTL", BiDiTestTextViewAlignmentRtl.class, R.id.textview_alignment_rtl);

        addItem(result, "Linear LTR", BiDiTestLinearLayoutLtr.class, R.id.linear_layout_ltr);
        addItem(result, "Linear RTL", BiDiTestLinearLayoutRtl.class, R.id.linear_layout_rtl);
        addItem(result, "Linear LOC", BiDiTestLinearLayoutLocale.class, R.id.linear_layout_locale);

        addItem(result, "Grid LTR", BiDiTestGridLayoutLtr.class, R.id.grid_layout_ltr);
        addItem(result, "Grid RTL", BiDiTestGridLayoutRtl.class, R.id.grid_layout_rtl);
        addItem(result, "Grid LOC", BiDiTestGridLayoutLocale.class, R.id.grid_layout_locale);
        addItem(result, "Grid C-LTR", BiDiTestGridLayoutCodeLtr.class, R.id.grid_layout_code);
        addItem(result, "Grid C-RTL", BiDiTestGridLayoutCodeRtl.class, R.id.grid_layout_code);

        addItem(result, "Frame LTR", BiDiTestFrameLayoutLtr.class, R.id.frame_layout_ltr);
        addItem(result, "Frame RTL", BiDiTestFrameLayoutRtl.class, R.id.frame_layout_rtl);
        addItem(result, "Frame LOC", BiDiTestFrameLayoutLocale.class, R.id.frame_layout_locale);

        addItem(result, "Relative LTR", BiDiTestRelativeLayoutLtr.class, R.id.relative_layout_ltr);
        addItem(result, "Relative RTL", BiDiTestRelativeLayoutRtl.class, R.id.relative_layout_rtl);

        addItem(result, "Relative2 LTR", BiDiTestRelativeLayout2Ltr.class, R.id.relative_layout_2_ltr);
        addItem(result, "Relative2 RTL", BiDiTestRelativeLayout2Rtl.class, R.id.relative_layout_2_rtl);
        addItem(result, "Relative2 LOC", BiDiTestRelativeLayout2Locale.class, R.id.relative_layout_2_locale);

        addItem(result, "Table LTR", BiDiTestTableLayoutLtr.class, R.id.table_layout_ltr);
        addItem(result, "Table RTL", BiDiTestTableLayoutRtl.class, R.id.table_layout_rtl);
        addItem(result, "Table LOC", BiDiTestTableLayoutLocale.class, R.id.table_layout_locale);

        addItem(result, "Padding", BiDiTestViewPadding.class, R.id.view_padding);
        addItem(result, "Padding MIXED", BiDiTestViewPaddingMixed.class, R.id.view_padding_mixed);

        addItem(result, "Margin MIXED", BiDiTestViewGroupMarginMixed.class, R.id.view_group_margin_mixed);

        addItem(result, "TextView Drawables LTR", BiDiTestTextViewDrawablesLtr.class, R.id.textview_drawables_ltr);
        addItem(result, "TextView Drawables RTL", BiDiTestTextViewDrawablesRtl.class, R.id.textview_drawables_rtl);

        addItem(result, "Gallery LTR", BiDiTestGalleryLtr.class, R.id.gallery_ltr);
        addItem(result, "Gallery RTL", BiDiTestGalleryRtl.class, R.id.gallery_rtl);

        return result;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
        return true;
    }
}
