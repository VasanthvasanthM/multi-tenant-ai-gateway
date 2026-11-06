package io.vasanth.gateway.common.llm;

import java.util.Objects;

/**
 * One turn in a conversation sent to an LLM.
 *
 * @param role who produced this turn
 * @param content the text of the turn
 */
public record Message(Role role, String content) {

    public Message {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
    }

    public static Message system(String content) {
        return new Message(Role.SYSTEM, content);
    }

    public static Message user(String content) {
        return new Message(Role.USER, content);
    }

    public static Message assistant(String content) {
        return new Message(Role.ASSISTANT, content);
    }

    /** Speaker of a message. */
    public enum Role {
        SYSTEM("system"),
        USER("user"),
        ASSISTANT("assistant");

        private final String wireValue;

        Role(String wireValue) {
            this.wireValue = wireValue;
        }

        /**
         * The value used on the wire and in the cache key.
         *
         * <p>Pinned explicitly rather than derived from {@link #name()} so that renaming a
         * constant cannot silently change every previously computed cache key, which would
         * invalidate the entire cache on deploy.
         */
        public String wireValue() {
            return wireValue;
        }
    }
}
