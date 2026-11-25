package com.jankxia.hyundaic.core.auth

import com.jankxia.hyundaic.core.common.MalformedMessageException
import com.jankxia.hyundaic.core.common.hexToBytes
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 与认证库车机角色（C 实现）对照：注入同一随机数序列、收到同样的手机消息时，消息的字段顺序、空白、
 * 十六进制大小写、结果码序列与错误码必须相同。向量见 accessory-vectors.json。
 *
 * 关于「逐字节」：向量里的挑战、salt、epk、密文与 kcf 都取决于双方实际取到的随机数，只有当实现按同样的
 * 顺序、同样的长度从 `SequenceRandom` 取数时才可能逐字节相同。所以这里分三层校验：
 * 1. 取随机数的**次数与顺序**：`random.drawn` 的断言与消息长度一起钉住（取值不同不影响）；
 * 2. 消息**结构**：用 [shapeOf] 把随机材料换成长度占位后逐字段对照；
 * 3. **不含随机材料**的消息（错误通知、字段错误的应答）仍然逐字节相同，并且错误码必须一致。
 */
class CVectorsTest {

    private class Run(val outputs: List<String>, val results: List<Int>, val actions: List<AccessoryAction>)

    private fun replay(auth: AccessoryAuthenticator, inputs: List<String>): Run {
        val all = inputs.flatMap { auth.onMessage(it) }
        return Run(all.sent(), all.resultCodes(), all)
    }

    /** 用测试手机真正配对一次，返回有记录的信任库（后面回连与「已配对」探针要用）。 */
    private fun pairedStore(): InMemoryTrustStore {
        val store = InMemoryTrustStore()
        val car = KotlinAccessoryAuthenticator(carConfig(Synthetic.PIN), store, java.security.SecureRandom())
        val phone = TestPhone(Synthetic.SERVICE, Synthetic.PHONE_ID, Synthetic.CAR_ID, Synthetic.PIN,
            java.security.SecureRandom(), TestPhone.Store())
        var toCar: String? = phone.startPake()
        while (toCar != null && !car.isFinished) {
            val step = car.onMessage(toCar)
            toCar = null
            for (message in step.sent()) phone.onMessage(message)?.let { toCar = it }
        }
        assertEquals(1, store.snapshot().peers.size, "测试手机配对应当成功")
        return store
    }

    private fun seed(scenario: String) = Vectors.string(Vectors.scenario(scenario), "carSeed")

    @Test
    fun pinPairingMatchesCShape() {
        val store = InMemoryTrustStore()
        val random = SequenceRandom(seed("pair"))
        val auth = KotlinAccessoryAuthenticator(carConfig(Synthetic.PIN), store, random)
        assertEquals(false, auth.peerTrusted)
        val expected = Vectors.carMessages("pair")
        // 只喂第一条：C 侧后面的确认值（kcf）绑定的是 C 侧自己那组挑战，与我们的随机数不配套，
        // 必然在确认处按 VERIFY_PROOF_FAILED 分叉，所以逐字段对照只能做到首条应答。
        val run = replay(auth, Vectors.phoneMessages("pair").take(1))
        assertEquals(1, run.outputs.size)
        assertEquals(shapeOf(expected[0]), shapeOf(run.outputs[0]))
        assertEquals(expected[0].length, run.outputs[0].length)
        assertEquals(953, run.outputs[0].length)
        assertEquals(listOf(2), run.results)
        // 取随机数：长期身份 32 + salt 16 + 挑战 16 + 私有指数 32（还没走到交换那步的 nonce）
        assertEquals(96, random.drawn)
        assertEquals(0, store.snapshot().peers.size, "还没响应确认值，不该写存储")
    }

    @Test
    fun feedingWholeCTrameEndsAtProofCheck() {
        // 把 C 侧整段手机消息喂完：确认值与我们的挑战对不上，按 VERIFY_PROOF_FAILED 结束，存储不动。
        val store = InMemoryTrustStore()
        val auth = KotlinAccessoryAuthenticator(carConfig(Synthetic.PIN), store, SequenceRandom(seed("pair")))
        val run = replay(auth, Vectors.phoneMessages("pair"))
        assertEquals(2, run.outputs.size)
        assertEquals(listOf(2, 1), run.results)
        assertEquals("{\"message\":32896, \"payload\":{\"errorCode\":17}}", run.outputs.last())
        assertEquals(HcError.VERIFY_PROOF_FAILED, assertIs<AuthFailure.Local>(assertIs<AccessoryAction.Failed>(run.actions.last()).failure).error)
        assertEquals(0, store.snapshot().peers.size)
    }

    @Test
    fun stsReconnectMatchesCShapeUpToFirstResponse() {
        // 模拟进程重启：信任库编码再解码；配对用测试手机真正做一次，C 侧向量只用来对照首条应答。
        val restored = InMemoryTrustStore(TrustStoreCodec.decode(TrustStoreCodec.encode(pairedStore().snapshot())))
        val random = SequenceRandom(seed("sts"))
        val auth = KotlinAccessoryAuthenticator(carConfig(pin = null), restored, random)
        assertEquals(true, auth.peerTrusted)
        val expected = Vectors.carMessages("sts")
        val run = replay(auth, Vectors.phoneMessages("sts").take(1))
        assertEquals(1, run.outputs.size)
        assertEquals(shapeOf(expected[0]), shapeOf(run.outputs[0]))
        assertEquals(expected[0].length, run.outputs[0].length)
        assertEquals(623, run.outputs[0].length)
        assertEquals(listOf(2), run.results)
        // 取随机数：X25519 私有 32 + salt 16 + 密文 nonce 12 + 挑战 16（已有长期身份，不再生成）
        assertEquals(76, random.drawn)
    }

    @Test
    fun wrongPinMatchesC() {
        val store = InMemoryTrustStore()
        val auth = KotlinAccessoryAuthenticator(carConfig(Synthetic.PIN), store, SequenceRandom(seed("wrongPin")))
        val expected = Vectors.carMessages("wrongPin")
        val run = replay(auth, Vectors.phoneMessages("wrongPin"))
        assertEquals(shapeOf(expected[0]), shapeOf(run.outputs.first()))
        assertEquals(expected[0].length, run.outputs.first().length)
        // 我们的确认值与 C 侧那组挑战不配套，在确认处就失败；错误通知与错误码必须和原库一致。
        assertEquals(listOf(2, 1), run.results)
        assertEquals("{\"message\":32896, \"payload\":{\"errorCode\":17}}", run.outputs.last())
        val failed = assertIs<AccessoryAction.Failed>(run.actions.last())
        assertEquals(HcError.VERIFY_PROOF_FAILED, assertIs<AuthFailure.Local>(failed.failure).error)
        // 失败的会话不改动存储
        assertEquals(0, store.snapshot().peers.size)
        assertEquals(0, store.snapshot().identities.size)
    }

    @Test
    fun errorPathsMatchC() {
        val paired = pairedStore().snapshot()
        var exact = 0
        var shapeOnly = 0
        for (probe in Vectors.probes) {
            val name = Vectors.string(probe, "name")
            val pin = probe["carPin"].takeIf { it != null && it !is JsonNull }?.jsonPrimitive?.content
            val store = InMemoryTrustStore(if (Vectors.string(probe, "store") == "paired") paired else TrustStoreSnapshot.EMPTY)
            val auth = KotlinAccessoryAuthenticator(carConfig(pin), store, SequenceRandom(Vectors.string(probe, "carSeed")))
            val inputs = Vectors.strings(probe["inputs"])
            val errors = probe["errors"]!!.jsonArray.map { it.jsonObject }
            val inputError = errors.any { Vectors.string(it, "source") == "input" }
            val outputs = ArrayList<String>()
            val actions = ArrayList<AccessoryAction>()
            for (input in inputs) {
                if (inputError) {
                    assertFailsWith<MalformedMessageException>(name) { auth.onMessage(input) }
                    break
                }
                val step = auth.onMessage(input)
                actions += step
                outputs += step.sent()
            }
            val expected = Vectors.strings(probe["outputs"])
            // 首条应答（如果发过）的结构：字段名、顺序、空白、各段长度
            assertEquals(
                expected.firstOrNull()?.let { shapeOf(it) },
                outputs.firstOrNull()?.let { shapeOf(it) },
                "$name 首条应答结构",
            )
            if (outputs.none { hasRandomMaterial(it) }) {
                // 不含随机材料：逐字节相同，结果码和错误码也必须一致
                assertEquals(expected, outputs, name)
                assertEquals(Vectors.ints(probe, "results"), actions.resultCodes(), name)
                val peerError = errors.firstOrNull { Vectors.string(it, "source") == "peer_inform" }
                if (peerError != null) {
                    val failure = assertIs<AuthFailure.Peer>(assertIs<AccessoryAction.Failed>(actions.last()).failure, name)
                    assertEquals(Vectors.string(peerError, "code").toInt(), failure.errorCode, name)
                }
                exact++
            } else {
                // 后续步骤要拿双方随机数配对（例如 KCF 绑定的是对端看到的那组挑战），取值不同必然在那一步分叉，
                // 所以只对照结构，不再比对结果码与具体错误码。
                shapeOnly++
            }
        }
        assertEquals(24, exact, "不含随机材料的探针数")
        assertEquals(14, shapeOnly, "含随机材料的探针数")
    }

    @Test
    fun localFailureCodesMatchInformMessage() {
        // 每个本地失败的探针：Failed 里的错误码与发出的 0x8080 通知一致。
        // 只取不含随机材料的探针：含随机材料的探针会在配对确认处提前分叉，走不到 C 侧那一步。
        val paired = pairedStore().snapshot()
        var checked = 0
        for (probe in Vectors.probes) {
            val outputs = Vectors.strings(probe["outputs"])
            if (outputs.any { hasRandomMaterial(it) }) continue
            val inform = outputs.lastOrNull()?.takeIf { it.startsWith("{\"message\":32896") } ?: continue
            val pin = probe["carPin"].takeIf { it != null && it !is JsonNull }?.jsonPrimitive?.content
            val store = InMemoryTrustStore(if (Vectors.string(probe, "store") == "paired") paired else TrustStoreSnapshot.EMPTY)
            val auth = KotlinAccessoryAuthenticator(carConfig(pin), store, SequenceRandom(Vectors.string(probe, "carSeed")))
            val actions = Vectors.strings(probe["inputs"]).flatMap { auth.onMessage(it) }
            val failure = assertIs<AuthFailure.Local>(assertIs<AccessoryAction.Failed>(actions.last()).failure)
            val code = Regex("\"errorCode\":(\\d+)").find(inform)!!.groupValues[1].toInt()
            assertEquals(code, failure.error.code, Vectors.string(probe, "name"))
            checked++
        }
        assertEquals(20, checked)
    }

    @Test
    fun testPhoneStartMessagesMatchCShape() {
        // 测试用手机角色发出的消息格式也照 C 客户端：起始消息完全一致，第二条起只对照结构。
        val pair = Vectors.scenario("pair")
        val phone = TestPhone(Synthetic.SERVICE, Synthetic.PHONE_ID, Synthetic.CAR_ID, Synthetic.PIN,
            SequenceRandom(Vectors.string(pair, "phoneSeed")), TestPhone.Store())
        val expected = Vectors.phoneMessages("pair")
        val sent = mutableListOf(phone.startPake())
        assertEquals(expected[0], sent[0])
        // 只喂车机的第一条（PAKE 应答）：后面那条确认值与我们的随机数不配套，必然在确认处停下。
        phone.onMessage(Vectors.carMessages("pair")[0])?.let { sent += it }
        assertEquals(2, sent.size)
        assertEquals(shapeOf(expected[1]), shapeOf(sent[1]))
        assertEquals(expected[1].length, sent[1].length)
    }

    @Test
    fun vectorsUseSyntheticValuesOnly() {
        val root = Vectors.root
        assertEquals(Synthetic.CAR_ID, Vectors.string(root, "carAuthId"))
        assertEquals(Synthetic.PHONE_ID, Vectors.string(root, "phoneAuthId"))
        assertEquals(Synthetic.PIN, Vectors.string(root, "pin"))
        val service = root["service"]!!.jsonObject
        assertEquals("org.example.auth", Vectors.string(service, "packageName"))
        assertEquals("EXAMPLE", Vectors.string(service, "serviceType"))
        assertNotNull(Vectors.string(Vectors.scenario("pair"), "sessionKey").hexToBytes())
        assertNull(root["realDevice"])
    }
}

