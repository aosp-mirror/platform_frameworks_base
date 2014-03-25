package com.google.android.test.shared_library;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

public class AddressView extends LinearLayout {
    private TextView mNameView;
    private TextView mStreetView;
    private TextView mCityStateZipView;
    private TextView mCountryView;

    public AddressView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);

        View view = LayoutInflater.from(context).inflate(R.layout.address, this);
        mNameView = (TextView) view.findViewById(R.id.name);
        mStreetView = (TextView) view.findViewById(R.id.street);
        mCityStateZipView = (TextView) view.findViewById(R.id.cityStateZip);
        mCountryView = (TextView) view.findViewById(R.id.country);

        TypedArray a = context.getTheme().obtainStyledAttributes(
                attrs,
                R.styleable.AddressView,
                0, 0);
        try {
            mNameView.setText(a.getString(R.styleable.AddressView_name));
            int streetNumber = a.getInteger(R.styleable.AddressView_streetNumber, -1);
            mStreetView.setText((streetNumber <= 0 ? "" : Integer.toString(streetNumber)) +
                    " " + a.getString(R.styleable.AddressView_streetName));
            mCityStateZipView.setText(a.getString(R.styleable.AddressView_city) + ", " +
                    a.getString(R.styleable.AddressView_state) + " " +
                    a.getString(R.styleable.AddressView_zip));
            mCountryView.setText(a.getString(R.styleable.AddressView_country));
        } finally {
            a.recycle();
        }
    }
}
