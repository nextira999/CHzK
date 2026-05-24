package io.github.nextira999;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

public class CHzK implements ClientModInitializer {
	private final HttpClient httpClient = HttpClient.newHttpClient();
	private WebSocket webSocket;
	private String broadcasterId = "";
	private boolean isEnabled = false;
	private final Gson gson = new Gson();

	private Path getConfigPath() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("chzk_config.txt");
	}

	private void saveChannelId(String id) {
		try {
			Files.writeString(getConfigPath(), id);
		} catch (IOException e) {
			System.err.println("[CHzK] 채널 ID 저장 실패: " + e.getMessage());
		}
	}

	private String loadChannelId() {
		try {
			Path path = getConfigPath();
			if (Files.exists(path)) {
				return Files.readString(path).strip();
			}
		} catch (IOException e) {
			System.err.println("[CHzK] 채널 ID 불러오기 실패: " + e.getMessage());
		}
		return "";
	}

	@Override
	public void onInitializeClient() {
		String saved = loadChannelId();
		if (!saved.isEmpty()) {
			this.broadcasterId = saved;
			System.out.println("[CHzK] 저장된 채널 ID 불러옴: " + broadcasterId);
		}
		registerCommands();
	}

	private void registerCommands() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(ClientCommands.literal("chzk")
					.then(ClientCommands.literal("set").then(ClientCommands.argument("id", StringArgumentType.word()).executes(ctx -> {
						this.broadcasterId = StringArgumentType.getString(ctx, "id");
						saveChannelId(this.broadcasterId);
						ctx.getSource().sendFeedback(Component.literal("§a[CHzK] 방송 채널 ID 설정 및 저장 완료: " + broadcasterId));
						return 1;
					})))
					.then(ClientCommands.literal("on").executes(ctx -> {
						if (this.broadcasterId.isEmpty()) {
							ctx.getSource().sendFeedback(Component.literal("§c[CHzK] 먼저 /chzk set <id> 명령어로 채널 ID를 설정하세요!"));
							return 0;
						}
						this.isEnabled = true;
						ctx.getSource().sendFeedback(Component.literal("§e[CHzK] 치지직 방송 상태를 확인 중입니다..."));
						System.out.println("[디버그] 연결 시도 시작: " + this.broadcasterId);
						connect();
						return 1;
					}))
					.then(ClientCommands.literal("off").executes(ctx -> {
						this.isEnabled = false;
						if (webSocket != null) {
							webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "사용자 요청으로 종료");
							webSocket = null;
						}
						ctx.getSource().sendFeedback(Component.literal("§c[CHzK] 치지직 채팅 연결을 수동으로 종료했습니다."));
						return 1;
					}))
			);
		});
	}

	private void connect() {
		String liveStatusUrl = "https://api.chzzk.naver.com/polling/v2/channels/" + this.broadcasterId + "/live-status";
		System.out.println("[디버그] 1단계 - 방송 상태 조회 요청: " + liveStatusUrl);

		HttpRequest statusRequest = HttpRequest.newBuilder()
				.uri(URI.create(liveStatusUrl))
				.GET()
				.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
				.build();

		httpClient.sendAsync(statusRequest, HttpResponse.BodyHandlers.ofString())
				.thenAccept(statusResponse -> {
					System.out.println("[디버그] 1단계 응답 코드: " + statusResponse.statusCode());
					if (statusResponse.statusCode() == 200) {
						try {
							JsonObject json = gson.fromJson(statusResponse.body(), JsonObject.class);
							JsonObject content = json.getAsJsonObject("content");

							if ("CLOSE".equals(content.get("status").getAsString())) {
								sendMessageToGame("§c[CHzK] 스트리머가 현재 오프라인 상태입니다.");
								return;
							}

							String chatChannelId = content.get("chatChannelId").getAsString();
							System.out.println("[디버그] 추출된 채팅 채널 ID: " + chatChannelId);
							sendMessageToGame("§a[CHzK] 채팅 채널 ID 획득! 토큰을 발급받습니다.");

							fetchAccessToken(chatChannelId);

						} catch (Exception e) {
							System.err.println("[디버그] 1단계 파싱 에러: " + e.getMessage());
						}
					}
				}).exceptionally(e -> {
					System.err.println("[디버그] 1단계 통신 에러: " + e.getMessage());
					return null;
				});
	}

	private void fetchAccessToken(String chatChannelId) {
		String tokenUrl = "https://comm-api.game.naver.com/nng_main/v1/chats/access-token?channelId=" + chatChannelId + "&chatType=STREAMING";
		System.out.println("[디버그] 2단계 - 토큰 발급 요청: " + tokenUrl);

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(tokenUrl))
				.GET()
				.header("User-Agent", "Mozilla/5.0")
				.build();

		httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenAccept(response -> {
					System.out.println("[디버그] 2단계 응답 코드: " + response.statusCode());
					if (response.statusCode() == 200) {
						try {
							JsonObject json = gson.fromJson(response.body(), JsonObject.class);
							String accessToken = json.getAsJsonObject("content").get("accessToken").getAsString();
							System.out.println("[디버그] 토큰 발급 성공: " + accessToken.substring(0, 10) + "...");

							connectWebSocket(accessToken, chatChannelId);
						} catch (Exception e) {
							System.err.println("[디버그] 2단계 토큰 파싱 에러: " + e.getMessage());
						}
					}
				}).exceptionally(e -> {
					System.err.println("[디버그] 2단계 통신 에러: " + e.getMessage());
					return null;
				});
	}

	private void connectWebSocket(String accessToken, String chatChannelId) {
		String wsUrl = "wss://kr-ss1.chat.naver.com/chat";
		sendMessageToGame("§e[CHzK] 웹소켓 연결 시도 중...");
		System.out.println("[디버그] 3단계 - 웹소켓 연결 주소: " + wsUrl);

		httpClient.newWebSocketBuilder().buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
			private final StringBuilder messageBuffer = new StringBuilder();

			@Override
			public void onOpen(WebSocket webSocket) {
				CHzK.this.webSocket = webSocket;
				System.out.println("[웹소켓] onOpen 이벤트 발생!");

				long timestamp = System.currentTimeMillis();

				JsonObject authBdy = new JsonObject();
				authBdy.addProperty("uid", "");
				authBdy.addProperty("devType", 2001);
				authBdy.addProperty("accTkn", accessToken);
				authBdy.addProperty("auth", "READ");

				JsonObject authPacket = new JsonObject();
				authPacket.addProperty("ver", "2");
				authPacket.addProperty("cmd", 100);
				authPacket.addProperty("svcid", "game");
				authPacket.addProperty("cid", chatChannelId);
				authPacket.addProperty("tid", timestamp);
				authPacket.add("bdy", authBdy);

				String authJsonStr = gson.toJson(authPacket);
				System.out.println("[웹소켓] 서버로 인증 패킷 전송: " + authJsonStr);
				webSocket.sendText(authJsonStr, true);

				startHeartbeat(webSocket);

				WebSocket.Listener.super.onOpen(webSocket);
			}

			@Override
			public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
				if (!isEnabled) return null;

				messageBuffer.append(data);
				if (!last) return WebSocket.Listener.super.onText(webSocket, data, last);

				String fullMessage = messageBuffer.toString();
				messageBuffer.setLength(0);

				System.out.println("[웹소켓 수신 RAW] " + fullMessage);

				try {
					JsonObject json = gson.fromJson(fullMessage, JsonObject.class);
					int cmd = json.has("cmd") ? json.get("cmd").getAsInt() : -1;

					if (cmd == 0) {
						System.out.println("[웹소켓] 서버로부터 Ping(0) 수신 -> Pong(10000) 응답 전송");
						webSocket.sendText("{\"ver\":\"2\",\"cmd\":10000}", true);
					}
					else if (cmd == 10100) {
						System.out.println("[웹소켓] 서버 인증 성공! (cmd: 10100)");
					}
					else if (cmd == 93101) {
						if (json.has("bdy") && json.get("bdy").isJsonArray()) {
							json.getAsJsonArray("bdy").forEach(element -> {
								try {
									JsonObject chat = element.getAsJsonObject();

									if (!chat.has("profile") || chat.get("profile").isJsonNull()) return;
									if (chat.has("msgTypeCode") && chat.get("msgTypeCode").getAsInt() != 1) return;

									String profileStr = chat.get("profile").getAsString();
									if (profileStr == null || profileStr.isEmpty()) return;

									JsonObject profile = gson.fromJson(profileStr, JsonObject.class);
									String nickname = profile.has("nickname") ? profile.get("nickname").getAsString() : "익명";
									String msg = chat.has("msg") ? chat.get("msg").getAsString() : "";

									if (!msg.isEmpty()) {
										sendMessageToGame("§7<" + nickname + "> §f" + msg);
									}
								} catch (Exception innerE) {
									System.err.println("[디버그] 개별 채팅 파싱 에러: " + innerE.getMessage());
								}
							});
						}
					}
				} catch (Exception e) {
					System.err.println("[디버그] JSON 파싱 에러: " + e.getMessage());
				}

				return WebSocket.Listener.super.onText(webSocket, data, last);
			}

			@Override
			public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
				System.out.println("[웹소켓] 닫힘 - 코드: " + statusCode + ", 이유: " + reason);
				sendMessageToGame("§c[CHzK] 채팅 서버와의 연결이 끊어졌습니다. (코드: " + statusCode + ")");
				return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
			}

			@Override
			public void onError(WebSocket webSocket, Throwable error) {
				System.err.println("[웹소켓] 에러 발생: " + error.getMessage());
				sendMessageToGame("§c[CHzK] 웹소켓 에러 발생! 콘솔을 확인하세요.");
				WebSocket.Listener.super.onError(webSocket, error);
			}
		}).thenAccept(ws -> CHzK.this.webSocket = ws);
	}

	private void startHeartbeat(WebSocket webSocket) {
		Thread heartbeatThread = new Thread(() -> {
			try {
				while (isEnabled && !webSocket.isInputClosed()) {
					Thread.sleep(20000); // 20초마다
					if (isEnabled && !webSocket.isInputClosed()) {
						JsonObject pingPacket = new JsonObject();
						pingPacket.addProperty("ver", "2");
						pingPacket.addProperty("cmd", 0); // PING
						webSocket.sendText(gson.toJson(pingPacket), true);
						System.out.println("[웹소켓] Heartbeat Ping 전송");
					}
				}
			} catch (InterruptedException e) {
				System.out.println("[웹소켓] Heartbeat 중단됨");
			}
		});
		heartbeatThread.setDaemon(true);
		heartbeatThread.start();
	}

	private void sendMessageToGame(String message) {
		if (Minecraft.getInstance().gui != null && Minecraft.getInstance().gui.getChat() != null) {
			Minecraft.getInstance().execute(() -> {
				Minecraft.getInstance().gui.getChat().addClientSystemMessage(Component.literal(message));
			});
		}
	}
}