/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.transitiontests;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;
import android.transition.Fade;
import android.transition.Scene;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.transition.AutoTransition;
import android.transition.ChangeBounds;
import android.transition.Transition;
import android.transition.TransitionListenerAdapter;
import android.transition.TransitionManager;
import android.transition.TransitionSet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class ListViewAddRemove extends Activity {

    final ArrayList<String> numList = new ArrayList<String>();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.list_view_add_remove);

        final LinearLayout container = findViewById(R.id.container);

        final ListView listview = findViewById(R.id.listview);
        for (int i = 0; i < 200; ++i) {
            numList.add(Integer.toString(i));
        }
        final StableArrayAdapter adapter = new StableArrayAdapter(this,
                android.R.layout.simple_list_item_1, numList);
        listview.setAdapter(adapter);

        final ViewTreeObserver observer = container.getViewTreeObserver();
        observer.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            public void onGlobalLayout() {
                System.out.println("-------------------------------------");
                System.out.println("onLayoutListener: listview view tops: ");
                for (int i = 0; i < listview.getChildCount(); ++i) {
                    TextView view = (TextView) listview.getChildAt(i);
                    System.out.println("    " + view.getText() + ": " + view.getTop());
                }
            }
        });

        final Scene mySceneChanger = new Scene(listview);

        mySceneChanger.setEnterAction(new Runnable() {
            @Override
            public void run() {
                numList.remove(mItemToDelete);
                adapter.notifyDataSetChanged();
            }
        });
        final Transition myTransition = new AutoTransition();
        final TransitionSet noFadeIn = new TransitionSet().
                setOrdering(TransitionSet.ORDERING_SEQUENTIAL);
        Fade fadeIn = new Fade(Fade.IN);
        fadeIn.setDuration(50);
        noFadeIn.addTransition(new Fade(Fade.OUT)).addTransition(new ChangeBounds()).addTransition(fadeIn);

        myTransition.addListener(new TransitionListenerAdapter() {
            @Override
            public void onTransitionStart(Transition transition) {
                System.out.println("---------ListView Tops: Before--------");
                for (int i = 0; i < listview.getChildCount(); ++i) {
                    TextView view = (TextView) listview.getChildAt(i);
                    int position = listview.getPositionForView(view);
                }
            }

            @Override
            public void onTransitionEnd(Transition transition) {
                System.out.println("---------ListView Tops: After--------");
                for (int i = 0; i < listview.getChildCount(); ++i) {
                    TextView view = (TextView) listview.getChildAt(i);
                    int position = listview.getPositionForView(view);
                    if (view.hasTransientState()) {
//                        view.setHasTransientState(false);
                    }
                }
                myTransition.removeListener(this);
            }
        });

        listview.setOnItemClickListener(new AdapterView.OnItemClickListener() {

            @Override
            public void onItemClick(AdapterView<?> parent, final View view, int position, long id) {
                System.out.println("---------ListView Tops: OnClick--------");
                String item = (String) parent.getItemAtPosition(position);
                for (int i = 0; i < listview.getChildCount(); ++i) {
                    TextView v = (TextView) listview.getChildAt(i);
                    if (!item.equals(v.getText())) {
//                        v.setHasTransientState(true);
                    }
                }
//                listview.setHasTransientState(true);
                mItemToDelete = item;
//                numList.remove(item);
                TransitionManager.go(mySceneChanger, noFadeIn);
//                view.postDelayed(new Runnable() {
//                    @Override
//                    public void run() {
//                        for (int i = 0; i < listview.getChildCount(); ++i) {
//                            TextView v = (TextView) listview.getChildAt(i);
//                            v.setHasTransientState(false);
//                        }
//                    }
//                }, 200);
            }

        });
    }

    String mItemToDelete = null;

    private class StableArrayAdapter extends ArrayAdapter<String> {

        HashMap<String, Integer> mIdMap = new HashMap<String, Integer>();

        public StableArrayAdapter(Context context, int textViewResourceId,
                List<String> objects) {
            super(context, textViewResourceId, objects);
            for (int i = 0; i < objects.size(); ++i) {
                mIdMap.put(objects.get(i), i);
            }
        }

        @Override
        public long getItemId(int position) {
            String item = getItem(position);
            return mIdMap.get(item);
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

    }
}
