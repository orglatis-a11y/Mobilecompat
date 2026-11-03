package com.mobilecompat;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.stream.Stream;

public class MobileCompat implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("MobileCompat");

    @Override
    public void onInitializeClient() {
        Path cfgDir = FabricLoader.getInstance().getConfigDir();
        Properties cfg = loadConfig(cfgDir.resolve("mobilecompat.properties"));

        boolean force = Boolean.parseBoolean(cfg.getProperty("force", "false"));
        if (!force && !isMobileLauncher()) {
            LOG.info("Not running under a mobile launcher; Mobile Compat is idle.");
            return;
        }
        LOG.info("Mobile launcher detected (arch={}, renderer={}).",
                System.getProperty("os.arch"), System.getenv("POJAV_RENDERER"));

        if (Boolean.parseBoolean(cfg.getProperty("scanMods", "true"))) {
            writeReport(cfgDir.resolve("mobilecompat-report.txt"));
        }
        if (Boolean.parseBoolean(cfg.getProperty("applyDefaults", "true"))) {
            int view = Integer.parseInt(cfg.getProperty("renderDistance", "6"));
            int sim = Integer.parseInt(cfg.getProperty("simulationDistance", "5"));
            int fps = Integer.parseInt(cfg.getProperty("maxFps", "60"));
            Path marker = cfgDir.resolve("mobilecompat.applied");
            if (!Files.exists(marker)) {
                ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
                    tune(client, view, sim, fps);
                    try { Files.writeString(marker, "applied"); } catch (IOException ignored) {}
                });
            }
        }
    }

    private static boolean isMobileLauncher() {
        for (String key : System.getenv().keySet()) {
            if (key.startsWith("POJAV_") || key.startsWith("ZALITH_")) return true;
        }
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String vendor = System.getProperty("java.vendor", "").toLowerCase(Locale.ROOT);
        return vendor.contains("android") || (System.getProperty("java.vm.vendor", "").toLowerCase(Locale.ROOT).contains("android") && arch.contains("aarch64"));
    }

    private static void tune(MinecraftClient client, int view, int sim, int fps) {
        try {
            GameOptions o = client.options;
            o.getViewDistance().setValue(view);
            o.getSimulationDistance().setValue(sim);
            o.getMaxFps().setValue(fps);
            o.write();
            LOG.info("Applied mobile defaults: render={}, simulation={}, maxFps={}", view, sim, fps);
        } catch (Throwable t) {
            LOG.warn("Could not apply mobile defaults", t);
        }
    }

    private static void writeReport(Path out) {
        List<String> lines = new ArrayList<>();
        lines.add("Mobile Compat report");
        lines.add("Mods bundling native libraries (inside the jar). Nested jars are not scanned.");
        lines.add("");
        int problems = 0;

        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String id = mod.getMetadata().getId();
            if (id.equals("java") || id.equals("minecraft") || id.equals("fabricloader")) continue;
            for (Path root : mod.getRootPaths()) {
                try (Stream<Path> s = Files.walk(root)) {
                    for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                        String name = p.toString().toLowerCase(Locale.ROOT);
                        String verdict = null;
                        if (name.endsWith(".dll")) verdict = "BROKEN on mobile: Windows-only (.dll)";
                        else if (name.endsWith(".dylib") || name.endsWith(".jnilib")) verdict = "BROKEN on mobile: macOS-only";
                        else if (name.endsWith(".so")) {
                            boolean arm = name.contains("aarch64") || name.contains("arm64") || name.contains("android");
                            verdict = arm ? "ok: has an ARM/Android build" : "LIKELY BROKEN: .so with no ARM/Android path (probably x86_64)";
                        }
                        if (verdict != null) {
                            if (!verdict.startsWith("ok")) problems++;
                            lines.add(id + " -> " + root.relativize(p) + " : " + verdict);
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    LOG.debug("Skipping {}: {}", id, e.toString());
                }
            }
        }
        lines.add("");
        lines.add(problems == 0 ? "No obviously incompatible native libraries found."
                : problems + " suspicious native file(s). Remove those mods or find ARM builds.");
        try {
            Files.write(out, lines);
            lines.forEach(l -> { if (l.contains("BROKEN")) LOG.warn(l); });
            LOG.info("Wrote {}", out);
        } catch (IOException e) {
            LOG.warn("Could not write report", e);
        }
    }

    private static Properties loadConfig(Path file) {
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) { p.load(in); } catch (IOException ignored) {}
        } else {
            p.setProperty("force", "false");
            p.setProperty("applyDefaults", "true");
            p.setProperty("scanMods", "true");
            p.setProperty("renderDistance", "6");
            p.setProperty("simulationDistance", "5");
            p.setProperty("maxFps", "60");
            try (OutputStream out = Files.newOutputStream(file)) { p.store(out, "Mobile Compat config"); } catch (IOException ignored) {}
        }
        return p;
    }
}
