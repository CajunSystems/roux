package com.cajunsystems.roux.capability;

import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.runtime.DefaultEffectRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LayerCompositionTest {

    // Capability families
    sealed interface StoreOps extends Capability<String> {
        record Get(String key) implements StoreOps {}
        record Put(String key, String value) implements StoreOps {}
    }

    sealed interface LogOps extends Capability<String> {
        record Info(String message) implements LogOps {}
    }

    sealed interface ConfigOps extends Capability<String> {
        record GetConfig(String key) implements ConfigOps {}
    }

    sealed interface EmailOps extends Capability<String> {
        record Send(String to, String body) implements EmailOps {}
    }

    private Map<String, String> store;
    private List<String> logSink;
    private Map<String, String> config;
    private DefaultEffectRuntime runtime;

    @BeforeEach
    void setUp() {
        store = new HashMap<>();
        logSink = new ArrayList<>();
        config = new HashMap<>();
        config.put("email.host", "smtp.example.com");
        runtime = DefaultEffectRuntime.create();
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    Layer<Empty, RuntimeException, StoreOps> storeLayer() {
        return Layer.succeed(StoreOps.class, cap -> switch (cap) {
            case StoreOps.Get g -> store.getOrDefault(g.key(), "missing");
            case StoreOps.Put p -> { store.put(p.key(), p.value()); yield "ok"; }
        });
    }

    Layer<Empty, RuntimeException, LogOps> logLayer() {
        return Layer.succeed(LogOps.class, cap -> switch (cap) {
            case LogOps.Info m -> { logSink.add(m.message()); yield "logged"; }
        });
    }

    // --- and() tests ---

    @Test
    void and_buildsBothLeafLayers() throws Throwable {
        Layer<Empty, Throwable, With<StoreOps, LogOps>> appLayer = storeLayer().and(logLayer());

        HandlerEnv<With<StoreOps, LogOps>> env =
                runtime.unsafeRun(appLayer.build(HandlerEnv.empty()));

        String putResult = env.toHandler().handle(new StoreOps.Put("k", "v"));
        assertEquals("ok", putResult);

        String getResult = env.toHandler().handle(new StoreOps.Get("k"));
        assertEquals("v", getResult);

        String logResult = env.toHandler().handle(new LogOps.Info("hello"));
        assertEquals("logged", logResult);
        assertEquals(1, logSink.size());
    }

    @Test
    void and_threeLayersViaNesting() throws Throwable {
        Layer<Empty, RuntimeException, ConfigOps> configLayer = Layer.succeed(ConfigOps.class, cap -> switch (cap) {
            case ConfigOps.GetConfig g -> config.getOrDefault(g.key(), "unset");
        });

        Layer<Empty, Throwable, With<With<StoreOps, LogOps>, ConfigOps>> triLayer =
                storeLayer().and(logLayer()).and(configLayer);

        HandlerEnv<With<With<StoreOps, LogOps>, ConfigOps>> env =
                runtime.unsafeRun(triLayer.build(HandlerEnv.empty()));

        assertEquals("ok", env.toHandler().handle(new StoreOps.Put("x", "42")));
        assertEquals("42", env.toHandler().handle(new StoreOps.Get("x")));
        assertEquals("logged", env.toHandler().handle(new LogOps.Info("test")));
        assertEquals("smtp.example.com", env.toHandler().handle(new ConfigOps.GetConfig("email.host")));
    }

    // --- andProvide() tests ---

    @Test
    void andProvide_configFeedsEmailLayer() throws Throwable {
        Layer<Empty, RuntimeException, ConfigOps> configLayer = Layer.succeed(ConfigOps.class, cap -> switch (cap) {
            case ConfigOps.GetConfig g -> config.getOrDefault(g.key(), "unset");
        });

        List<String> sentEmails = new ArrayList<>();
        Layer<ConfigOps, Exception, EmailOps> emailLayer = Layer.fromEffect(
                EmailOps.class,
                configEnv -> Effect.suspend(() -> {
                    String host = configEnv.toHandler().handle(new ConfigOps.GetConfig("email.host"));
                    return (EmailOps cap) -> switch (cap) {
                        case EmailOps.Send s -> {
                            sentEmails.add(host + ":" + s.to() + ":" + s.body());
                            yield "sent";
                        }
                    };
                })
        );

        Layer<Empty, Throwable, With<ConfigOps, EmailOps>> combined = configLayer.andProvide(emailLayer);

        HandlerEnv<With<ConfigOps, EmailOps>> env =
                runtime.unsafeRun(combined.build(HandlerEnv.empty()));

        String result = env.toHandler().handle(new EmailOps.Send("alice@example.com", "hello"));
        assertEquals("sent", result);
        assertEquals(1, sentEmails.size());
        assertEquals("smtp.example.com:alice@example.com:hello", sentEmails.get(0));

        String host = env.toHandler().handle(new ConfigOps.GetConfig("email.host"));
        assertEquals("smtp.example.com", host);
    }

    @Test
    void andProvide_retainsBothOutputs() throws Throwable {
        Layer<Empty, RuntimeException, ConfigOps> configLayer = Layer.succeed(ConfigOps.class, cap -> switch (cap) {
            case ConfigOps.GetConfig g -> config.getOrDefault(g.key(), "unset");
        });

        config.put("log.prefix", "[INFO] ");
        Layer<ConfigOps, RuntimeException, LogOps> logFromConfigLayer = Layer.fromEffect(
                LogOps.class,
                configEnv -> Effect.suspend(() -> {
                    String prefix = configEnv.toHandler().handle(new ConfigOps.GetConfig("log.prefix"));
                    return (LogOps cap) -> switch (cap) {
                        case LogOps.Info m -> { logSink.add(prefix + m.message()); yield "ok"; }
                    };
                })
        );

        Layer<Empty, Throwable, With<ConfigOps, LogOps>> combined = configLayer.andProvide(logFromConfigLayer);
        HandlerEnv<With<ConfigOps, LogOps>> env =
                runtime.unsafeRun(combined.build(HandlerEnv.empty()));

        env.toHandler().handle(new LogOps.Info("message"));
        assertEquals("[INFO] message", logSink.get(0));

        String configVal = env.toHandler().handle(new ConfigOps.GetConfig("log.prefix"));
        assertEquals("[INFO] ", configVal);
    }
}
