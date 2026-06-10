package com.tinyclaw.application.approval;

/**
 * Produces a truncated, safe preview of tool arguments for approval records.
 */
public class ApprovalArgumentPreviewer {

    private static final int MAX_LENGTH = 1000;
    private static final String TRUNCATION_SUFFIX = "... [truncated]";

    /**
     * Returns a preview of the given JSON arguments.
     *
     * <ul>
     *   <li>null is converted to empty string</li>
     *   <li>content longer than 1000 chars is truncated with a suffix</li>
     * </ul>
     */
    public String preview(String argumentsJson) {
        if (argumentsJson == null) {
            return "";
        }
        if (argumentsJson.length() <= MAX_LENGTH) {
            return argumentsJson;
        }
        int end = MAX_LENGTH - TRUNCATION_SUFFIX.length();
        if (end < 0) {
            end = 0;
        }
        return argumentsJson.substring(0, end) + TRUNCATION_SUFFIX;
    }
}
