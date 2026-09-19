package com.mineaudio.client.fabric;

import com.mineaudio.client.ProtocolClient;
import com.mineaudio.client.fabric.audio.ClientAudioManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MineAudio Client 入口：通道注册、握手、校时心跳。 */
public class MineAudioFabricClient implements ClientModInitializer {

    public static final String MOD_ID = "mineaudio-client";
    public static final String MINECRAFT_VERSION = "26.2";
    public static final Logger LOGGER = LoggerFactory.getLogger("MineAudio");

    private static final ClientAudioManager AUDIO = new ClientAudioManager();

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.serverboundPlay().register(MineAudioPayload.TYPE, MineAudioPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MineAudioPayload.TYPE, MineAudioPayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(MineAudioPayload.TYPE, (payload, context) ->
                context.client().execute(() -> ProtocolClient.get().handle(payload.data())));

        ProtocolClient.get().setListener(AUDIO);

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (AUDIO.available()) {
                ProtocolClient.get().setCapabilities(ClientAudioManager.CAPABILITIES);
                ProtocolClient.get().setFormats(ClientAudioManager.FORMATS);
            }
            ProtocolClient.get().onJoin(
                    bytes -> ClientPlayNetworking.send(new MineAudioPayload(bytes)),
                    version(), MINECRAFT_VERSION, locale());
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            AUDIO.closeAll();
            ProtocolClient.get().onDisconnect();
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            AUDIO.tick();
            ProtocolClient.get().tick(System.nanoTime() / 1_000_000);
        });

        registerLocalBridge();

        LOGGER.info("MineAudio Client {} 初始化完成（26.2，流媒体{}）",
                version(), AUDIO.available() ? "可用" : "不可用");
    }

    /** MineUI 客户端本地 API：注册 {local.mineaudio.*} 与 local:mineaudio.*；api mod 缺失时惰性降级。 */
    private static void registerLocalBridge() {
        try {
            MineAudioLocalBridge.register(AUDIO);
            LOGGER.info("MineUI 本地状态/动作已注册（命名空间 mineaudio）");
        } catch (Throwable t) {
            LOGGER.info("MineUI 客户端 API 不可用，本地绑定关闭（界面走服务端推送回退）：{}", t.toString());
        }
    }

    public static String version() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("dev");
    }

    private static String locale() {
        try {
            return Minecraft.getInstance().getLanguageManager().getSelected();
        } catch (Throwable t) {
            return "zh_cn";
        }
    }
}
