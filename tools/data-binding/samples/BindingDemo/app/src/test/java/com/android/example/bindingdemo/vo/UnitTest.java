package com.android.example.bindingdemo.vo;

import android.binding.OnPropertyChangedListener;

import com.android.example.bindingdemo.R;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.android.example.bindingdemo.BR;
public class UnitTest {

    private User testUser;

    @Before
    public void setUp() throws Exception {
        testUser = new User("Ted", "Tester", R.drawable.george, User.ROBOT);
    }

    @Test
    public void settersWorkFineOnTheJvm() throws Exception {
        assertEquals("Ted", testUser.getName());
        testUser.setName("Tom");
        assertEquals("Tom", testUser.getName());
    }

    @Test
    public void listeners() throws Exception {
        OnPropertyChangedListener mockListener = mock(OnPropertyChangedListener.class);
        testUser.addOnPropertyChangedListener(mockListener);
        testUser.setName("Tom");
        verify(mockListener).onPropertyChanged(testUser, BR.name);
        verifyNoMoreInteractions(mockListener);
    }
}