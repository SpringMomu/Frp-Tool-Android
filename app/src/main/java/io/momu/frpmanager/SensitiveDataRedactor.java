package io.momu.frpmanager;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps credentials out of UI, Logcat and clipboard-facing text. */
public final class SensitiveDataRedactor {

    private static final String MASK = "[REDACTED]";
    private static final Pattern INI_SECRET = Pattern.compile(
        "(?im)^(\\s*(?:token|password|passphrase|private_key)\\s*=\\s*)(.*)$"
    );
    private static final Pattern INLINE_SECRET = Pattern.compile(
        "(?i)(token|password|passphrase|private[_-]?key)(\\s*[=:]\\s*)([^\\s'\\\"]+)"
    );
    private static final Pattern PEM_BLOCK = Pattern.compile(
        "(?s)-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----"
    );

    private SensitiveDataRedactor() { }

    public static String redact(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        String redacted = PEM_BLOCK.matcher(value).replaceAll("[PRIVATE KEY REDACTED]");
        redacted = INI_SECRET.matcher(redacted).replaceAll("$1" + MASK);
        Matcher matcher = INLINE_SECRET.matcher(redacted);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                matcher.group(1) + matcher.group(2) + MASK
            ));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public static String redactCommandDescription(String operation) {
        return operation == null || operation.trim().isEmpty() ? "远端操作" : operation;
    }
}
