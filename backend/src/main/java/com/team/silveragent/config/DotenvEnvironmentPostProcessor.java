package com.team.silveragent.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 让直接 `mvn spring-boot:run`（非 docker）也能读到项目里的 .env 文件。
 * <p>
 * 动机：文档让用户在根目录建 .env，但 Spring Boot 本身不会读 .env，只有 docker compose 会注入；
 * 直接 mvn 跑时，用户往往以为 .env 生效了，实则没读到，导致模型一直显示未开启。
 * <p>
 * 加载顺序：先项目根目录（../.env，与 compose.yml 的根 .env 一致），再当前目录（./.env），
 * 后者覆盖前者。.env 的优先级最高，因此可以覆盖容器里默认写死的 AGENT_LLM_ENABLED=false。
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> props = loadDotenv();
        if (!props.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("dotenv", props));
        }
    }

    private Map<String, Object> loadDotenv() {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (Path path : List.of(Path.of("..", ".env"), Path.of(".env"))) {
            if (!Files.isRegularFile(path)) continue;
            try {
                for (String line : Files.readAllLines(path)) {
                    String[] kv = parse(line);
                    if (kv != null) merged.put(kv[0], kv[1]);
                }
            } catch (IOException ignored) {
                // 读不到就跳过，不影响启动
            }
        }
        return merged;
    }

    /** 解析一行 KEY=VALUE；非键值行或空值返回 null。支持引号、`export ` 前缀、`#` 注释。 */
    private String[] parse(String raw) {
        String line = raw.trim();
        if (line.isEmpty() || line.startsWith("#")) return null;
        if (line.startsWith("export ")) line = line.substring("export ".length()).trim();
        int eq = line.indexOf('=');
        if (eq <= 0) return null;
        String key = line.substring(0, eq).trim();
        String value = line.substring(eq + 1).trim();
        if (key.isEmpty() || value.isEmpty()) return null;
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                value = value.substring(1, value.length() - 1);
            }
        }
        return new String[]{key, value};
    }

    @Override
    public int getOrder() {
        // 早于 ConfigData 加载，保证 application.yml 里的 ${...} 能解析到 .env 的值
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }
}
