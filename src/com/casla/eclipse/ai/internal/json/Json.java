package com.casla.eclipse.ai.internal.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small dependency-free JSON codec for the OpenAI-compatible wire format. */
public final class Json {
    private Json() {}

    public static Object parse(String source) {
        Parser parser = new Parser(source == null ? "" : source);
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("Unexpected trailing JSON content at " + parser.index);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(Object value) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        return List.of();
    }

    public static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public static String stringify(Object value) {
        StringBuilder output = new StringBuilder();
        write(value, output);
        return output.toString();
    }

    private static void write(Object value, StringBuilder output) {
        switch (value) {
            case null -> output.append("null");
            case String text -> writeString(text, output);
            case Number number -> output.append(number);
            case Boolean bool -> output.append(bool);
            case Map<?, ?> map -> {
                output.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) output.append(',');
                    first = false;
                    writeString(String.valueOf(entry.getKey()), output);
                    output.append(':');
                    write(entry.getValue(), output);
                }
                output.append('}');
            }
            case Iterable<?> iterable -> {
                output.append('[');
                boolean first = true;
                for (Object item : iterable) {
                    if (!first) output.append(',');
                    first = false;
                    write(item, output);
                }
                output.append(']');
            }
            default -> writeString(String.valueOf(value), output);
        }
    }

    private static void writeString(String value, StringBuilder output) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) {
                        output.append(String.format("\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }

    private static final class Parser {
        private final String source;
        private int index;

        private Parser(String source) {
            this.source = source;
        }

        private boolean atEnd() {
            return index >= source.length();
        }

        private void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(source.charAt(index))) index++;
        }

        private Object readValue() {
            skipWhitespace();
            if (atEnd()) throw error("Expected JSON value");
            return switch (source.charAt(index)) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (consume('}')) return result;
            while (true) {
                skipWhitespace();
                if (atEnd() || source.charAt(index) != '"') throw error("Expected object key");
                String key = readString();
                skipWhitespace();
                expect(':');
                result.put(key, readValue());
                skipWhitespace();
                if (consume('}')) return result;
                expect(',');
            }
        }

        private List<Object> readArray() {
            expect('[');
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (consume(']')) return result;
            while (true) {
                result.add(readValue());
                skipWhitespace();
                if (consume(']')) return result;
                expect(',');
            }
        }

        private String readString() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (!atEnd()) {
                char character = source.charAt(index++);
                if (character == '"') return result.toString();
                if (character != '\\') {
                    result.append(character);
                    continue;
                }
                if (atEnd()) throw error("Unterminated escape sequence");
                char escaped = source.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> result.append(escaped);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> result.append(readUnicode());
                    default -> throw error("Invalid escape sequence");
                }
            }
            throw error("Unterminated string");
        }

        private char readUnicode() {
            if (index + 4 > source.length()) throw error("Invalid unicode escape");
            try {
                char value = (char) Integer.parseInt(source.substring(index, index + 4), 16);
                index += 4;
                return value;
            } catch (NumberFormatException error) {
                throw error("Invalid unicode escape");
            }
        }

        private Object readNumber() {
            int start = index;
            if (!atEnd() && source.charAt(index) == '-') index++;
            while (!atEnd() && Character.isDigit(source.charAt(index))) index++;
            if (!atEnd() && source.charAt(index) == '.') {
                index++;
                while (!atEnd() && Character.isDigit(source.charAt(index))) index++;
            }
            if (!atEnd() && (source.charAt(index) == 'e' || source.charAt(index) == 'E')) {
                index++;
                if (!atEnd() && (source.charAt(index) == '+' || source.charAt(index) == '-')) index++;
                while (!atEnd() && Character.isDigit(source.charAt(index))) index++;
            }
            if (start == index) throw error("Expected number");
            String number = source.substring(start, index);
            try {
                return number.contains(".") || number.contains("e") || number.contains("E")
                    ? Double.valueOf(number)
                    : Long.valueOf(number);
            } catch (NumberFormatException error) {
                throw error("Invalid number");
            }
        }

        private Object readLiteral(String literal, Object value) {
            if (!source.startsWith(literal, index)) throw error("Invalid literal");
            index += literal.length();
            return value;
        }

        private void expect(char expected) {
            skipWhitespace();
            if (atEnd() || source.charAt(index) != expected) {
                throw error("Expected '" + expected + "'");
            }
            index++;
        }

        private boolean consume(char character) {
            skipWhitespace();
            if (!atEnd() && source.charAt(index) == character) {
                index++;
                return true;
            }
            return false;
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at " + index);
        }
    }
}
