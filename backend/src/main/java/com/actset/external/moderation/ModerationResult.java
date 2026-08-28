package com.actset.external.moderation;

public record ModerationResult(boolean allowed, String reason) {
    public static ModerationResult allow() {
        return new ModerationResult(true, null);
    }

    public static ModerationResult block(String reason) {
        return new ModerationResult(false, reason);
    }
}
