package com.tapscene.hosting;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Bounded JSON and RFC 8785 serialization for this profile's deliberately restricted numbers. */
final class HostedJson {
    static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    static final int MAX_DEPTH = 32, MAX_NODES = 65536, MAX_STRING = 614400, MAX_CHARS = 2097152;
    private final String input;
    private int at, nodes, chars;
    private HostedJson(String input) { this.input = input; }

    static Object parse(byte[] bytes, int maxBytes) {
        require(bytes != null && bytes.length <= maxBytes, "JSON exceeds byte budget");
        String value;
        try {
            value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) { throw new IllegalArgumentException("Invalid UTF-8", e); }
        HostedJson parser = new HostedJson(value);
        Object result = parser.value(0);
        parser.space();
        require(parser.at == value.length(), "Trailing JSON content");
        return result;
    }
    private Object value(int depth) {
        require(depth <= MAX_DEPTH && ++nodes <= MAX_NODES, "JSON structure exceeds budget");
        space(); require(at < input.length(), "Truncated JSON");
        char c = input.charAt(at);
        if (c == '{') {
            at++; Map<String, Object> result = new LinkedHashMap<>(); space();
            if (take('}')) return result;
            do {
                space(); require(at < input.length() && input.charAt(at) == '"', "Expected object key");
                String key = string(); require(!result.containsKey(key), "Duplicate JSON key");
                require(result.size() < 64, "Too many object fields");
                space(); require(take(':'), "Expected colon"); result.put(key, value(depth + 1)); space();
                if (take('}')) return result;
                require(take(','), "Expected comma");
            } while (true);
        }
        if (c == '[') {
            at++; List<Object> result = new ArrayList<>(); space();
            if (take(']')) return result;
            do {
                require(result.size() < 4096, "Too many array items");
                result.add(value(depth + 1)); space(); if (take(']')) return result;
                require(take(','), "Expected comma");
            } while (true);
        }
        if (c == '"') return string();
        if (c == 't') { literal("true"); return Boolean.TRUE; }
        if (c == 'f') { literal("false"); return Boolean.FALSE; }
        if (c == 'n') { literal("null"); return null; }
        int start = at;
        take('-'); require(at < input.length(), "Invalid JSON number");
        if (!take('0')) {
            require(input.charAt(at) >= '1' && input.charAt(at) <= '9', "Invalid JSON value");
            while (at < input.length() && digit(input.charAt(at))) at++;
        }
        if (take('.')) {
            int begin = at;
            while (at < input.length() && digit(input.charAt(at))) at++;
            require(at > begin && at - begin <= 6, "Only up to six decimal places supported");
        }
        require(at - start <= 24, "Number exceeds supported precision");
        require(at == input.length() || input.charAt(at) != 'e' && input.charAt(at) != 'E',
                "Exponent notation is outside the static profile");
        BigDecimal number;
        try { number = new BigDecimal(input.substring(start, at)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid JSON number", e); }
        require(number.abs().compareTo(BigDecimal.valueOf(MAX_SAFE_INTEGER)) <= 0, "Unsafe JSON integer");
        require(number.scale() <= 0 || number.abs().compareTo(BigDecimal.ONE) <= 0,
                "Fractional values are only supported for normalized coordinates");
        return number;
    }
    private String string() {
        require(take('"'), "Expected string"); StringBuilder out = new StringBuilder(); boolean ended = false;
        while (at < input.length()) {
            char c = input.charAt(at++);
            if (c == '"') { ended = true; break; }
            require(c >= 0x20, "Unescaped JSON control character");
            if (c == '\\') {
                require(at < input.length(), "Truncated escape"); c = input.charAt(at++);
                switch (c) {
                    case '"': case '\\': case '/': break;
                    case 'b': c = '\b'; break;
                    case 'f': c = '\f'; break;
                    case 'n': c = '\n'; break;
                    case 'r': c = '\r'; break;
                    case 't': c = '\t'; break;
                    case 'u':
                        require(at + 4 <= input.length(), "Truncated Unicode escape"); int value = 0;
                        for (int i = 0; i < 4; i++) {
                            char h = input.charAt(at++);
                            int d = h >= '0' && h <= '9' ? h - '0' : h >= 'a' && h <= 'f' ? h - 'a' + 10
                                    : h >= 'A' && h <= 'F' ? h - 'A' + 10 : -1;
                            require(d >= 0, "Invalid Unicode escape"); value = value * 16 + d;
                        }
                        c = (char) value; break;
                    default: throw new IllegalArgumentException("Invalid JSON escape");
                }
            }
            out.append(c); require(out.length() <= MAX_STRING, "JSON string exceeds budget");
        }
        require(ended, "Unterminated JSON string"); String result = out.toString(); validUnicode(result);
        chars += result.length(); require(chars <= MAX_CHARS, "JSON text exceeds budget"); return result;
    }
    private void literal(String text) { require(input.startsWith(text, at), "Invalid JSON literal"); at += text.length(); }
    private static boolean digit(char c) { return c >= '0' && c <= '9'; }
    private boolean take(char c) { if (at < input.length() && input.charAt(at) == c) { at++; return true; } return false; }
    private void space() { while (at < input.length() && (input.charAt(at) == ' ' || input.charAt(at) == '\n'
            || input.charAt(at) == '\r' || input.charAt(at) == '\t')) at++; }
    static void validUnicode(String value) {
        require(value != null, "Missing string");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                require(i + 1 < value.length() && Character.isLowSurrogate(value.charAt(++i)), "Unpaired Unicode surrogate");
            } else require(!Character.isLowSurrogate(c), "Unpaired Unicode surrogate");
        }
    }
    static byte[] canonical(Object value) {
        StringBuilder out = new StringBuilder(); append(out, value); return out.toString().getBytes(StandardCharsets.UTF_8);
    }
    @SuppressWarnings("unchecked")
    private static void append(StringBuilder out, Object value) {
        if (value == null) out.append("null");
        else if (value instanceof String) quote(out, (String) value);
        else if (value instanceof Boolean || value instanceof Long || value instanceof Integer) out.append(value);
        else if (value instanceof BigDecimal) {
            BigDecimal n = ((BigDecimal) value).stripTrailingZeros();
            out.append(n.signum() == 0 ? "0" : n.toPlainString());
        } else if (value instanceof List) {
            out.append('['); boolean first = true;
            for (Object item : (List<?>) value) { if (!first) out.append(','); first = false; append(out, item); }
            out.append(']');
        } else if (value instanceof Map) {
            out.append('{'); boolean first = true;
            // Java String ordering is UTF-16 code unit order, as required by RFC 8785.
            for (Map.Entry<String, Object> item : new TreeMap<>((Map<String, Object>) value).entrySet()) {
                if (!first) out.append(','); first = false; quote(out, item.getKey()); out.append(':'); append(out, item.getValue());
            }
            out.append('}');
        } else throw new IllegalArgumentException("Unsupported JSON value");
    }
    private static void quote(StringBuilder out, String value) {
        validUnicode(value); out.append('"');
        final char[] hex = "0123456789abcdef".toCharArray();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\t': out.append("\\t"); break;
                case '\n': out.append("\\n"); break;
                case '\f': out.append("\\f"); break;
                case '\r': out.append("\\r"); break;
                default:
                    if (c < 0x20) out.append("\\u00").append(hex[c >> 4]).append(hex[c & 15]);
                    else out.append(c);
            }
        }
        out.append('"');
    }
    static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
