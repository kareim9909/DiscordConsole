package com.example.discordconsole;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

public class DiscordConsole extends JavaPlugin {

    private JDA jda;

    @Override
    public void onEnable() {
        saveDefaultConfig();
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
                        c -> out.add(PlainTextComponentSerializer.plainText().serialize(c)));
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
