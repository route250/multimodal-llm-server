package llm;

import json.Json;

/**
 * LLM に渡す会話履歴の 1 メッセージです。
 */
public record Message(Role role, String message) {
    public static enum Role {
        User, Assistant, System, Developer;
        public String toString() {
            return this.name().toLowerCase();
        }
        public static Message.Role of(String role) {
            switch(role!=null?role.toLowerCase():"") {
                case "assistant": return Message.Role.Assistant;
                case "user": return Message.Role.User;
                case "system": return Message.Role.System;
                case "developer": return Message.Role.Developer;
                default: return Message.Role.Assistant;
            }
        }
    }
    public Message {
        if (role == null) {
            throw new IllegalArgumentException("role must be user or assistant: null");
        }
        if (message == null || message.isBlank()) {
            //throw new IllegalArgumentException("text must not be blank");
            message = "";
        }
    }
    public Message( String role, String message ) {
        this(Role.of(role),message);
    }
    public String toString() {
        return "{\""+this.role+"\":\""+this.message+"\"}";
    }

    public StringBuilder toJson(StringBuilder json) {
        String contentType = this.role==Role.Assistant ? "output_text" : "input_text";
        json.append("{\"type\":\"message\",\"role\":").append(Json.string(this.role.toString()))
                .append(",\"content\":[{\"type\":").append(Json.string(contentType))
                .append(",\"text\":").append(Json.string(this.message)).append("}]}");
        return json;
    }
}
