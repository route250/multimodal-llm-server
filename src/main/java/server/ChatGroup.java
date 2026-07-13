package server;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

import llm.LanguageModel;
import llm.OpenAiResponsesLanguageModel;
import audio.AudioProcessor;

public class ChatGroup {
    private final String id;
    private final MlServer server;
    private final Map<String, ChatClient> clients = new ConcurrentHashMap<>();

    public ChatGroup(String id, MlServer server) {
        this.id = id;
        this.server = server;
    }

    public String id() {
        return id;
    }

    public int nextId() {
        return this.server.nextId();
    }

    MlServer server() {
        return server;
    }
    public void execute(Runnable r) {
        this.server.execute(r);
    }
    public Future<?> submit(Runnable task) {
        return this.server.submit(task);
    }
    public <T> Future<T> submit(Runnable task, T result ) {
        return this.server.submit(task,result);
    }
    public <T> Future<T> submit(Callable<T> task) {
        return this.server.submit(task);
    }

    void assignFaceName(String trackId, String name) {
        this.server.assignFaceName(trackId, name);
    }

    public ChatClient join(String clientId) {
        ChatClient client = new ChatClient(clientId, this);
        return join(clientId, client);
    }

    ChatClient join(String clientId, AudioProcessor audioProcessor) {
        ChatClient client = new ChatClient(clientId, this, audioProcessor, new OpenAiResponsesLanguageModel());
        return join(clientId, client);
    }

    ChatClient join(String clientId, AudioProcessor audioProcessor, LanguageModel languageModel) {
        ChatClient client = new ChatClient(clientId, this, audioProcessor, languageModel);
        return join(clientId, client);
    }

    private ChatClient join(String clientId, ChatClient client) {
        ChatClient previous = clients.put(clientId, client);
        if (previous != null && previous != client) {
            previous.close();
        }
        publish(ServerEvent.system(clientId + " joined " + id));
        return client;
    }

    public ChatClient client(String clientId) {
        return clients.get(clientId);
    }

    public void leave(ChatClient client) {
        client.close();
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

    /** 保存済みの Group 設定を、接続済みクライアントの次回呼び出しへ反映します。 */
    void applyLanguageModelConfig(OpenAiResponsesLanguageModel.Config config) {
        for (ChatClient client : clients.values()) {
            client.setLanguageModel(new OpenAiResponsesLanguageModel(config));
        }
    }
}
