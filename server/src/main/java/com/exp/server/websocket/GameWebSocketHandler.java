package com.exp.server.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.exp.server.model.MatchModel;
import com.exp.server.model.PlayerModel;
import com.exp.server.repository.MatchRepository;
import com.exp.server.repository.PlayerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.exp.server.service.simulation.dto.MoveCommand;
import com.exp.server.service.simulation.dto.EntityState;
import com.exp.server.service.simulation.PhysicsEngineService;
import com.exp.server.service.simulation.GameService;
import com.exp.server.repository.RoomRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.ConcurrentHashMap;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.time.Duration;

@Component
public class GameWebSocketHandler extends TextWebSocketHandler {

    @Autowired
    private PlayerRepository playerRepository;

    @Autowired
    private MatchRepository matchRepository;


    @Autowired
    private PhysicsEngineService physicsEngineService;
    
    @Autowired
    private RoomRepository roomRepository;


    private static final Map<String, WebSocketSession> tokenSessionMap = new ConcurrentHashMap<>();
    private static final Map<String, String> sessionIdToTokenMap = new ConcurrentHashMap<>();
    private static final Map<String, LocalDateTime> disconnectTimeMap = new ConcurrentHashMap<>();
    private static final Map<String, LocalDateTime> lastActiveTimeMap = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    // 儲存所有連線的玩家（可依照房間編碼分群）
    // private static final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String token = getQueryParam(session, "token");

        if (token == null || token.isBlank()) {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("缺少 token"));
            return;
        }

        PlayerModel player = playerRepository.findByToken(token);
        if (player == null) {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("無效 token"));
            return;
        }

        System.out.println("玩家已通過驗證: " + player.getUserName());

        tokenSessionMap.put(token, session);
        sessionIdToTokenMap.put(session.getId(), token);

        disconnectTimeMap.remove(token);
        // 玩家斷線後重新連線
        List<MatchModel> matchesAsP1 = matchRepository.findAllByPlayer1Id(token);
        List<MatchModel> matchesAsP2 = matchRepository.findAllByPlayer2Id(token);

        lastActiveTimeMap.put(token, LocalDateTime.now());

        MatchModel match = Stream.concat(matchesAsP1.stream(), matchesAsP2.stream())
                .filter(m -> "playing".equals(m.getMatchStatus()))
                .findFirst()
                .orElse(null);
        if (match != null && "playing".equals(match.getMatchStatus())) {
            boolean yourTurn = match.getCurrentPlayerId().equals(token);
            String restoreMsg = String.format(
                    "{\"type\":\"restore\",\"matchId\":\"%s\",\"yourTurn\":%s,\"score1\":%d,\"score2\":%d}",
                    match.getId(), yourTurn, match.getScore1(), match.getScore2());
            sendToToken(token, restoreMsg);
            System.out.println("已恢復玩家對局狀態：" + token);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String token = sessionIdToTokenMap.remove(session.getId());
        if (token != null) {
            tokenSessionMap.remove(token);
            disconnectTimeMap.put(token, LocalDateTime.now());
        }

        System.out.println("玩家離線: " + session.getId());
    }

    private String getQueryParam(WebSocketSession session, String key) {
        String query = session.getUri().getQuery();
        if (query == null)
            return null;
        for (String param : query.split("&")) {
            String[] pair = param.split("=");
            if (pair.length == 2 && pair[0].equals(key))
                return pair[1];
        }
        return null;
    }

    public static void sendToToken(String token, String jsonMessage) {
        WebSocketSession session = tokenSessionMap.get(token);
        if (session != null && session.isOpen()) {
            try {
                session.sendMessage(new TextMessage(jsonMessage));
            } catch (Exception e) {
                System.out.println("傳送給 " + token + " 失敗：" + e.getMessage());
            }
        } else {
            System.out.println("找不到 token 對應的 session 或連線已關閉：" + token);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            JsonNode json = mapper.readTree(message.getPayload());
            String type = json.get("type").asText();

            if ("shot".equals(type)) {
                String matchId = json.get("matchId").asText();
                MatchModel match = matchRepository.findById(matchId).orElse(null);
                if (match == null) return;
            
                String senderToken = getQueryParam(session, "token");
                if (!match.getCurrentPlayerId().equals(senderToken)) {
                    sendToToken(senderToken, "{\"type\":\"error\",\"message\":\"不是你的回合\"}");
                    return;
                }

                JsonNode payload = json.get("payload");

                // 判斷 chessId 是否是自己的棋子（暫略）
                String chessId = payload.get("id").asText();

                boolean isPlayer1 = senderToken.equals(match.getPlayer1Id());
                boolean isValid = (isPlayer1 && chessId.startsWith("p1_")) || (!isPlayer1 && chessId.startsWith("p2_"));

                // System.out.println("收到 chessId = " + chessId);
                // System.out.println("isPlayer1 = " + isPlayer1);
                // System.out.println("isValid = " + isValid);

                if (!isValid) {
                    sendToToken(senderToken, "{\"type\":\"error\",\"message\":\"不能操作對手的棋子\"}");
                    return;
                }


                MoveCommand cmd;

                // 加一個限制最大最小力
                try {
                    cmd = mapper.readValue(payload.toString(), MoveCommand.class);
                } catch (Exception ex) {
                    session.sendMessage(new TextMessage("{\"type\":\"error\",\"message\":\"MoveCommand 解析失敗\"}"));
                    return;
                }
            
                cmd.setSessionId(matchId);
            
                if (!physicsEngineService.hasSession(matchId)) {
                    List<EntityState> initStates = new GameService().initEntityStates();
                    physicsEngineService.createSession(matchId, initStates);
                }
            
                physicsEngineService.enqueue(cmd);
            
                // 進球模擬（簡化處理）
                if (isPlayer1) {
                    match.setScore1(match.getScore1() + 1);
                } else {
                    match.setScore2(match.getScore2() + 1);
                }
            
                if (match.getScore1() >= 7 || match.getScore2() >= 7) {
                    match.setMatchStatus("finished");
                    match.setEndedAt(LocalDateTime.now());
                    match.setWinnerId(match.getScore1() >= 7 ? match.getPlayer1Id() : match.getPlayer2Id());
                    matchRepository.save(match);
                
                    roomRepository.deleteById(match.getRoomId());
                
                    String msg = String.format("{\"type\":\"game_over\",\"winner\":\"%s\"}", match.getWinnerId());
                    sendToToken(match.getPlayer1Id(), msg);
                    sendToToken(match.getPlayer2Id(), msg);
                    return;
                }
            
                // 等待物理結束後自動切換回合
                match.setWaitingForTurnSwitch(true);
                matchRepository.save(match);
            }
        } catch (Exception e) {
            System.out.println("全域 JSON解析失敗：" + e.getMessage());
            e.printStackTrace();
            session.sendMessage(new TextMessage("{\"type\":\"error\",\"message\":\"JSON格式錯誤\"}"));
        }
    }

    // 系統測試中 為求方便暫時關閉
    // @Scheduled(fixedRate = 1000)
    // public void autoLogout() {
    // LocalDateTime now = LocalDateTime.now();

    // for (Map.Entry<String, LocalDateTime> entry : disconnectTimeMap.entrySet()) {
    // String token = entry.getKey();
    // LocalDateTime disconnectTime = entry.getValue();

    // if (Duration.between(disconnectTime, now).getSeconds() >= 300) {
    // PlayerModel player = playerRepository.findByToken(token);
    // if (player != null) {
    // player.setToken(null);
    // playerRepository.save(player);
    // System.out.println("已清除資料庫中的 token：" + token);
    // }

    // tokenSessionMap.remove(token);
    // sessionIdToTokenMap.values().remove(token);
    // disconnectTimeMap.remove(token);
    // System.out.println("登出並移除記憶體記錄：" + token);
    // }
    // }
    // }

    @Scheduled(fixedRate = 1000)
    public void checkTimeouts() {
        LocalDateTime now = LocalDateTime.now();

        for (Map.Entry<String, LocalDateTime> entry : GameWebSocketHandler.disconnectTimeMap.entrySet()) {
            String token = entry.getKey();
            LocalDateTime disconnectTime = entry.getValue();

            if (Duration.between(disconnectTime, now).getSeconds() >= 60) {
                // 找到正在進行的對局
                List<MatchModel> matchesAsP1 = matchRepository.findAllByPlayer1Id(token);
                List<MatchModel> matchesAsP2 = matchRepository.findAllByPlayer2Id(token);

                MatchModel match = Stream.concat(matchesAsP1.stream(), matchesAsP2.stream())
                        .filter(m -> "playing".equals(m.getMatchStatus()))
                        .findFirst()
                        .orElse(null);

                if (match != null) {
                String winner = token.equals(match.getPlayer1Id()) ? match.getPlayer2Id() : match.getPlayer1Id();

                match.setMatchStatus("finished");
                match.setWinnerId(winner);
                match.setEndedAt(now);
                matchRepository.save(match);

                //加入刪除房間
                roomRepository.deleteById(match.getRoomId());

                String msg = String.format(
                        "{\"type\":\"game_over\",\"winner\":\"%s\",\"reason\":\"disconnect\"}",
                        winner);

                GameWebSocketHandler.sendToToken(match.getPlayer1Id(), msg);
                GameWebSocketHandler.sendToToken(match.getPlayer2Id(), msg);

                System.out.println("玩家斷線超過 60 秒，自動判定敗方為：" + token);

                GameWebSocketHandler.disconnectTimeMap.remove(token);
            }
            }
        }
    }

    public void broadcast(String matchId, String update) throws Exception {
        MatchModel match = matchRepository.findById(matchId).orElse(null);
        if (match == null) {
            System.out.println("找不到 matchId：" + matchId);
            return;
        }
    
        String player1Token = match.getPlayer1Id();
        String player2Token = match.getPlayer2Id();
    
        WebSocketSession session1 = tokenSessionMap.get(player1Token);
        WebSocketSession session2 = tokenSessionMap.get(player2Token);
    
        if (session1 != null && session1.isOpen()) {
            session1.sendMessage(new TextMessage(update));
        }
    
        if (session2 != null && session2.isOpen()) {
            session2.sendMessage(new TextMessage(update));
        }
    }
}
