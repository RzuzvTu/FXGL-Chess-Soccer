package com.example;

import com.almasb.fxgl.app.GameApplication;
import com.almasb.fxgl.app.GameSettings;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublishers;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import static com.almasb.fxgl.dsl.FXGL.*;

public class GameMain extends GameApplication {

    private GameWebSocketClient webSocketClient = new GameWebSocketClient();
    private String playerToken;
    private String currentRoomCode;

    @Override
    protected void initSettings(GameSettings settings) {
        settings.setTitle("Chess Soccer - 登入");
        settings.setVersion("0.1");
    }

    @Override
    protected void initGame() {
        showLoginScreen();
    }

    private void showLoginScreen() {
        getGameScene().clearUINodes();

        VBox loginBox = new VBox(10);
        loginBox.setTranslateX(300);
        loginBox.setTranslateY(200);

        TextField usernameField = new TextField();
        usernameField.setPromptText("請輸入帳號");

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("請輸入密碼");

        Button loginBtn = new Button("登入");
        loginBtn.setOnAction(e -> {
            String email = usernameField.getText();
            String password = passwordField.getText();

            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("email", email);
            requestBody.put("password", password);

            try {
                ObjectMapper mapper = new ObjectMapper();
                String json = mapper.writeValueAsString(requestBody);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(new URI("http://localhost:8080/players/login"))
                        .header("Content-Type", "application/json")
                        .POST(BodyPublishers.ofString(json))
                        .build();

                HttpClient client = HttpClient.newHttpClient();
                client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                      .thenApply(HttpResponse::body)
                      .thenAccept(responseBody -> {
                          String token = responseBody.replaceAll("\"", "").trim();
                          this.playerToken = token;

                          System.out.println("登入成功，Token: " + token);
                          webSocketClient.connect(token);

                          runOnce(() -> showRoomMenuScreen(), Duration.seconds(0.1));
                      })
                      .exceptionally(ex -> {
                          ex.printStackTrace();
                          runOnce(() -> showMessage("登入失敗，請檢查伺服器"), Duration.seconds(0.1));
                          return null;
                      });

            } catch (Exception ex) {
                ex.printStackTrace();
                showMessage("登入請求失敗！");
            }
        });

        Button exitBtn = new Button("離開遊戲");
        exitBtn.setOnAction(e -> getGameController().exit());

        loginBox.getChildren().addAll(usernameField, passwordField, loginBtn, exitBtn);
        addUINode(loginBox);
    }

    private void showRoomMenuScreen() {
        getGameScene().clearUINodes();

        VBox menuBox = new VBox(10);
        menuBox.setTranslateX(300);
        menuBox.setTranslateY(200);

        Button createRoomBtn = new Button("🔘 建立房間");
        createRoomBtn.setOnAction(e -> {
            try {
                ObjectMapper mapper = new ObjectMapper();
                Map<String, String> body = new HashMap<>();
                body.put("token", playerToken);
                String json = mapper.writeValueAsString(body);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(new URI("http://localhost:8080/room/create"))
                        .header("Content-Type", "application/json")
                        .POST(BodyPublishers.ofString(json))
                        .build();

                HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString())
                        .thenApply(HttpResponse::body)
                        .thenAccept(resp -> {
                            runOnce(() -> {
                                showMessage("建立成功，房號: " + resp);
                                currentRoomCode = extractRoomCode(resp);
                                showHostRoomScreen();
                            }, Duration.seconds(0.1));
                        })
                        .exceptionally(ex -> {
                            ex.printStackTrace();
                            runOnce(() -> showMessage("建立房間失敗"), Duration.seconds(0.1));
                            return null;
                        });
            } catch (Exception ex) {
                ex.printStackTrace();
                showMessage("請求錯誤");
            }
        });

        TextField roomCodeField = new TextField();
        roomCodeField.setPromptText("請輸入房號");

        Button joinRoomBtn = new Button("🔘 加入房間");
        joinRoomBtn.setOnAction(e -> {
            try {
                String roomCode = roomCodeField.getText();
                ObjectMapper mapper = new ObjectMapper();
                Map<String, String> body = new HashMap<>();
                body.put("token", playerToken);
                body.put("roomCode", roomCode);
                String json = mapper.writeValueAsString(body);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(new URI("http://localhost:8080/room/join"))
                        .header("Content-Type", "application/json")
                        .POST(BodyPublishers.ofString(json))
                        .build();

                HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString())
                        .thenApply(HttpResponse::body)
                        .thenAccept(resp -> runOnce(() -> {
                            showMessage("加入成功: " + resp);
                            currentRoomCode = roomCode;
                            showGuestReadyScreen();
                        }, Duration.seconds(0.1)))
                        .exceptionally(ex -> {
                            ex.printStackTrace();
                            runOnce(() -> showMessage("加入房間失敗"), Duration.seconds(0.1));
                            return null;
                        });
            } catch (Exception ex) {
                ex.printStackTrace();
                showMessage("請求錯誤");
            }
        });

        Button logoutBtn = new Button("登出並返回登入畫面");
        logoutBtn.setOnAction(e -> showLoginScreen());

        menuBox.getChildren().addAll(createRoomBtn, roomCodeField, joinRoomBtn, logoutBtn);
        addUINode(menuBox);
    }

    private void showHostRoomScreen() {
        getGameScene().clearUINodes();

        VBox box = new VBox(10);
        box.setTranslateX(300);
        box.setTranslateY(200);

        Label label = new Label("房主畫面：等待 guest 準備中...\n房號: " + currentRoomCode);
        Button startBtn = new Button("開始遊戲");
        startBtn.setOnAction(e -> {
            try {
                ObjectMapper mapper = new ObjectMapper();
                Map<String, String> body = new HashMap<>();
                body.put("token", playerToken);
                body.put("roomCode", currentRoomCode);
                String json = mapper.writeValueAsString(body);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(new URI("http://localhost:8080/room/start"))
                        .header("Content-Type", "application/json")
                        .POST(BodyPublishers.ofString(json))
                        .build();

                HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString())
                        .thenApply(HttpResponse::body)
                        .thenAccept(resp -> runOnce(() -> showMessage("已開始對局"), Duration.seconds(0.1)))
                        .exceptionally(ex -> {
                            ex.printStackTrace();
                            runOnce(() -> showMessage("開始對局失敗"), Duration.seconds(0.1));
                            return null;
                        });
            } catch (Exception ex) {
                ex.printStackTrace();
                showMessage("請求錯誤");
            }
        });

        box.getChildren().addAll(label, startBtn);
        addUINode(box);
    }

    private void showGuestReadyScreen() {
        getGameScene().clearUINodes();

        VBox box = new VBox(10);
        box.setTranslateX(300);
        box.setTranslateY(200);

        Label label = new Label("玩家畫面：請按下我準備好了\n房號: " + currentRoomCode);
        Button readyBtn = new Button("我準備好了");
        readyBtn.setOnAction(e -> {
            try {
                ObjectMapper mapper = new ObjectMapper();
                Map<String, String> body = new HashMap<>();
                body.put("token", playerToken);
                body.put("roomCode", currentRoomCode);
                String json = mapper.writeValueAsString(body);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(new URI("http://localhost:8080/room/ready"))
                        .header("Content-Type", "application/json")
                        .POST(BodyPublishers.ofString(json))
                        .build();

                HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString())
                        .thenApply(HttpResponse::body)
                        .thenAccept(resp -> runOnce(() -> showMessage("已準備"), Duration.seconds(0.1)))
                        .exceptionally(ex -> {
                            ex.printStackTrace();
                            runOnce(() -> showMessage("準備失敗"), Duration.seconds(0.1));
                            return null;
                        });
            } catch (Exception ex) {
                ex.printStackTrace();
                showMessage("請求錯誤");
            }
        });

        box.getChildren().addAll(label, readyBtn);
        addUINode(box);
    }

    private String extractRoomCode(String responseJson) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<?, ?> map = mapper.readValue(responseJson, Map.class);
            return (String) map.get("roomCode");
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}