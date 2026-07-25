package llm;

import java.util.HashMap;
import java.util.Map;

import json.Json;

/**
 * LLM に渡す会話履歴の 1 メッセージです。
 */
public record Message(Role role, String message, String name, Map<String,String> meta, long tm) {
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
        if( name==null || name.isBlank() ) {
            name = "";
        }
        if( meta==null ) {
            meta = new HashMap<>();
        }
        if( tm<0 ) {
            throw new IllegalArgumentException("tm<0");
        }
    }
    public Message( Role role, String message ) {
        this(role,message,null,null, System.currentTimeMillis());
    }
    public Message( Role role, String message, String name ) {
        this(role,message,name,null, System.currentTimeMillis());
    }
    public Message strip() {
        return new Message(this.role(),this.message(),this.name(),null,this.tm());
    }
    public String meta( String key ) {
        return this.meta.get(key);
    }
    public String meta( String key, String value ) {
        return this.meta.put(key,value);
    }
    public boolean contains(String key) {
        return this.meta.containsKey(key);
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
