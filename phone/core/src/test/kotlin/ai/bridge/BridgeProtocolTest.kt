package ai.bridge

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails

class BridgeProtocolTest {
    @Test
    fun frameRoundTripAndBadCrc() {
        val frame = BridgeFrame(BridgeType.PING, 42, byteArrayOf(1, 2, 3))
        val encoded = BridgeProtocol.encode(frame)
        val decoded = BridgeProtocol.decode(encoded)
        assertEquals(frame.type, decoded.type)
        assertEquals(frame.sequenceId, decoded.sequenceId)
        assertContentEquals(frame.payload, decoded.payload)
        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 1).toByte()
        assertFails { BridgeProtocol.decode(encoded) }
    }

    @Test
    fun actionBatchIsBoundedAndTyped() {
        val payload = ActionValidator.encodeBatch(listOf(AgentAction.Key(0x28), AgentAction.Text("OK")))
        assertEquals(2, payload[0].toInt())
        assertFails { ActionValidator.encodeBatch(listOf(AgentAction.Text("not ASCII: ё"))) }
        assertFails { ActionValidator.validate(listOf(AgentAction.MouseMove(3000, 0))) }
        assertFails { ActionValidator.validate((1..11).map { AgentAction.Key(40) }, maxActions = 10) }
    }

    @Test
    fun rpcAppDataUsesDelimitedProtocolBuffer() {
        val encoded = RpcWire.encodeDataExchange(7, byteArrayOf(0x46, 0x42))
        val message = RpcWire.parseDelimited(encoded).single()
        val top = RpcWire.parseFields(message)
        assertEquals(7L, RpcWire.decodeVarint(top.first { it.first == 1 }.second))
        val appData = RpcWire.parseFields(top.first { it.first == RpcWire.RPC_APP_DATA_EXCHANGE }.second)
        assertContentEquals(byteArrayOf(0x46, 0x42), appData.first { it.first == 1 }.second)
    }

    @Test
    fun bleFragmentsAndReassemblesRpcMessagesAcrossBoundaries() {
        val first = RpcWire.encodeDataExchange(7, byteArrayOf(1, 2, 3))
        val second = RpcWire.encodeDataExchange(8, ByteArray(80) { it.toByte() })
        val combined = first + second
        val fragments = BlePacketFragmenter.fragment(combined, 11)
        val assembler = RpcPacketAssembler()
        val output = fragments.flatMap(assembler::append)
        assertEquals(2, output.size)
        assertEquals(7L, RpcWire.decodeVarint(RpcWire.parseFields(output[0]).first { it.first == 1 }.second))
        assertEquals(8L, RpcWire.decodeVarint(RpcWire.parseFields(output[1]).first { it.first == 1 }.second))
        assertFails { BlePacketFragmenter.fragment(combined, 0) }
    }

    @Test
    fun rpcAssemblerRejectsOversizedAndMalformedLengths() {
        val assembler = RpcPacketAssembler(maxMessageBytes = 10)
        assertFails { assembler.append(byteArrayOf(11)) }
        assertFails { assembler.append(byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte())) }
    }

    @Test
    fun safetyPolicyRequestsOwnerForAuthenticationAndRestrictsActions() {
        assertEquals(true, SafetyPolicy.requiresHumanInteraction("Windows sign in is required"))
        assertEquals(false, SafetyPolicy.requiresHumanInteraction("Settings app is open"))
        assertFails { SafetyPolicy.validate(listOf(AgentAction.Text("x".repeat(97)))) }
        assertFails { SafetyPolicy.validate(listOf(AgentAction.KeyDown(40))) }
        SafetyPolicy.validate(listOf(AgentAction.KeyDown(40), AgentAction.KeyUp(40)))
    }

    @Test
    fun agentStateMachineAllowsLoopAndEmergencyStopButRejectsUnsafeTransitions() {
        val machine = AgentStateMachine(AgentState.READY)
        machine.transition(AgentState.REQUESTING_SCREENSHOT)
        machine.transition(AgentState.WAITING_SCREENSHOT)
        machine.transition(AgentState.THINKING)
        machine.transition(AgentState.VALIDATING)
        machine.transition(AgentState.SENDING_ACTIONS)
        machine.transition(AgentState.EXECUTING)
        machine.transition(AgentState.WAITING_RESULT)
        machine.transition(AgentState.STOPPED)
        assertEquals(AgentState.STOPPED, machine.current())
        assertFails { machine.transition(AgentState.EXECUTING) }
    }
}
