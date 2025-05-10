package com.tkuimwd.component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

import com.almasb.fxgl.dsl.FXGL;
import com.almasb.fxgl.entity.Entity;
import com.almasb.fxgl.entity.component.Component;
import com.almasb.fxgl.physics.PhysicsComponent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.tkuimwd.api.dto.StateUpdate;
import com.tkuimwd.type.EntityType;

import javafx.util.Duration;

import com.tkuimwd.api.dto.EntityState;

public class NetworkComponent extends Component {

    private WebSocket ws;
    private ObjectMapper mapper = new ObjectMapper();
    private Map<String, Entity> idMap = new HashMap<>();
    private int seq = 0;
    private int frameCount = 0;
    private final int FRAMES_PER_UPDATE = 18; // 每6幀更新一次，約0.1秒(假設60FPS)

    @Override
    public void onAdded() {
        idMap.clear();

        // 1) 先 collect idMap
        List<Entity> p1List = FXGL.getGameWorld()
                .getEntitiesByType(EntityType.P1_CHESS);
        for (int i = 0; i < p1List.size(); i++) {
            Entity e = p1List.get(i);

            // 設定屬性
            String key = "p1_chess_" + i;
            e.getProperties().setValue("id", key);

            // 放入 map
            idMap.put(key, e);
        }

        List<Entity> p2List = FXGL.getGameWorld()
                .getEntitiesByType(EntityType.P2_CHESS);
        for (int i = 0; i < p2List.size(); i++) {
            Entity e = p2List.get(i);
            String key = "p2_chess_" + i;
            e.getProperties().setValue("id", key);
            idMap.put(key, e);
        }

        Entity football = FXGL.getGameWorld().getEntitiesByType(EntityType.FOOTBALL).get(0);
        football.getProperties().setValue("id", "football");
        String id = football.getString("id");
        if (id != null) {
            idMap.put(id, football);
        }

        // 2) 建 WS，連到你的 relay server
        HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:8080/ws/game?token=3271341c-c863-4f2c-8d19-eb25ac33b7fd"),
                        new WSListener())
                .thenAccept(ws -> this.ws = ws);
    }

    @Override
    public void onUpdate(double tpf) {
        // 每幀或固定間隔，collect 全部實體狀態並 broadcast
        frameCount++;
        if (frameCount >= FRAMES_PER_UPDATE) {
            StateUpdate update = collectState(seq);
            if(!update.getStates().isEmpty()) {
                seq++;
                System.out.println(update.toString());
                sendStateUpdate(update);
            }
            frameCount = 0;
        }
    }

    /** 把當前所有實體的位置打包 */
    private StateUpdate collectState(int seq) {
        List<EntityState> list = new ArrayList<>();
        list.clear();
        idMap.forEach((id, e) -> {
            PhysicsComponent phy = e.getComponent(PhysicsComponent.class);

            if (e != null) {
                double x = e.getX();
                double y = e.getY();
                double vx = phy.getLinearVelocity().getX();
                double vy = phy.getLinearVelocity().getY();
                if (vx != 0 || vy != 0) {
                    list.add(new EntityState(id, x, y, vx, vy));
                }
            }
        });
        StateUpdate update = new StateUpdate(seq,"shot","681f41ff0464937d8065de93", list);
        return update;
    }

    private void sendStateUpdate(StateUpdate update) {
        if (ws != null && !ws.isOutputClosed() && !update.getStates().isEmpty()) {
    
            EntityState s = update.getStates().get(0); // 只取第一個實體
    
            ObjectNode msg = mapper.createObjectNode();
            msg.put("type", "shot");
            msg.put("matchId", update.getMatchId());
            msg.put("seq", update.getSeq());
    
            ObjectNode payload = mapper.createObjectNode();
            payload.put("id", s.getId());
            payload.put("start_x", s.getX());
            payload.put("start_y", s.getY());
            payload.put("end_x", s.getX() + s.getVx()); // 模擬方向
            payload.put("end_y", s.getY() + s.getVy());
    
            msg.set("payload", payload);
            //System.out.println("json is = " + msg);
            ws.sendText(msg.toString(), true);
        }
    }

    private class WSListener implements WebSocket.Listener {
        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            try {
                System.out.println("收到後端訊息: " + data);

                JsonNode root = mapper.readTree(data.toString());
                if ("state_update".equals(root.get("type").asText())) {
                    StateUpdate upd = mapper.treeToValue(root.get("entities"), StateUpdate.class);
                    FXGL.getGameTimer().runOnceAfter(() -> applyRemote(upd), Duration.ZERO);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return WebSocket.Listener.super.onText(webSocket, data, last);
        }
    }

    /** 把遠端傳來的物件狀態覆寫到本地 Entity */
    private void applyRemote(StateUpdate update) {
        for (EntityState s : update.getStates()) {
            Entity e = idMap.get(s.getId());
            if (e == null){
                System.out.println("Entity not found: " + s.getId());
                continue;
            }
            e.setPosition(s.getX(), s.getY());
            e.getComponent(PhysicsComponent.class)
                    .setLinearVelocity(s.getVx(), s.getVy());
        }
    }
}
