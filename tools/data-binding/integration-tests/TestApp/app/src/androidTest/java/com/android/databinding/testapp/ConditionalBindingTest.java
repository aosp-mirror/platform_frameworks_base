package com.android.databinding.testapp;

import com.android.databinding.testapp.generated.ConditionalBindingBinding;
import com.android.databinding.testapp.vo.NotBindableVo;

import android.test.UiThreadTest;

public class ConditionalBindingTest extends BaseDataBinderTest<ConditionalBindingBinding>{

    public ConditionalBindingTest() {
        super(ConditionalBindingBinding.class);
    }

    @UiThreadTest
    public void test1() {
        testCorrectness(true, true);
    }

    private void testCorrectness(boolean cond1, boolean cond2) {
        NotBindableVo o1 = new NotBindableVo("a");
        NotBindableVo o2 = new NotBindableVo("b");
        NotBindableVo o3 = new NotBindableVo("c");
        mBinder.setObj1(o1);
        mBinder.setObj2(o2);
        mBinder.setObj3(o3);
        mBinder.setCond1(cond1);
        mBinder.setCond2(cond2);
        mBinder.executePendingBindings();
        final String text = mBinder.getTextView().getText().toString();
        assertEquals(cond1 && cond2, "a".equals(text));
        assertEquals(cond1 && !cond2, "b".equals(text));
        assertEquals(!cond1, "c".equals(text));
    }
}
