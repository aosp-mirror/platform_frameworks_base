package com.android.multidexlegacytestapp.test2;

import android.os.Bundle;
import android.support.multidex.MultiDex;
import android.support.test.runner.AndroidJUnitRunner;

public class MultiDexAndroidJUnitRunner extends AndroidJUnitRunner {

  @Override
  public void onCreate(Bundle arguments) {
      MultiDex.installInstrumentation(getContext(), getTargetContext());
      super.onCreate(arguments);
  }

}
