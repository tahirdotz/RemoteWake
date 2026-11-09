package com.remotewake

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {

    @Test
    fun `pair codes are 16 readable characters and differ each time`() {
        val code = Protocol.newPairCode()
        assertEquals(16, code.length)
        assertEquals(code, Protocol.normalizeCode(code))
        assertNotEquals(code, Protocol.newPairCode())
    }

    @Test
    fun `a typed code is accepted with dashes, spaces and capitals`() {
        assertEquals("abcdefghjkmnpqrs", Protocol.normalizeCode(" ABCD-efgh jkmn-PQRS "))
        assertEquals("abcd-efgh-jkmn-pqrs", Protocol.formatCode("abcdefghjkmnpqrs"))
    }

    @Test
    fun `a wrong code is rejected`() {
        assertNull(Protocol.normalizeCode("abcd-efgh-jkmn"))            // too short
        assertNull(Protocol.normalizeCode("abcd-efgh-jkmn-pqrs-t"))     // too long
        assertNull(Protocol.normalizeCode("abcd-efgh-jkmn-pqr0"))       // 0 is not in the alphabet
        assertNull(Protocol.normalizeCode(""))
    }

    @Test
    fun `both phones use separate topics for commands and replies`() {
        val code = "abcdefghjkmnpqrs"
        assertEquals("rw-abcdefghjkmnpqrs-a", Protocol.commandTopic(code))
        assertEquals("rw-abcdefghjkmnpqrs-b", Protocol.replyTopic(code))
    }

    @Test
    fun `commands survive the round trip`() {
        for (command in Protocol.Command.entries) {
            val message = Protocol.CommandMessage(command, Protocol.newId())
            assertEquals(message, Protocol.decodeCommand(Protocol.encodeCommand(message)))
        }
        assertEquals("v1|wake|k3m9p2qx", Protocol.encodeCommand(Protocol.CommandMessage(Protocol.Command.WAKE, "k3m9p2qx")))
    }

    @Test
    fun `anything else on the topic is ignored`() {
        assertNull(Protocol.decodeCommand("hello"))
        assertNull(Protocol.decodeCommand("v2|wake|k3m9p2qx"))
        assertNull(Protocol.decodeCommand("v1|reboot|k3m9p2qx"))
        assertNull(Protocol.decodeCommand("v1|wake|short"))
        assertNull(Protocol.decodeCommand("v1|wake|k3m9p2qx|extra"))
        assertNull(Protocol.decodeCommand("v1|awake|k3m9p2qx|87|1|1")) // a reply is not a command
    }

    @Test
    fun `replies survive the round trip`() {
        val reply = Protocol.Reply(Protocol.Reply.Kind.AWAKE, "k3m9p2qx", 87, charging = true, screenOn = true)
        val text = Protocol.encodeReply(reply)
        assertEquals("v1|awake|k3m9p2qx|87|1|1", text)
        assertEquals(reply, Protocol.decodeReply(text))

        val pong = Protocol.Reply(Protocol.Reply.Kind.PONG, "k3m9p2qx", -1, charging = false, screenOn = false)
        assertEquals(pong, Protocol.decodeReply(Protocol.encodeReply(pong)))
    }

    @Test
    fun `broken replies are ignored`() {
        assertNull(Protocol.decodeReply("v1|awake|k3m9p2qx|187|1|1")) // battery over 100
        assertNull(Protocol.decodeReply("v1|awake|k3m9p2qx|87|yes|1"))
        assertNull(Protocol.decodeReply("v1|wake|k3m9p2qx"))
        assertNotNull(Protocol.decodeReply("v1|pong|k3m9p2qx|0|0|0"))
    }

    @Test
    fun `old commands are not acted on`() {
        val now = 1_800_000_000L
        assertTrue(Protocol.isFresh(now, now))
        assertTrue(Protocol.isFresh(now - 60, now))
        assertTrue(Protocol.isFresh(now + 60, now)) // Phone A's clock a minute behind the relay
        assertFalse(Protocol.isFresh(now - 600, now))
        assertFalse(Protocol.isFresh(now + 600, now))
    }

    @Test
    fun `relay address must be https`() {
        assertEquals("https://ntfy.sh", Protocol.normalizeServer(" https://ntfy.sh/ "))
        assertEquals("https://push.example.com/ntfy", Protocol.normalizeServer("https://push.example.com/ntfy"))
        assertNull(Protocol.normalizeServer("http://ntfy.sh"))
        assertNull(Protocol.normalizeServer("ntfy.sh"))
        assertNull(Protocol.normalizeServer("https://"))
    }
}
