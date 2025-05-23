package com.tkuimwd.component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

import com.almasb.fxgl.core.collection.PropertyMap;
import com.almasb.fxgl.dsl.FXGL;
import com.almasb.fxgl.entity.Entity;
import com.almasb.fxgl.entity.component.Component;
import com.almasb.fxgl.physics.PhysicsComponent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.tkuimwd.api.dto.State;
import com.tkuimwd.event.ChessReleaseEvent;
import com.tkuimwd.type.EntityType;

import javafx.geometry.Point2D;
import javafx.util.Duration;

import com.tkuimwd.api.dto.EntityState;
import com.tkuimwd.api.dto.MatchData;
import com.tkuimwd.api.dto.MoveCommand;
import com.tkuimwd.api.dto.ShotCommand;
import com.tkuimwd.Config;

public class NetworkComponent extends Component {

    private WebSocket ws;
    private ObjectMapper mapper = new ObjectMapper();
    private Map<String, Entity> p1_chess_Map = new HashMap<>();
    private Map<String, Entity> idMap = new HashMap<>();
    private int tick = 0;
    private int frameCount = 0;
    private final int FRAMES_PER_UPDATE = 18; // 每6幀更新一次，約0.1秒(假設60FPS)
    private final int UPDATE_INTERVAL_MS = 100; // 更新間隔時間(毫秒)

    private final MatchData matchData = Config.matchData;
    private final String playerToken = Config.token;

    // private String matchId = "681f4eb322f7275fd1de93d4"; // 這是測試用的matchId
    // private String playerToken = "3271341c-c863-4f2c-8d19-eb25ac33b7fd"; //
    // 這是測試用的playerToken

    @Override
    public void onAdded() {
        idMap.clear();

        // 1) 先 collect idMap
        List<Entity> p1List = FXGL.getGameWorld()
                .getEntitiesByType(EntityType.P1_CHESS);
        for (int i = 0; i < p1List.size(); i++) {
            Entity e = p1List.get(i);

            // 設定屬性
            ChessComponent chessComponent = e.getComponent(ChessComponent.class);
            String id = chessComponent.getId();
            idMap.put(id, e);
            p1_chess_Map.put(id, e);
        }

        List<Entity> p2List = FXGL.getGameWorld()
                .getEntitiesByType(EntityType.P2_CHESS);
        for (int i = 0; i < p2List.size(); i++) {
            Entity e = p2List.get(i);
            ChessComponent chessComponent = e.getComponent(ChessComponent.class);
            String id = chessComponent.getId();
            idMap.put(id, e);
        }

        Entity football = FXGL.getGameWorld().getEntitiesByType(EntityType.FOOTBALL).get(0);
        FootBallComponent footBallComponent = football.getComponent(FootBallComponent.class);
        String id = footBallComponent.getId();
        if (id != null) {
            idMap.put(id, football);
        }

        if(playerToken == null || playerToken.isEmpty()) {
            System.err.println("[Network] token is null or empty");
            return;
        }
        System.out.println("[Network] token: "+ playerToken);

        // 2) 建 WS，連到你的 relay server
        HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(
                        URI.create("ws://localhost:8080/ws/game?token=" + playerToken),
                        new WSListener())
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        System.err.println("[Network] WS 連線失敗: " + err.getMessage());
                        err.printStackTrace();
                    } else {
                        this.ws = ws;
                        System.out.println("[Network] WS 已連線");
                        System.out.println(FXGL.getWorldProperties().getString("token"));
                        createListener();
                    }
                });
    }

    private void createListener() {
        System.out.println("監聽事件: ChessReleaseEvent.CHESS_RELEASE");
        FXGL.getEventBus().addEventHandler(
                ChessReleaseEvent.CHESS_RELEASE,
                e -> {
                    MoveCommand mc = new MoveCommand(
                            e.getId(),
                            e.getStartX(), e.getStartY(),
                            e.getEndX(), e.getEndY());

                    ShotCommand sc = new ShotCommand(tick++, "send", matchData.getId(), mc);
                    sendCommand(sc);
                });
        System.out.println("test");
    }

    private void sendCommand(ShotCommand shotcommand) {
        if (ws != null && !ws.isOutputClosed()) {
            ObjectNode msg = mapper.createObjectNode()
                    .put("type", shotcommand.getType())
                    .put("matchId", shotcommand.getMatchId())
                    .set("payload", mapper.valueToTree(shotcommand.getCommand()));

            System.out.println(msg.toString());
            ws.sendText(msg.toString(), true);
        }
    }

    // @Override
    // public void onUpdate(double tpf) {
    // // 每幀或固定間隔，collect 全部實體狀態並 broadcast
    // frameCount++;
    // if (frameCount >= FRAMES_PER_UPDATE) {
    // State update = collectState(tick);
    // if (!update.getStates().isEmpty()) {
    // tick++;
    // System.out.println(update.toString());
    // sendStateUpdate(update);
    // }
    // frameCount = 0;
    // }
    // }

    /** 把當前所有實體的位置打包 */
    // private State collectState(int seq) {
    // List<EntityState> list = new ArrayList<>();
    // list.clear();
    // idMap.forEach((id, e) -> {
    // PhysicsComponent phy = e.getComponent(PhysicsComponent.class);

    // if (e != null) {
    // double x = e.getX();
    // double y = e.getY();
    // double vx = phy.getLinearVelocity().getX();
    // double vy = phy.getLinearVelocity().getY();
    // if (vx != 0 || vy != 0) {
    // list.add(new EntityState(id, x, y, vx, vy));
    // }
    // }
    // });
    // State update = new State(tick, "send", matchData.getId(), list);
    // return update;
    // }

    // private void sendStateUpdate(State update) {
    // if (ws != null && ws.isOutputClosed() == false) {
    // ObjectNode msg = mapper.createObjectNode();
    // msg.put("type", update.getType());
    // msg.put("matchId", update.getMatchId());
    // msg.set("payload", mapper.valueToTree(update));
    // ws.sendText(msg.toString(), true);
    // }
    // }

    private class WSListener implements WebSocket.Listener {
        @Override
        public CompletionStage<?> onText(WebSocket webSocket,
                CharSequence data, boolean last) {
            try {
                JsonNode root = mapper.readTree(data.toString());
                if ("shot".equals(root.get("type").asText())) {
                    MoveCommand payload = mapper.treeToValue(
                            root.get("payload"), MoveCommand.class);
                    // 收到別人的狀態 → 更新本地
                    FXGL.getGameTimer().runOnceAfter(() -> applyRemote(payload), Duration.ZERO);
                } 
                // else if ("turn_update".equals(root.get("type").asText())) {
                //     boolean turn = root.get("yourTurn").asBoolean();
                //     matchData.setWaitingForTurnSwitch(!turn);
                //     matchData.setCurrentPlayerId(playerToken);

                // } else if ("score_update".equals(root.get("type").asText())) {
                //     int score1 = mapper.treeToValue(root.get("scroe1"), Integer.class);
                //     int score2 = mapper.treeToValue(root.get("scroe2"), Integer.class);
                //     matchData.setScore1(score1);
                //     matchData.setScore2(score2);
                //     applyReset();
                // } else if ("restore".equals(root.get("type").asText())) {
                //     // int score1 = mapper.treeToValue(root.get("scroe1"), Integer.class);
                //     // int score2 = mapper.treeToValue(root.get("scroe2"), Integer.class);
                //     // global_var.setValue("score1", score1);
                //     // global_var.setValue("score2", score2);
                //     // global_var.setValue("yourTurn", root.get("yourTurn").asBoolean());
                // } else if ("game_over".equals(root.get("type").asText())) { // ! fix send token
                //     String winner = root.get("winner").asText();
                //     matchData.setWinnerId(winner);
                // }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return WebSocket.Listener.super.onText(webSocket, data, last);
        }
    }

    private void applyRemote(MoveCommand payload) {
        double startX = payload.getStartX();
        double startY = payload.getStartY();
        double endX = payload.getEndX();
        double endY = payload.getEndY();

        Entity e = idMap.get(payload.getId());
        ChessComponent chess = e.getComponent(ChessComponent.class);
        Point2D impulse =  chess.caculateImpulse(startX, startY, endX, endY);
        chess.applyImpulse(impulse); 
    }

    // 用來處理進球後重置初始位置
    // private void applyReset() {
    //     double[][] pi_chess_position = Config.player1_chess_position;
    //     double[][] p2_chess_position = Config.player2_chess_position;
    //     Point2D football_position = Config.FOOTBALL_POSITION;

    //     idMap.forEach((id, e) -> {
    //         if (id.startsWith("p1_chess")) {
    //             int index = Integer.parseInt(id.substring(9));
    //             e.setPosition(pi_chess_position[index][0], pi_chess_position[index][1]);
    //             e.getComponent(PhysicsComponent.class).setLinearVelocity(0, 0);
    //         } else if (id.startsWith("p2_chess")) {
    //             int index = Integer.parseInt(id.substring(9));
    //             e.setPosition(p2_chess_position[index][0], p2_chess_position[index][1]);
    //             e.getComponent(PhysicsComponent.class).setLinearVelocity(0, 0);
    //         } else if (id.equals("football")) {
    //             e.setPosition(football_position.getX(), football_position.getY());
    //             e.getComponent(PhysicsComponent.class).setLinearVelocity(0, 0);
    //         }
    //     });
    // }
}
