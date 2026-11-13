package com.example.discordconsole;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.awt.Color;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

public class DiscordConsole extends JavaPlugin {

    private JDA jda;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(new GameEvents(), this);
        startBot();
    }

    @Override
    public void onDisable() {
        stopBot();
    }

    private void startBot() {
        String token = getConfig().getString("token", "");
        if (token.isBlank() || token.equals("PUT_BOT_TOKEN_HERE")) {
            getLogger().severe("حط توكن البوت في config.yml");
            return;
        }
        // التشغيل بخيط منفصل حتى ما يعلق السيرفر
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                jda = JDABuilder.createLight(token,
                                GatewayIntent.GUILD_MESSAGES,
                                GatewayIntent.MESSAGE_CONTENT)
                        .addEventListeners(new Listener())
                        .build();
                jda.awaitReady();
                getLogger().info("البوت اشتغل: " + jda.getSelfUser().getAsTag());
            } catch (Exception e) {
                getLogger().severe("فشل تشغيل البوت: " + e.getMessage());
            }
        });
    }

    private void stopBot() {
        if (jda != null) {
            jda.shutdownNow();
            jda = null;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            stopBot();
            startBot();
            sender.sendMessage("تم إعادة التحميل.");
            return true;
        }
        return false;
    }

    private boolean isAllowed(Member member) {
        FileConfiguration c = getConfig();
        if (c.getStringList("allowed-user-ids").contains(member.getId())) return true;
        List<String> roles = c.getStringList("allowed-role-ids");
        for (Role r : member.getRoles()) {
            if (roles.contains(r.getId())) return true;
        }
        return false;
    }

    // ===== إرسال للقنوات =====

    private void sendToChannel(String channelId, String text) {
        if (jda == null || channelId == null || channelId.isBlank() || channelId.matches("0+")) return;
        var channel = jda.getTextChannelById(channelId);
        if (channel != null) channel.sendMessage(text).queue();
    }

    private void sendEmbedToChannel(String channelId, EmbedBuilder embed) {
        if (jda == null || channelId == null || channelId.isBlank() || channelId.matches("0+")) return;
        var channel = jda.getTextChannelById(channelId);
        if (channel != null) channel.sendMessageEmbeds(embed.build()).queue();
    }

    // يمنع الماركدوان والمنشن (@everyone) من التأثير على ديسكورد
    private String safe(String s) {
        return MarkdownSanitizer.escape(s).replace("@", "@\u200B");
    }

    private EmbedBuilder playerEmbed(String title, String name, Color color, String description) {
        String head = "https://mc-heads.net/avatar/" + name + "/128";
        return new EmbedBuilder()
                .setColor(color)
                .setTitle(title)
                .setDescription(description)
                .setAuthor(name, null, head)
                .setThumbnail(head)
                .setTimestamp(java.time.Instant.now());
    }

    // ===== أحداث اللعبة =====

    private class GameEvents implements org.bukkit.event.Listener {

        @EventHandler
        public void onJoin(PlayerJoinEvent e) {
            String name = e.getPlayer().getName();
            sendEmbedToChannel(getConfig().getString("join-leave-channel-id", ""),
                    playerEmbed("Player Joined", name, new Color(0x2ECC71),
                            "**" + safe(name) + "** joined the server"));
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent e) {
            String name = e.getPlayer().getName();
            sendEmbedToChannel(getConfig().getString("join-leave-channel-id", ""),
                    playerEmbed("Player Left", name, new Color(0xE74C3C),
                            "**" + safe(name) + "** left the server"));
        }

        @EventHandler
        public void onChat(AsyncPlayerChatEvent e) {
            sendToChannel(getConfig().getString("chat-channel-id", ""),
                    "💬 **" + safe(e.getPlayer().getName()) + "**: " + safe(e.getMessage()));
        }
    }

    // ===== أوامر ديسكورد =====

    private class Listener extends ListenerAdapter {
        @Override
        public void onMessageReceived(MessageReceivedEvent e) {
            if (e.getAuthor().isBot() || !e.isFromGuild() || e.getMember() == null) return;
            if (!e.getChannel().getId().equals(getConfig().getString("channel-id"))) return;
            if (!isAllowed(e.getMember())) return;

            String msg = e.getMessage().getContentRaw().trim();
            String prefix = getConfig().getString("prefix", "");
            if (!prefix.isEmpty()) {
                if (!msg.startsWith(prefix)) return;
                msg = msg.substring(prefix.length()).trim();
            }
            if (msg.startsWith("/")) msg = msg.substring(1);
            if (msg.isEmpty()) return;

            String first = msg.split("\\s+")[0].toLowerCase(Locale.ROOT);
            if (first.contains(":")) first = first.substring(first.indexOf(':') + 1);
            for (String b : getConfig().getStringList("blocked-commands")) {
                if (b.equalsIgnoreCase(first)) {
                    e.getChannel().sendMessage("❌ هذا الأمر ممنوع.").queue();
                    return;
                }
            }

            final String command = msg;
            getLogger().info(e.getAuthor().getAsTag() + " نفّذ من ديسكورد: " + command);

            Bukkit.getScheduler().runTask(DiscordConsole.this, () -> {
                List<String> out = new CopyOnWriteArrayList<>();
                CommandSender sender = Bukkit.createCommandSender(
                        c -> out.add(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                                .plainText().serialize(c)));
                boolean ok;
                try {
                    ok = Bukkit.dispatchCommand(sender, command);
                } catch (Exception ex) {
                    e.getChannel().sendMessage("⚠️ خطأ: " + ex.getMessage()).queue();
                    return;
                }
                if (!getConfig().getBoolean("send-output", true)) {
                    e.getMessage().addReaction(net.dv8tion.jda.api.entities.emoji.Emoji.fromUnicode(ok ? "✅" : "❌")).queue();
                    return;
                }
                String text = out.isEmpty() ? (ok ? "✅ تم التنفيذ." : "❌ أمر غير معروف أو فشل.") : String.join("\n", out);
                if (text.length() > 1900) text = text.substring(0, 1900) + "...";
                e.getChannel().sendMessage("```\n" + text.replace("```", "'''") + "\n```").queue();
            });
        }
    }
}
