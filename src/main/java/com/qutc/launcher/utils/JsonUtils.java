package com.qutc.launcher.utils;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * JSON 读写与损坏自动修复。
 *
 * <p>总纲要求：所有 JSON 配置格式化、可自动修复损坏文件。
 * 修复策略 —— 读取失败时把损坏文件重命名为 {@code *.corrupt-<时间戳>} 留档，
 * 然后返回调用方提供的默认值，由上层重新落盘，绝不因一个坏 JSON 让启动器起不来。
 */
public final class JsonUtils {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.INDENT_OUTPUT, true)
            .configure(JsonGenerator.Feature.ESCAPE_NON_ASCII, false)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    private JsonUtils() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static <T> T read(Path file, Class<T> type, T fallback) {
        if (file == null || !Files.isRegularFile(file)) {
            return fallback;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.isBlank()) {
                return fallback;
            }
            return MAPPER.readValue(text, type);
        } catch (IOException | RuntimeException e) {
            quarantine(file, e);
            return fallback;
        }
    }

    public static JsonNode readTree(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.isBlank()) {
                return null;
            }
            return MAPPER.readTree(text);
        } catch (IOException | RuntimeException e) {
            quarantine(file, e);
            return null;
        }
    }

    /** 宽松解析：字符串直接给 JSON 文本（来自网络响应）。 */
    public static JsonNode parse(String text) throws IOException {
        return MAPPER.readTree(text);
    }

    /** 从网络响应字节直接反序列化成模型。 */
    public static <T> T parse(byte[] body, Class<T> type) throws IOException {
        if (body == null || body.length == 0) {
            throw new IOException("响应内容为空");
        }
        return MAPPER.readValue(body, type);
    }

    public static void write(Path file, Object value) throws IOException {
        String text = MAPPER.writeValueAsString(value);
        FsUtils.writeAtomic(file, text);
    }

    public static ObjectNode newObject() {
        return MAPPER.createObjectNode();
    }

    private static void quarantine(Path file, Exception cause) {
        Log.warn("Json", "配置文件损坏，已留档并重建: " + file, cause);
        try {
            Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + STAMP.format(LocalDateTime.now()));
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Log.warn("Json", "损坏文件留档失败: " + file, e);
        }
    }
}
