package com.cajunsystems.roux.capability;

import com.cajunsystems.roux.Effect;
import com.cajunsystems.roux.EffectWithEnv;
import com.cajunsystems.roux.runtime.DefaultEffectRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for the full Layer → Env → EffectWithEnv workflow.
 */
class LayerIntegrationTest {

    // Capability families
    sealed interface DbOps extends Capability<String> {
        record Query(String sql) implements DbOps {}
        record Execute(String sql) implements DbOps {}
    }

    sealed interface AuditOps extends Capability<String> {
        record Log(String event) implements AuditOps {}
    }

    sealed interface ConfigOps extends Capability<String> {
        record Get(String key) implements ConfigOps {}
    }

    sealed interface EmailOps extends Capability<String> {
        record Send(String to, String subject) implements EmailOps {}
    }

    private Map<String, String> database;
    private List<String> auditLog;
    private Map<String, String> config;
    private List<String> sentEmails;
    private DefaultEffectRuntime runtime;

    @BeforeEach
    void setUp() {
        database = new HashMap<>();
        auditLog = new ArrayList<>();
        config = new HashMap<>();
        sentEmails = new ArrayList<>();
        runtime = DefaultEffectRuntime.create();

        database.put("user:42", "Alice");
        config.put("smtp.host", "smtp.example.com");
        config.put("smtp.from", "noreply@example.com");
    }

    @AfterEach
    void tearDown() throws Exception {
        runtime.close();
    }

    Layer<Empty, RuntimeException, DbOps> dbLayer() {
        return Layer.succeed(DbOps.class, cap -> switch (cap) {
            case DbOps.Query q -> database.getOrDefault(q.sql(), "not-found");
            case DbOps.Execute e -> { database.put(e.sql(), "executed"); yield "ok"; }
        });
    }

    Layer<Empty, RuntimeException, AuditOps> auditLayer() {
        return Layer.succeed(AuditOps.class, cap -> switch (cap) {
            case AuditOps.Log l -> { auditLog.add(l.event()); yield "logged"; }
        });
    }

    Layer<Empty, RuntimeException, ConfigOps> configLayer() {
        return Layer.succeed(ConfigOps.class, cap -> switch (cap) {
            case ConfigOps.Get g -> config.getOrDefault(g.key(), "unset");
        });
    }

    Layer<ConfigOps, Exception, EmailOps> emailLayer() {
        return Layer.fromEffect(
                EmailOps.class,
                configEnv -> Effect.suspend(() -> {
                    String host = configEnv.toHandler().handle(new ConfigOps.Get("smtp.host"));
                    String from = configEnv.toHandler().handle(new ConfigOps.Get("smtp.from"));
                    return (EmailOps cap) -> switch (cap) {
                        case EmailOps.Send s -> {
                            sentEmails.add(host + "|" + from + "|" + s.to() + "|" + s.subject());
                            yield "sent";
                        }
                    };
                })
        );
    }

    @Test
    void horizontalComposition_fullWorkflow() throws Throwable {
        Layer<Empty, Throwable, With<DbOps, AuditOps>> appLayer = dbLayer().and(auditLayer());
        HandlerEnv<With<DbOps, AuditOps>> env =
                runtime.unsafeRun(appLayer.build(HandlerEnv.empty()));

        EffectWithEnv<With<DbOps, AuditOps>, Throwable, String> fetchAndAudit =
                EffectWithEnv.of(
                        new DbOps.Query("user:42")
                                .toEffect()
                                .flatMap(name ->
                                        new AuditOps.Log("fetched user: " + name)
                                                .toEffect()
                                                .map(__ -> name)
                                )
                );

        String result = fetchAndAudit.run(env, runtime);
        assertEquals("Alice", result);
        assertEquals(List.of("fetched user: Alice"), auditLog);
    }

    @Test
    void verticalComposition_configFeedsEmail() throws Throwable {
        Layer<Empty, Throwable, With<ConfigOps, EmailOps>> combined =
                configLayer().andProvide(emailLayer());
        HandlerEnv<With<ConfigOps, EmailOps>> env =
                runtime.unsafeRun(combined.build(HandlerEnv.empty()));

        EffectWithEnv<With<ConfigOps, EmailOps>, Throwable, String> sendWelcome =
                EffectWithEnv.of(
                        new EmailOps.Send("alice@example.com", "Welcome!").toEffect()
                );

        String result = sendWelcome.run(env, runtime);
        assertEquals("sent", result);
        assertEquals(1, sentEmails.size());
        assertEquals("smtp.example.com|noreply@example.com|alice@example.com|Welcome!", sentEmails.get(0));
    }

    @Test
    void threeCapabilities_horizontalComposition() throws Throwable {
        Layer<Empty, Throwable, With<With<DbOps, AuditOps>, ConfigOps>> triLayer =
                dbLayer().and(auditLayer()).and(configLayer());
        HandlerEnv<With<With<DbOps, AuditOps>, ConfigOps>> env =
                runtime.unsafeRun(triLayer.build(HandlerEnv.empty()));

        String dbResult = env.toHandler().handle(new DbOps.Query("user:42"));
        assertEquals("Alice", dbResult);

        env.toHandler().handle(new AuditOps.Log("accessed Alice"));
        assertEquals(1, auditLog.size());

        String cfgResult = env.toHandler().handle(new ConfigOps.Get("smtp.host"));
        assertEquals("smtp.example.com", cfgResult);
    }

    @Test
    void handlerEnvOf_usesCorrectReturnType() throws Throwable {
        HandlerEnv<DbOps> env = HandlerEnv.of(DbOps.class, cap -> switch (cap) {
            case DbOps.Query q -> "query-result";
            case DbOps.Execute e -> "execute-result";
        });

        String result = env.toHandler().handle(new DbOps.Query("anything"));
        assertEquals("query-result", result);
    }

    @Test
    void pureEffect_runsWithEmptyEnv() throws Exception {
        EffectWithEnv<Empty, RuntimeException, Integer> constant =
                EffectWithEnv.pure(Effect.succeed(42));

        int result = constant.run(HandlerEnv.empty(), runtime);
        assertEquals(42, result);
    }
}
