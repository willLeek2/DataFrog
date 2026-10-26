package world.willfrog.datafrog.server.objectdata.service;

import world.willfrog.datafrog.server.objectdata.domain.SemanticRef;

import java.util.ArrayList;
import java.util.List;

/**
 * 本模块三个集合列（semantic_refs / inputs / primary_key / part_checksums）的 JSON 读写小工具。
 * 只支持我们自己写出的两种形态：字符串数组与 {name,version} 对象数组——不引 JSON 依赖，
 * 也不承担解析任意 JSON 的职责。写入时字符串做过转义；解析按同名规则还原。
 */
final class Jsons {

    private Jsons() {
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    static String writeStringArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escape(values.get(i))).append('"');
        }
        return sb.append(']').toString();
    }

    static String writeRefs(List<SemanticRef> refs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < refs.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            SemanticRef r = refs.get(i);
            sb.append("{\"name\":\"").append(escape(r.name())).append("\",\"version\":").append(r.version()).append('}');
        }
        return sb.append(']').toString();
    }

    /** sha256 串包成 JSON 标量（part_checksums 用 JSONB || 拼接时需要的是带引号的元素）。 */
    static String jsonScalar(String value) {
        return "\"" + escape(value) + "\"";
    }

    static List<String> readStringArray(String json) {
        List<String> out = new ArrayList<>();
        Parser p = new Parser(json);
        p.skipWs();
        p.expect('[');
        p.skipWs();
        if (p.peek() == ']') {
            p.next();
            return out;
        }
        while (true) {
            out.add(p.parseString());
            p.skipWs();
            char c = p.next();
            if (c == ']') {
                return out;
            }
            if (c != ',') {
                throw new IllegalArgumentException("非法 JSON 数组: " + json);
            }
        }
    }

    static List<SemanticRef> readRefs(String json) {
        List<SemanticRef> out = new ArrayList<>();
        Parser p = new Parser(json);
        p.skipWs();
        p.expect('[');
        p.skipWs();
        if (p.peek() == ']') {
            p.next();
            return out;
        }
        while (true) {
            p.skipWs();
            p.expect('{');
            String name = null;
            int version = 0;
            p.skipWs();
            while (p.peek() != '}') {
                String key = p.parseString();
                p.skipWs();
                p.expect(':');
                p.skipWs();
                if ("name".equals(key)) {
                    name = p.parseString();
                } else if ("version".equals(key)) {
                    StringBuilder num = new StringBuilder();
                    while (Character.isDigit(p.peek())) {
                        num.append(p.next());
                    }
                    version = Integer.parseInt(num.toString());
                } else {
                    throw new IllegalArgumentException("非法定义引用 JSON: " + json);
                }
                p.skipWs();
                if (p.peek() == ',') {
                    p.next();
                    p.skipWs();
                }
            }
            p.expect('}');
            if (name == null) {
                throw new IllegalArgumentException("定义引用缺 name: " + json);
            }
            out.add(new SemanticRef(name, version));
            p.skipWs();
            char c = p.next();
            if (c == ']') {
                return out;
            }
            if (c != ',') {
                throw new IllegalArgumentException("非法 JSON 数组: " + json);
            }
        }
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        char peek() {
            return s.charAt(i);
        }

        char next() {
            return s.charAt(i++);
        }

        void expect(char c) {
            if (next() != c) {
                throw new IllegalArgumentException("JSON 解析失败，期望 " + c + ": " + s);
            }
        }

        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        String parseString() {
            skipWs();
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> throw new IllegalArgumentException("非法转义: \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }
    }
}
