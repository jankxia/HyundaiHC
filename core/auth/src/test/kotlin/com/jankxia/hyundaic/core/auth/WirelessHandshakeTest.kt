package com.jankxia.hyundaic.core.auth

import com.jankxia.hyundaic.core.common.toHex
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 无线认证（蓝牙 RFCOMM 子通道上）：外层与有线只差两处——应答里 `usb_connection` 为 false、不下发 `pin_code`；
 * 之后的认证库消息与有线逐字节相同。期望值由有线的合成向量改出来。
 */
class WirelessHandshakeTest {
    private val env = Vectors.envelope
    private val carInfo: Map<String, String> = env["carInfo"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }

    private fun wirelessSession(store: TrustStore, seed: String, pin: String? = Synthetic.PIN) = HiCarAuthSession(
        HiCarAuthConfig(Synthetic.SERVICE, Synthetic.CAR_ID, env["version"]!!.jsonPrimitive.content, pin, usbConnection = false),
        KotlinAccessoryAuthenticator.factory(store, SequenceRandom(seed)),
        SecureRandom(),
    ) { carInfo }

    private fun handshake(hasPin: Boolean, usb: Boolean): String =
        phoneEnvelope(1, "{\"deviceId\":\"${Synthetic.PHONE_ID}\",\"has_pin\":$hasPin,\"usb_connection\":$usb,\"next\":\"1\"}")

    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

    private fun List<HiCarAuthAction>.sentText() =
        filterIsInstance<HiCarAuthAction.Send>().map { it.bytes.toString(Charsets.UTF_8) }

    private fun phoneFor(pin: String?, store: TestPhone.Store = TestPhone.Store()) = TestPhone(
        Synthetic.SERVICE, Synthetic.PHONE_ID, Synthetic.CAR_ID, pin, SecureRandom(), store,
    )

    /** 会话与测试手机来回换消息直到结束。 */
    private fun converse(s: HiCarAuthSession, phone: TestPhone, first: String): Pair<List<String>, HiCarAuthAction?> {
        val sent = ArrayList<String>()
        var toCar: String? = first
        var last: HiCarAuthAction? = null
        while (toCar != null && !s.isFinished) {
            val actions = s.feed(bytes(phoneEnvelope(3, toCar)))
            sent += actions.sentText()
            last = actions.lastOrNull()
            toCar = null
            for (text in actions.sentText()) {
                phone.onMessage(unwrapEnvelope(text))?.let { toCar = it }
            }
        }
        return sent to last
    }

    /** 有线应答的结尾换成无线的：`usb_connection` 为 false，没有 `pin_code`。 */
    private fun wirelessVersionOf(wired: String): String {
        val cut = wired.indexOf(",\"usb_connection\":true")
        assertTrue(cut > 0)
        return wired.substring(0, cut) + ",\"usb_connection\":false}"
    }

    @Test
    fun pinPairingAnswersWithoutPinCodeAndDerivesSameKey() {
        val s = wirelessSession(InMemoryTrustStore(), Vectors.string(Vectors.scenario("pair"), "carSeed"))
        val first = s.feed(bytes(handshake(hasPin = true, usb = false)))
        val reply = first.sentText().single()
        assertEquals(wirelessVersionOf(env["handshakeWithPin"]!!.jsonPrimitive.content), reply)
        assertFalse(reply.contains("pin_code"))
        assertFalse(reply.contains(Synthetic.PIN))
        val answered = assertIs<HiCarAuthAction.HandshakeAnswered>(first.last())
        assertEquals(AuthMode.PIN_PAIRING, answered.mode)
        // 口令就是配置里的连接码（二维码上那个），与有线向量的口令相同，所以后面每条消息都和有线一样。
        assertEquals(Synthetic.PIN, answered.pinCode)

        // 认证数据用测试手机真正跑一遍：无线与有线的会话密钥推导一致，通道只影响外层。
        val phone = phoneFor(Synthetic.PIN)
        val (sent, last) = converse(s, phone, phone.startPake())
        val vectorWrapped = Vectors.strings(env["wrapped"]!!.jsonObject["pair"])
        assertEquals(shapeOf(vectorWrapped.first()), shapeOf(sent.first()))
        val success = assertIs<HiCarAuthAction.Succeeded>(last)
        assertEquals(16, success.sessionKey.size)
        assertEquals(Synthetic.PIN, answered.pinCode)
        assertEquals(phone.sessionKey!!.size, success.sessionKey.size)
    }

    @Test
    fun trustedReconnectOverWireless() {
        val store = InMemoryTrustStore()
        val phoneStore = TestPhone.Store()
        val pairingPhone = phoneFor(Synthetic.PIN, phoneStore)
        val pairing = wirelessSession(store, "pairing")
        pairing.feed(bytes(handshake(hasPin = true, usb = false)))
        val (_, paired) = converse(pairing, pairingPhone, pairingPhone.startPake())
        assertIs<HiCarAuthAction.Succeeded>(paired)
        assertTrue(pairing.isFinished)

        val s = wirelessSession(store, "sts")
        val first = s.feed(bytes(handshake(hasPin = false, usb = false)))
        assertEquals(wirelessVersionOf(env["handshakeWithoutPin"]!!.jsonPrimitive.content), first.sentText().single())
        assertEquals(AuthMode.TRUSTED_RECONNECT, assertIs<HiCarAuthAction.HandshakeAnswered>(first.last()).mode)
        val stsPhone = phoneFor(null, phoneStore)
        val (sent, last) = converse(s, stsPhone, stsPhone.startSts())
        val vectorWrapped = Vectors.strings(env["wrapped"]!!.jsonObject["sts"])
        assertEquals(shapeOf(vectorWrapped.first()), shapeOf(sent.first()))
        assertEquals(16, assertIs<HiCarAuthAction.Succeeded>(last).sessionKey.size)
    }

    @Test
    fun wiredHandshakeOnWirelessSessionIsRejected() {
        val s = wirelessSession(InMemoryTrustStore(), "x")
        val actions = s.feed(bytes(handshake(hasPin = true, usb = true)))
        assertIs<AuthFailure.UnsupportedConnection>(actions.filterIsInstance<HiCarAuthAction.Failed>().single().failure)
        assertTrue(s.isFinished)
    }

    @Test
    fun wirelessConfigNeedsCurrentPin() {
        assertFailsWith<IllegalArgumentException> {
            HiCarAuthConfig(Synthetic.SERVICE, Synthetic.CAR_ID, Synthetic.ENVELOPE_VERSION, pin = null, usbConnection = false)
        }
        // 有线配置的默认值不变：没给连接码时会话自己生成。
        assertTrue(HiCarAuthConfig(Synthetic.SERVICE, Synthetic.CAR_ID, Synthetic.ENVELOPE_VERSION).usbConnection)
    }
}

