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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
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
		}
	}

	private String loadChannelId() {
		try {
			Path path = getConfigPath();
			if (Files.exists(path)) {
				return Files.readString(path).strip();
			}
		} catch (IOException e) {
		}
		return "";
	}

	private String sanitize(String input) {
		return input.replace("§", "");
	}

	private void disconnectIfConnected() {
		isEnabled = false;
		if (webSocket != null) {
			webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "");
			webSocket = null;
		}
	}

	@Override
	public void onInitializeClient() {
		String saved = loadChannelId();
		if (!saved.isEmpty()) {
			this.broadcasterId = saved;
		}
		registerCommands();
	}

	private void registerCommands() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(ClientCommands.literal("chzk")
					.then(ClientCommands.literal("set").then(ClientCommands.argument("id", StringArgumentType.word()).executes(ctx -> {
						this.broadcasterId = StringArgumentType.getString(ctx, "id");
						saveChannelId(this.broadcasterId);
						ctx.getSource().sendFeedback(Component.literal("§a[CHzK] 채널 ID 저장 완료: " + broadcasterId));
						return 1;
					})))
					.then(ClientCommands.literal("on").executes(ctx -> {
						if (this.broadcasterId.isEmpty()) {
							ctx.getSource().sendFeedback(Component.literal("§c[CHzK] 먼저 /chzk set <id> 로 채널 ID를 설정하세요."));
							return 0;
						}
						disconnectIfConnected();
						isEnabled = true;
						ctx.getSource().sendFeedback(Component.literal("§e[CHzK] 연결 중..."));
						connect();
						return 1;
					}))
					.then(ClientCommands.literal("off").executes(ctx -> {
						disconnectIfConnected();
						ctx.getSource().sendFeedback(Component.literal("§c[CHzK] 연결을 종료했습니다."));
						return 1;
					}))
			);
		});
	}

	private void connect() {
		String liveStatusUrl = "https://api.chzzk.naver.com/polling/v2/channels/" + this.broadcasterId + "/live-status";

		HttpRequest statusRequest = HttpRequest.newBuilder()
				.uri(URI.create(liveStatusUrl))
				.GET()
				.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
				.build();

		httpClient.sendAsync(statusRequest, HttpResponse.BodyHandlers.ofString())
				.thenAccept(statusResponse -> {
					if (statusResponse.statusCode() == 200) {
						try {
							JsonObject json = gson.fromJson(statusResponse.body(), JsonObject.class);
							JsonObject content = json.getAsJsonObject("content");

							if ("CLOSE".equals(content.get("status").getAsString())) {
								sendMessageToGame("§c[CHzK] 스트리머가 오프라인 상태입니다.");
								return;
							}

							String chatChannelId = content.get("chatChannelId").getAsString();
							fetchAccessToken(chatChannelId);

						} catch (Exception e) {
							sendMessageToGame("§c[CHzK] 방송 정보를 불러오지 못했습니다.");
						}
					}
				}).exceptionally(e -> {
					sendMessageToGame("§c[CHzK] 서버 연결에 실패했습니다.");
					return null;
				});
	}

	private void fetchAccessToken(String chatChannelId) {
		String encodedId = URLEncoder.encode(chatChannelId, StandardCharsets.UTF_8);
		String tokenUrl = "https://comm-api.game.naver.com/nng_main/v1/chats/access-token?channelId=" + encodedId + "&chatType=STREAMING";

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(tokenUrl))
				.GET()
				.header("User-Agent", "Mozilla/5.0")
				.build();

		httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenAccept(response -> {
					if (response.statusCode() == 200) {
						try {
							JsonObject json = gson.fromJson(response.body(), JsonObject.class);
							String accessToken = json.getAsJsonObject("content").get("accessToken").getAsString();
							connectWebSocket(accessToken, chatChannelId);
						} catch (Exception e) {
							sendMessageToGame("§c[CHzK] 토큰 발급에 실패했습니다.");
						}
					}
				}).exceptionally(e -> {
					sendMessageToGame("§c[CHzK] 토큰 서버 연결에 실패했습니다.");
					return null;
				});
	}

	private void connectWebSocket(String accessToken, String chatChannelId) {
		sendMessageToGame("§e[CHzK] 채팅 서버에 연결 중...");

		httpClient.newWebSocketBuilder().buildAsync(URI.create("wss://kr-ss1.chat.naver.com/chat"), new WebSocket.Listener() {
			private final StringBuffer messageBuffer = new StringBuffer();

			@Override
			public void onOpen(WebSocket webSocket) {
				CHzK.this.webSocket = webSocket;

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
				authPacket.addProperty("tid", System.currentTimeMillis());
				authPacket.add("bdy", authBdy);

				webSocket.sendText(gson.toJson(authPacket), true);
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

				try {
					JsonObject json = gson.fromJson(fullMessage, JsonObject.class);
					int cmd = json.has("cmd") ? json.get("cmd").getAsInt() : -1;

					if (cmd == 0) {
						JsonObject pong = new JsonObject();
						pong.addProperty("ver", "2");
						pong.addProperty("cmd", 10000);
						webSocket.sendText(gson.toJson(pong), true);
					} else if (cmd == 10100) {
						sendMessageToGame("§a[CHzK] 채팅 연결 완료!");
					} else if (cmd == 93101) {
						if (json.has("bdy") && json.get("bdy").isJsonArray()) {
							json.getAsJsonArray("bdy").forEach(element -> {
								try {
									JsonObject chat = element.getAsJsonObject();

									if (!chat.has("profile") || chat.get("profile").isJsonNull()) return;
									if (chat.has("msgTypeCode") && chat.get("msgTypeCode").getAsInt() != 1) return;

									String profileStr = chat.get("profile").getAsString();
									if (profileStr == null || profileStr.isEmpty()) return;

									JsonObject profile = gson.fromJson(profileStr, JsonObject.class);
									String nickname = sanitize(profile.has("nickname") ? profile.get("nickname").getAsString() : "익명");
									String msg = sanitize(chat.has("msg") ? chat.get("msg").getAsString() : "");

									if (!msg.isEmpty()) {
										sendMessageToGame("§7<" + nickname + "> §f" + msg);
									}
								} catch (Exception ignored) {
								}
							});
						}
					}
				} catch (Exception ignored) {
				}

				return WebSocket.Listener.super.onText(webSocket, data, last);
			}

			@Override
			public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
				sendMessageToGame("§c[CHzK] 연결이 끊어졌습니다. (코드: " + statusCode + ")");
				return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
			}

			@Override
			public void onError(WebSocket webSocket, Throwable error) {
				sendMessageToGame("§c[CHzK] 웹소켓 오류가 발생했습니다.");
				WebSocket.Listener.super.onError(webSocket, error);
			}
		}).thenAccept(ws -> CHzK.this.webSocket = ws);
	}

	private void startHeartbeat(WebSocket webSocket) {
		Thread heartbeatThread = new Thread(() -> {
			try {
				while (isEnabled && !webSocket.isInputClosed()) {
					Thread.sleep(20000);
					if (isEnabled && !webSocket.isInputClosed()) {
						JsonObject ping = new JsonObject();
						ping.addProperty("ver", "2");
						ping.addProperty("cmd", 0);
						webSocket.sendText(gson.toJson(ping), true);
					}
				}
			} catch (InterruptedException ignored) {
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