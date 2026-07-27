package com.kangaroo.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A complete JSON model and parser in one file, with no dependencies.
 *
 * <p>Kangaroo ships zero runtime dependencies — the JDK is the whole stack — so this exists instead
 * of a JSON library. It is not a compromise: modelling JSON as a {@code sealed} interface of records
 * makes reading it an exhaustive {@code switch} with pattern matching, which is both shorter and
 * safer than the reflective object-mapping the usual libraries do. A malformed field is a compile-
 * time-shaped problem here, not a runtime {@code ClassCastException}.
 *
 * <p>The parser is deliberately strict: it rejects trailing commas, unquoted keys, comments and
 * NaN. Every network-facing parser in a clinical tool should refuse to guess.
 */
public sealed interface Json
        permits Json.Obj, Json.Arr, Json.Str, Json.Num, Json.Bool, Json.Nul {

    /**
     * A JSON object, with its field order preserved.
     *
     * <p>The copy is a {@link LinkedHashMap} rather than {@link Map#copyOf}, and that is
     * load-bearing rather than stylistic. {@code Map.copyOf} returns an immutable map whose
     * iteration order is deliberately unspecified — and in practice randomised per JVM run — so an
     * object parsed and re-serialised through it comes back with its fields in a different order
     * and therefore different bytes.
     *
     * <p>Every encounter record in this system is signed over its serialised bytes, so a
     * serialisation that is not stable is a signature that does not verify. Insertion order here is
     * what makes the record canonical, the signature checkable, and the stored JSON diffable.
     */
    record Obj(Map<String, Json> fields) implements Json {
        public Obj {
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }

        public Optional<Json> field(String name) { return Optional.ofNullable(fields.get(name)); }

        public String str(String name, String fallback) {
            return field(name).flatMap(Json::asString).orElse(fallback);
        }

        public double num(String name, double fallback) {
            return field(name).flatMap(Json::asDouble).orElse(fallback);
        }

        public int intAt(String name, int fallback) {
            return (int) Math.round(num(name, fallback));
        }

        public boolean bool(String name, boolean fallback) {
            return field(name).flatMap(Json::asBoolean).orElse(fallback);
        }

        public List<Json> array(String name) {
            return field(name)
                    .filter(j -> j instanceof Arr)
                    .map(j -> ((Arr) j).items())
                    .orElse(List.of());
        }

        public Optional<Obj> obj(String name) {
            return field(name).filter(j -> j instanceof Obj).map(j -> (Obj) j);
        }
    }

    /** A JSON array. */
    record Arr(List<Json> items) implements Json {
        public Arr { items = List.copyOf(items); }
    }

    record Str(String value) implements Json {}

    record Num(double value) implements Json {}

    record Bool(boolean value) implements Json {}

    /** JSON null. Named {@code Nul} because {@code Null} reads like a mistake at every use site. */
    record Nul() implements Json {
        public static final Nul INSTANCE = new Nul();
    }

    // ---------------------------------------------------------------- accessors

    default Optional<String> asString() {
        return this instanceof Str s ? Optional.of(s.value()) : Optional.empty();
    }

    default Optional<Double> asDouble() {
        return this instanceof Num n ? Optional.of(n.value()) : Optional.empty();
    }

    default Optional<Boolean> asBoolean() {
        return this instanceof Bool b ? Optional.of(b.value()) : Optional.empty();
    }

    default Optional<Obj> asObj() {
        return this instanceof Obj o ? Optional.of(o) : Optional.empty();
    }

    // ---------------------------------------------------------------- construction

    static Json of(String s) { return s == null ? Nul.INSTANCE : new Str(s); }

    static Json of(double d) { return new Num(d); }

    static Json of(int i) { return new Num(i); }

    static Json of(long l) { return new Num(l); }

    static Json of(boolean b) { return new Bool(b); }

    static Json nul() { return Nul.INSTANCE; }

    static Arr arr(List<Json> items) { return new Arr(items); }

    static Arr ofStrings(List<String> items) {
        return new Arr(items.stream().map(Json::of).toList());
    }

    /** Fluent object builder, preserving insertion order. */
    static ObjBuilder obj() { return new ObjBuilder(); }

    final class ObjBuilder {
        private final Map<String, Json> fields = new LinkedHashMap<>();

        public ObjBuilder put(String k, Json v) { fields.put(k, v == null ? Nul.INSTANCE : v); return this; }
        public ObjBuilder put(String k, String v) { return put(k, Json.of(v)); }
        public ObjBuilder put(String k, double v) { return put(k, Json.of(v)); }
        public ObjBuilder put(String k, int v) { return put(k, Json.of(v)); }
        public ObjBuilder put(String k, long v) { return put(k, Json.of(v)); }
        public ObjBuilder put(String k, boolean v) { return put(k, Json.of(v)); }
        public ObjBuilder put(String k, List<Json> v) { return put(k, new Arr(v)); }
        public ObjBuilder putStrings(String k, List<String> v) { return put(k, ofStrings(v)); }

        /** Adds the entry only when the value is non-null and, for strings, non-blank. */
        public ObjBuilder putIfPresent(String k, String v) {
            if (v != null && !v.isBlank()) put(k, Json.of(v));
            return this;
        }

        public Obj build() { return new Obj(new LinkedHashMap<>(fields)); }

        @Override public String toString() { return build().toString(); }
    }

    // ---------------------------------------------------------------- writing

    /**
     * Serialise. Exhaustive over the sealed hierarchy: adding a JSON kind without teaching the
     * writer about it will not compile.
     */
    default String write() {
        StringBuilder sb = new StringBuilder();
        writeTo(sb);
        return sb.toString();
    }

    private void writeTo(StringBuilder sb) {
        switch (this) {
            case Nul _ -> sb.append("null");
            case Bool b -> sb.append(b.value());
            case Num n -> {
                double v = n.value();
                if (!Double.isFinite(v)) {
                    sb.append("null");
                } else if (v == Math.rint(v) && Math.abs(v) < 1e15) {
                    sb.append((long) v);
                } else {
                    sb.append(v);
                }
            }
            case Str s -> escape(s.value(), sb);
            case Arr a -> {
                sb.append('[');
                boolean first = true;
                for (Json item : a.items()) {
                    if (!first) sb.append(',');
                    first = false;
                    item.writeTo(sb);
                }
                sb.append(']');
            }
            case Obj o -> {
                sb.append('{');
                boolean first = true;
                for (var e : o.fields().entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    escape(e.getKey(), sb);
                    sb.append(':');
                    e.getValue().writeTo(sb);
                }
                sb.append('}');
            }
        }
    }

    private static void escape(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ---------------------------------------------------------------- parsing

    static Json parse(String text) {
        Parser p = new Parser(text);
        p.skipWhitespace();
        Json v = p.value();
        p.skipWhitespace();
        if (!p.atEnd()) throw new JsonException("trailing content at offset " + p.pos);
        return v;
    }

    static Obj parseObject(String text) {
        Json j = parse(text);
        if (j instanceof Obj o) return o;
        throw new JsonException("expected a JSON object, got " + j.getClass().getSimpleName());
    }

    static Arr parseArray(String text) {
        Json j = parse(text);
        if (j instanceof Arr a) return a;
        throw new JsonException("expected a JSON array, got " + j.getClass().getSimpleName());
    }

    /** Thrown for any malformed input. Never partially applied — parsing is all or nothing. */
    final class JsonException extends RuntimeException {
        public JsonException(String message) { super(message); }
    }

    final class Parser {
        private final String s;
        private int pos;

        Parser(String s) { this.s = s == null ? "" : s; }

        boolean atEnd() { return pos >= s.length(); }

        void skipWhitespace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        Json value() {
            if (atEnd()) throw new JsonException("unexpected end of input");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> new Str(string());
                case 't' -> { expect("true"); yield new Bool(true); }
                case 'f' -> { expect("false"); yield new Bool(false); }
                case 'n' -> { expect("null"); yield Nul.INSTANCE; }
                default -> number();
            };
        }

        private void expect(String literal) {
            if (!s.startsWith(literal, pos)) {
                throw new JsonException("expected '" + literal + "' at offset " + pos);
            }
            pos += literal.length();
        }

        private Obj object() {
            pos++; // '{'
            Map<String, Json> fields = new LinkedHashMap<>();
            skipWhitespace();
            if (!atEnd() && s.charAt(pos) == '}') { pos++; return new Obj(fields); }
            while (true) {
                skipWhitespace();
                if (atEnd() || s.charAt(pos) != '"') throw new JsonException("expected a quoted key at offset " + pos);
                String key = string();
                skipWhitespace();
                if (atEnd() || s.charAt(pos) != ':') throw new JsonException("expected ':' at offset " + pos);
                pos++;
                skipWhitespace();
                fields.put(key, value());
                skipWhitespace();
                if (atEnd()) throw new JsonException("unterminated object");
                char c = s.charAt(pos++);
                if (c == '}') return new Obj(fields);
                if (c != ',') throw new JsonException("expected ',' or '}' at offset " + (pos - 1));
            }
        }

        private Arr array() {
            pos++; // '['
            List<Json> items = new ArrayList<>();
            skipWhitespace();
            if (!atEnd() && s.charAt(pos) == ']') { pos++; return new Arr(items); }
            while (true) {
                skipWhitespace();
                items.add(value());
                skipWhitespace();
                if (atEnd()) throw new JsonException("unterminated array");
                char c = s.charAt(pos++);
                if (c == ']') return new Arr(items);
                if (c != ',') throw new JsonException("expected ',' or ']' at offset " + (pos - 1));
            }
        }

        private String string() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) throw new JsonException("unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                if (atEnd()) throw new JsonException("unterminated escape");
                char e = s.charAt(pos++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > s.length()) throw new JsonException("truncated \\u escape");
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new JsonException("invalid escape \\" + e + " at offset " + (pos - 1));
                }
            }
        }

        private Json number() {
            int start = pos;
            if (!atEnd() && s.charAt(pos) == '-') pos++;
            while (!atEnd() && isNumberChar(s.charAt(pos))) pos++;
            if (start == pos) throw new JsonException("expected a value at offset " + start);
            try {
                return new Num(Double.parseDouble(s.substring(start, pos)));
            } catch (NumberFormatException e) {
                throw new JsonException("malformed number at offset " + start);
            }
        }

        private static boolean isNumberChar(char c) {
            return (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-';
        }
    }
}
