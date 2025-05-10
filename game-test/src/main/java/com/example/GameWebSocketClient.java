package com.example;

import java.net.URI;
import java.net.http.WebSocket;
import java.net.http.HttpClient;
import java.util.concurrent.CompletionStage;

public class GameWebSocketClient implements WebSocket.Listener {

    private WebSocket webSocket;

    public void connect(String token) {
        try {
            URI uri = new URI("ws://localhost:8080/ws/game?token=" + token);
            webSocket = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .buildAsync(uri, this)
                    .join();

            System.out.println("WebSocket 已連線");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        System.out.println("WebSocket 開啟");
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        System.out.println("接收到訊息：" + data);
        webSocket.request(1);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        System.out.println("WebSocket 發生錯誤：" + error.getMessage());
    }
}