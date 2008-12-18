/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package android.core;

import junit.framework.TestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.test.suitebuilder.annotation.MediumTest;

public class StrictMathTest extends TestCase {

    private final double HYP = StrictMath.sqrt(2.0);

    private final double OPP = 1.0;

    private final double ADJ = 1.0;

    /* Required to make previous preprocessor flags work - do not remove */
    int unused = 0;

    /**
     * @tests java.lang.StrictMath#abs(double)
     */
    @SmallTest
    public void testAbsD() {
        // Test for method double java.lang.StrictMath.abs(double)

        assertTrue("Incorrect double abs value",
                (StrictMath.abs(-1908.8976) == 1908.8976));
        assertTrue("Incorrect double abs value",
                (StrictMath.abs(1908.8976) == 1908.8976));
    }

    /**
     * @tests java.lang.StrictMath#abs(float)
     */
    @SmallTest
    public void testAbsF() {
        // Test for method float java.lang.StrictMath.abs(float)
        assertTrue("Incorrect float abs value",
                (StrictMath.abs(-1908.8976f) == 1908.8976f));
        assertTrue("Incorrect float abs value",
                (StrictMath.abs(1908.8976f) == 1908.8976f));
    }

    /**
     * @tests java.lang.StrictMath#abs(int)
     */
    @SmallTest
    public void testAbsI() {
        // Test for method int java.lang.StrictMath.abs(int)
        assertTrue("Incorrect int abs value",
                (StrictMath.abs(-1908897) == 1908897));
        assertTrue("Incorrect int abs value",
                (StrictMath.abs(1908897) == 1908897));
    }

    /**
     * @tests java.lang.StrictMath#abs(long)
     */
    @SmallTest
    public void testAbsJ() {
        // Test for method long java.lang.StrictMath.abs(long)
        assertTrue("Incorrect long abs value", (StrictMath
                .abs(-19088976000089L) == 19088976000089L));
        assertTrue("Incorrect long abs value",
                (StrictMath.abs(19088976000089L) == 19088976000089L));
    }

    /**
     * @tests java.lang.StrictMath#acos(double)
     */
    @SmallTest
    public void testAcosD() {
        // Test for method double java.lang.StrictMath.acos(double)
        assertTrue("Returned incorrect arc cosine", StrictMath.cos(StrictMath
                .acos(ADJ / HYP)) == ADJ / HYP);
    }

    /**
     * @tests java.lang.StrictMath#asin(double)
     */
    @SmallTest
    public void testAsinD() {
        // Test for method double java.lang.StrictMath.asin(double)
        assertTrue("Returned incorrect arc sine", StrictMath.sin(StrictMath
                .asin(OPP / HYP)) == OPP / HYP);
    }

    /**
     * @tests java.lang.StrictMath#atan(double)
     */
    @SmallTest
    public void testAtanD() {
        // Test for method double java.lang.StrictMath.atan(double)
        double answer = StrictMath.tan(StrictMath.atan(1.0));
        assertTrue("Returned incorrect arc tangent: " + answer, answer <= 1.0
                && answer >= 9.9999999999999983E-1);
    }

    /**
     * @tests java.lang.StrictMath#atan2(double,double)
     */
    @SmallTest
    public void testAtan2DD() {
        // Test for method double java.lang.StrictMath.atan2(double, double)
        double answer = StrictMath.atan(StrictMath.tan(1.0));
        assertTrue("Returned incorrect arc tangent: " + answer, answer <= 1.0
                && answer >= 9.9999999999999983E-1);
    }

    /**
     * @tests java.lang.StrictMath#cbrt(double)
     */
    @SuppressWarnings("boxing")
    @SmallTest
    public void testCbrtD() {
        // Test for special situations
        assertTrue("Should return Double.NaN", Double.isNaN(StrictMath
                .cbrt(Double.NaN)));
        assertEquals("Should return Double.POSITIVE_INFINITY",
                Double.POSITIVE_INFINITY, StrictMath
                .cbrt(Double.POSITIVE_INFINITY));
        assertEquals("Should return Double.NEGATIVE_INFINITY",
                Double.NEGATIVE_INFINITY, StrictMath
                .cbrt(Double.NEGATIVE_INFINITY));
        assertEquals(Double.doubleToLongBits(0.0), Double
                .doubleToLongBits(StrictMath.cbrt(0.0)));
        assertEquals(Double.doubleToLongBits(+0.0), Double
                .doubleToLongBits(StrictMath.cbrt(+0.0)));
        assertEquals(Double.doubleToLongBits(-0.0), Double
                .doubleToLongBits(StrictMath.cbrt(-0.0)));

        assertEquals("Should return 3.0", 3.0, StrictMath.cbrt(27.0));
        assertEquals("Should return 23.111993172558684", 23.111993172558684,
                StrictMath.cbrt(12345.6));
        assertEquals("Should return 5.643803094122362E102",
                5.643803094122362E102, StrictMath.cbrt(Double.MAX_VALUE));
        assertEquals("Should return 0.01", 0.01, StrictMath.cbrt(0.000001));

        assertEquals("Should return -3.0", -3.0, StrictMath.cbrt(-27.0));
        assertEquals("Should return -23.111993172558684", -23.111993172558684,
                StrictMath.cbrt(-12345.6));
        assertEquals("Should return 1.7031839360032603E-108",
                1.7031839360032603E-108, StrictMath.cbrt(Double.MIN_VALUE));
        assertEquals("Should return -0.01", -0.01, StrictMath.cbrt(-0.000001));

        try {
            StrictMath.cbrt((Double) null);
            fail("Should throw NullPointerException");
        } catch (NullPointerException e) {
            //expected
        }
    }

    /**
     * @tests java.lang.StrictMath#ceil(double)
     */
    @SmallTest
    public void testCeilD() {
        // Test for method double java.lang.StrictMath.ceil(double)
        assertEquals("Incorrect ceiling for double",
                79, StrictMath.ceil(78.89), 0.0);
        assertEquals("Incorrect ceiling for double",
                -78, StrictMath.ceil(-78.89), 0.0);
    }

    /**
     * @tests java.lang.StrictMath#cos(double)
     */
    @SmallTest
    public void testCosD() {
        // Test for method double java.lang.StrictMath.cos(double)

        assertTrue("Returned incorrect cosine", StrictMath.cos(StrictMath
                .acos(ADJ / HYP)) == ADJ / HYP);
    }

    /**
     * @tests java.lang.StrictMath#cosh(double)
     */
    @SuppressWarnings("boxing")
    @SmallTest
    public void testCosh_D() {
        // Test for special situations        
        assertTrue("Should return NaN", Double.isNaN(StrictMath
                .cosh(Double.NaN)));
        assertEquals("Should return POSITIVE_INFINITY",
                Double.POSITIVE_INFINITY, StrictMath
                .cosh(Double.POSITIVE_INFINITY));
        assertEquals("Should return POSITIVE_INFINITY",
                Double.POSITIVE_INFINITY, StrictMath
                .cosh(Double.NEGATIVE_INFINITY));
        assertEquals("Should return 1.0", 1.0, StrictMath.cosh(+0.0));
        assertEquals("Should return 1.0", 1.0, StrictMath.cosh(-0.0));

        assertEquals("Should return POSITIVE_INFINITY",
                Double.POSITIVE_INFINITY, StrictMath.cosh(1234.56));
        assertEquals("Should return POSITIVE_INFINITY",
                Double.POSITIVE_INFINITY, StrictMath.cosh(-1234.56));
        assertEquals("Should return 1.0000000000005", 1.0000000000005,
                StrictMath.cosh(0.000001));
        assertEquals("Should return 1.0000000000005", 1.0000000000005,
                StrictMath.cosh(-0.000001));
        assertEquals("Should return 5.212214351945598", 5.212214351945598,
                StrictMath.cosh(2.33482));

        assertEquals("Should return POSITIVE_INFINITY",
                Double.POSITIVE_INFINITY, StrictMath.cosh(Double.MAX_VALUE));
        assertEquals("Should return 1.0", 1.0, StrictMath
                .cosh(Double.MIN_VALUE));
    }

    /**
     * @tests java.lang.StrictMath#exp(double)
     */
    @SmallTest
    public void testExpD() {
        // Test for method double java.lang.StrictMath.exp(double)
        assertTrue("Incorrect answer returned for simple power", StrictMath
                .abs(StrictMath.exp(4D) - StrictMath.E * StrictMath.E
                        * StrictMath.E * StrictMath.E) < 0.1D);
        assertTrue("Incorrect answer returned for larger power", StrictMath
                .log(StrictMath.abs(StrictMath.exp(5.5D)) - 5.5D) < 10.0D);
    }

    /**
     * @tests java.lang.StrictMath#expm1(double)
     */
    @SuppressWarnings("boxing")
    @SmallTest
    public void testExpm1D() {
        //Test for special cases        
        assertTrue("Should return NaN", Double.isNaN(StrictMath.expm1(Double.NaN)));
        assertEquals("Should return POSITIVE_INFINITY",
                Double.POSITIVE_INFINITY, StrictMath.expm1(Double.POSITIVE_INFINITY));
        assertEquals("Should return -1.0", -1.0, StrictMath
                .expm1(Double.NEGATIVE_INFINITY));
        assertEquals(Double.doubleToLongBits(0.0), Double
                .doubleToLongBits(StrictMath.expm1(0.0)));
        assertEquals(Double.doubleToLongBits(+0.0), Double
                .doubleToLongBits(StrictMath.expm1(+0.0)));
        assertEquals(Double.doubleToLongBits(-0.0), Double
                .doubleToLongBits(StrictMath.expm1(-0.0)));

        assertEquals("Should return -9.999950000166666E-6",
                -9.999950000166666E-6, StrictMath.expm1(-0.00001));
        assertEquals("Should return 1.0145103074469635E60",
                1.0145103074469635E60, StrictMath.expm1(138.16951162));
        assertEquals("Should return POSITIVE_INFINITY",
                Double.POSITIVE_INFINITY, StrictMath
                .expm1(123456789123456789123456789.4521584223));
        assertEquals("Should return POSITIVE_INFINITY",
                Double.POSITIVE_INFINITY, StrictMath.expm1(Double.MAX_VALUE));
        assertEquals("Should return MIN_VALUE", Double.MIN_VALUE, StrictMath
                .expm1(Double.MIN_VALUE));

    }

    /**
     * @tests java.lang.StrictMath#floor(double)
     */
    @SmallTest
    public void testFloorD() {
        // Test for method double java.lang.StrictMath.floor(double)
        assertEquals("Incorrect floor for double",
                78, StrictMath.floor(78.89), 0.0);
        assertEquals("Incorrect floor for double",
                -79, StrictMath.floor(-78.89), 0.0);
    }

    /**
     * @tests java.lang.StrictMath#hypot(double,double)
     */
    @SuppressWarnings("boxing")
    @SmallTest
    public void testHypotDD() {
        // Test for special cases
        assertEquals("Should return POSITIVE_INFINITY",
                Double.POSITIVE_INFINITY, StrictMath.hypot(Double.POSITIVE_INFINITY,
                1.0));
        assertEquals("Should return POSITIVE_INFINITY",
                Double.POSITIVE_INFINITY, StrictMath.hypot(Double.NEGATIVE_INFINITY,
                123.324));
        assertEquals("Should return POSITIVE_INFINITY",
                Double.POSITIVE_INFINITY, StrictMath.hypot(-758.2587,
                Double.POSITIVE_INFINITY));
        assertEquals("Should return POSITIVE_INFINITY",
                Double.POSITIVE_INFINITY, StrictMath.hypot(5687.21,
                Double.NEGATIVE_INFINITY));
        assertEquals("Should return POSITIVE_INFINITY",
                Double.POSITIVE_INFINITY, StrictMath.hypot(Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY));
        assertEquals("Should return POSITIVE_INFINITY",
                Double.POSITIVE_INFINITY, StrictMath.hypot(Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY));
        assertTrue("Should return NaN", Double.isNaN(StrictMath.hypot(Double.NaN,
                2342301.89843)));
        assertTrue("Should return NaN", Double.isNaN(StrictMath.hypot(-345.2680,
                Double.NaN)));

        assertEquals("Should return 2396424.905416697", 2396424.905416697, StrictMath
                .hypot(12322.12, -2396393.2258));
        assertEquals("Should return 138.16958070558556", 138.16958070558556,
                StrictMath.hypot(-138.16951162, 0.13817035864));
        assertEquals("Should return 1.7976931348623157E308",
                1.7976931348623157E308, StrictMath.hypot(Double.MAX_VALUE, 211370.35));
        assertEquals("Should return 5413.7185", 5413.7185, StrictMath.hypot(
                -5413.7185, Double.MIN_VALUE));

    }

    /**
     * @tests java.lang.StrictMath#IEEEremainder(double,double)
     */
    @SmallTest
    public void testIEEEremainderDD() {
        // Test for method double java.lang.StrictMath.IEEEremainder(double,
        // double)
        assertEquals("Incorrect remainder returned", 0.0, StrictMath.IEEEremainder(
                1.0, 1.0), 0.0);
        assertTrue(
                "Incorrect remainder returned",
                StrictMath.IEEEremainder(1.32, 89.765) >= 1.4705063220631647E-2
                        || StrictMath.IEEEremainder(1.32, 89.765) >= 1.4705063220631649E-2);
    }

    /**
     * @tests java.lang.StrictMath#log(double)
     */
    @SmallTest
    public void testLogD() {
        // Test for method double java.lang.StrictMath.log(double)
        for (double d = 10; d >= -10; d -= 0.5) {
            double answer = StrictMath.log(StrictMath.exp(d));
            assertTrue("Answer does not equal expected answer for d = " + d
                    + " answer = " + answer,
                    StrictMath.abs(answer - d) <= StrictMath
                            .abs(d * 0.00000001));
        }
    }

    /**
     * @tests java.lang.StrictMath#log10(double)
     */
    @SuppressWarnings("boxing")
    @SmallTest
    public void testLog10D() {
        // Test for special cases        
        assertTrue("Should return NaN", Double.isNaN(StrictMath
                .log10(Double.NaN)));
        assertTrue("Should return NaN", Double.isNaN(StrictMath
                .log10(-2541.05745687234187532)));
        assertEquals("Should return POSITIVE_INFINITY",
                Double.POSITIVE_INFINITY, StrictMath
                .log10(Double.POSITIVE_INFINITY));
        assertEquals("Should return NEGATIVE_INFINITY",
                Double.NEGATIVE_INFINITY, StrictMath.log10(0.0));
        assertEquals("Should return NEGATIVE_INFINITY",
                Double.NEGATIVE_INFINITY, StrictMath.log10(+0.0));
        assertEquals("Should return NEGATIVE_INFINITY",
                Double.NEGATIVE_INFINITY, StrictMath.log10(-0.0));
        assertEquals("Should return 14.0", 14.0, StrictMath.log10(StrictMath
                .pow(10, 14)));

        assertEquals("Should return 3.7389561269540406", 3.7389561269540406,
                StrictMath.log10(5482.2158));
        assertEquals("Should return 14.661551142893833", 14.661551142893833,
                StrictMath.log10(458723662312872.125782332587));
        assertEquals("Should return -0.9083828622192334", -0.9083828622192334,
                StrictMath.log10(0.12348583358871));
        assertEquals("Should return 308.25471555991675", 308.25471555991675,
                StrictMath.log10(Double.MAX_VALUE));
        assertEquals("Should return -323.3062153431158", -323.3062153431158,
                StrictMath.log10(Double.MIN_VALUE));
    }

    /**
     * @tests java.lang.StrictMath#log1p(double)
     */
    @SuppressWarnings("boxing")
    @SmallTest
    public void testLog1pD() {
        // Test for special cases
        assertTrue("Should return NaN", Double.isNaN(StrictMath
                .log1p(Double.NaN)));
        assertTrue("Should return NaN", Double.isNaN(StrictMath
                .log1p(-32.0482175)));
        assertEquals("Should return POSITIVE_INFINITY",
                Double.POSITIVE_INFINITY, StrictMath
                .log1p(Double.POSITIVE_INFINITY));
        assertEquals(Double.doubleToLongBits(0.0), Double
                .doubleToLongBits(StrictMath.log1p(0.0)));
        assertEquals(Double.doubleToLongBits(+0.0), Double
                .doubleToLongBits(StrictMath.log1p(+0.0)));
        assertEquals(Double.doubleToLongBits(-0.0), Double
                .doubleToLongBits(StrictMath.log1p(-0.0)));

        assertEquals("Should return -0.2941782295312541", -0.2941782295312541,
                StrictMath.log1p(-0.254856327));
        assertEquals("Should return 7.368050685564151", 7.368050685564151,
                StrictMath.log1p(1583.542));
        assertEquals("Should return 0.4633708685409921", 0.4633708685409921,
                StrictMath.log1p(0.5894227));
        assertEquals("Should return 709.782712893384", 709.782712893384,
                StrictMath.log1p(Double.MAX_VALUE));
        assertEquals("Should return Double.MIN_VALUE", Double.MIN_VALUE,
                StrictMath.log1p(Double.MIN_VALUE));
    }

    /**
     * @tests java.lang.StrictMath#max(double,double)
     */
    @SmallTest
    public void testMaxDD() {
        // Test for method double java.lang.StrictMath.max(double, double)
        assertEquals("Incorrect double max value", 1908897.6000089, StrictMath.max(
                -1908897.6000089, 1908897.6000089), 0D);
        assertEquals("Incorrect double max value", 1908897.6000089, StrictMath.max(2.0,
                1908897.6000089), 0D);
        assertEquals("Incorrect double max value", -2.0, StrictMath.max(-2.0,
                -1908897.6000089), 0D);

    }

    /**
     * @tests java.lang.StrictMath#max(float,float)
     */
    @SmallTest
    public void testMaxFF() {
        // Test for method float java.lang.StrictMath.max(float, float)
        assertTrue("Incorrect float max value", StrictMath.max(-1908897.600f,
                1908897.600f) == 1908897.600f);
        assertTrue("Incorrect float max value", StrictMath.max(2.0f,
                1908897.600f) == 1908897.600f);
        assertTrue("Incorrect float max value", StrictMath.max(-2.0f,
                -1908897.600f) == -2.0f);
    }

    /**
     * @tests java.lang.StrictMath#max(int,int)
     */
    @SmallTest
    public void testMaxII() {
        // Test for method int java.lang.StrictMath.max(int, int)
        assertEquals("Incorrect int max value", 19088976, StrictMath.max(-19088976,
                19088976));
        assertEquals("Incorrect int max value",
                19088976, StrictMath.max(20, 19088976));
        assertEquals("Incorrect int max value",
                -20, StrictMath.max(-20, -19088976));
    }

    /**
     * @tests java.lang.StrictMath#max(long,long)
     */
    @SmallTest
    public void testMaxJJ() {
        // Test for method long java.lang.StrictMath.max(long, long)
        assertEquals("Incorrect long max value", 19088976000089L, StrictMath.max(-19088976000089L,
                19088976000089L));
        assertEquals("Incorrect long max value", 19088976000089L, StrictMath.max(20,
                19088976000089L));
        assertEquals("Incorrect long max value", -20, StrictMath.max(-20,
                -19088976000089L));
    }

    /**
     * @tests java.lang.StrictMath#min(double,double)
     */
    @SmallTest
    public void testMinDD() {
        // Test for method double java.lang.StrictMath.min(double, double)
        assertEquals("Incorrect double min value", -1908897.6000089, StrictMath.min(
                -1908897.6000089, 1908897.6000089), 0D);
        assertEquals("Incorrect double min value", 2.0, StrictMath.min(2.0,
                1908897.6000089), 0D);
        assertEquals("Incorrect double min value", -1908897.6000089, StrictMath.min(-2.0,
                -1908897.6000089), 0D);
    }

    /**
     * @tests java.lang.StrictMath#min(float,float)
     */
    @SmallTest
    public void testMinFF() {
        // Test for method float java.lang.StrictMath.min(float, float)
        assertTrue("Incorrect float min value", StrictMath.min(-1908897.600f,
                1908897.600f) == -1908897.600f);
        assertTrue("Incorrect float min value", StrictMath.min(2.0f,
                1908897.600f) == 2.0f);
        assertTrue("Incorrect float min value", StrictMath.min(-2.0f,
                -1908897.600f) == -1908897.600f);
    }

    /**
     * @tests java.lang.StrictMath#min(int,int)
     */
    @SmallTest
    public void testMinII() {
        // Test for method int java.lang.StrictMath.min(int, int)
        assertEquals("Incorrect int min value", -19088976, StrictMath.min(-19088976,
                19088976));
        assertEquals("Incorrect int min value",
                20, StrictMath.min(20, 19088976));
        assertEquals("Incorrect int min value",
                -19088976, StrictMath.min(-20, -19088976));

    }

    /**
     * @tests java.lang.StrictMath#min(long,long)
     */
    @SmallTest
    public void testMinJJ() {
        // Test for method long java.lang.StrictMath.min(long, long)
        assertEquals("Incorrect long min value", -19088976000089L, StrictMath.min(-19088976000089L,
                19088976000089L));
        assertEquals("Incorrect long min value", 20, StrictMath.min(20,
                19088976000089L));
        assertEquals("Incorrect long min value", -19088976000089L, StrictMath.min(-20,
                -19088976000089L));
    }

    /**
     * @tests java.lang.StrictMath#pow(double,double)
     */
    @SmallTest
    public void testPowDD() {
        // Test for method double java.lang.StrictMath.pow(double, double)
        assertTrue("pow returned incorrect value",
                (long) StrictMath.pow(2, 8) == 256l);
        assertTrue("pow returned incorrect value",
                StrictMath.pow(2, -8) == 0.00390625d);
    }

    /**
     * @tests java.lang.StrictMath#rint(double)
     */
    @SmallTest
    public void testRintD() {
        // Test for method double java.lang.StrictMath.rint(double)
        assertEquals("Failed to round properly - up to odd",
                3.0, StrictMath.rint(2.9), 0D);
        assertTrue("Failed to round properly - NaN", Double.isNaN(StrictMath
                .rint(Double.NaN)));
        assertEquals("Failed to round properly down  to even", 2.0, StrictMath
                .rint(2.1), 0D);
        assertTrue("Failed to round properly " + 2.5 + " to even", StrictMath
                .rint(2.5) == 2.0);
    }

    /**
     * @tests java.lang.StrictMath#round(double)
     */
    @SmallTest
    public void testRoundD() {
        // Test for method long java.lang.StrictMath.round(double)
        assertEquals("Incorrect rounding of a float",
                -91, StrictMath.round(-90.89d));
    }

    /**
     * @tests java.lang.StrictMath#round(float)
     */
    @SmallTest
    public void testRoundF() {
        // Test for method int java.lang.StrictMath.round(float)
        assertEquals("Incorrect rounding of a float",
                -91, StrictMath.round(-90.89f));
    }

    /**
     * @tests java.lang.StrictMath#signum(double)
     */
    @SmallTest
    public void testSignumD() {
        assertTrue(Double.isNaN(StrictMath.signum(Double.NaN)));
        assertEquals(Double.doubleToLongBits(0.0), Double
                .doubleToLongBits(StrictMath.signum(0.0)));
        assertEquals(Double.doubleToLongBits(+0.0), Double
                .doubleToLongBits(StrictMath.signum(+0.0)));
        assertEquals(Double.doubleToLongBits(-0.0), Double
                .doubleToLongBits(StrictMath.signum(-0.0)));

        assertEquals(1.0, StrictMath.signum(253681.2187962), 0D);
        assertEquals(-1.0, StrictMath.signum(-125874693.56), 0D);
        assertEquals(1.0, StrictMath.signum(1.2587E-308), 0D);
        assertEquals(-1.0, StrictMath.signum(-1.2587E-308), 0D);

        assertEquals(1.0, StrictMath.signum(Double.MAX_VALUE), 0D);
        assertEquals(1.0, StrictMath.signum(Double.MIN_VALUE), 0D);
        assertEquals(-1.0, StrictMath.signum(-Double.MAX_VALUE), 0D);
        assertEquals(-1.0, StrictMath.signum(-Double.MIN_VALUE), 0D);
        assertEquals(1.0, StrictMath.signum(Double.POSITIVE_INFINITY), 0D);
        assertEquals(-1.0, StrictMath.signum(Double.NEGATIVE_INFINITY), 0D);

    }

    /**
     * @tests java.lang.StrictMath#signum(float)
     */
    @SmallTest
    public void testSignumF() {
        assertTrue(Float.isNaN(StrictMath.signum(Float.NaN)));
        assertEquals(Float.floatToIntBits(0.0f), Float
                .floatToIntBits(StrictMath.signum(0.0f)));
        assertEquals(Float.floatToIntBits(+0.0f), Float
                .floatToIntBits(StrictMath.signum(+0.0f)));
        assertEquals(Float.floatToIntBits(-0.0f), Float
                .floatToIntBits(StrictMath.signum(-0.0f)));

        assertEquals(1.0f, StrictMath.signum(253681.2187962f), 0f);
        assertEquals(-1.0f, StrictMath.signum(-125874693.56f), 0f);
        assertEquals(1.0f, StrictMath.signum(1.2587E-11f), 0f);
        assertEquals(-1.0f, StrictMath.signum(-1.2587E-11f), 0f);

        assertEquals(1.0f, StrictMath.signum(Float.MAX_VALUE), 0f);
        assertEquals(1.0f, StrictMath.signum(Float.MIN_VALUE), 0f);
        assertEquals(-1.0f, StrictMath.signum(-Float.MAX_VALUE), 0f);
        assertEquals(-1.0f, StrictMath.signum(-Float.MIN_VALUE), 0f);
        assertEquals(1.0f, StrictMath.signum(Float.POSITIVE_INFINITY), 0f);
        assertEquals(-1.0f, StrictMath.signum(Float.NEGATIVE_INFINITY), 0f);
    }

    /**
     * @tests java.lang.StrictMath#sin(double)
     */
    @SmallTest
    public void testSinD() {
        // Test for method double java.lang.StrictMath.sin(double)
        assertTrue("Returned incorrect sine", StrictMath.sin(StrictMath
                .asin(OPP / HYP)) == OPP / HYP);
    }

    /**
     * @tests java.lang.StrictMath#sinh(double)
     */
    @SmallTest
    public void testSinhD() {
        // Test for special situations
        assertTrue(Double.isNaN(StrictMath.sinh(Double.NaN)));
        assertEquals("Should return POSITIVE_INFINITY",
                Double.POSITIVE_INFINITY, StrictMath
                .sinh(Double.POSITIVE_INFINITY), 0D);
        assertEquals("Should return NEGATIVE_INFINITY",
                Double.NEGATIVE_INFINITY, StrictMath
                .sinh(Double.NEGATIVE_INFINITY), 0D);
        assertEquals(Double.doubleToLongBits(0.0), Double
                .doubleToLongBits(StrictMath.sinh(0.0)));
        assertEquals(Double.doubleToLongBits(+0.0), Double
                .doubleToLongBits(StrictMath.sinh(+0.0)));
        assertEquals(Double.doubleToLongBits(-0.0), Double
                .doubleToLongBits(StrictMath.sinh(-0.0)));

        assertEquals("Should return POSITIVE_INFINITY",
                Double.POSITIVE_INFINITY, StrictMath.sinh(1234.56), 0D);
        assertEquals("Should return NEGATIVE_INFINITY",
                Double.NEGATIVE_INFINITY, StrictMath.sinh(-1234.56), 0D);
        assertEquals("Should return 1.0000000000001666E-6",
                1.0000000000001666E-6, StrictMath.sinh(0.000001), 0D);
        assertEquals("Should return -1.0000000000001666E-6",
                -1.0000000000001666E-6, StrictMath.sinh(-0.000001), 0D);
        assertEquals("Should return 5.115386441963859", 5.115386441963859,
                StrictMath.sinh(2.33482), 0D);
        assertEquals("Should return POSITIVE_INFINITY",
                Double.POSITIVE_INFINITY, StrictMath.sinh(Double.MAX_VALUE), 0D);
        assertEquals("Should return 4.9E-324", 4.9E-324, StrictMath
                .sinh(Double.MIN_VALUE), 0D);
    }

    /**
     * @tests java.lang.StrictMath#sqrt(double)
     */
    @SmallTest
    public void testSqrtD() {
        // Test for method double java.lang.StrictMath.sqrt(double)
        assertEquals("Incorrect root returned1",
                2, StrictMath.sqrt(StrictMath.pow(StrictMath.sqrt(2), 4)), 0.0);
        assertEquals("Incorrect root returned2", 7, StrictMath.sqrt(49), 0.0);
    }

    /**
     * @tests java.lang.StrictMath#tan(double)
     */
    @SmallTest
    public void testTanD() {
        // Test for method double java.lang.StrictMath.tan(double)
        assertTrue(
                "Returned incorrect tangent: ",
                StrictMath.tan(StrictMath.atan(1.0)) <= 1.0
                        || StrictMath.tan(StrictMath.atan(1.0)) >= 9.9999999999999983E-1);
    }

    /**
     * @tests java.lang.StrictMath#tanh(double)
     */
    @SmallTest
    public void testTanhD() {
        // Test for special situations
        assertTrue(Double.isNaN(StrictMath.tanh(Double.NaN)));
        assertEquals("Should return +1.0", +1.0, StrictMath
                .tanh(Double.POSITIVE_INFINITY), 0D);
        assertEquals("Should return -1.0", -1.0, StrictMath
                .tanh(Double.NEGATIVE_INFINITY), 0D);
        assertEquals(Double.doubleToLongBits(0.0), Double
                .doubleToLongBits(StrictMath.tanh(0.0)));
        assertEquals(Double.doubleToLongBits(+0.0), Double
                .doubleToLongBits(StrictMath.tanh(+0.0)));
        assertEquals(Double.doubleToLongBits(-0.0), Double
                .doubleToLongBits(StrictMath.tanh(-0.0)));

        assertEquals("Should return 1.0", 1.0, StrictMath.tanh(1234.56), 0D);
        assertEquals("Should return -1.0", -1.0, StrictMath.tanh(-1234.56), 0D);
        assertEquals("Should return 9.999999999996666E-7",
                9.999999999996666E-7, StrictMath.tanh(0.000001), 0D);
        assertEquals("Should return 0.981422884124941", 0.981422884124941,
                StrictMath.tanh(2.33482), 0D);
        assertEquals("Should return 1.0", 1.0, StrictMath
                .tanh(Double.MAX_VALUE), 0D);
        assertEquals("Should return 4.9E-324", 4.9E-324, StrictMath
                .tanh(Double.MIN_VALUE), 0D);
    }

    /**
     * @tests java.lang.StrictMath#random()
     */
    @MediumTest
    public void testRandom() {
        // There isn't a place for these tests so just stick them here
        assertEquals("Wrong value E",
                4613303445314885481L, Double.doubleToLongBits(StrictMath.E));
        assertEquals("Wrong value PI",
                4614256656552045848L, Double.doubleToLongBits(StrictMath.PI));

        for (int i = 500; i >= 0; i--) {
            double d = StrictMath.random();
            assertTrue("Generated number is out of range: " + d, d >= 0.0
                    && d < 1.0);
        }
    }

    /**
     * @tests java.lang.StrictMath#toRadians(double)
     */
    @MediumTest
    public void testToRadiansD() {
        for (double d = 500; d >= 0; d -= 1.0) {
            double converted = StrictMath.toDegrees(StrictMath.toRadians(d));
            assertTrue("Converted number not equal to original. d = " + d,
                    converted >= d * 0.99999999 && converted <= d * 1.00000001);
        }
    }

    /**
     * @tests java.lang.StrictMath#toDegrees(double)
     */
    @MediumTest
    public void testToDegreesD() {
        for (double d = 500; d >= 0; d -= 1.0) {
            double converted = StrictMath.toRadians(StrictMath.toDegrees(d));
            assertTrue("Converted number not equal to original. d = " + d,
                    converted >= d * 0.99999999 && converted <= d * 1.00000001);
        }
    }

    /**
     * @tests java.lang.StrictMath#ulp(double)
     */
    @SuppressWarnings("boxing")
    @SmallTest
    public void testUlp_D() {
        // Test for special cases
        assertTrue("Should return NaN", Double
                .isNaN(StrictMath.ulp(Double.NaN)));
        assertEquals("Returned incorrect value", Double.POSITIVE_INFINITY,
                StrictMath.ulp(Double.POSITIVE_INFINITY), 0D);
        assertEquals("Returned incorrect value", Double.POSITIVE_INFINITY,
                StrictMath.ulp(Double.NEGATIVE_INFINITY), 0D);
        assertEquals("Returned incorrect value", Double.MIN_VALUE, StrictMath
                .ulp(0.0), 0D);
        assertEquals("Returned incorrect value", Double.MIN_VALUE, StrictMath
                .ulp(+0.0), 0D);
        assertEquals("Returned incorrect value", Double.MIN_VALUE, StrictMath
                .ulp(-0.0), 0D);
        assertEquals("Returned incorrect value", StrictMath.pow(2, 971),
                StrictMath.ulp(Double.MAX_VALUE), 0D);
        assertEquals("Returned incorrect value", StrictMath.pow(2, 971),
                StrictMath.ulp(-Double.MAX_VALUE), 0D);

        assertEquals("Returned incorrect value", Double.MIN_VALUE, StrictMath
                .ulp(Double.MIN_VALUE), 0D);
        assertEquals("Returned incorrect value", Double.MIN_VALUE, StrictMath
                .ulp(-Double.MIN_VALUE), 0D);

        assertEquals("Returned incorrect value", 2.220446049250313E-16,
                StrictMath.ulp(1.0), 0D);
        assertEquals("Returned incorrect value", 2.220446049250313E-16,
                StrictMath.ulp(-1.0), 0D);
        assertEquals("Returned incorrect value", 2.2737367544323206E-13,
                StrictMath.ulp(1153.0), 0D);
    }

    /**
     * @tests java.lang.StrictMath#ulp(float)
     */
    @SuppressWarnings("boxing")
    @SmallTest
    public void testUlpF() {
        // Test for special cases
        assertTrue("Should return NaN", Float.isNaN(StrictMath.ulp(Float.NaN)));
        assertEquals("Returned incorrect value", Float.POSITIVE_INFINITY,
                StrictMath.ulp(Float.POSITIVE_INFINITY), 0f);
        assertEquals("Returned incorrect value", Float.POSITIVE_INFINITY,
                StrictMath.ulp(Float.NEGATIVE_INFINITY), 0f);
        assertEquals("Returned incorrect value", Float.MIN_VALUE, StrictMath
                .ulp(0.0f), 0f);
        assertEquals("Returned incorrect value", Float.MIN_VALUE, StrictMath
                .ulp(+0.0f), 0f);
        assertEquals("Returned incorrect value", Float.MIN_VALUE, StrictMath
                .ulp(-0.0f), 0f);
        assertEquals("Returned incorrect value", 2.028241E31f, StrictMath
                .ulp(Float.MAX_VALUE), 0f);
        assertEquals("Returned incorrect value", 2.028241E31f, StrictMath
                .ulp(-Float.MAX_VALUE), 0f);

        assertEquals("Returned incorrect value", 1.4E-45f, StrictMath
                .ulp(Float.MIN_VALUE), 0f);
        assertEquals("Returned incorrect value", 1.4E-45f, StrictMath
                .ulp(-Float.MIN_VALUE), 0f);

        assertEquals("Returned incorrect value", 1.1920929E-7f, StrictMath
                .ulp(1.0f), 0f);
        assertEquals("Returned incorrect value", 1.1920929E-7f, StrictMath
                .ulp(-1.0f), 0f);
        assertEquals("Returned incorrect value", 1.2207031E-4f, StrictMath
                .ulp(1153.0f), 0f);
        assertEquals("Returned incorrect value", 5.6E-45f, Math
                .ulp(9.403954E-38f), 0f);
    }
}
