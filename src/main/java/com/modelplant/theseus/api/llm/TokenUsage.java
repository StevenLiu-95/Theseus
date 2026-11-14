package com.modelplant.theseus.api.llm;

/**
 * Token accounting from an LLM response. Negative fields mean "not reported".
 */
public record TokenUsage(int promptTokens, int completionTokens, int totalTokens) {
    public static final TokenUsage UNKNOWN = new TokenUsage(-1, -1, -1);

    public boolean known() {
        return promptTokens >= 0 || completionTokens >= 0 || totalTokens >= 0;
    }

    public TokenUsage plus(TokenUsage other) {
        if (other == null || !other.known()) {
            return this;
        }
        if (!known()) {
            return other;
        }
        return new TokenUsage(
                sum(promptTokens, other.promptTokens),
                sum(completionTokens, other.completionTokens),
                sum(totalTokens, other.totalTokens)
        );
    }

    public String format() {
        if (!known()) {
            return "n/a";
        }
        StringBuilder sb = new StringBuilder();
        if (promptTokens >= 0) {
            sb.append("prompt=").append(promptTokens);
        }
        if (completionTokens >= 0) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append("completion=").append(completionTokens);
        }
        int total = totalTokens >= 0
                ? totalTokens
                : (promptTokens >= 0 && completionTokens >= 0 ? promptTokens + completionTokens : -1);
        if (total >= 0) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append("total=").append(total);
        }
        return sb.toString();
    }

    private static int sum(int a, int b) {
        if (a < 0) {
            return b;
        }
        if (b < 0) {
            return a;
        }
        return a + b;
    }
}
