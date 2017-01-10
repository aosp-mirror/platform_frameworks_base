package com.android.internal.logging;

import junit.framework.TestCase;

public class LogBuilderTest extends TestCase {

    public void testSerialize() {
        LogBuilder builder = new LogBuilder(0);
        builder.addTaggedData(1, "one");
        builder.addTaggedData(2, "two");
        Object[] out = builder.serialize();
        assertEquals(1, out[0]);
        assertEquals("one", out[1]);
        assertEquals(2, out[2]);
        assertEquals("two", out[3]);
    }

    public void testInvalidInputThrows() {
        LogBuilder builder = new LogBuilder(0);
        boolean threw = false;
        try {
            builder.addTaggedData(0, new Object());
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assertTrue(threw);
        assertEquals(0, builder.serialize().length);
    }

    public void testValidInputTypes() {
        LogBuilder builder = new LogBuilder(0);
        builder.addTaggedData(1, "onetwothree");
        builder.addTaggedData(2, 123);
        builder.addTaggedData(3, 123L);
        builder.addTaggedData(4, 123.0F);
        Object[] out = builder.serialize();
        assertEquals("onetwothree", out[1]);
        assertEquals(123, out[3]);
        assertEquals(123L, out[5]);
        assertEquals(123.0F, out[7]);
    }

}
