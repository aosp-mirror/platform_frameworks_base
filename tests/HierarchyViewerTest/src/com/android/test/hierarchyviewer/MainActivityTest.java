package com.android.test.hierarchyviewer;

import android.test.ActivityInstrumentationTestCase2;
import android.view.View;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

public class MainActivityTest extends ActivityInstrumentationTestCase2<MainActivity> {
    private MainActivity mActivity;
    private View mTextView;


    public MainActivityTest() {
        super(MainActivity.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mActivity = getActivity();
        mTextView = mActivity.findViewById(R.id.textView);
    }

    private byte[] encode(View view) throws ClassNotFoundException, NoSuchMethodException,
            IllegalAccessException, InstantiationException, InvocationTargetException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024 * 1024);

        Object encoder = createEncoder(baos);
        invokeMethod(View.class, view, "encode", encoder);
        invokeMethod(encoder.getClass(), encoder, "endStream");

        return baos.toByteArray();
    }

    private Object invokeMethod(Class targetClass, Object target, String methodName, Object... params)
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Class[] paramClasses = new Class[params.length];
        for (int i = 0; i < params.length; i++) {
            paramClasses[i] = params[i].getClass();
        }
        Method method = targetClass.getDeclaredMethod(methodName, paramClasses);
        method.setAccessible(true);
        return method.invoke(target, params);
    }

    private Object createEncoder(ByteArrayOutputStream baos) throws ClassNotFoundException,
            NoSuchMethodException, IllegalAccessException, InvocationTargetException,
            InstantiationException {
        Class clazz = Class.forName("android.view.ViewHierarchyEncoder");
        Constructor constructor = clazz.getConstructor(ByteArrayOutputStream.class);
        return constructor.newInstance(baos);
    }

    public void testTextView() throws Exception {
        byte[] data = encode(mTextView);
        assertNotNull(data);
        assertTrue(data.length > 0);

        ViewDumpParser parser = new ViewDumpParser();
        parser.parse(data);

        List<Map<Short, Object>> views = parser.getViews();
        Map<String, Short> propertyNameTable = parser.getIds();

        assertEquals(1, views.size());
        assertNotNull(propertyNameTable);

        Map<Short, Object> textViewProperties = views.get(0);
        assertEquals("android.widget.TextView",
                textViewProperties.get(propertyNameTable.get("meta:__name__")));

        assertEquals(mActivity.getString(R.string.test),
                textViewProperties.get(propertyNameTable.get("text:text")));
    }
}
