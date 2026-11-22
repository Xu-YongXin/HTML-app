package com.dsh.htmlviewer;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 极简本地 HTTP 服务：只监听 127.0.0.1，把最近一次「转换好」的 HTML 交给系统浏览器。
 * 这样浏览器拿到的就是一个真正的 http 页面，脚本、外链资源、localStorage 都和线上一致。
 */
public final class LocalWebServer {

    private static final int PORT_START = 8720;
    private static final int PORT_END = 8780;

    private static final LocalWebServer INSTANCE = new LocalWebServer();

    public static LocalWebServer get() {
        return INSTANCE;
    }

    private ServerSocket server;
    private int port = -1;
    private volatile byte[] body = new byte[0];

    private LocalWebServer() {
    }

    /** 更新内容并返回当前端口（必要时启动服务）。 */
    public synchronized int publish(String html) throws IOException {
        body = html == null ? new byte[0] : html.getBytes(StandardCharsets.UTF_8);
        if (server == null || server.isClosed()) {
            start();
        }
        return port;
    }

    private void start() throws IOException {
        IOException last = null;
        for (int p = PORT_START; p <= PORT_END; p++) {
            try {
                ServerSocket candidate = new ServerSocket();
                candidate.setReuseAddress(true);
                candidate.bind(new InetSocketAddress(
                        InetAddress.getByName("127.0.0.1"), p), 16);
                server = candidate;
                port = p;
                last = null;
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (server == null) {
            throw last != null ? last : new IOException("no free port");
        }
        Thread t = new Thread(this::acceptLoop, "local-http");
        t.setDaemon(true);
        t.start();
    }

    private void acceptLoop() {
        while (true) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                break; // server closed
            }
            Thread worker = new Thread(() -> handle(socket), "local-http-worker");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(8000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null) return;

            // 丢弃请求头，最多 64 行，避免恶意长请求。
            String header;
            int guard = 0;
            while ((header = readLine(in)) != null && !header.isEmpty() && guard++ < 64) {
                // ignore
            }

            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String path = parts.length > 1 ? parts[1] : "/";

            String status;
            byte[] payload;
            String contentType = "text/html; charset=utf-8";

            if (path.startsWith("/favicon")) {
                status = "404 Not Found";
                payload = new byte[0];
                contentType = "image/x-icon";
            } else if ("GET".equals(method) || "HEAD".equals(method)) {
                status = "200 OK";
                payload = body;
            } else {
                status = "405 Method Not Allowed";
                payload = new byte[0];
            }

            StringBuilder head = new StringBuilder()
                    .append("HTTP/1.1 ").append(status).append("\r\n")
                    .append("Content-Type: ").append(contentType).append("\r\n")
                    .append("Content-Length: ").append(payload.length).append("\r\n")
                    .append("Cache-Control: no-store, no-cache, must-revalidate\r\n")
                    .append("Referrer-Policy: no-referrer\r\n")
                    .append("Connection: close\r\n\r\n");

            OutputStream out = s.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (!"HEAD".equals(method) && payload.length > 0) {
                out.write(payload);
            }
            out.flush();
        } catch (IOException ignored) {
            // 浏览器提前断开等情况，忽略即可。
        }
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                byte[] bytes = buf.toByteArray();
                int len = bytes.length;
                if (len > 0 && bytes[len - 1] == '\r') len--;
                return new String(bytes, 0, len, StandardCharsets.ISO_8859_1);
            }
            buf.write(b);
            if (buf.size() > 8192) break;
        }
        return buf.size() == 0 ? null : buf.toString(StandardCharsets.ISO_8859_1.name());
    }
}
