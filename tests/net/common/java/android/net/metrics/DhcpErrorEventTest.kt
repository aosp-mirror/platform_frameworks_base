package android.net.metrics

import android.net.metrics.DhcpErrorEvent.errorCodeWithOption
import android.net.metrics.DhcpErrorEvent.DHCP_INVALID_OPTION_LENGTH
import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import com.android.internal.util.TestUtils.parcelingRoundTrip
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_ERROR_CODE = 12345
/**
 * DHCP Optional Type: DHCP Subnet Mask (Copy from DhcpPacket.java)
 */
private const val DHCP_SUBNET_MASK = 1

@RunWith(AndroidJUnit4::class)
@SmallTest
class DhcpErrorEventTest {

    @Test
    fun testConstructor() {
        val event = DhcpErrorEvent(TEST_ERROR_CODE)
        assertEquals(TEST_ERROR_CODE, event.errorCode)
    }

    @Test
    fun testParcelUnparcel() {
        val event = DhcpErrorEvent(TEST_ERROR_CODE)
        val parceled = parcelingRoundTrip(event)
        assertEquals(TEST_ERROR_CODE, parceled.errorCode)
    }

    @Test
    fun testErrorCodeWithOption() {
        val errorCode = errorCodeWithOption(DHCP_INVALID_OPTION_LENGTH, DHCP_SUBNET_MASK);
        assertTrue((DHCP_INVALID_OPTION_LENGTH and errorCode) == DHCP_INVALID_OPTION_LENGTH);
        assertTrue((DHCP_SUBNET_MASK and errorCode) == DHCP_SUBNET_MASK);
    }

    @Test
    fun testToString() {
        val names = listOf("L2_ERROR", "L3_ERROR", "L4_ERROR", "DHCP_ERROR", "MISC_ERROR")
        val errorFields = DhcpErrorEvent::class.java.declaredFields.filter {
            it.type == Int::class.javaPrimitiveType
                    && Modifier.isPublic(it.modifiers) && Modifier.isStatic(it.modifiers)
                    && it.name !in names
        }

        errorFields.forEach {
            val intValue = it.getInt(null)
            val stringValue = DhcpErrorEvent(intValue).toString()
            assertTrue("Invalid string for error 0x%08X (field %s): %s".format(intValue, it.name,
                    stringValue),
                    stringValue.contains(it.name))
        }
    }

    @Test
    fun testToString_InvalidErrorCode() {
        assertNotNull(DhcpErrorEvent(TEST_ERROR_CODE).toString())
    }
}