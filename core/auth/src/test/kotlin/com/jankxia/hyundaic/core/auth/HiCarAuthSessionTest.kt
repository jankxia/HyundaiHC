package com.jankxia.hyundaic.core.auth

import com.jankxia.hyundaic.core.common.MalformedMessageException
import com.jankxia.hyundaic.core.common.toHex
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 外层会话：认证 TCP 字节流的分帧、设备握手与 `oper_type=3` 封装。
 * 期望字节按 Python json.dumps(..., ensure_ascii=False, separators=(",", ":")) 的紧凑输出生成，见向量里的 envelope 一节。
 */
class HiCarAuthSessionTest {
    private val env = Vectors.envelope
    private val carInfo: Map<String, String> = env["carInfo"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }

    private fun session(
        store: TrustStore,
        seed: String,
        pin: String? = Synthetic.PIN,
        info: (PhoneHandshake) -> Map<String, String> = { carInfo },
    ) = HiCarAuthSession(
        HiCarAuthConfig(Synthetic.SERVICE, Synthetic.CAR_ID, env["version"]!!.jsonPrimitive.content, pin),
        KotlinAccessoryAuthenticator.factory(store, SequenceRandom(seed)),
        SecureRandom(),
        info,
    )

    private fun handshake(hasPin: Boolean, usb: Boolean = true, deviceId: String = Synthetic.PHONE_ID, extra: String = ""): String {
        val body = "{\"deviceId\":\"$deviceId\",\"has_pin\":$hasPin,\"usb_connection\":$usb,\"next\":\"1\"$extra}"
        return phoneEnvelope(1, body)
    }

    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

    private fun List<HiCarAuthAction>.sentText() = filterIsInstance<HiCarAuthAction.Send>().map { it.bytes.toString(Charsets.UTF_8) }

    private fun phoneFor(pin: String?) = TestPhone(
        Synthetic.SERVICE, Synthetic.PHONE_ID, Synthetic.CAR_ID, pin, SecureRandom(), TestPhone.Store(),
    )

    private class Talk(val sent: List<String>, val phone: List<String>, val last: HiCarAuthAction?)

    /** 让会话与测试手机来回换消息直到结束；手机发出的每条消息都记下来，便于按字节回放。 */
    private fun converse(s: HiCarAuthSession, phone: TestPhone, first: String): Talk {
        val sent = ArrayList<String>()
        val outgoing = arrayListOf(first)
        var toCar: String? = first
        var last: HiCarAuthAction? = null
        while (toCar != null && !s.isFinished) {
            val actions = s.feed(bytes(phoneEnvelope(3, toCar)))
            sent += actions.sentText()
            last = actions.lastOrNull()
            toCar = null
            for (text in actions.sentText()) {
                val reply = phone.onMessage(unwrapEnvelope(text)) ?: continue
                outgoing += reply
                toCar = reply
            }
        }
        return Talk(sent, outgoing, last)
    }

    /** 用测试手机真正配对一次（不依赖向量的随机数），返回有记录的信任库。 */
    private fun pairedStore(phone: TestPhone = phoneFor(Synthetic.PIN)): InMemoryTrustStore {
        val store = InMemoryTrustStore()
        val s = session(store, "pairing")
        s.feed(bytes(handshake(true)))
        val talk = converse(s, phone, phone.startPake())
        assertIs<HiCarAuthAction.Succeeded>(talk.last)
        return store
    }

    @Test
    fun pinPairingThroughEnvelopesMatchesPrototypeShell() {
        val store = InMemoryTrustStore()
        var seen: PhoneHandshake? = null
        val s = session(store, Vectors.string(Vectors.scenario("pair"), "carSeed"), info = { seen = it; carInfo })
        val first = s.feed(bytes(handshake(true, extra = ",\"deviceParams\":{\"supportDesktops\":[1,2,3]}")))
        assertEquals(listOf(env["handshakeWithPin"]!!.jsonPrimitive.content), first.sentText())
        val answered = assertIs<HiCarAuthAction.HandshakeAnswered>(first.last())
        assertEquals(AuthMode.PIN_PAIRING, answered.mode)
        assertEquals(Synthetic.PIN, answered.pinCode)
        assertEquals(Synthetic.PHONE_ID, seen!!.deviceId)
        assertEquals(listOf(1, 2, 3), seen!!.data["deviceParams"]!!.jsonObject["supportDesktops"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() })

        // 认证数据让测试手机真正跑一遍配对：C 侧向量里的随机材料取不到同一组值，
        // 只有首条认证应答可以和向量对照结构（字段名、顺序、空白、各段长度）。
        val phone = phoneFor(Synthetic.PIN)
        val talk = converse(s, phone, phone.startPake())
        val vectorWrapped = Vectors.strings(env["wrapped"]!!.jsonObject["pair"])
        assertEquals(shapeOf(vectorWrapped.first()), shapeOf(talk.sent.first()))
        val success = assertIs<HiCarAuthAction.Succeeded>(talk.last)
        assertEquals(16, success.sessionKey.size)
        assertContentEquals(phone.sessionKey, success.sessionKey)
        assertEquals(AuthMode.PIN_PAIRING, success.mode)
        assertEquals(Synthetic.PHONE_ID, success.peerAuthId)
        assertTrue(s.isFinished)
        assertEquals(emptyList(), s.feed(bytes(handshake(true))))
    }

    @Test
    fun trustedReconnectThroughEnvelopes() {
        val paired = phoneFor(Synthetic.PIN)
        val store = pairedStore(paired)
        val s = session(store, "sts", pin = null)
        val first = s.feed(bytes(handshake(false)))
        assertEquals(listOf(env["handshakeWithoutPin"]!!.jsonPrimitive.content), first.sentText())
        val answered = assertIs<HiCarAuthAction.HandshakeAnswered>(first.last())
        assertEquals(AuthMode.TRUSTED_RECONNECT, answered.mode)
        assertNull(answered.pinCode)
        val stsPhone = TestPhone(Synthetic.SERVICE, Synthetic.PHONE_ID, Synthetic.CAR_ID, null, SecureRandom(), paired.store)
        val talk = converse(s, stsPhone, stsPhone.startSts())
        val vectorWrapped = Vectors.strings(env["wrapped"]!!.jsonObject["sts"])
        assertEquals(shapeOf(vectorWrapped.first()), shapeOf(talk.sent.first()))
        val success = assertIs<HiCarAuthAction.Succeeded>(talk.last)
        assertEquals(16, success.sessionKey.size)
        assertContentEquals(stsPhone.sessionKey, success.sessionKey)
    }

    @Test
    fun wrongPinThroughEnvelopes() {
        val s = session(InMemoryTrustStore(), Vectors.string(Vectors.scenario("wrongPin"), "carSeed"))
        s.feed(bytes(handshake(true)))
        val sent = ArrayList<String>()
        var last: HiCarAuthAction? = null
        for (m in Vectors.phoneMessages("wrongPin")) {
            val actions = s.feed(bytes(phoneEnvelope(3, m)))
            sent += actions.sentText()
            last = actions.lastOrNull()
        }
        // 首条应答结构照向量；确认值一定对不上（口令不同），所以只会有「应答 + 错误通知」两条。
        val expected = Vectors.strings(env["wrapped"]!!.jsonObject["wrongPin"])
        assertEquals(shapeOf(expected.first()), shapeOf(sent.first()))
        assertEquals(2, sent.size)
        // 错误通知里的空格在外层被规整掉
        assertTrue(sent.last().contains("{\\\"message\\\":32896,\\\"payload\\\":{\\\"errorCode\\\":17}}"))
        val failure = assertIs<HiCarAuthAction.Failed>(last).failure
        assertEquals(HcError.VERIFY_PROOF_FAILED, assertIs<AuthFailure.Local>(failure).error)
    }

    @Test
    fun arbitraryTcpChunkingGivesSameResult() {
        // 先用测试手机跑一遍拿到手机侧完整字节，再按各种切法喂回同一个种子新建的会话：结果必须一样。
        val handshakeText = " \r\n" + handshake(true, extra = ",\"name\":\"测试手机{}\\\"\"")
        val reference = session(InMemoryTrustStore(), "chunk")
        reference.feed(bytes(handshakeText))
        val phone = phoneFor(Synthetic.PIN)
        val talk = converse(reference, phone, phone.startPake())
        assertIs<HiCarAuthAction.Succeeded>(talk.last)

        val buffer = ByteArrayOutputStream()
        buffer.write(bytes(handshakeText))
        for (m in talk.phone) buffer.write(bytes(phoneEnvelope(3, m)))
        val data = buffer.toByteArray()

        var baseline: List<String>? = null
        for (chunk in listOf(1, 2, 3, 7, 64, 1000, data.size)) {
            val s = session(InMemoryTrustStore(), "chunk")
            val actions = ArrayList<HiCarAuthAction>()
            var offset = 0
            while (offset < data.size) {
                val n = minOf(chunk, data.size - offset)
                actions += s.feed(data, offset, n)
                offset += n
            }
            val sent = actions.sentText()
            assertEquals(env["handshakeWithPin"]!!.jsonPrimitive.content, sent.first(), "切分 $chunk")
            if (baseline == null) baseline = sent else assertEquals(baseline, sent, "切分 $chunk")
            assertIs<HiCarAuthAction.Succeeded>(actions.last(), "切分 $chunk")
        }
    }

    @Test
    fun reconnectWithoutTrustIsRefusedWithoutReply() {
        val s = session(InMemoryTrustStore(), "x", pin = null)
        val actions = s.feed(bytes(handshake(false)))
        assertEquals(emptyList(), actions.sentText())
        assertIs<AuthFailure.NotTrusted>(actions.filterIsInstance<HiCarAuthAction.Failed>().single().failure)
        assertTrue(s.isFinished)
    }

    @Test
    fun wirelessHandshakeIsNotSupportedYet() {
        val s = session(InMemoryTrustStore(), "x")
        val actions = s.feed(bytes(handshake(true, usb = false)))
        assertIs<AuthFailure.UnsupportedConnection>(actions.filterIsInstance<HiCarAuthAction.Failed>().single().failure)
    }

    @Test
    fun generatesPinWhenNotConfigured() {
        val s = session(InMemoryTrustStore(), "x", pin = null)
        val actions = s.feed(bytes(handshake(true)))
        val pin = assertIs<HiCarAuthAction.HandshakeAnswered>(actions.last()).pinCode!!
        assertTrue(PinCodes.isValid(pin))
        assertTrue(actions.sentText().single().endsWith(",\"usb_connection\":true,\"pin_code\":\"$pin\"}"))
    }

    @Test
    fun diagnosticNotesCarryFlagsAndCodesOnly() {
        val s = session(InMemoryTrustStore(), Vectors.string(Vectors.scenario("pair"), "carSeed"))
        val first = s.feed(bytes(handshake(true)))
        val handshakeNote = assertIs<HiCarAuthAction.Note>(first.first()).text
        assertTrue(handshakeNote.contains("has_pin=true") && handshakeNote.contains("usb_connection=true"), handshakeNote)
        assertTrue(handshakeNote.contains("配对记录=false"), handshakeNote)
        // 不带手机身份、连接码。
        assertFalse(handshakeNote.contains(Synthetic.PHONE_ID), handshakeNote)
        assertFalse(handshakeNote.contains(Synthetic.PIN), handshakeNote)
        val second = s.feed(bytes(phoneEnvelope(3, Vectors.phoneMessages("pair").first())))
        val hiChainNote = assertIs<HiCarAuthAction.Note>(second.first()).text
        assertTrue(Regex("认证库消息：收到 0x[0-9A-F]{4}，回 0x[0-9A-F]{4}").matches(hiChainNote), hiChainNote)
    }

    /** 手机改走输码时把设备握手连发两遍（中间没有认证数据）：按新的一次重新应答，之后照常配对成功。 */
    @Test
    fun handshakeRepeatedBeforeAuthDataIsAnsweredAgain() {
        val store = InMemoryTrustStore()
        val seed = Vectors.string(Vectors.scenario("pair"), "carSeed")
        // 每个认证器都从同一个种子取随机数（重发握手会新建认证器），向量才对得上。
        val s = HiCarAuthSession(
            HiCarAuthConfig(Synthetic.SERVICE, Synthetic.CAR_ID, env["version"]!!.jsonPrimitive.content, Synthetic.PIN),
            AccessoryAuthenticatorFactory { config -> KotlinAccessoryAuthenticator.factory(store, SequenceRandom(seed)).create(config) },
            SecureRandom(),
        ) { carInfo }
        val first = s.feed(bytes(handshake(true)))
        val again = s.feed(bytes(handshake(true)))
        assertFalse(s.isFinished)
        assertEquals(first.sentText(), again.sentText(), "应答内容一样")
        val note = assertIs<HiCarAuthAction.Note>(again.first()).text
        assertTrue(note.contains("重发了设备握手") && note.contains("第 2 次") && note.contains("上次 has_pin=true"), note)
        assertFalse(note.contains(Synthetic.PHONE_ID) || note.contains(Synthetic.PIN), note)
        assertEquals(AuthMode.PIN_PAIRING, assertIs<HiCarAuthAction.HandshakeAnswered>(again.last()).mode)
        val phone = phoneFor(Synthetic.PIN)
        val talk = converse(s, phone, phone.startPake())
        assertIs<HiCarAuthAction.Succeeded>(talk.last)
        assertTrue(s.isFinished)
    }

    /** 免码回连的握手之后马上改成要输码：按输码重新应答。 */
    @Test
    fun repeatedHandshakeMaySwitchToPinPairing() {
        val s = session(pairedStore(), "x")
        assertEquals(AuthMode.TRUSTED_RECONNECT, assertIs<HiCarAuthAction.HandshakeAnswered>(s.feed(bytes(handshake(false))).last()).mode)
        assertEquals(AuthMode.PIN_PAIRING, assertIs<HiCarAuthAction.HandshakeAnswered>(s.feed(bytes(handshake(true))).last()).mode)
    }

    /** 配置没给连接码（有线）时，重发握手沿用第一次生成的码。 */
    @Test
    fun generatedPinStaysTheSameAcrossRepeats() {
        val s = session(InMemoryTrustStore(), "x", pin = null)
        val pins = (1..3).map { assertIs<HiCarAuthAction.HandshakeAnswered>(s.feed(bytes(handshake(true))).last()).pinCode }
        assertTrue(PinCodes.isValid(pins[0]!!))
        assertEquals(List(3) { pins[0] }, pins)
    }

    @Test
    fun repeatedHandshakeLimits() {
        // 认证数据开始交换之后再握手：协议错误，报错里写明两次的标志。
        val afterData = session(InMemoryTrustStore(), Vectors.string(Vectors.scenario("pair"), "carSeed"))
        afterData.feed(bytes(handshake(true)))
        afterData.feed(bytes(phoneEnvelope(3, Vectors.phoneMessages("pair").first())))
        val e = assertFailsWith<MalformedMessageException> { afterData.feed(bytes(handshake(false))) }
        val text = e.message.orEmpty()
        assertTrue(text.contains("上次 has_pin=true") && text.contains("这次 has_pin=false") && text.contains("认证数据已经开始交换"), text)
        assertTrue(afterData.isFinished)
        // 一条连接上最多握手 3 次。
        val many = session(InMemoryTrustStore(), "x")
        repeat(3) { many.feed(bytes(handshake(true))) }
        assertFailsWith<MalformedMessageException> { many.feed(bytes(handshake(true))) }
        // 换了 deviceId。
        val other = session(InMemoryTrustStore(), "x")
        other.feed(bytes(handshake(true)))
        assertFailsWith<MalformedMessageException> { other.feed(bytes(handshake(true, deviceId = "OTHER-PHONE"))) }
    }

    @Test
    fun protocolViolationsThrowAndEndSession() {
        val hiChain = phoneEnvelope(3, Vectors.phoneMessages("pair").first())
        val cases = listOf(
            listOf(hiChain), // 握手之前收到认证数据
            listOf(handshake(true), handshake(true), handshake(true), handshake(true)), // 握手次数超了
            listOf(phoneEnvelope(2, "{}")), // 不支持的 oper_type
            listOf("{\"oper_type\":1,\"data\":{}}"), // data 不是字符串
            listOf("{\"oper_type\":\"1\",\"data\":\"{}\"}"), // oper_type 不是整数
            listOf("{\"oper_type\":1,\"Data\":\"{}\"}"), // 外层字段名区分大小写
            listOf(phoneEnvelope(1, "[1]")), // data 不是对象
            listOf(phoneEnvelope(1, "{\"deviceId\":\"A\",\"has_pin\":\"true\",\"usb_connection\":true}")),
            listOf(phoneEnvelope(1, "{\"deviceId\":\"A\",\"has_pin\":true}")),
            listOf(handshake(true, deviceId = "A".repeat(65))),
            listOf(handshake(true, deviceId = "")),
            listOf(handshake(true), phoneEnvelope(3, "not json")),
            listOf(handshake(true), phoneEnvelope(3, "{\"message\":\"1\"}")),
            listOf("[\"x\"]"), // 不是以 { 开头
        )
        for ((index, messages) in cases.withIndex()) {
            val s = session(InMemoryTrustStore(), "x")
            assertFailsWith<MalformedMessageException>("用例 $index") {
                for (m in messages) s.feed(bytes(m))
            }
            assertTrue(s.isFinished, "用例 $index")
            assertEquals(emptyList(), s.feed(bytes(handshake(true))), "用例 $index")
        }
    }

    @Test
    fun framingLimitsAndEncoding() {
        val tooLong = "{\"data\":\"" + "a".repeat(AuthJsonFramer.MAX_MESSAGE_BYTES) + "\"}"
        assertFailsWith<MalformedMessageException> { session(InMemoryTrustStore(), "x").feed(bytes(tooLong)) }
        // 非法 UTF-8
        assertFailsWith<MalformedMessageException> {
            session(InMemoryTrustStore(), "x").feed(byteArrayOf('{'.code.toByte(), '"'.code.toByte(), 0xC3.toByte(), '"'.code.toByte(), '}'.code.toByte()))
        }
        // 跨包的多字节字符
        val framer = AuthJsonFramer()
        val text = bytes("{\"k\":\"中\"}")
        assertEquals(emptyList(), framer.feed(text, 0, 7))
        assertEquals(listOf("{\"k\":\"中\"}"), framer.feed(text, 7, text.size - 7))
        // 字符串里的括号、转义的引号不影响分帧
        val tricky = bytes("{\"a\":\"}{\\\"]\"}{\"b\":[{}]}")
        assertEquals(listOf("{\"a\":\"}{\\\"]\"}", "{\"b\":[{}]}"), AuthJsonFramer().feed(tricky, 0, tricky.size))
    }

    @Test
    fun connectionClosedReportsPendingData() {
        val s = session(InMemoryTrustStore(), "x")
        s.feed(bytes("{\"oper_type\":1"))
        val closed = assertIs<AuthFailure.ConnectionClosed>(assertIs<HiCarAuthAction.Failed>(s.onConnectionClosed().single()).failure)
        assertTrue(closed.incompleteMessage)
        assertEquals(emptyList(), s.onConnectionClosed())

        val s2 = session(InMemoryTrustStore(), "x")
        s2.feed(bytes(handshake(true)))
        val closed2 = assertIs<AuthFailure.ConnectionClosed>(assertIs<HiCarAuthAction.Failed>(s2.onConnectionClosed().single()).failure)
        assertEquals(false, closed2.incompleteMessage)
    }

    @Test
    fun secretsAreNotPrinted() {
        val s = session(InMemoryTrustStore(), "x")
        val actions = s.feed(bytes(handshake(true)))
        for (a in actions) assertTrue(!a.toString().contains(Synthetic.PIN), a.toString())
        val succeeded = HiCarAuthAction.Succeeded(ByteArray(16) { 0x5A }, Synthetic.PHONE_ID, AuthMode.PIN_PAIRING)
        assertTrue(!succeeded.toString().contains("5a", ignoreCase = true) && !succeeded.toString().contains(Synthetic.PHONE_ID))
        assertTrue(!HiCarAuthConfig(Synthetic.SERVICE, Synthetic.CAR_ID, "v", Synthetic.PIN).toString().contains(Synthetic.PIN))
        assertTrue(!carConfig(Synthetic.PIN).toString().contains(Synthetic.PIN))
    }

    @Test
    fun configValidation() {
        assertFailsWith<IllegalArgumentException> { HiCarAuthConfig(Synthetic.SERVICE, Synthetic.CAR_ID, "v", "12345") }
        assertFailsWith<IllegalArgumentException> { HiCarAuthConfig(Synthetic.SERVICE, Synthetic.CAR_ID, "v", "12345a") }
        assertFailsWith<IllegalArgumentException> { HiCarAuthConfig(Synthetic.SERVICE, Synthetic.CAR_ID, "", null) }
        assertFailsWith<IllegalArgumentException> { HiCarAuthConfig(Synthetic.SERVICE, "中文", "v", null) }
        assertContentEquals("012345".toByteArray(), "012345".toByteArray())
        assertEquals(6, PinCodes.random(SequenceRandom("pin")).length)
        assertEquals(JsonPrimitive("x").toString(), "\"x\"")
    }
}
