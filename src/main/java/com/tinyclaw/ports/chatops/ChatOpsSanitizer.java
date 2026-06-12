package com.tinyclaw.ports.chatops;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight sanitizer for ChatOps outbound messages.
 *
 * <p>Masks common secret patterns in tool arguments and outputs before they
 * are sent to chat channels. This is a defence-in-depth layer: secrets should
 * never reach the reporter in the first place, but if they do, they are
 * masked before leaving the process.</p>
 *
 * <p>Patterns covered:</p>
 * <ul>
 *   <li>api_key, api-key, apikey (JSON and plain)</li>
 *   <li>Authorization / Bearer tokens (HTTP headers)</li>
 *   <li>token, access_token, refresh_token</li>
 *   <li>password, passwd, pwd</li>
 *   <li>secret, app_secret, client_secret</li>
 * </ul>
 */
public class ChatOpsSanitizer {

    private static final Pattern[] SENSITIVE_PATTERNS = {
        // JSON-style, assignment, or natural language: "api_key": "sk-xxx", api_key=sk-xxx, api_key is sk-xxx
        Pattern.compile("(\"?(api[_-]?key|apikey)\"?\s*(?:[:=]|\s+is\s+)\s*\"?)([^\\s\"&,}\\]<>]{4,})", Pattern.CASE_INSENSITIVE),
        // Authorization / Bearer header
        Pattern.compile("((?i)Authorization\s*[:=]\s*(?i)Bearer\s+)([^\\s\"&,}\\]<>]{4,})", Pattern.CASE_INSENSITIVE),
        // token variants
        Pattern.compile("(\"?(token|access_token|refresh_token)\"?\s*(?:[:=]|\s+is\s+)\s*\"?)([^\\s\"&,}\\]<>]{4,})", Pattern.CASE_INSENSITIVE),
        // password variants
        Pattern.compile("(\"?(password|passwd|pwd)\"?\s*(?:[:=]|\s+is\s+)\s*\"?)([^\\s\"&,}\\]<>]{4,})", Pattern.CASE_INSENSITIVE),
        // secret variants
        Pattern.compile("(\"?(secret|app_secret|client_secret)\"?\s*(?:[:=]|\s+is\s+)\s*\"?)([^\\s\"&,}\\]<>]{4,})", Pattern.CASE_INSENSITIVE),
    };

    private static final String MASK = "***";

    /**
     * Sanitize a string that may contain secrets.
     *
     * @param input the raw string (may be null)
     * @return the sanitized string; null input returns empty string
     */
    public static String sanitize(String input) {
        if (input == null || input.isBlank()) {
            return input != null ? input : "";
        }
        String result = input;
        for (Pattern pattern : SENSITIVE_PATTERNS) {
            Matcher matcher = pattern.matcher(result);
            StringBuilder sb = new StringBuilder();
            while (matcher.find()) {
                // group 1 = prefix (key + separator), group 2/3 = value
                String replacement = matcher.group(1) + MASK;
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(sb);
            result = sb.toString();
        }
        return result;
    }

    /**
     * Truncate and sanitize in one step.
     *
     * @param input   the raw string
     * @param maxLen  maximum length before truncation
     * @return sanitized and truncated string
     */
    public static String sanitizeAndTruncate(String input, int maxLen) {
        String sanitized = sanitize(input);
        if (sanitized == null) {
            return "";
        }
        String t = sanitized.trim();
        if (t.length() <= maxLen) {
            return t;
        }
        return t.substring(0, maxLen) + "... (truncated)";
    }
}
