package io.github.workflowtool.application

internal sealed interface JsonValue {
    data class JsonObject(val values: Map<String, JsonValue>) : JsonValue
    data class JsonArray(val values: List<JsonValue>) : JsonValue
    data class JsonString(val value: String) : JsonValue
    data class JsonNumber(val value: String) : JsonValue
    data class JsonBoolean(val value: Boolean) : JsonValue
    data object JsonNull : JsonValue
}

internal fun parseJsonValue(input: String): JsonValue {
    return JsonParser(input).parse()
}

internal fun parseJsonObject(input: String): JsonValue.JsonObject? =
    parseJsonValue(input) as? JsonValue.JsonObject

internal fun JsonValue.asObject(): JsonValue.JsonObject? = this as? JsonValue.JsonObject
internal fun JsonValue.asArray(): JsonValue.JsonArray? = this as? JsonValue.JsonArray
internal fun JsonValue.asString(): String? = (this as? JsonValue.JsonString)?.value
internal fun JsonValue.asInt(): Int? = (this as? JsonValue.JsonNumber)?.value?.toDoubleOrNull()?.toInt()
internal fun JsonValue.asLong(): Long? = (this as? JsonValue.JsonNumber)?.value?.toDoubleOrNull()?.toLong()
internal fun JsonValue.asDouble(): Double? = (this as? JsonValue.JsonNumber)?.value?.toDoubleOrNull()
internal fun JsonValue.asFloat(): Float? = (this as? JsonValue.JsonNumber)?.value?.toFloatOrNull()
internal fun JsonValue.asBoolean(): Boolean? = (this as? JsonValue.JsonBoolean)?.value

internal operator fun JsonValue.JsonObject.get(key: String): JsonValue? = values[key]

private class JsonParser(
    private val input: String
) {
    private var index = 0

    fun parse(): JsonValue {
        skipWhitespace()
        val value = parseValue()
        skipWhitespace()
        require(index == input.length) { "Unexpected trailing content at index $index" }
        return value
    }

    private fun parseValue(): JsonValue {
        skipWhitespace()
        check(index < input.length) { "Unexpected end of input" }
        return when (val char = input[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonValue.JsonString(parseString())
            't' -> parseLiteral("true", JsonValue.JsonBoolean(true))
            'f' -> parseLiteral("false", JsonValue.JsonBoolean(false))
            'n' -> parseLiteral("null", JsonValue.JsonNull)
            '-', in '0'..'9' -> JsonValue.JsonNumber(parseNumber())
            else -> error("Unexpected token '$char' at index $index")
        }
    }

    private fun parseObject(): JsonValue.JsonObject {
        expect('{')
        skipWhitespace()
        if (peek('}')) {
            index += 1
            return JsonValue.JsonObject(emptyMap())
        }

        val values = linkedMapOf<String, JsonValue>()
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            values[key] = parseValue()
            skipWhitespace()
            when {
                peek('}') -> {
                    index += 1
                    return JsonValue.JsonObject(values)
                }
                peek(',') -> index += 1
                else -> error("Expected ',' or '}' at index $index")
            }
        }
    }

    private fun parseArray(): JsonValue.JsonArray {
        expect('[')
        skipWhitespace()
        if (peek(']')) {
            index += 1
            return JsonValue.JsonArray(emptyList())
        }

        val values = mutableListOf<JsonValue>()
        while (true) {
            values += parseValue()
            skipWhitespace()
            when {
                peek(']') -> {
                    index += 1
                    return JsonValue.JsonArray(values)
                }
                peek(',') -> index += 1
                else -> error("Expected ',' or ']' at index $index")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val output = StringBuilder()
        while (index < input.length) {
            val char = input[index++]
            when (char) {
                '"' -> return output.toString()
                '\\' -> {
                    check(index < input.length) { "Unexpected end of input in escape sequence" }
                    output.append(
                        when (val escaped = input[index++]) {
                            '"', '\\', '/' -> escaped
                            'b' -> '\b'
                            'f' -> '\u000C'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'u' -> parseUnicodeEscape()
                            else -> error("Unsupported escape '\\$escaped' at index $index")
                        }
                    )
                }
                else -> output.append(char)
            }
        }
        error("Unterminated string literal")
    }

    private fun parseNumber(): String {
        val start = index
        if (peek('-')) index += 1
        consumeDigits()
        if (peek('.')) {
            index += 1
            consumeDigits()
        }
        if (peek('e') || peek('E')) {
            index += 1
            if (peek('+') || peek('-')) index += 1
            consumeDigits()
        }
        return input.substring(start, index)
    }

    private fun consumeDigits() {
        val start = index
        while (index < input.length && input[index].isDigit()) {
            index += 1
        }
        check(index > start) { "Expected digit at index $index" }
    }

    private fun parseLiteral(expected: String, value: JsonValue): JsonValue {
        check(input.regionMatches(index, expected, 0, expected.length)) {
            "Expected '$expected' at index $index"
        }
        index += expected.length
        return value
    }

    private fun parseUnicodeEscape(): Char {
        check(index + 4 <= input.length) { "Incomplete unicode escape at index $index" }
        val value = input.substring(index, index + 4)
        index += 4
        return value.toInt(16).toChar()
    }

    private fun skipWhitespace() {
        while (index < input.length && input[index].isWhitespace()) {
            index += 1
        }
    }

    private fun expect(char: Char) {
        check(index < input.length && input[index] == char) { "Expected '$char' at index $index" }
        index += 1
    }

    private fun peek(char: Char): Boolean = index < input.length && input[index] == char
}
