package com.remotewake

import java.security.SecureRandom

/**
 * What the two phones say to each other through the relay.
 *
 * Both phones share a secret pairing code. Phone B sends commands to the topic
 * `rw-<code>-a`, and Phone A answers on `rw-<code>-b`. The code is 16 random characters
 * (about 79 bits), so nobody can guess the topic names.
 *
 * Messages are plain text, for example `v1|wake|k3m9p2qx` and
 * `v1|awake|k3m9p2qx|87|1|1` (battery 87%, charging, screen on).
 */
object Protocol {
    private const val VERSION = "v1"

    /** Phone A ignores commands older than this (for example ones sent while it was offline). */
    const val MAX_COMMAND_AGE_SECONDS = 180L

    const val CODE_LENGTH = 16
    private const val ID_LENGTH = 8

    // No 0/o, 1/l/i: easy to read out and type on the other phone.
    private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
    private val random = SecureRandom()

    enum class Command(val wire: String) { WAKE("wake"), PING("ping") }

    data class CommandMessage(val command: Command, val id: String)

    data class Reply(
        val kind: Kind,
        val id: String,
        val batteryPercent: Int,
        val charging: Boolean,
        val screenOn: Boolean,
    ) {
        enum class Kind(val wire: String) { AWAKE("awake"), PONG("pong") }
    }

    fun newPairCode(): String = randomString(CODE_LENGTH)

    fun newId(): String = randomString(ID_LENGTH)

    /** `abcd-efgh-jkmn-pqrs`, for showing on screen. */
    fun formatCode(code: String): String = code.chunked(4).joinToString("-")

    /** Accepts what a person typed (any case, with or without dashes or spaces). Null if it is not a valid code. */
    fun normalizeCode(input: String): String? {
        val code = input.lowercase().filter { it.isLetterOrDigit() }
        return code.takeIf { it.length == CODE_LENGTH && it.all { c -> c in ALPHABET } }
    }

    fun commandTopic(code: String) = "rw-$code-a"

    fun replyTopic(code: String) = "rw-$code-b"

    fun encodeCommand(message: CommandMessage) = "$VERSION|${message.command.wire}|${message.id}"

    fun decodeCommand(text: String): CommandMessage? {
        val parts = text.trim().split('|')
        if (parts.size != 3 || parts[0] != VERSION || !isValidId(parts[2])) return null
        val command = Command.entries.firstOrNull { it.wire == parts[1] } ?: return null
        return CommandMessage(command, parts[2])
    }

    fun encodeReply(reply: Reply) = listOf(
        VERSION,
        reply.kind.wire,
        reply.id,
        reply.batteryPercent.toString(),
        if (reply.charging) "1" else "0",
        if (reply.screenOn) "1" else "0",
    ).joinToString("|")

    fun decodeReply(text: String): Reply? {
        val parts = text.trim().split('|')
        if (parts.size != 6 || parts[0] != VERSION || !isValidId(parts[2])) return null
        val kind = Reply.Kind.entries.firstOrNull { it.wire == parts[1] } ?: return null
        val battery = parts[3].toIntOrNull()?.takeIf { it in -1..100 } ?: return null
        val charging = parts[4].toFlag() ?: return null
        val screenOn = parts[5].toFlag() ?: return null
        return Reply(kind, parts[2], battery, charging, screenOn)
    }

    /** True if a command sent at [sentAtSeconds] should still be acted on at [nowSeconds]. */
    fun isFresh(sentAtSeconds: Long, nowSeconds: Long): Boolean =
        nowSeconds - sentAtSeconds <= MAX_COMMAND_AGE_SECONDS &&
            sentAtSeconds - nowSeconds <= MAX_COMMAND_AGE_SECONDS // tolerate a phone clock that is behind

    /** Relay addresses must be HTTPS, with no trailing slash. Null if [input] is not usable. */
    fun normalizeServer(input: String): String? {
        val server = input.trim().trimEnd('/')
        if (!server.startsWith("https://")) return null
        val host = server.removePrefix("https://")
        return server.takeIf { host.isNotEmpty() && host.none { it.isWhitespace() || it == '?' || it == '#' } }
    }

    private fun isValidId(id: String) = id.length == ID_LENGTH && id.all { it in ALPHABET }

    private fun String.toFlag(): Boolean? = when (this) {
        "1" -> true
        "0" -> false
        else -> null
    }

    private fun randomString(length: Int) =
        String(CharArray(length) { ALPHABET[random.nextInt(ALPHABET.length)] })
}
