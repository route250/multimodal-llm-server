package server;

import java.util.concurrent.LinkedBlockingQueue;

public class ChatClient {
    private final String id;
    private final ChatGroup chatGroup;
    private final LinkedBlockingQueue<ServerEvent> events = new LinkedBlockingQueue<>();

    public ChatClient(String id, ChatGroup chatGroup) {
        this.id = id;
        this.chatGroup = chatGroup;
    }

    public String id() {
        return id;
    }

    public LinkedBlockingQueue<ServerEvent> events() {
        return events;
    }

    public void handle(ChatRequest request) {
        sendToGroup(request.toEvent());
    }

    private void sendToGroup(ServerEvent event) {
        chatGroup.publish(event);
    }

    public void receive(ServerEvent event) {
        events.offer(event);
    }
}
