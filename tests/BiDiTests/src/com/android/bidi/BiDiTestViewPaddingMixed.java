// Copyright 2011 Google Inc. All Rights Reserved.
package com.android.bidi;

import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

public class BiDiTestViewPaddingMixed extends Fragment {

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        return inflater.inflate(R.layout.view_padding_mixed, container, false);
    }
}
