package com.modelplant.theseus.llm;

import com.modelplant.theseus.config.TheseusServerConfig;

import java.net.http.HttpClient;
import java.time.Duration;

public final class HttpClients {
    private static volatile HttpClient CLIENT;

    private HttpClients() {
    }

    public static HttpClient get() {
        HttpClient local = CLIENT;
        if (local == null) {
            synchronized (HttpClients.class) {
                local = CLIENT;
                if (local == null) {
                    CLIENT = local = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(TheseusServerConfig.REQUEST_TIMEOUT_SECONDS.getAsInt()))
                            .version(HttpClient.Version.HTTP_1_1)
                            .build();
                }
            }
        }
        return local;
    }
}
