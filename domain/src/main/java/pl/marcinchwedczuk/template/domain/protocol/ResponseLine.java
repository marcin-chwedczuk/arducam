package pl.marcinchwedczuk.template.domain.protocol;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One text line received from the firmware, split into tokens.
 * {@code key=value} tokens are also collected into {@link #params()}.
 */
public record ResponseLine(String raw, Type type, List<String> tokens, Map<String, String> params) {
    public enum Type { BOOT, OK, ERR, INFO, TEST, DATA, END, UNKNOWN }

    public static ResponseLine parse(String raw) {
        List<String> tokens = raw.isBlank()
                ? List.of()
                : List.of(raw.trim().split(" +"));

        Map<String, String> params = new LinkedHashMap<>();
        for (String t : tokens) {
            int eq = t.indexOf('=');
            if (eq > 0) {
                params.put(t.substring(0, eq), t.substring(eq + 1));
            }
        }

        Type type = Type.UNKNOWN;
        if (!tokens.isEmpty()) {
            type = Arrays.stream(Type.values())
                    .filter(t -> t != Type.UNKNOWN && t.name().equals(tokens.getFirst()))
                    .findFirst()
                    .orElse(Type.UNKNOWN);
        }

        return new ResponseLine(raw, type, tokens, Collections.unmodifiableMap(params));
    }

    /** {@code OK} or {@code ERR}: the last line of a command response. */
    public boolean isFinal() {
        return type == Type.OK || type == Type.ERR;
    }

    /** Token at index, or empty string when missing. */
    public String token(int index) {
        return index < tokens.size() ? tokens.get(index) : "";
    }

    /** For OK/ERR lines: the verb, e.g. {@code PICTURE}. */
    public String verb() {
        return token(1);
    }

    /** For ERR lines: the error code, e.g. {@code POWER_OFF}. */
    public String errorCode() {
        return token(2);
    }

    /** For ERR lines: the free text after the error code. */
    public String errorText() {
        return String.join(" ", tokens.subList(Math.min(3, tokens.size()), tokens.size()));
    }

    public String param(String key) {
        return params.get(key);
    }

    public int intParam(String key) {
        String v = params.get(key);
        if (v == null) throw new IllegalArgumentException("Missing '" + key + "' in: " + raw);
        try {
            return v.startsWith("0x") ? Integer.parseInt(v.substring(2), 16) : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Bad number '" + key + "=" + v + "' in: " + raw);
        }
    }
}
