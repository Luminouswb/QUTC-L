package com.qutc.launcher.core.version;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动参数条目。
 *
 * <p>官方 JSON 里 {@code arguments.game} 是混合数组：
 * 既有纯字符串，也有 {@code {"rules":[...],"value":"..."}} 形式的条件条目。
 * 这里用自定义反序列化统一成同一结构，调用方不必再区分。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonDeserialize(using = ArgumentEntry.Deserializer.class)
public final class ArgumentEntry {

    public List<Rule> rules;

    /** 展开后的参数片段；条件条目可能一次给出多个（例如窗口宽高）。 */
    public List<String> value;

    public static ArgumentEntry plain(String text) {
        ArgumentEntry entry = new ArgumentEntry();
        entry.value = List.of(text);
        return entry;
    }

    /** 本条规则是否适用于当前环境。 */
    public boolean applies(Rules.Context context) {
        return Rules.allows(rules, context);
    }

    /** 展开为实际参数字符串列表。 */
    public List<String> expand(Rules.Context context) {
        if (!applies(context) || value == null) {
            return List.of();
        }
        return value;
    }

    @Override
    public String toString() {
        return "Arg{" + (rules == null ? "always" : rules.size() + " rules") + ", " + value + '}';
    }

    static final class Deserializer extends JsonDeserializer<ArgumentEntry> {
        @Override
        public ArgumentEntry deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            ArgumentEntry entry = new ArgumentEntry();
            if (parser.currentToken() == JsonToken.VALUE_STRING) {
                entry.value = List.of(parser.getText());
                return entry;
            }

            JsonNode node = parser.getCodec().readTree(parser);
            if (node.isTextual()) {
                entry.value = List.of(node.asText());
                return entry;
            }

            JsonNode rulesNode = node.get("rules");
            if (rulesNode != null && rulesNode.isArray()) {
                entry.rules = context.readTreeAsValue(rulesNode,
                        context.getTypeFactory().constructCollectionType(List.class, Rule.class));
            }
            JsonNode valueNode = node.get("value");
            entry.value = readValue(valueNode);
            return entry;
        }

        private List<String> readValue(JsonNode valueNode) {
            if (valueNode == null || valueNode.isNull()) {
                return List.of();
            }
            if (valueNode.isArray()) {
                List<String> list = new ArrayList<>(valueNode.size());
                for (JsonNode item : valueNode) {
                    list.add(item.asText());
                }
                return list;
            }
            return List.of(valueNode.asText());
        }
    }
}
