package server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ChatGroup {
    private final String id;
    private final Map<String, ChatClient> clients = new ConcurrentHashMap<>();

    public ChatGroup(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public ChatClient join(String clientId) {
        ChatClient client = new ChatClient(clientId, this);
        clients.put(clientId, client);
        publish(ServerEvent.system(clientId + " joined " + id));
        return client;
    }

    public ChatClient client(String clientId) {
        return clients.get(clientId);
    }

    public void leave(ChatClient client) {
        if (clients.remove(client.id(), client)) {
            publish(ServerEvent.system(client.id() + " left " + id));
        }
    }

    public void publish(ServerEvent event) {
        for (ChatClient client : clients.values()) {
            client.receive(event);
        }
    }

    public int clientCount() {
        return clients.size();
    }
}
