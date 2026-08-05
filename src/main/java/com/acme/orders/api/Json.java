package com.acme.orders.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON writer and parser — enough for this service's request and response shapes, and
 * nothing more.
 *
 * <p>Hand-rolling this rather than pulling in Jackson is a deliberate trade for a service of this
 * size: it keeps the build dependency-free and reproducible, and the whole codec is short enough
 * to audit in one sitting. The limits are stated plainly rather than discovered later:
 *
 * <ul>
 *   <li>Numbers parse to {@link Long} when integral and {@link Double} otherwise.</li>
 *   <li>No support for comments, trailing commas, or duplicate keys (last one wins).</li>
 *   <li>Writing accepts Map, List, String, Number, Boolean and null; anything else is written as
 *       its {@code toString()} in quotes, which keeps output valid rather than throwing mid-stream
 *       and truncating a response.</li>
 * </ul>
 *
 * <p>If the payloads ever grow beyond this, replace it with a real library — do not extend it.
 */
public final class Json {

    /** Thrown on malformed input, with the offset so a bad request can be reported precisely. */
    public static class ParseException extends RuntimeException {
        public ParseException(String message, int position) {
            super(message + " at position " + position);
        }
    }

    private Json() {
    }

    // -- writing -------------------------------------------------------------

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(value, out);
        return out.toString();
    }

    private static void writeValue(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            writeString(s, out);
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(String.valueOf(entry.getKey()), out);
                out.append(':');
                writeValue(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof Iterable<?> items) {
            out.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeValue(item, out);
            }
            out.append(']');
        } else {
            writeString(String.valueOf(value), out);
        }
    }

    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    // -- parsing -------------------------------------------------------------

    /** Parses a JSON document. Returns Map, List, String, Long, Double, Boolean or null. */
    public static Object parse(String text) {
        if (text == null || text.isBlank()) {
            throw new ParseException("empty document", 0);
        }
        Parser parser = new Parser(text);
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new ParseException("unexpected trailing content", parser.position);
        }
        return value;
    }

    /** Parses and casts to a map, for request bodies. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new ParseException("expected a JSON object", 0);
        }
        return (Map<String, Object>) value;
    }

    private static final class Parser {
        private final String text;
        private int position;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return position >= text.length();
        }

        void skipWhitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }

        Object readValue() {
            skipWhitespace();
            if (atEnd()) {
                throw new ParseException("unexpected end of input", position);
            }
            char c = text.charAt(position);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't', 'f' -> readBoolean();
                case 'n' -> readNull();
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            Map<String, Object> out = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (peek() == '}') {
                position++;
                return out;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                out.put(key, readValue());
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return out;
                }
                if (c != ',') {
                    throw new ParseException("expected ',' or '}' but found '" + c + "'", position - 1);
                }
            }
        }

        private List<Object> readArray() {
            List<Object> out = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (peek() == ']') {
                position++;
                return out;
            }
            while (true) {
                out.add(readValue());
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return out;
                }
                if (c != ',') {
                    throw new ParseException("expected ',' or ']' but found '" + c + "'", position - 1);
                }
            }
        }

        private String readString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new ParseException("unterminated string", position);
                }
                char c = text.charAt(position++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char escape = text.charAt(position++);
                switch (escape) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                        position += 4;
                    }
                    default -> throw new ParseException("invalid escape '\\" + escape + "'", position - 1);
                }
            }
        }

        private Object readNumber() {
            int start = position;
            if (peek() == '-' || peek() == '+') {
                position++;
            }
            boolean fractional = false;
            while (!atEnd()) {
                char c = text.charAt(position);
                if (Character.isDigit(c)) {
                    position++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') {
                    fractional = fractional || c == '.' || c == 'e' || c == 'E';
                    position++;
                } else {
                    break;
                }
            }
            String literal = text.substring(start, position);
            if (literal.isEmpty()) {
                throw new ParseException("expected a value", start);
            }
            try {
                return fractional ? (Object) Double.parseDouble(literal) : (Object) Long.parseLong(literal);
            } catch (NumberFormatException e) {
                throw new ParseException("invalid number '" + literal + "'", start);
            }
        }

        private Boolean readBoolean() {
            if (text.startsWith("true", position)) {
                position += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", position)) {
                position += 5;
                return Boolean.FALSE;
            }
            throw new ParseException("invalid literal", position);
        }

        private Object readNull() {
            if (text.startsWith("null", position)) {
                position += 4;
                return null;
            }
            throw new ParseException("invalid literal", position);
        }

        private char peek() {
            return atEnd() ? '\0' : text.charAt(position);
        }

        private char next() {
            if (atEnd()) {
                throw new ParseException("unexpected end of input", position);
            }
            return text.charAt(position++);
        }

        private void expect(char expected) {
            char c = next();
            if (c != expected) {
                throw new ParseException("expected '" + expected + "' but found '" + c + "'", position - 1);
            }
        }
    }
}
