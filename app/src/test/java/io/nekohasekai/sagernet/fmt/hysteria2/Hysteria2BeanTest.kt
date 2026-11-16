package io.nekohasekai.sagernet.fmt.hysteria2

import io.nekohasekai.sagernet.fmt.KryoConverters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.nekohasekai.sagernet.ktx.byteBuffer
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Hysteria2BeanTest {

    private fun bean(dlc: Boolean) = Hysteria2Bean().apply {
        initializeDefaultValues()
        serverAddress = "example.com"
        serverPort = 443
        auth = "pw"
        uploadMbps = 50
        disableLossCompensation = dlc
    }

    @Test
    fun roundTrip() {
        val bytes = KryoConverters.serialize(bean(true))
        assertEquals(13, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int)
        val back = KryoConverters.deserialize(Hysteria2Bean(), bytes)
        assertTrue(back.disableLossCompensation)
        assertEquals(50L, back.uploadMbps)
        assertFalse(KryoConverters.deserialize(Hysteria2Bean(), KryoConverters.serialize(bean(false))).disableLossCompensation)
    }

    @Test
    fun readsVersion12Data() {
        // a version 12 record is the version 13 one without the boolean that ends the bean fields
        val b = bean(false)
        val v13 = KryoConverters.serialize(b)
        val fieldsEnd = ByteArrayOutputStream().also { out ->
            out.byteBuffer().apply { b.serialize(this); flush() }
        }.size()
        val v12 = v13.copyOfRange(0, fieldsEnd - 1) + v13.copyOfRange(fieldsEnd, v13.size)
        ByteBuffer.wrap(v12).order(ByteOrder.LITTLE_ENDIAN).putInt(12)
        val back = KryoConverters.deserialize(Hysteria2Bean(), v12)
        assertFalse(back.disableLossCompensation)
        assertEquals("example.com", back.serverAddress)
        assertEquals("pw", back.auth)
        assertEquals(50L, back.uploadMbps)
    }
}
