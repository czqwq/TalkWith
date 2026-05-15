package com.czqwq.talkwith.util;

import com.czqwq.talkwith.ClientProxy;
import com.czqwq.talkwith.Config;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ApiPinger {
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    public static void ping() {
        executor.submit(() -> {
            try {
                URL url = new URL(Config.baseUrl + "/v1/models");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "Bearer " + Config.apiKey);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                int status = conn.getResponseCode();
                if (status >= 200 && status < 300) {
                    ClientProxy.scheduleOnMainThread(() -> TextUtils.info("API available ✓"));
                } else {
                    InputStream err = conn.getErrorStream();
                    String msg = err != null ? new String(err.readAllBytes()) : "HTTP " + status;
                    ClientProxy.scheduleOnMainThread(() -> TextUtils.error("API unavailable: " + msg));
                }
            } catch (Exception e) {
                String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                ClientProxy.scheduleOnMainThread(() -> TextUtils.error("API unavailable: " + errMsg));
            }
        });
    }
}
